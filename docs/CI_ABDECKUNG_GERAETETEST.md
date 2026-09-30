# CI-Abdeckung der Gerätetests

Beantwortet eine Owner-Frage vom 30.09.2026: *„Können wir die Gerätetests parallel zum
eigentlichen Gerätetest auch in den CI-Checks abbilden?"*

Antwort in einem Satz: **für einen großen Teil ja, für einen wichtigen Teil niemals — und das
Nützliche an diesem Dokument ist die Trennlinie zwischen beidem.**

---

## 1. Was ein CI-Lauf beweisen kann und was nicht

Alle Prüfungen hier laufen gegen `FakeMeterTransport`, nicht gegen ein PCE-323. Daraus folgt eine
harte Grenze:

> Ein Fake beweist, dass die App **auf eine gegebene Eingabe richtig reagiert**.
> Er beweist nie, dass **die Eingabe stimmt**.

Zwei Befunde dieses Projekts sind genau durch diese Lücke geschlüpft, beide mit grüner CI:

- Der `--.-`-Fehler bei Gerätetest A5 zu PR #216: `isCalibrated` enthielt „eine Messung läuft",
  also war der Pegel ohne Messung **immer** `null`. Logisch konsistent, am Gerät falsch.
- [F-37](UX_UI_AUDIT.md#f-37): eine Messung ohne Messgeräteverbindung sah aus wie eine
  kalibrierte. Ebenfalls logisch konsistent, am Gerät falsch.

**Deshalb heißt dieses Dokument nicht „Gerätetests in der CI".** Was hier steht, ist ein
Regressionsnetz für die *App-Hälfte* jedes Prüfschritts. Es ersetzt keinen einzigen Durchlauf von
[`CHECKLISTE_GERAETETEST.md`](CHECKLISTE_GERAETETEST.md) — es verhindert, dass ein bereits einmal
bestätigtes Verhalten unbemerkt zurückfällt.

Wer einen Prüfschritt wegen eines grünen Hakens hier überspringt, verliert genau den Teil, für
den es den Gerätetest gibt.

---

## 2. Die drei Ebenen

Ein Prüfschritt wird nicht „von der CI" abgedeckt, sondern von genau einer von drei Ebenen mit
sehr unterschiedlicher Aussagekraft.

| Ebene | Gradle | Was sie kann | Was sie nicht kann |
|---|---|---|---|
| **JVM-Unit** | `test` | Zustandsmaschinen mit **virtueller Zeit** — eine 15-Minuten-Wartezeit kostet 0 ms. Deterministisch, kein Flake-Risiko | Kein Android-Framework, keine Darstellung |
| **Robolectric** | `test` | Echte Compose-Komposition, echte Pixel (`GraphicsMode.NATIVE`), Bildschirmfotos ohne Gerät | Dialoge und Bottom Sheets liegen in eigenen Fenstern und sind **unsichtbar**; kein echter Dienstlebenszyklus |
| **Emulator** | `connectedAndroidTest` | Echter Foreground Service, echte Notification, echte `Activity`-Wiederherstellung, Dialoge | Kein Bluetooth-Stack, kein Prozesstod, kein Neustart, kein Doze |

**Die wichtigste Einsicht aus der Bestandsaufnahme:** Für die Verbindungs-Zustandsmaschine ist die
JVM-Ebene dem Gerätetest **überlegen**. `ConnectionSupervisorTest` prüft die Backoff-Folge
(1, 2, 4, 8, 16, 30, 60, 60 s) exakt und in Millisekunden, wo Gerätetest C3 zwei Minuten warten und
die Zeit mit der Stoppuhr schätzen muss. Der Gerätetest ist an dieser Stelle nicht die genauere
Messung, sondern die einzige, die den echten Funkstack sieht.

---

## 3. Legende

| Zeichen | Bedeutung |
|---|---|
| ✅ | Die Zusicherung des Schritts ist vollständig automatisiert. Der Gerätetest prüft hier nur noch die Hardware-Seite |
| ◐ | Teilweise — die Spalte nennt, **was der Test nicht beweist** |
| — | Nur am Gerät. Die Spalte nennt den Grund |

Ein `—` ist kein Mangel, sondern eine Aussage: der Schritt existiert, **weil** ihn kein Test
ersetzen kann.

---

## 4. Teil A — Grundfunktion (M2)

| # | CI | Wo |
|---|---|---|
| A1 | ◐ | `MeterPairingDialogInstrumentedTest`, `MeterScreenAndroidTest.scanButtonStartetScanUndFaengtFehlerOhneCrash` — prüft Scan-UI und Fehlerbehandlung gegen einen eingespeisten Scanner. **Nicht bewiesen:** dass ein echtes PCE-323 im Scan auftaucht |
| A2 | ✅ | `ConnectionSupervisorTest.verbindungsverlustFuehrtUeberReconnectingZurueckZuStreaming` (Zustandsfolge), `BluetoothStatusBadgeInstrumentedTest.zeigtGeraetenamenUndVerbundenBeiStreamingAn` (Anzeige) |
| A3 | — | **Metrologie.** Ob der App-Wert dem Gerätedisplay entspricht, entscheidet der Dekoder gegen echte Bytes eines echten Geräts. Ein Fake liefert die Zahl, die man ihm vorgibt |
| A4 | ◐ | `ConnectionSupervisorTest.kadenzInnerhalbDerToleranzLoestKeinDegradedAus` und `.stallFuehrtNachTStaleZuDegradedUndErholtSichNachEndeDesStalls` — die Kadenzerwartung und die Stillstandserkennung. **Nicht bewiesen:** dass der Funkkanal die 2 Hz real einhält |
| A5 | ◐ | `ForegroundServiceAndroidTest.foregroundServiceStartetUndStopptUeberUiUndVerwaltetOngoingNotification` (Notification-Lebenszyklus). **Nicht bewiesen:** Verhalten nach echtem Backgrounding durch das System |

---

## 5. Teil B — die zwei inhaltlichen Fragen

| # | CI | Begründung |
|---|---|---|
| B1 | — | **Physik.** Ob die BLE-Sendetätigkeit das Mikrofon des Messgeräts elektrisch stört, ist keine Softwarefrage. Am Fremdgerät Uni-T UT353BT sind 15 dB dokumentiert |
| B2 | — | **Physik.** Die A/C-Zuordnung von `0x2C`/`0x2D` lässt sich nur mit einem 63-Hz-Ton gegen ein echtes Gerät beweisen. `MeterScreenAndroidTest.unbestaetigteFrequenzbewertungZeigtWarnhinweis` prüft lediglich, dass die App die **Unsicherheit** korrekt ausweist — nicht, ob die Annahme stimmt |

Diese beiden Schritte sind der Grund, warum die Checkliste existiert. Kein CI-Lauf wird sie je
beantworten.

---

## 6. Teil C — Robustheit (M3)

Hier liegt das stärkste Ergebnis der Bestandsaufnahme: **sechs von acht Schritten sind auf
JVM-Ebene vollständig abgedeckt** — und zwar deterministisch, mit virtueller Zeit.

| # | CI | Wo |
|---|---|---|
| C1 | ✅ | `ConnectionSupervisorTest.verbindungsverlustFuehrtUeberReconnectingZurueckZuStreaming` |
| C2 | ✅ | derselbe Test — die Erholung ist Teil derselben Zusicherung |
| C3 | ✅ | `.nachErschoepftenVersuchenWirdFailedGemeldet` **und** `.backoffFolgtDefinierterSequenzMitJitter` — letzterer prüft die Folge exakt, nicht per Stoppuhr |
| C4 | ✅ | `.nachFailedFuehrtDieWartezeitZuEinemNeuenAnlauf`, `.erneutVersuchenHoltDenAnlaufVorDieWartezeit`, `.anstossImMomentVonFailedGehtNichtVerloren` |
| C5 | ✅ | `.adapterAusPausiertUndAdapterAnStartetSofortNeu` — deckt auch die Zusicherung „keine hektischen Wiederholversuche" ab |
| C6 | ✅ | derselbe Test |
| C7 | — | **Prozesstod.** `am kill` und der Neustart des Dienstes über `START_STICKY` sind Betriebssystemverhalten |
| C8 | — | **Neustart.** `BOOT_COMPLETED` lässt sich weder in Robolectric noch im Emulator-Job realistisch auslösen |

Bemerkenswert an C3: Die Checkliste sagt „Deutlich schneller oder deutlich langsamer ist ein
Befund" — also eine Schätzung. Der Unit-Test prüft dieselbe Folge exakt. Was der Gerätetest dort
zusätzlich beiträgt, ist allein die Frage, ob der echte Stack die berechneten Abstände auch
einhält.

---

## 7. Teil D — Dauerlauf

| Prüfung | CI | Begründung |
|---|---|---|
| Über Nacht verbunden | — | Echte Zeit, echter Funk, echte Akkuverwaltung |
| Logcat auf `status 133` | — | Die GATT-Slot-Kaskade entsteht nur im echten Bluetooth-Stack |
| Speicherverbrauch | — | Auf dem Owner-Gerät wurden 89 Abstürze in einer Woche gemessen, 88 davon Speichermangel ([`BEFUNDE_P30_2026-09-23.md`](BEFUNDE_P30_2026-09-23.md)). Kein CI-Lauf hätte das gezeigt |

Teil D ist vollständig gerätegebunden, ohne Ausnahme.

---

## 8. Teil E

Historische Feldbefunde mit Status „gelöst", keine ausführbaren Prüfschritte. Keine Zuordnung
nötig.

---

## 9. Teil F — zugeordnet (Stapel 2)

17 Abschnitte, rund 60 Einzelprüfungen. **Jede Zeile unten ist am Testrumpf nachgelesen, nicht
aus Klassennamen geraten** — und das Nachlesen hat die ursprüngliche Kandidatenliste nach unten
korrigiert.

### Die Erkenntnis, die alles andere ordnet

> Die vorhandenen Tests decken **die Bildschirme und die Verdrahtung** ab.
> Teil F fragt nach **Verhalten über die Zeit, am echten Gerät, mit echten Diensten.**

Ein Beispiel statt einer Erklärung: `BeweisVideoOeffnenTest` prüft sorgfältig, dass der
Öffnen-Intent stimmt — `ACTION_VIEW`, `video/mp4`, FileProvider-Authority, Lese-Flag, und dass
eine fehlende Datei keinen Absturz erzeugt. Checklistenzeile F1.4 fragt aber, ob **Bild und Ton
synchron** sind. Der Test ist gut und trifft die Frage nicht. Wer nur Klassennamen liest, hält
F1 für abgedeckt.

### F1 — Videobeweis

| # | Prüfung | CI | Beleg / Grund |
|---|---|---|---|
| F1.1 | Aufnahme läuft sofort an | — | Kein Test startet eine Aufnahme; `VideoAufnahmeScreenInstrumentedTest` prüft Titel und Zurück |
| F1.2 | Beenden schließt Bildschirm, Vorschau aus | — | CameraX-Lebenszyklus, nicht simulierbar |
| F1.3 | „Ton wird hinzugefügt …" → „mit Ton" | — | Kein Test |
| F1.4 | Bild und Ton synchron | ◐ | `BeweisVideoOeffnenTest` — nur der Öffnen-Intent. **Synchronität: nie** |
| F1.5 | Keine Lücke in der Messreihe | — | Braucht eine laufende Messung über die Aufnahmedauer |
| F1.6 | Warnung **vor** dem Start ohne Mikrofon | ✅ | `VideoAufnahmeScreenInstrumentedTest.mikrofonWarnungErscheintNurOhneLaufendesMikrofonFormat` |
| F1.7 | Speicher < 500 MB → startet nicht | — | Kein Test |
| F1.8 | Maximaldauer stoppt automatisch | ◐ | `VideobeweisSettingsInstrumentedTest` prüft nur, dass der Regler den Wert speichert — nicht die Wirkung |

### F2 — Fotodokumentation

| # | Prüfung | CI | Beleg / Grund |
|---|---|---|---|
| F2.1 | Foto erscheint in Detail und PDF | ◐ | `FotoDokumentationSheetPermissionInstrumentedTest` belegt nur, dass der Knopf den Kamera-Intent auslöst |
| F2.2 | EXIF-Drehung im PDF korrekt | — | Braucht ein echtes Kamerabild |
| F2.3 | Abbrechen stört die Messung nicht | — | Kein Test |

### F3 — Google Drive

| # | Prüfung | CI | Beleg / Grund |
|---|---|---|---|
| F3.1 | Ordnerstruktur in Drive | — | Echtes Google-Konto |
| F3.2 | Zwei Zyklen erzeugen Ordner nur einmal | — | dito |
| F3.3 | Gestriges Video landet im gestrigen Ordner | — | dito |
| F3.4 | Upload-Übersicht zeigt die Zustände | ✅ | `DriveUploadScreenInstrumentedTest` — Leerzustand, Eintrag, Sync aus, Sync an |
| F3.5 | Großes Video, Abbruch überlebt | — | Echtes Netz |

### F4 — Protokoll und Alarm ohne erreichbares Messgerät

| # | CI | Beleg / Grund |
|---|---|---|
| F4.1 kein Alarm | — | Alarmkette über Karenzzeit, kein Test |
| F4.2 genau eine Mikrofon-Sitzung | ◐ | `MicrophoneCockpitRegressionTest` deckt die Anzeige, nicht die Sitzungsbuchführung |
| F4.3 Sitzung wird beendet angezeigt | — | |
| F4.4 Quellenwechsel während der Messung | — | |
| F4.5 Alarm greift danach | — | |

### F5 — „Verbindung steht, keine Frames"

**CI: —, und das ist der Punkt.** Ursache unbekannt, die Analyse braucht ein Support-Bundle vom
echten Gerät. Ein Fake liefert per Definition Frames.

### F6 — Neue Bildschirme

| # | Bildschirm | CI | Beleg |
|---|---|---|---|
| F6.1 | Videoaufnahme | ✅ | `VideoAufnahmeScreenInstrumentedTest`, `…PermissionInstrumentedTest` |
| F6.2 | Upload-Übersicht | ✅ | `DriveUploadScreenInstrumentedTest` |
| F6.3 | Speicherplatz-Abschnitt | ✅ | `SettingsScreenInstrumentedTest.speicherplatzAbschnittZeigtErmittelteGroessenNachDemAufklappen` — **nicht** `SpeicherplatzUebersichtInstrumentedTest`, der misst nur die Laufzeit der Ermittlung |
| F6.4 | „Wie die Lärmerkennung arbeitet" | ✅ | `KiErklaerungScreenInstrumentedTest` |

Der Abschnitt sagt „die Optik ist ungeprüft". Das gilt weiterhin — gerendert heißt nicht schön.

### F7 — Huawei/EMUI-Fallback

**CI: ◐.** Die Checkliste sagt es selbst: belegt nur über eine simulierte `SecurityException` in
einem Robolectric-Test (`OemDeviceHelperCardTest`). Auf echter Huawei-Hardware nie wiederholt.

### F8 — Aufnahme-Selbstheilung nach Schreibfehler

| # | CI | Beleg / Grund |
|---|---|---|
| F8.1 kein stiller Mehrstunden-Ausfall | — | Braucht eine Nacht echten Betrieb |
| F8.2 Neustart nach `AUDIO_FILE_WRITE_FAILED` | ✅ | `AudioMonitoringRestartPolicyTest` (JVM) — die Regel ist geprüft, das Eintreten nicht |

### F9 — Fotos aus der Galerie

**CI: ◐** über `FotoDokumentationTest` (JVM, Datenpfad). Der System-Fotopicker, die Markierung
„nachträglich hinzugefügt" im PDF und der Import während laufender Messung: kein Test.

### F10 — Protokollreiter

**Der am besten abgedeckte Abschnitt.**

| # | CI | Beleg |
|---|---|---|
| F10.1 Tages-Kopfzeilen | ✅ | `ProtokollScreenAndroidTest.protokollScreen_sessionsAnUnterschiedlichenTagenBekommenGetrennteTagesueberschriften` |
| F10.2 Kopfzeile klappt ein/aus | ✅ | `HomeTagHeaderCollapseInstrumentedTest.tagHeaderKlapptNurDieEigeneGruppeEinUndAus` |
| F10.3 Filter-Panel öffnet | ✅ | `HomeScreenInstrumentedTest.filterPanelLaesstSichAufUndZuklappen` |
| F10.4 Chips filtern | ✅ | vier eigene Tests: Favoriten, Ruhezeit, Messgerät, kalibriert |
| F10.5 „nur mit Ereignissen" wirkt | ◐ | Kein Test benennt genau diesen Trichter |

### F11 — Mikrofon-Fallback erkennbar

| # | CI | Beleg |
|---|---|---|
| F11.1 Gerät weg → Fallback-Kennzeichnung | ✅ | `MicrophoneCockpitRegressionTest.meterSessionOhneVerbindungZeigtMikrofonwertAlsErkennbarenFallback` + Bildschirmfoto `cockpit_messgeraet_getrennt_fallback` |
| F11.2 Gerät zurück → kalibriert | ✅ | Bildschirmfoto `cockpit_verbunden_messung_laeuft` (68.4 dB(A), ohne Kennzeichnung) |
| F11.3 reiner Mikrofonlauf unverändert | ✅ | `keinErfundenerPegelUndMikrofonAenderungenWerdenAngezeigt` |
| F11.4 Bericht mit gemischten Quellen | ◐ | Die Diagrammtrennung ist in den Berichtstests belegt, die Zeile selbst nicht als Ganzes |

### F12 / F16 — Datenbank-Sicherung

| # | CI | Beleg / Grund |
|---|---|---|
| F12.1 eigene Zeile „Letzte Sicherung" | ✅ | `DriveStatusCardInstrumentedTest.driveStatusCardZeigtNochKeineSicherungWennAktivAberNieHochgeladen` |
| F12.2 Rotmarkierung nach >26 h | — | Braucht Tage echter Laufzeit |
| F12.3–F12.5 Wiederherstellung, Diagnoseeinträge | ✅ | `SettingsSicherungInstrumentedTest` — echte ZIP mit Datenbankinhalt, voller Wiederherstellungspfad, Abbrechen |
| F16.1–F16.3 streamende Sicherung | ◐ | `SicherungManagerTest` deckt den Weg; die 492-MB-Datenbank des Owner-Geräts nicht |

### F13 — High-End-Bericht

**CI: ✅ für den technischen Pfad**, wörtlich wie die Checkliste ihn beschreibt:
`ChaquopyReportRunnerInstrumentedTest` (Room → CSV → echter Chaquopy-/Matplotlib-Aufruf → PDF,
plus kaputtes JSON, unbekanntes Gebiet, fehlende Datei) und `BerichtPdfInhaltInstrumentedTest`
(echte Seite, Deckblatt, Tagesseite).

**— für alles andere:** 20 reale Messtage, Laufzeit auf dem Zielgerät, und die rechtliche
Freigabe. Ein synthetischer Lauf bestätigt keinen Sensor und keine Rechtsprosa.

### F14 — Absturzdiagnose

| # | CI | Beleg / Grund |
|---|---|---|
| F14.1 RuntimeException → Bundle | ✅ | `CrashDiagnoseInstrumentedTest.runtimeExceptionHinterlaesstEinAbsturzBundle` |
| F14.2 OutOfMemoryError → Bundle | ✅ | `.outOfMemoryErrorHinterlaesstEinAbsturzBundle` |
| F14.3 ANR → Thread-Dump | ✅ | `.anrHinterlaesstEinenThreadDump`, `.anrWatchdogErkenntHaengerUndBautAnrBundle` |
| F14.4 Absturz **während** der Aufzeichnung | — | Kein Test |
| F14.5–F14.9 Ankunft in Drive, 24 h, WLAN-Nachholung | — | Echtes Konto, echte Zeit |
| F14.10 Diagnose-Screen mit >200 Einträgen | ◐ | `DiagnoseScreenInstrumentedTest` rendert den Screen, nicht unter Last |

### F15 — Drive-Sync OOM

**CI: ✅ für die Regel** (`DriveSyncCoordinatorTest` — keine parallelen Zyklen, Nachholen wird
fertig), **— für die Wirkung** (24 h Laufzeit, `db_stats.json`, Crash-Puffer).

### F17 — High-End-Bericht: Fehlschläge sichtbar

**CI: ✅** über `HighEndReportExportTest` und `ExportFehlerbehandlungComposeTest` — dass ein
Fehlschlag im Diagnoseprotokoll landet, ist geprüft. **Die eigentliche Ursache des Fehlschlags
vom 23.09. auf dem P30 bleibt unbekannt**, und kein Test wird sie finden.

### Bilanz Teil F

| | Anzahl |
|---|---|
| ✅ vollständig | **19** |
| ◐ teilweise | **11** |
| — nur am Gerät | **28** |

Knapp ein Drittel ist automatisiert — deutlich mehr, als die Checkliste vermuten lässt, und
deutlich weniger, als die Klassennamen versprochen hätten.

## 10. Was noch offen ist

| Stapel | Inhalt | Stand |
|---|---|---|
| **1** | Teil A–D zugeordnet, Spalte in der Checkliste, zwei Werkstattaufnahmen für F11 | ✅ erledigt |
| **2** | Teil F Zeile für Zeile am Testrumpf nachgelesen und zugeordnet | ✅ erledigt — Abschnitt 9 |
| **3** | Der #216-Plan (S-3: A1–A8, B1–B5, C1–C4, D1–D4) | in Arbeit, siehe unten |

### Warum Stapel 3 nicht auf `main` liegen kann

`MeterAutoConnect` und `trenneMessgeraetFallsNiemandEsBraucht()` existieren nur im Branch von
[#216](https://github.com/arthurschaab-bit/Noiseprotocol_Android/pull/216). Ein Test dafür auf
einem Branch von `main` lässt sich nicht einmal übersetzen.

Drei Wege, und warum der dritte gewählt ist:

1. **In #216 hineinpushen** — macht das Debug-APK `app-debug-apk-745` ungültig, mit dem der
   Gerätetest des Owners gerade ansteht. Der Preis ist zu hoch für einen Testzusatz.
2. **Auf den Merge von #216 warten** — verschiebt die Arbeit ohne Gegenwert.
3. **Aufgesetzter Branch mit Basis `feature/s3-verbindung-getrennt`** ← gewählt. Fasst #216
   nicht an, das APK bleibt gültig, und die Tests sind fertig, sobald #216 durch ist.

### Was Stapel 3 abdecken kann — und was nicht

Aus dem 25-schrittigen Plan von #216 sind rund 15 Schritte auf App-Ebene prüfbar. Der Rest ist
dieselbe Grenze wie überall: **A5 („die Zahl entspricht dem Gerätedisplay") bleibt
gerätegebunden**, egal wie gut die Naht ist. Ein Fake liefert die Zahl, die man ihm vorgibt.

### Namensschema

Testnamen tragen die Schritt-ID als Präfix, damit ein roter Test unmittelbar auf eine
Checklistenzeile zeigt:

```
b3_pegelLaeuftNachMessungsendeWeiter
f11_1_getrenntesMessgeraetZeigtFallbackKennzeichnung
```

Für Abschnitte, deren Zeilen bisher keine ID trugen, ist sie in Abschnitt 9 vergeben:
`F1.1` … `F1.8` in der Reihenfolge der Tabelle.

## 11. Was in der CI danach sichtbar ist

Der Emulator-Job liefert die instrumentierten Ergebnisse, der JVM-Job die Unit- und
Robolectric-Ergebnisse. Dazu lädt die CI das Artefakt **`bildschirmfotos`** hoch — seit diesem PR
auch die beiden Cockpit-Zustände aus F11:

| Bild | Zustand |
|---|---|
| `cockpit_verbunden_messung_laeuft.png` | Messung läuft, PCE-323 verbunden: **68.4 dB(A)**, Betriebsart „Automatisch → Messgerät", keine Fallback-Kennzeichnung |
| `cockpit_messgeraet_getrennt_fallback.png` | Dieselbe Messung, Gerät getrennt: Mikrofonwert, Einheit „dB (Mikrofon-Fallback)", roter Hinweis |

Das ist der Teil der Frage, der am unmittelbarsten „Gerätetest parallel im CI" ist: der Owner
sieht den Bildschirm, ohne ein Gerät anzufassen. Was er dort **nicht** sieht, steht in Abschnitt 1.

### Eine Korrektur, die das erst möglich gemacht hat

In PR #216 und im KDoc von `BildschirmfotoWerkstatt.cockpitOhneMessung()` stand, der verbundene
Zustand sei nicht aufnehmbar, weil der `ConnectionSupervisor` den Transportzustand dauerhaft
maskiere und es dafür „keine Nahtstelle" gebe. Die Messung dahinter (30 von 30 Stichproben
`supervisor=DISCONNECTED transport=STREAMING`) stimmte — die Erklärung war falsch.

Die Ursache: `BluetoothAdapterStateObserver` startet mit dem **echten** Adapterzustand, und der
ist unter Robolectric aus. Der Supervisor setzt daraufhin in seiner ersten Schleifeniteration
`setOverride(DISCONNECTED)` und parkt in `adapterEnabled.first { it }`
(`ConnectionSupervisor.kt:217-221`). Es fehlte keine Naht am Supervisor — es fehlte Bluetooth.

Dazu kam eine zweite, unabhängige Falle: `FakeMeterTransport` setzt `STREAMING` in einer Coroutine
auf seinem eigenen Scope (`FakeMeterTransport.kt:158-160`). `runBlocking` kehrt zurück, bevor die
gelaufen ist. Im Emulator reicht die Wanduhr, unter Robolectric mit angehaltener Testuhr nicht —
der erste Anlauf fotografierte den Fallback-Zustand und trug fälschlich „verbunden" im
Dateinamen. Beide Fallen stehen jetzt im Quelltext, damit sie niemand zweimal bezahlt.
