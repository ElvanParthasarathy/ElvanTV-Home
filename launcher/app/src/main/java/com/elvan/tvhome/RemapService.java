package com.elvan.tvhome;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Gives the remote's buttons the jobs set in Settings, Button remapper, from any app. It only sees keys (like the other
 * helpers) and does nothing for a button that has no job. While the remapper page is waiting for a button
 * ("capture"), the next unprotected key goes to the page instead.
 */
public class RemapService extends AccessibilityService {
    /** The page that is waiting for a button. Called on the main thread with the key and its scancode. */
    interface Capture {
        void onKey(int keyCode, int scanCode);
    }

    private static volatile Capture capture;
    /** Android has bound the helper and it is running. "Enabled" in Settings is not the same: after an update or a slow boot it can be on but never bound. */
    private static volatile boolean connected;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** The button the capture took on its key down, so its key up is swallowed too. */
    private int takenKey = Integer.MIN_VALUE;

    static void setCapture(Capture c) {
        capture = c;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        connected = true;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        connected = false;
        return super.onUnbind(intent);
    }

    /**
     * Settings says the helper is on and there are buttons to run, but Android has not bound it (seen on the Mi TV Stick
     * after an update or a restart). Switching Android's accessibility switch off and on again makes it bind it. Needs the
     * secure-settings grant.
     */
    static void repair(Context ctx) {
        if (connected || !isEnabled(ctx)) return;
        if (ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return;
        try {
            Settings.Secure.putInt(ctx.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0);
            final Context app = ctx.getApplicationContext();
            MAIN.postDelayed(() -> enable(app), 700);   // writes the list again and switches accessibility back on
        } catch (SecurityException ignored) {
        }
    }

    static boolean isConnected() {
        return connected;
    }

    static boolean isEnabled(Context ctx) {
        String list = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        String me = new ComponentName(ctx, RemapService.class).flattenToString();
        return list != null && (":" + list + ":").contains(":" + me + ":")
                && Settings.Secure.getInt(ctx.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1;
    }

    /** Turns the helper on by itself when the launcher was granted WRITE_SECURE_SETTINGS (adb); false when it was not. */
    static boolean enable(Context ctx) {
        if (ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return false;
        try {
            String me = new ComponentName(ctx, RemapService.class).flattenToString();
            String list = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (list == null || list.isEmpty()) list = me;
            else if (!(":" + list + ":").contains(":" + me + ":")) list = list + ":" + me;
            Settings.Secure.putString(ctx.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list);
            Settings.Secure.putInt(ctx.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }

    private static int idOf(int code, int scan) {
        return code == KeyEvent.KEYCODE_UNKNOWN ? -2 - scan : code;
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        final int code = event.getKeyCode(), scan = event.getScanCode();
        if (ButtonMap.isProtected(code)) return false;
        Capture c = capture;
        if (c != null) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                takenKey = idOf(code, scan);
                MAIN.post(() -> c.onKey(code, scan));
            }
            return true;   // the button is only being learned: it does nothing now
        }
        if (event.getAction() == KeyEvent.ACTION_UP && takenKey == idOf(code, scan)) {
            takenKey = Integer.MIN_VALUE;
            return true;
        }
        ButtonMap.Entry e = ButtonMap.find(this, code, scan);
        if (e == null) return false;
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) run(e);
        return true;
    }

    private void run(ButtonMap.Entry e) {
        switch (e.action) {
            case ButtonMap.ACTION_APP:
                RemoteKeys.launch(this, e.arg);
                break;
            case ButtonMap.ACTION_HOME:
                startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(getPackageName())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
                break;
            case ButtonMap.ACTION_SETTINGS:
                // Home in front: the panel opens in it. Any other app: the panel opens over the app, in a transparent
                // screen of its own, so Home does not come forward first.
                startActivity(new Intent(MainActivity.ACTION_OPEN_SETTINGS)
                        .setClass(this, MainActivity.homeInFront ? MainActivity.class : SettingsOverlayActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
                break;
            case ButtonMap.ACTION_INPUTS:
                startActivity(new Intent(MainActivity.ACTION_VIEW_INPUTS).setClass(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                break;
            case ButtonMap.ACTION_MEDIA:
                media(e.arg);
                break;
            case ButtonMap.ACTION_SYSTEM:
                system(e.arg);
                break;
            default:   // block: the button is held back and does nothing
                break;
        }
    }

    private void media(String id) {
        android.media.AudioManager audio = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
        if (audio == null) return;
        switch (id) {
            case "vol_up":
                audio.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_RAISE, android.media.AudioManager.FLAG_SHOW_UI);
                return;
            case "vol_down":
                audio.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_LOWER, android.media.AudioManager.FLAG_SHOW_UI);
                return;
            case "mute":
                audio.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_TOGGLE_MUTE, android.media.AudioManager.FLAG_SHOW_UI);
                return;
            default:
                break;
        }
        int code;
        switch (id) {
            case "play_pause": code = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE; break;
            case "next": code = KeyEvent.KEYCODE_MEDIA_NEXT; break;
            case "previous": code = KeyEvent.KEYCODE_MEDIA_PREVIOUS; break;
            case "rewind": code = KeyEvent.KEYCODE_MEDIA_REWIND; break;
            case "fast_forward": code = KeyEvent.KEYCODE_MEDIA_FAST_FORWARD; break;
            case "stop": code = KeyEvent.KEYCODE_MEDIA_STOP; break;
            default: return;
        }
        long now = android.os.SystemClock.uptimeMillis();
        audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0));
        audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0));
    }

    private void system(String id) {
        String action;
        switch (id) {
            case "wifi": action = Settings.ACTION_WIFI_SETTINGS; break;
            case "display": action = Settings.ACTION_DISPLAY_SETTINGS; break;
            case "apps": action = Settings.ACTION_APPLICATION_SETTINGS; break;
            case "storage": action = Settings.ACTION_INTERNAL_STORAGE_SETTINGS; break;
            case "date": action = Settings.ACTION_DATE_SETTINGS; break;
            case "accessibility": action = Settings.ACTION_ACCESSIBILITY_SETTINGS; break;
            case "developer": action = Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS; break;
            default: action = Settings.ACTION_BLUETOOTH_SETTINGS; break;
        }
        Intent open = SystemSettings.intent(this, action);
        if ("bluetooth".equals(id)) {
            // The TV's own pairing screen, when it has one, like the Sound output page.
            Intent pair = new Intent("com.google.android.intent.action.CONNECT_INPUT");
            if (getPackageManager().resolveActivity(pair, 0) != null) open = pair;
        }
        try {
            startActivity(open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (android.content.ActivityNotFoundException | SecurityException unavailable) {
            android.widget.Toast.makeText(this, R.string.not_available, android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }
}
