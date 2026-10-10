"""Compile the production MPV engine and EXO session with deterministic player/queue doubles.
Checks pending-start cancellation and async recovery; does not validate vendor codecs/pixels.
Requires JDK 21+. Run: python other/tests/test_player_pending_start.py
"""
from pathlib import Path
import subprocess
from tempfile import TemporaryDirectory

ROOT = Path(__file__).resolve().parents[2]
STUBS = {
    'androidx/annotation/NonNull.java': 'package androidx.annotation; public @interface NonNull {}',
    'androidx/annotation/Nullable.java': 'package androidx.annotation; public @interface Nullable {}',
    'androidx/media3/common/C.java': 'package androidx.media3.common; public class C {public static final long TIME_UNSET=-9223372036854775807L;}',
    'androidx/media3/common/MimeTypes.java': 'package androidx.media3.common; public class MimeTypes {public static final String APPLICATION_M3U8="application/x-mpegURL";}',
    'androidx/media3/common/TrackSelectionOverride.java': 'package androidx.media3.common; public class TrackSelectionOverride {}',
    'androidx/media3/common/MediaItem.java': 'package androidx.media3.common; public class MediaItem {public String url;public MediaItem(String u){url=u;}}',
    'androidx/media3/common/Tracks.java': 'package androidx.media3.common; public class Tracks {}',
    'androidx/media3/common/PlaybackException.java': '''package androidx.media3.common;public class PlaybackException extends Exception {public final int errorCode;public PlaybackException(int c){errorCode=c;}public static final int ERROR_CODE_BEHIND_LIVE_WINDOW=1002,ERROR_CODE_TIMEOUT=1003,ERROR_CODE_IO_UNSPECIFIED=2000,ERROR_CODE_IO_NETWORK_CONNECTION_FAILED=2001,ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT=2002,ERROR_CODE_IO_BAD_HTTP_STATUS=2004,ERROR_CODE_PARSING_CONTAINER_MALFORMED=3001,ERROR_CODE_PARSING_MANIFEST_MALFORMED=3002,ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED=3003,ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED=3004,ERROR_CODE_DECODER_INIT_FAILED=4001,ERROR_CODE_DECODER_QUERY_FAILED=4002,ERROR_CODE_DECODING_FAILED=4003,ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES=4004;}''',
    'androidx/media3/common/Player.java': '''package androidx.media3.common;public class Player {public static final int COMMAND_SET_VOLUME=99;public boolean isCommandAvailable(int c){return true;}public void setVolume(float v){}public static final int STATE_IDLE=1,STATE_BUFFERING=2,STATE_READY=3,STATE_ENDED=4;public boolean ready,released;public int loads,stops,state=1;public long position;public MediaItem item;public interface Listener {default void onTracksChanged(Tracks t){}default void onPlaybackStateChanged(int s){}}public boolean getPlayWhenReady(){return ready;}public void setPlayWhenReady(boolean b){ready=b;}public void play(){ready=true;}public void pause(){ready=false;}public void stop(){stops++;state=1;}public void release(){released=true;}public void setMediaItem(MediaItem m,long p){if(released)throw new AssertionError("load after release");item=m;position=p;loads++;state=1;ready=false;}public void prepare(){state=2;}public long getCurrentPosition(){return position;}public MediaItem getCurrentMediaItem(){return item;}public int getPlaybackState(){return state;}public void seekToDefaultPosition(){position=0;}public long getDuration(){return 10000;}public boolean isCurrentMediaItemLive(){return false;}public void addListener(Listener l){}public void removeListener(Listener l){}public void clearMediaItems(){item=null;}}''',
    'androidx/media3/exoplayer/ExoPlayer.java': 'package androidx.media3.exoplayer;public class ExoPlayer extends androidx.media3.common.Player {}',
    'androidx/media3/mpvplayer/MpvPlayer.java': '''package androidx.media3.mpvplayer;public class MpvPlayer extends androidx.media3.common.Player {public void setVolumeGain(float g){}public void setSecondaryTextTrackSelectionOverride(androidx.media3.common.TrackSelectionOverride o){}public void setSecondarySubtitleDelayMs(long t){}public void addSubtitle(Object o){}}''',
    'com/fongmi/android/tv/App.java': '''package com.fongmi.android.tv;public class App {public static final java.util.ArrayDeque<Runnable> main=new java.util.ArrayDeque<>();public static void post(Runnable r){main.add(r);}public static void drain(){while(!main.isEmpty())main.remove().run();}}''',
    'com/fongmi/android/tv/utils/Task.java': '''package com.fongmi.android.tv.utils;public class Task {public static final java.util.ArrayDeque<Runnable> jobs=new java.util.ArrayDeque<>();public static Object submit(Runnable r){jobs.add(r);return null;}public static void one(){jobs.remove().run();}}''',
    'com/fongmi/android/tv/bean/Sub.java': 'package com.fongmi.android.tv.bean;public class Sub {public boolean isEmpty(){return false;}}',
    'com/fongmi/android/tv/player/effect/PlayerEffect.java': 'package com.fongmi.android.tv.player.effect;public interface PlayerEffect {PlayerEffect NONE=new PlayerEffect(){};}',
    'com/fongmi/android/tv/player/media/PlaySpec.java': '''package com.fongmi.android.tv.player.media;public class PlaySpec {public String url,format;public PlaySpec(String u){url=u;}public String getUrl(){return url;}public String getFormat(){return format;}public void setFormat(String f){format=f;}}''',
    'com/fongmi/android/tv/player/media/MediaItemFactory.java': '''package com.fongmi.android.tv.player.media;public class MediaItemFactory {public static androidx.media3.common.MediaItem from(PlaySpec s){return new androidx.media3.common.MediaItem(s.url);}public static androidx.media3.common.MediaItem from(PlaySpec s,int d){return from(s);}public static Object buildSubConfig(Object s){return s;}}''',
    'com/fongmi/android/tv/player/util/HlsPngTsPrepare.java': '''package com.fongmi.android.tv.player.util;public class HlsPngTsPrepare {public static com.fongmi.android.tv.player.media.PlaySpec prepare(com.fongmi.android.tv.player.media.PlaySpec s){return s;}}''',
    'com/fongmi/android/tv/player/mpv/MpvUtil.java': '''package com.fongmi.android.tv.player.mpv;public class MpvUtil {public static int config=1;public static java.util.Map<String,Object> playbackConfig(int d,boolean l){return java.util.Map.of("setting",config,"decode",d,"live",l);}public static boolean isAvailable(){return true;}public static androidx.media3.mpvplayer.MpvPlayer buildPlayer(int d,boolean l,androidx.media3.common.Player.Listener e){return new androidx.media3.mpvplayer.MpvPlayer();}public static void setSubtitleStyle(Object o){}}''',
    'com/fongmi/android/tv/player/mpv/MpvPlayerEffect.java': '''package com.fongmi.android.tv.player.mpv;public class MpvPlayerEffect implements com.fongmi.android.tv.player.effect.PlayerEffect {public MpvPlayerEffect(Object p){}public void applyVideoEffect(){}public void applyAudioEffect(){}}''',
    'com/fongmi/android/tv/player/mpv/MpvErrorMsgProvider.java': '''package com.fongmi.android.tv.player.mpv;public class MpvErrorMsgProvider {public String get(Object e){return "error";}}''',
    'com/fongmi/android/tv/player/exo/ExoUtil.java': '''package com.fongmi.android.tv.player.exo;public class ExoUtil {public static int config=1;public static int playbackConfig(int d,boolean l){return config;}public static androidx.media3.exoplayer.ExoPlayer buildPlayer(int d,androidx.media3.common.Player.Listener l,Object p,boolean live){return new androidx.media3.exoplayer.ExoPlayer();}}''',
    'com/fongmi/android/tv/player/exo/ExoPlayerEffect.java': '''package com.fongmi.android.tv.player.exo;public class ExoPlayerEffect implements com.fongmi.android.tv.player.effect.PlayerEffect {public boolean isAudioProcessorInstalled(){return true;}public ExoPlayerEffect(boolean b){}public Object getAudioProcessor(){return null;}public void setPlayer(Object p){}public void applyVideoEffect(){}public void applyAudioEffect(){}public void release(){}}''',
    'com/fongmi/android/tv/player/exo/ErrorMsgProvider.java': 'package com.fongmi.android.tv.player.exo;public class ErrorMsgProvider {public String get(Object e){return "error";}}',
    'com/fongmi/android/tv/player/exo/ExoVolumeGain.java': '''package com.fongmi.android.tv.player.exo;public class ExoVolumeGain {public void attach(Object p){}public void setGain(float g){}public void release(){}}''',
    'com/fongmi/android/tv/player/exo/PreCache.java': '''package com.fongmi.android.tv.player.exo;public class PreCache {public void preload(Object m,long p){}public void clearPreload(){}public void stop(){}public void release(){}public void start(Object p,Object m){}}''',
    'com/fongmi/android/tv/setting/AudioSetting.java': 'package com.fongmi.android.tv.setting;public class AudioSetting {public static boolean hasEffect(int x){return false;}}',
    'com/fongmi/android/tv/setting/PlayerSetting.java': 'package com.fongmi.android.tv.setting;public class PlayerSetting {public static void putAudioPassThrough(boolean b){}public static boolean isAudioPassThrough(){return false;}}',
    'com/fongmi/android/tv/player/exo/PendingStartProbe.java': '''package com.fongmi.android.tv.player.exo;
import com.fongmi.android.tv.player.mpv.MpvPlayerEngine;import com.fongmi.android.tv.player.media.PlaySpec;import com.fongmi.android.tv.utils.Task;import com.fongmi.android.tv.App;import androidx.media3.common.*;
public class PendingStartProbe {
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 static void settle(){while(!Task.jobs.isEmpty())Task.one();App.drain();}
 static void restart(com.fongmi.android.tv.player.engine.PlayerEngine e,PlaySpec s,long p,boolean ready){
  try {var m=e.getClass().getMethod("start",PlaySpec.class,long.class,boolean.class);m.invoke(e,s,p,ready);}
  catch(NoSuchMethodException missing){e.start(s,p);}
  catch(Exception err){throw new AssertionError(err);}
 }
 public static void main(String[] a){
  if(a[0].startsWith("intent")){
   var engines=a[0].equals("intent-exo")?java.util.List.<com.fongmi.android.tv.player.engine.PlayerEngine>of(new ExoPlayerEngine(1,new Player.Listener(){})):java.util.List.<com.fongmi.android.tv.player.engine.PlayerEngine>of(new MpvPlayerEngine(1,new Player.Listener(){}),new ExoPlayerEngine(1,new Player.Listener(){}));
   for(var e:engines){
    var s=new PlaySpec("http://owned/intent.m3u8");e.start(s,321);settle();e.getPlayer().pause();
    restart(e,s,321,false);settle();check(!e.getPlayer().getPlayWhenReady(),e.getType()+" retry discarded pause");
    e.rebuild();restart(e,s,321,false);settle();check(!e.getPlayer().getPlayWhenReady(),e.getType()+" rebuild discarded pause");
    check(e.getPlayer().getCurrentPosition()==321,"rebuild lost position");
    restart(e,s,321,true);settle();check(e.getPlayer().getPlayWhenReady(),"playing recovery lost intent");
    restart(e,s,321,false);e.getPlayer().play();settle();check(e.getPlayer().getPlayWhenReady(),"late preparation lost user resume");
    e.start(s,0);settle();check(e.getPlayer().getPlayWhenReady(),"fresh start lost autoplay");
    check(!e.refreshConfig(),"unchanged settings requested a rebuild");
    if(e.getType()==com.fongmi.android.tv.player.engine.PlayerEngine.Type.MPV)com.fongmi.android.tv.player.mpv.MpvUtil.config++;
    else ExoUtil.config++;
    check(e.refreshConfig(),"construction setting change ignored");e.rebuild();
    check(!e.refreshConfig(),"rebuild did not refresh construction snapshot");
    restart(e,s,321,false);settle();check(!e.getPlayer().getPlayWhenReady()&&e.getPlayer().getCurrentPosition()==321,"setting rebuild lost pause/position");e.release();
   }
   System.out.println("PASS both production engines preserve explicit restart/rebuild intent, position and fresh autoplay");
  }else if(a[0].equals("stop")){
   var engine=new MpvPlayerEngine(1,new Player.Listener(){});var player=engine.getPlayer();
   engine.start(new PlaySpec("http://owned/a.m3u8"),900);Task.one();engine.stop();App.drain();
   check(player.loads==0,"MPV pending start revived after stop");
   engine.start(new PlaySpec("http://owned/b.m3u8"),100);engine.stop();settle();
   check(player.loads==0,"MPV stopped worker published");
   engine.start(new PlaySpec("http://owned/obsolete.m3u8"),100);
   engine.start(new PlaySpec("http://owned/current.m3u8"),200);settle();
   check(player.loads==1&&player.item.url.contains("current")&&player.getPlayWhenReady(),"new request did not supersede old request with autoplay");
   engine.start(new PlaySpec("http://owned/released.m3u8"),100);Task.one();engine.release();App.drain();
   check(player.loads==1,"MPV released request published");
   var rebuilding=new MpvPlayerEngine(1,new Player.Listener(){});var old=rebuilding.getPlayer();
   rebuilding.start(new PlaySpec("http://owned/old.m3u8"),100);Task.one();rebuilding.rebuild();App.drain();
   check(old.loads==0&&rebuilding.getPlayer().loads==0,"MPV rebuilt request published");rebuilding.release();
   System.out.println("PASS MPV stop/release/rebuild reject late requests; new source supersedes obsolete work");
  }else if(a[0].equals("pause")){
   var engine=new MpvPlayerEngine(1,new Player.Listener(){});var player=engine.getPlayer();
   engine.start(new PlaySpec("http://owned/a.m3u8"),900);player.pause();settle();
   check(player.loads==1&&player.position==900,"MPV did not prepare intended item");
   check(!player.getPlayWhenReady(),"MPV asynchronous prepare discarded pause");
   engine.handleError(new PlaybackException(PlaybackException.ERROR_CODE_IO_UNSPECIFIED));
   check(!player.getPlayWhenReady(),"MPV HLS retry discarded pause");
   engine.start(new PlaySpec("http://owned/resume.m3u8"),900);player.pause();player.play();settle();
   check(player.getPlayWhenReady(),"MPV prepare discarded resume");engine.release();
   System.out.println("PASS MPV prepare respects pause while retaining resume position");
  }else{
   var session=new ExoPlayerSession(1,new Player.Listener(){});var player=session.player();
   session.start(new PlaySpec("http://owned/a.m3u8"),400);
   session.handleError(new PlaybackException(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED));
   player.pause();settle();check(!player.getPlayWhenReady(),"EXO PNG-TS recovery discarded pause");
   session.start(new PlaySpec("http://owned/b.m3u8"),800);
   check(player.getPlayWhenReady(),"EXO fresh start lost autoplay");
   session.handleError(new PlaybackException(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED));
   player.pause();player.play();settle();check(player.getPlayWhenReady()&&player.position==800,"EXO recovery discarded resume or position");
   session.start(new PlaySpec("http://owned/c.m3u8"),800);session.handleError(new PlaybackException(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED));
   int loads=player.loads;Task.one();session.stop();App.drain();check(player.loads==loads,"EXO stopped recovery published");
   session.start(new PlaySpec("http://owned/d.m3u8"),800);session.handleError(new PlaybackException(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED));
   loads=player.loads;Task.one();session.release();App.drain();check(player.loads==loads,"EXO released recovery published");
   System.out.println("PASS EXO recovery preserves pause/resume and position; stop/release reject late replies");
  }
 }
}''',
}


