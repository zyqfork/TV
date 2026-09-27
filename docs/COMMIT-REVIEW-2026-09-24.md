# 提交复核报告 — 2026-09-24（release 分支）

复核范围：`8e32f2751` → `6a3e2db3d`（当日 6 个提交），并回溯 `6bfc1e6e8` / `1cab2b5ca` / `a6a88559d` 作为上下文。
复核方式：逐提交 diff 审读 + 全项目调用点反查 + 与提交前版本（`8e32f2751^`）对比。
未做实机验证，以下结论均为静态可验证事实（标注了文件与行号，可直接跳转核对）。

---

## 一、结论速览

| 提交 | 解决的问题 | 必要性 | 引入的新问题 |
|---|---|---|---|
| `6a3e2db3d` dav1d 换镜像 | code.videolan.org clone 失败 | 必要 | 无（会整体失效 native 缓存，属预期） |
| `e4dd2e000` 存储/UI | 把 `.txt` 推进播放器会崩；进页面卡顿 | 必要 | i18n 缺三语、白名单过严、删除语义危险 |
| `988303dc2` 搜索防抖 | 每次按键发一次 suggest 请求 | 必要 | 热门榜响应无序号保护，仍可与 suggest 竞态 |
| `4825abffb` DLNA 分页 | 分页时首屏不显示 | 方向正确 | 后续分页无进度提示；无 generation token |
| `623968778` MediaSession 防抖 | 系统日志刷屏 | 必要但实现偏弱 | 窗口内仍会立即发一次，节流效果有限 |
| `8e32f2751` 播放器/DLNA | 错误面板卡死、DLNA 空库误报、DLNA 发现慢 | **必要** | **2 个确定性回归**（见 P0/P1） |

**最严重的一条**：`8e32f2751` 删掉了 leanback 播放页 `showProgress()` 里的 `hideError()`，导致 `hideError()` 变成**没有任何调用点**的死方法（已用提交前后对比确认）。错误面板一旦显示就再也不会消失。

---

## 二、确认的缺陷（按严重度）

### P0 — leanback 播放页错误面板永久残留（`8e32f2751`）

证据（提交前 → 提交后 `hideError` 调用点消失）：

```
8e32f2751^  VideoActivity(leanback): 1153  hideError();   ← 在 showProgress() 内
            LiveActivity(leanback):  553  hideError();   ← 在 showProgress() 内
8e32f2751   VideoActivity(leanback): hideError 只剩定义（1171）
            LiveActivity(leanback):  hideError 只剩定义（568）
```

现在全项目 `hideError()` 的调用点只剩 `CastActivity` 与 **mobile** 两个播放页；leanback 的
`VideoActivity` / `LiveActivity` 已经没有任何地方能把 `widget.error` 置为 `GONE`。

触发链（leanback）：

1. 某一集/某个频道致命失败 → `resetPlaybackForError` → `showError(msg)`（`VideoActivity.java:557-560`）→ 错误面板 `VISIBLE`。
2. 用户换到**另一集**（同一 `VideoActivity` 实例，`prepareSource` 原地续播）→ `STATE_BUFFERING` →
   `showProgress()` 因 `error.getVisibility()==VISIBLE` **直接 return**（`VideoActivity.java:1151-1156`）→ 不显示加载圈。
3. `STATE_READY` → `hideProgress()` 只关进度条，`widget.error` 仍 `VISIBLE` → **旧报错文字永久叠在正在播放的画面上**。
4. 唯一出路是销毁 activity。

影响：一次播放失败后，后续所有集数都带残留报错、且加载态消失。`VodFallbackPolicy` 自动切线路成功后同样会残留。

修复建议：不要把 `hideError()` 塞回 `showProgress()`（那正是本次要避免的：“重试 BUFFERING 不能盖掉错误”），而应挂在
**明确的“重新开始播放”语义**上 —— 建议两处：
`VideoActivity.startPlayer()/prepareSource()` 入口处，以及 `onStateChanged(STATE_READY)`；
`LiveActivity` 同理（换台入口 + READY）。这样重试中的 BUFFERING 仍保留错误可见，换集后立即清空。

### P1 — MPV 首帧延长逻辑变成死代码（`8e32f2751`）

