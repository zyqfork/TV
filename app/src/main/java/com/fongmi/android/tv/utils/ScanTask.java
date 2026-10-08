package com.fongmi.android.tv.utils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Device;
import com.fongmi.android.tv.server.Server;
import com.github.catvod.net.OkHttp;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import okhttp3.OkHttpClient;
import okhttp3.Response;

public class ScanTask {

    private static final int CONNECT_MS = 250;
    private static final int CALL_MS = 400;
    private static final int PROBES = 48;

    private final CopyOnWriteArrayList<Future<?>> future;
    private final OkHttpClient client;
    private final AtomicInteger generation = new AtomicInteger();
    private final AtomicReference<ExecutorService> pool = new AtomicReference<>();
    private Listener listener;

    public ScanTask(Listener listener) {
        this.client = OkHttp.client(CONNECT_MS).newBuilder().callTimeout(CALL_MS, TimeUnit.MILLISECONDS).build();
        this.future = new CopyOnWriteArrayList<>();
        this.listener = listener;
    }

    public void start() {
        int gen = generation.incrementAndGet();
        cancelProbes();
        Task.execute(() -> run(getUrl(), gen));
    }

    public void start(String url) {
        int gen = generation.incrementAndGet();
        cancelProbes();
        Task.execute(() -> run(List.of(url), gen));
    }

    public void stop() {
        listener = null;
        generation.incrementAndGet();
        cancelProbes();
    }

    private void cancelProbes() {
        OkHttp.cancel(client, "scan");
        future.forEach(f -> f.cancel(true));
        future.clear();
        ExecutorService previous = pool.getAndSet(null);
        if (previous != null) previous.shutdownNow();
    }

    private void run(List<String> urls, int gen) {
        if (generation.get() != gen) return;
        if (urls.isEmpty()) {
            finish(gen);
            return;
        }
        int threads = Math.min(PROBES, urls.size());
        ExecutorService local = Executors.newFixedThreadPool(threads);
        ExecutorService replaced = pool.getAndSet(local);
        if (replaced != null) replaced.shutdownNow();
        if (generation.get() != gen) {
            local.shutdownNow();
            return;
        }
        List<Future<?>> batch = new ArrayList<>();
        try {
            for (String url : urls) {
                if (generation.get() != gen || local.isShutdown()) return;
                Future<?> item = local.submit(() -> findDevice(url));
                future.add(item);
                batch.add(item);
            }
        } catch (RejectedExecutionException ignored) {
            local.shutdownNow();
        } finally {
            local.shutdown();
        }
        for (Future<?> item : batch) {
            if (generation.get() != gen) return;
            try {
                item.get(CALL_MS + 100L, TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {
            }
        }
        finish(gen);
    }

    private void finish(int gen) {
        if (generation.get() != gen) return;
        App.post(() -> {
            if (generation.get() != gen || listener == null) return;
            listener.onFinished();
        });
    }

    private List<String> getUrl() {
        String local = Server.get().getAddress();
        String base = local.substring(0, local.lastIndexOf(".") + 1);
        return IntStream.range(1, 256).mapToObj(i -> base + i + ":9978").toList();
    }

    private void findDevice(String url) {
        if (url.equals(Server.get().getAddress())) return;
        try (Response res = OkHttp.newCall(client, url.concat("/device"), "scan").execute()) {
            Device device = Device.objectFrom(res.body().string());
            if (device != null) App.post(() -> {
                if (listener != null) listener.onFind(device.save());
            });
        } catch (Exception ignored) {
        }
    }

    public interface Listener {

        void onFind(Device device);

        default void onFinished() {
        }
    }
}
