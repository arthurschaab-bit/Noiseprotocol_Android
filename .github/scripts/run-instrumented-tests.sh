#!/usr/bin/env bash
# CI-Fund 10.09.2026 (PR #132): reactivecircus/android-emulator-runner fuehrt jede Zeile des
# `script:`-Inputs als EIGENEN `sh -c`-Aufruf aus, nicht als ein zusammenhaengendes Skript -
# Zeilenfortsetzung per `\` und mehrzeilige Konstrukte (for/if/Arrays) brechen deshalb dort.
# Diese Datei laeuft als EIN einziger `bash`-Aufruf (eine Zeile im Workflow), genau deshalb als
# eigene Skriptdatei statt inline im Workflow.
#
# $1: API-Level des Emulators (fuer den BLUETOOTH_SCAN/CONNECT-Fall, siehe unten).
set -euo pipefail

api_level="$1"
APP_ID="com.example.lrmprotokoll"

# Fehler bei der Diagnose sind Zusatzinformationen, niemals Ersatz fuer den Teststatus.
diagnose_adb() {
  local status
  if timeout 5s adb "$@"; then
    return 0
  else
    status=$?
    printf '\nADB-Diagnose fehlgeschlagen (Status %s): %s\n' "$status" "$*" >&2
    return 0
  fi
}

# Dieser Block muss im laufenden Emulator-Kontext ausgeführt werden. Der nachgelagerte
# Workflow-Schritt läuft erst nach Ende von android-emulator-runner und kann deshalb keine
# aussagekräftigen ADB-Diagnosen mehr liefern.
sichere_emulator_diagnosen() {
  mkdir -p logs

  # Eine kleine, selbstbeschreibende Momentaufnahme: Locale, Display und
  # Animationseinstellungen sind häufig entscheidend für UI-Test-Unterschiede.
  {
    echo "Zeitpunkt UTC: $(date --iso-8601=seconds)"
    echo
    echo "== adb devices -l =="
    diagnose_adb devices -l
    echo
    echo "== Build und Locale =="
    diagnose_adb shell getprop ro.build.fingerprint
    diagnose_adb shell getprop ro.build.version.release
    diagnose_adb shell getprop persist.sys.locale
    diagnose_adb shell getprop ro.product.locale
    diagnose_adb shell settings get system system_locales
    echo
    echo "== Display und Orientierung =="
    diagnose_adb shell wm size
    diagnose_adb shell wm density
    diagnose_adb shell dumpsys input
    echo
    echo "== Animationen =="
    diagnose_adb shell settings get global window_animation_scale
    diagnose_adb shell settings get global transition_animation_scale
    diagnose_adb shell settings get global animator_duration_scale
  } > logs/emulator-info.txt 2>&1

  diagnose_adb logcat -b all -d > logs/adb-logcat.txt 2>&1
  diagnose_adb shell dumpsys activity > logs/dumpsys-activity.txt 2>&1
  diagnose_adb shell dumpsys window > logs/dumpsys-window.txt 2>&1
  diagnose_adb shell dumpsys gfxinfo "$APP_ID" > logs/gfxinfo.txt 2>&1
  diagnose_adb shell dumpsys meminfo "$APP_ID" > logs/meminfo.txt 2>&1
  diagnose_adb shell dumpsys package "$APP_ID" > logs/package-info.txt 2>&1

  # Screenshot und Hierarchie ergänzen die Logs um den sichtbaren bzw. für Android
  # zugänglichen Zustand unmittelbar am Fehlerzeitpunkt.
  diagnose_adb exec-out screencap -p > logs/screenshot.png 2> logs/screenshot-stderr.txt
  if timeout 5s adb shell uiautomator dump /sdcard/ci-ui-hierarchy.xml > logs/ui-hierarchy-stderr.txt 2>&1; then
    diagnose_adb pull /sdcard/ci-ui-hierarchy.xml logs/ui-hierarchy.xml >> logs/ui-hierarchy-stderr.txt 2>&1
  fi
}

beende_lauf() {
  local status=$?
  if [ "${fehlgeschlagen:-0}" -ne 0 ]; then
    status=$fehlgeschlagen
  fi
  trap - EXIT INT TERM
  set +e
  mkdir -p logs
  printf 'Teststatus: %s\nZeitpunkt UTC: %s\n' "$status" "$(date --iso-8601=seconds)" > logs/emulator-status.txt
  if [ "$status" -ne 0 ]; then
    sichere_emulator_diagnosen
  fi
  exit "$status"
}

trap beende_lauf EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# Auch Setup-Fehler muessen den Collector noch VOR dem Emulator-Teardown erreichen.
timeout 60s adb wait-for-device
adb shell input keyevent 82
adb logcat -c

