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

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.LOCAL_VARIABLE;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE_USE;

import android.graphics.Typeface;
import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.media3.common.util.Util;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

public final class DanmakuConfig {

  public static final int STYLE_NONE = 0;
  public static final int STYLE_SHADOW = 1;
  public static final int STYLE_STROKE = 2;
  public static final int STYLE_PROJECTION = 3;
  public static final int COLOR_MODE_DEFAULT = 1;
  public static final int COLOR_MODE_COLORFUL = 2;
  public static final int COLOR_MODE_GRADIENT = 3;
  public static final DanmakuConfig DEFAULT = new Builder().build();
  public final long durationMs;
  public final long fixedDurationMs;
  public final float textSizeSp;
  public final float textScale;
  public final @Nullable Typeface typeface;
  public final boolean textBold;
  public final float transparency;
  public final @StyleMode int styleMode;
  public final float shadowTransparency;
  public final float shadowRadiusMultiplier;
  public final float strokeWidthMultiplier;
  public final float projectionOffsetXMultiplier;
  public final float projectionOffsetYMultiplier;
  public final float projectionTransparency;
  public final int maxOnScreen;
  public final int maxScrollLines;
  public final int maxTopLines;
  public final int maxBottomLines;
  public final float scrollAreaRatio;
  public final float lineSpacing;
  public final boolean showScroll;
  public final boolean showTop;
  public final boolean showBottom;
  public final boolean showReverse;
  public final boolean allowScrollOverlap;
  public final boolean allowTopOverlap;
  public final boolean allowBottomOverlap;
  public final boolean allowReverseOverlap;
  public final float scrollSpeedFactor;
  public final float scrollGapRatio;
  public final boolean showPositioned;
  public final boolean showSubtitle;
  public final boolean showSpecial;
  public final @ColorMode int colorMode;
  public final long timeOffsetMs;

  private DanmakuConfig(Builder builder) {
    this.durationMs = builder.durationMs;
    this.fixedDurationMs = builder.fixedDurationMs;
    this.textSizeSp = builder.textSizeSp;
    this.textScale = builder.textScale;
    this.typeface = builder.typeface;
    this.textBold = builder.textBold;
    this.transparency = builder.transparency;
    this.styleMode = builder.styleMode;
    this.shadowTransparency = builder.shadowTransparency;
    this.shadowRadiusMultiplier = builder.shadowRadiusMultiplier;
    this.strokeWidthMultiplier = builder.strokeWidthMultiplier;
    this.projectionOffsetXMultiplier = builder.projectionOffsetXMultiplier;
    this.projectionOffsetYMultiplier = builder.projectionOffsetYMultiplier;
    this.projectionTransparency = builder.projectionTransparency;
    this.maxOnScreen = builder.maxOnScreen;
    this.maxScrollLines = builder.maxScrollLines;
    this.maxTopLines = builder.maxTopLines;
    this.maxBottomLines = builder.maxBottomLines;
    this.scrollAreaRatio = builder.scrollAreaRatio;
    this.lineSpacing = builder.lineSpacing;
    this.showScroll = builder.showScroll;
    this.showTop = builder.showTop;
    this.showBottom = builder.showBottom;
    this.showReverse = builder.showReverse;
    this.allowScrollOverlap = builder.allowScrollOverlap;
    this.allowTopOverlap = builder.allowTopOverlap;
    this.allowBottomOverlap = builder.allowBottomOverlap;
    this.allowReverseOverlap = builder.allowReverseOverlap;
    this.scrollSpeedFactor = builder.scrollSpeedFactor;
    this.scrollGapRatio = builder.scrollGapRatio;
    this.showPositioned = builder.showPositioned;
    this.showSubtitle = builder.showSubtitle;
    this.showSpecial = builder.showSpecial;
    this.colorMode = builder.colorMode;
    this.timeOffsetMs = builder.timeOffsetMs;
  }

  public Builder buildUpon() {
    return new Builder(this);
  }

  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target({FIELD, METHOD, PARAMETER, LOCAL_VARIABLE, TYPE_USE})
  @IntDef({STYLE_NONE, STYLE_SHADOW, STYLE_STROKE, STYLE_PROJECTION})
  public @interface StyleMode {

  }

  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target({FIELD, METHOD, PARAMETER, LOCAL_VARIABLE, TYPE_USE})
  @IntDef({COLOR_MODE_DEFAULT, COLOR_MODE_COLORFUL, COLOR_MODE_GRADIENT})
  public @interface ColorMode {

  }

  public static final class Builder {

