#!/bin/bash
# =============================================================================
# AI-Ops Platform 启动脚本（单进程部署 · 后端同时托管 web/ 前端）
# 用法: ./springBoot.sh {start|stop|restart|status}
#
# 说明:
#   - JDK8+（Spring Boot 2.7）
#   - 若存在 ./web/index.html，会自动加 --app.frontend.dir 由后端提供页面
#   - start 使用进程 PID + 端口 + 健康检查三重判断，避免“已经启动却提示失败”
#   - 端口可用 SERVER_PORT 覆盖，例如: SERVER_PORT=8080 ./springBoot.sh start
# =============================================================================
APP_NAME=aiops-platform
JAVA_HOME=${JAVA_HOME:-/u01/jdk1.8.0_121}
SERVER_PORT=${SERVER_PORT:-8181}
SPRING_PROFILES_ACTIVE=${SPRING_PROFILES_ACTIVE:-prod}

cd "$(dirname "$0")" || exit 1
BASEDIR=$(pwd)
LOGDIR=${BASEDIR}/logs
mkdir -p "${LOGDIR}" "${BASEDIR}/data"

# ---- 定位 jar（优先 aiops-platform.jar）----
if [ -f "${BASEDIR}/${APP_NAME}.jar" ]; then
  _JAR="${APP_NAME}.jar"
  _JAR_PATTERN="${APP_NAME}\.jar"
else
  _JAR=$(ls aiops-backend-*.jar 2>/dev/null | head -1)
  _JAR_PATTERN="aiops-backend-.*\.jar"
fi
if [ -z "${_JAR}" ]; then
  echo "[error] 未找到 aiops-platform.jar 或 aiops-backend-*.jar"
  exit 1
fi

# ---- 前端静态目录（可选）----
FRONTEND_OPT=""
if [ -f "${BASEDIR}/web/index.html" ]; then
  FRONTEND_OPT="--app.frontend.dir=${BASEDIR}/web"
fi

# ---- 解析 java ----
if [ -x "${JAVA_HOME}/bin/java" ]; then JAVA_BIN="${JAVA_HOME}/bin/java"; else JAVA_BIN="java"; fi

# ---- 内存 ----
MEM_OPTS="-Xms2048m -Xmx2048m -XX:NewRatio=1 -XX:MetaspaceSize=256m -XX:MaxMetaspaceSize=512m"
MEM_OPTS="$MEM_OPTS -XX:+AlwaysPreTouch -Xss256k"

# ---- GC：JDK8 使用 CMS ----
GC_OPTS="-XX:+UseConcMarkSweepGC -XX:+CMSParallelRemarkEnabled -XX:CMSInitiatingOccupancyFraction=80 -XX:+UseCMSInitiatingOccupancyOnly -XX:+UseCMSCompactAtFullCollection"

# ---- GC 日志（JDK8 传统格式）----
GCLOG="-Xloggc:${LOGDIR}/gc.log -XX:+PrintGCDetails -XX:+PrintGCDateStamps -XX:+PrintGCApplicationStoppedTime -XX:+UseGCLogFileRotation -XX:NumberOfGCLogFiles=5 -XX:GCLogFileSize=20M"

OPTIMIZE_OPTS="-XX:AutoBoxCacheMax=20000"
SHOOTING_OPTS="-XX:-OmitStackTraceInFastThrow -XX:ErrorFile=${LOGDIR}/hs_err_%p.log"

# 编码与时区
ENC_OPTS="-Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai"
# 应用滚动日志目录（logback-spring.xml 使用；stdout.log 仍保留兜底）
LOG_OPTS="-Dlogging.file.path=${LOGDIR}"
export JAVA_OPTS="$MEM_OPTS $GC_OPTS $GCLOG $OPTIMIZE_OPTS $SHOOTING_OPTS $ENC_OPTS $LOG_OPTS"

# ---- 版本校验 ----
if ! "$JAVA_BIN" -version 2>&1 | grep -qE '"(1\.[89]|10|[1-9][0-9])' ; then
  echo "[error] 需要 JDK8+，当前: $("$JAVA_BIN" -version 2>&1 | head -1)"
  echo "请设置 JAVA_HOME 指向 JDK8，例如: export JAVA_HOME=/u01/jdk1.8.0_121"
  exit 1
fi

# ---- 可靠的进程查找（pgrep -f / ps -eo args，避免 ps aux 截断命令行）----
find_pid() {
  if command -v pgrep >/dev/null 2>&1; then
    pgrep -f "java .*${_JAR_PATTERN}" 2>/dev/null | head -1
  else
    ps -eo pid,args 2>/dev/null | grep -E "[j]ava .*${_JAR_PATTERN}" | awk '{print $1}' | head -1
  fi
}

is_running() {
  local p="$1"
  [ -n "$p" ] && kill -0 "$p" 2>/dev/null
}

port_in_use() {
  if command -v ss >/dev/null 2>&1; then
    ss -lnt 2>/dev/null | awk '{print $4}' | grep -qE "[:.]${SERVER_PORT}$"
  elif command -v netstat >/dev/null 2>&1; then
    netstat -lnt 2>/dev/null | awk '{print $4}' | grep -qE "[:.]${SERVER_PORT}$"
  else
    return 1
  fi
}

