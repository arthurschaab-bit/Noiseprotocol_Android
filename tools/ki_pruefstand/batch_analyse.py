"""Stapelanalyse: alle Ereignisse ab N dB durch DSP-Merkmale und YAMNet schicken (parallel).

Aufruf:
  python batch_analyse.py <Drive-Wurzel> <Ausgabeordner> [--min-db 60] [--workers 5]
                          [--yamnet <yamnet.tflite>] [--max-shards N] [--von JJJJ-MM-TT] [--bis JJJJ-MM-TT]

Parallelisierung: Ein Auftrag = eine Stunde eines Tages (ein Zip bzw. mehrere "(1)"-Zips). Die
Auftraege laufen in einem Prozess-Pool; jeder Prozess laedt das Modell einmal. BLAS/OpenMP sind je
Prozess auf einen Thread begrenzt, sonst konkurrieren die Prozesse um die Kerne.

Fortsetzbar: Pro Auftrag entsteht <Ausgabeordner>/shards/<Tag>_<HH>.npz. Vorhandene Shards werden
uebersprungen, ein abgebrochener Lauf kann mit demselben Aufruf fortgesetzt werden. Die WAVs im Drive
werden nur gelesen, nie veraendert; im Ausgabeordner landen nur Zahlen, keine Audiodaten.
"""
import argparse
import csv
import glob
import io
import os
import sys
import time
import wave
import zipfile
from multiprocessing import Pool

# vor dem NumPy-Import setzen, damit auch die Kindprozesse es erben
for _v in ("OMP_NUM_THREADS", "OPENBLAS_NUM_THREADS", "MKL_NUM_THREADS"):
    os.environ.setdefault(_v, "1")

import numpy as np  # noqa: E402

import merkmale  # noqa: E402
from auswahl import lade  # noqa: E402
from embeddings import FENSTER, HOP, normalisiere_peak  # noqa: E402
from yamnet_numpy import YamnetNumpy  # noqa: E402

_yam = None
_modell = None


def _init(modell):
    global _yam, _modell
    _modell = modell
    _yam = YamnetNumpy(modell)


def _samples(daten):
    with wave.open(io.BytesIO(daten), "rb") as w:
        if (w.getframerate(), w.getnchannels(), w.getsampwidth()) != (16000, 1, 2):
            return None
        return np.frombuffer(w.readframes(w.getnframes()), dtype="<i2")


def analysiere(samples):
    feat = merkmale.alle_merkmale(samples)
    x = normalisiere_peak(samples).astype(np.float32) / 32768.0
    if len(x) < FENSTER:
        x = np.pad(x, (0, FENSTER - len(x)))
    sc_f, e_f = [], []
    for start in range(0, len(x) - FENSTER + 1, HOP):
        sc, e = _yam.run(x[start:start + FENSTER])
        sc_f.append(sc)
        e_f.append(e)
    return feat, np.mean(e_f, axis=0), np.mean(sc_f, axis=0), np.max(sc_f, axis=0), len(sc_f)


