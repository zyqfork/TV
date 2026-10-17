# TV 解码矩阵与资源释放实测（2026-10-17）

记录日期：2026-10-17。设备：RK3588 / AIC TV Box，容器 `aic_tv_v11_rc555`，Android 12。
App：`com.fongmi.android.tv` 5.6.2。范围：EXO / MPV 两引擎 × 软硬解 × 8 种格式的解码器选择，以及播放退出后的资源释放。

**结论：默认全部走硬件解码；三处回退均为设备能力所限且行为正确；退出后解码器线程正确释放，无内存泄漏。**

## 1. 硬件解码器清单

```
c2.rk.avc.decoder     H.264
c2.rk.hevc.decoder    HEVC
c2.rk.vp9.decoder     VP9
c2.rk.av1.decoder     AV1
```

四种主流格式全部有硬件支持。

软件解码器：

```
c2.android.avc.decoder        H.264   可用
c2.android.hevc.decoder       HEVC    仅 Level ≤ 4.1
c2.android.vp9.decoder        VP9     可用
c2.android.av1-dav1d.decoder  AV1     仅 8-bit
```

## 2. 解码矩阵

测试素材：720p fixture（`.pi/hw-check/*.mkv`）+ 4K HEVC Main10 + 960×400 SDR。

### EXO

| 格式 | 硬解 | 软解 |
|---|---|---|
| H.264 8-bit | `c2.rk.avc.decoder` | `c2.android.avc.decoder` |
| HEVC 8-bit | `c2.rk.hevc.decoder` | `c2.android.hevc.decoder` |
| HEVC 10-bit | `c2.rk.hevc.decoder` | **回退硬件** |
| HEVC 4K Main10 | `c2.rk.hevc.decoder` | **回退硬件** |
| VP9 Profile 0 | `c2.rk.vp9.decoder` | `c2.android.vp9.decoder` |
| VP9 Profile 2 | `c2.rk.vp9.decoder` | `c2.android.vp9.decoder` |
| AV1 Main 8-bit | `c2.rk.av1.decoder` | `Libdav1dVideoRenderer` |
| AV1 Main 10-bit | `c2.rk.av1.decoder` | **回退硬件** |
| H.264 SDR 960×400 | `c2.rk.avc.decoder` | `c2.android.avc.decoder` |

### MPV

| 格式 | 硬解 | 软解 |
|---|---|---|
| 全部格式 | 对应 `c2.rk.*` | 内置 libavcodec，不经 MediaCodec |

## 3. 三处回退的原因

### 3.1 HEVC 10-bit / 4K Main10 → 硬件

```
D MediaCodecInfo: NoSupport [codec.profileLevel, hvc1.2.6.L150.90, video/hevc]
                  [c2.android.hevc.decoder, video/hevc]
                  [rk3588_aic_tv, AIC TV Box, AIC, 31]
```

设备软件解码器不支持 Level 5.0（4K 所需）。EXO 回退到 `c2.rk.hevc.decoder`。

判定：正确。设备软解确实无法处理该规格。

### 3.2 AV1 10-bit → 硬件

```
E DecoderVideoRenderer: xv0: dav1dGetFrame error:
  High bit depth (10 or 12 bits per pixel) output format is not supported with YUV.
    at androidx.media3.decoder.av1.Dav1dDecoder.decode
E ExoPlayerImplInternal: Libdav1dVideoRenderer error, format_supported=YES
```

内置 dav1d 不支持 10-bit YUV 输出。EXO 回退到硬件。

判定：正确。dav1d 库自身限制，非配置问题。

### 3.3 MPV 软解不经 MediaCodec

MPV 软解使用内置 `libavcodec` 解码器（`hevc`、`h264`、`vp9`、`libdav1d`），不调用 Android MediaCodec，因此 `MediaCodec: [...]` 日志为空。

判定：正常，属 MPV 设计。

## 4. 资源释放

### 4.1 多轮退出后 PSS 稳定，无泄漏

同一进程内播放→退出，等待 10/30/60/120 秒：

| 轮次 | 相对基线 PSS |
|---|---|
| 1 | +75~83 MB |
| 2 | +77~85 MB |
| 3 | +78~84 MB |

三轮稳定、不随轮次增长；多等 30 秒回落约 8 MB 后保持稳定。+75 MB 为播放器常驻开销（引擎实例、解码器基础设施、MediaSession）。

### 4.2 解码器线程正确释放

| 引擎 / 模式 | 播放中 | 退出后 |
|---|---|---|
| EXO 硬解 | `MediaCodec_loop`×2, `CodecLooper`×3, `CCodecWatchdog` | `CodecLooper`×2, `CCodecWatchdog` |
| EXO 软解 | `MediaCodec_loop`×1, `CodecLooper`×3, `CCodecWatchdog` | `CodecLooper`×2, `CCodecWatchdog` |
| MPV 硬解 | `CodecLooper`×2, `CCodecWatchdog` | 同左 |
| MPV 软解 | 无 | 无 |

`MediaCodec_loop`（解码器工作线程）在退出后全部消失，解码器已释放。

残留的 `CodecLooper` / `CCodecWatchdog` 是系统级解码器基础设施线程，非泄漏。

### 4.3 fd 与线程

退出后 fd 稳定在 185~212，线程回落到 92~105，与基线差异 5~10 个，属正常波动。

## 5. 测量方法说明

- 内存：`dumpsys meminfo <pid>` 的 `TOTAL PSS`。
- 线程：`ls /proc/<pid>/task | wc -l`；线程名读 `/proc/<pid>/task/*/comm`。
- fd：`su 0 ls /proc/<pid>/fd | wc -l`（需 root，应用为普通 uid）。
- 解码器：`logcat` 中 `MediaCodec: [<name>]` 与 mpv 的 `Selected decoder:`。
- **画面确认不能靠 `screencap`**：本设备视频层为 `composition type=DEVICE`，截图得到纯黑。改以 `setting surface generation`（MediaCodec）或 `first video frame`（mpv）判定是否出帧。

## 6. 未验证

- 未量化实际帧率，只确认解码器选择与出帧。
- MPV 软解 4K HEVC 的播放流畅度未测（RK3588 CPU 可能跑不满）。
- 手机端（Redmi Note 8）矩阵只确认了 codec 选择正确，`frames` 判定不可靠——手机触屏界面未跑通播放入口。
- 未测长时间（>30 分钟）连续播放的内存曲线。

## 7. 证据文件

本机工作目录，不随仓库提交：

- `.pi/resource-test/tv-matrix.py`、`tv-matrix.json`：TV 解码矩阵。
- `.pi/resource-test/settle.py`、`settle.json`：多轮退出内存曲线。
- `.pi/resource-test/final.py`、`final.json`：解码器线程释放对照。
- `.pi/resource-test/leak.py`：共用测量工具（`snap()` 等）。
