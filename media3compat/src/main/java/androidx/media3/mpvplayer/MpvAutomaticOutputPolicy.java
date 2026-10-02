package androidx.media3.mpvplayer;

import java.util.Map;

/** Eligibility for the internal zero-copy output route (never a user decode preference).
 * Hard decode always uses MediaCodec. VOD, effects, external/selected subtitles, and a
 * non-SurfaceView output use GPU/libass instead; an unsupported embed route is also latched
 * to GPU for the current item. The output choice does not write the user's soft/hard setting.
 */
public final class MpvAutomaticOutputPolicy {

    public static final String HWDEC_MEDIACODEC = "mediacodec";
    public static final String VO_MEDIACODEC_EMBED = "mediacodec_embed";

    private MpvAutomaticOutputPolicy() {}

    /** Preserve explicit mpv.conf decoder/output settings rather than silently forcing embed. */
    public static boolean acceptsUserOptions(Map<String, String> options,
                                             boolean gpuNext, boolean vulkan) {
        String hwdec = options.get("hwdec");
        String vo = options.get("vo");
        return (hwdec == null || HWDEC_MEDIACODEC.equalsIgnoreCase(hwdec.trim()))
                && (vo == null || VO_MEDIACODEC_EMBED.equalsIgnoreCase(vo.trim()))
                && !options.containsKey("gpu-api") && !options.containsKey("gpu-context")
                && !gpuNext && !vulkan;
    }

    public static boolean direct(boolean live, boolean hard, boolean videoEffects,
                                 boolean externalSubtitles, boolean selectedSubtitles,
                                 boolean surfaceView) {
        return live && hard && !videoEffects && !externalSubtitles
                && !selectedSubtitles && surfaceView;
    }
}
