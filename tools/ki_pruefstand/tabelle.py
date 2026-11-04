"""Detailtabelle aus den Shards von batch_analyse.py: je Clip so viele Angaben wie moeglich.

Aufruf:
  python tabelle.py <Drive-Wurzel> <Ausgabeordner> <labels.json> [--cluster 30] [--min-db 60]

Schreibt in <Ausgabeordner>:
  detail.csv            eine Zeile je Clip (UTF-8 mit BOM, Trenner ';', Dezimalkomma - Excel-tauglich)
  cluster_uebersicht.md Gruppen nach Klang mit Kennzahlen und typischen Clips (zum Anhoeren)

Spalten (Auszug): Zeit, Tag, Wochentag, Stunde, Arbeitszeit, Pegel, App-Label (Baulaerm ja/moeglich/nein,
Spitzenklasse), DSP-Merkmale, die 5 staerksten YAMNet-Klassen (max. Score), Summen ueber Klassengruppen
(Werkzeug/Hammer, Motor/Fahrzeug, Zug, Sprache, Natur), Baustelle-Wahrscheinlichkeit und Klassen-
wahrscheinlichkeiten eines logistischen Modells, Abstand zum naechsten bestaetigten Clip, Cluster.

WICHTIG: Das Modell lernt nur aus den bestaetigten Labels (siehe labels.json). Bei wenigen Labels sind die
Wahrscheinlichkeiten eine Orientierung und KEINE belastbare Klassifikation. Die Spalte "trainiert" zeigt,
welche Clips selbst zum Lernen gedient haben (deren Werte sind zu optimistisch).
"""
import argparse
import csv
import glob
import json
import os
import re
import zipfile
from collections import Counter, defaultdict
from datetime import datetime

import numpy as np
from sklearn.cluster import MiniBatchKMeans
from sklearn.linear_model import LogisticRegression
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler

from auswahl import lade, arbeitszeit

WOCHENTAGE = ["Mo", "Di", "Mi", "Do", "Fr", "Sa", "So"]
# Klassengruppen (Namen aus der YAMNet-Labelliste); Summe der maximalen Scores, auf 1 begrenzt
GRUPPEN = {
    "g_werkzeug_hammer": ["Jackhammer", "Hammer", "Power tool", "Drill", "Tools", "Sawing", "Chainsaw", "Ratchet, pawl", "Sanding", "Filing (rasp)", "Mechanisms"],
    "g_motor_fahrzeug": ["Engine", "Heavy engine (low frequency)", "Medium engine (mid frequency)", "Engine knocking", "Idling", "Vehicle", "Truck", "Bus", "Car", "Motor vehicle (road)", "Accelerating, revving, vroom", "Reversing beeps"],
    "g_zug": ["Train", "Rail transport", "Railroad car, train wagon", "Clickety-clack", "Train wheels squealing", "Subway, metro, underground"],
    "g_schlag_knall": ["Thump, thud", "Bang", "Explosion", "Boom", "Slam", "Knock", "Tap", "Clang", "Clatter", "Smash, crash", "Crack"],
    "g_sprache": ["Speech", "Male speech, man speaking", "Female speech, woman speaking", "Conversation", "Shout", "Yell", "Laughter"],
    "g_natur_wetter": ["Wind", "Rain", "Thunderstorm", "Rustling leaves", "Wind noise (microphone)", "Bird", "Water"],
}


def lade_labels(pfad):
    with open(pfad, encoding="utf-8") as f:
        raw = json.load(f)
    out = {}
    for k, v in raw.items():
        kl = v["klasse"] or ("BAU_SONST" if v["notiz"] else "")
        if kl and kl != "UNKLAR":
            out[k] = kl
    return out


def lade_shards(ausgabe):
    teile = defaultdict(list)
    fehlend = kaputt = 0
    for p in sorted(glob.glob(os.path.join(ausgabe, "shards", "*.npz"))):
        z = np.load(p, allow_pickle=True)
        if len(z["namen"]) == 0:
            fehlend += len(z["fehlend"])
            kaputt += len(z["kaputt"])
            continue
        for k in ("namen", "feat", "emb", "score_mittel", "score_max", "fenster"):
            teile[k].append(z[k])
        teile["feat_namen"] = list(z["feat_namen"])
        fehlend += len(z["fehlend"])
        kaputt += len(z["kaputt"])
    return {k: (np.concatenate(v) if k != "feat_namen" else v) for k, v in teile.items()}, fehlend, kaputt


def app_info(label):
    ja = "ja" if label.startswith("Baulärm") else "möglich" if label.startswith("Möglicher") else "nein"
    m = re.search(r"Spitze: (.+)$", label)
    p = re.search(r"(\d+)% der Aufnahme", label)
    return ja, (m.group(1) if m else ""), (int(p.group(1)) if p else "")


