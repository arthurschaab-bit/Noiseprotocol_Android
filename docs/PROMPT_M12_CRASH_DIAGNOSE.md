# Prompt: M12 — Absturzsichere Diagnose mit automatischem Drive-Upload

Auftrag des Owners vom 17.09.2026, direkt eingeschoben wie schon M7b, M7c, M9, M10 und M11. Er
steht **nicht** im `docs/IMPLEMENTIERUNGSPLAN_PCE-323_BLUETOOTH.md` und widerspricht ihm nicht.

**Der Auftrag in den Worten des Owners:**

> Ich möchte das bestehende Diagnosekonzept massiv aufbohren. Die App crasht während dem
> Aufzeichnungsbetrieb gerade während dem Durchsehen der Diagnoselogs. Wir müssen alle notwendigen
> Informationen für eine Analyse warum der Crash passiert ist sichern und beispielsweise beim
> nächsten App-Start automatisch auf Google Drive `Lärmprotokoll/Support-Bundle` hochladen.

**Das zugehörige Konzept ist [DIAGNOSE_CRASH_KONZEPT.md](DIAGNOSE_CRASH_KONZEPT.md). Lies es
vollständig, bevor du irgendeinen der Schritte anfängst.** Dieses Dokument ist nur die
Arbeitsanweisung; die Begründungen stehen dort.

**Acht Schritte, acht PRs.** Kein Schritt darf Arbeit eines späteren vorwegnehmen.

---

## 0 · Bevor du eine Zeile Code schreibst

1. **`AGENTS.md` vollständig lesen.** Sie gilt unverändert. Insbesondere:
   - Neuer Branch von `main`, Namensschema `feature/m12-<n>-<kurzbeschreibung>`.
     **Nie auf `main` committen oder pushen.**
   - Commit-Nachrichten auf Deutsch, kleine Commits je abgeschlossenem Teilschritt.
   - Code-Bezeichner englisch, UI-Texte deutsch, Doku deutsch.
   - `./gradlew assembleDebug` und `./gradlew test` müssen nach jedem Schritt grün sein, die
     **Ausgabe** kommt in den PR — nicht die Zusammenfassung der Ausgabe.
   - **Handgeschriebene Fakes. Kein Mockito, kein MockK.** In diesem Repository gibt es beides
     nicht, und das bleibt so.
   - **`fallbackToDestructiveMigration()` ist verboten.** Jede Schemaänderung bekommt eine
     explizite `Migration` *und* einen Migrationstest.
   - **Nie behaupten, etwas funktioniere, ohne es ausgeführt zu haben.** Was du in dieser Umgebung
     nicht verifizieren konntest (kein Gerät, kein Drive-Konto, kein echter Absturz), schreibst du
     genau so in den PR.
2. **Nicht raten.** Dieses Dokument nennt zu jeder Behauptung über den Ist-Zustand die Datei und
   die Zeile. Stellst du beim Lesen fest, dass eine Angabe nicht mehr stimmt (der Code hat sich
   seit dem Schreiben bewegt), **halte dich an den Code und melde die Abweichung im PR** —
   überschreib nicht stillschweigend die Absicht.
3. **Kein Vorgriff.** Schritt 1 baut keinen Upload. Schritt 4 baut keinen Worker. Keine
   „wenn ich schon mal hier bin"-Aufräumarbeiten.
4. **Offene Entscheidungen entscheidest du nicht selbst.** Abschnitt 8 des Konzepts listet O-1
   bis O-6. Wo ein Schritt unten auf einen offenen Punkt trifft, steht das dabei — dann fragst du
   den Owner, bevor du diesen Teil baust. Der Rest des Schritts wird trotzdem fertig.
5. **Das Repository ist öffentlich.** Keine DSNs, keine Tokens, keine Drive-Ordner-IDs, keine
   Kontonamen in Code, Tests, Kommentaren oder PR-Texten.

---

## 1 · Ist-Zustand — überprüft, mit Fundstelle

Stand 17.09.2026, `main` bei `5bc2dc5`:

