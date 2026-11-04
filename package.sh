#!/usr/bin/env bash
# =============================================================================
# AI-Ops Platform 前后端分离打包脚本
#
# 用法:
#   ./package.sh all        # 后端 jar + 前端静态包（默认）
#   ./package.sh backend    # 只构建后端
#   ./package.sh frontend   # 只构建前端
#   ./package.sh clean      # 清理构建产物
#
# 输出 (dist/):
#   aiops-backend-<version>.jar         后端可执行 jar
#   aiops-backend-<version>.tar.gz      后端部署包（jar + 启动脚本 + 配置样例）
#   aiops-frontend-<version>.tar.gz     前端静态包（dist + Nginx 配置样例）
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

VERSION="$(awk '/<artifactId>aiops<\/artifactId>/{f=1;next} f&&/<version>/{gsub(/.*<version>|<\/version>.*/,"");print;exit}' pom.xml)"
VERSION="${VERSION:-1.0.0}"
DIST="$ROOT/dist"
FRONTEND_DIR="$ROOT/frontend"

info() { printf '\033[36m[INFO]\033[0m %s\n' "$1"; }
ok()   { printf '\033[32m[OK]\033[0m %s\n' "$1"; }
fail() { printf '\033[31m[FAIL]\033[0m %s\n' "$1"; exit 1; }

require() {
    command -v "$1" >/dev/null 2>&1 || fail "未找到命令: $1"
}

build_backend() {
    require mvn
    info "构建后端（纯 API jar，不含前端）..."
    mvn -B -DskipTests clean package

    local jar="target/aiops-backend-${VERSION}.jar"
    [ -f "$jar" ] || fail "后端 jar 不存在: $jar"
    mkdir -p "$DIST"
    cp "$jar" "$DIST/"
    ok "后端 jar: dist/aiops-backend-${VERSION}.jar"

    # 后端部署包
    local stage="$DIST/aiops-backend-${VERSION}"
    rm -rf "$stage"
    mkdir -p "$stage/data" "$stage/logs"
    cp "$jar" "$stage/"
    cp deploy/backend/springBoot.sh "$stage/"
    cp deploy/backend/application.yml.sample "$stage/"
    cp deploy/README-split.md "$stage/README.md"
    chmod +x "$stage/springBoot.sh"
    tar -czf "$DIST/aiops-backend-${VERSION}.tar.gz" -C "$DIST" "aiops-backend-${VERSION}"
    rm -rf "$stage"
    ok "后端部署包: dist/aiops-backend-${VERSION}.tar.gz"
}

build_frontend() {
    require npm
    if [ ! -d "$FRONTEND_DIR/node_modules" ]; then
        info "安装前端依赖..."
        (cd "$FRONTEND_DIR" && npm install --no-audit --no-fund)
    fi
    info "构建前端静态资源..."
    (cd "$FRONTEND_DIR" && npm run build)
    [ -f "$FRONTEND_DIR/dist/index.html" ] || fail "前端构建产物不存在: frontend/dist/index.html"

    mkdir -p "$DIST"
    local stage="$DIST/aiops-frontend-${VERSION}"
    rm -rf "$stage"
    mkdir -p "$stage"
    cp -r "$FRONTEND_DIR/dist/." "$stage/"
    cp deploy/nginx/aiops.conf "$stage/nginx.conf.sample"
    cp deploy/README-split.md "$stage/README.md"
    tar -czf "$DIST/aiops-frontend-${VERSION}.tar.gz" -C "$DIST" "aiops-frontend-${VERSION}"
    rm -rf "$stage"
    ok "前端静态包: dist/aiops-frontend-${VERSION}.tar.gz"
}

clean() {
    info "清理构建产物..."
    rm -rf "$DIST" "$ROOT/target" "$FRONTEND_DIR/dist"
    ok "已清理 dist/、target/、frontend/dist/"
}

case "${1:-all}" in
    all)
        build_backend
        build_frontend
        echo ""
        ok "打包完成，产物位于 $DIST"
        ls -lh "$DIST"
        ;;
    backend)  build_backend ;;
    frontend) build_frontend ;;
    clean)    clean ;;
    *)
        echo "用法: $0 {all|backend|frontend|clean}"
        exit 1
        ;;
esac