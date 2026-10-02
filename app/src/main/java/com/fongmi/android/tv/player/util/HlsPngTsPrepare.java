package com.fongmi.android.tv.player.util;

import android.net.Uri;
import android.text.TextUtils;

import com.fongmi.android.tv.player.media.PlaySpec;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.utils.UrlUtil;
import com.github.catvod.net.OkHttp;

import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import okhttp3.Response;

/** Rewrites finite HLS playlists whose MPEG-TS segments are hidden behind PNG prefixes. */
public final class HlsPngTsPrepare {

    private static final long SESSION_TTL_MS = 30 * 60_000L;
    private static final int MAX_SESSIONS = 32;
    private static final int MAX_PLAYLIST_CHARS = 2 * 1024 * 1024;
    private static final ConcurrentHashMap<String, Session> SESSIONS = new ConcurrentHashMap<>();

    private HlsPngTsPrepare() {
    }

    public static PlaySpec prepare(PlaySpec spec) {
        if (spec == null || TextUtils.isEmpty(spec.getUrl()) || !isHlsUrl(spec.getUrl())) return spec;
        try {
            Map<String, String> headers = spec.checkUa().getHeaders();
            String body = normalizePlaylist(OkHttp.string(spec.getUrl(), headers));
            if (TextUtils.isEmpty(body) || body.length() > MAX_PLAYLIST_CHARS || !body.contains("#EXTINF")
                    || !body.contains("#EXT-X-ENDLIST") || body.contains("#EXT-X-BYTERANGE")
                    || body.contains("URI=\"")) return spec;
            String segment = firstSegmentUrl(body, spec.getUrl());
            if (segment == null || !needsUnwrap(segment, headers)) return spec;
            String id = UUID.randomUUID().toString().replace("-", "");
            Set<String> allowed = new HashSet<>();
            String rewritten = rewrite(body, spec.getUrl(), id, allowed);
            if (allowed.isEmpty()) return spec;
            Map<String, String> safeHeaders = new HashMap<>(headers);
            safeHeaders.keySet().removeIf(key -> "Host".equalsIgnoreCase(key) || "Range".equalsIgnoreCase(key));
            safeHeaders = Collections.unmodifiableMap(safeHeaders);
            purgeExpired();
            if (SESSIONS.size() >= MAX_SESSIONS) removeOldest();
            SESSIONS.put(id, new Session(safeHeaders, rewritten, Collections.unmodifiableSet(allowed)));
            String local = Server.get().getAddress(true) + "/tsraw/m3u8?id=" + id;
            // Remote credentials stay inside the in-process session and are never sent by mpv
            // to the loopback playlist endpoint.
            return spec.copyWithSource(local, Collections.emptyMap());
        } catch (Throwable ignored) {
            return spec;
        }
    }

    public static Session get(String id) {
        if (TextUtils.isEmpty(id) || id.length() != 32) return null;
        purgeExpired();
        Session session = SESSIONS.get(id);
        if (session != null) session.touch();
        return session;
    }

    private static boolean needsUnwrap(String segmentUrl, Map<String, String> headers) throws Exception {
        try (Response response = OkHttp.newCall(segmentUrl, headers).execute()) {
            if (!response.isSuccessful() || response.body() == null) return false;
            try (InputStream input = response.body().byteStream()) {
                return PngTsUnwrap.findTsOffset(PngTsUnwrap.readProbe(input, PngTsUnwrap.probeSize())) > 0;
            }
        }
    }

    private static String rewrite(String body, String playlistUrl, String id, Set<String> allowed) {
        StringBuilder output = new StringBuilder(body.length() + 256);
        String prefix = Server.get().getAddress(true) + "/tsraw/seg?id=" + id + "&u=";
        for (String raw : body.split("\\r?\\n", -1)) {
            String line = raw.trim();
            if (line.isEmpty()) output.append(raw).append('\n');
            else if (line.startsWith("#")) output.append(raw).append('\n');
            else {
                String absolute = UrlUtil.resolve(playlistUrl, line);
                if (!isHttpUrl(absolute)) throw new IllegalArgumentException("Unsupported HLS segment URL");
                allowed.add(absolute);
                output.append(prefix).append(Uri.encode(absolute)).append('\n');
            }
        }
        return output.toString();
    }

    private static String firstSegmentUrl(String body, String playlistUrl) {
        for (String raw : body.split("\\r?\\n")) {
            String line = raw.trim();
            if (!line.isEmpty() && !line.startsWith("#")) {
                String absolute = UrlUtil.resolve(playlistUrl, line);
                return isHttpUrl(absolute) ? absolute : null;
            }
        }
        return null;
    }

    private static String normalizePlaylist(String body) {
        if (TextUtils.isEmpty(body)) return body;
        String lower = body.toLowerCase(Locale.US);
        int marker = body.indexOf("#EXTM3U");
        int preStart = lower.indexOf("<pre");
        if (marker < 0 || preStart < 0) return body;
        int openEnd = body.indexOf('>', preStart);
        int preEnd = lower.lastIndexOf("</pre>");
        if (openEnd < 0 || preEnd <= openEnd) return body.substring(marker);
        return body.substring(openEnd + 1, preEnd).replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'");
    }

    private static boolean isHlsUrl(String url) {
        String lower = url.toLowerCase(Locale.US);
        return lower.contains(".m3u8") || lower.contains("m3u8?");
    }

    private static boolean isHttpUrl(String value) {
        try {
            Uri uri = Uri.parse(value);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && !TextUtils.isEmpty(uri.getHost()) && TextUtils.isEmpty(uri.getUserInfo());
        } catch (Throwable e) {
            return false;
        }
    }

    private static void purgeExpired() {
        long now = System.currentTimeMillis();
        SESSIONS.entrySet().removeIf(entry -> now - entry.getValue().lastAccessMs > SESSION_TTL_MS);
    }

    private static void removeOldest() {
        Map.Entry<String, Session> oldest = null;
        for (Map.Entry<String, Session> entry : SESSIONS.entrySet()) {
            if (oldest == null || entry.getValue().lastAccessMs < oldest.getValue().lastAccessMs) oldest = entry;
        }
        if (oldest != null) SESSIONS.remove(oldest.getKey(), oldest.getValue());
    }

    public static final class Session {
        public final Map<String, String> headers;
        public final String playlist;
        private final Set<String> allowedSegments;
        private volatile long lastAccessMs;

        Session(Map<String, String> headers, String playlist, Set<String> allowedSegments) {
            this.headers = headers;
            this.playlist = playlist;
            this.allowedSegments = allowedSegments;
            touch();
        }

        public boolean allows(String url) {
            return allowedSegments.contains(url);
        }

        void touch() {
            lastAccessMs = System.currentTimeMillis();
        }
    }
}