| Fund | Fundstelle |
|---|---|
| Kein `setDefaultUncaughtExceptionHandler` im gesamten `app/src/main` | Suche über das Modul; `DiagnosticCode.APP_UNCAUGHT` nur in `AppContainer.kt:74` (CoroutineExceptionHandler) |
| Sentry-DSN leer, Remote-Pfad wirkungslos | `app/build.gradle.kts:39`, `LaermprotokollApp.kt:34-38`, `SentryDiagnosticSink.kt:25` |
| Breadcrumbs nur im RAM, max. 100 | `CompositeDiagnosticsReporter.kt:26-28` (`CopyOnWriteArrayList`, `maxHistorySize`) |
| Lokales Log standardmäßig aus | `DiagnosticSink.kt:29` und `:45` (`if (!aktiv()) return`), `DiagnosticLogEntity.kt` KDoc |
| Nur 1 ExitInfo-Eintrag, `getTraceInputStream()` ungenutzt | `LaermprotokollApp.kt:66` |
| Support-Bundle nur per Share-Intent, keine Drive-Kategorie | `DiagnoseScreen.kt:301-304`, `DriveAblage.kt` (`DriveKategorie`) |
| Bundle wird vollständig im Heap gebaut | `SupportBundleExporter.kt:62-75` (`String` → `toByteArray()` je Eintrag) |
| Diagnose-Log ohne `LIMIT` als Flow in der UI | `DiagnosticLogDao.kt` (`alle()`), `DiagnoseScreen.kt:82` |
| OOM auf dem Zielgerät bereits dokumentiert | `SettingsManager.kt:448` (KDoc zu `datenbankSicherungLastAttemptAt`) |
| Room-Schemaversion | `AppDatabase.kt:562` — **25**, nicht 19 wie in AGENTS.md Abschnitt 3 behauptet |
| WorkManager vorhanden | `gradle/libs.versions.toml:50-51` (`work = "2.9.1"`, inkl. `work-testing`) |
| Drive-Client vorhanden und erprobt | `GoogleDriveApiClient.kt`, `DriveApiClient.kt`, `DriveOrdnerbaum` in `DriveAblage.kt` |

---

# ETAPPE A — Beweissicherung

> Nach Schritt 3 ist der nächste Absturz vollständig analysierbar. Das ist der Zweck dieser
> Etappe und der Grund, warum sie vor dem Fix kommt.

---

## Schritt 1 — ACRA-Grundgerüst

**Branch:** `feature/m12-1-acra-grundgeruest`
**Ziel:** Ein Absturz wird abgefangen und hinterlässt eine Report-Datei auf der Platte.
**Ausdrücklich noch nicht:** Upload, Drive, Bundle-Umbau, eigene Collectors.

### Aufgaben

1. **Abhängigkeiten** in `gradle/libs.versions.toml` und `app/build.gradle.kts`:
   - `acra = "5.13.1"`
   - `acra-core`, `acra-advanced-scheduler`, `acra-limiter` (Gruppe `ch.acra`)
   - `com.google.auto.service:auto-service-annotations` als `compileOnly`,
     `com.google.auto.service:auto-service` als `ksp` (dieses Projekt nutzt KSP, nicht kapt)
   - **Nicht** aufnehmen: `acra-http`, `acra-mail`, `acra-dialog`, `acra-notification`,
     `acra-toast`. Wir brauchen keinen der mitgelieferten Versandwege und keinen Nutzerdialog —
     die App läuft unbeaufsichtigt.

2. **Initialisierung in `LaermprotokollApp`:**
   - ACRA wird in `attachBaseContext(base: Context)` initialisiert, **nicht** in `onCreate()`.
     Das ist eine harte Anforderung von ACRA, kein Stilfrage.
   - **Prozess-Weiche:** `onCreate()` muss bei `ACRA.isACRASenderServiceProcess()` sofort
     zurückkehren, **bevor** `container = AppContainer(this)` läuft. ACRA startet den Sender in
     einem eigenen Prozess; ohne diese Weiche entstehen dort eine zweite Room-Instanz, ein
     zweiter BLE-Transport und ein zweiter OkHttp-Pool — auf einem Gerät, das eventuell gerade
     wegen Speichermangels abgestürzt ist. Siehe Konzept 4.1.
   - Konfiguration:
     - `buildConfigClass = BuildConfig::class.java`
     - `reportFormat = StringFormat.JSON`
     - Report-Felder mindestens: `REPORT_ID`, `APP_VERSION_CODE`, `APP_VERSION_NAME`,
       `PACKAGE_NAME`, `PHONE_MODEL`, `BRAND`, `PRODUCT`, `ANDROID_VERSION`, `BUILD`,
       `TOTAL_MEM_SIZE`, `AVAILABLE_MEM_SIZE`, `STACK_TRACE`, `THREAD_DETAILS`, `LOGCAT`,
       `INITIAL_CONFIGURATION`, `CRASH_CONFIGURATION`, `USER_APP_START_DATE`,
       `USER_CRASH_DATE`, `IS_SILENT`, `CUSTOM_DATA`
     - `logcatArguments`: auf den eigenen Prozess und eine sinnvolle Zeilenzahl begrenzen
       (Vorschlag `-t 500 -v time`). **Kein `READ_LOGS` ins Manifest** — für den eigenen Prozess
       nicht nötig, für fremde wirkungslos.
     - `LimiterConfiguration`: aktiv, mit `failedReportLimit` und einer Obergrenze je Zeitraum,
       damit eine Absturzschleife nicht hunderte Reports erzeugt.
     - `SchedulerConfiguration`: `requiresNetworkType` zunächst `NetworkType.CONNECTED`,
       `restartAfterCrash = false` (die App startet nicht von selbst neu — bei einer
       Dauerüberwachung wäre ein stiller Neustart ohne laufenden Foreground Service irreführend).
   - **Kommentar im Code hinterlassen:** Wenn Sentry später aktiviert wird (offener Punkt O-1),
     entscheidet die Initialisierungsreihenfolge, ob Sentry den Absturz noch sieht. Beide
     Bibliotheken verketten den vorherigen Handler, aber das ist dann explizit zu prüfen und zu
     testen. Siehe Konzept Abschnitt 5.

