# Datenmapping Bericht-Umbau Schritt 4 (report_bridge.py)

Owner-Entscheidung 13.09.2026: Schritt 4 wird **V1 minimal** umgesetzt - nur der portable
LAeq/§287-ZPO-Kern, ohne Bautagebuch, Vergleichstag-Kennzeichnung, Dauerlärm-Phasenerkennung und
Quellenverteilung nach Lärmart. Dieses Dokument hält fest, wie sich der dafür nötige Teil des
Originalskripts (`Baul-rm`, `pipeline/Verknuepfung/scripts/gesamtbericht_lib_v3.py`,
`compute_day()`, Zeile 607ff.) auf das bestehende Room-Schema dieses Projekts abbildet, und was
davon noch fehlt oder klärungsbedürftig ist. Referenzcommit des Originalskripts:
`arthurschaab-bit/Baul-rm` (öffentlich, siehe README dort).

## 1. Warum V1 minimal

Das Originalskript ist kein generischer Berichtsgenerator, sondern über Monate auf EINEN
laufenden Fall hin angereichert worden: `DAYS_ALL_DEFAULT`, `VERGLEICHSTAG_LABEL`,
`FEIERABEND_BELEGT` (Zeilen 64-116) sind hartkodierte Kalendertage mit handschriftlich
formulierten juristischen Begründungen aus einem externen Bautagebuch. Die
Quellenverteilungs-Seiten hängen an `relevante_ereignisse.csv`, dem Output einer eigenen
PANNs/CLAP/OpenAI-Klassifikationspipeline (bewusst nicht Teil dieses Ports, App bleibt bei
YAMNet). Die Dauerlärm-Phasenerkennung (`dauerlaerm.csv`) ist eine eigene Regel-Engine
(`05_dauerlaerm_v7.py`), kein Teil von `compute_day()`. Alle drei sind eigenständige, noch nicht
gebaute App-Funktionen - V1 lässt sie bewusst weg, um zuerst den Kern zum Laufen zu bringen.

## 2. Datenquellen-Mapping

| Original (`gesamtbericht_lib_v3.py`) | Room-Äquivalent | Bemerkung |
|---|---|---|
| Tages-CSV `{day} *.csv`, Spalten `idx,datum,zeit,dba,...` (Zeile 619), auf 1 Hz reindiziert | `MeasurementEntity` (`sessionId, timestamp, levelDb, flags`) | Nur `timestamp`+`levelDb` werden im Original wirklich genutzt (`c4..c6` werden gelesen, aber verworfen) |
| Fehlende Sekunden = `NaN` nach `reindex(freq="1s")` (Zeile 642f.) | `MeasurementFlags.GAP` / `GAP_REASON_SENSOR_ERROR` (`SessionEntity.kt`) | Funktional äquivalent: GAP-Zeilen bleiben bestehen (mit letztem/fehlerhaftem Rohwert), fließen aber nicht in `energy.rolling(...).mean()` ein - report_bridge.py muss GAP-Zeilen beim Aufbau der energetischen Zeitreihe genauso ausschließen, wie das Original NaN behandelt |
| `COVERAGE_VALID=0.90`, `COVERAGE_WINDOW=0.70` (Zeile 44f.) | `ReportConfigEntity.tierSchwelleVollmessungProzent/-TeilerfassungProzent` | bereits gebaut (Schritt 2/3) |
| `STERN_ANNAHME_DB=50.0`, `KONSERVATIV_ANNAHME_DB=RW_TAG_WA=55.0` (Zeile 54f.) | `ReportConfigEntity.schaetzpegelTeilerfassungDb/-MessfensterAbbruchDb` | bereits gebaut, Defaults stimmen mit dem Original überein |
| `GERAET_TOL_DB=1.4` (Zeile 59) | `ReportConfigEntity.geraeteUnsicherheitDb` | bereits gebaut, Default identisch |
| `KONSERVATIV_FENSTER_START/-ENDE = 15/19` (Zeile 52f.) | **fehlt noch** | siehe Abschnitt 5 |
| `ADRESSE`, Gebietseinstufung/Richtwerte (WA fest, Zeile 64, 1240ff.) | `ReportConfigEntity.gebietseinstufung` | Feld existiert (Freitext), die zugehörige Richtwert-/Fließtext-Generalisierung (55/40 dB nur für WA hartkodiert) ist selbst noch **nicht portiert** - eigener Punkt in Schritt 4, nicht nur Datenmapping |
| `load_pos(day)` - Position/Innen-Außen pro Kalendertag (Zeile 232) | `StammdatenVerlaufEntity` (`messort`, `innenAussen`, `mikrofonposition`, ... via `GesamtberichtStammdatenSheet`) | Granularität passt nicht 1:1 - Original ist pro Kalendertag, unser Modell ist pro Messbeginn (Session). Siehe Abschnitt 4 |
| `load_meteo()` - Wetterhistorie pro Tag (Zeile 446, aus DWD-CSV) | `StammdatenVerlaufEntity.wetter` (Schnappschuss bei Messbeginn) | Original hat eine Tageszeitreihe, wir nur einen Zeitpunkt-Wert - für V1 als Vereinfachung übernehmen, nicht nachbauen |
| Fotos (Messaufbau-Seite, Zeile 1562/1581) | `DokumentationsFotoEntity` | bereits vorhanden, direkt nutzbar |
| SHA-256-Manifest (Zeile 1794-1873) | `SessionEntity.rohdatenPruefsumme`, `DokumentationsFotoEntity.pruefsumme` | Prüfsummen existieren bereits pro Session/Foto; Manifest-Seite muss sie nur noch auflisten statt (wie im Original) Dateien im Cache-Ordner zu hashen |
| `dauerlaerm.csv`, `relevante_ereignisse.csv`, `Bautagebuch_*.xlsx`, `tiefbohrer.py` | **kein Äquivalent, bewusst außerhalb V1** | siehe Abschnitt 1 |

