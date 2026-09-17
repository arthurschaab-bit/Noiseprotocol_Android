# Prompt: Saubere Versionskennung für CI- und Debug-Builds

**Auftraggeber-Entscheidungen vom 17.09.2026** — die vier Punkte in Abschnitt 2 sind entschieden
und nicht mehr zur Diskussion zu stellen. Alles andere in diesem Dokument ist Umsetzungsdetail;
wenn dir etwas davon falsch vorkommt, setze es trotzdem um und flagge es im PR (AGENTS.md §2).

---

## 1. Das Problem

`app/build.gradle.kts` (Zeilen 30–31):

```kotlin
versionCode = (findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
versionName = (findProperty("versionName") as String?) ?: "1.0"
```

Nur `.github/workflows/release.yml` reicht echte Werte per `-P` durch. `androidci.yml` tut das
nicht. Folge: **jede Debug-APK aus jedem CI-Lauf meldet sich als `1.0 (1)`.** Der Owner
installiert genau diese APKs (`docs/TESTEN_EINES_PR.md` §1) und kann auf dem Telefon nicht
feststellen, welchen Stand er vor sich hat. Es gibt zusätzlich **keinen einzigen Git-Tag** im
Repository — `release.yml` ist also noch nie gelaufen, das Schema aus
`docs/PROMPT_RELEASE_PIPELINE.md` §4 existiert bisher nur auf dem Papier.

Drei Stellen verbrauchen die Version bereits und schreiben deshalb alle „1.0" fort:

| Datei | Zeile | Was dort steht |
|---|---|---|
| `ui/ProtokollDetailScreen.kt` | 642 | `AuditDetailRow("App-Version", "Noise Protocol v${BuildConfig.VERSION_NAME}")` — im **gerichtsverwertbaren** Audit-Block |
| `backup/SicherungManager.kt` | 170–171 | `appVersionName` / `appVersionCode` im Sicherungs-JSON |
| `diagnose/export/SupportBundleExporter.kt` | 102, 160–161 | `appVersion` / `versionCode` im Diagnose-Export |

Die Audit-Zeile ist der fachlich schwerwiegendste Punkt: ein Beweisdokument behauptet dort eine
Versionsnummer, die keinen einzigen Build identifiziert.

Eine Anzeige zum Nachsehen auf dem Telefon existiert **nirgends** — weder in `SettingsScreen.kt`
noch in `DiagnoseScreen.kt`.

## 2. Entschieden (Owner, 17.09.2026)

1. **Kein Gradle-Plugin.** Geprüft und verworfen wurden `io.github.reactivecircus.app-versioning`
   1.6.0 (einzige Android-spezifische Lösung, min. AGP 8.2.2, kompiliert gegen AGP 9.1.1 — dieses
   Repo fährt AGP 9.2.1/Gradle 9.4.1, Kompatibilität also ungetestet), `pl.allegro.tech.build.
   axion-release` (kennt kein `versionCode`), `com.palantir.git-version` und
   `net.nemerosa.versioning` (dünne `git describe`-Wrapper, die Logik schreibt man trotzdem
   selbst). Begründung der Ablehnung: der Kernnutzen aller vier ist „aus Git-Tags ableiten" — der
   Schmerz liegt aber genau bei den **Debug-/CI-Builds, auf denen kein Tag liegt**. Man landete
   bei selbstgeschriebenen `override`-Lambdas in einer Abhängigkeit statt bei
   selbstgeschriebenen Zeilen ohne. AGENTS.md hält den Abhängigkeitsstil bewusst minimal.
   **Umsetzung also in eigenem Gradle-Code, die bestehende `-P`-Weiche wird ausgebaut.**
2. **Schema:** Basisversion + PR-Nummer + CI-Laufnummer + Commit-Kürzel (Abschnitt 3).
3. **Anzeige:** in den Einstellungen **und** im Diagnose-Screen, jeweils mit Tippen zum Kopieren.
4. **Zusätzlich anzufassen:** APK-Dateiname und CI-Artefaktname, die Audit-Zeile im
   Protokoll-Detail, Sicherung und Diagnose-Export.

## 3. Das Versionsschema

**Basisversion** an genau einer Stelle gepflegt: neuer Eintrag `basisVersion=1.0.0` in
`gradle.properties`. Sie gilt nur für den Debug-/CI-Zweig — der Release-Zweig leitet weiterhin
alles aus dem Git-Tag ab und wird von diesem Auftrag **nicht** angefasst.

