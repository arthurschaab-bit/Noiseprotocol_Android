"""Tagesprotokoll: je Tag und Geräuschklasse "wie oft, wie lange" als Text und CSV.

Aufruf:
  python tagesprotokoll.py <voll-Ordner> <gruppen_urteile.json> <zuordnung.json> <Ausgabeordner>
         [--drive <Laermprotokoll-Ordner>] [--luecke 60] [--clip-sekunden 5] [--min-db-klassifizieren 75]
         [--schwelle 0.6] [--pegel-abstand 8] [--pegel-minimum 60] [--max-erweiterung 30]

dB(A)-Verlauf: Mit --drive wird je Tag laermprotokoll_<Tag>.csv gelesen (Sekundenwerte des PCE-323, Spalte Pegel_dB,
nur Quelle PCE_323). Damit werden Episoden am Pegel entlang verlaengert (solange Pegel >= Klassenmedian - pegel-abstand,
mindestens pegel-minimum, hoechstens max-erweiterung Sekunden je Seite) und LAeq, Lmax sowie der Zeitanteil >= 75 dB
je Episode und je Tag berechnet. Ohne --drive entfaellt das.

Klassen sind die 13 Feinklassen aus Baul-rm (src/baul_rm/audio_model/schema.py), Mehrfachlabels werden als
"B04+B06" geführt. Quellen der Klasse je Clip:
  bestaetigt     = Clip wurde vom Owner direkt gehört (typische Clips der Gruppen, Einzelurteile)
  wahrscheinlich = Gruppenurteil des Owners auf alle Clips der Gruppe übertragen
  vorhergesagt   = Modell (logistische Regression auf YAMNet-Embeddings) für Clips der Mischgruppen
  zu_unsicher    = Modell-Wahrscheinlichkeit unter der Schwelle -> "nicht zuordenbar"
  leise          = unter --min-db-klassifizieren: nicht beurteilt (keine Labels vorhanden)

Episode: Folge von Clips derselben Klasse mit Abstand <= --luecke Sekunden. Aktive Zeit = Summe der Episodendauern
(erster Clip-Beginn bis letztes Clip-Ende). Das ist ein Mindestwert: die App zeichnet nur auf, wenn der Pegel die
Schwelle überschreitet, und jede Aufnahme ist rund 5 s lang.
"""
import argparse
import csv
import glob
import json
import math
import os
import time
from collections import Counter, defaultdict
from datetime import datetime, timedelta
from statistics import median

import numpy as np
from sklearn.linear_model import LogisticRegression
from sklearn.model_selection import LeaveOneGroupOut
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler

TITEL = {
    "B01": "Tief-/Drehbohrgerät", "B02": "Abbruch-/Schlagmaschine (hämmernd)", "B03": "Bohren/Fräsen/Schleifen",
    "B04": "Säge/Trennschleifer", "B05": "Schweres Baugerät (Bagger)", "B06": "Motor/Aggregat",
    "B07": "Baustellenfahrzeug", "B08": "Warnsignal", "B09": "Materialimpuls (Schlag, Klirren)",
    "U01": "Straßenverkehr", "U02": "Sprache/Menschen", "U03": "Natur/Wetter", "U04": "Sonstige Umgebung",
}
PHRASE = {
    "B01": "Geräusche des Tief-/Drehbohrgeräts", "B02": "hämmernde Geräusche (Abbruch-/Schlagmaschine)",
    "B03": "Bohr-, Fräs- und Schleifgeräusche", "B04": "Säge- und Trennschleifgeräusche",
    "B05": "Motorgeräusche schwerer Baugeräte (Bagger)", "B06": "Motor- und Aggregatgeräusche",
    "B07": "Baustellenfahrzeuge", "B08": "Warnsignale (z. B. Rückfahrwarner)",
    "B09": "Schläge und Klirren (Materialimpulse)", "U01": "Straßenverkehr", "U02": "Sprache",
    "U03": "Natur und Wetter", "U04": "sonstige Umgebungsgeräusche",
}
WOCHENTAG = ["Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag", "Sonntag"]


def zahl(s):
    return float(s.replace(",", "."))


