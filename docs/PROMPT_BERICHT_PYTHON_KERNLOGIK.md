# Prompt: High-End-Bericht Schritt 4a — Portable Python-Kernlogik (LAeq/Tier/§287 ZPO)

Zweiter Baustein von Schritt 4 (siehe `docs/DATENMAPPING_BERICHT_SCHRITT4.md`). Ziel: der
generische, nicht fallspezifische Rechenkern aus `compute_day()` (Referenz-Repo
`arthurschaab-bit/Baul-rm`, `pipeline/Verknuepfung/scripts/gesamtbericht_lib_v3.py`, Zeile
607-822) wird als eigenständiges, reines Python-Modul portiert — **ohne** Chaquopy, **ohne**
Android, **ohne** Dateizugriff. Test und Entwicklung laufen lokal mit `python -m pytest`, ganz
ohne Gradle/Emulator. Das macht diesen Schritt risikofrei ausführbar, bevor überhaupt eine
Android-Integration ansteht.

**Nicht Teil dieses Auftrags:** Chaquopy-Anbindung, PDF-Erzeugung, `dauerlaerm.csv`/
`relevante_ereignisse.csv`/Bautagebuch-Auswertung (bewusst außerhalb "V1 minimal", siehe
`DATENMAPPING_BERICHT_SCHRITT4.md` Abschnitt 1), Gebietseinstufungs-Generalisierung (eigener
Auftrag, `docs/PROMPT_BERICHT_GEBIETSEINSTUFUNG.md` — dieses Modul nimmt Richtwerte als Parameter
entgegen, entscheidet nicht selbst, welcher Gebietstyp welchen Wert hat).

---

## 0 · Arbeitsregeln

- Neuer Branch von `main`: `feature/bericht-python-kernlogik`. **Nie auf `main` pushen.**
- Ort im Repo: `app/src/main/python/laermbericht/` (Chaquopy-Konvention, `app/build.gradle.kts`
  verweist über die Chaquopy-Konfiguration bereits auf `src/main/python`) plus
  `app/src/test/python/` für die pytest-Tests — **prüfe die tatsächliche von Chaquopy erwartete
  Verzeichnisstruktur im Gradle-Setup, bevor du den Pfad festlegst**, dieser Prompt schlägt ihn
  nur vor.
- Reines Python, Zielversion 3.11 (wie in `app/build.gradle.kts`, `chaquopy { defaultConfig {
  version = "3.11" } }`). Nur `numpy`/`pandas` als Abhängigkeit — beide sind bereits als über
  Chaquopy für Android baubar verifiziert (Spike in PR #140-Kontext, `pandas` baut sauber für
  `arm64-v8a`/`x86_64`).
- Commit-Nachrichten deutsch. Kommentare/Docstrings deutsch (Konsistenz mit dem Rest des
  Projekts), Funktions-/Variablennamen können sich am Original orientieren (englisch/deutsch
  gemischt wie im Referenzskript) oder eingedeutscht werden — **eigene Entscheidung, aber
  konsistent innerhalb des Moduls durchhalten.**
- Jede Funktion, die eine Zahl oder Klassifikation aus dem Original übernimmt, referenziert die
  Zeilennummer im Referenzskript im Docstring — das macht spätere Abweichungsprüfungen möglich,
  ohne das Originalskript erneut komplett lesen zu müssen.
- **Nie behaupten, ein Ergebnis stimme mit dem Original überein, ohne es an einem konkreten
  Zahlenbeispiel nachgerechnet zu haben** (siehe Abschnitt 3, Vergleichstests).

---

## 1 · Kritischer Befund vor dem Start: Abtastrate weicht vom Original ab

Das Original geht von exakt **1 Sample/Sekunde** aus — `compute_day()` liest die Tages-CSV,
`reindex(pd.date_range(..., freq="1s"))` (Zeile 642f.) erzwingt ein strenges 1-Hz-Raster, fehlende
Sekunden werden zu `NaN`.

