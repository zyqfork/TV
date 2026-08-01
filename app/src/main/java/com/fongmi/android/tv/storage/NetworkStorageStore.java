package com.fongmi.android.tv.storage;

import android.text.TextUtils;

import com.github.catvod.utils.Prefers;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public class NetworkStorageStore {

    private static final String KEY = "network_storages";
    private static final String KEY_HOME = "network_storage_home";
    private static final Gson GSON = new Gson();
    private static final Type LIST_TYPE = new TypeToken<List<NetworkStorage>>() {
    }.getType();

    public static List<NetworkStorage> getAll() {
        String json = Prefers.getString(KEY, "[]");
        try {
            List<NetworkStorage> list = GSON.fromJson(json, LIST_TYPE);
            return list == null ? new ArrayList<>() : new ArrayList<>(list);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public static NetworkStorage find(String id) {
        if (TextUtils.isEmpty(id)) return null;
        for (NetworkStorage item : getAll()) {
            if (id.equals(item.getId())) return item;
        }
        return null;
    }

    public static NetworkStorage getHome() {
        return find(getHomeId());
    }

    public static String getHomeId() {
        return Prefers.getString(KEY_HOME);
    }

    public static boolean isHome(String id) {
        return !TextUtils.isEmpty(id) && id.equals(getHomeId());
    }

    public static void setHome(String id) {
        if (TextUtils.isEmpty(id)) Prefers.remove(KEY_HOME);
        else Prefers.put(KEY_HOME, id);
    }

    public static void clearHomeIf(String id) {
        if (isHome(id)) Prefers.remove(KEY_HOME);
    }

    public static void save(NetworkStorage item) {
        if (item == null || TextUtils.isEmpty(item.getId())) return;
        List<NetworkStorage> list = getAll();
        boolean found = false;
        for (int i = 0; i < list.size(); i++) {
            if (item.getId().equals(list.get(i).getId())) {
                list.set(i, item);
                found = true;
                break;
            }
        }
        if (!found) list.add(item);
        Prefers.put(KEY, GSON.toJson(list));
    }

    public static void delete(String id) {
        if (TextUtils.isEmpty(id)) return;
        List<NetworkStorage> list = getAll();
        list.removeIf(item -> id.equals(item.getId()));
        Prefers.put(KEY, GSON.toJson(list));
        clearHomeIf(id);
    }
}
