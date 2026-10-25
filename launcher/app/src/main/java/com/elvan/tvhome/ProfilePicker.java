package com.elvan.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputFilter;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * The profile screen that covers Home: "Who's watching?" with one avatar per profile (like Google TV's account
 * screen), an editor to make a profile (a typed name, a character, a gradient).
 * Built in code from vector avatars, so there are no images to size.
 */
final class ProfilePicker extends FrameLayout {
    interface Listener {
        /** A profile was chosen: the screen is done. */
        void onChosen(Profiles.Profile profile);
    }

    private static final int BG = 0xFF0D0D0F, TEXT = 0xFFE8EAED, DIM = 0xFF9AA0A6, ACCENT = 0xFFE8EAED;
    private final Listener listener;
    private final float d;
    private boolean editMode;
    /** Editor state. */
    private Profiles.Profile editing;
    private int shape, color;
    private boolean firstRun;
    private LinearLayout page;
    private EditText nameField;
    private View previewHost;

    ProfilePicker(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        this.d = context.getResources().getDisplayMetrics().density;
        setBackgroundColor(BG);
        setClickable(true);
        setFocusable(false);
        setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);
        // The TV's keyboard would open by itself over the name and take the arrow keys; it opens when OK is pressed on the name.
        if (context instanceof android.app.Activity) {
            ((android.app.Activity) context).getWindow().setSoftInputMode(
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        }
    }

    // ---- entry points ------------------------------------------------------------------------------------------------
    /** First run: nobody yet, so ask for a name. Otherwise the list of people. */
    void start() {
        if (Profiles.list(getContext()).isEmpty()) showEditor(null, true);
        else showChooser();
    }

    /** Back: out of the editor to the list. The list itself cannot be left without choosing someone. */
    boolean onBack() {
        if (busy || page == null) return true;
        if (Boolean.TRUE.equals(page.getTag())) {   // the editor
            if (firstRun) return true;
            hideKeyboard();
            showChooser();
            return true;
        }
        if (editMode) {
            editMode = false;
            showChooser();
        }
        return true;
    }

