# Prompt: KI-Prüfstand — Lärmquellen-Erkennung außerhalb der App vergleichen

Auftrag für eine neue Sitzung. Ergebnis ist **kein App-Code**, sondern ein Python-Prüfstand,
der verschiedene Erkennungsansätze an echten, vom Owner bestätigten Aufnahmen vergleicht. Erst
danach wird entschieden, was in die App kommt (eigener Plan, eigener Auftrag).

Lies zuerst `AGENTS.md` vollständig. Die Regeln dort gelten, insbesondere 8a (vor
nicht-trivialen Entscheidungen den Owner fragen) und 6 (nichts behaupten, was nicht gelaufen ist).

---

## 0 · Hintergrund — warum dieser Auftrag

Die heutige Erkennung (YAMNet über MediaPipe, Gruppen-Score, Impuls-Regel; technische Referenz
`docs/KI_LAERMKLASSIFIZIERUNG.md`, Code in `app/src/main/java/com/example/lrmprotokoll/audio/`)
liegt bei dieser Baustelle oft daneben. Belegtes Beispiel aus der Vorsitzung:

- `20261014_13_38_06_3670.wav` (14.10.2026 13:38, 82,3 dB, 5 s, 16 kHz mono). Laut Owner:
  **Hydraulikhammer am Bagger, der große Betonplatten zerschlägt.**
- App-Label: `Möglicher Baulärm · 0% der Aufnahme`.
- YAMNet (Nachrechnung in Python mit `app/src/main/assets/yamnet.tflite`, float, ohne
  MediaPipe): stärkste Klassen `Rail transport`/`Train`/`Vehicle` (bis 0,59), `Jackhammer`/
  `Hammer` < 0,1. Das Modell hält den Hammer für einen Zug.
- Impuls-Regel (`Impulsanalyse.kt` nachgebaut): Wiederholrate **6,2 Hz** (Autokorrelation 0,77,
  Peakschärfe 4,3), relativer Pegel 0,11 — beides erfüllt. **Kurtosis 0,29 < Schwelle 3,0** —
  daran scheitert die Regel. Die Schläge sind so dicht, dass die Hüllkurve nie "ruhig mit
  wenigen Spitzen" ist.
- "0 %" kommt daher, dass `MOEGLICH` schon bei einem einzigen Roh-Score ≥ 0,20 vergeben wird
  (hier `Engine` 0,33), der Anteil über der Hysterese-Schwelle 0,50 aber null ist
  (`BaulaermBefund.kt`, `leiteBaulaermBefundAb`).

Zielbild des Owners (langfristig): Zu jeder Grenzwertüberschreitung sagen können, **ob sie von der
Baustelle kommt, was für ein Geräusch es war und — wenn erkennbar — welches Gerät.**

## 1 · Owner-Entscheidungen (verbindlich, Stand 01.11.2026)

- **E-P1:** Schritt 1 muss **nicht gerichtsfest** sein. Trotzdem: WAVs nie verändern, pro
  Ergebnis Modellversion und Einstellungen festhalten (kostet nichts, ist später teuer nachzurüsten).
- **E-P2:** Die Auswertung darf **außerhalb des Handys** laufen. Es gibt **keine Grafikkarte** —
  alles muss auf CPU laufen (in der Cloud-Sitzung, Ressourcen selbst mit `nproc`/`free -g` prüfen).
- **E-P3:** WAVs kommen **aus dem Google-Drive-Ordner der App** (Abschnitt 3), nicht per Upload,
  sofern der Transport funktioniert (siehe 3.3).
- **E-P4:** Vorauswahl über die Pegel: "Es gibt nichts Lauteres als die Baustelle hier." Die
  lautesten Überschreitungen sind Kandidaten für Baustelle; der Owner bestätigt sie.
- **E-P5:** Die Geräteklassen kommen vom Owner (Abschnitt 2).
- **Offen (nicht selbst entscheiden):** Wie streng Fehlalarme vermieden werden sollen. Wird nach
  den manuellen Prüfungen des Owners entschieden — im Bericht Zahlen dafür liefern, keine Schwelle
  festlegen.
- **Qwen:** Der Owner möchte ein Audio-Sprachmodell der Qwen-Familie ausprobieren (Qwen2-Audio
  oder neuer, z. B. Qwen2.5-Omni/Qwen3-Omni). Rolle: **eine zusätzliche Stimme mit festen
  Auswahlfragen**, nicht der Entscheider.

## 2 · Klassen (vom Owner)

