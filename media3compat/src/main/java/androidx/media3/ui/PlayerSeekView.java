package androidx.media3.ui;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.Nullable;
import androidx.media3.common.util.RepeatModeUtil;

/**
 * Compact Media3 controller used by the TV and mobile playback overlays.
 *
 * <p>The regular {@link PlayerControlView} already owns the timeline update and
 * scrubbing logic. This view configures it as a seek-only controller and
 * exposes the time bar required by the application.
 */
public final class PlayerSeekView extends PlayerControlView {

    public PlayerSeekView(Context context) {
        this(context, null);
    }

    public PlayerSeekView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public PlayerSeekView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setShowPreviousButton(false);
        setShowNextButton(false);
        setShowRewindButton(false);
        setShowFastForwardButton(false);
        setShowShuffleButton(false);
        setShowSubtitleButton(false);
        setShowVrButton(false);
        setRepeatToggleModes(RepeatModeUtil.REPEAT_TOGGLE_MODE_NONE);
        setShowTimeoutMs(0);
    }

    public TimeBar getTimeBar() {
        TimeBar timeBar = findViewById(R.id.exo_progress);
        if (timeBar == null) throw new IllegalStateException("Player control layout has no time bar");
        return timeBar;
    }
}
