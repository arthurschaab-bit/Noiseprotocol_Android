# Prompt: M9 — UX-Überarbeitung nach Best Practices (Theming, Barrierefreiheit, Navigation, Zustände)

Für eine eigene Session, idealerweise **Antigravity** (Emulator/visuelles Feedback nötig, siehe
AGENTS.md §8) — reine Compose-/Ressourcen-Arbeit ohne BLE-Protokoll-Anteil.

**Zur Nummerierung:** M8 (Härtung, Plan Abschnitt 12) behält seine Nummer und seinen Platz. M9
und M10 sind vom Owner direkt eingeschobene Meilensteine wie schon M7b und M7c — sie stehen
*nach* M8 in der Nummerierung, sind aber **unabhängig von M8** und können vorher laufen. Sie
stehen nicht im Implementierungsplan; sie widersprechen ihm auch nicht.

Teil A ist die **Bestandsaufnahme** (was ist heute wie, und warum ist das ein Problem), Teil B
der **Umsetzungsauftrag**, Teil C die **offenen Owner-Entscheidungen**. Neue Funktionen stehen
bewusst nicht hier, sondern in [`docs/PROMPT_M10_FUNKTIONEN.md`](PROMPT_M10_FUNKTIONEN.md) — M9
repariert und vereinheitlicht, was da ist, M10 baut Neues.

---

## Teil A — Befunde

