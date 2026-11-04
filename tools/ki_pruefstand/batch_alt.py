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
    kennung, quelle_art, pfad, wavs, ausgabe, chunk = arg
    ziel = os.path.join(ausgabe, "shards", f"{chunk}.npz")
    if os.path.exists(ziel):
        return chunk, -1, 0.0
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
        return chunk, -2, time.perf_counter() - t0
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
    return chunk, len(namen), time.perf_counter() - t0


import ctypes
import json
import multiprocessing as mp


class _MEM(ctypes.Structure):
    _fields_ = [("dwLength", ctypes.c_ulong), ("dwMemoryLoad", ctypes.c_ulong), ("ullTotalPhys", ctypes.c_ulonglong),
                ("ullAvailPhys", ctypes.c_ulonglong), ("ullTotalPageFile", ctypes.c_ulonglong),
                ("ullAvailPageFile", ctypes.c_ulonglong), ("ullTotalVirtual", ctypes.c_ulonglong),
                ("ullAvailVirtual", ctypes.c_ulonglong), ("sullAvailExtendedVirtual", ctypes.c_ulonglong)]


def ram_frei_gb():
    """Freier Arbeitsspeicher in GB (Windows), sonst None."""
    try:
        m = _MEM()
        m.dwLength = ctypes.sizeof(_MEM)
        ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(m))
        return m.ullAvailPhys / 2 ** 30
    except Exception:
        return None


def _cpu_zeiten():
    try:
        class FT(ctypes.Structure):
            _fields_ = [("lo", ctypes.c_ulong), ("hi", ctypes.c_ulong)]
        i, k, u = FT(), FT(), FT()
        ctypes.windll.kernel32.GetSystemTimes(ctypes.byref(i), ctypes.byref(k), ctypes.byref(u))
        z = lambda f: (f.hi << 32) | f.lo
        return z(i), z(k), z(u)
    except Exception:
        return None


def cpu_auslastung(vorher, jetzt):
    """CPU-Auslastung 0..1 zwischen zwei _cpu_zeiten()-Messungen."""
    if not vorher or not jetzt:
        return None
    didle, dkern, duser = jetzt[0] - vorher[0], jetzt[1] - vorher[1], jetzt[2] - vorher[2]
    gesamt = dkern + duser  # Kernel-Zeit enthaelt die Leerlaufzeit
    return max(0.0, min(1.0, 1 - didle / gesamt)) if gesamt > 0 else None


def _worker(arg, yamnet, wartezeiten, q):
    try:
        ba._init(yamnet, wartezeiten)
        q.put(auftrag(arg))
    except BaseException as ex:  # noqa: BLE001 - der Controller entscheidet ueber Wiederholung
        q.put((arg[5], -3, 0.0, str(ex)))


def shard_ok(pfad, erwartet):
    """Shard vorhanden, lesbar und vollstaendig (analysiert + defekt == erwartete Clips)."""
    try:
        z = np.load(pfad, allow_pickle=True)
        n = len(z["namen"])
        return n + len(z["kaputt"]) == erwartet and z["emb"].shape == (n, 1024) and bool(np.isfinite(z["emb"]).all())
    except Exception:
        return False


def chunks_von(jobs, ausgabe, groesse):
    """Alle Chunks als (kennung, art, pfad, wavs, ausgabe, chunk-name); fertige Shards (auch alte Quellen-Shards) fehlen."""
    liste = []
    for kennung, art, pfad, wavs, _ in jobs:
        if not wavs or os.path.exists(os.path.join(ausgabe, "shards", f"{kennung}.npz")):
            continue  # ganze Quelle schon im alten Format erledigt
        for i in range(0, len(wavs), groesse):
            teil = wavs[i:i + groesse]
            name = f"{kennung}__{i // groesse:03d}"
            p = os.path.join(ausgabe, "shards", f"{name}.npz")
            if os.path.exists(p) and shard_ok(p, len(teil)):
                continue
            if os.path.exists(p):
                os.remove(p)  # unvollstaendig oder defekt -> neu rechnen
            liste.append((kennung, art, pfad, teil, ausgabe, name))
    return liste