3. **Vorläufiger Sender:** eine `ReportSenderFactory` + `ReportSender`, registriert per
   `@AutoService`, die den Report **nur** als Datei nach
   `context.filesDir/support_outbox/` schreibt. Kein Netzwerk, kein Drive, kein Worker.
   Schritt 5 ersetzt das Innenleben, die Schnittstelle bleibt.

4. **Testabsturz-Auslöser:** Im **Debug-Build** ein Bereich im `DiagnoseScreen` mit drei Knöpfen:
   `RuntimeException` werfen, `OutOfMemoryError` provozieren, Main-Thread blockieren (ANR).
   Über `BuildConfig.DEBUG` abgeschirmt, im Release nicht vorhanden. Ohne diesen Auslöser ist die
   Kette Absturz → Bundle → Drive in keinem der folgenden Schritte am Stück prüfbar.

### Akzeptanzkriterien

- `./gradlew assembleDebug` und `./gradlew test` grün, Ausgabe im PR.
- Nach einem ausgelösten Testabsturz existiert eine Report-Datei in `support_outbox/`.
  **Falls du das nicht auf einem Gerät oder Emulator ausführen konntest: genau das in den PR
  schreiben, nicht behaupten, es funktioniere.**
- Der zweite Prozess baut nachweislich keinen `AppContainer` auf (Test siehe unten).
- Keine neue Berechtigung im Manifest.

### Tests

- Robolectric: `LaermprotokollApp` mit gesetztem Sender-Prozess-Flag baut keinen `AppContainer`.
- Unit: Die `ReportSenderFactory` liefert einen Sender; der Sender schreibt bei einem
  Fake-`CrashReportData` eine Datei mit erwartetem Namensschema in ein temporäres Verzeichnis.
- Unit: Die ACRA-Konfiguration enthält die geforderten Report-Felder (Konfiguration als reine
  Funktion bauen, damit sie ohne Android-Laufzeit prüfbar ist).

---

## Schritt 2 — Breadcrumbs absturzfest machen

**Branch:** `feature/m12-2-breadcrumb-persistenz`
**Ziel:** Der strukturierte App-Kontext der letzten Minuten überlebt den Prozesstod.
**Behebt:** Lücke L3 aus dem Konzept.

### Aufgaben

1. **`BreadcrumbRingFile`** in `com.example.lrmprotokoll.diagnose`:
   - Zwei Dateien à 256 KB im Wechsel, Gesamtobergrenze 512 KB. Ist A voll, wird auf B
     umgeschaltet; beim nächsten Wechsel wird A überschrieben. So liegen immer mindestens 256 KB
     Historie vor.
   - Zeilenweises Anhängen als JSON Lines, UTF-8, ein Breadcrumb je Zeile.
   - Schreiben auf einem eigenen Single-Thread-Executor — der Aufrufer blockiert nie.
   - **Sparsam mit Allokationen.** OOM ist der Hauptverdächtige für den akuten Absturz; ein
     Diagnosepfad, der bei Speichermangel selbst zuschlägt, ist wertlos.
   - Lesefunktion, die beide Dateien in zeitlicher Reihenfolge zusammenführt und dabei
     angebrochene erste Zeilen (Folge der Rotation) verwirft.
   - Die Datei liegt in `filesDir` (App-interner Speicher), nicht im externen Speicher.

