#!/usr/bin/env bash
set -euo pipefail

readonly COMMIT="56381e407c0ccfb3a6f71e668a27a901001d22ce"
readonly SHA256="7fd19c03e7d7bb02c07e64c47e0539aeabe54f366a59be822f550b5f012a8751"
readonly ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly DESTINATION="${ROOT}/third_party/llama.cpp"

if [[ -f "${DESTINATION}/CMakeLists.txt" ]]; then
    printf 'llama.cpp is already available at %s\n' "${DESTINATION}"
    exit 0
fi

archive="$(mktemp -t llama-cpp.XXXXXX.tar.gz)"
staging="$(mktemp -d -t llama-cpp.XXXXXX)"
trap 'rm -rf "${archive}" "${staging}"' EXIT

curl --fail --location \
    "https://github.com/ggml-org/llama.cpp/archive/${COMMIT}.tar.gz" \
    --output "${archive}"

actual="$(shasum -a 256 "${archive}" | awk '{print $1}')"
if [[ "${actual}" != "${SHA256}" ]]; then
    printf 'Checksum mismatch for llama.cpp archive\n' >&2
    exit 1
fi

tar -xzf "${archive}" --strip-components=1 -C "${staging}"
mkdir -p "$(dirname "${DESTINATION}")"
mv "${staging}" "${DESTINATION}"
printf 'Fetched llama.cpp %s\n' "${COMMIT}"
