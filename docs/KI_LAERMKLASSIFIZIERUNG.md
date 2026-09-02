# Dokumentation: KI-Lärmklassifizierung (Baulärm-Erkennung)

> Stand: 2026-09-02. Beschreibt den KI-Umbau in drei Etappen (siehe Auftrag
> "Umbau der KI-Lärmklassifizierung"). **Etappe 1 und 2 sind auf `main` gemergt.
> Etappe 3 ist als Draft-PR #94 offen und wartet auf Verifikation auf echter
> Hardware** — dieses Dokument beschreibt den Zielzustand aller drei Etappen und
> markiert den Etappe-3-Teil entsprechend.
>
> Zielgruppen: **Teil A** richtet sich an alle, die die App nutzen oder ihre
> Ergebnisse verstehen wollen, ohne Programmierkenntnisse. **Teil B** ist die
> technische Referenz für Entwickler:innen. **Teil C** sammelt Fehlerquellen,
> Unschärfen und offene Punkte — bewusst nicht beschönigt, weil die App
> Beweismittel für ein laufendes Mietrechtsverfahren erzeugt.

---

> **Die Erklärung für Nutzer steht in der App, nicht hier.**
>
> Die laienverständliche Beschreibung („Wie die Lärmerkennung arbeitet") und die Darstellung der
> Unschärfen sind in den Bildschirm `ui/KiErklaerungScreen.kt` gewandert, erreichbar über das
> Menü und über den KI-Abschnitt der Einstellungen. Wer wissen will, was „Möglicher Baulärm ·
> 41 %" bedeutet, sitzt vor dem Telefon und nicht vor einem Git-Checkout.
>
> Was hier bleibt, ist die **technische Herleitung für Entwickler**: Datenfluss, Schwellen,
> Datenmodell und die Begründungen hinter den Entscheidungen. Sie gehört nicht in die App.

## Teil B — Technische Dokumentation

### B.0 Überblick über den Datenfluss

```
Mikrofon
   │  (Schwellwert überschritten → Aufnahme inkl. Pre-Roll)
   ▼
WAV-Datei (unverändertes Beweismittel, wird nie nachträglich verändert)
   │
   ▼
NoiseClassifier.leseUndKlassifiziere()
   │  liest WAV, normalisiert NUR eine Kopie für die Inferenz (Etappe 1.6),
   │  YAMNet-Inferenz über MediaPipe Tasks Audio (521 Klassen, ~1x/Frame)
   ▼
RohdatenBauplan  ──────────────────────────────────────────────┐
   │  Frame-Scores (quantisiert), Top-Kategorien, Impuls-Merkmale │
   ▼                                                              │
KlassifikationsRohdaten (Room-Tabelle, an NoiseRecord gekoppelt)  │
   │                                                              │
   ▼                                                              │
leiteLabelAb() ◄─────────────────────────────────────────────────┘
   │  REINE Funktion: Referenzabgleich → Gruppen-Score/Zeitaggregation
   │  → Impuls-Fusion → Einstufung
   ▼
BaulaermBefund  →  formatiereBaulaermBefund()  →  Anzeigetext
   │
   ▼
NoiseRecord.detectedLabel (der Text, den man in der App sieht)
```

Der entscheidende Architekturpunkt: **alles ab `KlassifikationsRohdaten`
abwärts ist reine, deterministische Berechnung ohne Zugriff auf Mikrofon,
Datei oder KI-Modell.** Wenn sich Schwellenwerte oder Logik ändern, muss
nicht neu aufgenommen oder neu durch YAMNet geschickt werden — "Neu
bewerten" wendet die aktuelle Logik einfach erneut auf die gespeicherten
Rohdaten an. Das ist der rote Faden durch alle drei Etappen.

Relevante Dateien (Kotlin, Paket `com.example.lrmprotokoll.audio` sofern
nicht anders angegeben):

| Datei | Zweck |
|---|---|
| `AudioRecordingService.kt` | Foreground-Service, Aufnahme, Trigger |
| `NoiseClassifier.kt` | YAMNet-Inferenz über MediaPipe, baut den `RohdatenBauplan` |
| `RohdatenKlassen.kt` | Die 23 gespeicherten YAMNet-Klassenindizes |
| `Baulaermgruppen.kt` | Kern/Kontext/Impuls-Gruppierung mit Gewichten |
| `GruppenScore.kt` | noisy-OR-Aggregation pro Frame |
| `Zeitaggregation.kt` | Median-Glättung, Hysterese, Blockerkennung |
| `BaulaermBefund.kt` | Ergebnisobjekt, Konfiguration, Ableitung, Formatierung |
| `LabelAbleitung.kt` | Referenzmuster-Abgleich + Einstiegspunkt `leiteLabelAb()` |
| `Impulsanalyse.kt` | YAMNet-unabhängige DSP-Analyse (Etappe 3) |
| `NeuBewerten.kt` | "Neu bewerten"-Funktion |
| `data/KlassifikationsRohdaten.kt` | Room-Entity für die Rohdaten |
| `data/NoiseRecord.kt` | Room-Entity für die Aufnahme selbst |
| `data/AppDatabase.kt` | Alle Room-Migrationen |

---

### B.1 Etappe 1 — Fundament: gehärtete Aufnahme, Rohdaten-Persistenz

**Ziel:** Bevor irgendetwas an der Erkennungslogik verändert wird, muss (a)
sichergestellt sein, dass die Aufnahme selbst dem Modell ein sauberes Signal
liefert, und (b) müssen die Rohscores der KI gespeichert werden, statt nur
das fertige Wort.

#### B.1.1 Aufnahmequelle und -qualität

`AudioRecordingService.startMonitoring()`:

- Bevorzugt `MediaRecorder.AudioSource.UNPROCESSED` (rohes Mikrofonsignal
  ohne jede Plattform-Vorverarbeitung), sofern
  `AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED == "true"` — sonst
  `MIC`. **Nie** `VOICE_RECOGNITION`/`VOICE_COMMUNICATION`/`CAMCORDER`, weil
  diese Quellen automatisch Rauschunterdrückung und automatische
  Verstärkungsregelung (AGC) aktivieren — beides zerstört genau die
  Pegeldynamik, an der man Impulslärm erkennt.
- Deaktiviert zusätzlich, wo verfügbar, explizit `AcousticEchoCanceler`,
  `NoiseSuppressor` und `AutomaticGainControl` auf Betriebssystemebene.
- Fordert 16.000 Hz Mono PCM-16 an; unterstützt das Gerät das nicht, wird die
  nächst **höhere** unterstützte Rate gewählt — nie eine niedrigere (YAMNet
  erwartet 16 kHz, niedrigere Raten würden hochfrequente Merkmale wie
  Winkelschleifer oder Rückfahrwarner unauffindbar machen).
- Alle vier tatsächlich verwendeten Werte (`aufnahmeQuelle`, `abtastrate`,
  `kanalzahl`, `agcAktiv`) werden pro Aufnahme in `NoiseRecord` gespeichert —
  im Beweiskontext muss nachvollziehbar bleiben, unter welchen Bedingungen
  eine Aufnahme entstand.

**Auf einem echten Gerät (Pixel 10 Pro XL) verifiziert:** `UNPROCESSED` wird
dort nicht unterstützt, die Härtung fällt korrekt auf `MIC` zurück; die
gewünschte Rate (16 kHz) wird erreicht; AEC und NS lassen sich deaktivieren,
AGC-Status ist auf diesem Gerät nicht feststellbar (`null`, kein geratenes
"funktioniert").

#### B.1.2 Rohdaten-Persistenz (`KlassifikationsRohdaten`)

Vor Etappe 1 wurde nur das fertige Label gespeichert. Jede Änderung an
Schwellenwerten hätte eine komplette Neu-Inferenz über den gesamten Bestand
erfordert. Seit Etappe 1 entsteht bei **jeder** Klassifizierung zusätzlich
ein Datensatz mit:

- `modellVersion` — Dateiname + CRC32-Hash von `yamnet.tflite`, damit
  erkennbar bleibt, falls sich das Modell zwischen App-Versionen ändert.
- `frameAnzahl`, `frameDauerMs` (960, unverifizierter Nominalwert),
  `frameHopMs` (**empirisch gemessen**, nicht angenommen — siehe B.3.1 für
  den Grund, warum das wichtig war).
- `klassenIndizes` + `frameScores` — quantisierte (`round(score·255)` als
  Byte) Rohwerte für 23 fest ausgewählte YAMNet-Klassen, **pro Frame**, nicht
  nur ein Gesamtwert.
- `topKlassen` — alle Kategorien einer Aufnahme über einer festen internen
  Schwelle (0,3), als einfacher Text kodiert (`Name:Score;Name:Score;...`),
  für den Referenzmuster-Abgleich.

Die 23 gespeicherten Klassen (`RohdatenKlassen.kt`) sind **gegen die
tatsächlich im Modell eingebettete Labelliste verifiziert** (nicht
angenommen) — dazu wurde das im `.tflite`-File als ZIP-Anhang mitgelieferte
`yamnet_label_list.txt` direkt ausgelesen.

#### B.1.3 Peak-Normalisierung (Etappe 1.6)

Aufnahmen durch ein geschlossenes Fenster sind leise; leise Signale drücken
alle YAMNet-Scores nach unten. Der Sample-Puffer wird **nur für die
Inferenz** auf einen Ziel-Peak von 0,95 normalisiert (echte bidirektionale
Normalisierung: leise Clips werden verstärkt, bereits volle Clips
heruntergeskaliert). Die auf der SD-Karte gespeicherte WAV-Datei bleibt
davon komplett unberührt — sie ist Beweismittel. Abschaltbar über die
Einstellung "Pegel vor der KI-Analyse normalisieren" (Default: an).

#### B.1.4 "Neu bewerten"

Overflow-Menü → "Neu bewerten": leitet für **alle** Aufnahmen mit
vorhandenen Rohdaten das Label neu ab, ohne eine einzige WAV-Datei zu lesen
oder eine neue KI-Inferenz zu starten. Altaufnahmen ohne Rohdaten (vor
Etappe 1 entstanden) werden dabei übersprungen, nicht angefasst — kein
Absturz, keine falschen Werte.

---

### B.2 Etappe 2 — Gruppen-Score und Zeitaggregation

**Problem, das diese Etappe löst:** YAMNet ist ein **Multi-Label-Sigmoid-
Modell**, keine Softmax-Klassifikation. Ein Presslufthammer verteilt sich
typischerweise auf mehrere Kategorien gleichzeitig
(`Jackhammer 0.14, Drill 0.11, Power tool 0.09, Tools 0.08, Engine 0.07`).
Eine "nimm die Kategorie mit dem höchsten Einzelwert und vergleiche mit
einer festen Schwelle"-Logik (der Stand vor Etappe 2) verwirft das komplett,
weil kein Einzelwert die Schwelle reißt — obwohl in Summe ein eindeutiges
Baustellensignal vorliegt.

#### B.2.1 Klassengruppen (`Baulaermgruppen.kt`)

23 gespeicherte Klassen werden in drei Gruppen mit Gewicht eingeteilt (5
weitere sind reine "Ausschluss"-Klassen wie Stille/Rauschen und fließen in
keine Gruppe ein):

| Gruppe | Gewicht | Klassen |
|---|---|---|
| **Kern** | 1,0 | Tools, Hammer, Jackhammer, Sawing, Sanding, Power tool, Drill |
| **Kontext** | 0,5 | Truck, Air brake, Reversing beeps, Engine, Chainsaw, Heavy engine (low frequency), Idling |
| **Impuls** | 0,3 | Explosion, Boom, Scrape, Crushing |

#### B.2.2 Gruppen-Score pro Frame (noisy-OR)

Nicht Summe (kann > 1 werden und verzerrt die Gewichtung), sondern:

```
gruppenScore(frame) = 1 − Π_i (1 − gewicht_i · score_i)
```

Wichtig: das rechnet direkt mit den **ungefilterten** `frameScores`, nicht
mit `topKlassen` (das war mit der alten Einheitsschwelle gefiltert) — ein
Frame knapp unter der alten 30-%-Schwelle wäre früher unsichtbar gewesen,
fließt hier korrekt ein.

#### B.2.3 Zeitaggregation

1. **Median-Glättung** über ein Fenster von 3 Frames — unterdrückt
   Frame-zu-Frame-Flackern (und filtert isolierte 1-Frame-Ausreißer
   vollständig heraus, siehe Teil C).
2. **Hysterese-Schwellung**: Einstieg erst bei `θ_ein = 0,50`, Ausstieg erst
   bei `θ_aus = 0,35`. Ein Score, der knapp um eine einzelne Schwelle
   pendelt, zerhackt einen zusammenhängenden Block sonst in viele
   Fragmente.
3. **Blockerkennung**: zusammenhängende Bereiche über der Hysterese-Schwelle
   werden zu Blöcken zusammengefasst → `blockAnzahl`, `laengsterBlockSekunden`
   (Frame-Anzahl × gemessener `frameHopMs`), `anteil` (Frame-Anteil über
   Schwelle), `gesamtBaulaermSekunden` (Summe **aller** Blöcke, nicht nur des
   längsten — Grundlage der Tagessummenzeile).
4. **Spitzenklasse**: innerhalb des stärksten Blocks die Einzelklasse mit dem
   höchsten Rohwert (nicht der Gruppen-Score) — "Spitze: Hämmern".

#### B.2.4 Einstufung

```kotlin
einstufung = wenn (anteil >= anteilFuerBaulaerm)         → BAULAERM
             sonst wenn (maxRohScore >= minimalerScoreFuerMoeglich) → MOEGLICH
             sonst                                        → KEIN_BAULAERM
```

mit Defaults `anteilFuerBaulaerm = 0,15`, `minimalerScoreFuerMoeglich =
0,20` — **eigene, im Auftrag nicht konkret vorgegebene Werte**, siehe Teil C.

#### B.2.5 Referenzmuster-Abgleich (unverändert aus Etappe 1)

Vor der obigen Logik wird geprüft, ob die erkannten `topKlassen` zu über 50 %
mit einem gelernten Referenzmuster überlappen ("Als Referenz lernen"). Ist
das der Fall, hat das Ergebnis **Vorrang** vor dem Gruppen-Score-Pfad — eine
konkrete, gelernte Quelle ist eine stärkere Aussage als eine generische
Kategorie. Dieser Mechanismus stammt unverändert aus der Zeit vor dem
KI-Umbau; Etappe 3 sollte ihn ursprünglich durch einen robusteren
Embedding-Vergleich ersetzen (siehe B.3.1 — das ist nicht passiert).

#### B.2.6 Konfigurierbare Schwellen

`SettingsManager.aiEinSchwelle` / `.aiAusSchwelle` (Settings-UI, zwei
Slider) — ersetzen die frühere pauschale "KI-Vertrauensschwelle"
(`aiConfidenceThreshold`, 30 % für jede Klasse), die bei einem
Sigmoid-Multilabel-Modell strukturell die falschen Klassen bevorzugte.

---

### B.3 Etappe 3 — Embeddings (nicht verfügbar) und Impulsanalyse

> **Status: Draft-PR #94, noch nicht gemergt.** Code ist fertig, Build/Tests/
> Lint sind grün, aber die Fusion-Schwellen sind — wie die Etappe-2-Schwellen —
> nicht an echten Presslufthammer-/Rüttelplatten-Aufnahmen verifiziert.

#### B.3.1 Warum es keinen Embedding-basierten Referenzabgleich gibt

Der Auftrag sah vor, den fragilen Label-Overlap-Referenzabgleich (B.2.5)
durch einen robusteren Vergleich von **Embeddings** (numerische
"Fingerabdrücke" eines Klangs, 1024-dimensional bei der TF-Hub-Variante von
YAMNet) zu ersetzen — Kosinus-Ähnlichkeit statt Mengenüberlappung.

Das wurde **geprüft, bevor etwas gebaut wurde**, mit zwei direkten, nicht
geratenen Befunden:

1. `yamnet.tflite` (die in dieser App eingebettete Modelldatei) wurde direkt
   auf Flatbuffer-Ebene inspiziert: Sie hat **genau einen** Output-Tensor
   (`tower0/network/layer32/final_output`, Form `[1, 521]` — nur die
   521 Klassen-Scores). Kein Embedding-Tensor, kein Spectrogram-Output,
   anders als die vom Auftrag beschriebene TF-Hub-Variante.
2. Die verwendete Bibliothek `com.google.mediapipe:tasks-audio:1.0.0`
   (bereits die **neueste verfügbare Version** — gegen Googles
   Maven-Metadaten geprüft) enthält **kein** `AudioEmbedder`-API überhaupt,
   nur `audioclassifier`.

Damit greift der im Auftrag selbst für genau diesen Fall vorgesehene
Fallback: **"Falls beides scheitert: melden und stoppen [...]. Dann entfällt
3.2/3.3."** Der bestehende Label-Overlap-Abgleich aus B.2.5 bleibt also der
einzige Referenzmechanismus — mit seinen in Teil C beschriebenen Schwächen.

**Nebenbefund**, der Etappe 1 rückwirkend bestätigt: Der Input-Tensor des
Modells hat feste Größe `[15600]` = 0,975 s bei 16 kHz **ohne Überlappung**.
Das erklärt, warum der auf echtem Gerät gemessene `frameHopMs` (~975 ms) so
deutlich von der allgemeinen YAMNet-Dokumentation ("0,96 s Frame, 0,48 s
Hop", vom Auftrag zitiert) abweicht: MediaPipe verarbeitet dieses konkrete
Modell nicht überlappend. Genau deshalb misst Etappe 1 `frameHopMs`
empirisch, statt einen Literaturwert zu übernehmen.

#### B.3.2 Impulsanalyse — eine zweite, YAMNet-unabhängige Meinung

`Impulsanalyse.kt`, reine DSP-Funktionen auf dem rohen (nicht
peak-normalisierten) Sample-Puffer:

1. **Hüllkurve**: RMS-Energie in nicht überlappenden 10-ms-Fenstern —
   reduziert das Signal auf eine grobe Energie-Zeitreihe.
2. **Crest-Faktor**: Spitze ÷ Effektivwert der Hüllkurve — hoch bei kurzen
   Ausschlägen vor ruhigem Hintergrund.
3. **Kurtosis** (Exzess-Kurtosis, Normalverteilung = 0): hoch bei wenigen
   Spitzen vor flachem Rest (Hammerschläge), niedrig bei gleichmäßiger
   Energie (Dauerton, Verkehrsrauschen).
4. **Wiederholrate**: Autokorrelation der Hüllkurve, dominanter Peak im
   Bereich 0,5–80 Hz (Presslufthammer typischerweise 8–25 Hz, manuelles
   Hämmern 0,5–3 Hz, Rüttelplatte höher).
5. **Peakschärfe** (eigene Ergänzung, im Auftrag nur als Feldname genannt):
   Verhältnis des stärksten Autokorrelations-Peaks zum **betragsmäßigen**
   Durchschnitt der durchsuchten Werte — ein Maß dafür, wie verlässlich die
   gefundene Rate ist. Weißes Rauschen hat keinen herausragenden Peak.

Diese fünf Werte werden pro Aufnahme in `KlassifikationsRohdaten`
gespeichert (`impulsCrest`, `impulsKurtosis`, `impulsWiederholrateHz`,
`impulsPeakSchaerfe`, `impulsMittlererPegel`).

#### B.3.3 Fusion

```
baulaerm = gruppenScore-Einstufung bereits BAULAERM
        ODER (kurtosis > 3,0 UND wiederholrateHz ∈ [5, 30] UND pegel > X)
```

Die Impuls-Regel greift **nur**, wenn der Gruppen-Score allein (noch) nicht
zu `BAULAERM` führt — sie kann eine Einstufung nur anheben, nie eine bereits
per Gruppen-Score erkannte wieder entwerten. Der Ratenbereich [5, 30] Hz ist
bewusst enger als der allgemeine Erkennungsbereich der Hüllkurvenanalyse
(0,5–80 Hz): er zielt gezielt auf maschinelle Taktraten
(Presslufthammer/Rüttelplatte), nicht auf das langsamere manuelle Hämmern,
das der Gruppen-Score-Pfad ohnehin abdeckt.

**Pegelquelle** (`pegel > X`): der kalibrierte PCE-323-Messwert, falls für
den Zeitraum vorhanden; sonst ersatzweise der relative Hüllkurven-Pegel aus
den Rohdaten. Aus Aufwandsgründen nutzt das **Live-Klassifizieren**
(online/Batch) immer den relativen Ersatzwert — der kalibrierte Wert des
zugehörigen `NoiseRecord` ist zum Inferenzzeitpunkt nicht ohne Weiteres
verfügbar. **"Neu bewerten"** dagegen holt den kalibrierten Wert, wenn
vorhanden, und ist damit die vollständigste Auswertung.

Ein per Impuls-Regel (statt Gruppen-Score) erkannter Baulärm wird in der
Anzeige gekennzeichnet: **"Baulärm (impulsiv, 12 Hz) · ..."**.

---

### B.4 Datenmodell (Room)

| Tabelle | Wichtige Felder | Seit |
|---|---|---|
| `noise_records` | `timestamp`, `filePath`, `detectedLabel`, `calibratedDbA`, `aufnahmeQuelle`, `abtastrate`, `kanalzahl`, `agcAktiv` | v1 / Etappe 1 (die vier letzten) |
| `klassifikations_rohdaten` | `recordId` (FK, CASCADE), `modellVersion`, `frameAnzahl`, `frameHopMs`, `klassenIndizes`, `frameScores`, `topKlassen`, `impulsCrest`, `impulsKurtosis`, `impulsWiederholrateHz`, `impulsPeakSchaerfe`, `impulsMittlererPegel` | Etappe 1 (Grundfelder), Etappe 3 (`impuls*`) |

Migrationshistorie relevant für diesen Umbau: `MIGRATION_12_13` (Etappe 1:
neue Tabelle + 4 Spalten auf `noise_records`), `MIGRATION_13_14` (Etappe 3:
5 `impuls*`-Spalten). Alle rein additiv, keine bestehende Spalte umbenannt
oder gelöscht, `fallbackToDestructiveMigration()` nirgends verwendet. Jede
Migration hat einen Migrationstest, der einen alten Datenbankstand simuliert
und prüft, dass Altdaten überleben.

### B.5 Alle konfigurierbaren Schwellen im Überblick

| Schwelle | Default | Einstellbar über | Datei |
|---|---|---|---|
| `aiEinSchwelle` (θ_ein) | 0,50 | Settings-UI | `SettingsManager.kt` |
| `aiAusSchwelle` (θ_aus) | 0,35 | Settings-UI | `SettingsManager.kt` |
| `anteilFuerBaulaerm` | 0,15 | nur Code | `BaulaermBefund.kt` |
| `minimalerScoreFuerMoeglich` | 0,20 | nur Code | `BaulaermBefund.kt` |
| `glaettungsFenster` | 3 Frames | nur Code | `BaulaermBefund.kt` |
| `impulsKurtosisSchwelle` (K) | 3,0 | nur Code | `BaulaermBefund.kt` |
| `impulsRateBereichHz` | 5–30 Hz | nur Code | `BaulaermBefund.kt` |
| `impulsPegelSchwelleDbA` (X, kalibriert) | 55,0 dBA | nur Code | `BaulaermBefund.kt` |
| `impulsPegelSchwelleRelativ` (X, Ersatzwert) | 0,05 | nur Code | `BaulaermBefund.kt` |
| `aiNormalisierung` | an | Settings-UI | `SettingsManager.kt` |

---

## Anhang: Glossar

| Begriff | Bedeutung |
|---|---|
| **YAMNet** | Vortrainiertes, allgemeines Audioklassifikationsmodell (521 Kategorien), läuft komplett offline auf dem Gerät. |
| **Frame** | Ein Zeitausschnitt (~0,975 s), für den YAMNet eine eigene Einschätzung abgibt. |
| **Rohdaten / `KlassifikationsRohdaten`** | Die quantisierten Frame-Scores, gespeichert statt nur des fertigen Ergebnisses — Grundlage für "Neu bewerten". |
| **Gruppen-Score** | Ein pro Frame berechneter Wert (0–1), wie stark baustellentypische Klassen in diesem Frame vertreten sind (noisy-OR-Kombination). |
| **Hysterese** | Zwei verschiedene Schwellen für "Einstieg" und "Ausstieg" in einen Zustand, um Flackern zu vermeiden. |
| **Einstufung** | Eines von vier Ergebnissen: BAULAERM, MOEGLICH, KEIN_BAULAERM, UNKLAR. |
| **Hüllkurve** | Grob gerasterter Energieverlauf eines Signals (hier: RMS in 10-ms-Schritten), Grundlage der Impulsanalyse. |
| **Kurtosis** | Statistisches Maß für "Spitzigkeit" einer Verteilung — hoch bei wenigen Ausreißern vor flachem Rest. |
| **Autokorrelation** | Vergleich eines Signals mit einer zeitlich verschobenen Kopie seiner selbst — findet periodische Muster. |
| **"Neu bewerten"** | Wendet die aktuelle Auswertungslogik auf alle gespeicherten Rohdaten erneut an, ohne Audiodatei oder KI-Inferenz. |