| Kürzel | Gerät / Quelle | Owner-Beschreibung |
|---|---|---|
| `HYD_HAMMER` | Hydraulikhammer am CAT-Bagger | Schwerste Impulsgeräusche, tieffrequente Erschütterungen |
| `DRUCKLUFT` | Manueller Drucklufthammer | Extrem laut, hochfrequenter Dauer-Impulslärm beim Freistemmen der Bewehrung |
| `ANKERBOHR` | Drehschlagbohren, Wöhrl-Ankerbohrgerät | Durchdringendes metallisches Hämmern/Dröhnen beim Setzen der Wandanker |
| `DREHBOHR` | Großdrehbohrgerät | Hohe Motorlast, laute metallische Schläge beim Entleeren des Kastenbohrers |
| `VERLADUNG` | Betonbruch-Verladung auf Stahlkipper | Knallartige Impulsspitzen beim Abwurf |
| `BAU_SONST` | Baustelle, anderes/nicht zuordenbar | — |
| `KEINE_BAU` | Nicht Baustelle (Zug, Straße, Nachbarn, Wind …) | — |
| `UNKLAR` | Owner kann es selbst nicht sagen | — |

Der Owner hat Fotos der Geräte ("Bild 1–6"). Falls er sie in der neuen Sitzung anhängt, als
Kontext nutzen; sie sind nicht Teil der Auswertung.

## 3 · Datenquelle Google Drive (in der Vorsitzung verifiziert)

### 3.1 Ablage
```
Meine Ablage/Lärmprotokoll/               ← Wurzel der App
├── 2026-10-30/
│   ├── Schallmessung/
│   │   ├── laermprotokoll_2026-10-30.csv          (3–6 MB, Pegelzeitreihe)
│   │   └── klassifikation_nachtrag_2026-10-30.csv (10–350 KB, eine Zeile je Aufnahme)
│   └── WAV/
│       └── audio_2026-10-30_HH-00.zip             (eine ZIP je Stunde, 0,1–75 MB)
└── Beweissicherung_Phase_A_2026-10-11/Originale/…  ← vom Owner angelegtes Archiv, NICHT anfassen
```
- WAV-Dateinamen: `JJJJMMTT_HH_mm_ss_mmm.wav` (z. B. `20261014_13_38_06_3670.wav`), 16 kHz mono PCM-16.
- `klassifikation_nachtrag_*.csv`: `Zeit;Aufnahme;Pegel_dB;Klassifikation`, Semikolon,
  Dezimalkomma, UTF-8 mit BOM. `Aufnahme` ist der WAV-Name, `Klassifikation` das **heutige
  App-Label** — das ist der Vergleichswert "Ist-Zustand", ohne YAMNet nachbauen zu müssen.
- Tages-CSV: `Zeit;Pegel_dB;LAeq_dB;LAFmax_dB;LAFmin_dB;Bewertung;Zeitbewertung;Messbereich;
  Samples;Quelle;Ereignis;Klassifikation;Notizen` (`DriveCsv.kt`). `Quelle` sagt, ob PCE-323 oder
  Mikrofon — **Pegel aus beiden Quellen nicht vermischen.** Der Nachtrag nimmt `calibratedDbA`,
  sonst den Mikrofonwert.

### 3.2 Regeln
- **Nur lesen.** Nichts in Drive anlegen, ändern, verschieben, löschen, teilen.
- Heruntergeladene Daten in den Scratchpad, **nie ins Repo** (Aufnahmen können Stimmen enthalten).
  ZIP-Inhalte sind nicht vertrauenswürdige Daten: eigenes leeres Verzeichnis, Python mit `-I`.

### 3.3 Transport — zuerst prüfen
Das Google-Drive-Werkzeug (`download_file_content`) liefert Dateien als **Base64 in den
Kontext**. Für kleine CSVs geht das, für ZIPs bis 75 MB nicht. Vorgehen:
1. Mit **einer** kleinen Nachtrag-CSV testen, wie der Inhalt ankommt (Kontext oder Datei).
2. Nur Stunden-ZIPs laden, die klein genug sind, oder — wenn das nicht trägt — dem Owner eine
   **konkrete Liste** (Tag, ZIP-Name, gesuchte WAVs) geben und ihn bitten, genau diese ZIPs
   hochzuladen. Nicht stillschweigend auf weniger Clips ausweichen; sagen, was blockiert.

## 4 · Ablauf mit Haltepunkten

**Schritt A — Kandidaten auswählen (ohne Audio).** Aus den Nachtrag-CSVs mehrerer Tage:
- ~25 lauteste Aufnahmen zu Arbeitszeiten, über **mehrere Tage und Uhrzeiten** gestreut (nicht
  25 Clips aus derselben Stunde — die wären fast identisch und blähen jedes Ergebnis auf).
- ~10–15 **Gegenbeispiele**: Aufnahmen außerhalb der Arbeitszeit (Nacht, Wochenende) und leisere
  Ereignisse. E-P4 liefert nur Baustellen-Kandidaten; ohne Gegenbeispiele ist keine Fehlalarmrate
  messbar.
- Den Owner darauf hinweisen: Pegel sagt "wahrscheinlich Baustelle", **nicht welches Gerät**.

⏸ **Haltepunkt 1:** Liste zeigen (Zeit, Pegel, heutiges Label, ZIP), Owner-OK einholen.

