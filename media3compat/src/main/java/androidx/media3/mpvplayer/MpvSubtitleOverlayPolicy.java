package androidx.media3.mpvplayer;

import java.util.Locale;

/** Deliberately narrow prototype: plain text only, not a replacement for libass/bitmap rendering. */
public final class MpvSubtitleOverlayPolicy {
    private MpvSubtitleOverlayPolicy() {}

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

    public static boolean supports(String codec) {
        if (codec == null) return false;
        return switch (codec.toLowerCase(Locale.ROOT)) {
            case "subrip", "srt", "text", "utf8" -> true;
            default -> false;
        };
    }
}
