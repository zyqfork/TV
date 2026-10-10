"""Production first-frame clock, font metadata/face names and PiP PendingIntent routing.
Android framework is doubled; this does NOT prove vendor rendering or AirPlay sender behaviour.
"""
from pathlib import Path
from tempfile import TemporaryDirectory
import struct,subprocess,argparse
ROOT=Path(__file__).resolve().parents[2]
STUBS={
'androidx/annotation/Nullable.java':'package androidx.annotation;public @interface Nullable{}',
'androidx/annotation/DrawableRes.java':'package androidx.annotation;public @interface DrawableRes{}',
'androidx/annotation/StringRes.java':'package androidx.annotation;public @interface StringRes{}',
'android/annotation/TargetApi.java':'package android.annotation;public @interface TargetApi{int value();}',
'android/os/Build.java':'package android.os;public class Build{public static class VERSION{public static int SDK_INT=35;}public static class VERSION_CODES{public static final int O=26,S=31;}}',
'android/graphics/Typeface.java':'package android.graphics;public class Typeface{public static final Typeface DEFAULT=new Typeface();public int index;public static Typeface createFromFile(java.io.File f){return DEFAULT;}public static class Builder{int index;public Builder(java.io.File f){}public Builder setTtcIndex(int i){index=i;return this;}public Typeface build(){var t=new Typeface();t.index=index;return t;}}}',
'android/text/TextUtils.java':'package android.text;public class TextUtils{public static boolean isEmpty(CharSequence s){return s==null||s.length()==0;}}',
'android/content/Context.java':'package android.content;public class Context{public android.content.pm.PackageManager getPackageManager(){return new android.content.pm.PackageManager();}public android.content.ContentResolver getContentResolver(){return new android.content.ContentResolver();}}',
'android/content/ContentResolver.java':'package android.content;public class ContentResolver{public java.io.InputStream openInputStream(android.net.Uri uri){return null;}}',
'android/content/pm/PackageManager.java':'package android.content.pm;public class PackageManager{public static final String FEATURE_PICTURE_IN_PICTURE="pip";public boolean hasSystemFeature(String s){return true;}}',
'android/net/Uri.java':'package android.net;public class Uri{}',
'com/fongmi/android/tv/App.java':'package com.fongmi.android.tv;public class App{public static android.content.Context get(){return new android.content.Context();}}',
'com/fongmi/android/tv/utils/FileUtil.java':'package com.fongmi.android.tv.utils;public class FileUtil{public static String getDisplayName(android.net.Uri u){return "font.ttf";}}',
'com/github/catvod/utils/Crypto.java':'package com.github.catvod.utils;public class Crypto{public static String md5(String s){return "owned";}}',
'com/github/catvod/utils/Path.java':'package com.github.catvod.utils;public class Path{public static java.io.File font(){return new java.io.File(System.getProperty("font-dir"));}}',
'android/content/Intent.java':'package android.content;public class Intent{public String action,pkg;public Intent(String a){action=a;}public Intent setPackage(String p){pkg=p;return this;}}',
'android/app/PendingIntent.java':'package android.app;public class PendingIntent{public static final int FLAG_IMMUTABLE=1,FLAG_UPDATE_CURRENT=2;public String owner;public android.content.Intent intent;public int flags;public static PendingIntent getBroadcast(Activity a,int request,android.content.Intent i,int flags){var p=new PendingIntent();p.owner="broadcast";p.intent=i;p.flags=flags;return p;}}',
'android/app/Activity.java':'package android.app;public class Activity{public PictureInPictureParams params;public String getString(int n){return "title_"+n;}public String getPackageName(){return "owned.app";}public boolean isInPictureInPictureMode(){return false;}public void setPictureInPictureParams(PictureInPictureParams p){params=p;}public void enterPictureInPictureMode(PictureInPictureParams p){params=p;}}',
'android/app/PictureInPictureParams.java':'package android.app;public class PictureInPictureParams{public java.util.List<RemoteAction> actions=java.util.List.of();public static class Builder{java.util.List<RemoteAction> actions=java.util.List.of();public Builder setActions(java.util.List<RemoteAction> a){actions=java.util.List.copyOf(a);return this;}public Builder setSourceRectHint(android.graphics.Rect r){return this;}public Builder setSeamlessResizeEnabled(boolean b){return this;}public Builder setAspectRatio(android.util.Rational r){return this;}public PictureInPictureParams build(){var p=new PictureInPictureParams();p.actions=actions;return p;}}}',
'android/app/RemoteAction.java':'package android.app;public class RemoteAction{public PendingIntent pending;public RemoteAction(android.graphics.drawable.Icon i,String title,String description,PendingIntent p){pending=p;}}',
'android/graphics/drawable/Icon.java':'package android.graphics.drawable;public class Icon{public static Icon createWithResource(android.app.Activity a,int n){return new Icon();}}',
'android/graphics/Rect.java':'package android.graphics;public class Rect{}',
'android/view/View.java':'package android.view;public class View{public void getGlobalVisibleRect(android.graphics.Rect r){}}',
'android/util/Rational.java':'package android.util;public class Rational{int x,y;public Rational(int x,int y){this.x=x;this.y=y;}public boolean isInfinite(){return y==0;}public float floatValue(){return (float)x/y;}}',
'androidx/media3/ui/R.java':'package androidx.media3.ui;public class R{public static class drawable{public static final int exo_icon_pause=1,exo_icon_play=2,exo_icon_next=3;}public static class string{public static final int exo_controls_pause_description=1,exo_controls_play_description=2,exo_controls_hide=3,exo_controls_next_description=4;}}',
'com/fongmi/android/tv/R.java':'package com.fongmi.android.tv;public class R{public static class drawable{public static final int ic_action_audio=1;}}',
'com/fongmi/android/tv/setting/PlayerSetting.java':'package com.fongmi.android.tv.setting;public class PlayerSetting{public static boolean isBackgroundPiP(){return true;}}',
'com/fongmi/android/tv/event/ActionEvent.java':'package com.fongmi.android.tv.event;public class ActionEvent{public static final String PAUSE="pause",PLAY="play",AUDIO="audio",NEXT="next";}',
'com/fongmi/android/tv/receiver/ActionReceiver.java':'package com.fongmi.android.tv.receiver;public class ActionReceiver{public static int calls;public static android.app.PendingIntent getPendingIntent(android.app.Activity a,String action){calls++;var p=new android.app.PendingIntent();p.owner="PlaybackService";p.intent=new android.content.Intent(action);return p;}}',
'io/github/jqssun/airplay/service/AirPlayService.java':'package io.github.jqssun.airplay.service;public class AirPlayService{public static final String ACTION_PLAY="io.github.jqssun.airplay.PLAY",ACTION_PAUSE="io.github.jqssun.airplay.PAUSE",ACTION_NEXT="io.github.jqssun.airplay.NEXT";}',
'com/fongmi/android/tv/player/subtitle/PlaybackGuardProbe.java':'''package com.fongmi.android.tv.player.subtitle;import androidx.media3.mpvplayer.MpvFirstFrameWatchdog;import com.fongmi.android.tv.utils.PiP;import com.fongmi.android.tv.receiver.ActionReceiver;import io.github.jqssun.airplay.service.AirPlayService;import java.io.File;
public class PlaybackGuardProbe{
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 public static void main(String[]args){
  var deadline=new MpvFirstFrameWatchdog();check(!deadline.expired(999999),"unarmed watchdog expired");check(deadline.arm(100),"initial watchdog not armed");check(!deadline.allowEmbedRecovery(1099)&&deadline.allowEmbedRecovery(1100),"embed first-frame grace not preserved");for(long t=200;t<30100;t+=100){check(!deadline.arm(t),"audio progress extended first-frame deadline");check(!deadline.expired(t),"watchdog too early");}check(deadline.expired(30100),"GPU deadline not bounded");deadline.reset();check(!deadline.expired(999999),"stop/frame reset ignored");deadline.arm(100000);check(!deadline.expired(129999)&&deadline.expired(130000),"resume/new-load budget failed");
  var fonts=ExternalFont.getEntries(new File(args[0],"faces.ttc"));check(fonts.size()==2,"TTC faces lost");check(fonts.get(0).mpvFont().equals("Owned Sans")&&fonts.get(1).mpvFont().equals("Owned Sans Bold"),"face/full names not selected");check(fonts.get(1).family().equals("Owned Sans")&&fonts.get(1).faceIndex()==1&&fonts.get(1).typeface().index==1,"TTC face metadata/Android index lost");check(!fonts.get(1).mpvFont().contains(":style="),"literal pattern sent to libass");var fallback=ExternalFont.getEntry(new File(args[0],"fallback.ttf"));check(fallback.mpvFont().equals("Fallback Sans"),"missing full name did not fall back to family");check(ExternalFont.getEntries(new File(args[0],"broken.ttf")).isEmpty(),"invalid font accepted");check(ExternalFont.getEntry(new File(args[0],"postscript.otf")).mpvFont().equals("OwnedCFF-Bold"),"CFF face did not use PostScript name");
  var pip=new PiP();var activity=new android.app.Activity();pip.update(activity,true);check(activity.params.actions.size()==3&&ActionReceiver.calls==3,"ordinary player PiP changed");pip.updateAirPlay(activity,true,true,false);check(activity.params.actions.size()==1&&ActionReceiver.calls==3,"AirPlay video reached ordinary ActionReceiver or showed queue/audio actions");var action=activity.params.actions.get(0).pending;check(action.owner.equals("broadcast")&&action.intent.action.equals(AirPlayService.ACTION_PAUSE)&&action.intent.pkg.equals("owned.app")&&(action.flags&android.app.PendingIntent.FLAG_IMMUTABLE)!=0,"AirPlay pause target/immutability invalid");pip.updateAirPlay(activity,false,true,true);check(activity.params.actions.size()==2&&activity.params.actions.get(0).pending.intent.action.equals(AirPlayService.ACTION_PLAY)&&activity.params.actions.get(1).pending.intent.action.equals(AirPlayService.ACTION_NEXT),"audio DACP actions invalid");pip.updateAirPlay(activity,true,false,false);check(activity.params.actions.isEmpty()&&ActionReceiver.calls==3,"mirror/idle retains false capabilities or touches normal playback");
  System.out.println("PASS production first-frame deadline (progress cannot extend/reset works); font ID4 names/TTC faces/family fallback/invalid file; PiP owner routing, immutable package scope, video/audio/mirror capabilities");
 }
}'''
}
def font_blob(family,style,full,offset,postscript=None):
 names={1:family,2:style,16:family,17:style}
 if full:names[4]=full
 if postscript:names[6]=postscript
 payload=b'';records=b''
 for key,value in names.items():
  data=value.encode('utf-16-be');records+=struct.pack('>6H',3,1,0x409,key,len(data),len(payload));payload+=data
 table=struct.pack('>3H',0,len(names),6+12*len(names))+records+payload
 return struct.pack('>I4H',0x4F54544F if postscript else 0x10000,1,16,0,0)+struct.pack('>4s3I',b'name',0,offset+28,len(table))+table