**Stand:** `main` @ `0bbb33e` (nach PR #46), gelesen am 2026-08-20. **Methodik:** reine
Code-Lektüre, **kein Emulator, kein Gerät** in dieser Umgebung. Jeder Befund ist deshalb
markiert:

- **[C]** — am Code belegt, Datei:Zeile steht dabei. Gilt unabhängig vom Gerät.
- **[G]** — Folgerung aus dem Code, die erst am Gerät/Emulator sichtbar oder messbar wird. Vor
  der Umsetzung einmal nachstellen, nicht ungeprüft glauben.

Die vier Befunde aus [`docs/BESTANDSAUFNAHME_UI.md`](BESTANDSAUFNAHME_UI.md) sind mit M7c
erledigt und stehen hier nicht noch einmal. Das hier ist die nächste Schicht darunter.

> **Hinweis zur Aktualität.** Diese Durchsicht wurde zunächst gegen `4c60f3e` (PR #38)
> geschrieben und danach vollständig gegen `0bbb33e` nachgeprüft, weil `main` inzwischen um
> PR #40, #43, #44, #45 und #46 weitergelaufen war. Zwei Dinge haben sich dadurch geändert:
> Der Live-Pegelverlauf, der ursprünglich als Vorschlag in M10 stand, ist mit PR #46 **bereits
> gebaut** (`ui/PegelverlaufChart.kt`) — der Vorschlag ist dort entsprechend gestrichen. Und aus
> PR #46 ist ein neuer, gewichtiger Befund entstanden (A1 unten), den es vorher nicht gab.

### A1 — Der Start-Screen rechnet die gesamte laufende Session alle 5 Sekunden neu

Der schwerwiegendste Befund dieser Durchsicht, neu seit PR #46.

**[C]** `MainActivity.kt:619–644` (`ServiceControl`) sammelt für das Live-Diagramm die Messwerte
der laufenden Session über `db.measurementDao().fuerSessionFlow(s.id)`. Diese Query ist
`SELECT * FROM measurements WHERE sessionId = :sessionId ORDER BY timestamp`
(`data/SessionDao.kt:53–54`) — **ohne `LIMIT`**, sie liefert also immer den kompletten
Datenbestand der Session.

Room stößt einen solchen Flow bei **jeder** Änderung an der Tabelle neu an. `MeasurementRecorder`
schreibt alle 5 Sekunden oder alle 50 Werte (`messreihe/MeasurementRecorder.kt:49–50`). Damit
wird während einer laufenden Überwachung etwa **alle 5 Sekunden die gesamte bisherige Messreihe**
neu aus SQLite gelesen — und in derselben Emission verarbeitet:

- `AkustischeKennwerte.berechne(geladeneMesswerte)` (`MainActivity.kt:626`), das **zwei
  vollständige Sortierungen** über alle Werte macht (`sortedBy { it.timestamp }` und
  `pegel.sorted()`, `messreihe/AkustischeKennwerte.kt:50,54`) plus je Wert ein `10.0.pow(...)`;
- `downsampleMesswerteFuerChart(messwerte, …)` (`MainActivity.kt:705–711`), das noch einmal über
  alle Werte läuft.

Beides steht im Rumpf eines `collectLatest` innerhalb eines `LaunchedEffect`, läuft also im
Kontext der Komposition — **auf dem Main-Thread**. `collectLatest` hilft hier nicht: Es bricht
den vorigen Rumpf nur an einer Suspendierungsstelle ab, und `berechne` suspendiert nie.

Der Aufwand je Durchlauf wächst linear mit der Sessiondauer, der Abstand zwischen den
Durchläufen bleibt konstant bei 5 Sekunden. Über eine ganze Session summiert sich das
quadratisch. Eine Nachtmessung von acht Stunden bei rund 2 Werten/s ergibt etwa 57.000 Zeilen,
die dann alle 5 Sekunden gelesen, in Objekte verwandelt, zweimal sortiert und zweimal
durchlaufen werden — auf dem Start-Screen, dem Startziel der App.

**[C]** `ProtokollDetailScreen.kt:111–120` hat seit demselben PR exakt dasselbe Muster für die
Detailansicht.

**[G]** Wie stark das ruckelt und wie viel Akku es kostet, ist am Gerät zu messen — mit einer
über Stunden gelaufenen Session, nicht mit einer frisch gestarteten. Der Code sagt, dass es
passiert; wie schlimm es ist, sagt nur die Messung.

Die Funktion ist richtig und soll bleiben. Falsch ist nur der Weg, auf dem die Daten kommen.

### A2 — Theming: die App ist immer hell, auch im Dunkelmodus

**[C]** `MainActivity.kt:76` ruft `MaterialTheme { … }` ohne `colorScheme` auf. Der Default ist
`MaterialTheme.colorScheme`, also `LocalColorScheme.current`, und dessen Startwert ist
`lightColorScheme()`. Über dem Aufruf steht kein weiteres `MaterialTheme` — die App rendert
damit **immer im hellen Farbschema**, egal was das System sagt. Es gibt im ganzen Projekt keine
`Theme.kt`, kein `darkColorScheme()`, kein `isSystemInDarkTheme()`, kein `dynamicColorScheme`
(geprüft: 0 Treffer in `app/src/main/java/`).

Das XML-Theme `Theme.Laermprotokoll` erbt zwar von `Theme.Material3.DayNight.NoActionBar`
(`res/values/themes.xml`, `res/values-night/themes.xml` — beide leer, nur der Parent), aber das
steuert nur Fensterhintergrund und Systemdekor, nicht die Compose-Farben. **[G]** Im
Dunkelmodus ist deshalb ein dunkler Fensterhintergrund unter einer hellen Compose-Oberfläche zu
erwarten — beim Start als Aufblitzen, an den Rändern dauerhaft.

Für eine App, die nachts läuft und deren Bedienung nachts stattfindet (Lärmprotokoll bei
nächtlicher Ruhestörung), ist „kein Dunkelmodus" nicht kosmetisch.

**[C]** Dazu **30 hartcodierte Farbliterale** im Produktivcode — vor PR #46 waren es 17, die
neuen Dateien `PegelverlaufChart.kt` und `BluetoothStatusBadge.kt` haben den Bestand vergrößert,
nicht verkleinert. Beispiele: `Color.Red` (`MainActivity.kt:681,684`), `Color(0xFF4CAF50)`
(Start-Button, `MainActivity.kt:775`), `Color(0xFF2E7D32)` (Live-Punkt, `MainActivity.kt:735`),
`Color(0xFFFFB300)` (`MainActivity.kt:842`), `Color.Gray` / `Color(0xFF1976D2)` /
`Color(0xFFFFA000)` / `Color(0xFFD32F2F)` (Verbindungszustände, `MeterScreen.kt:450–459`). Diese
Werte kennen kein Farbschema: Sie bleiben im Dunkelmodus, wie sie sind, und ihr Kontrast gegen
`surfaceVariant`/`background` ist nirgends geprüft.

Der Zuwachs ist das eigentliche Argument: Ohne Farbtokens wächst der Bestand mit jedem neuen
Screen weiter.

### A3 — Texte: keine einzige String-Ressource

**[C]** `stringResource` kommt im gesamten Produktivcode **null mal** vor. `res/values/strings.xml`
enthält genau einen Eintrag (`app_name`). Sämtliche UI-Texte stehen als deutsche Literale im
Kotlin-Code.

Folgen, unabhängig von jedem Lokalisierungswunsch:

- Dieselbe Zeichenkette existiert mehrfach und driftet auseinander — `"Zurück"` steht sechsmal
  einzeln da (`AudioPlayerScreen.kt:77`, `DiagnoseScreen.kt:105`, `MeterScreen.kt:168`,
  `ProtokollDetailScreen.kt:155`, `ProtokollScreen.kt:52`, `SettingsScreen.kt:150`).
- Pluralformen sind handgebaut (`"Ausfälle (${ausfallbaender.size})"`), `plurals` gibt es nicht.
- `app_name` ist `Laermprotokoll` (ohne Umlaut) — im Launcher steht damit ein anderer Name als
  in der App selbst („Lärmprotokoll", `MainActivity.kt:307`).

**[C]** Und eine Sprachmischung, die direkt Barrierefreiheit betrifft: `MainActivity.kt:838`
setzt `contentDescription = "AI Recognition"` in einer sonst durchgehend deutschen Oberfläche —
TalkBack liest das auf einem deutschen Gerät als englischen Text vor.

### A4 — Barrierefreiheit

**[C]** **Antippbare Fläche von 16 dp.** `MainActivity.kt:399`: das „Entfernen"-Kreuz an den
Chips der gelernten Geräusche ist `Icon(… Modifier.size(16.dp).clickable { … })`. Das ist ein
16-dp-Ziel für eine **löschende** Aktion — Material fordert mindestens 48 dp, und die Aktion
löscht ohne Nachfrage und ohne Rückgängig (`dao.deleteReference(ref.id)` direkt im `onClick`).

**[C]** **Nicht gespiegelter Zurück-Pfeil.** `AudioPlayerScreen.kt:8,77` verwendet seit dem
Umbau in PR #46 wieder `Icons.Default.ArrowBack` statt `Icons.AutoMirrored.Filled.ArrowBack`,
das die anderen fünf Screens benutzen. Das Manifest deklariert `android:supportsRtl="true"` —
in einer RTL-Sprache zeigt der Pfeil dann in die falsche Richtung. Nebenbei ist das Symbol
zugunsten der AutoMirrored-Variante als veraltet markiert.

**[C]** **Kein `liveRegion` auf dem Live-Pegel.** Der Wert in `MeterScreen.kt:228–234`
(`displayLarge`), die Dashboard-Zeilen in `ServiceControl` und das neue Live-Diagramm ändern sich
laufend, ohne dass ein Screenreader etwas davon mitbekommt. Das Diagramm selbst ist für TalkBack
eine leere Fläche — ein `contentDescription` mit der Kernaussage („Pegelverlauf, aktuell X dB,
Höchstwert Y dB, N Ausfälle") wäre der Mindestersatz.

**[C]** **Statusfarbe teils ohne Textbegleitung.** `MeterScreen.connectionStateDisplay()` macht
es richtig — Icon *und* Text *und* Farbe, das KDoc sagt das auch ausdrücklich. `ServiceControl`
(`MainActivity.kt:671–688`) macht es fast richtig (roter Punkt + Text „AKTIV"), aber der Punkt
selbst hat kein `contentDescription` und die Farbe ist `Color.Red` statt eines Schema-Tokens.
Der neue grüne Live-Punkt (`MainActivity.kt:731–738`) trägt gar keine Textentsprechung — „live"
ist dort ausschließlich über die Farbe kodiert.

**[G]** Verhalten bei großer Systemschrift und bei `fontScale ≥ 1.5` ist nirgends geprüft.
Kandidaten für Abschneiden: `displayLarge` für den Pegel, die fünf `NavigationBarItem`-Labels
(„Einstellungen" ist lang), die Chip-Reihe in `NoiseRecordItem`, und die neuen
Achsenbeschriftungen im Diagramm, die mit fester `sp`-Größe gezeichnet werden.

### A5 — Navigation und Informationsarchitektur

**[C]** **Zurück-Pfeile auf Top-Level-Zielen.** `meter`, `protokoll`, `diagnose` und `settings`
sind seit M7c Einträge der `NavigationBar` (`MainActivity.kt:187–227`) — und tragen gleichzeitig
alle einen `TopAppBar`-Zurück-Pfeil mit `popBackStack()`. Material ist da eindeutig: Ein
Top-Level-Ziel hat keine Up-Navigation. Zusammen mit `navigiereZuTab()`s
`popUpTo("main") { inclusive = … }` (`MainActivity.kt:112–121`) landet der Pfeil außerdem
nicht zuverlässig dort, wo der Nutzer herkam.

**[C]** **Die NavigationBar erscheint auch auf Detailseiten.** Sie umschließt in `AppNavigation`
den gesamten `NavHost` — auch `player?path={path}` und `protokoll/{sessionId}`. Für
`protokoll/{sessionId}` ist das über `istBottomNavZielAktiv()` sauber gelöst (Präfix-Vergleich,
„Protokoll" bleibt markiert). Für den **Player** ist es das nicht: dessen KDoc sagt selbst „passt
bewusst zu keinem Tab" — es steht dann eine Leiste ohne jede Markierung unter einer
Vollbild-Wiedergabe.

**[C]** **Der Start-Screen ist der einzige ohne `TopAppBar`.** `NoiseProtocolApp` baut
stattdessen eine eigene `Row` mit `headlineMedium` (`MainActivity.kt:306–315`). Damit springt
die Kopfzeile beim Tabwechsel in Höhe und Stil. Das neue `BluetoothStatusBadge`
(`MainActivity.kt:310`) hängt genau in dieser Sonderkonstruktion und ist deshalb auf allen
anderen Screens nicht zu sehen — obwohl der Verbindungszustand dort mindestens genauso relevant
ist.

**[C]** **Icon-Semantik.** `Icons.Default.Refresh` steht gleichzeitig für den Tab „Messgerät"
(`MainActivity.kt:205`), für „KI-Erkennung starten" (`MainActivity.kt:838`) und für die
Verbindungszustände SCANNING/CONNECTING/RECONNECTING (`MeterScreen.kt:451–457`).
`Icons.Default.Star` steht für „Geräusch lernen" (`MainActivity.kt:842`), was jeder als „Favorit"
liest. `Icons.Default.Menu` steht für „Filter" (`MainActivity.kt:471`). `Icons.Default.Info` ist
der Tab „Diagnose" — die Bestandsaufnahme hat genau das schon einmal als „nicht selbsterklärend"
notiert; der Text daneben behebt es halb, das Icon bleibt falsch.

### A6 — Zustände: leer, lädt, Fehler, Erfolg

**[C]** **Kein Leerzustand auf dem Start-Screen.** Ohne Aufnahmen zeigt `NoiseProtocolApp` die
Statuskarte, die Filterkarte und darunter eine leere `LazyColumn` (`MainActivity.kt:425`). Beim
allerersten Start ist das die gesamte App. `ProtokollScreen.kt:59–64` macht es dagegen
vorbildlich (erklärender Satz statt Leere) — das ist das zu übernehmende Muster.

**[C]** **Kein `SnackbarHost` in der gesamten App.** Rückmeldung gibt es an genau einer Stelle,
als `Toast` (`MainActivity.kt:765`, „Berechtigung erforderlich"). Alles andere quittiert gar
nicht: CSV-Export, PDF-Export, ZIP-Teilen, „Server übernehmen", „Ping-URL übernehmen", Label
setzen, Referenz löschen.

**[C]** **Exporte laufen ohne Fortschritt und ohne Ergebnis.** `ProtokollDetailScreen.kt:232–247`
startet CSV/PDF in einer Coroutine und ruft danach `export.teilen(datei)`. Bei einer langen
Session passiert zwischen Tap und Share-Dialog sichtbar nichts, und der Button bleibt aktiv und
mehrfach drückbar.

**[C]** **Fehler sind Sackgassen.** Scanfehler (`MeterScreen.kt:345–348`), Drive-Fehler
(`SettingsScreen.kt:487–490`), Player-Fehler — alles roter Text ohne Wiederholen-Aktion.

**[C]** **„Lädt…" als nackter Text.** `ProtokollDetailScreen.kt:170`, während im Hintergrund der
in A1 beschriebene Vollbestand geladen wird.

### A7 — Berechtigungen und Erstkontakt

**[C]** `MainActivity.kt:288–298`: beim allerersten Frame (`LaunchedEffect(Unit)`) fragt die App
in einem Schwung `RECORD_AUDIO`, `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT` und `POST_NOTIFICATIONS`
ab — ohne einen Satz Erklärung davor, ohne dass der Nutzer bis dahin irgendetwas von der App
gesehen hat, und ohne dass Bluetooth zu diesem Zeitpunkt überhaupt gebraucht wird (das erste
Koppeln passiert auf `MeterScreen`).

**[C]** Wird abgelehnt, gibt es keinen Weg zurück: `hasPermissions` wird nur in diesem einen
`LaunchedEffect(Unit)` neu gesetzt — kehrt der Nutzer aus den Systemeinstellungen zurück,
aktualisiert sich der Zustand nicht (kein `ON_RESUME`-Lifecycle-Beobachter). Der Start-Button
antwortet dann dauerhaft mit einem Toast. `MeterScreen.kt:97–100` liest die
Bluetooth-Berechtigungen sogar in einem `remember { }` **ohne Key** — einmal pro Komposition,
danach nie wieder.

**[C]** Kein Onboarding. Nichts erklärt beim ersten Start, dass es zwei Betriebsarten gibt
(Mikrofon unkalibriert vs. PCE-323 kalibriert) — genau die Unterscheidung, an der die gesamte
Aussagekraft des Protokolls hängt.

### A8 — Layout- und Scroll-Risiken

**[C]** Das Muster, das M7c für `MeterScreen` behoben hat, **steht auf dem Start-Screen noch**:
`MainActivity.kt:305` ist ein `Column(Modifier.padding(16.dp))` ohne `verticalScroll`, das
Titelzeile, Statuskarte, Filterkarte (aufklappbar, mit zwei `RangeSlider`), die `FlowRow` der
gelernten Geräusche (unbegrenzt viele Chips) und darunter die `LazyColumn` trägt
(`MainActivity.kt:425`). Der feste Kopfbereich ist seit PR #46 **weiter gewachsen** — die
Statuskarte enthält jetzt zusätzlich das 140 dp hohe Live-Diagramm plus Überschriftzeile
(`MainActivity.kt:702–755`). **[G]** Auf kleinen Displays ist zu erwarten, dass die Aufnahmeliste
auf wenige Zeilen zusammenschrumpft oder ganz verschwindet — und weil der Kopf nicht scrollt,
kommt man nicht dran. Am Emulator mit laufender Session, ~15 gelernten Geräuschen und
aufgeklapptem Filter nachstellen.

**[C]** Zwei ineinandergeschachtelte `Scaffold` auf jedem Nicht-Start-Screen (außen
`AppNavigation`, innen der Screen). Funktioniert, aber die Insets werden zweimal angefasst.

**[C]** `enableEdgeToEdge()` (`MainActivity.kt:73`) und direkt darunter
`windowInsetsPadding(WindowInsets.safeDrawing)` auf der Wurzel-`Surface`
(`MainActivity.kt:80`). Das eine schaltet Edge-to-Edge ein, das andere nimmt es wieder zurück:
die `NavigationBar` kann nicht hinter die Systemleiste zeichnen. **[G]** Sichtbar als
farbloser Streifen unter der Navigationsleiste. Bei `targetSdk = 36` ist Edge-to-Edge nicht mehr
optional, die halbe Umsetzung ist es aber auch nicht.

### A9 — Einstellungen

**[C]** `SettingsScreen.kt` ist ein einziger `verticalScroll`-`Column` (`:160`) über **692
Zeilen** mit sechs durch `HorizontalDivider` getrennten Abschnitten (Aufnahme, KI, Alarmierung,
Drive, Diagnose-Log, Akku). Kein Suchfeld, keine Unterseiten, keine Sprungmarken, keine
Zusammenfassung je Abschnitt („Alarmierung: aktiv, ntfy konfiguriert"). Der wichtigste Schalter
der App — Alarmierung bei Verbindungsabbruch — liegt hinter vier Slidern.

**[C]** **Zwei Bestätigungsmodelle nebeneinander.** Slider und Switches wirken sofort;
`ntfyServer` und `heartbeatUrl` brauchen einen Button („Server übernehmen"
`SettingsScreen.kt:318`, „Ping-URL übernehmen" `SettingsScreen.kt:386`). Wer den Screen mit
getipptem, nicht übernommenem Text verlässt, verliert die Eingabe kommentarlos — und die
Totmannschaltung bleibt still aus.

### A10 — Daten, die die App hat und nirgends zeigt

**[C]** Der inhaltlich stärkste Befund. `NoiseRecord` trägt seit M4 die Felder `calibratedDbA`,
`meterWeighting` und `meterConnected` (`data/NoiseRecord.kt:26–28`) — also genau das, wofür das
gesamte PCE-323-Vorhaben gebaut wurde. Gesucht in `ui/` und `report/`: **null Treffer.**
Geschrieben werden sie in `AudioRecordingService`, gelesen nirgends. Auch nach PR #46 nicht.

Konkret heißt das:

- Die Aufnahmeliste zeigt `"Pegel: … dB (Amp: …)"` (`MainActivity.kt:828`) — den
  **unkalibrierten** Mikrofonwert plus eine rohe Amplitude, die für niemanden eine Bedeutung
  hat. Ob das Messgerät verbunden war, ob ein kalibrierter Wert existiert und wie er lautet:
  nicht sichtbar.
- Der Tagesbericht (`report/ReportManager.kt:27–37`) schreibt Zeit, unkalibrierten Pegel,
  Amplitude, Label, KI-Label. Kein Messgerätewert, kein Gerätename, kein Hinweis auf den
  Kalibrierungsstand. Für ein Dokument, das Lärm belegen soll, ist „Amplitude: 8412" wertlos
  und „Pegel: 74,3 dB" ohne Quellenangabe irreführend.

Das ist kein Schönheitsfehler, sondern der Punkt, an dem die App ihre eigene Kernaussage
verschweigt.

### A11 — Kleinkram, verifiziert

- **[C]** `String.format("%.1f", …)` ohne `Locale` an vier Stellen (`MainActivity.kt:828`,
  `MeterScreen.kt:230,232`, `AudioRecordingService.kt:373`), während `SettingsScreen`,
  `ProtokollDetailScreen`, `PegelverlaufChart` und der neue LAeq-Text auf dem Start-Screen
  durchgehend `Locale.getDefault()` mitgeben. Auf einem deutschen Gerät ist das der Unterschied
  zwischen „74,3" und „74.3" — im neuen Dashboard sogar **in derselben Karte übereinander**.
- **[C]** `AssistChip(onClick = {})` als reine Anzeige des aktiven dB-Filters
  (`MainActivity.kt:344`): sieht antippbar aus, tut nichts. Naheliegend wäre „tippen setzt den
  Filter zurück".
- **[C]** Die vier Label-Chips sind fest verdrahtet: `listOf("Bagger", "Bohren", "Hämmern",
  "Verkehr")` (`MainActivity.kt:854`) an **jeder** Aufnahmezeile. Kein eigenes Label, kein
  Entfernen eines gesetzten Labels, keine Rückmeldung beim Setzen.
- **[C]** `SimpleDateFormat` wird pro Datensatz neu gebaut — innerhalb des `groupBy`-Lambdas
  (`MainActivity.kt:421`). `ProtokollScreen`/`ProtokollDetailScreen` machen es richtig
  (`remember { }`).
- **[C]** `res/layout/activity_main.xml` wird nirgends referenziert (`R.layout`: 0 Treffer) —
  Rest aus der View-Zeit.
- **[C]** Löschen ist überall endgültig. Der Bestätigungsdialog (`MainActivity.kt:564–590`) ist
  gut, aber es gibt kein Rückgängig, und die Mehrfachauswahl per Long-Press
  (`MainActivity.kt:495–498`) ist nirgends angekündigt und hat keine kontextuelle App-Bar.
- **[C]** Compose-BOM `2024.05.00` (`app/build.gradle.kts:101`) bei compileSdk 36 / Kotlin 2.2 —
  siehe Teil C, Entscheidung 3.

### A12 — Was den Testrahmen dieses Meilensteins verändert hat

**[C]** Die instrumentierte UI-Test-Suite existiert nicht mehr. `app/src/androidTest/…/ui/` ist
mit PR #43–#46 vollständig entfernt worden (übrig ist nur `ExampleInstrumentedTest.kt`), und
`.github/workflows/emulator-tests.yml` ebenfalls. `docs/TESTPLAN_INSTRUMENTIERT.md` beschreibt
damit einen Stand, den es nicht mehr gibt.

Für M9 heißt das: **Absicherung läuft über die Robolectric-Compose-Tests** in
`app/src/test/…/ui/*ComposeTest.kt`, die es weiterhin gibt und die in der normalen `test`-Suite
mitlaufen. Nicht versuchen, die gelöschte androidTest-Suite wiederzubeleben — ob und wie sie
zurückkommt, ist eine eigene Entscheidung und nicht Teil dieses Meilensteins.

---

## Teil B — Der Auftrag

```text
Du setzt M9 um, einen vom Owner direkt beauftragten UX-Meilenstein (kein Plan-Kapitel).

PROJEKT
Android-App "Lärmprotokoll" (com.example.lrmprotokoll), Kotlin + Compose + Room.
Repository: arthurschaab-bit/Noiseprotocol_Android, Default-Branch: main.
Branch für diese Arbeit: feature/m9-ux-grundlagen

ZUERST LESEN
1. docs/PROMPT_M9_UX.md Teil A — die Befunde, vollständig. Jeder Befund trägt Datei:Zeile.
   ACHTUNG: Die Zeilenangaben beziehen sich auf main @ 0bbb33e. Ist main weitergelaufen,
   die Stellen über den zitierten Codeschnipsel wiederfinden, nicht blind der Zeilennummer
   folgen.
2. docs/BESTANDSAUFNAHME_UI.md — der Stand VOR M7c, damit nichts doppelt entsteht
3. docs/PROMPT_M7C.md — was M7c bereits gebaut hat
4. docs/PROMPT_UMSETZUNG.md Abschnitt B — Arbeitsregeln, gelten unverändert
5. AGENTS.md — vollständig

REIHENFOLGE IST TEIL DES AUFTRAGS
Aufgabe 1 (Datenpfad) zuerst: sie ist die einzige, die ein reales Laufzeitproblem behebt, und
sie fasst genau die Stellen an, die die anderen Aufgaben später umgestalten. Dann Aufgabe 2
(Theme) — wer Farben in Screens anfasst, bevor es ein Farbschema gibt, schreibt die
hartcodierten Literale nur um statt sie loszuwerden. Dann Aufgabe 3 (Strings), aus demselben
Grund: jede Textänderung ohne Ressourcen-Basis ist Doppelarbeit. Aufgaben 4-9 danach in
beliebiger Reihenfolge.

=== AUFGABE 1: Den Datenpfad des Live-Diagramms reparieren (Befund A1) ===

Die Funktion aus PR #46 bleibt, der Weg der Daten ändert sich.

- ServiceControl darf nicht mehr bei jeder Messwert-Schreibung die komplette Messreihe der
  Session neu laden und neu durchrechnen. Zwei gangbare Wege, beide zulässig:
  (a) Ringpuffer im Speicher, gespeist aus dem laufenden Pegel-Flow (container.meterTransport
      bzw. dem Mikrofonpegel) — dann braucht das Live-Diagramm die Datenbank gar nicht mehr.
      Bevorzugt, weil es die Query komplett einspart.
  (b) Wenn die Datenbank die Quelle bleiben soll: begrenzte Query (nur das angezeigte
      Zeitfenster, mit LIMIT), und ein inkrementell fortgeschriebener Kennwert statt einer
      Neuberechnung über den Gesamtbestand.
- In beiden Fällen: Die Berechnung (AkustischeKennwerte.berechne, downsample*) gehört auf
  Dispatchers.Default, nicht in den Kompositionskontext.
- ProtokollDetailScreen (ProtokollDetailScreen.kt:111-120) hat dasselbe Muster für eine
  LAUFENDE Session. Dort ist ein Vollbestand beim Öffnen vertretbar (einmalig), eine
  Neuberechnung alle 5 Sekunden nicht — mindestens die Wiederholung entschärfen.
- Beweis statt Behauptung: ein JVM-Test, der zählt, wie oft neu gerechnet wird, wenn N
  Messwert-Batches eintreffen. Vor der Änderung muss er fehlschlagen.
- Nicht die Anzeige verschlechtern, um das Problem zu umgehen. Das Diagramm soll weiterhin
  live sein.

=== AUFGABE 2: Farbschema und Dunkelmodus (Befund A2) ===

Neue Datei ui/theme/Theme.kt mit einer Composable LaermprotokollTheme(content):
- lightColorScheme() und darkColorScheme(), Auswahl über isSystemInDarkTheme()
- Dynamic Color (Material You) ist ab Android 12 verfügbar und minSdk ist 31 — also ohne
  Versionsweiche nutzbar. Ob es DEFAULT sein soll, ist Owner-Entscheidung 1: bis zur Antwort
  einen Parameter dynamicColor: Boolean = false vorsehen und die statischen Schemata liefern.
- MainActivity.kt:76 auf LaermprotokollTheme umstellen.
- Statusfarben (verbunden / gestört / getrennt / Alarm / Aufnahme läuft / live) gehören NICHT
  in colorScheme, sondern in eine eigene, über CompositionLocal bereitgestellte Farbmenge mit
  je einem Wert für hell und dunkel. Grund: "verbunden = grün" ist keine Material-Rolle, und
  colorScheme.error für alles Rote zu missbrauchen macht Alarm und Ausfall ununterscheidbar.
- Alle 30 hartcodierten Farbliterale durch Tokens ersetzen — einschliesslich der neuen in
  PegelverlaufChart.kt und BluetoothStatusBadge.kt. Danach muss
  `grep -rn "Color(0x\|Color\.Red\|Color\.Gray" app/src/main/java/` leer sein (Ausnahme:
  Theme.kt selbst).
- Kontrast prüfen: jede Kombination Text-auf-Hintergrund in beiden Schemata mindestens 4,5:1.
  Das ist nachrechenbar, nicht Geschmackssache — WCAG-Formel, kurz im PR dokumentieren.
  Das Diagramm gehört dazu: eine 0,25-Alpha-Fläche, die auf Weiss funktioniert, verschwindet
  auf Dunkelgrau.

=== AUFGABE 3: String-Ressourcen (Befund A3) ===

Sämtliche UI-Literale nach res/values/strings.xml, Zugriff über stringResource(). Dabei:
- app_name auf "Lärmprotokoll" korrigieren (Umlaut).
- Mengenangaben ("Ausfälle (n)", "Diagnose-Log (n)", "Sync-Historie (n)", "n Messwerte")
  als <plurals>.
- contentDescription = "AI Recognition" (MainActivity.kt:838) auf Deutsch.
- Kein res/values-en/ anlegen. Deutsch bleibt die einzige Sprache (AGENTS.md §5: UI-Strings
  sind Deutsch) — es geht hier um Wartbarkeit und Barrierefreiheit, nicht um Übersetzung.
Reine Fließarbeit, aber Voraussetzung für alles Weitere. Nicht auslassen.

=== AUFGABE 4: Barrierefreiheit (Befund A4) ===

- Das 16-dp-Löschkreuz (MainActivity.kt:399) auf mindestens 48 dp bringen (IconButton statt
  clickable-Icon).
- AudioPlayerScreen.kt:8,77 zurück auf Icons.AutoMirrored.Filled.ArrowBack, wie die anderen
  fünf Screens.
- Live-Pegel und Dashboard-Werte als liveRegion auszeichnen, damit TalkBack Änderungen ansagt.
- PegelverlaufChart bekommt ein contentDescription, das die Kernaussage in einem Satz nennt
  (aktueller/mittlerer/höchster Pegel, Anzahl Ausfälle). Die Zahlen liegen bereits vor.
- Der grüne Live-Punkt (MainActivity.kt:731-738) bekommt eine Textentsprechung; Farbe darf
  nirgends der einzige Zustandsträger sein.

=== AUFGABE 5: Navigation aufräumen (Befund A5) ===

- Zurück-Pfeile aus den TopAppBars der vier Tab-Ziele (meter, protokoll, diagnose, settings)
  entfernen. Nur echte Detailseiten (protokoll/{sessionId}, player) behalten einen.
- NavigationBar auf der Player-Route ausblenden (die Route ist über istBottomNavZielAktiv()
  bereits als "keinem Tab zugehörig" erkennbar).
- Start-Screen bekommt eine TopAppBar wie alle anderen, statt der eigenen Row mit
  headlineMedium. BluetoothStatusBadge wandert in diese TopAppBar und wird damit auf allen
  Screens sichtbar statt nur auf "Start" — es zeigt einen app-weiten Zustand.
- Icons ersetzen: Messgerät (Refresh -> z.B. Sensors/Bluetooth), Diagnose (Info -> z.B.
  MonitorHeart/Troubleshoot), KI-Erkennung (Refresh -> AutoAwesome o.ä.), Geräusch lernen
  (Star -> Bookmark o.ä.), Filter (Menu -> FilterList). Wenn dafür
  androidx.compose.material:material-icons-extended nötig wird: das ist Owner-Entscheidung 2,
  vorher fragen — die extended-Bibliothek ist groß.

=== AUFGABE 6: Start-Screen — Scrollrisiko und Leerzustand (Befunde A8, A6) ===

- MainActivity.kt:305 auf ein einziges LazyColumn umstellen: item{} für Statuskarte (jetzt
  inklusive Diagramm), Filter und Chips, items() für die Aufnahmen. Dasselbe Muster wie
  ProtokollDetailScreen/DiagnoseScreen und wie M7c es für MeterScreen gemacht hat.
- Leerzustand ohne Aufnahmen: erklärender Text statt leerer Fläche, nach dem Vorbild von
  ProtokollScreen.kt:59-64. Zwei verschiedene Fälle: "noch nie etwas aufgezeichnet" und
  "Filter blendet alles aus" — der zweite braucht einen Button "Filter zurücksetzen".
- SimpleDateFormat aus dem groupBy-Lambda herausziehen (MainActivity.kt:421).

=== AUFGABE 7: Rückmeldung, Fehler, Rückgängig (Befund A6) ===

- SnackbarHost in AppNavigation, einmal für die ganze App.
- Erfolg quittieren: Export erstellt, Server übernommen, Label gesetzt.
- Löschen von Aufnahmen und von gelernten Geräuschen: Snackbar mit "Rückgängig". Für Aufnahmen
  heißt das, dass die Datei erst nach Ablauf der Snackbar wirklich gelöscht wird — den
  bestehenden Bestätigungsdialog dabei behalten, nicht ersetzen.
- Exporte: Button während des Laufs sperren und Fortschritt zeigen.
- Fehlermeldungen (Scan, Drive, Player) bekommen eine Wiederholen-Aktion.
- Toast (MainActivity.kt:765) entfällt zugunsten der Snackbar bzw. der Karte aus Aufgabe 8.

=== AUFGABE 8: Berechtigungen und Onboarding (Befund A7) ===

- Der Sammelaufruf beim ersten Frame (MainActivity.kt:288-298) entfällt.
- RECORD_AUDIO und POST_NOTIFICATIONS werden angefragt, wenn der Nutzer "Aufnahme starten"
  drückt, mit einem erklärenden Dialog davor (wofür, was passiert ohne).
- BLUETOOTH_SCAN/BLUETOOTH_CONNECT werden auf MeterScreen angefragt, beim ersten Scan-Versuch
  — nicht auf dem Start-Screen.
- Berechtigungsstand bei jedem ON_RESUME neu lesen (LifecycleEventObserver oder
  lifecycle-runtime-compose), damit die Rückkehr aus den Systemeinstellungen ankommt. Betrifft
  auch MeterScreen.kt:97-100 (remember ohne Key).
- Dauerhaft abgelehnt: statt Toast eine bleibende Karte mit Erklärung und einem Button, der
  die App-Einstellungen öffnet (ACTION_APPLICATION_DETAILS_SETTINGS).
- Erststart-Onboarding: 3 bis 4 Seiten (Zweck / Betriebsart Mikrofon vs. PCE-323 mit dem
  ehrlichen Hinweis auf die fehlende Kalibrierung / Berechtigungen / Akku-Optimierung), einmalig,
  Merker in SettingsManager, jederzeit aus den Einstellungen wieder aufrufbar.

=== AUFGABE 9: Kalibrierten Wert und Quelle sichtbar machen (Befund A10) ===

Der inhaltlich wichtigste Punkt dieses Meilensteins.
- NoiseRecordItem zeigt, welche Quelle ausgelöst hat (meterConnected) und, falls vorhanden,
  calibratedDbA — deutlich als der belastbarere Wert, mit dem unkalibrierten Mikrofonwert
  daneben, nicht an seiner Stelle. Beide Felder sind bereits in NoiseRecord vorhanden.
- Die rohe Amplitude verschwindet aus der Hauptzeile (sie sagt einem Nutzer nichts) — höchstens
  in eine Detailansicht.
- ReportManager.generateDailyReport ergänzt je Ereignis: Quelle, kalibrierter Wert falls
  vorhanden, Gerätename. Und einmal im Kopf des Berichts den ehrlichen Hinweis, dass die
  Frequenzbewertung unbestätigt ist, solange MeterFrame.modeAssumptionConfirmed false ist —
  dieselbe Ehrlichkeit, die MeterScreen bereits an den Tag legt. Nicht "dBA" schreiben, solange
  es nicht belegt ist.
- Aufpassen: NICHTS an der Room-Struktur ändern. Die Felder existieren, es geht nur ums Anzeigen.

=== AUFGABE 10: Einstellungen gliedern (Befund A9) ===

- Die sechs Abschnitte als eigenständige, aufklappbare Blöcke mit Kopfzeile und
  Zustandszusammenfassung ("Alarmierung — aktiv, Push konfiguriert"), zugeklappt als Default,
  statt eines durchgehenden 692-Zeilen-Scrolls.
- Die beiden Textfelder mit "Übernehmen"-Button (ntfyServer, heartbeatUrl): beim Verlassen des
  Feldes ungespeicherte Änderungen sichtbar machen, statt sie still zu verwerfen.
- Alarmierung nach oben, direkt unter Aufnahme — nicht hinter vier Slider.
- Aufteilen in echte Unterseiten ist erlaubt, aber nicht gefordert.

NICHT TEIL VON M9
- Neue Funktionen jeder Art. Die stehen in docs/PROMPT_M10_FUNKTIONEN.md und sind ein eigener
  Meilenstein. Wer hier Funktionen einbaut, macht den Review unmöglich.
- Änderungen an Room-Entities, DAOs, Migrationen oder Schemata. Eine zusätzliche, begrenzte
  Query für Aufgabe 1 ist erlaubt — sie ändert kein Schema.
- BLE-Protokollcode, ConnectionSupervisor, Decoder.
- Neue Chart- oder UI-Bibliothek.
- Die gelöschte androidTest-Suite wiederbeleben (siehe Befund A12).
- applicationId (B-6, bewusst vertagt).

TESTS
- Jede reine Logik neu ausgelagert und per JVM-Test geprüft, wie es MeterScreen
  (scanFehlermeldung, connectionStateDisplay), ServiceControl (leiteDashboardAnzeigeAb),
  ChartDaten (downsample*) und PegelverlaufChart (PegelverlaufChartTest) schon vormachen.
  Konkret mindestens: der Zähltest aus Aufgabe 1, Leerzustands-Auswahl (nie aufgezeichnet vs.
  wegfiltriert), Sichtbarkeit des Zurück-Pfeils je Route, Zusammenfassungstext je
  Einstellungsabschnitt, Auswahl der anzuzeigenden Pegelquelle aus Aufgabe 9.
- Compose-Regressionstest unter Robolectric für Aufgabe 6, nach dem Muster der bestehenden
  Tests in app/src/test/.../ui/: kleiner Viewport, laufende Session (Diagramm sichtbar), viele
  gelernte Geräusche, aufgeklappter Filter — die letzte Aufnahmezeile muss per
  performScrollTo().assertIsDisplayed() erreichbar sein. Der Test muss fehlschlagen, wenn man
  auf die alte Column zurückbaut. Gegenprobe im PR zeigen.
- Die bestehenden *ComposeTest.kt prüfen Texte, die Aufgabe 3 in Ressourcen verschiebt und
  Aufgabe 5 teils entfernt (u.a. contentDescription "Zurück"). Die mitziehen, nicht abschalten.
- Für jeden neuen Test eine Gegenprobe: Schlägt er fehl, wenn man die zugehörige Logik entfernt?
- Beide Room-Migrationstests müssen unverändert grün bleiben — M9 fasst die Datenbank nicht an.
  Tut es einer nicht mehr, hat jemand gegen "NICHT TEIL VON M9" verstoßen.

DEFINITION OF DONE
- ./gradlew assembleDebug und ./gradlew test grün — Ausgabe im PR zeigen, nicht behaupten.
- Beide Room-Migrationstests grün, app/schemas/ unverändert.
- Screenshots im PR, hell UND dunkel, je Screen. Ohne den Dunkelmodus-Screenshot ist Aufgabe 2
  nicht abgenommen.
- Für Aufgabe 1: eine Messung, nicht nur der Zähltest — eine über mehrere Minuten laufende
  Session am Emulator, vorher/nachher. Wenn das nicht möglich war: genau das schreiben.
- Ein Durchgang mit TalkBack und mit fontScale 1.5 am Emulator, Ergebnis im PR beschrieben.
  Wo etwas nicht geprüft werden konnte: genau das schreiben (AGENTS.md §6).
- Draft-PR gegen main: was geändert, was verifiziert (Befehl und Ergebnis), was offen.
```

---

## Teil C — Owner-Entscheidungen, vor der Umsetzung zu klären

Diese drei sind **nicht** vom umsetzenden Agenten zu entscheiden (AGENTS.md §2).

1. **Dynamic Color (Material You) als Default?** Ab Android 12 verfügbar, minSdk ist 31, also
   technisch ohne Weiche nutzbar. Dafür spricht, dass sich die App ins System einfügt; dagegen,
   dass die Statusfarben (rot = Ausfall, grün = verbunden) dann neben wechselnden Systemfarben
   stehen und der Kontrast je Gerätehintergrund anders ausfällt. **Vorschlag: aus.** Die App ist
   ein Messwerkzeug, gleiche Optik auf jedem Gerät ist hier mehr wert als Anpassung.

2. **`material-icons-extended` als Abhängigkeit?** Die passenden Icons aus Aufgabe 5
   (`Sensors`, `MonitorHeart`, `FilterList`, `Bookmark`) liegen größtenteils nicht im
   `material-icons-core`-Satz. Die Alternative sind ein paar handgebaute `ImageVector`s, wie
   `PauseIcon` in `AudioPlayerScreen.kt` schon einer war. **Vorschlag: keine neue Abhängigkeit,
   vier Vektoren selbst zeichnen** — passt zum durchgehend minimalen Abhängigkeits-Stil des
   Projekts.

3. **Compose-BOM anheben?** Aktuell `2024.05.00` (Mai 2024) bei compileSdk 36, AGP 9.2 und
   Kotlin 2.2. Neuere Stände bringen stabile Bausteine, die für M9/M10 direkt einschlägig wären
   (`SearchBar`, `PullToRefreshBox`, `NavigationSuiteScaffold`). Dagegen spricht das Risiko
   still veränderten Verhaltens quer durch alle Screens, mitten in einem Meilenstein, dessen
   Prüfung ohnehin visuell ist — und dass die instrumentierte Test-Suite als Netz gerade nicht
   mehr existiert (Befund A12). **Vorschlag: nicht in M9.** Als eigener, isolierter PR davor
   oder danach.

---

## Warum diese Reihenfolge und nicht die naheliegende

Der Reflex wäre, mit dem Sichtbarsten anzufangen — Navigation und Einstellungen. Das wäre
falsch herum.

Aufgabe 1 steht vorn, weil sie als einzige kein Aussehen betrifft, sondern ein Verhalten: Der
Start-Screen macht mit jeder Stunde Messdauer mehr Arbeit für dasselbe Bild. Das wird nicht
besser, während man Farben sortiert, und es fasst genau die Stellen an, die die anderen
Aufgaben danach umgestalten.

Theme und String-Ressourcen kommen als Nächstes, weil sie die Grundlage sind, auf der alle
übrigen Aufgaben aufsetzen: Wer die Navigation umbaut, bevor es Farbtokens gibt, schleppt die
30 Farbliterale mit; wer Texte umformuliert, bevor es `strings.xml` gibt, formuliert sie
zweimal. Dass der Farbbestand seit PR #46 von 17 auf 30 gewachsen ist, ist genau das Argument:
Ohne Tokens wächst er mit jedem Screen weiter.

Und Aufgabe 9 (kalibrierter Wert sichtbar) steht bewusst nicht am Anfang, obwohl sie inhaltlich
am meisten wiegt: Sie ändert Anzeigeformate in Liste und Bericht, und die will man einmal
schreiben — auf Ressourcen und Tokens, nicht davor.
