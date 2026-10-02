#!/usr/bin/env python3
"""Exercise the production live zero-copy policy without an Android emulator."""
from pathlib import Path
import subprocess
import tempfile

source = Path('media3compat/src/main/java/androidx/media3/mpvplayer/MpvAutomaticOutputPolicy.java')
harness = '''
import androidx.media3.mpvplayer.MpvAutomaticOutputPolicy;
import java.util.Map;
public class AutoOutputProbe {
  static void check(boolean expected, boolean live, boolean hard, boolean effects,
                    boolean external, boolean selected, boolean surfaceView) {
    boolean actual = MpvAutomaticOutputPolicy.direct(live, hard, effects, external, selected, surfaceView);
    if (actual != expected) throw new AssertionError("unexpected direct output: " + actual);
  }
  public static void main(String[] args) {
    if (!"mediacodec".equals(MpvAutomaticOutputPolicy.HWDEC_MEDIACODEC)
        || !"mediacodec_embed".equals(MpvAutomaticOutputPolicy.VO_MEDIACODEC_EMBED))
      throw new AssertionError("production MPV option constants changed");
    check(true, true, true, false, false, false, true);
    check(false, false, true, false, false, false, true); // VOD stays on GPU
    check(false, true, false, false, false, false, true); // soft decode
    check(false, true, true, true, false, false, true);  // video effects
    check(false, true, true, false, true, false, true);  // external subtitle, before load
    check(false, true, true, false, false, true, true);  // embedded/manual selected subtitle
    check(false, true, true, false, false, false, false);// TextureView/non-SurfaceView output
    if (!MpvAutomaticOutputPolicy.acceptsUserOptions(Map.of(), false, false))
      throw new AssertionError("default hard mode must allow eligible live embed");
    if (!MpvAutomaticOutputPolicy.acceptsUserOptions(Map.of("hwdec", "mediacodec", "vo", "mediacodec_embed"), false, false))
      throw new AssertionError("compatible explicit mpv.conf");
    for (String option : new String[]{"hwdec", "vo", "gpu-api", "gpu-context"}) {
      Map<String,String> config = Map.of(option, switch (option) {
        case "hwdec" -> "mediacodec-copy";
        case "vo" -> "gpu";
        default -> "opengl";
      });
      if (MpvAutomaticOutputPolicy.acceptsUserOptions(config, false, false))
        throw new AssertionError("ignored mpv.conf " + option);
    }
    if (MpvAutomaticOutputPolicy.acceptsUserOptions(Map.of("hwdec", "no"), false, false)
        || MpvAutomaticOutputPolicy.acceptsUserOptions(Map.of(), true, false)
        || MpvAutomaticOutputPolicy.acceptsUserOptions(Map.of(), false, true))
      throw new AssertionError("user soft/GPU preference overwritten");
    System.out.println("PASS automatic live output and mpv.conf override matrix");
  }
}
'''
with tempfile.TemporaryDirectory() as tmp:
    path = Path(tmp)
    (path / 'AutoOutputProbe.java').write_text(harness, encoding='utf8')
    subprocess.run(['javac', '-d', tmp, str(source), str(path / 'AutoOutputProbe.java')], check=True)
    subprocess.run(['java', '-cp', tmp, 'AutoOutputProbe'], check=True)
