# 上游功能缺口审计（2026-09-20）

> 历史审计记录：下文各节描述对应日期的状态，不代表当前 HEAD；后续修订见文末。

## 结论

当前 fork 并不是简单落后于上游：上游运行时依赖一个未提交到 Git 的 `app/libs/lib-*.aar`（由 `app/libs/.gitignore` 明确忽略），其中包含定制的 Media3、MPV 和磁盘预载 API。本 fork 的 `media3compat` 是公开源码兼容层。因此，直接复制上游 Java 代码会遇到私有 API 缺失，必须逐项用公开 API 重写。

本轮优先补齐了会造成“设置存在但功能无效”或明显行为错误的项目。大型音视频效果系统建议单独迁移，不应混入修复提交。

## 本轮已补齐（高优先级）

1. **真实 EXO 音量增益**
   - 旧实现把 `1.5f` 传给 Media3 `setVolume`，但 Media3 会钳制到 `0..1`，实际无增益。
   - 现使用 Android `LoudnessEnhancer` 绑定 EXO audio session；MPV 继续使用原生 volume。
   - audio session 变化时重建 effect，关闭或释放播放器时释放资源。

2. **下一集磁盘预载**
   - 原 `DiskPreloadManager.start()` 是空实现，UI 虽有预载设置但不会下载。
   - 现用 Media3 1.10.1 的公开 `DefaultPreloadManager` + `PreloadStatus.specifiedRangeCached(start, duration)` 实现磁盘预载，并复用播放器 `SimpleCache`；不再依赖包私有 `PreCacheHelper`。
   - VOD 控制器在当前集 READY 后预载正序/倒序方向的下一集；播放时由普通 `CacheDataSource` 消费缓存，不依赖私有 MediaSource hand-off API。
   - 预载失败不影响正常播放，切换源或重置时会清理任务。

3. **外部字幕字体**
   - 旧实现用文件名作为 MPV `sub-font`，文件名和字体内部 family 不一致时会失效。
   - 新增有边界检查的 TTF/OTF/TTC `name` 表解析，导入时校验真实字体并限制 32 MiB。
   - MPV 使用内部 family；播放字幕弹窗已接入系统字体选择器，不再是空回调。

4. **已确认的行为修复**
   - Leanback 列表仅在到底时加载下一页。
   - VOD 倒序播放时按正确方向预载。
   - seek 到结尾时由调用方获知已到末尾。
   - CI Android SDK、原生构建 stdout/stderr、x86_64 FFmpeg JNI 构建目录问题已同步修复。

## 中优先级（本轮已实现）

> 下方每一项先保留原始缺口描述，再记录本轮落地的实现。构建与设备验证状态见文末「验证记录」。

### 1. 预载生命周期和可观测性

原缺口：

- 预载完成、取消、错误指标及调试日志；
- 网络类型、按流量计费和低存储空间策略；
- 自动化测试：本地 HTTP 服务提供两集 HLS/MP4，验证正序、倒序、切集命中和清理；→ 已落地 `other/tests/device/preload_e2e.py`（带 Range 支持的本地 HTTP + `adb reverse`），五场景真机跑通，见文末「验证记录」。
- 评估预载 manager 释放时清除缓存跨度的行为，必要时改为独立 `CacheWriter` 以精确控制保留策略。

本轮实现：

