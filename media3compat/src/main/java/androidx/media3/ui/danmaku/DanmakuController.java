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
package androidx.media3.ui.danmaku;

import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.Player.DiscontinuityReason;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSourceUtil;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.ui.danmaku.fetcher.BiliFetcher;
import androidx.media3.ui.danmaku.fetcher.Fetcher;
import androidx.media3.ui.danmaku.fetcher.IQIYIFetcher;
import androidx.media3.ui.danmaku.fetcher.MGTVFetcher;
import androidx.media3.ui.danmaku.fetcher.QQFetcher;
import androidx.media3.ui.danmaku.fetcher.YoukuFetcher;
import androidx.media3.ui.danmaku.parser.BiliParser;
import androidx.media3.ui.danmaku.parser.IQIYIParser;
import androidx.media3.ui.danmaku.parser.MGTVParser;
import androidx.media3.ui.danmaku.parser.Parser;
import androidx.media3.ui.danmaku.parser.QQParser;
import androidx.media3.ui.danmaku.parser.TxtParser;
import androidx.media3.ui.danmaku.parser.YoukuParser;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.OkHttpClient;

public final class DanmakuController {

  public static final long DEFAULT_WINDOW_AHEAD_MS = 120_000;
  public static final long DEFAULT_WINDOW_BEHIND_MS = 10_000;
  public static final long DEFAULT_RELOAD_THRESHOLD_MS = 30_000;
  private static final int SNIFF_LENGTH = 512;
  private static final int DEFAULT_MAX_AHEAD_SEGMENTS = 2;
  private static final long LOAD_CHECK_INTERVAL_MS = 30_000;
  private static final long BACKWARD_FILL_DELAY_MS = 2_000;
  private static final long BACKWARD_FILL_SEEK_DELAY_MS = 200L;
  private static final long POSITION_POLL_INTERVAL_MS = 1_000L;
  private final PlayerListener playerListener;
  private final List<Parser> parsers = new ArrayList<>();
  private final List<Fetcher> fetchers = new ArrayList<>();
  private final Handler mainHandler = new Handler(Looper.getMainLooper());
  private final Set<Integer> loadedSegments = new HashSet<>();
  private final Set<Integer> failedSegments = new HashSet<>();
  private final AtomicInteger loadGeneration = new AtomicInteger();

  private final Runnable positionPollRunnable = new Runnable() {
    @Override
    public void run() {
      Player currentPlayer = player;
      if (!enabled || danmakuView == null || currentPlayer == null) {
        return;
      }
      if (currentPlayer.isPlaying()) {
        long positionMs = currentPlayer.getCurrentPosition();
        long sourceMs = positionMs - timeOffsetMs();
        boolean outsideWindow = sourceMs < loadedFrom || sourceMs > loadedTo;
        boolean nearWindowEnd = (loadedTo - sourceMs) < reloadThresholdMs;
        if (outsideWindow || nearWindowEnd) {
          extendWindowTo(positionMs, false);
        }
      }
      mainHandler.postDelayed(this, POSITION_POLL_INTERVAL_MS);
    }
  };

  @Nullable
  private Player player;
  @Nullable
  private DanmakuView danmakuView;
  private Danmaku[] sortedItems;
  private long loadedFrom;
  private long loadedTo;
  private long windowAheadMs;
  private long windowBehindMs;
  private long reloadThresholdMs;
  private boolean enabled = true;
  @Nullable
  private Listener listener;
  @Nullable
  private OkHttpClient okHttpClient;
  @Nullable
  private DataSource.Factory httpDataSourceFactory;
  @Nullable
  private HandlerThread loaderThread;
  @Nullable
  private Handler loaderHandler;
  @Nullable
  private Fetcher.Session activeSession;
  @Nullable
  private Uri activeUri;
  @Nullable
  private Uri dataSourceUri;
  private DanmakuConfig config;
  private int sessionSegDurationMs;
  private int sessionTotalSegs;
  private int nextForwardSeg;
  private int nextBackwardSeg;
  private boolean afterSeek;
  private boolean dataSourceDirty;

