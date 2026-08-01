package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.util.AttributeSet;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.OverScroller;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.leanback.widget.VerticalGridView;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.utils.ResUtil;

/**
 * VerticalGridView that tolerates mouse wheel / drag scrolling without snapping back to the
 * previously selected row (Leanback keeps the selected item on-screen by default).
 */
public class HomeGridView extends VerticalGridView {

    private boolean freeScroll;
    private float wheelCarry;
    private final OverScroller scroller;
    private final Runnable endFreeScroll = () -> {
        freeScroll = false;
        syncSelectionToVisible();
    };
    private final Runnable wheelFling = new Runnable() {
        @Override
        public void run() {
            if (scroller.computeScrollOffset()) {
                int y = scroller.getCurrY();
                int dy = y - lastScrollerY;
                lastScrollerY = y;
                if (dy != 0) scrollBy(0, dy);
                syncSelectionToVisible();
                postOnAnimation(this);
            } else {
                endFreeScrollSoon();
            }
        }
    };
    private int lastScrollerY;

    public HomeGridView(@NonNull Context context) {
        this(context, null);
    }

    public HomeGridView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public HomeGridView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        scroller = new OverScroller(context, new DecelerateInterpolator());
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        trackPointer(e);
        return super.onInterceptTouchEvent(e);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        trackPointer(e);
        return super.onTouchEvent(e);
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_SCROLL && event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) {
            float scroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
            if (scroll != 0f) {
                beginFreeScroll();
                // Accumulate fractional ticks for smoother, smaller steps than a full page jump.
                wheelCarry += -scroll * ResUtil.dp2px(48);
                int dy = (int) wheelCarry;
                if (dy != 0) {
                    wheelCarry -= dy;
                    scroller.forceFinished(true);
                    lastScrollerY = 0;
                    scroller.startScroll(0, 0, 0, dy, 120);
                    removeCallbacks(wheelFling);
                    postOnAnimation(wheelFling);
                }
                return true;
            }
        }
        return super.onGenericMotionEvent(event);
    }

    @Override
    public void scrollToPosition(int position) {
        if (freeScroll) return;
        super.scrollToPosition(position);
    }

    @Override
    public void smoothScrollToPosition(int position) {
        if (freeScroll) return;
        super.smoothScrollToPosition(position);
    }

    private void trackPointer(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                scroller.forceFinished(true);
                removeCallbacks(wheelFling);
                beginFreeScroll();
                break;
            case MotionEvent.ACTION_MOVE:
                beginFreeScroll();
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                syncSelectionToVisible();
                endFreeScrollSoon();
                break;
            default:
                break;
        }
    }

    private void beginFreeScroll() {
        freeScroll = true;
        removeCallbacks(endFreeScroll);
    }

    private void endFreeScrollSoon() {
        removeCallbacks(endFreeScroll);
        post(endFreeScroll);
    }

    private void syncSelectionToVisible() {
        int childCount = getChildCount();
        if (childCount == 0 || getAdapter() == null) return;
        int anchor = getPaddingTop();
        View best = null;
        int bestScore = Integer.MAX_VALUE;
        for (int i = 0; i < childCount; i++) {
            View child = getChildAt(i);
            if (child.getBottom() <= anchor) continue;
            int score = Math.abs(child.getTop() - anchor);
            if (score < bestScore) {
                bestScore = score;
                best = child;
            }
        }
        if (best == null) best = getChildAt(0);
        int pos = getChildAdapterPosition(best);
        if (pos == RecyclerView.NO_POSITION || pos == getSelectedPosition()) return;
        boolean keep = freeScroll;
        freeScroll = true;
        setSelectedPosition(pos);
        freeScroll = keep;
    }
}
