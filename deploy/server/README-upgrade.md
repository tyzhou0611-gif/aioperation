# AI-Ops Platform 升级说明（2026-10-28 版本）

适用现有服务器目录：`/u01/aiops-platform`（后端 jar + web 部署）。

## 2026-10-28 版本变更

1. **监控面板可配置**：仪表盘 → 监控面板 → 自定义面板，可勾选全部概览卡片/图表/主机表、调整图表宽度；新增“重点指标”开发功能，支持自定义 PromQL、单位、图形、颜色、重点卡片和从指标目录导入。配置存 `sys_config.monitor.panel.config`，无需改表。
2. **指标注册中心（Metric Catalog）**：系统设置 → 指标目录，统一维护 Prometheus / HTTP JSON / Push 指标；新增接口 `GET/POST/PUT/DELETE /api/metrics/definitions`、`GET /api/metrics/catalog`、`POST /api/metrics/query|preview|push`。首次启动自动创建 `metric_definition` / `metric_sample` 表并写入 12 个 node_exporter 内置指标，**无需手工迁移**。接口说明见包内 `metric-catalog-API.md`。
3. **HiAgent OpenAPI**：HiAgent 支持 `openapi`（chat_query_v2 + Apikey）模式，内网参数已写死，可在设置页测试；旧 `bypass` 抓包模式保留。
4. **AI Provider 测试修复**：切换 Provider 后测试不会再复用上一个服务的 URL/Key；测试结果回显真实目标（provider/URL/app_id）。
5. **Push 指标样本保留**：默认保留 30 天，可通过 `METRIC_SAMPLE_RETENTION_DAYS` 调整，每天 03:17 自动清理。
6. **Prometheus Exporter 一键安装**（服务器管理 → Agent 安装）：
   - 上传官方/社区离线包（`tar.gz/tgz/tar.xz/tar`），选择一台或多台服务器（单次最多 30 台），通过 SFTP + systemd 自动安装
   - 内置 node、blackbox、mysqld、postgres、redis、ES、Kafka、nginx、mongodb、rabbitmq、process、cadvisor、haproxy、memcached、statsd、pushgateway、snmp 等目录；也支持“自定义 Exporter”安装任意 tar.gz
   - 自动校验 sha256、复制包内配置文件（blackbox.yml / snmp.yml / process-exporter.yml 等）、创建运行用户、systemd 服务、健康检查、可选开放防火墙；重装即升级，配置默认保留
   - 安装记录包含版本/端口/时间/结果；“已安装”页签可卸载（删除远端服务和目录）或删除记录
   - 安装成功后可通过 `GET /api/monitor/targets`（Prometheus HTTP SD）自动发现目标；建议设置 `AGENT_SD_TOKEN`
   - 离线包目录 `AGENT_PACKAGE_DIR`（默认 `./data/agent-packages`），不支持 Windows Exporter 一键安装
7. **资产管理新增 FTP 服务器**（资产管理 → FTP 服务器）：
   - 支持 FTP / SFTP 资产、AES 加密密码、测试连接、远程目录浏览
   - “拉取到本地”先进入平台暂存区，可下载到浏览器或通过 SFTP **分发到受管服务器**（支持覆盖、权限位、后置命令 `{file}`）
   - 暂存目录 `FTP_STAGING_DIR`（默认 `./data/ftp-staging`）；FTP 为明文协议，建议内网或使用 SFTP
   - 菜单/按钮权限由启动时的 `AssetMenuInitializer` 自动补齐，无需手工改库
8. **资产标签**：服务器、中间件/数据库资产新增标签编辑、列表展示和关键字检索；不再限制 3 个/10 字，当前上限为 30 个标签、单个 50 字、总长度 500 字。`schema.sql` 自动新增 `server_asset.tags` / `server_agent.version` 列。
9. **智能大屏默认分层布局**：拓扑图默认按「应用在上、中间件居中、服务器在底部」固定区域展示，层内自动排行并换行，减少力导向节点堆叠；支持拖拽层内调整，拖出所属层会自动回到原位置；仍保留「力导向 / 环形」布局和「重排」按钮。
10. **知识库页面直接写文档**：知识库 → 文档新增「+ 新建文档」和「编辑」：
    - 页面内填写标题、相对路径（如 `运维/MySQL排查.md`）和 Markdown 正文，保存即写入磁盘并自动切块
    - 支持 `[[双链]]`、`#标签`、frontmatter；保存后知识树/图谱/检索同步更新
    - 同名新建会自动加 `-2` 后缀；编辑时可改文件名，同库重名会提示；单篇上限 2MB
    - 新增接口 `POST /api/kb/{id}/doc`、`PUT /api/kb/doc/{docId}`，权限沿用 `kb:upload`