- **指标与日志**：`PreloadDiagnostics` 增加 `reset()` / `summary()` / `cachedBytes(MediaItem)`。计数覆盖 started / completed / cancelled / failed / skipped，跳过时记录原因（`disabled`、`unsupported_scheme`、`network_metered`、`low_storage` 等），全部同时写入 `VodPreload` logcat tag。
- **命中判定可量化**：`cachedBytes()` 用 `CacheKeyFactory.DEFAULT` + `DataSpec` 计算真实 cache key，累加 `SimpleCache` 中该资源已落盘的 span 字节数。由于正常播放的 `CacheDataSource` 写通道是关闭的（`setCacheWriteDataSinkFactory(null)`），**字节数 > 0 即等价于“下一集确实在磁盘上”**，不再依赖耗时猜测。
- **策略分层**：`PreloadPolicy` 区分硬约束与用户可选项。硬约束始终阻止——网络断开/未验证（含门户网络）、可用空间 < 256 MiB、后台数据被系统限制；用户可选项——按流量计费网络默认阻止，新增设置项「按流量计费网络也预载」可放开。允许计费网络**不会绕过**未验证网络检查。另加 `setTestBypass(boolean)`，仅供 `app/src/debug` 自动化使用。
- **释放语义与优先级**：`DiskPreloadManager.release()` 释放 preload manager 与在途 source，但 `SimpleCache` 由应用持有，已下载 span 会保留；淘汰交给其 LRU（容量取自用户预载容量设置），因此**不需要额外 `CacheWriter`**。数据源接入 `PriorityDataSource.Factory`，任务开始/结束时成对注册、注销 `C.PRIORITY_DOWNLOAD`；补 `released` 标记保证幂等，且释放后不再回调。
- **UI**：手机 `SettingPreloadFragment` 与电视 `SettingPreloadActivity` 新增「按流量计费网络也预载」开关与「预载诊断」入口（`PreloadDiagnosticsDialog`：策略判定 + 计数器 + 最近一次事件）。
- **自动化测试**：新增 debug 源集 `PreloadSmokeActivity` 与 `other/tests/device/preload_e2e.py`（本地 Range HTTP 服务 + `adb reverse`），覆盖 forward / reverse / retained / cancelled / failure 五场景，并报告 `cachedBytes`、生命周期计数与切集就绪耗时。

### 2. 字体集合与字体元数据

原缺口：当前解析器可从 TTC 中取得可用 family，但只返回第一个字体。若用户需要 TTC 子字体选择、localized family 或 variable font 实例，需要更完整的 OpenType 元数据模型。普通 TTF/OTF 使用已满足。

本轮实现：

- **解析**：`FontFamilyParser` 返回 `Face` 列表，含 TTC 内 face 序号、family / subfamily / PostScript 名、是否可变字体，以及 `fvar` 命名实例；`name` 表选择按当前系统语言（中/日/韩/英）加权。
- **选择**：`ExternalFont.getAll()` 展开为「每个 face 一行」，TTC 显示 `文件名 · 字体族`，可变字体附带实例名。
- **持久化**：选择结果按 **path + faceIndex + family** 三元组保存（`SubtitleSetting.putFontSelection`）。此前只存路径，重新解析永远只能取到 TTC 第一个 face，多 face 选择会静默丢失。
- **应用**：MPV 使用已保存 family（`getFontFamily()`）而非重新解析文件；Exo / Media3 通过 `SubtitleSetting.captionStyle(Context)` 把 `Typeface` 合入 `CaptionStyleCompat` 再 `SubtitleView.setStyle()`，使自定义字体在 Exo 侧同样生效。该方法是字幕样式的唯一来源，播放页与字幕弹窗共用，改字体后立即生效。

### 3. 播放错误恢复与 session 拆分

原缺口：上游有 `ExoPlayerSession`、`ExoSubtitleController` 和更完整的预载交接逻辑。当前 fork 的播放器管理器体积较大，但现有功能可用。建议以可测试的小提交拆分，不建议整体覆盖。

本轮实现（按“小而有界”落地，未整体覆盖 `PlayerManager`）：

