"""Minimaler TFLite-Interpreter in NumPy, nur fuer app/src/main/assets/yamnet.tflite.

Hintergrund: Auf dem Owner-PC blockiert die Windows-Anwendungssteuerung die nativen DLLs von
ai-edge-litert und TensorFlow, Docker war nicht startbar. Das Modell ist ein int8-Netz mit
Float-Frontend (STFT, Mel, Log). Hier werden genau die darin vorkommenden Operatoren nachgebaut:
Quantisierte Ops rechnen mit int32-Akkumulator und Float-Requantisierung (Abweichung gegenueber der
Festkomma-Requantisierung von TFLite: hoechstens +-1 Quantisierungsstufe, selten).

Eingang: 15600 float32-Samples in [-1, 1]. Ausgang: (Scores 521, Embedding 1024).
Das Embedding ist die Ausgabe der MEAN-Schicht (Eingang der letzten Fully-Connected-Schicht),
dequantisiert.
"""
import numpy as np
import tflite

_TYPEN = {
    tflite.TensorType.FLOAT32: np.float32,
    tflite.TensorType.INT8: np.int8,
    tflite.TensorType.INT32: np.int32,
    tflite.TensorType.UINT8: np.uint8,
    tflite.TensorType.COMPLEX64: np.complex64,
}
_OPS = {v: k for k, v in vars(tflite.BuiltinOperator).items() if not k.startswith("_")}


class Tensor:
    def __init__(self, g, buffers, i):
        t = g.Tensors(i)
        self.dtype = _TYPEN[t.Type()]
        self.shape = [int(s) for s in t.ShapeAsNumpy()] if t.ShapeLength() else []
        q = t.Quantization()
        self.scale = q.ScaleAsNumpy() if q is not None and q.ScaleLength() else None
        self.zp = q.ZeroPointAsNumpy() if q is not None and q.ZeroPointLength() else None
        self.qdim = q.QuantizedDimension() if q is not None else 0
        b = buffers(t.Buffer())
        self.const = None
        if b.DataLength():
            self.const = np.frombuffer(b.DataAsNumpy().tobytes(), dtype=self.dtype).reshape(self.shape).copy()


