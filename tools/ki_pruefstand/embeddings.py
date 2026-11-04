"""YAMNet-Embeddings und -Scores je Clip (Schritt D3, Datenbasis).

Aufruf: python embeddings.py <yamnet.tflite> <WAV-Ordner> <Ausgabe.npz>

Eingang wie die App: Peak-Normalisierung auf 0,95 NUR fuer die Inferenz (PeakNormalisierung.kt),
die WAV bleibt unveraendert. 0,975-s-Fenster (15600 Samples), Hop 0,48 s. Embedding = Ausgabe der
MEAN-Schicht (Eingang der letzten Fully-Connected-Schicht, 1024-dim) je Fenster, danach Mittelwert ueber die Fenster.
Zusaetzlich: mittlere und maximale Klassen-Scores (521) und die Rechenzeit je Clip.
"""
import glob
import os
import sys
import time
import wave

import numpy as np
from yamnet_numpy import YamnetNumpy

FENSTER = 15600
HOP = 7680


def lade_wav(pfad):
    with wave.open(pfad, "rb") as w:
        assert (w.getframerate(), w.getnchannels(), w.getsampwidth()) == (16000, 1, 2), pfad
        return np.frombuffer(w.readframes(w.getnframes()), dtype="<i2")


def normalisiere_peak(s, ziel=0.95):
    m = int(np.abs(s.astype(np.int32)).max()) if s.size else 0
    if m == 0:
        return s
    f = ziel * 32767 / m
    return np.clip(np.rint(s * f), -32768, 32767).astype(np.int16)


def main(modell, wavdir, ausgabe):
    yam = YamnetNumpy(modell)
    namen, embs, mittel, maxi, zeit = [], [], [], [], []
    for pfad in sorted(glob.glob(os.path.join(wavdir, "*.wav"))):
        s = normalisiere_peak(lade_wav(pfad))
        x = s.astype(np.float32) / 32768.0
        t0 = time.perf_counter()
        e_f, sc_f = [], []
        for start in range(0, len(x) - FENSTER + 1, HOP):
            sc, e = yam.run(x[start:start + FENSTER])
            sc_f.append(sc)
            e_f.append(e)
        zeit.append(time.perf_counter() - t0)
        namen.append(os.path.basename(pfad)[:-4])
        embs.append(np.mean(e_f, axis=0))
        mittel.append(np.mean(sc_f, axis=0))
        maxi.append(np.max(sc_f, axis=0))
    np.savez(ausgabe, namen=np.array(namen), emb=np.array(embs), score_mittel=np.array(mittel),
             score_max=np.array(maxi), sekunden=np.array(zeit))
    print(f"{len(namen)} Clips, {np.mean(zeit) * 1000:.0f} ms/Clip, Embedding {np.array(embs).shape}")


if __name__ == "__main__":
    main(*sys.argv[1:4])
