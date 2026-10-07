# FTP 存储真机验收清单

本地 transport 回归：

```sh
python other/tests/test_ftp_storage.py \
  --commons-net-jar /path/to/commons-net-3.12.0.jar \
  --commons-io-jar /path/to/commons-io-2.19.0.jar \
  --pyftpdlib-dir /path/to/python/site-packages
```

脚本编译 production FTP/model/resolver/dispatch/data source/router 和 Source/JianPian，使用真实 FTP socket；Android、Media3 基础类型及 SMB/WebDAV transport 是 doubles。默认依赖位置参见脚本。可用 `--jianpian-source /temporary/directory/JianPian.java` 指定旧提取器作负对照：旧源码必须在私有 FTP URL 透传断言失败，而不是因为无法编译或缺少依赖失败。

这些检查不能替代 Android 播放验收。

## 数据安全

- 仅使用得到授权的设备、FTP 服务和测试媒体。
- 覆盖安装 `adb install -r`；不卸载、不 `pm clear`、不清空历史/收藏/数据库。
- 测试前记录已有 NAS 配置、首页入口及 adb reverse；私有备份和日志不要加入 git。
- 使用唯一的临时 profile 名称与 ID、唯一测试影片标题。不要修改已有 profile。
- 普通 FTP 会明文传输凭据；仅使用临时测试账号，显式允许明文登录。不要把凭据写入播放 URI或日志。

## 受控服务与素材

1. 本机 FTP 监听 loopback，设置固定被动端口范围。通过 adb reverse 映射控制与被动端口，使真实 Android FTP 数据源访问服务；普通 LAN 服务也可，但记录网络条件。
2. 准备不少于 15 分钟的 H.264/AAC MP4，嵌入可见时间码；另准备静态 HLS/TS、DASH/fMP4，清单使用相对分段路径。
3. 在含中文、空格的目录下放置清单。文件较多时需要滚动查找，或使用排序靠前的唯一测试文件名。
4. 服务端保存脱敏 USER/PASS、MLSD/LIST、SIZE、REST、RETR 记录。可限制吞吐；断线测试需关闭已有控制/数据连接并暂拒新连接，而不是只停监听。

## 手机与 TV 分别验收

| 项目 | 必需证据 |
|---|---|
| 匿名浏览/MP4打开 | 原生目录、实际视频画面、时间码变化；不能仅看 PLAYING |
| 保存的 FTP URL 提取 | 保持私有 profile URI，不被 JianPian 改为本机 P2P HTTP 地址；普通荐片 FTP 原行为保留 |
| 暂停/恢复 | 手机触控、TV遥控；暂停两次位置采样不前进，恢复后前进 |
| 双向 seek | 超出已缓存范围；位置跳转、实际画面、服务端非零 REST |
| 连续播放 | 每端至少10分钟，每30秒采样，定期截图；检测 BUFFERING/ERROR、停滞、fatal |
| 短断线 | 断开连接、seek到缓冲外、恢复服务；观察 BUFFERING→PLAYING及重新取流 |
| 重试耗尽 | 持续断线直到明确错误，恢复服务后记录是否自动恢复；若需重选，不写成自动恢复通过 |
| 人工恢复 | 从目录重新选择同一影片，确认实际画面及历史续播位置 |
| 账号安全 | 带凭据且未允许明文时拒绝访问；开启后能登录；profile JSON/URI不含账号密码 |
| HLS/DASH | 实际画面、位置前进、相对分段 RETR；启动通过不等于 seek/结束/断线矩阵通过 |

MediaSession 的 position 可能更新稀疏。需保留原始 position/updated/speed，必要时结合设备 uptime 推算当前位置，并用画面时间码交叉检查。TV 的 `cmd media_session dispatch` 不一定可用；实际遥控按键应单独测试。

## 清理和结果边界

- 先结束活跃测试播放，再通过应用 UI 只删除本轮 profile 和测试历史，避免播放器迟到保存重建记录。
- 只撤销本轮新增的 reverse，关闭临时服务。
- 核对原 NAS/首页配置；可读取备份比较 History/Track/Keep 的逻辑行集合，不要求数据库字节完全一致。不得为追求文件哈希一致覆盖用户数据库。
- 分开报告 fixture、构建、UI、播放和持续时间。明确素材编码、码率、时长及网络条件；不要外推为 WAN、高码率/4K/HDR、多小时稳定性或所有 NAS 兼容。
