package com.fongmi.android.tv.utils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Device;
import com.fongmi.android.tv.server.Server;
import com.github.catvod.net.OkHttp;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import okhttp3.OkHttpClient;
import okhttp3.Response;

/** One bounded scan owner. Network workers never publish into a newer scan. */
public class ScanTask {
    private static final int CONNECT_MS = 250;
    private static final int CALL_MS = 400;
    private static final int PROBES = 48;
    private final Object gate = new Object();
    private final String callTag = "scan-" + UUID.randomUUID();
    private final List<Future<?>> futures = new ArrayList<>();
    private final OkHttpClient client;
    private int generation;
    private ExecutorService pool;
    private Listener listener;

    public ScanTask(Listener listener) {
        client = OkHttp.client(CONNECT_MS).newBuilder().callTimeout(CALL_MS, TimeUnit.MILLISECONDS).build();
        this.listener = listener;
    }

    public void start() { startBatch(null); }
    public void start(String url) { startBatch(url == null || url.isBlank() ? List.of() : List.of(url)); }

    private void startBatch(List<String> urls) {
        final int gen;
        synchronized (gate) {
            if (listener == null) return;
            gen = ++generation;
            cancelProbes();
        }
        Task.execute(() -> run(urls == null ? getUrl() : urls, gen));
    }

    public void stop() {
        synchronized (gate) {
            listener = null;
            generation++;
            cancelProbes();
        }
    }

    private void cancelProbes() {
        OkHttp.cancel(client, callTag);
        futures.forEach(f -> f.cancel(true));
        futures.clear();
        if (pool != null) pool.shutdownNow();
        pool = null;
    }

    private void run(List<String> urls, int gen) {
        List<Future<?>> batch = new ArrayList<>();
        synchronized (gate) {
            // Registration and cancellation share the same ownership boundary.
            if (generation != gen || listener == null) return;
            if (!urls.isEmpty()) {
                pool = Executors.newFixedThreadPool(Math.min(PROBES, urls.size()));
                for (String url : urls) {
                    Future<?> item = pool.submit(() -> findDevice(url, gen));
                    futures.add(item);
                    batch.add(item);
                }
                pool.shutdown();
            }
        }
        for (Future<?> item : batch) {
            synchronized (gate) { if (generation != gen) return; }
            try { item.get(CALL_MS + 100L, TimeUnit.MILLISECONDS); }
            catch (Exception ignored) { item.cancel(true); }
        }
        App.post(() -> {
            synchronized (gate) {
                if (generation == gen && listener != null) listener.onFinished();
            }
        });
    }

    private List<String> getUrl() {
        String local = Server.get().getAddress();
        String base = local.substring(0, local.lastIndexOf('.') + 1);
        return IntStream.range(1, 256).mapToObj(i -> base + i + ":9978").toList();
    }

    private void findDevice(String url, int gen) {
        if (url.equals(Server.get().getAddress())) return;
        synchronized (gate) { if (generation != gen || listener == null) return; }
        try (Response response = OkHttp.newCall(client, url.concat("/device"), callTag).execute()) {
            if (!response.isSuccessful() || response.body() == null) return;
            Device device = Device.objectFrom(response.body().string());
            if (device != null) App.post(() -> {
                synchronized (gate) {
                    if (generation == gen && listener != null) listener.onFind(device.save());
                }
            });
        } catch (Exception ignored) { }
    }

    public interface Listener {
        void onFind(Device device);
        default void onFinished() { }
    }
}
