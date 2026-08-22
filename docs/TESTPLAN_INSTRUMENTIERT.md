# Testplan: Instrumentierte UI-Tests (echter Emulator)

**Status:** Vollständig implementiert (`app/src/androidTest/`) & automatisiert in CI (`.github/workflows/emulator-tests.yml`).

Vollständige Liste der interaktiven Elemente in der App (Button, Switch, Checkbox, Slider,
Chip, klickbare Card) mit Positiv- und Negativtest, verlinkt auf die genaue Codestelle. Die
`androidTest`-Suite umfasst 34 instrumentierte Smoke- und Interaktionstests, die bei jedem
Pull Request und Push auf `main` auf einem Android 14 (API 34) ATD-Emulator ausgeführt werden.

## Was hier NEU ist gegenüber den bestehenden Robolectric-Compose-Tests

Die App hat bereits reale Compose-UI-Tests unter Robolectric (`app/src/test/...ui/*ComposeTest.kt`,
M7c). Die sind schnell, laufen ohne Emulator, aber Robolectric stellt manche Android-Subsysteme
nur unvollständig oder gar nicht bereit - genau dort hat ein echter Emulator Mehrwert:

- **Echter Bluetooth-Stack** (`BluetoothLeScanner`, `BluetoothGattServer`) - Robolectric hat nur
  Shadow-Klassen, kein reales GATT-Verhalten.
- **Echte Laufzeit-Berechtigungsdialoge** (`RequestMultiplePermissions`) - unter Robolectric
  automatisch gewährt/verweigert, nie ein echter Systemdialog.
