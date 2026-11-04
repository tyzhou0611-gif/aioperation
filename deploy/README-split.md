# AI-Ops Platform 前后端分离部署指南

本项目已改为**前后端完全分离**：

```
浏览器
  │
  ├── 前端静态资源（Vue 3 + Vite 构建产物）── Nginx / CDN
  │
  └── /api/**  ── Nginx 反向代理 ── 后端 Spring Boot jar（8181，纯 API + H2/MySQL）
```

- 后端 jar **不再包含**前端代码，`mvn package` 不触发 npm 构建。
- 前端与后端可独立构建、独立发版、独立扩容。
- 推荐**同源反向代理**部署（无需 CORS）；也支持前端直连后端（跨域，需配置 CORS）。

---

## 1. 构建产物

在项目根目录执行：

```bash
./package.sh all        # 后端 + 前端
./package.sh backend    # 只构建后端
./package.sh frontend   # 只构建前端
```

产物位于 `dist/`：

| 文件 | 说明 |
| --- | --- |
| `aiops-backend-<version>.jar` | 后端可执行 jar（纯 API） |
| `aiops-backend-<version>.tar.gz` | 后端部署包：jar + `springBoot.sh` + 配置样例 + `data/` `logs/` |
| `aiops-frontend-<version>.tar.gz` | 前端静态包：`index.html` + `assets/` + `nginx.conf.sample` |

环境要求：构建机需 JDK 8+、Maven 3.6+、Node.js 18+ / npm。

---

## 2. 本地开发（前后端分别启动）

```bash
# 终端 1：后端（8181）
mvn spring-boot:run

# 终端 2：前端（5173，Vite 已把 /api 代理到 8181）
cd frontend
npm install
npm run dev
```

浏览器访问 <http://localhost:5173>，默认账号 `admin / 123456`。

本地验证“分离部署”效果（构建产物 + 后端）：

```bash
./package.sh all
java -jar dist/aiops-backend-1.0.0.jar
cd frontend && npm run preview   # 4173，preview 同样把 /api 代理到 8181
```

---

## 3. 生产部署（推荐：同源反向代理）

### 3.1 后端

```bash
tar -xzf aiops-backend-<version>.tar.gz
cd aiops-backend-<version>

cp application.yml.sample application.yml   # 按需修改端口、数据源、CORS
chmod +x springBoot.sh
./springBoot.sh start

./springBoot.sh status
tail -f logs/stdout.log
```

健康检查：

```bash
curl http://127.0.0.1:8181/api/system/health
```

### 3.2 前端

```bash
# 解压到 Nginx 站点目录
mkdir -p /usr/share/nginx/html/aiops
tar -xzf aiops-frontend-<version>.tar.gz -C /tmp
cp -r /tmp/aiops-frontend-<version>/* /usr/share/nginx/html/aiops/

# 安装 Nginx 配置
cp nginx.conf.sample /etc/nginx/conf.d/aiops.conf
# 按实际修改：root 路径、server_name、后端地址 127.0.0.1:8181
nginx -t && nginx -s reload
```

访问 `http://<前端服务器IP>/`。

> Nginx 配置已包含 Vue History 路由回退、静态资源缓存、`/api/` 反向代理、gzip。
> 前端和后端同源，因此**不需要 CORS**。

---

## 4. 可选：前端跨域直连后端

适用于前端部署在 CDN / 独立域名，无法做同源代理的场景。

1. 构建前端时指定后端地址（末尾不要带 `/api`）：

```bash
cd frontend
VITE_API_BASE_URL=http://192.0.2.6:8181 npm run build
# 或在 .env.production 中写：VITE_API_BASE_URL=http://192.0.2.6:8181
```

2. 后端放行前端来源（`application.yml` 或环境变量）：

```yaml
app:
  cors:
    allowed-origin-patterns: https://aiops.example.com,http://192.0.2.8
```

```bash
export CORS_ALLOWED_ORIGIN_PATTERNS="https://aiops.example.com"
./springBoot.sh restart
```

生产环境建议把 CORS 白名单收窄到具体域名，不要使用 `*`。

---

## 5. 关键配置

| 配置 | 位置 | 说明 |
| --- | --- | --- |
| `VITE_API_BASE_URL` | 前端 `.env` / `.env.production` | 留空=同源代理；填写=跨域直连后端 |
| `app.cors.allowed-origin-patterns` | 后端 `application.yml` / `CORS_ALLOWED_ORIGIN_PATTERNS` | 允许的前端来源 |
| `server.port` | 后端 `application.yml` / `SERVER_PORT` | 后端端口，默认 8181 |
| `spring.profiles.active` | `springBoot.sh` 默认 `prod` | `prod` 关闭 SQL 日志与 H2 控制台 |

### 5.1 运行期配置（推荐在页面修改，无需改配置文件）

以下配置已持久化在 `sys_config` 表，页面保存后**即时生效**；`application.yml` 中的值只作为首次启动的默认值，均可留空：

| 配置项 | 页面位置 | 环境变量默认值 |
| --- | --- | --- |
| AI Provider / API URL / Model | 系统设置 → AI 服务 | `AI_PROVIDER` / `ANTHROPIC_BASE_URL` / `AI_MODEL` |
| AI API Key | 系统设置 → AI 服务（AES 加密存储，页面不回显） | `ANTHROPIC_AUTH_TOKEN`（建议留空，首次在页面录入） |
| 指标源（Prometheus / 本地） | 系统设置 → 外部数据源 | `METRICS_SOURCE` |
| Prometheus URL | 系统设置 → 外部数据源（保存即生效） | `PROMETHEUS_URL` |
| Grafana URL | 系统设置 → 外部数据源 | `GRAFANA_URL` |

安全与日志相关环境变量：

| 环境变量 | 说明 |
| --- | --- |
| `AES_KEY` | 敏感配置加密密钥（AI Key、数据库/ELK/SSH 密码）。**生产必须设置**，更换会导致历史密文无法解密 |
| `LOG_DIR` | 日志目录，默认 `./logs`（`springBoot.sh` 会自动传入） |
| `APP_LOG_LEVEL` | 应用日志级别，默认 `INFO`（排查问题时可临时设 `DEBUG`） |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | 数据源连接 |

日志文件为 `logs/aiops.log`（UTF-8），按天 + 50MB 滚动，保留 30 天，总量上限 2GB。

---

## 6. 与旧版单 jar 的差异

| 项目 | 旧版 | 新版 |
| --- | --- | --- |
| 后端 jar | 含前端 static（约 57 MB） | 纯 API（体积更小） |
| 前端发布 | 需重新打 jar | 只替换 Nginx 静态文件 |
| 开发构建 | `mvn package` 触发 npm | 后端/前端各自构建 |
| 路由回退 | 后端 VueRoutingFilter | Nginx `try_files` |
| CORS | 硬编码 | 配置化，同源部署可不用 |