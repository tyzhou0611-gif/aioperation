#!/usr/bin/env bash
# =============================================================================
# 生成服务器单进程升级包（jar + web + springBoot.sh）
# 用法: ./package-server.sh
# 输出: dist/aiops-platform-deploy-<version>.tar.gz
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

VERSION="$(awk '/<artifactId>aiops<\/artifactId>/{f=1;next} f&&/<version>/{gsub(/.*<version>|<\/version>.*/,"");print;exit}' pom.xml)"
VERSION="${VERSION:-1.0.0}"
DIST="$ROOT/dist"
STAGE="$DIST/aiops-platform"
PKG="$DIST/aiops-platform-deploy-${VERSION}.tar.gz"

info() { printf '\033[36m[INFO]\033[0m %s\n' "$1"; }
ok()   { printf '\033[32m[OK]\033[0m %s\n' "$1"; }
fail() { printf '\033[31m[FAIL]\033[0m %s\n' "$1"; exit 1; }

info "构建后端与前端..."
bash "$ROOT/package.sh" all

[ -f "$ROOT/target/aiops-backend-${VERSION}.jar" ] || fail "后端 jar 不存在"
[ -f "$ROOT/frontend/dist/index.html" ] || fail "前端 dist 不存在"

info "组装服务器升级包..."
rm -rf "$STAGE"
mkdir -p "$STAGE/web" "$STAGE/data" "$STAGE/logs"

cp "$ROOT/target/aiops-backend-${VERSION}.jar" "$STAGE/aiops-platform.jar"
cp -r "$ROOT/frontend/dist/." "$STAGE/web/"
# 内置 Prometheus：Linux 部署包只带 linux-amd64，避免把 Windows 二进制也打进去
cp -r "$ROOT/prometheus" "$STAGE/prometheus"
rm -rf "$STAGE/prometheus/windows-amd64"
cp "$ROOT/deploy/server/springBoot.sh" "$STAGE/springBoot.sh"
cp "$ROOT/deploy/server/README-upgrade.md" "$STAGE/README-升级说明.md"
cp "$ROOT/deploy/backend/application.yml.sample" "$STAGE/application.yml.sample"
cp "$ROOT/deploy/nginx/aiops.conf" "$STAGE/nginx.conf.sample"
mkdir -p "$STAGE/migrate"
cp "$ROOT/scripts/migrate/H2ToMySQL.java" "$STAGE/migrate/"
chmod +x "$STAGE/springBoot.sh"
touch "$STAGE/data/.keep" "$STAGE/logs/.keep"

tar -czf "$PKG" -C "$DIST" aiops-platform
ok "部署包: $PKG"
ls -lh "$PKG"