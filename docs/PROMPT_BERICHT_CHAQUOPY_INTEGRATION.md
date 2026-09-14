# Prompt: High-End-Bericht Schritt 4c — `report_bridge.py` und Chaquopy-Integration

Vierter und letzter Baustein von Schritt 4. Setzt voraus, dass
`docs/PROMPT_BERICHT_PYTHON_KERNLOGIK.md` (portable Rechenlogik) und
`docs/PROMPT_BERICHT_GEBIETSEINSTUFUNG.md` (Gebietstypen-Tabelle) bereits umgesetzt und gemergt
sind — dieser Auftrag verdrahtet beides zu `report_bridge.py`, schließt an den JSON-Vertrag aus
`docs/PROMPT_BERICHT_KONFIGURATION_UND_ERSTELLUNG.md` an, baut die PDF-Ausgabe und verifiziert
den gesamten Pfad einmal wirklich auf einem Gerät/Emulator.

**Dies ist der einzige der vier Bericht-Aufträge, der ein Gerät oder einen Emulator braucht** —
Chaquopy führt echten nativen Code aus und läuft nicht unter Robolectric (JVM-only). Wenn du hier
ohne Emulator arbeitest: sag das im PR ausdrücklich, baue aber trotzdem alles, was ohne Ausführung
geht (Code, `connectedAndroidTest`-Testquelle), und markiere den unverifizierten Teil klar.

---

## 0 · Arbeitsregeln

- Neuer Branch von `main`: `feature/bericht-chaquopy-integration`. **Nie auf `main` pushen.**
- `./gradlew assembleDebug` muss grün sein (das schließt den Chaquopy-Build mit ein — pip-Install
  für beide ABIs). `./gradlew connectedAndroidTest` nur, wenn ein Gerät/Emulator verfügbar ist;
  wenn nicht, sag das explizit statt es zu verschweigen.
- Commit-Nachrichten deutsch.
- **Baue keine neue, eigene PDF-Architektur, wenn die des Originals wiederverwendbar ist** (siehe
  Abschnitt 2) — das Ziel ist ein möglichst treuer Port, keine Neuerfindung.

---

## 1 · Ist-Zustand — mit Fundstelle

### Der bestehende Vertrag, den du erfüllen musst

`app/src/main/java/com/example/lrmprotokoll/report/ChaquopyReportRunner.kt` (bereits gemergt,
Schritt 1):

```kotlin
suspend fun erzeugeBericht(parameterJson: String): Ergebnis = withContext(Dispatchers.IO) {
    try {
        val modul = Python.getInstance().getModule("report_bridge")
        val pdfPfad = modul.callAttr("generate_report", parameterJson).toString()
        Ergebnis.Erfolg(pdfPfad)
    } catch (e: PyException) {
        Ergebnis.Fehler("Python-Fehler bei der Berichtserzeugung: ${e.message}", e)
    } ...
}
```

`report_bridge.py` muss also eine Modulfunktion `generate_report(parameter_json: str) -> str`
bereitstellen, die **synchron** den absoluten PDF-Pfad zurückgibt oder eine Python-Exception
wirft (kommt als `PyException` mit `.message` bei Kotlin an — **formuliere Fehlermeldungen so,
dass `e.message` für den Nutzer verständlich ist**, nicht ein rohes Traceback).

Der KDoc dort (Zeile 50-54) nennt bereits die Grundidee: *"DB-Pfad bzw. exportierte CSV-Pfade …
strukturiert übergeben statt als CLI-Argumente"* — **das JSON trägt kleine, strukturierte Daten;
große Massendaten (Rohwerte) gehören nicht direkt ins JSON.** Siehe Abschnitt 2, das ist keine
Nebensache.

### Was aus den vorherigen Schritten bereits existiert

- Portable Rechenlogik (`docs/PROMPT_BERICHT_PYTHON_KERNLOGIK.md`): eine Funktion, die aus
  Samples + Konfiguration Tageskennwerte berechnet.
- Gebietstypen-Tabelle (`docs/PROMPT_BERICHT_GEBIETSEINSTUFUNG.md`): Richtwerte und
  (mindestens teilweise) Fließtext je Gebietstyp.