def auftrag(arg):
    wurzel, ausgabe, tag, stunde, wavs = arg
    ziel = os.path.join(ausgabe, "shards", f"{tag}_{stunde}.npz")
    if os.path.exists(ziel):
        return tag, stunde, -1, 0, 0.0
    t0 = time.perf_counter()
    gesucht = set(wavs)
    zips = sorted(glob.glob(os.path.join(glob.escape(wurzel), tag, "WAV", f"audio_{tag}_{stunde}-00*.zip")))
    namen, feats, embs, smean, smax, nfenster = [], [], [], [], [], []
    fehlend, kaputt = [], []
    for zp in zips:
        try:
            z = zipfile.ZipFile(zp)
        except zipfile.BadZipFile:
            kaputt.append(os.path.basename(zp))
            continue
        with z:
            vorhanden = {os.path.basename(n): n for n in z.namelist()}
            for w in sorted(gesucht & vorhanden.keys()):
                try:
                    s = _samples(z.read(vorhanden[w]))
                    if s is None or len(s) < 1600:
                        kaputt.append(w)
                        gesucht.discard(w)
                        continue
                    f, e, m, x, n = analysiere(s)
                except Exception as ex:  # ein defekter Clip darf den Lauf nicht stoppen
                    kaputt.append(f"{w}: {ex}")
                    gesucht.discard(w)
                    continue
                gesucht.discard(w)
                namen.append(w[:-4])
                feats.append(f)
                embs.append(e)
                smean.append(m)
                smax.append(x)
                nfenster.append(n)
    fehlend = sorted(gesucht)
    fkeys = list(feats[0].keys()) if feats else list(merkmale.alle_merkmale(np.zeros(16000, dtype=np.int16) + 1).keys())
    os.makedirs(os.path.dirname(ziel), exist_ok=True)
    tmp = ziel + ".tmp.npz"
    np.savez(tmp, namen=np.array(namen, dtype=object), feat_namen=np.array(fkeys),
             feat=np.array([[f[k] for k in fkeys] for f in feats], dtype=np.float64).reshape(len(feats), len(fkeys)),
             emb=np.array(embs, dtype=np.float32).reshape(len(embs), 1024),
             score_mittel=np.array(smean, dtype=np.float32).reshape(len(smean), 521),
             score_max=np.array(smax, dtype=np.float32).reshape(len(smax), 521),
             fenster=np.array(nfenster), fehlend=np.array(fehlend, dtype=object), kaputt=np.array(kaputt, dtype=object))
    os.replace(tmp, ziel)
    return tag, stunde, len(namen), len(fehlend), time.perf_counter() - t0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("wurzel")
    ap.add_argument("ausgabe")
    ap.add_argument("--min-db", type=float, default=60.0)
    ap.add_argument("--workers", type=int, default=5)
    ap.add_argument("--yamnet", default=os.path.join(os.path.dirname(__file__), "..", "..", "app", "src", "main", "assets", "yamnet.tflite"))
    ap.add_argument("--max-shards", type=int, default=0)
    ap.add_argument("--von", default="")
    ap.add_argument("--bis", default="9999")
    a = ap.parse_args()

    ereignisse = [e for e in lade(a.wurzel) if e["pegel"] >= a.min_db
                  and a.von <= f"{e['zeit']:%Y-%m-%d}" <= a.bis]
    gruppen = {}
    for e in ereignisse:
        gruppen.setdefault((f"{e['zeit']:%Y-%m-%d}", f"{e['zeit']:%H}"), []).append(e["wav"])
    os.makedirs(os.path.join(a.ausgabe, "shards"), exist_ok=True)
    # Ereignisliste lokal ablegen, damit tabelle.py ohne Drive auskommt (nur Metadaten, kein Audio)
    with open(os.path.join(a.ausgabe, "ereignisse.csv"), "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f, delimiter=";")
        w.writerow(["zeit", "wav", "pegel", "label"])
        for e in ereignisse:
            w.writerow([e["zeit"].isoformat(), e["wav"], e["pegel"], e["label"]])
    auftraege = [(a.wurzel, a.ausgabe, t, h, w) for (t, h), w in sorted(gruppen.items())]
    offen = [x for x in auftraege if not os.path.exists(os.path.join(a.ausgabe, "shards", f"{x[2]}_{x[3]}.npz"))]
    if a.max_shards:
        offen = offen[:a.max_shards]
    clips_offen = sum(len(x[4]) for x in offen)
    print(f"{len(ereignisse)} Ereignisse >= {a.min_db:g} dB in {len(auftraege)} Stunden-Auftraegen; "
          f"offen: {len(offen)} Auftraege / {clips_offen} Clips; {a.workers} Prozesse", flush=True)

    t0 = time.perf_counter()
    fertig_clips = fehlend = 0
    with Pool(a.workers, initializer=_init, initargs=(os.path.abspath(a.yamnet),)) as pool:
        for i, (tag, h, n, f, sek) in enumerate(pool.imap_unordered(auftrag, offen), 1):
            fertig_clips += max(n, 0)
            fehlend += f
            vergangen = time.perf_counter() - t0
            rest = vergangen / fertig_clips * (clips_offen - fertig_clips) if fertig_clips else float("nan")
            print(f"[{i}/{len(offen)}] {tag} {h}h: {n} Clips, {f} nicht im Zip, {sek:.0f}s "
                  f"| gesamt {fertig_clips}/{clips_offen}, Rest ca. {rest / 60:.0f} min", flush=True)
    print(f"Fertig: {fertig_clips} Clips, {fehlend} Ereignisse ohne WAV im Zip, {(time.perf_counter() - t0) / 60:.1f} min")


if __name__ == "__main__":
    sys.exit(main())
