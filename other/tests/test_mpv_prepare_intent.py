#!/usr/bin/env python3
"""Execute the production timeline replacement method with doubles; not a native/pixel test."""
from pathlib import Path
import tempfile,subprocess,argparse
p=argparse.ArgumentParser();p.add_argument('--source-root',type=Path,default=Path(__file__).resolve().parents[2]);root=p.parse_args().source_root
s=(root/'media3compat/src/main/java/androidx/media3/mpvplayer/MpvPlayer.java').read_text(encoding='utf-8');a=s.index('    protected ListenableFuture<?> handleSetMediaItems(');b=s.index('\n    @Override',a);method=s[a:b]
java=r'''
import java.util.*;
public class Probe {
 interface ListenableFuture<T>{}static ListenableFuture<Void> done(){return null;}
 static final int STATE_IDLE=1;static class C {static final int INDEX_UNSET=-1;static final long TIME_UNSET=-9223372036854775807L;}
 static class MediaItem{}static class Tracks {static Tracks EMPTY=new Tracks();}static class Requests {void reset(){}}static class State {boolean playWhenReady;State(boolean v){playWhenReady=v;}}
 Requests subtitleRequests=new Requests();List<MediaItem> playlist=new ArrayList<>();Map<Integer,Integer> mpvTrackIds=new HashMap<>();List<Object> chapters,editions;Tracks currentTracks;MediaItem mediaItem;State state=new State(true);List<Boolean> published=new ArrayList<>();int currentMediaItemIndex,videoWidth,videoHeight,videoCropWidth,videoCropHeight,videoOutputWidth,videoOutputHeight,embedSurfaceRetries;long positionMs,pendingSeekMs,durationMs,bufferedPositionMs;boolean fileLoaded,embedVoDisabled,recentSurfaceFailure,softwareFallbackReported,softwareFallbackSeen;String lastNativeError,activeVideoOutput;
 void updateState(int s,boolean intent,Object err){state=new State(intent);published.add(intent);}
 METHOD
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 public static void main(String[] a){Probe p=new Probe();p.videoCropWidth=100;p.videoCropHeight=80;p.handleSetMediaItems(List.of(new MediaItem()),C.INDEX_UNSET,1234);check(p.state.playWhenReady,"timeline replacement silently paused pending autoplay");check(p.videoCropWidth==0&&p.videoCropHeight==0,"previous file crop leaked into new timeline");check(p.positionMs==1234&&p.pendingSeekMs==1234,"lost resume position");check(!p.published.contains(false),"controller observed a spurious pause pulse");p.state=new State(false);p.handleSetMediaItems(List.of(new MediaItem()),0,C.TIME_UNSET);check(!p.state.playWhenReady&&p.positionMs==0,"replacement lost real user pause or sentinel position");p.state=new State(true);p.handleSetMediaItems(List.of(),C.INDEX_UNSET,C.TIME_UNSET);check(p.state.playWhenReady,"empty timeline must not invent a pause request");System.out.println("PASS exact production replacement: playing and paused intents preserved, no transient false publication, resume/sentinel valid");}
}
'''.replace('METHOD',method)
with tempfile.TemporaryDirectory() as d:
 f=Path(d)/'Probe.java';f.write_text(java,encoding='utf-8');subprocess.run(['javac','-d',d,str(f)],check=True);subprocess.run(['java','-cp',d,'Probe'],check=True)
stop=s[s.index('protected ListenableFuture<?> handleStop'):s.index('protected ListenableFuture<?> handleSetTrackSelectionParameters')]
load=s[s.index('private void loadFile'):s.index('private void applyPendingSeekAndSubtitles')]
assert 'setPropertyAsync("vo", "null")' not in stop+load
assert 'setPropertyAsync("hwdec", "no")' not in stop
print('PASS STATIC lifecycle: stop/load do not race pending decoder destruction with a null VO or software hwdec override')