2. **`CompositeDiagnosticsReporter` anbinden:** `breadcrumb()` schreibt zusätzlich in die
   Ringdatei. Die bestehende RAM-Historie und `recentBreadcrumbs()` bleiben unverändert —
   anderer Code hängt daran (`SupportBundleExporter.kt:56`).

3. **Logcat-Spiegelung:** `breadcrumb()` gibt zusätzlich `Log.i()` aus, damit ACRAs
   Logcat-Collector dieselben Spuren als zweites Netz einfängt. Nur als Ergänzung — der
   Logcat-Puffer ist klein und wird geteilt.

4. **ACRA-Collector:** Ein `Collector` per `@AutoService`, der die zusammengeführte Ringdatei
   unter einem eigenen Schlüssel in `CUSTOM_DATA` an den Report hängt. Bei großen Inhalten
   gekürzt, mit Vermerk über die Kürzung.

5. **Aufräumen:** Die Ringdatei wird beim Start genau einmal beschnitten, falls sie die
   Obergrenze durch einen früheren Fehler überschreitet.

### Akzeptanzkriterien

- Breadcrumbs sind nach einem Neustart der App lesbar.
- Die Gesamtgröße überschreitet 512 KB auch im Dauerbetrieb nicht.
- Kein erkennbarer Einfluss auf den Aufnahmepfad (das Schreiben blockiert den Aufrufer nicht).
- `assembleDebug` und `test` grün, Ausgabe im PR.

### Tests

- Ringdatei: Schreiben, Rotation, Zusammenführen, Obergrenze, angebrochene erste Zeile.
- Nebenläufigkeit: viele Schreibvorgänge aus mehreren Threads verlieren keine Zeilen und
  überschreiten die Grenze nicht.
- `CompositeDiagnosticsReporter`: Ein Breadcrumb landet in RAM-Historie **und** Ringdatei
  (Fake-Ringdatei).
- Collector: liefert die erwarteten Daten bei gefüllter und bei fehlender Datei.

---

## Schritt 3 — ApplicationExitInfo vollständig auswerten

**Branch:** `feature/m12-3-exit-info`
**Ziel:** ANR-Thread-Dumps und native Tombstones werden gesichert.
**Behebt:** Lücke L5.

### Aufgaben

1. **`ProcessExitCollector`** in `com.example.lrmprotokoll.diagnose`, gelöst aus
   `LaermprotokollApp.checkPreviousProcessExit()` (`LaermprotokollApp.kt:62-107`):
   - **Alle** verfügbaren Einträge holen (bis 16), nicht nur einen:
     `getHistoricalProcessExitReasons(packageName, 0, 0)`.
   - Je Eintrag erfassen: `reason`, `status`, `timestamp`, `importance`, `pss`, `rss`,
     `description`, `processName`, `definingUid`.
   - Bei `REASON_ANR`: `getTraceInputStream()` lesen und als `anr_trace.txt` in einem
     Diagnoseverzeichnis ablegen.
   - Bei `REASON_CRASH_NATIVE` und `Build.VERSION.SDK_INT >= 31`: `getTraceInputStream()` liefert
     ein **Protobuf mit Binärdaten**. Byteweise kopieren, **nicht** durch einen `Reader` oder
     eine `String`-Konvertierung schicken — das zerstört den Inhalt. Als
     `native_tombstone.pb` ablegen.
   - `getTraceInputStream()` darf `null` liefern (die Traces liegen in einem globalen Ringpuffer,
     den andere Apps überschreiben können). Das ist ein Normalfall, kein Fehler.
   - Streamend kopieren mit fester Obergrenze je Datei.

2. **Entprellung:** Der Zeitstempel des zuletzt verarbeiteten Exits wird im `SettingsManager`
   gemerkt. Ohne das wird derselbe Absturz bei jedem Start erneut gemeldet und später erneut
   hochgeladen.

3. **`LaermprotokollApp` umstellen:** ruft den Collector auf, statt die Auswertung selbst zu
   machen. Verhalten bleibt sonst gleich (Breadcrumb + Report bei CRASH/ANR).

4. **Aufräumen:** Gesicherte Traces werden nach dem Einpacken in ein Bundle gelöscht bzw. nach
   einer festen Frist verworfen, damit sie sich nicht ansammeln.

### Akzeptanzkriterien

- Nach einem ANR liegt ein lesbarer Thread-Dump vor. **Wenn nicht auf Hardware geprüft: so in den
  PR schreiben.**