- JSON-Erzeugung auf Kotlin-Seite (`docs/PROMPT_BERICHT_KONFIGURATION_UND_ERSTELLUNG.md`
  Abschnitt 3.5) — **prüfe das dort tatsächlich gebaute JSON-Schema am Code**, dieser Prompt kann
  es nicht vorwegnehmen, weil es von den Entscheidungen in jenem Auftrag abhängt (Zeitraumauswahl,
  Stammdaten-Zuordnung je Tag, Override-Flag).

### PDF-Erzeugung im Original — kein ReportLab

`gesamtbericht_lib_v3.py` erzeugt die PDF **ausschließlich über Matplotlib**
(`matplotlib.backends.backend_pdf.PdfPages`, `matplotlib.use("Agg")`) — jede Seite ist eine
`Figure`, Text wird über `fig.text(...)` platziert, keine einzige Zeile nutzt `reportlab`. Die
`chaquopy { pip { install("reportlab") } }`-Abhängigkeit in `app/build.gradle.kts` wurde in
Schritt 1 auf Verdacht (aus der ursprünglichen, noch ungeprüften Auftragsbeschreibung) ergänzt,
**bevor** das tatsächliche Referenzskript bekannt war. **Prüfe, ob `reportlab` für irgendetwas in
diesem Auftrag noch gebraucht wird — wenn nicht, entferne es aus der Chaquopy-Konfiguration**
(kleinere APK, ein Chaquopy-Paket weniger, das gebaut/gewartet werden muss). Matplotlib ist
dagegen bereits als Chaquopy-Abhängigkeit vorhanden und funktionsfähig verifiziert.

---

## 2 · Datenübergabe-Architektur — Entscheidung vor dem Bauen

Ein typischer Fall im Original umfasst ~20 Messtage. Bei ~2 Hz PCE-323-Frame-Rate (siehe
`docs/PROMPT_BERICHT_PYTHON_KERNLOGIK.md` Abschnitt 1) über 13 Stunden Tageszeit sind das
größenordnungsmäßig 90.000+ Samples **pro Tag**. Diese Rohwerte als Array direkt ins
`parameterJson` zu packen (ein einziger String-Parameter über die Chaquopy/JNI-Grenze) ist
sowohl unhandlich als auch möglicherweise ineffizient.

**Baue stattdessen einen Datei-Handoff, wie es der KDoc in `ChaquopyReportRunner.kt` bereits
andeutet:**

1. Kotlin exportiert die relevanten `MeasurementEntity`-Zeilen je ausgewähltem Kalendertag in eine
   Datei (Format deine Wahl — CSV analog dem Originalformat ist naheliegend, weil
   `docs/PROMPT_BERICHT_PYTHON_KERNLOGIK.md` dann leicht mit dem `Sample`-Typ darauf aufbauen
   kann) im App-privaten Speicher (`context.filesDir` oder `cacheDir` — **nicht** extern, das sind
   personenbezogene/standortbezogene Rohdaten).
2. `parameterJson` trägt nur: die Liste dieser Dateipfade (ein Pfad je Tag oder ein Pfad für den
   gesamten Zeitraum, deine Wahl), die `ReportConfigEntity`-Werte, die gewählten Stammdaten je
   Tag, das Override-Flag, den Ausgabe-Zielpfad.
3. `report_bridge.py` liest die Dateien selbst ein und ruft die Kernlogik-Funktion auf.

**Räume die Exportdateien nach erfolgreicher (oder fehlgeschlagener) Berichtserzeugung auf** —
das sind temporäre Artefakte, keine dauerhaften App-Daten; sonst entsteht ein neues, unkontrolliert
wachsendes Datei-Leck, das der Speicherplatz-Bereinigung in `SettingsScreen.kt`
("Speicherplatz freigeben") nicht bekannt ist.

---

## 3 · PDF-Seiten-Umfang für "V1 minimal"