  public DanmakuController() {
    sortedItems = new Danmaku[0];
    invalidateWindow();
    windowAheadMs = DEFAULT_WINDOW_AHEAD_MS;
    windowBehindMs = DEFAULT_WINDOW_BEHIND_MS;
    reloadThresholdMs = DEFAULT_RELOAD_THRESHOLD_MS;
    config = DanmakuConfig.DEFAULT;
    playerListener = new PlayerListener();
    parsers.add(BiliParser.INSTANCE);
    parsers.add(TxtParser.INSTANCE);
    parsers.add(QQParser.INSTANCE);
    parsers.add(YoukuParser.INSTANCE);
    parsers.add(MGTVParser.INSTANCE);
    parsers.add(IQIYIParser.INSTANCE);
    fetchers.add(BiliFetcher.INSTANCE);
    fetchers.add(IQIYIFetcher.INSTANCE);
    fetchers.add(MGTVFetcher.INSTANCE);
    fetchers.add(QQFetcher.INSTANCE);
    fetchers.add(YoukuFetcher.INSTANCE);
  }

  private static List<Danmaku> subList(Danmaku[] sorted, long fromMs, long toMs) {
    if (sorted.length == 0 || fromMs > toMs) {
      return Collections.emptyList();
    }
    int lo = lowerBound(sorted, fromMs);
    int hi = upperBound(sorted, toMs);
    if (lo >= hi) {
      return Collections.emptyList();
    }
    return Arrays.asList(sorted).subList(lo, hi);
  }

  private static int lowerBound(Danmaku[] sorted, long target) {
    int lo = 0;
    int hi = sorted.length;
    while (lo < hi) {
      int mid = (lo + hi) >>> 1;
      if (sorted[mid].timeMs < target) {
        lo = mid + 1;
      } else {
        hi = mid;
      }
    }
    return lo;
  }

  private static int upperBound(Danmaku[] sorted, long target) {
    int lo = 0;
    int hi = sorted.length;
    while (lo < hi) {
      int mid = (lo + hi) >>> 1;
      if (sorted[mid].timeMs <= target) {
        lo = mid + 1;
      } else {
        hi = mid;
      }
    }
    return lo;
  }

  private static boolean isHttpUri(Uri uri) {
    String scheme = uri.getScheme();
    return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
  }

  public void setListener(@Nullable Listener listener) {
    this.listener = listener;
  }

  public void registerParser(Parser parser) {
    parsers.add(0, parser);
    markDataSourceDirty();
  }

  public void registerFetcher(Fetcher fetcher) {
    fetchers.add(0, fetcher);
    markDataSourceDirty();
  }

  public void setOkHttpClient(@Nullable OkHttpClient client) {
    if (okHttpClient == client) {
      return;
    }
    okHttpClient = client;
    httpDataSourceFactory = client != null ? new OkHttpDataSource.Factory(client) : null;
    if (dataSourceUri != null && isHttpUri(dataSourceUri)) {
      dataSourceDirty = true;
    }
  }

  public void setDataSource(@Nullable Uri uri) {
    if (uri == null) {
      clearItems();
      return;
    }
    if (isCurrentDataSource(uri)) {
      refreshCurrentDataSource();
      return;
    }
    for (Fetcher fetcher : fetchers) {
      if (fetcher.accepts(uri)) {
        setDataSource(uri, null);
        return;
      }
    }
    boolean http = isHttpUri(uri);
    if (http && httpDataSourceFactory != null) {
      setDataSource(uri, httpDataSourceFactory);
      return;
    }
    if (danmakuView == null) {
      throw new IllegalStateException(http ? "Call setOkHttpClient() before loading an HTTP/HTTPS URI" : "Call setView() before setDataSource(Uri) for non-HTTP URIs");
    }
    setDataSource(uri, new DefaultDataSource.Factory(danmakuView.getContext()));
  }

