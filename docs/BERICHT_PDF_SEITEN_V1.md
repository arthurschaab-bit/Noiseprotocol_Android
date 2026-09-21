# High-End-Bericht V1: Seitenport und Datei-Vertrag (Schritt 4c)

Basis: `arthurschaab-bit/Baul-rm`, `pipeline/Verknuepfung/scripts/gesamtbericht_lib_v3.py`,
Commit `646c7c7e7edfa87332d1e6783767f626f67741dd`. Alle unten genannten Funktionskörper wurden
vor dem Port gelesen. Aufbau weiterhin Matplotlib `Agg`, `Figure`, `fig.text`, Tabellen,
Diagramm-Achsen und `PdfPages`; kein ReportLab, keine zweite PDF-Engine.

## Vollständiges Original-Seiteninventar

„Portabel (Teil)“ heißt: Der datenunabhängige Seitenkern wird übernommen; die ausdrücklich
benannten Fall-/V2-Blöcke fehlen ohne Platzhalterseiten. Originalzeilen beziehen sich auf den
oben fixierten Stand. Die tatsächliche Seitenzahl wächst bei langen Texten/Listen.

| Originalfunktion (Zeilen) | Kategorie | V1-Entscheidung / Begründung |
|---|---|---|
| `page_cover` (917–1113) | Portabel (Teil) | Rahmen und nach Abdeckung getrennte Ergebnisse; feste Adresse, Mieter, Wohnungsseiten, Dauerlärm und Nacht-Ausnahmetag entfallen. |
| `page_juristische_kurzfassung` (1118–1206) | Braucht ausgeschlossene Daten | Kausalitätsbehauptung aus Bauphasen, Videos, Quellen und Bautagebuch; gesamte Seite entfällt. |
| `page_legal` (1211–1223) | Portabel (Teil) | Gebietsrichtwerte, Quellen und ausdrückliche Rechts-TODOs aus 4b. |
| `_legal_p2` (1225–1268) | Portabel (Teil) | Keine behördliche WA-Bestätigung, falsche Nr.-3.2-Nachtzeitreferenz oder automatische Handlungspflicht übernehmen; 4b-Bausteine ersetzen diese Texte. |
| `_legal_p3` (1270–1330) | Portabel (Teil) | Mess-/Abdeckungsmethodik in Berechnungsseiten integriert; feste Kalibrierungstage, Quellen und Ausnahmetag entfallen. |
| `page_berechnung_kennwerte` (1336–1424) | Portabel (Teil) | Formeln, 1-Hz-Entscheidung, konfigurierbare Abdeckung/Schätzungen, Taktkennwert-Vorbehalt; Dauerlärm-/Tiefbohrer-/Quellenregeln entfernt. |
| `page_summary` (1429–1554) | Portabel (Teil) | Gemessener LAeq und zusätzliche Schätzung stehen getrennt; keine Quellen-/Phasenspalte, keine automatische Werktag-/Wochenendzuordnung. |
| `page_messaufbau` (1581–1655) | Portabel (Teil) | Gewählte Stammdaten und Session-Fotos, EXIF-korrigiert; keine festen Balkon-/Höhen-/Wetterstationsbehauptungen. Fehlende Angaben bleiben sichtbar. |
| `page_lageplan` (1679–1726) | Braucht ausgeschlossene Daten | Feste Abrisskante, Tiefbohrerpositionen und Kausalitätsargumente; keine Lageplan-Zuordnung im V1-Vertrag. Entfällt. |
| `page_referenz` (1731–1789) | Braucht ausgeschlossene Daten | `REFERENZ_TAGE`, Aussage „bauarm“ und fixes Aufbau-Regime; kein generischer Tagesvergleich. Entfällt. |
| `page_manifest` (1875–1906) | Portabel | Vollständige gespeicherte Session-/Foto-SHA-256, keine Neuberechnung. Lange Listen erhalten Folgeseiten statt Kürzung auf 56 Einträge. |
| `page_kernbefunde` (1912–1994) | Braucht ausgeschlossene Daten | Dauerlärm, fixes Kalibrierungsdatum, Wohnungsseiten und Baustellenkausalität. Entfällt. |
| `page_belastungsdauer` (1999–2036) | Portabel (Teil) | Zwei gebietsabhängige Schwellenbalken; dritter Dauerlärm-Balken entfernt. Nur bestätigte Außenaufstellung. |
| `page_lauteste_stunde` (2041–2103) | Portabel | Lautestes Fenster mit tatsächlich gemessenen Sekunden; keine fixen 55/60/70-dB-Pegelklassen oder Ausnahme „27.06.“. Alle Messzeiten einschließlich Nacht. |
| `page_innenraum` (2109–2163) | Portabel (Teil) | Tabellenkern aus realen Innenraumtagen; fest eingetragene Mai-Messwerte, Videopegel und Ausweichunmöglichkeit entfernt. Ohne Innenraumtage entfällt die Seite. |
| `page_referenzpegel` (2169–2206) | Braucht ausgeschlossene Daten | Feste Video-Schätzwerte vom 30.05. und Vergleich mit späteren Baupegeln. Entfällt. |
| `page_referenzpegel_aussen` (2215–2263) | Braucht ausgeschlossene Daten | `REF_RUHETAGE` 27./28.06., Bauruhe laut Bautagebuch. Entfällt. |
| `page_referenz_vergleich` (2278–2350) | Braucht ausgeschlossene Daten | Gleiche festen Ruhetage und Werktage automatisch als Bautage; kein neutraler generischer Vergleich. Entfällt. |
| `page_video_only_day` (2369–2438) | Unklar für V1 / ausgeschlossen | Benötigt externe Video-Liste und dB-Schätzungen aus Dateinamen, die der V1-Vertrag nicht enthält. Entfällt ohne Ersatzseite. |
| `page_videoliste` (2445–2498) | Unklar für V1 / ausgeschlossen | Externe `VIDEOS`-Zuordnung und fixer Lückentag; keine Portierung/Neuerfindung eines Video-Mappings. |
| `page_day` (2507–3022) | Portabel (Teil) | Sekunden-/Minutenkurven, Kennwerte, gemessene Abdeckung und additive Schätzungen. Quellenflächen, Tiefbohrer, Bauende, Hagel-Falltag und Videomarker entfernt. Tatsächliche GAP-Sekunden werden schraffiert. |

