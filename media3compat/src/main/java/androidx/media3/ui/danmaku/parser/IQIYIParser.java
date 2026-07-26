/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package androidx.media3.ui.danmaku.parser;

import androidx.annotation.Nullable;
import androidx.media3.common.util.Util;
import androidx.media3.ui.danmaku.Danmaku;
import com.google.common.io.ByteStreams;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class IQIYIParser implements Parser {

  public static final IQIYIParser INSTANCE = new IQIYIParser();
  private static final int WIRE_VARINT = 0;
  private static final int WIRE_64BIT = 1;
  private static final int WIRE_LEN = 2;
  private static final int WIRE_32BIT = 5;
  private static final int TAG_DANMU_ENTRY = (6 << 3) | WIRE_LEN;
  private static final int TAG_ENTRY_BULLET = (2 << 3) | WIRE_LEN;

  private static void parseDanmu(ByteBuffer buf, List<Danmaku> out) {
    while (buf.hasRemaining()) {
      int tag = (int) readVarint(buf);
      if (tag == 0) {
        break;
      }
      int wireType = tag & 0x7;
      int fieldNum = tag >>> 3;
      if (wireType == WIRE_LEN) {
        int len = (int) readVarint(buf);
        if (len < 0 || len > buf.remaining()) {
          break;
        }
        int entryEnd = buf.position() + len;
        if (fieldNum == 6) {
          int savedLimit = buf.limit();
          buf.limit(entryEnd);
          parseEntry(buf, out);
          buf.limit(savedLimit);
        }
        buf.position(entryEnd);
      } else {
        skipField(buf, wireType);
      }
    }
  }

  private static void parseEntry(ByteBuffer buf, List<Danmaku> out) {
    while (buf.hasRemaining()) {
      int tag = (int) readVarint(buf);
      if (tag == 0) {
        break;
      }
      int wireType = tag & 0x7;
      int fieldNum = tag >>> 3;
      if (wireType == WIRE_LEN) {
        int len = (int) readVarint(buf);
        if (len < 0 || len > buf.remaining()) {
          break;
        }
        int bulletEnd = buf.position() + len;
        if (fieldNum == 2) {
          int savedLimit = buf.limit();
          buf.limit(bulletEnd);
          @Nullable Danmaku d = parseBulletInfo(buf);
          buf.limit(savedLimit);
          if (d != null) {
            out.add(d);
          }
        }
        buf.position(bulletEnd);
      } else {
        skipField(buf, wireType);
      }
    }
  }

  @Nullable
  private static Danmaku parseBulletInfo(ByteBuffer buf) {
    String content = null;
    String timeStr = null;
    String colorStr = null;
    while (buf.hasRemaining()) {
      int tag = (int) readVarint(buf);
      if (tag == 0) {
        break;
      }
      int wireType = tag & 0x7;
      int fieldNum = tag >>> 3;
      if (wireType == WIRE_LEN) {
        int len = (int) readVarint(buf);
        if (len < 0 || len > buf.remaining()) {
          break;
        }
        byte[] bytes = new byte[len];
        buf.get(bytes);
        String value = Util.fromUtf8Bytes(bytes);
        switch (fieldNum) {
          case 2:
            content = value;
            break;
          case 6:
            timeStr = value;
            break;
          case 8:
            colorStr = value;
            break;
          default:
            break;
        }
      } else {
        skipField(buf, wireType);
      }
    }
    if (content == null || content.isEmpty()) {
      return null;
    }
    if (timeStr == null || timeStr.isEmpty()) {
      return null;
    }
    long timeMs;
    try {
      timeMs = (long) (Double.parseDouble(timeStr) * 1000L);
    } catch (NumberFormatException e) {
      return null;
    }
    return new Danmaku(content, timeMs, Danmaku.TYPE_SCROLL, parseColor(colorStr), 0f);
  }

  private static int parseColor(@Nullable String colorStr) {
    if (colorStr == null || colorStr.isEmpty()) {
      return 0xFFFFFFFF;
    }
    try {
      String s = colorStr.trim();
      if (s.startsWith("#")) {
        s = s.substring(1);
      }
      if (s.length() == 6) {
        return 0xFF000000 | Integer.parseInt(s, 16);
      }
      return 0xFF000000 | (int) (Long.parseLong(s) & 0xFFFFFFL);
    } catch (NumberFormatException e) {
      return 0xFFFFFFFF;
    }
  }

  private static long readVarint(ByteBuffer buf) {
    long result = 0;
    int shift = 0;
    while (buf.hasRemaining()) {
      int b = buf.get() & 0xFF;
      result |= (long) (b & 0x7F) << shift;
      shift += 7;
      if ((b & 0x80) == 0) {
        return result;
      }
      if (shift >= 64) {
        break;
      }
    }
    return result;
  }

  private static void skipField(ByteBuffer buf, int wireType) {
    if (!buf.hasRemaining()) {
      return;
    }
    switch (wireType) {
      case WIRE_VARINT:
        while (buf.hasRemaining() && (buf.get() & 0x80) != 0) {
        }
        break;
      case WIRE_64BIT:
        if (buf.remaining() >= 8) {
          buf.position(buf.position() + 8);
        }
        break;
      case WIRE_LEN:
        int len = (int) readVarint(buf);
        if (len > 0 && len <= buf.remaining()) {
          buf.position(buf.position() + len);
        }
        break;
      case WIRE_32BIT:
        if (buf.remaining() >= 4) {
          buf.position(buf.position() + 4);
        }
        break;
      default:
        break;
    }
  }

  @Override
  public boolean sniff(InputStream is, int sniffLength) throws IOException {
    int first = is.read();
    if (first != TAG_DANMU_ENTRY) {
      return false;
    }
    long entryLen = 0;
    int shift = 0;
    for (int i = 0; i < 10; i++) {
      int b = is.read();
      if (b < 0) {
        return false;
      }
      entryLen |= (long) (b & 0x7F) << shift;
      shift += 7;
      if ((b & 0x80) == 0) {
        break;
      }
    }
    if (entryLen <= 0) {
      return false;
    }
    int entryFirst = is.read();
    return entryFirst == TAG_ENTRY_BULLET;
  }

  @Override
  public List<Danmaku> parse(InputStream inputStream) throws IOException {
    ByteBuffer buf = ByteBuffer.wrap(ByteStreams.toByteArray(inputStream));
    List<Danmaku> result = new ArrayList<>();
    parseDanmu(buf, result);
    Collections.sort(result, Danmaku.BY_TIME);
    return result;
  }
}