```java
// PlayerManager.java:787-796
private void onFirstFrameTimeout() {
    if (openReported || spec == null || isReleased()) return;   // ← 788 已把 openReported 为真短路掉
    if (engine.getType() == PlayerEngine.Type.MPV && openReported && firstFrameExtendCount < 3) { // ← 790 恒 false
        firstFrameExtendCount++;
        scheduleFirstFrameTimeout();
        return;
    }
    onPlayTimeout();
}
```

第 788 行已经 `return` 掉 `openReported == true` 的情况，第 790 行又要求 `openReported == true` → **条件恒假**。
提交把原来的 `getPosition() > 0` 换成了 `openReported`，而 `openReported` 只在这一处被置真
（`PlayerManager.java:1010`，且带 `liveMode` 条件）——live 时它一为真就被 788 行拦住，VOD 时它永远是 false。

后果：注释里明确写着的 “FFmpeg-backed MPV may inspect every rendition in an HLS master before the first frame”
所依赖的安全阀（最长 4×超时）被静默移除。直播 MPV 的首帧超时就是 `Constant.TIMEOUT_PLAY`(15s)
（`PlayerManager.java:907-913`），慢 HLS master 会误判“播放超时”。`firstFrameExtendCount` 也成了只写不读的字段。

修复建议：恢复一个与 788 行不冲突的判据，例如 `getPosition() > 0`，或把 `openReported` 的置真条件从
`liveMode` 扩展为“引擎已上报打开/位置前进”，并给 VOD/直播分别定义延长上限。

### P2 — 重试预算被同时砍半（`8e32f2751`）

```java
// PlayerManager.java:729-738
if (++sourceRetry > 1) { ... 致命; }   // 原来是 > 2
```

同一提交里 `ExoPlayerSession.retryTransientLater()` 改成**不再自己 post 重试**，只返回 `RETRY`
交给 `PlayerManager`（`ExoPlayerSession.java:151-161`）。两个改动叠加后，源级错误的恢复次数由
“内部 2 次 + 外部 3 次”降到**总共 1 次**。抖动型 IPTV/HTTP 源的容错明显下降，且提交信息未提及预算被改。

修复建议：把预算显式化（如 `MAX_SOURCE_RETRY = 2`）并在注释里说明它与 `PlaybackRecoveryPolicy.MAX_ATTEMPTS`
的关系；或让一次 `RETRY` 走内部指数退避而不是立刻致命。

### P3 — DLNA 分页缺少 generation token（`8e32f2751` + `4825abffb`）

`DlnaBrowseActivity.load()` 每次 new 一个匿名 `BrowseCallback`，`mAdapter` 是共享的，`DlnaMediaManager`
也不提供取消。打开大目录（`MAX_BROWSE_ENTRIES=2000`）后立刻返回并打开另一个目录时，
旧浏览链路仍在跑：

- 旧链路的 `onPage(firstPage=false)` → `mAdapter.addItems(...)` → **把旧目录的条目追加进新目录列表**。
- 旧链路的 `onError` → `setItems(null)` → 清空新目录新拉到的内容。

改前是“整体 last-writer-wins”，不会互相追加；改成分页追加后这个既有缺陷被放大成了可见的串台。

修复建议：给 `load()` 加一个自增 token，回调里 `if (token != mLoadToken) return;`；
同时 `onBackInvoked()` 走同一 token。

### P4 — DLNA 网络地址工厂被无条件收紧（`8e32f2751`）

`DLNAServiceConfiguration.createNetworkAddressFactory()` 删掉了 `if (!bindPreferredOnly) return super...`
的短路，于是**控点侧（browser）也被强制**只绑候选网卡 + `Inet4Address` 限定：

```java
protected boolean isUsableAddress(NetworkInterface nif, InetAddress address) {
    return address instanceof Inet4Address && super.isUsableAddress(nif, address);
}
```

- 纯 IPv6 / 非常规网卡环境（`DlnaNetwork.isCandidate` 会排除 `tailscale`/`wg`/`vpn`/`rmnet`/`docker`/`br-`/`veth`…）
  会出现“一个可用地址都没有” → jUPnP 起不来、永远搜不到设备。
- 本次只想优化 server 绑定，却连带改了 `bindPreferredOnly == false` 的语义（该参数名已不再表达实际行为）。
- 另：`BROWSE_COUNT 200 → 500` 对老服务器是兼容性风险（部分实现在 `RequestedCount` 过大时直接报错或忽略分页）。

