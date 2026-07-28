package com.fongmi.android.tv.player.util;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.util.Arrays;

/**
 * Some CDNs wrap MPEG-TS in a tiny PNG (magic + optional {@code TS_RAW} marker) so naive
 * demuxers treat the segment as an image. Exo finds TS sync via sniff; libmpv/ffmpeg does not.
 */
public final class PngTsUnwrap {

    private static final byte[] PNG_SIG = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] TS_RAW = "TS_RAW".getBytes();
    private static final int TS_PACKET = 188;
    private static final int PROBE = 4096;

    private PngTsUnwrap() {
    }

    public static boolean looksLikePng(byte[] data) {
        if (data == null || data.length < PNG_SIG.length) return false;
        for (int i = 0; i < PNG_SIG.length; i++) if (data[i] != PNG_SIG[i]) return false;
        return true;
    }

    /**
     * @return byte offset of MPEG-TS payload, or {@code -1} if not a PNG-wrapped TS, or {@code 0} if
     * data is not PNG (pass-through).
     */
    public static int findTsOffset(byte[] data) {
        if (data == null || data.length < PNG_SIG.length) return 0;
        if (!looksLikePng(data)) return 0;
        int from = indexOf(data, TS_RAW);
        if (from >= 0) {
            from += TS_RAW.length;
            if (from < data.length && data[from] == 0) from++;
            int sync = findTsSync(data, from);
            return sync >= 0 ? sync : from;
        }
        return findTsSync(data, PNG_SIG.length);
    }

    public static InputStream unwrap(byte[] head, InputStream rest) throws IOException {
        int offset = findTsOffset(head);
        if (offset < 0) {
            return new SequenceInputStream(new ByteArrayInputStream(head), rest);
        }
        if (offset == 0) {
            return new SequenceInputStream(new ByteArrayInputStream(head), rest);
        }
        if (offset >= head.length) {
            long skip = offset - head.length;
            while (skip > 0) {
                long n = rest.skip(skip);
                if (n <= 0) break;
                skip -= n;
            }
            return rest;
        }
        return new SequenceInputStream(new ByteArrayInputStream(head, offset, head.length - offset), rest);
    }

    public static byte[] readProbe(InputStream in, int max) throws IOException {
        byte[] buf = new byte[max];
        int off = 0;
        while (off < max) {
            int n = in.read(buf, off, max - off);
            if (n < 0) break;
            off += n;
        }
        return off == max ? buf : Arrays.copyOf(buf, off);
    }

    public static int probeSize() {
        return PROBE;
    }

    private static int findTsSync(byte[] data, int start) {
        int limit = Math.min(data.length - TS_PACKET * 2, start + PROBE);
        for (int i = Math.max(0, start); i <= limit; i++) {
            if (data[i] != 0x47) continue;
            if (i + TS_PACKET < data.length && data[i + TS_PACKET] == 0x47
                    && i + TS_PACKET * 2 < data.length && data[i + TS_PACKET * 2] == 0x47) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOf(byte[] data, byte[] needle) {
        outer:
        for (int i = 0; i <= data.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}
