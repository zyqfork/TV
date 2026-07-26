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
import android.util.Base64;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.util.Util;
import androidx.media3.ui.danmaku.Danmaku;
import androidx.media3.ui.danmaku.parser.YoukuParser;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONException;
import org.json.JSONObject;

public final class YoukuFetcher implements Fetcher {

  public static final YoukuFetcher INSTANCE = new YoukuFetcher();
  private static final Pattern RE_VID_1 = Pattern.compile("/id_([^./?]+)\\.html");
  private static final Pattern RE_VID_2 = Pattern.compile("[?&]vid=([^&]+)");
  private static final Pattern RE_VID_S = Pattern.compile("[?&]s=([^&]+)");
  private static final String UA = FetcherUtil.UA;
  private static final String GUID = "NJnMGnrls3wCAXQaiNsMGIsY";
  private static final String DANMU_SECRET = "MkmC9SoIw6xCkSKHhJ7b5D2r51kBiREr";

  private static List<Danmaku> fetchSegment(String vid, int num, String token, OkHttpClient client) throws IOException {
    long timestamp = System.currentTimeMillis();
    String msgSrc = String.format(Locale.US, "{\"ctime\": %d, \"ctype\": 10004, \"cver\": \"v1.0\", \"guid\": \"%s\", \"mat\": %d, \"mcount\": 1, \"pid\": 0, \"sver\": \"3.1.0\", \"vid\": \"%s\"}", timestamp, GUID, num, vid);
    String msg = Base64.encodeToString(Util.getUtf8Bytes(msgSrc), Base64.NO_WRAP);
    String dataSign = FetcherUtil.md5Hex(msg + DANMU_SECRET);
    String dataStr = String.format(Locale.US, "{\"pid\": 0, \"ctype\": 10004, \"sver\": \"3.1.0\", \"cver\": \"v1.0\", \"ctime\": %d, \"guid\": \"%s\", \"vid\": \"%s\", \"mat\": %d, \"mcount\": 1, \"type\": 1, \"msg\": \"%s\", \"sign\": \"%s\"}", timestamp, GUID, vid, num, msg, dataSign);
    String signSrc = token + "&" + timestamp + "&24679788&" + dataStr;
    String sign = FetcherUtil.md5Hex(signSrc);
    String postUrl = "https://acs.youku.com/h5/mopen.youku.danmu.list/1.0/?jsv=2.7.0&appKey=24679788&t=" + timestamp + "&api=mopen.youku.danmu.list&v=1.0&type=originaljson&dataType=jsonp&timeout=20000&jsonpIncPrefix=utility&sign=" + sign;
    String encodedData = Uri.encode(dataStr);
    String body = "data=" + encodedData;
    String responseStr = httpPost(client, postUrl, body);
    String innerJson;
    try {
      innerJson = new JSONObject(responseStr).getJSONObject("data").getString("result");
    } catch (JSONException e) {
      throw new IOException("Unexpected Youku danmaku API response format", e);
    }
    try {
      if (new JSONObject(innerJson).optInt("code", -1) != 1) {
        return Collections.emptyList();
      }
    } catch (JSONException ignored) {
    }
    return YoukuParser.INSTANCE.parse(new ByteArrayInputStream(Util.getUtf8Bytes(innerJson)));
  }

  @Nullable
  private static String extractVidFromUrl(String url) {
    Matcher m1 = RE_VID_1.matcher(url);
    if (m1.find()) {
      return m1.group(1);
    }
    Matcher m2 = RE_VID_2.matcher(url);
    if (m2.find()) {
      return m2.group(1);
    }
    return null;
  }

  @Nullable
  private static String extractVidFromPage(String url, OkHttpClient client) throws IOException {
    String html = httpGet(client, url);
    Matcher m = RE_VID_1.matcher(html);
    return m.find() ? m.group(1) : null;
  }

