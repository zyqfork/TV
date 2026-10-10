package com.fongmi.android.tv.db;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Keep;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.annotations.SerializedName;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

import okhttp3.Response;

/** Validated pull snapshot. A legacy asynchronous push acknowledgement is NOT a snapshot. */
public final class SyncSnapshot {
    private static final String PROTOCOL = "tv-sync-snapshot-v1";
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final int MAX_ROWS = 20_000;
    @SerializedName("protocol")
    private String protocol;
    @SerializedName("type")
    private String type;
    @SerializedName("url")
    private String url;
    @SerializedName("configs")
    private List<Config> configs;
    @SerializedName("history")
    private List<History> history;
    @SerializedName("keep")
    private List<Keep> keep;

    public static String export(String type, String requestedConfig) {
        AppDatabase db = AppDatabase.get();
        String[] result = new String[1];
        db.runInTransaction(() -> {
            SyncSnapshot snapshot = new SyncSnapshot();
            snapshot.protocol = PROTOCOL;
            snapshot.type = type;
            if ("history".equals(type)) {
                Config request = Config.objectFrom(requestedConfig);
                if (request == null || request.getUrl().isBlank()) throw new IllegalArgumentException("Missing sync source");
                Config source = db.getConfigDao().find(request.getUrl(), 0);
                if (source == null) throw new IllegalArgumentException("Sync source is not stored on the peer");
                snapshot.url = source.getUrl();
                snapshot.history = History.get(source.getId());
            } else if ("keep".equals(type)) {
                snapshot.keep = Keep.getVod();
                Set<Integer> cids = new HashSet<>();
                snapshot.keep.forEach(item -> cids.add(item.getCid()));
                snapshot.configs = Config.findUrls().stream().filter(config -> cids.contains(config.getId())).toList();
            } else throw new IllegalArgumentException("Unknown sync type");
            snapshot.validate();
            result[0] = App.gson().toJson(snapshot);
            if (result[0].getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new IllegalArgumentException("Sync snapshot too large");
        });
        return result[0];
    }

    public static void receive(Response response, String type, int cid, String sourceUrl,
                               BooleanSupplier active) throws IOException {
        if (!response.isSuccessful() || response.body() == null) throw new IOException("Sync HTTP " + response.code());
        String json;
        try (InputStream input = response.body().byteStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                requireActive(active);
                if (output.size() + count > MAX_BYTES) throw new IOException("Sync snapshot too large");
                output.write(buffer, 0, count);
            }
            json = output.toString(StandardCharsets.UTF_8.name());
        }
        final SyncSnapshot snapshot;
        try {
            JsonElement element = JsonParser.parseString(json);
            if (!element.isJsonObject()) throw new IllegalArgumentException("Missing sync snapshot");
            snapshot = App.gson().fromJson(element, SyncSnapshot.class);
            snapshot.validate();
            if (!type.equals(snapshot.type) || ("history".equals(type) && !sourceUrl.equals(snapshot.url))) {
                throw new IllegalArgumentException("Sync snapshot source/type mismatch");
            }
        } catch (RuntimeException invalid) {
            throw new ProtocolException(invalid);
        }
        AppDatabase db = AppDatabase.get();
        db.runInTransaction(() -> {
            requireActive(active);
            if ("history".equals(type)) {
                Config source = db.getConfigDao().findById(cid);
                if (source == null || !sourceUrl.equals(source.getUrl())) throw new IllegalStateException("Sync source changed");
                History.delete(cid);
                for (History target : snapshot.history) {
                    requireActive(active);
                    target.cid(cid).save();
                }
            } else {
                Keep.deleteAll(); // VOD only; live favourites are retained.
                Keep.sync(snapshot.configs, snapshot.keep);
            }
            // Cancellation, validation or insertion failures roll back the entire replacement.
            requireActive(active);
        });
    }

    public static final class ProtocolException extends IOException {
        private ProtocolException(Throwable cause) { super("Invalid or unsupported sync snapshot", cause); }
    }

    private static void requireActive(BooleanSupplier active) {
        if (!active.getAsBoolean()) throw new CancellationException("Sync request is no longer active");
    }

    private void validate() {
        if (!PROTOCOL.equals(protocol)) throw new IllegalArgumentException("Unsupported sync protocol");
        if ("history".equals(type)) {
            if (url == null || url.isBlank() || history == null || history.size() > MAX_ROWS) throw new IllegalArgumentException("Invalid history snapshot");
            validateHistory(history);
        } else if ("keep".equals(type)) {
            if (configs == null || keep == null || configs.size() > MAX_ROWS || keep.size() > MAX_ROWS) throw new IllegalArgumentException("Invalid keep snapshot");
            validateKeep(configs, keep);
        } else throw new IllegalArgumentException("Invalid sync type");
    }

    public static void requireArray(String json) {
        if (json == null || !JsonParser.parseString(json).isJsonArray()) throw new IllegalArgumentException("Missing sync array");
    }

    public static void validateHistory(List<History> history) {
        if (history == null || history.size() > MAX_ROWS) throw new IllegalArgumentException("Invalid sync history");
        Set<String> keys = new HashSet<>();
        int remoteCid = -1;
        for (History item : history) {
            if (item == null || !validKey(item.getKey(), item.getCid()) || item.getVodName() == null
                    || !keys.add(item.getKey())) throw new IllegalArgumentException("Invalid/duplicate sync history");
            if (remoteCid != -1 && remoteCid != item.getCid()) throw new IllegalArgumentException("Mixed history sources");
            remoteCid = item.getCid();
        }
    }

    public static void validateKeep(List<Config> configs, List<Keep> keep) {
        if (configs == null || keep == null || configs.size() > MAX_ROWS || keep.size() > MAX_ROWS) throw new IllegalArgumentException("Invalid keep snapshot");
        Map<Integer, Config> byId = new HashMap<>();
        Set<String> urls = new HashSet<>();
        Set<String> keys = new HashSet<>();
        for (Config config : configs) {
            if (config == null || config.getType() != 0 || config.getId() <= 0
                    || byId.putIfAbsent(config.getId(), config) != null) throw new IllegalArgumentException("Invalid sync config");
            // A legacy sender may include an unused empty default config.
            if (!config.getUrl().isBlank() && !urls.add(config.getUrl())) throw new IllegalArgumentException("Duplicate sync config URL");
        }
        for (Keep item : keep) {
            Config source = item == null ? null : byId.get(item.getCid());
            if (item == null || item.getType() != 0 || !validKey(item.getKey(), item.getCid())
                    || source == null || source.getUrl().isBlank() || !keys.add(item.getKey())) {
                throw new IllegalArgumentException("Invalid/orphan sync keep");
            }
        }
    }

    private static boolean validKey(String key, int cid) {
        if (key == null || cid <= 0 || !key.endsWith(AppDatabase.SYMBOL + cid)) return false;
        int first = key.indexOf(AppDatabase.SYMBOL);
        int last = key.lastIndexOf(AppDatabase.SYMBOL);
        return first > 0 && last > first + AppDatabase.SYMBOL.length();
    }
}
