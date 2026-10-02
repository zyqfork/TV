#!/usr/bin/env python3
"""Compile the production native-subtitle track classifier; GPU/libass renders all formats."""
from pathlib import Path
import subprocess
import tempfile
ROOT = Path(__file__).resolve().parents[2]
PROBE = '''import androidx.media3.mpvplayer.MpvSubtitleFormats;
public class SubtitleFormatsProbe {
 public static void main(String[] args) {
  String[][] cases = {{"hdmv_pgs_subtitle", "application/pgs"},
    {"dvd_subtitle", "application/vobsub"}, {"subrip", "application/x-subrip"},
    {"ass", "text/x-ssa"}, {"webvtt", "text/vtt"}, {"bad", "text/x-unknown"}};
  for (String[] item : cases)
   if (!MpvSubtitleFormats.mimeType(item[0]).equals(item[1])) throw new AssertionError(item[0]);
  if (!MpvSubtitleFormats.mimeType(null).equals("text/x-unknown")) throw new AssertionError("null");
  System.out.println("PASS GPU/libass subtitle track MIME classification");
 }
}'''
with tempfile.TemporaryDirectory() as directory:
    out = Path(directory)
    probe = out / 'SubtitleFormatsProbe.java'
    probe.write_text(PROBE, encoding='utf8')
    subprocess.run(['javac', '-d', str(out), str(ROOT / 'media3compat/src/main/java/androidx/media3/mpvplayer/MpvSubtitleFormats.java'), str(probe)], check=True)
    subprocess.run(['java', '-cp', str(out), 'SubtitleFormatsProbe'], check=True)