**Die tatsächliche PCE-323-Frame-Rate ist aber ~2 Hz, nicht 1 Hz** — dokumentiert in
`docs/PROTOKOLL_PCE-323.md` Zeile 137 (`| Frame-Rate? | ~2 Hz, bestätigt |`) und
`docs/IMPLEMENTIERUNGSPLAN_PCE-323_BLUETOOTH.md` Zeile 228. Ein 1:1-Port, der stillschweigend
1 Sample/Sekunde annimmt, würde auf echten Daten falsche Kennzahlen liefern (Perzentile, Anteile
"Zeit über Schwelle" usw. sind alle sample-count-gewichtet, nicht zeitgewichtet).

**Das musst du lösen, nicht ignorieren.** Zwei plausible Ansätze, keine Vorentscheidung:

- **(a) Auf 1 Hz vorverdichten**, bevor die Kernlogik läuft (z. B. pro Sekunde: letzter Wert,
  Mittelwert der Energie, oder Maximalwert — jede Wahl hat andere Auswirkungen auf Spitzenwerte).
  Erhält die Fundstellen-Kompatibilität zum Original am ehesten.
- **(b) Die Kernlogik selbst zeitgewichtet statt sample-count-gewichtet rechnen** (jedes Sample
  trägt sein tatsächliches Zeitintervall bis zum nächsten Sample in die Energie-Mittelung ein,
  nicht "1 Sample = 1 Sekunde"). Fachlich sauberer, aber eine echte Abweichung vom Original —
  Ergebnisse sind dann bei identischen Rohdaten nicht mehr zahlengleich mit dem Original, sondern
  nur noch methodisch gleichwertig.

**Frag den Owner, welcher Ansatz gewünscht ist**, bevor du `compute_day()` überträgst — das ist
eine Entscheidung mit echtem Effekt auf die im Bericht ausgewiesenen Zahlen, keine
Implementierungsdetail-Frage.

---

## 2 · Zu portierende Funktionen — mit Fundstelle

Alle Referenzen: `gesamtbericht_lib_v3.py`, Repo `arthurschaab-bit/Baul-rm`.

### 2.1 `get_tier(abd)` (Zeile 558-563)

```python
def get_tier(abd):
    if abd >= COVERAGE_VALID: return 'valid'
    if abd >= COVERAGE_WINDOW: return 'partial'
    return 'window'
```

`COVERAGE_VALID`/`COVERAGE_WINDOW` (Zeile 44f., aktuell `0.90`/`0.70`) werden zu Parametern —
Ursprung: `ReportConfigEntity.tierSchwelleVollmessungProzent`/`.tierSchwelleTeilerfassungProzent`
(bereits als Prozent 0-100 in Kotlin, hier auf 0.0-1.0 umrechnen oder das Modul konsequent auf
Prozent umstellen — deine Wahl, aber einheitlich).

### 2.2 Kernberechnung aus `compute_day()` (Zeile 607-822)

Nicht die ganze Funktion — nur der **datenzugriffsfreie** Teil (kein `glob`, kein `csv.DictReader`
auf `dauerlaerm.csv`/`relevante_ereignisse.csv`, kein `load_pos`/`load_bautagebuch`/`load_meteo`,
kein `tb.detect_spans` (Tiefbohrer-Erkennung, fallspezifisch, explizit außerhalb V1)). Konkret zu
portieren, alle Zeile 646-822:

- Energetische Größen: `energy=10**(dba/10)`, rollierendes 1-Minuten-LAeq (`laeq1m`), Tag-/Vor7-/
  Nach20-Fenster-Masken, `leq_of(mask)`.
- Abdeckung: `tag_sec`, `vor7_sec`, `na20_sec`, `abd_tag = tag_sec/TAG_REF_SEC`
  (`TAG_REF_SEC = 13*3600`, Zeile 46 — als Parameter oder Konstante, ist geräteunabhängig und
  nicht fallspezifisch, kann fest bleiben).
- Kennwerte: `laeq_tag`, `laeq_vor7`, `laeq_na20`, `l1_tag` (99. Perzentil), `hoechst` (Maximum),
  `lmax_b7`, `lmax_a20`, `pct_thr`/`n_above_sec`/`n_above_60_sec` (Zeitanteile über 55/60 dB —
  **die 55/60-Schwellen sind Gebietsrichtwerte, siehe Abschnitt 4, nicht hart codieren**),
  `mess_min`.
