package com.fongmi.android.tv.ui.base;

import android.os.Build;
import android.view.View;
import android.view.ViewGroup;

import androidx.appcompat.app.AppCompatActivity;

import com.fongmi.android.tv.utils.Util;
import com.google.android.material.appbar.MaterialToolbar;

/**
 * The cast settings pages (投屏设置 → DLNA 设置 / AirPlay 设置 / AirPlay 高级设置) are shared by both
 * flavours, but only the phone needs a title bar: a TV has no back button and is driven by a
 * remote, so its layouts never had one.
 *
 * The shared layouts ship the toolbar with {@code visibility="gone"}; this turns it on for the
 * phone only, so the TV layout stays byte-for-byte the same as it was.
 */
public final class CastSettingPage {

    private CastSettingPage() {
    }

    public static void showToolbar(AppCompatActivity activity, MaterialToolbar toolbar, int titleRes) {
        if (!Util.isMobile()) return;
        toolbar.setVisibility(View.VISIBLE);
        activity.setSupportActionBar(toolbar);
        activity.setTitle(titleRes);
    }

    /**
     * Put remote focus on the page's first row. The phone must not do this: the shared layouts
     * mark rows {@code focusableInTouchMode}, so {@code requestFocus()} would leave the row
     * focused and Android's default focus highlight would paint it as if it were selected.
     */
    public static void focusFirst(View row) {
        if (row == null) return;
        suppressDefaultFocusHighlight(row.getRootView());
        if (!Util.isMobile()) row.requestFocus();
    }

    /**
     * The TV flavour draws its own focus state via selector_item; the phone's ripple does not.
     * Without this, any focused row on the phone (the first row, or a row the user just tapped)
     * gets the platform highlight and looks permanently "on".
     */
    private static void suppressDefaultFocusHighlight(View view) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            view.setDefaultFocusHighlightEnabled(false);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) suppressDefaultFocusHighlight(group.getChildAt(i));
        }
    }
}
