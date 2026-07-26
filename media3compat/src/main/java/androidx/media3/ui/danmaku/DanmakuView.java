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

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.util.Util;
import androidx.media3.ui.danmaku.bean.PaintColorState;
import androidx.media3.ui.danmaku.bean.PlaybackClock;
import androidx.media3.ui.danmaku.bean.ShadowPaintState;
import androidx.media3.ui.danmaku.parser.TxtParser;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class DanmakuView extends View {

  private static final float DARK_LUMINANCE_THRESHOLD = 0.5f;
  private static final long MAX_ACTIVATION_WINDOW_MS = 3000;
  private static final float COLORFUL_SATURATION = 0.55f;
  private static final float COLORFUL_VALUE = 1f;
  private static final float GRADIENT_HUE_OFFSET = 117f;
  private final Object lock = new Object();
  private final TreeMap<Long, List<Danmaku>> pool = new TreeMap<>();
  private final List<Danmaku> activeItems = new ArrayList<>(200);
  private final List<Danmaku> activationScratch = new ArrayList<>(64);
  private final Paint fillPaint;
  private final Paint strokePaint;
  private final Matrix gradientMatrix = new Matrix();
  private final AtomicInteger poolGeneration = new AtomicInteger();
  private final float[] hsvTmp = new float[3];
  private final PaintColorState fillState = new PaintColorState();
  private final PaintColorState strokeState = new PaintColorState();
  private final PlaybackClock clock = new PlaybackClock();
  private final ShadowPaintState shadowState = new ShadowPaintState();
  private float currentPaintSizePx;
  private DanmakuConfig config;
  private float textSizePx;
  private float trackHeight;
  private Danmaku[] scrollTrackTails = new Danmaku[0];
  private Danmaku[] reverseTrackTails = new Danmaku[0];
  private long[] topTrackExpiries = new long[0];
  private long[] bottomTrackExpiries = new long[0];
  private int scrollTrackCount;
  private int topTrackCount;
  private int bottomTrackCount;
  private int nextScrollTrackIndex;
  private int nextReverseTrackIndex;
  private int nextTopTrackIndex;
  private int nextBottomTrackIndex;
  private boolean drawEnabled = true;
  private float lastStrokeWidth = -1f;
  private float defaultAscent;
  private float defaultDescent;
  private long lastActivationPositionMs = Long.MIN_VALUE;
  @Nullable
  private HandlerThread backgroundThread;
  @Nullable
  private Handler backgroundHandler;
  private boolean released;
  private int viewWidth;
  private int viewHeight;

  public DanmakuView(Context context) {
    this(context, null);
  }

  public DanmakuView(Context context, @Nullable AttributeSet attrs) {
    this(context, attrs, 0);
  }

  public DanmakuView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
    fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    fillPaint.setStyle(Paint.Style.FILL);
    strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    strokePaint.setStyle(Paint.Style.STROKE);
    config = DanmakuConfig.DEFAULT;
    updatePaintConfig();
    applyLayerTypeForStyle();
  }

  private static int getConfiguredTrackCount(int autoCount, int configuredCount) {
    if (configuredCount <= 0) {
      return Math.max(1, autoCount);
    }
    return Math.max(1, Math.min(autoCount, configuredCount));
  }

  private static int computeShadowColor(@androidx.annotation.ColorInt int textColor) {
    float r = Color.red(textColor) / 255f;
    float g = Color.green(textColor) / 255f;
    float b = Color.blue(textColor) / 255f;
    float luminance = 0.2126f * r + 0.7152f * g + 0.0722f * b;
    return luminance < DARK_LUMINANCE_THRESHOLD ? Color.WHITE : Color.BLACK;
  }

  private static int applyAlpha(@androidx.annotation.ColorInt int color, float alpha) {
    int clampedAlpha = (int) (255 * Util.constrainValue(alpha, 0f, 1f));
    return (color & 0x00FFFFFF) | (clampedAlpha << 24);
  }

  private static float hueOf(String text) {
    return (Math.abs(text.hashCode()) * 137.508f) % 360f;
  }

  private int hsvColor(float hue) {
    hsvTmp[0] = ((hue % 360f) + 360f) % 360f;
    hsvTmp[1] = COLORFUL_SATURATION;
    hsvTmp[2] = COLORFUL_VALUE;
    return Color.HSVToColor(hsvTmp);
  }

  private int colorfulColor(String text) {
    return hsvColor(hueOf(text));
  }

  private boolean shouldForceWhite(Danmaku d) {
    if (config.colorMode != DanmakuConfig.COLOR_MODE_COLORFUL && config.colorMode != DanmakuConfig.COLOR_MODE_GRADIENT) {
      return false;
    }
    return (Math.abs(d.text.hashCode()) % 5) == 0;
  }

  private void applyLayerTypeForStyle() {
    int desired = config.styleMode == DanmakuConfig.STYLE_SHADOW
        ? LAYER_TYPE_SOFTWARE : LAYER_TYPE_HARDWARE;
    if (getLayerType() != desired) {
      setLayerType(desired, null);
    }
  }

  public DanmakuConfig getConfig() {
    return config;
  }

  public void setConfig(DanmakuConfig config) {
    if (released) {
      return;
    }
    DanmakuConfig oldConfig = this.config;
    this.config = config;
    updatePaintConfig();
    applyLayerTypeForStyle();
    if (oldConfig.styleMode == DanmakuConfig.STYLE_SHADOW
        && config.styleMode != DanmakuConfig.STYLE_SHADOW) {
      shadowState.clearIfSet(fillPaint);
    }
    if (oldConfig.timeOffsetMs != config.timeOffsetMs) {
      long newCursor = getCurrentPositionMs() - config.timeOffsetMs - 1;
      lastActivationPositionMs = Math.max(Long.MIN_VALUE + 1, Math.min(lastActivationPositionMs, newCursor));
    }
    remeasurePool();
    remeasureActiveItems();
    for (int i = 0; i < activeItems.size(); i++) {
      activeItems.get(i).cachedStrokeColor = 0;
    }
    recalculateTracks();
    reconcileActiveItemsToTracks();
    requestRedrawForExternalChange();
  }

  public void addItems(List<Danmaku> items) {
    if (items.isEmpty()) {
      return;
    }
    if (released) {
      return;
    }
    List<Danmaku> copy = new ArrayList<>(items);
    float defaultSizePx = textSizePx;
    int generation = poolGeneration.get();
    postToBackground(() -> {
      Paint measurePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
      for (int i = 0; i < copy.size(); i++) {
        Danmaku d = copy.get(i);
        float sizePx = d.textSizeSp > 0 ? resolveTextSizePx(d.textSizeSp) : defaultSizePx;
        measurePaint.setTextSize(sizePx);
        d.measuredWidth = measurePaint.measureText(d.text);
      }
      synchronized (lock) {
        if (poolGeneration.get() != generation) {
          return;
        }
        for (int i = 0; i < copy.size(); i++) {
          Danmaku d = copy.get(i);
          List<Danmaku> bucket = pool.get(d.timeMs);
          if (bucket == null) {
            bucket = new ArrayList<>();
            pool.put(d.timeMs, bucket);
          }
          bucket.add(d);
        }
      }
      if (clock.started && !clock.paused) {
        postInvalidate();
      }
    });
  }

  public void start(long positionMs) {
    clock.rebase(positionMs);
    resetActivationCursor(positionMs);
    clock.started = true;
    clock.paused = false;
    clearActiveItems();
    postInvalidateOnAnimation();
  }

  public void pause() {
    if (!clock.started || clock.paused) {
      return;
    }
    clock.rebase(getCurrentPositionMs());
    clock.paused = true;
  }

  public void resume() {
    if (!clock.started || !clock.paused) {
      return;
    }
    clock.unpause();
    postInvalidateOnAnimation();
  }

  public void seekTo(long positionMs) {
    clock.rebase(positionMs);
    resetActivationCursor(positionMs);
    clearActiveItems();
    if (clock.started && !clock.paused) {
      postInvalidateOnAnimation();
    }
  }

  public void syncPosition(long positionMs) {
    clock.rebase(positionMs);
  }

  public void setPlaybackSpeed(float speed) {
    if (clock.started && !clock.paused) {
      clock.rebase(getCurrentPositionMs());
    }
    clock.playbackSpeed = Math.max(0.01f, speed);
  }

  public void stop() {
    clock.started = false;
    clock.paused = false;
    clearActiveItems();
    invalidate();
  }

  public void sendNow(String text) {
    boolean looksLikeTxt = text.length() > 1 && text.charAt(0) == '[' && Character.isDigit(text.charAt(1));
    Danmaku danmaku = looksLikeTxt ? TxtParser.parseLine(text) : null;
    sendNow(danmaku != null ? danmaku : new Danmaku(text, 0));
  }

  public void sendNow(Danmaku danmaku) {
    if (released || !clock.started || viewWidth <= 0) {
      return;
    }
    float sizePx = danmaku.textSizeSp > 0 ? resolveTextSizePx(danmaku.textSizeSp) : textSizePx;
    fillPaint.setTextSize(sizePx);
    danmaku.measuredWidth = fillPaint.measureText(danmaku.text);
    fillPaint.setTextSize(textSizePx);
    long currentMs = getCurrentPositionMs();
    if (!tryActivate(danmaku, currentMs)) {
      forceSendActivate(danmaku, currentMs);
    }
    if (danmaku.active) {
      activeItems.add(danmaku);
      if (!clock.paused) {
        postInvalidateOnAnimation();
      }
    }
  }

  public void clear() {
    clearPool();
    clearActiveItems();
    lastActivationPositionMs = Long.MIN_VALUE;
    invalidate();
  }

  public void replacePool(List<Danmaku> items) {
    if (released) {
      return;
    }
    clearPool();
    if (!items.isEmpty()) {
      addItems(items);
    }
  }

  private void clearPool() {
    poolGeneration.incrementAndGet();
    synchronized (lock) {
      pool.clear();
    }
  }

  public void release() {
    released = true;
    poolGeneration.incrementAndGet();
    stop();
    synchronized (lock) {
      pool.clear();
    }
    shutdownBackgroundThread();
  }

  public boolean isStarted() {
    return clock.started;
  }

  public boolean isPaused() {
    return clock.paused;
  }

  @Override
  protected void onSizeChanged(int w, int h, int oldw, int oldh) {
    viewWidth = w;
    viewHeight = h;
    resetActivationCursor(getCurrentPositionMs());
    clearActiveItems();
    recalculateTracks();
    if (clock.started && !clock.paused) {
      postInvalidateOnAnimation();
    }
  }

  private void resetActivationCursor(long positionMs) {
    lastActivationPositionMs = Math.max(Long.MIN_VALUE + 1, positionMs - config.timeOffsetMs - 1);
  }

  private void requestRedrawForExternalChange() {
    if (!clock.started) {
      invalidate();
    } else if (clock.paused) {
      invalidate();
    } else {
      postInvalidateOnAnimation();
    }
  }

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    ensureBackgroundThread();
  }

  @Override
  protected void onDetachedFromWindow() {
    super.onDetachedFromWindow();
    stop();
    shutdownBackgroundThread();
  }

  public boolean isDrawEnabled() {
    return drawEnabled;
  }

  public void setDrawEnabled(boolean drawEnabled) {
    this.drawEnabled = drawEnabled;
    if (drawEnabled) {
      postInvalidateOnAnimation();
    } else {
      invalidate();
    }
  }

  @Override
  protected void onDraw(@NonNull Canvas canvas) {
    if (!clock.started || viewWidth <= 0 || viewHeight <= 0) {
      return;
    }
    long currentMs = getCurrentPositionMs();
    currentPaintSizePx = textSizePx;
    fillPaint.setTextSize(textSizePx);
    strokePaint.setTextSize(textSizePx);
    activateNewItems(currentMs);
    int writeIdx = 0;
    for (int i = 0; i < activeItems.size(); i++) {
      Danmaku d = activeItems.get(i);
      boolean alive = drawDanmaku(drawEnabled ? canvas : null, d, currentMs);
      if (alive) {
        if (writeIdx != i) {
          activeItems.set(writeIdx, d);
        }
        writeIdx++;
      } else {
        d.active = false;
        d.trackIndex = -1;
      }
    }
    if (activeItems.size() > writeIdx) {
      activeItems.subList(writeIdx, activeItems.size()).clear();
    }
    if (!clock.paused) {
      if (writeIdx > 0) {
        postInvalidateOnAnimation();
      } else {
        long nextDelayMs = nextActivationDelayMs(currentMs);
        if (nextDelayMs == 0L) {
          postInvalidateOnAnimation();
        } else if (nextDelayMs > 0L) {
          postInvalidateDelayed(Math.max(16L, nextDelayMs - 16L));
        }
      }
    }
  }

  private long getCurrentPositionMs() {
    return clock.getPositionMs();
  }

  private void activateNewItems(long currentMs) {
    long activationMs = currentMs - config.timeOffsetMs;
    if (lastActivationPositionMs >= activationMs) {
      return;
    }
    long fromMs = Math.max(lastActivationPositionMs, activationMs - MAX_ACTIVATION_WINDOW_MS);
    List<Danmaku> candidates = activationScratch;
    candidates.clear();
    synchronized (lock) {
      NavigableMap<Long, List<Danmaku>> range = pool.subMap(fromMs, false, activationMs, true);
      for (List<Danmaku> bucket : range.values()) {
        for (int i = 0, size = bucket.size(); i < size; i++) {
          Danmaku d = bucket.get(i);
          if (d.active) {
            continue;
          }
          if (!isTypeVisible(d.type) || !isPoolVisible(d.pool)) {
            continue;
          }
          candidates.add(d);
        }
      }
    }
    long retryWindowMs = Math.max(2000L, config.fixedDurationMs);
    long advanceTo = activationMs;
    for (int i = 0, n = candidates.size(); i < n; i++) {
      Danmaku d = candidates.get(i);
      if (activeItems.size() >= config.maxOnScreen) {
        if (currentMs - d.timeMs < retryWindowMs) {
          advanceTo = Math.min(advanceTo, d.timeMs - 1);
        }
        break;
      }
      if (tryActivate(d, currentMs)) {
        activeItems.add(d);
      } else if (currentMs - d.timeMs < retryWindowMs) {
        advanceTo = Math.min(advanceTo, d.timeMs - 1);
      }
    }
    candidates.clear();
    lastActivationPositionMs = Math.max(lastActivationPositionMs, advanceTo);
  }

  private boolean isTypeVisible(@Danmaku.Type int type) {
    switch (type) {
      case Danmaku.TYPE_SCROLL:
        return config.showScroll;
      case Danmaku.TYPE_REVERSE:
        return config.showReverse;
      case Danmaku.TYPE_TOP:
        return config.showTop;
      case Danmaku.TYPE_BOTTOM:
        return config.showBottom;
      case Danmaku.TYPE_POSITIONED:
        return config.showPositioned;
      default:
        return false;
    }
  }

  private boolean isPoolVisible(int pool) {
    switch (pool) {
      case Danmaku.POOL_SUBTITLE:
        return config.showSubtitle;
      case Danmaku.POOL_SPECIAL:
        return config.showSpecial;
      default:
        return true;
    }
  }

  private void forceSendActivate(Danmaku d, long currentMs) {
    switch (d.type) {
      case Danmaku.TYPE_SCROLL:
      case Danmaku.TYPE_REVERSE:
        assignTrackWithoutOverlapCheck(d, currentMs, scrollTrackCount, false);
        break;
      case Danmaku.TYPE_TOP:
        assignTrackWithoutOverlapCheck(d, currentMs, topTrackCount, false);
        break;
      case Danmaku.TYPE_BOTTOM:
        assignTrackWithoutOverlapCheck(d, currentMs, bottomTrackCount, true);
        break;
      default:
        break;
    }
  }

  private boolean tryActivate(Danmaku d, long currentMs) {
    switch (d.type) {
      case Danmaku.TYPE_SCROLL:
        return assignScrollTrack(d, currentMs);
      case Danmaku.TYPE_REVERSE:
        return assignReverseTrack(d, currentMs);
      case Danmaku.TYPE_TOP:
        return assignTopTrack(d, currentMs);
      case Danmaku.TYPE_BOTTOM:
        return assignBottomTrack(d, currentMs);
      case Danmaku.TYPE_POSITIONED:
        d.trackIndex = 0;
        d.activatedTimeMs = currentMs;
        d.active = true;
        return true;
      default:
        return false;
    }
  }

  private boolean assignScrollTrack(Danmaku d, long currentMs) {
    if (config.allowScrollOverlap) {
      return assignTrackWithoutOverlapCheck(d, currentMs, scrollTrackCount, false);
    }
    for (int i = 0; i < scrollTrackCount; i++) {
      Danmaku tail = scrollTrackTails[i];
      if (tail == null || canUseScrollTrack(tail, d, currentMs)) {
        d.trackIndex = i;
        d.activatedTimeMs = currentMs;
        d.active = true;
        scrollTrackTails[i] = d;
        return true;
      }
    }
    return false;
  }

  private boolean assignReverseTrack(Danmaku d, long currentMs) {
    if (config.allowReverseOverlap) {
      return assignTrackWithoutOverlapCheck(d, currentMs, scrollTrackCount, false);
    }
    for (int i = 0; i < scrollTrackCount; i++) {
      Danmaku tail = reverseTrackTails[i];
      if (tail == null || canUseScrollTrack(tail, d, currentMs)) {
        d.trackIndex = i;
        d.activatedTimeMs = currentMs;
        d.active = true;
        reverseTrackTails[i] = d;
        return true;
      }
    }
    return false;
  }

  private boolean canUseScrollTrack(Danmaku prev, Danmaku next, long currentMs) {
    float prevSpeed = (viewWidth + prev.measuredWidth) / (float) getEffectiveScrollDurationMs();
    long prevElapsed = currentMs - prev.activatedTimeMs;
    float prevRightEdge = viewWidth + prev.measuredWidth - prevSpeed * prevElapsed;
    float minGap = textSizePx * config.scrollGapRatio;
    if (prevRightEdge > viewWidth - minGap) {
      return false;
    }
    float nextSpeed = (viewWidth + next.measuredWidth) / (float) getEffectiveScrollDurationMs();
    if (nextSpeed <= prevSpeed) {
      return true;
    }
    float gap = viewWidth - prevRightEdge;
    float catchUpTime = gap / (nextSpeed - prevSpeed);
    float prevExitTime = prevRightEdge / prevSpeed;
    return catchUpTime >= prevExitTime;
  }

  private boolean assignTopTrack(Danmaku d, long currentMs) {
    if (config.allowTopOverlap) {
      return assignTrackWithoutOverlapCheck(d, currentMs, topTrackCount, false);
    }
    for (int i = 0; i < topTrackCount; i++) {
      if (topTrackExpiries[i] <= currentMs) {
        d.trackIndex = i;
        d.activatedTimeMs = currentMs;
        d.active = true;
        topTrackExpiries[i] = currentMs + config.fixedDurationMs;
        return true;
      }
    }
    return false;
  }

  private boolean assignBottomTrack(Danmaku d, long currentMs) {
    if (config.allowBottomOverlap) {
      return assignTrackWithoutOverlapCheck(d, currentMs, bottomTrackCount, true);
    }
    for (int i = 0; i < bottomTrackCount; i++) {
      if (bottomTrackExpiries[i] <= currentMs) {
        d.trackIndex = i;
        d.activatedTimeMs = currentMs;
        d.active = true;
        bottomTrackExpiries[i] = currentMs + config.fixedDurationMs;
        return true;
      }
    }
    return false;
  }

  private boolean assignTrackWithoutOverlapCheck(Danmaku d, long currentMs, int trackCount, boolean isBottom) {
    if (trackCount <= 0) {
      return false;
    }
    int trackIndex;
    if (d.type == Danmaku.TYPE_TOP) {
      trackIndex = nextTopTrackIndex;
      nextTopTrackIndex = (nextTopTrackIndex + 1) % trackCount;
    } else if (isBottom) {
      trackIndex = nextBottomTrackIndex;
      nextBottomTrackIndex = (nextBottomTrackIndex + 1) % trackCount;
    } else if (d.type == Danmaku.TYPE_REVERSE) {
      trackIndex = nextReverseTrackIndex;
      nextReverseTrackIndex = (nextReverseTrackIndex + 1) % trackCount;
    } else {
      trackIndex = nextScrollTrackIndex;
      nextScrollTrackIndex = (nextScrollTrackIndex + 1) % trackCount;
    }
    d.trackIndex = trackIndex;
    d.activatedTimeMs = currentMs;
    d.active = true;
    if (d.type == Danmaku.TYPE_TOP) {
      topTrackExpiries[trackIndex] = currentMs + config.fixedDurationMs;
    } else if (isBottom) {
      bottomTrackExpiries[trackIndex] = currentMs + config.fixedDurationMs;
    } else if (d.type == Danmaku.TYPE_REVERSE) {
      reverseTrackTails[trackIndex] = d;
    } else {
      scrollTrackTails[trackIndex] = d;
    }
    return true;
  }

  private boolean drawDanmaku(@Nullable Canvas canvas, Danmaku d, long currentMs) {
    long elapsed = currentMs - d.activatedTimeMs;
    float sizePx = d.textSizeSp > 0 ? resolveTextSizePx(d.textSizeSp) : textSizePx;
    if (currentPaintSizePx != sizePx) {
      fillPaint.setTextSize(sizePx);
      strokePaint.setTextSize(sizePx);
      currentPaintSizePx = sizePx;
    }
    float x;
    float y;
    boolean alive;
    switch (d.type) {
      case Danmaku.TYPE_SCROLL:
        float speed = (viewWidth + d.measuredWidth) / (float) getEffectiveScrollDurationMs();
        x = viewWidth - speed * elapsed;
        alive = x + d.measuredWidth > 0;
        y = computeBaseline(d.trackIndex);
        break;
      case Danmaku.TYPE_REVERSE:
        float revSpeed = (viewWidth + d.measuredWidth) / (float) getEffectiveScrollDurationMs();
        x = -d.measuredWidth + revSpeed * elapsed;
        alive = x < viewWidth;
        y = computeBaseline(d.trackIndex);
        break;
      case Danmaku.TYPE_TOP:
        x = (viewWidth - d.measuredWidth) / 2f;
        alive = elapsed < config.fixedDurationMs;
        y = computeBaseline(d.trackIndex);
        break;
      case Danmaku.TYPE_BOTTOM:
        x = (viewWidth - d.measuredWidth) / 2f;
        alive = elapsed < config.fixedDurationMs;
        y = computeBottomBaseline(d.trackIndex);
        break;
      case Danmaku.TYPE_POSITIONED:
        x = d.x * viewWidth - d.measuredWidth / 2f;
        y = d.y * viewHeight;
        long effectiveDurationMs = d.durationMs > 0 ? d.durationMs : config.fixedDurationMs;
        alive = elapsed < effectiveDurationMs;
        break;
      default:
        return false;
    }
    if (!alive) {
      return false;
    }
    if (canvas == null) {
      return true;
    }
    boolean forceWhite = shouldForceWhite(d);
    int effectiveColor = forceWhite ? Color.WHITE : resolveColor(d);
    int alpha = (int) (Color.alpha(effectiveColor) * (1f - config.transparency));
    fillState.apply(fillPaint, effectiveColor, alpha);
    if (!forceWhite && config.colorMode == DanmakuConfig.COLOR_MODE_GRADIENT) {
      buildGradientShaderIfNeeded(d, config.colorMode, null);
      gradientMatrix.setTranslate(x, 0);
      d.gradientShader.setLocalMatrix(gradientMatrix);
      fillPaint.setShader(d.gradientShader);
      drawText(canvas, d, x, y, effectiveColor, alpha, sizePx);
      fillPaint.setShader(null);
    } else if (!forceWhite && config.colorMode == DanmakuConfig.COLOR_MODE_DEFAULT
        && d.sourceGradientColors != null
        && d.sourceGradientColors.length >= 2) {
      buildGradientShaderIfNeeded(d, config.colorMode, d.sourceGradientColors);
      gradientMatrix.setTranslate(x, 0);
      d.gradientShader.setLocalMatrix(gradientMatrix);
      fillPaint.setShader(d.gradientShader);
      drawText(canvas, d, x, y, effectiveColor, alpha, sizePx);
      fillPaint.setShader(null);
    } else {
      drawText(canvas, d, x, y, effectiveColor, alpha, sizePx);
    }
    return true;
  }

  private int resolveColor(Danmaku d) {
    switch (config.colorMode) {
      case DanmakuConfig.COLOR_MODE_COLORFUL:
        return colorfulColor(d.text);
      case DanmakuConfig.COLOR_MODE_GRADIENT:
      case DanmakuConfig.COLOR_MODE_DEFAULT:
      default:
        return d.color;
    }
  }

  private void buildGradientShaderIfNeeded(Danmaku d, int colorMode, @Nullable int[] sourceColors) {
    if (d.gradientShader != null
        && d.gradientShaderWidth == d.measuredWidth
        && d.gradientShaderColorMode == colorMode) {
      return;
    }
    if (sourceColors == null) {
      float hue = hueOf(d.text);
      d.gradientShader = new LinearGradient(0, 0, d.measuredWidth, 0, hsvColor(hue), hsvColor(hue + GRADIENT_HUE_OFFSET), Shader.TileMode.CLAMP);
    } else if (sourceColors.length == 2) {
      d.gradientShader = new LinearGradient(0, 0, d.measuredWidth, 0, sourceColors[0], sourceColors[1], Shader.TileMode.CLAMP);
    } else {
      float[] positions = new float[sourceColors.length];
      for (int i = 0; i < sourceColors.length; i++) {
        positions[i] = i / (float) (sourceColors.length - 1);
      }
      d.gradientShader = new LinearGradient(0, 0, d.measuredWidth, 0, sourceColors, positions, Shader.TileMode.CLAMP);
    }
    d.gradientShaderWidth = d.measuredWidth;
    d.gradientShaderColorMode = colorMode;
  }

  private void drawText(Canvas canvas, Danmaku d, float x, float y, int textColor, int alpha, float sizePx) {
    String text = d.text;
    switch (config.styleMode) {
      case DanmakuConfig.STYLE_NONE:
        canvas.drawText(text, x, y, fillPaint);
        return;
      case DanmakuConfig.STYLE_SHADOW:
        int shadowColor = applyAlpha(computeShadowColor(textColor), 1f - config.shadowTransparency);
        float shadowRadiusPx = sizePx * config.shadowRadiusMultiplier;
        shadowState.apply(fillPaint, shadowColor, shadowRadiusPx);
        canvas.drawText(text, x, y, fillPaint);
        return;
      case DanmakuConfig.STYLE_PROJECTION:
        strokePaint.setStyle(Paint.Style.FILL);
        int projStrokeColor = computeShadowColor(textColor);
        int projStrokeAlpha = (int) (255 * Util.constrainValue((1f - config.projectionTransparency) * (1f - config.transparency), 0f, 1f));
        strokeState.apply(strokePaint, projStrokeColor, projStrokeAlpha);
        float projOffsetX = sizePx * config.projectionOffsetXMultiplier;
        float projOffsetY = sizePx * config.projectionOffsetYMultiplier;
        canvas.drawText(text, x + projOffsetX, y + projOffsetY, strokePaint);
        canvas.drawText(text, x, y, fillPaint);
        strokePaint.setStyle(Paint.Style.STROKE);
        return;
      case DanmakuConfig.STYLE_STROKE:
      default:
        float sw = sizePx * config.strokeWidthMultiplier;
        if (lastStrokeWidth != sw) {
          strokePaint.setStrokeWidth(sw);
          lastStrokeWidth = sw;
        }
        if (d.cachedStrokeColor == 0) {
          d.cachedStrokeColor = computeShadowColor(textColor);
        }
        strokeState.apply(strokePaint, d.cachedStrokeColor, alpha);
        canvas.drawText(text, x, y, strokePaint);
        canvas.drawText(text, x, y, fillPaint);
    }
  }

  private float computeBaseline(int trackIndex) {
    float trackTop = trackIndex * trackHeight;
    return trackTop + (trackHeight - defaultDescent - defaultAscent) / 2f;
  }

  private float computeBottomBaseline(int trackIndex) {
    float trackTop = viewHeight - (trackIndex + 1) * trackHeight;
    return trackTop + (trackHeight - defaultDescent - defaultAscent) / 2f;
  }

  private long nextActivationDelayMs(long currentMs) {
    long activationMs = currentMs - config.timeOffsetMs;
    Long nextKey;
    synchronized (lock) {
      nextKey = pool.higherKey(activationMs);
    }
    if (nextKey == null) {
      return -1L;
    }
    long delta = nextKey - activationMs;
    return Math.max(0L, (long) (delta / clock.playbackSpeed));
  }

  private void clearActiveItems() {
    for (int i = 0; i < activeItems.size(); i++) {
      Danmaku d = activeItems.get(i);
      d.active = false;
      d.trackIndex = -1;
      d.activatedTimeMs = -1;
      d.cachedStrokeColor = 0;
    }
    activeItems.clear();
    Arrays.fill(scrollTrackTails, null);
    Arrays.fill(reverseTrackTails, null);
    Arrays.fill(topTrackExpiries, 0);
    Arrays.fill(bottomTrackExpiries, 0);
    nextScrollTrackIndex = 0;
    nextReverseTrackIndex = 0;
    nextTopTrackIndex = 0;
    nextBottomTrackIndex = 0;
  }

  private void remeasurePool() {
    float sizePx = textSizePx;
    postToBackground(() -> {
      Paint measurePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
      synchronized (lock) {
        for (List<Danmaku> bucket : pool.values()) {
          for (int i = 0, n = bucket.size(); i < n; i++) {
            Danmaku d = bucket.get(i);
            float sp = d.textSizeSp > 0 ? resolveTextSizePx(d.textSizeSp) : sizePx;
            measurePaint.setTextSize(sp);
            d.measuredWidth = measurePaint.measureText(d.text);
          }
        }
      }
    });
  }

  private void remeasureActiveItems() {
    if (activeItems.isEmpty()) {
      return;
    }
    for (int i = 0; i < activeItems.size(); i++) {
      Danmaku d = activeItems.get(i);
      float sizePx = d.textSizeSp > 0 ? resolveTextSizePx(d.textSizeSp) : textSizePx;
      if (fillPaint.getTextSize() != sizePx) {
        fillPaint.setTextSize(sizePx);
      }
      d.measuredWidth = fillPaint.measureText(d.text);
    }
    fillPaint.setTextSize(textSizePx);
  }

  private void reconcileActiveItemsToTracks() {
    int writeIdx = 0;
    for (int i = 0; i < activeItems.size(); i++) {
      Danmaku d = activeItems.get(i);
      if (d.trackIndex < trackCountForType(d.type)) {
        if (writeIdx != i) {
          activeItems.set(writeIdx, d);
        }
        writeIdx++;
      } else {
        d.active = false;
        d.trackIndex = -1;
        d.activatedTimeMs = -1;
      }
    }
    if (activeItems.size() > writeIdx) {
      activeItems.subList(writeIdx, activeItems.size()).clear();
    }
    Arrays.fill(scrollTrackTails, null);
    Arrays.fill(reverseTrackTails, null);
    Arrays.fill(topTrackExpiries, 0);
    Arrays.fill(bottomTrackExpiries, 0);
    for (int i = 0; i < activeItems.size(); i++) {
      Danmaku d = activeItems.get(i);
      int idx = d.trackIndex;
      switch (d.type) {
        case Danmaku.TYPE_SCROLL:
          Danmaku prevScroll = scrollTrackTails[idx];
          if (prevScroll == null || d.activatedTimeMs > prevScroll.activatedTimeMs) {
            scrollTrackTails[idx] = d;
          }
          break;
        case Danmaku.TYPE_REVERSE:
          Danmaku prevReverse = reverseTrackTails[idx];
          if (prevReverse == null || d.activatedTimeMs > prevReverse.activatedTimeMs) {
            reverseTrackTails[idx] = d;
          }
          break;
        case Danmaku.TYPE_TOP:
          long topEnd = d.activatedTimeMs + config.fixedDurationMs;
          if (topEnd > topTrackExpiries[idx]) {
            topTrackExpiries[idx] = topEnd;
          }
          break;
        case Danmaku.TYPE_BOTTOM:
          long botEnd = d.activatedTimeMs + config.fixedDurationMs;
          if (botEnd > bottomTrackExpiries[idx]) {
            bottomTrackExpiries[idx] = botEnd;
          }
          break;
        default:
      }
    }
  }

  private int trackCountForType(int type) {
    switch (type) {
      case Danmaku.TYPE_SCROLL:
      case Danmaku.TYPE_REVERSE:
        return scrollTrackCount;
      case Danmaku.TYPE_TOP:
        return topTrackCount;
      case Danmaku.TYPE_BOTTOM:
        return bottomTrackCount;
      default:
        return Integer.MAX_VALUE;
    }
  }

  private void recalculateTracks() {
    if (viewHeight <= 0 || trackHeight <= 0) {
      return;
    }
    int totalTracks = (int) (viewHeight * config.scrollAreaRatio / trackHeight);
    int autoFixedTracks = Math.max(1, totalTracks / 3);
    scrollTrackCount = getConfiguredTrackCount(totalTracks, config.maxScrollLines);
    topTrackCount = getConfiguredTrackCount(autoFixedTracks, config.maxTopLines);
    bottomTrackCount = getConfiguredTrackCount(autoFixedTracks, config.maxBottomLines);
    scrollTrackTails = new Danmaku[scrollTrackCount];
    reverseTrackTails = new Danmaku[scrollTrackCount];
    topTrackExpiries = new long[topTrackCount];
    bottomTrackExpiries = new long[bottomTrackCount];
  }

  private void updatePaintConfig() {
    textSizePx = resolveTextSizePx(config.textSizeSp);
    float strokeWidthPx = textSizePx * config.strokeWidthMultiplier;
    trackHeight = textSizePx * config.lineSpacing;
    fillPaint.setTextSize(textSizePx);
    fillPaint.setFakeBoldText(config.textBold);
    fillPaint.setTypeface(config.typeface != null ? config.typeface : Typeface.DEFAULT);
    strokePaint.setTextSize(textSizePx);
    strokePaint.setStrokeWidth(strokeWidthPx);
    strokePaint.setFakeBoldText(config.textBold);
    strokePaint.setTypeface(config.typeface != null ? config.typeface : Typeface.DEFAULT);
    currentPaintSizePx = textSizePx;
    defaultAscent = fillPaint.ascent();
    defaultDescent = fillPaint.descent();
    shadowState.invalidate();
    lastStrokeWidth = strokeWidthPx;
    fillState.reset();
    strokeState.reset();
  }

  private long getEffectiveScrollDurationMs() {
    return Math.max(1L, (long) (config.durationMs / Math.max(0.01f, config.scrollSpeedFactor)));
  }

  private float resolveTextSizePx(float textSizeSp) {
    return spToPx(textSizeSp * config.textScale);
  }

  private void ensureBackgroundThread() {
    if (released) {
      return;
    }
    if (backgroundThread == null) {
      backgroundThread = new HandlerThread("DanmakuMeasure");
      backgroundThread.start();
      backgroundHandler = new Handler(backgroundThread.getLooper());
    }
  }

  private void postToBackground(Runnable runnable) {
    ensureBackgroundThread();
    @Nullable Handler handler = backgroundHandler;
    if (handler != null) {
      handler.post(runnable);
    }
  }

  private void shutdownBackgroundThread() {
    if (backgroundThread != null) {
      backgroundThread.quitSafely();
      backgroundThread = null;
      backgroundHandler = null;
    }
  }

  private float spToPx(float sp) {
    return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, getResources().getDisplayMetrics());
  }
}