## Vertrag Version 2 und Lebenszyklus

`HighEndReportExport.generate` liest Vorprüfung und Rohdaten in einer Room-Transaktion erneut.
So kann eine parallele Retention nicht zwischen Prüfung und Export die Rohdaten entfernen.
Tage werden nach denselben lokalen `[von, bis)`-Grenzen wie `ladeBerichtstage` abgefragt,
auch bei über Mitternacht laufenden Sessions. Nur ein Tag Rohwerte liegt gleichzeitig in Kotlin
im Speicher. Keine Datenbank-/Schemaänderung.

- Privater temporärer Ordner: `cacheDir/report_handoff/<UUID>/`.
- CSV pro ausgewähltem Datum, auch als reine Kopfzeile für Tage ohne Rohdaten:
  `timestampMillis,levelDb,flags,sessionId,weighting,timeWeighting` (UTF-8).
- JSON enthält `contractVersion=2`, IANA-`timeZone`, `days` mit `samplesPath`, tatsächlicher
  `rawSampleCount`, ausgewählten `stammdaten`, Lückenliste, `sessions` und `photos`;
  dazu `reportConfig`, `unconfirmedWeightingOverride`, Zeitraumgrenzen und `outputPath`.
- Die Android-Allowlist (`filesDir`, `cacheDir`) konfiguriert der Runner über
  `configure_private_directories(...)` getrennt vom untrusted Parameter-JSON. Python akzeptiert
  PDFs nur unter `filesDir/reports/` und Rohdaten/Fotos nur
  unter `cacheDir/report_handoff/`; alle Pfade werden kanonisiert. PDF-Ziel:
  `filesDir/reports/Schallbericht_<UUID>.pdf`; Rückgabepfad und Ziel dieses Laufs müssen exakt
  übereinstimmen.
- Die bisherigen Dokumentationsfotos liegen im app-eigenen externen Speicher. Für den Handoff
  werden sie in den privaten temporären Ordner kopiert; die Originale bleiben unverändert.
  Vorhandene SHA-256-Werte werden mitgegeben, nicht neu berechnet. Fehlende/defekte Fotos
  erscheinen als sichtbare Dokumentationslücke, inklusive ihrer vorhandenen Metadaten.
