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

## 9. Teil F — Stand und Stapel

Teil F umfasst 17 Abschnitte mit rund 60 Einzelprüfungen. Die Stichprobe zeigt, dass ein
**erheblicher Teil bereits abgedeckt ist und nur die Zuordnung fehlt** — das Repository hat
239 instrumentierte Tests in 66 Klassen, unter anderem:

| Abschnitt | Kandidat |
|---|---|
| F1 Videobeweis | `VideoAufnahmeScreenInstrumentedTest`, `VideoAufnahmeScreenPermissionInstrumentedTest`, `BeweisVideoOeffnenTest` |
| F2 Fotodokumentation | `FotoDokumentationSheetPermissionInstrumentedTest`, `FotodokumentationSettingsInstrumentedTest` |
| F3 Google Drive | `DriveUploadScreenInstrumentedTest`, `DriveFolderPickerDialogInstrumentedTest`, `DriveStatusCardInstrumentedTest` |
| F6 Neue Bildschirme | alle vier existieren: `VideoAufnahmeScreenInstrumentedTest`, `DriveUploadScreenInstrumentedTest`, `SpeicherplatzUebersichtInstrumentedTest`, `KiErklaerungScreenInstrumentedTest` |
| F10 Protokollreiter | `ProtokollScreenAndroidTest`, `HomeTagHeaderCollapseInstrumentedTest`, `HomeScreenInstrumentedTest` |
| F11 Mikrofon-Fallback | `MicrophoneCockpitRegressionTest`, `MicrophoneCockpitRegressionInstrumentedTest` |
| F12/F16 Sicherung | `SettingsSicherungInstrumentedTest` |
| F13 High-End-Bericht | `BerichtPdfInhaltInstrumentedTest`, `ChaquopyReportRunnerInstrumentedTest` |
| F14 Absturzdiagnose | `CrashDiagnoseInstrumentedTest` |

**Diese Tabelle ist eine Kandidatenliste, keine Zusicherung.** Sie entstand aus Testnamen und
Klassennamen; welche Zeile welcher Test tatsächlich deckt, ist Zeile für Zeile am Testrumpf zu
prüfen. Genau das ist Stapel 2 — und es als abgedeckt auszuweisen, bevor es nachgelesen ist,
wäre die Art von Behauptung, gegen die dieses Dokument geschrieben ist.

---

## 10. Was noch offen ist

| Stapel | Inhalt | Voraussetzung |
|---|---|---|
| **1** *(dieser PR)* | Teil A–D zugeordnet und in der Checkliste ausgewiesen; zwei neue Werkstattaufnahmen für F11 | — |
| **2** | Teil F Zeile für Zeile am Testrumpf nachprüfen und zuordnen | — |
| **3** | Der #216-Plan (S-3: A1–A8, B1–B5, C1–C4, D1–D4) | **Blockiert:** `MeterAutoConnect` und `trenneMessgeraetFallsNiemandEsBraucht()` liegen nur im Branch von [#216](https://github.com/arthurschaab-bit/Noiseprotocol_Android/pull/216). Die Tests gehören dorthin oder hinter dessen Merge |

### Namensschema

Testnamen tragen die Schritt-ID als Präfix, damit ein roter Test unmittelbar auf eine
Checklistenzeile zeigt:

```
b3_pegelLaeuftNachMessungsendeWeiter
f11_1_getrenntesMessgeraetZeigtFallbackKennzeichnung
```

Für Abschnitte, deren Zeilen bisher keine ID tragen (Teil F), wird sie beim Zuordnen vergeben:
`F1.1` … `F1.8` in der Reihenfolge der Tabelle.

---

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
