#!/usr/bin/env python3
"""Compile the real HDR direct-output gate and check every branch without Android."""
from pathlib import Path
import subprocess
import tempfile

source = (Path(__file__).resolve().parents[1] / 'src/main/java/androidx/media3/mpvplayer/MpvPlayer.java').read_text()
start = source.index('    static boolean shouldUpgradeForHdr(')
opening = source.index('{', start)
depth, end = 1, opening + 1
while depth:
    depth += (source[end] == '{') - (source[end] == '}')
    end += 1
method = source[start:end]

java = '''class HdrDirectTest {
''' + method + '''
static boolean up(String gamma, boolean hdr, boolean embed, boolean sub, int decode, boolean loaded, boolean surface, boolean external, int sid) {
    return shouldUpgradeForHdr(hdr, embed, sub, decode, loaded, surface, external, sid, gamma);
}
static void check(boolean expected, String what, boolean actual) {
    if (expected != actual) throw new AssertionError(what + ": expected " + expected + " got " + actual);
}
public static void main(String[] args) {
    check(true,  "pq upgrade",       up("pq", false, false, false, 1, true, true, false, -1));
    check(true,  "hlg upgrade",      up("hlg", false, false, false, 1, true, true, false, -1));
    check(false, "sdr bt.1886 stays", up("bt.1886", false, false, false, 1, true, true, false, -1));
    check(false, "already direct",   up("pq", true, false, false, 1, true, true, false, -1));
    check(false, "embed disabled",   up("pq", false, true, false, 1, true, true, false, -1));
    check(false, "subtitle gpu",     up("pq", false, false, true, 1, true, true, false, -1));
    check(false, "software decode",  up("pq", false, false, false, 0, true, true, false, -1));
    check(false, "not loaded",       up("pq", false, false, false, 1, false, true, false, -1));
    check(false, "no surface",       up("pq", false, false, false, 1, true, false, false, -1));
    check(false, "external subs",    up("pq", false, false, false, 1, true, true, true, -1));
    check(false, "selected sid",     up("pq", false, false, false, 1, true, true, false, 3));
    check(false, "decode 2 already direct", up("bt.1886", false, false, false, 2, true, true, false, -1));
    System.out.println("HDR_DIRECT_OUTPUT_GATE_TESTS_PASS");
}}
'''
with tempfile.TemporaryDirectory() as directory:
    file = Path(directory) / 'HdrDirectTest.java'
    file.write_text(java)
    subprocess.run(['javac', str(file)], check=True)
    subprocess.run(['java', '-ea', '-cp', directory, 'HdrDirectTest'], check=True)