- `beurt(laf, laeq)` (Zeile 674-679): Taktmaximalpegel-Verfahren (5-Sekunden-Takte, TA-Lärm-
  Methodik) → Beurteilungspegel `lr_tag` und Impulszuschlag `ki_tag = lr_tag - laeq_tag`.
  **Achtung:** "5 Sekunden" bezieht sich auf 5 Werte bei 1 Hz — bei anderer Abtastrate (Abschnitt
  1) muss das zu 5 Sekunden Zeitspanne werden, nicht 5 Samples.
- Lauteste Stunde: rollierendes 60-Minuten-LAeq (`leq60`), Maximum, Zeitfenster-Text, Abdeckung
  des Fensters (`lh_val`, `lh_txt`, `lh_cov`).
- Konservative Hochrechnungen (Zeile 758-795):
  - "Konservative Volltag-Hochrechnung" bei Tier=`window`, Messende zwischen
    `KONSERVATIV_FENSTER_START`/`-ENDE` Uhr (jetzt `ReportConfigEntity`-Felder, siehe
    `docs/PROMPT_BERICHT_KONFIGURATION_UND_ERSTELLUNG.md` Abschnitt 2): überbrückt den Rest bis
    20 Uhr mit der Annahme `KONSERVATIV_ANNAHME_DB` — **das ist im Original `RW_TAG_WA`, also der
    Gebiets-Tagesrichtwert, nicht 55.0 als feste Zahl.** Nimm diesen Wert als Parameter
    (`konservativAnnahmeDb`) entgegen, den `PROMPT_BERICHT_GEBIETSEINSTUFUNG.md` später aus dem
    gewählten Gebietstyp befüllt.
  - "Hohe Abdeckung*" bei Tier=`partial` (≥70 % echt): überbrückt die GESAMTE Restzeit im
    07-20-Uhr-Fenster mit `STERN_ANNAHME_DB` (→ `ReportConfigEntity.schaetzpegelTeilerfassungDb`).
- `laeq_tag_str`/`laeq_supp` (`coverage_label_short`/`coverage_supplement`, Zeile 563-606): die
  Text-Kennzeichnung "► hohe Abdeckung" / "(~) Teilerfassung" / "(M) Messfenster" — reine
  Formatierungslogik, gehört noch zur Kernlogik (kein Fließtext mit Rechtsbezug, anders als die
  Seiten in `PROMPT_BERICHT_CHAQUOPY_INTEGRATION.md`).

### 2.3 Anti-Null-Regel statt `NaN`-Reindizierung

Das Original markiert fehlende Sekunden durch `NaN` nach `reindex()`. Unser Datenmodell markiert
ungültige/fehlende Werte stattdessen explizit über `MeasurementFlags.GAP`
(`data/SessionEntity.kt`) — die Zeile bleibt bestehen, nur ihr Wert darf nicht in die
energetische Mittelung einfließen. **Deine Eingabestruktur muss das abbilden**, z. B. ein Sample
als `(zeitstempel: int, pegel_db: float, ist_gap: bool)`-Tupel oder eine `NamedTuple`/
`dataclass` — bei `ist_gap=True` genauso behandeln wie das Original `NaN` behandelt (aus jeder
energetischen Mittelung ausschließen, aus keinem Zähler für "echt gemessene Sekunden").

---

## 3 · Schnittstelle (Vorschlag, kein Zwang)

```python
@dataclass
class Sample:
    zeitstempel_epoch_s: int
    pegel_db: float
    ist_gap: bool

@dataclass
class TagesKonfiguration:
    tier_schwelle_vollmessung: float       # 0.0-1.0 oder 0-100, konsistent waehlen
    tier_schwelle_teilerfassung: float
    schaetzpegel_teilerfassung_db: float
    schaetzpegel_messfenster_db: float     # aktuell KONSERVATIV_ANNAHME_DB = RW_TAG_WA im Original
    konservativ_fenster_start_stunde: int
    konservativ_fenster_ende_stunde: int
    geraete_unsicherheit_db: float
    richtwert_tag_db: float                # aus der Gebietseinstufung, s.o.
    richtwert_eingreif_tag_db: float        # fuer die 55/60-Schwellen-Auswertung, ebenfalls gebietsabhaengig

@dataclass
class TagesKennwerte:
    tier: str  # "valid" | "partial" | "window"
    abdeckung_tag: float
    laeq_tag: float
    # ... alle weiteren Werte aus Abschnitt 2.2

def berechne_tageskennwerte(
    samples: list[Sample],
    tag_datum: date,
    konfiguration: TagesKonfiguration,
) -> TagesKennwerte:
    ...
```