# Die vier "ohneBerechtigung..."-Tests unten laufen HIER NICHT mit: AGP gewaehrt beim Install
# alle im Manifest deklarierten Laufzeitberechtigungen bereits (`pm install -g`), ein Revoke
# waehrend dieses Laufs wuerde den instrumentierten Prozess toeten und den gesamten restlichen
# Testlauf mitreissen. Sie laufen stattdessen weiter unten einzeln per `adb shell am instrument`,
# jeweils nach einem `pm revoke` VOR dem Prozessstart.
notclass="com.example.lrmprotokoll.ui.FotoDokumentationSheetPermissionInstrumentedTest#ohneBerechtigungFragtDerAufnahmeButtonErstNachUndStartetDannDenKameraIntent,com.example.lrmprotokoll.ui.VideoAufnahmeScreenPermissionInstrumentedTest#ohneBerechtigungFragtDerScreenBeimBetretenNachUndSchaltetNachErlaubnisWeiter,com.example.lrmprotokoll.ui.GesamtberichtStammdatenSheetPermissionInstrumentedTest#ohneBerechtigungFragtStandortErmittelnErstNachUndHaengtNichtEndlosImLadezustand,com.example.lrmprotokoll.ui.MeterScreenPermissionInstrumentedTest#ohneBerechtigungFragtDerScanButtonErstNachUndBesitztDanachDieBerechtigung"

python3 .github/scripts/ci-timing.py run "UTP / instrumentierte Tests" -- \
  ./gradlew connectedDebugAndroidTest --stacktrace -Pandroid.testInstrumentationRunnerArguments.notClass="$notclass"

# CI-Fund 10.09.2026 (2. Iteration, PR #132): connectedDebugAndroidTest deinstalliert App- und
# Test-APK am Ende des Laufs (Unified Test Platform, AGP 9) - `pm list instrumentation` faende
# danach nichts mehr. Fuer den Rest hier braucht es beide APKs wieder installiert; installDebug/
# installDebugAndroidTest sind reine `adb install`-Wrapper ohne die UTP-Deinstallation.
python3 .github/scripts/ci-timing.py run "APK-Installation fuer Permission-Tests" -- \
  ./gradlew installDebug installDebugAndroidTest --stacktrace

instrumentierungen=$(adb shell pm list instrumentation)
RUNNER=$(printf '%s\n' "$instrumentierungen" | sed -nE "/target=$APP_ID/s/instrumentation:([^ ]+) .*/\\1/p" | tr -d '\r')
if [ -z "$RUNNER" ]; then
  echo "::error::Keine Instrumentation fuer $APP_ID gefunden (pm list instrumentation leer) - App-/Test-APK nicht installiert?"
  exit 1
fi
echo "Instrumentation-Komponente: $RUNNER"

# Format je Eintrag: "<Klasse>#<Methode>|<Berechtigung1>[,<Berechtigung2>]"
faelle=(
  "com.example.lrmprotokoll.ui.FotoDokumentationSheetPermissionInstrumentedTest#ohneBerechtigungFragtDerAufnahmeButtonErstNachUndStartetDannDenKameraIntent|android.permission.CAMERA"
  "com.example.lrmprotokoll.ui.VideoAufnahmeScreenPermissionInstrumentedTest#ohneBerechtigungFragtDerScreenBeimBetretenNachUndSchaltetNachErlaubnisWeiter|android.permission.CAMERA"
  "com.example.lrmprotokoll.ui.GesamtberichtStammdatenSheetPermissionInstrumentedTest#ohneBerechtigungFragtStandortErmittelnErstNachUndHaengtNichtEndlosImLadezustand|android.permission.ACCESS_COARSE_LOCATION"
)
# BLUETOOTH_SCAN/BLUETOOTH_CONNECT gibt es erst ab API 31 (Legacy-Pfad davor laeuft ueber
# ACCESS_FINE_LOCATION) - der Test selbst ueberspringt sich unterhalb von API 31 per
# assumeTrue(), was am-instrument NICHT als "OK (1 test)" meldet. Deshalb hier bereits
# ausgelassen, statt faelschlich als Fehlschlag gewertet zu werden.
if [ "$api_level" -ge 31 ]; then
  faelle+=("com.example.lrmprotokoll.ui.MeterScreenPermissionInstrumentedTest#ohneBerechtigungFragtDerScanButtonErstNachUndBesitztDanachDieBerechtigung|android.permission.BLUETOOTH_SCAN,android.permission.BLUETOOTH_CONNECT")
fi

