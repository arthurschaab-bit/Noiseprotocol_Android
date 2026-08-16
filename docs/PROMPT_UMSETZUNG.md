# Umsetzungs-Prompt für Folge-Sessions

Dieses Dokument enthält den Prompt, mit dem die Implementierung des
[Implementierungsplans](./IMPLEMENTIERUNGSPLAN_PCE-323_BLUETOOTH.md) in einer neuen
Chat-Session (Sonnet 5) durchgeführt wird.

**Verwendung:** Abschnitt A + B + den gewünschten Meilenstein aus Abschnitt C kopieren und als
erste Nachricht in die neue Session geben. Ein Meilenstein pro Session — nicht mehr.

---

## A. Kontext und Arbeitsauftrag

```text
Du setzt einen bereits fertigen Implementierungsplan um. Der Plan ist beschlossen — deine
Aufgabe ist die Umsetzung, nicht die Neuplanung.

PROJEKT
Android-App "Lärmprotokoll" (com.example.lrmprotokoll), Kotlin + Jetpack Compose + Room.
Repository: arthurschaab-bit/Noiseprotocol_Android, Default-Branch: main.

PLAN
docs/IMPLEMENTIERUNGSPLAN_PCE-323_BLUETOOTH.md im Repo (ca. 1.230 Zeilen).
LIES DIESEN PLAN ZUERST VOLLSTÄNDIG, bevor du irgendetwas änderst.
Besonders relevant: Abschnitt 0 (Bestandsaufnahme, Befunde B-1..B-11), Abschnitt 4
(Zielarchitektur), Abschnitt 12 (Meilensteine).

WORUM ES INHALTLICH GEHT
Die App misst heute über das Telefonmikrofon mit einer unkalibrierten Pegelformel
(dBFS + willkürlicher Offset 100, ohne A-Bewertung). Ein externes Schallpegelmessgerät
PCE-323 soll über Bluetooth LE angebunden werden und diese Pegelquelle durch echte,
A-bewertete dBA-Werte ersetzen. Dazu kommen: robuste/sichere Verbindungsführung,
Alarmierung per SMS und Push bei Verbindungsabbruch, und ein 30-minütiger Sync der
Messwerte nach Google Drive (eine Datei pro Tag).
```

## B. Arbeitsregeln

```text
ARBEITSWEISE
1. Plan zuerst lesen. Wenn dein Auftrag einem Abschnitt widerspricht, folge dem Plan und
   sage mir, wo der Widerspruch liegt — ändere den Plan nicht eigenmächtig.
2. Arbeite nur den unten genannten Meilenstein ab. Keine Vorgriffe auf spätere
   Meilensteine, auch wenn es "gerade naheliegt".
3. Kleine, nachvollziehbare Commits mit deutschen Commit-Messages. Ein Commit pro
   abgeschlossenem Teilschritt, nicht ein großer Sammelcommit.
4. Neuer Branch von main, Namensschema: feature/m<N>-<kurzbeschreibung>.
   NIEMALS direkt auf main committen oder pushen.
5. Wenn du auf eine Entscheidung stößt, die im Plan als offen markiert ist (Abschnitt 13):
   NICHT selbst entscheiden. Frag mich.
6. Passe dich dem vorhandenen Stil an: deutsche Bezeichner in UI-Texten, englische im Code,
   Kommentardichte wie im Bestand. Kein Refactoring "nebenbei".

VERIFIKATION — nicht verhandelbar
- Nach jeder Änderung muss `./gradlew assembleDebug` durchlaufen. Läuft der Build nicht,
  ist der Schritt nicht fertig.
- Falls JAVA_HOME fehlt (bekanntes Problem, siehe manifest_error.txt im Repo): auf das
  JBR von Android Studio zeigen, z. B.
  Windows: $env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
  Linux/macOS: export JAVA_HOME=/opt/android-studio/jbr
- Neue Logik bekommt Unit-Tests. `./gradlew test` muss grün sein.
- Behaupte NIE, etwas funktioniere, ohne es ausgeführt zu haben. Wenn du etwas nicht
  verifizieren konntest, schreib genau das hin.

ABSCHLUSS
- Branch pushen, Draft-PR gegen main öffnen.
- Im PR-Text: was geändert wurde, was verifiziert wurde (mit Befehl und Ergebnis), was
  bewusst offen blieb.
- Danach eine kurze Zusammenfassung an mich: erledigt / nicht erledigt / aufgefallen.
```

