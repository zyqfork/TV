#!/usr/bin/env python3
"""Execute the exact production decoder-reopen method with doubles; label other checks static."""
from pathlib import Path
import subprocess,tempfile
ROOT=Path(__file__).resolve().parents[2]
s=(ROOT/'media3compat/src/main/java/androidx/media3/mpvplayer/MpvPlayer.java').read_text(encoding='utf-8')
a=s.index('    private void reopenVideoDecoder() {');b=s.index('\n    @Override\n    public void logMessage',a);method=s[a:b]
probe=r'''
import java.util.*;
public class Probe {
 static class C {static final int TRACK_TYPE_VIDEO=2;}
 static class Format {String id;Format(String s){id=s;}}
 static class Tracks {List<Group> groups=new ArrayList<>();List<Group> getGroups(){return groups;}
 static class Group {int length=1,type;boolean selected;Format format;Group(int t,boolean s,String id){type=t;selected=s;format=new Format(id);}int getType(){return type;}boolean isTrackSelected(int i){return selected;}Format getTrackFormat(int i){return format;}}}
 static class Config {Map<String,String> preInitOptions=new HashMap<>();}
 static class MPVLib {static List<String> writes=new ArrayList<>();static void setPropertyAsync(String k,String v){writes.add(k+"="+v);}}
 Config config=new Config();Tracks currentTracks=new Tracks();
 METHOD
 public static void main(String[] args){Probe p=new Probe();p.currentTracks.groups.add(new Tracks.Group(1,true,"11"));p.currentTracks.groups.add(new Tracks.Group(2,false,"7"));p.currentTracks.groups.add(new Tracks.Group(2,true,"3"));p.reopenVideoDecoder();if(!MPVLib.writes.equals(List.of("vid=no","vid=3")))throw new AssertionError(MPVLib.writes);MPVLib.writes.clear();p.currentTracks.groups.clear();p.config.preInitOptions.put("vid","5");p.reopenVideoDecoder();if(!MPVLib.writes.equals(List.of("vid=no","vid=5")))throw new AssertionError();System.out.println("PASS exact production method: close selected internal decoder then restore its native track ID; preserve configured selection when inventory pending");}
}
'''.replace('METHOD',method)
with tempfile.TemporaryDirectory() as d:
 f=Path(d)/'Probe.java';f.write_text(probe,encoding='utf-8');subprocess.run(['javac','-d',d,str(f)],check=True);subprocess.run(['java','-cp',d,'Probe'],check=True)
assert 'MPVLib.commandAsync(new String[]{"video-reload"})' not in s
end=s[s.index('private void scheduleEndFileError'):s.index('private void cancelPendingEndFileError')]
assert 'updateState(STATE_IDLE, state.playWhenReady' in end and 'updateState(STATE_IDLE, false' not in end
# The container-side decoder deadlock is fixed in libcodec2_rk_component.so. The in-app
# dead-codec rebuild loop that only worked around it must be gone, or a slow decoder
# would still be torn down mid-playback.
for gone in ('recoverDeadHardwareDecoder', 'isDeadHardwareDecoder', 'hwdecFailStreak',
             'hwdecRecoveryAttempts', 'hwdecRecoveredFrameElapsed', 'hwdecFailureReported',
             'HWDEC_RECOVERY_LIMIT', 'HWDEC_STABLE_MS'):
    assert gone not in s, f'stale stall workaround {gone}'
# MpvFirstFrameWatchdog is kept: it turns an unbounded hang into a bounded error
# and is independent of the container-side decoder fix.
assert 'MpvFirstFrameWatchdog' in s
print('PASS STATIC: the dead-decoder rebuild workaround is removed; network error retains intent. Does not prove device pixels.')
