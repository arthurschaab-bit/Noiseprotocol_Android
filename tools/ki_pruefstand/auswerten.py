"""Schritt D: Ansaetze auf den bestaetigten Labels auswerten (Ist-Zustand, DSP, YAMNet-kNN/LogReg).

Aufruf:
  python auswerten.py <Kandidaten.csv> <labels.json> <WAV-Ordner> <embeddings.npz> <Ergebnis.json>

Kreuzvalidierung: Leave-one-group-out, Gruppe = Kalendertag (Clips desselben Tages landen nie
gleichzeitig in Training und Test). UNKLAR-Clips werden nicht bewertet, nur mitprotokolliert.
Klasse leer + Notiz (z. B. "Bagger") wird als BAU_SONST gewertet (Annahme, im Bericht genannt).
"""
import csv
import json
import sys
import time
from collections import Counter

import numpy as np
from sklearn.linear_model import LogisticRegression
from sklearn.model_selection import LeaveOneGroupOut
from sklearn.neighbors import KNeighborsClassifier
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler
from sklearn.tree import DecisionTreeClassifier

import merkmale

DREI = ["BAU_SONST", "KEINE_BAU", "VERLADUNG"]  # Klassen mit >= 5 Clips fuer die Geraete-Ebene


def lade(kand, labels, wavdir):
    with open(kand, encoding="utf-8") as f:
        rows = list(csv.DictReader(f, delimiter=";"))
    with open(labels, encoding="utf-8") as f:
        lab = json.load(f)
    out = []
    for r in rows:
        cid = r["wav"][:-4]
        l = lab.get(cid)
        if l is None:
            continue
        klasse = l["klasse"] or ("BAU_SONST" if l["notiz"] else "")
        if not klasse:
            continue
        samples = merkmale.lade_wav(f"{wavdir}/{r['wav']}")
        out.append(dict(id=cid, tag=r["zeit"][:10], pegel=float(r["pegel_db"]), app_label=r["label"],
                        klasse=klasse, rolle=r["rolle"], feat=merkmale.alle_merkmale(samples)))
    return out


def baustelle(k):
    return k != "KEINE_BAU"


def conf(y, p, klassen):
    m = np.zeros((len(klassen), len(klassen)), dtype=int)
    for a, b in zip(y, p):
        m[klassen.index(a), klassen.index(b)] += 1
    return m.tolist()


def cv(modell_fn, X, y, gruppen):
    pred = np.empty(len(y), dtype=object)
    for tr, te in LeaveOneGroupOut().split(X, y, gruppen):
        m = modell_fn()
        m.fit(X[tr], y[tr])
        pred[te] = m.predict(X[te])
    return pred


def bewerte(name, y_bin, p_bin, y_drei=None, p_drei=None, sekunden=None):
    y_bin, p_bin = np.asarray(y_bin), np.asarray(p_bin)
    gegen = y_bin == "nein"
    res = dict(
        name=name,
        n=int(len(y_bin)),
        treffer_ja_nein=int((y_bin == p_bin).sum()),
        konfusion_ja_nein=conf(list(y_bin), list(p_bin), ["ja", "nein"]),  # Zeile = wahr, Spalte = Vorhersage
        gegenbeispiele=int(gegen.sum()),
        gegenbeispiele_faelschlich_baustelle=int((p_bin[gegen] == "ja").sum()),
        baustellen_clips=int((~gegen).sum()),
        baustellen_clips_erkannt=int((p_bin[~gegen] == "ja").sum()),
    )
    if y_drei is not None:
        res["treffer_drei_klassen"] = int((np.asarray(y_drei) == np.asarray(p_drei)).sum())
        res["n_drei_klassen"] = int(len(y_drei))
        res["konfusion_drei_klassen"] = conf(list(y_drei), list(p_drei), DREI)
    if sekunden is not None:
        res["sekunden_pro_clip"] = float(sekunden)
    return res