- Derselbe Exit wird nicht mehrfach gemeldet.
- Ein `null`-Trace führt zu keinem Fehler.
- `assembleDebug` und `test` grün, Ausgabe im PR.

### Tests

- Collector gegen eine handgeschriebene Fake-Quelle von Exit-Einträgen: mehrere Einträge,
  Entprellung, `null`-Trace, Binärdaten bleiben byteweise identisch, Obergrenze greift.
- Robolectric für den Pfad in `LaermprotokollApp`.
- Auf SDK < 30 (minSdk ist 29!) macht der Collector nichts und stürzt nicht ab — **expliziter
  Test**, das ist eine echte Fehlerquelle.

---

# ETAPPE B — Auslieferung

---

## Schritt 4 — Bundle streamend bauen und inhaltlich erweitern

**Branch:** `feature/m12-4-bundle-inhalt`
**Ziel:** Ein Bundle, das für eine Analyse ausreicht und dabei selbst kein OOM auslöst.
**Behebt:** Lücke L7 und den Selbstschutz aus Konzept 4.5.
**Offene Punkte vorher klären:** O-2 (Logcat-Umfang), O-6 (Größenbudget).

### Aufgaben

1. **`SupportBundleExporter` auf Streaming umbauen.** Heute wird jeder Eintrag als `String`
   erzeugt, in ein `ByteArray` konvertiert und in einer `Map` gesammelt, bevor das ZIP entsteht
   (`SupportBundleExporter.kt:62-75`). Das ist genau das Verhalten, das bei Speichermangel
   zuschlägt. Neu: direkt in den `ZipOutputStream` schreiben, Prüfsummen über einen
   `DigestOutputStream` im Vorbeigehen berechnen.

2. **Seitenweises Lesen aus Room.** Neue DAO-Methode mit `LIMIT`/`OFFSET` (oder Keyset-Paginierung
   über `timestamp`). `createBundle()` nimmt **keine** `List<DiagnosticLogEntity>` mehr entgegen —
   diese Signatur ist der Grund, warum der Export heute die ganze Tabelle in den Heap zieht.
   Aufrufer in `DiagnoseScreen.kt:301` entsprechend anpassen.

3. **Neue Bundle-Inhalte** gemäß Konzept 4.4 — Struktur genau so:
   `manifest.json`, `crash/`, `log/`, `state/`, `checksums.sha256`.
   - `state/runtime.json`: Heap benutzt/frei/max, `ActivityManager.MemoryInfo` inkl.
     `lowMemory`-Flag, Akkustand, Doze-/Batterieoptimierungsstatus, erteilte Berechtigungen,
     laufende Dienste, BLE-Verbindungszustand, Aufnahmezustand.
   - `state/settings.json`: aktive Einstellungen **ohne Geheimnisse**. Kein Token, keine DSN,
     keine ntfy-Topics, keine Heartbeat-URL, keine Drive-Ordner-ID.
   - `state/db_stats.json`: Zeilenzahlen je Tabelle, DB-Dateigröße, ältester/neuester Eintrag.
   - `log/logcat.txt`: eigener Prozess, redigiert, mit Obergrenze.
   - `crash/`: ACRA-Report, Thread-Dump, Exit-Historie, ANR-Trace, natives Tombstone.

4. **`DiagnosticRedactor` erweitern** um Muster, die in Logcat vorkommen und bisher nicht
   abgedeckt sind: `Bearer`-Tokens in OkHttp-Zeilen, `Authorization`-Header, Dateipfade mit
   Kontonamen, BLE-MACs in Systemmeldungen, E-Mail-Adressen. **Bestehende Tests müssen grün
   bleiben** — der Redactor wird erweitert, nicht umgebaut.

5. **Fehlertoleranz:** Jeder Sammelschritt ist einzeln abgesichert. Scheitert einer, entsteht das
   Bundle trotzdem, mit Fehlervermerk in `manifest.json`. Ein halbes Bundle ist unendlich viel
   besser als keines.

6. **Bundle-Typen:** `manifest.json` enthält den Typ (`absturz`, `anr`, `periodisch`, `manuell`)
   und den Auslöser. Periodische Bundles lassen Logcat und Events schlanker ausfallen.

### Akzeptanzkriterien

- Ein Bundle entsteht bei 50 000 Log-Einträgen ohne OOM. **Test mit entsprechend gefüllter
  Fake-Datenquelle, nicht nur mit zehn Zeilen.**
