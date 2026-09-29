package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.leanback.widget.VerticalGridView;

import com.fongmi.android.tv.utils.ResUtil;

/**
 * Home list for the TV. D-pad navigation stays Leanback-native; mouse wheel scrolls the list
 * the way a NestedScrollView does — just move by the tick, no selection re-alignment.
 *
 * WINDOW_ALIGN_NO_EDGE is the important bit: Leanback's default LOW_EDGE alignment pulls the
 * selected row back into view on every layout, which is the wheel "jump".
 */
public class HomeGridView extends VerticalGridView {

    public HomeGridView(@NonNull Context context) {
        this(context, null);
    }

    public HomeGridView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public HomeGridView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        setWindowAlignment(WINDOW_ALIGN_NO_EDGE);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        // Row HorizontalGridViews swallow vertical wheel ticks; handle them on the home list.
        if (isWheelEvent(event) && handleWheel(event)) return true;
        return super.dispatchGenericMotionEvent(event);
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (isWheelEvent(event) && handleWheel(event)) return true;
        return super.onGenericMotionEvent(event);
    }

    @Override
    public void scrollToPosition(int position) {
        if (position == 0) {
            stopScroll();
            setSelectedPosition(0);
            return;
        }
        super.scrollToPosition(position);
    }

    @Override
    public boolean onRequestFocusInDescendants(int direction, Rect previouslyFocusedRect) {
        // First D-pad into the list starts on the top row; later focus moves keep the current one.
        if (direction == View.FOCUS_DOWN || direction == View.FOCUS_FORWARD) {
            if (getAdapter() != null && getAdapter().getItemCount() > 0 && getSelectedPosition() == NO_POSITION) {
                setSelectedPosition(0);
            }
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
        scrollBy(0, Math.round(-scroll * ResUtil.dp2px(48)));
        return true;
    }
}
