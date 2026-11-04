# 指标注册中心（Metric Catalog）API

> 状态：方向一已实现（Prometheus / HTTP JSON / Push 三种数据源）。
> 告警引擎（方向二）后续直接复用本目录的 `code` 与定义。

## 1. 能力

| 数据源 | 说明 | 查询方式 |
| --- | --- | --- |
| `prometheus` | 任意 PromQL | 按时间范围返回多条序列 |
| `http_json` | 任意 HTTP JSON 接口 | 按 `jsonPath` 取当前值（单点） |
| `push` | 外部系统推送样本 | 按时间范围返回推送序列 |

统一返回结构：

```json
{
  "code": "node_cpu_usage",
  "name": "CPU 使用率",
  "unit": "pct",
  "source": "prometheus",
  "query": "100 - (avg by(instance) (...))",
  "start": 1793160000,
  "end": 1793163600,
  "step": 60,
  "times": [1793160000000, 1793160060000],
  "series": [
    { "name": "node-01", "labels": { "instance": "192.0.2.5:9100" }, "data": [4.2, 4.5] }
  ],
  "seriesCount": 1,
  "latest": 4.5,
  "latestLabel": "node-01",
  "error": null
}
```

- 多序列时 `latest`：`bps/bytes/count` 求和，`pct/num/ms` 求平均，`latestLabel` 为 `合计/平均`。
- 查询失败时返回 `error` 字段（HTTP 仍为 200），便于页面展示。

## 2. 鉴权

- 读接口：登录后的 JWT（`Authorization: Bearer <token>`）
- 指标定义增删改 / `preview`：需要 `settings:source` 权限
- `push`：当前登录即可；**下一阶段会增加 API Key**（`X-API-Key`），供外部脚本无 JWT 推送

获取 Token：

```bash
TOKEN=$(curl -s -X POST http://127.0.0.1:8181/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"123456"}' | jq -r .data.token)
```

## 3. 接口

### 3.1 指标目录

```bash
# 启用的指标（面板/告警下拉使用，不含敏感请求头）
GET /api/metrics/catalog

# 全部指标（含停用；非 settings:source 角色不回传 configJson）
GET /api/metrics/definitions
GET /api/metrics/definitions/{id}

# 新增 / 更新 / 删除
POST   /api/metrics/definitions
PUT    /api/metrics/definitions/{id}
DELETE /api/metrics/definitions/{id}
```

新增 Prometheus 指标：

```bash
curl -X POST http://127.0.0.1:8181/api/metrics/definitions \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{
    "code": "order_qps",
    "name": "订单 QPS",
    "description": "业务指标示例",
    "sourceType": "prometheus",
    "query": "sum(rate(order_total[5m]))",
    "unit": "count",
    "valueType": "gauge",
    "scopeType": "GLOBAL",
    "labelsJson": "[\"app\",\"env\"]",
    "thresholdsJson": "{\"warning\":100,\"critical\":50}",
    "enabled": 1
  }'
```

新增 HTTP JSON 指标：

```bash
curl -X POST http://127.0.0.1:8181/api/metrics/definitions \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{
    "code": "third_party_health",
    "name": "第三方健康值",
    "sourceType": "http_json",
    "query": "https://api.example.com/health",
    "configJson": "{\"method\":\"GET\",\"headers\":{\"Authorization\":\"Bearer xxx\"},\"jsonPath\":\"data.score\"}",
    "unit": "num",
    "enabled": 1
  }'
```

新增 Push 指标：

```bash
curl -X POST http://127.0.0.1:8181/api/metrics/definitions \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{
    "code": "order_qps",
    "name": "订单 QPS",
    "sourceType": "push",
    "unit": "count",
    "labelsJson": "[\"app\",\"env\"]",
    "enabled": 1
  }'
```

字段说明：

| 字段 | 说明 |
| --- | --- |
| `code` | 唯一编码，字母开头，2–100 位，可含 `_ . -` |
| `sourceType` | `prometheus` / `http_json` / `push` |
| `query` | PromQL 或 HTTP URL |
| `configJson` | HTTP JSON 配置：`method/headers/jsonPath/body` |
| `labelsJson` | 标签数组 JSON 字符串 |
| `unit` | `pct/bps/bytes/num/ms/count` |
| `valueType` | `gauge/counter` |
| `scopeType/scopeId` | `GLOBAL/PROJECT/ASSET/MIDDLEWARE/APP` + 范围 ID |
| `thresholdsJson` | 默认阈值 `{"warning":75,"critical":90}`，供方向二告警使用 |
| `builtin` | 内置指标（不可删除，可停用） |

### 3.2 查询

```bash
curl -X POST http://127.0.0.1:8181/api/metrics/query \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{
    "code": "node_cpu_usage",
    "range": "1h",
    "instance": "all",
    "vars": { "env": "prod" }
  }'
```

- `range`：`1h/6h/24h/7d`
- 自定义时间：`start/end`（epoch 秒，同时提供，跨度 1 分钟–31 天）
- `instance`：Prometheus 单主机过滤（`all` 表示全部）
- `vars`：替换查询中的 `$env`、`$project` 等变量；`$instance` 自动使用 `instance` 参数

### 3.3 预览（未保存的定义）

```bash
curl -X POST http://127.0.0.1:8181/api/metrics/preview \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{
    "definition": {
      "code": "preview_cpu",
      "name": "预览 CPU",
      "sourceType": "prometheus",
      "query": "100 - (avg by(instance) (rate(node_cpu_seconds_total{mode=\"idle\"}[15m])) * 100)",
      "unit": "pct"
    },
    "range": "1h",
    "instance": "all"
  }'
```

### 3.4 推送样本

```bash
# 单条
curl -X POST http://127.0.0.1:8181/api/metrics/push \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{
    "metric": "order_qps",
    "labels": { "app": "order", "env": "prod" },
    "value": 135,
    "timestamp": 1793171113
  }'

# 批量（最多 1000 条）
curl -X POST http://127.0.0.1:8181/api/metrics/push \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{
    "samples": [
      {"metric":"order_qps","labels":{"app":"order","env":"prod"},"value":100,"timestamp":1793170993},
      {"metric":"order_qps","labels":{"app":"order","env":"prod"},"value":135,"timestamp":1793171113}
    ]
  }'
```

- `timestamp` 支持秒或毫秒，缺省为当前时间
- 只允许推送到 `sourceType=push` 的已注册指标
- 返回 `accepted/rejected/errors`

## 4. 内置指标

首次启动自动写入 12 个 node_exporter 指标（不覆盖用户修改）：

`node_cpu_usage`、`node_memory_usage`、`node_disk_usage`、`node_inode_usage`、`node_load1`、
`node_network_rx_bps`、`node_network_tx_bps`、`node_tcp_established`、`node_procs_running`、
`node_filefd_allocated`、`node_iowait`、`node_up`。

## 5. 页面入口

- 系统设置 → **指标目录**：增删改、测试查询、预览图表
- 仪表盘 → 监控面板 → 自定义面板 → 重点指标：**从指标目录导入**（仅 Prometheus 类型）

## 6. 后续（方向二/三）

- 告警规则直接引用 `metric_definition.code` + `expr`，支持 `for/severity/scope/groupBy`
- API Key + OpenAPI/Swagger，供外部系统无 JWT 推送/查询
- SQL 数据源、Alertmanager webhook、告警去重/静默/确认