## 3. Geltungsbereichsgrenze: Retention/Minutenaggregate

`MeasurementEntity` wird nach 90 Tagen vom `RetentionCoordinator` auf `MinuteAggregateEntity`
verdichtet (nur noch `leqDb/maxDb/minDb/sampleCount` je Minute, keine Sekundenwerte mehr). Das
Original rechnet ausnahmslos auf Sekundenbasis (99%-Perzentil `l1_tag`, "Lauteste Stunde" als
rollierendes 1h-Fenster, Taktmaximalpegel `beurt()` auf 5-Sekunden-Takten - Zeile 665-679, 690).

**Vorschlag:** `report_bridge.py` (bzw. der Kotlin-Aufrufer davor) prüft, ob der angeforderte
Berichtszeitraum vollständig innerhalb der Rohdaten-Aufbewahrung liegt (`MeasurementEntity`
vorhanden), und lehnt sonst mit einer klaren Fehlermeldung ab, statt mit gröberen
Minutenaggregaten eine andere (schwächere) Kennzahlberechnung still zu approximieren - das wäre
genau die Art von stillschweigender Verwässerung, die die Anti-Null-Regel/GAP-Flags verhindern
sollen. Ob das so gewünscht ist, ist eine offene Frage (Abschnitt 6).

## 4. Kalendertag-Gruppierung

Es gibt bereits eine Konvention für "Session → Kalendertag" im Protokollreiter:
`gruppiereSessionsNachTag()` (`messreihe/SessionFilterUndGruppierung.kt:83`), gruppiert nach dem
lokalen Kalendertag von `SessionEntity.startedAt` (`SimpleDateFormat("dd.MM.yyyy")`,
Default-Locale/-Zeitzone). Für report_bridge.py sinnvoll wiederzuverwenden, damit "welcher Tag ist
das" app-weit einheitlich bleibt - **keine neue/andere Tagesgrenze für den Bericht erfinden.**

Eine Konsequenz: Ein Kalendertag kann mehrere Sessions enthalten (App-Neustart, manuelles
Stopp/Start). `compute_day()` erwartet einen einzigen zusammenhängenden Datensatz je Tag
(`frames.append(...)` sammelt zwar mehrere CSVs, aber alle für denselben Kalendertag) - das passt:
alle `MeasurementEntity`-Zeilen aller Sessions, deren `startedAt` auf denselben Kalendertag fällt,
werden zusammengeführt, genau wie das Original mehrere Tages-CSVs desselben Tages zusammenführt
(Zeile 641, `pd.concat(frames)`).

