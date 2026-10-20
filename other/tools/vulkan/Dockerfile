FROM ubuntu:26.04
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential cmake ninja-build meson git ca-certificates pkg-config \
    python3 python3-mako python3-yaml python3-ply python3-packaging python3-pycparser \
    rustc cargo bindgen bison flex clang-21 llvm-21-dev libclang-21-dev \
    libclc-21-dev libclc-21 libllvmspirvlib-21-dev spirv-tools spirv-headers \
    libdrm-dev libudev-dev libexpat1-dev libelf-dev zlib1g-dev \
    && rm -rf /var/lib/apt/lists/*
ENV LLVM_CONFIG=llvm-config-21 LIBCLANG_PATH=/usr/lib/llvm-21/lib
RUN ln -s /usr/bin/llvm-config-21 /usr/local/bin/llvm-config

RUN apt-get update && apt-get install -y --no-install-recommends spirv-tools-dev spirv-tools-headers \
    && rm -rf /var/lib/apt/lists/*
