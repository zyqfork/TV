package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.os.Build;
import android.util.AttributeSet;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewTreeObserver;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/** Native list scrolling and explicit focus operations shared by both product flavours. */
public class PointerRecyclerView extends RecyclerView {

    private final int touchSlop;
    private float downX;
    private float downY;
    private boolean mouseGesture;

    public PointerRecyclerView(@NonNull Context context) {
        this(context, null);
    }

    public PointerRecyclerView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public PointerRecyclerView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setOverScrollMode(OVER_SCROLL_NEVER);
        setFocusable(true);
        setFocusableInTouchMode(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) setDefaultFocusHighlightEnabled(false);
        // Recovering an old off-screen focus after a data update must not undo mouse scrolling.
        // D-pad entry explicitly focuses a visible item instead.
        setPreserveFocusAfterLayout(false);
    }

    public boolean isMouseGesture() {
        return mouseGesture;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getRawX();
                downY = event.getRawY();
                mouseGesture = event.isFromSource(InputDevice.SOURCE_MOUSE);
                break;
            case MotionEvent.ACTION_MOVE:
                LayoutManager layout = getLayoutManager();
                float dx = Math.abs(event.getRawX() - downX);
                float dy = Math.abs(event.getRawY() - downY);
                if (event.getPointerCount() == 1 && layout != null && dy > touchSlop && dy > dx) {
                    if (layout.canScrollVertically()) {
                        // Clear a horizontal child's disallow-intercept flag before native
                        // RecyclerView sees this MOVE. Its normal intercept path cancels the child.
                        requestDisallowInterceptTouchEvent(false);
                    } else if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(false);
                    }
                }
                break;
            default:
                break;
        }
        boolean handled = super.dispatchTouchEvent(event);
        if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            if (mouseGesture) trace("release", 0);
            mouseGesture = false;
        }
        return handled;
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        LayoutManager layout = getLayoutManager();
        if (layout != null && layout.canScrollVertically()
                && event.getActionMasked() == MotionEvent.ACTION_SCROLL
                && event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)
                && event.getAxisValue(MotionEvent.AXIS_VSCROLL) != 0f) {
            // Route vertical wheel input to the vertical list, not the horizontal row below it.
            // RecyclerView owns the scroll factor, bounds and nested scrolling.
            super.onGenericMotionEvent(event);
            return true;
        }
        return super.dispatchGenericMotionEvent(event);
    }

    @Override
    public boolean fling(int velocityX, int velocityY) {
        // Keep desktop-style mouse dragging under the pointer; finger touch retains native fling.
        return !mouseGesture && super.fling(velocityX, velocityY);
    }

    @Override
    public void onScrolled(int dx, int dy) {
        super.onScrolled(dx, dy);
        trace(dy == 0 ? "layout" : "scroll", dy);
    }

    private void trace(String action, int dy) {
        if (!Log.isLoggable("HomePointer", Log.DEBUG)
                || !(getLayoutManager() instanceof LinearLayoutManager layout) || !layout.canScrollVertically()) return;
        int position = layout.findFirstVisibleItemPosition();
        View first = layout.findViewByPosition(position);
        Log.d("HomePointer", action + " dy=" + dy + " first=" + position + "@"
                + (first == null ? 0 : first.getTop()) + " atTop=" + isAtTop());
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && isNavigationKey(event.getKeyCode())) {
            View focus = findFocus();
            View item = focus == null ? null : findContainingItemView(focus);
            LayoutManager layout = getLayoutManager();
            boolean outside = item == null || (layout != null && layout.canScrollHorizontally()
                    ? item.getRight() <= getPaddingLeft() || item.getLeft() >= getWidth() - getPaddingRight()
                    : item.getBottom() <= getPaddingTop() || item.getTop() >= getHeight() - getPaddingBottom());
            if (outside) {
                if (focusVisibleItem()) return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private boolean isNavigationKey(int code) {
        return code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;
    }

    public int getFocusedPosition() {
        View focus = findFocus();
        View item = focus == null ? null : findContainingItemView(focus);
        if (item != null) return getChildAdapterPosition(item);
        return getLayoutManager() instanceof LinearLayoutManager layout ? layout.findFirstVisibleItemPosition() : NO_POSITION;
    }

    public boolean focusPosition(int position) {
        ViewHolder holder = findViewHolderForAdapterPosition(position);
        if (holder == null) return false;
        if (holder.itemView instanceof PointerRecyclerView row) return row.focusVisibleItem();
        // Explicit remote/back/action restoration must also work after mouse input left
        // the window in touch mode. Ordinary pointer scrolling never calls this helper.
        return holder.itemView.requestFocusFromTouch();
    }

    public void scrollToPositionAndFocus(int position) {
        if (getAdapter() == null || getAdapter().getItemCount() == 0) return;
        int target = Math.max(0, Math.min(position, getAdapter().getItemCount() - 1));
        scrollToPosition(target);
        afterNextLayout(() -> focusPosition(target));
    }

    public boolean focusVisibleItem() {
        if (!(getLayoutManager() instanceof LinearLayoutManager layout)) return false;
        int start = layout.findFirstCompletelyVisibleItemPosition();
        if (start == NO_POSITION) start = layout.findFirstVisibleItemPosition();
        int last = layout.findLastVisibleItemPosition();
        for (int position = Math.max(0, start); position <= last; position++) {
            if (focusPosition(position)) return true;
        }
        return false;
    }

    public boolean isAtTop() {
        if (!(getLayoutManager() instanceof LinearLayoutManager layout)) return true;
        View first = layout.findViewByPosition(0);
        return first != null && first.getTop() >= getPaddingTop();
    }

    /** Run after the requested layout, not in postOnAnimation's pre-traversal phase. */
    public void afterNextLayout(Runnable action) {
        ViewTreeObserver observer = getViewTreeObserver();
        observer.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                if (observer.isAlive()) observer.removeOnPreDrawListener(this);
                else getViewTreeObserver().removeOnPreDrawListener(this);
                action.run();
                return true;
            }
        });
    }

    public void scrollToTop() {
        stopScroll();
        if (getLayoutManager() instanceof LinearLayoutManager layout) layout.scrollToPositionWithOffset(0, 0);
    }
}
