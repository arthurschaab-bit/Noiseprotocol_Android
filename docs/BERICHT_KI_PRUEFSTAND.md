# Bericht: KI-Prüfstand (Stand 02.11.2026)

Auftrag: `docs/PROMPT_KI_PRUEFSTAND.md` (Abschnitt 4). Es wurde **kein App-Code** geändert.
Skripte: `tools/ki_pruefstand/`. Alle Zahlen stammen aus einem Lauf auf 43 Clips, von denen der
Owner 37 bewertet hat (6 mal `UNKLAR`). Das ist ein **Stimmungsbild, keine Statistik**.

## 1 · Datensatz

- 43 Clips à 5 s (16 kHz, mono, PCM-16), 25 verschiedene Tage zwischen 14.09. und 29.10.2026.
- Auswahl (Schritt A): 25 laute Clips Mo–Fr 07–18 Uhr (je Tag und Stunde höchstens einer, je Tag
  höchstens drei), 6 laute Clips Mo–Fr 06–07 Uhr, 6 lauteste Nacht-/Wochenendclips (je ein Tag),
  6 leise Ereignisse zur Arbeitszeit. Zwei Clips fehlten in den Stundenzips und wurden durch den
  nächstpassenden ersetzt.
- **Bias aus E-P4:** Die Auswahl folgt dem Pegel. Die lauten Clips sind Baustellenkandidaten, die
  Gegenbeispiele sind es nur zum Teil. 8 der 9 Clips der Klasse `KEINE_BAU` stammen aus
  Nacht/Wochenende oder frühmorgens (vor 07 Uhr), nur einer (25.09., 07:08, 94,7 dB) aus der
  Arbeitszeit. Es gibt praktisch keine Gegenbeispiele wie Zug oder Straße zur Arbeitszeit. Die Fehlalarmzahlen unten sind deshalb eher zu günstig.
- Labels (Owner, blind, ohne App-Label): `VERLADUNG` 10, `BAU_SONST` 13, `KEINE_BAU` 9,
  `DREHBOHR` 2, `HYD_HAMMER` 2, `DRUCKLUFT` 1, `ANKERBOHR` 0, `UNKLAR` 6.
- **Annahmen:** Drei Clips ohne Klasse, aber mit Notiz („Bagger“, „Sonstiger Hammer“), werden als
  `BAU_SONST` gewertet. Der Nachtclip vom 13.10., 01:31, war zuerst als `DREHBOHR` gelabelt.
  Der Owner hält das für unplausibel (02.11.), der Clip zählt jetzt als `UNKLAR`. `UNKLAR` zählt in
  keiner Auswertung.
- **Gerät:** Nur `VERLADUNG` (10 Clips) hat genug Clips für eine Aussage. `HYD_HAMMER`,
  `DRUCKLUFT`, `DREHBOHR` (2) und `ANKERBOHR` haben weniger als 5 Clips: **nicht beurteilbar**.
  Die Geräte-Ebene wurde nur mit den drei Klassen `BAU_SONST`, `KEINE_BAU`, `VERLADUNG`
  bewertet (32 Clips).

## 2 · Ergebnisse

Bewertung: 37 Clips, davon 28 Baustelle und 9 Gegenbeispiele (`KEINE_BAU`). Lernende Ansätze
mit Leave-one-group-out, Gruppe = Kalendertag (25 Tage). Konfusionsmatrizen: Zeile = wahr,
Spalte = Vorhersage.

| Ansatz | Treffer ja/nein | Baustelle erkannt | Gegenbeispiele fälschlich „Baustelle“ | Laufzeit/Clip |
|---|---|---|---|---|
| Ist-Zustand streng (nur „Baulärm“) | 13 / 37 | 4 / 28 | **0 / 9** | – |
| Ist-Zustand weit (auch „Möglicher Baulärm“) | 19 / 37 | 10 / 28 | **0 / 9** | – |
| Nur Pegel (Kontrolle), Baum Tiefe 2 | 26 / 37 | 22 / 28 | 5 / 9 | – |
| DSP-Merkmale, Baum Tiefe 2 | 30 / 37 | 26 / 28 | 5 / 9 | ca. 4 ms |
| YAMNet-Embedding + kNN (k=3) | 29 / 37 | 27 / 28 | 7 / 9 | ca. 0,66 s (1) |
| YAMNet-Embedding + Logistische Regression | 32 / 37 | 27 / 28 | 4 / 9 | ca. 0,66 s (1) |

(1) Gemessen mit dem NumPy-Nachbau auf dem PC (9 Fenster je Clip), nicht auf dem Handy.

Konfusion ja/nein (ja = Baustelle):

| Ansatz | ja→ja | ja→nein | nein→ja | nein→nein |
|---|---|---|---|---|
| Ist-Zustand streng | 4 | 24 | 0 | 9 |
| Ist-Zustand weit | 10 | 18 | 0 | 9 |
| Nur Pegel | 22 | 6 | 5 | 4 |
| DSP-Baum | 26 | 2 | 5 | 4 |
| kNN | 27 | 1 | 7 | 2 |
| LogReg | 27 | 1 | 4 | 5 |

Gerät, drei Klassen (Reihenfolge `BAU_SONST`, `KEINE_BAU`, `VERLADUNG`), 32 Clips:

| Ansatz | Treffer | Matrix (Zeilen wahr) |
|---|---|---|
| kNN | 13 / 32 | [2,0,11], [6,2,1], [1,0,9] |
| LogReg | 13 / 32 | [3,2,8], [4,4,1], [4,0,6] |