- 新增 `PlaybackRecoveryPolicy`：纯逻辑错误码决策表，输出 `SEEK_DEFAULT` / `SWITCH_DECODE` / `RETRY_FORMAT` / `RETRY_TRANSIENT` / `FATAL`。
- **有界重试**：`MAX_ATTEMPTS = 2`。旧实现里解析类错误的重启是无界的，源站持续返回坏容器会无限重试。
- **瞬时错误不再致命**：`IO_NETWORK_CONNECTION_FAILED` / `IO_NETWORK_CONNECTION_TIMEOUT` / `IO_BAD_HTTP_STATUS` / `TIMEOUT` 改为带退避（500ms → 3s）重试；此前这些错误码落到 `default` 分支直接报致命错误。
- **预算复位**：`PlayerEngine.resetErrorBudget()` 在播放到达 `STATE_READY` 时调用，因此「卡一下 → 恢复 → 再卡一下」不会立刻致命，而真正的死源仍会在 2 次后停止。
- 新增 `ExoPlayerSession`，集中持有单实例 ExoPlayer、预载、音量增益、恢复预算与延时重试；`ExoPlayerEngine` 收敛为 session facade。`start()` / `release()` / `rebuild()` / `stop()` 会取消挂起的重试回调。
- `PlayerManager` 创建引擎后立即应用持久化音量增益，修复进程重启后设置存在、效果却要再次操作才生效的问题。

### 中优先级仍待补齐

- **带字幕视频的字体观感确认**：真机端到端预载已跑通（见文末「验证记录」），字体只剩「改完看画面」这一步需要人工确认。
- **`ExoSubtitleController` 拆分**：会话生命周期已抽为 `ExoPlayerSession`；字幕仍通过公开 Media3 `SubtitleView` 与 `PlayerManager` 管理。上游控制器依赖私有 libass API，后续只能按公开 API 逐步拆分，不能直接复制。
- **TTC 子字体在 MPV 侧只能按 family 命中**：libass/fontconfig 按 family 名解析，无法表达 face 序号；同一 family 存在多个 face 时结果由 fontconfig 决定。Exo 侧已按 faceIndex 精确生效。
- **HLS 命中判定**：`cachedBytes()` 对 MP4 精确；HLS 的 cache key 是分段 URI，只能覆盖清单，脚本已注明优先用 MP4 验证。

## 仍值得移植的上游功能清单

以下项目未纳入本轮中优先级实现；状态按当前 fork 与 `upstream/fongmi` 的静态差异归类，移植前仍应逐提交核对，避免覆盖 fork 自有逻辑。

| 优先级 | 上游能力/代表提交 | 建议 |
| --- | --- | --- |
| 高 | 本地文件服务与导入加固 `899f7d7d8` / `5ecd6595f` | 优先移植路径规范化、URI/文件名校验、目录穿越防护；与播放器耦合小，安全收益高 |
| 高 | 文件系统操作加固 `b3b4c2478` | 拆分审查 `FileUtil` / `Path`，补越界、原子写入及失败清理；不要整文件覆盖 fork 的 cache 路径 |
| 高 | 数据库备份分离 `dca422818` | 当前 fork 已有 `BackupManager`，应做语义 diff，择取原子备份、恢复失败回滚与 UI 状态处理 |
| 高 | QuickJS Global 生命周期 `c11a72118` | 可降低 crawler 全局对象泄漏/竞态；需对现有 spider 回归，适合独立提交 |
| 中 | 播放历史序列化/恢复 `2841ddfc9` | 价值高但触及 History、VOD controller、同步与两套 UI，应配迁移和旧数据兼容测试 |
| 中 | MediaSession artwork / 会话恢复 | 可改善 Android 系统媒体面板与重启恢复；能用公开 Media3 Session API 实现，不复制私有 AAR 类 |
| 中 | DNS-over-HTTPS 校验与延迟初始化 | 减少坏 DoH 配置拖慢启动；需保留 fork 当前 DNS 设置和失败回退 |
| 中 | VOD/live DataSource 与播放结果模型拆分 | 有利于测试和复用，但改动面大；建议先抽纯数据解析，再迁移控制器 |
| 中 | 字幕直链下载、压缩包与命名完善 | 当前 fork 已有 `SubtitleApi` / `Download` / `SubtitleArchive`，只择取上游新增格式、失败提示和安全校验 |
| 低 | 播放速度 preset `2326cf15f`、消息位置 `ec4ba2083`、离开前列表置顶 `13ac4327e` | 小型 UX 改进，可按需求独立 cherry-pick/手工移植 |
| 已有/无需重复 | 直播 cookie、MPV 伪装 HLS 重试、DV7 HEVC fallback、老固件退后台 | 当前 fork 已存在等价实现，应只做行为回归，不重复复制 |

