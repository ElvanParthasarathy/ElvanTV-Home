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

    private boolean unclipped;

    /**
     * Draws the text with no clip at all. A TextView cuts its text at the bottom of its box less the bottom padding, and
     * Tamil (drawn from a fallback font) reaches below the line height Android reports for it, so the second line of a long
     * name was cut across. The label of an app tile is as tall as its lines; what spills a few pixels is shown.
     */
    void setUnclipped(boolean value) {
        unclipped = value;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        if (!unclipped || MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) return;
        // Tamil and other scripts that are drawn from a fallback font hang below the line Android reports for them: the label
        // takes a little more height, so whatever sits under it (the "press to move" line) moves down by what it needs.
        CharSequence text = getText();
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) > 0x24F) {
                setMeasuredDimension(getMeasuredWidth(), getMeasuredHeight() + Math.round(getLineHeight() * 0.7f));
                return;
            }
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (unclipped && offset == 0f && getLayout() != null) {
            android.text.Layout layout = getLayout();
            getPaint().setColor(getCurrentTextColor());
            getPaint().drawableState = getDrawableState();
            canvas.save();
            canvas.translate(getCompoundPaddingLeft(), getExtendedPaddingTop());
            layout.draw(canvas);
            canvas.restore();
            return;
        }
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