  private static String httpGet(OkHttpClient client, String url) throws IOException {
    Request request = new Request.Builder().url(url).header("User-Agent", UA).build();
    try (Response response = client.newCall(request).execute()) {
      return response.body().string();
    }
  }

  private static String httpPost(OkHttpClient client, String url, String formBody) throws IOException {
    RequestBody body = RequestBody.create(formBody, MediaType.get("application/x-www-form-urlencoded"));
    Request request = new Request.Builder()
        .url(url)
        .header("User-Agent", UA)
        .addHeader("Referer", "https://v.youku.com")
        .post(body)
        .build();
    try (Response response = client.newCall(request).execute()) {
      return response.body().string();
    }
  }

  @Override
  public boolean accepts(Uri uri) {
    String host = uri.getHost();
    return host != null && host.endsWith(".youku.com");
  }

  @Override
  public Session prepare(Uri uri, OkHttpClient client, long durationMs) throws IOException {
    String urlStr = uri.toString();
    List<Cookie> sessionCookies = new ArrayList<>();
    CookieJar cookieJar = new CookieJar() {
      @Override
      public void saveFromResponse(@NonNull HttpUrl url, List<Cookie> cookies) {
        for (Cookie newCookie : cookies) {
          for (int i = sessionCookies.size() - 1; i >= 0; i--) {
            if (sessionCookies.get(i).name().equals(newCookie.name())) {
              sessionCookies.remove(i);
            }
          }
          sessionCookies.add(newCookie);
        }
      }

      @NonNull
      @Override
      public List<Cookie> loadForRequest(@NonNull HttpUrl url) {
        return new ArrayList<>(sessionCookies);
      }
    };
    OkHttpClient sessionClient = client.newBuilder().cookieJar(cookieJar).build();
    String vid;
    if (RE_VID_S.matcher(urlStr).find()) {
      vid = extractVidFromPage(urlStr, sessionClient);
    } else {
      vid = extractVidFromUrl(urlStr);
    }
    if (vid == null) {
      throw new IOException("Cannot extract vid from Youku URL: " + urlStr);
    }
    double durationSec = durationMs / 1000.0;
    if (durationSec <= 0) {
      String infoUrl = "https://openapi.youku.com/v2/videos/show.json?client_id=53e6cc67237fc59a&video_id=" + vid;
      String infoStr = httpGet(sessionClient, infoUrl);
      try {
        durationSec = Double.parseDouble(new JSONObject(infoStr).getString("duration"));
      } catch (JSONException | NumberFormatException e) {
        throw new IOException("Cannot parse duration from Youku OpenAPI response", e);
      }
      if (durationSec <= 0) {
        throw new IOException("Invalid duration for Youku vid: " + vid);
      }
    }
    String cookieUrl = "https://acs.youku.com/h5/mtop.youku.favorite.query.isfavorite/1.0/?appKey=24679788";
    httpGet(sessionClient, cookieUrl);
    String tkValue = null;
    for (Cookie cookie : sessionCookies) {
      if ("_m_h5_tk".equals(cookie.name())) {
        tkValue = cookie.value();
        break;
      }
    }
    if (tkValue == null) {
      throw new IOException("Cannot obtain _m_h5_tk cookie from Youku");
    }
    String token = tkValue.split("_")[0];
    int maxNum = (int) Math.ceil(durationSec / 60.0);
    return new YoukuSession(vid, maxNum, token, sessionClient);
  }

  private static final class YoukuSession implements Fetcher.Session {

    private final String vid;
    private final int maxNum;
    private final String token;
    private final OkHttpClient client;

    YoukuSession(String vid, int maxNum, String token, OkHttpClient client) {
      this.vid = vid;
      this.maxNum = maxNum;
      this.token = token;
      this.client = client;
    }

    @Override
    public int segmentCount() {
      return maxNum;
    }

    @Override
    public List<Danmaku> fetchSegment(int segNum) throws IOException {
      return YoukuFetcher.fetchSegment(vid, segNum - 1, token, client);
    }
  }
}