### 3.1 `versionName`

| Fall | Format | Beispiel |
|---|---|---|
| CI, PR-Lauf | `<basis>-pr<N>.ci<Lauf>+<sha7>` | `1.0.0-pr181.ci342+a1b2c3d` |
| CI, Push auf `main` | `<basis>-main.ci<Lauf>+<sha7>` | `1.0.0-main.ci343+e4f5a6b` |
| Lokal, sauberer Baum | `<basis>-lokal+<sha7>` | `1.0.0-lokal+a1b2c3d` |
| Lokal, uncommittete Änderungen | `<basis>-lokal+<sha7>.dirty` | `1.0.0-lokal+a1b2c3d.dirty` |
| Lokal, kein Git verfügbar | `<basis>-lokal` | `1.0.0-lokal` |
| Release (Tag) | `X.Y.Z` — **unverändert** | `1.0.0` |

### 3.2 `versionCode`

| Fall | Wert |
|---|---|
| CI-Debug | die CI-Laufnummer (`github.run_number`) |
| Lokal | `1` — unverändert |
| Release | `X * 10000 + Y * 100 + Z` — unverändert |

Die Laufnummer ist streng monoton, damit `adb install -r` eine ältere durch eine neuere CI-APK
ersetzen kann, ohne an `INSTALL_FAILED_VERSION_DOWNGRADE` zu scheitern. Ein Wiederholungslauf
(„Re-run") behält dieselbe Laufnummer — richtig so, es ist derselbe Code.

**Nicht „reparieren":** CI-Debug-Codes (dreistellig) liegen unter den Release-Codes (ab 10000).
Das ist folgenlos, weil Debug- und Release-APK mit **verschiedenen Schlüsseln** signiert sind —
zwischen ihnen gibt es ohnehin keinen Update-Pfad, sondern nur Deinstallieren und Neuinstallieren.
Bitte als Kommentar im Code festhalten, damit es später niemand für einen Bug hält.

### 3.3 Vorrangregel

Ist `-PversionName` bzw. `-PversionCode` gesetzt, **gewinnt die Property** und der Debug-Zweig
rechnet gar nicht erst. Damit bleibt `release.yml` unverändert funktionsfähig, ohne dass du sie
anfassen musst (Ausnahme: die Dateipfade, siehe 4.3).

## 4. Umsetzung

### 4.1 `app/build.gradle.kts` — Werteermittlung

Neue Gradle-Properties, benannt wie die bestehenden (camelCase, kein Präfix):
`buildSha`, `buildPrNumber`, `buildCiRun`.

**Die wichtigste Falle:** Ermittle die SHA im CI **nicht** selbst aus Git. `actions/checkout@v4`
checkt bei `pull_request`-Läufen einen **Merge-Commit** aus; `git rev-parse HEAD` liefert dort
eine SHA, die in keinem PR und auf keinem Branch existiert und die man in GitHub nicht
wiederfindet. Die SHA kommt deshalb aus dem Workflow (Abschnitt 4.3) per `-PbuildSha`. Nur wenn
die Property fehlt (= lokaler Build), fragst du Git selbst.

Für den lokalen Fall:

- `providers.exec { ... }` verwenden — **nicht** `project.exec`, nicht `"git ...".execute()`,
  nicht `ProcessBuilder` direkt. Nur `providers.exec` ist mit der Konfigurations-Cache
  verträglich, und die muss weiterhin funktionieren (Abschnitt 6).
- `git rev-parse --short=7 HEAD` für die SHA, `git status --porcelain` (leere Ausgabe = sauber)
  für den dirty-Marker.
- **Jeder Fehlerfall muss weich landen:** kein Git installiert, kein Git-Repository, Exit-Code
  ungleich 0, leere Ausgabe → auf `<basis>-lokal` zurückfallen. Der Build darf daran **niemals**
  scheitern. Cloud-Sandboxes und ZIP-Downloads ohne `.git` sind reale Fälle.
- **Kein Zeitstempel** im Versionsnamen. Er würde bei jedem einzelnen Build die
  Manifest-Merge- und BuildConfig-Tasks invalidieren und den Gradle-Build-Cache wertlos machen.

**Keine zusätzlichen `buildConfigField`s anlegen.** `BuildConfig.VERSION_NAME` trägt bereits die
vollständige Kennung; ein separates `GIT_SHA`-Feld wäre eine zweite Wahrheit, die auseinander
laufen kann. Wenn du beim Umsetzen überzeugt bist, dass es eines braucht, begründe es im PR,
statt es stillschweigend hinzuzufügen.

ktlint läuft hier mit `android.set(true)`, also **maximal 100 Zeichen pro Zeile** — in dieser
Datei gab es dafür schon einen Nachbesserungs-Commit (`39dc20d`).

### 4.2 `app/build.gradle.kts` — APK-Dateiname

Aktuell heißt jede Datei `app-debug.apk`. Lade drei Artefakte herunter und du hast drei
gleichnamige Dateien im Download-Ordner — genau der Verwechslungsfall, um den es geht.

Über `base { archivesName.set(...) }` auf `laermprotokoll-<versionName>` setzen, Ergebnis z. B.
`laermprotokoll-1.0.0-pr181.ci342-a1b2c3d-debug.apk`. Den Versionsnamen dafür **bereinigen**:
alles außer `[A-Za-z0-9._-]` durch `-` ersetzen (das `+` aus dem Schema ist in Dateinamen und
URLs unschön).

Sollte `archivesName` sich mit AGP 9.2.1 nicht wie erwartet verhalten, ist die Variant-API
(`androidComponents.onVariants`) der Rückfallweg — dann aber im PR vermerken, warum.

### 4.3 `.github/workflows/` — die Werte durchreichen

**`androidci.yml`:** im Schritt „Build Debug & Test APKs" (beide Zweige der `if`-Abfrage!) die
drei Properties ergänzen:

- `-PbuildSha=` → bei `pull_request` aus `github.event.pull_request.head.sha`, sonst
  `github.sha`; auf 7 Zeichen kürzen.
- `-PbuildPrNumber=` → `github.event.pull_request.number`, bei Push auf `main` leer lassen
  (dann greift die `main`-Variante aus 3.1).
- `-PbuildCiRun=` → `github.run_number`.

Außerdem den Artefaktnamen (Zeile 153) von `app-debug-apk` auf
`app-debug-apk-${{ github.run_number }}` ändern und den Kommentar in Zeile 148 mitziehen (der
Dateiname `app-debug.apk` darin stimmt nicht mehr). Der `path:` ist bereits ein Glob
(`app/build/outputs/apk/debug/*.apk`) und funktioniert unverändert weiter.

**`release.yml` — Pflichtanpassung, sonst brichst du den Release-Pfad:** Zeilen 97–98 listen
`app-release.apk` und `app-release-unsigned.apk` mit **festem Namen** als Release-Anhang. Durch
4.2 heißen die Dateien anders, und `softprops/action-gh-release` würde **stillschweigend nichts**
hochladen. Beide Zeilen auf Globs umstellen (`app/build/outputs/apk/release/*.apk`). Sonst
bleibt `release.yml` unangetastet.

**`emulator-tests.yml`** braucht nichts: der Workflow lädt keine APK hoch und referenziert keinen
Dateinamen.

### 4.4 Neu: `Versionskennung.kt`

Neue Datei `app/src/main/java/com/example/lrmprotokoll/Versionskennung.kt`. Zweck: die
Anzeige-Logik aus `BuildConfig` herauslösen, damit sie **ohne Robolectric** als reine
JVM-Unit-Tests prüfbar ist (AGENTS.md §6: neue Logik bekommt Tests).

Die Funktionen nehmen `versionName`/`versionCode` als **Parameter** entgegen — nicht aus
`BuildConfig` lesen —, damit die Tests alle Fälle aus der Tabelle in 3.1 durchspielen können.
Ein schlanker Convenience-Zugriff darüber darf `BuildConfig` verwenden.

Mindestens:

- `formatiere(versionName, versionCode)` → `"1.0.0-pr181.ci342+a1b2c3d (342)"` — die Zeichenkette,
  die in die Zwischenablage und in die Exporte geht.
- `istReleaseBuild(versionName)` → `true` nur beim reinen `X.Y.Z` ohne Suffix. Wird für die
  Audit-Zeile (4.6) und einen Hinweis in der UI gebraucht.

Deutsche Bezeichner sind hier in Ordnung — das Repo mischt (`SicherungManager`,
`ConnectionSupervisor`); orientiere dich an den Nachbardateien.

Tests nach `app/src/test/.../VersionskennungTest.kt`, **alle sechs** Fälle aus 3.1.

### 4.5 Anzeige in der App

**`ui/SettingsScreen.kt`:** neuer Abschnitt „Über die App" **ganz unten**. Verwende das
vorhandene ausklappbare Abschnitts-Composable dieser Datei (das mit
`AnimatedVisibility(visible = expanded)`), nicht ein neues. Inhalt: die volle Kennung aus
`Versionskennung.formatiere(...)`; ein Antippen kopiert sie in die Zwischenablage und quittiert
das sichtbar. Ist `istReleaseBuild()` falsch, zusätzlich eine ruhige Zeile in der Art
„Test-/Entwicklungsstand, kein offizieller Release" — kein Warn-Rot, das ist der Normalfall für
den Owner.

Das Kopier-Muster steht bereits in `ui/DiagnoseScreen.kt` um Zeile 283
(`clipboard.setPrimaryClip(ClipData.newPlainText(...))`) — dasselbe verwenden, nichts Eigenes.

**`ui/DiagnoseScreen.kt`:** dieselbe Kennung als Zeile in der Karte, in der schon die
Diagnose-ID steht, mit demselben Kopier-Verhalten. Beim Support-Fall braucht man beides zusammen.

UI-Texte deutsch. Die Datei mischt `stringResource(R.string...)` und direkte deutsche Literale —
halte dich an das, was in der unmittelbaren Umgebung steht, statt einen Stil zu erzwingen.

Vergib `testTag`s, damit die Anzeige in einem Compose-Test unter Robolectric prüfbar ist (das
Repo testet Compose bereits so, siehe `SettingsScreenComposeTest` und
`DiagnoseScreenComposeTest`). Am Muster von
`BILDSCHIRM_ENDE_TAG` in `SettingsScreen.kt` orientieren.

### 4.6 Die drei bestehenden Verbraucher

- **`ui/ProtokollDetailScreen.kt:642`** — aus `"Noise Protocol v${BuildConfig.VERSION_NAME}"` wird
  die volle Kennung über `Versionskennung`. Bei einem Nicht-Release-Build muss aus der Zeile
  **erkennbar hervorgehen, dass es kein offizieller Release-Stand ist** — dieser Block ist das
  gerichtsverwertbare Audit-Protokoll, und eine Aussage, die einen offiziellen Stand suggeriert,
  wo ein CI-Zwischenstand lief, ist dort schlimmer als gar keine Aussage. Formulierung ist dein
  Vorschlag; im PR benennen, wofür du dich entschieden hast.
- **`backup/SicherungManager.kt:170–171`** — `appVersionName` schreibt die volle Kennung.
  `appVersionCode` bleibt numerisch. **Vorher prüfen**, ob der Wiederherstellungs-Pfad
  `appVersionName` irgendwo parst oder vergleicht (nach aktuellem Stand wird das Feld nur
  geschrieben, aber verlasse dich nicht darauf — lies den Restore-Code). Falls doch: nicht
  brechen, und im PR melden.
- **`diagnose/export/SupportBundleExporter.kt:102, 160–161`** — analog, beide Stellen.

### 4.7 Doku

Anpassen, weil dort heute falsche Namen stehen:

- `docs/TESTEN_EINES_PR.md` §1, Zeilen 21–23 und 35 sowie 140 (Artefaktname, Dateiname,
  `adb install`-Aufruf). Der Abschnitt „Welcher Lauf enthält was?" darf jetzt kürzer werden: die
  Frage beantwortet die App selbst über „Über die App".
- `README.md` Zeile 487 (Artefaktbeschreibung) und der umgebende CI-Abschnitt.
- `docs/PROMPT_RELEASE_PIPELINE.md` §4: **einen Querverweis** ergänzen, dass für Debug-/CI-Builds
  jetzt zusätzlich dieses Dokument gilt. Den bestehenden Text nicht umschreiben.

**Nicht anfassen:** `docs/PROMPT_B11.md` Zeile 90 und `docs/PROMPT_M1.md` Zeile 190 nennen
`app-debug.apk` ebenfalls, sind aber Protokolle abgeschlossener Aufträge. Sie dokumentieren, was
damals galt, und werden nicht rückwirkend umgeschrieben.

## 5. Ausdrücklich nicht Teil dieses Auftrags

- **Keinen Git-Tag setzen, keinen Release auslösen.** Der Owner hat das bewusst nicht beauftragt.
  Der Release-Pfad wird nur so weit angefasst, wie 4.3 es zwingend verlangt.
- **Sentry nicht konfigurieren.** Sentry übernimmt `versionName` automatisch als Release-Kennung
  und profitiert damit ohne Zutun. Mehr wäre ein eigener Auftrag.
- Kein `CHANGELOG.md`, keine automatische Versionserhöhung, kein `git describe` (es gibt keine
  Tags), kein Zeitstempel im Versionsnamen.
- Keine Änderung an `applicationId`, `minSdk`, Signier-Konfiguration oder Keystore-Handhabung.

## 6. Verifikation — vorzeigen, nicht behaupten

AGENTS.md §6: Ausgabe in den PR, keine Zusammenfassung davon. Wenn du etwas nicht prüfen konntest,
schreib genau das hin.

1. `./gradlew assembleDebug` — grün.
2. **Die Version im gebauten APK tatsächlich nachsehen**, nicht aus dem Gradle-Log schließen:
   `$ANDROID_HOME/build-tools/36.0.0/aapt2 dump badging <apk> | head -2`. Drei Läufe, alle drei
   Ausgaben zeigen:
   - ohne Properties → `versionName='1.0.0-lokal+<sha>'`, `versionCode='1'`
   - `-PbuildPrNumber=181 -PbuildCiRun=342 -PbuildSha=a1b2c3d` →
     `versionName='1.0.0-pr181.ci342+a1b2c3d'`, `versionCode='342'`
   - `assembleRelease -PversionName=1.0.0 -PversionCode=10000` → `versionName='1.0.0'`,
     `versionCode='10000'` — **der Beleg dafür, dass der Release-Pfad unberührt ist.**
3. `./gradlew assembleDebug --configuration-cache` — muss durchlaufen. Das ist das eigentliche
   Risiko bei `providers.exec`; ohne diesen Lauf ist der Schritt nicht fertig.
4. `./gradlew test` grün, inklusive der neuen `VersionskennungTest` und **beider bestehender
   Room-Migrationstests**.
5. `./gradlew ktlintCheck` — in den von dir angefassten Dateien keine **neuen** Verstöße. (Der
   Bestand hat mehrere tausend, der CI-Schritt läuft deshalb mit `continue-on-error` — das ist
   kein Freibrief für neue.)
6. Den Bereinigungs-Fall des Dateinamens einmal wirklich ansehen: `ls app/build/outputs/apk/debug/`
   nach Lauf 2 — die Ausgabe zeigen.
7. Was du **nicht** prüfen kannst und auch nicht behaupten sollst: dass der geänderte
   `androidci.yml`-Schritt auf GitHub durchläuft, und dass `release.yml` mit den Globs ein echtes
   Release anhängt. Beides zeigt sich erst beim ersten echten Lauf. Im PR so benennen.

## 7. Definition of Done

1. Punkte 1–6 aus Abschnitt 6 erledigt, Ausgaben im PR.
2. Alle vier Owner-Entscheidungen aus Abschnitt 2 umgesetzt, jede einzeln adressiert.
3. Branch `feature/versionskennung` von `main` (AGENTS.md §5 — **niemals direkt auf `main`**),
   Commits klein und auf Deutsch.
4. **Draft-PR** gegen `main`. Im Rumpf: was geändert · was verifiziert (Befehl + Ausgabe) · was
   bewusst offen geblieben ist (mindestens 6.7) · jeder Widerspruch zum Plan oder jede offene
   Entscheidung, auf die du gestoßen bist.
5. Kurze Rückmeldung an den Owner: erledigt / nicht erledigt / aufgefallen.

## 8. Wenn du auf etwas Ungeklärtes stößt

AGENTS.md §8a gilt: **nicht raten und trotzdem bauen.** Melde dich mit der konkreten Frage, bevor
du eine Annahme in Code gießt. Zwei Stellen, an denen das wahrscheinlich ist:

- Der Wiederherstellungs-Pfad in `SicherungManager` verarbeitet `appVersionName` doch (4.6).
- `archivesName` verhält sich unter AGP 9.2.1 anders als erwartet und die Variant-API wäre nötig
  (4.2) — das ist eher zu melden als still umzubauen.