11. **内置 Prometheus（含二进制）**：部署包新增 `prometheus/linux-amd64/`（Prometheus 3.15.1，约 250MB 解压体积）：
    - 系统设置 → Prometheus 可切换「内置 / 外部」；内置模式随平台自动启动，地址为 `http://127.0.0.1:9090`
    - 页面可直接启动/停止/重启/热加载、查看 targets、编辑 `prometheus.yml`（保存前用 promtool 校验）、恢复平台默认配置
    - 支持图形化新增/编辑/删除静态抓取任务（目标、metrics_path、间隔、标签）；平台 Agent 安装的 Exporter 通过 HTTP SD 自动发现，无需手改
    - 从外部切换到内置时，会自动把外部当前 active targets 快照导入内置配置，避免切换后历史主机指标立刻空白
    - 相关配置：`PROMETHEUS_MODE=embedded|external`、`PROMETHEUS_PORT`、`PROMETHEUS_DATA_DIR`、`PROMETHEUS_CONFIG_FILE`、`PROMETHEUS_RETENTION`、`PROMETHEUS_PUBLIC_URL`
    - **从 Prometheus 同步服务器改为严格增量**：只新增缺失 IP、更新已有主机的状态/来源/探测时间；主机名、环境、SSH、标签、项目等人工字段永不覆盖；`MAINTENANCE` 状态保留；只有原本由 Prometheus 管理的 ONLINE 主机在消失时才标记 OFFLINE
    - 内置模式需要应用服务器可写 `data/prometheus/`、可执行 `prometheus/linux-amd64/prometheus`；如端口 9090 被占用请修改 `PROMETHEUS_PORT`

> 升级方式不变：备份后替换 `aiops-platform.jar` 与 `web/`，保留 `application.yml`、`config/`、`data/`、`logs/`，执行 `./springBoot.sh restart`。新表/新列由 `schema.sql` 自动创建。

## 2026-10-26 版本变更

1. **演示数据清理**：代码不再预置演示项目/服务器/模拟日志；生产库请自行确认并清理历史演示数据。
2. **智能诊断接入大模型**：新增 LLM 诊断（失败自动回退规则引擎），页面显示实际引擎/模型/置信度/耗时。
3. **AI 助手形象**：右下角悬浮按钮与助手内统一为「小维」形象，带眨眼/思考/说话动画。
4. **敏感配置加密**：AI API Key、HiAgent Cookie/CSRF、SSH/数据库/ELK 密码均 AES 加密存储，接口不回显明文；
   启动时自动把库中历史明文迁移为密文。
5. **Prometheus 页面可配**：系统设置 → 外部数据源 可修改 Prometheus 地址并真实测试，保存即时生效（无需重启）。
6. **日志治理**：默认 INFO、关闭 SQL 日志；新增 `logs/aiops.log`（UTF-8，按天 + 50MB 滚动，保留 30 天，总量 2GB）。
7. **ELK 集群分片**：ELK 管理新增「集群分片」标签页，查看集群健康、节点分配、全部分片及未分配原因，
   支持按索引/状态/主副本/节点筛选（`/asset/middleware/elk?tab=shards&connectionId=<id>`）。
8. **SQL 工作台连接改由资产管理同步**：不再支持在 SQL 工作台手工新建/编辑/删除连接；
   连接统一来自「资产管理 → 中间件/数据库管理 → 数据库类」，该资产新增 数据库账号/密码/默认库 字段，
   密码 AES 加密、页面不回显。工作台打开时自动同步，也可点「从资产管理同步」手动刷新；
   历史手工连接会在首次同步时自动迁移为数据库资产（保留原加密密码），避免连接丢失。
9. **中间件实例详情页**：列表中每个实例可点「管理」进入独立页面（`/asset/middleware/<id>`）：
   - Kafka：Broker 节点 TCP 连通状态与延迟
   - ZooKeeper：四字命令（ruok/srvr/mntr/conf）通信状态、连接数、延迟、znode、集群 peers
   - Redis：Cluster/Standalone 模式、主从数量、节点分布、槽位、链路状态（支持角色筛选）
   - Hadoop：YARN REST 集群信息、节点资源、运行中应用，并可一键跳转 YARN 控制台
   - 通用：节点列表 TCP 探测、自动刷新（10s）、管理控制台跳转
   资产新增「节点列表」「控制台地址」字段；Redis 可配置访问账号/密码（AES 加密）。
   注：Kafka 未内置客户端，Broker 列表来自资产配置并做 TCP 探测；ZooKeeper 四字命令需服务端 whitelist 开启。
