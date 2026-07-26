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
import androidx.media3.ui.danmaku.parser.IQIYIParser;
import com.google.common.collect.ImmutableMap;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.OkHttpClient;
import org.brotli.dec.BrotliInputStream;
import org.json.JSONException;
import org.json.JSONObject;

public final class IQIYIFetcher implements Fetcher {

  public static final IQIYIFetcher INSTANCE = new IQIYIFetcher();
  private static final Pattern RE_TVID = Pattern.compile("(?:\"tvId\"|\"tv_id\")\\s*:\\s*\"?(\\d{8,})\"?");
  private static final Pattern RE_DURATION = Pattern.compile("\"videoDuration\"\\s*:\\s*\"?(\\d+)\"?");
  private static final String ACCEL_JS_URL = "https://mesh.if.iqiyi.com/player/lw/lwplay/accelerator.js?apiVer=3";
  private static final String SEGMENT_URL = "https://cmts.iqiyi.com/bullet/%s/%s/%s_60_%d_%s.br";
  private static final ImmutableMap<String, String> HEADERS = FetcherUtil.headers("https://www.iqiyi.com", "https://www.iqiyi.com/");

  private static String resolveLink(String url, @Nullable OkHttpClient client) throws IOException {
    if (!url.contains("mesh.if.iqiyi.com")) {
      return url;
    }
    String json = FetcherUtil.fetchString(client, url, HEADERS);
    try {
      String link = new JSONObject(json).getJSONObject("data").optString("pageurl_iqiyi_pc", "");
      if (!link.isEmpty()) {
        return link;
      }
    } catch (JSONException ignored) {
    }
    throw new IOException("Cannot resolve mesh.if.iqiyi.com URL: " + url);
  }

  @Nullable
  private static String extractTvid(String js) {
    Matcher m = RE_TVID.matcher(js);
    return m.find() ? m.group(1) : null;
  }

  private static int extractDuration(String js) {
    Matcher m = RE_DURATION.matcher(js);
    if (!m.find()) {
      return 0;
    }
    try {
      return Integer.parseInt(m.group(1));
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  private static String generateToken(String tvid, int interval, int num) throws IOException {
    String input = tvid + "_" + interval + "_" + num + "cbzuw1259a";
    String hex = FetcherUtil.md5Hex(input);
    return hex.substring(hex.length() - 8);
  }

  @Override
  public boolean accepts(Uri uri) {
    String host = uri.getHost();
    return host != null && host.endsWith(".iqiyi.com");
  }

  @Override
  public Session prepare(Uri uri, OkHttpClient client, long durationMs) throws IOException {
    String pageUrl = resolveLink(uri.toString(), client);
    Map<String, String> refHeaders = new HashMap<>(HEADERS);
    refHeaders.put("Referer", pageUrl);
    String jsStr = FetcherUtil.fetchString(client, ACCEL_JS_URL, refHeaders);
    String tvid = extractTvid(jsStr);
    if (tvid == null || tvid.length() < 4) {
      throw new IOException("Cannot extract tvid from iQIYI accelerator.js");
    }
    int durationSec = (int) (durationMs / 1000L);
    if (durationSec <= 0) {
      durationSec = extractDuration(jsStr);
      if (durationSec <= 0) {
        throw new IOException("Cannot extract duration from iQIYI accelerator.js");
      }
    }
    int segCount = Math.max(1, (int) Math.ceil(durationSec / 60.0));
    return new IQIYISession(tvid, segCount, client);
  }

  private static final class IQIYISession implements Fetcher.Session {

    private final String tvid;
    private final int segCount;
    @Nullable
    private final OkHttpClient client;
    private final String dir1;
    private final String dir2;

    IQIYISession(String tvid, int segCount, @Nullable OkHttpClient client) {
      this.tvid = tvid;
      this.segCount = segCount;
      this.client = client;
      this.dir1 = tvid.substring(tvid.length() - 4, tvid.length() - 2);
      this.dir2 = tvid.substring(tvid.length() - 2);
    }

    @Override
    public int segmentCount() {
      return segCount;
    }

    @Override
    public List<Danmaku> fetchSegment(int segNum) throws IOException {
      String token = IQIYIFetcher.generateToken(tvid, 60, segNum);
      String segUrl = String.format(Locale.US, SEGMENT_URL, dir1, dir2, tvid, segNum, token);
      byte[] data = FetcherUtil.fetchBytes(client, segUrl, HEADERS);
      try (BrotliInputStream br = new BrotliInputStream(new ByteArrayInputStream(data))) {
        return IQIYIParser.INSTANCE.parse(br);
      }
    }
  }
}