## 不建议立即照搬（低优先级/大改造）

上游新增了完整效果系统：

- 多段 EQ、声道模式、平衡、中心声道、人声增强；
- 响度归一化、动态稳定、limiter；
- EXO PCM `AudioProcessor` 和 MPV filter 双实现；
- 色调、细节、着色器视频效果与 preset/profile UI。

这些代码规模大、CPU/耗电/延迟风险高，并依赖私有 Media3/MPV AAR 中的扩展（例如 MPV audio mix）。除非用户明确需要 EQ、响度归一化或画质调节，否则不应作为基础兼容修复移植。若移植，应先做基准测试并分为“音频效果”和“视频效果”两个独立里程碑。

## 验证记录

- Mobile/Leanback arm64 release 完整构建成功，包含 javac、lintVital、R8、签名和打包。
- 两台设备安装成功并可启动，无 crash/ANR/`VerifyError`/`NoSuchMethodError`。
- 手机 EXO 直播播放时，`dumpsys media.audio_flinger` 显示当前 app audio session 上存在且启用 `Loudness Enhancer` effect，证明 1.5x 增益不再被 Media3 钳制掉。
- `FontFamilyParser` 对 `DejaVuSans.ttf` 返回 `DejaVu Sans`；手机系统文件选择器成功导入字体。
- MPV 直播和统一暂停层此前已在 TV 设备验证。
- VOD 端到端预载此前受视频源请求超时影响；本轮已用可控双集测试源（本地 Range HTTP + `adb reverse`）补齐命中测试，见下方「本轮（中优先级）验证状态」。

### 本轮（中优先级）验证状态

构建机 `zyq@192.168.42.153` 已恢复访问。此前的「不可达」结论是误判：服务端只接受 ed25519，
而该密钥在 **Windows OpenSSH agent** 里，MSYS/Git-Bash 的 `ssh` 用不了这个命名管道；本轮改用
PowerShell 下的 Windows OpenSSH。最终验证在独立 worktree
`/data/home/zyq/IdeaProjects/github/FongMi/TV-review` 中进行，未触碰服务端主树在途改动。

**1. 编译（全部通过）**

- `:app:compileMobileArm64_v8aDebugJavaWithJavac` 成功。
- `assembleMobileArm64_v8aDebug` / `assembleMobileArm64_v8aRelease` / `assembleLeanbackArm64_v8aRelease` 三个变体 `BUILD SUCCESSFUL`，含 lintVital 与 R8 压缩。
- 踩坑记录：Chaquopy 需要 PATH 里有 `/data/home/zyq/.local/bin`（`python3.10` 由 uv 安装在此），
  否则 `:chaquo:installArm64_v8aDebugPythonRequirements` 报 `Couldn't find Python 3.10` 并在 Java 编译**之前**失败。

**2. 预载端到端（`other/tests/device/preload_e2e.py`，手机 Redmi Note 8，10 秒预载窗口）**

| 场景 | completed | failed | cachedBytes | hit |
| --- | --- | --- | --- | --- |
| forward（ep1 播、预载 ep2） | 1 | 0 | 234,921 | ✅ |
| reverse（ep2 播、预载 ep1） | 1 | 0 | 846,223 | ✅ |
| retained（重复 forward，验证不重复下载） | 1 | 0 | 234,921 | ✅ |
| cancelled（唯一 cache key，1 ms 后取消） | 0 | 0，cancelled=1 | 0 | ✅ |
| failure（404 下一集） | 0 | 1 | 0 | ✅ |