Das ist ein Vorschlag zur Orientierung, keine verbindliche Signatur — wichtiger ist, dass die
Funktion **rein** bleibt (keine Seiteneffekte, kein Dateizugriff, kein globaler Zustand wie im
Original `PIPELINE_CONFIG`/`ADRESSE`/`os.environ`), damit sie mit pytest ohne jede Android-/
Chaquopy-Umgebung testbar ist.

---

## 4 · Tests

**4.1 · Reine Funktionstests je portierter Funktion** — mindestens:

- `get_tier`: Grenzwerte exakt auf, knapp über, knapp unter beiden Schwellen.
- `berechne_tageskennwerte` mit synthetischen Samples:
  - Volle 13h Tagesabdeckung, konstanter Pegel → `laeq_tag` entspricht exakt dem konstanten
    Pegel, `tier == "valid"`.
  - Teilweise Abdeckung mit `ist_gap=True`-Lücken → Lücken fließen nicht in `laeq_tag` ein, aber
    `abdeckung_tag` sinkt korrekt.
  - Messende um 17 Uhr bei Tier `window` → konservative Hochrechnung aktiv, Ergebnis rechnerisch
    nachvollzogen (Energie-Mittelwert von Hand nachrechnen, nicht nur "läuft ohne Fehler").
  - Tier `partial` (≥70 % echt) → "hohe Abdeckung*"-Regel aktiv, nicht die Fenster-Regel.
  - Taktmaximalpegel (`beurt`-Äquivalent): synthetisches Signal mit bekannten Spitzen, Ergebnis
    von Hand nachgerechnet.

**4.2 · Vergleichstest gegen das Original, wo möglich.** Wenn du reproduzierbare Testdaten aus
`arthurschaab-bit/Baul-rm` findest (z. B. in `tests/` dort) oder synthetische Daten durch beide
Implementierungen (dein Modul und `compute_day()` aus dem Original, lokal mit Python ausgeführt)
laufen lässt: ein Diff der Kennzahlen ist der stärkste Beleg für einen korrekten Port. Wenn nicht
möglich (Original braucht Dateien/Umgebung, die nicht ohne Weiteres nachstellbar sind), sag das
im PR explizit statt es zu verschweigen.

**4.3 · Ausführung:** `python -m pytest app/src/test/python/` (oder wo du die Tests ablegst) —
dokumentiere den tatsächlich verwendeten Befehl im PR, inklusive Python-Version.

---

## 5 · Doku

- Docstring-Header des neuen Moduls: Verweis auf `docs/DATENMAPPING_BERICHT_SCHRITT4.md` und
  dieses Prompt-Dokument, kurze Erklärung der Abtastraten-Entscheidung aus Abschnitt 1.
- `docs/DATENMAPPING_BERICHT_SCHRITT4.md` um einen Absatz ergänzen, der die getroffene
  Abtastraten-Entscheidung (Abschnitt 1 hier) festhält — das ist eine Owner-Entscheidung mit
  Auswirkung auf die ausgewiesenen Kennzahlen und gehört ins Plandokument, nicht nur in den
  Python-Docstring.

---

## 6 · Definition of Done

1. `python -m pytest` grün — Ausgabe im PR. **Kein Gradle-Build nötig für diesen Schritt**, das
   Modul ist noch nicht an Chaquopy angeschlossen; wenn du es trotzdem probehalber einbindest,
   sag im PR, dass das ein Vorgriff war.
2. Abschnitt 1 (Abtastrate) mit dem Owner geklärt und die Antwort umgesetzt, nicht nur
   dokumentiert als offene Frage.
3. Jede Funktion aus Abschnitt 2 portiert und einzeln getestet (Abschnitt 4.1).
4. Abschnitt 5 (Doku) erledigt.
5. Draft-PR gegen `main`: was portiert wurde · wie die Abtastraten-Frage gelöst wurde · welche
   Vergleichstests gegen das Original möglich waren und welche nicht · was offen blieb.