class YamnetNumpy:
    def __init__(self, pfad):
        buf = open(pfad, "rb").read()
        self.m = tflite.Model.GetRootAsModel(buf, 0)
        self.g = self.m.Subgraphs(0)
        self.t = [Tensor(self.g, self.m.Buffers, i) for i in range(self.g.TensorsLength())]
        self.ein = self.g.Inputs(0)
        self.aus = self.g.Outputs(0)
        self._wc = {}  # dequantisierte Gewichte/Biase, einmal berechnet

    # --- Hilfen -----------------------------------------------------------------
    def _deq(self, i, x):
        t = self.t[i]
        s = t.scale.astype(np.float32)
        z = t.zp.astype(np.float32)
        if len(s) > 1:
            shape = [1] * x.ndim
            shape[t.qdim] = -1
            s, z = s.reshape(shape), z.reshape(shape)
        return (x.astype(np.float32) - z) * s

    def _req(self, i, real):
        t = self.t[i]
        q = np.rint(real / t.scale[0]) + t.zp[0]
        return np.clip(q, -128, 127).astype(np.int8)

    @staticmethod
    def _act(real, fused):
        if fused == tflite.ActivationFunctionType.RELU6:
            return np.clip(real, 0, 6)
        if fused == tflite.ActivationFunctionType.RELU:
            return np.maximum(real, 0)
        return real

    @staticmethod
    def _pad_hw(h, w, k, s, same):
        if not same:
            return (0, 0), (0, 0), (h - k[0]) // s[0] + 1, (w - k[1]) // s[1] + 1
        oh, ow = -(-h // s[0]), -(-w // s[1])
        ph = max((oh - 1) * s[0] + k[0] - h, 0)
        pw = max((ow - 1) * s[1] + k[1] - w, 0)
        return (ph // 2, ph - ph // 2), (pw // 2, pw - pw // 2), oh, ow

    def _windows(self, x, k, s, same):
        n, h, w, c = x.shape
        (pt, pb), (pl, pr), oh, ow = self._pad_hw(h, w, k, s, same)
        xp = np.pad(x, ((0, 0), (pt, pb), (pl, pr), (0, 0)))  # x ist bereits um den Zero-Point verschoben
        win = np.empty((n, oh, ow, k[0], k[1], c), dtype=x.dtype)
        for a in range(k[0]):
            for b in range(k[1]):
                win[:, :, :, a, b, :] = xp[:, a:a + s[0] * oh:s[0], b:b + s[1] * ow:s[1], :][:, :oh, :ow, :]
        return win

    # --- Operatoren -------------------------------------------------------------
    def _conv(self, op, ins, out, depthwise):
        x_i, w_i, b_i = ins[0], ins[1], ins[2]
        x = self._deq(x_i, self.v[x_i])
        if w_i not in self._wc:
            self._wc[w_i] = (self._deq(w_i, self.t[w_i].const),
                             self.t[b_i].const.astype(np.float32) * (self.t[x_i].scale[0] * self.t[w_i].scale.astype(np.float32)))
        w, b = self._wc[w_i]
        if depthwise:
            o = tflite.DepthwiseConv2DOptions()
            o.Init(op.BuiltinOptions().Bytes, op.BuiltinOptions().Pos)
            kh, kw = w.shape[1], w.shape[2]
            s = (o.StrideH(), o.StrideW())
            win = self._windows(x, (kh, kw), s, o.Padding() == tflite.Padding.SAME)
            # Fenster: n,oh,ow,kh,kw,c ; Gewichte: 1,kh,kw,c (Multiplier 1)
            y = np.einsum("nhwabc,abc->nhwc", win, w[0])
        else:
            o = tflite.Conv2DOptions()
            o.Init(op.BuiltinOptions().Bytes, op.BuiltinOptions().Pos)
            kh, kw = w.shape[1], w.shape[2]
            s = (o.StrideH(), o.StrideW())
            if kh == 1 and kw == 1 and s == (1, 1):
                y = x @ w[:, 0, 0, :].T  # 1x1-Faltung = Matrixprodukt
            else:
                win = self._windows(x, (kh, kw), s, o.Padding() == tflite.Padding.SAME)
                y = np.einsum("nhwabc,oabc->nhwo", win, w, optimize=True)
        y = self._act(y + b, o.FusedActivationFunction())
        self.v[out] = self._req(out, y)

    def _fc(self, op, ins, out):
        o = tflite.FullyConnectedOptions()
        o.Init(op.BuiltinOptions().Bytes, op.BuiltinOptions().Pos)
        wt = self.t[ins[1]]
        if wt.dtype == np.float32:
            y = self.v_f(ins[0]) @ wt.const.T + self.t[ins[2]].const
            self.v[out] = self._act(y, o.FusedActivationFunction()).astype(np.float32)
            return
        x = self._deq(ins[0], self.v[ins[0]]).reshape(-1, wt.shape[1])
        if ins[1] not in self._wc:
            self._wc[ins[1]] = (self._deq(ins[1], wt.const),
                                self.t[ins[2]].const.astype(np.float32) * (self.t[ins[0]].scale[0] * wt.scale.astype(np.float32)))
        w, b = self._wc[ins[1]]
        y = self._act(x @ w.T + b, o.FusedActivationFunction())
        self.v[out] = self._req(out, y).reshape(self.t[out].shape)

    def v_f(self, i):
        return self.t[i].const if self.t[i].const is not None else self.v[i]

    # --- Ausfuehrung --------------------------------------------------------------
    def run(self, wave):
        self.v = {self.ein: np.asarray(wave, dtype=np.float32).reshape(self.t[self.ein].shape)}
        emb = None
        for k in range(self.g.OperatorsLength()):
            op = self.g.Operators(k)
            code = self.m.OperatorCodes(op.OpcodeIndex())
            name = _OPS[max(code.BuiltinCode(), code.DeprecatedBuiltinCode())]
            ins = [op.Inputs(j) for j in range(op.InputsLength())]
            outs = [op.Outputs(j) for j in range(op.OutputsLength())]
            o0 = outs[0]
            if name == "STRIDED_SLICE":
                self.v[o0] = self.v_f(ins[0]).reshape(self.t[o0].shape)  # nur Vollschnitt im Modell
            elif name == "RESHAPE":
                self.v[o0] = self.v_f(ins[0]).reshape(self.t[o0].shape)
            elif name == "GATHER":
                self.v[o0] = self.v_f(ins[0])[self.t[ins[1]].const.astype(np.int64)]
            elif name == "MUL":
                self.v[o0] = (self.v_f(ins[0]) * self.v_f(ins[1])).astype(np.float32)
            elif name == "PAD":
                p = self.t[ins[1]].const
                self.v[o0] = np.pad(self.v_f(ins[0]), [(int(a), int(b)) for a, b in p])
            elif name == "RFFT2D":
                self.v[o0] = np.fft.rfft(self.v_f(ins[0]), n=512, axis=-1).astype(np.complex64)
            elif name == "COMPLEX_ABS":
                self.v[o0] = np.abs(self.v_f(ins[0])).astype(np.float32)
            elif name == "LOG":
                self.v[o0] = np.log(self.v_f(ins[0])).astype(np.float32)
            elif name == "QUANTIZE":
                self.v[o0] = self._req(o0, self.v_f(ins[0]))
            elif name == "SPLIT":
                self.v[o0] = self.v_f(ins[1])
            elif name == "CONV_2D":
                self._conv(op, ins, o0, False)
            elif name == "DEPTHWISE_CONV_2D":
                self._conv(op, ins, o0, True)
            elif name == "FULLY_CONNECTED":
                self._fc(op, ins, o0)
            elif name == "MEAN":
                x = self._deq(ins[0], self.v[ins[0]])
                emb = x.mean(axis=(1, 2), keepdims=True)
                self.v[o0] = self._req(o0, emb)
                emb = self._deq(o0, self.v[o0]).reshape(-1)
            elif name == "LOGISTIC":
                x = self._deq(ins[0], self.v[ins[0]])
                self.v[o0] = self._req(o0, 1.0 / (1.0 + np.exp(-x)))
            elif name == "DEQUANTIZE":
                self.v[o0] = self._deq(ins[0], self.v[ins[0]]).astype(np.float32)
            else:
                raise NotImplementedError(name)
        return self.v[self.aus].reshape(-1), emb
