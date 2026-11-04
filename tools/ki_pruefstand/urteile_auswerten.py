"""Auswertung auf den Owner-Urteilen zu den Klanggruppen (Schritt D, zweiter Durchgang).

Aufruf:
  python urteile_auswerten.py <voll-Ordner> <gruppen_urteile.json> <labels.json> <Ergebnis.json>

Daten:
  Training  = Gruppenurteile auf alle Clips der Gruppe uebertragen (ohne Mischgruppen, Einzelurteile haben Vorrang),
              abzueglich der Testclips.
  Test      = direkt gehoerte Clips: die 43 frueher bestaetigten (labels.json, ohne UNKLAR) und die Einzelurteile
              der Mischgruppen/Ausnahmen. Sie sind unabhaengig von der Gruppenbildung.
  Zusaetzlich Kreuzvalidierung auf dem Trainingsteil, Gruppe = Tag. ACHTUNG: Die Labels der Trainingsdaten stammen
  aus Klangclustern der YAMNet-Embeddings; Embedding-Ansaetze koennen die Cluster daher fast zwangslaeufig
  wiederfinden. Diese Kreuzvalidierung ist KEIN unabhaengiger Beleg, nur der Test auf direkt gehoerten Clips ist es.
"""
import csv
import glob
import json
import os
import sys
from collections import Counter

import numpy as np
from sklearn.linear_model import LogisticRegression
from sklearn.model_selection import LeaveOneGroupOut
from sklearn.neighbors import KNeighborsClassifier
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler
from sklearn.tree import DecisionTreeClassifier

ORIG_ART = {"HYD_HAMMER": "HAEMMERND", "DRUCKLUFT": "HAEMMERND", "VERLADUNG": "SCHLAG"}
ART_KLASSEN = ["MOTOR", "HAEMMERND", "SCHLAG"]  # Klassen mit genug Clips fuer eine Auswertung


def zahl(s):
    return float(s.replace(",", "."))


def lade_shards(voll, gebraucht):
    namen, emb, feat, fnamen = [], [], [], None
    for p in sorted(glob.glob(os.path.join(voll, "shards", "*.npz"))):
        z = np.load(p, allow_pickle=True)
        n = [str(x) for x in z["namen"]]
        if not n:
            continue
        idx = [i for i, x in enumerate(n) if x in gebraucht]
        if not idx:
            continue
        namen += [n[i] for i in idx]
        emb.append(z["emb"][idx])
        feat.append(z["feat"][idx])
        fnamen = [str(x) for x in z["feat_namen"]]
    return namen, np.concatenate(emb), np.concatenate(feat), fnamen


