#!/usr/bin/env python3
"""Test actual ICD declarations with a C preprocessor, plus FD/conformance C checks.
Usage: python3 test_release_hardening.py /path/to/mesa
"""
from pathlib import Path
import re
import subprocess
import sys
import tempfile

root = Path(sys.argv[1])
physical = (root / 'src/panfrost/vulkan/panvk_vX_physical_device.c').read_text()
instance = (root / 'src/panfrost/vulkan/panvk_instance.c').read_text()
gralloc = (root / 'src/util/u_gralloc/u_gralloc_fallback.c').read_text()

def cpp(code, android):
    flags = ['-DPAN_ARCH=10', '-DPANVK_USE_WSI_PLATFORM', '-DVK_USE_PLATFORM_ANDROID_KHR']
    if android:
        flags.append('-DHAVE_ANDROID_PLATFORM')
    return subprocess.run(['cc', '-E', '-P', '-x', 'c', *flags, '-'], input=code,
                          text=True, capture_output=True, check=True).stdout

def initializer(text, start):
    return text.split(start, 1)[1].split('\n   };', 1)[0]

extensions = initializer(physical, '*ext = (struct vk_device_extension_table){')
features = initializer(physical, '*features = (struct vk_features){')
instance_extensions = instance.split('panvk_instance_extensions = {', 1)[1].split('\n};', 1)[0]
unsafe = ['KHR_present_id', 'KHR_present_id2', 'KHR_present_wait', 'KHR_present_wait2',
          'KHR_swapchain_maintenance1', 'EXT_swapchain_maintenance1', 'EXT_present_timing',
          'KHR_swapchain_mutable_format', 'KHR_swapchain']
for android in (True, False):
    expanded = cpp(extensions, android)
    for name in unsafe:
        present = re.search(r'\.' + name + r'\s*=', expanded) is not None
        assert present == (not android), (android, name)
    expanded = cpp(features, android)
    for name in ('presentId', 'presentId2', 'presentWait', 'presentWait2',
                 'swapchainMaintenance1', 'presentTiming', 'presentAtRelativeTime',
                 'presentAtAbsoluteTime'):
        present = re.search(r'\.' + name + r'\s*=', expanded) is not None
        assert present == (not android), (android, name)
    expanded = cpp(instance_extensions, android)
    for name in ('KHR_surface', 'KHR_surface_maintenance1', 'EXT_surface_maintenance1'):
        present = re.search(r'\.' + name + r'\s*=', expanded) is not None
        assert present == (not android), (android, name)
    assert '.ANDROID_native_buffer = has_gralloc' in cpp(extensions, android)

start = gralloc.index('static unsigned\nget_native_buffer_fds(')
fds = gralloc[start:gralloc.index('\n}\n', start)+3]
start = physical.index('static VkConformanceVersion\nget_conformance_version(')
conformance = physical[start:physical.index('\n}\n', start)+3]
program = '''
#include <stdint.h>
#include <string.h>
#include <assert.h>
#define UNUSED
#define HAVE_PAN_KMOD_KBASE
#define PAN_ARCH 10
typedef struct { int numFds, numInts, data[8]; } native_handle_t;
typedef struct { uint8_t major, minor, subminor, patch; } VkConformanceVersion;
struct panvk_physical_device { char kbase_node_path[32]; };
''' + fds + conformance + '''
int main(void) {
    native_handle_t h = {.numFds=4, .data={10,11,12,13}};
    int out[4] = {0,0,0,12345};
    assert(get_native_buffer_fds(&h,out)==0 && out[3]==12345);
    h.numFds=3;
    assert(get_native_buffer_fds(&h,out)==3 && out[2]==12 && out[3]==12345);
    h.numFds=-1;
    assert(get_native_buffer_fds(&h,out)==0);
    assert(get_native_buffer_fds(0,out)==0);
    struct panvk_physical_device dev={"/dev/mali0"};
    VkConformanceVersion v=get_conformance_version(&dev);
    assert(v.major==0 && v.minor==0 && v.subminor==0 && v.patch==0);
    dev.kbase_node_path[0]=0;
    v=get_conformance_version(&dev);
    assert(v.major==1 && v.minor==4 && v.subminor==1 && v.patch==2);
}
'''
with tempfile.TemporaryDirectory() as d:
    p = Path(d)
    (p/'check.c').write_text(program)
    subprocess.run(['cc','-std=c11','-Wall','-Wextra','-Werror',str(p/'check.c'),'-o',str(p/'check')],check=True)
    subprocess.run([str(p/'check')],check=True)
print('PASS: Android WSI declarations/features, non-Android preservation, FD bounds, kbase certification')
