# Review-Prompt für Fortschrittskontrolle

Gegenstück zu [PROMPT_UMSETZUNG.md](./PROMPT_UMSETZUNG.md). Wird in einer **eigenen, frischen
Session** ausgeführt — nicht in derselben, die implementiert hat. Ein Reviewer, der seinen
eigenen Code prüft, findet seine eigenen blinden Flecken nicht.

Prüft drei Fragen:

1. Wurde umgesetzt, was der Plan vorsieht? (Plankonformität)
2. Stimmen die Behauptungen der Umsetzungs-Session? (Verifikation)
3. Welche Fehler stecken drin — neu eingeführte wie bestehende? (Bug-Suche)

---

## Der Prompt

```text
Du führst ein Code-Review durch. Du implementierst nicht — du prüfst und berichtest.

PROJEKT
Android-App "Lärmprotokoll", Kotlin + Jetpack Compose + Room.
Repository: arthurschaab-bit/Noiseprotocol_Android, Default-Branch: main.

REFERENZDOKUMENTE (zuerst lesen, in dieser Reihenfolge)
1. docs/IMPLEMENTIERUNGSPLAN_PCE-323_BLUETOOTH.md — der beschlossene Plan.
   Besonders Abschnitt 0.3 (Befunde B-1..B-11) und Abschnitt 12 (Meilensteine).
2. docs/PROMPT_UMSETZUNG.md — der Auftrag, den die Umsetzungs-Session bekommen hat.
   Abschnitt C.1 enthält die Abnahmekriterien, gegen die du prüfst.

SCHRITT 1 — BESTANDSAUFNAHME
Finde selbst heraus, was seit dem Merge des Plans passiert ist. Verlasse dich nicht auf
Zusammenfassungen, Commit-Messages oder PR-Beschreibungen — die behaupten, du verifizierst.

- git log --oneline main..<branch> für jeden offenen Feature-Branch
- git diff main...<branch> vollständig lesen
- Offene und gemergte Pull Requests auflisten, deren Beschreibungen lesen
- Notiere: Welcher Meilenstein sollte bearbeitet werden? Was wurde tatsächlich angefasst?

Wenn der Umfang deutlich vom Auftrag abweicht (mehr oder weniger als beauftragt), ist das
selbst ein Befund.

SCHRITT 2 — VERIFIKATION (bevor du den Code liest)
Führe selbst aus, statt Behauptungen zu glauben:
  ./gradlew assembleDebug
  ./gradlew test
  ./gradlew connectedAndroidTest     (falls ein Gerät/Emulator verfügbar ist)

Falls JAVA_HOME fehlt:
  Windows: $env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
  Linux/macOS: export JAVA_HOME=/opt/android-studio/jbr

Notiere für jeden Befehl das tatsächliche Ergebnis. Schlägt etwas fehl, ist das Befund
Nr. 1 und alle Erfolgsmeldungen der Umsetzungs-Session sind ab da unglaubwürdig.

SCHRITT 3 — PLANKONFORMITÄT
Gehe die Abnahmekriterien aus PROMPT_UMSETZUNG.md Abschnitt C.1 einzeln durch und
vergebe je Punkt genau ein Urteil: ERFÜLLT / TEILWEISE / NICHT ERFÜLLT / NICHT BEAUFTRAGT.
"Teilweise" braucht immer eine Begründung, was fehlt.

Für M-1 sind das B-1, B-2, B-3, B-4, B-5, B-8 und der Rückfragepunkt B-6.

SCHRITT 4 — GEZIELTE FEHLERSUCHE
Prüfe mindestens die folgenden Punkte. Sie sind aus dem Code hergeleitet, nicht geraten —
aber verifiziere jeden einzeln am aktuellen Stand, statt sie zu übernehmen.

A) B-2, Datenerhalt — der wichtigste Prüfpunkt
   Behauptet wird, dass bestehende Aufnahmen eine Schemaänderung überleben.
   - Ist fallbackToDestructiveMigration() wirklich entfernt, auch in etwaigen weiteren
     Room.databaseBuilder-Aufrufen?
   - Ist exportSchema = true gesetzt UND liegt das erzeugte Schema-JSON für Version 6
     tatsächlich im Repo (nicht nur der Ordner)?
   - Der Migrationstest: Prüft er wirklich, dass DATEN erhalten bleiben, oder nur, dass
     die Migration ohne Exception durchläuft? Das ist ein Unterschied. Der Test muss
     Datensätze in die v6-DB schreiben und sie nach dem Öffnen wieder auslesen und
     inhaltlich vergleichen.
   - Wurde die Schema-Version versehentlich angehoben? In M-1 darf sie auf 6 bleiben.

B) FileProvider-Kopplung — greift, falls B-6 (applicationId) umgesetzt wurde
   Im Manifest steht die Authority hartcodiert:
     android:authorities="com.example.lrmprotokoll.fileprovider"
   ReportManager.kt:44 baut sie dagegen dynamisch:
     FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
   Wurde die applicationId geändert, ohne beide Stellen anzugleichen, stürzt jeder
   Teilen-/Export-Vorgang zur Laufzeit ab (IllegalArgumentException, "Couldn't find
   meta-data for provider"). Der Build merkt davon nichts.
   Korrekt wäre android:authorities="${applicationId}.fileprovider".
   → Teilen eines Berichts tatsächlich ausprobieren, nicht nur den Code lesen.

C) targetSdk 34 → 36, Verhaltensänderungen
   Die Anhebung ist nicht folgenlos. Prüfe am laufenden Gerät, nicht nur im Code:
   - Android 15 (targetSdk 35+) erzwingt Edge-to-Edge. Zeichnet die UI jetzt unter
     Status- und Navigationsleiste? Sind Bedienelemente verdeckt?
   - Foreground-Service-Typen werden ab API 34 strenger geprüft. Startet
     AudioRecordingService noch zuverlässig, auch aus dem Hintergrund?
   - Wird die Mikrofonberechtigung zum Startzeitpunkt des Dienstes wirklich gehalten?
   - Läuft die App noch auf minSdk 29 (in M-1 wurde minSdk NICHT angehoben)?

D) B-5, Backup-Regeln
   allowBackup="false" allein reicht nicht. Prüfe, ob backup_rules.xml und
   data_extraction_rules.xml konsistent dazu sind und der Build sie nicht ignoriert.

E) B-4, CameraX-Entfernung
   Compiliert ohne unaufgelöste Referenzen? Ist die APK tatsächlich kleiner geworden?
   Zahl nennen, nicht behaupten.

F) B-8, POST_NOTIFICATIONS
   Manifest-Eintrag vorhanden UND die Laufzeitabfrage in MainActivity korrekt hinter
   Build.VERSION.SDK_INT >= 33 gekapselt? Auf einem Android-13+-Gerät: erscheint der
   Dialog, und ist die Notification des laufenden Dienstes sichtbar?

SCHRITT 5 — BESTEHENDE FEHLER IM ALTBESTAND
Die folgenden vier Punkte existierten bereits vor M-1. Sie sind am Code verifiziert.
Prüfe für jeden: Besteht er noch? Wurde er unabsichtlich verschlimmert? Wurde er
stillschweigend mitgefixt (dann gehört das in den Bericht)?

1. AudioRecordingService.kt — Synchronisation auf einem veränderlichen Feld.
   updateRollingBuffer() weist rollingBuffer (Zeile ~50) neu zu, OHNE Lock.
   writeToRollingBuffer() und getPreRollData() synchronisieren aber auf genau diesem
   Feld (synchronized(rollingBuffer)). Nach einer Neuzuweisung sperren die Threads auf
   verschiedenen Objekten — der Schutz ist wirkungslos, Datenkorruption im Ringpuffer
   möglich. Korrekt wäre ein separates, finales Lock-Objekt.

2. AudioRecordingService.kt — audioRecord wird nicht zuverlässig freigegeben.
   stop() und release() stehen nach der while-Schleife, nicht in einem finally. Da
   delay(50) in der Schleife auf Cancellation reagiert, wirft onDestroy() ->
   serviceJob.cancel() eine CancellationException und beide Aufrufe werden übersprungen.
   Folge: Das Mikrofon bleibt belegt, der nächste Start kann fehlschlagen.
   Reproduzierbar durch mehrfaches Starten/Stoppen des Dienstes.

3. NoiseClassifier.kt:89 — unvollständiges Lesen.
   fis.read(byteBuffer.array()) liest möglicherweise weniger Bytes als angefordert; der
   Rückgabewert wird ignoriert. Bei größeren WAV-Dateien enthält der Puffer dann teilweise
   Nullen und die Klassifikation arbeitet auf Müll — ohne Fehlermeldung. Korrekt wäre
   readFully bzw. eine Leseschleife.

4. NoiseClassifier.kt:54 — runBlocking innerhalb von classify(), das aus dem suspend-
   Kontext von saveRecording() aufgerufen wird. Blockiert einen Dispatcher-Thread.
   Funktional unkritisch, aber ein vermeidbarer Stolperstein.

SCHRITT 6 — FREIE PRÜFUNG
Über die Liste hinaus: Lies den gesamten Diff mit Blick auf Nebenwirkungen. Achte auf
- Änderungen, die niemand beauftragt hat ("Refactoring nebenbei")
- gelöschte oder abgeschwächte Tests
- neue Hardcodings, wo vorher Konfiguration war
- alles, was die bestehende Funktionalität (Mikrofon-Trigger, WAV-Aufnahme, YAMNet,
  Player, Tagesbericht, ZIP-Export) beeinträchtigen könnte

BERICHTSFORMAT
Erst eine Tabelle, dann die Details.

| # | Schwere | Datei:Zeile | Befund | Beleg |
Schwere: KRITISCH (Datenverlust, Absturz, Sicherheit) / HOCH (Funktion kaputt) /
MITTEL (Fehlverhalten im Randfall) / NIEDRIG (Qualität).

Für jeden Befund: konkreter Auslöser (welche Eingabe, welcher Ablauf) und beobachtete
Auswirkung. Kein "könnte problematisch sein" ohne Szenario.

Danach:
- Plankonformitäts-Tabelle aus Schritt 3
- Verifikationsergebnisse aus Schritt 2, mit tatsächlicher Ausgabe
- Gesamturteil: Kann der Stand gemergt werden, ja oder nein, und was muss vorher weg?

REGELN
- Du änderst KEINEN Code und öffnest keinen PR. Nur prüfen und berichten.
- Findest du etwas Kritisches, melde es sofort, statt bis zum Ende weiterzuprüfen.
- Sag ausdrücklich, was du NICHT prüfen konntest (kein Gerät, kein Emulator, fehlende
  Abhängigkeit). Eine ehrliche Lücke ist brauchbar, eine stillschweigende nicht.
- Findest du nichts Nennenswertes, schreib das hin. Erfinde keine Befunde, um die
  Tabelle zu füllen.
```

---

## Hinweise zur Anwendung

**Eigene Session.** Nicht in der Session ausführen, die implementiert hat.

**Nach jedem Meilenstein wiederholbar.** Für spätere Meilensteine bleiben Schritt 1, 2, 5 und 6
unverändert; getauscht werden nur die Abnahmekriterien in Schritt 3 und die gezielten
Prüfpunkte in Schritt 4. Für M2/M3 gehören dorthin insbesondere: serialisierte
GATT-Operationsqueue, `gatt.close()` vor jedem Reconnect, Staleness-Erkennung, Verhalten bei
Bluetooth-Aus/Ein. Für M5: Karenzzeit über `AlarmManager` statt `delay()`, Entprellung gegen
Alarmstürme. Für M7b: Idempotenz der Tagesdatei, Uploadvolumen.

**Die vier Altbestand-Fehler aus Schritt 5** sind am Code verifiziert, aber bewusst nicht Teil
von M-1. Sie stehen im Review, damit sie nicht in Vergessenheit geraten — ob und wann sie
behoben werden, ist eine eigene Entscheidung.
