# Android MPV decoding: pinned-source trade-off (2026-10-02)

This describes the pinned mpv dependency `8c67647b50059406c5c0444903597281b81516cf`, **not** a measurement of every TV display pipeline.

- `hwdec=mediacodec` with `vo=gpu` uses an `AImageReader` surface and imports its `AHardwareBuffer` as an `EGLImage` / external GL texture. It does **not** copy decoded video into CPU RAM. See `video/out/hwdec/hwdec_aimagereader.c`: `AIMAGE_FORMAT_PRIVATE`, `AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE`, `AImage_getHardwareBuffer`, `eglCreateImageKHR`, `glEGLImageTargetTexture2DOES`.
- That mpv GPU mapper advertises `IMGFMT_RGB0` and a four-channel UNORM texture. The pinned `DOCS/man/options.rst` explicitly warns that Android `mediacodec` forces RGB conversion and reduces 10-bit output to 8-bit when 10-bit is supported. This is a **GPU path limitation**, not evidence of CPU copy-back.
- `vo=mediacodec_embed` bypasses that GPU mapper and hands the Surface to MediaCodec. It may avoid mpv's RGB0 limitation, but **does not prove end-to-end 10-bit or HDR**: the codec, Android compositor, Surface format and display must each support it. Real measurements remain required.
- `mediacodec-copy` is a separate explicit hardware decoder mode that copies frames to system RAM; `mediacodec` does not silently become `mediacodec-copy` when the VO changes. Hard and direct modes set `--hwdec-software-fallback=no` unless `mpv.conf` already sets it, so a failed hardware decoder does not silently become software. Soft mode must leave that option unset: `hwdec=no` together with fallback `no` makes this libmpv force-EOF the video track. Verify `Using hardware decoding` or `Using software decoding` in the log, not the configured option alone.
- Copy-back green/glitchy frames have been reported on **both** an RK3588 box and non-Rockchip Formuler Z10 / Amlogic S905W2 devices ([mpv-android #853](https://github.com/mpv-android/mpv-android/issues/853), [#1088](https://github.com/mpv-android/mpv-android/issues/1088)). These reports do not establish a Rockchip decoder's stride, tiling or crop metadata as the root cause; the previous Rockchip-specific explanation in `MpvPlayer` was too strong. No controlled copy-back vs GPU vs embed run with the same owned 10-bit file and pixel/bit-depth capture exists for our two devices. Do not enable copy-back automatically on that assumption.

Policy: hard VOD uses MediaCodec + GPU for subtitle/effect composition; eligible subtitle-free live may use embed. Explicit conflicting `mpv.conf` hwdec/VO/GPU settings disable automatic embed, without deleting the user's override. A user who intentionally opts into `mediacodec-copy` remains able to do so via `mpv.conf`, at their own compatibility risk. Soft remains an explicit user mode. No model-specific AV1 blacklist or automatic AV1 software override is shipped. See `Release/device-test/mpv_decode_tests.py` for **passive**, per-session native-log criteria; historical multi-source logs do not establish a specific session's output or display bit depth.

## Follow-up: corrupted AV1 frames and AirPlay startup

- The affected device reproduced corrupted AV1 frames through the same Android MediaCodec decoder in both EXO and MPV. An owned AV1 sample displayed correctly with software decoding. This narrows the problem to that hardware decoding/output path, but does not establish a chip defect rather than a driver or firmware defect.
- The experimental model-specific AV1 exclusion was removed before publication. Users can explicitly select software decoding for affected content; normal codec failure fallback remains. Corrupted frames do not necessarily produce an error, so an automatic error fallback cannot reliably detect them.
- The affected network source advertises DASH MIME. The existing DASH-to-EXO route is independent of the corrupted-frame issue; it was not removed. The original network source still needs end-to-end revalidation.
- AirPlay no longer bypasses Android's default decoder solely because `videoHeight > 2160`. It first attempts the default decoder (normally hardware) at the requested dimensions. Software is queried only after configure/start fails, or after actual source input cannot make first-input/first-output progress within the startup watchdog window. No model/vendor lookup or sender resolution change was added in these follow-ups; the older service-level portrait size hint is a separate policy.
- Stalled-start recovery caches at most 8 MiB / 256 packets before the first output. It verifies cached parameter sets and a random-access picture, then starts software immediately and replays the complete cached input sequence with its original timestamps. It does not require the sender to issue another keyframe. Software choice remains latched through codec errors until dimensions, codec type or session change. Actual resolution changes cancel old input/replay and rebuild the decoder for the new parameter sets; repeated reports of the same dimensions preserve the choice. First output and codec/session reset release the cache.
- A dedicated, single-owner codec worker handles startup, input/output and replay. UI surface controls never hold a codec lock, and session reset/release invalidate a generation immediately and defer cleanup to the worker. The native receive callback retains one-frame backpressure instead of filling an unbounded frame queue, and polls cancellation so teardown can leave promptly even when a codec call is still blocked. The idle worker expires, and the renderer remains reusable after release for server restart. Service teardown ignores late video/size callbacks.
- Recovery is bounded, not a guarantee for every stream: incomplete/oversized startup input or unavailable software leaves the current decoder intact and logs that reconnection is required; software startup/replay failure uses the normal codec error path. Replay feeding has a three-second total budget and checks session cancellation between codec calls. This does not preempt a vendor codec call that itself hangs; UI controls still do not wait for that worker. A second software stall does not trigger an endless restart loop. The watchdog concerns first output only, not every later freeze.
- The production AirPlay regression uses Android test doubles and covers tall dimensions, lazy software queries, failed-codec release, AVC/HEVC recovery through `feedFrame`, packet/timestamp replay, software-choice persistence, resolution/session reset, permanent hardware input-buffer unavailability, idle-source guards, blocked-replay UI responsiveness, cancellation/late-input isolation, reusable release and replay time limits. Previous ARM64 TV/mobile release builds passed. These tests are **not** a real AirPlay session or pixel-quality validation; real sender/device revalidation remains required.
- Both detail pages paint their root view with `CustomWallView.readableBackdrop(Setting.getWallColor())` — the colour the wallpaper-backed pages resolve to, so the page follows the user's colour instead of the theme background. Playback pages skip the wallpaper layer (`PlaybackActivity.customWall()` returns false: no image decode, and never a video behind a video), which is why the colour has to be applied by hand. The video container, inside the page, retains its black background. An earlier revision had the TV page paint `colorSurfaceVariant` while the phone set no background at all; reverting that only uncovered the theme `windowBackground` (`colorSurface`, a flat near-black), and the TV flavour was in fact missing the backdrop call the phone had always made. Both now paint the root, and the phone's call moved to the root so it also covers the strip behind the collapsed video.

## 后续：2026-10-08 软解黑屏、直播暂停、硬解定格

下面每条都是当时看到的现象、改了哪一段、以及打算怎么收场。没有改解码策略：仍然不自动使用 `mediacodec-copy`，点播硬解仍然是 `hwdec=mediacodec` + `vo=gpu`，符合条件的无字幕直播才用 `mediacodec_embed`。

### 软解点播有声音、画面是黑的

软解会把 `hwdec` 设成 `no`。硬解为了避免悄悄变成软解，原先无条件写入 `hwdec-software-fallback=no`。这版 libmpv 在「没有使用硬解」并且 fallback 为 `no`（内部值 `INT_MAX`）时，把视频轨标成结束：日志是 `No hardware decoding requested`、`Software decoding fallback is disabled`，然后 `playback restart ... video=eof`。声音继续，Surface 保持黑的，视频包堆在 demuxer 里。大约 30 秒首帧超时后，看起来像播放失败。

`MpvUtil.addApplicationOptions` 只在 `decode != 0` 且 `mpv.conf` 没有自己写这个键时才加 `hwdec-software-fallback=no`。软解沿用 libmpv 默认，日志变为 `Using software decoding`。

手机和电视都用本地 `fongmi_soft.mp4` 看过：MPV 软解有画面；手机 EXO 软解是 `c2.android.avc.decoder`，也有画面；电视 EXO 软解同样有画面。

### 有尺寸但首帧回调没到，画面仍被盖住

`PlayerView` 的 FIT 封面和 `exo_shutter` 会盖在 Surface 上。软解的缓冲输出，以及 MPV 在监听挂上之前就已经画出的第一帧，都可能不走到 `onRenderedFirstFrame`，于是盖层一直是黑的。

`PlaybackActivity.revealVideo()` 在拿到非零视频尺寸后关掉封面并隐藏 `exo_shutter`。非直出的 MPV 若已有宽度且播放位置移动超过 1 秒，`MpvPlayer.checkBlackScreen` 直接记为首帧，不再把这次误报成 30 秒超时。真的没有视频尺寸时，超时仍然成立。

### 直播点了暂停却继续播

直播按钮和双击看的是 `player().isPlaying()`。缓冲期间它是 false，于是控件去调用 `play()`，暂停不会发生，图标也停在播放。电视确定键仍要打开频道列表，不能改成暂停。另外直播 MPV 的 `demuxer-max-back-bytes=0` 会丢掉暂停后唯一能接着播的数据。

手机和电视的 `LiveActivity` 改为看 `controller().getPlayWhenReady()`，按钮图标跟着这个状态。电视媒体播放/暂停键经 `CustomKeyDownLive` 调 `onPlayPause()`，确定键仍是 `showUI()`。直播回退缓冲在低延迟时为 `2MiB`，否则 `8MiB`。

手机上实际播过一路 HLS：媒体键后日志为 `pause=yes`，再按恢复为 `PLAYING`，画面还在。

### 点播发顿、以及硬解失败后的空转

点播也套了 `framedrop=vo`，丢晚到的帧看起来就是一顿一顿。这个选项现在只留给直播；`video-sync=audio` 同样只用于直播。点播预读 `cache-secs` 继续跟播放缓冲设置，避免高码率远程文件只用 mpv 很短的默认预读。

硬解点播另有一种停住。2026-10-08 电视正在播的 HEVC 停在 4 分 23 秒，隔两秒截图是同一帧，进度也不走。日志被刷满：`hevc_mediacodec: Failed to dequeue output buffer (status=-542398533)`、`IllegalStateException`、`Error while decoding frame (hardware decoding)!`。解码器已经离开可执行状态，但 fallback 被关掉，libmpv 不会换解码器，播放循环就停在最后一帧上。这些错误若逐条丢到主线程，界面时钟也会被堵住。

`MpvPlayer.logMessage` 在 mpv 线程上累计这类重复错误，不逐条 post。连续 8 次且距上次处理超过 2.5 秒时，`recoverDeadHardwareDecoder()` 对当前这一集做一次重开：`vo=null`，重新套上当前的 `hwdec`，恢复 `vo`，再 `video-reload`。同一集里再死一次，上报 `ERROR_CODE_DECODING_FAILED`，由 `PlayerManager` 提示并切换解码，而不是再空转。播放时间一旦前进，连续计数清零；打开新文件时重试次数清零。这里不启用 `mediacodec-copy`，也不在 libmpv 内部悄悄改软解。

装上这版之后，电视本地 H.264 的 MPV 硬解从 2.6 秒走到 5.6 秒，两帧不同，日志是 `Using hardware decoding (mediacodec)`。原先定格的那一集被安装打断，没有在失败点之后再播一遍，所以还不能把「这一个源再也不会停」当成已经测完。

## 后续：2026-10-10 硬解卡死的真正原因（容器侧，已修复）

4K HEVC Main10 播几秒后画面永久卡死、声音继续、日志出现 `queue exceeded timeout`，
原因不在 App，而在容器的解码组件里：`/vendor/lib/libcodec2_rk_component.so`（2024-07-20 构建）
的 `C2RKMpiDec::ensureDecoderState()` 用

    count = mOutputDelay - 已占用缓冲数

决定还要提交几块解码缓冲。这份片源算出 `mOutputDelay = 6`，稳态时 `count = 6 - 6 = 0`，
组件一块都不再提交；而 MPP 的 HEVC 解析器还需要第 7 块给待处理的输出槽位，于是永久等待。
日志里 `required (3840x2176) ... fetch 0/0` 出现 227,543 次，MPP 缓冲组 8 块全占用、0 空闲。

Rockchip 上游在 2024-08-22 的提交 `b79cd040a292` 已修（`+ 1`），本容器比它早约 5 周。
修复是在容器里把两处 4 字节 `blo.w` 换成 `adds r6,#1 ; nop`，共 8 字节；
一键脚本和说明放在宿主 `/sata/starryos/cmcc-c2-fix/`，容器重建后重跑一次即可。

因此**删除了 App 里只为绕过这个卡死而加的代码**：

- `recoverDeadHardwareDecoder()`、`isDeadHardwareDecoder()`、`hwdecFailStreak` 及
  `HWDEC_RECOVERY_LIMIT` / `HWDEC_STABLE_MS` 重建预算：原本靠"检测到解码器死亡就重建、
  每文件最多 3 次、每次需 30 秒稳定窗口"来把卡死变成一次短暂停顿。真因修好后这只会让
  慢解码器被中途拆掉，所以移除。
- native 侧只为定位这个问题加的一次性诊断：`FONGMI_IMAGE_FENCE_LEDGER` 及其 fence 轮询、
  `FONGMI_IMAGE_SUBMIT` / `ACQUIRE` / `BUFFER_DIAGNOSTIC`、`FONGMI_HEVC_SPS` / `DPB`，
  以及只读的 buffer 格式 / `AImage_getCropRect` 记录。
- native 侧为缓解这个问题加的缓冲所有权绕过，**同样移除**：
  `FONGMI_IMAGE_COPY`（额外一份 10-bit 中间纹理，目的只是拷完就归还 codec buffer、
  少钉住解码槽）、`FONGMI_IMAGE_LIFETIME` / `FONGMI_IMAGE_UNMAP_HOLDS`，以及配套的
  `eglCreateSyncKHR` / `eglDupNativeFenceFDANDROID` 释放 fence 与 `AImage_deleteAsync`。
  现在 `mapper_unmap` 回到上游的 `AImage_delete`，每帧不再多走一次 FBO 拷贝。
  核验方式：对 pristine 固定版本源码跑补丁脚本，可干净应用且幂等；构建出的
  `libmpv.so` 里上述标记已全部消失。

**保留的**（都是独立问题，与卡死无关）：顶部白线的裁剪修复
（`FONGMI_MEDIACODEC_FRAME_CROP` + `FONGMI_IMAGE_STORAGE_SIZE`）、VO 帧信号
（`FONGMI_VO_FRAME_PTS`）、异步命令 JNI 与 `FONGMI_SUBTITLE_ABORT_PUBLISH`
（打开后取消不得发布旧请求）、以及 `MpvFirstFrameWatchdog`——它把无界挂起变成有界错误，
本身不掩盖故障。

实测（同一份之前 100% 复现的 596 帧原始码流）：独立解码从"13 帧后超时"变为
596/596 帧到达 EOS；MPV 内嵌/全屏/12 次连续 seek 均为 1 次硬解初始化、0 超时、0 软解、
0 次时间戳倒退。

## 后续：2026-10-10 壁纸配色回归，以及两端解码矩阵

### 壁纸配色被上一次"简化背景"改动抹平

`5e68d17c2`（简化壁纸背景）为了让背景固定，去掉了从壁纸取色，把 scrim 写死成
`black_70`（70% 黑）、窗口背景固定 `#141218`，并删掉了两个详情页根视图上的
`readableBackdrop()` 调用。结果是**任何壁纸都被压成同一层近黑**：电视首页实测背景从
`(46,71,26)` 掉到 `(24,37,13)`。同屏对照旧截图还能反推出当时用的 scrim 约 108/255，
而写死的是 179/255。

现在恢复成按画面亮度算 scrim：目标仍是白字 6:1 对比度、上限 204，算法与原实现一致
（`TARGET_CONTRAST` / `MAX_SCRIM`）。两点与旧实现不同，都是为了避免当初那个 bug：

- **不再用 Palette 取色**，改成把 WallConfig 已经写好的壁纸快照按 `inSampleSize=8`
  解码后取"较亮一半"的均值。整图均值会被暗角拉低，算出的 scrim 对白字所在的高光区
  不够；同时也不重新引入 `androidx.palette` 依赖。
- **内置壁纸的颜色表按 flavour 分开**放在各自的 `res/values/arrays.xml`。两个 source set
  在同一个 `wallpaper_N` 名字下是**不同的图**，这正是旧代码用硬编码表会漂色的原因。
  播放页仍然跳过壁纸层（不解码、不在视频后面再放视频），所以 `readableBackdrop()` 的
  调用在 `VideoActivity` / `LiveActivity` 的根视图上恢复。

实测：电视首页背景回到 `(46,71,26)`，与回归前截图逐像素一致；详情页背景变成壁纸绿
`(36,110,83)`，不再是主题的平灰；手机（Redmi Note 8）首页同样跟随壁纸。

### 两端解码矩阵（EXO + MPV）

`other/tools/codec_matrix.py` 在电视（RK3588）和手机（Redmi Note 8，Android 16）上跑
AVC 与 HEVC 各引擎组合，除"能播"之外还看资源侧：每个分配的 MediaCodec 是否都释放、
连续播放后活跃 codec 数是否增长、切引擎是否留下上一个实例。

结果 `problems=0`：全部走各自平台硬解（电视 `c2.rk.*`、手机 `c2.qti.*`），无泄漏。
同一份 596 帧 4K HEVC Main10 片段在电视上 MPV 播完：1 次硬解初始化、0 次
`queue exceeded timeout`、0 次 `Failed to dequeue output buffer`、0 次软解回退；
10 次连续 seek 后仍是 1 次硬解初始化、0 超时，`crop=3840x2160+0+4` 白线修复保持不变。

### 构建侧：手机包曾装进损坏的 libavcodec.so

一次并发构建（两个 Gradle 进程同时写同一个 strip 输出目录）产出的
`app/build/intermediates/stripped_native_libs/.../libavcodec.so` 节头被清零，
`.dynamic` 不可读。Android 16 的 linker 直接拒绝加载，报
`dlopen failed: ...libavcodec.so .dynamic section header was not found`，
于是 MPV 静默回退到 EXO——**表现是"手机上 MPV 不生效"而不是报错**。

判定与处理：同一 APK 里 leanback 的 `libavcodec.so` 哈希正常、mobile 的不同，且
`readelf -S` 显示该文件全部节头为空；删掉 `stripped_native_libs` / `merged_native_libs`
与旧 APK 后重建，两端 `libavcodec.so` 哈希恢复一致（`921e39e6...`），手机 MPV 正常。
**不要并发跑两个 Gradle 构建**；换 APK 后可用
`readelf -d <lib>.so | grep NEEDED` 快速确认 `.dynamic` 可读。

### 回归

33 个脚本中 32 个通过。唯一失败的 `test_airplay_decoder_start` 需要 `kotlinc`，本机没有；
它在**未修改的基线上同样失败**，与本轮改动无关。

### AV1 硬解错位：已确认与本次清理无关

清理后复测 AV1 时，电视的 `c2.rk.av1.decoder` 把竖条测试图渲染成**斜向彩条**（stride
错位），而源文件是纯竖条纹。这不是本次改动引入的：

- **同一条码流换 EXO 软解**（`c2.android.av1-dav1d.decoder`）画面完全正确，竖条纹逐段
  与源帧一致。
- **换回清理前的 APK**（含 `FONGMI_IMAGE_COPY`）走 EXO 硬解，同样是错位画面。EXO 根本
  不经过被清理的 mpv native 代码，所以两者无关。

手机侧 AV1 走的是软件 dav1d（`c2.android.av1-dav1d.decoder`），画面正常。

结论与上文第 13 节一致：这是该设备 AV1 硬解输出路径的问题，不是应用层能修的；需要时
用户可对这类内容显式选择软解。本次没有加入机型黑名单或自动软解。
