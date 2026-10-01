#!/usr/bin/env bash
set -euo pipefail

sdk_dir=$1
cache_dir=$2
output_dir=$3
requested_abis=${4:-arm64-v8a,armeabi-v7a,x86_64}
ndk_version=29.0.14206865
source_revision=46ef59a1f093b30e774f463d5c5942a3ac8d22be
native_overlay_dir="$(cd "$(dirname "$0")/../src/main/cpp/mpv" && pwd)"
overlay_patch_script="$(cd "$(dirname "$0")" && pwd)/apply_subtitle_overlay.py"
overlay_revision=$(sha256sum "$native_overlay_dir"/* "$overlay_patch_script" | cut -d' ' -f1 | sha256sum | cut -d' ' -f1)
build_revision="${source_revision}-surface-guard-vulkan-subtitle-v1-${overlay_revision}"

mkdir -p "$cache_dir" "$output_dir"
all_present=true
IFS=, read -ra abi_list <<< "$requested_abis"
for abi in "${abi_list[@]}"; do
    marker="$output_dir/.build-revision-$abi"
    player_lib="$output_dir/$abi/libplayer.so"
    mpv_lib="$output_dir/$abi/libmpv.so"
    if [[ ! -f "$marker" ]] || [[ $(<"$marker") != "$build_revision" ]] \
            || [[ ! -f "$player_lib" ]] || ! strings "$player_lib" | grep -q eventEndFile \
            || [[ ! -f "$mpv_lib" ]] || ! strings "$mpv_lib" | grep -q VK_KHR_surface; then
        all_present=false
    fi
done
if $all_present; then
    exit 0
fi

command -v docker >/dev/null || {
    echo "Docker is required for the reproducible libmpv source build." >&2
    exit 1
}
[[ -d "$sdk_dir/ndk/$ndk_version" ]] || {
    echo "Android NDK $ndk_version is required." >&2
    exit 1
}

# Never patch the original/shared dependency worktree. Seed an owned bridge tree from its
# pinned sources/cache where available; copies (not hardlinks) protect the original files.
old_source_dir="$cache_dir/mpv-android-$source_revision"
source_dir="$cache_dir/mpv-android-$source_revision-subtitle-v1"
if [[ ! -d "$source_dir/.git" ]]; then
    if [[ -d "$old_source_dir/.git" ]]; then
        git clone --local "$old_source_dir" "$source_dir"
        git -C "$source_dir" remote set-url origin https://github.com/zyqfork/mpv-android.git
        for cached in deps prefix; do
            if [[ -d "$old_source_dir/buildscripts/$cached" ]]; then
                cp -a --reflink=auto "$old_source_dir/buildscripts/$cached" "$source_dir/buildscripts/"
            fi
        done
    else
        git clone https://github.com/zyqfork/mpv-android.git "$source_dir"
    fi
fi
git -C "$source_dir" checkout --detach "$source_revision"

uid=$(id -u)
gid=$(id -g)
docker run --rm \
    -v "$source_dir:/src" \
    -v "$sdk_dir:/android-sdk:ro" \
    -v "$native_overlay_dir:/subtitle-overlay:ro" \
    -v "$overlay_patch_script:/apply-subtitle-overlay.py:ro" \
    -w /src/buildscripts ubuntu:24.04 bash -euc "
export DEBIAN_FRONTEND=noninteractive
apt-get update >/dev/null
apt-get install -y autoconf automake build-essential ca-certificates cmake git gperf \
    libtool nasm ninja-build pkg-config python3 python3-pip unzip wget xz-utils \
    glslang-tools libvulkan-dev >/dev/null
pip3 install --break-system-packages 'meson>=1.6.1' >/dev/null
git config --global --add safe.directory '*'
if [[ ! -d deps/mpv ]]; then
    for attempt in 1 2 3; do
        rm -rf deps
        if IN_CI=1 ./include/download-deps.sh; then
            break
        fi
        echo \"Dependency download attempt \$attempt failed.\" >&2
    done
fi
[[ -d deps/mpv/.git ]] || {
    echo \"Unable to download the pinned mpv dependency sources.\" >&2
    exit 1
}
git -C deps/dav1d checkout --detach 54706fc6bc0cdecab7e9593974a4039cc038fca7
git -C deps/ffmpeg checkout --detach 894da5ca7d742e4429ffb2af534fcda0103ef593
git -C deps/freetype2 checkout --detach 0a0221a1347e2f1e07c395263540026e9a0aa7c7
git -C deps/libass checkout --detach f9fd3d20dff1cd84b7c74c8ae7f79711ad7736fa
git -C deps/libplacebo checkout --detach 4c426e466814536def653cb23f1d1c287ea7a7f5
git -C deps/mpv checkout --detach 8c67647b50059406c5c0444903597281b81516cf
# Build libplacebo's Vulkan backend. FFmpeg Vulkan filters remain disabled because MPV's
# gpu-next renderer only needs libplacebo/Vulkan; this keeps the experimental APK smaller.
sed -i 's/-Dvulkan=disabled/-Dvulkan=enabled/' scripts/libplacebo.sh
sed -i 's/-Dvulkan=enabled -Ddemos=false/-Dvulkan=enabled -Dvk-proc-addr=enabled -Ddemos=false/' scripts/libplacebo.sh
sed -i '/unset CC CXX/a rm -rf \"\$build\"' scripts/libplacebo.sh
# MPV has its own Vulkan context feature gate in addition to libplacebo's backend.
# Without this, libplacebo contains Vulkan symbols but gpu-api=vulkan silently remains
# unavailable and the Android renderer continues to probe OpenGL.
sed -i 's/-Dlibmpv=true -Dcplayer=false/-Dlibmpv=true -Dcplayer=false -Dvulkan=enabled/' scripts/mpv.sh
# Dependency discovery is stored in MPV's Meson build directory. Reconfigure from scratch so a
# newly generated Android Vulkan pkg-config file cannot be shadowed by an older negative result.
sed -i '/unset CC CXX/a rm -rf \"\$build\"' scripts/mpv.sh
# Android may destroy the Surface between Java's readiness check and the VO thread. Upstream's
# debug assertion aborts the whole app in that legitimate lifecycle race. Fail VO initialization
# instead so mpv reports an error and the Media3 wrapper can retry/fallback.
sed -i 's/mp_assert(vo->opts->WinID != 0 && vo->opts->WinID != -1);/if (vo->opts->WinID == 0 || vo->opts->WinID == -1) { MP_ERR(vo, \"Android Surface unavailable\\\\n\"); av_buffer_unref(\\&device_ref); return NULL; }/' \
    deps/mpv/video/out/vo_mediacodec_embed.c
python3 /apply-subtitle-overlay.py --root /src --native /subtitle-overlay
mkdir -p sdk
ln -sfn /android-sdk/ndk/$ndk_version sdk/android-ndk-r29
prefix_env=
for abi in ${requested_abis//,/ }; do
    case \$abi in
        arm64-v8a) arch=arm64; prefix=arm64; triple=aarch64-linux-android; prefix_env=\"\$prefix_env PREFIX64=/src/buildscripts/prefix/arm64\" ;;
        armeabi-v7a) arch=armv7l; prefix=armv7l; triple=arm-linux-androideabi; prefix_env=\"\$prefix_env PREFIX32=/src/buildscripts/prefix/armv7l\" ;;
        x86_64) arch=x86_64; prefix=x86_64; triple=x86_64-linux-android; prefix_env=\"\$prefix_env PREFIX_X64=/src/buildscripts/prefix/x86_64\" ;;
        *) echo \"Unsupported ABI: \$abi\" >&2; exit 2 ;;
    esac
    # The NDK exposes Vulkan headers and the system loader, but does not ship a pkg-config
    # descriptor. Give Meson cross dependency discovery the missing metadata; -lvulkan still
    # resolves against the Android sysroot and no desktop Vulkan loader is bundled.
    # buildall creates usr/local -> . only when the ABI prefix does not exist. We create the
    # prefix early for vulkan.pc, so establish the same flat-prefix links ourselves.
    mkdir -p \"/src/buildscripts/prefix/\$prefix/lib/pkgconfig\"
    ln -sfn . \"/src/buildscripts/prefix/\$prefix/usr\"
    ln -sfn . \"/src/buildscripts/prefix/\$prefix/local\"
    cp \"/android-sdk/ndk/$ndk_version/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/\$triple/24/libvulkan.so\" \
        \"/src/buildscripts/prefix/\$prefix/lib/libvulkan.so\"
    printf '%s\n' \
        'prefix=/usr/local' \
        'libdir=\${prefix}/lib' \
        'Name: Vulkan' \
        'Description: Android NDK Vulkan loader' \
        'Version: 1.3.280' \
        'Libs: -L\${libdir} -lvulkan' \
        'Cflags:' \
        > \"/src/buildscripts/prefix/\$prefix/lib/pkgconfig/vulkan.pc\"
    ./buildall.sh --arch \$arch mpv
done
env \$prefix_env /android-sdk/ndk/$ndk_version/ndk-build \
    -C /src/app/src/main -j\$(nproc)
chown -R $uid:$gid /src
"

for abi in "${abi_list[@]}"; do
    case "$abi" in
        arm64-v8a) prefix=arm64; triple=aarch64-linux-android ;;
        armeabi-v7a) prefix=armv7l; triple=arm-linux-androideabi ;;
        x86_64) prefix=x86_64; triple=x86_64-linux-android ;;
        *) echo "Unsupported ABI: $abi" >&2; exit 2 ;;
    esac
    mkdir -p "$output_dir/$abi"
    cp "$source_dir/buildscripts/prefix/$prefix/lib/"libav*.so "$output_dir/$abi/"
    cp "$source_dir/buildscripts/prefix/$prefix/lib/"libsw*.so "$output_dir/$abi/"
    cp "$source_dir/buildscripts/prefix/$prefix/lib/libmpv.so" "$output_dir/$abi/"
    cp "$source_dir/app/src/main/libs/$abi/libplayer.so" "$output_dir/$abi/"
    cp "$sdk_dir/ndk/$ndk_version/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/$triple/libc++_shared.so" "$output_dir/$abi/"
    printf '%s\n' "$build_revision" > "$output_dir/.build-revision-$abi"
done
rm -f "$output_dir/.build-revision"
