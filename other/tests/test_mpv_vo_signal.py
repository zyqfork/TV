#!/usr/bin/env python3
"""Exact Java first-frame method with doubles; native contracts are labelled static."""
from pathlib import Path
import tempfile,subprocess
root=Path(__file__).resolve().parents[2]
s=(root/'media3compat/src/main/java/androidx/media3/mpvplayer/MpvPlayer.java').read_text(encoding='utf-8')
def method(start,end):
 a=s.index(start);b=s.index(end,a);return s[a:b].replace('android.os.SystemClock.elapsedRealtime()','now')
first=method('    private void reportFirstFrame()','\n    private boolean hasVideoTrack')
java=r'''
import java.util.*;
public class Probe {
 static final int STATE_READY=3;
 boolean firstFrameReported,surfaceReady=true,fileLoaded=true,videoFrameSeen,playbackRestartSeen,video=true,released,surfaceRecovering;
 boolean softwareFallbackReported,softwareFallbackSeen;
 void noteActiveHwdec(String v){}
 static class MPVLib {static String getCachedString(String n){return null;}}
 Object blackScreenWatchdog=new Object();
 int published;long positionMs,firstFrameStartPositionMs;
 String lastNativeError,activeVideoOutput;
 static class Watchdog {void reset(){}}static class Handler {void removeCallbacks(Object r){}}static class State {boolean playWhenReady=true,frame;State buildUpon(){return this;}State setNewlyRenderedFirstFrame(boolean b){frame=b;return this;}State build(){return this;}}
 static class Log {static void w(String t,String m){}}
 State state=new State();Watchdog firstFrameWatchdog=new Watchdog();Handler applicationHandler=new Handler();
 boolean hasVideoTrack(){return video;}State buildState(int i,boolean p,Object e){State s=new State();s.playWhenReady=p;return s;}void invalidateState(){if(state.frame)published++;}
 String getDecodeOption(){return "mediacodec";}String getVo(){return "gpu";}void scheduleProgressBlackScreenCheck(){}
 @FIRST@
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 public static void main(String[] a){
  Probe p=new Probe();p.playbackRestartSeen=true;p.reportFirstFrame();check(p.published==0,"metadata/restart is not a decoded video frame");p.videoFrameSeen=true;p.reportFirstFrame();check(p.published==1&&!p.state.frame,"valid VO frame failed to reveal or edge event remained set");p.reportFirstFrame();check(p.published==1,"duplicate first frame");
  p=new Probe();p.videoFrameSeen=true;p.playbackRestartSeen=true;p.state.playWhenReady=false;p.reportFirstFrame();check(p.published==1&&!p.state.playWhenReady,"paused decoded first frame discarded or resumed");
  p=new Probe();p.videoFrameSeen=true;p.playbackRestartSeen=true;p.video=false;p.reportFirstFrame();check(p.published==0,"audio-only presentation claimed video");
  System.out.println("PASS production first-frame: VO frame required, single edge, paused intent kept");
 }
}
'''.replace('@FIRST@',first)
with tempfile.TemporaryDirectory() as d:
 f=Path(d)/'Probe.java';f.write_text(java,encoding='utf-8');subprocess.run(['javac','-d',d,str(f)],check=True);subprocess.run(['java','-cp',d,'Probe'],check=True)

# The container-side decoder deadlock is fixed in libcodec2_rk_component.so. The in-app
# dead-codec rebuild loop that only worked around it must be gone, or a slow decoder
# would still be torn down mid-playback.
for gone in ('recoverDeadHardwareDecoder', 'isDeadHardwareDecoder', 'hwdecFailStreak',
             'hwdecRecoveryAttempts', 'hwdecRecoveredFrameElapsed', 'hwdecFailureReported',
             'HWDEC_RECOVERY_LIMIT', 'HWDEC_STABLE_MS'):
    assert gone not in s, f'stale stall workaround {gone}'
# The bounded first-frame deadline is kept: it turns an unbounded hang into a bounded error.
assert 'MpvFirstFrameWatchdog' in s

patch=(root/'media3compat/scripts/apply_subtitle_overlay.py').read_text(encoding='utf-8')
assert 'FONGMI_VO_FRAME_PTS' in patch and 'f->params.force_window || f->pts == MP_NOPTS_VALUE' in patch
# The top-edge crop origin and the real texture storage size are independent fixes.
assert 'FONGMI_MEDIACODEC_FRAME_CROP' in patch
assert 'FONGMI_IMAGE_STORAGE_SIZE' in patch
# Everything the stall investigation added to the native tree must be gone: the
# container-side C2/MPP defect is fixed in libcodec2_rk_component.so, so an app-side
# copy/fence/pool workaround would only cost quality or hide real errors.
for gone in ('FONGMI_IMAGE_FENCE_LEDGER', 'FONGMI_IMAGE_SUBMIT', 'FONGMI_IMAGE_ACQUIRE',
             'FONGMI_IMAGE_BUFFER_DIAGNOSTIC', 'FONGMI_HEVC_SPS_DIAGNOSTIC', 'FONGMI_HEVC_DPB',
             'FONGMI_IMAGE_COPY', 'FONGMI_IMAGE_LIFETIME', 'FONGMI_IMAGE_UNMAP_HOLDS',
             'AImage_deleteAsync', 'AImage_getCropRect', 'buffer_format_logged',
             'copy_prog', 'copy_valid', 'native_fence', 'FONGMI_IMAGE_RELEASE',
             'disable_frame_copy', 'copy_external_frame', 'FONGMI_IMAGE_SLOTS'):
    assert gone not in patch, f'stale stall workaround {gone}'
# Only the pinned mpv/ffmpeg files this project still needs are patched. Count call
# sites at line start so the helper definition does not inflate the number.
assert sum(1 for line in patch.splitlines() if line.startswith('patch(')) == 8
util=(root/'app/src/main/java/com/fongmi/android/tv/player/mpv/MpvUtil.java').read_text(encoding='utf-8')
assert 'vd-queue' not in util  # mpv's decoder queue is off by default; enabling it only holds more codec buffers
assert 'FONGMI_EXTERNAL_CROP_CLAMP' not in patch # rejected prototype did not improve actual hardware pixels
# Rejected diagnostic experiment: do not ship a vendor-name gate or fabricated HEVC level.
assert 'HEVCMainTierLevel6' not in patch and 'c2.rk' not in patch
print('PASS STATIC: stall workarounds and one-off diagnostics removed; kept fixes intact. Does not prove device pixels.')
