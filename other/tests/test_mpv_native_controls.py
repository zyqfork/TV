#!/usr/bin/env python3
"""Production control sequencing/property cache; Android/native wiring checks are labelled static."""
import pathlib, subprocess, tempfile, textwrap
ROOT=pathlib.Path(__file__).resolve().parents[2]
PROBE=r'''
import is.xyz.mpv.*;
import java.util.*;
public class Probe {
 static void check(boolean ok,String msg){if(!ok)throw new AssertionError(msg);}
 public static void main(String[] args){
  List<String> sent=new ArrayList<>();List<Long> ids=new ArrayList<>();
  MpvNativeControls q=new MpvNativeControls(new MpvNativeControls.Sender(){
   public int property(long id,String name,String value){ids.add(id);sent.add(name+"="+value);return 0;}
   public int command(long id,String[] values){ids.add(id);sent.add(String.join(" ",values));return 0;}
  });
  long start=System.nanoTime();q.property("pause","yes");q.property("vo","null");q.property("vo","gpu");
  String[] load={"loadfile","owned.ts","replace"};q.command(load);load[1]="changed";
  check((System.nanoTime()-start)/1000000<100,"controls waited for core");
  check(sent.equals(List.of("pause=yes")),"async operations reordered before reply");
  q.reply(17);check(sent.size()==1,"unrelated subtitle reply consumed barrier");
  for(int i=0;i<3;i++)q.reply(ids.get(i));
  check(sent.equals(List.of("pause=yes","vo=null","vo=gpu","loadfile owned.ts replace")),"wrong order/argument mutation");
  check(ids.stream().allMatch(id->id>(1L<<48)),"subtitle IDs collide");
  q.property("pause","no");q.close();q.reply(ids.get(3));check(sent.size()==4,"closed context dispatched late work");
  check(q.property("pause","yes")==-3,"closed context accepted work");
  MpvNativeControls bounded=new MpvNativeControls(new MpvNativeControls.Sender(){
   public int property(long id,String n,String v){return 0;}
   public int command(long id,String[] a){return 0;}
  });
  for(int i=0;i<1025;i++)check(bounded.property("pause","yes")==0,"premature queue limit");
  check(bounded.property("pause","no")==-1,"unbounded requests while core stalled");
  List<String> observations=new ArrayList<>();MpvPropertyCache cache=new MpvPropertyCache((n,f)->observations.add(n+":"+f));
  check(cache.get("track-list/0/type",1)==null,"invented initial metadata");cache.get("track-list/0/type",1);
  check(observations.size()==1,"repeated observation leaked");cache.put("track-list/0/type","video");
  check(cache.get("track-list/0/type",1).equals("video"),"event metadata missing");cache.put("vo","gpu");cache.put("time-pos",90.0);cache.clearMedia();
  check(cache.get("track-list/0/type",1)==null&&cache.get("time-pos",5)==null,"stale preceding media");
  check(cache.get("vo",1).equals("gpu"),"media reset erased construction options");
  cache.put("track-list/0/type","audio");check(cache.get("track-list/0/type",1).equals("audio"),"observation could not repopulate");
  System.out.println("PASS production MPV async ordering, reply ownership, argument copy, UI latency, close, bounded queue, event-fed cache/media reset");
 }
}
'''
with tempfile.TemporaryDirectory() as d:
 p=pathlib.Path(d);(p/'Probe.java').write_text(PROBE)
 src=ROOT/'media3compat/src/main/java/is/xyz/mpv'
 subprocess.run(['javac','-d',d,str(src/'MpvNativeControls.java'),str(src/'MpvPropertyCache.java'),str(p/'Probe.java')],check=True)
 subprocess.run(['java','-cp',d,'Probe'],check=True)
player=(ROOT/'media3compat/src/main/java/androidx/media3/mpvplayer/MpvPlayer.java').read_text(encoding='utf-8')
assert 'MPVLib.setPropertyBoolean(' not in player and 'MPVLib.setPropertyString(' not in player
assert 'MPVLib.command(' not in player and 'MPVLib.getProperty' not in player
assert 'metadataChanged(property)' in player and 'if (playbackRestartSeen) reportFirstFrame()' in player
assert 'apply-profile", "fast' not in player
config=(ROOT/'app/src/main/java/com/fongmi/android/tv/player/mpv/MpvUtil.java').read_text(encoding='utf-8')
assert 'analyzeduration=1000000' not in config and 'analyzeduration=2500000' not in config
installer=(ROOT/'app/src/main/java/com/fongmi/android/tv/ui/activity/UpdateInstallActivity.java').read_text(encoding='utf-8')
assert 'canRequestPackageInstalls()' in installer and 'ACTION_MANAGE_UNKNOWN_APP_SOURCES' in installer
assert 'ACTION_INSTALL_PACKAGE' in installer and 'setComponent(' in installer and 'setClipData(' in installer
assert 'getPackageArchiveInfo' in installer and 'getPackageName().equals(info.packageName)' in installer
assert 'killProcess' not in installer and 'System.exit' not in installer
print('PASS STATIC wiring: async controls/cached reads, late metadata/frame signal, native probe defaults, explicit installer/permission/private APK validation; not real Android/native/sender evidence')