修复建议：`isUsableAddress` 回退为“有 IPv4 时优先 IPv4，否则允许 IPv6”；把接口收敛放到明确的开关下；
`BROWSE_COUNT` 失败时回退 200。

### P5 — 应用内更新：两个可致“永远提示更新 / 静默失效”的坑（`1cab2b5ca` + `e4dd2e000`）

1. `Updater.java:65-67` 用 `Github.parseSourceRevision(BuildConfig.VERSION_NAME)` 取“已装 source 号”，
   但 `VERSION_NAME` 只是 `5.5.8`（`gradle.properties`），**永远拿不到 `-source.N`**，恒为 0。
   如果以后又发 `v5.5.8-source.2`，装了这个包的用户 `code==558` 相等、`src=2 > 0` → 一直提示更新，
   装完还是提示（无限更新循环）。要么把 source 号写进 `VERSION_NAME`，要么删掉这段 `-source` 逻辑。
2. `Github.API_LATEST` 用 `releases/latest`（按发布时间取最新发布）。仓库里存在 `v0.0.0-manual.22/.24`
   这类手工 tag，一旦它排在真实版本之后就会成为 `latest` → `parseCode == 0` → `isNewer` 直接返回 false →
   **所有用户静默收不到更新**。建议改用 `/releases` 列表取 `parseCode` 最大者，或过滤掉 manual tag。

### P6 — 其他

| # | 位置 | 问题 | 严重度 |
|---|---|---|---|
| 1 | `NetworkMediaTypes.supportedLabel()` | 唯一硬编码中文文案，未走 `strings.xml`（其余同类文案都有 zh-rCN/zh-rTW/values 三份） | 低 |
| 2 | `NetworkMediaTypes.isPlayable` | 白名单策略：无扩展名、`.iso/.img/.m2v` 等会被误拦；每加格式要改代码 | 低 |
| 3 | `NetworkStorageStore.delete("")` | 空 id 语义变成“删除全部无 id 行”，与旧实现的 no-op 相反；误传空 id 会批量删 | 低 |
| 4 | `SearchActivity.fetchWord` | 序号保护只覆盖 suggest；`getCallback(true)` 的热门榜响应**无 seq 校验**，慢响应会覆盖建议列表并污染 `Setting.putHot` | 低中 |
| 5 | `SearchActivity` | `checkKeyword()` 会双重请求（`setText` 触发 debounce + 立即 `fetchWord`）；`getWord()` 已成近似死代码 | 低 |
| 6 | `PlaybackService.notifyVodChildrenDebounced` | 窗口内分支只 `removeCallbacks + post`，不更新 `lastChildrenNotifyAt`；且首帧就直接发一次，位置更新约 1s/次时几乎每次都命中“立即发” → 节流收益有限 | 低 |
| 7 | `PlaybackService.onUnbind` | 打开“后台播放”后即使**没在播放**也不再 `tryShutdown()` → 服务/前台通知可能常驻（需实机确认） | 中低 |
| 8 | `CustomWallView.refresh` | 异步化后缺少“本次刷新是否过期”的判定，连续切换壁纸时后完成的任务可能覆盖新设置（仅靠 `binding == null` 不足） | 低 |
| 9 | `DlnaMediaManager.search()` | `STAllHeader` → `DeviceTypeHeader(MediaServer:1)`：符合规范的设备更快，但**只应答 `ssdp:all` 的山寨 IPTV 盒子会漏发现**——而本次改动的目标恰恰是这类盒子（反向风险，建议实测或两种都发一轮） | 中低 |
| 10 | `DlnaMediaManager` 重扫时序 | `400ms / 1200ms / 3500ms` 三次 + `init()` 与 `DlnaServerActivity.onResume()` 各自触发一次 → 单次进页面最多 4 轮 M-SEARCH；换来“发现更快”，代价是 SSDP 广播量明显上升 | 需实测 |

---

## 三、本次改动中判断正确的部分（避免误改回去）

- `VideoActivity.isSiteChangeable() → !isDirectPushPlay() && …` 与 `VodFallbackPolicy` 的直推保护：正确。
  直推 URL 没有备用线路，按标题回搜只会无限重播死链 + 转圈。
