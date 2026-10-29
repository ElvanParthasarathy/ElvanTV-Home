#!/usr/bin/env bash
# Restore script for Xiaomi Mi TV 4A / 4C Pro (MiTV-AXSO0, dangal)
# Re-enables all 38 disabled packages and restores default system settings.

set -euo pipefail

TV_HOST="${1:-${TV_HOST:-}}"
if [ -z "$TV_HOST" ]; then
    read -r -p "Enter TV IP address and port (e.g. 192.168.1.50:5555): " TV_HOST
fi

ADB="adb"
if ! command -v adb >/dev/null 2>&1; then
    if [ -n "${LOCALAPPDATA:-}" ] && [ -x "${LOCALAPPDATA}/Android/Sdk/platform-tools/adb.exe" ]; then
        ADB="${LOCALAPPDATA}/Android/Sdk/platform-tools/adb.exe"
    else
        echo "Error: adb command not found in PATH" >&2
        exit 1
    fi
fi

echo "Connecting to $TV_HOST..."
"$ADB" connect "$TV_HOST"

DISABLED_PACKAGES=(
    "com.xiaomi.android.tvsetup.partnercustomizer"
    "com.android.providers.calendar"
    "com.android.wallpapercropper"
    "com.mitv.tvlock"
    "android.autoinstalls.config.xioami.dangal"
    "com.xiaomi.floatingframe"
    "com.google.android.backdrop"
    "com.xm.webcontent"
    "com.google.android.marvin.talkback"
    "com.android.camera2"
    "com.google.android.speech.pumpkin"
    "com.mitv.tvhome.atv"
    "com.android.printspooler"
    "com.xiaomi.mitv.updateservice"
    "com.google.android.tvrecommendations"
    "com.google.android.leanbacklauncher.recommendations"
    "com.xiaomi.mimusic2"
    "com.google.android.tvlauncher"
    "com.google.android.youtube.tvmusic"
    "com.android.gallery3d"
    "com.google.android.tts"
    "com.google.android.videos"
    "com.xiaomo.tv.milegal"
    "com.mitv.tvhome.mitvplus"
    "com.google.android.feedback"
    "com.google.android.syncadapters.calendar"
    "com.google.android.tv.bugreportsender"
    "com.android.smspush"
    "com.miui.tv.analytics"
    "com.google.android.leanbacklauncher"
    "com.xiaomi.statistic"
    "com.android.wallpaperbackup"
    "com.mitv.milinkservice"
    "com.mitv.dream"
    "com.google.android.play.games"
    "com.xiaomi.mitv.handbook"
    "com.android.providers.contacts"
    "com.xiaomi.mitv.tvmanager"
)

echo "Re-enabling all 38 packages on $TV_HOST..."
for pkg in "${DISABLED_PACKAGES[@]}"; do
    echo "Enabling $pkg..."
    "$ADB" -s "$TV_HOST" shell "pm enable $pkg" || true
done

echo "Restoring default UI animation scales and activity manager limits..."
"$ADB" -s "$TV_HOST" shell "settings put global window_animation_scale 1.0"
"$ADB" -s "$TV_HOST" shell "settings put global transition_animation_scale 1.0"
"$ADB" -s "$TV_HOST" shell "settings put global animator_duration_scale 1.0"
"$ADB" -s "$TV_HOST" shell "settings delete global activity_manager_constants"

echo "Restoration complete! All 38 packages re-enabled and settings restored."
