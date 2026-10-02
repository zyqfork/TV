package androidx.media3.mpvplayer;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Application-thread queue for optional subtitle I/O; never waits for native completion. */
final class MpvSubtitleRequests {
    interface Transport {
        int send(long id, String[] args);
        void abort(long id);
    }

    interface Listener {
        void completed(int error);
    }

    // JNI/mpv is process-global. IDs must not be reused by a replacement player instance.
    private static final AtomicLong NEXT_ID = new AtomicLong();
    private final Transport transport;
    private final Listener listener;
    private final ArrayDeque<Request> pending = new ArrayDeque<>();
    private final Set<String> imported = new HashSet<>();
    private Request active;
    private long activeId;
    private boolean enabled;

    MpvSubtitleRequests(Transport transport, Listener listener) {
        this.transport = transport;
        this.listener = listener;
    }

    void start() {
        enabled = true;
        pump();
    }

    void enqueue(String uri, boolean select) {
        if (!select) {
            if (imported.contains(uri) || active != null && active.uri.equals(uri)) return;
            for (Request request : pending) if (request.uri.equals(uri)) return;
        }
        pending.addLast(new Request(uri, select));
        pump();
    }

    void complete(long id, int error) {
        if (id != activeId || active == null) return; // cancelled/file/owner's late reply
        if (error >= 0) imported.add(active.uri);
        active = null;
        activeId = 0;
        try {
            listener.completed(error);
        } finally {
            pump();
        }
    }

    /** Closing TEXT cancels I/O, but retains the already imported tracks for re-selection. */
    void cancel() {
        enabled = false;
        pending.clear();
        long id = activeId;
        active = null;
        activeId = 0;
        if (id != 0) transport.abort(id);
    }

    /** A new file/stop/release invalidates both requests and the per-file import cache. */
    void reset() {
        cancel();
        imported.clear();
    }

    private void pump() {
        while (enabled && active == null && !pending.isEmpty()) {
            active = pending.removeFirst();
            long id = activeId = NEXT_ID.incrementAndGet();
            // Keep original auto/select semantics and source order, one request at a time.
            int result = transport.send(id, new String[]{"sub-add", active.uri,
                    active.select ? "select" : "auto"});
            if (result >= 0 || activeId != id) return;
            active = null;
            activeId = 0;
            // Queuing failure has no native reply. Report it and still try subsequent entries.
            listener.completed(result);
        }
    }

    private static final class Request {
        final String uri;
        final boolean select;

        Request(String uri, boolean select) {
            this.uri = uri;
            this.select = select;
        }
    }
}