- `TVBus.change()` 去掉 `App.post(() -> System.exit(0), 100)`：正确，之前会直接杀掉整个 App；
  改抛 `ExtractException` 由上层呈现失败。**注意** `LiveSetting.putBoot(true)` 仍会写入 boot 标记，需确认这是期望行为。
- `BaseActivity.onWindowFocusChanged` 增加 `FLAG_FULLSCREEN` 判断：正确。
  `Util.hideSystemUI` 确实会 `window.addFlags(FLAG_FULLSCREEN)`（`Util.java:57`），
  所以 onCreate 之后不会重复调用；一旦别处 `showSystemUI` 清了 flag，下次焦点变化仍会补上。
- `BaseActivity.hackResources` 收敛条件：功能等价（`960f` 与 manifest `design_width_in_dp=960` 一致，
  AutoSize 转换后 `density == widthPixels/960` 会收敛）。**但**硬编码 960 与 AndroidManifest 重复配置，
  两处一旦不一致，这个判断会恒真、优化静默失效 —— 建议直接复用 `AutoSizeConfig` 的设计宽度或在注释里锁定关系。
- `PlaybackActivity.customWall() == false`：播放页跳过壁纸 inflate/decode 合理（`LiveActivity` 本来就是 false）。
- `CustomWallView` 把 `Palette`/解码挪到 `Task.execute` 线程：方向正确。
- `PlaybackService` 的 `-source` / `Updater` 版本接线：`app/build.gradle:20-21` 已正确接到
  `VERSION_CODE` / `VERSION_NAME`（`a6a88559d`），CI 的 `sed` 只改 jvmargs 不影响版本。

## 四、复核时排除的疑点（曾怀疑、验证后不成立）

- **`firstFrameDeadlineMs` 复用会导致换集立刻失败**：曾高度怀疑，但 `PlaybackReset.afterError()`
  (`PlaybackReset.java:12-18`) 与两个播放页的 `onStateChanged(STATE_READY)` 都会调 `player().reset()`，
  会清零该字段，绝大多数路径因此被兜住。**残留隐患**：`PlayerManager.parse()`（`PlayerManager.java:871-878`）
  不像 `start()` 那样做 URL 变化判定，若上一次尝试未 READY 且未走 `reset()`，下一次会比预期提前触发首帧看门狗
  （`scheduleFirstFrameTimeout` 会用 `Math.max(100, deadline-now)`）。属强耦合设计味道，建议与 `start()` 对齐。
- **`Math.clamp` / R8**：本次改动未触碰 R8 与 desugar 配置，无需重新验证。
- **`SearchActivity` 的 `onFailure` 缺失导致编译不过**：不成立 —— 用的是项目自己的
  `com.fongmi.android.tv.impl.Callback`（`impl/Callback.java`），`onFailure` 有默认空实现。

## 五、修复记录（2026-09-24 已完成，25 文件 / +436 −176）

上面 P0–P6 已全部落地，另修了复核时新发现的问题。逐条对应：