  public void setDataSource(Uri uri, @Nullable DataSource.Factory dataSourceFactory) {
    if (isCurrentDataSource(uri)) {
      refreshCurrentDataSource();
      return;
    }
    ensureLoaderThread();
    if (loaderHandler == null) {
      return;
    }
    cancelActiveSession();
    resetWindowState();
    if (danmakuView != null) {
      danmakuView.clear();
    }
    activeUri = uri;
    dataSourceUri = uri;
    dataSourceDirty = false;
    List<Parser> parserSnapshot = new ArrayList<>(parsers);
    List<Fetcher> fetcherSnapshot = new ArrayList<>(fetchers);
    int generation = loadGeneration.incrementAndGet();
    long startPositionMs = player != null ? player.getCurrentPosition() : 0L;
    long durationMs = playerDurationMs();
    loaderHandler.post(() -> loadDataSource(uri, dataSourceFactory, fetcherSnapshot, parserSnapshot, generation, startPositionMs, durationMs));
  }

  private long playerDurationMs() {
    if (player == null) {
      return 0L;
    }
    long d = player.getDuration();
    return d == C.TIME_UNSET ? 0L : d;
  }

  private void refreshCurrentDataSource() {
    if (player != null) {
      invalidateWindow();
      syncToPlayer(true);
    } else if (danmakuView != null) {
      invalidateWindow();
      extendWindowTo(0, true);
    }
  }

  public void setPlayer(@Nullable Player newPlayer) {
    if (player == newPlayer) {
      return;
    }
    detachPlayer();
    player = newPlayer;
    attachPlayer(newPlayer);
    restartPositionPolling();
  }

  private void detachPlayer() {
    if (player != null) {
      player.removeListener(playerListener);
    }
    stopView();
  }

  private void attachPlayer(@Nullable Player newPlayer) {
    if (newPlayer != null) {
      newPlayer.addListener(playerListener);
      if (enabled) {
        syncToPlayer(true);
      }
    }
  }

  public void setView(@Nullable DanmakuView newView) {
    if (danmakuView == newView) {
      return;
    }
    detachView(newView == null);
    danmakuView = newView;
    attachView(newView);
    restartPositionPolling();
  }

  private void detachView(boolean releaseLoading) {
    if (danmakuView == null) {
      return;
    }
    if (releaseLoading) {
      if (activeUri != null || activeSession != null) {
        markDataSourceDirty();
      }
      loadGeneration.incrementAndGet();
      cancelActiveSession();
      quitLoaderThread();
    }
    stopView();
  }

  private void attachView(@Nullable DanmakuView newView) {
    if (newView == null) {
      return;
    }
    newView.setConfig(config);
    newView.setDrawEnabled(enabled);
    if (dataSourceDirty && dataSourceUri != null) {
      setDataSource(dataSourceUri);
      return;
    }
    if (player != null && enabled) {
      invalidateWindow();
      syncToPlayer(true);
    }
  }

  private void stopView() {
    if (danmakuView != null) {
      danmakuView.stop();
    }
  }

  public void setItems(List<Danmaku> items) {
    dataSourceUri = null;
    dataSourceDirty = false;
    replaceItems(items);
  }

  private void setLoadedItems(Uri uri, List<Danmaku> items) {
    replaceItems(items);
    dataSourceUri = uri;
    dataSourceDirty = false;
  }

  private void replaceItems(List<Danmaku> items) {
    loadGeneration.incrementAndGet();
    cancelActiveSession();
    sortedItems = items.toArray(new Danmaku[0]);
    Arrays.sort(sortedItems, Danmaku.BY_TIME);
    invalidateWindow();
    if (player != null) {
      syncToPlayer(true);
    } else if (danmakuView != null) {
      extendWindowTo(0, true);
    }
  }

  public void clearItems() {
    dataSourceUri = null;
    dataSourceDirty = false;
    loadGeneration.incrementAndGet();
    cancelActiveSession();
    resetWindowState();
    if (danmakuView != null) {
      danmakuView.clear();
    }
  }

  public void sendNow(String text) {
    if (danmakuView != null) {
      danmakuView.sendNow(text);
    }
  }

  public void sendNow(Danmaku danmaku) {
    if (danmakuView != null) {
      danmakuView.sendNow(danmaku);
    }
  }