## C. Meilensteine

### C.1 — M-1: Bestand instandsetzen ← **hier anfangen**

Voraussetzungsfrei, blockiert alles andere. Aufwand laut Plan: 1 Tag.

```text
AUFTRAG: Meilenstein M-1 — Bestand instandsetzen (Plan Abschnitt 0.3 und 12).

Arbeite die folgenden Befunde ab. Reihenfolge einhalten — B-1 zuerst, sonst baut nichts.

B-1  BUILD-BLOCKER
     app/src/main/AndroidManifest.xml enthält noch das Attribut
     package="com.example.lrmprotokoll", während namespace bereits in app/build.gradle.kts
     gesetzt ist. Seit AGP 8 ist das ein harter Fehler; das Projekt nutzt AGP 9.2.1.
     → Attribut ersatzlos entfernen.
     Abnahme: ./gradlew assembleDebug läuft durch.

B-2  DATENVERLUST-RISIKO — der wichtigste Punkt dieses Meilensteins
     AppDatabase.kt nutzt fallbackToDestructiveMigration() bei Schema-Version 6. Dadurch
     löscht JEDE künftige Schemaänderung sämtliche aufgezeichneten Messdaten. Die späteren
     Meilensteine fügen zwingend neue Spalten hinzu.
     → fallbackToDestructiveMigration() entfernen.
     → exportSchema = true setzen und den Room-Schema-Ordner konfigurieren
       (ksp { arg("room.schemaLocation", "$projectDir/schemas") }), das erzeugte
       Schema-JSON für Version 6 als Baseline committen.
     → Schema-Version NICHT anheben. In diesem Schritt entsteht nur die Grundlage;
       die eigentliche Migration kommt mit den neuen Spalten in einem späteren Meilenstein.
     Abnahme: Ein instrumentierter Migrationstest (androidx.room.testing.MigrationTestHelper)
     existiert und läuft. Eine bestehende v6-Datenbank mit Testdatensätzen muss nach dem
     Öffnen durch die App unverändert vorhanden sein.
     WICHTIG: Prüfe, ob das deklarierte Schema zum tatsächlichen v6-Stand passt — die
     destruktive Fallback-Einstellung kann Abweichungen bisher verdeckt haben. Falls es
     abweicht, melde das, bevor du weitermachst.

B-3  compileSdk und targetSdk stehen auf 34 bei AGP 9.2.1.
     → beide auf 36 anheben. minSdk bleibt in diesem Meilenstein auf 29 (Anhebung auf 31
       gehört zu M1).
     Abnahme: Build läuft, App startet auf einem Emulator.

B-4  app/build.gradle.kts deklariert 6 CameraX-Abhängigkeiten. Im gesamten Quellcode gibt
     es keinen einzigen Camera-Aufruf (verifiziert).
     → alle androidx.camera:* Abhängigkeiten entfernen.
     Abnahme: Build läuft, APK ist messbar kleiner (Größe vorher/nachher nennen).

B-5  android:allowBackup="true" im Manifest. Später kommen Rufnummern und OAuth-Zustand
     dazu, die nicht über Auto-Backup abfließen dürfen.
     → allowBackup="false" setzen, dataExtractionRules und backup_rules entsprechend
       anpassen (die XML-Dateien existieren bereits unter app/src/main/res/xml/).

B-8  POST_NOTIFICATIONS wird in MainActivity.kt zur Laufzeit angefragt, fehlt aber im
     Manifest. Ab targetSdk 33 wird die Anfrage dadurch stillschweigend abgelehnt und die
     Foreground-Notification bleibt unsichtbar.
     → <uses-permission android:name="android.permission.POST_NOTIFICATIONS" /> ergänzen.
     Abnahme: Auf einem Android-13+-Gerät/Emulator erscheint der Berechtigungsdialog und
     die Notification des laufenden Dienstes ist sichtbar.

B-6  applicationId ist "com.example.lrmprotokoll". com.example.* ist im Play Store
     unzulässig, nach Veröffentlichung nie wieder änderbar, und Google-Drive-OAuth (M7b)
     braucht eine stabile Package-ID.
     → ⚠ NICHT einfach ändern. Eine Änderung der applicationId lässt Android die App als
       komplett neue Installation behandeln: bereits aufgezeichnete Messdaten und WAV-
       Dateien auf meinem Testgerät wären dann nicht mehr erreichbar.
       FRAG MICH ZUERST, ob auf einem Gerät erhaltenswerte Aufnahmen liegen. Erst nach
       meiner Antwort umsetzen oder zurückstellen.

NICHT TEIL VON M-1 (nicht anfassen):
- B-7 (KSP-Version) nur prüfen und berichten, wenn der Build Probleme macht
- B-9, B-10 (Audio-Schleife), B-11 (TFLite-Migration) — spätere Meilensteine
- minSdk-Anhebung, Paketumstrukturierung, Bluetooth, Alarmierung, Drive-Sync

DEFINITION OF DONE
- ./gradlew assembleDebug und ./gradlew test laufen grün (Ausgabe zeigen)
- Migrationstest existiert und beweist, dass v6-Daten erhalten bleiben
- App startet und nimmt weiterhin auf wie vorher — die bestehende Funktionalität
  (Mikrofon-Trigger, WAV, YAMNet, Player, Bericht) ist unverändert
- Draft-PR gegen main offen
```