Für die Stammdaten (Position/Innen-Außen/Wetter, Abschnitt 2) heißt das: pro Kalendertag kann es
mehrere `StammdatenVerlaufEntity`-Einträge geben (einen je Session-Start). **Offene Frage**, welcher
davon für den Tagesbericht gilt (Abschnitt 6).

## 5. Fehlende Konfigurationskonstante

`KONSERVATIV_FENSTER_START=15`, `KONSERVATIV_FENSTER_ENDE=19` (Uhrzeit-Fenster, in dem ein
Messende bei Tier=Messfenster die konservative Volltag-Hochrechnung auslöst, Zeile 52f./758-781)
fehlt bisher in `ReportConfigEntity`. Nachtrag als zwei weitere `Int`-Felder (Stunde 0-23) ist
eine reine additive Migration (23→24), analog zu den bereits bestehenden Feldern - unkritisch,
aber noch nicht umgesetzt.

## 6. Entscheidungen (Owner-Klärung 13.09.2026)

1. **Retention-Grenze (Abschnitt 3):** *Ablehnen mit klarer Fehlermeldung.* Enthält der
   angeforderte Zeitraum auch nur einen Kalendertag, der bereits auf `MinuteAggregateEntity`
   verdichtet ist, wird kein Bericht erzeugt - keine stillschweigend schwächere
   Kennzahlgenauigkeit. `report_bridge.py` (bzw. der Kotlin-Aufrufer davor) muss das VOR dem
   eigentlichen Python-Aufruf prüfen und dem Nutzer den Grund nennen.
2. **Mehrere Stammdaten-Einträge pro Kalendertag (Abschnitt 4):** *Nutzer wählt beim
   Berichtserstellen explizit.* Enthält ein Kalendertag mehrere `StammdatenVerlaufEntity`-Einträge
   (mehrere Session-Starts), muss der Berichtserstellungs-Dialog dem Nutzer diese zur Auswahl
   anbieten, statt still einen davon zu bevorzugen - **neue UI-Anforderung für den
   Berichtserstellungs-Flow**, der in Schritt 3 noch nicht existiert (dort ging es nur um die
   globalen § 287-Parameter, nicht um den eigentlichen "Bericht jetzt erzeugen"-Ablauf).
3. **`weighting`/`timeWeighting` unconfirmed:** *Konfigurierbar, Owner kann bewusst übersteuern.*
   Standardverhalten ist Verweigerung (kein Bericht ohne hardwareseitig bestätigte A-/
   Zeitbewertung), aber ein expliziter Schalter erlaubt das bewusste Übersteuern. Braucht ein
   neues `ReportConfigEntity`-Feld (z. B. `erzwingeBerichtOhneBestaetigteBewertung: Boolean =
   false`, additive Migration) plus eine deutlich sichtbare Warnung/Disclaimer-Zeile im
   generierten Bericht selbst, wenn übersteuert wurde - ein Bericht darf nie so aussehen, als wäre
   er ohne diesen Vorbehalt entstanden.
4. **Gebietseinstufungs-Generalisierung (Schritt 4b, 18.09.2026):** Registry und
   parameterisierte Textbausteine in `laermbericht/areas.py`. Werte für WA, WR, MI, GE und GI
   aus AVV Baulärm vom 19.08.1970 und amtlicher Zuordnung belegt. WS, WB, MD, MDW, MU und MK
   bleiben ohne Werte (`TODO(Owner)`) und sind für Berichte gesperrt. Rechtliche Begründung
   der örtlichen Zuordnung bleibt für jeden Typ `TODO(Owner)`, auch WA. Owner-Entscheidung
   18.09.2026: feste Auswahl statt Freitext; unbekannte Alttexte verlangen Neuauswahl.
   Quellen, vollständiges Original-Textinventar, Unterschiede zur Referenz und 4c-Anschluss:
   `docs/BERICHT_GEBIETSEINSTUFUNG_QUELLEN.md`. Messfenster-Schätzpegel folgt jetzt dem
   Gebietstagesrichtwert; freier Altwert bleibt in der DB erhalten, wird dafür nicht genutzt.


## 7. Daraus folgende Nachträge

