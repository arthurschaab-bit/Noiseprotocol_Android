# Einen PR testen — auf dem Telefon und im Emulator

Kurzfassung: **Alles außer Bluetooth lässt sich im Emulator prüfen.** Das Bluetooth-Modul des
PCE-323 braucht ein echtes Telefon, alles andere nicht.

---

## 1. Die APK bekommen, ohne selbst zu bauen

Jeder CI-Lauf legt die Debug-APK als Artefakt ab. Im PR auf **Checks → build-and-test → Artifacts
→ `app-debug-apk`**, herunterladen, entpacken, dann:

```bat
adb install -r app-debug.apk
```

Damit ist keine lokale Build-Umgebung nötig, um eine PR-Fassung auszuprobieren.

> ⚠ **Vorher die Datenbank sichern**, wenn auf dem Gerät gewachsene Aufnahmen liegen — jede
> Schemaänderung migriert sie:
> ```bat
> adb exec-out run-as com.example.lrmprotokoll cat databases/noise_database > backup.db
> ```

Alternativ lokal bauen: `git fetch origin && git checkout <branch> && ./gradlew installDebug`

---

## 2. Was der Emulator kann — und was nicht

| | Emulator | Echtes Telefon |
|---|---|---|
| App startet, UI, Einstellungen | ✅ | ✅ |
| Room-Migration mit Altbestand | ✅ | ✅ |
| Mikrofon-Aufnahme, KI-Klassifikation | ✅ (Host-Mikrofon) | ✅ |
| **ntfy-Push senden und empfangen** | ✅ | ✅ |
| Lokale Alarm-Meldung, „Nicht stören" | ✅ | ✅ |
| Berechtigungsdialoge, exakte Alarme | ✅ | ✅ |
| Totmannschaltung (Ping) | ✅ | ✅ |
| **BLE-Verbindung zum PCE-323** | ❌ | ✅ |
| Verhalten in Doze, Hersteller-ROM-Kills | ❌ | ✅ |
| 24-h-Dauerlauf | ❌ | ✅ |

**Warum Bluetooth nicht geht:** Der Android-Emulator hat keinen durchgereichten
Bluetooth-Adapter. Es gibt keinen Weg, aus dem Emulator heraus mit dem PCE-323 zu sprechen — der
gesamte Pfad aus M2/M3 (Scan, GATT, Notify, Reconnect) braucht deshalb echtes Gerät **und** echtes
Messgerät.

---

## 3. M5 im Emulator prüfen (Alarmierung)

Das ist der lohnendste Teil, weil er ohne Hardware geht und die fehleranfälligsten Stellen trifft.

### 3.1 Einrichten

1. Einstellungen → **Alarmierung aktiv** einschalten.
2. **Push aktiv** einschalten — dabei wird automatisch ein Topic erzeugt.
3. Topic kopieren.

### 3.2 Empfangen — der einfachste Weg braucht kein zweites Gerät

Im Browser `https://ntfy.sh/<topic>` öffnen. Die Weboberfläche zeigt eingehende Nachrichten
sofort an. Das reicht für die Prüfung, ob der Versand überhaupt funktioniert.

Für die echte Alarmwirkung (Ton, „Nicht stören" durchbrechen) braucht es die ntfy-App auf dem
Zweitgerät mit demselben Topic.

> ⚠ Beim öffentlichen Server ist der Topic-Name das einzige Geheimnis. Wer ihn kennt, liest alle
> Alarme mit. Nicht in Screenshots, Tickets oder Chats posten.

### 3.3 Die zwei Fragen, die nur hier zu klären sind

**a) Kommt der Titel lesbar an?** Einstellungen → **Test-Push**. In der ntfy-Weboberfläche auf die
Titelzeile schauen:

- steht dort „Lärmprotokoll: Verbindung verloren" → ntfy dekodiert RFC 2047, alles gut
- steht dort `=?UTF-8?B?TMOkcm1w...?=` → ntfy dekodiert es nicht. Der Alarm kommt trotzdem an und
  der Text im Rumpf ist lesbar; dann sollte der Titel auf reines ASCII umgestellt werden
  („Laermprotokoll"). **Bitte zurückmelden, was dort steht.**

HTTP-Header dürfen nur ASCII enthalten — ohne Kodierung scheitert der Versand komplett. Welche der
beiden Varianten ntfy anzeigt, ließ sich ohne Netzzugang nicht klären.

**b) Kommt die lokale Meldung durch?** Einstellungen → **Test-Meldung**. Sie muss als
Heads-up-Benachrichtigung erscheinen, nicht still in der Leiste.

### 3.4 Was im Emulator *nicht* prüfbar ist

Der vollständige Ablauf „Messgerät fällt aus → 60 s Karenzzeit → Alarm" braucht eine
Verbindung, die abbrechen kann — also das echte Messgerät. Die Logik dahinter ist durch 21
Unit-Tests abgedeckt; ungeprüft bleibt allein das Zusammenspiel mit echten Zustandswechseln.

> **Offener Vorschlag:** Ein Debug-Schalter, der den vorhandenen `FakeMeterTransport` antreibt,
> würde genau diese Lücke schließen — dann ließe sich ein Verbindungsabbruch im Emulator
> auslösen und die ganze Kette bis zum Push durchspielen. Noch nicht gebaut.

---

## 4. Emulator einrichten

Android Studio → Device Manager → Create Device. Empfehlung: **API 34 oder 36, Image *mit* Play
Store** (dann lässt sich die ntfy-App direkt installieren).

Nützliche Befehle:

```bat
adb devices                                  :: läuft der Emulator?
adb install -r app-debug.apk
adb logcat -s AlarmCoordinator NtfyAlert AudioRecordingService ConnectionSupervisor
adb shell dumpsys deviceidle force-idle      :: Doze erzwingen
adb shell dumpsys deviceidle unforce
```

> Der Topic-Name taucht bewusst in keiner Logzeile auf. Wer ihn braucht, holt ihn aus den
> Einstellungen.

---

## 5. Was zwingend echtes Gerät braucht

Diese Punkte stehen in [`CHECKLISTE_GERAETETEST.md`](CHECKLISTE_GERAETETEST.md) und sind durch
nichts zu ersetzen:

- der gesamte BLE-Pfad gegen das PCE-323 (M2)
- Reconnect, Funkloch, Bluetooth aus/an, Prozess-Kill, Neustart (M3)
- Alarm bei echtem Verbindungsabbruch und die Entwarnung danach (M5)
- Totmannschaltung: Telefon ausschalten, prüfen ob der Dienst den ausbleibenden Ping meldet
- 24-h-Dauerlauf ohne Fehlalarm und ohne Speicherleck