| 项 | 修法 | 文件 |
|---|---|---|
| P0 | `VideoActivity.prepareSource()`、`LiveActivity.start()` 及两处 `STATE_READY` 补 `hideError()`（`hideError` 原本零调用者） | VideoActivity / LiveActivity |
| P1 | 删掉 `onFirstFrameTimeout()` 里 `&& openReported` 这个恒假条件；改为 MPV 有界延长（`MAX_FIRST_FRAME_EXTENSIONS = 3`），每轮显式把 `firstFrameDeadlineMs` 推后一个完整超时，总上限 `(1+3)×timeout`。**没用**报告建议的 `getPosition() > 0`——续播项起始位置本就 > 0，那是错的判据 | PlayerManager |
| P2 | `MAX_SOURCE_RETRY = 2` 与 `PlaybackRecoveryPolicy.MAX_ATTEMPTS` 对齐；延时改走 `PlaybackRecoveryPolicy.retryDelayMs()`（500ms / 1000ms，替代原来的 `retry×800`），toast 每次重试都提示 | PlayerManager |
| P3 | `DlnaBrowseActivity` 加 `mLoadToken`，三个回调都比对 token | DlnaBrowseActivity |
| P4 | `bindPreferredOnly` 更名 `fixedListenPort`（语义已变）；`isUsableAddress` 恢复 IPv6 兜底（仅有 IPv6 的机器不再绑不到地址）；`lateRescan` 改为"注册表为空才放宽到 `ssdp:all`"；`Browse` 失败时首页按 200 重试一次 | DLNAServiceConfiguration / DlnaMediaManager / DlnaNetwork |
| P5 | 改用 `/releases?per_page=30` 列表 + `findNewer()` 取版本最大者（跳过 draft/prerelease 与 `code<=0`）；下载走 tag 精确 URL；CI 在 tag 构建时把 `VERSION_NAME/VERSION_CODE` 从 tag 写回 `gradle.properties` | Github / Updater / source-build.yml |
| P6-1 | `supportedLabel()` 走 `R.string.network_storage_supported_types`（补 en / zh-rCN / zh-rTW 三份） | NetworkMediaTypes + 3 份 strings.xml |
| P6-2 | 白名单补 `.3g2 .ogm .m2v .m2p .mp2v .tp .trp .mxf .wtv .dav .rec .rmx .iso .img` 与音频 `.m4b .mp2 .aif .aiff .amr .ra .au .mpc .tak .tta .wv` | NetworkMediaTypes |
| P6-3 | `delete("")` 恢复 no-op；新增 `delete(NetworkStorage)` 按 endpoint 匹配，供无 id 的历史条目使用 | NetworkStorageStore / NetworkStorageActivity |
| P6-4 | 热门榜响应加 seq 校验（`getHot(seq)`），且只在输入框为空时才写回，慢响应不再覆盖建议列表或污染 `putHot` | SearchActivity / SearchFragment |
| P6-5 | `checkKeyword()` 里 `setText` 后 `removeCallbacks(wordRunnable)`，消除进页面双请求 | 同上 |
| P6-6 | `lastChildrenNotifyAt` 改在 runnable 真正执行时打戳（原来记的是"请求时刻"，导致窗口判断失效） | PlaybackService |
| P6-7 | `onUnbind` 抽出 `shouldKeepAlive()`：开着后台播放但 player 已 IDLE 时不再常驻服务 | PlaybackService |
| P6-8 | `CustomWallView` 加 `wallGeneration`，过期任务丢弃并 `recycle()` 自己解出的 GIF | CustomWallView |
| P6-9 | 见 P4：只发 `DeviceTypeHeader`，注册表为空时才补一轮 `ssdp:all`（不额外增加轮次，只放宽 ST） | DlnaMediaManager |
| 复核四的残留隐患 | `PlayerManager.onParseSuccess()` 补 `resetBudgetsIfUrlChanged()`，与 `start()` 对齐（抽成私有方法两处共用） | PlayerManager |

**复核时新发现（报告未提）**：

1. `ExoPlayerSession` 里 `retryRunnable` / `retryPositionMs` / `retryTransient()` / `cancelPendingRetry()` 全是死代码
   （`retryTransientLater()` 已改成只上报 RETRY，不再自己重发）。已删除，`start()/stop()/resetErrorBudget()/release()`
   里的 `cancelPendingRetry()` 一并去掉。
2. `Prefers.put/remove` 的 `commit()` 是主线程同步写盘。当初要 `commit()` 的唯一理由是紧随其后的
   `System.exit`（`TVBus` 里那个），而 `8e32f2751` 已把 `System.exit` 删掉 → 改回 `apply()`。
   全项目已无 `System.exit`，也确认没有其它 `SharedPreferences.commit()`。
3. `NetworkMediaTypes.supportedLabel()` 是唯一硬编码中文的文案（P6-1），且 `NetworkStorageStore.delete(String)`
   的空 id 分支语义与旧实现相反（P6-3）。

**编译验证**：本地无法构建，已在远程构建机 `inspur` 上验证。
新建隔离 worktree `/data/home/zyq/IdeaProjects/github/FongMi/TV-verify924`（基于 `6a3e2db3d`），
`airplay` 的两个 submodule 用软链指回主树，`media3compat/build/generated/{libmpv,dav1d,ffmpeg,av1}`
从主树播种，并 `-x` 掉 4 个原生构建任务（只验证 Java 编译）：

