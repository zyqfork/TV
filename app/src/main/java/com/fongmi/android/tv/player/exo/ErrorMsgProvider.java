package com.fongmi.android.tv.player.exo;

import androidx.media3.common.PlaybackException;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.utils.ResUtil;

public class ErrorMsgProvider {

    public String get(PlaybackException e) {
        return switch (e.errorCode) {
            case PlaybackException.ERROR_CODE_TIMEOUT,
                 PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> ResUtil.getString(R.string.error_play_timeout);
            case PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> ResUtil.getString(R.string.error_play_http);
            case PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                 PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> ResUtil.getString(R.string.error_play_network);
            case PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
                 PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                 PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> ResUtil.getString(R.string.error_play_format);
            case PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                 PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
                 PlaybackException.ERROR_CODE_DECODING_FAILED,
                 PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> ResUtil.getString(R.string.error_decode_fallback);
            case PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
                 PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
                 PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> ResUtil.getString(R.string.error_play_source);
            default -> ResUtil.getString(R.string.error_play_url);
        };
    }
}
