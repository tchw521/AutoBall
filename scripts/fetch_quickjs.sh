#!/usr/bin/env bash
# 拉取 QuickJS 源码到 app/src/main/cpp/quickjs。
# 失败不阻断构建：CMake 会自动切到 stub 引擎（JS 不可用，动作流不受影响）。
#
# 注意：必须复制全部 .c/.h（含 cutils.h / libregexp.h / libunicode.h / list.h 等），
# 只复制一部分会导致 CMake 编译 quickjs.c 时找不到头文件。
set -u
DEST="app/src/main/cpp/quickjs"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

if [ -f "$DEST/quickjs.h" ] && [ -f "$DEST/quickjs.c" ] && [ -f "$DEST/cutils.h" ]; then
  echo "[quickjs] 已存在，跳过"
  exit 0
fi

URLS=(
  "https://bellard.org/quickjs/quickjs-2024-01-13.tar.xz"
  "https://github.com/bellard/quickjs/releases/download/quickjs-2024-01-13/quickjs-2024-01-13.tar.xz"
)

for URL in "${URLS[@]}"; do
  echo "[quickjs] 尝试 $URL"
  if curl -fsSL --max-time 90 -o "$TMP/qjs.tar.xz" "$URL"; then
    if tar -xf "$TMP/qjs.tar.xz" -C "$TMP" 2>/dev/null; then
      SRC="$(find "$TMP" -maxdepth 2 -name quickjs.h -printf '%h\n' | head -n 1)"
      if [ -n "$SRC" ]; then
        mkdir -p "$DEST"
        # 复制全部源码与头文件，排除构建脚本与示例
        find "$SRC" -maxdepth 1 -type f \( -name '*.c' -o -name '*.h' \) ! -name 'qjs.c' ! -name 'qjsc.c' ! -name 'run-test262.c' ! -name 'test262o.c' ! -name 'fuzz.c' ! -name 'gen-*.c' -exec cp {} "$DEST/" \;
        if [ -f "$DEST/quickjs.h" ] && [ -f "$DEST/quickjs.c" ] && [ -f "$DEST/cutils.h" ]; then
          echo "[quickjs] 已就位：$DEST（$(ls "$DEST" | wc -l) 个文件）"
          exit 0
        fi
        rm -rf "$DEST"
      fi
    fi
  fi
done

echo "[quickjs] 拉取失败，将使用 stub 引擎（动作流仍可完整运行）"
exit 0
