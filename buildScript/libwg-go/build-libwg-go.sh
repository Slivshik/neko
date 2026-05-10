#!/usr/bin/env bash
set -euo pipefail

LIB_WG_GO_DIR="${1:-libwg-go}"
OUT_ROOT="${2:-app/executableSo}"
ANDROID_PACKAGE_NAME="${ANDROID_PACKAGE_NAME:-io.nekohasekai.sagernet}"

resolve_ndk_root() {
  if [[ -n "${ANDROID_NDK_HOME:-}" && -f "${ANDROID_NDK_HOME}/source.properties" ]]; then
    echo "${ANDROID_NDK_HOME}"
    return
  fi
  if [[ -n "${ANDROID_HOME:-}" ]]; then
    if [[ -f "${ANDROID_HOME}/ndk/30.0.14904198/source.properties" ]]; then
      echo "${ANDROID_HOME}/ndk/30.0.14904198"
      return
    fi
    local any_ndk
    any_ndk="$(find "${ANDROID_HOME}/ndk" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | head -n1 || true)"
    if [[ -n "${any_ndk}" && -f "${any_ndk}/source.properties" ]]; then
      echo "${any_ndk}"
      return
    fi
  fi
  echo "ANDROID_NDK_HOME is not set or invalid. Please set ANDROID_NDK_HOME to your Android NDK path." >&2
  exit 1
}

build_one_abi() {
  local abi="$1"
  local target="$2"
  local goarch="$3"
  local clang_bin="$4"
  local ndk_root="$5"
  local repo_root="$6"

  local host_tag
  case "$(uname -s)" in
    Linux) host_tag="linux-x86_64" ;;
    Darwin) host_tag="darwin-x86_64" ;;
    *) echo "Unsupported host OS: $(uname -s)" >&2; exit 1 ;;
  esac

  local toolchain="${ndk_root}/toolchains/llvm/prebuilt/${host_tag}"
  local cc="${toolchain}/bin/${clang_bin}"
  [[ -x "${cc}" ]] || { echo "Compiler not found: ${cc}" >&2; exit 1; }

  export GOOS=android
  export GOARCH="${goarch}"
  export CGO_ENABLED=1
  export CC="${cc}"
  export CFLAGS=""
  export LDFLAGS=""

  local dest="${repo_root}/${OUT_ROOT}/${abi}"
  mkdir -p "${dest}"

  echo "Building libwg-go.so for ${abi} ..."
  go build \
    -tags linux \
    -ldflags "-checklinkname=0 -X golang.zx2c4.com/wireguard/ipc.socketDirectory=/data/data/${ANDROID_PACKAGE_NAME}/cache/wireguard -buildid=" \
    -v -trimpath -buildvcs=false \
    -o "${dest}/libwg-go.so" \
    -buildmode c-shared
}

REPO_ROOT="$(pwd)"
NDK_ROOT="$(resolve_ndk_root)"

pushd "${LIB_WG_GO_DIR}" >/dev/null
build_one_abi "arm64-v8a" "aarch64-linux-android21" "arm64" "aarch64-linux-android21-clang" "${NDK_ROOT}" "${REPO_ROOT}"
build_one_abi "armeabi-v7a" "armv7a-linux-androideabi21" "arm" "armv7a-linux-androideabi21-clang" "${NDK_ROOT}" "${REPO_ROOT}"
build_one_abi "x86_64" "x86_64-linux-android21" "amd64" "x86_64-linux-android21-clang" "${NDK_ROOT}" "${REPO_ROOT}"
build_one_abi "x86" "i686-linux-android21" "386" "i686-linux-android21-clang" "${NDK_ROOT}" "${REPO_ROOT}"
popd >/dev/null

echo "libwg-go.so built for all ABI into ${REPO_ROOT}/${OUT_ROOT}"
