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
import androidx.media3.ui.danmaku.parser.BiliParser;
import com.google.common.collect.ImmutableMap;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.OkHttpClient;

public final class BiliFetcher implements Fetcher {

  public static final BiliFetcher INSTANCE = new BiliFetcher();
  private static final Pattern RE_CID = Pattern.compile("\"cid\"\\s*:\\s*(\\d+)");
  private static final String COMMENT_URL = "https://comment.bilibili.com/%s.xml";
  private static final ImmutableMap<String, String> HEADERS = FetcherUtil.headers("https://www.bilibili.com", "https://www.bilibili.com/");

  @Override
  public boolean accepts(Uri uri) {
    String host = uri.getHost();
    return "www.bilibili.com".equals(host) || "m.bilibili.com".equals(host);
  }

  @Override
  public Session prepare(Uri uri, OkHttpClient client, long durationMs) throws IOException {
    String pageHtml = FetcherUtil.fetchString(client, uri.toString(), HEADERS);
    Matcher m = RE_CID.matcher(pageHtml);
    if (!m.find()) {
      throw new IOException("Cannot extract cid from Bilibili page: " + uri);
    }
    return new BiliSession(m.group(1), client);
  }

  private static final class BiliSession implements Fetcher.Session {

    private final String cid;
    @Nullable
    private final OkHttpClient client;

    BiliSession(String cid, @Nullable OkHttpClient client) {
      this.cid = cid;
      this.client = client;
    }

    @Override
    public int segmentCount() {
      return 1;
    }

    @Override
    public List<Danmaku> fetchSegment(int segNum) throws IOException {
      byte[] data = FetcherUtil.fetchBytes(client, String.format(Locale.US, COMMENT_URL, cid), HEADERS);
      return BiliParser.INSTANCE.parse(new ByteArrayInputStream(data));
    }
  }
}