def main(kand, labels, wavdir, emb_npz, ausgabe):
    t0 = time.perf_counter()
    daten = lade(kand, labels, wavdir)
    t_feat = (time.perf_counter() - t0) / max(len(daten), 1)
    klassen = Counter(d["klasse"] for d in daten)
    ergebnis = dict(klassenverteilung=dict(klassen), tage=len({d["tag"] for d in daten}), ansaetze=[])

    bew = [d for d in daten if d["klasse"] != "UNKLAR"]
    y_bin = np.array(["ja" if baustelle(d["klasse"]) else "nein" for d in bew])
    gruppen = np.array([d["tag"] for d in bew])
    drei_mask = np.array([d["klasse"] in DREI for d in bew])
    y_drei = np.array([d["klasse"] for d in bew], dtype=object)

    # 1. Ist-Zustand (App-Label aus dem Nachtrag)
    for name, ja in (("Ist-Zustand streng (nur 'Baulärm')", lambda s: s.startswith("Baulärm")),
                     ("Ist-Zustand weit (auch 'Möglicher Baulärm')", lambda s: s.startswith(("Baulärm", "Möglicher Baulärm")))):
        p = np.array(["ja" if ja(d["app_label"]) else "nein" for d in bew])
        ergebnis["ansaetze"].append(bewerte(name, y_bin, p))

    # 2. DSP-Merkmale mit kleinem Entscheidungsbaum
    namen_dsp = list(bew[0]["feat"].keys())
    X_dsp = np.array([[d["feat"][k] for k in namen_dsp] for d in bew])
    tree = lambda: DecisionTreeClassifier(max_depth=2, min_samples_leaf=3, random_state=0)
    p = cv(tree, X_dsp, y_bin, gruppen)
    ergebnis["ansaetze"].append(bewerte("DSP-Merkmale, Baum Tiefe 2", y_bin, p, sekunden=t_feat))
    X_pegel = np.array([[d["pegel"]] for d in bew])
    p = cv(tree, X_pegel, y_bin, gruppen)
    ergebnis["ansaetze"].append(bewerte("Nur Pegel (Kontrolle), Baum Tiefe 2", y_bin, p))
    full = DecisionTreeClassifier(max_depth=2, min_samples_leaf=3, random_state=0).fit(X_dsp, y_bin)
    ergebnis["dsp_baum_auf_allen_daten"] = dict(
        merkmale=namen_dsp, wichtigkeit={k: round(float(v), 3) for k, v in zip(namen_dsp, full.feature_importances_) if v > 0})

    # 3. YAMNet-Embeddings
    npz = np.load(emb_npz, allow_pickle=True)
    idx = {n: i for i, n in enumerate(npz["namen"])}
    E = np.array([npz["emb"][idx[d["id"]]] for d in bew])
    sek = float(np.mean(npz["sekunden"]))
    for name, fn in (("YAMNet-Embedding + kNN (k=3)", lambda: make_pipeline(StandardScaler(), KNeighborsClassifier(3))),
                     ("YAMNet-Embedding + LogReg", lambda: make_pipeline(StandardScaler(), LogisticRegression(C=0.1, max_iter=2000)))):
        pb = cv(fn, E, y_bin, gruppen)
        m = drei_mask
        pd_ = cv(fn, E[m], y_drei[m], gruppen[m])
        ergebnis["ansaetze"].append(bewerte(name, y_bin, pb, y_drei[m], pd_, sekunden=sek))

    # UNKLAR protokollieren (nicht bewertet)
    ergebnis["unklar"] = [dict(id=d["id"], pegel=d["pegel"], app_label=d["app_label"]) for d in daten if d["klasse"] == "UNKLAR"]
    with open(ausgabe, "w", encoding="utf-8") as f:
        json.dump(ergebnis, f, ensure_ascii=False, indent=1)
    for a in ergebnis["ansaetze"]:
        print(f"{a['name']:48} ja/nein {a['treffer_ja_nein']}/{a['n']}  "
              f"Fehlalarme {a['gegenbeispiele_faelschlich_baustelle']}/{a['gegenbeispiele']}  "
              f"Baustelle erkannt {a['baustellen_clips_erkannt']}/{a['baustellen_clips']}")


if __name__ == "__main__":
    main(*sys.argv[1:6])
