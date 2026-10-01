import android.content.Context;
import android.graphics.Bitmap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.Looper;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import is.xyz.mpv.MPVLib;

/** Separate shell process; never installs/restarts the user's player or changes its prefs.
 * Run against candidate JNI libraries on a spare device, not the currently playing TV.
 */
public final class MpvNativeSubtitleProbe implements MPVLib.EventObserver, MPVLib.LogObserver {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicInteger shown = new AtomicInteger();
    private long revision = -1;
    private boolean cleared;
    private String imagePath;
    private String expectedCodec;
    private ImageReader reader;
    private boolean destroyed;
    private boolean pausedCleared;

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("video-path bitmap-output-path expected-codec");
        Looper.prepareMainLooper();
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object thread = at.getMethod("systemMain").invoke(null);
        Context context = (Context) at.getMethod("getSystemContext").invoke(thread);
        MpvNativeSubtitleProbe p = new MpvNativeSubtitleProbe();
        p.imagePath = args[1]; p.expectedCodec = args[2];
        if (!MPVLib.acquireInstance() || !MPVLib.hasSubtitleOverlay()) throw new AssertionError("Native ABI missing");
        MPVLib.addObserver(p); MPVLib.addLogObserver(p); MPVLib.create(context);
        MPVLib.setOptionString("vo", "mediacodec_embed");
        MPVLib.setOptionString("hwdec", "mediacodec");
        MPVLib.setOptionString("ao", "null"); MPVLib.setOptionString("audio", "no");
        MPVLib.setOptionString("sub-ass-override", "no");
        MPVLib.init();
        // PRIVATE buffers exercise actual hardware direct output without displaying over a user app.
        p.reader = ImageReader.newInstance(1280, 720, 34, 4);
        p.reader.setOnImageAvailableListener(r -> { Image image = r.acquireLatestImage(); if (image != null) image.close(); }, p.handler);
        MPVLib.attachSurface(p.reader.getSurface());
        MPVLib.nativeConfigureSubtitleOverlay(1920, 1080, true, 42);
        MPVLib.command(new String[]{"loadfile", args[0], "replace"});
        p.handler.postDelayed(() -> {
            try {
                if (p.shown.get() == 0) throw new AssertionError("No native subtitle bitmap");
                MPVLib.setPropertyBoolean("pause", true);
                MPVLib.setPropertyDouble("sub-delay", 300);
                p.handler.postDelayed(() -> {
                    p.pausedCleared = p.cleared;
                    MPVLib.setPropertyDouble("sub-delay", 0);
                    MPVLib.setPropertyBoolean("pause", false);
                }, 1000);
            } catch (Throwable e) { p.fail(e); }
        }, 3000);
        p.handler.postDelayed(() -> {
            try {
                String vo = MPVLib.getPropertyString("current-vo");
                if (!"mediacodec_embed".equals(vo)) throw new AssertionError("Unexpected VO="+vo);
                if (p.shown.get()<2 || !p.pausedCleared) throw new AssertionError("Missing delay/pause/reappearance evidence: "+p.shown+" "+p.pausedCleared);
                String codec=null;
                Integer count=MPVLib.getPropertyInt("track-list/count");
                for(int i=0;count!=null && i<count;i++) {
                    String prefix="track-list/"+i+"/";
                    if("sub".equals(MPVLib.getPropertyString(prefix+"type"))
                            && Boolean.TRUE.equals(MPVLib.getPropertyBoolean(prefix+"selected")))
                        codec=MPVLib.getPropertyString(prefix+"codec");
                }
                if(!p.expectedCodec.equals(codec)) throw new AssertionError("Wrong selected native codec="+codec);
                MPVLib.nativeConfigureSubtitleOverlay(0,0,false,43);
                MPVLib.SubtitleOverlayFrame cleared=MPVLib.nativeReadSubtitleOverlay(-1);
                if(cleared==null || cleared.epoch!=43 || cleared.bitmap!=null) throw new AssertionError("Disable/epoch clear failed");
                System.out.println("PASS native "+p.expectedCodec+" bitmap bridge / actual embed / paused delay clear / reset / epoch / alpha");
                p.close(); System.exit(0);
            } catch (Throwable e) { p.fail(e); }
        }, 11000);
        Looper.loop();
    }
    private void fail(Throwable e) { e.printStackTrace(); close(); System.exit(1); }
    private void close() {
        if (destroyed) return; destroyed=true;
        MPVLib.removeObserver(this); MPVLib.removeLogObserver(this);
        MPVLib.nativeConfigureSubtitleOverlay(0,0,false,43);
        MPVLib.destroy(); MPVLib.releaseInstance(); reader.close();
    }
    @Override public void eventSubtitleOverlay() { handler.post(this::read); }
    private void read() {
        if (destroyed) return;
        try {
            MPVLib.SubtitleOverlayFrame f=MPVLib.nativeReadSubtitleOverlay(revision);
            if (f==null || f.epoch!=42) return;
            revision=f.revision;
            if (f.error!=0) throw new AssertionError("Native render error="+f.error);
            if (f.bitmap==null) { if(shown.get()>0) cleared=true; return; }
            if(f.canvasWidth!=1920 || f.canvasHeight!=1080 || !f.bitmap.hasAlpha()) throw new AssertionError("geometry/alpha");
            int opaque=0,transparent=0;
            for(int y=0;y<f.bitmap.getHeight();y++) for(int x=0;x<f.bitmap.getWidth();x++) {
                int alpha=f.bitmap.getPixel(x,y)>>>24;
                if(alpha>0) opaque++; else transparent++;
            }
            if(opaque==0) throw new AssertionError("Empty pixels");
            if(shown.incrementAndGet()==1) try(FileOutputStream out=new FileOutputStream(imagePath)) { f.bitmap.compress(Bitmap.CompressFormat.PNG,100,out); }
            System.out.println("bitmap="+f.bitmap.getWidth()+"x"+f.bitmap.getHeight()+" xy="+f.left+","+f.top+" epoch="+f.epoch+" revision="+revision+" pts="+f.pts+" opaque="+opaque+" transparent="+transparent);
        } catch(Throwable e) { fail(e); }
    }
    @Override public void logMessage(String prefix,int level,String text) {
        if(text.contains("VO:") || text.contains("hardware decoding") || text.contains("Subs ")) System.out.print(prefix+": "+text);
    }
    @Override public void event(int id) {}
    @Override public void eventProperty(String s) {}
    @Override public void eventProperty(String s,long v) {}
    @Override public void eventProperty(String s,double v) {}
    @Override public void eventProperty(String s,boolean v) {}
    @Override public void eventProperty(String s,String v) {}
}