def abbilden(art, quelle):
    """Owner-Urteil (Geräuschart + Quelle) -> Feinklassen-Schlüssel wie 'B05' oder 'B04+B06'; None = ausschließen."""
    q = (quelle or "").lower()
    if art == "MOTOR":
        if "bohr" in q:
            return "B01"
        if "bagger" in q:
            return "B05"
        return "B06"
    return {"HAEMMERND": "B02", "SCHLAG": "B09", "KLIRREN": "B09", "BOHREND": "B03", "SIGNAL": "B08",
            "MOTOR+SAEGEND_SCHLEIFEND": "B04+B06", "MOTOR+HAEMMERND": "B02+B06",
            "SIRENE": "U04", "MESSGERAET_HANDHABUNG": "U04", "KEINE_BAU": "U04"}.get(art)


def lade_labels(urteile, zuordnung):
    """clip -> (klasse, status)."""
    G, C = urteile["gruppen"], urteile["clips"]
    gehoert = {c for cs in zuordnung.values() for c in cs}
    return G, C, gehoert


def episoden(starts, dauer, luecke):
    """starts: sortierte Startzeiten (datetime). Gibt Liste (beginn, ende, anzahl) zurück."""
    out = []
    for t in starts:
        e = t + timedelta(seconds=dauer)
        if out and (t - out[-1][1]).total_seconds() <= luecke:
            out[-1] = (out[-1][0], max(out[-1][1], e), out[-1][2] + 1)
        else:
            out.append((t, e, 1))
    return out


def lade_serie(drive, tag, cache):
    """Sekundenverlauf des PCE-323 (dB(A)) als Array [86400], NaN = kein Messwert. Mit lokalem Cache und Wiederholungen."""
    pfad_c = os.path.join(cache, f"{tag}.npy")
    if os.path.exists(pfad_c):
        return np.load(pfad_c)
    quelle = os.path.join(drive, tag, "Schallmessung", f"laermprotokoll_{tag}.csv")
    text = None
    for versuch, warte in enumerate((0, 60, 300, 600, 3600)):
        time.sleep(warte)
        try:
            with open(quelle, encoding="utf-8-sig", newline="") as f:
                text = f.read()
            break
        except FileNotFoundError:
            return None
        except OSError as ex:
            print(f"Lesefehler {quelle}: {ex}; Versuch {versuch + 1}/5", flush=True)
    if text is None:
        return None
    s = np.full(86400, np.nan)
    for z in csv.DictReader(text.splitlines(), delimiter=";"):
        if z["Quelle"] == "PCE_323" and z["Pegel_dB"]:
            sek = int(z["Zeit"][11:13]) * 3600 + int(z["Zeit"][14:16]) * 60 + int(z["Zeit"][17:19])
            s[sek] = zahl(z["Pegel_dB"])
    os.makedirs(cache, exist_ok=True)
    np.save(pfad_c, s)
    return s


def sek_des_tages(dt):
    return dt.hour * 3600 + dt.minute * 60 + dt.second


def laeq(werte):
    w = werte[~np.isnan(werte)]
    return 10 * math.log10(float(np.mean(10 ** (w / 10)))) if len(w) else float("nan")


def erweitere(serie, b, e, schwelle, max_s):
    """Episode (sek_beginn, sek_ende) am Pegelverlauf entlang nach beiden Seiten verlaengern."""
    n = 0
    while n < max_s and b - 1 >= 0 and not np.isnan(serie[b - 1]) and serie[b - 1] >= schwelle:
        b -= 1
        n += 1
    n = 0
    while n < max_s and e + 1 < 86400 and not np.isnan(serie[e + 1]) and serie[e + 1] >= schwelle:
        e += 1
        n += 1
    return b, e


