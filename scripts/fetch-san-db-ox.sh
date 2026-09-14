#!/usr/bin/env bash
set -euo pipefail

# 全言語で共有する唯一のダウンローダ (.claude/rules/testing.md)。
# 追従先タグ・protocol 番号の正典は .claude/rules/protocol.md。本体側で
# タグを追従し直す際は、まずそちらを更新してから、この変数を合わせる。
SAN_DB_OX_TAG="v0.1.1"
SAN_DB_OX_REPO="amisonnet8/san-db-ox"

BIN_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/bin"
OUT_PATH="${BIN_DIR}/san-db-ox"

# ローカル開発向けの上書き (.claude/rules/testing.md, naming.md の環境
# 変数一覧)。設定されていればダウンロードせずそのパスをそのまま返す。
if [[ -n "${SAN_DB_OX_BIN:-}" ]]; then
  echo "${SAN_DB_OX_BIN}"
  exit 0
fi

os="$(uname -s)"
arch="$(uname -m)"

case "${os}" in
  Linux) goos="linux" ;;
  Darwin) goos="darwin" ;;
  *)
    echo "fetch-san-db-ox: unsupported OS: ${os} (only Linux/Darwin are fetched by this script; set SAN_DB_OX_BIN to use another binary)" >&2
    exit 1
    ;;
esac

case "${arch}" in
  x86_64 | amd64) goarch="amd64" ;;
  aarch64 | arm64) goarch="arm64" ;;
  *)
    echo "fetch-san-db-ox: unsupported architecture: ${arch}" >&2
    exit 1
    ;;
esac

asset_name="san-db-ox_${SAN_DB_OX_TAG}_${goos}_${goarch}"
out_path="${OUT_PATH}"

# sha256 検証コマンドは OS によって名前が違う (coreutils vs BSD/macOS)。
if command -v sha256sum >/dev/null 2>&1; then
  sha256() { sha256sum "$1" | cut -d' ' -f1; }
elif command -v shasum >/dev/null 2>&1; then
  sha256() { shasum -a 256 "$1" | cut -d' ' -f1; }
else
  echo "fetch-san-db-ox: neither sha256sum nor shasum found" >&2
  exit 1
fi

base_url="https://github.com/${SAN_DB_OX_REPO}/releases/download/${SAN_DB_OX_TAG}"
sha_url="${base_url}/${asset_name}.sha256"
bin_url="${base_url}/${asset_name}"

# .sha256 の中身が「ハッシュのみ」「ハッシュ+ファイル名」どちらでも
# 通るよう、先頭フィールドだけを取り出す。
expected_sha="$(curl -fsSL "${sha_url}" | cut -d' ' -f1)"

# 冪等性: 既にあるバイナリが期待ハッシュと一致すればダウンロードを省略。
if [[ -x "${out_path}" ]] && [[ "$(sha256 "${out_path}")" == "${expected_sha}" ]]; then
  echo "${out_path}"
  exit 0
fi

mkdir -p "${BIN_DIR}"
tmp_path="${out_path}.tmp"
curl -fsSL -o "${tmp_path}" "${bin_url}"

actual_sha="$(sha256 "${tmp_path}")"
if [[ "${actual_sha}" != "${expected_sha}" ]]; then
  rm -f "${tmp_path}"
  echo "fetch-san-db-ox: checksum mismatch for ${asset_name}: expected ${expected_sha}, got ${actual_sha}" >&2
  exit 1
fi

# GitHub Releases 経由の Linux/macOS 向けアセットは実行ビットが失われる
# 前提で chmod +x する (.claude/rules/testing.md)。
chmod +x "${tmp_path}"
mv "${tmp_path}" "${out_path}"

echo "${out_path}"