10. **智能大屏连线交互优化**：
   - 编辑模式支持拖拽节点到目标节点连线（实时虚线预览、目标吸附、松开即弹关系配置）
   - 保留点击源/目标连线；点击空白、Esc 可取消；不支持的方向（如服务器→应用）会明确提示
   - 连线自动使用完整资产类型（MIDDLEWARE/SERVER/APP），修复了此前缩写导致“无合法关系类型、保存按钮禁用”的问题
   - 连线存在重复时自动打开编辑；自动推导的虚线可一键确认转为手动关系
   - 点击连线弹出操作菜单（编辑/删除/确认），代替原来的多层确认框
   - 节点较多（>20）时默认环形布局，保证节点全部可见可点；关系增删后保持节点位置，避免整图跳动；新增「重排」按钮
11. **智能诊断对象扩展**：不再只支持服务器，可选服务器 / Kafka、ZooKeeper、Redis、数据库等中间件 / 应用，
    也可选择「自定义诊断」直接描述问题（如 Kafka 消费延迟怎么排查），并支持填写补充说明。
    中间件诊断会自动采集节点连通性、ZK/Redis/YARN/Kafka 等实时状态作为大模型上下文；诊断历史显示诊断对象。
12. **运维执行新增 Web SSH 终端**（菜单：运维执行 → Web 终端）：
    - 选择服务器后打开交互式 shell，支持 PTY、窗口缩放、全屏、清屏、字号调整，类似 Xshell
    - 使用资产中已保存的 SSH 密码/私钥；仅允许访问当前角色有权限的服务器；会话记录审计日志
    - 交互式终端会绕过命令安全校验，请谨慎使用
    - SSH 客户端已升级为 mwiede JSch 0.2.20，支持 rsa-sha2 / curve25519 等现代算法（解决旧版连不上 OpenSSH 8.8+ 的问题）
    - **已有数据库需要新增菜单**：执行 `mysql-init/03-add-web-ssh-menu.sql`（或到 系统管理 → 菜单管理 手动添加），
      然后重新登录加载菜单；`data.sql` 已包含该菜单，新库无需额外操作
13. **服务器管理新增「复制」**：在服务器列表点「复制」可基于现有服务器快速新增一台：
    - 自动预填操作系统、环境、SSH 端口/用户、所属项目等；IP 和主机名需按新服务器修改
    - SSH 密码留空则**复用源服务器的加密密码**（服务端直接复制密文，页面/接口都不回显明文）；也可输入新密码覆盖
    - SSH 私钥同样支持沿用；目标 IP 重复会被拒绝
    - 适合批量添加密码相同/相近的服务器
14. **服务器 / Web 终端凭据联动优化**：
    - 服务器列表新增「SSH连接」列，直接显示 `用户@IP:端口` 和凭据状态（密码 / 私钥 / 未配置）
    - 移除与状态列重复的「状态」按钮；移除与「自动探测」重复的「SSH测试」按钮（保留功能更完整的自动探测）
    - Web 终端下拉显示 SSH 用户与凭据状态；打开下拉或点击连接前自动刷新服务器详情，
      在其它页面刚补完密码/私钥后无需手动刷新即可识别，不再显示过期的“未配置凭据”
    - Web 终端工具栏新增「刷新」按钮；未配置凭据时直接提示并引导到服务器编辑页
    - 命令执行本身每次都由后端读取最新服务器凭据，不缓存密码
15. **服务器状态探测增强**：
    - 状态来源可在「系统设置 → 外部数据源」选择：Prometheus（up 指标）/ SSH（登录认证）/ ICMP（ping），保存即生效
    - 服务器列表新增「最后探测」时间；新建服务器默认状态为 UNKNOWN，探测后才变更
    - 服务器页面支持 **60 秒自动探测**（默认开启，可关闭；页面不可见或弹窗打开时自动跳过）
    - 批量探测按当前用户可访问的服务器并行执行；SSH 无凭据时返回 UNKNOWN，不误判为离线
    - ICMP 依赖应用服务器能执行 `ping`；Prometheus 模式依赖对应主机存在 `up` 指标
    - 启动时 `schema.sql` 自动新增 `server_asset.last_probe_time` 列，无需手工迁移

