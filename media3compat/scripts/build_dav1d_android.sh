#!/usr/bin/env bash
set -euo pipefail

sdk_dir=$1
cache_dir=$2
output_dir=$3
requested_abis=${4:-arm64-v8a,armeabi-v7a,x86_64}

ndk_version=26.1.10909125
media_revision=5fb306449733dd71595700c1227ad6087578c559
dav1d_revision=54706fc6bc0cdecab7e9593974a4039cc038fca7
cpu_features_revision=d3b2440fcfc25fe8e6d0d4a85f06d68e98312f5b

mkdir -p "$cache_dir" "$output_dir"
IFS=, read -ra abi_list <<< "$requested_abis"
all_present=true
for abi in "${abi_list[@]}"; do
    library="$output_dir/$abi/libdav1dJNI.so"
    if [[ ! -f "$library" ]] || ! grep -aq dav1d_send_data "$library"; then
        all_present=false
    fi
done
if $all_present; then
    exit 0
fi

command -v docker >/dev/null || {
    echo "Docker is required for the reproducible dav1d source build." >&2
    exit 1
}
[[ -d "$sdk_dir/ndk/$ndk_version" ]] || {
    echo "Android NDK $ndk_version is required." >&2
    exit 1
}

media_dir="$cache_dir/androidx-media-$media_revision"
dav1d_dir="$cache_dir/dav1d-$dav1d_revision"
cpu_features_dir="$cache_dir/cpu_features-$cpu_features_revision"

if [[ ! -d "$media_dir/.git" ]]; then
    git clone --filter=blob:none --no-checkout https://github.com/androidx/media.git "$media_dir"
    git -C "$media_dir" sparse-checkout set libraries/decoder_av1/src/main/jni
fi
git -C "$media_dir" checkout --detach "$media_revision"

if [[ ! -d "$dav1d_dir/.git" ]]; then
    git clone --filter=blob:none https://code.videolan.org/videolan/dav1d.git "$dav1d_dir"
fi
git -C "$dav1d_dir" checkout --detach "$dav1d_revision"

if [[ ! -d "$cpu_features_dir/.git" ]]; then
    git clone --filter=blob:none https://github.com/google/cpu_features.git "$cpu_features_dir"
fi
git -C "$cpu_features_dir" checkout --detach "$cpu_features_revision"

jni_dir="$media_dir/libraries/decoder_av1/src/main/jni"
ln -sfn "$(realpath --relative-to="$jni_dir" "$dav1d_dir")" "$jni_dir/dav1d"
ln -sfn "$(realpath --relative-to="$jni_dir" "$cpu_features_dir")" "$jni_dir/cpu_features"

uid=$(id -u)
gid=$(id -g)
docker run --rm \
    -v "$cache_dir:/cache" \
    -v "$sdk_dir:/android-sdk:ro" \
    -w "/cache/androidx-media-$media_revision/libraries/decoder_av1/src/main/jni" \
    ubuntu:24.04 bash -euc "
export DEBIAN_FRONTEND=noninteractive
apt-get update >/dev/null
apt-get install -y build-essential ca-certificates cmake git meson ninja-build nasm >/dev/null
ndk=/android-sdk/ndk/$ndk_version
toolchain=\$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin
for abi in ${requested_abis//,/ }; do
    case \$abi in
        arm64-v8a)
            target=aarch64-linux-android21
            cross_file=aarch64-android.meson
            ;;
        armeabi-v7a)
            target=armv7a-linux-androideabi21
            cross_file=arm-android.meson
            ;;
        x86_64)
            target=x86_64-linux-android21
            cross_file=x86_64-android.meson
            ;;
        *) echo \"Unsupported ABI: \$abi\" >&2; exit 2 ;;
    esac

    build_root=/cache/build-\$abi
    rm -rf \"\$build_root\"
    mkdir -p \"\$build_root\"
    cp \"dav1d/package/crossfiles/\$cross_file\" \"\$build_root/cross.meson\"
    sed -i \
      -e \"s|c = .*|c = '\$toolchain/\$target-clang'|\" \
      -e \"s|cpp = .*|cpp = '\$toolchain/\$target-clang++'|\" \
      -e \"s|ar = .*|ar = '\$toolchain/llvm-ar'|\" \
      -e \"s|strip = .*|strip = '\$toolchain/llvm-strip'|\" \
      \"\$build_root/cross.meson\"
    meson setup \"\$build_root/dav1d\" dav1d \
        --cross-file=\"\$build_root/cross.meson\" --default-library=static \
        -Denable_tools=false -Denable_tests=false
    ninja -C \"\$build_root/dav1d\"
    mkdir -p \"nativelib/\$abi\"
    cp \"\$build_root/dav1d/src/libdav1d.a\" \"nativelib/\$abi/\"

    cmake -S . -B \"\$build_root/jni\" -G Ninja \
        -DCMAKE_MAKE_PROGRAM=/usr/bin/ninja \
        -DCMAKE_TOOLCHAIN_FILE=\"\$ndk/build/cmake/android.toolchain.cmake\" \
        -DANDROID_ABI=\"\$abi\" -DANDROID_PLATFORM=android-21 \
        -DANDROID_STL=c++_static -DCMAKE_BUILD_TYPE=Release
    cmake --build \"\$build_root/jni\" --target dav1dJNI
done
chown -R $uid:$gid /cache
"

for abi in "${abi_list[@]}"; do
    mkdir -p "$output_dir/$abi"
    cp "$cache_dir/build-$abi/jni/libdav1dJNI.so" "$output_dir/$abi/"
    "$sdk_dir/ndk/$ndk_version/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" \
        --strip-unneeded "$output_dir/$abi/libdav1dJNI.so"
done
