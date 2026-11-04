<template>
  <div class="mw-detail" v-loading="loading">
    <div class="detail-head">
      <div class="head-left">
        <el-button link class="back" @click="goBack">
          <el-icon><ArrowLeft /></el-icon>返回中间件列表
        </el-button>
        <h2>{{ asset?.name || '中间件详情' }}</h2>
        <el-tag v-if="asset" size="small">{{ asset.type }}</el-tag>
        <el-tag v-if="asset" size="small" :type="statusType(asset.status)">{{ statusLabel(asset.status) }}</el-tag>
      </div>
      <div class="head-actions">
        <el-button v-permission="'asset:middleware:edit'" v-if="asset" @click="editAsset">编辑资产</el-button>
        <el-button v-if="consoleUrl" type="primary" plain @click="openConsole">打开管理控制台</el-button>
        <el-button :loading="loading" @click="load">刷新</el-button>
        <el-switch v-model="autoRefresh" active-text="自动刷新" />
      </div>
    </div>

    <div class="meta-line" v-if="asset">
      <span>{{ asset.ip || '-' }}:{{ asset.port || '-' }}</span>
      <span>环境：{{ asset.env || '-' }}</span>
      <span v-if="asset.version">版本：{{ asset.version }}</span>
      <span v-if="asset.clusterName">集群：{{ asset.clusterName }}</span>
      <el-tag v-for="t in tagList" :key="t" size="small" effect="plain">#{{ t }}</el-tag>
      <span v-if="lastUpdated">更新于 {{ lastUpdated }}</span>
    </div>

    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" style="margin-bottom:12px" />

    <!-- 指标卡片 -->
    <div class="stat-grid" v-if="statCards.length">
      <div class="stat-card" v-for="(c, i) in statCards" :key="i">
        <div class="k">{{ c.label }}</div>
        <div class="v" :class="c.tone">{{ c.value }}</div>
        <div class="sub" v-if="c.sub">{{ c.sub }}</div>
      </div>
    </div>

    <!-- 节点连通性（通用；Kafka 在下方 Broker 区域展示） -->
    <el-card v-if="type !== 'KAFKA'" shadow="never" class="section">
      <template #header>
        <div class="section-head">
          <span>节点连通性</span>
          <span class="hint">TCP 探测，超时 2s；节点来自资产配置的节点列表</span>
        </div>
      </template>
      <el-table :data="nodes" size="small" stripe>
        <el-table-column prop="target" label="节点" min-width="190" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="row.reachable ? 'success' : 'danger'">
              {{ row.reachable ? '可达' : '不可达' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="延迟" width="110">
          <template #default="{ row }">{{ row.reachable ? (row.latencyMs + ' ms') : '-' }}</template>
        </el-table-column>
        <el-table-column prop="error" label="错误" min-width="220" show-overflow-tooltip />
      </el-table>
      <el-empty v-if="!nodes.length" description="未配置节点（可在资产编辑中填写节点列表）" :image-size="60" />
    </el-card>

    <!-- ZooKeeper -->
    <template v-if="type === 'ZOOKEEPER'">
      <el-card shadow="never" class="section">
        <template #header><div class="section-head"><span>ZooKeeper 通信状态</span><span class="hint">四字命令 srvr / mntr / conf</span></div></template>
        <el-alert v-if="zk?.error" :title="'部分四字命令不可用：' + zk.error" type="warning" show-icon :closable="false" style="margin-bottom:10px" />
        <el-descriptions :column="3" border size="small">
          <el-descriptions-item label="模式">{{ zk?.mode || '-' }}</el-descriptions-item>
          <el-descriptions-item label="版本">{{ zk?.version || '-' }}</el-descriptions-item>
          <el-descriptions-item label="活跃连接">{{ zk?.connections ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="平均延迟">{{ zk?.avgLatency != null ? zk.avgLatency + ' ms' : '-' }}</el-descriptions-item>
          <el-descriptions-item label="最大延迟">{{ zk?.maxLatency != null ? zk.maxLatency + ' ms' : '-' }}</el-descriptions-item>
          <el-descriptions-item label="未完成请求">{{ zk?.outstanding ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="Znode 数">{{ zk?.znodeCount ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="Followers">{{ zk?.followers ?? '-' }} / 已同步 {{ zk?.syncedFollowers ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="Watcher 数">{{ zk?.watches ?? '-' }}</el-descriptions-item>
        </el-descriptions>

        <div class="section-title" v-if="zk?.peers?.length">集群节点</div>
        <el-table v-if="zk?.peers?.length" :data="zk.peers" size="small" stripe>
          <el-table-column prop="id" label="Server ID" width="100" />
          <el-table-column label="地址" min-width="180">
            <template #default="{ row }">{{ row.host }}:{{ row.port }}</template>
          </el-table-column>
          <el-table-column prop="role" label="角色" width="120" />
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="row.reachable ? 'success' : 'danger'">{{ row.reachable ? '可达' : '不可达' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="延迟" width="100">
            <template #default="{ row }">{{ row.reachable && row.latencyMs != null ? row.latencyMs + ' ms' : '-' }}</template>
          </el-table-column>
        </el-table>
      </el-card>
    </template>

    <!-- Redis -->
    <template v-if="type === 'REDIS'">
      <el-card shadow="never" class="section">
        <template #header>
          <div class="section-head">
            <span>Redis 节点分布</span>
            <span class="hint">INFO / CLUSTER NODES（需账号具备权限）</span>
          </div>
        </template>
        <el-alert v-if="redis?.authError" :title="'认证失败：' + redis.authError" type="error" show-icon :closable="false" style="margin-bottom:10px" />
        <el-alert v-else-if="redis?.error" :title="redis.error" type="error" show-icon :closable="false" style="margin-bottom:10px" />
        <el-descriptions :column="4" border size="small" style="margin-bottom:12px">
          <el-descriptions-item label="运行模式">{{ redis?.clusterEnabled ? 'Cluster' : 'Standalone' }}</el-descriptions-item>
          <el-descriptions-item label="当前角色">{{ redis?.role || '-' }}</el-descriptions-item>
          <el-descriptions-item label="集群状态">{{ redis?.clusterState || (redis?.clusterEnabled ? '-' : '未启用集群') }}</el-descriptions-item>
          <el-descriptions-item label="Key 空间">{{ (redis?.keyspaces || []).join('；') || '-' }}</el-descriptions-item>
        </el-descriptions>

        <div class="filter-line">
          <span class="section-title" style="margin:0">节点列表</span>
          <el-select v-model="redisRoleFilter" size="small" style="width:140px" clearable placeholder="全部角色">
            <el-option label="Master" value="master" />
            <el-option label="Slave" value="slave" />
            <el-option label="Unknown" value="unknown" />
          </el-select>
        </div>
        <el-table :data="redisNodes" size="small" stripe>
          <el-table-column prop="address" label="地址" min-width="170" />
          <el-table-column label="角色" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="row.role === 'master' ? 'warning' : 'info'">{{ row.role }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="链路" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="String(row.linkState).includes('connected') ? 'success' : 'danger'">{{ row.linkState || '-' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="slots" label="负责槽位" min-width="180" show-overflow-tooltip />
          <el-table-column prop="slotCount" label="槽位数" width="90" align="right" />
          <el-table-column label="自身" width="80" align="center">
            <template #default="{ row }">{{ row.myself ? '是' : '' }}</template>
          </el-table-column>
        </el-table>
      </el-card>
    </template>

    <!-- Hadoop / YARN -->
    <template v-if="type === 'HADOOP'">
      <el-card shadow="never" class="section">
        <template #header>
          <div class="section-head">
            <span>YARN 集群</span>
            <span class="hint">{{ yarn?.baseUrl }}</span>
          </div>
        </template>
        <el-alert v-if="yarnErrors.length" type="warning" show-icon :closable="false" style="margin-bottom:10px"
                  :title="'部分接口不可用：' + yarnErrors.join('；')" />
        <el-descriptions :column="4" border size="small" style="margin-bottom:12px">
          <el-descriptions-item label="集群 ID">{{ yarn?.cluster?.id || '-' }}</el-descriptions-item>
          <el-descriptions-item label="集群状态">{{ yarn?.cluster?.state || '-' }}</el-descriptions-item>
          <el-descriptions-item label="Hadoop 版本">{{ yarn?.cluster?.hadoopVersion || '-' }}</el-descriptions-item>
          <el-descriptions-item label="ResourceManager">{{ yarn?.cluster?.resourceManagerVersion || '-' }}</el-descriptions-item>
        </el-descriptions>

        <div class="section-title">节点</div>
        <el-table :data="yarn?.nodes || []" size="small" stripe style="margin-bottom:14px">
          <el-table-column prop="host" label="主机" min-width="170" />
          <el-table-column prop="rack" label="Rack" min-width="150" show-overflow-tooltip />
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="row.state === 'RUNNING' ? 'success' : 'warning'">{{ row.state }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="containers" label="容器数" width="90" align="right" />
          <el-table-column label="已用 / 可用内存" width="180" align="right">
            <template #default="{ row }">{{ row.usedMemoryMB }} / {{ row.availableMemoryMB }} MB</template>
          </el-table-column>
        </el-table>

        <div class="section-title">运行中的应用</div>
        <el-table :data="yarnApps" size="small" stripe>
          <el-table-column prop="name" label="应用" min-width="220" show-overflow-tooltip />
          <el-table-column prop="user" label="用户" width="100" />
          <el-table-column prop="queue" label="队列" width="120" />
          <el-table-column prop="type" label="类型" width="100" />
          <el-table-column label="状态" width="120">
            <template #default="{ row }">
              <el-tag size="small" :type="row.state === 'RUNNING' ? 'success' : row.state === 'ACCEPTED' ? 'warning' : 'info'">{{ row.state }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="进度" width="140">
            <template #default="{ row }"><el-progress :percentage="Math.round((row.progress || 0) * 100)" :stroke-width="7" /></template>
          </el-table-column>
        </el-table>
        <el-empty v-if="!yarnApps.length" description="暂无运行中的应用" :image-size="60" />
      </el-card>
    </template>

    <!-- Kafka -->
    <template v-if="type === 'KAFKA'">
      <el-card shadow="never" class="section">
        <template #header><div class="section-head"><span>Kafka Brokers</span><span class="hint">节点连接状态</span></div></template>
        <el-alert :title="kafka?.note" type="info" :closable="false" show-icon style="margin-bottom:10px" />
        <el-table :data="kafka?.brokers || []" size="small" stripe>
          <el-table-column prop="target" label="Broker" min-width="200" />
          <el-table-column label="连接状态" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="row.reachable ? 'success' : 'danger'">{{ row.reachable ? '可达' : '不可达' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="延迟" width="110">
            <template #default="{ row }">{{ row.reachable ? (row.latencyMs + ' ms') : '-' }}</template>
          </el-table-column>
          <el-table-column prop="error" label="错误" min-width="220" show-overflow-tooltip />
        </el-table>
        <el-empty v-if="!(kafka?.brokers || []).length" description="请在资产编辑中填写 broker 节点列表" :image-size="60" />
      </el-card>
    </template>

    <!-- 其他类型：资产信息 -->
    <el-card v-if="!['ZOOKEEPER','REDIS','HADOOP','KAFKA'].includes(type)" shadow="never" class="section">
      <template #header><div class="section-head"><span>资产信息</span><span class="hint">{{ type }}</span></div></template>
      <el-descriptions :column="3" border size="small">
        <el-descriptions-item label="实例名">{{ asset?.name || '-' }}</el-descriptions-item>
        <el-descriptions-item label="类型">{{ asset?.type || '-' }}</el-descriptions-item>
        <el-descriptions-item label="版本">{{ asset?.version || '-' }}</el-descriptions-item>
        <el-descriptions-item label="地址">{{ asset?.ip }}:{{ asset?.port }}</el-descriptions-item>
        <el-descriptions-item label="环境"><EnvTag :env="asset?.env" size="default" /></el-descriptions-item>
        <el-descriptions-item label="状态">{{ statusLabel(asset?.status) }}</el-descriptions-item>
      </el-descriptions>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft } from '@element-plus/icons-vue'
import request from '@/utils/request'
import EnvTag from '@/components/EnvTag.vue'
import { parseTags } from '@/utils/tags'

const route = useRoute()
const router = useRouter()
const id = computed(() => Number(route.params.id))
const asset = ref<any>(null)
const tagList = computed(() => parseTags(asset.value?.tags))
const monitor = ref<any>(null)
const loading = ref(false)
const error = ref('')
const autoRefresh = ref(true)
const lastUpdated = ref('')
const redisRoleFilter = ref('')
const DB_TYPES = ['MYSQL', 'POSTGRESQL', 'ORACLE', 'MONGODB', 'DAMENG', 'GREENPLUM', 'GBASE', 'ELASTICSEARCH']
let timer: any = null

function isDbType(t?: string) { return DB_TYPES.includes(String(t || '').toUpperCase()) }
function listCategory() { return isDbType(asset.value?.type) ? 'DB' : 'MW' }

const type = computed(() => (asset.value?.type || monitor.value?.type || '').toUpperCase())
const nodes = computed(() => monitor.value?.nodes || [])
const zk = computed(() => monitor.value?.zookeeper || null)
const redis = computed(() => monitor.value?.redis || null)
const yarn = computed(() => monitor.value?.yarn || null)
const kafka = computed(() => monitor.value?.kafka || null)
const yarnErrors = computed(() => Object.values(yarn.value?.errors || {}).map(String))
const redisNodes = computed(() => {
  const list = redis.value?.nodes || []
  return redisRoleFilter.value ? list.filter((n: any) => n.role === redisRoleFilter.value) : list
})
const yarnApps = computed(() => yarn.value?.apps || [])
const consoleUrl = computed(() => asset.value?.consoleUrl || monitor.value?.consoleUrl || '')
const statCards = computed<any[]>(() => {
  const m = monitor.value
  if (!m) return []
  const cards: any[] = [
    { label: '节点连通', value: `${m.reachableNodes ?? 0}/${m.totalNodes ?? 0}`, sub: 'TCP 探测' }
  ]
  if (m.zookeeper) {
    cards.push(
      { label: 'ZK 模式', value: m.zookeeper.mode || '-', sub: m.zookeeper.reachable ? '可访问' : '不可访问' },
      { label: '活跃连接', value: m.zookeeper.connections ?? '-' },
      { label: '平均延迟', value: m.zookeeper.avgLatency != null ? m.zookeeper.avgLatency + ' ms' : '-' },
      { label: 'Znode 数', value: m.zookeeper.znodeCount ?? '-' }
    )
  }
  if (m.redis) {
    cards.push(
      { label: '运行模式', value: m.redis.clusterEnabled ? 'Cluster' : 'Standalone', sub: m.redis.clusterState || '' },
      { label: 'Master / Slave', value: `${m.redis.masterCount ?? 0} / ${m.redis.slaveCount ?? 0}` },
      { label: '客户端连接', value: m.redis.connectedClients ?? '-' },
      { label: '内存占用', value: m.redis.usedMemory || '-' }
    )
  }
  if (m.yarn) {
    const met = m.yarn.metrics || {}
    const used = Number(met.memoryUsed || 0), total = Number(met.memoryTotal || 0)
    cards.push(
      { label: '集群状态', value: m.yarn.cluster?.state || '-', sub: m.yarn.cluster?.id || '' },
      { label: '运行应用', value: met.appsRunning ?? '-', sub: `待处理 ${met.appsPending ?? 0}` },
      { label: '容器', value: met.containersAllocated ?? '-', sub: `待分配 ${met.containersPending ?? 0}` },
      { label: '内存', value: total ? `${used} / ${total}` : '-', sub: 'MB' }
    )
  }
  if (m.kafka) {
    const brokers = m.kafka.brokers || []
    const ok = brokers.filter((b: any) => b.reachable).length
    cards.push(
      { label: 'Broker 可达', value: `${ok}/${brokers.length}`, sub: '节点连通性' },
      { label: '控制台', value: m.kafka.consoleUrl ? '已配置' : '未配置', sub: '资产编辑可填写' }
    )
  }
  return cards
})

function statusType(s: string) { return s === 'ONLINE' ? 'success' : s === 'OFFLINE' ? 'danger' : 'info' }
function statusLabel(s?: string) { return s === 'ONLINE' ? '在线' : s === 'OFFLINE' ? '离线' : s === 'MAINTENANCE' ? '维护' : '探测中' }

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [a, m] = await Promise.all([
      request.get(`/api/middleware/${id.value}`),
      request.get(`/api/middleware/${id.value}/monitor`)
    ])
    asset.value = a.data
    monitor.value = m.data
    lastUpdated.value = new Date().toLocaleTimeString('zh-CN', { hour12: false })
  } catch (e: any) {
    error.value = '加载失败：' + (e?.message || e)
  } finally {
    loading.value = false
  }
}

function goBack() { router.push({ path: '/asset/middleware', query: { category: listCategory() } }) }
function editAsset() { router.push({ path: '/asset/middleware', query: { editId: String(asset.value?.id || ''), category: listCategory() } }) }
function openConsole() { if (consoleUrl.value) window.open(consoleUrl.value, '_blank') }

function startTimer() {
  stopTimer()
  if (autoRefresh.value) timer = setInterval(load, 10000)
}
function stopTimer() { if (timer) { clearInterval(timer); timer = null } }

watch(autoRefresh, (v) => { v ? startTimer() : stopTimer() })
watch(id, () => { load(); startTimer() })
onMounted(() => { load(); startTimer() })
onUnmounted(stopTimer)
</script>

<style scoped>
.mw-detail { padding-bottom: 24px; }
.detail-head { display: flex; align-items: center; justify-content: space-between; gap: 16px; flex-wrap: wrap; }
.head-left { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.head-left h2 { margin: 0; font-size: 22px; color: #303133; }
.head-actions { display: flex; align-items: center; gap: 10px; }
.back { margin-right: 4px; }
.meta-line { display: flex; gap: 18px; flex-wrap: wrap; color: #909399; font-size: 13px; margin: 8px 0 14px; }
.stat-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(170px, 1fr)); gap: 12px; margin-bottom: 14px; }
.stat-card { background: #f7f9fc; border: 1px solid #e8edf5; border-radius: 8px; padding: 12px 14px; }
.stat-card .k { color: #909399; font-size: 12.5px; }
.stat-card .v { margin-top: 6px; font-size: 20px; font-weight: 700; color: #303133; }
.stat-card .v.bad { color: #f56c6c; }
.stat-card .sub { margin-top: 4px; color: #909399; font-size: 12px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.section { margin-bottom: 14px; }
.section-head { display: flex; align-items: baseline; justify-content: space-between; gap: 10px; flex-wrap: wrap; }
.section-head span:first-child { font-weight: 600; }
.section-title { font-size: 14px; font-weight: 600; color: #303133; margin: 12px 0 8px; }
.filter-line { display: flex; align-items: center; justify-content: space-between; margin: 4px 0 8px; }
.hint { color: #909399; font-size: 12px; }
</style>