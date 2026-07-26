#!/usr/bin/env bash
#
# Builds Media3's FFmpeg audio decoder extension for one Android ABI.
# FFmpeg is LGPL 2.1+ with this decoder-only configuration (no GPL components).
set -euo pipefail

if [[ "$#" -lt 3 || "$#" -gt 4 ]]; then
  echo "Usage: $0 <android-sdk> <cache-dir> <output-so> [abi]" >&2
  exit 2
fi

ANDROID_SDK="$1"
BUILD_CACHE="$2"
OUTPUT_SO="$3"
ABI="${4:-x86_64}"
NDK_VERSION="26.1.10909125"
MEDIA3_REVISION="c8a183b8ca7e57f43213c55f418d89fe45965db8"
FFMPEG_REVISION="3f92512fd1fd6f5e6d6eb45a156c352835314d69"
FFMPEG_ARCHIVE_SHA256="6b1878387ad04ba735fadfee85a65f6118548cfb817f13129a6184febab42916"
JNI_SHA256="cebafb59c70cd7082d40a94d33680f8627547994758fbd8551ab4b3434d4088c"
CMAKE_SHA256="8044f1d53d8f115e5fee59255931ca2886698f7cd7b6a8b03f38539b998b5f3d"
NDK_PATH="${ANDROID_SDK}/ndk/${NDK_VERSION}"
CMAKE_HOME="${ANDROID_SDK}/cmake/3.22.1"
TOOLCHAIN="${NDK_PATH}/toolchains/llvm/prebuilt/linux-x86_64/bin"
SOURCE_ROOT="${BUILD_CACHE}/ffmpeg-${FFMPEG_REVISION}"
JNI_ROOT="${BUILD_CACHE}/media3-jni-${MEDIA3_REVISION}"
NATIVE_BUILD="${BUILD_CACHE}/jni-build"
BUILT_SO="${NATIVE_BUILD}/libffmpegJNI.so"

case "${ABI}" in
  x86_64) ARCH=x86_64; CPU=x86-64; TRIPLE=x86_64-linux-android; MACHINE="X86-64"; ASM=--disable-asm ;;
  arm64-v8a) ARCH=aarch64; CPU=armv8-a; TRIPLE=aarch64-linux-android; MACHINE="AArch64"; ASM= ;;
  armeabi-v7a) ARCH=arm; CPU=armv7-a; TRIPLE=armv7a-linux-androideabi; MACHINE="ARM"; ASM= ;;
  *) echo "Unsupported ABI: ${ABI}" >&2; exit 2 ;;
esac

if [[ ! -x "${TOOLCHAIN}/${TRIPLE}24-clang" ]]; then
  echo "Android NDK ${NDK_VERSION} is missing from ${ANDROID_SDK}." >&2
  echo "Install it with sdkmanager 'ndk;${NDK_VERSION}' 'cmake;3.22.1'." >&2
  exit 1
fi
if [[ ! -x "${CMAKE_HOME}/bin/cmake" ]] || [[ ! -x "${CMAKE_HOME}/bin/ninja" ]]; then
  echo "Android CMake 3.22.1 is missing from ${ANDROID_SDK}." >&2
  echo "Install it with sdkmanager 'cmake;3.22.1'." >&2
  exit 1
fi
if [[ -z "${BUILD_CACHE}" ]] || [[ "${BUILD_CACHE}" == "/" ]]; then
  echo "Refusing to use an unsafe FFmpeg build cache path." >&2
  exit 1
fi

mkdir -p "${BUILD_CACHE}" "${JNI_ROOT}" "$(dirname "${OUTPUT_SO}")"

