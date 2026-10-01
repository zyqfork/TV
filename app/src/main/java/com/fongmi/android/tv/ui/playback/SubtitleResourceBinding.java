package com.fongmi.android.tv.ui.playback;

import android.view.View;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.text.CueGroup;
import androidx.media3.ui.SubtitleView;
import androidx.media3.ui.SubtitleViewResources;

/** UI-side counterpart of disabling the text renderer; also drops canvas painter caches. */
public final class SubtitleResourceBinding implements Player.Listener {
    private final SubtitleView view;
    @Nullable private Player player;

    public SubtitleResourceBinding(SubtitleView view) { this.view = view; }

    public void bind(@Nullable Player player) {
        if (this.player != player) {
            if (this.player != null) this.player.removeListener(this);
            this.player = player;
            if (player != null) player.addListener(this);
        }
        update();
    }

    private boolean disabled() {
        return player == null || player.getTrackSelectionParameters().disabledTrackTypes.contains(C.TRACK_TYPE_TEXT);
    }

    private void update() {
        if (disabled()) SubtitleViewResources.release(view);
        else {
            view.setCues(player.getCurrentCues().cues);
            view.setVisibility(player.getCurrentCues().cues.isEmpty() ? View.INVISIBLE : View.VISIBLE);
        }
    }

    @Override public void onTrackSelectionParametersChanged(TrackSelectionParameters parameters) {
        if (android.util.Log.isLoggable("SubtitleResources", android.util.Log.DEBUG))
            android.util.Log.d("SubtitleResources", "text disabled=" + disabled()
                    + " current cues=" + (player == null ? 0 : player.getCurrentCues().cues.size()));
        update();
    }
    @Override public void onCues(CueGroup cues) {
        if (android.util.Log.isLoggable("SubtitleResources", android.util.Log.DEBUG))
            android.util.Log.d("SubtitleResources", "cue update disabled=" + disabled() + " cues=" + cues.cues.size());
        if (disabled()) SubtitleViewResources.release(view);
        else if (!cues.cues.isEmpty()) view.setVisibility(View.VISIBLE);
    }

    public void release() {
        if (player != null) player.removeListener(this);
        player = null;
        SubtitleViewResources.release(view);
    }
}
