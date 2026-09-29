package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.leanback.widget.OnChildViewHolderSelectedListener;
import androidx.leanback.widget.VerticalGridView;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.utils.ResUtil;

/**
 * VerticalGridView that tolerates mouse wheel / drag scrolling without snapping back to the
 * previously selected row (Leanback keeps the selected item on-screen by default).
 */
public class HomeGridView extends VerticalGridView {

    private static final long WHEEL_IDLE_MS = 250;

    private boolean freeScroll;
    private boolean pinning;
    private boolean pointerDragged;
    private float downX;
    private float downY;
    private float wheelCarry;
    private final Runnable endFreeScroll = () -> {
        freeScroll = false;
        syncSelectionToVisible();
        // Stay where the user left the list. Only pin when already on the top row so the
        // wheel does not feel like it is fighting Leanback's "keep selection visible" scroll.
        if (getSelectedPosition() == 0) pinTopRow();
    };

    public HomeGridView(@NonNull Context context) {
        this(context, null);
    }

    public HomeGridView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public HomeGridView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        addOnChildViewHolderSelectedListener(new OnChildViewHolderSelectedListener() {
            @Override
            public void onChildViewHolderSelected(@NonNull RecyclerView parent, @Nullable RecyclerView.ViewHolder child, int position, int subposition) {
                // Only pin after a free-scroll settles. Pinning on every D-pad selection
                // fights Leanback's fling and makes row scrolling feel sticky.
                if (position == 0 && !freeScroll) post(HomeGridView.this::pinTopRow);
            }
        });
        addOnScrollListener(new OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                if (newState == SCROLL_STATE_IDLE && !freeScroll && getSelectedPosition() == 0) pinTopRow();
            }
        });
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        // Rows are HorizontalGridViews and will swallow vertical wheel ticks before we see
        // them. Handle the wheel here so the home list always scrolls under the pointer.
        if (isWheelEvent(event) && handleWheel(event)) return true;
        return super.dispatchGenericMotionEvent(event);
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (isWheelEvent(event) && handleWheel(event)) return true;
        return super.onGenericMotionEvent(event);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        if (isPointerFreeScrollSource(e)) trackPointer(e);
        return super.onInterceptTouchEvent(e);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (isPointerFreeScrollSource(e)) trackPointer(e);
        return super.onTouchEvent(e);
    }

    @Override
    public void scrollToPosition(int position) {
        if (freeScroll) return;
        if (position == 0) {
            stopScroll();
            setSelectedPosition(0);
            pinTopRow();
            return;
        }
        super.scrollToPosition(position);
    }

    @Override
    public void smoothScrollToPosition(int position) {
        if (freeScroll) return;
        if (position == 0) {
            scrollToPosition(0);
            return;
        }
        super.smoothScrollToPosition(position);
    }

    @Override
    public boolean onRequestFocusInDescendants(int direction, Rect previouslyFocusedRect) {
        // Keep whatever row the user wheeled/dragged to. Jumping to 0 here made a click
        // after scrolling yank the list back to the top.
        if (getAdapter() != null && getAdapter().getItemCount() > 0 && getSelectedPosition() == NO_POSITION) {
            setSelectedPosition(0);
        }
        return super.onRequestFocusInDescendants(direction, previouslyFocusedRect);
    }

    private boolean isWheelEvent(MotionEvent event) {
        return event.getAction() == MotionEvent.ACTION_SCROLL
                && event.isFromSource(InputDevice.SOURCE_CLASS_POINTER);
    }

    private boolean handleWheel(MotionEvent event) {
        float scroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
        if (scroll == 0f) return false;
        beginFreeScroll();
        // Fractional ticks accumulate so slow wheels still move one row-sized step.
        wheelCarry += -scroll * ResUtil.dp2px(48);
        int dy = (int) wheelCarry;
        if (dy != 0) {
            wheelCarry -= dy;
            scrollBy(0, dy);
            syncSelectionToVisible();
        }
        endFreeScrollSoon();
        return true;
    }

    private boolean isPointerFreeScrollSource(MotionEvent e) {
        return e.isFromSource(InputDevice.SOURCE_CLASS_POINTER)
                && (e.isFromSource(InputDevice.SOURCE_MOUSE) || e.isFromSource(InputDevice.SOURCE_STYLUS));
    }

    private void trackPointer(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                stopScroll();
                removeCallbacks(endFreeScroll);
                pointerDragged = false;
                downX = e.getX();
                downY = e.getY();
                break;
            case MotionEvent.ACTION_MOVE:
                if (!pointerDragged) {
                    float slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
                    if (Math.hypot(e.getX() - downX, e.getY() - downY) < slop) break;
                    pointerDragged = true;
                }
                beginFreeScroll();
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (pointerDragged) {
                    syncSelectionToVisible();
                    endFreeScrollSoon();
                } else {
                    freeScroll = false;
                    removeCallbacks(endFreeScroll);
                }
                pointerDragged = false;
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
        postDelayed(endFreeScroll, WHEEL_IDLE_MS);
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

    private void pinTopRow() {
        if (freeScroll || pinning || getSelectedPosition() != 0) return;
        stopScroll();
        int offset = computeVerticalScrollOffset();
        if (offset == 0) return;
        pinning = true;
        try {
            scrollBy(0, -offset);
        } finally {
            pinning = false;
        }
    }
}
