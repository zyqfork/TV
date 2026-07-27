package is.xyz.mpv;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.Surface;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Java binding for the native bridge maintained by mpv-android.
 *
 * <p>The matching JNI source is available at
 * https://github.com/zyqfork/mpv-android/tree/46ef59a1f093b30e774f463d5c5942a3ac8d22be/app/src/main/jni
 * under the MIT license.
 */
public final class MPVLib {

    private static final CopyOnWriteArrayList<EventObserver> observers = new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<LogObserver> logObservers = new CopyOnWriteArrayList<>();
    private static volatile boolean loaded;
    private static final AtomicBoolean instanceInUse = new AtomicBoolean();

    private MPVLib() {
    }

    private static volatile String features;

    public static synchronized boolean load() {
        if (loaded) return true;
        try {
            System.loadLibrary("mpv");
            System.loadLibrary("player");
            loaded = true;
        } catch (LinkageError e) {
            loaded = false;
        }
        return loaded;
    }

    /**
     * Check if the native libmpv was compiled with a given feature (e.g. "vulkan").
     * Queries mpv_get_property("options/gpu-context") after init to detect available contexts,
     * but pre-init we fall back to trying the native method which reads mpv's feature string.
     */
    public static boolean hasFeature(String feature) {
        if (!loaded) return false;
        if (features == null) {
            try {
                features = nativeGetFeatures();
            } catch (UnsatisfiedLinkError e) {
                // JNI not yet implemented; return empty until native rebuild
                features = "";
            } catch (Throwable e) {
                features = "";
            }
        }
        return features.contains(feature);
    }

    private static native String nativeGetFeatures();

    public static boolean acquireInstance() {
        return load() && instanceInUse.compareAndSet(false, true);
    }

    public static void releaseInstance() {
        instanceInUse.set(false);
    }

    public static native void create(Context context);

    public static native void init();

    public static native void destroy();

    public static native void attachSurface(Surface surface);

    /**
     * Atomically switches the Android render target.
     *
     * <p>This entry point is provided by FongMi's player bridge. Older mpv-android builds don't
     * have it, so callers must fall back to detach/attach when it is unavailable.
     */
    public static native void replaceSurface(Surface surface);

    public static native void detachSurface();

    public static native void command(String[] command);

    public static native int setOptionString(String name, String value);

    public static native Bitmap grabThumbnail(int dimension);

    public static native Integer getPropertyInt(String property);

    public static native void setPropertyInt(String property, int value);

    public static native Double getPropertyDouble(String property);

    public static native void setPropertyDouble(String property, double value);

    public static native Boolean getPropertyBoolean(String property);

    public static native void setPropertyBoolean(String property, boolean value);

    public static native String getPropertyString(String property);

    public static native void setPropertyString(String property, String value);

    public static native void observeProperty(String property, int format);

    public static void addObserver(EventObserver observer) {
        observers.addIfAbsent(observer);
    }

    public static void removeObserver(EventObserver observer) {
        observers.remove(observer);
    }

    public static void addLogObserver(LogObserver observer) {
        logObservers.addIfAbsent(observer);
    }

    public static void removeLogObserver(LogObserver observer) {
        logObservers.remove(observer);
    }

    @SuppressWarnings("unused")
    public static void eventProperty(String property) {
        for (EventObserver observer : observers) observer.eventProperty(property);
    }

    @SuppressWarnings("unused")
    public static void eventProperty(String property, long value) {
        for (EventObserver observer : observers) observer.eventProperty(property, value);
    }

    @SuppressWarnings("unused")
    public static void eventProperty(String property, boolean value) {
        for (EventObserver observer : observers) observer.eventProperty(property, value);
    }

    @SuppressWarnings("unused")
    public static void eventProperty(String property, String value) {
        for (EventObserver observer : observers) observer.eventProperty(property, value);
    }

    @SuppressWarnings("unused")
    public static void eventProperty(String property, double value) {
        for (EventObserver observer : observers) observer.eventProperty(property, value);
    }

    @SuppressWarnings("unused")
    public static void event(int eventId) {
        for (EventObserver observer : observers) observer.event(eventId);
    }

    @SuppressWarnings("unused")
    public static void eventEndFile(int reason, int error, String fileError) {
        for (EventObserver observer : observers) observer.eventEndFile(reason, error, fileError);
    }

    @SuppressWarnings("unused")
    public static void logMessage(String prefix, int level, String text) {
        for (LogObserver observer : logObservers) observer.logMessage(prefix, level, text);
    }

    public interface EventObserver {
        void eventProperty(String property);

        void eventProperty(String property, long value);

        void eventProperty(String property, boolean value);

        void eventProperty(String property, String value);

        void eventProperty(String property, double value);

        void event(int eventId);

        default void eventEndFile(int reason, int error, String fileError) {
            event(MpvEvent.END_FILE);
        }
    }

    public interface LogObserver {
        void logMessage(String prefix, int level, String text);
    }

    public static final class MpvFormat {
        public static final int NONE = 0;
        public static final int STRING = 1;
        public static final int FLAG = 3;
        public static final int INT64 = 4;
        public static final int DOUBLE = 5;

        private MpvFormat() {
        }
    }

    public static final class MpvEvent {
        public static final int SHUTDOWN = 1;
        public static final int START_FILE = 6;
        public static final int END_FILE = 7;
        public static final int FILE_LOADED = 8;
        public static final int VIDEO_RECONFIG = 17;
        public static final int PLAYBACK_RESTART = 21;

        private MpvEvent() {
        }
    }

    public static final class MpvEndFileReason {
        public static final int EOF = 0;
        public static final int STOP = 2;
        public static final int QUIT = 3;
        public static final int ERROR = 4;
        public static final int REDIRECT = 5;

        private MpvEndFileReason() {
        }
    }
}
