package com.elvan.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The remote buttons the owner has given a job (Settings, Button remapper). Kept as a small JSON list in the launcher's
 * own preferences; RemapService reads it on every key, so a change takes effect at once.
 */
final class ButtonMap {
    static final String ACTION_APP = "app", ACTION_HOME = "home", ACTION_SETTINGS = "settings",
            ACTION_INPUTS = "inputs", ACTION_BLOCK = "block", ACTION_MEDIA = "media", ACTION_SYSTEM = "system";
    /** The media and volume jobs, and the settings pages a button can open (RemapService does them). */
    static final String[] MEDIA_IDS = {"play_pause", "next", "previous", "rewind", "fast_forward", "stop", "vol_up", "vol_down", "mute"};
    static final String[] SYSTEM_IDS = {"wifi", "bluetooth", "display", "apps", "storage", "date", "accessibility", "developer"};
    private static final String PREFS = "button_map", KEY = "entries";

    static final class Entry {
        final int keyCode, scanCode;
        final String action, arg, label;

        Entry(int keyCode, int scanCode, String action, String arg, String label) {
            this.keyCode = keyCode;
            this.scanCode = scanCode;
            this.action = action;
            this.arg = arg == null ? "" : arg;
            this.label = label == null ? "" : label;
        }

        boolean matches(int code, int scan) {
            return keyCode != KeyEvent.KEYCODE_UNKNOWN
                    ? keyCode == code
                    : code == KeyEvent.KEYCODE_UNKNOWN && scanCode == scan;
        }
    }

    private ButtonMap() {
    }

    /** Buttons that can never be given a job: the ones that move the focus, go back or home, and power, so a wrong choice
     *  cannot lock the owner out of the TV. Volume, mute, media and every other button can be remapped. */
    static boolean isProtected(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_HOME:
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_POWER:
            case KeyEvent.KEYCODE_TV_POWER:
            case KeyEvent.KEYCODE_SLEEP:
            case KeyEvent.KEYCODE_WAKEUP:
                return true;
            default:
                return false;
        }
    }

    static List<Entry> list(Context ctx) {
        List<Entry> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs(ctx).getString(KEY, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Entry(o.optInt("k"), o.optInt("s"), o.optString("a"), o.optString("g"), o.optString("l")));
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    static Entry find(Context ctx, int keyCode, int scanCode) {
        for (Entry e : list(ctx)) if (e.matches(keyCode, scanCode)) return e;
        return null;
    }

    /** Saves the job of a button, replacing the one it had; action null removes it. */
    static void set(Context ctx, int keyCode, int scanCode, String action, String arg, String label) {
        JSONArray out = new JSONArray();
        try {
            for (Entry e : list(ctx)) {
                if (e.matches(keyCode, scanCode)) continue;
                out.put(json(e));
            }
            if (action != null) out.put(json(new Entry(keyCode, scanCode, action, arg, label)));
        } catch (JSONException ignored) {
        }
        prefs(ctx).edit().putString(KEY, out.toString()).apply();
    }

    private static JSONObject json(Entry e) throws JSONException {
        return new JSONObject().put("k", e.keyCode).put("s", e.scanCode).put("a", e.action).put("g", e.arg).put("l", e.label);
    }

    /** "PROG RED", or "Button 353" for a button Android has no name for. */
    static String keyName(int keyCode, int scanCode) {
        if (keyCode != KeyEvent.KEYCODE_UNKNOWN) {
            String name = KeyEvent.keyCodeToString(keyCode);
            if (name.startsWith("KEYCODE_")) name = name.substring("KEYCODE_".length());
            return name.replace('_', ' ');
        }
        return "Button " + scanCode;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
