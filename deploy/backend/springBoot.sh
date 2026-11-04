#!/bin/bash
# =============================================================================
# AI-Ops Platform 后端启动脚本（前后端分离 · 纯 API 服务）
# 用法: ./springBoot.sh {start|stop|restart|status}
# 依赖: JDK 8+（Spring Boot 2.7）
# =============================================================================
APP_NAME=aiops-backend
# 目标机 JDK8 路径，按实际修改（或留空用系统 java）
JAVA_HOME=${JAVA_HOME:-/u01/jdk1.8.0_121}
# 服务端口：被占用时用环境变量改，例如 SERVER_PORT=8182 ./springBoot.sh start
SERVER_PORT=${SERVER_PORT:-8181}
# 运行环境：prod 关闭 SQL 日志与 H2 控制台
SPRING_PROFILES_ACTIVE=${SPRING_PROFILES_ACTIVE:-prod}

cd "$(dirname "$0")" || exit 1
BASEDIR=$(pwd)
LOGDIR=${BASEDIR}/logs
mkdir -p "${LOGDIR}" "${BASEDIR}/data"

# ---- 定位后端 jar ----
_JAR=$(ls ${APP_NAME}-*.jar 2>/dev/null | head -1)
if [ -z "${_JAR}" ]; then
  echo "[error] 未找到 ${APP_NAME}-*.jar，请确认与本脚本在同一目录"
  exit 1
fi

# 匹配进程
_JAR_KEYWORDS="${APP_NAME}-.*\.jar"

# ---- 解析 java（JAVA_HOME 有效则用，否则系统 java）----
if [ -x "${JAVA_HOME}/bin/java" ]; then JAVA_BIN="${JAVA_HOME}/bin/java"; else JAVA_BIN="java"; fi

# ---- 内存 ----
MEM_OPTS="-Xms2048m -Xmx2048m -XX:NewRatio=1 -XX:MetaspaceSize=256m -XX:MaxMetaspaceSize=512m"
MEM_OPTS="$MEM_OPTS -XX:+AlwaysPreTouch -Xss256k"

# ---- GC：JDK8 使用 CMS（与你们原模板一致）----
GC_OPTS="-XX:+UseConcMarkSweepGC -XX:+CMSParallelRemarkEnabled -XX:CMSInitiatingOccupancyFraction=80 -XX:+UseCMSInitiatingOccupancyOnly -XX:+UseCMSCompactAtFullCollection"

# ---- GC 日志（JDK8 传统格式）----
GCLOG="-Xloggc:${LOGDIR}/gc.log -XX:+PrintGCDetails -XX:+PrintGCDateStamps -XX:+PrintGCApplicationStoppedTime -XX:+UseGCLogFileRotation -XX:NumberOfGCLogFiles=5 -XX:GCLogFileSize=20M"

OPTIMIZE_OPTS="-XX:AutoBoxCacheMax=20000"
SHOOTING_OPTS="-XX:-OmitStackTraceInFastThrow -XX:ErrorFile=${LOGDIR}/hs_err_%p.log"

# 编码与时区（离线环境必须，中文不乱码）
ENC_OPTS="-Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai"
# 应用滚动日志目录（logback-spring.xml 使用）
LOG_OPTS="-Dlogging.file.path=${LOGDIR}"

export JAVA_OPTS="$MEM_OPTS $GC_OPTS $GCLOG $OPTIMIZE_OPTS $SHOOTING_OPTS $ENC_OPTS $LOG_OPTS"

# ---- 版本校验：需 JDK8 及以上 ----
if ! "$JAVA_BIN" -version 2>&1 | grep -qE '"(1\.[89]|10|[1-9][0-9])' ; then
  echo "[error] 需要 JDK8+，当前: $("$JAVA_BIN" -version 2>&1 | head -1)"
  echo "请设置 JAVA_HOME 指向 JDK8，例如: export JAVA_HOME=/usr/lib/jvm/java-1.8.0-openjdk"
  exit 1
fi

PID=$(ps aux | grep "java" | grep -E "${_JAR_KEYWORDS}" | grep -v grep | awk '{print $2}' | head -1)
check_if_process_is_running(){ if [ -z "$PID" ]; then return 1; fi; ps -p "$PID" | grep "java" > /dev/null; return $?; }

case "$1" in
  status)
    if check_if_process_is_running; then echo -e "\033[32m $APP_NAME is running (PID=$PID) \033[0m"
    else echo -e "\033[33m $APP_NAME not running \033[0m"; fi ;;
  stop)
    if ! check_if_process_is_running; then echo -e "\033[32m $APP_NAME already stopped \033[0m"; exit 0; fi
    kill "$PID"; echo -e "\033[32m Waiting for process to stop \033[0m"
    NOT_KILLED=1
    for i in $(seq 1 20); do if check_if_process_is_running; then echo -ne "."; sleep 1; else NOT_KILLED=0; break; fi; done; echo
    if [ $NOT_KILLED = 1 ]; then echo -e "\033[31m force kill \033[0m"; kill -9 "$PID"; exit 1; fi
    echo -e "\033[32m $APP_NAME already stopped \033[0m" ;;
  start)
    if [ -n "$PID" ] && check_if_process_is_running; then echo -e "\033[33m $APP_NAME already running \033[0m"; exit 1; fi
    nohup "$JAVA_BIN" -server $JAVA_OPTS -jar "$_JAR" \
      --server.port="$SERVER_PORT" \
      --spring.profiles.active="$SPRING_PROFILES_ACTIVE" \
      > "${LOGDIR}/stdout.log" 2>&1 &
    echo -ne "\033[32m Starting \033[0m"
    for i in $(seq 1 20); do echo -ne "\033[32m.\033[0m"; sleep 1; done; echo
    PID=$(ps aux | grep "java" | grep -E "${_JAR_KEYWORDS}" | grep -v grep | awk '{print $2}' | head -1)
    if check_if_process_is_running; then echo -e "\033[32m $APP_NAME started on port $SERVER_PORT \033[0m"
    else echo -e "\033[31m $APP_NAME fail, see ${LOGDIR}/stdout.log （若报 Port $SERVER_PORT is already in use，用 SERVER_PORT=<空闲端口> ./springBoot.sh start）\033[0m"; exit 1; fi ;;
  restart) "$0" stop; if [ $? = 1 ]; then exit 1; fi; "$0" start ;;
  *) echo "Usage: $0 {start|stop|restart|status}"; exit 1 ;;
esac
exit 0