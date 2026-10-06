#!/usr/bin/env bash
# 拉取 Shizuku 官方 AIDL，覆盖内置最小桩。
#
# 安全策略：Shizuku 官方 AIDL 可能使用带自定义 transaction id 的方言写法
# （形如 `int getVersion() = 1;`），标准 AIDL 编译器无法解析，会直接让构建失败。
# 因此每个文件落地前都做一次语法检查，不合规就保留内置桩。
#
# 失败不阻断构建：Shizuku 后端会自动降级为不可用，无障碍后端仍可完整运行脚本。
set -u
DEST="app/src/main/aidl/moe/shizuku/api"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "[shizuku-aidl] 尝试拉取官方 AIDL ..."

fetch_files() {
  local SRC="$1"
  local ok=1
  for f in "$SRC"/**/*.aidl "$SRC"/*.aidl; do
    [ -f "$f" ] || continue
    # 方言写法检测：方法声明后直接跟 = 数字
    if grep -Eq '\)[[:space:]]*=[[:space:]]*[0-9]+[[:space:]]*;' "$f"; then
      echo "[shizuku-aidl] 跳过（方言语法）：$f"
      continue
    fi
    mkdir -p "$DEST/$(dirname "${f#$SRC/}")"
    cp "$f" "$DEST/${f#$SRC/}"
    ok=0
  done
  return $ok
}

if command -v git >/dev/null 2>&1; then
  if git clone --depth 1 --filter=blob:none --sparse \
      https://github.com/RikkaApps/Shizuku-API.git "$TMP/api" >/dev/null 2>&1; then
    ( cd "$TMP/api" && git sparse-checkout set api/src/main/aidl >/dev/null 2>&1 )
    SRC="$(find "$TMP/api" -type d -path '*api/src/main/aidl' | head -n 1)"
    if [ -n "$SRC" ]; then fetch_files "$SRC"; fi
  fi
fi

if [ ! -d "$DEST" ] || [ -z "$(ls -A "$DEST" 2>/dev/null)" ]; then
  if command -v curl >/dev/null 2>&1 && command -v unzip >/dev/null 2>&1; then
    if curl -fsSL -o "$TMP/api.zip" https://codeload.github.com/RikkaApps/Shizuku-API/zip/refs/heads/main; then
      unzip -q -o "$TMP/api.zip" -d "$TMP/z" >/dev/null 2>&1
      SRC="$(find "$TMP/z" -type d -path '*api/src/main/aidl' | head -n 1)"
      if [ -n "$SRC" ]; then fetch_files "$SRC"; fi
    fi
  fi
fi

if [ -z "$(ls -A "$DEST" 2>/dev/null)" ]; then
  echo "[shizuku-aidl] 未取得可用 AIDL，沿用内置桩"
else
  echo "[shizuku-aidl] 已更新 $DEST"
fi
exit 0
