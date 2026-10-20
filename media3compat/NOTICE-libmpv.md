# libmpv Android native components

This module reproducibly builds mpv-android and its FFmpeg/libmpv dependencies from pinned source
revisions. No prebuilt mpv or FFmpeg binaries are downloaded.

- Project fork: https://github.com/zyqfork/mpv-android
- JNI source revision: `46ef59a1f093b30e774f463d5c5942a3ac8d22be`
- Upstream base revision: `3018d47277d5b3ca02acdd96466f261c1d23ee08`
- mpv source revision: `mpv-player/mpv@8c67647b50059406c5c0444903597281b81516cf`
- FFmpeg source revision: `FFmpeg/FFmpeg@894da5ca7d742e4429ffb2af534fcda0103ef593`
- SPIR-V compiler: `KhronosGroup/glslang@1062752a891c95b2bfeed9e356562d88f9df84ac` (15.1.0)
  (licenses and third-party notices: upstream `LICENSE.txt`)
- mpv-android application/JNI code: MIT License
- mpv and linked libraries: see the corresponding upstream source trees and release notes

The complete native build entry point is `scripts/build_libmpv_android.sh`.

The pinned overlay also selects `CLOCK_MONOTONIC` on Android, matching
`AudioTimestamp.nanoTime`. Upstream's Linux `CLOCK_MONOTONIC_RAW` preference otherwise
mixes clock domains in AudioTrack playback-head extrapolation, producing a false
output delay even when mpv reports near-zero `avsync`. Other platforms keep their
existing clock selection. Regression check (host C compiler required):

```sh
python3 other/tests/test_mpv_android_clock.py /path/to/mpv/osdep/timer-linux.c
```