- Nach Erfolg, Fehler oder Coroutine-Abbruch entfernt Kotlin den temporären Laufordner.
  Python schreibt zunächst `.pdf.part`, veröffentlicht erst nach vollständigem Rendering und
  entfernt die Teildatei bei Fehlern. Kein Rohwert-Array im JSON.
- Erfolgreiche PDFs bleiben privat erhalten und sind über einen auf `reports/` begrenzten
  FileProvider-Pfad teilbar. Die bisherigen Berichtsdialoge bleiben bestehen.
- Pro Prozess serialisierter Python-/Matplotlib-Zugriff; Interpreterstart und Rendering auf IO.
  Keine neue Foreground-Service-/Worker-Architektur in diesem Auftrag.

## Fachliche Grenzen und bewusste Abweichungen

- Verdichtungsverfahren aus 4a unverändert: Energie-Mittel je Sekunde für LAeq/Zeiten, Sekundenmaximum für Spitzen/L1/
  Taktmaximum. GAP-Frames werden ausgeschlossen. Gruppenbildung des Original-Taktkennwerts
  entfernt fehlende Werte vor Fünfergruppen und kann deshalb Lücken überspannen; dieser
  bestehende methodische Vorbehalt wird ausdrücklich gedruckt, kein AVV-Beurteilungspegel behauptet.
- Unbekannte Innen-/Außenlage wird nicht still auf Außen gesetzt. Messwerte werden gezeigt,
  Außenrichtwertvergleich und beide Außenhochrechnungen entfallen bis zur Dokumentation.
- Alle gemessenen und geschätzten LAeq-Werte bleiben separat sichtbar. Schätzungen ändern
  weder reale Abdeckung noch Abdeckungsstufe. Fensterhochrechnung füllt keine früheren Lücken.
- Der Override erscheint auf jeder Seite. Bekannte C-Bewertung wird auch mit Override abgelehnt:
  Der Schalter erlaubt unbestätigte Bewertung, keine Umbenennung bekannter C-Werte in dB(A).
- Nachtwerte und lauteste Stunde umfassen alle tatsächlich vorhandenen Tagesmesszeiten,
  ohne den festen Original-Ausnahmetag. Keine Behauptung einer nächtlichen Baustellenursache.
- Lange Stammdaten/Notizen/Hashes werden vollständig umbrochen und auf Folgeseiten fortgesetzt.
  Die Tagesgrafik und ihre ausführlichen Kennwerte stehen deshalb auf getrennten Seiten.
- Grenzwerte und Rechts-TODOs stammen ausschließlich aus dem gemergten 4b-Modul. Die örtliche
  rechtliche Begründung bleibt für alle Gebiete offen. WS/WB/MD/MDW/MU/MK bleiben gesperrt.
- Integrationskorrektur im 4a-Kern: Raster-Flooring erfolgt in UTC und wird zurückkonvertiert,
  damit doppelte Nachtstunden eindeutig bleiben. Tagesfenster verwenden 07/20 Uhr Ortszeit
  statt vergangener Stunden seit Mitternacht. Beide Umstellungstage werden nachgerechnet.
- Kalenderzeitumstellung: grafische Wandzeit-Dopplungen werden für die Lückenschraffur
  zusammengefasst; die ursprünglichen Zeitstempel bleiben in der Berechnung erhalten.

## Verifikation

Befehle und tatsächliche Ergebnisse werden im PR dokumentiert. `test_report_bridge.py` prüft
Dateien/JSON, Bereichsgrenzen, echte PDF-Texte, vollständige Langtexte/Hashes und Override.
`ChaquopyReportRunnerInstrumentedTest` führt den echten Room → CSV → Chaquopy → Matplotlib →
PDF-Pfad aus, liest/rendert die PDF mit Android `PdfRenderer`, öffnet sie über den FileProvider
und prüft Cleanup bei Erfolg/Fehler sowie verständliche Fehlerfälle.

Lokaler Emulator fehlt; lokale Gradle-Ausführung scheitert bereits am Distribution-Download
(`Network is unreachable`). Das ist kein erfolgreicher Build. Verifikation auf Android erfolgt
über die bestehenden CI-Workflows; ein echter PCE-323-Hardwaretest ist damit nicht ersetzt.
