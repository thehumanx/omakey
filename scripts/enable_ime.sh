#!/usr/bin/env bash
# Enables and switches to omakey on a connected device/emulator without navigating
# system settings by hand. Requires an already-installed debug build.
set -euo pipefail

IME_ID="dev.omakey.app/.keyboard.OmakeyInputMethodService"

adb shell ime enable "$IME_ID"
adb shell ime set "$IME_ID"
adb shell ime list -s
