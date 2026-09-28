package com.fongmi.android.tv.storage;

import android.text.TextUtils;

import com.github.catvod.utils.Prefers;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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

    public static synchronized List<NetworkStorage> getAll() {
        String json = Prefers.getString(KEY, "[]");
        try {
            List<NetworkStorage> parsed = GSON.fromJson(json, LIST_TYPE);
            List<NetworkStorage> list = parsed == null ? new ArrayList<>() : new ArrayList<>(parsed);
            boolean migrated = false;
            try {
                migrated = migratePlaintextCredentials(json, list);
            } catch (Exception ignored) {
            }
            // Drop anonymous stubs left by older add/edit flows (no id and no host).
            // They render as a bare "smb://" tile and cannot be deleted by id.
            int before = list.size();
            list.removeIf(item -> item == null
                    || (TextUtils.isEmpty(item.getId()) && TextUtils.isEmpty(item.getHost())));
            if (list.size() != before) migrated = true;
            for (NetworkStorage item : list) hydrateCredentials(item);
            if (migrated) Prefers.put(KEY, GSON.toJson(list));
            return list;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private static boolean migratePlaintextCredentials(String json, List<NetworkStorage> list) {
        JsonElement root = JsonParser.parseString(json);
        if (!root.isJsonArray()) return false;
        JsonArray values = root.getAsJsonArray();
        boolean migrated = false;
        for (int i = 0; i < values.size() && i < list.size(); i++) {
            JsonObject object = values.get(i).isJsonObject() ? values.get(i).getAsJsonObject() : null;
            if (object == null || (!object.has("username") && !object.has("password"))) continue;
            String username = stringValue(object.get("username"));
            String password = stringValue(object.get("password"));
            NetworkStorage item = list.get(i);
            // put() is a no-op for an entry without an id, so rewriting the list afterwards would
            // drop the plaintext copy (username/password are transient) without having stored the
            // secret anywhere — the user's password would be gone for good. Leave those alone.
            if (TextUtils.isEmpty(item.getId())) continue;
            if (!TextUtils.isEmpty(username) || !TextUtils.isEmpty(password)) {
                NetworkCredentialStore.put(item.getId(), username, password);
            }
            migrated = true;
        }
        return migrated;
    }

    private static String stringValue(JsonElement value) {
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    private static void hydrateCredentials(NetworkStorage item) {
        NetworkCredentialStore.Credentials credentials = NetworkCredentialStore.get(item.getId());
        item.setUsername(credentials.username());
        item.setPassword(credentials.password());
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

    public static synchronized void save(NetworkStorage item) {
        if (item == null || TextUtils.isEmpty(item.getId())) return;
        if (!item.isValid()) return;
        List<NetworkStorage> list = getAll();
        int found = -1;
        for (int i = 0; i < list.size(); i++) {
            NetworkStorage old = list.get(i);
            if (item.getId().equals(old.getId()) || sameEndpoint(old, item)) {
                found = i;
                // Keep the original id so play URLs and credential keys stay stable.
                item.setId(old.getId());
                break;
            }
        }
        if (found >= 0) {
            // Collapse duplicates left by older add/edit flows as well; otherwise fixing save only
            // prevents new duplicates while the existing second tile remains visible forever.
            for (int i = list.size() - 1; i >= 0; i--) {
                if (i == found) continue;
                NetworkStorage duplicate = list.get(i);
                if (!item.getId().equals(duplicate.getId()) && !sameEndpoint(duplicate, item)) continue;
                list.remove(i);
                if (isHome(duplicate.getId())) setHome(item.getId());
                NetworkCredentialStore.remove(duplicate.getId());
                if (i < found) found--;
            }
            list.set(found, item);
        } else {
            list.add(item);
        }
        NetworkCredentialStore.put(item.getId(), item.getUsername(), item.getPassword());
        Prefers.put(KEY, GSON.toJson(list));
    }

    /** Same NAS share / WebDAV root counts as one storage, even if saved with a new UUID. */
    private static boolean sameEndpoint(NetworkStorage a, NetworkStorage b) {
        if (a == null || b == null) return false;
        if (!a.getType().equalsIgnoreCase(b.getType())) return false;
        if (!a.getHost().equalsIgnoreCase(b.getHost())) return false;
        if (a.getPort() != b.getPort()) return false;
        if (a.isSmb() && !a.getShare().equalsIgnoreCase(b.getShare())) return false;
        try {
            return NetworkPathPolicy.cleanRelative(a.getPath())
                    .equals(NetworkPathPolicy.cleanRelative(b.getPath()));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static synchronized void delete(String id) {
        // An empty id is not a target: callers delete a listed item, and an empty id used to be a
        // no-op. Sweeping every id-less entry from here deleted unrelated tiles by accident, so
        // legacy stubs go through delete(NetworkStorage) instead.
        if (TextUtils.isEmpty(id)) return;
        List<NetworkStorage> list = getAll();
        list.removeIf(item -> id.equals(item.getId()));
        Prefers.put(KEY, GSON.toJson(list));
        NetworkCredentialStore.remove(id);
        clearHomeIf(id);
    }

    /**
     * Deletes exactly one stored entry. Legacy entries saved before ids existed cannot be addressed
     * by id, so those are matched by endpoint; anything else would have to guess which tile the
     * user picked.
     */
    public static synchronized void delete(NetworkStorage target) {
        if (target == null) return;
        if (!TextUtils.isEmpty(target.getId())) {
            delete(target.getId());
            return;
        }
        List<NetworkStorage> list = getAll();
        for (int i = 0; i < list.size(); i++) {
            if (!sameEndpoint(list.get(i), target)) continue;
            // Remove the single tile the user picked. removeIf() took out every legacy entry that
            // happens to point at the same share, which is not what "delete this one" means.
            list.remove(i);
            Prefers.put(KEY, GSON.toJson(list));
            return;
        }
    }
}