  public void setEnabled(boolean enabled) {
    if (this.enabled == enabled) {
      return;
    }
    this.enabled = enabled;
    if (danmakuView != null) {
      danmakuView.setDrawEnabled(enabled);
      if (!enabled) {
        stopView();
      }
    }
    if (enabled) {
      syncToPlayer(true);
    }
    restartPositionPolling();
  }

  public DanmakuConfig getConfig() {
    return config;
  }

  public void setConfig(DanmakuConfig config) {
    long oldOffset = this.config.timeOffsetMs;
    this.config = config;
    if (danmakuView == null) {
      return;
    }
    danmakuView.setConfig(config);
    if (oldOffset == config.timeOffsetMs || player == null) {
      return;
    }
    long positionMs = player.getCurrentPosition();
    if (activeSession != null) {
      resetSegmentCursors(positionMs);
    }
    remapWindow(positionMs);
  }

  public void setWindowParams(long aheadMs, long behindMs, long thresholdMs) {
    windowAheadMs = Math.max(0L, aheadMs);
    windowBehindMs = Math.max(0L, behindMs);
    reloadThresholdMs = Math.min(windowAheadMs, Math.max(0L, thresholdMs));
  }

  public void release() {
    loadGeneration.incrementAndGet();
    cancelActiveSession();
    mainHandler.removeCallbacks(positionPollRunnable);
    detachPlayer();
    player = null;
    danmakuView = null;
    dataSourceUri = null;
    dataSourceDirty = false;
    resetWindowState();
    quitLoaderThread();
  }

  private void syncToPlayer(boolean forceSeek) {
    if (!enabled || danmakuView == null || player == null) {
      return;
    }
    long positionMs = player.getCurrentPosition();
    danmakuView.setPlaybackSpeed(player.getPlaybackParameters().speed);
    if (forceSeek && activeSession != null) {
      resetSegmentCursors(positionMs);
      afterSeek = true;
    }
    if (forceSeek) {
      long sourceMs = positionMs - timeOffsetMs();
      if (sourceMs < loadedFrom || sourceMs > loadedTo) {
        extendWindowTo(positionMs, true);
      }
      if (player.isPlaying()) {
        if (!danmakuView.isStarted()) {
          danmakuView.start(positionMs);
        } else {
          danmakuView.seekTo(positionMs);
        }
      } else if (danmakuView.isStarted()) {
        danmakuView.seekTo(positionMs);
        if (!danmakuView.isPaused()) {
          danmakuView.pause();
        }
      }
      return;
    }
    extendWindowTo(positionMs, false);
    if (player.isPlaying()) {
      if (!danmakuView.isStarted()) {
        danmakuView.start(positionMs);
      } else if (danmakuView.isPaused()) {
        danmakuView.resume();
        danmakuView.syncPosition(positionMs);
      } else {
        danmakuView.syncPosition(positionMs);
      }
    } else if (danmakuView.isStarted() && !danmakuView.isPaused()) {
      danmakuView.pause();
      danmakuView.syncPosition(positionMs);
    }
  }

  private void extendWindowTo(long positionMs, boolean forceReload) {
    long sourceMs = positionMs - timeOffsetMs();
    long desiredTo = sourceMs + windowAheadMs;
    if (sortedItems.length == 0) {
      if (danmakuView != null && forceReload) {
        danmakuView.clear();
      }
      loadedFrom = sourceMs - windowBehindMs;
      loadedTo = desiredTo;
      return;
    }
    boolean outsideWindow = sourceMs < loadedFrom || sourceMs > loadedTo;
    boolean nearWindowEnd = (loadedTo - sourceMs) < reloadThresholdMs;
    if (!forceReload && !outsideWindow && !nearWindowEnd) {
      return;
    }
    if (forceReload || outsideWindow) {
      long newFrom = sourceMs - windowBehindMs;
      if (danmakuView != null) {
        danmakuView.clear();
        List<Danmaku> window = subList(sortedItems, newFrom, desiredTo);
        if (!window.isEmpty()) {
          danmakuView.addItems(window);
        }
      }
      loadedFrom = newFrom;
    } else {
      if (danmakuView != null) {
        List<Danmaku> extension = subList(sortedItems, loadedTo + 1, desiredTo);
        if (!extension.isEmpty()) {
          danmakuView.addItems(extension);
        }
      }
    }
    loadedTo = desiredTo;
  }

