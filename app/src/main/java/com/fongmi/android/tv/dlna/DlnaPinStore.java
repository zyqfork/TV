package com.fongmi.android.tv.dlna;

import android.text.TextUtils;

import com.github.catvod.utils.Prefers;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class DlnaPinStore {

    private static final String KEY = "dlna_pins";
    private static final Gson GSON = new Gson();
    private static final Type LIST_TYPE = new TypeToken<List<DlnaPin>>() {
    }.getType();

    public static List<DlnaPin> getAll() {
        String json = Prefers.getString(KEY, "[]");
        try {
            List<DlnaPin> list = GSON.fromJson(json, LIST_TYPE);
            return list == null ? new ArrayList<>() : new ArrayList<>(list);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public static boolean contains(String key) {
        if (TextUtils.isEmpty(key)) return false;
        for (DlnaPin pin : getAll()) {
            if (key.equals(pin.getKey())) return true;
        }
        return false;
    }

    public static boolean contains(DlnaPin pin) {
        return pin != null && contains(pin.getKey());
    }

    public static void add(DlnaPin pin) {
        if (pin == null || TextUtils.isEmpty(pin.getUuid())) return;
        List<DlnaPin> list = getAll();
        for (int i = 0; i < list.size(); i++) {
            if (pin.getKey().equals(list.get(i).getKey())) {
                list.set(i, pin);
                Prefers.put(KEY, GSON.toJson(list));
                return;
            }
        }
        list.add(0, pin);
        Prefers.put(KEY, GSON.toJson(list));
    }

    public static void remove(String key) {
        if (TextUtils.isEmpty(key)) return;
        List<DlnaPin> list = getAll();
        Iterator<DlnaPin> it = list.iterator();
        while (it.hasNext()) {
            if (key.equals(it.next().getKey())) it.remove();
        }
        Prefers.put(KEY, GSON.toJson(list));
    }

    public static void remove(DlnaPin pin) {
        if (pin != null) remove(pin.getKey());
    }

    public static boolean toggle(DlnaPin pin) {
        if (pin == null) return false;
        if (contains(pin)) {
            remove(pin);
            return false;
        }
        add(pin);
        return true;
    }
}
