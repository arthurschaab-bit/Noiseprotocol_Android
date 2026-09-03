# Lärmprotokoll

Android-App zur Dokumentation von Lärmereignissen. Zeichnet bei Überschreiten einer Pegelschwelle
automatisch einen Audioschnitt mit Vorlauf auf, klassifiziert das Geräusch per KI und legt alles
als durchsuchbares Protokoll ab.

**In Arbeit:** Anbindung eines externen Schallpegelmessgeräts **PCE-323** über Bluetooth, um
kalibrierte dBA-Werte statt unkalibrierter Mikrofonwerte zu protokollieren.

---

## Status

| Bereich | Stand |
|---------|-------|
| Aufnahme über Mikrofon, Pre-Roll, WAV | ✅ läuft |
| KI-Klassifikation (YAMNet, 16-KB Page Size) | ✅ läuft |
| Wellenform-Player, Tagesbericht, CSV/ZIP-Export | ✅ läuft |
| **M-1** Bestand instandsetzen | ✅ abgeschlossen |
| **M0** Protokoll-Discovery am PCE-323 | ✅ abgeschlossen |
| **M1** Fundament, `MeterTransport`, Decoder | ✅ abgeschlossen |
| **M2** BLE-Transport (Scan, Verbindung, Notify, On-Demand Permissions) | ✅ abgeschlossen, real am Gerät bestätigt |
| **M3** Robustheit (Reconnect, Ausfallerkennung, FGS-Typen dynamisch) | ✅ abgeschlossen, real am Gerät bestätigt |
| **M5** Alarmierung bei Verbindungsabbruch (ntfy + Totmannschaltung, Tablet/OEM-Härtung) | ✅ abgeschlossen & gehärtet (PR #55) |
| **M7b** Google-Drive-Sync (Upload, Kontopersistenz, DriveStatusCard, Sofortiger WAV-Sync & Rohwert-CSV) | ✅ abgeschlossen & erweitert |
| **M4** Persistenz der Messreihe (Sessions, Verbindungsereignisse, Kennwerte, Retention) | ✅ abgeschlossen |
| **M6** Sicherheit (Pinning-Härtung, Kadenz-Watcher, verschlüsselte Ablage, Diagnose-Log standardmäßig aktiv) | ✅ abgeschlossen & gehärtet |
| **M7** UI-Ausbau (Protokollansicht, Diagnose-Screen, CSV/PDF-Export, Wohnraum-Presets, Pro/Lite-Modus) | ✅ abgeschlossen & erweitert |
| **M7c** UI-Harmonisierung & Entkopplung (Startseite, 4 Tabs, stabile Sortierung) | ✅ abgeschlossen (PR #53) |
| **M8** Härtung — Release-Build (R8/Minify) & Herstellerhinweis (Xiaomi, Huawei, Oppo, Vivo, OnePlus, Samsung) | ✅ hardwarefreier Teil abgeschlossen — Chaos-Checkliste/24h-Dauerlauf brauchen ein Gerät, siehe Gerätetest-Zeile unten |
| **Diagnose & Observability** (Sentry, DiagnosticsReporter, Redactor, Support-Paket) | ✅ abgeschlossen |
| **CI-Qualitäts-Gates** (Android Lint 0 Fehler, 394 JVM Tests, 34 Emulator Tests) | ✅ vollständig grün & aktiv |
| **Gerätetests & Härtung** (PCE-323 Kopplung, Google Drive, Xiaomi Pad 6 Härtung) | ✅ erfolgreich durchgeführt & umgesetzt |
| **UX Redesign (26-Punkte Designbrief)** (OLED Dark Mode, Live-Cockpit, Quick-Tagger, Zoom-Chart, Revisions-Audit) | ✅ abgeschlossen (PRs #58–#61) |
| **Modernes App-Redesign (Designer-Canvas & Screenshots)** (Start/Cockpit Idle/Live, 3x3 Mark Noise Event Sheet, Modern Protocol List, Wohnraum-Grenzwerte & Pro/Lite-Modus) | ✅ vollständig umgesetzt |
| **Mehrsprachigkeit & Lokalisierung (i18n)** (Deutsch, Englisch, In-App-Sprachauswahl & Android 13+ Per-App Language) | ✅ vollständig umgesetzt & getestet |
| **M11 Etappe A** Fotodokumentation (Messaufbau/Kalibrierung, Umfang konfigurierbar, Einbindung in Bericht und Drive-Sync) | ✅ umgesetzt — Fotos, EXIF-Drehung und PDF-Einbettung noch nicht am Gerät gesichtet |
| **M11 Etappe B** Videobeweis (CameraX ohne Tonspur, Ton aus der laufenden Messung nachträglich eingemuxt, resumable Drive-Upload) | ⚠️ umgesetzt, **nicht am Gerät verifiziert** — Kamera, A/V-Synchronität und der echte Upload brauchen Hardware |

**Gesamtfortschritt: Alle Meilensteine + Google Drive Ordner-Management + WAV-Sofortupload + Rohwert-CSV + Wohnraum-Presets + Pro/Lite-Modus + Modernes UI/UX-Redesign + Mehrsprachigkeit (i18n) vollständig umgesetzt, getestet und verifiziert.**

---

## Bluetooth-Anbindung: was da ist und was fehlt

### Vorhanden

**Das Protokoll ist am realen Gerät ermittelt** (M0) und in
[`docs/PROTOKOLL_PCE-323.md`](docs/PROTOKOLL_PCE-323.md) sowie im Code als
`meter/ble/Pce323Profile.kt` festgeschrieben:

- BLE-Modul **Lierda LSD4BTC**, Custom-Service `0000fff0`
- Notify auf `0000fff2`, Write auf `0000fff1` — **kein CONNECT-Kommando nötig**, der Strom läuft
  nach dem CCCD-Write von allein
- Logisches Frame 23 Byte, wegen Default-MTU auf zwei Notifications (20 + 3 Byte) aufgeteilt
- Messwert als **IEEE-754-float32 big endian** in dB, Intervall rund 515 ms

**Fundament und Abstraktion** (M-1, M1): Build läuft, Room migriert nachweislich ohne
Datenverlust, Paketstruktur nach Zuständigkeit, `AppContainer`, `MeterTransport` mit
`FakeMeterTransport` für hardwarefreie Tests.

**Aus M2:** BLE-Scan mit Geräte-Pinning, Verbindungsaufbau über eine serialisierte `GattQueue`,
CCCD-Write, Reassembly der 20 + 3 Byte, Decoder auf dem realen 23-Byte-Format,
Bluetooth-Berechtigungen und Live-Anzeige.

**Aus M3:** Reconnect mit Backoff und Jitter, vier unabhängige Ausfallsignale (Abbruch,
Datenstillstand, Adapter aus, Fehlerrate), Verbindung im Foreground Service statt in der UI,
Wiederaufnahme nach Neustart.

**Aus M5:** Alarm bei Verbindungsabbruch nach 60 s Karenzzeit, Push über **ntfy** auf ein zweites
Gerät und Meldung auf dem Gerät selbst, beide parallel; Cooldown, Eskalation und Entwarnung;
Alarmzustand in Room, damit ein Prozess-Tod während der Karenzzeit den Alarm nicht verschluckt;
**Totmannschaltung** über eine Ping-URL; Probealarm je Kanal in den Einstellungen.

**Aus M7b:** Pegelwerte aus Mikrofon *und* PCE-323 werden gepuffert, zu Zeitfenstern verdichtet
(LAeq als energetischer Mittelwert, Lücken bleiben als Lücken sichtbar) und alle 30 Minuten als
CSV in einen selbst gewählten Drive-Ordner hochgeladen — eine Datei pro Tag, aktualisiert statt
dupliziert, mit Dedup-Absicherung gegen Waisen. Läuft bewusst auch **ohne** gepinntes PCE-323
allein mit Mikrofonwerten. Aufzeichnungsgenauigkeit, WLAN-only und WAV-Upload sind in den
Einstellungen konfigurierbar.

**Aus M4:** Jede Überwachungsperiode wird als `SessionEntity` festgehalten, Messwerte batchweise
(alle 5 s oder 50 Werte) als `MeasurementEntity` weggeschrieben, Verbindungsausfälle als
`ConnectionEventEntity` — eine zusammenhängende Ausfallperiode erzeugt genau eine Zeile, nicht
eine je Zwischenzustand. Der Auslöse-Trigger schaltet automatisch auf das Messgerät um, sobald
eines verbunden ist (eigener Schwellwert, weil „60" bei Mikrofon und Messgerät nichts
Vergleichbares bedeutet), und fällt sonst auf den Mikrofonwert zurück. Kennwerte (LAeq
energetisch, Max/Min, L10/L50/L90, Überschreitungsdauer) lassen sich über `AkustischeKennwerte`
abfragen. Ein täglicher Retention-Job verdichtet Rohwerte älter als 90 Tage zu Minutenaggregaten
(Owner-Entscheidung, Plan 13.2) — erst schreiben, dann löschen, damit ein Abbruch dazwischen nie
Daten verliert. Die A/C-Frequenzbewertung bleibt dabei durchgehend `null`, bis der Gerätetest sie
bestätigt (siehe Warnhinweis unten).

**Aus M6:** Bonding scheitert an diesem Gerät nachweislich (M0-Fund: `createBond()` bricht die
Verbindung sofort ab) — statt es wiederholt zu versuchen, kennzeichnet die Live-Anzeige die
Verbindung jetzt ehrlich als unverschlüsselt. Geräte-Pinning gehärtet: ein Advertiser mit
demselben Namen wie das gekoppelte Gerät, aber anderer Adresse, wird nicht mehr kommentarlos
angeboten, sondern gewarnt, geloggt und nur nach Bestätigung akzeptiert (`GeraetePinning`).
Stream-Plausibilisierung als Spoofing-Erkennung: ein neuer Kadenz-Watcher in
`ConnectionSupervisor` trennt die Verbindung, wenn die Frame-Rate wiederholt mehr als ±20% vom
erwarteten Intervall abweicht. Die ntfy-Konfiguration (Topic, Server, Heartbeat-URL) liegt jetzt
in `EncryptedSharedPreferences` (Android Keystore) statt im Klartext, mit automatischer Migration
bestehender Werte. Ein neues, standardmäßig **ausgeschaltetes** Diagnose-Log (7-Tage-Löschung)
zeichnet technische Ereignisse auf (Datenstillstand, Fehlerrate, Kadenz-Auffälligkeiten,
gescheiterte Verbindungsversuche) — die Anzeige dafür ist M7. SQLCipher bleibt **bewusst aus**
(Owner-Entscheidung, siehe M4/Plan 13.2).

**Aus M7:** Protokollansicht (`ProtokollScreen`/`ProtokollDetailScreen`) zeigt alle Sessions,
Kennwerte je Session (aus Rohwerten oder, falls der Retention-Job sie bereits verdichtet hat, aus
Minutenaggregaten) und Verbindungsausfälle als rote Karten im Verlauf. Diagnose-Screen
(`DiagnoseScreen`) zeigt den Verbindungszustand live, einen aus den Verbindungsereignissen
abgeleiteten Reconnect-Zähler, die Decode-Fehlerrate, das Diagnose-Log (M6) und die
Drive-Sync-Historie. Export der Messreihe als CSV (`MessreiheCsv`, dieselbe Konvention wie
`DriveCsv` aus M7b) und als PDF-Textbericht (`MessreiheExport`, `android.graphics.pdf.PdfDocument`
aus dem SDK, kein neuer Dependency), geteilt über denselben FileProvider-Weg wie der bestehende
Tagesbericht. Einstellungen waren bereits konsolidiert (durchgängig betitelte Abschnitte) —
keine Änderung nötig.

**Aus M7c / UI-Harmonisierung & Entkopplung (PR #53):** Nach Rückmeldung aus den physischen Gerätetests am PCE-323 umfassend harmonisiert:
- **Startseiten-Integration:** PCE-323 Messgerätesteuerung und Kopplungsdialog (`MeterControlCard`, `MeterPairingDialog`) direkt auf der Startseite integriert. Die Bottom-Navigation ist auf 4 Hauptziele gestrafft (`Start`, `Protokoll`, `Diagnose`, `Einstellungen`).
- **Entkopplung der Audio-Aufnahme:** Das Beenden der Audio-Aufnahme (`ACTION_STOP_AUDIO_RECORDING`) stoppt gezielt nur die Mikrofon-Erfassung, während die Bluetooth-Dauermessung und der Foreground Service unterbrechungsfrei weiterlaufen.
- **Stabile BLE-Geräteliste:** Stabile Sortierreihenfolge (`sortiereGefundeneGeraete`) verhindert das Springen der Gerätezeilen bei RSSI-Pegelschwankungen während des Scans.
- **Transparente Status- & Protokoll-Führung:** Startseite bietet eine Session-Übersichtskarte für aktive und abgeschlossene Dauermessungen mit Direktlink ins Protokoll sowie präzise Leerzustandstexte, die Schwellenwert-Events von kontinuierlichen Hintergrund-Messreihen klar trennen.

**Aus Google-Drive Persistenz & Upload-Status (PR #54):**
- **Kontopersistenz & Stiller Token-Flow:** Ausgewähltes Google-Konto wird dauerhaft im verschlüsselten/gesicherten Speicher gehalten (`googleAccountEmail`). Beim Wiederverbinden oder automatischen Hintergrund-Sync erfolgt keine störende erneute Account-Auswahl mehr.
- **Transparente Statusanzeige:** `DriveStatusCard` in `SettingsScreen` und `DiagnoseScreen` zeigt Live-Synchronisationsstatus, zuletzt synchronisierte Datei, Fehlerhistorie und bietet einen 1-Klick-Button („Jetzt synchronisieren" mit Ladeanzeige).

**Aus OEM- & Tablet-Härtung (PR #55):**
- **Akustische Alarmierung mit `USAGE_ALARM`:** Für Android-Tablets ohne hardwareseitigen Vibrationsmotor (z.B. Xiaomi Pad 6) und Geräte im Lautlos-Modus spielt `LocalNotificationAlertChannel` bei Verbindungsabbruch forciert einen Alarmton über den Alarm-Audiokanal ab.
- **Hardware-Vibrationserkennung & NotificationChannel v3:** Direkte Vibrationsansteuerung mit `hasVibrator()`-Prüfung und NotificationChannel mit Priorität `MAX`.
- **OemDeviceHelperCard:** Erkennt Xiaomi/HyperOS/MIUI-Besonderheiten, prüft Berechtigungen (`POST_NOTIFICATIONS`, `SCHEDULE_EXACT_ALARM`, Akku-Optimierung, Autostart) und bietet 1-Klick-Intents zur Behebung.

**Aus M8 (Härtung, hardwarefreier Teil):**
- **Release-Build gehärtet:** `isMinifyEnabled`/`isShrinkResources` im release-Buildtype aktiv.
  `./gradlew assembleRelease` geprüft: keine unerklärten R8-„Missing class"-Warnungen, die vier
  WorkManager-Worker (`RetentionWorker`, `DiagnosticLogCleanupWorker`, `DriveSyncWorker`,
  `HeartbeatWorker`) samt Konstruktor stehen in `seeds.txt`, Credentials/Tink/MediaPipe bringen
  ihre Regeln bereits selbst über automatisch eingebundene consumer-rules.pro mit — keine
  zusätzliche `-keep`/`-dontwarn`-Zeile in `proguard-rules.pro` nötig.
- **Herstellerhinweis erweitert:** `leiteOemAutostartHinweisAb()` (`ui/OemAutostart.kt`) ist eine
  reine, per JVM-Unit-Test geprüfte Ableitungsfunktion und deckt jetzt zusätzlich zu Xiaomi auch
  Huawei/EMUI, Oppo/ColorOS, Vivo, OnePlus/OxygenOS und (mit Einschränkung, siehe Code-Kommentar)
  Samsung ab. `OemDeviceHelperCard` zeigt bei erkanntem Hersteller Hinweistext + Best-Effort-Intent
  zur herstellereigenen Autostart-Seite, robust gegen `ActivityNotFoundException`.

### Nicht vorhanden

Nichts mehr aus dem ursprünglichen Bluetooth-Plan-Umfang (M-1 bis M7 sowie M7b). M8 (Härtung), M9 (UX-Feinschliff) und M10 (Neue Funktionen) sind als optionale Folge-Meilensteine spezifiziert.

> ℹ **Gerätetests & physische Verifikation am PCE-323:**
> - Reale BLE-Kopplung und Frequenzgang-Messung (dB(A) vs. dB(C)): **Erfolgreich bestätigt.**
> - Google Drive Login und Upload-Struktur: **Erfolgreich bestätigt.**
> - Tablet- & HyperOS-Besonderheiten (Xiaomi Pad 6): **Erfolgreich analysiert und gehärtet.**

---

## Nächste Schritte

| # | Was | Braucht Hardware? |
|---|-----|-------------------|
| **Gerätetest** | M2, M3, M4, M5, M6, M7, M7c + M7b am realen Gerät, plus die zwei offenen Messfragen, die Google-Anmeldung mit der jetzt echten Client-ID sowie M8s Chaos-Checkliste und 24h-Dauerlauf (Teil C/D) — Checkliste: [`docs/CHECKLISTE_GERAETETEST.md`](docs/CHECKLISTE_GERAETETEST.md) | **ja** |
| M9 | UX-Überarbeitung — **größtenteils erledigt** (Datenpfad, Theme/Dunkelmodus-Infrastruktur, Navigation/Drawer, Start-Screen-Scroll+Leerzustand, App-weiter Snackbar/Papierkorb, Onboarding inkl. Wiederaufruf aus den Einstellungen, kalibrierter Wert sichtbar in Liste+Bericht, Alarmierung direkt unter Aufnahme). Offen: **Farbtokens** sind eingeführt, aber noch nicht in allen Dateien mit hartcodierten Farbliteralen übernommen (Befund A2 — das wäre eine sichtbare Farbänderung, bewusst als Owner-Entscheidung offengelassen), und **String-Ressourcen** für den Drive-Sync-Dialog sowie die Diagnose-Debug-Oberfläche fehlen noch — [`docs/PROMPT_M9_UX.md`](docs/PROMPT_M9_UX.md) | nein |
| M10 | Neue Funktionen — **F1–F5 (Stufe 1), F9 (Papierkorb), F12 (Zeitraumbericht mit Diagramm), F13 (Sicherung/Wiederherstellung), F14 (Widget + Schnelleinstellungs-Kachel), F15 (Alarm-Historie) erledigt**; F6–F8/F10–F11 nicht begonnen — [`docs/PROMPT_M10_FUNKTIONEN.md`](docs/PROMPT_M10_FUNKTIONEN.md) | nein |

Fertige Prompts für Umsetzungs-Sessions liegen in [`docs/`](docs/).

### Offene Entscheidungen

**Für M5 entschieden und umgesetzt:** Entwarnung je Kanal schaltbar · Cooldown 30 min, Eskalation
nach 60 min, max. 3 Wiederholungen · Push-Kanal **ntfy** (zunächst öffentlicher Server, Basis-URL
konfigurierbar). **SMS wurde gestrichen** — `SEND_SMS` ist eine von Google eingeschränkte
Berechtigung. Damit entfällt die Absicherung gegen „Internet weg", die Plan §7.4 dem SMS-Kanal
zugedacht hatte; sie wird jetzt von der Totmannschaltung getragen: Ohne Internet bleibt auch der
Ping aus, und der Dienst auf der Gegenseite meldet sich.

**Für M7b entschieden und umgesetzt:** Aufzeichnungsgenauigkeit **konfigurierbar, Default so fein
wie technisch sinnvoll (1 s)** statt der im Plan vorgeschlagenen 10 s — dafür WLAN-only per
Default an, um das dadurch höhere Uploadvolumen abzufangen. OAuth-Scope **`drive.file`** (App legt
eigenen Ordner an, keine Google-Verifizierung nötig). WAV-Upload **als Option vorhanden, Default
an** — Owner-Entscheidung, abweichend vom Plan-Vorschlag „nein", der WAVs komplett ausschließt.
(Ursprünglich Default aus; nach Rückmeldung aus dem ersten Gerätetest auf Default an umgestellt.)

**Für M4 entschieden und umgesetzt:** Aufbewahrungsdauer der Rohmesswerte **90 Tage**, wie im Plan
vorgeschlagen, danach Verdichtung zu Minutenaggregaten. **SQLCipher explizit gestrichen** —
Owner-Entscheidung, abweichend vom Plan-Vorschlag: die App-Sandbox von Android schützt bereits
gegen andere Apps, der Aufwand beim Öffnen/Migrieren stünde dazu nicht im Verhältnis. Die
Datenbank bleibt unverschlüsselt; M6 setzt stattdessen nur EncryptedSharedPreferences für
Alarmkonfiguration/Rufnummern um.

**Für M6 umgesetzt, keine Owner-Entscheidung nötig:** Bonding wird nicht erneut versucht (M0 hat
den Fehlschlag bereits mit Beweis belegt, `createBond()` bricht die Verbindung ab) — die Konsequenz
aus dem Plan (ehrliche UI-Kennzeichnung statt Sicherheit vorzutäuschen) greift direkt, ohne dass
das noch einmal am Gerät ausprobiert werden musste. Das Diagnose-Log protokolliert bewusst keine
rohen Frame-Bytes, sondern die von `ConnectionSupervisor` ohnehin erkannten Ereignisse
(Datenstillstand, Fehlerrate, Kadenz, gescheiterte Verbindungsversuche) — ein Rohframe-Capture
hätte noch keinen Konsumenten, der Diagnose-Screen dafür ist M7.

**Für M7 umgesetzt, keine Owner-Entscheidung nötig:** Export-CSV folgt derselben Konvention wie
`DriveCsv` aus M7b (Semikolon, Dezimalkomma, UTF-8-BOM, CRLF, `_dB` statt `_dBA`) statt einer
eigenen Formatierung — Konsistenz zwischen den beiden Export-Wegen der App. Das PDF ist bewusst
ein reiner Textbericht ohne Diagramm/Grafik und ohne neuen Bibliotheks-Dependency
(`android.graphics.pdf.PdfDocument` aus dem SDK) — passend zum durchgängig minimalen
Abhängigkeits-Stil des Projekts.

---

## Bekannte Einschränkungen

- **Der Mikrofon-Pegelwert ist unkalibriert.** `20·log10(rms/32767) + 100` ist dBFS plus
  willkürlicher Offset, ohne A-Bewertung und geräteabhängig. Genau deshalb das PCE-323.
- **`applicationId` ist `com.example.lrmprotokoll`** (B-6). Im Play Store unzulässig, aber nach
  Veröffentlichung nie wieder änderbar — bewusst vertagt, weil eine Änderung bestehende Aufnahmen
  auf dem Gerät unerreichbar macht.
- Vier weitere Altbefunde (Ringpuffer-Synchronisation, `audioRecord.release()`, unvollständiges
  `InputStream.read`, `runBlocking`) in [`docs/PROMPT_REVIEW.md`](docs/PROMPT_REVIEW.md), Schritt 5.
- **`MeasurementRecorder` flusht nicht bei `onTrimMemory`.** Plan 8.2 nennt das als
  zusätzliche Absicherung neben dem 5-s/50-Werte-Intervall; bewusst nicht umgesetzt (siehe
  `docs/PROMPT_M4.md`) — im ungünstigsten Fall (Speicherdruck kurz vor Prozess-Kill) gehen bis
  zu 5 s bzw. 50 Messwerte verloren.
- **Ob `EncryptedSharedPreferences` auf einem echten Gerät tatsächlich verschlüsselt, ist nicht
  durch Unit-Tests belegt.** Robolectric/die JVM stellen den Provider „AndroidKeyStore"
  grundsätzlich nicht bereit (`KeyStoreException: AndroidKeyStore not found`, selbst geprüft) -
  jeder Test, der `SettingsManager` unverändert konstruiert, läuft real über den dokumentierten
  Klartext-Fallback. Die Migrations-/Fallback-*Logik* ist gegen ein injiziertes Test-Double
  geprüft, die tatsächliche Verschlüsselung nur am Gerät (siehe `docs/PROMPT_M6.md`).
- **Der PDF-Export ist nicht durch Unit-Tests belegt.** `android.graphics.pdf.PdfDocument()`
  wirft unter Robolectric bei jedem `startPage()`-Aufruf `IllegalStateException: document is
  closed!`, reproduzierbar auch isoliert — ein verifiziertes Robolectric-Limit, keine Aussage
  über die Korrektheit von `MessreiheExport.exportierePdf`. Nur durch `assembleDebug` verifiziert,
  Inhalt und Layout müssen am echten Gerät geprüft werden (siehe `docs/PROMPT_M7.md`).
- **Der Videobeweis ist ohne Gerät nur zur Hälfte belegbar (M11 Etappe B).** Die Aufnahme läuft
  über CameraX **ohne Tonspur** — kein `withAudioEnabled()` —, damit die Kamera das Mikrofon
  nicht anfasst und die Pegelmessung während der Aufnahme durchläuft; der Ton wird aus dem
  laufenden `AudioRecord` mitgeschnitten und danach über `MediaCodec`/`MediaMuxer` in die MP4
  eingefügt. Unit-getestet sind die Rechenteile: die A/V-Synchronisation
  (`VideoTonSynchronisation`), der Tonmitschnitt (`VideoTonMitschnitt`), die Speicher- und
  Dauergrenzen (`Videospeicher`) und der resumable Drive-Upload gegen `MockWebServer`.
  **Nicht** belegt sind Kameraverhalten, die tatsächliche Lippensynchronität des gemuxten
  Videos und der echte Drive-Upload — `MediaCodec`, `MediaMuxer` und CameraX existieren auf der
  JVM nicht. Über sehr lange Aufnahmen können Bild und Ton auseinanderlaufen (zwei unabhängig
  getaktete Quellen); dagegen steht die einstellbare Maximaldauer, Default 3 Minuten.
- **Der Video-Upload nach Google Drive ist standardmäßig AUS** — bewusst anders als WAV und
  Fotos. Ein Video kann Dritte, Kennzeichen und Wohnungsinneres zeigen und ist um ein Vielfaches
  größer als alles andere, was die App speichert.
- **Die neuen Compose-Screens aus M7 (Protokoll, Diagnose) und M7c (Live-Dashboard,
  NavigationBar, Pegelverlauf-Chart) sind mangels Emulator in dieser Entwicklungsumgebung nicht
  visuell geprüft** — wohl aber durch echte Compose-UI-Tests unter Robolectric gegen die
  produktiven Screen-Funktionen mit vollem `AppContainer` (nicht nur kompiliert). Die konkrete
  Optik (Icon-Wahl für „Messgerät"/„Protokoll" in der `NavigationBar`, Chart-Farben/-Proportionen)
  bleibt eine visuelle Entscheidung für eine Emulator-Session.
- **Die `liveRegion`-Auszeichnung des Live-Pegels (`MeterScreen`, `LiveCockpitCard`,
  PROMPT_M9_UX.md Aufgabe 4) ist nur durch `assembleDebug` verifiziert, nicht durch einen
  Compose-Test.** `AppContainer.meterTransport` ist fest auf `BleMeterTransport` verdrahtet,
  nicht über ein Test-Double ersetzbar — ein Robolectric-Test kann den STREAMING-Zustand mit
  echtem Frame deshalb nicht erreichen, nur den unverbundenen IDLE-Pfad (siehe
  `MeterScreenComposeTest`, `LiveCockpitCardTest`). Ob TalkBack den Pegel tatsächlich laufend
  ansagt, muss am echten Gerät geprüft werden. Die `contentDescription` von `PegelverlaufChart`
  (dieselbe Aufgabe) ist dagegen voll getestet — der Chart ist eine reine, parameterisierte
  Composable ohne Container-Abhängigkeit.
- **Der minifizierte Release-Build (M8) ist nur durch `assembleRelease` und R8-Log-Prüfung
  verifiziert, nie an einem echten Gerät gestartet.** R8 kann beim Bauen unauffällig bleiben und
  trotzdem zur Laufzeit eine per Reflection gebrauchte, aber gestrippte Klasse treffen — ob alle
  Features (insbesondere WorkManager-Worker, Google-Anmeldung, Drive-Sync) im minifizierten Build
  tatsächlich fehlerfrei laufen, muss der Gerätetest zeigen.
- **30 hartcodierte Farbliterale (PROMPT_M9_UX.md Befund A2) sind noch nicht durch die
  vorhandenen Farbtokens (`AppStatusColors`/`statusColors`, `ui/theme/Tokens.kt`) ersetzt** —
  betroffen u. a. `StatusPill.kt`, `MicrophoneStatusBadge.kt`, `BluetoothStatusBadge.kt`,
  `MeterScreen.kt`, `OemDeviceHelperCard.kt`, `ProtokollScreen.kt`. Jedes dieser Literale hat
  eine andere Nuance für dieselbe Bedeutung (z. B. „verbunden = grün") — sie zu vereinheitlichen
  wäre eine sichtbare, projektweite Farbänderung, keine reine Code-Aufräumung, deshalb bewusst
  als Owner-Entscheidung offengelassen statt hier stillschweigend entschieden.
- **~59 hartcodierte deutsche UI-Literale bleiben außerhalb von `strings.xml`**, konzentriert in
  `DriveFolderPickerDialog.kt`, `DriveStatusCard.kt` und Debug-Reglern in `DiagnoseScreen.kt`
  (PROMPT_M9_UX.md Aufgabe 3, Rest). Die übrige Oberfläche (Onboarding, Aufnahmeliste,
  Einstellungslabels) ist vollständig auf `stringResource()` umgestellt.
- **`TrashScreen.kt` (Papierkorb, weiches Löschen mit `NoiseRecord.deletedAt`,
  `MIGRATION_11_12`) ist bereits vollständig umgesetzt und live** — das entspricht Funktion F9
  aus `docs/PROMPT_M10_FUNKTIONEN.md`, die der Katalog dort ausdrücklich als „Stufe 2, erst nach
  dem Gerätetest" vorschlägt. Die Funktion selbst ist fertig und läuft (eigener
  Retention-Mechanismus, 30 Tage), das ist keine Einschränkung — nur eine Abweichung von der
  dokumentierten Reihenfolge, hier zur Nachvollziehbarkeit festgehalten (siehe Korrektur in
  `docs/PROMPT_M10_FUNKTIONEN.md`).
- **Sicherung/Wiederherstellung (F13, `backup/SicherungManager.kt`) ist nur durch JVM-/
  Robolectric-Tests verifiziert, nie an einem echten Gerät durchgespielt.** Insbesondere der
  erzwungene Prozess-Neustart nach einer Wiederherstellung (`Intent.makeRestartActivityTask` +
  `Runtime.getRuntime().exit(0)`) und der echte SAF-Dateiauswahldialog sind unter Robolectric
  nicht simulierbar. Muss im Gerätetest geprüft werden, bevor die Funktion als vollständig
  verlässlich gilt.
- **Zeitraumbericht (F12, `report/PeriodenBerichtExport.kt`) ist nur durch JVM-Tests der
  Datenzusammenführung (`ermittlePeriodenBericht`, `SessionDao.zwischen`) verifiziert.** Das
  gezeichnete Diagramm selbst hängt an `android.graphics.pdf.PdfDocument`, das unter Robolectric
  nicht testbar ist (dasselbe verifizierte Limit wie bei `MessreiheExport.exportierePdf`) — nur am
  Gerät prüfbar.
- **Homescreen-Widget (F14, `widget/NoiseMonitoringWidgetProvider.kt`) ist nicht am Gerät
  geprüft.** `AppWidgetProvider`/`RemoteViews` lassen sich unter Robolectric nicht gegen einen
  echten Homescreen-Widgethost simulieren — dieselbe Einschränkung gilt bereits für die
  Schnelleinstellungs-Kachel (`service/NoiseMonitoringTileService.kt`), für die ebenfalls kein
  Test existiert.
- **UX-Review-Korrekturen (Nutzer-Feedback anhand von Gerät-Screenshots) sind nur durch
  `assembleDebug`/JVM-Tests verifiziert, nicht visuell am Gerät geprüft:** app-weite
  Text-Selektierbarkeit (`SelectionContainer` um `AppNavigation` in `MainActivity.kt`), die
  tagesweise/pro-Aufnahme-KI-Klassifizierung in `MainActivity.kt`/`ProtokollDetailScreen.kt`
  anstelle des entfernten globalen Batch-Buttons in den Einstellungen, sowie der Bugfix, dass
  `MainActivity` nun `AppCompatActivity` statt `ComponentActivity` erbt (die
  Sprachumschaltung Deutsch/Englisch über `AppCompatDelegate.setApplicationLocales()` griff
  unterhalb von Android 13 sonst nicht, weil das dafür nötige Context-Wrapping in
  `attachBaseContext()` nur `AppCompatActivity` bereitstellt).

---

## Entwicklung

```bash
./gradlew assembleDebug      # bauen
./gradlew test               # Unit-Tests inkl. Migrationstests
./gradlew installDebug       # auf verbundenes Gerät installieren
```

Fehlt `JAVA_HOME`, auf das JBR von Android Studio zeigen:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
```

**Vor App-Updates mit gewachsener Datenbank:** Die Datenbank sichern, bevor eine neue Version
installiert wird. Room migriert seit M-1 statt zu löschen — geht dabei etwas schief, ist das
Backup die einzige Rückfalloption. Seit M10 (F13) geht das **direkt in der App**: Einstellungen →
„Sicherung und Wiederherstellung" → „Sicherung erstellen" schreibt Datenbank und Einstellungen
über einen Storage-Access-Framework-Dialog in eine selbst gewählte ZIP-Datei, „Sicherung
einspielen…" liest sie mit Warnung und Versionsprüfung zurück (erzwingt danach einen
App-Neustart). Kein Terminal mehr nötig. Der `adb`-Weg bleibt als Alternative:

```bat
adb exec-out run-as com.example.lrmprotokoll cat databases/noise_database > backup.db
```

### CI

Drei Workflows unter [`.github/workflows/`](.github/workflows/), alle mit `pull_request` (gegen
`main`), `push` (nach `main`) und manuellem `workflow_dispatch` als Auslöser, pro Ref jeweils nur
ein gleichzeitiger Lauf (ein neuer Push bricht einen laufenden ab):

1. **[`androidci.yml`](.github/workflows/androidci.yml)** (Job `build-and-test`, Ubuntu-Runner,
   30 min Timeout):
   - Baut Debug- und Test-APK (`assembleDebug assembleDebugAndroidTest
     compileDebugAndroidTestKotlin`).
   - **Android Lint** (`lintDebug`) — muss ohne Fehler durchlaufen.
   - Die komplette JVM-/Robolectric-Testsuite (`test --continue`, inkl. der beiden
     Room-Migrationstests); `--continue` sorgt dafür, dass ein einzelner fehlgeschlagener Test
     nicht den Rest verdeckt. Das Ergebnis landet zusätzlich lesbar auf der
     Zusammenfassungsseite des Laufs (`GITHUB_STEP_SUMMARY`), nicht nur im Artefakt.
   - **Artefakte** (jeweils mit `if: always()` — werden auch bei fehlgeschlagenem Build/Test
     hochgeladen, soweit vorhanden): `lint-reports` (7 Tage), `unit-test-reports` (7 Tage) und
     **`app-debug-apk` (14 Tage)** — die fertig gebaute, mit dem fest eingecheckten
     `app/debug.keystore` signierte Debug-APK aus `app/build/outputs/apk/debug/*.apk`. Das ist
     der einzige Weg, eine PR-Fassung auf einem Telefon auszuprobieren, ohne selbst zu bauen —
     siehe [`docs/TESTEN_EINES_PR.md`](docs/TESTEN_EINES_PR.md) für den genauen Weg vom PR bis
     zur installierten APK (Artefakte hängen an der **Übersichtsseite des Laufs**, nicht an der
     Job-Seite mit dem Protokoll — das ist die übliche Stolperstelle).
2. **[`emulator-tests.yml`](.github/workflows/emulator-tests.yml)** (Job `instrumented-tests`,
   25 min Timeout): baut Debug- und Test-APK, startet einen echten Android-Emulator (API 34,
   `aosp_atd`, KVM-beschleunigt) und führt darauf `connectedDebugAndroidTest` aus — die
   instrumentierten Compose-UI-Tests laufen damit bei jedem PR automatisch, nicht nur manuell.
   Lädt Testberichte (`instrumented-test-reports-api-34`) und bei Fehlschlag zusätzlich
   `logcat`/`dumpsys`-Diagnose hoch. **Deckt weiterhin kein echtes BLE ab** — der Emulator hat
   keinen durchgereichten Bluetooth-Adapter, siehe
   [`docs/TESTEN_EINES_PR.md`](docs/TESTEN_EINES_PR.md) Abschnitt 2 für die genaue Grenze.

### Release erstellen (Signierte APKs über GitHub Releases)

Releases werden über Git-Tags auf dem `main`-Branch ausgelöst:

1. **Tag erstellen und pushen:**
   ```bash
   git tag v1.0.0
   git push origin v1.0.0
   ```
2. **Automatischer Workflow ([`release.yml`](.github/workflows/release.yml)):**
   - Leitet `versionName` (`1.0.0`) und `versionCode` (`10000`) deterministisch aus dem Tag ab.
   - Führt die vollständige Test-Suite aus.
   - Baut und signiert das Release-APK mit dem hinterlegten Release-Keystore (`RELEASE_KEYSTORE_BASE64` in GitHub Secrets).
   - Erstellt automatisch einen GitHub Release mit dem fertigen `app-release.apk` und Release Notes.

---

## Dokumentation

| Datei | Inhalt |
|-------|--------|
| [`AGENTS.md`](AGENTS.md) | Arbeitsregeln für Coding-Agents (Claude Code, Codex, Antigravity/Gemini): Branches, Commits, Verifikation, Zuständigkeiten |
| [`docs/IMPLEMENTIERUNGSPLAN_PCE-323_BLUETOOTH.md`](docs/IMPLEMENTIERUNGSPLAN_PCE-323_BLUETOOTH.md) | Der vollständige Plan: Protokoll, Architektur, Robustheit, Sicherheit, Alarmierung, Drive-Sync, Meilensteine, Risiken |
| [`docs/PROMPT_UMSETZUNG.md`](docs/PROMPT_UMSETZUNG.md) | Prompt-Vorlage für Umsetzungs-Sessions, ein Meilenstein pro Session |
| [`docs/PROMPT_REVIEW.md`](docs/PROMPT_REVIEW.md) | Prompt für die Fortschrittskontrolle nach jedem Meilenstein |
| [`docs/PROMPT_M1.md`](docs/PROMPT_M1.md) | Auftrag für M1 (erledigt) |
| [`docs/PROMPT_M2.md`](docs/PROMPT_M2.md) | Auftrag für M2 (erledigt) — BLE-Transport, Decoder-Umbau, Kopplung |
| [`docs/PROMPT_M3.md`](docs/PROMPT_M3.md) | Auftrag für M3 (erledigt) — Reconnect, Ausfallerkennung, Foreground Service |
| [`docs/PROMPT_B11.md`](docs/PROMPT_B11.md) | Auftrag für B-11 (erledigt) — 16-KB-Seitengröße, TFLite-Ablösung |
| [`docs/PROMPT_M5.md`](docs/PROMPT_M5.md) | Auftrag für M5 (erledigt) — Alarmierung, Karenzzeit, ntfy, Totmannschaltung |
| [`docs/PROMPT_M7B.md`](docs/PROMPT_M7B.md) | Auftrag für M7b (erledigt) — Drive-Sync, Aggregation, Google-Anmeldung |
| [`docs/PROMPT_M4.md`](docs/PROMPT_M4.md) | Auftrag für M4 (erledigt) — Persistenz, Trigger-Umstellung, Kennwerte, Retention |
| [`docs/PROMPT_M6.md`](docs/PROMPT_M6.md) | Auftrag für M6 (erledigt) — Bonding-Kennzeichnung, Geräte-Pinning, Kadenz-Watcher, verschlüsselte Ablage, Diagnose-Log |
| [`docs/PROMPT_M7.md`](docs/PROMPT_M7.md) | Auftrag für M7 (erledigt) — Protokollansicht, Diagnose-Screen, CSV/PDF-Export |
| [`docs/BESTANDSAUFNAHME_UI.md`](docs/BESTANDSAUFNAHME_UI.md) | Bestandsaufnahme der App-UI nach dem ersten Gerätetest — Screen-Inventar, Live-Status-Lücken, fehlende Chart-Infrastruktur, Verbesserungsvorschläge |
| [`docs/PROMPT_M7C.md`](docs/PROMPT_M7C.md) | Auftrag für M7c (erledigt) — Live-Status-Dashboard, Aufzeichnungs-Chart, Navigationsstruktur, Scroll-Fix `MeterScreen` |
| [`docs/PROMPT_M8.md`](docs/PROMPT_M8.md) | Auftrag für M8, hardwarefreier Teil (erledigt) — Release-Build härten (R8/Minify), Herstellerspezifika (Xiaomi, Huawei, Oppo, Vivo, OnePlus, Samsung); Chaos-Checkliste/24h-Dauerlauf bleiben Teil des Gerätetests |
| [`docs/PROMPT_M9_UX.md`](docs/PROMPT_M9_UX.md) | **UX-Review und Auftrag für M9 (offen)** — Befunde am Code mit Datei:Zeile (kein Dunkelmodus, keine String-Ressourcen, Barrierefreiheit, Navigation, leere/ladende/fehlerhafte Zustände, Berechtigungsablauf) plus Umsetzungsauftrag; der ursprünglich mitgemeldete Befund „Live-Diagramm rechnet die Session alle 5 s neu“ ist mit M9a behoben |
| [`docs/PROMPT_M9A.md`](docs/PROMPT_M9A.md) | **Owner-Entscheidungen aus dem UX-Review (erledigt, PR #80)** — Abgleich der vier Antworten gegen den Code (Dunkelmodus, kalibrierter Wert, Test-Suite waren bereits erledigt); umgesetzt: `MeasurementDao.fuerSessionAbFlow` begrenzt den Datenpfad des Live-Cockpits auf ein gerastertes 4-Stunden-Fenster statt bei jedem Batch die volle Session zu laden, `pegelEinheit()` leitet „dBA“/„dBC“/„dB“ im Tagesbericht aus `meterWeighting` ab statt es hart anzuhängen |
| [`docs/PROMPT_M10_FUNKTIONEN.md`](docs/PROMPT_M10_FUNKTIONEN.md) | **Funktionsvorschläge und Auftrag für M10 (offen)** — 15 Vorschläge in drei Stufen nach Migrationsbedarf, Umsetzungsauftrag für Stufe 1 (ohne Room-Migration), offene Owner-Entscheidungen |
| [`docs/PROMPT_M11_FOTO_VIDEO.md`](docs/PROMPT_M11_FOTO_VIDEO.md) | **Auftrag für M11 (umgesetzt)** — Etappe A Fotodokumentation, Etappe B Videobeweis; enthält die Owner-Entscheidungen E1 (Fotodoku auch beim Mikrofonlauf), E4 (Video mit Ton) und E9 (V4: Aufnahme ohne Tonspur, Ton nachträglich eingemuxt) und die verbliebenen offenen Entscheidungen E2, E3, E5–E8 |
| [`docs/PROMPT_BUGFIX_TRIGGER.md`](docs/PROMPT_BUGFIX_TRIGGER.md) | Auftrag für zwei Trigger-Fehler (erledigt) — stiller Ausfall der Auslösung, fehlende Trigger-Quelle „Mikrofon" |
| [`docs/TESTPLAN_INSTRUMENTIERT.md`](docs/TESTPLAN_INSTRUMENTIERT.md) | Testplan für instrumentierte UI-Tests (Emulator) — Positiv-/Negativtest je Button/Funktion, verlinkt auf den Code (umgesetzt & aktiv) |
| [`docs/DIAGNOSE_OBSERVABILITY_KONZEPT.md`](docs/DIAGNOSE_OBSERVABILITY_KONZEPT.md) | **Diagnose-, Fehleranalyse- und Observability-Konzept** — Architektur für Sentry, Breadcrumbs, Redaction & Support-Bundle (umgesetzt) |
| [`docs/EXTERNE_DIENSTE_EINRICHTUNG.md`](docs/EXTERNE_DIENSTE_EINRICHTUNG.md) | Externe Dienste einrichten (Sentry, Google Drive, ntfy, Healthchecks.io) |
| [`docs/PROMPT_RELEASE_PIPELINE.md`](docs/PROMPT_RELEASE_PIPELINE.md) | Release-Pipeline (umgesetzt) — signierte APKs über GitHub Releases via Tag `vX.Y.Z` |
| [`docs/TESTEN_EINES_PR.md`](docs/TESTEN_EINES_PR.md) | **Einen PR ausprobieren** — APK aus der CI, was der Emulator kann und was nicht |
| [`docs/CHECKLISTE_GERAETETEST.md`](docs/CHECKLISTE_GERAETETEST.md) | **Checkliste für den Gerätetest** — M2, M3, M5 und die zwei offenen Messfragen |
| [`docs/PROTOKOLL_PCE-323.md`](docs/PROTOKOLL_PCE-323.md) | **Das reale Geräteprotokoll aus M0** — verbindliche Quelle für M2 |
| [`docs/PROTOKOLL_PCE-323_ANLEITUNG.md`](docs/PROTOKOLL_PCE-323_ANLEITUNG.md) | Schritt-für-Schritt-Anleitung für M0 (Protokoll-Discovery am realen Gerät) |