    private long durationMs;
    private long fixedDurationMs;
    private float textSizeSp;
    private float textScale;
    private @Nullable Typeface typeface;
    private boolean textBold;
    private float transparency;
    private @StyleMode int styleMode;
    private float shadowTransparency;
    private float shadowRadiusMultiplier;
    private float strokeWidthMultiplier;
    private float projectionOffsetXMultiplier;
    private float projectionOffsetYMultiplier;
    private float projectionTransparency;
    private int maxOnScreen;
    private int maxScrollLines;
    private int maxTopLines;
    private int maxBottomLines;
    private float scrollAreaRatio;
    private float lineSpacing;
    private boolean showScroll;
    private boolean showTop;
    private boolean showBottom;
    private boolean showReverse;
    private boolean allowScrollOverlap;
    private boolean allowTopOverlap;
    private boolean allowBottomOverlap;
    private boolean allowReverseOverlap;
    private float scrollSpeedFactor;
    private float scrollGapRatio;
    private boolean showPositioned;
    private boolean showSubtitle;
    private boolean showSpecial;
    private @ColorMode int colorMode;
    private long timeOffsetMs;

    public Builder() {
      durationMs = 8000;
      fixedDurationMs = 5000;
      textSizeSp = 14f;
      textScale = 1f;
      typeface = null;
      textBold = false;
      transparency = 0f;
      styleMode = STYLE_STROKE;
      shadowTransparency = 0.1f;
      shadowRadiusMultiplier = 0.15f;
      strokeWidthMultiplier = 0.12f;
      projectionOffsetXMultiplier = 0.08f;
      projectionOffsetYMultiplier = 0.08f;
      projectionTransparency = 0.2f;
      maxOnScreen = 150;
      maxScrollLines = 0;
      maxTopLines = 0;
      maxBottomLines = 0;
      scrollAreaRatio = 0.5f;
      lineSpacing = 1.4f;
      showScroll = true;
      showTop = true;
      showBottom = true;
      showReverse = true;
      allowScrollOverlap = false;
      allowTopOverlap = false;
      allowBottomOverlap = false;
      allowReverseOverlap = false;
      scrollSpeedFactor = 1f;
      scrollGapRatio = 0f;
      showPositioned = true;
      showSubtitle = true;
      showSpecial = true;
      colorMode = COLOR_MODE_DEFAULT;
      timeOffsetMs = 0;
    }

    private Builder(DanmakuConfig config) {
      this.durationMs = config.durationMs;
      this.fixedDurationMs = config.fixedDurationMs;
      this.textSizeSp = config.textSizeSp;
      this.textScale = config.textScale;
      this.typeface = config.typeface;
      this.textBold = config.textBold;
      this.transparency = config.transparency;
      this.styleMode = config.styleMode;
      this.shadowTransparency = config.shadowTransparency;
      this.shadowRadiusMultiplier = config.shadowRadiusMultiplier;
      this.strokeWidthMultiplier = config.strokeWidthMultiplier;
      this.projectionOffsetXMultiplier = config.projectionOffsetXMultiplier;
      this.projectionOffsetYMultiplier = config.projectionOffsetYMultiplier;
      this.projectionTransparency = config.projectionTransparency;
      this.maxOnScreen = config.maxOnScreen;
      this.maxScrollLines = config.maxScrollLines;
      this.maxTopLines = config.maxTopLines;
      this.maxBottomLines = config.maxBottomLines;
      this.scrollAreaRatio = config.scrollAreaRatio;
      this.lineSpacing = config.lineSpacing;
      this.showScroll = config.showScroll;
      this.showTop = config.showTop;
      this.showBottom = config.showBottom;
      this.showReverse = config.showReverse;
      this.allowScrollOverlap = config.allowScrollOverlap;
      this.allowTopOverlap = config.allowTopOverlap;
      this.allowBottomOverlap = config.allowBottomOverlap;
      this.allowReverseOverlap = config.allowReverseOverlap;
      this.scrollSpeedFactor = config.scrollSpeedFactor;
      this.scrollGapRatio = config.scrollGapRatio;
      this.showPositioned = config.showPositioned;
      this.showSubtitle = config.showSubtitle;
      this.showSpecial = config.showSpecial;
      this.colorMode = config.colorMode;
      this.timeOffsetMs = config.timeOffsetMs;
    }

    public Builder setDurationMs(long durationMs) {
      this.durationMs = Math.max(1L, durationMs);
      return this;
    }

    public Builder setFixedDurationMs(long fixedDurationMs) {
      this.fixedDurationMs = Math.max(1L, fixedDurationMs);
      return this;
    }

    public Builder setTextSizeSp(float textSizeSp) {
      this.textSizeSp = Math.max(0f, textSizeSp);
      return this;
    }

    public Builder setTextScale(float textScale) {
      this.textScale = Math.max(0.01f, textScale);
      return this;
    }

    public Builder setTypeface(@Nullable Typeface typeface) {
      this.typeface = typeface;
      return this;
    }

    public Builder setTextBold(boolean textBold) {
      this.textBold = textBold;
      return this;
    }

    public Builder setTransparency(float transparency) {
      this.transparency = Util.constrainValue(transparency, 0f, 1f);
      return this;
    }