- Kein Geheimnis in irgendeinem Bundle-Eintrag — mit Test belegt.
- Größenbudget (O-6) wird eingehalten.
- Bestehende `SupportBundleExporter`-Tests angepasst und grün.
- `assembleDebug` und `test` grün, Ausgabe im PR.

### Tests

- Exporter gegen ein temporäres Verzeichnis: ZIP-Struktur, alle erwarteten Einträge,
  Prüfsummen stimmen.
- Speichertest: sehr große Fake-Datenmenge, das Ergebnis entsteht, Obergrenzen greifen.
- Redaction: Für jedes neue Muster ein Fall; die alten Fälle bleiben grün.
- Fehlertoleranz: Ein absichtlich scheiternder Sammelschritt erzeugt trotzdem ein Bundle mit
  Fehlervermerk.
- Paginierung: Das DAO liefert bei mehr Einträgen als der Seitengröße alle Zeilen genau einmal.

---

## Schritt 5 — Automatischer Drive-Upload

**Branch:** `feature/m12-5-drive-upload`
**Ziel:** Bundles landen automatisch in `Lärmprotokoll/Support-Bundle`.
**Behebt:** Lücke L6.
**Offene Punkte vorher klären:** O-4 (ntfy-Meldung bei Absturz — im Zweifel **nein**, siehe
Konzept 8).

### Aufgaben