`cachedBytes` 均**小于**对应文件体积（ep2 = 340,733 B，ep1 = 1,228,696 B），说明预载窗口确实被裁剪，
不是把整集抓完。正常播放只读缓存（`CacheWriteDataSinkFactory(null)`），因此该字节数即「预载已落盘」。
脚本此前的 `--ei` 传参有 bug：`waitMs`/`durationMs` 在 Activity 里按 long 读取，
`--ei` 存入的是 Integer，`getLongExtra()` 会静默回退默认值，已改为 `--el`。

**3. 设备 UI（电视 rk3588，leanback release）**

- 设置 → 播放设置 → 预载设置：开启预载后，「按流量计费网络也预载」由 关→开→关 切换正常。
- 「预载诊断」弹窗正常渲染，电视实测输出：
  `policy=allowed (allowed) / meteredAllowed=false / duration=120s / threads=1 / started=0 completed=0 cancelled=0 failed=0 skipped=0 / last=idle`。
- 设置 → 播放设置 → 字幕字体：弹窗列出「系统默认 / 导入字体…」，标签走 `Entry.name()` 路径正常。

**4. 逻辑自检（`app/src/debug/.../SelfCheckActivity.java`，手机实测 `RESULT passed=39 failed=0`）**

预载测试覆盖不到的三块逻辑，用 debug 自检 Activity 补齐：

- `PlaybackRecoveryPolicy` 全部 18 条判定，重点是两个真实回归：
  `container_malformed_exhausted=FATAL`（解析错误重试**有界**，旧代码会无限重试）、
  `net_timeout_first=RETRY_TRANSIENT`（瞬时网络错误改为重试，旧代码落到 FATAL）。
  另含退避 `500 → 1000` 且封顶 3000 ms。
- 字体选择往返：用真机上的多面 TTC `/system/fonts/NotoSansCJK-Regular.ttc` 取 face index 4
  （family `Noto Sans CJK HK`），`putFontSelection` 后 `getFontPath/getFontFaceIndex/getFontFamily`
  三者均与所选面一致 —— 这正是多面 TTC 丢失所选子字体的修复点。自检结束会**还原**用户原有选择
  （实测还原为 `/storage/emulated/0/TV/fonts/DejaVuSans.ttf`，face 0）。
- **字体确实作用到渲染**：同一句 cue 走真实 `SubtitleView` 渲染两遍 —— 默认样式 vs 叠字体样式 —— 比对位图。
  实测 `ink 854 / right 280`（默认）→ `ink 1007 / right 291`（DejaVu Sans），像素哈希与墨迹宽度都变，
  还原后哈希与基线完全一致。这条比「不抛异常」强：即使 typeface 被静默丢弃，`captionStyle()` 也照样不报错。

**仍未覆盖**：真人看画面确认字幕换字体的观感。渲染层的效果已由上面的位图比对证明，剩下的是主观确认，无法自动化。

**设备状态**：两个测试 Activity 都会在结束时还原用户设置。`SelfCheckActivity` 实测还原
`DejaVuSans.ttf`（face 0）；`PreloadSmokeActivity` 实测 `durationMs=60000` 跑完后回到 `timeSeconds=20`。
手机现已重置为出厂默认（`preload=false timeSeconds=120 metered=false`）。
`PreloadSmokeActivity --ez resetDefaults true` 可在需要时再次恢复出厂默认。

### 真机冒烟与资源占用（release 包，2026-09-20）

两台设备均装 **release** 包（`mobile-arm64_v8a` / `leanback-arm64_v8a`，versionName 5.5.6）。
方法：`am start -W` 冷启动 + logcat 过滤本进程 + `dumpsys meminfo` / `top` 采样。

