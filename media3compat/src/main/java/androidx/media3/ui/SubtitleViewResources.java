package androidx.media3.ui;

import android.util.Log;
import android.view.View;

import androidx.annotation.Nullable;

import java.lang.reflect.Field;
import java.util.List;

/** Releases cached subtitle bitmaps/layouts without creating a WebView or touching video.
 * Media3 1.10.1's canvas output keeps its SubtitlePainters even after setCues(null).
 * There is no public release API. This narrowly scoped adapter to the pinned Media3
 * artifact is protected by a keep rule; a missing field is logged, never crashes playback.
 * Call on the player's application/UI thread only.
 */
public final class SubtitleViewResources {
    private static final String TAG = "SubtitleResources";
    private SubtitleViewResources() {}

    public static void release(@Nullable SubtitleView view) {
        if (view == null) return;
        view.setCues(null);
        view.setVisibility(View.INVISIBLE); // Keep FIT geometry stable for later re-selection.
        for (int i = 0; i < view.getChildCount(); i++) {
            View child = view.getChildAt(i);
            if (!(child instanceof CanvasSubtitleOutput)) continue;
            try {
                Field field = CanvasSubtitleOutput.class.getDeclaredField("painters");
                field.setAccessible(true);
                Object value = field.get(child);
                if (value instanceof List<?> painters) {
                    int count = painters.size();
                    painters.clear(); // Drop bitmap/text/layout references, do not recycle shared Bitmaps.
                    if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "released canvas painters=" + count);
                }
            } catch (ReflectiveOperationException | RuntimeException error) {
                Log.w(TAG, "Pinned Media3 subtitle painter release unavailable", error);
            }
        }
    }
}