if [[ ! -f "${SOURCE_ROOT}/android-libs/${ABI}/libavcodec.a" ]]; then
  ARCHIVE="${BUILD_CACHE}/ffmpeg-${FFMPEG_REVISION}.tar.gz"
  if [[ ! -f "${ARCHIVE}" ]] ||
      [[ "$(sha256sum "${ARCHIVE}" | cut -d ' ' -f 1)" != "${FFMPEG_ARCHIVE_SHA256}" ]]; then
    curl --fail --location --retry 3 \
      "https://github.com/FFmpeg/FFmpeg/archive/${FFMPEG_REVISION}.tar.gz" \
      --output "${ARCHIVE}"
  fi
  echo "${FFMPEG_ARCHIVE_SHA256}  ${ARCHIVE}" | sha256sum --check
  rm -rf "${SOURCE_ROOT}"
  mkdir -p "${SOURCE_ROOT}"
  tar -xzf "${ARCHIVE}" --strip-components=1 -C "${SOURCE_ROOT}"

  pushd "${SOURCE_ROOT}" >/dev/null
  ./configure \
    --target-os=android \
    --enable-static \
    --disable-shared \
    --disable-doc \
    --disable-programs \
    --disable-everything \
    --disable-avdevice \
    --disable-avformat \
    --disable-swscale \
    --disable-postproc \
    --disable-avfilter \
    --disable-symver \
    --enable-swresample \
    --disable-v4l2-m2m \
    --disable-vulkan \
    --arch="${ARCH}" \
    --cpu="${CPU}" \
    --cross-prefix="${TOOLCHAIN}/${TRIPLE}24-" \
    --nm="${TOOLCHAIN}/llvm-nm" \
    --ar="${TOOLCHAIN}/llvm-ar" \
    --ranlib="${TOOLCHAIN}/llvm-ranlib" \
    --strip="${TOOLCHAIN}/llvm-strip" \
    ${ASM} \
    --extra-ldexeflags=-pie \
    --libdir="${SOURCE_ROOT}/android-libs/${ABI}" \
    --enable-decoder=aac \
    --enable-decoder=mp3 \
    --enable-decoder=ac3 \
    --enable-decoder=eac3 \
    --enable-decoder=truehd \
    --enable-decoder=dca \
    --enable-decoder=vorbis \
    --enable-decoder=opus \
    --enable-decoder=amrnb \
    --enable-decoder=amrwb \
    --enable-decoder=flac \
    --enable-decoder=alac \
    --enable-decoder=pcm_mulaw \
    --enable-decoder=pcm_alaw
  make -j"$(nproc)"
  make install-libs
  popd >/dev/null
fi

download_media3_file() {
  local name="$1"
  local checksum="$2"
  local target="${JNI_ROOT}/${name}"
  if [[ ! -f "${target}" ]] ||
      [[ "$(sha256sum "${target}" | cut -d ' ' -f 1)" != "${checksum}" ]]; then
    curl --fail --location --retry 3 \
      "https://raw.githubusercontent.com/FongMi/media/${MEDIA3_REVISION}/libraries/decoder_ffmpeg/src/main/jni/${name}" \
      --output "${target}"
  fi
  echo "${checksum}  ${target}" | sha256sum --check
}

download_media3_file "ffmpeg_jni.cc" "${JNI_SHA256}"
download_media3_file "CMakeLists.txt" "${CMAKE_SHA256}"
ln -sfn "${SOURCE_ROOT}" "${JNI_ROOT}/ffmpeg"

rm -rf "${NATIVE_BUILD}"
"${CMAKE_HOME}/bin/cmake" \
  -S "${JNI_ROOT}" \
  -B "${NATIVE_BUILD}" \
  -G Ninja \
  -DCMAKE_MAKE_PROGRAM="${CMAKE_HOME}/bin/ninja" \
  -DCMAKE_TOOLCHAIN_FILE="${NDK_PATH}/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI="${ABI}" \
  -DANDROID_PLATFORM=android-24 \
  -DANDROID_STL=c++_static \
  -DCMAKE_BUILD_TYPE=Release
"${CMAKE_HOME}/bin/cmake" --build "${NATIVE_BUILD}" --parallel
"${TOOLCHAIN}/llvm-strip" --strip-unneeded "${BUILT_SO}"

if ! "${TOOLCHAIN}/llvm-readelf" -h "${BUILT_SO}" | grep -q "Machine:.*${MACHINE}"; then
  echo "Built FFmpeg JNI has the wrong architecture." >&2
  exit 1
fi
install -m 0644 "${BUILT_SO}" "${OUTPUT_SO}"