| 指标 | 手机 Redmi Note 8（mobile） | 电视 rk3588（leanback） |
| --- | --- | --- |
| 冷启动 TotalTime | 530 ms | 165 ms |
| 空闲 PSS / CPU | 147–153 MB / 0% | 160–166 MB / ~16% |
| 播放中 PSS / CPU | 未测（见下） | 236–239 MB / 32–44% |
| 崩溃 | 无 | 无 |
| gfxinfo | — | janky 2.49%，50th 7 ms / 90th 17 ms |

- **电视空闲的 16% CPU 不是本轮的改动**：热点线程是 `RenderThread` 8.5% + `mali-cpu-comman` 2% +
  `mali-event-hand` 1% + `glide-animation` 1%，即首页动态壁纸的渲染开销。手机锁屏无渲染时为 0%。
- **电视播放走 MPV**（日志 `V mpv :`），稳定态 PSS 波动 <1%，无泄漏迹象。
- 启动瞬间有 `Audio device underrun` / `Audio/Video desynchronisation`，但**稳定后 20 秒窗口内
  underrun / desync / buffering 计数均为 0**，属起播缓冲（`End buffering (waited 1.27s)`），非持续问题。
- 唯一真实错误来自**视频源本身**，与本轮改动无关：`JSONException: Expected literal value at character 0 of
  /movies/139277/@folder`、`Value <html> ... cannot be converted to JSONObject`、`SocketTimeoutException: timeout`。
  该源当前返回 HTML/路径而非 JSON，导致点播详情页显示「这里什麽都没有」，首页也因此退化成空状态。
- **手机端已补测**：通过同一条 adb 命令完成唤醒、解锁和启动后，设置页、预载页、诊断弹窗均正常；EXO 直播成功进入 MediaCodec 播放，进程无崩溃。`dumpsys media.audio_flinger` 可见 app session 上启用的 `Loudness Enhancer`。
- 新增 `other/tests/device/playback_probe.py`：本地起带 Range 的 HTTP 服务 + `adb reverse`，用于触发播放并采样 PSS/CPU/audio_flinger；`HomeActivity` 不处理冷启动 `ACTION_VIEW video/*`，所以自动探针仍需已有播放服务或 debug 测试入口配合。

### 最终回归补充（独立 worktree `TV-review`）

- 三变体完整 APK 构建通过：mobile arm64 debug/release、leanback arm64 release；后续异步字体导入修正再次通过三变体 javac。
- `SelfCheckActivity`：**39/39 PASS**，覆盖恢复决策、退避、TTC 多面选择/持久化和真实 `SubtitleView` 位图差异。
- 预载 E2E 在接入 `PriorityTaskManager`、改用公开 `DefaultPreloadManager` 后复跑：forward `234921 B`、reverse `846223 B`、retained `234921 B` 均命中；唯一 key 的 cancelled 为 `cancelled=1/cachedBytes=0`；404 failure 为 `failed=1`。五场景全部 PASS。
- 手机 release：EXO 播放正常，`Loudness Enhancer` 存在；手机预载设置/诊断 UI 正常。
- 电视 release：MPV 日志确认 `Using hardware decoding (mediacodec)` 与 `playback restart complete`；预载诊断弹窗焦点可达。
- 字体导入的复制、校验和元数据解析已移到 `Task` 后台线程，避免最大 32 MiB 文件在 UI 线程造成 ANR；完成后仅在仍存活的 Fragment/Activity 上刷新标签或字幕样式。
- 双端最终日志均无 `FATAL EXCEPTION`、`VerifyError`、`NoSuchMethodError`。

---

## 2026-09-28 会话：手机端投屏接收端 + 首页入口行 + GSON 注解修复

**当次代码已推送到 `origin/release`（历史 HEAD `9d77e3696`，13 个提交）。**
本地操作手册 `docs/AI-HANDOFF-2026-09-28.md` 不随此公开审计记录发布。

