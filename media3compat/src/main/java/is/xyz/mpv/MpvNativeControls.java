package is.xyz.mpv;

import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicLong;

/** One in-flight control: libmpv's async API does not itself guarantee operation order. */
public final class MpvNativeControls {
    public interface Sender {
        int property(long id, String name, String value);
        int command(long id, String[] arguments);
    }
    private record Request(long id, String name, String value, String[] arguments) {}
    private static final AtomicLong IDS = new AtomicLong(1L << 48);
    private static final int LIMIT = 1024;
    private final Sender sender;
    private final ArrayDeque<Request> pending = new ArrayDeque<>();
    private Request inFlight;
    private boolean closed;

    public MpvNativeControls(Sender sender) { this.sender = sender; }
    public synchronized int property(String name, String value) {
        return enqueue(new Request(IDS.incrementAndGet(), name, value, null));
    }
    public synchronized int command(String[] arguments) {
        return enqueue(new Request(IDS.incrementAndGet(), null, null, arguments.clone()));
    }
    private int enqueue(Request request) {
        if (closed) return -3;
        if (pending.size() >= LIMIT) return -1;
        pending.addLast(request);
        drain();
        return 0;
    }
    private void drain() {
        while (!closed && inFlight == null && !pending.isEmpty()) {
            Request request = pending.removeFirst();
            inFlight = request;
            int status = request.arguments == null
                    ? sender.property(request.id, request.name, request.value)
                    : sender.command(request.id, request.arguments);
            if (status >= 0) return;
            inFlight = null; // failed submissions have no reply; never wedge the queue
        }
    }
    public synchronized void reply(long id) {
        if (closed || inFlight == null || inFlight.id != id) return;
        inFlight = null;
        drain();
    }
    /** Clear queued work, but retain the active barrier until native context disposal. */
    public synchronized void close() { closed = true; pending.clear(); }
}
