# GLES 与 Vulkan 基准报告

**日期**：2026-10-20
**设备**：AIC TV Box，RK3588，Mali-G610 MC4，Android 12 (SDK 31)，容器 `aic_tv:1.0.9`
**GLES**：OpenGL ES 3.2，驱动 `v1.g13p0-01eac0.bce9c6a1f205f86589d60cabb0509764`
**Vulkan**：实例 1.1（AOSP Android 12 loader 上限），物理设备 `1.4.363`，PanVK/Mesa

## 方法

自写基准 `other/tools/vulkan/bench.c`，离屏渲染，不接显示。GLES3 与 Vulkan 使用
**同分辨率、同图元数、同 draw 数、同着色器数学、同每帧硬同步**（GLES `glFinish`；VK `submit`+`wait fence`）。
GLES 目标为 GL_RGBA8 纹理附着的 FBO，Vulkan 目标为 `VK_FORMAT_R8G8B8A8_UNORM` optimal tiled image。

| 工作负载 | 内容 | 压力 |
|---|---|---|
| TRIS | 1280×720，每帧 5000 个 3 顶点小三角形，200 帧 | draw call / 顶点 |
| FILL | 1280×720 全屏 quad，overdraw 次数可配（1/5/20/40），100 帧 | 填充率 |
| TEX | 全屏 quad 采样 256×256 纹理（8×8 平铺），每帧 20 次，100 帧 | 采样 / 带宽 |

每组先预热再计时；报告取多次运行的中位数。计时为墙钟时间。

## 结果

### FILL：随 overdraw 缩放（关键验证）

| overdraw/帧 | GLES Gpx/s | VK Gpx/s | VK/GLES |
|---|---|---|---|
| 1 | 2.26 | 2.83 | 125% |
| 5 | 9.54 | 14.36 | 150% |
| 20 | 31.9 | 42.3 | 133% |
| 40 | 39.1（中位 39.7） | 49.3（中位 49.3） | 126% |

数字随 overdraw 正常增长并趋于饱和，说明确实在测填充，不是计时噪声。

### TRIS 与 TEX（默认配置）

| 负载 | GLES | Vulkan | VK/GLES |
|---|---|---|---|
| TRIS | 205 fps（1026 万三角/秒） | 161 fps（806 万三角/秒） | 79% |
| TEX | 27.6 Gpx/s | 32.7 Gpx/s | 119% |

## 结论

1. **填充率 Vulkan 稳定领先 26%~50%**，overdraw 越高差距越稳定在 25%~33%。
2. **纹理采样 Vulkan 领先约 19%**。
3. **大量小三角形 GLES 领先约 26%**（VK 只有 79%），每帧 5000 次 draw 时 VK 命令提交开销更重。
4. 这台设备上 **GLES 与 VK 各有胜负**，Vulkan 的优势集中在填充与采样——正是视频渲染的主要负载。

## 方法修正记录（重要）

排查过程中发现并修正了两处会误导结论的问题：

1. **首版 `glbench` 未绑定着色器程序**，GLES 绘制被丢弃，测出 3500+ fps 的无效数据，已弃用。
2. **`FILL_DRAWS` 环境变量首次注入失败**（源文件为 CRLF，替换未生效），导致 overdraw=1/5/20/40
   输出完全相同。修好后数字才随负载变化。**任何没有随负载缩放的吞吐数字都不可信。**

另外验证了 GLES 用 FBO 而非 pbuffer 作渲染目标：FILL 反而从 31.9 降到 29.0 Gpx/s，
说明之前 pbuffer 并没有压低 GLES，两组目标类型结论一致。

## 局限

- 只测这一台 RK3588 + PanVK，不能外推到其它 SoC 或厂商驱动。
- TRIS 是合成压力测试，不代表真实应用；真实播放的 draw 密度远低于此。
- 未测真实 MediaCodec→渲染链路（零拷贝 vs copy、gpu vs gpu-next 渲染器），
  此前对照片测试只证明出画与丢帧计数，没做帧率对比。
- 未测功耗、温度、长时间稳定性。GPU 默认 `simple_ondemand`，短测试可能未升到最高频。
- 未做锁频对比的最终版：早期锁 1GHz 数据基于未修正版本，已不再引用。

## 复现

```bash
aarch64-linux-android31-clang -O2 -std=c11 -I. bench.c -o bench -lEGL -lGLESv3 -lvulkan
adb push bench /data/local/tmp/bench && adb shell chmod 755 /data/local/tmp/bench
adb shell /data/local/tmp/bench both
FILL_DRAWS=40 adb shell /data/local/tmp/bench gles   # 填充压力对照
```
