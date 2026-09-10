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

# Die vier "ohneBerechtigung..."-Tests unten laufen HIER NICHT mit: AGP gewaehrt beim Install
# alle im Manifest deklarierten Laufzeitberechtigungen bereits (`pm install -g`), ein Revoke
# waehrend dieses Laufs wuerde den instrumentierten Prozess toeten und den gesamten restlichen
# Testlauf mitreissen. Sie laufen stattdessen weiter unten einzeln per `adb shell am instrument`,
# jeweils nach einem `pm revoke` VOR dem Prozessstart.
notclass="com.example.lrmprotokoll.ui.FotoDokumentationSheetPermissionInstrumentedTest#ohneBerechtigungFragtDerAufnahmeButtonErstNachUndStartetDannDenKameraIntent,com.example.lrmprotokoll.ui.VideoAufnahmeScreenPermissionInstrumentedTest#ohneBerechtigungFragtDerScreenBeimBetretenNachUndSchaltetNachErlaubnisWeiter,com.example.lrmprotokoll.ui.GesamtberichtStammdatenSheetPermissionInstrumentedTest#ohneBerechtigungFragtStandortErmittelnErstNachUndHaengtNichtEndlosImLadezustand,com.example.lrmprotokoll.ui.MeterScreenPermissionInstrumentedTest#ohneBerechtigungFragtDerScanButtonErstNachUndBesitztDanachDieBerechtigung"

./gradlew connectedDebugAndroidTest --no-daemon --stacktrace -Pandroid.testInstrumentationRunnerArguments.notClass="$notclass"
if [ $? -ne 0 ]; then
  timeout 10s adb logcat -d > logcat-failure.txt
  exit 1
fi

# CI-Fund 10.09.2026 (2. Iteration, PR #132): connectedDebugAndroidTest deinstalliert App- und
# Test-APK am Ende des Laufs (Unified Test Platform, AGP 9) - `pm list instrumentation` faende
# danach nichts mehr. Fuer den Rest hier braucht es beide APKs wieder installiert; installDebug/
# installDebugAndroidTest sind reine `adb install`-Wrapper ohne die UTP-Deinstallation.
./gradlew installDebug installDebugAndroidTest --no-daemon --stacktrace
if [ $? -ne 0 ]; then
  timeout 10s adb logcat -d > logcat-failure.txt
  exit 1
fi

APP_ID="com.example.lrmprotokoll"
RUNNER=$(adb shell pm list instrumentation | grep "target=$APP_ID" | sed -E 's/instrumentation:([^ ]+) .*/\1/' | tr -d '\r')
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

fehlgeschlagen=0
for eintrag in "${faelle[@]}"; do
  klasse_methode="${eintrag%%|*}"
  berechtigungen="${eintrag##*|}"
  echo "::group::$klasse_methode (revoke: $berechtigungen)"

  # Kein laufender Prozess = kein Kill-Risiko beim Revoke (siehe Kommentar oben).
  adb shell am force-stop "$APP_ID" || true
  IFS=',' read -ra perms <<< "$berechtigungen"
  for p in "${perms[@]}"; do
    adb shell pm revoke "$APP_ID" "$p" || true
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
  timeout 10s adb logcat -d > logcat-failure.txt
  exit 1
fi
