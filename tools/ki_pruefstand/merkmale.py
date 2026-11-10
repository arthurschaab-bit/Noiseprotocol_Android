"""DSP-Merkmale (Schritt D2). Impulsmerkmale sind 1:1 aus Impulsanalyse.kt nachgebaut
(10-ms-RMS-Huellkurve, Crest, Exzess-Kurtosis, Autokorrelation 0,5..80 Hz, Peakschaerfe,
mittlerer Pegel), auf der UNnormalisierten WAV. Dazu Spektralmerkmale.
"""
import wave

import numpy as np

SR = 16000
FENSTER_MS = 10
RATE_BEREICH = (0.5, 80.0)


def lade_wav(pfad):
    with wave.open(pfad, "rb") as w:
        assert (w.getframerate(), w.getnchannels(), w.getsampwidth()) == (SR, 1, 2), pfad
        return np.frombuffer(w.readframes(w.getnframes()), dtype="<i2")


def huellkurve(samples):
    n = SR * FENSTER_MS // 1000
    k = len(samples) // n
    x = samples[: k * n].astype(np.float64).reshape(k, n) / 32768.0
    return np.sqrt((x * x).mean(axis=1))


def kurtosis(h):
    if len(h) < 4:
        return 0.0
    d = h - h.mean()
    var = (d * d).mean()
    if var <= 1e-12:
        return 0.0
    return float((d ** 4).mean() / (var * var) - 3.0)


def wiederholrate(h, hz=1000.0 / FENSTER_MS):
    """Gibt (Rate Hz, Peakschaerfe, Autokorrelationswert am Peak) zurueck."""
    if len(h) < 4:
        return 0.0, 0.0, 0.0
    min_lag = max(int(hz / RATE_BEREICH[1]), 1)
    max_lag = min(int(hz / RATE_BEREICH[0]), len(h) - 1)
    if min_lag > max_lag:
        return 0.0, 0.0, 0.0
    z = h - h.mean()
    norm = float((z * z).sum())
    if norm <= 1e-12:
        return 0.0, 0.0, 0.0
    werte = np.array([float((z[: len(z) - lag] * z[lag:]).sum()) / norm for lag in range(min_lag, max_lag + 1)])
    i = int(werte.argmax())
    best = float(werte[i])
    mean_abs = float(np.abs(werte).mean())
    schaerfe = best / mean_abs if mean_abs > 1e-6 else best
    return hz / (min_lag + i), schaerfe, best


def impuls_merkmale(samples):
    h = huellkurve(samples)
    rms = float(np.sqrt((h * h).mean()))
    rate, schaerfe, ac = wiederholrate(h)
    return {
        "crest": float(h.max() / rms) if rms > 0 else 0.0,
        "kurtosis": kurtosis(h),
        "rate_hz": rate,
        "peak_schaerfe": schaerfe,
        "autokorr": ac,
        "pegel_rel": float(h.mean()),
    }


def spektral_merkmale(samples):
    x = samples.astype(np.float64) / 32768.0
    n = 1024
    k = len(x) // n
    fenster = x[: k * n].reshape(k, n) * np.hanning(n)
    p = (np.abs(np.fft.rfft(fenster, axis=1)) ** 2).mean(axis=0) + 1e-18
    f = np.fft.rfftfreq(n, 1.0 / SR)
    gesamt = p.sum()
    return {
        "anteil_tief": float(p[f < 250].sum() / gesamt),
        "anteil_mittel": float(p[(f >= 250) & (f < 2000)].sum() / gesamt),
        "anteil_hoch": float(p[f >= 2000].sum() / gesamt),
        "schwerpunkt_hz": float((f * p).sum() / gesamt),
        # Tonalitaet: 1 - spektrale Flachheit (geom. / arithm. Mittel), 0 = Rauschen, 1 = reiner Ton
        "tonalitaet": float(1.0 - np.exp(np.log(p).mean()) / p.mean()),
    }


def alle_merkmale(samples):
    return {**impuls_merkmale(samples), **spektral_merkmale(samples)}
