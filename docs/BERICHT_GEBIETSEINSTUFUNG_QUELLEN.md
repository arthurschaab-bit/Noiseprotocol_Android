# Bericht Schritt 4b: Gebietstypen, Quellen und Textinventar

Stand: 18.09.2026. Basis: Schritt 4a, gemergt mit PR #173 (`03458f7`).
Referenz: `Baul-rm/pipeline/Verknuepfung/scripts/gesamtbericht_lib_v3.py`,
Commit `646c7c7e7edfa87332d1e6783767f626f67741dd`.

## Quellen und Grenzen

1. **AVV Baulärm vom 19.08.1970**, Beilage zum BAnz. Nr. 160 vom 01.09.1970:
   [amtlicher Volltext](https://www.verwaltungsvorschriften-im-internet.de/bsvwvbund_19081970_IGI7501331.htm).
   Gelesen: Nr. 3.1.1 (Werte), 3.1.2 (Nachtzeit), 3.1.3 (Nachtspitzen),
   3.2 (Gebietszuordnung), 4.1 (Maßnahmen) und 6 (Beurteilungspegel).
2. **Stadt Jena, Fachdienst Umweltschutz**, Merkblatt zum Schutz vor Baulärm und
   Luftverunreinigungen beim Baustellenbetrieb, **Februar 2021, Seite 1**:
   [amtliches Merkblatt](https://service.jena.de/system/files/2021-11/Merkblatt_Baustelle_02_2021.pdf).
   Belegt zusätzlich die Zuordnung der AVV-Kategorien zu Industrie-, Gewerbe-, Misch-,
   allgemeinen und reinen Wohngebieten. Die Zahlen selbst stammen aus Quelle 1.
3. [BauNVO, amtliches Inhaltsverzeichnis](https://www.gesetze-im-internet.de/baunvo/):
   Bezeichnungen und Paragraphen der vorbereiteten Gebietstypen. Diese Quelle allein
   belegt **keine** AVV-Richtwertzuordnung.

Alle Quellen am 18.09.2026 geöffnet. Die AVV enthält Nutzungskategorien, keine vollständige
Tabelle sämtlicher heutiger BauNVO-Kürzel. Nr. 3.2 berücksichtigt Bebauungsplan und tatsächliche
Nutzung. Die App ordnet keinen Standort eigenständig einem Gebiet zu. Insbesondere wird
keine Bestätigung aus dem ursprünglichen Münchner Einzelfall übertragen.

| Kürzel | BauNVO | AVV Nr. 3.1.1 | Tag/Nacht dB(A) | Vergleichsgrenzen Nr. 4.1 Tag/Nacht | Status/Quelle |
|---|---|---|---|---|---|
| WA | § 4 | d | 55/40 | 60/45 | Werte Quelle 1, Zuordnung Quelle 2; zusätzlich Original Z. 47–50 |
| WR | § 3 | e | 50/35 | 55/40 | Werte Quelle 1, Zuordnung Quelle 2 |
| MI | § 6 | c | 60/45 | 65/50 | Werte Quelle 1, Zuordnung Quelle 2 |
| GE | § 8 | b | 65/50 | 70/55 | Werte Quelle 1, Zuordnung Quelle 2 |
| GI | § 9 | a | 70/70 | 75/75 | Quelle 1 nennt 70 ohne Tag-/Nachtunterscheidung, Zuordnung Quelle 2 |
| WS | § 2 | offen | — | — | TODO(Owner): Zuordnung nicht belegt |
| WB | § 4a | offen | — | — | TODO(Owner): Zuordnung nicht belegt |
| MD | § 5 | offen | — | — | TODO(Owner): Zuordnung nicht belegt |
| MDW | § 5a | offen | — | — | TODO(Owner): Zuordnung nicht belegt |
| MU | § 6a | offen | — | — | TODO(Owner): keine automatische Gleichsetzung mit MI; Nutzungsmischung muss nach § 6a Abs. 1 nicht gleichgewichtig sein |
| MK | § 7 | offen | — | — | TODO(Owner): Zuordnung nicht belegt |

Es werden bewusst nicht die Werte der TA Lärm eingesetzt. Sondergebiete/Kurgebiete und
Einrichtungen nach AVV Nr. 3.1.1 f sind kein pauschaler zusätzlicher BauNVO-Gebietstyp in dieser
Auswahl; hierfür wäre eine eigene belegte Zuordnung erforderlich.

**Eingreifschwelle:** Nr. 4.1 gilt allgemein, nicht nur für WA: Der nach Nr. 6 ermittelte
Beurteilungspegel muss den Richtwert um **mehr als** 5 dB überschreiten. Vergleich ist `>`,
nicht `>=`. Die Soll-Regel und ihre Fremdgeräusch-Ausnahme bleiben im Text erkennbar.
Die Sekunden-Schwellenzeiten aus Schritt 4a sind deskriptive Kennwerte; sie weisen für sich
keinen behördlichen Maßnahmenanspruch nach. Ebenso ersetzt LAeq nicht automatisch den
Beurteilungspegel nach Nr. 6. Die zusätzliche Nachtgrenze ist Richtwert +20 dB, ebenfalls
strikt `>`, bezogen auf Messwerte nach Nr. 6.5 (keine pauschale rechtliche Einordnung jedes
einzelnen Rohframes).

## Implementierung und Owner-Entscheidung

- `laermbericht/areas.py`: unveränderliche Registry, belegte Richtwerte, Lookup ohne
  WA-Fallback, klare Fehler für unbekannte und ungeprüfte Kürzel.
- `apply_area_to_config` ersetzt Tagesrichtwert, Eingreif-Vergleichsgrenze und
  Messfenster-Schätzpegel gemeinsam. Letzterer folgt Auftrag 4b/3.4 und ist der
  Gebietstagesrichtwert. Teilerfassungs-Schätzpegel bleibt unabhängig konfigurierbar.
- `area_report_texts` liefert parameterisierte Bausteine; der tatsächliche PDF-Seitenport
  bleibt Schritt 4c. Keine Aussage, dass ein frei konfigurierter Schätzpegel zwangsläufig
  unterhalb eines Richtwerts liegt.
- **Owner am 18.09.2026: feste Auswahl.** `ReportAreaSelection` ersetzt das Freitextfeld.
  Ungeprüfte Typen sind sichtbar, markiert und nicht auswählbar. Ein bestehender unbekannter
  Text bleibt unverändert erhalten und verlangt eine bewusste Neuauswahl. Nur Kürzel werden
  normalisiert (Leerraum/Großschreibung), keine Vermutung aus langen Texten.
- Kotlin enthält nur Kürzel, Namen und Verfügbarkeit, keine zweite numerische Tabelle.
  Ein Python-Vertragstest prüft diese Metadaten gegen die Registry.
- Vorprüfung und JSON-Erzeugung lehnen ungeklärte Gebietseinstufungen vor dem Python-Aufruf
  ab. Keine neue Room-Spalte, keine Schemaänderung/Migration.
- Der freie Messfenster-Schätzregler entfällt, da sein Wert nach 4b/3.4 nicht mehr maßgeblich
  ist. Der Datenbank-Altwert wird nicht gelöscht und kann bis 4c noch im vorläufigen JSON
  stehen. Beim Anschluss muss `apply_area_to_config` verwendet werden.

## Rechtsprosa: bewusst offene Freigabe

Für **jeden** Typ, auch WA, enthält `qualification_todo` einen typbezogenen
`TODO(Owner)`-Platzhalter. Die technische Verfügbarkeit der fünf Wertpaare bedeutet keine
Freigabe einer gebietsspezifischen juristischen Argumentation. Standortbelege, behördliche
Bestätigung und konkrete rechtliche Begründung bleiben offen. Checkpoint: Review dieses PRs,
vor dem Anschluss der Rechtsseiten in Schritt 4c. Nicht-WA-Werte fehlen nur bei den sechs
ausdrücklich gesperrten Typen; deren Zuordnung darf auch durch einen Override nicht geraten werden.

## Vollständiges Inventar gebietsspezifischer Stellen im Original

Systematisch gesucht nach `WA|55 dB|40 dB|Wohngebiet` und ergänzend nach `RW_TAG_WA`,
`RW_NACHT_WA`, `EINGREIF_TAG`, `EINGREIF_NACHT`, `NACHT_SPITZE` und Zahlen 55/60/40/45.
Die Referenzdatei wird nicht verändert. In 4b sind Bausteine generalisiert; wo die Seiten
noch nicht portiert sind, ist der Anschluss ausdrücklich `TODO(Owner)` für den 4c-Review.

| Originalstellen (Zeilen) | Behandlung in 4b / verbindlicher Anschluss in 4c |
|---|---|
| Konstanten 47–54; `compute_day` 669–671, 763–780 | `AreaLimits`, `apply_area_to_config`; keine WA-Festwerte im neuen Gebietsmodul außerhalb der WA-Zeile |
| `page_cover` 936–942, 985, 1012, 1026 | `heading`, `guidelines`, Schwellenlabels; TODO(Owner): in 4c anschließen, Bestätigungsbehauptung Z. 985 durch `qualification` ersetzen |
| `page_juristische_kurzfassung` 1127–1128, 1143–1144, 1163–1181 | `guidelines`, `intervention`, Schwellenlabels; TODO(Owner): konkrete Rechtsargumentation/fallspezifische Kennzahlen vor Seitenübernahme prüfen |
| `_legal_p2` 1240–1267 | `heading`, `guidelines`, `intervention`, `night_peak`, `qualification`; TODO(Owner): Rechtsprosa freigeben, keine Bestätigung/kein Ausschluss anderer Gebietstypen aus Original übernehmen |
| `_legal_p3` 1307–1310, 1326 | TODO(Owner): fallbezogene Behauptungen und WAV-/Dauerlärmschwellen gehören nicht zur automatischen Gebietsbegründung |
| `page_berechnung_kennwerte` 1371–1397 | `window_estimate`, `partial_estimate`, `intervention`; TODO(Owner): 4c-Anschluss, Beurteilungspegel nicht pauschal LAeq nennen |
| `page_summary` 1440, 1447, 1502–1504, 1543 | Konfigurationswerte, Schätztexte, Schwellenlabels; TODO(Owner): 4c-Anschluss, belegtes Nacht-Baugeschehen nicht aus Pegel allein behaupten |
| `page_messaufbau` 1628 | TODO(Owner): WAV-Aufnahmeschwelle ist keine Gebietsschwelle; ausgeschlossene Klassifikationspipeline nicht übernehmen |
| `page_referenz` 1781 | TODO(Owner): fallspezifischer Vergleichstag bleibt außerhalb V1; keine WA-Textübernahme |
| `page_manifest` 1887 | `AreaLimits` statt globaler WA-Werte; TODO(Owner): 4c-Anschluss |
| `page_kernbefunde` 1922–1959 | Schwellenlabels und ausgewählte Werte; TODO(Owner): Dauerlärm/fallspezifische Aussagen gesondert prüfen, nicht über Gebietswahl legitimieren |
| `page_belastungsdauer` 2006–2029 | Schwellenlabels; TODO(Owner): 4c-Anschluss; nicht mit Dauerlärmkriterium ≥60 gleichsetzen |
| `page_lauteste_stunde` 2051–2098 | Schwellen-/Linienlabels; TODO(Owner): 4c-Anschluss |
| `page_referenz_vergleich` 2317–2322 | TODO(Owner): fallspezifischer Vergleich außerhalb V1; keine WA-Linien übernehmen |
| `page_day` 2520–2522, 2615–2634, 2769, 2784–2796, 2882–2914, 2955–2958 | Ausgewählte Werte, Schätz-/Linien-/Schwellenlabels; TODO(Owner): 4c-Anschluss; Schwellenüberschreitung allein nicht als abschließende Rechtsbewertung ausgeben |
| `page_day` 2985 | TODO(Owner): WAV-Schwelle gehört zur ausgeschlossenen Quellenklassifikation, kein Gebietsrichtwert |

Sonstige Suchtreffer sind Minuten-/Stundenumrechnung, Layoutmaße, Farben (`C_WARN`),
WAV-Dateinamen oder konkrete Original-Messdaten (`page_innenraum` 2133–2151,
`page_referenzpegel` 2184–2185). Sie sind **keine** zusätzlichen Gebietswerte und werden
nicht in die Registry übertragen. Insbesondere die festen Innenraum-/Videobelege des
Originalfalls dürfen im späteren PDF nicht als Messungen eines anderen Nutzers erscheinen.

Weitere beim Quellenabgleich erkannte Originalfehler: Nachtzeit ist **Nr. 3.1.2**, nicht
Nr. 3.2; Nr. 3.2 behandelt Gebietszuordnung. Die Formulierung „strengste Schutzklasse neben
reinen Wohngebieten“ und pauschale Handlungspflicht werden nicht übernommen. Die sekundäre
PDF-Abschrift der Städtebaulichen Lärmfibel enthält bei Kategorie c einen abweichenden
Text; maßgeblich ist hier ausdrücklich der amtliche Volltext aus Quelle 1.
