package com.elvan.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.widget.TextView;

/**
 * A one-line text that TitleScroller can slide sideways. TextView's own scrolling moves in whole pixels, which at a slow
 * speed shows as steps (about one pixel every other frame); drawing the text at a fractional offset is smooth.
 */
public class ScrollTextView extends TextView {
    private float offset;
    /** The distance from the title to its second copy, drawn behind it so the loop is seamless; 0 = no second copy. */
    private float loop;

    public ScrollTextView(Context context) {
        super(context);
    }

    public ScrollTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public ScrollTextView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    /** How far the text is moved to the left, in pixels (0 = at its start). */
    void setTextOffset(float pixels) {
        if (pixels == offset) return;
        offset = pixels;
        invalidate();
    }

    void setLoop(float distance) {
        loop = distance;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (offset == 0f) {
            super.onDraw(canvas);
            return;
        }
        int save = canvas.save();
        // The labels sit in rows that do not clip, so the moving text is cut to its own box here.
        canvas.clipRect(getPaddingLeft(), 0, getWidth() - getPaddingRight(), getHeight());
        canvas.translate(-offset, 0f);
        super.onDraw(canvas);
        if (loop > 0f) {
            canvas.translate(loop, 0f);
            super.onDraw(canvas);
        }
        canvas.restoreToCount(save);
    }
}
