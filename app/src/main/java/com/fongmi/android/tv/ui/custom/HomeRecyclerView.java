package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/** Pointer scrolling stays native. TV vertical navigation follows logical rows, not screen distance. */
public class HomeRecyclerView extends PointerRecyclerView {
    private boolean remoteScrollEnabled;
    private boolean remoteViewport;
    private boolean placingFocus;
    private float focusX;
    private int lastPosition = NO_POSITION;
    private int pendingGroup = -1;
    private int pendingDirection;

    public HomeRecyclerView(@NonNull Context context) { super(context); }
    public HomeRecyclerView(@NonNull Context context, @Nullable AttributeSet attrs) { super(context, attrs); }
    public HomeRecyclerView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); }

    public void setRemoteScrollEnabled(boolean enabled) { remoteScrollEnabled = enabled; }
    public boolean isRemoteViewport() { return remoteViewport; }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (remoteScrollEnabled && (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && !event.isCanceled()) navigateVertical(code);
            return true;
        }
        if (remoteScrollEnabled && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            View focused = findFocus();
            if (focused != null && focused == findContainingItemView(focused) && focused.isClickable()) {
                // A grid row does not wrap into another row at its short/right edge. Headings
                // stay put; nested horizontal histories/functions and the source keep native keys.
                if (event.getAction() == KeyEvent.ACTION_DOWN) navigateHorizontal(code, focused);
                return true;
            }
        }
        if (remoteScrollEnabled && event.getAction() == KeyEvent.ACTION_DOWN && isActivation(code)) {
            // Focus recovery must deliver THIS key down and its key up to the same real control.
            // Never leave OK on the RecyclerView or spend a click merely moving the highlight.
            View focused = findFocus();
            if (focused == null || focused == this || !isVisibleControl(focused)) recoverFocus();
        }
        boolean handled = super.dispatchKeyEvent(event);
        if (remoteScrollEnabled && event.getAction() == KeyEvent.ACTION_DOWN
                && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            pendingGroup = -1;
            rememberFocus();
        }
        return handled;
    }

    private void navigateHorizontal(int code, View focused) {
        int position = positionOf(focused);
        int target = position + (code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
        if (group(position) < 0 || group(target) != group(position)) return;
        ViewHolder holder = findViewHolderForAdapterPosition(target);
        if (holder == null || !holder.itemView.isFocusable() || !isVisibleControl(holder.itemView)) return;
        placeFocus(holder.itemView);
        pendingGroup = -1;
        rememberFocus();
    }

    private static boolean isActivation(int code) {
        return code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    private int positionOf(View view) {
        View item = view == null ? null : findContainingItemView(view);
        return item == null ? NO_POSITION : getChildAdapterPosition(item);
    }

    private int group(int position) {
        if (!(getLayoutManager() instanceof GridLayoutManager layout) || getAdapter() == null
                || position < 0 || position >= getAdapter().getItemCount()) return -1;
        return layout.getSpanSizeLookup().getSpanGroupIndex(position, layout.getSpanCount());
    }

    private void rememberFocus() {
        View focused = findFocus();
        int position = positionOf(focused);
        if (position != NO_POSITION && focused != this) {
            Rect rect = bounds(focused);
            focusX = rect.exactCenterX();
            lastPosition = position;
        } else if (!remoteViewport) focusX = getWidth() / 2f;
    }

    private Rect bounds(View view) {
        Rect rect = new Rect();
        view.getDrawingRect(rect);
        offsetDescendantRectToMyCoords(view, rect);
        return rect;
    }

    private boolean isVisibleControl(View view) {
        if (!view.isShown() || view == this || view instanceof RecyclerView) return false;
        Rect rect = bounds(view);
        return rect.bottom > getPaddingTop() && rect.top < getHeight() - getPaddingBottom()
                && rect.right > getPaddingLeft() && rect.left < getWidth() - getPaddingRight();
    }

    private void navigateVertical(int code) {
        if (!(getLayoutManager() instanceof GridLayoutManager) || getAdapter() == null
                || getAdapter().getItemCount() == 0) return;
        int direction = code == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1;
        if (!remoteViewport) { rememberFocus(); pendingGroup = -1; }
        remoteViewport = true;
        stopScroll();
        int position = positionOf(findFocus());
        if (position != NO_POSITION) lastPosition = position;
        int current = group(lastPosition);
        int lastGroup = group(getAdapter().getItemCount() - 1);
        if (pendingGroup < 0 || pendingDirection != direction || pendingGroup > lastGroup) {
            pendingGroup = Math.max(0, Math.min(lastGroup, current < 0 ? 0 : current + direction));
            pendingDirection = direction;
        }
        // All presses/repeats have a bounded viewport step; focus never item-aligns the list.
        int step = Math.max(1, Math.round(80 * getResources().getDisplayMetrics().density));
        scrollBy(0, direction * step);
        // Group ordering wins over X distance: a far-right history item can always reach the
        // left-aligned headings/title above; an incomplete final grid row clamps to its last card.
        View target = bestInGroup(pendingGroup);
        if (target != null) {
            placeFocus(target);
            lastPosition = positionOf(target);
            pendingGroup = -1;
        } else if (!canScrollVertically(direction)) {
            // Non-focusable loading/empty rows must not strand focus at either boundary.
            int boundary = direction < 0 ? 0 : lastGroup;
            for (int g = boundary; g >= 0 && g <= lastGroup; g -= direction) {
                if ((target = bestInGroup(g)) != null) { placeFocus(target); lastPosition = positionOf(target); break; }
            }
            pendingGroup = -1;
        }
    }

    @Nullable private View bestInGroup(int wanted) {
        View best = null;
        double score = Double.MAX_VALUE;
        for (View view : getFocusables(View.FOCUS_FORWARD)) {
            if (!isVisibleControl(view) || group(positionOf(view)) != wanted) continue;
            Rect rect = bounds(view);
            // Do not highlight a sliver of a poster. At a boundary a tall item may not fit.
            int visible = Math.min(rect.bottom, getHeight() - getPaddingBottom()) - Math.max(rect.top, getPaddingTop());
            if (visible < Math.min(rect.height(), Math.round(48 * getResources().getDisplayMetrics().density))) continue;
            double value = Math.abs(rect.exactCenterX() - focusX);
            if (value < score) { score = value; best = view; }
        }
        return best;
    }

    private void recoverFocus() {
        View target = bestInGroup(group(lastPosition));
        if (target == null) {
            int firstGroup = Integer.MAX_VALUE;
            for (View view : getFocusables(View.FOCUS_FORWARD)) {
                if (!isVisibleControl(view)) continue;
                int g = group(positionOf(view));
                if (g >= 0 && g < firstGroup) { firstGroup = g; target = view; }
            }
        }
        if (target != null) placeFocus(target);
    }

    private void placeFocus(View view) {
        placingFocus = true;
        try { view.requestFocusFromTouch(); }
        finally { placingFocus = false; }
    }

    @Override public boolean requestChildRectangleOnScreen(View child, Rect rect, boolean immediate) {
        if (remoteViewport || placingFocus) return false;
        return super.requestChildRectangleOnScreen(child, rect, immediate);
    }

    private void leaveRemoteMode() { remoteViewport = false; pendingGroup = -1; lastPosition = NO_POSITION; }
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) leaveRemoteMode();
        return super.dispatchTouchEvent(event);
    }
    @Override public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_SCROLL) leaveRemoteMode();
        return super.dispatchGenericMotionEvent(event);
    }
    @Override public void scrollToTop() { leaveRemoteMode(); super.scrollToTop(); }
}
