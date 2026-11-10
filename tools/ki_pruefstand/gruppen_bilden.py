"""Klanggruppen fuer alle Clips bilden, die noch kein Owner-Urteil haben (Hoerrunde 2).

Aufruf:
  python gruppen_bilden.py <voll-Ordner> <alt-Ordner> <Bautagebuch-Schallmessung> <Ausgabe.csv> [--k 20] [--grenze-db 75]

Mengen (jede wird fuer sich gruppiert, damit Hintergrundgeraeusche laute Gruppen nicht ueberdecken):
  AL  aeltere Aufnahmen (Juni-August), Pegel >= --grenze-db
  NQ  neue Aufnahmen (ab 14.09.), Pegel < --grenze-db  (>= 60 dB; die lauteren sind schon beurteilt)
  AQ  aeltere Aufnahmen, Pegel < --grenze-db
Ausgabe im Format der detail.csv (plus quelle_pfad, menge, rang), damit gruppen_seiten.py sie lesen kann.
"""
import argparse
import csv
import glob
import os
import sys
import zipfile
from datetime import datetime

import numpy as np
from sklearn.cluster import MiniBatchKMeans
from sklearn.preprocessing import StandardScaler

WOCHENTAGE = None


def fmt(x):
    return f"{x:.1f}".replace(".", ",")


def lade_emb(shard_dir, gebraucht, labels):
    """Embeddings und staerkste YAMNet-Klasse der gesuchten Clips aus allen Shards."""
    namen, emb, top = [], [], []
    for p in sorted(glob.glob(os.path.join(shard_dir, "*.npz"))):
        if ".tmp." in p:
            continue
        z = np.load(p, allow_pickle=True)
        n = [str(x) for x in z["namen"]]
        idx = [i for i, x in enumerate(n) if x in gebraucht]
        if not idx:
            continue
        namen += [n[i] for i in idx]
        emb.append(z["emb"][idx])
        top += [labels[int(np.argmax(z["score_max"][i]))] for i in idx]
    return namen, (np.concatenate(emb) if emb else np.zeros((0, 1024), dtype=np.float32)), top


def gruppiere(emb, k):
    z = StandardScaler().fit_transform(emb)
    km = MiniBatchKMeans(n_clusters=min(k, max(len(z) // 5, 2)), random_state=0, n_init=5, batch_size=4096).fit(z)
    abst = np.linalg.norm(z - km.cluster_centers_[km.labels_], axis=1)
    return km.labels_, abst


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("voll")
    ap.add_argument("alt")
    ap.add_argument("quelle_alt")
    ap.add_argument("ausgabe")
    ap.add_argument("--k", type=int, default=20)
    ap.add_argument("--grenze-db", type=float, default=75.0)
    ap.add_argument("--yamnet", default=os.path.join(os.path.dirname(__file__), "..", "..", "app", "src", "main", "assets", "yamnet.tflite"))
    a = ap.parse_args()
    labels = zipfile.ZipFile(a.yamnet).read("yamnet_label_list.txt").decode().splitlines()

    zeilen = []  # (menge, clip, zeit, pegel, app, spitze, top1, pfad)
    # --- neue Aufnahmen, leise (Cluster -1) ---
    neu = {}
    with open(os.path.join(a.voll, "detail.csv"), encoding="utf-8-sig", newline="") as f:
        for r in csv.DictReader(f, delimiter=";"):
            if r["cluster"] == "-1" and float(r["pegel_db"].replace(",", ".")) < a.grenze_db:
                neu[r["clip"]] = r
    namen, E, top = lade_emb(os.path.join(a.voll, "shards"), set(neu), labels)
    print(f"NQ: {len(namen)} Clips", flush=True)
    sets = {"NQ": (namen, E, top, {n: neu[n] for n in namen})}
    # --- aeltere Aufnahmen ---
    alt = {}
    with open(os.path.join(a.alt, "ereignisse.csv"), encoding="utf-8", newline="") as f:
        for r in csv.DictReader(f, delimiter=";"):
            alt[r["wav"][:-4]] = r
    namen_a, E_a, top_a = lade_emb(os.path.join(a.alt, "shards"), set(alt), labels)
    print(f"Alt gesamt: {len(namen_a)} Clips", flush=True)
    laut = [i for i, n in enumerate(namen_a) if float(alt[n]["pegel"]) >= a.grenze_db]
    leise = [i for i, n in enumerate(namen_a) if float(alt[n]["pegel"]) < a.grenze_db]
    for kz, ix in (("AL", laut), ("AQ", leise)):
        sets[kz] = ([namen_a[i] for i in ix], E_a[ix], [top_a[i] for i in ix], {namen_a[i]: alt[namen_a[i]] for i in ix})

    rang = 0
    out_rows, uebersicht = [], []
    for kz in ("AL", "NQ", "AQ"):
        namen, E, top, info = sets[kz]
        if len(namen) < 10:
            continue
        lab, abst = gruppiere(E, a.k)
        groessen = np.bincount(lab)
        ordnung = {int(c): i for i, c in enumerate(np.argsort(-groessen))}
        for i, n in enumerate(namen):
            r = info[n]
            c = int(lab[i])
            if kz == "NQ":
                zeit, pegel = r["zeit"], float(r["pegel_db"].replace(",", "."))
                app, spitze, pfad = r["app_baulaerm"], r["app_spitze"], ""
            else:
                zeit, pegel = r["zeit"], float(r["pegel"])
                app, spitze = "-", ""
                kenn = r["quelle"]
                pfad = os.path.join(a.quelle_alt, kenn[:-7]) if kenn.endswith("_ordner") else os.path.join(a.quelle_alt, kenn + ".zip")
            t = datetime.fromisoformat(zeit)
            out_rows.append([n, zeit, f"{t:%Y-%m-%d}", WOCHENTAGE or "", t.hour, fmt(pegel), app, spitze, top[i],
                             f"{kz}{ordnung[c]:02d}", f"{abst[i]:.4f}".replace(".", ","), pfad, kz, rang + ordnung[c]])
        for c in sorted(ordnung, key=ordnung.get):
            ix = np.where(lab == c)[0]
            uebersicht.append(f"{kz}{ordnung[c]:02d}: {len(ix)} Clips, haeufigste YAMNet-Klasse "
                              f"{max(set(top[i] for i in ix), key=[top[i] for i in ix].count)}")
        rang += len(ordnung)
        print(f"{kz}: {len(namen)} Clips in {len(ordnung)} Gruppen", flush=True)
    kopf = ["clip", "zeit", "tag", "wochentag", "stunde", "pegel_db", "app_baulaerm", "app_spitze", "yamnet_top1",
            "cluster", "cluster_abstand", "quelle_pfad", "menge", "rang"]
    with open(a.ausgabe, "w", encoding="utf-8-sig", newline="") as f:
        w = csv.writer(f, delimiter=";")
        w.writerow(kopf)
        w.writerows(out_rows)
    with open(os.path.splitext(a.ausgabe)[0] + "_uebersicht.txt", "w", encoding="utf-8") as f:
        f.write("\n".join(uebersicht))
    print(f"{len(out_rows)} Zeilen -> {a.ausgabe}")


if __name__ == "__main__":
    sys.exit(main())
