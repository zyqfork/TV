package com.fongmi.android.tv.ui.home;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.bean.Func;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.storage.NetworkStorage;
import com.fongmi.android.tv.storage.NetworkStorageStore;

import java.util.ArrayList;
import java.util.List;

/**
 * The home screen's row of app-level destinations.
 *
 * Both flavours build it from here. Before this existed each flavour had its own copy, which is
 * how the phone ended up without 网盘 / 媒体库 / 推送 — the TV's list grew and the phone's did not.
 * Adding a destination now means editing one method.
 */
public final class HomeFuncs {

    private HomeFuncs() {
    }

    public static List<Func> create() {
        List<Func> items = new ArrayList<>();
        items.add(Func.create(R.string.home_vod));
        if (LiveConfig.hasUrl()) items.add(Func.create(R.string.home_live));
        items.add(Func.create(R.string.home_search));
        items.add(Func.create(R.string.home_keep));
        items.add(Func.create(R.string.home_push));
        NetworkStorage home = NetworkStorageStore.getHome();
        if (home != null) items.add(Func.create(R.string.home_network_storage, null, home.getId()));
        if (Setting.isDlnaLibrary()) items.add(Func.create(R.string.home_media_library));
        items.add(Func.create(R.string.home_setting));
        return items;
    }

    /** Identity plus label, so a resume can skip rebuilding an unchanged entry row. */
    public static boolean same(List<Func> left, List<Func> right) {
        if (left == null || right == null || left.size() != right.size()) return false;
        for (int i = 0; i < left.size(); i++) if (!left.get(i).isSameContent(right.get(i))) return false;
        return true;
    }
}
