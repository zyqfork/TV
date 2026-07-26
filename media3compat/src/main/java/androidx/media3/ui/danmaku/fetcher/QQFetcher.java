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
package androidx.media3.ui.danmaku.fetcher;

import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.media3.ui.danmaku.Danmaku;
import androidx.media3.ui.danmaku.parser.QQParser;
import com.google.common.collect.ImmutableMap;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.OkHttpClient;

public final class QQFetcher implements Fetcher {

  public static final QQFetcher INSTANCE = new QQFetcher();
  private static final Pattern RE_VID_PATH = Pattern.compile("/([^/]+)\\.html");
  private static final Pattern RE_VID_QUERY = Pattern.compile("[?&]vid=([^&]+)");
  private static final Pattern RE_DURATION = Pattern.compile("\"duration\":\"(\\d+)\"");
  private static final String INFO_URL = "https://union.video.qq.com/fcgi-bin/data?otype=json&tid=1804&appid=20001238&appkey=6c03bbe9658448a4&union_platform=1&idlist=";
  private static final String SEGMENT_URL = "https://dm.video.qq.com/barrage/segment/%s/t/v1/%d/%d";
  private static final ImmutableMap<String, String> HEADERS = FetcherUtil.headers("https://v.qq.com", "https://v.qq.com/");

  @Override
  public boolean accepts(Uri uri) {
    String host = uri.getHost();
    return host != null && (host.equals("v.qq.com") || host.endsWith(".v.qq.com"));
  }

  @Override
  public Session prepare(Uri uri, OkHttpClient client, long durationMs) throws IOException {
    String urlStr = uri.toString();
    String vid = null;
    Matcher mq = RE_VID_QUERY.matcher(urlStr);
    if (mq.find()) {
      vid = mq.group(1);
    } else {
      Matcher mp = RE_VID_PATH.matcher(urlStr);
      if (mp.find()) {
        vid = mp.group(1);
      }
    }
    if (vid == null) {
      throw new IOException("Cannot extract vid from QQ URL: " + urlStr);
    }
    int durationSec = (int) (durationMs / 1000L);
    if (durationSec <= 0) {
      String infoStr = FetcherUtil.fetchString(client, INFO_URL + vid, HEADERS);
      Matcher dm = RE_DURATION.matcher(infoStr);
      if (!dm.find()) {
        throw new IOException("Cannot extract duration from QQ info response");
      }
      durationSec = Integer.parseInt(dm.group(1));
      if (durationSec <= 0) {
        throw new IOException("Invalid duration for QQ vid: " + vid);
      }
    }
    int segCount = (int) Math.ceil(durationSec / 60.0);
    return new QQSession(vid, segCount, client);
  }

  private static final class QQSession implements Fetcher.Session {

    private final String vid;
    private final int segCount;
    @Nullable
    private final OkHttpClient client;

    QQSession(String vid, int segCount, @Nullable OkHttpClient client) {
      this.vid = vid;
      this.segCount = segCount;
      this.client = client;
    }

    @Override
    public int segmentCount() {
      return segCount;
    }

    @Override
    public List<Danmaku> fetchSegment(int segNum) throws IOException {
      long startMs = (long) (segNum - 1) * 60_000L;
      String segUrl = String.format(Locale.US, SEGMENT_URL, vid, startMs, startMs + 60_000L);
      byte[] data = FetcherUtil.fetchBytes(client, segUrl, HEADERS);
      return QQParser.INSTANCE.parse(new ByteArrayInputStream(data));
    }
  }
}

