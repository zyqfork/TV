# 播放器日志与退出修复（2026-10-17）

记录日期：2026-10-17。设备：RK3588 / AIC TV Box，容器 `aic_tv_v11_rc555`。
App：`com.fongmi.android.tv`。范围：通知 PendingIntent 泄漏、爬虫日志洪水、退出播放报 4003。

四个修复，均基于日志证据定位，均带回归测试。

## 1. 通知 PendingIntent 泄漏

### 现象

长时间播放后系统限流应用通知：

```
E ActivityManager: Too many PendingIntent created for uid 10003, recent 10:
  [Key{startService pkg=com.fongmi.android.tv
   intent=act=androidx.media3.session.CUSTOM_NOTIFICATION_ACTION
   cmp=com.fongmi.android.tv/.service.PlaybackService flags=0x4000000}
   requestCode=-2018811927, ... requestCode=171893827, ...]
E NotificationService: Package enqueue rate is 6.221733.
  Shedding 0|com.fongmi.android.tv|1001|null|10003
```

`enqueue rate` 精确每 5 秒一条，持续 17 分钟，共 188 条；`Too many PendingIntent` 14 条。触发场景为直播（`LiveActivity`）。

### 根因（Media3 1.10.1 源码）

`CustomCommandPendingIntentBuilder.build()`：

```java
return PendingIntent.getService(
    context,
    /* requestCode= */ ThreadLocalRandom.current().nextInt(),   // 随机，永不复用
    createCustomCommandIntent(...),
    /* flags= */ PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
```

对照标准播放命令 `PlaybackPendingIntentBuilder.build()`：

```java
/* requestCode= */ keyCode,   // 固定，FLAG_UPDATE_CURRENT 复用同一个
```

因果链：`DefaultMediaNotificationProvider` 每次重建通知都会调用
`createCustomActionFromCustomCommandButton()` → `CustomCommandPendingIntentBuilder.build()`
→ 随机 requestCode → 新的 PendingIntent 永不回收。

而"循环"按钮当时用 `setSessionCommand(COMMAND_REPEAT)`（自定义命令），所以每次通知重建都泄漏一个。通知每 5 秒重建一次（直播状态刷新），17 分钟累积到系统限流。

### 修复

分两步。

**第一步（`878efd49f`）**：`onRepeatModeChanged` 不再无条件重建按钮行，只在循环图标真正变化时重建。这一步**不够**——通知重建本身仍会泄漏。

**第二步（`44e233ce4`）**：把按钮从自定义命令换成标准播放命令，并传参：

```java
private CommandButton buildRepeatButton() {
    boolean repeatOne = player.isRepeatOne();
    int target = repeatOne ? Player.REPEAT_MODE_OFF : Player.REPEAT_MODE_ONE;
    return new CommandButton.Builder(repeatOne ? CommandButton.ICON_REPEAT_ONE : CommandButton.ICON_REPEAT_OFF)
            .setPlayerCommand(Player.COMMAND_SET_REPEAT_MODE, target)
            .setDisplayName(getString(R.string.play_repeat))
            .build();
}
```

`COMMAND_SET_REPEAT_MODE` 的参数类型是 `PARAMETER_TYPE_INT`（见 `CommandButton.getParameterTypeForPlayerCommand`），因此可以携带目标模式。按钮仍能切换循环，但走固定 requestCode，全生命周期只创建一个 PendingIntent。

`COMMAND_REPEAT` 保留给外部控制器（`ActionEvent.REPEAT`、手机遥控）使用。

### 验证

干净对照（`force-stop` 后重新计时）：

| 场景 | PendingIntent 增量 | `enqueue rate` | `Too many PendingIntent` |
|---|---|---|---|
| 旧版，20 次循环操作 | +9 | 有 | 有 |
| 新版，90 秒播放 | 0 | 0 | 0 |
| 新版，4K HEVC 120 秒 | 0 | 0 | 0 |

## 2. 爬虫日志洪水

### 现象

```
D TV-SpiderDebug: ┌──────────────────────────────
D TV-SpiderDebug: │ com.fongmi.android.tv.ui.activity.VideoActivity
D TV-SpiderDebug: └──────────────────────────────
```

71 分钟内 939 行，内容全是同一个类名，每 5~6 秒一条。

### 根因