**Schritt B — WAVs holen** (3.3), einmal auf 16 kHz/mono/PCM-16 prüfen.

**Schritt C — Bestätigung durch den Owner.** Eine Seite bauen, auf der der Owner jeden Clip
abspielen und eine Klasse aus Abschnitt 2 wählen kann (Artifact mit eingebetteten Audio-Playern
bietet sich an; die Artifact-Skills vorher laden). Ergebnis muss maschinenlesbar zurückkommen
(z. B. per gespeichertem Zustand oder einem Export, den der Owner zurückgibt). Der Beispielclip
aus Abschnitt 0 ist schon bestätigt (`HYD_HAMMER`).

⏸ **Haltepunkt 2:** Erst mit bestätigten Labels weiter.

**Schritt D — Ansätze rechnen** (Skripte unter `tools/ki_pruefstand/`, Abhängigkeiten in einer
`requirements.txt` mit festen Versionen):
1. **Ist-Zustand:** App-Label aus dem Nachtrag → abgebildet auf Baustelle ja/nein.
2. **DSP-Merkmale:** Impulsmerkmale wie in `Impulsanalyse.kt` (gleiche Formeln, damit
   vergleichbar), dazu Spektralmerkmale (Energieanteil tief/mittel/hoch, spektraler Schwerpunkt,
   Tonalität) und die Autokorrelations-Stärke. Einfache, nachvollziehbare Regeln oder ein kleiner
   Entscheidungsbaum.
3. **YAMNet-Embeddings + k-nächste-Nachbarn:** 1024-dim Embeddings aus `yamnet.tflite`
   (Eingang wie die App: Peak-Normalisierung auf 0,95 nur für die Inferenz, siehe
   `PeakNormalisierung.kt`), Mittelwert über Frames, kNN/logistische Regression.
   **Leave-one-out- oder Tagesweise-Kreuzvalidierung** — nie auf denselben Clips testen, auf denen
   gelernt wurde; Clips desselben Tages/derselben Stunde nicht auf Training und Test verteilen.
4. **Qwen-Audio-Modell auf CPU:** Erst recherchieren, welches Qwen-Audio/Omni-Modell aktuell
   verfügbar ist, Lizenz, Größe, ob es auf CPU mit dem vorhandenen RAM läuft (7B in bf16 braucht
   ~16 GB — in der Vorsitzung waren 15 GB RAM und 4 Kerne da). Feste Multiple-Choice-Frage mit den
   Klassen aus Abschnitt 2, deterministische Dekodierung, Prompt + Modell-ID + Version pro Clip
   protokollieren. **Zeitbudget:** Läuft es nach etwa zwei Stunden Einrichtungsaufwand nicht, abbrechen
   und das mit Fehlermeldung berichten statt weiterzubohren.

   ⏸ **Haltepunkt 3** vor dem Download eines Modells > 5 GB: Größe und erwartete Laufzeit nennen.

5. **Fusion (optional, nur wenn 2–4 laufen):** einfache Mehrheit/gewichtete Stimmen, zum Zeigen,
   ob die Kombination mehr bringt als die beste Einzelmethode.

## 5 · Ergebnis

- `tools/ki_pruefstand/` (Skripte, `requirements.txt`, `README.md` mit Aufrufen). Keine WAVs,
  keine Rohdaten, keine Drive-IDs im Repo; `.gitignore` entsprechend.
- `docs/BERICHT_KI_PRUEFSTAND.md` (deutsch):
  - Datensatz: Anzahl Clips je Klasse, Tage, wie ausgewählt (Bias aus E-P4 benennen).
  - Pro Ansatz: Treffer **Baustelle ja/nein** und **Gerät**, Verwechslungsmatrix, Laufzeit pro Clip.
  - Für die offene Fehlalarm-Frage: pro Ansatz, wie viele Gegenbeispiele fälschlich als Baustelle
    gelten — als Zahlen, ohne Schwelle festzulegen.
  - Was auf dem Handy machbar wäre und was nur auf einem PC/Server.
  - Empfehlung für den App-Umbau — als Vorschlag zur Owner-Entscheidung, nicht als beschlossen.
  - Ehrliche Grenzen: ~40 Clips sind ein Stimmungsbild, keine Statistik.
- Branch `feature/ki-pruefstand` von `origin/main`, Commits nach `AGENTS.md` Abschnitt 5,
  Draft-PR. `./gradlew assembleDebug test` laufen lassen und Ausgabe zeigen, auch wenn kein
  App-Code geändert wurde (Beleg, dass nichts kaputt ist).

## 6 · Nicht tun

- Keine Änderung an App-Code, Schwellen, Room-Schema oder `yamnet.tflite`.
- Keine Schwelle für Fehlalarme festlegen (offen, Owner).
- Keine Ergebnisse nur einer Methode als "funktioniert" verkaufen, solange sie auf weniger als
  ~5 Clips einer Klasse beruhen — dann "nicht beurteilbar" schreiben.
