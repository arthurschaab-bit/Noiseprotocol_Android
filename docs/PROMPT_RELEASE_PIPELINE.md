# Prompt: Release-Pipeline (signierte APKs über GitHub Releases)

Plan für eine Release-Pipeline, ergänzend zur bestehenden `androidci.yml` (die bleibt unverändert
als schneller PR-Gate). Dieses Dokument ist die Planung; die eigentliche Umsetzung ist erst
möglich, nachdem der Owner die in Abschnitt 2 genannten Voraussetzungen persönlich erledigt hat
(Keystore erzeugen, Secrets hinterlegen) - das kann kein Agent für ihn tun.

---

## 1. Rahmenbedingungen (bereits entschieden, nicht neu zu diskutieren)

- **Kein Play Store.** Plan Abschnitt 0.1: "Vertriebsweg vertagt. Bis auf Weiteres interne
  Verteilung (Sideload)." `applicationId = "com.example.lrmprotokoll"` ist ohnehin im Play Store
  unzulässig (README "Bekannte Einschränkungen", B-6) und wird bewusst nicht mehr geändert. Diese
  Pipeline baut deshalb **signierte APKs für Sideload**, kein `.aab`, kein Play-Console-Upload,
  kein Fastlane.
- **Ein Nutzer, ein Gerät.** Kein Staged Rollout, keine Beta-Schiene, kein A/B - ein Release ist
  eine Datei, die der Owner selbst herunterlädt und installiert.

## 2. Owner-Voraussetzungen — MUSS zuerst erledigt werden, nicht Teil der Umsetzung

Ein Agent darf und kann einen Signierschlüssel nicht selbst erzeugen oder verwahren - das ist ein
dauerhaftes Geheimnis, dessen Verlust oder Kompromittierung nicht rückgängig zu machen ist (ein
Sideload-APK mit gewechseltem Schlüssel lässt sich auf dem Gerät nur noch per Deinstallation +
Neuinstallation aktualisieren, mit Datenverlust wie bei jeder Neuinstallation).

1. **Keystore lokal erzeugen** (einmalig, Owner-Rechner, NICHT in einer Agent-Session):
   ```bash
   keytool -genkeypair -v -keystore laermprotokoll-release.jks \
     -alias laermprotokoll -keyalg RSA -keysize 2048 -validity 10000
   ```
   Passwort für Keystore und Key-Alias notieren (Passwortmanager) - ohne sie ist der Schlüssel
   nutzlos. Die `.jks`-Datei selbst gehört **niemals** ins Repository (auch nicht verschlüsselt) -
   nur als GitHub Secret.
2. **Vier GitHub Secrets anlegen** (Repository → Settings → Secrets and variables → Actions):
   - `RELEASE_KEYSTORE_BASE64` — `base64 -w0 laermprotokoll-release.jks` (der komplette
     Base64-String)
   - `RELEASE_KEYSTORE_PASSWORD`
   - `RELEASE_KEY_ALIAS`
   - `RELEASE_KEY_PASSWORD`
3. **Die `.jks`-Datei selbst sicher aufbewahren** (Owner, außerhalb von GitHub) - GitHub Secrets
   sind schreibgeschützt für Workflows abrufbar, aber nicht zum Wiederherunterladen gedacht; geht
   die lokale Kopie verloren, ist auch das GitHub-Secret praktisch nicht mehr auslesbar.

Ohne diese drei Schritte kann die Pipeline aus Abschnitt 4 nicht laufen - `assembleRelease` würde
ohne `signingConfig` ein unsigniertes APK erzeugen (aktueller Zustand, siehe Abschnitt 3), das sich
auf keinem Gerät ohne zusätzlichen manuellen Signierschritt installieren lässt.

## 3. Ausgangslage

- `app/build.gradle.kts`: `buildTypes { release { isMinifyEnabled = false, proguardFiles(...) } }`
  — **kein `signingConfig`** zugewiesen. `./gradlew assembleRelease` baut heute ein unsigniertes
  APK.
- `versionCode = 1`, `versionName = "1.0"` fest im `defaultConfig` verdrahtet, seit Projektbeginn
  nie erhöht.
- Keine Git-Tags im Repository, kein `CHANGELOG.md`.
- `.github/workflows/androidci.yml` baut bei jedem PR/Push nach `main` `assembleDebug` + `test`,
  lädt das Debug-APK 14 Tage lang als CI-Artefakt hoch (`docs/TESTEN_EINES_PR.md`) - das ist der
  bestehende, bewusst einfache Weg, eine PR-Fassung auszuprobieren. Die hier geplante Pipeline
  ersetzt das nicht, sondern ergänzt einen **versionierten, signierten** Release-Weg für den
  tatsächlichen Produktivbetrieb auf dem Owner-Gerät.