def main(voll, urteile_pfad, labels_pfad, ausgabe):
    U = json.load(open(urteile_pfad, encoding="utf-8"))
    G, C = U["gruppen"], U["clips"]
    L = json.load(open(labels_pfad, encoding="utf-8"))
    rows = {}
    with open(os.path.join(voll, "detail.csv"), encoding="utf-8-sig", newline="") as f:
        for r in csv.DictReader(f, delimiter=";"):
            rows[r["clip"]] = r

    # --- Labels zusammenstellen --------------------------------------------------
    test = {}   # clip -> (baustelle ja/nein, art oder None)
    for cid, v in L.items():
        k = v["klasse"] or ("BAU_SONST" if v["notiz"] else "")
        if not k or k == "UNKLAR":
            continue
        test[cid] = ("nein" if k == "KEINE_BAU" else "ja", "KEINE_BAU" if k == "KEINE_BAU" else ORIG_ART.get(k))
    for cid, v in C.items():
        if v["art"] != "UNKLAR":
            test[cid] = (v["baustelle"], v["art"])
    train = {}
    for cid, r in rows.items():
        c = r["cluster"]
        if c in G and G[c]["art"] != "GEMISCHT" and cid not in test:
            train[cid] = (G[c]["baustelle"], G[c]["art"])
    gebraucht = set(train) | set(test)
    namen, E, F, fnamen = lade_shards(voll, gebraucht)
    pos = {n: i for i, n in enumerate(namen)}
    test = {k: v for k, v in test.items() if k in pos}
    train = {k: v for k, v in train.items() if k in pos}
    peg = {n: zahl(rows[n]["pegel_db"]) for n in namen if n in rows}
    # Pegel der frueher bestaetigten Clips steht ebenfalls in detail.csv (ab 60 dB)
    ok = [n for n in namen if n in rows]
    pos = {n: i for i, n in enumerate(namen)}

    def matrix(ids):
        ix = [pos[i] for i in ids]
        pegel = np.array([[peg[i]] for i in ids])
        return E[ix], np.hstack([F[ix], pegel]), pegel

    tr_ids = [i for i in train if i in peg]
    te_ids = [i for i in test if i in peg]
    Etr, Dtr, Ptr = matrix(tr_ids)
    Ete, Dte, Pte = matrix(te_ids)
    ytr = np.array([train[i][0] for i in tr_ids])
    yte = np.array([test[i][0] for i in te_ids])
    tage_tr = np.array([rows[i]["tag"] for i in tr_ids])
    out = dict(train_clips=len(tr_ids), train_ja=int((ytr == "ja").sum()), train_nein=int((ytr == "nein").sum()),
               test_clips=len(te_ids), test_ja=int((yte == "ja").sum()), test_nein=int((yte == "nein").sum()), ansaetze=[])

    def zaehle(y, p):
        return dict(tp=int(((y == "ja") & (p == "ja")).sum()), fn=int(((y == "ja") & (p == "nein")).sum()),
                    fp=int(((y == "nein") & (p == "ja")).sum()), tn=int(((y == "nein") & (p == "nein")).sum()))

    # --- App-Label (Ist-Zustand) auf Testclips und auf allen beurteilten Clips ----------
    def app_pred(ids, weit):
        ja = ("Baul", ) if False else None
        out_ = []
        for i in ids:
            a = rows[i]["app_baulaerm"]
            out_.append("ja" if (a == "ja" or (weit and a.startswith("m"))) else "nein")
        return np.array(out_)
    alle_ids = tr_ids + te_ids
    yalle = np.concatenate([ytr, yte])
    for name, weit in (("App streng ('Baulärm')", False), ("App weit (auch 'möglich')", True)):
        out["ansaetze"].append(dict(name=name, test=zaehle(yte, app_pred(te_ids, weit)), gesamt=zaehle(yalle, app_pred(alle_ids, weit))))

    # --- lernende Ansaetze ------------------------------------------------------------
    modelle = {
        "Nur Pegel, Baum Tiefe 2": ("pegel", lambda: DecisionTreeClassifier(max_depth=2, min_samples_leaf=5, class_weight="balanced", random_state=0)),
        "DSP + Pegel, Baum Tiefe 3": ("dsp", lambda: DecisionTreeClassifier(max_depth=3, min_samples_leaf=5, class_weight="balanced", random_state=0)),
        "YAMNet-Embedding + kNN (k=5)": ("emb", lambda: make_pipeline(StandardScaler(), KNeighborsClassifier(5))),
        "YAMNet-Embedding + LogReg": ("emb", lambda: make_pipeline(StandardScaler(), LogisticRegression(C=0.1, max_iter=3000, class_weight="balanced"))),
    }
    X = {"pegel": (Ptr, Pte), "dsp": (Dtr, Dte), "emb": (Etr, Ete)}
    for name, (feat_art, fn) in modelle.items():
        Xtr, Xte = X[feat_art]
        m = fn().fit(Xtr, ytr)
        p_te = m.predict(Xte)
        p_cv = np.empty(len(ytr), dtype=object)
        for a, b in LeaveOneGroupOut().split(Xtr, ytr, tage_tr):
            if len(set(ytr[a])) < 2:
                p_cv[b] = ytr[a][0]
                continue
            p_cv[b] = fn().fit(Xtr[a], ytr[a]).predict(Xtr[b])
        out["ansaetze"].append(dict(name=name, test=zaehle(yte, p_te), kreuzvalidierung_tag_train=zaehle(ytr, p_cv)))

    # --- Geraeuschart (3 grosse Klassen) -----------------------------------------------
    atr = [i for i in tr_ids if train[i][1] in ART_KLASSEN]
    ate = [i for i in te_ids if test[i][1] in ART_KLASSEN]
    out["art_train"] = dict(Counter(train[i][1] for i in atr))
    out["art_test"] = dict(Counter(test[i][1] for i in ate))
    if ate:
        Xa, _, _ = matrix(atr)
        Xt, _, _ = matrix(ate)
        ya = np.array([train[i][1] for i in atr])
        yt = np.array([test[i][1] for i in ate])
        art_res = []
        for name, fn in (("Art: Embedding + kNN (k=5)", lambda: make_pipeline(StandardScaler(), KNeighborsClassifier(5))),
                         ("Art: Embedding + LogReg", lambda: make_pipeline(StandardScaler(), LogisticRegression(C=0.1, max_iter=3000, class_weight="balanced")))):
            p = fn().fit(Xa, ya).predict(Xt)
            mat = [[int(((yt == a) & (p == b)).sum()) for b in ART_KLASSEN] for a in ART_KLASSEN]
            art_res.append(dict(name=name, treffer=int((yt == p).sum()), n=int(len(yt)), konfusion_zeilen_wahr=mat))
        out["art_ansaetze"] = art_res
    with open(ausgabe, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
    print(f"Training {out['train_clips']} (ja {out['train_ja']}, nein {out['train_nein']}), Test {out['test_clips']} (ja {out['test_ja']}, nein {out['test_nein']})")
    for a in out["ansaetze"]:
        print(a["name"], "| Test", a["test"], "| CV/Gesamt", a.get("kreuzvalidierung_tag_train") or a.get("gesamt"))
    for a in out.get("art_ansaetze", []):
        print(a["name"], f"{a['treffer']}/{a['n']}", a["konfusion_zeilen_wahr"])


if __name__ == "__main__":
    main(*sys.argv[1:5])
