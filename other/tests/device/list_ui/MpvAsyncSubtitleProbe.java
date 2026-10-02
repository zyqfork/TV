import android.content.Context;
import android.content.ContextWrapper;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.mpvplayer.MpvPlayer;
import androidx.media3.mpvplayer.MpvPlayerConfig;
import is.xyz.mpv.MPVLib;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Shell-owned process running the real candidate MpvPlayer. No user preferences/history touched.
 * Uses a PRIVATE ImageReader and local delayed HTTP server; not proof of on-screen HDR or physical keys.
 * Args: bare-video-file (no embedded subtitles), owned-cache-directory.
 */
public final class MpvAsyncSubtitleProbe {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Map<String,Integer> received = new ConcurrentHashMap<>();
    private final Map<String,Integer> served = new ConcurrentHashMap<>();
    private final AtomicInteger frames = new AtomicInteger();
    private ServerSocket server;
    private Context context;
    private ImageReader reader;
    private MpvPlayer player;
    private String video;
    private long lastTick, maxGap;
    private int ticks;
    private boolean finished;
    private boolean baseline;

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 3) throw new IllegalArgumentException("bare-video owned-cache-dir [baseline]");
        Looper.prepareMainLooper();
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object thread = at.getMethod("systemMain").invoke(null);
        Context base = (Context)at.getMethod("getSystemContext").invoke(thread);
        final File cache = new File(args[1]); cache.mkdirs();
        MpvAsyncSubtitleProbe test = new MpvAsyncSubtitleProbe();
        test.context = new ContextWrapper(base) {
            @Override public Context getApplicationContext() { return this; }
            @Override public String getPackageName() { return "com.android.shell"; }
            @Override public String getOpPackageName() { return "com.android.shell"; }
            @Override public File getCacheDir() { return cache; }
            @Override public File getFilesDir() { return cache; }
        };
        test.video = args[0];
        test.baseline = args.length == 3 && args[2].equals("baseline");        test.run();
        Looper.loop();
    }

    private void run() throws Exception {
        server = new ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"));
        Thread accept = new Thread(() -> {
            try {
                while (!server.isClosed()) {
                    final Socket socket = server.accept();
                    Thread request = new Thread(() -> respond(socket));
                    request.setDaemon(true); request.start();
                }
            } catch (IOException ignored) {}
        });
        accept.setDaemon(true); accept.start();
        reader = ImageReader.newInstance(1920, 1080, 34, 4);
        reader.setOnImageAvailableListener(r -> {
            Image image = r.acquireLatestImage();
            if (image != null) { frames.incrementAndGet(); image.close(); }
        }, ui);
        createPlayer();
        lastTick = SystemClock.elapsedRealtime();
        tick();
        ui.postDelayed(() -> fail(new AssertionError("probe deadline")), 75000);
        start(Arrays.asList("auto-slow.srt", "auto.ass", "bad.srt", "auto-fast.srt", "auto-last.srt"));
        waitFor("auto request", () -> got("auto-slow.srt"), 15000, () -> {
            if (baseline) {
                ui.postDelayed(() -> safe(() -> {
                    check(maxGap >= 5000, "old build did not reproduce UI block");
                    System.out.println("PASS negative control: old production player blocks main looper maxGapMs=" + maxGap);
                    finished = true; player.release(); reader.close();
                    try { server.close(); } catch(IOException ignored) {}
                    System.exit(0);
                }), 200);
                return;
            }
            timed("pause during HTTP", () -> player.pause());
            check(!player.getPlayWhenReady(), "pause ignored");
            ui.postDelayed(() -> safe(() -> {
                timed("resume during HTTP", () -> player.play());
                check(player.getPlayWhenReady(), "resume ignored");
                timed("seek during HTTP", () -> player.seekTo(2200));
                waitFor("four successful auto imports", () -> externalCount() == 4, 14000, () -> {
                    check(player.getVideoSize().width >= 3840, "4K fixture required");
                    check(player.getPlayerError() == null, "optional subtitle failure became playback error");
                    check("mediacodec_embed".equals(MPVLib.getPropertyString("current-vo")), "auto imports changed VO");
                    check(got("auto-last.srt"), "queue stopped on HTTP 500");
                    System.out.println("PASS actual MpvPlayer five auto URLs / delayed HTTP / ASS+SRT / HTTP failure continues / embed");
                    closeCase();
                });
            }), 600);
        });
    }

    private void createPlayer() {
        MpvPlayerConfig config = new MpvPlayerConfig.Builder()
                .addPreInitStringOption("ao", "null")
                .addPreInitStringOption("audio", "no")
                .addPreInitStringOption("network-timeout", "20").build();
        player = new MpvPlayer.Builder(context).setDecode(2).setConfig(config).build();
        player.setVideoSurface(reader.getSurface());
    }

    private String url(String name) { return "http://127.0.0.1:" + server.getLocalPort() + "/" + name; }
    private MediaItem.SubtitleConfiguration sub(String name) {
        return new MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(url(name)))
                .setMimeType(name.endsWith(".ass") ? "text/x-ssa" : "application/x-subrip").build();
    }
    private void start(List<String> names) {
        List<MediaItem.SubtitleConfiguration> subs = new ArrayList<>();
        for (String name : names) subs.add(sub(name));
        player.setMediaItem(new MediaItem.Builder().setUri(video).setSubtitleConfigurations(subs).build());
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build());
        player.prepare(); player.play();
    }

    private void closeCase() {
        start(Collections.emptyList());
        waitFor("bare file", () -> player.getPlaybackState() == Player.STATE_READY, 6000, () -> {
            timed("manual subtitle submission", () -> player.addSubtitle(sub("close-slow.srt")));
            waitFor("manual HTTP started", () -> got("close-slow.srt"), 4000, () -> {
                timed("TEXT disable during manual HTTP", () -> player.setTrackSelectionParameters(
                        player.getTrackSelectionParameters().buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()));
                start(Arrays.asList("switch-slow.srt"));
                waitFor("old file HTTP", () -> got("switch-slow.srt"), 4000, () -> {
                    timed("file change during HTTP", () -> start(Arrays.asList("new-fast.srt")));
                    waitFor("new file import", () -> hasExternal("new-fast.srt"), 6000, () -> {
                        waitFor("old HTTP responses", () -> done("switch-slow.srt") && done("close-slow.srt"), 12000, () -> {
                            check(externalCount() == 1 && !hasExternal("switch-slow.srt") && !hasExternal("close-slow.srt"), "late cancelled imports polluted new file");
                            System.out.println("PASS actual manual async / TEXT close cancellation / file change / delayed old replies ignored");
                            stopCase();
                        });
                    });
                });
            });
        });
    }

    private void stopCase() {
        start(Arrays.asList("stop-slow.srt"));
        waitFor("stop HTTP", () -> got("stop-slow.srt"), 6000, () -> {
            timed("stop during HTTP", () -> player.stop());
            check(player.getPlaybackState() == Player.STATE_IDLE, "stop not idle");
            start(Collections.emptyList());
            waitFor("stopped old HTTP response", () -> done("stop-slow.srt"), 12000, () -> {
                check(externalCount() == 0, "stop's old import published after replay");
                System.out.println("PASS actual stop cancels HTTP / no late subtitle after replay");
                releaseCase();
            });
        });
    }

    private void releaseCase() {
        start(Arrays.asList("release-slow.srt"));
        waitFor("release HTTP", () -> got("release-slow.srt"), 6000, () -> {
            timed("release during HTTP", () -> player.release());
            timed("replacement player ownership", () -> createPlayer());
            start(Arrays.asList("owner-fast.srt"));
            waitFor("new owner import", () -> hasExternal("owner-fast.srt"), 6000, () -> {
                waitFor("released old HTTP response", () -> done("release-slow.srt"), 12000, () -> {
                    check(externalCount() == 1 && !hasExternal("release-slow.srt"), "old owner reply contaminated replacement");
                    check(maxGap < 1500 && ticks > 100 && frames.get() > 50, "UI heartbeat/decoded frames missing");
                    System.out.println("PASS actual release cancellation / replacement singleton / no late native import");
                    System.out.println("PASS main-looper ticks=" + ticks + " maxGapMs=" + maxGap + " decoded PRIVATE frames=" + frames.get());
                    finished = true; player.release(); reader.close();
                    try { server.close(); } catch (IOException ignored) {}
                    System.exit(0);
                });
            });
        });
    }

    private int externalCount() {
        int found=0; Integer count=MPVLib.getPropertyInt("track-list/count");
        for(int i=0;count!=null && i<count;i++) if(MPVLib.getPropertyString("track-list/"+i+"/external-filename") != null) found++;
        return found;
    }
    private boolean hasExternal(String name) {
        Integer count=MPVLib.getPropertyInt("track-list/count");
        for(int i=0;count!=null && i<count;i++) if(url(name).equals(MPVLib.getPropertyString("track-list/"+i+"/external-filename"))) return true;
        return false;
    }
    private boolean got(String path) { return received.containsKey(path); }
    private boolean done(String path) { return served.containsKey(path); }
    private void waitFor(String name, BooleanSupplier condition, long timeout, Runnable next) {
        final long deadline=SystemClock.elapsedRealtime()+timeout;
        class Poll implements Runnable {
            public void run() { safe(() -> {
                if(condition.getAsBoolean()) { next.run(); return; }
                check(SystemClock.elapsedRealtime()<deadline, "timeout: "+name);
                ui.postDelayed(this,100);
            }); }
        }
        new Poll().run();
    }
    private void timed(String name,Runnable action) {
        long before=SystemClock.elapsedRealtime(); action.run(); long duration=SystemClock.elapsedRealtime()-before;
        System.out.println(name+" ms="+duration); check(duration<1500,name+" blocked UI");
    }
    private void tick() {
        if(finished) return;
        long now=SystemClock.elapsedRealtime(); maxGap=Math.max(maxGap,now-lastTick);lastTick=now;ticks++;
        ui.postDelayed(this::tick,100);
    }
    private void safe(Runnable run) { if(!finished) try { run.run(); } catch(Throwable e) { fail(e); } }
    private static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }
    private void fail(Throwable e) {
        if(finished) return; finished=true;e.printStackTrace();
        try { if(player!=null) player.release(); } catch(Throwable ignored) {}
        try { if(reader!=null) reader.close(); if(server!=null) server.close(); } catch(Throwable ignored) {}
        System.exit(1);
    }
    private void respond(Socket socket) {
        String path="unknown";
        try (Socket s=socket) {
            s.setSoTimeout(3000);
            BufferedReader in=new BufferedReader(new InputStreamReader(s.getInputStream(),StandardCharsets.UTF_8));
            String line=in.readLine(); if(line==null) return;
            path=line.split(" ")[1].substring(1);
            while((line=in.readLine())!=null && !line.isEmpty()) {}
            received.merge(path,1,Integer::sum);
            System.out.println("HTTP received "+path);
            if(path.contains("slow")) Thread.sleep(8000);
            boolean ass=path.endsWith(".ass");
            String body=ass ? "[Script Info]\nScriptType: v4.00+\nPlayResX: 1280\nPlayResY: 720\n[V4+ Styles]\nFormat: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\nStyle: Default,sans-serif,36,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,1,0,2,10,10,20,1\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\nDialogue: 0,0:00:00.00,0:02:00.00,Default,,0,0,0,,ASYNC ASS\n" : "1\n00:00:00,000 --> 00:02:00,000\nASYNC "+path+"\n\n";
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
            String status=path.equals("bad.srt") ? "500 Internal Server Error" : "200 OK";
            OutputStream out=s.getOutputStream();
            out.write(("HTTP/1.1 "+status+"\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(bytes);out.flush();
        } catch(Exception ignored) {
        } finally { served.merge(path,1,Integer::sum); }
    }
}
