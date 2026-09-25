# Prompt: UX-Roadmap Phase 2 — Sichtbarkeit herstellen (vorhandene Logik verdrahten)

Umsetzung von **Phase 2** der Roadmap aus `docs/UX_UI_AUDIT.md` (Kapitel 33). Fünf Findings:
**F-04**, **F-05**, **F-09**, **F-11**, **F-12**.

**F-14 aus dieser Phase ist bereits erledigt** (PR #203, gemergt). Nicht erneut anfassen.

Diese Phase hat eine Besonderheit, die sie leicht macht: **Fast nichts davon ist neue Logik.**
Die Zustände werden im Code bereits ermittelt, berechnet oder geloggt — sie erreichen den
Nutzer nur nicht. Kapitel 19 des Audits („Visibility of System Status — was intern existiert und
nicht ankommt") listet sieben solche Fälle. Deine Aufgabe ist überwiegend Verdrahtung, nicht
Erfindung.

**Der Audit ist die Spezifikation.** Lies zu jedem Finding den vollständigen Eintrag; dort
stehen Code mit Zeilennummern, Zielverhalten und Testauswirkung.

---

## 0 · Arbeitsregeln

`AGENTS.md` vollständig lesen. Insbesondere:

- `git fetch origin`, dann `git switch -c feature/ux-phase2-sichtbarkeit origin/main`.
  **Nie auf `main` pushen.**
- Conventional Commits, Typ englisch, Beschreibung deutsch, klein geschnitten.
- Code-Bezeichner englisch, UI-Texte deutsch; neue Texte nach `strings.xml` in **allen drei**
  Ressourcenordnern (`values/`, `values-de/`, `values-en/`).
- `./gradlew assembleDebug lintDebug test` grün, `./gradlew ktlintCheck` ohne neue Befunde in
  den geänderten Dateien. **Ausgabe in den PR.**
- **Nie behaupten, etwas funktioniere, ohne es ausgeführt zu haben.**
- Keine Schemaänderung in dieser Phase. `fallbackToDestructiveMigration()` bleibt verboten.
- Draft-PR gegen `main`, Definition of Done nach `AGENTS.md` §7.

**Verifiziere jede Zeilenangabe am Code.** Stimmt eine nicht mehr, gilt der Code; die Abweichung
gehört in den PR.

---

## 1 · F-04 — Die System-Selbstprüfung lügt an drei Stellen

`MainActivity` übergibt drei Prüfergebnisse **fest verdrahtet als `true`**, statt sie zu
ermitteln: Bluetooth-Berechtigung, exakte Alarme, Bluetooth-Adapter eingeschaltet
(`MainActivity.kt:524`, `:526`, `:527`). Der `DiagnoseScreen` macht dasselbe für zwei davon
(`:139`, `:140`).

Die Folge ist schlimmer als „eine Prüfung fehlt": Die Selbstprüfung meldet **Grün für einen
Zustand, den sie nie angesehen hat**. Ein Nutzer mit ausgeschaltetem Bluetooth bekommt gesagt,
alles sei in Ordnung.

Die nötige Beobachtung existiert bereits: `AppContainer.kt:59` hält einen
`bluetoothAdapterStateObserver` — er ist nur `private`.

**Was zu tun ist:** Die drei Werte tatsächlich ermitteln. Den vorhandenen Observer zugänglich
machen, statt einen zweiten zu bauen. Für die Berechtigungen die Prüfwege nutzen, die der
`SystemHealthChecker` für die übrigen Punkte schon verwendet.

**Falle:** `canScheduleExactAlarms` verhält sich je nach API-Level unterschiedlich. `minSdk` ist
**29**, nicht 31 — der Plan, auf 31 zu gehen, wurde nie umgesetzt. Prüf die Verfügbarkeit, statt
sie anzunehmen.

### Abnahme F-04
- Mit ausgeschaltetem Bluetooth meldet die Selbstprüfung das — in `MainActivity` **und** im
  `DiagnoseScreen`.
- Kein hartkodiertes `true` mehr an den fünf genannten Stellen.
- Tests für jeden der drei Prüfpfade, echte und verweigerte Berechtigung.

---

## 2 · F-05 — Alle Aktionsknöpfe öffnen denselben System-Screen

`DiagnoseScreen.kt:229-240` wertet `checkItem.actionType` **nicht aus** und öffnet immer
`ACTION_APPLICATION_DETAILS_SETTINGS`. Wer wegen ausgeschaltetem Bluetooth auf „Beheben" tippt,
landet in den App-Details statt in den Bluetooth-Einstellungen — und muss selbst herausfinden,
was gemeint war.

**Was zu tun ist:** `actionType` auswerten und je Fall den passenden Intent öffnen. Für jeden
Fall einen Rückfallweg vorsehen: Nicht jedes Gerät beantwortet jeden Settings-Intent, ein
`ActivityNotFoundException` darf die App nicht beenden.

### Abnahme F-05
- Je `actionType` der passende Screen; belegt je Fall.
- Kein `ActivityNotFoundException` erreicht den Nutzer.
- Test je Fall, dass der erwartete Intent ausgelöst wird.

---

## 3 · F-09 und F-11 — Fehler, die nur in der Notification stehen

Zwei verwandte Fälle: der stille Ausfall samt Audio-Soll/Ist-Abweichung (F-09) und der
Mikrofon-Initialisierungsfehler (F-11). Beide sind erkannt und werden gemeldet — **aber nur in
der Notification** bzw. gar nicht in der App. Wer die App offen hat, sieht nichts.

**Was zu tun ist:** Den Zustand aus dem `AudioRecordingService` als `StateFlow` herausführen und
im Cockpit als Banner darstellen. Der Audit beschreibt das State-Modell.

**Falle:** Der Service läuft im Vordergrund und überlebt die Activity. Der Flow muss den
Lebenszyklus überstehen — häng ihn an den `AppContainer`, nicht an eine Composable.

### Abnahme F-09/F-11
- Ein erzwungener Mikrofonfehler erzeugt ein sichtbares Banner im Cockpit, nicht nur eine
  Notification.
- Die Soll/Ist-Abweichung der Audioaufzeichnung ist in der App sichtbar.
- Das Banner verschwindet, wenn der Zustand sich behebt.
- Tests für den neuen `StateFlow` und für die Bannerdarstellung.

---

## 4 · F-12 — Messintegrität wird erfasst und nirgends gezeigt

`berechneDatenverfuegbarkeitProzent` (`messreihe/Datenverfuegbarkeit.kt:14`) hat genau **einen**
Aufrufer: `report/GesamtberichtDaten.kt:125`. In der Oberfläche erscheint der Wert nie.
`MeasurementFlags.GAP` (`data/SessionEntity.kt:107`) hat **gar keinen** UI-Konsumenten.

Das heißt: Die App weiß, dass eine Messreihe Lücken hat — und sagt es dem Nutzer erst im
fertigen Bericht, wenn es zu spät ist, etwas zu ändern.

**Was zu tun ist:** Integritätsstatus in `ProtokollScreen` und `ProtokollDetailScreen`
darstellen. Der Audit nennt die Zustände.

**Falle:** Es geht um eine Aussage über **Beweiskraft**. Formuliere zurückhaltend und ohne
juristische Wertung — „Datenverfügbarkeit 87 %, 2 Lücken", nicht „Messung unbrauchbar".

### Abnahme F-12
- Datenverfügbarkeit und Lückenkennzeichnung sind in Liste und Detail sichtbar.
- Neue JVM-Tests für `bewerteMessintegritaet`.
- Vorhandene Protokoll-Tests bleiben grün.

---

## 5 · Reihenfolge

F-04 und F-05 zuerst (beide in der Diagnose, gleicher Code-Bereich, gemeinsam zu testen), dann
F-12, zuletzt F-09/F-11 als gemeinsamer Block — der braucht den neuen `StateFlow` und ist der
aufwendigste Teil.

---

## 6 · Was ausdrücklich **nicht** Teil des Auftrags ist

- **F-14** — bereits erledigt und gemergt.
- Jede Schemaänderung (→ Phase 4), jeder strukturelle Umbau (→ Phase 6).
- Den Foreground-Service-Lebenszyklus anfassen. Der Audit stuft das als hohes Risiko ein, und
  das README nennt ihn mehrfach als Quelle von Gerätetest-Befunden.

---

## 7 · Definition of Done

1. `assembleDebug`, `lintDebug`, `test` grün — **Ausgabe im PR**.
2. Room-Migrationstests weiterhin grün (Nachweis im PR, auch wenn nichts am Schema geändert wird).
3. Alle fünf Findings einzeln adressiert, je mit den genannten Abnahmekriterien.
4. Draft-PR gegen `main`: was geändert · was verifiziert (Kommando + Ergebnis) · was offen blieb ·
   jede Abweichung vom Audit.
5. Kurzmeldung an den Owner.