def lauf(offen, a, ziel_status):
    """Controller: Chunks mit dynamischer Prozesszahl abarbeiten. Gibt (fertig_clips, fehlgeschlagen) zurueck."""
    ctx = mp.get_context("spawn")
    ausstehend = list(offen)
    laufend = {}  # name -> (proc, queue, job, start)
    versuche = {}
    fehler = []
    ziel = max(a.min_workers, min(a.workers, a.max_workers))
    fertig = 0
    gesamt_clips = sum(len(j[3]) for j in offen)
    letzte_pruefung = time.perf_counter()
    cpu_vorher = _cpu_zeiten()
    t0 = time.perf_counter()
    while ausstehend or laufend:
        for name in list(laufend):
            proc, q, job, start = laufend[name]
            res = None
            try:
                res = q.get_nowait()
            except Exception:
                pass
            if res is None and proc.is_alive() and (time.perf_counter() - start) > a.job_timeout_min * 60:
                ba.log(f"ZEITUEBERSCHREITUNG {name} nach {a.job_timeout_min} min, Prozess wird beendet")
                proc.terminate()
                proc.join(10)
                res = (name, -3, 0.0, "Zeitueberschreitung")
            elif res is None and not proc.is_alive():
                try:
                    res = q.get(timeout=2)
                except Exception:
                    res = (name, -3, 0.0, f"Prozess beendet ohne Ergebnis (Exit {proc.exitcode})")
            if res is None:
                continue
            proc.join(5)
            del laufend[name]
            n = res[1]
            if n >= -1:
                fertig += max(n, 0)
                vergangen = time.perf_counter() - t0
                rest = vergangen / fertig * (gesamt_clips - fertig) / 60 if fertig else float("nan")
                ba.log(f"fertig {name}: {n} Clips, {res[2]:.0f}s | {fertig}/{gesamt_clips}, Rest ca. {rest:.0f} min, Prozesse {len(laufend)}/{ziel}")
            else:
                versuche[name] = versuche.get(name, 0) + 1
                grund = res[3] if len(res) > 3 else "zurueckgestellt (Drive-Zugriff)"
                ba.log(f"FEHLER {name} (Versuch {versuche[name]}/{a.max_versuche}): {grund}")
                if versuche[name] < a.max_versuche:
                    ausstehend.append(job)
                else:
                    fehler.append(name)
        # Auslastung alle --pruef-minuten pruefen und Prozesszahl anpassen
        if time.perf_counter() - letzte_pruefung >= a.pruef_minuten * 60:
            jetzt = _cpu_zeiten()
            cpu = cpu_auslastung(cpu_vorher, jetzt)
            ram = ram_frei_gb()
            alt = ziel
            if ram is not None and ram < a.ram_min_frei_gb:
                ziel = max(a.min_workers, ziel - 2)
            elif cpu is not None and cpu > 0.93:
                ziel = max(a.min_workers, ziel - 1)
            elif ram is not None and cpu is not None and ram > a.ram_gut_frei_gb and cpu < 0.80:
                ziel = min(a.max_workers, ziel + (2 if cpu < 0.60 else 1))
            ba.log(f"Auslastungspruefung: CPU {('%.0f%%' % (cpu * 100)) if cpu is not None else '?'}, frei {('%.1f GB' % ram) if ram is not None else '?'} -> Prozesse {alt} -> {ziel}")
            letzte_pruefung, cpu_vorher = time.perf_counter(), jetzt
        while ausstehend and len(laufend) < ziel:
            job = ausstehend.pop(0)
            name = job[5]
            q = ctx.Queue()
            proc = ctx.Process(target=_worker, args=(job, os.path.abspath(a.yamnet), ba.WARTEZEITEN, q), daemon=True)
            proc.start()
            laufend[name] = (proc, q, job, time.perf_counter())
        try:
            with open(ziel_status, "w", encoding="utf-8") as f:
                json.dump({"zeit": time.strftime("%Y-%m-%d %H:%M:%S"), "ziel_prozesse": ziel, "laufend": len(laufend),
                           "ausstehend": len(ausstehend), "fertig_clips": fertig, "gesamt_clips": gesamt_clips, "fehler": fehler}, f)
        except OSError:
            pass
        time.sleep(2)
    return fertig, fehler


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
    ap.add_argument("--chunk", type=int, default=300, help="Clips je Arbeitspaket (kleinere Pakete = feinere Steuerung der Prozesszahl)")
    ap.add_argument("--min-workers", type=int, default=2)
    ap.add_argument("--max-workers", type=int, default=10)
    ap.add_argument("--pruef-minuten", type=float, default=10.0, help="Intervall der Auslastungspruefung")
    ap.add_argument("--ram-min-frei-gb", type=float, default=2.5, help="darunter wird die Prozesszahl um 2 gesenkt")
    ap.add_argument("--ram-gut-frei-gb", type=float, default=4.0, help="darueber (und CPU < 80 %) wird die Prozesszahl erhoeht")
    ap.add_argument("--job-timeout-min", type=float, default=90.0, help="haengende Pakete werden nach dieser Zeit beendet und wiederholt")
    ap.add_argument("--max-versuche", type=int, default=4, help="Versuche je Paket innerhalb eines Durchgangs")
    ap.add_argument("--max-durchgaenge", type=int, default=6, help="Durchgaenge mit Wartezeit dazwischen, bis aufgegeben wird")
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
    status_pfad = os.path.join(a.ausgabe, "status.json")
    marker = os.path.join(a.ausgabe, "FERTIG.txt")
    if os.path.exists(marker):
        os.remove(marker)
    erwartet = sum(len(j[3]) for j in jobs)
    durchgang = 0
    while True:
        offen = chunks_von(jobs, a.ausgabe, a.chunk)
        if a.max_zips:
            offen = offen[:a.max_zips]
        if not offen:
            break
        durchgang += 1
        print(f"Durchgang {durchgang}: offen {len(offen)} Pakete / {sum(len(j[3]) for j in offen)} Clips; "
              f"Start mit {a.workers} Prozessen (Bereich {a.min_workers}-{a.max_workers}, Pruefung alle {a.pruef_minuten:g} min)", flush=True)
        fertig, fehler = lauf(offen, a, status_pfad)
        rest = chunks_von(jobs, a.ausgabe, a.chunk)
        if a.max_zips or not rest:
            break
        if durchgang >= a.max_durchgaenge:
            print(f"ABGEBROCHEN: {len(rest)} Pakete nach {durchgang} Durchgaengen nicht fertig: {[r_[5] for r_ in rest][:10]}", flush=True)
            return 2
        warte = ba.WARTEZEITEN[min(durchgang - 1, len(ba.WARTEZEITEN) - 1)]
        print(f"Noch {len(rest)} Pakete offen ({len(fehler)} fehlgeschlagen). Neuer Durchgang in {warte // 60} min.", flush=True)
        time.sleep(warte)
    # Abschlusspruefung: alle erwarteten Clips muessen in gueltigen Shards stehen
    analysiert = 0
    for j in jobs:
        muster_ = [os.path.join(a.ausgabe, "shards", f"{glob.escape(j[0])}.npz"),
                   os.path.join(a.ausgabe, "shards", f"{glob.escape(j[0])}__*.npz")]
        for p_ in [x for m_ in muster_ for x in glob.glob(m_)]:
            if ".tmp." in p_:
                continue
            try:
                z = np.load(p_, allow_pickle=True)
                analysiert += len(z["namen"]) + len(z["kaputt"])
            except Exception:
                pass
    if not a.max_zips and analysiert < erwartet:
        print(f"ABGEBROCHEN: nur {analysiert} von {erwartet} Clips in gueltigen Shards", flush=True)
        return 2
    with open(marker, "w", encoding="utf-8") as f:
        f.write(f"fertig {time.strftime('%Y-%m-%d %H:%M:%S')}: {analysiert} von {erwartet} Clips in gueltigen Shards\n")
    print(f"FERTIG: {analysiert} von {erwartet} Clips in gueltigen Shards ({marker})", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