health_ok() {
  if command -v curl >/dev/null 2>&1; then
    curl -fsS --max-time 2 "http://127.0.0.1:${SERVER_PORT}/api/system/health" >/dev/null 2>&1
  else
    (exec 3<>/dev/tcp/127.0.0.1/"${SERVER_PORT}") >/dev/null 2>&1
  fi
}

show_log_tail() {
  echo "---- logs/stdout.log (最后 40 行) ----"
  tail -n 40 "${LOGDIR}/stdout.log" 2>/dev/null
  echo "-------------------------------------"
}

# ==================== start ====================
do_start() {
  local pid
  pid=$(find_pid)
  if is_running "$pid"; then
    echo "[warn] $APP_NAME 已经在运行 (PID=$pid)"
    return 0
  fi
  if port_in_use; then
    if health_ok; then
      echo "[warn] 端口 ${SERVER_PORT} 已被占用且健康检查正常，服务可能已由其他方式（如 systemd）启动。"
      return 0
    fi
    echo "[error] 端口 ${SERVER_PORT} 已被占用，但未找到 $APP_NAME 进程。"
    command -v ss >/dev/null 2>&1 && ss -lntp 2>/dev/null | grep -E "[:.]${SERVER_PORT}$" || true
    echo "        请用 SERVER_PORT=<空闲端口> ./springBoot.sh start，或先释放端口。"
    return 1
  fi

  echo "Starting $APP_NAME (port ${SERVER_PORT}) ..."
  nohup "$JAVA_BIN" -server $JAVA_OPTS -jar "$_JAR" $FRONTEND_OPT \
    --server.port="$SERVER_PORT" \
    --spring.profiles.active="$SPRING_PROFILES_ACTIVE" \
    > "${LOGDIR}/stdout.log" 2>&1 &
  local new_pid=$!
  echo -n "  waiting for startup"

  local ok=0 i
  for i in $(seq 1 60); do
    if ! is_running "$new_pid"; then
      echo ""
      echo "[error] 进程已退出，启动失败。"
      show_log_tail
      return 1
    fi
    if health_ok; then ok=1; break; fi
    echo -n "."
    sleep 1
  done
  echo ""

  if [ "$ok" = "1" ]; then
    echo "[ok] $APP_NAME 启动成功 (PID=$new_pid, port=${SERVER_PORT})"
    [ -n "$FRONTEND_OPT" ] && echo "     前端目录: ${BASEDIR}/web"
    echo "     健康检查: http://127.0.0.1:${SERVER_PORT}/api/system/health"
    return 0
  fi

  # 进程还在但健康检查超时：进一步看端口
  if port_in_use; then
    echo "[warn] 进程已启动 (PID=$new_pid)，端口 ${SERVER_PORT} 已监听，但健康检查未在 60 秒内返回 200。"
    echo "       服务可能已可用，请查看：tail -f ${LOGDIR}/stdout.log"
    return 0
  fi
  echo "[error] 进程存在 (PID=$new_pid) 但端口 ${SERVER_PORT} 未监听，启动异常。"
  show_log_tail
  return 1
}

# ==================== stop ====================
do_stop() {
  local pid
  pid=$(find_pid)
  if ! is_running "$pid"; then
    echo "[ok] $APP_NAME 未在运行"
    return 0
  fi
  echo "Stopping $APP_NAME (PID=$pid) ..."
  kill "$pid" 2>/dev/null
  local i
  for i in $(seq 1 30); do
    is_running "$pid" || break
    echo -n "."
    sleep 1
  done
  echo ""
  if is_running "$pid"; then
    echo "[warn] 30 秒未退出，强制结束"
    kill -9 "$pid" 2>/dev/null
  fi
  if is_running "$pid"; then
    echo "[error] 停止失败 (PID=$pid)"
    return 1
  fi
  echo "[ok] $APP_NAME 已停止"
  return 0
}

# ==================== status ====================
do_status() {
  local pid
  pid=$(find_pid)
  if is_running "$pid"; then
    echo -e "\033[32m$APP_NAME is running (PID=$pid, port=${SERVER_PORT})\033[0m"
    if health_ok; then
      echo -e "\033[32mhealth: UP\033[0m"
    else
      echo -e "\033[33mhealth: not ready (请查看 logs/stdout.log)\033[0m"
    fi
  elif port_in_use; then
    if health_ok; then
      echo -e "\033[32m$APP_NAME is running (PID 未匹配, port=${SERVER_PORT}; 可能由 systemd 等托管)\033[0m"
    else
      echo -e "\033[33m端口 ${SERVER_PORT} 被占用，但健康检查失败；请检查进程与 logs/stdout.log\033[0m"
    fi
  else
    echo -e "\033[33m$APP_NAME not running\033[0m"
  fi
}

case "$1" in
  start)   do_start ;;
  stop)    do_stop ;;
  restart) do_stop && do_start ;;
  status)  do_status ;;
  *) echo "Usage: $0 {start|stop|restart|status}"; exit 1 ;;
esac
exit $?