#!/usr/bin/env python3
"""Compile the actual codec allow-list; not an Android rendering/synchronization test."""
from pathlib import Path
import subprocess
import tempfile
ROOT = Path(__file__).resolve().parents[2]
PROBE = '''import androidx.media3.mpvplayer.MpvSubtitleOverlayPolicy;
public class OverlayPolicyContracts {
 public static void main(String[] args) {
  for (String codec : new String[]{"subrip","SRT","text","utf8"})
   if (!MpvSubtitleOverlayPolicy.supports(codec)) throw new AssertionError(codec);
  for (String codec : new String[]{null,"","ass","ssa","hdmv_pgs_subtitle","dvd_subtitle","dvb_subtitle","webvtt","ttml","unknown"})
   if (MpvSubtitleOverlayPolicy.supports(codec)) throw new AssertionError("format silently flattened: "+codec);
  if (!MpvSubtitleOverlayPolicy.mimeType("hdmv_pgs_subtitle").equals("application/pgs")) throw new AssertionError("PGS classification");
  if (!MpvSubtitleOverlayPolicy.mimeType("dvd_subtitle").equals("application/vobsub")) throw new AssertionError("DVD classification");
  if (!MpvSubtitleOverlayPolicy.mimeType("subrip").equals("application/x-subrip")) throw new AssertionError("SRT classification");
  System.out.println("PASS actual overlay policy: plain SRT/text only; ASS/PGS/styled/unknown rejected");
 }
}'''
with tempfile.TemporaryDirectory() as directory:
    out = Path(directory)
    probe = out / 'OverlayPolicyContracts.java'
    probe.write_text(PROBE)
    subprocess.run(['javac', '-d', str(out), str(ROOT / 'media3compat/src/main/java/androidx/media3/mpvplayer/MpvSubtitleOverlayPolicy.java'), str(probe)], check=True)
    subprocess.run(['java', '-cp', str(out), 'OverlayPolicyContracts'], check=True)
