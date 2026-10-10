package is.xyz.mpv;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Event-fed metadata. A UI lookup registers an observation, never waits for the playback core. */
public final class MpvPropertyCache {
    public interface Observer { void observe(String name, int format); }
    private final Map<String, Object> values = new ConcurrentHashMap<>();
    private final Set<String> observed = ConcurrentHashMap.newKeySet();
    private final Observer observer;
    public MpvPropertyCache(Observer observer) { this.observer = observer; }
    public Object get(String name, int format) {
        if (observed.add(name + ":" + format)) observer.observe(name, format);
        return values.get(name);
    }
    public void put(String name, Object value) {
        if (value == null) values.remove(name); else values.put(name, value);
    }
    public void clearMedia() {
        values.keySet().removeIf(name -> name.startsWith("track-list/") || name.startsWith("chapter-list/")
                || name.startsWith("edition-list/") || name.startsWith("audio-params/")
                || name.startsWith("video-params/") || name.startsWith("video-out-params/")
                || name.startsWith("video-frame-info/") || name.equals("duration") || name.equals("time-pos")
                || name.equals("sid") || name.equals("secondary-sid") || name.equals("current-vo")
                // The selected decoder belongs to the item that is being replaced; keeping it
                // would report the previous item's software fallback for the next one.
                || name.equals("hwdec-current"));
    }
}
