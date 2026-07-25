# libmpv Android native components

This module downloads the pinned `2026-04-25` mpv-android release artifacts during the build and
packages their native libraries for `arm64-v8a` and `armeabi-v7a`.

- Project: https://github.com/mpv-android/mpv-android
- JNI source revision: `6deb01c`
- mpv source revision: `mpv-player/mpv@41f6a64` (libmpv 0.41.0)
- mpv-android application/JNI code: MIT License
- mpv and linked libraries: see the corresponding upstream source trees and release notes

The artifact URLs and SHA-256 checksums are declared in `build.gradle`. Corresponding native build
scripts are available in the mpv-android repository under `buildscripts/`.
