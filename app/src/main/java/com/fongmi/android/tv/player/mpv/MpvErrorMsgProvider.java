package com.fongmi.android.tv.player.mpv;

import androidx.media3.common.PlaybackException;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.player.exo.ErrorMsgProvider;

/**
 * Reuse the shared user-facing mapping; MPV mostly surfaces IO / decode codes.
 */
public class MpvErrorMsgProvider {

    private final ErrorMsgProvider delegate = new ErrorMsgProvider();

    public String get(PlaybackException e) {
        return delegate.get(e);
    }
}
