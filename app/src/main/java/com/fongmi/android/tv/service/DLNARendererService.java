package com.fongmi.android.tv.service;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.media3.common.C;
import androidx.media3.common.Player;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.dlna.CastAction;
import com.fongmi.android.tv.dlna.CastNetworkWatcher;
import com.fongmi.android.tv.dlna.DLNAAvTransportImpl;
import com.fongmi.android.tv.dlna.DLNARenderingControlImpl;
import com.fongmi.android.tv.dlna.DLNAServiceConfiguration;
import com.fongmi.android.tv.dlna.DlnaMulticastLock;
import com.fongmi.android.tv.dlna.RenderState;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.setting.DlnaSetting;
import com.fongmi.android.tv.utils.Notify;

import org.jupnp.UpnpServiceConfiguration;
import org.jupnp.android.AndroidRouter;
import org.jupnp.android.AndroidUpnpServiceImpl;
import org.jupnp.binding.annotations.AnnotationLocalServiceBinder;
import org.jupnp.model.DefaultServiceManager;
import org.jupnp.model.meta.DeviceDetails;
import org.jupnp.model.meta.DeviceIdentity;
import org.jupnp.model.meta.LocalDevice;
import org.jupnp.model.meta.LocalService;
import org.jupnp.model.meta.ManufacturerDetails;
import org.jupnp.model.meta.ModelDetails;
import org.jupnp.model.types.UDADeviceType;
import org.jupnp.model.types.UDN;
import org.jupnp.support.avtransport.lastchange.AVTransportLastChangeParser;
import org.jupnp.support.connectionmanager.ConnectionManagerService;
import org.jupnp.support.lastchange.LastChangeAwareServiceManager;
import org.jupnp.support.renderingcontrol.lastchange.RenderingControlLastChangeParser;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class DLNARendererService extends AndroidUpnpServiceImpl implements ServiceConnection {

    private final IBinder binder = new LocalBinder();

    private volatile PlayerManager player;
    private volatile boolean isDlnaActive;

    private DLNARenderingControlImpl renderingControlImpl;
    private DLNAAvTransportImpl avTransportImpl;
    private PlaybackService playbackService;
    private Player currentListenerPlayer;
    private boolean bound;
    private boolean upnpStarted;

    private static Runnable pendingApply;
    private static Runnable pendingStart;
    private static Runnable alivePulse;

    // Immediate republish happens at session boundaries; a five-minute safety pulse is enough
    // to refresh controller caches without waking a TV every minute while idle.
    private static final long ALIVE_PULSE_MS = 5 * 60_000L;

    public static void start(Context context) {
        if (!DlnaSetting.isEnabled()) return;
        ContextCompat.startForegroundService(context, new Intent(context, DLNARendererService.class));
    }

    public static void stop(Context context) {
        if (pendingApply != null) {
            App.removeCallbacks(pendingApply);
            pendingApply = null;
        }
        if (pendingStart != null) {
            App.removeCallbacks(pendingStart);
            pendingStart = null;
        }
        context.stopService(new Intent(context, DLNARendererService.class));
    }

    public static void apply(Context context) {
        Context app = context.getApplicationContext();
        if (pendingApply != null) App.removeCallbacks(pendingApply);
        if (pendingStart != null) App.removeCallbacks(pendingStart);
        pendingApply = () -> {
            pendingApply = null;
            stop(app);
            pendingStart = () -> {
                pendingStart = null;
                start(app);
            };
            App.post(pendingStart, 400);
        };
        App.post(pendingApply, 1000);
    }

    @Override
    protected UpnpServiceConfiguration createConfiguration() {
        return new DLNAServiceConfiguration(true);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Notification notification = new NotificationCompat.Builder(this, Notify.DEFAULT).setSmallIcon(R.drawable.ic_notification).setContentTitle(getString(R.string.app_name)).setSilent(true).build();
        startForeground(Notify.ID + 1, notification);
        if (!DlnaSetting.isEnabled()) {
            stopSelf();
            return;
        }
        CastNetworkWatcher.register(this);
        try {
            DlnaMulticastLock.acquire(this, this);
            upnpService.startup();
            upnpStarted = true;
            if (registerLocalDevice()) scheduleAlivePulse();
        } catch (RuntimeException e) {
            android.util.Log.e("DlnaRenderer", "DLNA renderer startup failed", e);
            stopSelf();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    private void scheduleAlivePulse() {
        if (alivePulse != null) App.removeCallbacks(alivePulse);
        alivePulse = new Runnable() {
            @Override
            public void run() {
                republish();
                App.post(this, ALIVE_PULSE_MS);
            }
        };
        App.post(alivePulse, ALIVE_PULSE_MS);
    }

    private boolean registerLocalDevice() {
        LocalService<DLNAAvTransportImpl> avTransport = createAvTransport();
        LocalService<ConnectionManagerService> connManager = createConnectionManager();
        LocalService<DLNARenderingControlImpl> renderControl = createRenderingControl();
        DeviceIdentity identity = new DeviceIdentity(new UDN(UUID.nameUUIDFromBytes((Build.MANUFACTURER + Build.MODEL + "-MediaRenderer").getBytes(StandardCharsets.UTF_8))));
        UDADeviceType type = new UDADeviceType("MediaRenderer", 1);
        DeviceDetails details = new DeviceDetails(DlnaSetting.getDisplayName(), new ManufacturerDetails(Build.MANUFACTURER), new ModelDetails(Build.MODEL, "DLNA Renderer", "1.0"));
        try {
            LocalDevice device = new LocalDevice(identity, type, details, new LocalService[]{avTransport, connManager, renderControl});
            upnpService.getRegistry().addDevice(device);
            android.util.Log.i("DlnaRenderer", "MediaRenderer registered udn=" + identity.getUdn()
                    + " name=" + DlnaSetting.getDisplayName()
                    + " iface=" + com.fongmi.android.tv.setting.DlnaSetting.resolveInterfaceName());
            return true;
        } catch (Exception e) {
            // A failed LocalDevice means zero SSDP advertisement. Stop instead of keeping an
            // invisible foreground service alive; START_STICKY/network rebind may retry later.
            android.util.Log.e("DlnaRenderer", "MediaRenderer registration failed", e);
            stopSelf();
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private LocalService<DLNAAvTransportImpl> createAvTransport() {
        avTransportImpl = new DLNAAvTransportImpl(this);
        LocalService<DLNAAvTransportImpl> service = new AnnotationLocalServiceBinder().read(DLNAAvTransportImpl.class);
        if (service == null) throw new IllegalStateException("AVTransport LocalService null — missing @UpnpService?");
        service.setManager(new LastChangeAwareServiceManager<>(service, new AVTransportLastChangeParser()) {
            @Override
            protected DLNAAvTransportImpl createServiceInstance() {
                return avTransportImpl;
            }
        });
        return service;
    }

    @SuppressWarnings("unchecked")
    private LocalService<ConnectionManagerService> createConnectionManager() {
        LocalService<ConnectionManagerService> service = new AnnotationLocalServiceBinder().read(ConnectionManagerService.class);
        if (service == null) throw new IllegalStateException("ConnectionManager LocalService null");
        service.setManager(new DefaultServiceManager<>(service, ConnectionManagerService.class));
        return service;
    }

    @SuppressWarnings("unchecked")
    private LocalService<DLNARenderingControlImpl> createRenderingControl() {
        renderingControlImpl = new DLNARenderingControlImpl(this);
        LocalService<DLNARenderingControlImpl> service = new AnnotationLocalServiceBinder().read(DLNARenderingControlImpl.class);
        if (service == null) throw new IllegalStateException("RenderingControl LocalService null — missing @UpnpService?");
        service.setManager(new LastChangeAwareServiceManager<>(service, new RenderingControlLastChangeParser()) {
            @Override
            protected DLNARenderingControlImpl createServiceInstance() {
                return renderingControlImpl;
            }
        });
        return service;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        if (alivePulse != null) {
            App.removeCallbacks(alivePulse);
            alivePulse = null;
        }
        unbindPlaybackService();
        if (!upnpStarted) {
            shutdownPartiallyInitializedService();
            DlnaMulticastLock.release(this);
            CastNetworkWatcher.unregisterIfUnused(this);
            return;
        }
        try {
            // Same jUPnP 3.0.4 shutdown NPE as DlnaBrowserService when startup() never ran.
            super.onDestroy();
        } catch (NullPointerException ignored) {
            shutdownPartiallyInitializedService();
        } finally {
            DlnaMulticastLock.release(this);
            CastNetworkWatcher.unregisterIfUnused(this);
        }
    }

    private void shutdownPartiallyInitializedService() {
        if (upnpService == null) return;
        try {
            if (upnpService.getRouter() instanceof AndroidRouter router) router.unregisterBroadcastReceiver();
        } catch (RuntimeException ignored) {
        }
        try {
            if (upnpService.getRegistry() != null) upnpService.getRegistry().shutdown();
        } catch (RuntimeException ignored) {
        }
        try {
            if (upnpService.getConfiguration() != null) upnpService.getConfiguration().shutdown();
        } catch (RuntimeException ignored) {
        }
        try {
            if (upnpService.getRouter() != null) upnpService.getRouter().shutdown();
        } catch (Exception ignored) {
        }
    }

    private void bindPlaybackService() {
        if (bound) return;
        bound = bindService(new Intent(this, PlaybackService.class).setAction(PlaybackService.LOCAL_BIND_ACTION), this, BIND_AUTO_CREATE);
    }

    private void cleanupPlaybackRefs() {
        App.removeCallbacks(positionUpdater);
        if (currentListenerPlayer != null) {
            currentListenerPlayer.removeListener(listener);
            currentListenerPlayer = null;
        }
        if (playbackService != null) {
            playbackService.removePlayerCallback(playerCallback);
            playbackService = null;
        }
        player = null;
        if (avTransportImpl != null) avTransportImpl.setPlayerManager(null);
    }

    private void unbindPlaybackService() {
        if (!bound) return;
        bound = false;
        cleanupPlaybackRefs();
        unbindService(this);
    }

    public void setDlnaActive(boolean active) {
        isDlnaActive = active;
        if (avTransportImpl != null) avTransportImpl.setDlnaActive(active);
        if (active) {
            DlnaMulticastLock.acquire(this, this);
            bindPlaybackService();
            republish();
        } else {
            if (avTransportImpl != null) avTransportImpl.reset();
            unbindPlaybackService();
            // Controllers drop a device after a session (byebye/cache). Re-send ssdp:alive
            // so the next scan can see us again without restarting the whole stack.
            republish();
        }
    }

    /** Re-send SSDP alive for the MediaRenderer (does not recreate the UPnP stack). */
    public void republish() {
        if (!upnpStarted) return;
        try {
            upnpService.getRegistry().advertiseLocalDevices();
            android.util.Log.i("DlnaRenderer", "republish alive isDlnaActive=" + isDlnaActive);
        } catch (RuntimeException e) {
            android.util.Log.w("DlnaRenderer", "republish failed", e);
        }
    }

    public long consumePendingSeekMs() {
        return avTransportImpl != null ? avTransportImpl.consumePendingSeekMs() : -1;
    }

    public CastAction consumeNext() {
        return avTransportImpl != null ? avTransportImpl.popNext() : null;
    }

    public void notifyError() {
        if (avTransportImpl != null) avTransportImpl.fireStateChange(RenderState.STOPPED);
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        if (!bound || !isDlnaActive) {
            unbindPlaybackService();
            return;
        }
        playbackService = ((PlaybackService.LocalBinder) binder).getService();
        playbackService.addPlayerCallback(playerCallback);
        player = playbackService.player();
        avTransportImpl.setPlayerManager(player);
        currentListenerPlayer = player.getPlayer();
        currentListenerPlayer.addListener(listener);
        App.post(positionUpdater, 1000);
        notifyState();
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        cleanupPlaybackRefs();
    }

    private void notifyState() {
        if (avTransportImpl == null || player == null || !isDlnaActive) return;
        int state = player.getPlaybackState();
        if (state == Player.STATE_IDLE) return;
        avTransportImpl.updatePositionCache(player.getPosition(), getDuration());
        RenderState renderState = switch (state) {
            case Player.STATE_BUFFERING -> RenderState.PREPARING;
            case Player.STATE_READY -> player.isPlaying() ? RenderState.PLAYING : RenderState.PAUSED;
            case Player.STATE_ENDED -> avTransportImpl.hasNext() ? RenderState.PREPARING : RenderState.STOPPED;
            default -> null;
        };
        if (renderState != null) avTransportImpl.fireStateChange(renderState);
    }

    private final Runnable positionUpdater = new Runnable() {
        @Override
        public void run() {
            if (!isDlnaActive || player == null) return;
            if (avTransportImpl != null && player.isPlaying()) avTransportImpl.updatePositionCache(player.getPosition(), getDuration());
            App.post(this, 1000);
        }
    };

    private long getDuration() {
        long duration = player.getDuration();
        return duration == C.TIME_UNSET || duration <= 0 ? -1 : duration;
    }

    private final Player.Listener listener = new Player.Listener() {
        @Override
        public void onPlaybackStateChanged(int playbackState) {
            notifyState();
        }

        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            notifyState();
        }
    };

    private final PlaybackService.PlayerCallback playerCallback = new PlaybackService.PlayerCallback() {
        @Override
        public void onPlayerRebuild(Player newPlayer) {
            if (currentListenerPlayer != null) currentListenerPlayer.removeListener(listener);
            currentListenerPlayer = newPlayer;
            newPlayer.addListener(listener);
            notifyState();
        }
    };

    public class LocalBinder extends Binder {

        public DLNARendererService getService() {
            return DLNARendererService.this;
        }
    }
}
