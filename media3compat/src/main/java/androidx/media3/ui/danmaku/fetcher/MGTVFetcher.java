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
import androidx.media3.ui.danmaku.parser.MGTVParser;
import com.google.common.collect.ImmutableMap;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.OkHttpClient;
import org.json.JSONException;
import org.json.JSONObject;

public final class MGTVFetcher implements Fetcher {

  public static final MGTVFetcher INSTANCE = new MGTVFetcher();
  private static final Pattern RE_URL = Pattern.compile("mgtv\\.com/b/(\\d+)/(\\d+)\\.html");
  private static final Pattern RE_NUXT_BLOCK = Pattern.compile("<script[^>]*>window\\.__NUXT__=([\\s\\S]{1,51200}?)</script>");
  private static final Pattern RE_DURATION = Pattern.compile("(\\d{1,2}:\\d{2}:\\d{2}|\\d+:\\d{2})");
  private static final String CDN_INFO_URL = "https://galaxy.bz.mgtv.com/getctlbarrage?version=8.1.39&abroad=0&os=10.0&platform=0&deviceid=5408f92e-8772-4f65-a8ec-1699b6764bcb&vid=%s&cid=%s";
  private static final String FALLBACK_SEGMENT_URL = "https://galaxy.bz.mgtv.com/cdn/opbarrage?vid=%s&cid=%s&time=%d";
  private static final ImmutableMap<String, String> HEADERS = FetcherUtil.headers("https://www.mgtv.com", "https://www.mgtv.com/");

  private static int extractDuration(String html) {
    Matcher nuxtMatcher = RE_NUXT_BLOCK.matcher(html);
    if (nuxtMatcher.find()) {
      String block = nuxtMatcher.group(1);
      Matcher durMatcher = RE_DURATION.matcher(block);
      if (durMatcher.find()) {
        return parseDurationSec(durMatcher.group(1));
      }
    }
    return 0;
  }

  private static int parseDurationSec(String s) {
    String[] parts = s.split(":");
    try {
      if (parts.length == 3) {
        return Integer.parseInt(parts[0]) * 3600 + Integer.parseInt(parts[1]) * 60 + Integer.parseInt(parts[2]);
      } else if (parts.length == 2) {
        return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
      }
    } catch (NumberFormatException ignored) {
    }
    return 0;
  }

  @Nullable
  private static String parseCdnBase(String json) {
    try {
      JSONObject data = new JSONObject(json).optJSONObject("data");
      if (data == null) {
        return null;
      }
      String cdnList = data.optString("cdn_list", "");
      String cdnVer = data.optString("cdn_version", "");
      if (cdnList.isEmpty() || cdnVer.isEmpty()) {
        return null;
      }
      for (String cdn : cdnList.split(",")) {
        String host = cdn.trim();
        if (host.isEmpty()) {
          continue;
        }
        host = host.replaceAll("/+$", "");
        String base = host + "/" + cdnVer;
        if (!base.startsWith("http")) {
          base = "https://" + base;
        }
        if (base.startsWith("https://")) {
          return base;
        }
      }
    } catch (JSONException ignored) {
    }
    return null;
  }

  @Override
  public boolean accepts(Uri uri) {
    String host = uri.getHost();
    return host != null && host.endsWith(".mgtv.com");
  }

  @Override
  public Session prepare(Uri uri, OkHttpClient client, long durationMs) throws IOException {
    String urlStr = uri.toString();
    Matcher m = RE_URL.matcher(urlStr);
    if (!m.find()) {
      throw new IOException("Not an MGTV URL: " + urlStr);
    }
    String cid = m.group(1);
    String vid = m.group(2);
    int durationSec = (int) (durationMs / 1000L);
    if (durationSec <= 0) {
      String pageHtml = FetcherUtil.fetchString(client, urlStr, HEADERS);
      durationSec = extractDuration(pageHtml);
      if (durationSec <= 0) {
        throw new IOException("Cannot extract duration from MGTV page: " + urlStr);
      }
    }
    @Nullable String segBaseUrl = null;
    try {
      String cdnJson = FetcherUtil.fetchString(client, String.format(Locale.US, CDN_INFO_URL, vid, cid), HEADERS);
      segBaseUrl = parseCdnBase(cdnJson);
    } catch (IOException ignored) {
    }
    int maxNum = (int) Math.ceil(durationSec / 60.0);
    return new MGTVSession(vid, cid, maxNum, segBaseUrl, client);
  }

  private static final class MGTVSession implements Fetcher.Session {

    private final String vid;
    private final String cid;
    private final int maxNum;
    @Nullable
    private final String segBaseUrl;
    @Nullable
    private final OkHttpClient client;

    MGTVSession(String vid, String cid, int maxNum, @Nullable String segBaseUrl, @Nullable OkHttpClient client) {
      this.vid = vid;
      this.cid = cid;
      this.maxNum = maxNum;
      this.segBaseUrl = segBaseUrl;
      this.client = client;
    }

    @Override
    public int segmentCount() {
      return maxNum;
    }

    @Override
    public List<Danmaku> fetchSegment(int segNum) throws IOException {
      String segUrl = (segBaseUrl != null) ? segBaseUrl + "/" + (segNum - 1) + ".json" : String.format(Locale.US, FALLBACK_SEGMENT_URL, vid, cid, (long) (segNum - 1) * 60_000L);
      byte[] data = FetcherUtil.fetchBytes(client, segUrl, HEADERS);
      return MGTVParser.INSTANCE.parse(new ByteArrayInputStream(data));
    }
  }
}

