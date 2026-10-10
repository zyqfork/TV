package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.widget.FrameLayout;

import androidx.activity.ComponentActivity;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.ViewWallBinding;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.utils.Path;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.io.File;
import java.io.IOException;

import pl.droidsonroids.gif.GifDrawable;

public class CustomWallView extends FrameLayout implements DefaultLifecycleObserver {

    private static final int[] WALL_PAPERS = {0, R.drawable.wallpaper_1, R.drawable.wallpaper_2, R.drawable.wallpaper_3, R.drawable.wallpaper_4};
    /**
     * Contrast kept for the white text that every layout draws straight onto the wallpaper. 6:1
     * leaves room for the translucent white cards layered on top of it (a 10% white card still
     * lands above 4.5:1) while keeping the scrim as light as the art allows.
     */
    private static final double TARGET_CONTRAST = 6.0;
    private static final int MAX_SCRIM = 204;
    private static final int SCRIM_STEP = 4;
    /** Used when the art cannot be sampled: missing cache, decode failure or no preference yet. */
    private static final int FALLBACK_COLOR = 0xFF40C090;
    private static final int TYPE_RES = 0;
    private static final int TYPE_GIF = 1;
    private static final int TYPE_VIDEO = 2;
    private static int[] artColors;
    private ViewWallBinding binding;
    private GifDrawable drawable;
    private PlayerView video;
    private ExoPlayer player;
    private boolean resumed;
    /** Only media loading is asynchronous; a late decode must not replace a newer wallpaper. */
    private int wallGeneration;

    public CustomWallView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    /**
     * The colour a self-drawn backdrop should end up as for white text to stay readable.
     *
     * Exposed because the playback pages deliberately skip the wallpaper layer (see
     * {@code PlaybackActivity.customWall()}): no image decode, and never a video behind a video.
     * Without this their backdrop was the theme's window background, a flat near-black that
     * ignored the wallpaper entirely, so the detail page looked nothing like the pages behind it.
     */
    public static int readableBackdrop(int color) {
        if (color == 0) color = FALLBACK_COLOR;
        return darken(color, scrimFor(color));
    }

    private static int scrimFor(int color) {
        double target = 1.05 / TARGET_CONTRAST - 0.05;
        int alpha = 0;
        while (alpha < MAX_SCRIM && luminance(darken(color, alpha)) > target) alpha += SCRIM_STEP;
        return alpha;
    }

    private static int darken(int color, int alpha) {
        int keep = 255 - alpha;
        return Color.rgb(Color.red(color) * keep / 255, Color.green(color) * keep / 255, Color.blue(color) * keep / 255);
    }

    private static double luminance(int color) {
        return 0.2126 * channel(Color.red(color)) + 0.7152 * channel(Color.green(color)) + 0.0722 * channel(Color.blue(color));
    }

