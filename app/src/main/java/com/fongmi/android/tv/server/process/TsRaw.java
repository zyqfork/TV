package com.fongmi.android.tv.server.process;

import android.text.TextUtils;

import com.fongmi.android.tv.player.util.HlsPngTsPrepare;
import com.fongmi.android.tv.player.util.PngTsUnwrap;
import com.fongmi.android.tv.server.Nano;
import com.fongmi.android.tv.server.impl.Process;
import com.github.catvod.net.OkHttp;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Response;
import okhttp3.ResponseBody;

/** Local, session-scoped unwrap endpoint for PNG-wrapped MPEG-TS segments. */
public class TsRaw implements Process {

    private static final long MAX_BUFFERED_SEGMENT = 64L * 1024 * 1024;

    @Override
    public boolean isRequest(IHTTPSession session, String url) {
        return url.startsWith("/tsraw/");
    }

    @Override
    public Response doResponse(IHTTPSession session, String url, Map<String, String> files) {
        try {
            Map<String, String> params = session.getParms();
            HlsPngTsPrepare.Session play = HlsPngTsPrepare.get(params.get("id"));
            if (play == null) return Nano.error(Response.Status.NOT_FOUND, "session expired");
            if (url.startsWith("/tsraw/m3u8")) {
                return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/vnd.apple.mpegurl", play.playlist);
            }
            if (url.startsWith("/tsraw/seg")) {
                String target = params.get("u");
                if (TextUtils.isEmpty(target) || !play.allows(target)) return Nano.error(Response.Status.FORBIDDEN, "invalid segment");
                return streamUnwrapped(target, play.headers);
            }
            return Nano.error(Response.Status.NOT_FOUND, "unknown tsraw path");
        } catch (Throwable e) {
            return Nano.error("segment unavailable");
        }
    }

    private static Response streamUnwrapped(String url, Map<String, String> headers) throws Exception {
        okhttp3.Response upstream = OkHttp.newCall(url, headers).execute();
        ResponseBody body = upstream.body();
        if (!upstream.isSuccessful() || body == null) {
            int code = upstream.code();
            upstream.close();
            return Nano.error("upstream " + code);
        }
        long sourceLength = body.contentLength();
        if (sourceLength > MAX_BUFFERED_SEGMENT) {
            upstream.close();
            return Nano.error("segment too large");
        }
        InputStream input = body.byteStream();
        // Nothing owns `upstream` until `output` exists, so a failure while probing or unwrapping
        // has to close it here. Otherwise a mid-read upstream failure leaks the connection while
        // the caller only reports "segment unavailable".
        int offset;
        InputStream output;
        try {
            byte[] head = PngTsUnwrap.readProbe(input, PngTsUnwrap.probeSize());
            offset = PngTsUnwrap.findTsOffset(head);
            InputStream unwrapped = PngTsUnwrap.unwrap(head, input);
            output = new FilterInputStream(unwrapped) {
                @Override
                public void close() throws IOException {
                    try {
                        super.close();
                    } finally {
                        upstream.close();
                    }
                }
            };
        } catch (Exception e) {
            upstream.close();
            throw e;
        }
        long payloadLength = sourceLength >= 0 && offset >= 0 ? Math.max(0, sourceLength - offset) : -1;
        if (payloadLength < 0) {
            byte[] payload;
            try (InputStream stream = output; ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
                byte[] bytes = new byte[32 * 1024];
                long total = 0;
                int count;
                while ((count = stream.read(bytes)) != -1) {
                    total += count;
                    if (total > MAX_BUFFERED_SEGMENT) throw new IOException("segment too large");
                    buffer.write(bytes, 0, count);
                }
                payload = buffer.toByteArray();
            }
            output = new ByteArrayInputStream(payload);
            payloadLength = payload.length;
        }
        Response response = NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "video/mp2t", output, payloadLength);
        response.addHeader("Accept-Ranges", "none");
        response.addHeader("Cache-Control", "no-store, no-transform");
        return response;
    }
}
