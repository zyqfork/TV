# libmpv Android native components

This module reproducibly builds mpv-android and its FFmpeg/libmpv dependencies from pinned source
revisions. No prebuilt mpv or FFmpeg binaries are downloaded.

- Project fork: https://github.com/zyqfork/mpv-android
- JNI source revision: `46ef59a1f093b30e774f463d5c5942a3ac8d22be`
- Upstream base revision: `3018d47277d5b3ca02acdd96466f261c1d23ee08`
- mpv source revision: `mpv-player/mpv@8c67647b50059406c5c0444903597281b81516cf`
- FFmpeg source revision: `FFmpeg/FFmpeg@894da5ca7d742e4429ffb2af534fcda0103ef593`
- mpv-android application/JNI code: MIT License
- mpv and linked libraries: see the corresponding upstream source trees and release notes

The complete native build entry point is `scripts/build_libmpv_android.sh`.
