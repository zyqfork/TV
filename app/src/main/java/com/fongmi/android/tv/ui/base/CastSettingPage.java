package com.fongmi.android.tv.ui.base;

import android.view.View;

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
}
