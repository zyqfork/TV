#!/usr/bin/env bash
# Build the RK3588 fix on exactly the PanVK RC3 / Mesa sources used by the original ICD.
set -euo pipefail
sdk_dir=$(realpath "$1")
work_dir=$(realpath -m "$2")
here=$(cd "$(dirname "$0")" && pwd)
stack_revision=fd6ed91eff1c8d3a83307a2348b959bb93797011
mesa_revision=5a07217f034b3e50d8c7c7794f97a2df1742613b
drm_revision=76a1e97a9a7c29fa3035bb370d654791bdc23d90
ndk_version=29.0.14206865
[[ -d "$sdk_dir/ndk/$ndk_version" ]] || { echo "Android NDK $ndk_version is required" >&2; exit 1; }
mkdir -p "$work_dir"

checkout() {
    local directory=$1 url=$2 revision=$3
    if [[ ! -d "$directory/.git" ]]; then
        git init -q "$directory"
        git -C "$directory" remote add origin "$url"
    fi
    [[ $(git -C "$directory" remote get-url origin) == "$url" ]] || {
        echo "Refusing to alter an unrelated source tree: $directory" >&2; exit 1;
    }
    if ! git -C "$directory" cat-file -e "$revision^{commit}" 2>/dev/null; then
        git -C "$directory" fetch --depth=1 origin "$revision"
    fi
    git -C "$directory" checkout --detach "$revision"
}
checkout "$work_dir/panvk" https://github.com/zenithblue-oss/panvk-kbase-android.git "$stack_revision"
checkout "$work_dir/mesa" https://gitlab.freedesktop.org/mesa/mesa.git "$mesa_revision"
checkout "$work_dir/drm" https://gitlab.freedesktop.org/mesa/drm.git "$drm_revision"
# This marker belongs only to the owned build cache; never reset a user's development tree.
if [[ ! -f "$work_dir/.rc3-patches-$stack_revision" ]]; then
    [[ -z $(git -C "$work_dir/mesa" status --porcelain) ]] || {
        echo "Mesa is modified; use an empty work directory" >&2; exit 1;
    }
    "$work_dir/panvk/scripts/apply-patches.sh" --profile g615-v11-csf --mesa "$work_dir/mesa"
    touch "$work_dir/.rc3-patches-$stack_revision"
fi
for patch_file in "$here/panvk-rk3588-android12-fix.patch" "$here/panvk-rk3588-release-hardening.patch"; do
    if git -C "$work_dir/mesa" apply --check "$patch_file"; then
        git -C "$work_dir/mesa" apply "$patch_file"
    else
        git -C "$work_dir/mesa" apply --reverse --check "$patch_file"
    fi
done
python3 "$here/../../tests/device/test_panvk_gralloc_metadata.py" "$work_dir/mesa"
python3 "$here/../../tests/test_panvk_release_hardening.py" "$work_dir/mesa"
docker build -t aic-panvk-builder:rc3-fix "$here"
docker run --rm --user "$(id -u):$(id -g)" -e HOME=/tmp \
    -v "$work_dir:/w" -v "$sdk_dir:/sdk:ro" -w /w aic-panvk-builder:rc3-fix bash -euo pipefail -c '
cores=$(nproc); if [[ $cores -gt 16 ]]; then cores=16; fi
reconfigure=()
[[ ! -f /w/host-tools/build.ninja ]] || reconfigure=(--reconfigure)
meson setup "${reconfigure[@]}" /w/host-tools /w/mesa \
    -Dbuildtype=release -Dgallium-drivers= -Dvulkan-drivers=panfrost -Dplatforms= \
    -Dglx=disabled -Degl=disabled -Dgbm=disabled -Dopengl=false -Dgles1=disabled -Dgles2=disabled \
    -Dmesa-clc=enabled -Dinstall-mesa-clc=true -Dprecomp-compiler=enabled \
    -Dinstall-precomp-compiler=true -Dllvm=enabled -Dzstd=disabled
ninja -j"$cores" -C /w/host-tools src/compiler/clc/mesa_clc src/compiler/spirv/vtn_bindgen2 src/panfrost/clc/panfrost_compile
mkdir -p /w/host-tools/bin /w/android-pkgconfig /w/dist
for tool in compiler/clc/mesa_clc compiler/spirv/vtn_bindgen2 panfrost/clc/panfrost_compile; do
    ln -sfn "../src/$tool" "/w/host-tools/bin/${tool##*/}"
done
ndk=/sdk/ndk/29.0.14206865/toolchains/llvm/prebuilt/linux-x86_64
export PATH=/w/host-tools/bin:$ndk/bin:$PATH
# Keep desktop .pc files out of the Bionic link; use NDK zlib and cross-built static libdrm.
printf "%s\n" "prefix=$ndk/sysroot/usr" "libdir=$ndk/sysroot/usr/lib/aarch64-linux-android/31" \
    "Name: zlib" "Description: Android NDK zlib" "Version: 1.2.13" \
    "Libs: -L$ndk/sysroot/usr/lib/aarch64-linux-android/31 -lz" > /w/android-pkgconfig/zlib.pc
sed "s/android35-clang/android31-clang/g" /w/panvk/meson/android-aarch64.ini > /w/android.ini
printf "\npkg_config_libdir = [\x27/w/android-deps/lib/pkgconfig\x27, \x27/w/android-pkgconfig\x27]\n" >> /w/android.ini
reconfigure=()
[[ ! -f /w/drm-build/build.ninja ]] || reconfigure=(--reconfigure)
meson setup "${reconfigure[@]}" /w/drm-build /w/drm --cross-file /w/android.ini \
    --prefix=/w/android-deps --libdir=lib -Dbuildtype=release -Ddefault_library=static \
    -Dauto_features=disabled -Dtests=false -Dman-pages=disabled
ninja -j"$cores" -C /w/drm-build install
reconfigure=()
[[ ! -f /w/android/build.ninja ]] || reconfigure=(--reconfigure)
meson setup "${reconfigure[@]}" /w/android /w/mesa --cross-file /w/android.ini \
    -Dbuildtype=release -Dplatforms=android -Dplatform-sdk-version=31 -Dandroid-stub=true -Dandroid-strict=false \
    -Dgallium-drivers= -Dvulkan-drivers=panfrost -Dpanfrost-kmds=kbase \
    -Dmesa-clc=system -Dprecomp-compiler=system -Dxlib-lease=disabled \
    -Degl=disabled -Dgles1=disabled -Dgles2=disabled -Dopengl=false -Dglx=disabled \
    -Dgbm=disabled -Dllvm=disabled -Dlibunwind=disabled -Dzstd=disabled -Dcpp_link_args=-static-libstdc++
ninja -j"$cores" -C /w/android src/panfrost/vulkan/libvulkan_panfrost.so
cp /w/android/src/panfrost/vulkan/libvulkan_panfrost.so /w/dist/vulkan.panvk.so
llvm-strip --strip-unneeded /w/dist/vulkan.panvk.so
sha256sum /w/dist/vulkan.panvk.so
'
