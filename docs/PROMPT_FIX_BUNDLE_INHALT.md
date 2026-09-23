# Prompt: Bugfix — Zwei Ungenauigkeiten im Support-Bundle

**Priorität 3.** Zwei kleine, voneinander unabhängige Korrekturen am Inhalt des Support-Bundles,
gefunden beim Gerätetest am 23.09.2026. Belege:
[`BEFUNDE_P30_2026-09-23.md`](BEFUNDE_P30_2026-09-23.md), Abschnitt 4.

**Prüfe jede genannte Stelle am aktuellen Code**, bevor du etwas änderst. Stimmt eine Angabe nicht
mehr, richte dich nach dem Code und melde die Abweichung im PR.

---

## 0 · Arbeitsregeln

`AGENTS.md` vollständig lesen; sie gilt unverändert. Besonders wichtig:

- **Vor dem Start:** `git fetch origin`, dann `git switch -c fix/bundle-inhalt origin/main`. Nie
  auf `main` pushen.
- **Commits:** Conventional Commits, Typ englisch, Beschreibung deutsch; ein Commit je Teil.
  Beispiel: `fix(diagnose): Google-Kontoname im Support-Bundle schwaerzen`.
- **Nach jeder Änderung:** `./gradlew assembleDebug lintDebug test` und `./gradlew ktlintCheck`.
  - ktlint: keine neuen Befunde in den geänderten Dateien.
  - Nur bei Grün committen und pushen. Ist ein Test rot, den deine Änderung nicht berührt: mit
    Ausgabe im PR melden und den Owner fragen.
- **Nur handgeschriebene Fakes**, kein Mockito, kein MockK. Keine Schemaänderung.
- Nie behaupten, etwas funktioniere, ohne es ausgeführt zu haben. Kommandoausgaben gehören in den
  PR.
- **Draft-PR gegen `main`**, Definition of Done nach AGENTS.md §7.

---

## Teil 1 — Google-Kontoname im Klartext

### Befund

`state/settings.json` jedes Bundles enthält:

```json
"google_account_name": "<Vor- und Nachname des Owners>",
"google_account_email": "[REDACTED_EMAIL]",
```

- Die E-Mail-Adresse schwärzt der `DiagnosticRedactor` über ein Muster; den Anzeigenamen nicht.
- Bundles werden auch geteilt („Support-Bundle exportieren (ZIP)“).

### Ursache

- `diagnose/export/SupportBundleExporter.kt`, `buildSettingsJson()` (Zeile 401): Die Werte gehen
  durch `DiagnosticRedactor.redactMap(...)`.
- `diagnose/DiagnosticRedactor.kt` (Zeile 11): `SENSITIVE_KEYS` enthält nichts, worauf
  `google_account_name` passt. Der Wert stammt aus
  `GoogleSignInAccessTokenProvider` (`settings?.googleAccountName = zugangsdaten.displayName`).

### Auftrag

- `"account_name"` in `SENSITIVE_KEYS` aufnehmen.
- **Nicht** das breite `"account"`: Das würde Schlüssel treffen, die nicht persönlich sind.
- Prüfe mit `grep`, ob der Anzeigename noch anderswo in Diagnose-Events, Breadcrumbs oder Logzeilen
  landet. Falls ja, im PR auflisten und dort ebenfalls schwärzen.

### Test

- In der vorhandenen Testklasse zum Redactor bzw. zu `SupportBundleExporter` (suchen):
  - `google_account_name` mit einem Klarnamen führt zu einem geschwärzten Wert in `state/settings.json`.
  - Ein unverdächtiger Schlüssel (z. B. `drive_folder_name`) bleibt unverändert.
- Der Test muss ohne die Änderung rot sein.

---

## Teil 2 — Berechtigungen, die es auf dem Gerät nicht gibt, stehen als `false` da

### Befund

`state/runtime.json` vom Huawei P30 (Android 10, API 29):

```json
"android.permission.FOREGROUND_SERVICE_MICROPHONE": false,
"android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE": false,
"android.permission.POST_NOTIFICATIONS": false,
"android.permission.BLUETOOTH_SCAN": false,
"android.permission.BLUETOOTH_CONNECT": false,
"android.permission.SCHEDULE_EXACT_ALARM": false,
```

- Diese Berechtigungen gibt es auf API 29 **gar nicht**: API 31, 33 bzw. 34.
- `false` liest sich wie „Recht fehlt“ und führt bei der Fehlersuche in die Irre.

### Ursache

`diagnose/export/SupportBundleExporter.kt` (etwa Zeile 376–385): Für jede angeforderte
Berechtigung wird nur das Flag `REQUESTED_PERMISSION_GRANTED` ausgewertet.

### Auftrag

Für jede angeforderte Berechtigung prüfen, ob die Plattform sie kennt: über
`packageManager.getPermissionInfo(name, 0)`. `NameNotFoundException` heißt „gibt es hier nicht“.

**Ausgabeformat, Owner-Vorgabe (vorbelegt), im PR ausdrücklich nennen:**
- `"berechtigungen"` enthält weiterhin nur `name: true/false` für die Berechtigungen, die es auf
  dem Gerät gibt. Die Typen bleiben stabil.
- Neu ist `"berechtigungenNichtVorhanden": ["android.permission.POST_NOTIFICATIONS", …]` für die
  übrigen.

Hinweis: Unter Robolectric kennt der `PackageManager` Plattform-Berechtigungen womöglich nicht.
Dann gilt:
- Die Prüfung hinter einer kleinen, injizierbaren Funktion verbergen.
- **Keine** Liste mit API-Levels von Hand pflegen, außer der generische Weg erweist sich als
  unzuverlässig. Dann anhalten und den Owner fragen.

### Test

- Robolectric mit einer Fake-Prüfung: Eine Berechtigung, die die Prüfung als nicht vorhanden
  meldet, erscheint nur in `berechtigungenNichtVorhanden`. Eine vorhandene erscheint wie bisher mit
  `true/false`.
- Wenn möglich zusätzlich `@Config(sdk = [29])` gegen den echten Weg.
- Der Test muss ohne die Änderung rot sein.

---

## Akzeptanzkriterien

- [ ] Der Google-Kontoname erscheint in keinem Bundle mehr im Klartext.
- [ ] `runtime.json` trennt „verweigert“ (`false`) von „gibt es auf diesem Gerät nicht“.
- [ ] `assembleDebug lintDebug test` grün, keine neuen ktlint-Befunde. Ausgabe im PR.

## Gerätecheck (macht der Owner nach dem Merge)

Ein Bundle auf dem P30 erstellen:
- In `state/settings.json` ist `google_account_name` geschwärzt.
- In `state/runtime.json` stehen `POST_NOTIFICATIONS` & Co. unter `berechtigungenNichtVorhanden`.

Trag das als neue Zeile in `docs/CHECKLISTE_GERAETETEST.md` Teil F ein (Ergebnis-Spalte leer).
