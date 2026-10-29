<#
.SYNOPSIS
    Restores Xiaomi Mi TV 4A / 4C Pro (MiTV-AXSO0) to factory package state.
.DESCRIPTION
    Re-enables all 38 disabled packages, restores default 1.0x animation scales,
    and resets the background cached process limit.
.PARAMETER TvHost
    TV IP address and port (e.g. 192.168.1.50:5555).
#>
[CmdletBinding()]
param(
    [string]$TvHost = $env:TV_HOST
)

if (-not $TvHost) {
    $TvHost = Read-Host "Enter TV IP address and port (e.g. 192.168.1.50:5555)"
}

# Find adb binary
$adb = "adb"
if (Get-Command adb -ErrorAction SilentlyContinue) {
    $adb = "adb"
} elseif (Test-Path "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe") {
    $adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
}

Write-Host "Connecting to $TvHost..."
& $adb connect $TvHost

$disabled = @(
    "com.xiaomi.android.tvsetup.partnercustomizer",
    "com.android.providers.calendar",
    "com.android.wallpapercropper",
    "com.mitv.tvlock",
    "android.autoinstalls.config.xioami.dangal",
    "com.xiaomi.floatingframe",
    "com.google.android.backdrop",
    "com.xm.webcontent",
    "com.google.android.marvin.talkback",
    "com.android.camera2",
    "com.google.android.speech.pumpkin",
    "com.mitv.tvhome.atv",
    "com.android.printspooler",
    "com.xiaomi.mitv.updateservice",
    "com.google.android.tvrecommendations",
    "com.google.android.leanbacklauncher.recommendations",
    "com.xiaomi.mimusic2",
    "com.google.android.tvlauncher",
    "com.google.android.youtube.tvmusic",
    "com.android.gallery3d",
    "com.google.android.tts",
    "com.google.android.videos",
    "com.xiaomo.tv.milegal",
    "com.mitv.tvhome.mitvplus",
    "com.google.android.feedback",
    "com.google.android.syncadapters.calendar",
    "com.google.android.tv.bugreportsender",
    "com.android.smspush",
    "com.miui.tv.analytics",
    "com.google.android.leanbacklauncher",
    "com.xiaomi.statistic",
    "com.android.wallpaperbackup",
    "com.mitv.milinkservice",
    "com.mitv.dream",
    "com.google.android.play.games",
    "com.xiaomi.mitv.handbook",
    "com.android.providers.contacts",
    "com.xiaomi.mitv.tvmanager"
)

Write-Host "Re-enabling all 38 packages on $TvHost..."
foreach ($pkg in $disabled) {
    Write-Host "Enabling $pkg..."
    & $adb -s $TvHost shell "pm enable $pkg"
}

Write-Host "Restoring default UI animation scales and activity manager limits..."
& $adb -s $TvHost shell "settings put global window_animation_scale 1.0"
& $adb -s $TvHost shell "settings put global transition_animation_scale 1.0"
& $adb -s $TvHost shell "settings put global animator_duration_scale 1.0"
& $adb -s $TvHost shell "settings delete global activity_manager_constants"

Write-Host "Restoration complete! All 38 packages re-enabled and settings restored."
