# RK3588 / Android 12 PanVK 构建（1.0.8 审计版）

用途：Mali-G610、mali_kbase CSF uAPI 1.18。不是通用 Mesa Android 构建，也不代表 Vulkan CTS/conformance 已通过。

## 构建

```bash
bash other/tools/vulkan/build_panvk_android.sh /path/to/Android/Sdk /path/to/owned-work
```

需要 Docker、NDK 29.0.14206865、Python 3 和宿主 C 编译器。工作目录必须独占；不要传入其他人的 Mesa 工作树。
控制器固定 RC3 `fd6ed91eff1c8d3a83307a2348b959bb93797011`、Mesa `5a07217f034b3e50d8c7c7794f97a2df1742613b`、libdrm 2.4.125；先应用 RC3 补丁，再依次应用本目录两个补丁。
Android platform SDK 显式指定为 31，不能沿用 Mesa 默认的 35。Docker 以调用者 UID/GID 输出，避免产生新的 root-owned 缓存。

输出：`owned-work/dist/vulkan.panvk.so`。本次已验证产物 SHA256：

```
7ac52c4bf48a61ac1863d9717b6e6c7ed0db9c9d52dba36425d1b723dd201741
```

编译容器的发行版/APT 包未逐包锁定，未来不同工具链不保证字节一致。不要为了让校验通过而盲目修改发布校验和。

## 修复

`panvk-rk3588-android12-fix.patch`：

1. GPU-only AHB 公开 stride=0 时，先读取 Mapper 元数据，保留原错误和 legacy fallback。
2. kbase CPU sync 不注册 DRM syncobj copy-payload helper。
3. Android 12 loader 不处理 present fence，不声明 swapchain maintenance1。

`panvk-rk3588-release-hardening.patch`：

1. Android 系统 loader 拥有 Surface/Swapchain；不透传 ICD 私有 WSI 的 present_id/wait、第二版、present_timing、mutable-format、surface maintenance 等能力。
2. kbase 移植不能继承 Panthor 的 CTS 认证声明；conformanceVersion 返回 0。
3. legacy YUV fallback 的三元素 FD 数组拒绝超过三 FD 的句柄，避免越界。

## 检查

```bash
python3 other/tests/device/test_panvk_gralloc_metadata.py /path/to/mesa
python3 other/tests/test_panvk_release_hardening.py /path/to/mesa
```

`device/vulkan_native_buffer.cpp` 经过系统 loader 验证 AHB GPU 像素回读及 300 帧私有 BufferQueue；后者明确不是物理显示测量。真实视频另以应用截图、视频区像素变化和 native 状态验收。

## 视频与能力限制

- 当前 mpv AImageReader interop 的入口明确要求 GL/EGL；Vulkan 会在导入任何 AHB 前失败，并回退软解。这不是仅补 P010/gralloc 就能解决的问题。
- 正式推荐 GLES + MediaCodec。Vulkan 保留实验性软解选项，不声称已实现 Vulkan 硬解、HDR 或通用模拟器兼容。
- 固件公共 feature 保守声明 1.1（32 位 Mali 驱动为 1.1）；64 位 loader 仍为实验性 1.2。应用必须探测本进程 loader 与物理设备版本，不能只读全局 PackageManager feature。
- 旧测试片有 B 帧、无 composition-time 信息且 PTS=DTS；不能用其错误时间戳或 EOF 静止判定 GPU 性能。

详细结果见 `docs/AIC-TV-1.0.8-RELEASE-AUDIT-2026-10-19.md`。
