#!/usr/bin/env python3
"""Compile real settings/parser code and check migration, reset and custom-config isolation."""
from pathlib import Path
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
exo = (ROOT/'app/src/main/java/com/fongmi/android/tv/player/exo/ExoUtil.java').read_text(encoding='utf8')
audio_method = re.search(r'    public static boolean isAudioPassThroughEnabled\(\) \{.*?\n    }', exo, re.S).group()
stubs = {
 'android/content/Context.java': 'package android.content; public class Context {}',
 'android/content/Intent.java': 'package android.content; public class Intent {public Intent(String s){} public Object resolveActivity(Object o){return o;}}',
 'android/provider/Settings.java': 'package android.provider; public class Settings {public static String ACTION_CAPTIONING_SETTINGS="caption";}',
 'android/net/Uri.java': 'package android.net; public class Uri {}',
 'com/fongmi/android/tv/App.java': 'package com.fongmi.android.tv; public class App {public static App get(){return new App();}public Object getPackageManager(){return new Object();}}',
 'com/fongmi/android/tv/setting/AudioSetting.java': 'package com.fongmi.android.tv.setting; public class AudioSetting {public static boolean effect;public static boolean hasEffect(int n){return effect;}}',
 'com/github/catvod/utils/Prefers.java': '''package com.github.catvod.utils;
import java.util.*;
public class Prefers {
 public static Map<String,Object> data=new HashMap<>();
 public static int getInt(String k){return getInt(k,0);} public static int getInt(String k,int d){return data.get(k) instanceof Integer i?i:d;}
 public static float getFloat(String k){return getFloat(k,0);} public static float getFloat(String k,float d){return data.get(k) instanceof Float f?f:d;}
 public static boolean getBoolean(String k){return getBoolean(k,false);} public static boolean getBoolean(String k,boolean d){return data.get(k) instanceof Boolean b?b:d;}
 public static void put(String k,Object v){data.put(k,v);}
}''',
 'com/github/catvod/utils/Path.java': '''package com.github.catvod.utils;
import java.io.*;import java.nio.file.Files;
public class Path {
 public static File mpv(String n){return new File(System.getProperty("probe.dir"),n);}
 public static File font(){return mpv("fonts");}
 public static String read(File f){try{return Files.readString(f.toPath());}catch(IOException e){return null;}}
 public static void write(File f,byte[] b){throw new AssertionError("unexpected write");}
}''',
 'com/fongmi/android/tv/utils/FileUtil.java': '''package com.fongmi.android.tv.utils;
import android.content.Context;import android.net.Uri;import java.io.*;
public class FileUtil {public static void writeAtomically(byte[] b,File f)throws IOException{throw new IOException();}public static void copyAtomically(Uri u,File f)throws IOException{throw new IOException();}}''',
 'com/fongmi/android/tv/player/mpv/MpvUtil.java': '''package com.fongmi.android.tv.player.mpv;
import java.util.*;public class MpvUtil {public static List<String> getManagedOptionNames(){return List.of("gpu-api","gpu-context","opengl-es","slang","sub-font");}}''',
 'SettingsProbe.java': '''import com.fongmi.android.tv.setting.*;
import com.fongmi.android.tv.player.mpv.MpvConfigFiles;
import com.github.catvod.utils.Prefers;
import java.nio.file.*;
public class SettingsProbe {
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 AUDIO_METHOD
 public static void main(String[] args)throws Exception {
  check(PlayerSetting.getEngine()==0,"new engine default");
  check(PlayerSetting.getDecode(false,0)==1,"new hard preference");
  check(PlayerSetting.getBuffer()==5 && !PreloadSetting.isPreload(),"conservative defaults");
  check(!MpvConfigFiles.isEnabled() && MpvConfigFiles.readGlobalOptions().isEmpty(),"new config off");
  for(String k:new String[]{"tunnel","audio_prefer","prefer_aac","mpv_gpu_next"})Prefers.put(k,true);
  Prefers.put("render",1);Prefers.put("exo_http",0);
  check(PlayerSetting.getDecode(false,0)==1,"retired keys affected decoder");
  Prefers.data.clear();Prefers.put("video_prefer",true);
  check(PlayerSetting.getDecode(false,0)==0 && PlayerSetting.getDecode(true,0)==0,"legacy explicit software migration");
  PlayerSetting.putDecode(false,0,1);
  check(PlayerSetting.getDecode(false,0)==1,"retired video_prefer undid later user choice");
  Prefers.data.clear();Prefers.put("decode_defaults_migrated_v3",true);
  for(String scene:new String[]{"live","vod"}){Prefers.put(scene+"_exo_decode",0);Prefers.put(scene+"_mpv_decode",1);}
  check(PlayerSetting.getDecode(false,0)==1,"legacy accidental soft footprint not repaired");
  Prefers.data.clear();Prefers.put("vod_exo_decode",0);
  check(PlayerSetting.getDecode(false,0)==0 && PlayerSetting.getDecode(true,0)==1,"isolated scene choice overwritten");
  String config="\\uFEFF# legacy file\\n[cinema]\\ntone-mapping=hable\\n[default]\\nvo=gpu-next\\n";
  Files.writeString(MpvConfigFiles.file().toPath(),config);Prefers.data.clear();
  check(MpvConfigFiles.isEnabled(),"existing expert file not preserved");
  check("gpu-next".equals(MpvConfigFiles.readGlobalOptions().get("vo")),"default profile parsing");
  MpvConfigFiles.setEnabled(false);
  check(MpvConfigFiles.readGlobalOptions().isEmpty(),"disabled Java config leaked");
  check(config.equals(Files.readString(MpvConfigFiles.file().toPath())),"disable destroyed expert file");
  for(boolean auto:new boolean[]{true,false})for(boolean effect:new boolean[]{true,false}){
   PlayerSetting.putAudioPassThrough(auto);AudioSetting.effect=effect;
   check(isAudioPassThroughEnabled()==(auto&&!effect),"effective audio policy");
   check(PlayerSetting.isAudioPassThrough()==auto,"effect mutated output preference");
  }
  Prefers.put("subscription_secret","keep");Prefers.put("subtitle_font","keep.ttf");
  Prefers.put("video_prefer",true);Prefers.put("playback_decode_migrated_v5",false);
  PlayerSetting.putEngine(1);PlayerSetting.putBuffer(10);PreloadSetting.putPreload(true);MpvConfigFiles.setEnabled(true);
  PlayerSetting.restorePlaybackDefaults();
  check(PlayerSetting.getEngine()==0 && PlayerSetting.getBuffer()==5,"reset defaults");
  for(int engine:new int[]{0,1})check(PlayerSetting.getDecode(false,engine)==1 && PlayerSetting.getDecode(true,engine)==1,"reset migration ordering");
  check(!MpvConfigFiles.isEnabled() && !PreloadSetting.isPreload(),"reset expert/preload");
  check("keep".equals(Prefers.data.get("subscription_secret")) && "keep.ttf".equals(Prefers.data.get("subtitle_font")),"reset erased unrelated data");
  check(config.equals(Files.readString(MpvConfigFiles.file().toPath())),"reset deleted config");
  System.out.println("PASS real settings: defaults, legacy migration, bounded buffer, expert gate, audio effects and non-destructive reset");
 }
}'''.replace('AUDIO_METHOD', audio_method),
}
with tempfile.TemporaryDirectory() as tmp:
    out=Path(tmp);files=[]
    for name,content in stubs.items():
        p=out/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content,encoding='utf8');files.append(str(p))
    real=[ROOT/'app/src/main/java/com/fongmi/android/tv'/p for p in ['setting/PlayerSetting.java','setting/PreloadSetting.java','player/mpv/MpvConfigFiles.java']]
    subprocess.run(['javac','--release','21','-d',str(out),*map(str,real),*files],check=True)
    subprocess.run(['java','-Dprobe.dir='+str(out),'-cp',str(out),'SettingsProbe'],check=True)

util=(ROOT/'app/src/main/java/com/fongmi/android/tv/player/mpv/MpvUtil.java').read_text(encoding='utf8')
assert '"config", MpvConfigFiles.isEnabled() ? "yes" : "no"' in util
assert '"load-scripts", MpvConfigFiles.isEnabled() ? "yes" : "no"' in util
assert '!MpvConfigFiles.isEnabled() && MpvAutomaticOutputPolicy.direct(' in util
assert 'addPreloadOptions' not in util and 'cache-on-disk' not in util
assert 'setPrioritizeTimeOverSizeThresholds(false)' in exo
for retired in ['isVideoPrefer','isAudioPrefer','isPreferAAC','isTunnelingEnabled']:
    assert retired not in exo
for f in ['app/src/leanback/res/layout/activity_setting_player.xml','app/src/mobile/res/layout/fragment_setting_player.xml']:
    text=(ROOT/f).read_text(encoding='utf8')
    for key in ['render','http','mpvGpuNext','mpvConf','preload','buffer']:
        assert '@+id/'+key+'"' not in text,(f,key)
print('PASS wiring: native config/scripts disabled, GPU-only custom path, retired controls absent and bounded Exo loading')
