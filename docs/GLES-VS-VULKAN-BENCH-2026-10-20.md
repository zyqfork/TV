# GLES 与 Vulkan 基准报告

**日期**：2026-10-20
**设备**：AIC TV Box，RK3588，Mali-G610 MC4，Android 12 (SDK 31)，容器 `aic_tv:1.0.9`
**GLES**：OpenGL ES 3.2，驱动 `v1.g13p0-01eac0.bce9c6a1f205f86589d60cabb0509764`
**Vulkan**：实例 1.1（AOSP loader 上限），物理设备 `1.4.363`，PanVK/Mesa

## 方法

自写基准 `other/tools/vulkan/bench.c`，离屏渲染，不接显示，三条工作负载，GLES3 与 Vulkan 使用
**同一分辨率、同图元数、同 draw 数、同着色器数学、同每帧硬同步**（GLES `glFinish`；VK `submit`+`wait fence`）：

| 工作负载 | 内容 | 压力 |
|---|---|---|
| TRIS | 1280×720，每帧 5000 个 3 顶点小三角形，200 帧 | draw call / 顶点 |
| FILL | 1280×720 全屏 quad，每帧 20 次 overdraw，100 帧 | 填充率 |
| TEX | 同上但采样 256×256 纹理（8×8 平铺），100 帧 | 采样 / 带宽 |

每组先跑预热，再计时。每项跑 5 次（ondemand）或 3 次（performance），报告取中位数。
计时是墙钟时间，只反映 GPU+驱动吞吐，**不代表显示呈现、延迟或实际播放流畅度**。

## 结果（GPU governor = simple_ondemand，默认）

| 工作负载 | GLES 中位数 | Vulkan 中位数 | VK/GLES |
|---|---|---|---|
| TRIS | 206.7 fps（1033 万三角/秒） | 168.4 fps（842 万三角/秒） | **81.5%** |
| FILL | 31.93 Gpx/s | 42.28 Gpx/s | **132.4%** |
| TEX | 26.39 Gpx/s | 32.74 Gpx/s | **124.1%** |

## 结果（GPU 锁定 1 GHz，performance）

| 工作负载 | GLES 中位数 | Vulkan 中位数 | VK/GLES |
|---|---|---|---|
| TRIS | 235.2 fps | 160.7 fps | **68.3%** |
| FILL | 31.93 Gpx/s | 40.71 Gpx/s | **127.5%** |
| TEX | 25.78 Gpx/s | 31.62 Gpx/s | **122.7%** |

## 结论

1. **填充/采样：Vulkan 明显更快**，约 +22%~+32%。视频渲染以全屏填充和纹理采样为主，这类负载 VK 占优。
2. **大量小三角形：GLES 更快**，VK 只有 GLES 的 68%~82%。每帧 5000 次独立 draw 时，Vulkan 的命令缓冲与
   提交开销在这种场景下更重；真实视频播放远没有这种 draw 密度。
3. **锁频后 GLES 提升明显（TRIS +14%），VK 反而略降**，说明 VK 数字对频率/调度更敏感，
   也说明默认 `simple_ondemand` 在短时基准里没有充分升频——两组数据都需保留，不能只用一组。
4. 这台设备上 **GLES 与 VK 各有胜负**，不存在“VK 全面更快”。Vulkan 的价值集中在填充与采样，
   Android 12 的 loader 又只有 1.1，所以应用继续走 GLES 是合理的默认选择。

## 局限

- 只测了这一台 RK3588 + PanVK；不能外推到其它 SoC 或厂商驱动。
- TRIS 场景是合成压力测试，不代表任何真实应用或游戏。
- 未测真实视频解码+渲染链路（MediaCodec 零拷贝 vs copy、gpu/gpu-next 渲染器），
  这部分此前的对照片测试只证明了出画与丢帧计数，没有做帧率对比。
- 未测功耗、温度与长时间稳定性；GPU 锁频结果只代表短时峰值。
- 首次尝试的 `glbench`（未绑定着色器程序）产生的 3500+ fps GLES 数据无效，已弃用。

## 复现

```bash
# 构建（NDK r29，Android 31）
aarch64-linux-android31-clang -O2 -std=c11 -I. bench.c -o bench -lEGL -lGLESv3 -lvulkan
# 着色器：glslangValidator -V vs3.vert -o vs3.spv，再转成 vs3_spv.inc 等
adb push bench /data/local/tmp/bench && adb shell chmod 755 /data/local/tmp/bench
adb shell /data/local/tmp/bench both
```