def fmt(x):
    return f"{x:.4g}".replace(".", ",") if isinstance(x, (float, np.floating)) else x


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("wurzel")
    ap.add_argument("ausgabe")
    ap.add_argument("labels")
    ap.add_argument("--cluster", type=int, default=30)
    ap.add_argument("--min-db", type=float, default=60.0)
    ap.add_argument("--cluster-ab-db", type=float, default=75.0, help="nur Clips ab diesem Pegel werden gruppiert (leisere: Gruppe -1)")
    ap.add_argument("--yamnet", default=os.path.join(os.path.dirname(__file__), "..", "..", "app", "src", "main", "assets", "yamnet.tflite"))
    a = ap.parse_args()

    klassen = zipfile.ZipFile(a.yamnet).read("yamnet_label_list.txt").decode().splitlines()
    idx = {n: i for i, n in enumerate(klassen)}
    lokal = os.path.join(a.ausgabe, "ereignisse.csv")
    if os.path.exists(lokal):  # von batch_analyse.py geschrieben, kein Drive-Zugriff noetig
        with open(lokal, encoding="utf-8", newline="") as f:
            quelle = [dict(zeit=datetime.fromisoformat(r["zeit"]), wav=r["wav"], pegel=float(r["pegel"]), label=r["label"])
                      for r in csv.DictReader(f, delimiter=";")]
    else:
        quelle = lade(a.wurzel)
    ereignis = {e["wav"][:-4]: e for e in quelle if e["pegel"] >= a.min_db}
    S, fehlend, kaputt = lade_shards(a.ausgabe)
    namen = list(S["namen"])
    n = len(namen)
    print(f"{n} analysierte Clips ({fehlend} Ereignisse ohne WAV im Zip, {kaputt} unlesbar)")
    emb, smax, feat, fn = S["emb"], S["score_max"], S["feat"], S["feat_namen"]

    # --- Modell aus bestaetigten Labels --------------------------------------
    labels = lade_labels(a.labels)
    train = np.array([nm in labels for nm in namen])
    yk = np.array([labels.get(nm, "") for nm in namen])
    ybin = np.where(yk == "KEINE_BAU", "nein", "ja")
    Z = StandardScaler().fit(emb)
    Ze = Z.transform(emb)
    p_ja = np.full(n, np.nan)
    p_kl, kl_namen = None, []
    nn_dist = np.full(n, np.nan)
    nn_label = np.array([""] * n, dtype=object)
    if train.sum() >= 10 and len(set(ybin[train])) == 2:
        m = LogisticRegression(C=0.1, max_iter=3000).fit(Ze[train], ybin[train])
        p_ja = m.predict_proba(Ze)[:, list(m.classes_).index("ja")]
        starke = [k for k, c in Counter(yk[train]).items() if c >= 5]
        if len(starke) >= 2:
            sel = train & np.isin(yk, starke)
            m2 = LogisticRegression(C=0.1, max_iter=3000).fit(Ze[sel], yk[sel])
            p_kl, kl_namen = m2.predict_proba(Ze), list(m2.classes_)
        T = Ze[train]
        tn = [nm for nm in np.array(namen)[train]]
        for i in range(n):
            d = np.linalg.norm(T - Ze[i], axis=1)
            if train[i]:
                d[np.where(np.array(namen)[train] == namen[i])[0]] = np.inf
            j = int(d.argmin())
            nn_dist[i], nn_label[i] = d[j], labels[tn[j]]
    # --- Cluster --------------------------------------------------------------
    pegel_arr = np.array([ereignis[nm]["pegel"] if nm in ereignis else 0.0 for nm in namen])
    sel_c = pegel_arr >= a.cluster_ab_db
    km = MiniBatchKMeans(n_clusters=min(a.cluster, max(int(sel_c.sum()) // 5, 2)), random_state=0, n_init=5, batch_size=2048).fit(Ze[sel_c])
    cl = np.full(n, -1)
    cl[sel_c] = km.labels_
    abstand_zentrum = np.full(n, np.nan)
    abstand_zentrum[sel_c] = np.linalg.norm(Ze[sel_c] - km.cluster_centers_[km.labels_], axis=1)

    # --- Tabelle ----------------------------------------------------------------
    gr_idx = {g: [idx[k] for k in ks if k in idx] for g, ks in GRUPPEN.items()}
    kopf = (["clip", "zeit", "tag", "wochentag", "stunde", "arbeitszeit", "pegel_db", "app_baulaerm", "app_spitze", "app_anteil_prozent", "app_label"]
            + [f"dsp_{k}" for k in fn] + [f"yamnet_top{i}" for i in range(1, 6)] + [f"yamnet_top{i}_score" for i in range(1, 6)]
            + list(GRUPPEN) + ["p_baustelle", "trainiert", "bestaetigt"]
            + [f"p_{k}" for k in kl_namen] + ["naechster_bestaetigter_abstand", "naechster_bestaetigter_klasse", "cluster", "cluster_abstand"])
    with open(os.path.join(a.ausgabe, "detail.csv"), "w", encoding="utf-8-sig", newline="") as f:
        w = csv.writer(f, delimiter=";")
        w.writerow(kopf)
        for i, nm in enumerate(namen):
            e = ereignis.get(nm)
            if e is None:
                continue
            t = e["zeit"]
            ja, spitze, anteil = app_info(e["label"])
            top = np.argsort(-smax[i])[:5]
            gsum = [min(float(smax[i][ix].sum()), 1.0) for ix in gr_idx.values()]
            row = [nm, t.isoformat(), f"{t:%Y-%m-%d}", WOCHENTAGE[t.weekday()], t.hour, "ja" if arbeitszeit(e) else "nein",
                   e["pegel"], ja, spitze, anteil, e["label"]]
            row += list(feat[i]) + [klassen[j] for j in top] + [float(smax[i][j]) for j in top] + gsum
            row += [p_ja[i], "ja" if train[i] else "nein", yk[i]]
            row += list(p_kl[i]) if p_kl is not None else []
            row += [nn_dist[i], nn_label[i], int(cl[i]), "" if np.isnan(abstand_zentrum[i]) else float(abstand_zentrum[i])]
            w.writerow([fmt(v) if not isinstance(v, (int, str)) else v for v in row])

    # --- Cluster-Uebersicht (Markdown) -----------------------------------------
    zeilen = ["# Gruppen nach Klang (Cluster)", "",
              f"{int(sel_c.sum())} Clips ab {a.cluster_ab_db:g} dB (von {n} Clips ab {a.min_db:g} dB), {km.n_clusters} Gruppen. Leisere Clips haben in detail.csv die Gruppe -1. Reihenfolge: groesste Gruppe zuerst.",
              "`p_ja` = mittlere Baustelle-Wahrscheinlichkeit des Modells (nur Orientierung, siehe Hinweis in tabelle.py).", ""]
    for c in sorted(range(km.n_clusters), key=lambda c: -(cl == c).sum()):
        ix = np.where(cl == c)[0]
        if not len(ix):
            continue
        infos = [ereignis[namen[i]] for i in ix if namen[i] in ereignis]
        pegel = np.array([e["pegel"] for e in infos])
        app = Counter(app_info(e["label"])[0] for e in infos)
        spitzen = Counter(app_info(e["label"])[1] for e in infos if app_info(e["label"])[1]).most_common(3)
        std = Counter(e["zeit"].hour for e in infos)
        top_cls = np.argsort(-smax[ix].mean(axis=0))[:3]
        bestaetigt = Counter(str(yk[i]) for i in ix if yk[i])
        typisch = ix[np.argsort(abstand_zentrum[ix])[:5]]
        zeilen += [f"## Gruppe {c}: {len(ix)} Clips", "",
                   f"- Pegel: Median {np.median(pegel):.1f} dB (Spanne {pegel.min():.1f}–{pegel.max():.1f}); Arbeitszeit-Anteil {np.mean([arbeitszeit(e) for e in infos]) * 100:.0f} %",
                   f"- Haeufigste Stunden: {', '.join(f'{h}h ({k})' for h, k in std.most_common(3))}; Tage: {len({e['zeit'].date() for e in infos})}",
                   f"- App-Label: Baulaerm {app['ja']}, moeglich {app['möglich']}, nein {app['nein']}"
                   + (f"; Spitzen: {', '.join(f'{k} ({v})' for k, v in spitzen)}" if spitzen else ""),
                   f"- Staerkste YAMNet-Klassen (Mittel der Max-Scores): {', '.join(f'{klassen[j]} {smax[ix][:, j].mean():.2f}' for j in top_cls)}",
                   f"- Modell: mittlere Baustelle-Wahrscheinlichkeit p_ja = {np.nanmean(p_ja[ix]):.2f}" if not np.all(np.isnan(p_ja[ix])) else "- Modell: kein Training moeglich",
                   f"- Bestaetigte Clips in der Gruppe: {dict(bestaetigt) if bestaetigt else 'keine'}",
                   f"- Typische Clips (zum Anhoeren): {', '.join(namen[i] + '.wav' for i in typisch)}", ""]
    with open(os.path.join(a.ausgabe, "cluster_uebersicht.md"), "w", encoding="utf-8") as f:
        f.write("\n".join(zeilen))
    print("Geschrieben: detail.csv, cluster_uebersicht.md")


if __name__ == "__main__":
    main()