- `ReportConfigEntity`: `konservativFensterStartStunde`, `konservativFensterEndeStunde` und
  `erzwingeBerichtOhneBestaetigteBewertung` sind mit Migration 23→24 und der erweiterten
  Berichtsparameter-Sektion in `SettingsScreen.kt` umgesetzt (Teil A, 14.09.2026).
- Der neue Einstieg in `BerichtScreen.kt` und `BerichtErstellenSheet.kt` nutzt einen frei
  wählbaren Datumsbereich und prüft Retention, Stammdaten-Auswahl sowie A-/Zeitbewertung vor
  dem Chaquopy-Aufruf (Teil B, Owner-Klärung 14.09.2026). Fehlende oder unvollständige
  Stammdaten sind eine sichtbare Lücke, kein stiller Default: Nacherfassung bleibt möglich,
  ein Bericht darf bis dahin mit dieser Lücke erstellt werden. Migration 24→25 ergänzt
  `StammdatenVerlaufEntity.giltFuerTagStart`, damit historische Zuordnung und tatsächlicher
  Erfassungszeitpunkt getrennt bleiben. Das JSON ist bis zur Integration von
  `report_bridge.py` ausdrücklich ein vorläufiger Vertrag; der derzeitige Modulfehler wird im
  UI verständlich gezeigt. Der alte Zeitraum-/Gesamtbericht-Dialog bleibt bestehen.

## 8. Abtastratenentscheidung für die portable Kernlogik (Schritt 4a)

Owner-Entscheidung 16.09.2026: Die etwa 2 Hz des PCE-323 werden vor der aus dem Original
portierten Kernlogik auf ein striktes 1-Hz-Raster verdichtet (Option a). Pro belegter Sekunde
laufen zwei bewusst getrennte Reihen weiter:

- Für LAeq, rollierende LAeq-Werte, Abdeckung und Zeiten über den Richtwerten wird der
  **energetische Mittelwert** aller gültigen Frames der Sekunde verwendet. Damit bleibt die
  Verdichtung konsistent zu `energy = 10**(dba/10)` im Original.
- Für `hoechst`, das 99.-Perzentil `l1_tag` und das 5-Sekunden-Taktmaximum in `beurt()` wird
  der **Maximalwert der Sekunde** verwendet. Eine alleinige Ableitung dieser Kennwerte aus dem
  energetischen Sekundenmittel würde kurze Spitzen systematisch absenken. Der Maximalwert ist
  für Schwellenzeiten dagegen bewusst nicht maßgeblich, weil ein einzelner etwa 0,5 Sekunden
  langer Frame sonst als volle Sekunde über der Schwelle gezählt würde.

`MeasurementFlags.GAP`-Frames fließen in keine der beiden Reihen ein. Sobald in derselben
Sekunde wenigstens ein gültiger Frame vorhanden ist, zählt diese Sekunde als gemessen. Die
Peak-Reihe ist eine dokumentierte, konservative Erweiterung gegenüber dem Original, das wegen
seiner bereits auf 1 Hz vorliegenden CSV nur eine gemeinsame Reihe kannte.

## 9. Schritt 4c: Integration V1 minimal

Implementierung abgeschlossen: `HighEndReportExport` übergibt private Tages-CSVs und kleine
JSON-Metadaten an `report_bridge.generate_report`. Matplotlib/PdfPages verbindet Kernlogik und
Gebietsregistry zu einem teilbaren PDF. Vollständige Seitenzuordnung, Vertrag, Bereinigung und
bewusste Abweichungen: `docs/BERICHT_PDF_SEITEN_V1.md`.

Fehlende Stammdaten bleiben sichtbar. Unbekannte Innen-/Außenlage sperrt Außenvergleiche und
Hochrechnungen; Gebietstypen/Rechtsprosa-TODOs aus 4b bleiben unverändert. Sommerzeitgrenzen im
4a-Kern sind auf lokale Uhrzeit korrigiert, das 1-Hz-Verdichtungsverfahren bleibt gleich.

Lokale Python-/PDF-Prüfung und echte Android-CI-Ergebnisse stehen im zugehörigen PR.
Ein realer PCE-323-Gerätetest und die rechtliche Freigabe der örtlichen Zuordnung bleiben offen.
