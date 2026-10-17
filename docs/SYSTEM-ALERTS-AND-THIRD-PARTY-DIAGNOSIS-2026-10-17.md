# 系统告警与第三方应用诊断（2026-10-17）

记录日期：2026-10-17。设备：RK3588 / AIC TV Box，容器 `aic_tv_v11_rc555`，Android 12（`SQ1D.220205.004`，`userdebug`）。
App：`com.fongmi.android.tv` 5.6.2。范围：`dropbox` 中累积的系统告警、搜狗输入法崩溃、哔哩哔哩（小电视）日志。

**结论先行：本档记录的 451 条系统告警、2 次输入法崩溃、B 站内部异常，没有一条来自本仓库的代码。** 它们分别是固件构建配置、容器网络环境、第三方应用自身缺陷的产物。本档给出证据链与判定，不提出应用层改动。

## 1. 告警总量与分类

`/data/system/dropbox/` 中 `system_server_wtf` 共 451 条：

| Subject | 条数 | 占比 | 归属 |
|---|---|---|---|
| `NetworkStats` | 171 | 38% | 容器网络环境 |
| `PackageSettings` | 134 | 30% | 固件（SELinux 禁用） |
| `DisplayDeviceConfig` | 113 | 25% | 固件（缺配置） |
| `ActivityManager` | 32 | 7% | 测试行为（`force-stop`） |
| `Binder` | 1 | <1% | — |

另外 `data_app_crash` 2 条，均为搜狗输入法。无 `FATAL EXCEPTION`，无 ANR。

## 2. PackageSettings：SELinux 被禁用

```
android.util.Log$TerribleFailure: Failed to set packages.list SELinux context
    at com.android.server.pm.Settings.writePackageListLPr(Settings.java:2528)
```

实测：

```
$ getenforce
Disabled
$ ls -laZ /data/system/packages.list
-rw-r----- 1 system package_info HACKED  5744 2026-10-17 16:25
```

`HACKED` 是 `ls -Z` 在 SELinux 关闭时的占位上下文，不是被篡改。

因果链：`Settings.writePackageListLPr()` 写 `packages.list` 后调用 `SELinux.restorecon()` 设置上下文；该镜像 `SELinux = Disabled`，`setfilecon()` 必然返回 -1；系统把失败当作严重错误写入 dropbox。

频次解释：每次安装/卸载/更新应用写一次 `packages.list`，因此长期累积 134 条。

判定：固件构建配置问题。`userdebug` 关闭了 SELinux，但系统代码未做兼容。无害，仅日志噪音。修复需改固件（启用 SELinux 或跳过 `restorecon`），风险高于收益。

## 3. DisplayDeviceConfig：缺亮度到 nits 的映射

```
android.util.Log$TerribleFailure: requesting nits when no mapping exists.
    at DisplayDeviceConfig.getNitsFromBacklight(DisplayDeviceConfig.java:243)
    at LocalDisplayAdapter.backlightToNits(LocalDisplayAdapter.java:817)
    at LocalDisplayAdapter.setDisplayBrightness(LocalDisplayAdapter.java:793)
```

实测：

```
$ ls /vendor/etc/display/ /system/etc/display/
（空）
$ grep -rl "nits" /vendor/etc/ /system/etc/
（空）
```

因果链：Android 12 引入 HDR 亮度管理，`DisplayDeviceConfig` 需要 `backlight → nits` 映射表，映射表来自 `/vendor/etc/display/` 下的配置文件；RK3588 固件未提供该文件；每次调亮度调用 `backlightToNits()` 找不到映射即报 `wtf`。

判定：固件缺配置文件。亮度调节本身回退到默认值、功能正常，仅每次调亮度产生一条告警。

## 4. NetworkStats：容器没有 tethering 上游接口

```
android.util.Log$TerribleFailure: problem reading network stats
    at NetworkStatsService.getNetworkStatsTethering(NetworkStatsService.java:1964)
    at NetworkStatsService.getNetworkStatsUidDetail(NetworkStatsService.java:1936)
    at NetworkStatsService.recordSnapshotLocked(NetworkStatsService.java:1440)
```

实测：

```
$ dumpsys tethering | grep "Current upstream"
    Current upstream interface(s): null
    Current upstream: null

$ ip addr
10: eth0@if3: inet 10.0.0.254/24        # 仅 Docker 网段，无默认路由

$ dumpsys tethering | grep tetherable
    tetherableWifiRegexs: [wlan\d, softap\d, ap_br_wlan\d, ap_br_softap\d]
    tetherableUsbRegexs: [usb\d, rndis\d]
    tetherableBluetoothRegexs: [bt-pan]
```

`eth0` 不匹配任何 tether 正则，且无上游接口。

因果链：`NetworkStatsService` 每 30 分钟（`pollInterval`）做一次流量快照，快照时调用 `getNetworkStatsTethering()` 统计 tether 流量；该函数遍历 tether 上游接口，容器内集合为空导致异常。

频次解释：171 次 ≈ 自 2026-10-14 建容器至 2026-10-17 约 3.5 天 × 每 30 分钟一次（168）。

补充：`/proc/net/xt_qtaguid` 不存在、`xt_qtaguid` 模块未加载，这是 Android 12 的正常状态（已改用 eBPF）。eBPF map 齐全（`/sys/fs/bpf/` 下 40 个 map + `tethering/` 下 9 个 offload map），因此失败点不在统计后端，而在"没有 tether 接口可统计"。

判定：容器网络环境导致。给容器接真实局域网网卡（非 Docker 网段）后应消失。

## 5. ActivityManager：测试行为导致

```
E AppOps: Operation not started: uid=10003 pkg=com.fongmi.android.tv(null) op=START_FOREGROUND
```

每次出现前一条必然是：