  private void remapWindow(long positionMs) {
    long sourceMs = positionMs - timeOffsetMs();
    loadedFrom = sourceMs - windowBehindMs;
    loadedTo = sourceMs + windowAheadMs;
    if (danmakuView != null) {
      danmakuView.replacePool(sortedItems.length == 0 ? Collections.emptyList() : subList(sortedItems, loadedFrom, loadedTo));
    }
  }

  private long timeOffsetMs() {
    return config.timeOffsetMs;
  }

  private int currentSegFromPosition(long positionMs) {
    int currentSeg = Math.max(1, (int) ((positionMs - timeOffsetMs()) / sessionSegDurationMs) + 1);
    return sessionTotalSegs > 0 ? Math.min(currentSeg, sessionTotalSegs) : currentSeg;
  }

  private void resetWindowState() {
    sortedItems = new Danmaku[0];
    invalidateWindow();
  }

  private void invalidateWindow() {
    loadedFrom = Long.MIN_VALUE;
    loadedTo = Long.MIN_VALUE;
  }

  private void resetSegmentCursors(long positionMs) {
    int currentSeg = currentSegFromPosition(positionMs);
    nextForwardSeg = currentSeg;
    nextBackwardSeg = currentSeg - 1;
    failedSegments.clear();
    scheduleNextLoad(loadGeneration.get());
  }

  private void mergeItems(List<Danmaku> newItems) {
    if (newItems.isEmpty()) {
      return;
    }
    Danmaku[] incoming = newItems.toArray(new Danmaku[0]);
    Arrays.sort(incoming, Danmaku.BY_TIME);
    Danmaku[] merged = new Danmaku[sortedItems.length + incoming.length];
    int i = 0, j = 0, k = 0;
    while (i < sortedItems.length && j < incoming.length) {
      if (sortedItems[i].timeMs <= incoming[j].timeMs) {
        merged[k++] = sortedItems[i++];
      } else {
        merged[k++] = incoming[j++];
      }
    }
    while (i < sortedItems.length) {
      merged[k++] = sortedItems[i++];
    }
    while (j < incoming.length) {
      merged[k++] = incoming[j++];
    }
    sortedItems = merged;
    if (loadedFrom == Long.MIN_VALUE) {
      if (player != null) {
        syncToPlayer(true);
      } else if (danmakuView != null) {
        extendWindowTo(0, true);
      }
      return;
    }
    if (danmakuView != null) {
      long posMs = player != null ? player.getCurrentPosition() : 0L;
      long sourceMs = posMs - timeOffsetMs();
      long freshTo = sourceMs + windowAheadMs;
      if (freshTo > loadedTo) {
        loadedTo = freshTo;
      }
      List<Danmaku> visible = subList(incoming, loadedFrom, loadedTo);
      if (!visible.isEmpty()) {
        danmakuView.addItems(visible);
      }
    }
  }

  private void ensureLoaderThread() {
    if (loaderThread == null) {
      loaderThread = new HandlerThread("DanmakuLoader");
      loaderThread.start();
      loaderHandler = new Handler(loaderThread.getLooper());
    }
  }

  private void restartPositionPolling() {
    mainHandler.removeCallbacks(positionPollRunnable);
    if (enabled && player != null && danmakuView != null) {
      mainHandler.postDelayed(positionPollRunnable, POSITION_POLL_INTERVAL_MS);
    }
  }

  private void quitLoaderThread() {
    if (loaderThread == null) {
      return;
    }
    loaderThread.quit();
    loaderThread = null;
    loaderHandler = null;
  }

