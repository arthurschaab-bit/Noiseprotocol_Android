#!/usr/bin/env bash
# CI-Fund 10.09.2026 (PR #132): reactivecircus/android-emulator-runner fuehrt jede Zeile des
# `script:`-Inputs als EIGENEN `sh -c`-Aufruf aus, nicht als ein zusammenhaengendes Skript -
# Zeilenfortsetzung per `\` und mehrzeilige Konstrukte (for/if/Arrays) brechen deshalb dort.
# Diese Datei laeuft als EIN einziger `bash`-Aufruf (eine Zeile im Workflow), genau deshalb als
# eigene Skriptdatei statt inline im Workflow.
#
# $1: API-Level des Emulators (fuer den BLUETOOTH_SCAN/CONNECT-Fall, siehe unten).
set -uo pipefail

api_level="$1"
APP_ID="com.example.lrmprotokoll"

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
    adb devices -l
    echo
    echo "== Build und Locale =="
    adb shell getprop ro.build.fingerprint
    adb shell getprop ro.build.version.release
    adb shell getprop persist.sys.locale
    adb shell getprop ro.product.locale
    adb shell settings get system system_locales
    echo
    echo "== Display und Orientierung =="
    adb shell wm size
    adb shell wm density
    adb shell dumpsys input | grep -E "SurfaceOrientation|SurfaceOrientation|orientation" || true
    echo
    echo "== Animationen =="
    adb shell settings get global window_animation_scale
    adb shell settings get global transition_animation_scale
    adb shell settings get global animator_duration_scale
  } > logs/emulator-info.txt 2>&1 || true

  timeout 10s adb logcat -b all -d > logs/adb-logcat.txt 2>&1 || true
  timeout 10s adb shell dumpsys activity > logs/dumpsys-activity.txt 2>&1 || true
  timeout 10s adb shell dumpsys window > logs/dumpsys-window.txt 2>&1 || true
  timeout 10s adb shell dumpsys gfxinfo "$APP_ID" > logs/gfxinfo.txt 2>&1 || true
  timeout 10s adb shell dumpsys meminfo "$APP_ID" > logs/meminfo.txt 2>&1 || true
  timeout 10s adb shell dumpsys package "$APP_ID" > logs/package-info.txt 2>&1 || true

  # Screenshot und Hierarchie ergänzen die Logs um den sichtbaren bzw. für Android
  # zugänglichen Zustand unmittelbar am Fehlerzeitpunkt.
  timeout 10s adb exec-out screencap -p > logs/screenshot.png 2> logs/screenshot-stderr.txt || true
  adb shell uiautomator dump /sdcard/ci-ui-hierarchy.xml >/dev/null 2>&1 &&
    adb pull /sdcard/ci-ui-hierarchy.xml logs/ui-hierarchy.xml >/dev/null 2>&1 || true
}

# Die vier "ohneBerechtigung..."-Tests unten laufen HIER NICHT mit: AGP gewaehrt beim Install
# alle im Manifest deklarierten Laufzeitberechtigungen bereits (`pm install -g`), ein Revoke
# waehrend dieses Laufs wuerde den instrumentierten Prozess toeten und den gesamten restlichen
# Testlauf mitreissen. Sie laufen stattdessen weiter unten einzeln per `adb shell am instrument`,
# jeweils nach einem `pm revoke` VOR dem Prozessstart.
notclass="com.example.lrmprotokoll.ui.FotoDokumentationSheetPermissionInstrumentedTest#ohneBerechtigungFragtDerAufnahmeButtonErstNachUndStartetDannDenKameraIntent,com.example.lrmprotokoll.ui.VideoAufnahmeScreenPermissionInstrumentedTest#ohneBerechtigungFragtDerScreenBeimBetretenNachUndSchaltetNachErlaubnisWeiter,com.example.lrmprotokoll.ui.GesamtberichtStammdatenSheetPermissionInstrumentedTest#ohneBerechtigungFragtStandortErmittelnErstNachUndHaengtNichtEndlosImLadezustand,com.example.lrmprotokoll.ui.MeterScreenPermissionInstrumentedTest#ohneBerechtigungFragtDerScanButtonErstNachUndBesitztDanachDieBerechtigung"

