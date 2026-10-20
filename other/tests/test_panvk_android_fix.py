#!/usr/bin/env python3
"""Contract checks for the shipped PanVK Android 12 fix patch (no GPU, no build)."""
from pathlib import Path
import re

patch = (Path(__file__).resolve().parents[1] / "tools/vulkan/panvk-rk3588-android12-fix.patch")
text = patch.read_text(encoding="utf-8")

files = re.findall(r"^\+\+\+ b/(\S+)$", text, re.M)
assert files == [
    "src/util/u_gralloc/u_gralloc_fallback.c",
    "src/panfrost/vulkan/panvk_vX_physical_device.c",
    "src/panfrost/vulkan/panvk_vX_device.c",
], files

# Mapper metadata must be consulted before the legacy stride calculation.
gralloc = text.split("--- a/src/panfrost")[0]
query = gralloc.index("panvk_v19_query_mapper(hnd->handle, &metadata)")
legacy = gralloc.index("fallback_gralloc_get_yuv_info")
assert query < legacy, "mapper metadata is not queried first"

# kbase must not publish DRM-syncobj payload copying.
device = text.split("+++ b/src/panfrost/vulkan/panvk_vX_device.c")[1]
assert "device->vk.copy_sync_payloads = vk_drm_syncobj_copy_payloads;" in device
assert "+#ifdef HAVE_PAN_KMOD_KBASE\n+   /* kbase syncs" in device

# Swapchain maintenance1 (present fences) must be off on Android.
physical = text.split("+++ b/src/panfrost/vulkan/panvk_vX_physical_device.c")[1].split(
    "--- a/src/panfrost/vulkan/panvk_vX_device.c")[0]
for name in (".KHR_swapchain_maintenance1", ".EXT_swapchain_maintenance1",
             ".swapchainMaintenance1"):
    guard = re.search(r"#(?:if|ifdef)[^\n]*ANDROID[^\n]*\n((?:.*\n)*?)\s*"
                      + re.escape(name) + r" = true,", physical)
    assert guard, f"{name} is not guarded for Android"

print("PASS PanVK Android fix patch: metadata order, kbase sync and present fences")