  private void loadDataSource(Uri uri, @Nullable DataSource.Factory factory, List<Fetcher> localFetchers, List<Parser> localParsers, int generation, long startPositionMs, long durationMs) {
    try {
      for (Fetcher fetcher : localFetchers) {
        if (fetcher.accepts(uri)) {
          if (okHttpClient == null) {
            throw new IOException("OkHttpClient not set; call DanmakuController.setOkHttpClient() before fetching");
          }
          Fetcher.Session session = fetcher.prepare(uri, okHttpClient, durationMs);
          mainHandler.post(() -> {
            if (loadGeneration.get() != generation) {
              session.release();
              return;
            }
            onSessionPrepared(session, startPositionMs, generation);
          });
          return;
        }
      }
      if (factory == null) {
        throw new IOException("No fetcher accepted the URI and no DataSource.Factory was provided: " + uri);
      }
      DataSource dataSource = factory.createDataSource();
      try {
        dataSource.open(new DataSpec(uri));
        InputStream is = new BufferedInputStream(new ByteArrayInputStream(DataSourceUtil.readToEnd(dataSource)));
        @Nullable List<Danmaku> items = null;
        for (Parser parser : localParsers) {
          is.mark(SNIFF_LENGTH);
          boolean matched = parser.sniff(is, SNIFF_LENGTH);
          is.reset();
          if (matched) {
            items = parser.parse(is);
            break;
          }
        }
        List<Danmaku> result = items != null ? items : Collections.emptyList();
        mainHandler.post(() -> {
          if (loadGeneration.get() != generation) {
            return;
          }
          setLoadedItems(uri, result);
          if (listener != null) {
            listener.onLoadCompleted(uri, result.size());
          }
        });
      } finally {
        DataSourceUtil.closeQuietly(dataSource);
      }
    } catch (IOException e) {
      mainHandler.post(() -> {
        if (loadGeneration.get() != generation) {
          return;
        }
        if (uri.equals(dataSourceUri)) {
          dataSourceDirty = true;
        }
        activeUri = null;
        if (listener != null) {
          listener.onLoadError(uri, e);
        }
      });
    } catch (RuntimeException e) {
      IOException wrapped = new IOException("Unexpected runtime exception in fetcher", e);
      mainHandler.post(() -> {
        if (loadGeneration.get() != generation) {
          return;
        }
        if (uri.equals(dataSourceUri)) {
          dataSourceDirty = true;
        }
        activeUri = null;
        if (listener != null) {
          listener.onLoadError(uri, wrapped);
        }
      });
    }
  }

  private void onSessionPrepared(Fetcher.Session session, long startPositionMs, int generation) {
    if (activeSession != null) {
      activeSession.release();
    }
    activeSession = session;
    sessionSegDurationMs = session.segmentDurationMs();
    sessionTotalSegs = session.segmentCount();
    loadedSegments.clear();
    resetWindowState();
    if (danmakuView != null) {
      danmakuView.clear();
    }
    int currentSeg = currentSegFromPosition(startPositionMs);
    nextForwardSeg = currentSeg;
    nextBackwardSeg = currentSeg - 1;
    scheduleNextLoad(generation);
  }

  private void scheduleNextLoad(int generation) {
    if (loaderHandler == null || activeSession == null || loadGeneration.get() != generation) {
      return;
    }
    int currentSeg = player != null ? currentSegFromPosition(player.getCurrentPosition()) : 1;
    int aheadLimit = currentSeg + DEFAULT_MAX_AHEAD_SEGMENTS - 1;
    while (nextForwardSeg <= aheadLimit && loadedSegments.contains(nextForwardSeg)) {
      nextForwardSeg++;
    }
    if (nextForwardSeg <= Math.min(aheadLimit, sessionTotalSegs)) {
      int seg = nextForwardSeg++;
      final Fetcher.Session sessionRef = activeSession;
      loaderHandler.post(() -> fetchAndDeliver(sessionRef, seg, generation, true));
    } else if (nextForwardSeg <= sessionTotalSegs) {
      loaderHandler.postDelayed(() -> {
        if (loadGeneration.get() == generation) {
          mainHandler.post(() -> scheduleNextLoad(generation));
        }
      }, LOAD_CHECK_INTERVAL_MS);
    } else if (nextBackwardSeg >= 1) {
      while (nextBackwardSeg >= 1 && loadedSegments.contains(nextBackwardSeg)) {
        nextBackwardSeg--;
      }
      if (nextBackwardSeg >= 1) {
        int seg = nextBackwardSeg--;
        final Fetcher.Session sessionRef = activeSession;
        long delay = afterSeek ? BACKWARD_FILL_SEEK_DELAY_MS : BACKWARD_FILL_DELAY_MS;
        loaderHandler.postDelayed(() -> fetchAndDeliver(sessionRef, seg, generation, true), delay);
      } else {
        afterSeek = false;
        scheduleRetry(generation);
      }
    } else {
      afterSeek = false;
      scheduleRetry(generation);
    }
  }

