#!/usr/bin/env bash
# 拉取 Shizuku 官方 AIDL，覆盖内置最小桩。
# 失败不阻断构建：Shizuku 后端会自动降级为不可用，无障碍后端仍可完整运行脚本。
set -u
DEST="app/src/main/aidl/moe/shizuku/api"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "[shizuku-aidl] 尝试拉取官方 AIDL ..."

if command -v git >/dev/null 2>&1; then
  if git clone --depth 1 --filter=blob:none --sparse \
      https://github.com/RikkaApps/Shizuku-API.git "$TMP/api" >/dev/null 2>&1; then
    ( cd "$TMP/api" && git sparse-checkout set api/src/main/aidl >/dev/null 2>&1 )
    if [ -d "$TMP/api/api/src/main/aidl" ]; then
      mkdir -p "$DEST"
      cp -r "$TMP/api/api/src/main/aidl/." "$DEST/"
      echo "[shizuku-aidl] 已覆盖 $DEST"
      exit 0
    fi
  fi
fi

if command -v curl >/dev/null 2>&1; then
  if curl -fsSL -o "$TMP/api.zip" https://codeload.github.com/RikkaApps/Shizuku-API/zip/refs/heads/main; then
    if command -v unzip >/dev/null 2>&1; then
      unzip -q -o "$TMP/api.zip" -d "$TMP/z" >/dev/null 2>&1
      SRC="$(find "$TMP/z" -type d -path "*api/src/main/aidl" | head -n 1)"
      if [ -n "$SRC" ]; then
        mkdir -p "$DEST"
        cp -r "$SRC/." "$DEST/"
        echo "[shizuku-aidl] 已覆盖 $DEST (zip)"
        exit 0
      fi
    fi
  fi
fi

echo "[shizuku-aidl] 拉取失败，沿用内置桩（Shizuku 通道将不可用，不影响无障碍后端）"
exit 0