def main():
 parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--font-source-root',type=Path,default=ROOT);args=parser.parse_args()
 with TemporaryDirectory() as temp:
  folder=Path(temp);fonts=folder/'fonts';fonts.mkdir()
  a=font_blob('Owned Sans','Regular','Owned Sans Regular',20);b=font_blob('Owned Sans','Bold','Owned Sans Bold',20+len(a))
  (fonts/'faces.ttc').write_bytes(struct.pack('>5I',0x74746366,0x10000,2,20,20+len(a))+a+b)
  (fonts/'fallback.ttf').write_bytes(font_blob('Fallback Sans','Bold',None,0));(fonts/'broken.ttf').write_bytes(b'broken')
  (fonts/'postscript.otf').write_bytes(font_blob('Owned CFF','Bold','Owned CFF Bold',0,'OwnedCFF-Bold'))
  sources=[ROOT/p for p in ['app/src/main/java/com/fongmi/android/tv/player/subtitle/FontFamilyParser.java','app/src/main/java/com/fongmi/android/tv/player/subtitle/ExternalFont.java','app/src/main/java/com/fongmi/android/tv/utils/PiP.java','media3compat/src/main/java/androidx/media3/mpvplayer/MpvFirstFrameWatchdog.java']]
  sources[:2]=[args.font_source_root/p.relative_to(ROOT) for p in sources[:2]]
  for name,body in STUBS.items():
   f=folder/name;f.parent.mkdir(parents=True,exist_ok=True);f.write_text(body,encoding='utf-8');sources.append(f)
  classes=folder/'classes';subprocess.run(['javac','-encoding','UTF-8','-d',str(classes),*map(str,sources)],check=True)
  subprocess.run(['java','-Dfont-dir='+str(fonts),'-cp',str(classes),'com.fongmi.android.tv.player.subtitle.PlaybackGuardProbe',str(fonts)],check=True)
 # Wiring checks are intentionally separate from the behavioural policy/framework-double checks.
 player=(ROOT/'media3compat/src/main/java/androidx/media3/mpvplayer/MpvPlayer.java').read_text(encoding='utf-8')
 watchdog=player.split('    private void checkBlackScreen()',1)[1].split('    private void recoverVideoOutput',1)[0]
 assert 'firstFrameWatchdog.expired' in watchdog and 'ERROR_CODE_TIMEOUT' in watchdog
 assert 'updateState(STATE_IDLE, state.playWhenReady' in watchdog
 assert '!state.playWhenReady' in watchdog and '!hasVideoTrack()' in watchdog
 frame=player.split('    private void reportFirstFrame()',1)[1].split('    private boolean hasVideoTrack()',1)[0]
 assert '!hasVideoTrack()' in frame and 'videoWidth <= 0' not in frame
 service=(ROOT/'airplay/src/main/kotlin/io/github/jqssun/airplay/service/AirPlayService.kt').read_text(encoding='utf-8')
 assert 'ACTION_PLAY -> setPlaybackPlaying(true)' in service and 'ACTION_PAUSE -> setPlaybackPlaying(false)' in service
 assert 'addAction(ACTION_PLAY)' in service and 'addAction(ACTION_PAUSE)' in service and 'RECEIVER_NOT_EXPORTED' in service
 for flavor in ('leanback','mobile'):
  sync=(ROOT/f'app/src/{flavor}/java/com/fongmi/android/tv/ui/dialog/SyncDialog.java').read_text(encoding='utf-8')
  assert 'deleteLocal()' not in sync and 'SyncSnapshot.receive' in sync and 'try (response)' in sync
 print('PASS static MPV/AirPlay service/sync UI wiring (not native/sender/Room runtime validation)')
if __name__=='__main__':main()