  private void scheduleRetry(int generation) {
    if (failedSegments.isEmpty()) {
      return;
    }
    int seg = failedSegments.iterator().next();
    failedSegments.remove(seg);
    final Fetcher.Session sessionRef = activeSession;
    loaderHandler.postDelayed(() -> fetchAndDeliver(sessionRef, seg, generation, false), BACKWARD_FILL_DELAY_MS);
  }

  private void fetchAndDeliver(Fetcher.Session session, int segNum, int generation, boolean trackFailure) {
    if (loadGeneration.get() != generation) {
      return;
    }
    List<Danmaku> items;
    boolean failed = false;
    try {
      items = session.fetchSegment(segNum);
    } catch (IOException e) {
      items = Collections.emptyList();
      failed = true;
    }
    final boolean isFailed = failed;
    final List<Danmaku> finalItems = items;
    mainHandler.post(() -> {
      if (loadGeneration.get() != generation) {
        return;
      }
      if (trackFailure && isFailed) {
        failedSegments.add(segNum);
        scheduleNextLoad(generation);
        return;
      }
      onSegmentDelivered(session, segNum, finalItems, generation);
    });
  }

  private void onSegmentDelivered(Fetcher.Session session, int segNum, List<Danmaku> items, int generation) {
    loadedSegments.add(segNum);
    if (listener != null && activeUri != null) {
      listener.onLoadProgress(activeUri, loadedSegments.size(), sessionTotalSegs);
    }
    if (!items.isEmpty()) {
      mergeItems(items);
    }
    if (loadedSegments.size() >= sessionTotalSegs && activeUri != null) {
      int total = sortedItems.length;
      if (listener != null) {
        listener.onLoadCompleted(activeUri, total);
      }
      session.release();
      if (activeSession == session) {
        activeSession = null;
      }
      activeUri = null;
      return;
    }
    scheduleNextLoad(generation);
  }

  private void cancelActiveSession() {
    if (activeSession != null) {
      activeSession.release();
      activeSession = null;
    }
    loadedSegments.clear();
    failedSegments.clear();
    afterSeek = false;
    activeUri = null;
  }

  private boolean isCurrentDataSource(Uri uri) {
    return !dataSourceDirty && uri.equals(dataSourceUri);
  }

  private void markDataSourceDirty() {
    if (dataSourceUri != null) {
      dataSourceDirty = true;
    }
  }

  public interface Listener {

    default void onLoadCompleted(Uri uri, int count) {
    }

    default void onLoadProgress(Uri uri, int loaded, int total) {
    }

    default void onLoadError(Uri uri, IOException error) {
    }
  }

  private final class PlayerListener implements Player.Listener {

    @Override
    public void onMediaItemTransition(@Nullable MediaItem mediaItem, @Player.MediaItemTransitionReason int reason) {
      if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
        return;
      }
      if (activeUri == null) {
        clearItems();
      }
    }

    @Override
    public void onPlaybackStateChanged(@Player.State int playbackState) {
      syncToPlayer(false);
    }

    @Override
    public void onPlayWhenReadyChanged(boolean playWhenReady, @Player.PlayWhenReadyChangeReason int reason) {
      syncToPlayer(false);
    }

    @Override
    public void onPlaybackParametersChanged(@NonNull PlaybackParameters playbackParameters) {
      if (danmakuView != null) {
        danmakuView.setPlaybackSpeed(playbackParameters.speed);
      }
    }

    @Override
    public void onPositionDiscontinuity(@NonNull Player.PositionInfo oldPosition, @NonNull Player.PositionInfo newPosition, @DiscontinuityReason int reason) {
      syncToPlayer(true);
    }
  }
}