Das Original hat ~20 `page_*`-Funktionen (`gesamtbericht_lib_v3.py`, Zeile 917-3022). Nicht alle
sind für "V1 minimal" geeignet — manche hängen an den bewusst ausgeschlossenen Datenquellen
(`dauerlaerm.csv`, `relevante_ereignisse.csv`, Bautagebuch, Tiefbohrer-Erkennung, siehe
`docs/DATENMAPPING_BERICHT_SCHRITT4.md` Abschnitt 1).

**Deine Aufgabe, bevor du eine Seite baust:** geh jede `page_*`/`_legal_*`-Funktion einzeln durch
und ordne sie einer Kategorie zu:

- **Portabel** — hängt nur an Kernlogik-Kennwerten, Fotos/Stammdaten/Manifest oder der
  Gebietstypen-Tabelle. Kandidat für V1.
- **Braucht ausgeschlossene Daten** — referenziert `dauerlaerm.csv`/`relevante_ereignisse.csv`/
  `Bautagebuch`/`tb.*` (Tiefbohrer) direkt oder über eine Hilfsfunktion, die das tut. Nicht für
  V1 — die Seite fehlt im generierten PDF, wird nicht durch einen Platzhalter ersetzt (ein
  Platzhalter, der wie eine fehlende Auswertung aussieht, ist ehrlicher als eine erfundene Seite).
- **Unklar** — z. B. `page_referenz`/`page_referenzpegel_aussen`/`page_referenz_vergleich`
  (Zeile 1731, 2215, 2278): könnten ein allgemeines "Tag X vs. Tag Y"-Konzept sein oder untrennbar
  am fallspezifischen "Vergleichstag"-Konzept (`VERGLEICHSTAG_LABEL`, außerhalb V1) hängen — **das
  liest sich nicht eindeutig aus der Funktionssignatur, lies den Funktionskörper.** Bei
  Unklarheit: außen vor lassen und im PR nennen, nicht raten.

**Baue eine Tabelle dieser Zuordnung in den PR** (Funktionsname → Kategorie → kurze Begründung) —
das ist die Grundlage, auf der der Owner später "V2" (mit Bautagebuch/Vergleichstag/Dauerlärm)
plant.

Mindestens diese Seiten sind mit hoher Wahrscheinlichkeit portabel und sollten in V1: Deckblatt
(`page_cover`), Methodik/Berechnungsgrundlagen (`page_berechnung_kennwerte`), Zusammenfassung
(`page_summary`), Manifest (`page_manifest` — **auf Basis der bereits vorhandenen
`SessionEntity.rohdatenPruefsumme`/`DokumentationsFotoEntity.pruefsumme` bauen, nicht erneut
Dateien hashen**), Belastungsdauer (`page_belastungsdauer`), Lauteste Stunde
(`page_lauteste_stunde`), Tagesseite (`page_day` — **prüfe, ob die Quellen-Einfärbung der Kurve
entfernt werden muss, da `bin_dom`/`src_stats` aus den ausgeschlossenen Klassifikationsdaten
stammen**), Messaufbau-Fotos (`page_messaufbau`), Innenraum-Orientierung (`page_innenraum`, wirkt
aus der Funktionssignatur allein generisch).

---

## 4 · Aufbau von `report_bridge.py`

```python
def generate_report(parameter_json: str) -> str:
    parameter = json.loads(parameter_json)
    # 1. Dateien einlesen (Abschnitt 2), Kernlogik-Funktion je Tag aufrufen
    # 2. Gebietstyp aus der Tabelle (PROMPT_BERICHT_GEBIETSEINSTUFUNG.md) auflösen
    # 3. PDF ueber PdfPages aufbauen, nur die Seiten aus Abschnitt 3
    # 4. Ausgabepfad zurueckgeben
    ...
```

- Jede unerwartete Eingabe (fehlende Datei, kaputtes JSON, unbekannter Gebietstyp) wirft eine
  **aussagekräftige** Exception — die kommt 1:1 als `PyException.message` beim Nutzer an
  (`ChaquopyReportRunner.kt` Zeile 65). "KeyError: 'foo'" ist keine brauchbare Nutzermeldung.
