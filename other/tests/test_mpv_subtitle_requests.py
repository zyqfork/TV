#!/usr/bin/env python3
"""Compile/run the production queue. This is not a device/native HTTP responsiveness test."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'media3compat/src/main/java/androidx/media3/mpvplayer/MpvSubtitleRequests.java'
PROBE = r'''package androidx.media3.mpvplayer;
import java.util.*;
public final class SubtitleRequestContracts {
 static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
 static class Wire implements MpvSubtitleRequests.Transport {
  List<Long> sent=new ArrayList<>(), aborted=new ArrayList<>();
  List<String[]> args=new ArrayList<>();
  public int send(long id,String[] command) { sent.add(id);args.add(command);return command[1].equals("reject") ? -18 : 0; }
  public void abort(long id) { aborted.add(id); }
  long last() { return sent.get(sent.size()-1); }
 }
 public static void main(String[] ignored) {
  Wire wire=new Wire();List<Integer> replies=new ArrayList<>();
  MpvSubtitleRequests q=new MpvSubtitleRequests(wire,replies::add);
  q.enqueue("slow",false);q.enqueue("fast",false);q.enqueue("fast",false);
  check(wire.sent.isEmpty(),"no imports before FILE_LOADED");
  q.start();long slow=wire.last();
  check(wire.sent.size()==1,"one native I/O at a time, no waiting in enqueue/start");
  q.complete(slow,-13);long fast=wire.last();
  check(wire.sent.size()==2 && wire.args.get(1)[1].equals("fast"),"HTTP failure continues queue");
  q.complete(slow,0);check(wire.sent.size()==2,"duplicate/late reply ignored");
  q.complete(fast,0);q.enqueue("fast",false);check(wire.sent.size()==2,"successful auto import deduplicated");
  q.enqueue("manual",true);check(wire.args.get(2)[2].equals("select"),"manual selection semantics");
  long manual=wire.last();q.enqueue("not-started",false);q.cancel();
  check(wire.aborted.equals(Arrays.asList(manual)),"close cancels active native request only");
  int count=replies.size();q.complete(manual,0);q.start();
  check(wire.sent.size()==3 && replies.size()==count,"close discards queued/late work");
  q.reset();q.enqueue("new-file",false);q.start();long newer=wire.last();
  check(newer!=manual,"new file request identity");q.complete(manual,0);
  check(replies.size()==count,"old file reply cannot complete new file");q.complete(newer,0);
  q.reset();q.enqueue("reject",false);q.enqueue("survivor",false);q.start();
  check(wire.args.get(wire.args.size()-1)[1].equals("survivor"),"ABI/queue rejection needs no callback and continues");
  long survivor=wire.last();q.reset();q.complete(survivor,0);
  Wire wire2=new Wire();MpvSubtitleRequests next=new MpvSubtitleRequests(wire2,e -> {});
  next.enqueue("replacement-owner",false);next.start();
  check(wire2.last()>survivor,"IDs never reused across native singleton owners");
  final MpvSubtitleRequests[] ref=new MpvSubtitleRequests[1];Wire reentrant=new Wire();
  ref[0]=new MpvSubtitleRequests(reentrant,e -> ref[0].reset());
  ref[0].enqueue("first",false);ref[0].enqueue("discard",false);ref[0].start();ref[0].complete(reentrant.last(),0);
  check(reentrant.sent.size()==1,"completion-induced stop/close cannot pump old tail");
  System.out.println("PASS production subtitle queue: nonblocking order, dedup, failures, manual, close/stop/file/owner, late reply");
 }
}'''
with tempfile.TemporaryDirectory() as directory:
    out = Path(directory)
    probe = out / 'SubtitleRequestContracts.java'
    probe.write_text(PROBE, encoding='utf8')
    subprocess.run(['javac', '--release', '8', '-d', str(out), str(SOURCE), str(probe)], check=True)
    subprocess.run(['java', '-cp', str(out), 'androidx.media3.mpvplayer.SubtitleRequestContracts'], check=True)

player = (ROOT / 'media3compat/src/main/java/androidx/media3/mpvplayer/MpvPlayer.java').read_text(encoding='utf8')
assert 'MPVLib.command(new String[]{"sub-add"' not in player
assert player.count('subtitleRequests.reset();') >= 5
assert 'subtitleRequests.cancel();' in player and 'eventCommandReply(long id, int error)' in player
bridge = (ROOT / 'media3compat/src/main/cpp/mpv/android_command_jni.cpp').read_text(encoding='utf8')
assert 'mpv_command_async(' in bridge and 'mpv_abort_async_command(' in bridge
assert 'mpv_command(' not in bridge
patch = (ROOT / 'media3compat/scripts/apply_subtitle_overlay.py').read_text(encoding='utf8')
assert 'MPV_EVENT_COMMAND_REPLY' in patch and 'mpctx->stop_play || mp_cancel_test(cancel)' in patch
print('PASS async import wiring, TEXT cancel, lifecycle resets and native post-open cancellation guard')
