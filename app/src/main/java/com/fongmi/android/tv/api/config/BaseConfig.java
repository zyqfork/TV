package com.fongmi.android.tv.api.config;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Task;
import com.fongmi.android.tv.utils.UrlUtil;
import com.github.catvod.bean.Header;
import com.github.catvod.bean.Proxy;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

abstract class BaseConfig {

    public static final int VOD = 0;
    public static final int LIVE = 1;
    public static final int WALL = 2;

    private final AtomicInteger taskId = new AtomicInteger(0);

    protected boolean sync;
    protected volatile Config config;
    private volatile Future<?> future;

    protected abstract String getTag();

    protected abstract Config defaultConfig();

    protected abstract void load(Config config) throws Throwable;

    protected abstract boolean isLoaded();

    public synchronized void ensureLoaded() {
        try {
            if (isLoaded()) return;
            if (config == null) config = defaultConfig();
            Server.get().start();
            loadWithSavedConfig(config, !Setting.isAutoSourceRefresh());
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    protected void postEvent() {
        ConfigEvent.common();
    }

    public boolean needSync(String url) {
        return sync || config == null || TextUtils.isEmpty(config.getUrl()) || url.equals(config.getUrl());
    }

    public Config getConfig() {
        return config == null ? defaultConfig() : config;
    }

    protected void setHeaders(List<Header> headers) {
        OkHttp.responseInterceptor().addAll(headers);
    }

    protected void setProxy(List<Proxy> proxy) {
        OkHttp.selector().addAll(proxy);
    }

    protected void setHosts(List<String> hosts) {
        OkHttp.dns().addAll(hosts);
    }

    /** Only startup obeys the refresh preference; explicit imports/refresh always fetch. */
    public void loadOnStartup(Callback callback) {
        scheduleLoad(callback, !Setting.isAutoSourceRefresh());
    }

    public void load(Callback callback) {
        scheduleLoad(callback, false);
    }

    private void scheduleLoad(Callback callback, boolean preferSaved) {
        int id = taskId.incrementAndGet();
        if (future != null && !future.isDone()) future.cancel(true);
        Config requested = config;
        future = Task.submit(() -> loadConfig(id, requested, callback, preferSaved));
        callback.start();
    }

    protected void loadConfig(int id, Config config, Callback callback) {
        loadConfig(id, config, callback, false);
    }

    /** Subclasses restore validated configuration snapshots stored in the existing Config table. */
    protected boolean loadSaved(Config config) throws Throwable {
        return false;
    }

    private boolean loadWithSavedConfig(Config config, boolean preferSaved) throws Throwable {
        if (preferSaved && config != null && !TextUtils.isEmpty(config.getJson())) {
            try {
                if (loadSaved(config)) return true;
            } catch (Throwable e) {
                if (isCanceled(e)) throw e;
                // Bad/missing snapshots may fetch once; never replace a good snapshot on failure.
                e.printStackTrace();
            }
        }
        load(config);
        return false;
    }

    private void loadConfig(int id, Config config, Callback callback, boolean preferSaved) {
        try {
            Server.get().start();
            OkHttp.cancel(getTag());
            boolean restored = loadWithSavedConfig(config, preferSaved);
            if (taskId.get() != id) return;
            if (config.equals(this.config)) {
                if (restored) config.save();
                else config.update();
            }
            App.post(() -> {
                if (taskId.get() != id) return;
                Notify.show(config.getNotice());
                callback.success();
            });
        } catch (Throwable e) {
            e.printStackTrace();
            if (isCanceled(e)) return;
            if (taskId.get() != id) return;
            String error = TextUtils.isEmpty(config.getUrl()) ? "" : Notify.getError(R.string.error_config_get, e);
            App.post(() -> { if (taskId.get() == id) callback.error(error); });
        } finally {
            if (taskId.get() == id) postEvent();
        }
    }

    protected boolean isCanceled(Throwable e) {
        return ConfigLoadCancellation.isCanceled(e);
    }

    protected JsonArray fetchArray(JsonObject object, String key) {
        if (!object.has(key)) return new JsonArray();
        JsonElement element = object.get(key);
        if (element.isJsonObject()) return new JsonArray();
        if (element.isJsonPrimitive()) element = fetch(element.getAsString());
        JsonArray result = new JsonArray();
        for (JsonElement item : element.getAsJsonArray()) {
            if (item.isJsonPrimitive()) result.addAll(fetch(item.getAsString()));
            else if (item.isJsonObject()) result.add(item);
        }
        // Persist expanded external lists so saved startup does not refetch them.
        object.add(key, result);
        return result;
    }

    private JsonArray fetch(String url) {
        try {
            JsonElement parsed = Json.parse(OkHttp.string(UrlUtil.convert(url)));
            return parsed.isJsonArray() ? parsed.getAsJsonArray() : new JsonArray();
        } catch (Exception e) {
            return new JsonArray();
        }
    }
}
