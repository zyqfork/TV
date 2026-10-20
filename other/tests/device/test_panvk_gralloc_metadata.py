#!/usr/bin/env python3
"""Compile the real PanVK fallback function with fake metadata (no GPU required).

Usage: python3 test_panvk_gralloc_metadata.py /path/to/patched/mesa
A path to u_gralloc_fallback.c also works, for checking the unpatched RC3 fails.
"""
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

if len(sys.argv) != 2:
    raise SystemExit(__doc__)
path = Path(sys.argv[1])
if path.is_dir():
    path /= "src/util/u_gralloc/u_gralloc_fallback.c"
source = path.read_text(encoding="utf-8")
start = source.index("static int\nfallback_gralloc_get_buffer_info(")
end = source.index("\nstatic int\ndestroy(", start)
function = source[start:end]

prefix = r'''
#include <assert.h>
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#define DRM_FORMAT_MOD_INVALID UINT64_MAX
#define mesa_loge(...) ((void)0)
#define mesa_logw(...) ((void)0)
struct native_handle { int numFds, numInts, data[4]; };
struct u_gralloc {};
struct u_gralloc_buffer_handle { const struct native_handle *handle; int hal_format, pixel_stride; };
struct u_gralloc_buffer_basic_info {
    uint32_t drm_fourcc; uint64_t modifier; int num_planes;
    int fds[4], offsets[4], strides[4]; uint64_t alloc_size, layer_count;
};
static int mapper_result, legacy_stride_calls;
static int panvk_v19_query_mapper(const struct native_handle *h, struct u_gralloc_buffer_basic_info *out) {
    (void)h;
    *out = (struct u_gralloc_buffer_basic_info){.drm_fourcc=1, .modifier=0x0800000000000011ULL,
        .num_planes=1, .strides={512}, .alloc_size=65536, .layer_count=1};
    return mapper_result;
}
static int is_hal_format_yuv(int format) { return format == 2; }
static int get_fourcc_from_hal_format(int format) { (void)format; return 1; }
static int get_hal_format_bpp(int format) { (void)format; ++legacy_stride_calls; return 4; }
static int fallback_gralloc_get_yuv_info(struct u_gralloc *g, struct u_gralloc_buffer_handle *h,
                                        struct u_gralloc_buffer_basic_info *out) {
    (void)g; (void)h; out->num_planes=1; out->strides[0]=128; return 0;
}
'''
suffix = r'''
int main(void) {
    struct native_handle raw = {.numFds=2};
    struct u_gralloc_buffer_handle h = {.handle=&raw, .hal_format=1, .pixel_stride=0};
    struct u_gralloc_buffer_basic_info out = {0};
    // Rockchip compressed buffers legitimately expose pixel_stride=0.
    assert(fallback_gralloc_get_buffer_info(NULL, &h, &out) == 0);
    assert(out.strides[0] == 512 && out.modifier == 0x0800000000000011ULL);
    assert(legacy_stride_calls == 0);
    h.hal_format = 2;
    assert(fallback_gralloc_get_buffer_info(NULL, &h, &out) == 0);
    assert(out.strides[0] == 512); // Metadata must also precede legacy YUV inference.
    mapper_result = -ENOTSUP;
    assert(fallback_gralloc_get_buffer_info(NULL, &h, &out) == 0);
    assert(out.strides[0] == 128); // Preserve a real legacy YUV implementation.
    h.hal_format = 1; h.pixel_stride = 64;
    assert(fallback_gralloc_get_buffer_info(NULL, &h, &out) == -ENOTSUP);
    assert(fallback_gralloc_get_buffer_info(NULL, NULL, &out) == -EINVAL);
    h.handle = NULL;
    assert(fallback_gralloc_get_buffer_info(NULL, &h, &out) == -EINVAL);
    puts("PASS zero-stride metadata, YUV precedence, legacy fallback and invalid handles");
}
'''
cc = shutil.which("cc") or shutil.which("gcc") or shutil.which("clang")
if not cc:
    raise SystemExit("A C compiler is required")
with tempfile.TemporaryDirectory() as directory:
    root = Path(directory)
    program = root / "metadata.c"
    program.write_text(prefix + function + suffix, encoding="utf-8")
    executable = root / "metadata-test"
    subprocess.run([cc, "-std=gnu11", "-Wall", "-Werror", str(program), "-o", str(executable)], check=True)
    subprocess.run([str(executable)], check=True)
