# Prompt: UX-Roadmap Phase 7 — Aufräumen

Umsetzung von **Phase 7** der Roadmap aus `docs/UX_UI_AUDIT.md` (Kapitel 33). Sechs Findings:
**F-25**, **F-24**, **F-29**, **F-17**, **F-19**, **F-20**.

Diese Phase kann jederzeit zwischen die anderen geschoben werden — sie hat keine Abhängigkeit
nach oben. **Aber sie zerfällt in zwei sehr ungleiche Teile**, und der zweite ist größer, als er
aussieht.

**Der Audit ist die Spezifikation.** Lies zu jedem Finding den vollständigen Eintrag.

---

## 0 · Arbeitsregeln

`AGENTS.md` vollständig lesen. Insbesondere:

- `git fetch origin`, dann `git switch -c chore/ux-phase7-aufraeumen origin/main`.
  **Nie auf `main` pushen.** F-20 bekommt einen eigenen Branch, siehe unten.
- Conventional Commits, Typ englisch, Beschreibung deutsch, klein geschnitten.
- `./gradlew assembleDebug lintDebug test` grün, `ktlintCheck` ohne neue Befunde in den
  geänderten Dateien. **Ausgabe in den PR.**
- **Nie behaupten, etwas funktioniere, ohne es ausgeführt zu haben.**
- Keine Schemaänderung.
- Draft-PR gegen `main`, Definition of Done nach `AGENTS.md` §7.

---

## Teil A — die vier kleinen (ein PR)

### F-25 · Toter UI-Code
`QuickEventTagDialog`, `SettingQuickRow`, `StatCard`, zwei ungenutzte `LiveCockpitCard`-Parameter
und zwei nie gelesene States: entfernen oder verdrahten.

**Achtung:** `leiteDashboardAnzeigeAb` steht im Audit ebenfalls unter F-25 — der wird aber in
**Phase 1** (F-06) produktiv genutzt. Prüf den Stand, bevor du ihn anfasst; wenn Phase 1 gemergt
ist, ist er kein toter Code mehr.

### F-24 · Uneinheitliche Navigation zu `meter` und `diagnose`
Eine Regel festlegen. Vorschlag des Audits: beide sind Unterseiten, „Zurück" kehrt zum Absender
zurück, also überall `navigate`. Die Bottom-Nav bleibt sichtbar und markiert dort keinen Eintrag
— so ist es heute schon.

### F-29 · Messgerät-Screen liegt vier Ebenen tief
Zwei Möglichkeiten: (a) „Gerät verwalten" im Bluetooth-Badge-Menü, (b) ein vierter
Bottom-Nav-Eintrag. **(a) ist die kleinere Änderung und passt zur bestehenden Drei-Tab-Struktur**
— nimm sie, wenn der Owner nichts anderes sagt.

### F-17 · Klassifizierungsstatus, drei verschiedene Null-Verhalten
Das „nichts erkannt"-Verhalten auf **einen** Weg festlegen und in allen Pfaden gleich behandeln.
`R.string.status_not_recognized` existiert bereits. Keine Persistenzänderung.

---

## Teil B — F-19 und F-20 (jeweils eigener PR)

### F-19 · 28 hartkodierte Farbliterale umgehen das Dark-Schema

**Owner-Entscheidung vom 25.09.2026: freigegeben** („ja gerne"). Der README-Vorbehalt ist damit
erledigt.

**Wichtiger Hinweis des Audits zur Vorgehensweise:** Heute ist jede Nuance leicht anders. Leg
deshalb **je Bedeutung einen Token fest** — verbunden / verbindet / instabil / fehlgeschlagen /
inaktiv — und bilde alle Literale darauf ab. **Nicht Literal für Literal übersetzen**, sonst
zementierst du 28 zufällige Farbtöne in 28 Token.

Der Audit führt „Dark-Mode-Wirkung der Farbliterale" zusätzlich als ungeprüft (Kapitel 35.1):
der Codebefund ist eindeutig, die optische Wirkung nicht gemessen. **Sieh sie dir am Emulator in
hell und dunkel an**, bevor du die Token festlegst.

### F-20 · 156 nicht lokalisierte UI-Literale · **eigener Branch, eigener PR**

Der Audit warnt ausdrücklich: **F-20 bricht viele Compose-Tests, die auf deutschen Text
matchen.** Das ist kein Nebeneffekt, sondern der Hauptaufwand.

Die Zahl „~59" im README ist veraltet — es sind 156 allein für `Text("…")` unter `ui/`.

**Vorgehen:**
1. Bei den sichtbarsten Dialogen beginnen, nicht alles auf einmal.
2. Für jedes verschobene Literal den zugehörigen Test auf `getString(R.string.…)` umstellen —
   der Flakiness-Bericht (`docs/CI_FLAKINESS_UNTERSUCHUNG_BERICHT.md` §5.3) nennt hartkodierte
   Lokalisierungsstrings in Tests ausdrücklich als Flake-Ursache.
3. Alle drei Ressourcenordner pflegen: `values/`, `values-de/`, `values-en/`.

**Nicht in einem PR mit Teil A oder F-19 mischen.** Ein PR, der gleichzeitig aufräumt und 156
Literale verschiebt, ist nicht mehr reviewbar.

---

## 1 · Reihenfolge

1. **Teil A** als ein PR (F-25, F-24, F-29, F-17).
2. **F-19** als eigener PR.
3. **F-20** als eigener PR, schrittweise, zuletzt.

---

## 2 · Was ausdrücklich **nicht** Teil des Auftrags ist

- Alles aus den Phasen 1–6.
- Farbwerte neu erfinden. Es geht um Vereinheitlichung bestehender Bedeutungen, nicht um ein
  neues Farbkonzept. Wenn dir auffällt, dass das Farbkonzept selbst fragwürdig ist: **melden,
  nicht ändern** (`AGENTS.md` §8a).
- Tests abschwächen oder überspringen, um F-20 schneller grün zu bekommen. Ein auf deutschen
  Text matchender Test wird **umgestellt**, nicht gelöscht.

---

## 3 · Definition of Done

1. `assembleDebug`, `lintDebug`, `test` grün — **Ausgabe im PR**.
2. Bei F-19: Screenshots oder Beschreibung von hell und dunkel im PR; wenn kein Emulator
   verfügbar war, steht genau das dort.
3. Bei F-20: jeder angepasste Test benannt, mit Begründung der Umstellung.
4. Draft-PR gegen `main`: was geändert · was verifiziert (Kommando + Ergebnis) · was offen blieb.
5. Kurzmeldung an den Owner.