- **`docs/PROMPT_M8.md` Aufgabe 1** plant bereits, `isMinifyEnabled = true` zu setzen (R8/Minify,
  noch nicht umgesetzt) - siehe Abschnitt 6 zur Reihenfolge.

## 4. Versionsschema

Kein bestehendes Schema im Plan festgelegt - hier vorgeschlagen, nicht mehr offen für Rückfrage
vor der Umsetzung, aber änderbar, falls der Owner etwas anderes will:

- **Git-Tag `vX.Y.Z`** (z. B. `v1.2.0`) auf `main` löst den Release aus.
- `versionName` = `X.Y.Z` (Tag ohne führendes `v`).
- `versionCode` = `X * 10_000 + Y * 100 + Z` (z. B. `v1.2.3` → `10203`) - deterministisch aus dem
  Tag ableitbar, kein zusätzlicher Zähler/State nötig, monoton solange `X.Y.Z` monoton steigt.
- Semantik locker angelehnt an SemVer, ohne es formal durchzusetzen: `X` für Meilenstein-Sprünge
  (z. B. "M2 fertig" → `v2.0.0`), `Y` für neue Funktionalität, `Z` für reine Fixes - der Owner tggt
  wann und wie er will, es gibt keine Automatik, die das erzwingt.

## 5. Pipeline-Entwurf (`.github/workflows/release.yml`, neue Datei)

```yaml
name: Release

on:
  push:
    tags: ["v*.*.*"]

permissions:
  contents: write   # fuer das Erstellen des GitHub Release

jobs:
  release:
    runs-on: ubuntu-latest
    timeout-minutes: 30
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 21

      - name: Set up Android SDK
        uses: android-actions/setup-android@v3
        with:
          packages: platforms;android-36 build-tools;36.0.0 platform-tools

      - name: Set up Gradle (with cache)
        uses: gradle/actions/setup-gradle@v4

      - name: Make gradlew executable
        run: chmod +x gradlew

      # Kein Release auf rotem Grund - dieselbe Suite wie androidci.yml, hier nochmal, weil
      # release.yml unabhaengig von der PR-Historie auf einem Tag laufen kann (Tag != zwingend
      # bereits gruen getesteter main-Stand, falls jemand von einem aelteren Commit aus taggt).
      - name: Unit tests
        run: ./gradlew test --no-daemon --stacktrace

      - name: Versionsnummern aus dem Tag ableiten
        id: version
        run: |
          TAG="${GITHUB_REF_NAME#v}"
          IFS='.' read -r MAJOR MINOR PATCH <<< "$TAG"
          CODE=$((MAJOR * 10000 + MINOR * 100 + PATCH))
          echo "name=$TAG" >> "$GITHUB_OUTPUT"
          echo "code=$CODE" >> "$GITHUB_OUTPUT"

      - name: Keystore aus Secret wiederherstellen
        run: echo "${{ secrets.RELEASE_KEYSTORE_BASE64 }}" | base64 -d > release.jks

      - name: Signierten Release-Build bauen
        env:
          RELEASE_KEYSTORE_PASSWORD: ${{ secrets.RELEASE_KEYSTORE_PASSWORD }}
          RELEASE_KEY_ALIAS: ${{ secrets.RELEASE_KEY_ALIAS }}
          RELEASE_KEY_PASSWORD: ${{ secrets.RELEASE_KEY_PASSWORD }}
        run: |
          ./gradlew assembleRelease --no-daemon --stacktrace \
            -PversionName=${{ steps.version.outputs.name }} \
            -PversionCode=${{ steps.version.outputs.code }} \
            -PreleaseStoreFile=$(pwd)/release.jks

      - name: Keystore-Kopie löschen
        if: always()
        run: rm -f release.jks

      - name: GitHub Release mit signiertem APK erstellen
        uses: softprops/action-gh-release@v2
        with:
          generate_release_notes: true
          files: |
            app/build/outputs/apk/release/app-release.apk
            app/build/outputs/mapping/release/mapping.txt
```

Ergänzend in `app/build.gradle.kts`:

```kotlin
val releaseStoreFile = (findProperty("releaseStoreFile") as String?)?.let { file(it) }

android {
    defaultConfig {
        versionName = (findProperty("versionName") as String?) ?: "1.0"
        versionCode = (findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
    }
    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            if (releaseStoreFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            // isMinifyEnabled etc. siehe PROMPT_M8.md Aufgabe 1
        }
    }
}
```

