package androidx.media3.mpvplayer;

import java.util.Locale;

/** Native mpv codec names mapped to Media3 track metadata; rendering stays in GPU/libass. */
public final class MpvSubtitleFormats {
    private MpvSubtitleFormats() {}

    /** Preserve bitmap MIME classification used by track dialogs and subtitle-delay controls. */
    public static String mimeType(String codec) {
        if (codec == null) return "text/x-unknown";
        return switch (codec.toLowerCase(Locale.ROOT)) {
            case "subrip", "srt" -> "application/x-subrip";
            case "ass", "ssa" -> "text/x-ssa";
            case "webvtt" -> "text/vtt";
            case "hdmv_pgs_subtitle", "pgs" -> "application/pgs";
            case "dvd_subtitle", "vobsub" -> "application/vobsub";
            case "dvb_subtitle", "dvbsub" -> "application/dvbsubs";
            default -> "text/x-unknown";
        };
    }
}
