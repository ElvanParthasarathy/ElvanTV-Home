# TV Configurations & One-Click Restore Backup

This directory stores TV configurations and the complete restore toolkit for the **Xiaomi Mi TV 4A Pro / 4C Pro (`MiTV-AXSO0`, dangal)** running **Android TV 9 (Pie, SDK 28)**.

> **Privacy & Security Guarantee:**
> This folder contains **zero personal data**: no IP addresses, no Wi-Fi credentials, no MAC addresses, no device serial numbers, and no account tokens.

---

## What is in this Folder

| File | Purpose |
|---|---|
| [`restore_mitv_axso0.ps1`](restore_mitv_axso0.ps1) | One-click PowerShell restoration script for Windows. |
| [`restore_mitv_axso0.sh`](restore_mitv_axso0.sh) | One-click Bash restoration script for Linux / macOS / Git Bash. |
| [`disabled_packages.txt`](disabled_packages.txt) | Complete list of all 38 disabled bloatware, telemetry, and background services. |
| [`debloat_axso0.txt`](debloat_axso0.txt) | Debloat list compatible with the repository's `tools/disable.sh` and `tools/restore.sh`. |
| [`tv_configs.json`](tv_configs.json) | Complete configuration metadata (display settings, dock apps, optimizations, USB apps). |

---

## How to Restore

If you ever want to revert the TV back to its original factory state, re-enable all 38 packages, and restore default system animations and background process limits:

### Windows (PowerShell)
```powershell
powershell -ExecutionPolicy Bypass -File private/restore_mitv_axso0.ps1 -TvHost <tv ip>:5555
```

### Linux / macOS / Git Bash
```bash
bash private/restore_mitv_axso0.sh <tv ip>:5555
```

Or using the repository's native restore tool:
```bash
TV_HOST=<tv ip>:5555 tools/restore.sh private/debloat_axso0.txt
```

---

## Optimizations Applied

1. **Animation Speed:** Reduced UI animation scales from `1.0x` to `0.5x` (`window_animation_scale`, `transition_animation_scale`, `animator_duration_scale`) for instant remote responsiveness.
2. **RAM Protection:** Capped background cached processes to `4` (`activity_manager_constants: max_cached_processes=4`) to eliminate low-memory thrashing on 1 GB RAM.
3. **USB & Media Preserved:** Kept Mi Media Explorer (`com.xiaomi.mitv.mediaexplorer`), Mi Gallery (`com.mitv.gallery`), Mi Video Player (`com.mitv.videoplayer`), and External Storage provider fully active.
4. **Dock Cleanliness:** Removed stock launcher, Leanback stubs, PatchWall ad services, wallpaper daemons, and inactive music services.