./gradlew connectedDebugAndroidTest --no-daemon --stacktrace -Pandroid.testInstrumentationRunnerArguments.notClass="$notclass"
if [ $? -ne 0 ]; then
  sichere_emulator_diagnosen
  cp logs/adb-logcat.txt logcat-failure.txt 2>/dev/null || true
  exit 1
fi

# CI-Fund 10.09.2026 (2. Iteration, PR #132): connectedDebugAndroidTest deinstalliert App- und
# Test-APK am Ende des Laufs (Unified Test Platform, AGP 9) - `pm list instrumentation` faende
# danach nichts mehr. Fuer den Rest hier braucht es beide APKs wieder installiert; installDebug/
# installDebugAndroidTest sind reine `adb install`-Wrapper ohne die UTP-Deinstallation.
./gradlew installDebug installDebugAndroidTest --no-daemon --stacktrace
if [ $? -ne 0 ]; then
  sichere_emulator_diagnosen
  cp logs/adb-logcat.txt logcat-failure.txt 2>/dev/null || true
  exit 1
fi

RUNNER=$(adb shell pm list instrumentation | grep "target=$APP_ID" | sed -E 's/instrumentation:([^ ]+) .*/\1/' | tr -d '\r')
if [ -z "$RUNNER" ]; then
  echo "::error::Keine Instrumentation fuer $APP_ID gefunden (pm list instrumentation leer) - App-/Test-APK nicht installiert?"
  sichere_emulator_diagnosen
  cp logs/adb-logcat.txt logcat-failure.txt 2>/dev/null || true
  exit 1
fi
echo "Instrumentation-Komponente: $RUNNER"

# Nach der Neuinstallation braucht der System-PermissionController Zeit, um das neu installierte
# Paket und dessen Berechtigungsgruppen vollstaendig im Cache zu erfassen ("Updating user sensitive").
# Ein kurzer Reset und 2 Sekunden Wartezeit verhindern "Group <pkg> <group> invalid" beim ersten Test.
adb shell am force-stop com.android.permissioncontroller || true
adb shell am force-stop com.google.android.permissioncontroller || true
sleep 2

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
  local versuch status_ausschnitt
  for versuch in 1 2; do
    adb shell pm revoke "$APP_ID" "$berechtigung" || true
    status_ausschnitt=$(adb shell dumpsys package "$APP_ID" | grep -A1 "$berechtigung:" | tr -d '\r')
    echo "pm revoke $berechtigung (Versuch $versuch) - dumpsys-Ausschnitt: $status_ausschnitt"
    if echo "$status_ausschnitt" | grep -q "granted=false"; then
      return 0
    fi
    sleep 1
  done
  echo "::warning::$berechtigung liess sich laut dumpsys nicht als granted=false bestaetigen - Test laeuft trotzdem weiter, siehe Diagnose-Ausgabe oben."
}

fehlgeschlagen=0
for eintrag in "${faelle[@]}"; do
  klasse_methode="${eintrag%%|*}"
  berechtigungen="${eintrag##*|}"
  echo "::group::$klasse_methode (revoke: $berechtigungen)"

  # Kein laufender Prozess = kein Kill-Risiko beim Revoke (siehe Kommentar oben).
  adb shell am force-stop "$APP_ID" || true
  adb shell am force-stop com.android.permissioncontroller || true
  adb shell am force-stop com.google.android.permissioncontroller || true
  IFS=',' read -ra perms <<< "$berechtigungen"
  for p in "${perms[@]}"; do
    revoke_und_pruefe "$p"
  done

  ausgabe=$(adb shell am instrument -w -e class "$klasse_methode" "$RUNNER" 2>&1)
  echo "$ausgabe"

  for p in "${perms[@]}"; do
    adb shell pm grant "$APP_ID" "$p" || true
  done

  if ! echo "$ausgabe" | grep -q "OK (1 test)"; then
    echo "::error::$klasse_methode ist NICHT mit 'OK (1 test)' durchgelaufen (siehe Ausgabe oben)."
    fehlgeschlagen=1
  fi
  echo "::endgroup::"
done

if [ "$fehlgeschlagen" -ne 0 ]; then
  sichere_emulator_diagnosen
  cp logs/adb-logcat.txt logcat-failure.txt 2>/dev/null || true
  exit 1
fi
