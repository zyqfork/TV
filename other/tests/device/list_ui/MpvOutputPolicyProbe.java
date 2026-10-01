import java.lang.reflect.*;

/** Shell-only selector regression against the installed APK. Does not open a native player. */
public final class MpvOutputPolicyProbe {
    static Object player;
    static Class<?> type;
    static void set(String field, Object value) throws Exception {
        Field f=type.getDeclaredField(field);f.setAccessible(true);f.set(player,value);
    }
    static Object call(String name) throws Exception {
        Method m=type.getDeclaredMethod(name);m.setAccessible(true);return m.invoke(player);
    }
    static void check(boolean pass,String why){if(!pass)throw new AssertionError(why);}
    public static void main(String[] args)throws Exception{
        type=Class.forName("androidx.media3.mpvplayer.MpvPlayer");
        Class<?> unsafe=Class.forName("sun.misc.Unsafe");Field f=unsafe.getDeclaredField("theUnsafe");f.setAccessible(true);
        player=unsafe.getMethod("allocateInstance",Class.class).invoke(f.get(null),type);
        Class<?> builder=Class.forName("androidx.media3.mpvplayer.MpvPlayerConfig$Builder");Object b=builder.getConstructor().newInstance();
        Method option=builder.getMethod("addPreInitStringOption",String.class,String.class);
        option.invoke(b,"vo","mediacodec_embed");option.invoke(b,"hwdec","mediacodec-copy");set("config",builder.getMethod("build").invoke(b));
        set("decode",2);check(call("getVo").equals("mediacodec_embed"),"plain performance must keep embed");check(call("getDecodeOption").equals("mediacodec"),"performance must not copy back");
        set("subtitleGpuRequired",true);check(call("getVo").equals("gpu"),"subtitle must have composition");check(call("getDecodeOption").equals("mediacodec"),"subtitle must retain direct hardware decode");check(call("getDecode").equals(2),"subtitle changed requested decode mode");check(call("isEmbedVo").equals(false),"GPU path incorrectly takes embed surface recovery");
        set("subtitleGpuRequired",false);set("embedVoDisabled",true);check(call("getVo").equals("gpu"),"surface fallback broken");
        set("decode",0);check(call("getDecodeOption").equals("no"),"soft decode changed");
        set("decode",1);check(call("getDecodeOption").equals("mediacodec-copy"),"compatible user config no longer respected");
        set("decode",2);set("released",true);set("fileLoaded",true);call("checkSelectedSubtitles");
        System.out.println("PASS installed MPV output policy (late subtitle callback ignored after release): embed / GPU subtitles / direct hwdec / unchanged mode / surface fallback / soft / user config");
    }
}
