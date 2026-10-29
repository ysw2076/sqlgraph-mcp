#!/bin/sh
# sqlgraph-mcp 설치 (Linux / macOS)
#
#   curl -fsSL https://raw.githubusercontent.com/ysw2076/sqlgraph-mcp/main/install.sh | sh
#
# 환경변수
#   SQLGRAPH_ZIP          내려받는 대신 쓸 로컬 배포 zip (저장소가 비공개일 때 Releases 에서 직접 받은 파일)
#   SQLGRAPH_VERSION      설치할 버전 태그 (기본: 최신, 예: v0.2.0)
#   SQLGRAPH_INSTALL_DIR  설치 폴더 (기본: ~/.sqlgraph/app)
#   SQLGRAPH_BIN_DIR      실행 파일 링크 폴더 (기본: ~/.local/bin)
#   SQLGRAPH_NO_REGISTER  1 이면 Claude Code 등록을 건너뜀
set -eu

REPO="ysw2076/sqlgraph-mcp"
INSTALL_DIR="${SQLGRAPH_INSTALL_DIR:-$HOME/.sqlgraph/app}"
BIN_DIR="${SQLGRAPH_BIN_DIR:-$HOME/.local/bin}"

say() { printf '%s\n' "$*"; }
fail() { printf '오류: %s\n' "$*" >&2; exit 1; }

case "$(uname -s)" in
  Linux) OS=linux ;;
  Darwin) OS=macos ;;
  *) fail "지원하지 않는 OS 입니다: $(uname -s) (Windows 는 install.ps1 을 쓰세요)" ;;
esac
case "$(uname -m)" in
  x86_64 | amd64) ARCH=x64 ;;
  arm64 | aarch64) ARCH=arm64 ;;
  *) fail "지원하지 않는 CPU 입니다: $(uname -m)" ;;
esac
ASSET="sqlgraph-mcp-$OS-$ARCH.zip"

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT INT TERM

if [ -n "${SQLGRAPH_ZIP:-}" ]; then
  [ -f "$SQLGRAPH_ZIP" ] || fail "SQLGRAPH_ZIP 파일이 없습니다: $SQLGRAPH_ZIP"
  cp "$SQLGRAPH_ZIP" "$TMP/$ASSET"
else
  if [ -n "${SQLGRAPH_VERSION:-}" ]; then
    URL="https://github.com/$REPO/releases/download/$SQLGRAPH_VERSION/$ASSET"
  else
    URL="https://github.com/$REPO/releases/latest/download/$ASSET"
  fi
  say "다운로드: $URL"
  if command -v curl >/dev/null 2>&1; then
    curl -fL --progress-bar -o "$TMP/$ASSET" "$URL" || fail "다운로드 실패. 저장소가 비공개라면 Releases 에서 $ASSET 를 받아 SQLGRAPH_ZIP 으로 지정하세요"
  elif command -v wget >/dev/null 2>&1; then
    wget -q -O "$TMP/$ASSET" "$URL" || fail "다운로드 실패: $URL"
  else
    fail "curl 또는 wget 이 필요합니다"
  fi
fi

say "압축 해제 중..."
if command -v unzip >/dev/null 2>&1; then
  unzip -q "$TMP/$ASSET" -d "$TMP/x"
elif command -v python3 >/dev/null 2>&1; then
  python3 -c "import sys,zipfile; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])" "$TMP/$ASSET" "$TMP/x"
  chmod +x "$TMP/x/sqlgraph/bin/sqlgraph" "$TMP/x/sqlgraph/runtime/bin/"* 2>/dev/null || true
  find "$TMP/x/sqlgraph/runtime/lib" -name 'jspawnhelper' -exec chmod +x {} \; 2>/dev/null || true
else
  fail "unzip 또는 python3 가 필요합니다"
fi
[ -x "$TMP/x/sqlgraph/bin/sqlgraph" ] || fail "배포 파일 구성이 올바르지 않습니다"

mkdir -p "$(dirname "$INSTALL_DIR")"
rm -rf "$INSTALL_DIR"
mv "$TMP/x/sqlgraph" "$INSTALL_DIR"
if [ "$OS" = "macos" ]; then
  xattr -dr com.apple.quarantine "$INSTALL_DIR" 2>/dev/null || true
fi

mkdir -p "$BIN_DIR"
ln -sf "$INSTALL_DIR/bin/sqlgraph" "$BIN_DIR/sqlgraph"
say "설치 완료: $INSTALL_DIR"
"$INSTALL_DIR/bin/sqlgraph" --version

if [ "${SQLGRAPH_NO_REGISTER:-0}" != "1" ]; then
  say ""
  "$INSTALL_DIR/bin/sqlgraph" install || true
fi

case ":$PATH:" in
  *":$BIN_DIR:"*) ;;
  *) say ""
     say "※ $BIN_DIR 가 PATH 에 없습니다. 터미널에서 sqlgraph 명령을 쓰려면 셸 설정에 추가하세요:"
     say "   export PATH=\"$BIN_DIR:\$PATH\"" ;;
esac