- **手机首页功能入口行**：两端入口定义合并到 `main/ui/home/HomeFuncs`（TV 的 `HomeActivity` 改为调它），
  `bean/Func` 与 8 个 `ic_home_*` 图标从 leanback 移入 main；手机端新增 chip 行
  （`adapter_home_func.xml` + `HomeFuncAdapter`，插在 `fragment_vod.xml` 的 app bar 里）。
  真机验证：6 个 chip 渲染 + 搜索/收藏/推送跳转正常。
- **DLNA 接收端做成 flavor 无关**：新契约 `dlna/CastPlatform`（同名同包、按 flavor 各一份、5 个方法），
  `DLNARendererService`/`DLNAAvTransportImpl`/`DLNARenderingControlImpl`/`CastAction`/
  `CastConflict`/`CastNetworkWatcher`/`RenderState` 移入 `main`，manifest 的 service 条目同步迁移。
  leanback 那份是原行为纯转发。
- **AirPlay 接收端**：`AirPlayServer`/`AirPlaySetting` 移入 `main`；`:airplay` 由
  `leanbackImplementation` 改为 `implementation`（手机包 +8.2MB：78.2 → 86.4MB）；
  手机 `HomeActivity` 随 app 启动两个接收端，设置页新增 `DLNA 设置` 与 `AirPlay` 开关（默认均开）。
- **DLNA 接收端端到端验证（本会话最有价值的一条）**：新增 PC 侧最小控制端
  `Release/device-test/dlna_controller.py`（M-SEARCH → 解析设备描述 → SOAP `SetAVTransportURI`+`Play`）
  与带 Range 的 `media_server.py`，媒体经 `adb reverse` 从手机 localhost 取（PC 不需任何入站端口）。
  实测：发现手机（`jUPnP/3.0`/`Xiaomi Redmi Note 8`）→ SOAP 200 → 服务器日志 `206` →
  手机 `state=PLAYING(3), position=5880` → logcat `START … VideoActivity`。**全链路打通**。
- **R8/GSON 注解修复**：`NetworkStorage`/`DlnaPin`/`NetworkEntry`/`NetworkCredentialStore.Credentials`/
  `SecondarySubtitleStore.Entry` 补 `@SerializedName`（此前 release 包字段名被混淆 → 升级后老数据静默丢失，
  且明文凭据迁移逻辑在 release 里是死代码）。真机前后对比验证。
- **当次未验证**：TV（leanback）侧全部行为（盒子离线）、AirPlay 端到端（无 Apple 设备）。

## 2026-10 后续修订

- 播放器改动已由 `12bea6f6c` 发布：网络字幕异步导入与迟到回执隔离、MPV 两档用户解码及保守直播输出路由、mpv.conf BOM 解析、EXO RTMP 依赖和 PNG-TS 错误后探测。旧直投字幕位图桥与无效 DV7 控件已移除；上文历史清单中的“DV7 已有”不再是当前状态。
- `cf2faff2f` 移除 AirPlay 高度超过 2160 时预先选择软解的规则，保留默认解码器启动失败后的软件回退；TV 详情页使用主题表面色。实验性的型号／AV1 解码器特判已撤销，从未随这两次提交发布。
- 软件解码选择、显式 mpv.conf 配置与通用错误回退仍保留。硬解不保证所有片源都不花屏；程序未加入按设备名称自动改为软件解码的规则。
- 解码链路与位深证据边界见 [MPV-ANDROID-DECODE-TRADEOFF.md](MPV-ANDROID-DECODE-TRADEOFF.md)。ARM64 TV／手机 release 构建和生产 AirPlay 模拟启动测试通过，但真实高画面 AirPlay、原网络花屏片源、真实直播输出切换及 10-bit 显示链路仍需分别验收，不能从点播或启动测试推断通过。