### C.2 — Folgemeilensteine

Gleicher Aufbau, jeweils Abschnitt A + B voranstellen. Reihenfolge laut Plan Abschnitt 12:

| Meilenstein | Auftrag in einem Satz | Plan-Abschnitt |
|---|---|---|
| **M0** | Protokoll-Discovery am realen PCE-323: GATT-Tabelle dumpen, HCI-Snoop-Log auswerten, Profil und Testvektoren festschreiben | 3 |
| **M1** | Paketstruktur, `AppContainer`, minSdk 29 → 31, `MeterTransport` + Fake-Implementierung | 4.2, 4.3 |
| **M2** | BLE-Basis: Scan, Verbindung, `GattQueue`, Notify, `FrameDecoder`, Live-Anzeige | 2.2, 5.2 |
| **M3** | Robustheit: Zustandsautomat, Backoff, Adapter-Beobachtung, Foreground Service, Boot-Receiver | 5 |
| **M4** | Persistenz: neue Spalten (Migration v6→v7), Batch-Writer, Sessions, LAeq/max/min | 4.5, 8 |
| **M5** | Alarmierung: Watchdog, Karenzzeit 60 s via AlarmManager, SMS + ntfy, Totmannschaltung | 7 |
| **M6** | Sicherheit: Bonding, Geräte-Pinning, Keystore, verschlüsselte Einstellungen | 6 |
| **M7** | UI-Ausbau: Protokollansicht, Einstellungen, Diagnose, Export | 9 |
| **M7b** | Google-Drive-Sync: OAuth `drive.file`, 10-s-Aggregate, `DriveSyncWorker`, Idempotenz | 8.4 |
| **M8** | Härtung: Chaos-Checkliste, 24-h-Dauerlauf, Release-Build | 11 |

**M0 ist Voraussetzung für M2** — ohne bestätigtes BLE-Profil ist die
Transport-Implementierung Spekulation. **M7b hängt nicht am Bluetooth-Pfad** und kann direkt
nach M-1 mit den heutigen Mikrofonwerten gebaut werden, wenn parallel gearbeitet werden soll.

### C.3 — Vor M4, M5 und M7b zu klärende Entscheidungen

Diese sieben Punkte stehen in Plan-Abschnitt 13 als offen. Die betroffenen Meilensteine
sollten erst nach der Klärung starten, sonst arbeitet die Session auf Annahmen:

| Betrifft | Frage | Vorschlag im Plan |
|---|---|---|
| M5 | Entwarnungsmeldung bei Wiederkehr? | bei Push ja, bei SMS nein |
| M4 | Aufbewahrungsdauer Rohwerte, SQLCipher ja/nein? | 90 Tage, danach Minutenaggregate |
| M5 | Cooldown und Eskalation | 30 min / 60 min / max. 3 |
| M5 | ntfy öffentlich oder self-hosted? | — |
| M7b | Drive-Aggregationsintervall | 10 s |
| M7b | Ordnerwahl: `drive.file` oder voller `drive`-Scope? | `drive.file` |
| M7b | WAV-Aufnahmen mit hochladen? | nein |