`findProperty(...)` liefert `null`, wenn `-PversionName=...`/`-PreleaseStoreFile=...` nicht gesetzt
sind - ein lokaler `./gradlew assembleRelease` ohne diese Properties baut weiterhin ein
unsigniertes APK mit `versionName = "1.0"`, genau wie heute. Das lokale Entwickeln bleibt
unverändert, nur der CI-Release-Lauf reicht die echten Werte durch.

## 6. Reihenfolge gegenüber `docs/PROMPT_M8.md`

M8 Aufgabe 1 (R8/Minify aktivieren) sollte **vor oder zusammen mit** dieser Pipeline erledigt
werden, nicht danach - ein Release-Build ohne Minifizierung zu signieren und zu verteilen, nur um
kurz darauf die R8-Konfiguration nachzuziehen, hieße zwei separate, in der Zusammensetzung nie
gemeinsam geprüfte Release-Konfigurationen. Empfehlung: M8 Aufgabe 1 zuerst (hardwarefrei,
eigenständig prüfbar über `./gradlew assembleRelease` + R8-Log), erst danach den ersten echten Tag
setzen.

## 7. Was diese Pipeline NICHT umfasst (bewusst)

- **Kein automatisches Taggen/Versionsbumping.** Der Owner entscheidet manuell, wann ein Release
  entsteht (`git tag vX.Y.Z && git push origin vX.Y.Z`) - keine Automatik, die bei jedem Merge
  nach `main` einen Release erzeugt. Das passt zum Ein-Nutzer-Charakter der App: ein Release ist
  ein bewusster Schritt, kein Nebenprodukt jedes Merges.
- **Kein Crash-Reporting-Anschluss.** `mapping.txt` wird als Release-Asset mitgeladen (für eine
  spätere manuelle Deobfuskierung, falls der Owner einmal einen Stacktrace hat), aber es gibt
  aktuell keinen automatischen Absturzmelder (kein Firebase Crashlytics o. ä.) - das wäre ein
  eigener, größerer Auftrag mit eigener Datenschutzabwägung, hier nicht mitentschieden.
- **Kein `CHANGELOG.md`-Zwang.** `generate_release_notes: true` lässt GitHub die Release Notes aus
  den Commits/PR-Titeln seit dem letzten Tag automatisch zusammenstellen - reicht für einen
  einzelnen Nutzer, ein von Hand gepflegtes Changelog wäre zusätzlicher Aufwand ohne klaren Nutzen
  hier.
- **Keine Instrumentierten Tests als Release-Gate.** `connectedAndroidTest` existiert noch nicht
  (siehe `docs/TESTPLAN_INSTRUMENTIERT.md`, separates Vorhaben) - sobald es das tut, gehört ein
  grüner Emulator-Lauf als zusätzliche Voraussetzung in `release.yml`, aber das ist bewusst nicht
  Teil dieses Plans.

## 8. Definition of Done für die spätere Umsetzungs-Session

1. Owner hat Abschnitt 2 vollständig erledigt (Keystore + vier Secrets) - **Voraussetzung, nicht
   Teil der Session selbst.**
2. `app/build.gradle.kts` um die `versionName`/`versionCode`/`signingConfig`-Property-Weiche
   ergänzt (Abschnitt 5) - lokales `./gradlew assembleDebug`/`assembleRelease` weiterhin ohne
   Properties lauffähig, unverändertes Verhalten.
3. `.github/workflows/release.yml` neu angelegt, orientiert an Abschnitt 5.
4. Test-Tag (z. B. `v0.0.1-test`) auf einem Fork oder testweise gepusht, Pipeline-Lauf verifiziert
   (signiertes APK im Release-Anhang, `versionName`/`versionCode` im APK korrekt via
   `aapt dump badging` geprüft) - Ausgabe im PR zeigen, nicht nur behaupten. Test-Tag danach wieder
   löschen (`git push --delete origin v0.0.1-test`), damit er nicht als echter Release
   missverstanden wird.
5. README um einen kurzen "Release bauen"-Abschnitt ergänzt (wie taggen, wo das APK landet).
6. Draft-PR gegen `main` mit: was geändert, was verifiziert, was offen (z. B. R8-Reihenfolge aus
   Abschnitt 6, falls M8 Aufgabe 1 zu dem Zeitpunkt noch nicht erledigt ist).
