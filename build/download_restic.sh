# build/download_restic.sh
#!/bin/bash
set -eo pipefail

RESTIC_VERSION=0.18.1

downloadResticBinary() {
    local resticArch="$1"
    local androidArch="$2"

    local target="$(pwd)/source/app/src/main/jniLibs/$androidArch"
    mkdir -p "$target"

    local resticFile="restic_${RESTIC_VERSION}_linux_${resticArch}.bz2"
    echo "Downloading $resticFile for $androidArch"
    curl -sSfL "https://github.com/restic/restic/releases/download/v${RESTIC_VERSION}/$resticFile" | bzip2 -dc > "$target/librestic.so"
    chmod +x "$target/librestic.so"
}

# 下载各架构的二进制
downloadResticBinary arm64 arm64-v8a
downloadResticBinary arm armeabi-v7a
downloadResticBinary amd64 x86_64
downloadResticBinary 386 x86