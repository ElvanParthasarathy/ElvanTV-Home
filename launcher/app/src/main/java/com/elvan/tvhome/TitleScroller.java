package com.elvan.tvhome;

import android.animation.ValueAnimator;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.animation.LinearInterpolator;
import android.widget.TextView;

/**
 * Scrolls a one-line title that is too long for its place, the way Android's own marquee does: it stays still while you
 * move through the row, waits once you rest on it, then slides slowly to the left until the whole title has gone off the
 * left edge and comes in again from the right, and stops back at its start. One loop only, always the same way: it never
 * slides back. Leaving the title puts it back at the start, cut with an ellipsis.
 *
 * TextView's own marquee cannot be told how long to wait or how fast to go and restarts as the system likes, so this
 * drives the scroll; ScrollTextView draws it at fractional pixels so it moves without steps.
 */
final class TitleScroller {
    /** How long you must rest on a title before it moves. */
    private static final long START_DELAY_MS = 1800;
    /** Scrolling speed, in dp a second: a reading pace. */
    private static final float SPEED_DP = 26f;
    /** The empty space between the end of the title leaving and its start coming back in, in dp. */
    private static final float GAP_DP = 40f;

    private final TextView view;
    private final int gravity;
    private ValueAnimator animator;

    private final Runnable startPass = this::runPass;

    private TitleScroller(TextView view) {
        this.view = view;
        this.gravity = view.getGravity();
    }

    /** The title took (focused) or lost the focus. */
    static void set(TextView view, boolean focused) {
        if (view == null) return;
        TitleScroller scroller = (TitleScroller) view.getTag(R.id.title_scroller);
        if (scroller == null) {
            scroller = new TitleScroller(view);
            view.setTag(R.id.title_scroller, scroller);
        }
        if (focused) scroller.begin();
        else scroller.reset();
    }

    private void begin() {
        reset();
        view.postDelayed(startPass, START_DELAY_MS);
    }

    /** Back at the start, cut with an ellipsis, nothing pending. */
    private void reset() {
        view.removeCallbacks(startPass);
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        if (view instanceof ScrollTextView) ((ScrollTextView) view).setLoop(0f);
        move(0f);
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setGravity(gravity);
    }

    private void move(float pixels) {
        if (view instanceof ScrollTextView) ((ScrollTextView) view).setTextOffset(pixels);
        else view.scrollTo(Math.round(pixels), 0);
    }

    private void runPass() {
        if (!view.isAttachedToWindow() || view.getWidth() == 0) return;
        float textWidth = view.getPaint().measureText(view.getText().toString());
        int room = view.getWidth() - view.getPaddingLeft() - view.getPaddingRight();
        if (textWidth <= room + 1) return;   // it fits: nothing to scroll
        float density = view.getResources().getDisplayMetrics().density;
        final boolean loops = view instanceof ScrollTextView;
        // The title leaves on the left and its second copy, one gap behind it, arrives at the start: that is the loop.
        // A plain TextView cannot draw the second copy, so it only slides to the end of the title.
        final float distance = loops ? textWidth + GAP_DP * density : textWidth - room + 6 * density;
        // Without the ellipsis the whole line is there; from the start edge, so it does not jump when it begins.
        view.setEllipsize(null);
        view.setGravity(Gravity.START | (gravity & Gravity.VERTICAL_GRAVITY_MASK));
        if (loops) ((ScrollTextView) view).setLoop(distance);
        move(0f);
        animator = ValueAnimator.ofFloat(0f, distance);
        animator.setInterpolator(new LinearInterpolator());
        animator.setDuration(Math.max(900L, Math.round(distance / (SPEED_DP * density) * 1000f)));
        animator.addUpdateListener(a -> move((Float) a.getAnimatedValue()));
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(android.animation.Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (!cancelled) reset();   // the loop is done: back at the start, and it stays there
            }
        });
        animator.start();
    }
}