def main():
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--only', choices=('stop', 'pause', 'exo', 'intent', 'intent-exo'))
    parser.add_argument('--source-root', type=Path, default=ROOT, help='Alternate tree for negative controls')
    args = parser.parse_args()
    with TemporaryDirectory() as tmp:
        folder = Path(tmp)
        sources = [args.source_root / p for p in (
            'app/src/main/java/com/fongmi/android/tv/player/engine/PlayerEngine.java',
            'app/src/main/java/com/fongmi/android/tv/player/mpv/MpvPlayerEngine.java',
            'app/src/main/java/com/fongmi/android/tv/player/exo/ExoPlayerSession.java',
            'app/src/main/java/com/fongmi/android/tv/player/exo/ExoPlayerEngine.java',
            'app/src/main/java/com/fongmi/android/tv/player/engine/PlaybackRecoveryPolicy.java',
        )]
        for name, body in STUBS.items():
            p = folder / name
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(body, encoding='utf-8')
            sources.append(p)
        classes = folder / 'classes'
        subprocess.run(['javac', '-encoding', 'UTF-8', '-d', str(classes), *map(str, sources)], check=True)
        for probe in (args.only,) if args.only else ('stop', 'pause', 'exo', 'intent'):
            subprocess.run(['java', '-cp', str(classes), 'com.fongmi.android.tv.player.exo.PendingStartProbe', probe], check=True)


if __name__ == '__main__':
    main()
