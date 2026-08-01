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
        return engine != null && engine.getType() == resolveType(preferredEngine, spec);
    }

    public static PlayerEngine.Type resolveType(int preferredEngine, PlaySpec spec) {
        return resolve(preferredEngine, spec);
    }

    public static boolean isMpvPreferred(int preferredEngine) {
        return isMpvReady(preferredEngine);
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
        if (spec.getDrm() != null) return true;
        String url = spec.getUrl();
        if (url == null || url.isEmpty()) return false;
        if ("smb".equals(UrlUtil.scheme(url))) return true;
        String lower = url.toLowerCase();
        String format = spec.getFormat() == null ? "" : spec.getFormat().toLowerCase();
        // DASH is more reliable on Exo; MPV can open .mpd but first-frame is slow / CPU heavy.
        if (format.contains("dash") || format.contains("mpd")) return true;
        if (lower.contains(".mpd") || lower.contains("format=mpd") || lower.contains("/dash/")) return true;
        return false;
    }

    private static boolean isMpvReady(int preferredEngine) {
        return preferredEngine == PlayerSetting.ENGINE_MPV && MpvPlayerEngine.isAvailable();
    }
}
