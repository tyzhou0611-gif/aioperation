# AI-Ops Platform 内网离线部署包

> 黑盒交付：单个 fat jar（前端已内嵌）+ H2 文件库 + 本地 mock 数据源。
> 目标机 **无需外网、无需 Maven/npm、无需 Prometheus/Grafana/Redis/MySQL** 即可完整运行。
> 运行时依赖：**JDK 8**（Spring Boot 2.7 + Java 8 构建，字节码 major 52）。

## 目录结构
```
aiops-platform/
├── aiops-platform.jar        # 预构建 fat jar（含前端 static + 全部依赖）
├── application.yml           # 本地配置（与 jar 同级，自动优先生效，覆盖 jar 内默认）
├── config/
│   └── application.yml       # 覆盖位（优先级最高，用于单机微调/灰度）
├── springBoot.sh             # 启动脚本 start|stop|restart|status
├── data/                     # H2 文件库目录（运行时自动建 aiops.mv.db）
├── logs/                     # 运行日志 + GC 日志 + hs_err
└── README.md
```
> `config-cache/`、`runtime/`（Apollo 等）本方案不使用——平台自包含、不依赖配置中心，故省略。

## 前置要求
1. 安装 **JDK 8**（本平台已适配 Spring Boot 2.7 + Java 8）。
2. 授予脚本执行权限：`chmod +x springBoot.sh`

## 部署步骤
```bash
# 1) 上传本目录到内网机器，如 /u01/aiops-platform
cd /u01/aiops-platform

# 2) 指定 JDK8 路径（编辑脚本首行 JAVA_HOME，或环境变量）
export JAVA_HOME=/u01/jdk1.8.0_121

# 3) 启动
./springBoot.sh start

# 4) 查看状态 / 日志
./springBoot.sh status
tail -f logs/stdout.log

# 5) 停止 / 重启
./springBoot.sh stop
./springBoot.sh restart
```
访问：`http://<服务器IP>:8080`  默认账号 **admin / 123456**
健康自检：`curl http://localhost:8080/api/system/health`

## 端口冲突处理（8080 被占用）
若启动报 `Port 8080 is already in use`，换一个空闲端口启动：
```bash
SERVER_PORT=8181 ./springBoot.sh start     # 访问 http://<IP>:8181
```
查谁占了 8080：`ss -lntp | grep 8080` 或 `netstat -lntp | grep 8080`。

## 配置说明
- 端口、数据源、密钥、外部服务开关均在 **`application.yml`** 修改，改完 `restart` 生效。
- 首次启动 `admin/123456` 为演示密码，**上线前请在页面「系统设置/用户管理」修改**。
- JWT 密钥 `jwt.secret` 上线务必替换为自有随机 Base64 串。

## 可选：接入内网已有服务（增强，不影响离线运行）
| 能力 | 操作 |
|------|------|
| MySQL 替代 H2 | 见 `application.yml` 末尾注释块，切换 datasource |
| Prometheus 真实指标 | `app.metrics.source: prometheus` + `app.prometheus.url` |
| Grafana 大屏嵌入 | `app.grafana.url: http://<host>:3000` 且前端重新构建启用开关（默认关闭） |
| Loki / ES 日志 | `app.log.source: loki/elasticsearch` |
| 大模型智能诊断 | `app.ai.provider` + `app.ai.api-key`（留空=本地规则引擎降级） |

## 与启动脚本的 JDK 差异提示
原模板使用 CMS（`-XX:+UseConcMarkSweepGC`），**JDK14+ 已移除 CMS**，会导致 JVM 启动失败。
本脚本已改为 **G1GC**，并统一 GC 日志到 JDK9+ 的 `-Xlog:gc*` 格式；编码强制 `UTF-8`。

## 离线升级
替换 `aiops-platform.jar`（保留 `application.yml` 与 `data/` 目录），`./springBoot.sh restart` 即可，数据不丢。

## 如何对接内网 HiAgent（AI 运维助手大模型）
HiAgent 是**已登录会话**才能调用 `ChatQueryHomeBot`，匿名握手拿不到 `app-visitor-key` 会 401。平台已内置三步握手，但内网环境推荐**直接粘贴 F12 抓包得到的会话**最稳。

改 `application.yml` 的 `app.ai.hiagent`：
```yaml
app:
  ai:
    provider: hiagent
    hiagent:
      base-url: http://192.168.121.245:32300
      app-id: d6p2n16lvndd2j7hjvug
      cookie: "_csrf=...; x-csrf-token=...; tenant=s%3A...; connect.sid=..."   # 抓包请求头里的整串 Cookie
      csrf: "cCBF0joT-..."          # 抓包的 x-csrf-token
      visitor-key: ""               # 抓包 app-visitor-key（可选）
```
改完 `./springBoot.sh restart`。页面右下角 AI 助手顶部会显示「✓ 已连接 HiAgent」。

- 未配 HiAgent 或 HiAgent 不可达/会话过期 → 自动降级本地规则模式，应用照常运行（黑盒友好）。
- 会话过期（cookie/csrf 失效）→ 重新从浏览器复制新的 Cookie + x-csrf-token 粘贴回来重启即可。

### 抓包步骤（F12）
1. 浏览器登录 HiAgent，打开 llm-chat，F12 → Network。
2. 随便发一句话，找到 `Action=ChatQueryHomeBot` 的请求。
3. 复制 Request Headers 里的：`Cookie` 整串、`x-csrf-token`、`app-visitor-key`。
4. 填入 `application.yml` 对应字段。

### 最小验证 curl（确认协议/会话有效）
```bash
curl -N -X POST \
  'http://192.168.121.245:32300/api/bypass/app/?Version=2023-08-01&Action=ChatQueryHomeBot' \
  -H 'Content-Type: application/json; charset=utf-8' \
  -H 'Accept: application/json, text/event-stream' \
  -H 'x-csrf-token: <抓包csrf>' \
  -H 'app-visitor-key: <抓包key>' \
  -H 'origin: http://192.168.121.245:32300' \
  -H 'referer: http://192.168.121.245:32300/product/llm/llm-chat' \
  -b '<抓包Cookie>' \
  -d '{"message":"你好","bot_id":"d6p2n16lvndd2j7hjvug","stream":true}'
# 期望：返回若干 data: {...content...} 直至 data: [DONE]
```

> 平台后端 `HiAgentClient` 解析该 SSE：聚合 `data.content` / `data.answer`，遇 `[DONE]` 结束；连续失败≥3次自动降级，每 5 分钟探测恢复。
```
```