```
./gradlew :app:compileLeanbackArm64_v8aDebugJavaWithJavac :app:compileMobileArm64_v8aDebugJavaWithJavac \
  -x :media3compat:prepareLibmpv -x :media3compat:prepareDav1d \
  -x :media3compat:prepareAv1Sources -x :media3compat:prepareFfmpegSources
→ BUILD SUCCESSFUL
```

> 注意：`-x` 是必须的。主树里 `libmpv` 的标记文件是旧的 `.build-revision`（内容 `…-surface-guard-v1`），
> 而当前脚本查的是 `.build-revision-$abi`（内容 `…-surface-guard-vulkan-experimental-v7`），
> 两者都不匹配 → 不 `-x` 就会触发 libmpv/dav1d 的 Docker 全量重编（小时级）。
> 也就是说**主树自己下次 assemble 也会重编原生库**，属于既有状态，与本次改动无关。

**整体巡检的额外收获（2 处真实资源泄漏）**：

1. `Action.post()`（`server/process/Action.java`）——`OkHttp…execute()` 的返回值被直接丢弃、从未 close。
   响应没读但**仍占着连接**，而这是每次 history/keep 同步都调一次的方法 → 每次同步泄漏一个连接。
   已改为 `try (okhttp3.Response ignored = …execute()) {}`。
2. `TsRaw.streamUnwrapped()`（`server/process/TsRaw.java`）——`upstream` 只在错误分支和包装流
   `close()` 里关闭；`PngTsUnwrap.readProbe()` 从网络读探测头，一旦中途失败就抛异常，
   `upstream` 永远不关，而调用方只是返回 "segment unavailable"，泄漏被静默吞掉。
   已把「探测 + 解包 + 构造包装流」整体包进 `try/catch(Exception)`，失败时先 `upstream.close()` 再抛。

顺带排除的（**确认不是问题，别改**）：全项目无 `registerReceiver`；`Cursor` 全在 try-with-resources；
**流也是干净的**——`SecondarySubtitleOverlay.read()` 用 `try (input)`、
`FileUtil.openInputStream()` 把流交给调用方的 try-with-resources、
`Local.createFileResponse()` 把 `FileInputStream` 交给 NanoHTTPD 的 response（由其负责关闭，是 Nano 的约定）、
`Path.copyOrThrow(File,File)` 转交给会关闭它的重载；无 `Thread.sleep`；UI 层没有同步 OkHttp 调用；
`DateTimeFormatter` 不可变、线程安全；`ZhuToPin.map` / `NetworkMediaTypes` 的静态集合只在静态初始化里写入，
之后不再变更，无需并发容器；`ImgUtil.failed` 已用 `synchronizedSet`；`App.time()` 有真实调用者
（`Device.setTime`），不是死代码；异步回调里的 `response.body().string()` 读到底会释放连接，属正常 OkHttp 用法。

**播放恢复逻辑复核（结论：无缺陷，勿改）**：`reset()`（`PlayerManager.java:653`）会清 `retry`/`sourceRetry`/
`decodeTriedMask`/`mpvFallbackUsed`/首帧看门狗状态，而它在每个条目边界都会被调用
（`VideoActivity.prepareSource()`、两个播放页的 `STATE_READY`、`PlaybackReset.afterError()`），
所以 `decodeTriedMask` 的「仅对本条目有效」语义成立，不会把上一条目的解码失败带到下一条目。
`resetBudgetsIfUrlChanged()` 不重置 `decodeTriedMask` 是刻意的：URL 变化时 `decode` 仍保持已降级的值，
掩码继续阻止 HARD↔SOFT 振荡。

## 六、建议的验证清单（实机）

1. leanback：先播一个必然失败的源 → 换一集正常的 → 确认**报错文字消失且加载圈出现**（验 P0）。
2. leanback MPV 直播：找一条 HLS master 打开较慢的源，确认首帧超时不再误报（验 P1）。
3. DLNA：打开一个大目录，中途返回并打开另一个目录，确认列表不串台（验 P3）。
4. DLNA：在装有 Docker/Tailscale 的 rk3588 上确认仍能发现 `IPNP-iptv` 与普通 NAS（验 P4/P6-9）。
5. 更新：手动构造一个比当前更高的 release tag，确认只提示一次、装完不再提示（验 P5）。
6. 打开“后台播放”，播放后停止并退出所有页面，观察通知栏与服务是否常驻（验 P6-7）。
