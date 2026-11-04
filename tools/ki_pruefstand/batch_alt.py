"""Stapelanalyse der aelteren Aufnahmen (Juni-August 2026) aus dem Bautagebuch-Ordner.

Aufruf:
  python batch_alt.py <Schallmessung-Ordner> <Ausgabeordner> [--min-db 60] [--workers 3]
                      [--yamnet <yamnet.tflite>] [--wartezeiten 60,300,600,3600] [--max-zips N]

Quelle: Laermprotokoll_TT.MM.2026.zip (und ggf. der entpackte Ordner Laermprotokoll_08.06.2026) mit
noise_<Epoch-ms>.wav. Die Aufnahmezeit steht im Dateinamen (Epoch in ms, UTC; Juni-August = MESZ = UTC+2).
Pegel: Die Aufnahmen tragen keinen Pegel. Er kommt aus den PCE-323-Exporten "JJJJ-MM-TT HH-MM-SS.csv" im selben
Ordner (Sekundenwerte dB(A), Spalten Sample;Date(mm-dd-yyyy);Time;Value(dBA)). Pegel einer Aufnahme = Maximum der
Sekundenwerte im Fenster [t-3 s, t+8 s]. Aufnahmen ohne PCE-Messwert oder unter --min-db werden nicht analysiert
(Anzahl steht in ausgelassen.csv). Nichts wird im Quellordner veraendert.

Ausgabe wie batch_analyse.py: <Ausgabeordner>/shards/<Zipname>.npz (Embeddings, Scores, DSP-Merkmale) und
ereignisse.csv (zeit;wav;pegel;label;quelle) sowie pegel/<Tag>.npy (Sekundenserie des Tages).
"""
import argparse
import csv
import glob
import os
import re
import sys
import time
import zipfile
from datetime import datetime, timedelta
from multiprocessing import Pool

for _v in ("OMP_NUM_THREADS", "OPENBLAS_NUM_THREADS", "MKL_NUM_THREADS"):
    os.environ.setdefault(_v, "1")

import numpy as np  # noqa: E402

import batch_analyse as ba  # noqa: E402
import merkmale  # noqa: E402

MESZ = timedelta(hours=2)
NAME = re.compile(r"noise_(\d{10,13})\.wav$", re.I)


def lese_text(pfad):
    return ba.mit_wiederholung(f"Lesen {os.path.basename(pfad)}", lambda: open(pfad, encoding="utf-8-sig", errors="replace").read())


def lade_serien(quelle, ausgabe):
    """tag (JJJJ-MM-TT) -> Array[86400] dB(A) aus allen PCE-Exporten des Tages (Maximum bei Ueberlappung)."""
    serien = {}
    for pfad in sorted(glob.glob(os.path.join(glob.escape(quelle), "20??-??-?? *.csv"))):
        text = lese_text(pfad)
        for zeile in text.splitlines():
            teile = zeile.split(",")
            if len(teile) < 4 or not teile[0].strip().isdigit():
                continue
            try:
                mm, dd, jj = teile[1].split("-")
                h, m, s = teile[2].split(":")
                wert = float(teile[3])
            except ValueError:
                continue
            tag = f"{jj}-{mm}-{dd}"
            arr = serien.setdefault(tag, np.full(86400, np.nan))
            i = int(h) * 3600 + int(m) * 60 + int(s)
            if np.isnan(arr[i]) or wert > arr[i]:
                arr[i] = wert
    os.makedirs(os.path.join(ausgabe, "pegel"), exist_ok=True)
    for tag, arr in serien.items():
        np.save(os.path.join(ausgabe, "pegel", f"{tag}.npy"), arr)
    return serien


def zeit_aus_name(name):
    m = NAME.search(name)
    if not m:
        return None
    ms = int(m.group(1))
    if ms < 10_000_000_000:  # Sekunden statt ms
        ms *= 1000
    return datetime(1970, 1, 1) + timedelta(milliseconds=ms) + MESZ


def pegel_am(serien, t):
    arr = serien.get(f"{t:%Y-%m-%d}")
    if arr is None:
        return None
    i = t.hour * 3600 + t.minute * 60 + t.second
    seg = arr[max(i - 3, 0):min(i + 9, 86400)]
    return None if np.all(np.isnan(seg)) else float(np.nanmax(seg))


