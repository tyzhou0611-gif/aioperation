#!/bin/bash
# =============================================================================
# AI-Ops Platform 一键部署脚本
# 用法：将 aiops-platform-deploy.tar.gz 和本脚本放在同一目录，执行 sh install.sh
# 部署目录：包所在目录的父目录（如包在 /u01/deploy/ 则部署到 /u01/）
# 可通过 DEPLOY_DIR 环境变量自定义，如 DEPLOY_DIR=/opt sh install.sh
# =============================================================================
set -e

# ==================== 配置 ====================
APP_NAME="aiops-platform"
PKG_FILE="aiops-platform-deploy.tar.gz"
JAVA_HOME="${JAVA_HOME:-}"
PORT="${SERVER_PORT:-8181}"

# ==================== 颜色输出 ====================
RED="\033[31m"
GREEN="\033[32m"
YELLOW="\033[33m"
CYAN="\033[36m"
NC="\033[0m"
info()  { echo -e "${CYAN}[INFO]${NC} $1"; }
ok()    { echo -e "${GREEN}[OK]${NC} $1"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $1"; }
fail()  { echo -e "${RED}[FAIL]${NC} $1"; exit 1; }

# ==================== 获取包所在目录（当前目录） ====================
SRC_DIR="$(cd "$(dirname "$0")" && pwd)"
PKG_PATH="$SRC_DIR/$PKG_FILE"

if [ ! -f "$PKG_PATH" ]; then
    fail "找不到 $PKG_FILE（搜索了 $SRC_DIR）\n请将部署包放在本脚本同目录"
fi
ok "包文件: $PKG_PATH ($(du -h "$PKG_PATH" | cut -f1))"

# ==================== 部署目录（默认为包所在目录的父目录） ====================
DEPLOY_DIR="${DEPLOY_DIR:-$(dirname "$SRC_DIR")}"
info "部署目录: $DEPLOY_DIR/$APP_NAME"

# ==================== 检查 JDK ====================
echo ""
info "检查 JDK..."
if [ -n "$JAVA_HOME" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
    JAVA_BIN="${JAVA_HOME}/bin/java"
elif command -v java &>/dev/null; then
    JAVA_BIN="java"
    JAVA_HOME=$(dirname "$(dirname "$(readlink -f "$(which java)")")")
    warn "使用系统 java（JAVA_HOME=$JAVA_HOME）"
else
    fail "未找到 java，请安装 JDK8 或设置 JAVA_HOME 环境变量"
fi

JAVA_VERSION=$("$JAVA_BIN" -version 2>&1 | head -1)
info "Java: $JAVA_VERSION"

"$JAVA_BIN" -version 2>&1 | grep -qE '"(1\.[89]|10|[1-9][0-9])' || fail "需要 JDK8+，当前：$JAVA_VERSION"
ok "JDK 检查通过"

# ==================== 停旧进程 ====================
echo ""
info "检查旧进程..."
OLD_PID=$(ps aux | grep "$APP_NAME.jar" | grep -v grep | awk '{print $2}')
if [ -n "$OLD_PID" ]; then
    info "发现旧进程 PID=$OLD_PID，正在停止..."
    kill "$OLD_PID" 2>/dev/null || true
    for i in $(seq 1 10); do
        if ! ps -p "$OLD_PID" > /dev/null 2>&1; then break; fi
        sleep 1
    done
    if ps -p "$OLD_PID" > /dev/null 2>&1; then
        warn "强制杀死..."
        kill -9 "$OLD_PID" 2>/dev/null || true
    fi
    ok "旧进程已停止"
else
    info "无旧进程"
fi

# ==================== 备份数据 ====================
DATA_DIR="$DEPLOY_DIR/$APP_NAME/data"
if [ -d "$DATA_DIR" ]; then
    BACKUP_DIR="$DEPLOY_DIR/${APP_NAME}_backup_$(date +%Y%m%d_%H%M%S)"
    info "备份旧数据到 $BACKUP_DIR ..."
    cp -r "$DATA_DIR" "$BACKUP_DIR"
    ok "数据已备份"
fi

# ==================== 解压 ====================
echo ""
info "解压到 $DEPLOY_DIR ..."
mkdir -p "$DEPLOY_DIR"

if [ -d "$DEPLOY_DIR/$APP_NAME" ]; then
    info "清理旧部署目录..."
    rm -rf "$DEPLOY_DIR/$APP_NAME"
fi

tar -xzf "$PKG_PATH" -C "$DEPLOY_DIR"

if [ ! -d "$DEPLOY_DIR/$APP_NAME" ]; then
    fail "解压失败，$DEPLOY_DIR/$APP_NAME 不存在"
fi
ok "解压完成: $DEPLOY_DIR/$APP_NAME"

# ==================== 恢复数据 ====================
if [ -n "$BACKUP_DIR" ] && [ -d "$BACKUP_DIR" ]; then
    info "恢复数据..."
    rm -rf "$DEPLOY_DIR/$APP_NAME/data"
    cp -r "$BACKUP_DIR" "$DEPLOY_DIR/$APP_NAME/data"
    ok "数据已恢复"
fi

# ==================== 创建目录 ====================
cd "$DEPLOY_DIR/$APP_NAME"
mkdir -p data logs
chmod +x springBoot.sh 2>/dev/null || true

# ==================== 启动 ====================
echo ""
info "启动应用（端口 $PORT）..."
export JAVA_HOME
"$DEPLOY_DIR/$APP_NAME/springBoot.sh" start

# ==================== 健康检查 ====================
echo ""
info "健康检查（等待应用启动，最多60秒）..."
HEALTH_OK=0
for i in $(seq 1 30); do
    HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:$PORT/api/system/health" 2>/dev/null)
    if [ "$HTTP_CODE" = "200" ]; then
        HEALTH_OK=1
        break
    fi
    sleep 2
done

echo ""
if [ "$HEALTH_OK" = "1" ]; then
    ok "================ 部署成功 ================"
    ok "访问地址: http://$(hostname -I 2>/dev/null | awk '{print $1}' || echo 'localhost'):$PORT"
    ok "默认账号: admin / 123456"
    ok "健康检查: curl http://localhost:$PORT/api/system/health"
    ok "日志文件: $DEPLOY_DIR/$APP_NAME/logs/stdout.log"
    echo ""
    info "常用命令:"
    echo "  $DEPLOY_DIR/$APP_NAME/springBoot.sh status   # 查看状态"
    echo "  $DEPLOY_DIR/$APP_NAME/springBoot.sh stop     # 停止"
    echo "  $DEPLOY_DIR/$APP_NAME/springBoot.sh restart  # 重启"
else
    warn "应用已启动但健康检查超时"
    warn "请查看日志: tail -f $DEPLOY_DIR/$APP_NAME/logs/stdout.log"
fi