    private static double channel(int value) {
        double v = value / 255.0;
        return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    /**
     * Representative colour of the built-in art, per flavour. The two source sets ship different
     * images under the same {@code wallpaper_N} name, so each keeps its own table; that is why the
     * values live in {@code res/values/arrays.xml} instead of one shared list.
     */
    private static int[] artColors() {
        if (artColors == null) {
            TypedArray array = ResUtil.getTypedArray(R.array.wall_art);
            int[] colors = new int[array.length()];
            for (int i = 0; i < colors.length; i++) colors[i] = array.getColor(i, FALLBACK_COLOR);
            array.recycle();
            artColors = colors;
        }
        return artColors;
    }

    private static int artColor(int wall) {
        int[] colors = artColors();
        return wall > 0 && wall < colors.length && colors[wall] != 0 ? colors[wall] : FALLBACK_COLOR;
    }

    /**
     * Average of the brighter half of the sampled art. The mean of the whole image would be pulled
     * down by dark corners and produce a scrim too light for the bright area white text actually
     * sits on.
     */
    private static int sampleColor(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        if (width <= 0 || height <= 0) return FALLBACK_COLOR;
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        double total = 0;
        for (int pixel : pixels) total += luminance(pixel);
        double mean = total / pixels.length;
        long r = 0, g = 0, b = 0;
        int used = 0;
        for (int pixel : pixels) {
            if (luminance(pixel) < mean) continue;
            r += Color.red(pixel);
            g += Color.green(pixel);
            b += Color.blue(pixel);
            used++;
        }
        return used == 0 ? FALLBACK_COLOR : Color.rgb((int) (r / used), (int) (g / used), (int) (b / used));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (isInEditMode()) return;
        binding = ViewWallBinding.inflate(LayoutInflater.from(getContext()), this, true);
        ((ComponentActivity) getContext()).getLifecycle().addObserver(this);
        refresh();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onConfigEvent(ConfigEvent event) {
        if (event.type() == ConfigEvent.Type.WALL) refresh();
    }

    private void refresh() {
        if (binding == null) return;
        stop();
        final int generation = ++wallGeneration;
        int wall = Setting.getWall();
        int type = Setting.getWallType();
        if (isBuiltIn(wall, type)) {
            // Built-in art has a known colour: no decode, no sampling, no background task.
            binding.image.setImageResource(WALL_PAPERS[wall]);
            applyWallColor(artColor(wall));
            return;
        }
        Task.execute(() -> {
            // A custom wallpaper can be any photo, so its scrim has to come from the art itself.
            // The loader already wrote a snapshot, and decoding it heavily downsampled keeps this
            // off the main thread without a palette dependency.
            int color = customColor();
            if (type == TYPE_VIDEO) {
                Drawable poster = cache();
                App.post(() -> {
                    if (binding == null || generation != wallGeneration) return;
                    loadVideo(Path.wall(wall), poster);
                    applyWallColor(color);
                });
                return;
            }
            GifDrawable gifDraw = type == TYPE_GIF ? gif(Path.wall(wall)) : null;
            Drawable decoded = gifDraw != null ? gifDraw : cache();
            App.post(() -> {
                if (binding == null || generation != wallGeneration) {
                    if (gifDraw != null) gifDraw.recycle();
                    return;
                }
                if (gifDraw != null) {
                    drawable = gifDraw;
                    binding.image.setImageDrawable(gifDraw);
                    if (!resumed) gifDraw.pause();
                } else if (decoded != null) {
                    binding.image.setImageDrawable(decoded);
                } else {
                    binding.image.setImageResource(R.drawable.wallpaper_1);
                }
                applyWallColor(color);
            });
        });
    }

    private void applyWallColor(int color) {
        binding.scrim.setBackgroundColor(Color.argb(scrimFor(color), 0, 0, 0));
        if (Setting.getWallColor() != color) Setting.putWallColor(color);
    }

    private int customColor() {
        File file = Path.wallCache();
        if (!file.exists()) return FALLBACK_COLOR;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 8;
        Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        if (bitmap == null) return FALLBACK_COLOR;
        int color = sampleColor(bitmap);
        bitmap.recycle();
        return color;
    }

    private boolean isBuiltIn(int wall, int type) {
        return type == TYPE_RES && wall > 0 && wall < WALL_PAPERS.length;
    }

    private void stop() {
        if (player != null) {
            player.stop();
            player.clearMediaItems();
        }
        if (video != null) {
            video.setPlayer(null);
            video.setVisibility(GONE);
        }
        if (drawable != null) {
            drawable.stop();
            drawable.recycle();
            drawable = null;
        }
    }

    private void loadVideo(File file, Drawable poster) {
        ensurePlayer();
        ensureVideoView();
        video.setPlayer(resumed ? player : null);
        video.setVisibility(VISIBLE);
        binding.image.setImageDrawable(poster);
        player.setPlayWhenReady(resumed);
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)));
        player.prepare();
    }

    private Drawable cache() {
        File file = Path.wallCache();
        return file.exists() ? Drawable.createFromPath(file.getAbsolutePath()) : null;
    }

    private GifDrawable gif(File file) {
        try {
            return new GifDrawable(file);
        } catch (IOException e) {
            return null;
        }
    }

    private void ensurePlayer() {
        if (player != null) return;
        player = new ExoPlayer.Builder(getContext()).build();
        player.setRepeatMode(Player.REPEAT_MODE_ALL);
        player.setPlayWhenReady(false);
        player.mute();
    }

    private void ensureVideoView() {
        if (video != null) return;
        video = (PlayerView) LayoutInflater.from(getContext()).inflate(R.layout.view_wall_video, this, false);
        // Video, like image/GIF art, stays below the scrim defined by view_wall.xml.
        addView(video, 1, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    private boolean hasVideo() {
        return player != null && video != null && video.getVisibility() == VISIBLE && player.getMediaItemCount() > 0;
    }

    @Override
    public void onCreate(@NonNull LifecycleOwner owner) {
        EventBus.getDefault().register(this);
    }

    @Override
    public void onResume(@NonNull LifecycleOwner owner) {
        resumed = true;
        if (drawable != null) drawable.start();
        if (!hasVideo()) return;
        video.setPlayer(player);
        player.play();
    }

    @Override
    public void onPause(@NonNull LifecycleOwner owner) {
        resumed = false;
        if (drawable != null) drawable.pause();
        if (!hasVideo()) return;
        video.setPlayer(null);
        player.pause();
    }

    @Override
    public void onDestroy(@NonNull LifecycleOwner owner) {
        EventBus.getDefault().unregister(this);
        ++wallGeneration;
        resumed = false;
        if (drawable != null) drawable.recycle();
        if (video != null) removeView(video);
        if (player != null) player.release();
        drawable = null;
        binding = null;
        player = null;
        video = null;
    }
}
