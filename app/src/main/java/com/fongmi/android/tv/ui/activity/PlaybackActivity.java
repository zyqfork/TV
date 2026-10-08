package com.fongmi.android.tv.ui.activity;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.media3.common.C;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.drm.FrameworkMediaDrm;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import androidx.media3.ui.CaptionStyleCompat;
import androidx.media3.ui.PlayerSeekView;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.TimeBar;
import androidx.media3.ui.danmaku.DanmakuConfig;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Sub;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.player.subtitle.SecondarySubtitleOverlay;
import com.fongmi.android.tv.player.media.PlaySpec;
import com.fongmi.android.tv.player.util.PlayerHelper;
import com.fongmi.android.tv.service.PlaybackService;
import com.fongmi.android.tv.setting.DanmakuSetting;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.setting.SubtitleSetting;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.playback.PlaybackOverlayBinder;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Traffic;
import com.github.catvod.net.OkHttp;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public abstract class PlaybackActivity extends BaseActivity implements MediaController.Listener, Player.Listener, ServiceConnection {

    protected final Traffic traffic = new Traffic();
    private final List<ServiceReadyObserver<?>> serviceReadyObservers = new ArrayList<>();
    private final List<Runnable> foreverObserverRemovers = new ArrayList<>();
    private ListenableFuture<MediaController> mControllerFuture;
    private MediaController mController;
    private PlaybackService mService;
    private SecondarySubtitleOverlay secondarySubtitleOverlay;
    private com.fongmi.android.tv.ui.playback.SubtitleResourceBinding subtitleResourceBinding;
    private int appliedRender = -1;
    private boolean initialized;
    private boolean audioOnly;
    private boolean scrubbing;
    private boolean redirect;
    private boolean bound;
    private boolean stop;
    private boolean lock;

    @Override
    protected boolean customWall() {
        // Player pages are fullscreen; skip the wallpaper inflate/decode on enter.
        return false;
    }

    protected MediaController controller() {
        return mController;
    }

    protected PlaybackService service() {
        return mService;
    }

    protected PlayerManager player() {
        return mService.player();
    }

    protected boolean isRedirect() {
        return redirect;
    }

    protected void setRedirect(boolean redirect) {
        this.redirect = redirect;
        if (mService != null) mService.setNavigationCallback(redirect ? null : getNavigationCallback(), getPlaybackKey());
    }

    protected void updateNavigationKey() {
        if (mService != null) mService.setNavigationCallback(getNavigationCallback(), getPlaybackKey());
    }

    protected boolean isAudioOnly() {
        return audioOnly;
    }

    protected void setAudioOnly(boolean audioOnly) {
        this.audioOnly = audioOnly;
    }

    protected boolean isStop() {
        return stop;
    }

    protected void setStop(boolean stop) {
        this.stop = stop;
    }

    protected boolean isLock() {
        return lock;
    }

    protected void setLock(boolean lock) {
        this.lock = lock;
    }

    protected abstract PlaybackService.NavigationCallback getNavigationCallback();

    protected abstract PlayerSeekView getSeekView();

    protected abstract PlayerView getPlayerView();

    protected abstract String getPlaybackKey();

    protected boolean isOwner() {
        String key = getPlaybackKey();
        return key == null || (mService != null && key.equals(player().getKey()));
    }

    /**
     * Whether leaving this activity must terminate playback rather than keeping the service alive.
     *
     * <p>Live screens override this because continuing a live stream with no visible playback UI
     * leaks network/decoder resources and leaves audio playing after returning home.
     */
    protected boolean stopPlaybackOnBackground() {
        return false;
    }

    protected boolean isLivePlayback() {
        return false;
    }

    protected <T> void observeForever(LiveData<T> liveData, Observer<T> observer) {
        liveData.observeForever(observer);
        foreverObserverRemovers.add(() -> liveData.removeObserver(observer));
    }

    /**
     * Observes results that need an active playback service. LiveData may complete while this
     * activity is stopped or before the asynchronous service binding is ready; retain only the
     * latest value and dispatch it after the activity becomes STARTED.
     */
    protected <T> void observeWhenServiceReady(LiveData<T> liveData, Observer<T> observer) {
        ServiceReadyObserver<T> serviceObserver = new ServiceReadyObserver<>(observer);
        serviceReadyObservers.add(serviceObserver);
        observeForever(liveData, serviceObserver);
    }

    public boolean isDebugViewVisible() {
        return false;
    }

    public void toggleDebugView() {
    }

    public void hideDebugView() {
    }

    public void chooseOtherPlayer(CharSequence title) {
        PlayerManager player = player();
        PlayerHelper.choose(this, player.getUrl(), player.getHeaders(), player.isVod(), player.getPosition(), title);
        setRedirect(true);
    }

    protected void setSeekNextFocusDown(int id) {
        View timeBar = getSeekView().findViewById(androidx.media3.ui.R.id.exo_progress);
        if (timeBar != null) timeBar.setNextFocusDownId(id);
    }

    protected void setActionFocusBoundary(View view) {
        if (view == null) return;
        if (view.isFocusable() && view.getId() != View.NO_ID) view.setNextFocusDownId(view.getId());
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) setActionFocusBoundary(group.getChildAt(i));
    }

    protected boolean isIdle() {
        return isPlaybackState(Player.STATE_IDLE);
    }

    protected boolean isEnded() {
        return isPlaybackState(Player.STATE_ENDED);
    }

    protected boolean isBuffering() {
        return isPlaybackState(Player.STATE_BUFFERING);
    }

    protected boolean isPaused() {
        return mController != null && !isBuffering() && !isIdle();
    }

    private boolean isPlaybackState(int state) {
        return mController != null && mController.getPlaybackState() == state;
    }

    protected void onServiceConnected() {
    }

    protected void onPrepare() {
    }

    protected void onTracksChanged() {
    }

    protected void onDecodeChanged() {
    }

    protected void onMediaOptionsChanged() {
    }

    protected void onError(String msg) {
    }

    protected void onPlayingChanged(boolean isPlaying) {
    }

    protected void onStateChanged(int state) {
    }

    protected void onSizeChanged(VideoSize size) {
    }

    protected void onReclaim() {
    }

    protected long startPositionMs() {
        return C.TIME_UNSET;
    }

    protected boolean seekTo(long deltaMs) {
        PlayerManager player = player();
        long targetMs = Math.max(0, player.getPosition() + deltaMs);
        long durationMs = player.getDuration();
        boolean seekToEnd = durationMs > 0 && targetMs >= durationMs;
        mController.seekTo(seekToEnd ? durationMs : targetMs);
        if (!seekToEnd) mController.play();
        return seekToEnd;
    }

    protected void startPlayer(String key, Result result, boolean useParse, long timeout, MediaMetadata metadata) {
        startPlayer(key, result, useParse, timeout, startPositionMs(), metadata);
    }

    protected void startPlayer(String key, Result result, boolean useParse, long timeout, long startPositionMs, MediaMetadata metadata) {
        if (result.getDrm() != null && !FrameworkMediaDrm.isCryptoSchemeSupported(result.getDrm().getUUID())) {
            onError(ResUtil.getString(R.string.error_play_drm));
        } else if (result.hasMsg()) {
            onError(result.getMsg());
        } else if (result.getRealUrl().isEmpty()) {
            onError(ResUtil.getString(R.string.error_play_url));
        } else if (result.needParse() || useParse) {
            attachSurface();
            player().parse(key, result, useParse, metadata, startPositionMs);
        } else {
            attachSurface();
            player().start(PlaySpec.from(result, key, metadata), timeout, startPositionMs);
        }
    }

    private void bindPlaybackService() {
        startService(new Intent(this, PlaybackService.class));
        bindService(new Intent(this, PlaybackService.class).setAction(PlaybackService.LOCAL_BIND_ACTION), this, BIND_AUTO_CREATE);
        buildControllerAsync();
        bound = true;
    }

    private void buildControllerAsync() {
        SessionToken token = new SessionToken(this, new ComponentName(this, PlaybackService.class));
        mControllerFuture = new MediaController.Builder(this, token).setListener(this).buildAsync();
        mControllerFuture.addListener(this::onControllerConnected, ContextCompat.getMainExecutor(this));
    }

    private void onControllerConnected() {
        try {
            mController = mControllerFuture.get();
            getSeekView().setPlayer(mController);
            mController.addListener(this);
            updateKeyIncrement();
        } catch (Exception ignored) {
        }
    }

    private void addSeekListener() {
        getSeekView().getTimeBar().addListener(new TimeBar.OnScrubListener() {
            @Override
            public void onScrubStart(@NonNull TimeBar timeBar, long position) {
                PlaybackActivity.this.setScrubbing(true);
            }

            @Override
            public void onScrubMove(@NonNull TimeBar timeBar, long position) {
                PlaybackActivity.this.setScrubbing(true);
            }

            @Override
            public void onScrubStop(@NonNull TimeBar timeBar, long position, boolean canceled) {
                PlaybackActivity.this.onScrubStop(canceled);
            }
        });
    }

    protected boolean isScrubbing() {
        return scrubbing;
    }

    protected void onScrubStop(boolean canceled) {
        if (!canceled && mController != null && mController.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)) mController.play();
        setScrubbing(false);
    }

    private void setScrubbing(boolean scrubbing) {
        if (this.scrubbing == scrubbing) return;
        this.scrubbing = scrubbing;
        onScrubbingChanged(scrubbing);
    }

    protected void onScrubbingChanged(boolean scrubbing) {
    }

    private void updateKeyIncrement() {
        long durationMs = mController == null ? C.TIME_UNSET : mController.getDuration();
        long incrementMs = getKeyTimeIncrementMs(durationMs);
        TimeBar timeBar = getSeekView().getTimeBar();
        timeBar.setKeyTimeIncrement(incrementMs);
    }

    private long getKeyTimeIncrementMs(long durationMs) {
        if (durationMs > TimeUnit.HOURS.toMillis(3)) {
            return TimeUnit.MINUTES.toMillis(5);
        } else if (durationMs > TimeUnit.MINUTES.toMillis(30)) {
            return TimeUnit.MINUTES.toMillis(1);
        } else if (durationMs > TimeUnit.MINUTES.toMillis(15)) {
            return TimeUnit.SECONDS.toMillis(30);
        } else if (durationMs > TimeUnit.MINUTES.toMillis(10)) {
            return TimeUnit.SECONDS.toMillis(15);
        } else {
            return TimeUnit.SECONDS.toMillis(10);
        }
    }

    private PendingIntent buildSessionIntent() {
        Intent intent = new Intent(this, getClass()).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        Bundle extras = getIntent().getExtras();
        if (extras != null) intent.putExtras(extras);
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private boolean shouldReclaim() {
        return mService != null && !isOwner();
    }

    private boolean canActivateService() {
        return mService != null && !isFinishing() && getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED);
    }

    private void activateService() {
        if (!canActivateService()) return;
        mService.replaceBinding(this::closePiP);
        mService.setSessionActivity(buildSessionIntent());
        if (!isRedirect()) mService.setNavigationCallback(getNavigationCallback(), getPlaybackKey());
        dispatchPendingObservers();
        if (initialized) return;
        initialized = true;
        onServiceConnected();
        applyDanmaku();
    }

    private void closePiP() {
        if (!isInPictureInPictureMode()) return;
        detach();
        finish();
    }

    private void attachSurface() {
        if (appliedRender != PlayerSetting.getRender()) {
            setRender();
            return;
        }
        if (mService != null) {
            boolean mpv = player().getEngine() == PlayerSetting.ENGINE_MPV;
            // MPV renders directly to the Surface. An artwork/EPG logo left by PlayerView can
            // otherwise cover the moving video even after native rendering has started.
            //
            // Do not use the deprecated setUseArtwork(boolean) helper here. FongMi's Media3 fork
            // currently maps its boolean in reverse, so setUseArtwork(false) enables FIT artwork
            // and leaves a static poster above MPV's moving Surface.
            getPlayerView().setArtworkDisplayMode(
                    mpv
                            ? PlayerView.ARTWORK_DISPLAY_MODE_OFF
                            : PlayerView.ARTWORK_DISPLAY_MODE_FIT);
            if (getPlayerView().getPlayer() == null) {
                getPlayerView().setPlayer(player().getPlayer());
            }
            if (mpv) {
                // PlaybackService may render MPV's first frame before this activity attaches its
                // PlayerView listener. In that race PlayerView misses onRenderedFirstFrame() and
                // its shutter permanently covers the live Surface with a static black/poster
                // frame. MPV owns the visible Surface, so remove that overlay deterministically.
                View shutter = getPlayerView().findViewById(androidx.media3.ui.R.id.exo_shutter);
                if (shutter != null) shutter.setVisibility(View.INVISIBLE);
            }
        }
        syncPlaybackOverlays();
    }

    private void syncPlaybackOverlays() {
        PlayerManager pm = mService == null ? null : player();
        PlaybackOverlayBinder.sync(getPlayerView(), pm);
        if (subtitleResourceBinding != null) subtitleResourceBinding.bind(pm == null ? null : pm.getPlayer());
        if (secondarySubtitleOverlay != null) secondarySubtitleOverlay.bind(pm);
        if (pm != null && !pm.isReleased()) {
            pm.setSubtitleStyle();
            pm.setVolumeGain(PlayerSetting.getVolumeGain());
        }
    }

    private void detachSurface() {
        if (subtitleResourceBinding != null) subtitleResourceBinding.bind(null);
        getPlayerView().setPlayer(null);
    }

    private void setRender() {
        appliedRender = PlayerSetting.getRender();
        getPlayerView().setRender(appliedRender);
        detachSurface();
        attachSurface();
    }

    private void configurePlayerView() {
        PlayerView playerView = getPlayerView();
        // App overlay owns pause/transport UI; Media3 PlayerView must stay chrome-free.
        playerView.setUseController(false);
        playerView.setControllerAutoShow(false);
        appliedRender = PlayerSetting.getRender();
        playerView.setRender(appliedRender);
        playerView.getSubtitleView().setStyle(getCaptionStyle());
        playerView.getSubtitleView().setApplyEmbeddedStyles(true);
        playerView.getSubtitleView().setApplyEmbeddedFontSizes(false);
        subtitleResourceBinding = new com.fongmi.android.tv.ui.playback.SubtitleResourceBinding(playerView.getSubtitleView());
        secondarySubtitleOverlay = new SecondarySubtitleOverlay(playerView);
        PlaybackOverlayBinder.sync(playerView, mService == null ? null : player());
    }

    private CaptionStyleCompat getCaptionStyle() {
        return SubtitleSetting.captionStyle(this);
    }

    private void applyDanmaku() {
        PlaybackOverlayBinder.applyDanmaku(getPlayerView(), mService == null ? null : player());
    }

    private void releasePlaybackService() {
        if (mService != null) releaseService(isOwner());
        detach();
    }

    private void releaseService(boolean owner) {
        mService.removePlayerCallback(mPlayerCallback);
        if (owner) mService.setNavigationCallback(null, null);
        if (mService.hasMediaClient() || mService.hasPlayerCallback()) {
            if (owner) mService.suspend();
            mService.resetSessionActivity();
        } else if (owner) {
            mService.shutdown();
        }
    }

    private void detach() {
        releaseController();
        releaseBinding();
    }

    private void pausePlayback() {
        if (mController != null) mController.pause();
        else if (mService != null) player().pause();
    }

    private void releaseController() {
        if (mControllerFuture != null) MediaController.releaseFuture(mControllerFuture);
        if (mController != null) mController.removeListener(this);
        if (mController != null) getSeekView().setPlayer(null);
        mControllerFuture = null;
        mController = null;
    }

    private void releaseBinding() {
        if (!bound) return;
        bound = false;
        initialized = false;
        if (mService != null) mService.removePlayerCallback(mPlayerCallback);
        unbindService(this);
        mService = null;
    }

    private void clearForeverObservers() {
        foreverObserverRemovers.forEach(Runnable::run);
        foreverObserverRemovers.clear();
        serviceReadyObservers.clear();
    }

    private void dispatchPendingObservers() {
        if (!canActivateService()) return;
        serviceReadyObservers.forEach(ServiceReadyObserver::dispatch);
    }

    private final PlaybackService.PlayerCallback mPlayerCallback = new PlaybackService.PlayerCallback() {

        @Override
        public void onPrepare() {
            if (isOwner()) PlaybackActivity.this.onPrepare();
        }

        @Override
        public void onTracksChanged() {
            if (isOwner()) PlaybackActivity.this.onTracksChanged();
        }

        @Override
        public void onDecodeChanged() {
            if (isOwner()) PlaybackActivity.this.onDecodeChanged();
        }

        @Override
        public void onMediaOptionsChanged() {
            if (isOwner()) PlaybackActivity.this.onMediaOptionsChanged();
        }

        @Override
        public void onError(String msg) {
            if (isOwner()) PlaybackActivity.this.onError(msg);
        }

        @Override
        public void onPlayerRebuild(Player player) {
            if (!isOwner()) return;
            traffic.reset();
            setRender();
        }

        @Override
        public void onDanmakuSourceChanged(Uri uri) {
            if (isOwner()) getPlayerView().setDanmakuSource(uri);
        }

        @Override
        public void onDanmakuConfigChanged(DanmakuConfig config) {
            if (isOwner()) getPlayerView().setDanmakuConfig(config);
        }

        @Override
        public void onDanmakuEnabledChanged(boolean enabled) {
            if (isOwner()) getPlayerView().setDanmakuEnabled(enabled);
        }

        @Override
        public void onDanmakuSent(String text) {
            if (isOwner()) getPlayerView().sendDanmaku(text);
        }

        @Override
        public void onSecondarySubtitleChanged(Sub sub) {
            if (isOwner() && secondarySubtitleOverlay != null) secondarySubtitleOverlay.setSubtitle(sub);
        }

        @Override
        public void onSubtitleStyleChanged() {
            if (isOwner() && secondarySubtitleOverlay != null) secondarySubtitleOverlay.applyStyle();
        }
    };

    @Override
    protected void initView(Bundle savedInstanceState) {
        super.initView(savedInstanceState);
        configurePlayerView();
        bindPlaybackService();
        addSeekListener();
    }

    @Override
    public void onEvents(@NonNull Player player, @NonNull Player.Events events) {
        if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_POSITION_DISCONTINUITY, Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_PLAYBACK_STATE_CHANGED, Player.EVENT_AVAILABLE_COMMANDS_CHANGED)) updateKeyIncrement();
    }

    @Override
    public void onIsPlayingChanged(boolean isPlaying) {
        if (!isOwner()) return;
        if (isPlaying) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else if (!isBuffering()) getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        onPlayingChanged(isPlaying);
    }

    @Override
    public void onPlaybackStateChanged(int state) {
        if (isOwner()) onStateChanged(state);
    }

    @Override
    public void onVideoSizeChanged(@NonNull VideoSize size) {
        if (isOwner()) onSizeChanged(size);
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        mService = ((PlaybackService.LocalBinder) binder).getService();
        player().setLiveMode(isLivePlayback());
        mService.addPlayerCallback(mPlayerCallback);
        activateService();
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        initialized = false;
        mService = null;
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (secondarySubtitleOverlay != null) secondarySubtitleOverlay.setActive(true);
        activateService();
    }

    @Override
    protected void onResume() {
        super.onResume();
        setRedirect(false);
        if (shouldReclaim()) {
            detachSurface();
            onReclaim();
        } else {
            attachSurface();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (isRedirect()) pausePlayback();
    }

    @Override
    protected void onStop() {
        if (secondarySubtitleOverlay != null) secondarySubtitleOverlay.setActive(false);
        super.onStop();
        if (!isOwner() || mService == null) return;
        if (stopPlaybackOnBackground() && mService != null) {
            // Stop the native decoder while its render target is still valid. Detaching first
            // makes mpv process the stop/video-reconfig events without an Android surface, which
            // can tear down gpu-next with "Missing surface pointer" and poison the next playback.
            mService.suspend();
            detachSurface();
        } else if (isFinishing() || PlayerSetting.isBackgroundOff()) {
            // The service/player can be ready before the asynchronous MediaController connects.
            // Never leave audio running just because the activity stopped during that window.
            pausePlayback();
        }
    }

    @Override
    protected void onDestroy() {
        clearForeverObservers();
        if (secondarySubtitleOverlay != null) secondarySubtitleOverlay.release();
        secondarySubtitleOverlay = null;
        if (subtitleResourceBinding != null) subtitleResourceBinding.release();
        subtitleResourceBinding = null;
        super.onDestroy();
        releasePlaybackService();
    }

    private final class ServiceReadyObserver<T> implements Observer<T> {

        private final Observer<T> observer;
        private T pendingValue;
        private boolean pending;

        private ServiceReadyObserver(Observer<T> observer) {
            this.observer = observer;
        }

        @Override
        public void onChanged(T value) {
            if (!canActivateService()) {
                pendingValue = value;
                pending = true;
            } else {
                deliver(value);
            }
        }

        private void deliver(T value) {
            pendingValue = null;
            pending = false;
            observer.onChanged(value);
        }

        private void dispatch() {
            if (pending) deliver(pendingValue);
        }
    }
}
