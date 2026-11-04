# AI-Ops Platform

基于 Spring Boot 2.7、Vue 3 的运维管理平台，提供资产管理、监控、告警、Web SSH、SQL 工作台、知识库和 AI 助手。

## 环境

- JDK 8、Maven 3.6+
- Node.js 20.19+、npm（建议使用 Node.js 22）
- MySQL 8（也可使用 H2 本地体验）

## 本地启动

在项目根目录生成并设置本机密钥。AES_KEY 需要持久保管，后续启动使用同一个值；更换它会使已有加密数据无法解密。

```bash
export AES_KEY="$(openssl rand -hex 32)"
export JWT_SECRET="$(openssl rand -base64 48 | tr -d '\n')"

# 终端 1：H2 会在 ./data 中创建本地数据库并初始化菜单和体验账号
mvn spring-boot:run -Dspring-boot.run.profiles=h2 \
  -Dspring-boot.run.arguments="--app.prometheus.embedded.enabled=false"

# 终端 2：前端
cd frontend
npm ci
npm run dev
```

浏览器访问 http://localhost:5173 。体验账号为 `admin / 123456`，使用后修改密码。AI 和监控服务在「系统设置」中自行配置，仓库不附带密钥和业务数据。

## MySQL

通过环境变量设置 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD`，自行创建数据库及账号。首次使用空库时，先执行 `src/main/resources/schema.sql` 和 `src/main/resources/data.sql`；已有业务库不要执行种子数据脚本，因为其中使用 REPLACE INTO。

```bash
export DB_URL='jdbc:mysql://127.0.0.1:3306/aiops?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&characterEncoding=utf8'
export DB_USERNAME=aiops
export DB_PASSWORD='<your-database-password>'
# 同时设置上述 AES_KEY 和 JWT_SECRET
mvn spring-boot:run -Dspring-boot.run.profiles=prod
```

## 构建与单进程运行

```bash
mvn -DskipTests package
cd frontend
npm ci
npm run build
cd ..
java -jar target/aiops-backend-1.0.0.jar \
  --spring.profiles.active=h2 \
  --app.frontend.dir=./frontend/dist \
  --app.prometheus.embedded.enabled=false
```

访问 http://localhost:8181 。MySQL 运行时把 profile 换为 `prod` 并配置数据库环境变量。

## 发布内容

仓库包含源码、建表及通用种子数据、部署样例和技术文档。环境中的密码、API Key、JWT 密钥、AES 主密钥必须通过本机环境变量或应用设置提供。文档中的 `192.0.2.x` 是示例地址，需要替换为自己的服务地址。

本地配置、数据库导出、知识库业务内容、运行数据库、日志、IDE 文件及隐私备份不进入版本控制。前端构建产物和依赖需要自行生成。

- [部署说明](deploy/README-split.md)
- [技术方案](docs/技术方案-v1.0.md)
- [架构图](docs/architecture-v1.0.png)