- **Echte System-Intents** (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`,
  `ACTION_REQUEST_SCHEDULE_EXACT_ALARM`) - ob sie überhaupt auflösen und die richtige Zieleinstellung
  öffnen, ist unter Robolectric nicht geprüft.
- **Echter `MediaPlayer`** (Audio-Wiedergabe) - unter Robolectric ein Shadow ohne echte Dekodierung.
- **Echtes Zurück-Gesture/-Button-Verhalten**, echtes Scrollen mit echten Touch-Koordinaten,
  echte Bildschirmgrößen/-dichten.
- **Echtes Room/SQLite auf echtem Dateisystem** statt In-Memory-DB.

Wo ein Element bereits durch einen Robolectric-Test **funktional** abgedeckt ist, steht das
explizit dabei ("Robolectric deckt X bereits ab") - der Emulator-Test dort bestätigt dann primär
"funktioniert auch real", er entdeckt selten neue Logikfehler.

---

## MainActivity.kt — `AppNavigation()` / `AppNavigationBar()`

| Element | Code | Positivtest | Negativtest |
|---|---|---|---|
| NavigationBar-Eintrag "Start" | `AppNavigationBar`, `MainActivity.kt:181` (`fun AppNavigation` ab Zeile 90, `fun AppNavigationBar` ab Zeile 168) | Von "Diagnose" aus auf "Start" tippen → `NoiseProtocolApp`-Inhalt sichtbar, Eintrag als ausgewählt markiert. Robolectric deckt Sichtbarkeit bereits ab (`HomeNavigationComposeTest`); Emulator-Mehrwert: echter Touch-Treffer auf der richtigen Bounding-Box bei echter Bildschirmgröße. | Wiederholtes schnelles Antippen (Doppel-Tap) darf keine doppelten Backstack-Einträge erzeugen - `adb shell dumpsys activity` bzw. System-Zurück-Taste mehrfach drücken muss nach genau einem Schritt am Start ankommen, nicht mehrfach durch Zwischenscreens. |
| NavigationBar-Eintrag "Messgerät" | `MainActivity.kt:187` | Tippen → `MeterScreen` öffnet, TopAppBar zeigt "Messgerät". | Tippen **ohne erteilte Bluetooth-Berechtigung** (Berechtigung vorher im Systemdialog abgelehnt) → Screen öffnet trotzdem, Scan-Button ist sichtbar aber deaktiviert (kein Crash, kein stiller Absturz). |
| NavigationBar-Eintrag "Protokoll" | `MainActivity.kt:193` | Tippen → `ProtokollScreen` öffnet. | Tippen bei leerer Datenbank (frische Installation) → Leerzustandstext sichtbar, keine Exception. |
| NavigationBar-Eintrag "Diagnose" | `MainActivity.kt:199` | Tippen → `DiagnoseScreen` öffnet, "Diagnose-Log (" sichtbar (Robolectric: `HomeNavigationComposeTest.diagnoseIstUeberEinenBeschrifteteNavigationseintragErreichbar`, bereits abgedeckt). | — (kein sinnvoller Negativpfad, reine Navigation ohne Vorbedingung). |
| NavigationBar-Eintrag "Einstellungen" | `MainActivity.kt:205` | Tippen → `SettingsScreen` öffnet. | — |
| Regressionsfall: Bar bleibt auf jeder Seite sichtbar | `AppNavigation`, `MainActivity.kt:90` | Auf jeden der 5 Screens navigieren, jeweils prüfen: alle 5 Labels weiterhin vorhanden (Robolectric: `navigationsleisteBleibtAufDemDiagnoseScreenSichtbar`, deckt Diagnose bereits ab - Emulator-Test sollte alle 5 Screens durchgehen, nicht nur Diagnose). | Auf `ProtokollDetailScreen` (Session-Detail, kein direktes Bottom-Nav-Ziel) navigieren → Bar bleibt sichtbar, "Protokoll" bleibt als ausgewählt markiert (`istBottomNavZielAktiv`-Präfixlogik). |

## MainActivity.kt — `NoiseProtocolApp()` (Home-Inhalt)

| Element | Code | Positivtest | Negativtest |
|---|---|---|---|
| Löschen-Icon in der Kopfzeile (erscheint nur bei Auswahl) | `MainActivity.kt:292` | Einen Eintrag per Long-Press auswählen → Icon erscheint → tippen → Lösch-Dialog öffnet. | Ohne vorherige Auswahl darf das Icon nicht sichtbar sein (negativ: Abwesenheit prüfen, nicht nur Anwesenheit im positiven Fall). |
| Globaler Filter: Auf-/Zuklappen-Zeile | `MainActivity.kt:311` | Tippen → `RangeSlider`s werden sichtbar (`AnimatedVisibility`). | Erneutes Tippen klappt wieder zu, ohne dass die zuvor gesetzten Filterwerte verloren gehen. |
| Pegel-`RangeSlider` (0–120 dB) | `MainActivity.kt:324` | Regler auf 40–80 dB ziehen → nur Einträge in diesem Bereich sichtbar. | Regler ganz auf ein Extrem ziehen (min=max=120) → Liste zeigt "keine Treffer" statt Crash/Endlosladen. |
| Uhrzeit-`RangeSlider` (0–23 h) | `MainActivity.kt:337` | Regler auf 8–18 Uhr ziehen → nur Einträge in diesem Fenster sichtbar. | Regler so ziehen, dass `startHour > endHour` durch Vertauschen der Handles NICHT möglich ist (Compose `RangeSlider` verhindert das strukturell) - Negativtest prüft, dass die Handles sich nicht kreuzen lassen. |
| "Filter zurücksetzen" | `MainActivity.kt:350` | Nach Ändern beider Filter tippen → beide auf Ausgangswerte zurück, volle Liste wieder sichtbar. | Tippen ohne vorherige Änderung → keine sichtbare Änderung, kein Fehler. |
| Referenz-Chip löschen (×-Icon) | `MainActivity.kt:373` | Tippen bei vorhandener Referenz → Referenz verschwindet aus der Chip-Zeile und aus der DB. | Zwei Chips schnell hintereinander löschen (Doppel-Tap auf denselben Chip, bevor die Liste neu zeichnet) → keine `IndexOutOfBounds`/doppelte Löschung. |
| Tag-Zeile auf-/zuklappen | `MainActivity.kt:410` | Tippen auf einen Tages-Header → Einträge dieses Tages werden aus-/eingeblendet. | Alle Tage zuklappen → Liste zeigt nur Header, kein leerer Zustand fälschlich als "keine Daten" interpretiert. |
| Löschen-Icon je Tag (gefiltert) | `MainActivity.kt:428` | Tippen → Lösch-Dialog nur für die **gefilterten** Einträge dieses Tages. | Tippen, wenn der Tagesfilter "Alle" aktiv aber die globalen Filter alles herausfiltern → Dialog zeigt "0 Aufnahmen", kein Crash bei leerer Liste. |
| "Bericht"-TextButton je Tag | `MainActivity.kt:432` | Tippen → Bericht-Dialog öffnet für die gefilterten Einträge dieses Tages. | Tippen bei einem Tag ohne Aufnahmen (Filter greift) → Dialog öffnet trotzdem ohne Exception, PDF-Erzeugung mit leerer Liste stürzt nicht ab. |
| Filter-Dropdown je Tag (Menu-Icon) | `MainActivity.kt:443` | Öffnen → alle vorkommenden Label des Tages als Einträge sichtbar, Auswahl filtert die Liste. | Öffnen bei einem Tag ohne gelabelte/erkannte Einträge → Menü zeigt nur "Alle", kein leeres/kaputtes Menü. |
| `NoiseRecordItem`: KI-Erkennung (Refresh-Icon) | `MainActivity.kt:722` | Tippen → `NoiseClassifier.classifyDetailed` läuft, Ergebnis erscheint als "KI Erkannt: …". | Tippen auf eine Aufnahme, deren WAV-Datei bereits gelöscht wurde (Datei-Handle ungültig) → Fehlerfall wird als "Nicht erkannt" abgefangen, kein Absturz (`MainActivity.kt` fängt das bereits über `?: "Nicht erkannt"` ab - Negativtest bestätigt das am echten Dateisystem). |
| `NoiseRecordItem`: Favorit/Stern-Icon | `MainActivity.kt:726` | Tippen → Dialog "Geräusch lernen" öffnet, Name eingeben, "Speichern" → neue Referenz erscheint als Chip. | "Speichern" mit leerem Namensfeld tippen → entweder Validierung verhindert das Speichern, oder ein leer benannter Eintrag entsteht sichtbar nachvollziehbar (aktuell keine Validierung im Code - Negativtest deckt genau diese Lücke auf). |
| `NoiseRecordItem`: Löschen-Icon | `MainActivity.kt:730` | Tippen → Lösch-Dialog für genau diese eine Aufnahme. | Tippen, danach im Dialog "Abbrechen" → Aufnahme bleibt unverändert in der Liste und auf der Festplatte. |
| `NoiseRecordItem`: Abspielen-Icon / Karte selbst | `MainActivity.kt:702`, `734` | Tippen → `AudioPlayerScreen` öffnet mit der richtigen `filePath`. | Tippen auf eine Aufnahme mit nicht mehr existierender Datei → `AudioPlayerScreen` zeigt einen Fehlertext statt abzustürzen (mittlerweile behoben, siehe Negativtest dort). |
| `NoiseRecordItem`: Label-Chips (Bagger/Bohren/Hämmern/Verkehr) | `MainActivity.kt:741` | Tippen auf einen Chip → `label` wird gesetzt und sofort in der Zeile sichtbar. | Zweimal hintereinander verschiedene Chips tippen → nur das zuletzt getippte Label bleibt gespeichert (keine Mehrfachzuordnung). |
| Referenz-Dialog: "Speichern" / "Abbrechen" | `MainActivity.kt:506/509` | "Speichern" → Referenz landet in der DB und schließt den Dialog. | "Abbrechen" → keine Referenz wird angelegt, Dialog schließt, `refName` wird zurückgesetzt (sonst würde der alte Name beim nächsten Öffnen wieder vorausgefüllt sein - Negativtest prüft genau das). |
| Bericht-Dialog: "Gefilterte teilen (ZIP)" / "Nur Bericht teilen" | `MainActivity.kt:525/532` | Jeweils tippen → Android-Share-Sheet öffnet mit der erzeugten ZIP- bzw. PDF-Datei. | Tippen bei 0 Aufnahmen im gefilterten Tag → Bericht/ZIP wird trotzdem erzeugt (leer) statt abzustürzen, Share-Sheet öffnet trotzdem. |
| Lösch-Dialog: "Löschen" / "Abbrechen" | `MainActivity.kt:557/560` | "Löschen" → Dateien werden von der Festplatte entfernt (`file.delete()`) UND aus der DB (`deleteMultiple`) - **beides** prüfen, nicht nur die DB. | "Abbrechen" → weder Datei noch DB-Eintrag werden angefasst; erneutes Öffnen der Liste zeigt den Eintrag unverändert. |

## MainActivity.kt — `ServiceControl()`

| Element | Code | Positivtest | Negativtest |
|---|---|---|---|
| "Aufnahme starten" | `MainActivity.kt:662` (Button in `ServiceControl`, `fun ServiceControl` ab Zeile 575) | Mit erteilter Mikrofon-Berechtigung tippen → `AudioRecordingService` startet als Foreground Service, Dashboard wechselt auf "AKTIV" (Robolectric deckt die reine Statuswechsel-Logik bereits ab: `ServiceControlComposeTest`, aber **nicht** den echten Service-Start). Negativ-/Positiv-Grenze hier: der Emulator ist die einzige Umgebung, die den echten `startForegroundService`-Aufruf und die echte Foreground-Notification prüfen kann. | Tippen **ohne** erteilte Mikrofon-Berechtigung → `Toast` "Berechtigung erforderlich" erscheint, **kein** Service-Start, `startForegroundService` wird nicht aufgerufen (sonst `SecurityException`/`ForegroundServiceStartNotAllowedException` je nach Android-Version - genau das darf am Emulator nicht passieren). |
| "Aufnahme beenden" | `MainActivity.kt:678` | Bei laufendem Dienst tippen → Service stoppt, Dashboard wechselt zurück auf "Inaktiv", Foreground-Notification verschwindet. | Tippen, während der Dienst **nicht** läuft (Button ist eigentlich `enabled = anzeige.dienstAktiv` - Negativtest bestätigt, dass er unter Robolectric UND am Emulator tatsächlich disabled/nicht antippbar ist, kein Leerlauf-Stop-Intent geht raus). |

## MeterScreen.kt

| Element | Code | Positivtest | Negativtest |
|---|---|---|---|
| Zurück-Pfeil | `MeterScreen.kt:167` | Tippen → zurück zum Home-Screen, Bottom-Nav "Start" wieder aktiv. | — |
| "Verbinden" (nur sichtbar wenn bereits gekoppelt) | `MeterScreen.kt:278` | Tippen bei erteilten Bluetooth-Berechtigungen → `ensureConnected()` startet den Foreground Service, Verbindungszustand wechselt weg von IDLE. | Tippen **ohne** Bluetooth aktiviert (Systemeinstellung Bluetooth aus, nicht nur Berechtigung) → App darf nicht abstürzen; `ConnectionSupervisor`/`BleScanner` müssen das als Fehlerzustand behandeln, nicht als unbehandelte Exception. |
| "Scannen (10s)" | `MeterScreen.kt:296` | Tippen mit aktivem Bluetooth und erteilten Berechtigungen → Button-Text wechselt zu "Suche läuft…", nach 10s zurück zu "Scannen (10s)", gefundene Geräte erscheinen in der Liste. | **Regressionstest für den behobenen Crash** ("Scannen crashed die App"): Bluetooth-Adapter während des laufenden Scans per `adb shell svc bluetooth disable` ausschalten → `onScanFailed` wird ausgelöst, App zeigt `scanFehler`-Text (`MeterScreen.kt:429`, `scanFehlermeldung()`) statt abzustürzen. Zusätzlich: Button zweimal sehr schnell hintereinander tippen (potenziell `SCAN_FAILED_SCANNING_TOO_FREQUENTLY`) - derselbe Codepfad muss greifen. |
| Gefundenes Gerät antippen (normaler Fall) | `MeterScreen.kt:356` | Auf ein Gerät ohne Namenskonflikt tippen → `pinne(device)` läuft, Adresse wird gespeichert, Verbindung startet. | — (Negativfall ist der nächste Eintrag). |
| Gefundenes Gerät antippen (Namenskonflikt) | `MeterScreen.kt:357`, `GeraetePinning.beurteile` | — | Ein zweites Testgerät mit demselben Advertising-Namen wie das bereits gekoppelte, aber anderer MAC-Adresse simulieren (zwei echte BLE-Peripherals oder ein Emulator-Begleitgerät als `BluetoothGattServer`) → antippen öffnet den Warn-Dialog "Anderes Gerät mit gleichem Namen" statt sofort zu koppeln. |
| Warn-Dialog: "Trotzdem koppeln" | `MeterScreen.kt:151` | Tippen → koppelt trotz Warnung, Dialog schließt. | — |
| Warn-Dialog: "Abbrechen" | `MeterScreen.kt:157` | Tippen → Dialog schließt, **keine** Kopplung, vorheriges gekoppeltes Gerät bleibt unverändert. | Nach "Abbrechen" erneut auf dasselbe verdächtige Gerät tippen → Dialog öffnet erneut (kein "totes" State-Flag, das ihn dauerhaft unterdrückt). |
| Geräteliste per Scroll erreichbar (Regressionsschutz M7c-A) | `MeterScreen.kt:180` ff., `GERAETE_LISTE_ENDE_TAG` | Bei sehr kleinem Viewport (z. B. geteilter Bildschirm/kleines Gerät) mit vielen gefundenen Geräten bis zum `GERAETE_LISTE_ENDE_TAG`-Element scrollen → erreichbar, kein abgeschnittener Bildschirm (Robolectric deckt das bereits strukturell ab: `MeterScreenComposeTest`; Emulator-Mehrwert: echte Touch-Scroll-Geste statt `performScrollTo()`). | — |

## ProtokollScreen.kt

| Element | Code | Positivtest | Negativtest |
|---|---|---|---|
| Zurück-Pfeil | `ProtokollScreen.kt:51` | Tippen → zurück zum Home-Screen. | — |
| Session-Zeile antippen | `ProtokollScreen.kt:79`, `onOpenSession` | Tippen auf eine vorhandene Session → `ProtokollDetailScreen` öffnet mit der richtigen `sessionId`. | Tippen auf eine Session, die **während des Ladens** der Liste durch den Retention-Job (parallel laufender Worker) bereits zu Minutenaggregaten verdichtet wurde → Detailansicht muss auf den Aggregat-Pfad zurückfallen (`AkustischeKennwerte.ausAggregaten`), nicht mit "keine Rohwerte gefunden" abstürzen. |
| Leerzustand | `ProtokollScreen.kt:59` | — | Bildschirm öffnen auf einer frischen Installation ohne jede Session → Hinweistext sichtbar, keine leere/kaputte `LazyColumn`. |

## ProtokollDetailScreen.kt

| Element | Code | Positivtest | Negativtest |
|---|---|---|---|
| Zurück-Pfeil | `ProtokollDetailScreen.kt:112` | Tippen → zurück zur Protokollliste. | — |
| "CSV exportieren" | `ProtokollDetailScreen.kt:169` | Tippen bei einer Session mit Messwerten → Datei wird erzeugt, Android-Share-Sheet öffnet. | Tippen bei einer Session **ohne** Messwerte (z. B. sofort wieder beendete Session) → CSV mit nur der Kopfzeile wird erzeugt, kein Crash bei leerer `messwerte`-Liste. |
| "PDF exportieren" | `ProtokollDetailScreen.kt:176` | Tippen bei vorhandenen Kennwerten → PDF wird erzeugt, Share-Sheet öffnet. **Nur am echten Gerät prüfbar** - `android.graphics.pdf.PdfDocument` wirft unter Robolectric bei jedem `startPage()` `IllegalStateException` (bekannte Robolectric-Grenze, siehe README "Bekannte Einschränkungen"), das ist also ein Element, bei dem der Emulator nicht nur bestätigt, sondern zum ersten Mal überhaupt prüft. | Tippen bei `kennwerte == null` (Button ist eigentlich durch `val k = kennwerte ?: return@Button` abgesichert) → kein Klick-Effekt, kein Crash - Negativtest bestätigt, dass der Guard tatsächlich greift statt eine `NullPointerException` zu werfen. |

## DiagnoseScreen.kt

Rein lesend (Flow-basiert) - keine Buttons außer Zurück. Kein Negativtest im Sinne eines
Fehlerpfads, aber ein Live-Update-Test gehört hierher:

| Element | Code | Positivtest | Negativtest |
|---|---|---|---|
| Zurück-Pfeil | `DiagnoseScreen.kt:88` | Tippen → zurück zum Home-Screen. | — |
| Live-Aktualisierung Diagnose-Log | `DiagnoseScreen.kt:64` | Screen offen lassen, im Hintergrund einen echten Verbindungsabbruch auslösen (Bluetooth am Testgerät ausschalten) → neuer Log-Eintrag erscheint **ohne** den Screen neu zu öffnen (Robolectric deckt die Flow-Mechanik bereits ab: `DiagnoseScreenComposeTest`, aber mit einem manuell eingefügten DB-Eintrag, nicht mit einem echten Verbindungsereignis). | Diagnose-Log ist in den Einstellungen deaktiviert (Default) → Screen zeigt weiterhin den Hinweistext "Kein Eintrag…", auch nachdem ein echter Verbindungsabbruch passiert ist (nichts wird heimlich doch geloggt). |

## SettingsScreen.kt

| Element | Code | Positivtest | Negativtest |
|---|---|---|---|
| Zurück-Pfeil | `SettingsScreen.kt:106` | Tippen → zurück zum Home-Screen, alle zuvor geänderten Werte bleiben persistiert (neu öffnen bestätigt). | — |
| Schwellenwert-Slider Mikrofon/Messgerät/Pre-Roll/Dauer/KI-Vertrauen/Karenzzeit/Aufzeichnungsgenauigkeit (7 Slider) | `SettingsScreen.kt:124,139,149,160,181,233,481` | Je Slider: Wert ändern → `onValueChangeFinished` schreibt in `SettingsManager`, Screen verlassen und neu öffnen → Wert bleibt. | Slider bis an den `valueRange`-Rand ziehen (Minimum/Maximum) → kein Absturz, gespeicherter Wert entspricht exakt der Grenze, keine Off-by-one-Rundung. |
| "KI-Erkennung aktivieren" (Switch) | `SettingsScreen.kt:172` | Einschalten → Vertrauensschwelle-Slider erscheint, `settings.aiEnabled = true`. | Ausschalten, während gerade eine KI-Erkennung läuft (Tag-Zeile → Refresh-Icon getippt, dann sofort in Einstellungen ausschalten) → laufende Erkennung darf nicht abstürzen, auch wenn die Funktion "eigentlich" gerade deaktiviert wurde. |
| Abtastrate-Chips (16000 Hz / 44100 Hz) | `SettingsScreen.kt:193,198` | Je Chip tippen → `selected`-Zustand wechselt, `settings.audioSampleRate` wird gesetzt. | Bei laufender Aufnahme (Dienst aktiv) die Abtastrate wechseln → nächste NEUE Aufnahme nutzt den neuen Wert, die gerade laufende stürzt nicht ab (Wert wird nicht mitten in einem `AudioRecord` verändert). |
| "Alarmierung aktiv" (Switch) | `SettingsScreen.kt:217` | Einschalten → Karenzzeit/Push/Totmannschaltung/Probealarm-Bereich erscheint. | Ausschalten, während ein Alarm gerade in der Karenzzeit hängt (`AlarmCoordinator`-Zustand) → laufender Karenzzeit-Timer wird sauber abgebrochen, kein verspäteter Alarm nach dem Ausschalten. |
| "Exakte Alarme erlauben" | `SettingsScreen.kt:250` | Nur sichtbar wenn `!exakteAlarmeErlaubt` (API ≥ 31): tippen → Systemeinstellung `ACTION_REQUEST_SCHEDULE_EXACT_ALARM` öffnet, dort erlauben, zurückkehren → `exakteAlarmeErlaubt` wird via `ON_RESUME`-Observer neu gelesen und der Button verschwindet. **Nur am Emulator/Gerät prüfbar**, da echter Intent-Roundtrip nötig ist. | Systemeinstellung öffnen, dort **ablehnen**, zurückkehren → Button bleibt sichtbar, Warnhinweistext bleibt sichtbar (kein optimistisches "wird schon geklappt haben"). |
| "Push aktiv" (ntfy, Switch) | `SettingsScreen.kt:262` | Erstmaliges Einschalten bei leerem Topic → `erzeugeNtfyTopic()` läuft automatisch, Topic-Feld füllt sich. | Einschalten, wenn bereits ein Topic gespeichert ist → **kein** neues Topic wird erzeugt (der Guard `ntfyTopic.isBlank()` muss greifen), bestehendes Topic bleibt erhalten. |
| "Server übernehmen" | `SettingsScreen.kt:289` | Server-URL ändern, tippen → `settings.ntfyServer` aktualisiert. | Leeres/ungültiges URL-Format eingeben, tippen → aktuell keine Validierung im Code; Negativtest dokumentiert das bewusst als offenen Befund (schreibt eine ungültige URL kommentarlos in die Einstellungen), kein Absturz aber ein UX-Mangel. |
| "Kopieren" (ntfy-Topic) | `SettingsScreen.kt:304` | Tippen → Topic landet in der Zwischenablage (`ClipboardManager`), **nur am echten Gerät/Emulator prüfbar** (Clipboard-Zugriff unter Robolectric nicht realistisch simulierbar). | Tippen bei leerem Topic (vor der ersten Erzeugung) → Zwischenablage bekommt einen leeren String statt eines Absturzes. |
| "Neu erzeugen" (ntfy-Topic) | `SettingsScreen.kt:308` | Tippen → neues zufälliges Topic ersetzt das alte, wird sofort gespeichert. | Mehrfach schnell hintereinander tippen → jedes Mal ein anderes Topic, kein doppeltes Schreiben/Race auf `SettingsManager`. |
| Entwarnung-Checkboxen (Push/Meldung) | `SettingsScreen.kt:323,330` | Je Checkbox: an-/abwählen → `settings.entwarnungUeberNtfy`/`entwarnungUeberMeldung` folgt sofort. | Beide gleichzeitig ausschalten → Entwarnung wird bei tatsächlicher Wiederverbindung auf **keinem** Kanal gesendet (Negativtest bestätigt das Ausbleiben, nicht nur den UI-Zustand). |
| "Ping-URL übernehmen" (Totmannschaltung) | `SettingsScreen.kt:357` | URL eintragen, tippen → `settings.heartbeatUrl` aktualisiert, `HeartbeatWorker` wird mit der neuen URL geplant. | Feld leeren, tippen → Totmannschaltung wird laut Hinweistext abgeschaltet; Negativtest bestätigt, dass `HeartbeatWorker` tatsächlich gestoppt wird, nicht nur die Anzeige sich ändert. |
| "Test-Push" | `SettingsScreen.kt:370` | Tippen bei erreichbarem ntfy-Server → Ergebnistext "Push wurde angenommen…" erscheint, echte Push kommt auf Zweitgerät an. **Nur am Emulator/Gerät mit echtem Internetzugriff sinnvoll prüfbar.** | Tippen ohne Netzwerkverbindung (Emulator-Flugmodus über `adb shell svc data disable`/`svc wifi disable`) → Ergebnistext "Push fehlgeschlagen: …" erscheint, kein Absturz, keine unbehandelte `IOException`. |
| "Test-Meldung" | `SettingsScreen.kt:380` | Tippen → lokale Systembenachrichtigung erscheint, Ergebnistext "Meldung auf diesem Gerät ausgelöst." **Nur am Emulator prüfbar** (echte Notification-Erzeugung, `POST_NOTIFICATIONS`-Berechtigung ab API 33). | Tippen **ohne** erteilte `POST_NOTIFICATIONS`-Berechtigung (API ≥ 33) → keine sichtbare Benachrichtigung, aber `sendeTest` darf nicht crashen, Ergebnistext muss den Fehlschlag ehrlich zeigen. |
| "Synchronisation aktiv" (Drive, Switch) | `SettingsScreen.kt:416` | Einschalten → `DriveSyncPlanung.plane()` läuft, Ordnerbereich erscheint. | Ausschalten während ein Sync-Zyklus gerade läuft (`DriveSyncWorker` aktiv) → laufender Worker wird nicht mitten im Schreiben abgebrochen (kein halb geschriebenes Sync-Ergebnis). |
| "Mit Google verbinden" / "Ordner neu einrichten" | `SettingsScreen.kt:453` | Mit eingerichteter, echter `GoogleClientConfig.SERVER_CLIENT_ID` tippen → Google-Anmeldedialog öffnet, nach Zustimmung wird ein Drive-Ordner angelegt, `driveOrdnerId` gesetzt. **Setzt eine echte Client-ID voraus (siehe README "Nächste Schritte") - ohne sie ist nur der Negativfall prüfbar.** | Tippen **ohne** eingerichtete Client-ID (aktueller Stand) → Fehlertext "Verbindung fehlgeschlagen: Kein Zugriffstoken verfügbar (Keine echte OAuth-Client-ID eingerichtet - siehe GoogleClientConfig)" erscheint (`formatiereDriveFehler`, `SettingsScreen.kt:463` - dieser genaue Text wurde erst kürzlich aus der Owner-Rückmeldung heraus verbessert und ist damit selbst ein guter Regressionstest). |
| "Nur über WLAN synchronisieren" (Switch) | `SettingsScreen.kt:492` | Einschalten bei aktiver Synchronisation → `DriveSyncPlanung.plane()` läuft mit WLAN-Constraint neu. | Einschalten, während das Gerät nur über Mobilfunk online ist → nächster geplanter Sync-Lauf startet **nicht** (WorkManager-Constraint greift), erst nach WLAN-Verbindung. |
| "Audioaufnahmen (WAV) ebenfalls hochladen" (Checkbox) | `SettingsScreen.kt:511` | Aktivieren → `settings.driveUploadWav = true`, nächster Sync-Zyklus lädt auch WAV-Dateien hoch. Default ist seit der Owner-Rückmeldung AN - Positivtest sollte den **Ausgangszustand nach Neuinstallation** mitprüfen (Checkbox bereits angehakt, ohne Zutun). | Deaktivieren → nächster Sync-Zyklus lädt **keine** WAV-Dateien mehr hoch, auch wenn vorher welche hochgeladen wurden (kein rückwirkendes Löschen, nur kein neuer Upload - Negativtest grenzt das ab). |
| "Diagnose-Log aktiv" (Switch) | `SettingsScreen.kt:539` | Einschalten → `DiagnosticLogCleanupPlanung.plane()` läuft, ab jetzt werden Ereignisse geloggt. | Ausschalten → `DiagnosticLogCleanupPlanung.stoppe()` läuft; **bereits vorhandene** Log-Einträge bleiben in der DB sichtbar (kein rückwirkendes Löschen beim Ausschalten - nur die 7-Tage-Automatik stoppt). |
| "Akku-Optimierung deaktivieren" | `SettingsScreen.kt:573` | Nur sichtbar wenn `!batteryOptimizationIgnored`: tippen → Systemdialog `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` öffnet, dort erlauben, zurückkehren → Hinweistext wechselt zu "ist ausgenommen", Button verschwindet (`ON_RESUME`-Observer, `SettingsScreen.kt:90`). **Nur am Emulator/Gerät prüfbar.** | Systemdialog öffnen, dort **ablehnen**, zurückkehren → Button bleibt sichtbar, Warnhinweis mit den Hersteller-Beispielen (Xiaomi/Huawei/Samsung) bleibt sichtbar. |
| Bildschirmende erreichbar (Regressionsschutz M7c-A) | `SettingsScreen.kt:588`, `BILDSCHIRM_ENDE_TAG` | Bei kleinem Viewport bis zum `BILDSCHIRM_ENDE_TAG`-Element scrollen → erreichbar (Robolectric deckt das bereits ab: `SettingsScreenComposeTest`; Emulator-Mehrwert: echte Scroll-Geste, echte Tastatur-Einblendung bei den Textfeldern verdeckt keinen unerreichbaren Bereich). | — |

## AudioPlayerScreen.kt

| Element | Code | Positivtest | Negativtest |
|---|---|---|---|
| Zurück-Pfeil | `AudioPlayerScreen.kt:58` | Tippen während Wiedergabe läuft → `mediaPlayer.release()` via `DisposableEffect.onDispose` läuft, kein weiterlaufender Ton nach Verlassen des Screens (**nur am Emulator/Gerät real hörbar/messbar prüfbar**). | — |
| Play/Pause-Icon | `AudioPlayerScreen.kt:109` | Tippen → Wiedergabe startet, Fortschrittsbalken (`WaveformDisplay`) bewegt sich; erneutes Tippen pausiert. | **Inzwischen behoben** (war hier als offener Befund vermerkt): `mediaPlayer.setDataSource(filePath)`/`.prepare()` in `DisposableEffect` (`AudioPlayerScreen.kt:39`) fängt jetzt Exceptions ab (`wiedergabeFehlermeldung()`, Zeile 175) - eine nicht mehr existierende oder beschädigte Datei zeigt einen Fehlertext statt zu crashen, der Play-Button ist dabei deaktiviert. Unter Robolectric per `AudioPlayerScreenComposeTest` regressionsgesichert (wirft dort `IllegalArgumentException` statt der auf echten Geräten erwarteten `IOException` - beides wird vom `catch (e: Exception)` abgedeckt, siehe Test-KDoc). Ein Emulator-Test mit einer echten, zwischenzeitlich gelöschten Datei bestätigt zusätzlich den genaueren `IOException`-Text ("...gelöscht oder ist sie beschädigt?"). |
| Icon zeigt fälschlich immer "Play" | `AudioPlayerScreen.kt:97`, `// TODO: Add Pause Icon` | — | **Bekannter, im Code selbst vermerkter Mangel:** Das Icon wechselt nie zu einem Pause-Symbol, obwohl `isPlaying` korrekt umschaltet - ein Negativtest, der bei laufender Wiedergabe das sichtbare Icon prüft (`contentDescription = "Pause"` ist zwar korrekt gesetzt, das **sichtbare Icon-Vektorbild** aber nicht), macht diese bekannte UX-Lücke messbar statt nur als Kommentar im Code zu stehen. |

---

## Priorisierung, falls nicht alles auf einmal umgesetzt wird

1. **Der bereits gefixte Scan-Crash** (`MeterScreen`, Scan-Button) - höchste Priorität, weil es
   der einzige Punkt in dieser Liste ist, der schon einmal einen echten Absturz beim Owner
   verursacht hat. Ein Emulator-Regressionstest verhindert, dass er unbemerkt wiederkehrt.
2. **Die beiden echten Systemeinstellungs-Roundtrips** (exakte Alarme, Akku-Optimierung) - reine
   Intent-Aufrufe, unter Robolectric nicht prüfbar, aber mit Espresso `Intents`/UiAutomator am
   Emulator gut automatisierbar.
3. **Der `AudioPlayerScreen`-Absturz bei fehlender Datei** - inzwischen ebenfalls behoben (siehe
   Tabelle oben), per Robolectric-Compose-Test regressionsgesichert. Ein Emulator-Test mit einer
   echten, zwischenzeitlich gelöschten Datei bleibt trotzdem sinnvoll: er ist die einzige
   Umgebung, in der der tatsächliche `IOException`-Pfad (statt Robolectrics
   `IllegalArgumentException`-Shadow-Ersatz) durchläuft.
4. Der Rest (reine State-Änderungen, bereits durch Robolectric-Tests strukturell abgedeckt) hat
   geringere Priorität - dort bestätigt der Emulator vor allem "funktioniert auch real", ohne neue
   Fehlerklassen zu finden.