1. **Support-Ordner in der Drive-Ablage.** Owner-Vorgabe ist `<Wurzel>/Support-Bundle`, also
   **direkt unter der Wurzel** — nicht unter dem Tagesordner wie die bestehenden Kategorien in
   `DriveAblage.kt`. Das ist beabsichtigt (Konzept 4.6) und muss im Code kommentiert werden,
   damit es nicht später jemand „korrigiert". Sauberste Lösung: eine eigene Auflösungsfunktion
   neben `DriveOrdnerbaum.ordnerFuer()`, nicht ein neuer Wert in `DriveKategorie` (der würde die
   Invariante „Kategorie liegt unter dem Tagesordner" brechen).

2. **`SupportBundleUploadWorker`** (WorkManager):
   - Nimmt Dateien aus `support_outbox/`, lädt sie über den vorhandenen `GoogleDriveApiClient`
     hoch, löscht sie bei Erfolg.
   - Benennung: `JJJJ-MM-TT_HHMMSS_<typ>[_<kurzcode>].zip`.
   - Constraints: `UNMETERED` bevorzugt. **Fallback:** Liegt ein Absturz-Bundle länger als 6 h,
     wird ein zweiter Job ohne Netzbeschränkung eingereiht. Ein Gerät, das wochenlang ohne WLAN
     überwacht, darf einen Absturzbericht nicht unbegrenzt zurückhalten.
   - Exponentielles Backoff bei Fehlschlag, Obergrenze für Versuche.
   - Läuft im **Hauptprozess** — dort liegen Keystore, Token und Drive-Client (Konzept 4.2).

3. **ACRA-Sender fertigstellen:** Der Sender aus Schritt 1 baut jetzt über den Exporter ein
   Bundle, legt es in `support_outbox/` und reiht den Worker ein. **Kein Netzwerkzugriff im
   ACRA-Prozess.**

4. **Aufräumen:** `support_outbox/` bekommt eine Obergrenze (Anzahl und Gesamtgröße). Läuft sie
   voll, werden die ältesten periodischen Bundles zuerst verworfen — Absturz-Bundles zuletzt.

5. **Sichtbarkeit:** Zeitpunkt und Ergebnis des letzten Uploads im `SettingsManager` ablegen, für
   die Anzeige in Schritt 8.

### Akzeptanzkriterien

- Ein Bundle landet in `<Wurzel>/Support-Bundle`. **Ohne Drive-Konto nicht verifizierbar — dann
  genau das in den PR schreiben** und stattdessen den Fake-Client-Test zeigen.
- Bei fehlendem Netz bleibt das Bundle erhalten und wird später versendet.
- Nichts wird doppelt hochgeladen.
- Der ACRA-Prozess öffnet keine Netzwerkverbindung.
- `assembleDebug` und `test` grün, Ausgabe im PR.

### Tests

- Worker mit `androidx.work:work-testing` und einem handgeschriebenen Fake-Drive-Client:
  Erfolg, Fehlschlag mit Retry, Löschen nach Erfolg, kein Doppelversand.
- Ordnerauflösung: Der Support-Ordner liegt unter der Wurzel, **nicht** unter dem Tagesordner —
  expliziter Test, das ist die Stelle, an der es leise falsch werden kann.
- Outbox-Aufräumen: Obergrenze greift, Absturz-Bundles überleben periodische.
- Namensschema für alle vier Bundle-Typen.

---

## Schritt 6 — Periodisches Gesundheits-Bundle

**Branch:** `feature/m12-6-periodisches-bundle`
**Ziel:** Schleichende Probleme werden sichtbar, bevor es knallt.
**Owner-Entscheidung vom 17.09.2026:** ausdrücklich gewünscht.
**Offene Punkte vorher klären:** O-3 (Aufbewahrungsfrist in Drive).

### Aufgaben

1. **`PeriodicWorkRequest`, alle 24 h**, Constraint `UNMETERED`, **kein** Fallback auf Mobilfunk.
2. **Schlanke Bundle-Variante** (Typ `periodisch`): deutlich kürzerer Logcat-Auszug, weniger
   Events, vollständiger `state/`-Teil. Ziel laut O-6 rund 1 MB.
3. **Zusätzliche Kennzahlen**, die nur im Zeitverlauf etwas aussagen: Reconnect-Zähler,
   Decode-Fehlerrate, Anzahl Diagnose-Einträge, DB-Wachstum seit dem letzten Bundle,
   Heap-Hochstand, Anzahl abgefangener Fehler je Code. Genau diese Reihe hätte den in
   `SettingsManager.kt:448` beschriebenen Speicherverlauf vor dem Absturz sichtbar gemacht.
4. **Abschaltbar** über einen Einstellungsschalter (UI kommt in Schritt 8, der Schalter hier).
5. **Kein Bundle ohne Not:** Hat sich seit dem letzten periodischen Bundle nichts Relevantes
   geändert und gab es keinen Fehler, wird keines erzeugt. Sonst füllt sich Drive mit
   Nullmeldungen.

### Akzeptanzkriterien

- Der Job läuft alle 24 h und wird nach einem Neustart nicht doppelt eingereiht
  (`ExistingPeriodicWorkPolicy.KEEP`).
- Das periodische Bundle bleibt im Budget.
- Abschalten verhindert Erzeugung **und** Upload.
- `assembleDebug` und `test` grün, Ausgabe im PR.

### Tests

- Worker mit `work-testing`: erzeugt Bundle, reiht Upload ein, respektiert den Schalter.
- Kennzahlen-Berechnung als reine Funktion, mit Fake-Daten.
- „Kein Bundle ohne Not": bei unveränderter Lage entsteht keines.

---

# ETAPPE C — Fix und Bedienung

---

## Schritt 7 — DiagnoseScreen entschärfen (der akute Absturz)

**Branch:** `fix/m12-7-diagnose-log-paginierung`
**Ziel:** Der vom Owner gemeldete Absturz ist behoben.

> **Vor diesem Schritt: das erste echte Bundle auswerten.** Der Owner hat entschieden, erst die
> Beweissicherung zu bauen. Bestätigt das Bundle den Verdacht aus Konzept Abschnitt 2, bau, was
> hier steht. Zeigt es eine andere Ursache, **ersetze diesen Schritt durch den dann belegten
> Fix** und melde die Abweichung. Blind reparieren wäre genau der Fehler, den die Reihenfolge
> vermeiden soll.

### Aufgaben (unter der Annahme, dass sich der Verdacht bestätigt)

1. **DAO:** `alle()` ohne `LIMIT` (`DiagnosticLogDao.kt`) wird nicht mehr von der UI benutzt.
   Neue Methode mit `LIMIT` für die Anzeige, plus die in Schritt 4 ergänzte Paginierung für den
   Export. Die alte Methode bleibt nur, falls anderer Code sie braucht — **prüfen, nicht
   annehmen**.
2. **`DiagnoseScreen.kt:82`:** Anzeige auf die neuesten N Einträge begrenzen (Vorschlag 200), mit
   Knopf zum Nachladen. Damit erzeugt ein neuer Eintrag im Aufzeichnungsbetrieb keine
   vollständige Neuemission der Tabelle mehr.
3. **Export entkoppeln:** `createBundle()` bekommt nicht mehr die angezeigte Liste
   (`DiagnoseScreen.kt:301`), sondern liest selbst seitenweise — das ist bereits in Schritt 4
   vorbereitet.
4. **Gegenprobe:** Ein Test, der belegt, dass bei sehr vielen Einträgen nur die begrenzte Menge
   geladen wird.

### Akzeptanzkriterien

- Der DiagnoseScreen bleibt im Aufzeichnungsbetrieb mit vielen Einträgen bedienbar.
- Der Export funktioniert unabhängig von der Anzeige.
- **Bestätigt durch ein Bundle vom Gerät**, nicht durch Argumentation. Ist das nicht möglich:
  genau so in den PR.
- `assembleDebug` und `test` grün, Ausgabe im PR.

### Tests

- DAO-Test mit vielen Einträgen: die begrenzte Abfrage liefert genau N, die neuesten zuerst.
- Compose-Test: Bei mehr Einträgen als der Grenze wird nur die Grenze dargestellt.
- Export-Test: funktioniert ohne die UI-Liste.

---

## Schritt 8 — Bedienung, Einstellungen, Dokumentation

**Branch:** `feature/m12-8-ui-und-doku`
**Offene Punkte vorher klären:** O-5 (instrumentierter Absturztest, AGENTS.md 8b verlangt
Owner-Freigabe zum Testumfang).

### Aufgaben

1. **DiagnoseScreen:** Abschnitt „Support-Bundles" mit Zeitpunkt und Ergebnis des letzten
   Uploads, Anzahl wartender Bundles in der Outbox, Knopf „Bundle jetzt erstellen und
   hochladen", weiterhin der manuelle Teilen-Knopf.
2. **Einstellungen:** Schalter für automatischen Upload bei Absturz, Schalter für das periodische
   Bundle, Anzeige des Drive-Zielordners. Deutsche UI-Texte in `strings.xml`, keine Literale im
   Code.
3. **Dokumentation:**
   - `DIAGNOSE_OBSERVABILITY_KONZEPT.md`: **Statusangaben korrigieren.** Das Dokument führt
     Sentry als „✅ Implementiert (DSN konfigurierbar)" und suggeriert eine Vollständigkeit, die
     es nicht gibt (Konzept 1.3). Sentry ist als ruhend zu kennzeichnen, mit Verweis auf O-1.
   - `DIAGNOSE_CRASH_KONZEPT.md`: Status auf umgesetzt, offene Punkte fortschreiben.
   - `README.md`: kurzer Absatz zum neuen Diagnoseweg.
   - `CHECKLISTE_GERAETETEST.md` Teil F: Punkte ergänzen, die nur auf Hardware prüfbar sind —
     Absturz im Aufzeichnungsbetrieb hinterlässt ein Bundle; Bundle kommt in Drive an;
     ANR-Trace ist lesbar; periodisches Bundle erscheint täglich; Upload ohne WLAN wird
     nachgeholt.
   - `AGENTS.md`: Room-Schemaversion in Abschnitt 3 stimmt nicht mehr (dort 19, tatsächlich 25
     laut `AppDatabase.kt:562`). **Nur diese Zahl korrigieren, sonst nichts anfassen.**
4. **Instrumentierter Test** nur nach Freigabe zu O-5.

### Akzeptanzkriterien

- Der Owner kann im DiagnoseScreen sehen, ob und wann zuletzt etwas hochgeladen wurde.
- Automatischer Upload ist abschaltbar.
- Kein deutscher UI-Text steht im Kotlin-Code.
- Dokumentation stimmt mit dem Code überein.
- `assembleDebug` und `test` grün, Ausgabe im PR.

### Tests

- Compose-Tests für den neuen Abschnitt und die Schalter (Robolectric, wie die bestehenden
  `DiagnoseScreenComposeTest`).
- Ein Test, der belegt, dass der Abschaltschalter tatsächlich Erzeugung und Upload verhindert.

---

## 9 · Definition of Done je Schritt

Nach AGENTS.md Abschnitt 7, unverändert gültig:

1. `./gradlew assembleDebug` und `./gradlew test` grün — **Ausgabe im PR, nicht die
   Zusammenfassung**.
2. Beide Room-Migrationstests weiterhin grün.
3. Jedes Akzeptanzkriterium des Schritts einzeln adressiert.
4. Branch gepusht, **Draft-PR** gegen `main`. PR-Text enthält: was geändert wurde · was verifiziert
   wurde (Befehl + Ergebnis) · was bewusst offen blieb · jede angetroffene Planabweichung oder
   offene Entscheidung.
5. Kurze Rückmeldung an den Owner: erledigt / nicht erledigt / aufgefallen.

**Und der Punkt, an dem dieses Vorhaben scheitert, wenn man ihn ignoriert:** Was du nicht auf
Hardware prüfen konntest, schreibst du als ungeprüft in den PR. Ein Diagnosesystem, von dem wir
nur glauben, dass es funktioniert, ist schlechter als keines — weil wir uns beim nächsten Absturz
darauf verlassen.