    public Builder setStyleMode(@StyleMode int styleMode) {
      this.styleMode = styleMode;
      return this;
    }

    public Builder setShadowTransparency(float shadowTransparency) {
      this.shadowTransparency = Util.constrainValue(shadowTransparency, 0f, 1f);
      return this;
    }

    public Builder setShadowRadiusMultiplier(float shadowRadiusMultiplier) {
      this.shadowRadiusMultiplier = Util.constrainValue(shadowRadiusMultiplier, 0f, 1f);
      return this;
    }

    public Builder setStrokeWidthMultiplier(float strokeWidthMultiplier) {
      this.strokeWidthMultiplier = Util.constrainValue(strokeWidthMultiplier, 0f, 1f);
      return this;
    }

    public Builder setProjectionOffsetXMultiplier(float projectionOffsetXMultiplier) {
      this.projectionOffsetXMultiplier = Util.constrainValue(projectionOffsetXMultiplier, 0f, 1f);
      return this;
    }

    public Builder setProjectionOffsetYMultiplier(float projectionOffsetYMultiplier) {
      this.projectionOffsetYMultiplier = Util.constrainValue(projectionOffsetYMultiplier, 0f, 1f);
      return this;
    }

    public Builder setProjectionTransparency(float projectionTransparency) {
      this.projectionTransparency = Util.constrainValue(projectionTransparency, 0f, 1f);
      return this;
    }

    public Builder setMaxOnScreen(int maxOnScreen) {
      this.maxOnScreen = Math.max(1, maxOnScreen);
      return this;
    }

    public Builder setMaxScrollLines(int maxScrollLines) {
      this.maxScrollLines = Math.max(0, maxScrollLines);
      return this;
    }

    public Builder setMaxTopLines(int maxTopLines) {
      this.maxTopLines = Math.max(0, maxTopLines);
      return this;
    }

    public Builder setMaxBottomLines(int maxBottomLines) {
      this.maxBottomLines = Math.max(0, maxBottomLines);
      return this;
    }

    public Builder setScrollAreaRatio(float scrollAreaRatio) {
      this.scrollAreaRatio = Util.constrainValue(scrollAreaRatio, 0.01f, 1f);
      return this;
    }

    public Builder setLineSpacing(float lineSpacing) {
      this.lineSpacing = Math.max(0.01f, lineSpacing);
      return this;
    }

    public Builder setShowScroll(boolean showScroll) {
      this.showScroll = showScroll;
      return this;
    }

    public Builder setShowTop(boolean showTop) {
      this.showTop = showTop;
      return this;
    }

    public Builder setShowBottom(boolean showBottom) {
      this.showBottom = showBottom;
      return this;
    }

    public Builder setShowReverse(boolean showReverse) {
      this.showReverse = showReverse;
      return this;
    }

    public Builder setAllowOverlapping(boolean allowOverlapping) {
      this.allowScrollOverlap = allowOverlapping;
      this.allowTopOverlap = allowOverlapping;
      this.allowBottomOverlap = allowOverlapping;
      this.allowReverseOverlap = allowOverlapping;
      return this;
    }

    public Builder setAllowScrollOverlap(boolean allowScrollOverlap) {
      this.allowScrollOverlap = allowScrollOverlap;
      return this;
    }

    public Builder setAllowTopOverlap(boolean allowTopOverlap) {
      this.allowTopOverlap = allowTopOverlap;
      return this;
    }

    public Builder setAllowBottomOverlap(boolean allowBottomOverlap) {
      this.allowBottomOverlap = allowBottomOverlap;
      return this;
    }

    public Builder setAllowReverseOverlap(boolean allowReverseOverlap) {
      this.allowReverseOverlap = allowReverseOverlap;
      return this;
    }

    public Builder setScrollSpeedFactor(float scrollSpeedFactor) {
      this.scrollSpeedFactor = Math.max(0.01f, scrollSpeedFactor);
      return this;
    }

    public Builder setScrollGapRatio(float scrollGapRatio) {
      this.scrollGapRatio = Math.min(5f, Math.max(0f, scrollGapRatio));
      return this;
    }

    public Builder setShowPositioned(boolean showPositioned) {
      this.showPositioned = showPositioned;
      return this;
    }

    public Builder setShowSubtitle(boolean showSubtitle) {
      this.showSubtitle = showSubtitle;
      return this;
    }

    public Builder setShowSpecial(boolean showSpecial) {
      this.showSpecial = showSpecial;
      return this;
    }

    public Builder setColorMode(@ColorMode int colorMode) {
      this.colorMode = colorMode;
      return this;
    }

    public Builder setTimeOffsetMs(long timeOffsetMs) {
      this.timeOffsetMs = timeOffsetMs;
      return this;
    }

    public DanmakuConfig build() {
      return new DanmakuConfig(this);
    }
  }
}