### 部署前检查（重要）

- **备份**：数据库、`aiops-platform.jar`、`web/`、`application.yml`、`logs/`。
- **外置 application.yml 优先级更高**：请确认 `spring.datasource` 指向生产库；建议删除其中明文 `app.ai.api-key`
  （新版本会加密迁移到数据库）并把 `logging.level.com.aiops` 设为 `INFO`，否则日志仍可能是 DEBUG。
  可参考本包 `application.yml.sample`，其中可集成项均可留空、改由页面配置。
- **首次启动前设置 `AES_KEY`**：用于加密敏感配置。设置后再录入各类密码/Key；中途更换会导致旧密文无法解密，需重新录入。
- 运行环境仍为 **JDK 8**。

## 包内容

| 文件 | 说明 |
| --- | --- |
| `aiops-platform.jar` | 新后端 jar（不再内嵌前端） |
| `web/` | 前端静态资源（必须保留） |
| `springBoot.sh` | 新启动脚本，检测到 `web/` 会自动启用后端托管前端 |
| `application.yml.sample` | 新配置参考，**不要覆盖现有 application.yml** |
| `nginx.conf.sample` | 可选：改用 Nginx 托管前端时的配置 |
| `README-升级说明.md` | 本文件 |

> 本包**不包含** `application.yml`、`config/`、`data/`、`logs/`，升级不会覆盖这些内容。

## 重要变更

1. 前端已从 jar 中拆出，必须解压出 `web/` 目录，并使用新 `springBoot.sh`。
   脚本会自动追加 `--app.frontend.dir=/u01/aiops-platform/web`，由后端直接提供页面，**无需额外安装 Nginx**。
2. 如果仍使用旧 `springBoot.sh`，页面会 404。
3. 首次启动会执行 `schema.sql` / `data.sql`：
   - 新建 `project` / `project_asset` 等表
   - 更新菜单（ELK 移入资产管理、系统设置置底等）
   - 不会删除已有业务数据
4. 运行环境仍为 **JDK 8**。
5. AI 运维助手已接入 Anthropic 兼容网关：
   - AI 配置保存在数据库 `sys_config`（API Key 加密存储），可在 **系统设置 → AI 服务** 修改
   - 一键迁移（`app.ai.bootstrap.*`）默认关闭，避免覆盖页面配置
   - 请确保服务器能访问 AI 网关地址（默认 `http://192.0.2.11:3000`）
6. 命令执行已升级：
   - 多机并发 + 矩阵结果 + 失败重试
   - WebSocket 实时输出与中途终止（/ws/exec，由后端直接托管，无需额外配置；Nginx 模式已包含 /ws 代理）
   - 危险命令黑名单、生产环境高危命令强制先 dry-run 预览
   - 参数化模板、执行历史检索/CSV 导出、JSON/表格/Diff 结果视图
7. 服务器管理增强：
   - 保留 Prometheus 同步
   - 新增 Xshell(.xsh) / SecureCRT(.ini) / OpenSSH config / CSV / JSON / ZIP 导入（密码加密格式不导入，可统一填写）
   - 新增 SSH 连接测试与自动探测（主机名/OS/CPU/内存/磁盘），确认后写回服务器资料
8. 数据存储（本项目已切换 MySQL）：
   - 默认数据源为 MySQL：`DB_URL` / `DB_USERNAME` / `DB_PASSWORD`（默认 `jdbc:mysql://127.0.0.1:3306/aiops`、`aiops`、`aiops=123`）
   - MySQL 模式只自动建表，不自动写种子数据；首次初始化执行：
     ```bash
     mysql --force -u aiops -p aiops < schema.sql
     mysql --force -u aiops -p aiops < data.sql
     ```
   - 需要 H2 离线模式时：`--spring.profiles.active=h2`
   - 旧 H2 数据迁移到 MySQL：`migrate/H2ToMySQL.java`（编译后传入 H2/MySQL JDBC 地址执行）
   - 服务器上如仍使用旧 `application.yml`（H2），该文件优先级更高，会继续使用 H2；要切 MySQL 请修改该配置或设置 `DB_URL/DB_USERNAME/DB_PASSWORD` 并去掉 H2 datasource

## 升级步骤