    // ---- the list of people --------------------------------------------------------------------------------------------
    private void showChooser() {
        removeAllViews();
        LinearLayout col = column();
        page = col;
        col.setTag(Boolean.FALSE);
        col.addView(label(getContext().getString(R.string.app_name), 14, DIM, false), lp(0, 0, 0, 10));
        col.addView(label(getContext().getString(editMode ? R.string.profile_pick_edit : R.string.profile_whos), 30, TEXT, true),
                lp(0, 0, 0, 36));

        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        View first = null;
        Profiles.Profile active = Profiles.active(getContext());
        for (Profiles.Profile p : Profiles.list(getContext())) {
            View tile = tile(new ProfileAvatar(getContext(), p.shape, p.color, 0), p.name, false);
            tile.setOnClickListener(v -> {
                if (editMode) showEditor(p, false);
                else choose(p);
            });
            tiles.put(p.id, tile);
            row.addView(tile, new LinearLayout.LayoutParams((int) (140 * d), ViewGroup.LayoutParams.WRAP_CONTENT));
            if (active != null && p.id.equals(active.id)) first = tile;
            else if (first == null && active == null) first = tile;
        }
        View add = tile(null, getContext().getString(R.string.profile_add), true);
        add.setOnClickListener(v -> showEditor(null, false));
        row.addView(add, new LinearLayout.LayoutParams((int) (140 * d), ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(row, lp(0, 0, 0, 40));

        TextView pill = button(getContext().getString(editMode ? R.string.profile_done : R.string.profile_edit));
        pill.setOnClickListener(v -> {
            editMode = !editMode;
            showChooser();
        });
        col.addView(pill, new LinearLayout.LayoutParams((int) (240 * d), (int) (46 * d)));
        addView(col, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        unclip(this);
        (first != null ? first : add).requestFocus();
    }

    private final java.util.Map<String, View> tiles = new java.util.HashMap<>();
    private boolean busy;

    private void choose(Profiles.Profile p) {
        Profiles.setActive(getContext(), p.id);
        View tile = tiles.get(p.id);
        finish(p, tile instanceof ViewGroup ? ((ViewGroup) tile).getChildAt(0) : null);
    }

    /**
     * The welcome, as part of choosing: the rest of the screen fades away while the chosen picture glides to the middle
     * and grows, and "வணக்கம், name" rises in under it. Then the launcher is told, and the screen leaves.
     */
    void finish(Profiles.Profile p, View face) {
        if (busy) return;
        busy = true;
        hideKeyboard();
        final View stage = page;
        if (face == null) face = previewHost;
        if (stage == null || face == null || face.getWidth() == 0) {
            listener.onChosen(p);
            return;
        }
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        int[] f = new int[2], me = new int[2];
        face.getLocationInWindow(f);
        getLocationInWindow(me);
        final int w = face.getWidth(), h = face.getHeight();
        final AvatarView big = new AvatarView(getContext(), p.shape, p.color, false);
        LayoutParams lp = new LayoutParams(w, h);
        lp.leftMargin = f[0] - me[0];
        lp.topMargin = f[1] - me[1];
        addView(big, lp);
        stage.animate().alpha(0f).setDuration(240).start();
        final float scale = 1.35f;
        float dx = getWidth() / 2f - (lp.leftMargin + w / 2f);
        float dy = getHeight() / 2f - 24 * d - (lp.topMargin + h / 2f);
        big.animate().translationX(dx).translationY(dy).scaleX(scale).scaleY(scale).setDuration(560)
                .setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)).start();
        // The greeting: the word dimmer, the name bold, rising into place.
        String lead = getContext().getString(R.string.profile_welcome_lead);
        android.text.SpannableString text = new android.text.SpannableString(lead + " " + p.name);
        text.setSpan(new android.text.style.ForegroundColorSpan(0xB3FFFFFF), 0, lead.length(), 0);
        text.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), lead.length() + 1, text.length(), 0);
        TextView greet = label("", 28, TEXT, false);
        greet.setText(text);
        LayoutParams gp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gp.gravity = Gravity.CENTER_HORIZONTAL | Gravity.TOP;
        gp.topMargin = (int) (getHeight() / 2f - 24 * d + h * scale / 2f + 22 * d);
        addView(greet, gp);
        greet.setAlpha(0f);
        greet.setTranslationY(16 * d);
        greet.animate().alpha(1f).translationY(0f).setStartDelay(260).setDuration(460)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
        postDelayed(() -> listener.onChosen(p), 1500);
    }

    // ---- the editor ------------------------------------------------------------------------------------------------
    private void showEditor(Profiles.Profile p, boolean first) {
        removeAllViews();
        editing = p;
        firstRun = first;
        shape = p == null ? (int) (Math.random() * ProfileAvatar.CHARACTERS) : p.shape;
        color = p == null ? (int) (Math.random() * ProfileAvatar.GRADIENTS.length) : p.color;
        LinearLayout col = column();
        page = col;
        col.setTag(Boolean.TRUE);
        col.addView(label(getContext().getString(first ? R.string.profile_first : p == null ? R.string.profile_new : R.string.profile_edit_one),
                26, TEXT, true), lp(0, 0, 0, 18));

        previewHost = new FrameLayout(getContext());
        col.addView(previewHost, new LinearLayout.LayoutParams((int) (88 * d), (int) (88 * d)));
        refreshPreview();

        nameField = new EditText(getContext());
        nameField.setText(p == null ? "" : p.name);
        nameField.setHint(R.string.profile_name_hint);
        nameField.setHintTextColor(DIM);
        nameField.setTextColor(TEXT);
        nameField.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        nameField.setTypeface(UiFont.get(getContext(), R.string.font_regular));
        nameField.setGravity(Gravity.CENTER);
        nameField.setSingleLine(true);
        nameField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        nameField.setImeOptions(EditorInfo.IME_ACTION_DONE);
        nameField.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        // On a TV the keyboard takes over the arrow keys, so it opens only when OK is pressed on the name, not on focus.
        nameField.setShowSoftInputOnFocus(false);
        // An editable text swallows Down (it moves its cursor), so the way on to the shapes and Save is done here.
        nameField.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_DPAD_DOWN || event.getAction() != KeyEvent.ACTION_DOWN) return false;
            View next = v.focusSearch(View.FOCUS_DOWN);
            if (next != null) next.requestFocus();
            return true;
        });
        nameField.setOnClickListener(v -> {
            InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(nameField, 0);
        });
        nameField.setFilters(new InputFilter[]{new InputFilter.LengthFilter(Profiles.MAX_NAME)});
        nameField.setBackground(focusBg(0x22FFFFFF, 0x44FFFFFF, 26));
        nameField.setPadding((int) (20 * d), 0, (int) (20 * d), 0);
        nameField.setOnEditorActionListener((v, action, event) -> {
            hideKeyboard();
            return true;
        });
        col.addView(nameField, new LinearLayout.LayoutParams((int) (340 * d), (int) (52 * d)) {{ topMargin = (int) (16 * d); }});

        // The shapes, then the colours.
        LinearLayout shapes = new LinearLayout(getContext());
        shapes.setGravity(Gravity.CENTER);
        for (int i = 0; i < ProfileAvatar.CHARACTERS; i++) {
            final int s = i;
            AvatarView v = new AvatarView(getContext(), s, color, true);
            v.setFocusable(true);
            v.setOnClickListener(x -> {
                shape = s;
                refreshPreview();
                refreshShapes();
            });
            v.setOnFocusChangeListener((x, f) -> {
                x.animate().scaleX(f ? 1.15f : 1f).scaleY(f ? 1.15f : 1f).setDuration(120).start();
                x.invalidate();
            });
            shapes.addView(v, new LinearLayout.LayoutParams((int) (54 * d), (int) (54 * d)) {{ leftMargin = rightMargin = (int) (4 * d); }});
        }
        shapes.setTag("shapes");
        col.addView(shapes, lp(0, 20, 0, 0));
        LinearLayout colors = new LinearLayout(getContext());
        colors.setGravity(Gravity.CENTER);
        for (int i = 0; i < ProfileAvatar.GRADIENTS.length; i++) {
            final int c = i;
            View v = new View(getContext()) {
                @Override
                protected void onDraw(Canvas canvas) {
                    Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
                    float cx = getWidth() / 2f;
                    float cy = getHeight() / 2f;
                    float r = Math.min(getWidth(), getHeight()) / 2f;
                    paint.setShader(ProfileAvatar.gradientShader(c, new RectF(0, 0, getWidth(), getHeight())));
                    canvas.drawCircle(cx, cy, r - 1.5f * d, paint);
                    paint.setShader(null);
                    if (isFocused() || isSelected() || color == c) {
                        paint.setStyle(Paint.Style.STROKE);
                        paint.setStrokeWidth(3 * d);
                        paint.setColor(0xFFFFFFFF);
                        canvas.drawCircle(cx, cy, r - 1.5f * d, paint);
                    }
                }
            };
            v.setFocusable(true);
            v.setOnClickListener(x -> {
                color = c;
                refreshPreview();
                refreshShapes();
                colors.invalidate();
                for (int k = 0; k < colors.getChildCount(); k++) colors.getChildAt(k).invalidate();
            });
            v.setOnFocusChangeListener((x, f) -> {
                x.animate().scaleX(f ? 1.15f : 1f).scaleY(f ? 1.15f : 1f).setDuration(120).start();
                if (f) {
                    color = c;
                    refreshPreview();
                    refreshShapes();
                    for (int k = 0; k < colors.getChildCount(); k++) colors.getChildAt(k).invalidate();
                }
                x.invalidate();
            });
            colors.addView(v, new LinearLayout.LayoutParams((int) (40 * d), (int) (40 * d)) {{ leftMargin = rightMargin = (int) (6 * d); }});
        }
        col.addView(colors, lp(0, 12, 0, 0));

        LinearLayout buttons = new LinearLayout(getContext());
        buttons.setGravity(Gravity.CENTER);
        TextView save = button(getContext().getString(R.string.profile_save));
        save.setOnClickListener(v -> save());
        buttons.addView(save, new LinearLayout.LayoutParams((int) (150 * d), (int) (46 * d)) {{ leftMargin = rightMargin = (int) (6 * d); }});
        if (!first) {
            TextView cancel = button(getContext().getString(R.string.profile_cancel));
            cancel.setOnClickListener(v -> {
                hideKeyboard();
                showChooser();
            });
            buttons.addView(cancel, new LinearLayout.LayoutParams((int) (150 * d), (int) (46 * d)) {{ leftMargin = rightMargin = (int) (6 * d); }});
        }
        if (!first && p != null && Profiles.list(getContext()).size() > 1) {
            TextView del = button(getContext().getString(R.string.profile_delete));
            del.setOnClickListener(v -> {
                Profiles.delete(getContext(), p.id);
                hideKeyboard();
                showChooser();
            });
            buttons.addView(del, new LinearLayout.LayoutParams((int) (170 * d), (int) (46 * d)) {{ leftMargin = rightMargin = (int) (6 * d); }});
        }
        col.addView(buttons, lp(0, 22, 0, 0));
        addView(col, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        unclip(this);
        nameField.requestFocus();
        refreshShapes();
    }

    private void save() {
        String name = Profiles.clean(nameField.getText().toString());
        if (name.isEmpty()) {
            nameField.requestFocus();
            nameField.setHintTextColor(0xFFF28B82);
            return;
        }
        hideKeyboard();
        Profiles.Profile saved;
        if (editing == null) {
            saved = Profiles.add(getContext(), name, shape, color);
        } else {
            Profiles.update(getContext(), editing.id, name, shape, color);
            saved = new Profiles.Profile(editing.id, name, shape, color);
        }
        if (firstRun || editing == null) {
            Profiles.setActive(getContext(), saved.id);
            finish(saved, null);
        } else {
            showChooser();
        }
    }

    private void refreshPreview() {
        ((FrameLayout) previewHost).removeAllViews();
        ((FrameLayout) previewHost).addView(new AvatarView(getContext(), shape, color, false),
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** The shape choices wear the colour chosen. */
    private void refreshShapes() {
        View shapes = page == null ? null : page.findViewWithTag("shapes");
        if (!(shapes instanceof LinearLayout)) return;
        LinearLayout row = (LinearLayout) shapes;
        for (int i = 0; i < row.getChildCount(); i++) {
            ((AvatarView) row.getChildAt(i)).set(i, color, i == shape);
        }
    }

    // ---- pieces ------------------------------------------------------------------------------------------------------
    /** A view that grows when focused (or draws a ring) must not be cut off by the box of the row it is in. */
    private static void unclip(View v) {
        if (!(v instanceof ViewGroup)) return;
        ViewGroup g = (ViewGroup) v;
        g.setClipChildren(false);
        g.setClipToPadding(false);
        for (int i = 0; i < g.getChildCount(); i++) unclip(g.getChildAt(i));
    }

    private LinearLayout column() {
        LinearLayout col = new LinearLayout(getContext());
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        return col;
    }

    private LinearLayout.LayoutParams lp(int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins((int) (l * d), (int) (t * d), (int) (r * d), (int) (b * d));
        return p;
    }

    private TextView label(String text, int sp, int color, boolean bold) {
        TextView t = new TextView(getContext());
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setGravity(Gravity.CENTER);
        t.setIncludeFontPadding(false);
        t.setTypeface(UiFont.get(getContext(), bold ? R.string.font_medium : R.string.font_regular));
        return t;
    }

    /** One person in the list: the avatar and the name under it; the "add" tile has a plus instead. */
    private View tile(ProfileAvatar avatar, String name, boolean plus) {
        LinearLayout t = new LinearLayout(getContext());
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setFocusable(true);
        t.setClickable(true);
        AvatarView v = plus ? new AvatarView(getContext(), -1, 0, false) : null;
        View face = plus ? v : new AvatarView(getContext(), avatar);
        t.addView(face, new LinearLayout.LayoutParams((int) (104 * d), (int) (104 * d)) {{ topMargin = (int) (6 * d); }});
        TextView label = label(name, 16, DIM, false);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.addView(label, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) {{ topMargin = (int) (10 * d); }});
        t.setOnFocusChangeListener((x, f) -> {
            face.animate().scaleX(f ? 1.12f : 1f).scaleY(f ? 1.12f : 1f).setDuration(140).start();
            label.setTextColor(f ? TEXT : DIM);
            face.setSelected(f);
            face.invalidate();
        });
        return t;
    }

    private TextView button(String text) {
        TextView b = label(text, 16, TEXT, true);
        b.setFocusable(true);
        b.setClickable(true);
        b.setBackground(focusBg(0x22FFFFFF, 0xFFE8EAED, 23));
        b.setTextColor(new android.content.res.ColorStateList(new int[][]{{android.R.attr.state_focused}, {}},
                new int[]{0xFF202124, TEXT}));
        return b;
    }

    private android.graphics.drawable.Drawable focusBg(int normal, int focused, int radiusDp) {
        android.graphics.drawable.StateListDrawable s = new android.graphics.drawable.StateListDrawable();
        GradientDrawable f = new GradientDrawable();
        f.setColor(focused);
        f.setCornerRadius(radiusDp * d);
        GradientDrawable n = new GradientDrawable();
        n.setColor(normal);
        n.setCornerRadius(radiusDp * d);
        s.addState(new int[]{android.R.attr.state_focused}, f);
        s.addState(new int[]{}, n);
        return s;
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null && nameField != null) imm.hideSoftInputFromWindow(nameField.getWindowToken(), 0);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        return super.dispatchKeyEvent(event);
    }

    /** A profile's picture as a view: also the plain "+" circle (shape -1), and the shape choices of the editor (small, ringed when chosen). */
    private static final class AvatarView extends View {
        private int shape, color;
        private boolean choice, chosen;
        private ProfileAvatar fixed;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        AvatarView(Context c, int shape, int color, boolean choice) {
            super(c);
            set(shape, color, false);
            this.choice = choice;
        }

        AvatarView(Context c, ProfileAvatar avatar) {
            super(c);
            this.fixed = avatar;
        }

        void set(int shape, int color, boolean chosen) {
            this.shape = shape;
            this.color = color;
            this.chosen = chosen;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float d = getResources().getDisplayMetrics().density;
            RectF b = new RectF(0, 0, getWidth(), getHeight());
            if (fixed != null) {
                fixed.setBounds(0, 0, getWidth(), getHeight());
                fixed.draw(canvas);
            } else if (shape < 0) {   // the "add" tile: a dark circle with a plus
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0xFF2A2B2E);
                canvas.drawCircle(b.centerX(), b.centerY(), Math.min(b.width(), b.height()) / 2f, paint);
                paint.setColor(0xFFE8EAED);
                float g = b.width() * 0.3f, t = 3 * d;
                canvas.drawRoundRect(b.centerX() - g / 2, b.centerY() - t / 2, b.centerX() + g / 2, b.centerY() + t / 2, t, t, paint);
                canvas.drawRoundRect(b.centerX() - t / 2, b.centerY() - g / 2, b.centerX() + t / 2, b.centerY() + g / 2, t, t, paint);
            } else {
                ProfileAvatar a = new ProfileAvatar(getContext(), shape, color, 0);
                a.setBounds(0, 0, getWidth(), getHeight());
                a.draw(canvas);
            }
            if (isSelected() || isFocused() || chosen) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(3 * d);
                paint.setColor(0xFFFFFFFF);
                canvas.drawCircle(b.centerX(), b.centerY(), Math.min(b.width(), b.height()) / 2f - 1.5f * d, paint);
            }
        }
    }
}
