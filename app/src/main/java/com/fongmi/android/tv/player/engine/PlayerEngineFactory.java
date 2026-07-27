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
        return create(decode, PlayerSetting.getVodEngine(), listener);
    }

    public static PlayerEngine create(int decode, int preferredEngine, Player.Listener listener) {
        return create(decode, resolve(preferredEngine), listener);
    }

    public static PlayerEngine create(int decode, int preferredEngine, PlaySpec spec, Player.Listener listener) {
        return create(decode, resolve(preferredEngine, spec), listener);
    }

    public static PlayerEngine createExo(int decode, Player.Listener listener) {
        return create(decode, EXO, listener);
    }

    private static PlayerEngine create(int decode, PlayerEngine.Type type, Player.Listener listener) {
        return switch (type) {
            case EXO -> new ExoPlayerEngine(decode, listener);
            case MPV -> new MpvPlayerEngine(decode, listener);
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

    private static boolean requiresExo(PlaySpec spec) {
        return spec.getDrm() != null || "smb".equals(UrlUtil.scheme(spec.getUrl()));
    }

    private static boolean isMpvReady(int preferredEngine) {
        return preferredEngine == PlayerSetting.ENGINE_MPV && MpvPlayerEngine.isAvailable();
    }
}
