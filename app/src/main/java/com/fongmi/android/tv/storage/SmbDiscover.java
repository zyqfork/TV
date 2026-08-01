package com.fongmi.android.tv.storage;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.utils.Util;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class SmbDiscover {

    private static final int PORT = 445;
    private static final int TIMEOUT_MS = 300;

    private final CopyOnWriteArrayList<Future<?>> futures = new CopyOnWriteArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Listener listener;

    public interface Listener {
        void onProgress(int done, int total);

        void onComplete(List<Host> hosts);
    }

    public static class Host {
        private final String ip;
        private final String name;

        public Host(String ip, String name) {
            this.ip = ip;
            this.name = name == null ? "" : name;
        }

        public String getIp() {
            return ip;
        }

        public String getName() {
            return name;
        }

        public String display() {
            if (TextUtils.isEmpty(name) || name.equals(ip)) return ip;
            return name + " (" + ip + ")";
        }
    }

    public SmbDiscover(Listener listener) {
        this.listener = listener;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) return;
        Task.execute(this::run);
    }

    public void stop() {
        running.set(false);
        listener = null;
        futures.forEach(f -> f.cancel(true));
        futures.clear();
    }

    private void run() {
        String local = Util.getIp();
        if (TextUtils.isEmpty(local) || !local.contains(".")) {
            finish(Collections.emptyList());
            return;
        }
        String base = local.substring(0, local.lastIndexOf('.') + 1);
        List<Host> found = new CopyOnWriteArrayList<>();
        AtomicInteger done = new AtomicInteger();
        int total = 254;
        for (int i = 1; i <= 254; i++) {
            String ip = base + i;
            futures.add(Task.submitLarge(() -> {
                if (!running.get()) return;
                if (!ip.equals(local) && isOpen(ip)) {
                    found.add(new Host(ip, resolveName(ip)));
                }
                int current = done.incrementAndGet();
                if (current % 16 == 0 || current == total) {
                    App.post(() -> {
                        if (listener != null) listener.onProgress(current, total);
                    });
                }
            }));
        }
        for (Future<?> job : futures) {
            try {
                job.get();
            } catch (Exception ignored) {
            }
        }
        futures.clear();
        List<Host> result = new ArrayList<>(found);
        Collections.sort(result, (a, b) -> a.getIp().compareTo(b.getIp()));
        finish(result);
    }

    private void finish(List<Host> hosts) {
        running.set(false);
        App.post(() -> {
            if (listener != null) listener.onComplete(hosts);
        });
    }

    private static boolean isOpen(String ip) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(ip, PORT), TIMEOUT_MS);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String resolveName(String ip) {
        try {
            String name = java.net.InetAddress.getByName(ip).getCanonicalHostName();
            return TextUtils.isEmpty(name) ? "" : name;
        } catch (Exception e) {
            return "";
        }
    }
}