```bash
cd /u01/aiops-platform
./springBoot.sh status

# 1) 停止服务
./springBoot.sh stop

# 2) 备份（强烈建议先备份 data）
cp aiops-platform.jar aiops-platform.jar.bak-20261022
cp springBoot.sh springBoot.sh.bak-20261022
cp -r data data.bak-20261022

# 3) 解压升级包到临时目录
mkdir -p /tmp/aiops-upgrade
tar -xzf aiops-platform-deploy-1.0.0.tar.gz -C /tmp/aiops-upgrade

# 4) 覆盖 jar / 启动脚本，并放入 web 目录
cp /tmp/aiops-upgrade/aiops-platform/aiops-platform.jar .
cp /tmp/aiops-upgrade/aiops-platform/springBoot.sh .
rm -rf web
cp -r /tmp/aiops-upgrade/aiops-platform/web .
chmod +x springBoot.sh

# 5) 启动
./springBoot.sh start
./springBoot.sh status
tail -f logs/aiops.log        # 新版滚动日志（stdout.log 仅作兜底）
```

> 如果服务器使用 systemd 管理进程，请用对应的 `systemctl restart <service>` 重启；
> 但要确保启动命令包含 `--app.frontend.dir=/u01/aiops-platform/web`，或直接用本包的 `springBoot.sh`。

## 验证

```bash
# 健康检查
curl http://127.0.0.1:8181/api/system/health

# 首页应返回 HTML（包含 /assets/index-*.js）
curl -I http://127.0.0.1:8181/

# 前端子路由也能返回 HTML（Vue History 回退）
curl -I http://127.0.0.1:8181/dashboard
```

浏览器访问：`http://<服务器IP>:8181/`，默认账号 `admin / 123456`。

> 端口说明：脚本默认 `8181`，可用 `SERVER_PORT=8080 ./springBoot.sh start` 覆盖。
> 命令行端口优先级高于 `application.yml` / `config/application.yml`。

## 回滚

```bash
./springBoot.sh stop
cp aiops-platform.jar.bak-20261022 aiops-platform.jar
cp springBoot.sh.bak-20261022 springBoot.sh
# 如需恢复数据库（H2）：
# rm -rf data && mv data.bak-20261022 data
./springBoot.sh start
```

## 常见问题

- **页面 404 / 只有 JSON**：确认 `web/index.html` 存在，且使用新 `springBoot.sh` 启动。
- **端口被占用**：`SERVER_PORT=<空闲端口> ./springBoot.sh start`。
- **启动失败**：查看 `logs/aiops.log` 与 `logs/stdout.log`；确认 JDK8 可用（`java -version`）。
- **日志过大**：确认外置 `application.yml` 未把 `logging.level.com.aiops` 覆盖为 `DEBUG`；滚动文件位于 `logs/aiops.log`。
- **菜单没变化**：确认 `application.yml` 中 `spring.sql.init.mode=always`（原部署包默认是），并查看启动日志中的 SQL 初始化错误。
- **Grafana 看板**：新版仪表盘已改为原生 Prometheus 监控面板，不再依赖 Grafana 登录。
- **ELK 管理**：菜单路径变为「资产管理 → 中间件管理 → ELK管理」。
## 内置 Prometheus 二进制与「一键启动」

平台会自动按以下顺序查找 Prometheus 二进制（`prometheus` / `prometheus.exe`）：

1. `prometheus/linux-amd64/prometheus`（部署包自带，最优先）
2. `prometheus/prometheus`
3. `data/prometheus/prometheus`（可直接把官方包解压到这里）
4. `data/prometheus/prometheus-<version>.linux-amd64/prometheus`（官方解压目录结构）

如果只上传了安装包（`prometheus-*.linux-amd64.tar.gz` / `.tgz` / `.tar.xz` / `.zip`）到 `data/prometheus/`，
在「仪表盘 → 监控面板」工具栏点击 **一键启动 Prometheus**（或 系统设置 → Prometheus → 启动），
平台会自动解压到 `data/prometheus/` 并拉起进程，无需手工安装。

- 配置固定读取 `data/prometheus/prometheus.yml`，TSDB 写入 `data/prometheus/`。
- 启动状态可在「监控面板」工具栏的 Prometheus 状态胶囊查看；未运行时会出现黄色提示条和一键启动按钮。
- 若 9090 端口上已有非本平台启动的 Prometheus（例如后端被强杀后遗留的子进程），平台会直接复用该实例，不会重复拉起。
- 一键启动/重启需要 `settings:source` 权限（平台运维/超级管理员）。