Dreiklassen-Treffer von 13/32 (rund 41 %) liegen kaum über dem Raten (häufigste Klasse
`BAU_SONST` mit 13 von 32). Eine Aussage zum Gerät ist damit **nicht belegt**.

### Was auffällt

- **Die App lässt fast alles durch.** Der Ist-Zustand hat keinen Fehlalarm, erkennt aber nur 4
  (streng) bzw. 10 (weit) von 28 Baustellenclips. Auch die lautesten Clips (bis 105 dB) tragen
  „Kein Baulärm erkannt“.
- **Der DSP-Baum ist praktisch ein Pegelfilter.** Auf allen Daten gelernt wählt er nur
  `pegel_rel` (Wichtigkeit 1,0). Die Impuls- und Spektralmerkmale haben in diesem Datensatz keinen
  zusätzlichen Nutzen gezeigt.
- **Die Einzelwerte sind instabil.** Bei 9 Gegenbeispielen ist ein Unterschied von 1–2 Clips
  Rauschen, kein Unterschied zwischen den Methoden.
- Der Nachbau der Wiederholraten-Suche aus `Impulsanalyse.kt` ist wörtlich übernommen. Dabei
  fällt auf: `minLag = (100 Hz / 80 Hz).toInt() = 1` (Ganzzahlabschneidung), die Suche reicht also
  bis 100 Hz und bei glatten Hüllkurven liegt der Peak oft bei Lag 1. Beim Referenzclip ergibt
  der Nachbau 100 Hz statt 6,2 Hz. Das ist ein Hinweis auf eine mögliche Schwäche der Regel und
  wurde nicht weiter verfolgt.

## 3 · Qwen-Audio auf CPU: nicht gelaufen (Haltepunkt 3)

Es wurde nichts heruntergeladen. Gründe:

- Der PC hat 15,8 GB RAM, beim Lauf waren nur rund 2,0 GB frei (andere Programme). Ein 7B-Modell
  in bf16 braucht rund 16 GB und passt nicht.
- Die kurze Recherche nannte als kleinste Variante Qwen2.5-Omni-3B. Die Quellen widersprechen sich
  bei der Lizenz (3B laut VentureBeat nur nicht-kommerziell, 7B laut Model Card Apache-2.0),
  und für die CPU-Laufzeit fand ich keine belegte Zahl. Qwen2-Audio und Qwen3-Omni habe ich nicht
  belastbar recherchiert. Sources:
  [VentureBeat](https://venturebeat.com/ai/qwen-swings-for-a-double-with-2-5-omni-3b-model-that-runs-on-consumer-pcs-laptops),
  [Qwen2.5-Omni-3B](https://huggingface.co/Qwen/Qwen2.5-Omni-3B),
  [Qwen2.5-Omni-7B](https://huggingface.co/Qwen/Qwen2.5-Omni-7B).
- Ein Download über 5 GB braucht laut Auftrag die Freigabe des Owners. **Offen: Soll ein
  Modell geladen werden?** Dann zuerst Lizenz und Größe der 3B-Variante prüfen und RAM freimachen.

Die optionale Fusion wurde nicht gerechnet.

## 4 · Handy oder PC

- **Auf dem Handy machbar:** Ist-Zustand, DSP-Merkmale (rund 4 ms je Clip auf dem PC) und
  YAMNet-Embeddings. YAMNet läuft in der App ohnehin. Ein kNN oder eine logistische Regression
  auf den 1024 Embedding-Werten ist rechnerisch trivial.
- **Nur PC/Server:** Ein Qwen-Audio-Modell (mehrere GB RAM). Es wurde nicht gemessen.

## 5 · Vorschlag für den App-Umbau (Entscheidung beim Owner)

1. Die jetzige Regel erkennt zu wenig. Falls die Fehlalarmquote vorrangig ist, bleibt sie sicher,
   falls das Erkennen vorrangig ist, nicht. Dafür fehlt die offene Entscheidung zur
   Fehlalarmschwelle.
2. Ein Pegel-Gate (laut ⇒ Kandidat) allein erreicht in diesem Datensatz 26/37. Der DSP-Baum und die
   Embedding-Ansätze bringen 29–32/37. Der Unterschied ist zu klein, um ihn zu belegen.
3. Bevor ein Umbau geplant wird, braucht es mehr bestätigte Clips, vor allem Gegenbeispiele
   zur Arbeitszeit (Zug, Straße, Nachbarn) und mindestens 5 Clips je Gerät.

## 6 · Grenzen

- 43 Clips, 37 bewertet, 25 Tage: ein Stimmungsbild.
- Gegenbeispiele sind pegelbedingt leicht (siehe Bias).
- YAMNet wurde mit einem **NumPy-Nachbau** gerechnet, weil die Windows-Anwendungssteuerung die
  Binärbibliotheken von LiteRT und TensorFlow blockiert und Docker nicht startete. Der Nachbau
  rechnet die int8-Schichten mit Float-Requantisierung und kann um eine Quantisierungsstufe von
  TFLite abweichen. Validierung: Am Referenzclip aus dem Auftrag (14.10. 13:38) stimmen die
  stärksten Klassen (`Rail transport`/`Train` 0,59, `Vehicle` 0,50, `Engine` 0,33,
  `Jackhammer` unter 0,01) mit den Werten aus der Vorsitzung überein.
- Kein Fehlalarmschwellenwert wurde festgelegt.
