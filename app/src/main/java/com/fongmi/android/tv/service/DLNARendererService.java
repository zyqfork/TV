package com.fongmi.android.tv.service;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

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
import org.jupnp.protocol.async.SendingNotificationAlive;
import org.jupnp.support.avtransport.lastchange.AVTransportLastChangeParser;
import org.jupnp.support.model.ProtocolInfo;
import org.jupnp.support.model.ProtocolInfos;
import org.jupnp.support.connectionmanager.ConnectionManagerService;
import org.jupnp.support.lastchange.LastChangeAwareServiceManager;
import org.jupnp.support.renderingcontrol.lastchange.RenderingControlLastChangeParser;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

public class DLNARendererService extends AndroidUpnpServiceImpl implements ServiceConnection {

    private final IBinder binder = new LocalBinder();

    private volatile PlayerManager player;
    private volatile boolean isDlnaActive;

    private DLNARenderingControlImpl renderingControlImpl;
    private DLNAAvTransportImpl avTransportImpl;
    private PlaybackService playbackService;
    private Player currentListenerPlayer;
    private LocalDevice rendererDevice;
    private boolean bound;
    private boolean upnpStarted;
    private volatile boolean destroyed;

    private static Runnable pendingApply;
    private static Runnable pendingStart;
    private Runnable alivePulse;
    private final Runnable startupAlive = this::sendCompatibilityAlive;
    private final ExecutorService healthExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "dlna-health");
        thread.setDaemon(true);
        return thread;
    });

    // Bilibili on the observed 10.0.0.243 device sends no M-SEARCH at all; it opens a short passive
    // window for ssdp:alive. One- or five-minute advertisements therefore only work by chance, while
    // toggling DLNA works because it broadcasts immediately. Pulse every five seconds while the
    // receiver screen is interactive, and back off to one minute while idle to protect mobile power.
    private static final long ACTIVE_ALIVE_PULSE_MS = 5_000L;
    private static final long IDLE_ALIVE_PULSE_MS = 60_000L;
    private static final long[] STARTUP_ALIVE_DELAYS_MS = {1_000L, 3_000L};

    /** Gap between stopService and startForegroundService in {@link #apply(Context)}. */
    private static final long RESTART_GAP_MS = 1500L;

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
            // Long enough for the old jUPnP instance to finish its asynchronous teardown: jUPnP
            // closes the old listen socket on a background thread, and rebinding 49152 while it is
            // still open used to leave the renderer advertising a port nothing served.
            App.post(pendingStart, RESTART_GAP_MS);
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
            // jUPnP's Android router rebuilds every transport on each CONNECTIVITY_CHANGE
            // (disable() then enable()). This box's network churns constantly — IPv6 temporary
            // addresses rotate and docker/tailscale interfaces come and go — and a disable() that
            // is not followed by a successful enable() leaves the router holding a stream server
            // whose socket is already closed. The renderer then keeps answering M-SEARCH and
            // advertising LOCATION http://<ip>:49152/…, but nothing listens there, so every
            // controller fails to load the description and the device never appears in its list —
            // until the whole stack is restarted from the settings page.
            // CastNetworkWatcher already restarts the stack on network changes, so jUPnP's own
            // receiver is redundant; dropping it removes the churn and that half-torn-down state.
            if (upnpService.getRouter() instanceof AndroidRouter router) router.unregisterBroadcastReceiver();
            if (registerLocalDevice()) {
                scheduleAlivePulse();
                scheduleHealthCheck();
            }
        } catch (RuntimeException e) {
            android.util.Log.e("DlnaRenderer", "DLNA renderer startup failed", e);
            stopSelf();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    /**
     * Guard against the renderer advertising a dead LOCATION.
     *
     * The device is only useful if the URL in its SSDP replies actually serves the description, so
     * every so often connect to it locally. If nothing answers, rebuild the stack (the same thing
     * the settings toggle does) instead of staying invisible on the LAN forever.
     */
    private static final long HEALTH_CHECK_MS = 15_000L;

    private void scheduleHealthCheck() {
        App.post(healthCheck, HEALTH_CHECK_MS * 3);
    }

    private final Runnable healthCheck = new Runnable() {
        @Override
        public void run() {
            if (destroyed || healthExecutor.isShutdown()) return;
            // Reuse one worker instead of creating a new thread every 15 seconds. The destroyed
            // check on both sides also prevents an in-flight probe from resurrecting callbacks
            // after onDestroy() removed them.
            try {
                healthExecutor.execute(() -> {
                    boolean reachable = isStreamServerReachable();
                    App.post(() -> {
                        if (destroyed) return;
                        if (upnpStarted && DlnaSetting.isEnabled() && !reachable) {
                            android.util.Log.w("DlnaRenderer", "HTTP stream server unreachable, restarting the renderer");
                            apply(DLNARendererService.this);
                            return;
                        }
                        App.post(healthCheck, HEALTH_CHECK_MS);
                    });
                });
            } catch (RejectedExecutionException ignored) {
                // onDestroy() won the race after the initial isShutdown() check.
            }
        }
    };

    private boolean isStreamServerReachable() {
        if (upnpService == null || upnpService.getRouter() == null) return true;
        java.util.List<org.jupnp.model.NetworkAddress> servers;
        try {
            servers = upnpService.getRouter().getActiveStreamServers(null);
        } catch (Exception e) {
            return true;
        }
        if (servers.isEmpty()) return false;
        for (org.jupnp.model.NetworkAddress address : servers) {
            try (java.net.Socket socket = new java.net.Socket()) {
                socket.connect(new java.net.InetSocketAddress(address.getAddress(), address.getPort()), 500);
                return true;
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    private void scheduleAlivePulse() {
        if (alivePulse != null) App.removeCallbacks(alivePulse);
        App.removeCallbacks(startupAlive);
        // The first addDevice advertisement can happen before a control-point opens its scan page.
        // These early pulses cover that startup race before the regular compatibility cadence.
        for (long delay : STARTUP_ALIVE_DELAYS_MS) App.post(startupAlive, delay);
        alivePulse = new Runnable() {
            @Override
            public void run() {
                sendCompatibilityAlive();
                App.post(this, getAlivePulseDelayMs());
            }
        };
        App.post(alivePulse, getAlivePulseDelayMs());
    }

    private long getAlivePulseDelayMs() {
        PowerManager power = (PowerManager) getSystemService(Context.POWER_SERVICE);
        return power != null && power.isInteractive() ? ACTIVE_ALIVE_PULSE_MS : IDLE_ALIVE_PULSE_MS;
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
            rendererDevice = device;
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

    /**
     * Media formats the renderer accepts, advertised through ConnectionManager.GetProtocolInfo.
     *
     * A DLNA renderer with an empty Sink list is unusable to strict controllers because they use
     * this action to filter devices before offering them as playback targets. Discovery testing later
     * showed that Bilibili also has an independent passive-SSDP compatibility requirement.
     */
    private static final ProtocolInfos SINK_PROTOCOLS = new ProtocolInfos(
            "http-get:*:video/mp4:*,http-get:*:video/x-matroska:*,http-get:*:video/x-msvideo:*,"
                    + "http-get:*:video/mpeg:*,http-get:*:video/mp2t:*,http-get:*:video/quicktime:*,"
                    + "http-get:*:video/webm:*,http-get:*:video/x-flv:*,http-get:*:video/3gpp:*,"
                    + "http-get:*:video/x-ms-wmv:*,http-get:*:video/vnd.dlna.mpeg-tts:*,"
                    + "http-get:*:application/vnd.apple.mpegurl:*,http-get:*:application/x-mpegURL:*,"
                    + "http-get:*:audio/mpegurl:*,http-get:*:audio/x-mpegurl:*,"
                    + "http-get:*:application/dash+xml:*,http-get:*:audio/mpeg:*,"
                    + "http-get:*:audio/mp4:*,http-get:*:audio/mp4a-latm:*,"
                    + "http-get:*:audio/x-ms-wma:*,http-get:*:audio/flac:*,http-get:*:audio/x-flac:*,"
                    + "http-get:*:audio/wav:*,http-get:*:audio/x-wav:*,http-get:*:audio/ogg:*,"
                    + "http-get:*:audio/aac:*,http-get:*:image/jpeg:*,http-get:*:image/png:*,"
                    + "http-get:*:image/gif:*,http-get:*:image/webp:*,"
                    + "http-get:*:application/octet-stream:*");

    private static final ProtocolInfos SOURCE_PROTOCOLS = new ProtocolInfos(new ProtocolInfo[0]);

    @SuppressWarnings("unchecked")
    private LocalService<ConnectionManagerService> createConnectionManager() {
        LocalService<ConnectionManagerService> service = new AnnotationLocalServiceBinder().read(ConnectionManagerService.class);
        if (service == null) throw new IllegalStateException("ConnectionManager LocalService null");
        service.setManager(new DefaultServiceManager<>(service, ConnectionManagerService.class) {
            @Override
            protected ConnectionManagerService createServiceInstance() {
                return new ConnectionManagerService(SOURCE_PROTOCOLS, SINK_PROTOCOLS);
            }
        });
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
        destroyed = true;
        rendererDevice = null;
        App.removeCallbacks(healthCheck);
        App.removeCallbacks(startupAlive);
        healthExecutor.shutdownNow();
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

    /**
     * One complete alive set (root, UDN, device type and every service type).
     *
     * jUPnP's normal advertisement repeats that set three times 150ms apart, which is appropriate
     * for startup/session changes. Doing the same every five seconds would produce unnecessary LAN
     * traffic, so the passive-client compatibility pulse sends one set. Frequent pulses provide
     * the packet-loss redundancy while cutting steady-state traffic to one third.
     */
    private void sendCompatibilityAlive() {
        LocalDevice device = rendererDevice;
        if (!upnpStarted || destroyed || device == null) return;
        try {
            SendingNotificationAlive protocol = new SendingNotificationAlive(upnpService, device) {
                @Override
                protected int getBulkRepeat() {
                    return 1;
                }
            };
            upnpService.getConfiguration().getAsyncProtocolExecutor().execute(protocol);
        } catch (RuntimeException e) {
            android.util.Log.w("DlnaRenderer", "compatibility alive failed", e);
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
        // Cache the rate here (main thread): the AVTransport action methods run on jUPnP threads
        // and must never touch the player themselves.
        avTransportImpl.updateSpeedCache(player.getSpeed());
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
            if (avTransportImpl != null) {
                avTransportImpl.updateSpeedCache(player.getSpeed());
                if (player.isPlaying()) avTransportImpl.updatePositionCache(player.getPosition(), getDuration());
            }
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
