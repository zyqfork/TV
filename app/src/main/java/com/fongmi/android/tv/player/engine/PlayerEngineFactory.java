package com.fongmi.android.tv.player.engine;

import static com.fongmi.android.tv.player.engine.PlayerEngine.Type.EXO;
import static com.fongmi.android.tv.player.engine.PlayerEngine.Type.MPV;

import androidx.media3.common.Player;

import com.fongmi.android.tv.player.exo.ExoPlayerEngine;
import com.fongmi.android.tv.player.media.PlaySpec;
import com.fongmi.android.tv.player.mpv.MpvPlayerEngine;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.utils.UrlUtil;

public final class PlayerEngineFactory {

    public static PlayerEngine create(int decode, Player.Listener listener) {
        return create(decode, PlayerSetting.getVodEngine(), false, listener);
    }

    public static PlayerEngine create(int decode, int preferredEngine, Player.Listener listener) {
        return create(decode, preferredEngine, false, listener);
    }

    public static PlayerEngine create(int decode, int preferredEngine, boolean live, Player.Listener listener) {
        return create(decode, resolve(preferredEngine), live, listener);
    }

    public static PlayerEngine create(int decode, int preferredEngine, PlaySpec spec, Player.Listener listener) {
        return create(decode, preferredEngine, false, spec, listener);
    }

    public static PlayerEngine create(int decode, int preferredEngine, boolean live, PlaySpec spec, Player.Listener listener) {
        return create(decode, resolve(preferredEngine, spec), live, listener);
    }

    public static PlayerEngine createExo(int decode, Player.Listener listener) {
        return createExo(decode, false, listener);
    }

    public static PlayerEngine createExo(int decode, boolean live, Player.Listener listener) {
        return create(decode, EXO, live, listener);
    }

    private static PlayerEngine create(int decode, PlayerEngine.Type type, boolean live, Player.Listener listener) {
        return switch (type) {
            case EXO -> new ExoPlayerEngine(decode, live, listener);
            case MPV -> new MpvPlayerEngine(decode, live, listener);
        };
    }

    public static boolean matches(PlayerEngine engine, int preferredEngine, PlaySpec spec) {
        return engine != null && engine.getType() == resolve(preferredEngine, spec);
    }

    private static PlayerEngine.Type resolve(int preferredEngine, PlaySpec spec) {
        if (!isMpvReady(preferredEngine)) return EXO;
        if (requiresExo(spec)) return EXO;
        return MPV;
    }

    private static PlayerEngine.Type resolve(int preferredEngine) {
        return isMpvReady(preferredEngine) ? MPV : EXO;
    }

    public static boolean requiresExo(PlaySpec spec) {
        if (spec == null) return false;
        if (spec.getDrm() != null || "smb".equals(UrlUtil.scheme(spec.getUrl())) || ("ftp".equals(UrlUtil.scheme(spec.getUrl())) && com.fongmi.android.tv.storage.NetworkPlayResolver.isNetworkPlayUrl(spec.getUrl()))) return true;
        String url = spec.getUrl();
        String lowerUrl = url == null ? "" : url.toLowerCase(java.util.Locale.US);
        String format = spec.getFormat();
        String lowerFormat = format == null ? "" : format.toLowerCase(java.util.Locale.US);
        // DASH remains substantially more reliable in Media3 on Android, especially for DRM-like
        // multi-period manifests and codec adaptation. Do not send it through the MPV fallback.
        return lowerFormat.contains("dash") || lowerFormat.contains("mpd")
                || lowerUrl.contains(".mpd") || lowerUrl.contains("format=mpd")
                || lowerUrl.contains("/dash/");
    }

    private static boolean isMpvReady(int preferredEngine) {
        return preferredEngine == PlayerSetting.ENGINE_MPV && MpvPlayerEngine.isAvailable();
    }
}