- Der Ausgabepfad muss im App-privaten Speicher liegen (`context.filesDir`-Analogon von der
  Kotlin-Seite mitgegeben, nicht selbst geraten) — dieselbe Stelle, von der aus
  `ChaquopyReportRunner.Ergebnis.Erfolg.pdfPfad` weiterverwendet wird (Teilen-Dialog o. ä., analog
  `PeriodenBerichtExport`/`GesamtberichtExport` in `BerichtScreen.kt`).

---

## 5 · Tests

- **`connectedAndroidTest`** (braucht Gerät/Emulator, siehe Kopf dieses Dokuments): Ende-zu-Ende
  über `ChaquopyReportRunner.erzeugeBericht()` mit einer kleinen, in-Test angelegten Session
  (wenige Minuten synthetischer Messwerte reichen) → tatsächliche PDF-Datei entsteht, existiert,
  ist nicht leer.
- Fehlerfälle: kaputtes JSON, unbekannter Gebietstyp, fehlende Exportdatei → jeweils ein
  `Ergebnis.Fehler` mit verständlicher `nachricht`, kein Absturz, kein rohes Traceback im UI.
- Die reine Rechenlogik ist bereits durch `docs/PROMPT_BERICHT_PYTHON_KERNLOGIK.md`/
  `docs/PROMPT_BERICHT_GEBIETSEINSTUFUNG.md` mit pytest abgedeckt — **hier nicht erneut testen**,
  nur die Verdrahtung (Dateihandling, JSON-Parsing, PDF-Zusammenbau, Fehlerpfade).

---

## 6 · Doku

1. `docs/DATENMAPPING_BERICHT_SCHRITT4.md`: Status auf "Schritt 4 abgeschlossen (V1 minimal)"
   aktualisieren, mit Verweis auf die tatsächliche PDF-Seiten-Tabelle (Abschnitt 3).
2. `docs/CHECKLISTE_GERAETETEST.md`, Teil F: neuer Eintrag für den vollständigen
   Bericht-Erstellungs-Pfad (falls in diesem Auftrag doch kein Gerät verfügbar war) bzw. Eintrag
   nach Abschnitt E verschieben/ergänzen, wenn er hier bereits verifiziert wurde.
3. `README.md`: **jetzt lohnt sich ein kurzer Statusabschnitt** ("High-End-Bericht (Chaquopy):
   Schritt 1-4 umgesetzt, V1 minimal") — vorher gab es dafür keinen abgeschlossenen Stand zu
   vermelden, jetzt schon. Kurz halten, im bestehenden README-Stil.
4. `app/build.gradle.kts`-Kommentar bei der Chaquopy-Konfiguration aktualisieren, falls
   `reportlab` entfernt wurde (Abschnitt 1) — der bestehende Kommentar dort erwähnt es noch als
   Teil der Konfiguration.

---

## 7 · Was ausdrücklich **nicht** Teil des Auftrags ist

- Jede PDF-Seite, die in Abschnitt 3 als "braucht ausgeschlossene Daten" oder ungeklärt
  eingestuft wurde.
- Bautagebuch-Erfassung, Vergleichstag-Kennzeichnung, Dauerlärm-Phasenerkennung,
  Quellenverteilung nach Lärmart als eigene Funktionen (das wäre "V2", eigener künftiger Auftrag).
- Der alte Zeitraum-/Gesamtbericht-Dialog in `BerichtScreen.kt` — Ablösung ist eine separate,
  spätere Owner-Entscheidung.

---

## 8 · Definition of Done

1. `./gradlew assembleDebug` grün, Ausgabe im PR.
2. `connectedAndroidTest` grün und im PR gezeigt — **oder** explizit vermerkt, dass kein
   Gerät/Emulator verfügbar war, mit Angabe, was dadurch unverifiziert bleibt.
3. Die PDF-Seiten-Tabelle aus Abschnitt 3 vollständig und im PR enthalten.
4. Abschnitt 6 (Doku) erledigt, nicht nur erwähnt.
5. Draft-PR gegen `main`: was gebaut wurde · was auf einem echten Gerät verifiziert wurde
   (Kommando + Ergebnis, oder Beleg über den Emulator) · welche Seiten fehlen und warum · jede
   Abweichung von diesem Prompt mit Begründung.
