package com.elvan.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** The people who use this TV: a name and a vector avatar each (a shape on a colour). Kept in the launcher's preferences. */
final class Profiles {
    static final class Profile {
        final String id;
        String name;
        int shape, color;

        Profile(String id, String name, int shape, int color) {
            this.id = id;
            this.name = name;
            this.shape = shape;
            this.color = color;
        }
    }

    static final int MAX_NAME = 16;
    private static final String PREFS = "profiles", LIST = "list", ACTIVE = "active";

    private Profiles() {
    }

    static List<Profile> list(Context ctx) {
        List<Profile> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs(ctx).getString(LIST, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Profile(o.getString("id"), o.optString("n"), o.optInt("s"), o.optInt("c")));
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    private static void save(Context ctx, List<Profile> all) {
        JSONArray a = new JSONArray();
        try {
            for (Profile p : all) a.put(new JSONObject().put("id", p.id).put("n", p.name).put("s", p.shape).put("c", p.color));
        } catch (JSONException ignored) {
        }
        prefs(ctx).edit().putString(LIST, a.toString()).apply();
    }

    /** The profile in use, or null before the first one is made. */
    static Profile active(Context ctx) {
        List<Profile> all = list(ctx);
        String id = prefs(ctx).getString(ACTIVE, "");
        for (Profile p : all) if (p.id.equals(id)) return p;
        return all.isEmpty() ? null : all.get(0);
    }

    static String activeName(Context ctx) {
        Profile p = active(ctx);
        return p == null ? "" : p.name;
    }

    static void setActive(Context ctx, String id) {
        prefs(ctx).edit().putString(ACTIVE, id).apply();
    }

    static Profile add(Context ctx, String name, int shape, int color) {
        List<Profile> all = list(ctx);
        Profile p = new Profile(UUID.randomUUID().toString().substring(0, 8), clean(name), shape, color);
        all.add(p);
        save(ctx, all);
        return p;
    }

    static void update(Context ctx, String id, String name, int shape, int color) {
        List<Profile> all = list(ctx);
        for (Profile p : all) {
            if (!p.id.equals(id)) continue;
            p.name = clean(name);
            p.shape = shape;
            p.color = color;
        }
        save(ctx, all);
    }

    /** Removes a profile; the last one cannot be removed. */
    static void delete(Context ctx, String id) {
        List<Profile> all = list(ctx);
        if (all.size() <= 1) return;
        for (int i = 0; i < all.size(); i++) if (all.get(i).id.equals(id)) all.remove(i--);
        save(ctx, all);
        if (id.equals(prefs(ctx).getString(ACTIVE, ""))) setActive(ctx, all.get(0).id);
    }

    /** What to add to a per-person file name: nothing for the first profile (it keeps the files that were there before
     *  profiles existed), "_id" for the others, so each person has their own app arrangement. */
    static String fileSuffix(Context ctx) {
        List<Profile> all = list(ctx);
        Profile p = active(ctx);
        return p == null || all.isEmpty() || all.get(0).id.equals(p.id) ? "" : "_" + p.id;
    }

    static String clean(String name) {
        String n = name == null ? "" : name.trim().replaceAll("\\s+", " ");
        if (n.length() > MAX_NAME) n = n.substring(0, MAX_NAME).trim();
        return n;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