# CI-Fund 10.09.2026 (3. Iteration, PR #132): fuer ACCESS_COARSE_LOCATION blieb der
# Berechtigungsdialog beim vorigen Lauf komplett aus (kein GrantPermissionsActivity im Logcat
# im gesamten Testfenster) - der App-Code fragt nur, wenn checkSelfPermission() DENIED liefert,
# also war die Berechtigung zum Startzeitpunkt des Tests offenbar noch (oder wieder) gewaehrt,
# obwohl `pm revoke` vorher lief. Ursache noch nicht sicher geklaert (CAMERA revoked im selben
# Skript zuverlaessig). Deshalb hier: nach jedem `pm revoke` per `dumpsys package` verifizieren,
# bei Bedarf einmal wiederholen (kurze Wartezeit fuer moegliche Propagationsverzoegerung), und
# das Ergebnis so oder so ins Log schreiben - naechster Fehlschlag zeigt dann die echte Ursache,
# statt erneut zu raten.
revoke_und_pruefe() {
  local berechtigung="$1"
  local versuch status_ausschnitt paket_status
  for versuch in 1 2; do
    if ! adb shell pm revoke "$APP_ID" "$berechtigung"; then
      echo "::warning::pm revoke fuer $berechtigung fehlgeschlagen; pruefe tatsaechlichen Berechtigungszustand."
    fi
    paket_status=$(adb shell dumpsys package "$APP_ID")
    status_ausschnitt=$(printf '%s\n' "$paket_status" | sed -n "/$berechtigung:/{N;p;}" | tr -d '\r')
    echo "pm revoke $berechtigung (Versuch $versuch) - dumpsys-Ausschnitt: $status_ausschnitt"
    if echo "$status_ausschnitt" | grep -q "granted=false"; then
      return 0
    fi
    if [ "$versuch" -lt 2 ]; then
      sleep 1
    fi
  done
  echo "::error::$berechtigung liess sich nach zwei Versuchen nicht als granted=false bestaetigen."
  return 1
}

fehlgeschlagen=0
mkdir -p logs/permission-tests app/build/outputs/androidTest-results/permission
for eintrag in "${faelle[@]}"; do
  klasse_methode="${eintrag%%|*}"
  berechtigungen="${eintrag##*|}"
  echo "::group::$klasse_methode (revoke: $berechtigungen)"

  # Kein laufender Prozess = kein Kill-Risiko beim Revoke (siehe Kommentar oben).
  adb shell am force-stop "$APP_ID"
  IFS=',' read -ra perms <<< "$berechtigungen"
  for p in "${perms[@]}"; do
    revoke_und_pruefe "$p"
  done

  fall_start=$SECONDS
  fall_status=0
  ausgabe=$(adb shell am instrument -w -e class "$klasse_methode" "$RUNNER" 2>&1) || fall_status=$?
  printf '%s\n' "$ausgabe"
  klasse="${klasse_methode%%#*}"
  methode="${klasse_methode#*#}"
  ausgabe_pfad="logs/permission-tests/${klasse##*.}.txt"
  printf '%s\n' "$ausgabe" > "$ausgabe_pfad"

  # adb kann trotz JUnit-Fehler Status 0 liefern: beide Signale muessen Erfolg bestaetigen.
  if [ "$fall_status" -eq 0 ] && ! printf '%s\n' "$ausgabe" | tr -d '\r' | grep -Eq '^OK \(1 test\)$'; then
    fall_status=1
  fi
  if [ "$fall_status" -ne 0 ] && [ "$fehlgeschlagen" -eq 0 ]; then
    fehlgeschlagen=$fall_status
  fi
  python3 - "$klasse" "$methode" "$fall_status" "$((SECONDS - fall_start))" "$ausgabe_pfad" <<'PY'
import pathlib
import sys
import xml.etree.ElementTree as ET

klasse, methode, status, dauer, ausgabe_pfad = sys.argv[1:]
ausgabe = pathlib.Path(ausgabe_pfad).read_text(encoding="utf-8", errors="replace")
# XML 1.0 erlaubt keine Steuerzeichen aus Terminalausgaben.
ausgabe = "".join(c for c in ausgabe if c in "\t\n\r" or ord(c) >= 32)
suite = ET.Element("testsuite", name=klasse, tests="1", failures=str(int(status != "0")), errors="0", time=dauer)
test = ET.SubElement(suite, "testcase", classname=klasse, name=methode, time=dauer)
if status != "0":
    ET.SubElement(test, "failure", message=f"Instrumentation fehlgeschlagen (Status {status})").text = ausgabe
ET.SubElement(test, "system-out").text = ausgabe
ET.ElementTree(suite).write(f"app/build/outputs/androidTest-results/permission/TEST-{klasse}.xml", encoding="utf-8", xml_declaration=True)
PY

  for p in "${perms[@]}"; do
    adb shell pm grant "$APP_ID" "$p"
  done

  if [ "$fall_status" -ne 0 ]; then
    echo "::error::$klasse_methode ist NICHT mit 'OK (1 test)' durchgelaufen (siehe Ausgabe oben)."
  fi
  echo "::endgroup::"
done

exit "$fehlgeschlagen"