```
I ActivityManager: Force stopping com.fongmi.android.tv appid=10003 user=0: from pid 46022
I ActivityManager: Killing 45778:com.fongmi.android.tv/u0a3 (adj 0): stop ...
```

因果链：`am force-stop` 终止进程时，前台服务正在启动的请求被 AppOps 拒绝并记为错误。触发者 pid 均为测试脚本。

判定：测试行为，非缺陷。

## 6. 搜狗输入法：开机未解锁时读 CE 存储

```
java.lang.IllegalStateException: SharedPreferences in credential encrypted storage
  are not available until after user is unlocked
    at ContextImpl.getSharedPreferences(ContextImpl.java:556)
    at lib.data.manager.sp.SharedPrefManager.getPreference(SogouSource:3)
    at com.sohu.inputmethod.sogou.SogouIME.onInitializeInterface(SogouSource:3)
    at InputMethodService.initialize(InputMethodService.java:1381)
```

关键数据：`Process-Runtime: 191`（进程存活 191ms 即崩溃）。

因果链：系统启动后 `InputMethodManagerService` 立即绑定默认输入法；此时 user 0 处于 `RUNNING_LOCKED`，`/data/user/0/` 使用凭证加密存储不可访问；输入法在 `onInitializeInterface()` 同步读 `SharedPreferences`；抛 `IllegalStateException`，进程崩溃；系统自动重启输入法，此时用户已解锁，恢复正常。

频次解释：仅冷启动遇到未解锁状态，因此只有 2 次（2026-10-16）。

当前状态：

```
$ ps -A | grep sogou
u0_a30  731  ...  S  com.sohu.inputmethod.sogou.tv      # 运行 23 小时无重启
$ dumpsys user | grep State
State: RUNNING_UNLOCKED
$ dumpsys meminfo 731 | grep TOTAL
           TOTAL PSS:    58377
```

功能实测（TV 首页搜索页）：英文输入正常（截图为 `hello`）、全键盘渲染正常、历史与建议正常、连续点击 10 次无错误、进程 pid 未变。`dumpsys input_method` 显示 `mInputShown=false` 属正常——FongMi 使用自绘键盘 `CustomSearchView`，不走系统软键盘通道。

判定：输入法自身 bug。正确做法是把 `SharedPreferences` 读取延后到 `onStartInput()`。与本仓库无关，不建议改。

## 7. 哔哩哔哩（`com.xiaodianshi.tv.yst` 1.8.7）

### 7.1 防截屏

```
$ dumpsys window | grep xiaodianshi
fl=LAYOUT_IN_SCREEN FULLSCREEN SECURE LAYOUT_INSET_DECOR ...
```

窗口带 `FLAG_SECURE`，`screencap` 返回 0 字节。对照：同一时刻 FongMi 截图正常（1282881 字节）。属应用主动版权保护，非系统故障。

### 7.2 Tab 配置异常

```
E OGVUtil: [main] exception in OGVUtil, message: tab config str is null or blank.....
    at OGVUtil.isRequireRefreshWhenBack(OGVUtil.kt:8)
    at OGVTabListCallback.parseTabResponse(OGVTabListCallback.kt:11)
```

异常被应用自身捕获（`E OGVUtil` 是它自己的日志 tag），界面回退到默认 Tab，未崩溃。疑似远程配置返回空，与容器无默认路由可能相关（未抓网络请求验证）。

### 7.3 运行状态

无崩溃、无 ANR。PSS 273MB / 运行 49 分钟，偏高但属 TV 应用常见范围。

## 8. 归属汇总

| 问题 | 次数 | 根因 | 归属 |
|---|---|---|---|
| `PackageSettings` wtf | 134 | SELinux `Disabled`，`setfilecon` 必然失败 | 固件 |
| `DisplayDeviceConfig` wtf | 113 | 缺 `/vendor/etc/display/` 亮度映射 | 固件 |
| `NetworkStats` wtf | 171 | 容器无 tether 上游接口，`eth0` 不匹配 tether 正则 | 容器环境 |
| `ActivityManager` wtf | 32 | 测试脚本 `force-stop` | 测试行为 |
| 搜狗输入法崩溃 | 2 | 开机未解锁时同步读 CE 存储 | 第三方应用 |
| B 站防截屏 | — | `FLAG_SECURE` | 第三方应用（设计） |
| B 站 Tab 异常 | 多次 | 远程配置返回空，已自行容错 | 第三方应用 |

## 9. 消除路径（均不在应用层）

- `NetworkStats`（171 条，最多）：给容器接非 Docker 网段的真实网卡，或让接口匹配 tether 正则。属容器配置。
- `PackageSettings`（134 条）：固件启用 SELinux（`androidboot.selinux=enforcing`），或修改 `Settings.java` 跳过 `restorecon`。需改系统，风险高。
- `DisplayDeviceConfig`（113 条）：在 `/vendor/etc/display/` 提供亮度到 nits 的配置。需改固件。
- 搜狗输入法：等官方修复或换用其他输入法。

## 10. 未验证

- `ActivityManager` 的 32 条中，有多少来自用户正常操作（如从最近任务划掉），而非测试脚本。
- B 站 Tab 配置失败是否确因容器无默认路由（只观察到返回空，未抓网络请求）。
- B 站视频播放路径未覆盖：遥控器导航未进入播放页，且 `FLAG_SECURE` 使画面无法确认。
- 输入法只测了英文输入，未测真实中文输入场景。

## 11. 证据文件

本机工作目录，不随仓库提交：

- `.pi/tvlog/sys.txt`、`.pi/tvlog/sys2.txt`：完整 logcat 抓取。
- `.pi/tvlog/search-small.png`：输入法正常工作截图。
- `.pi/resource-test/`：解码矩阵与资源释放测试脚本及结果。
