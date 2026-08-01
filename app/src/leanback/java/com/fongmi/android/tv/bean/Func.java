package com.fongmi.android.tv.bean;

import android.text.TextUtils;

import androidx.annotation.Nullable;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.impl.Diffable;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.Objects;

public class Func implements Diffable<Func> {

    private final int resId;
    private int drawable;
    private String text;
    private String id;

    public static Func create(int resId) {
        return new Func(resId);
    }

    public static Func create(int resId, String text, String id) {
        Func item = new Func(resId);
        item.text = text;
        item.id = id;
        return item;
    }

    public Func(int resId) {
        this.resId = resId;
        this.setDrawable();
    }

    public int getResId() {
        return resId;
    }

    public int getDrawable() {
        return drawable;
    }

    public String getId() {
        return id == null ? "" : id;
    }

    public String getText() {
        return TextUtils.isEmpty(text) ? ResUtil.getString(resId) : text;
    }

    public void setDrawable() {
        if (resId == R.string.home_vod) this.drawable = R.drawable.ic_home_vod;
        else if (resId == R.string.home_live) this.drawable = R.drawable.ic_home_live;
        else if (resId == R.string.home_keep) this.drawable = R.drawable.ic_home_keep;
        else if (resId == R.string.home_push) this.drawable = R.drawable.ic_home_push;
        else if (resId == R.string.home_search) this.drawable = R.drawable.ic_home_search;
        else if (resId == R.string.home_setting) this.drawable = R.drawable.ic_home_setting;
        else if (resId == R.string.home_network_storage) this.drawable = R.drawable.ic_home_network;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Func it)) return false;
        return getResId() == it.getResId() && Objects.equals(getId(), it.getId());
    }

    @Override
    public boolean isSameItem(Func other) {
        return equals(other);
    }

    @Override
    public boolean isSameContent(Func other) {
        return equals(other) && Objects.equals(getText(), other.getText());
    }
}
