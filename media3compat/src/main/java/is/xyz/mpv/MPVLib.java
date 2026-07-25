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
 * https://github.com/mpv-android/mpv-android/tree/6deb01c/app/src/main/jni
 * under the MIT license.
 */
public final class MPVLib {

    private static final CopyOnWriteArrayList<EventObserver> observers = new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<LogObserver> logObservers = new CopyOnWriteArrayList<>();
    private static volatile boolean loaded;
    private static final AtomicBoolean instanceInUse = new AtomicBoolean();

    private MPVLib() {
    }

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
}