def auftrag(arg):
    kennung, quelle_art, pfad, wavs, ausgabe = arg
    ziel = os.path.join(ausgabe, "shards", f"{kennung}.npz")
    if os.path.exists(ziel):
        return kennung, -1, 0.0
    t0 = time.perf_counter()
    namen, feats, embs, smean, smax, nf, kaputt = [], [], [], [], [], [], []
    try:
        if quelle_art == "zip":
            def oeffnen():
                z = zipfile.ZipFile(pfad)
                return z, {os.path.basename(n): n for n in z.namelist()}
            z, voll = ba.mit_wiederholung(f"Zip oeffnen {kennung}", oeffnen, (OSError, zipfile.BadZipFile))
        for w in wavs:
            try:
                if quelle_art == "zip":
                    daten = ba.mit_wiederholung(f"Lesen {w}", lambda w=w: z.read(voll[w]))
                else:
                    daten = ba.mit_wiederholung(f"Lesen {w}", lambda w=w: open(os.path.join(pfad, w), "rb").read())
                s = ba._samples(daten)
                if s is None or len(s) < 1600:
                    kaputt.append(w)
                    continue
                f, e, m, x, n = ba.analysiere(s)
            except ba.Zurueckgestellt:
                raise
            except Exception as ex:
                kaputt.append(f"{w}: {ex}")
                continue
            namen.append(w[:-4]); feats.append(f); embs.append(e); smean.append(m); smax.append(x); nf.append(n)
        if quelle_art == "zip":
            z.close()
    except ba.Zurueckgestellt as ex:
        ba.log(f"ZURUECKGESTELLT {kennung}: {ex}")
        return kennung, -2, time.perf_counter() - t0
    fkeys = list(feats[0].keys()) if feats else list(merkmale.alle_merkmale(np.zeros(16000, dtype=np.int16) + 1).keys())
    os.makedirs(os.path.dirname(ziel), exist_ok=True)
    tmp = f"{ziel}.{os.getpid()}.tmp.npz"
    np.savez(tmp, namen=np.array(namen, dtype=object), feat_namen=np.array(fkeys),
             feat=np.array([[f[k] for k in fkeys] for f in feats], dtype=np.float64).reshape(len(feats), len(fkeys)),
             emb=np.array(embs, dtype=np.float32).reshape(len(embs), 1024),
             score_mittel=np.array(smean, dtype=np.float32).reshape(len(smean), 521),
             score_max=np.array(smax, dtype=np.float32).reshape(len(smax), 521),
             fenster=np.array(nf), fehlend=np.array([], dtype=object), kaputt=np.array(kaputt, dtype=object))
    try:
        os.replace(tmp, ziel)
    except OSError:
        os.remove(tmp)
    return kennung, len(namen), time.perf_counter() - t0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("quelle")
    ap.add_argument("ausgabe")
    ap.add_argument("--min-db", type=float, default=60.0)
    ap.add_argument("--workers", type=int, default=3)
    ap.add_argument("--yamnet", default=os.path.join(os.path.dirname(__file__), "..", "..", "app", "src", "main", "assets", "yamnet.tflite"))
    ap.add_argument("--wartezeiten", default="60,300,600,3600")
    ap.add_argument("--max-zips", type=int, default=0)
    ap.add_argument("--nur-ereignisse", action="store_true")
    a = ap.parse_args()
    ba.WARTEZEITEN = tuple(int(x) for x in a.wartezeiten.split(","))
    os.makedirs(os.path.join(a.ausgabe, "shards"), exist_ok=True)

    serien = lade_serien(a.quelle, a.ausgabe)
    print(f"PCE-Serien fuer {len(serien)} Tage: {min(serien)} .. {max(serien)}", flush=True)

    # Inhaltsverzeichnis aller Quellen; Zips haben Vorrang vor dem losen Ordner (gleiche Dateinamen)
    quellen, gesehen = [], set()
    for zp in sorted(glob.glob(os.path.join(glob.escape(a.quelle), "Laermprotokoll_*.zip"))):
        try:
            namen = ba.mit_wiederholung(f"Zip lesen {os.path.basename(zp)}",
                                        lambda zp=zp: [os.path.basename(n) for n in zipfile.ZipFile(zp).namelist() if n.lower().endswith(".wav")],
                                        (OSError, zipfile.BadZipFile))
        except ba.Zurueckgestellt as ex:
            print("Zip nicht lesbar, uebersprungen:", ex, flush=True)
            continue
        quellen.append((os.path.splitext(os.path.basename(zp))[0], "zip", zp, [n for n in namen if n not in gesehen]))
        gesehen.update(namen)
    for ordner in sorted(glob.glob(os.path.join(glob.escape(a.quelle), "Laermprotokoll_*"))):
        if os.path.isdir(ordner):
            namen = [n for n in os.listdir(ordner) if n.lower().endswith(".wav") and n not in gesehen]
            if namen:
                quellen.append((os.path.basename(ordner) + "_ordner", "ordner", ordner, namen))
                gesehen.update(namen)

    ereignisse, ausgelassen = [], []
    jobs = []
    for kennung, art, pfad, namen in quellen:
        auswahl = []
        for n in namen:
            t = zeit_aus_name(n)
            if t is None:
                ausgelassen.append((kennung, n, "", "kein Zeitstempel im Namen"))
                continue
            p = pegel_am(serien, t)
            if p is None:
                ausgelassen.append((kennung, n, t.isoformat(), "kein PCE-Messwert"))
            elif p < a.min_db:
                ausgelassen.append((kennung, n, t.isoformat(), f"Pegel {p:.1f} dB unter {a.min_db:g}"))
            else:
                auswahl.append(n)
                ereignisse.append((t.isoformat(), n, p, "", kennung))
        jobs.append((kennung, art, pfad, sorted(auswahl), a.ausgabe))
    with open(os.path.join(a.ausgabe, "ereignisse.csv"), "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f, delimiter=";")
        w.writerow(["zeit", "wav", "pegel", "label", "quelle"])
        w.writerows(sorted(ereignisse))
    with open(os.path.join(a.ausgabe, "ausgelassen.csv"), "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f, delimiter=";")
        w.writerow(["quelle", "wav", "zeit", "grund"])
        w.writerows(ausgelassen)
    gesamt = sum(len(j[3]) for j in jobs)
    print(f"{sum(len(q[3]) for q in quellen)} WAVs gesamt; zu analysieren: {gesamt}; ausgelassen: {len(ausgelassen)} "
          f"({sum(1 for x in ausgelassen if 'PCE' in x[3])} ohne PCE-Messwert)", flush=True)
    if a.nur_ereignisse:
        return 0
    offen = [j for j in jobs if j[3] and not os.path.exists(os.path.join(a.ausgabe, "shards", f"{j[0]}.npz"))]
    if a.max_zips:
        offen = offen[:a.max_zips]
    clips_offen = sum(len(j[3]) for j in offen)
    print(f"offen: {len(offen)} Quellen / {clips_offen} Clips; {a.workers} Prozesse", flush=True)
    t0 = time.perf_counter()
    fertig = zurueck = 0
    with Pool(a.workers, initializer=ba._init, initargs=(os.path.abspath(a.yamnet), ba.WARTEZEITEN)) as pool:
        for i, (kennung, n, sek) in enumerate(pool.imap_unordered(auftrag, offen), 1):
            if n == -2:
                zurueck += 1
            fertig += max(n, 0)
            rest = (time.perf_counter() - t0) / fertig * (clips_offen - fertig) if fertig else float("nan")
            print(f"[{i}/{len(offen)}] {kennung}: {n} Clips, {sek:.0f}s | gesamt {fertig}/{clips_offen}, Rest ca. {rest / 60:.0f} min", flush=True)
    if zurueck:
        print(f"ACHTUNG: {zurueck} Quellen zurueckgestellt - denselben Aufruf spaeter erneut starten.", flush=True)
    print(f"Fertig: {fertig} Clips in {(time.perf_counter() - t0) / 60:.1f} min")
    return 0


if __name__ == "__main__":
    sys.exit(main())
