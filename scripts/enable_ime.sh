#!/usr/bin/env bash
# Enables and switches to omakey on a connected device/emulator without navigating
# system settings by hand. Requires an already-installed debug build.
set -euo pipefail

# Default: github/debug builds. For the Play build use: APP_ID=com.iambishistha.omakey ./enable_ime.sh
# (the service class stays in the dev.omakey.app namespace, so it must be spelled out in full).
APP_ID="${APP_ID:-dev.omakey.app}"
IME_ID="$APP_ID/dev.omakey.app.keyboard.OmakeyInputMethodService"

adb shell ime enable "$IME_ID"
adb shell ime set "$IME_ID"
adb shell ime list -s
