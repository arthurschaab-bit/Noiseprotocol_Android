#!/bin/bash
set -euo pipefail

# Only needed in Claude Code on the web / cloud sandboxes - a local dev machine
# already has Android Studio's SDK and a real local.properties.
if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

SDK_ROOT="/opt/android-sdk"
PLATFORM="android-36"
BUILD_TOOLS="36.0.0"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip"

sdk_is_complete() {
  [ -d "$SDK_ROOT/platforms/$PLATFORM" ] \
    && [ -d "$SDK_ROOT/build-tools/$BUILD_TOOLS" ] \
    && [ -x "$SDK_ROOT/platform-tools/adb" ]
}

if ! sdk_is_complete; then
  echo "Android SDK not found/incomplete at $SDK_ROOT - installing..."
  mkdir -p "$SDK_ROOT/cmdline-tools"

  if [ ! -x "$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" ]; then
    TMP_ZIP="$(mktemp)"
    curl -sSL -o "$TMP_ZIP" "$CMDLINE_TOOLS_URL"
    rm -rf "$SDK_ROOT/cmdline-tools/latest" "$SDK_ROOT/cmdline-tools/tmp-extract"
    unzip -q "$TMP_ZIP" -d "$SDK_ROOT/cmdline-tools/tmp-extract"
    mv "$SDK_ROOT/cmdline-tools/tmp-extract/cmdline-tools" "$SDK_ROOT/cmdline-tools/latest"
    rmdir "$SDK_ROOT/cmdline-tools/tmp-extract" 2>/dev/null || true
    rm -f "$TMP_ZIP"
  fi

  SDKMANAGER="$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
  yes | "$SDKMANAGER" --sdk_root="$SDK_ROOT" --licenses > /dev/null 2>&1 || true
  "$SDKMANAGER" --sdk_root="$SDK_ROOT" \
    "platform-tools" "platforms;$PLATFORM" "build-tools;$BUILD_TOOLS" > /dev/null

  echo "Android SDK installed at $SDK_ROOT"
else
  echo "Android SDK already present at $SDK_ROOT - skipping install"
fi

# Point Gradle at the SDK. local.properties is git-ignored, so this is safe to
# (re)write on every session start regardless of what's checked out.
echo "sdk.dir=$SDK_ROOT" > "$CLAUDE_PROJECT_DIR/local.properties"

# Make ANDROID_HOME/ANDROID_SDK_ROOT available for the rest of the session
# (e.g. for tools that read the env var instead of local.properties).
{
  echo "export ANDROID_HOME=\"$SDK_ROOT\""
  echo "export ANDROID_SDK_ROOT=\"$SDK_ROOT\""
} >> "$CLAUDE_ENV_FILE"