第三方爬虫 jar（`/data/data/<pkg>/cache/jar/*.jar`，dex 被加固）在播放期间周期性调用
`SpiderDebug.log(msg)`，把调用栈中的类名打了 1968 次，淹没真实日志。

### 修复（`da8e10895`）

`SpiderDebug.log(String msg)` 单参重载加去重：相同消息在窗口内只打一次。

第一版窗口设为 5000ms，**失效**——爬虫轮询间隔实测为 5~6 秒，5.x > 5000，每条都落在窗口外。改为 60 秒。

只对单参重载节流；`log(tag, msg, args)` 不节流，因为相同的格式串可以携带不同参数。

### 验证

播放 180 秒：SpiderDebug 从预期的约 36 条降到 3 行（1 条真实消息 + 2 条边框）。

## 3. 退出播放报 4003

### 现象

播放视频后按返回停在首页，弹出"硬解失败，已切换软解 / MPV Decoding Failed"（`ERROR_CODE_DECODING_FAILED` = 4003）。

### 根因

mpv 日志：

```
V mpv: event: video-reconfig
V mpv: [vo/gpu/opengl:v] Initializing GPU context 'android'
V mpv: [vo/gpu/android:fatal] Missing surface pointer
V mpv: [vo/gpu:error] Failed initializing any suitable GPU context!
V mpv: [cplayer:fatal] Error opening/initializing the VO window.
```

时序（logcat 实测）：

```
16:18:48.313  onBackKeyPressed
16:18:48.358  HomeActivity 启动
16:18:48.456  Missing surface pointer      ← Home 启动后约 100ms
```

代码时序问题：`VideoActivity.onBackInvoked()` 先 `startActivity(HomeActivity)` 再 finish；而
`PlaybackActivity.onDestroy()` 里 `releasePlaybackService()`（进而 `suspend()` 停播放器）
排在 `super.onDestroy()` **之后**——此时 View 已销毁、Surface 已失效，播放器才去停，
于是 mpv 在无窗口状态下处理 `video-reconfig` 并失败，映射成 4003。

### 修复（`066cad041`）

两处调整顺序：

1. `VideoActivity.onBackInvoked()`：启动 Home 之前先 `service().suspend()`
2. `PlaybackActivity.onDestroy()`：`releasePlaybackService()` 移到 `super.onDestroy()` 之前

**关键反例**：期间试过在 `onBackInvoked` / `onDestroy` 里调用 `detachSurface()`
（即 `getPlayerView().setPlayer(null)`），**反而更糟**——它本身就会销毁 Surface，
直接触发 `Missing surface pointer`。正确做法是**先停播放器，不要主动 detach**，
让 `SurfaceHolder.surfaceDestroyed` 回调按正确顺序处理。

### 验证

复现原操作（播放 4K HEVC → 返回退出全屏 → 再返回回首页）：

- 应用层零报错：无 `handleFatalError`、无 Toast、无 4003 映射
- 首页截图正常，无提示

`Missing surface pointer` 这条 mpv 日志仍会出现（native 层固有输出），但不再传到应用层。

## 4. 回归测试

| 文件 | 覆盖 |
|---|---|
| `other/tests/test_log_and_notification_churn.py` | 通知按钮必须用 `setPlayerCommand`；爬虫日志节流窗口 ≥ 10s；含节流行为模拟 |
| `other/tests/test_playback_exit_surface.py` | 两个 flavour 的 `onBackInvoked` 必须在 `startActivity` 前 `suspend()`；`onDestroy` 不得 `detachSurface()` |

两个测试均验证过负向对照（在修复前的代码上失败）。

## 5. 未验证

- 未在真实直播场景跑满 17 分钟复现原始通知风暴；用 4K HEVC 播放 120 秒验证了同等路径（通知每 5 秒重建）。
- 爬虫日志节流只验证了单参重载；未确认第三方 jar 是否也走 `log(tag,msg,args)`（若是则不受节流影响）。
- 退出修复只在 leanback flavour 的 4K HEVC 场景验证；mobile flavour 改动未在设备上验证。

## 6. 提交

```
066cad041 fix(player): stop playback before the window it renders into disappears
da8e10895 fix(log): widen the spider log throttle past the caller's poll interval
44e233ce4 fix(player): give the notification repeat button a standard player command
878efd49f fix(player): stop rebuilding the media notification on every repeat callback
```