def minuten(sek):
    m = sek / 60
    return f"{m:.0f} min" if m >= 10 else f"{m:.1f} min".replace(".", ",")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("voll")
    ap.add_argument("urteile")
    ap.add_argument("zuordnung")
    ap.add_argument("ausgabe")
    ap.add_argument("--luecke", type=float, default=60)
    ap.add_argument("--clip-sekunden", type=float, default=5.0)
    ap.add_argument("--min-db-klassifizieren", type=float, default=75.0)
    ap.add_argument("--schwelle", type=float, default=0.6)
    ap.add_argument("--drive", default="")
    ap.add_argument("--blockluecke", type=float, default=1800, help="Episoden mit Abstand bis zu dieser Sekundenzahl bilden einen Zeitraum im Text")
    ap.add_argument("--pegel-abstand", type=float, default=8.0)
    ap.add_argument("--pegel-minimum", type=float, default=60.0)
    ap.add_argument("--max-erweiterung", type=int, default=30)
    a = ap.parse_args()
    os.makedirs(a.ausgabe, exist_ok=True)

    urteile = json.load(open(a.urteile, encoding="utf-8"))
    zuordnung = json.load(open(a.zuordnung, encoding="utf-8"))
    G, C, gehoert = lade_labels(urteile, zuordnung)

    rows = []
    with open(os.path.join(a.voll, "detail.csv"), encoding="utf-8-sig", newline="") as f:
        for r in csv.DictReader(f, delimiter=";"):
            rows.append(r)

    # --- Klassen je Clip -----------------------------------------------------------------
    klasse, status = {}, {}
    for r in rows:
        cid, c = r["clip"], r["cluster"]
        if cid in C:
            k = abbilden(C[cid]["art"], C[cid].get("quelle", ""))
            if k:
                klasse[cid], status[cid] = k, "bestaetigt"
        elif c in G and G[c]["art"] != "GEMISCHT":
            k = abbilden(G[c]["art"], G[c].get("quelle", ""))
            if k:
                klasse[cid] = k
                status[cid] = "bestaetigt" if cid in gehoert else "wahrscheinlich"

    # --- Modell für die übrigen Clips ab Schwelle ----------------------------------------
    ziel_ids = [r["clip"] for r in rows if zahl(r["pegel_db"]) >= a.min_db_klassifizieren]
    gebraucht = set(ziel_ids)
    namen, E = [], []
    for p in sorted(glob.glob(os.path.join(a.voll, "shards", "*.npz"))):
        z = np.load(p, allow_pickle=True)
        n = [str(x) for x in z["namen"]]
        idx = [i for i, x in enumerate(n) if x in gebraucht]
        if idx:
            namen += [n[i] for i in idx]
            E.append(z["emb"][idx])
    E = np.concatenate(E)
    pos = {n: i for i, n in enumerate(namen)}
    tag_von = {r["clip"]: r["tag"] for r in rows}
    tr = [i for i in namen if i in klasse]
    häufig = {k for k, v in Counter(klasse[i] for i in tr).items() if v >= 20}
    tr = [i for i in tr if klasse[i] in häufig]
    Xtr, ytr, gtr = E[[pos[i] for i in tr]], np.array([klasse[i] for i in tr]), np.array([tag_von[i] for i in tr])
    modell = lambda: make_pipeline(StandardScaler(), LogisticRegression(C=0.1, max_iter=3000, class_weight="balanced"))
    treffer = 0
    for x, y in LeaveOneGroupOut().split(Xtr, ytr, gtr):
        if len(set(ytr[x])) >= 2:
            treffer += int((modell().fit(Xtr[x], ytr[x]).predict(Xtr[y]) == ytr[y]).sum())
    print(f"Klassen im Modell: {sorted(häufig)}; Tag-Kreuzvalidierung (zirkulär, nur Orientierung): {treffer}/{len(ytr)}")
    m = modell().fit(Xtr, ytr)
    offen = [i for i in namen if i not in klasse]
    if offen:
        P = m.predict_proba(E[[pos[i] for i in offen]])
        for i, p in zip(offen, P):
            j = int(p.argmax())
            if p[j] >= a.schwelle:
                klasse[i], status[i] = str(m.classes_[j]), "vorhergesagt"
            else:
                status[i] = "zu_unsicher"

    # --- Tagesstatistik ---------------------------------------------------------------------
    tage = defaultdict(list)
    for r in rows:
        tage[r["tag"]].append(r)
    csv_zeilen, text = [], []
    for tag in sorted(tage):
        rs = sorted(tage[tag], key=lambda r: r["zeit"])
        d = datetime.fromisoformat(rs[0]["zeit"])
        erfasst = f"{rs[0]['zeit'][11:16]}–{rs[-1]['zeit'][11:16]}"
        serie = lade_serie(a.drive, tag, os.path.join(a.ausgabe, "pegel_cache")) if a.drive else None
        if serie is not None and np.all(np.isnan(serie)):
            serie = None
        tagespegel = ""
        if serie is not None:
            gueltig = ~np.isnan(serie)
            arbeit = serie[7 * 3600:18 * 3600]
            tagespegel = (f"Pegelverlauf PCE-323 (dB(A), Sekundenwerte): {str(round(float(gueltig.sum()) / 3600, 1)).replace('.', ',')} h gemessen, "
                          f"LAeq 07–18 Uhr {laeq(arbeit):.0f} dB, Lmax {np.nanmax(serie):.0f} dB, "
                          f"≥ 75 dB: {minuten(float((serie >= 75).sum()))}, ≥ 85 dB: {minuten(float((serie >= 85).sum()))}.")
        je = defaultdict(list)
        leise = unsicher = 0
        for r in rs:
            cid = r["clip"]
            if zahl(r["pegel_db"]) < a.min_db_klassifizieren:
                leise += 1
            elif cid in klasse:
                je[klasse[cid]].append(r)
            else:
                unsicher += 1
        zeilen = []
        for k in sorted(je, key=lambda k: (k.startswith("U"), -len(je[k]))):
            cl = je[k]
            ep = episoden([datetime.fromisoformat(r["zeit"]) for r in cl], a.clip_sekunden, a.luecke)
            pegel = [zahl(r["pegel_db"]) for r in cl]
            aktiv_clips = sum((e - b).total_seconds() for b, e, _ in ep)
            ep_laeq, ep_max = [], []
            if serie is not None:
                schw = max(a.pegel_minimum, median(pegel) - a.pegel_abstand)
                neu = []
                for b, e, n_ in ep:
                    b2, e2 = erweitere(serie, sek_des_tages(b), min(sek_des_tages(e), 86399), schw, a.max_erweiterung)
                    if neu and b2 <= neu[-1][1]:  # erweiterte Episoden ueberlappen -> zusammenfassen
                        neu[-1] = (neu[-1][0], max(neu[-1][1], e2), neu[-1][2] + n_)
                    else:
                        neu.append((b2, e2, n_))
                base = datetime.fromisoformat(rs[0]["zeit"]).replace(hour=0, minute=0, second=0, microsecond=0)
                ep = [(base + timedelta(seconds=b2), base + timedelta(seconds=e2 + 1), n_) for b2, e2, n_ in neu]
                for b2, e2, _ in neu:
                    seg = serie[b2:e2 + 1]
                    ep_laeq.append(laeq(seg))
                    ep_max.append(float(np.nanmax(seg)) if not np.all(np.isnan(seg)) else float("nan"))
            aktiv = sum((e - b).total_seconds() for b, e, _ in ep)
            laeq_ges = (10 * math.log10(sum(10 ** (v / 10) * (e - b).total_seconds() for v, (b, e, _) in zip(ep_laeq, ep)) / max(aktiv, 1))
                        if ep_laeq else float("nan"))
            pausen = [(ep[i + 1][0] - ep[i][1]).total_seconds() for i in range(len(ep) - 1)]
            st = Counter(status[r["clip"]] for r in cl)
            namen_k = " + ".join(f"{TITEL[x]}" for x in k.split("+"))
            fenster = ", ".join((f"{b:%H:%M}" if f"{b:%H:%M}" == f"{e:%H:%M}" else f"{b:%H:%M}–{e:%H:%M}") for b, e, _ in ep)
            bloecke = []  # Episoden mit Abstand <= --blocklücke zu einem Block zusammenfassen
            for b, e, n_ in ep:
                if bloecke and (b - bloecke[-1]["ende"]).total_seconds() <= a.blockluecke:
                    bl = bloecke[-1]
                    bl["pause"] = max(bl["pause"], (b - bl["ende"]).total_seconds())
                    bl["aktiv"] += (e - b).total_seconds()
                    bl["ende"] = e
                    bl["n"] += 1
                else:
                    bloecke.append({"beginn": b, "ende": e, "aktiv": (e - b).total_seconds(), "pause": 0.0, "n": 1})
            csv_zeilen.append([tag, WOCHENTAG[d.weekday()], k, namen_k, len(ep), len(bloecke), round(aktiv / 60, 1), round(aktiv_clips / 60, 1), len(cl),
                               f"{ep[0][0]:%H:%M}", f"{ep[-1][1]:%H:%M}", round(max((e - b).total_seconds() for b, e, _ in ep) / 60, 1),
                               round(median(pausen) / 60, 1) if pausen else "", round(max(pausen) / 60, 1) if pausen else "",
                               round(median(pegel), 1), round(max(pegel), 1),
                               round(laeq_ges, 1) if ep_laeq else "", round(max(ep_max), 1) if ep_max else "",
                               st.get("bestaetigt", 0), st.get("wahrscheinlich", 0), st.get("vorhergesagt", 0), fenster])

            def dichte(bl):
                spanne = max((bl["ende"] - bl["beginn"]).total_seconds(), 1.0)
                anteil = bl["aktiv"] / spanne
                if anteil >= 0.5:
                    return "nahezu durchgehend"
                if anteil >= 0.12:
                    return "ständig wiederkehrend"
                if anteil >= 0.03:
                    return "immer wieder"
                return "gelegentlich"

            phrase = " und ".join(PHRASE.get(x, TITEL[x]) for x in k.split("+"))
            lang = [bl for bl in bloecke if bl["n"] > 1 or (bl["ende"] - bl["beginn"]).total_seconds() >= 300]
            einzel = [bl for bl in bloecke if bl not in lang]
            teile = [f"{bl['beginn']:%H:%M} bis {bl['ende']:%H:%M} {dichte(bl)} {phrase} (ca. {minuten(bl['aktiv'])} aktiv)" for bl in lang]
            if einzel:
                zeiten = ", ".join(f"{bl['beginn']:%H:%M}" for bl in einzel[:6]) + (" …" if len(einzel) > 6 else "")
                teile.append(f"vereinzelt {phrase} ({len(einzel)} Mal, z. B. {zeiten})")
            satz = "- " + "; ".join(teile) + f". Zusammen ca. {minuten(aktiv if ep_laeq else aktiv_clips)}{'' if ep_laeq else ' (nur nach Aufnahmen, Mindestwert)'}."
            zeilen.append(satz)
        text += [f"## {WOCHENTAG[d.weekday()]}, {d:%d.%m.%Y}", "", f"Messzeitraum {erfasst} Uhr.", ""]
        text += zeilen or ["- Keine Aufnahme ab {:g} dB.".format(a.min_db_klassifizieren)]
        if unsicher:
            text.append(f"- Nicht zuordenbar: {unsicher} Aufnahmen ab {a.min_db_klassifizieren:g} dB.")
        text += [f"- Leisere Ereignisse (60–{a.min_db_klassifizieren:g} dB), nicht beurteilt: {leise} Aufnahmen.", ""]

    kopf = ["tag", "wochentag", "klasse", "klasse_text", "episoden", "bloecke", "aktive_minuten_pegelverlauf", "aktive_minuten_aufnahmen_min", "aufnahmen", "erste", "letzte",
            "laengste_episode_min", "pause_median_min", "pause_max_min", "clip_pegel_median_db", "clip_pegel_max_db",
            "verlauf_laeq_db", "verlauf_lmax_db", "n_bestaetigt", "n_wahrscheinlich", "n_vorhergesagt", "zeitfenster"]
    ziel_csv = os.path.join(a.ausgabe, "tagesprotokoll.csv")
    try:
        open(ziel_csv, "a").close()
    except PermissionError:  # z. B. in Excel geoeffnet
        ziel_csv = os.path.join(a.ausgabe, f"tagesprotokoll_{datetime.now():%H%M%S}.csv")
        print("tagesprotokoll.csv ist gesperrt, schreibe nach", ziel_csv)
    with open(ziel_csv, "w", encoding="utf-8-sig", newline="") as f:
        w = csv.writer(f, delimiter=";")
        w.writerow(kopf)
        for z in csv_zeilen:
            w.writerow([str(v).replace(".", ",") if isinstance(v, float) else v for v in z])
    hinweis = ["# Lärm-Tagesprotokoll (Prototyp)", "",
               f"Parameter: Aufnahmen derselben Klasse bis {a.luecke:g} s Abstand = eine Episode; Episoden bis {a.blockluecke:g} s Abstand = ein Block im Text; Aufnahmelänge {a.clip_sekunden:g} s. Pegelwerte stehen im Schallbericht und in der CSV.",
               "Die Zeiten sind Mindestwerte: Die App nimmt nur auf, wenn der Pegel die Schwelle überschreitet.",
               "Klassen: Feinklassen aus Baul-rm. Einordnung: Gruppenurteile des Owners auf alle Clips der Gruppe übertragen (wahrscheinlich),",
               "Mischgruppen per Modell (vorhergesagt). Aufnahmen unter 75 dB sind noch nicht beurteilt.", ""]
    with open(os.path.join(a.ausgabe, "tagesprotokoll.md"), "w", encoding="utf-8") as f:
        f.write("\n".join(hinweis + text))
    gesamt = Counter(status.get(r["clip"], "leise" if zahl(r["pegel_db"]) < a.min_db_klassifizieren else "zu_unsicher") for r in rows)
    print("Aufnahmen nach Quelle der Klasse:", dict(gesamt))
    print(f"{len(tage)} Tage, {len(csv_zeilen)} Tag-Klasse-Zeilen -> tagesprotokoll.csv / .md")


if __name__ == "__main__":
    main()
