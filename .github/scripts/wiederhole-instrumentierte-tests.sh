#!/usr/bin/env bash
# Jede Iteration startet die Instrumentation in einem neuen App-Prozess. Wird spaeter
# clearPackageData aktiviert, vor jeder Iteration zusaetzlich pm clear "$APP_ID" aufrufen.
set -euo pipefail

APP_ID=com.example.lrmprotokoll
tests=${TESTS:-}
wiederholungen=${WIEDERHOLUNGEN:-20}
api_level=${API_LEVEL:-34}

fehler() {
  echo "::error::$*" >&2
  exit 2
}

[[ $wiederholungen =~ ^[0-9]+$ ]] && (( wiederholungen >= 1 && wiederholungen <= 100 )) ||
  fehler "wiederholungen muss eine ganze Zahl von 1 bis 100 sein (erhalten: $wiederholungen)."
[[ $api_level =~ ^[0-9]+$ ]] && (( api_level >= 29 && api_level <= 36 )) ||
  fehler "api_level muss ein unterstuetztes API-Level von 29 bis 36 sein (erhalten: $api_level)."
[[ -n ${tests//[[:space:]]/} ]] || fehler "tests darf nicht leer sein."
IFS=',' read -ra auswahl <<< "$tests"
(( ${#auswahl[@]} > 0 )) || fehler "tests darf nicht leer sein."
klassen=()
for raw in "${auswahl[@]}"; do
  testname=${raw//[[:space:]]/}
  [[ $testname =~ ^([A-Za-z_][A-Za-z_0-9]*\.)*[A-Za-z_][A-Za-z_0-9]*(#[A-Za-z_][A-Za-z_0-9]*)?$ ]] ||
    fehler "Ungueltiger Testname: $raw (erwartet Klasse oder Klasse#Methode)."
  if [[ $testname != "$APP_ID".* ]]; then
    testname="$APP_ID.$testname"
  fi
  klasse=${testname%%#*}
  quellpfad="app/src/androidTest/java/${klasse//./\/}"
  [[ -f "$quellpfad.kt" || -f "$quellpfad.java" ]] ||
    fehler "Testklasse $klasse nicht gefunden (erwartet $quellpfad.kt oder .java)."
  klassen+=("$testname")
done
[[ ${tests: -1} != ',' ]] || fehler "Leerer Testname nach dem letzten Komma."

[[ ${1:-} != --validate ]] || exit 0

./gradlew installDebug installDebugAndroidTest --no-daemon --stacktrace
runner=$(adb shell pm list instrumentation | grep "target=$APP_ID" | sed -E 's/instrumentation:([^ ]+) .*/\1/' | tr -d '\r' | head -n 1)
[[ -n $runner ]] || fehler "Keine Instrumentation fuer $APP_ID gefunden; App-/Test-APK nicht installiert?"
echo "Instrumentation-Komponente: $runner"

mkdir -p diagnose
fehlgeschlagen=0
{
  echo '## Emulator Flake Diagnose'
  echo
  echo "API $api_level, $wiederholungen Wiederholungen je Auswahl, Commit \`$GITHUB_SHA\`."
  echo
  echo '| Test | Bestanden | Fehlgeschlagen | Fehlerquote | Mittlere Dauer | Maximale Dauer | Fehlgeschlagene Iterationen |'
  echo '|---|---:|---:|---:|---:|---:|---|'
} >> "${GITHUB_STEP_SUMMARY:-/dev/stdout}"

for testname in "${klassen[@]}"; do
  bestanden=0
  fehlerzahl=0
  summe=0
  maximum=0
  iterationen=()
  dauern=()
  resultate=()
  for ((i=1; i<=wiederholungen; i++)); do
    echo "::group::$testname Iteration $i/$wiederholungen"
    adb shell am force-stop "$APP_ID"
    adb logcat -c
    start=$(date +%s)
    ausgabe=$(adb shell am instrument -w -e class "$testname" "$runner" 2>&1) || true
    sekunden=$(( $(date +%s) - start ))
    echo "$ausgabe"
    echo "Dauer: ${sekunden}s"
    summe=$((summe + sekunden))
    dauern+=("$sekunden")
    (( sekunden > maximum )) && maximum=$sekunden
    if [[ $ausgabe =~ OK\ \(([1-9][0-9]*)\ tests?\) ]]; then
      bestanden=$((bestanden + 1))
      resultate+=("bestanden")
    else
      fehlerzahl=$((fehlerzahl + 1))
      fehlgeschlagen=1
      iterationen+=("$i")
      resultate+=("fehlgeschlagen")
      ziel="diagnose/${testname//\#/_}/$i"
      mkdir -p "$ziel"
      printf '%s\n' "$ausgabe" > "$ziel/instrumentation.txt"
      timeout 10s adb logcat -d > "$ziel/logcat.txt" 2>&1 || true
      timeout 10s adb exec-out screencap -p > "$ziel/screenshot.png" 2> "$ziel/screenshot-stderr.txt" || true
      adb shell uiautomator dump /sdcard/ci-ui-hierarchy.xml >/dev/null 2>&1 &&
        adb pull /sdcard/ci-ui-hierarchy.xml "$ziel/ui-hierarchy.xml" >/dev/null 2>&1 || true
      echo "::error::$testname Iteration $i: kein positiver Testabschluss (OK mit mindestens 1 Test)."
    fi
    echo '::endgroup::'
  done
  mittel=$((summe / wiederholungen))
  quote=$(awk -v f="$fehlerzahl" -v n="$wiederholungen" 'BEGIN {printf "%.1f%%", 100*f/n}')
  liste=$(IFS=', '; echo "${iterationen[*]:-–}")
  echo "| \`$testname\` | $bestanden | $fehlerzahl | $quote | ${mittel}s | ${maximum}s | $liste |" >> "${GITHUB_STEP_SUMMARY:-/dev/stdout}"
  {
    echo
    echo "<details><summary>Alle $wiederholungen Iterationen: \`$testname\`</summary>"
    echo
    echo '| Iteration | Ergebnis | Dauer |'
    echo '|---:|---|---:|'
    for ((j=0; j<wiederholungen; j++)); do
      echo "| $((j + 1)) | ${resultate[j]} | ${dauern[j]}s |"
    done
    echo
    echo '</details>'
  } >> "${GITHUB_STEP_SUMMARY:-/dev/stdout}"
done

exit "$fehlgeschlagen"
