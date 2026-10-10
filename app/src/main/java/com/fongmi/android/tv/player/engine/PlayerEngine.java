package com.fongmi.android.tv.player.engine;

import androidx.annotation.Nullable;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;

import com.fongmi.android.tv.bean.Sub;
import com.fongmi.android.tv.player.effect.PlayerEffect;
import com.fongmi.android.tv.player.media.PlaySpec;

public interface PlayerEngine {

    int SOFT = 0;
    int HARD = 1;

    Type getType();

    Player getPlayer();

    default PlayerEffect getEffect() {
        return PlayerEffect.NONE;
    }

    default boolean requiresAudioEffectRebuild() {
        return false;
    }

    /**
     * True when this engine was built from playback settings that have since changed.
     * LoadControl, renderers, and track selection are fixed at construction.
     */
    default boolean refreshConfig() {
        return false;
    }

    void release();

    Player rebuild();

    boolean setDecode(int decode);

    default void setLiveMode(boolean live) {
    }

    void start(PlaySpec spec, long startPositionMs);

    /** Restart/rebuild without implicitly resuming a user-paused item. */
    void start(PlaySpec spec, long startPositionMs, boolean playWhenReady);

    default void preload(PlaySpec spec, long startPositionMs) {
    }

    default void clearPreload() {
    }

    /**
     * Clears the automatic-recovery budget. Called once playback actually reaches a healthy state,
     * so a stream that hiccups, recovers, and later hiccups again gets a fresh retry allowance
     * instead of immediately reporting a fatal error.
     */
    default void resetErrorBudget() {
    }

    default void stop() {
        getPlayer().stop();
    }

    boolean isLive();

    boolean isVod();

    default void setSubtitleStyle() {
    }

    /**
     * Whether this engine had to replace a requested hardware decoder with software for the
     * current item, consuming the notice. EXO reports its own renderer fallback; MPV reports the
     * decoder libmpv actually selected. At most one notice per item.
     */
    default boolean consumeSoftwareFallbackNotice() {
        return false;
    }

    default void setVolumeGain(float gain) {
        if (getPlayer().isCommandAvailable(Player.COMMAND_SET_VOLUME)) getPlayer().setVolume(Math.clamp(gain, 0f, 1f));
    }

    default boolean supportsEmbeddedSecondarySubtitle() {
        return false;
    }

    default void setEmbeddedSecondarySubtitle(@Nullable TrackSelectionOverride selection) {
    }

    default void setEmbeddedSecondarySubtitleOffset(long offsetMs) {
    }

    default boolean addSubtitle(Sub sub) {
        return false;
    }

    String getErrorMessage(PlaybackException e);

    ErrorAction handleError(PlaybackException e);

    enum ErrorAction {
        RECOVERED,
        DECODE,
        /** Transient/source-level failure: PlayerManager restarts the item with backoff. */
        RETRY,
        FATAL
    }

    enum Type {
        EXO,
        MPV
    }
}
