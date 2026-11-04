<template>
  <div class="shards-tab">
    <el-empty v-if="!connectionId" description="请先选择或新建一个 ELK 连接" :image-size="70" />

    <template v-else>
      <div class="shards-toolbar">
        <el-button type="primary" :loading="loading" @click="reloadAll">刷新集群状态</el-button>
        <span v-if="cluster" class="meta">
          集群 <b>{{ cluster.clusterName || '-' }}</b>
          <span v-if="cluster.elapsedMs != null"> · 查询耗时 {{ cluster.elapsedMs }}ms</span>
        </span>
        <span v-if="meta" class="meta">
          · 分片 {{ meta.filtered }}/{{ meta.total }} · 返回 {{ meta.returned }}
        </span>
      </div>

      <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" style="margin-bottom:12px" />

      <!-- 健康概览 -->
      <div class="stat-grid" v-if="health">
        <div class="stat-card">
          <div class="k">集群状态</div>
          <div class="v"><el-tag :type="statusType" effect="dark">{{ statusText }}</el-tag></div>
        </div>
        <div class="stat-card">
          <div class="k">节点</div>
          <div class="v">{{ health.numberOfNodes }} <span class="sub">数据节点 {{ health.numberOfDataNodes }}</span></div>
        </div>
        <div class="stat-card">
          <div class="k">分片（主 / 总）</div>
          <div class="v">{{ health.activePrimaryShards }} / {{ health.activeShards }}</div>
        </div>
        <div class="stat-card" :class="{ danger: health.unassignedShards > 0 }">
          <div class="k">未分配分片</div>
          <div class="v">{{ health.unassignedShards }}</div>
        </div>
        <div class="stat-card">
          <div class="k">迁移 / 初始化</div>
          <div class="v">{{ health.relocatingShards }} / {{ health.initializingShards }}</div>
        </div>
      </div>

      <el-alert
        v-if="cluster && (cluster.healthError || cluster.nodesError || cluster.allocationError)"
        type="warning" show-icon :closable="false" style="margin-bottom:12px"
        :title="'部分集群接口不可用：' + [cluster.healthError, cluster.nodesError, cluster.allocationError].filter(Boolean).join('；')"
      />

      <!-- 节点与磁盘 -->
      <div class="section-title" v-if="nodes.length">节点与磁盘分配</div>
      <el-table v-if="nodes.length" :data="nodes" size="small" stripe style="width:100%;margin-bottom:14px">
        <el-table-column label="节点" min-width="190">
          <template #default="{ row }">
            <span>{{ row.name }}</span>
            <el-tag v-if="row.master" size="small" type="warning" style="margin-left:6px">master</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="ip" label="IP" width="130" />
        <el-table-column label="Heap" width="150">
          <template #default="{ row }">
            <el-progress :percentage="clamp(row.heapPercent)" :stroke-width="8"
                         :status="row.heapPercent >= 90 ? 'exception' : row.heapPercent >= 75 ? 'warning' : ''" />
          </template>
        </el-table-column>
        <el-table-column label="磁盘" width="150">
          <template #default="{ row }">
            <el-progress :percentage="clamp(row.diskUsedPercent)" :stroke-width="8"
                         :status="row.diskUsedPercent >= 85 ? 'exception' : row.diskUsedPercent >= 70 ? 'warning' : ''" />
          </template>
        </el-table-column>
        <el-table-column prop="shards" label="分片数" width="90" align="right" />
        <el-table-column label="已用 / 可用" width="170" align="right">
          <template #default="{ row }">{{ human(row.diskUsedBytes) }} / {{ human(row.diskAvailBytes) }}</template>
        </el-table-column>
        <el-table-column label="CPU / Load1" width="120" align="right">
          <template #default="{ row }">{{ row.cpu ?? '-' }}% / {{ row.load1m ?? '-' }}</template>
        </el-table-column>
      </el-table>

      <!-- 筛选 -->
      <div class="section-title">分片列表 <span class="hint">（共 {{ meta?.filtered ?? 0 }} 条，点击索引名可筛选）</span></div>
      <el-form :inline="true" class="filter-bar">
        <el-form-item label="索引">
          <el-select v-model="filter.index" clearable filterable allow-create placeholder="全部索引（可输入通配符）"
                     style="width:240px" @change="onFilterChange">
            <el-option label="全部索引" value="" />
            <el-option v-for="i in indexOptions" :key="i.index" :label="i.index" :value="i.index" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="filter.state" style="width:140px" @change="onFilterChange">
            <el-option label="全部" value="" />
            <el-option label="STARTED" value="STARTED" />
            <el-option label="UNASSIGNED" value="UNASSIGNED" />
            <el-option label="RELOCATING" value="RELOCATING" />
            <el-option label="INITIALIZING" value="INITIALIZING" />
          </el-select>
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="filter.prirep" style="width:120px" @change="onFilterChange">
            <el-option label="全部" value="" />
            <el-option label="主分片" value="p" />
            <el-option label="副本" value="r" />
          </el-select>
        </el-form-item>
        <el-form-item label="节点">
          <el-select v-model="filter.node" clearable style="width:200px" @change="onFilterChange">
            <el-option label="全部节点" value="" />
            <el-option v-for="n in nodes" :key="n.name" :label="n.name" :value="n.name" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button :loading="shardsLoading" @click="loadShards">应用筛选</el-button>
        </el-form-item>
      </el-form>

      <el-alert v-if="meta?.truncated" type="info" :closable="false" show-icon style="margin-bottom:8px"
                :title="`结果较多，仅展示前 ${meta.returned} 条（共 ${meta.filtered} 条）；请用索引/状态/节点筛选缩小范围。`" />

      <el-table :data="pagedShards" size="small" stripe v-loading="shardsLoading" style="width:100%">
        <el-table-column label="索引" min-width="240" show-overflow-tooltip>
          <template #default="{ row }">
            <el-button link type="primary" @click="filterByIndex(row.index)">{{ row.index }}</el-button>
          </template>
        </el-table-column>
        <el-table-column prop="shard" label="分片" width="70" align="right" />
        <el-table-column label="类型" width="80">
          <template #default="{ row }">
            <el-tag size="small" :type="row.prirep === 'p' ? 'warning' : 'info'">
              {{ row.prirep === 'p' ? '主' : '副本' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag size="small" :type="stateType(row.state)">{{ row.state || '-' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="文档数" width="110" align="right">
          <template #default="{ row }">{{ Number(row.docs || 0).toLocaleString() }}</template>
        </el-table-column>
        <el-table-column prop="store" label="存储" width="100" align="right" />
        <el-table-column label="节点" min-width="190" show-overflow-tooltip>
          <template #default="{ row }">{{ row.node || '-' }} <span class="hint">({{ row.ip || '-' }})</span></template>
        </el-table-column>
        <el-table-column label="未分配原因" min-width="170" show-overflow-tooltip>
          <template #default="{ row }">{{ row.unassignedReason || '-' }}</template>
        </el-table-column>
      </el-table>

      <div class="pager">
        <el-pagination v-model:current-page="page" v-model:page-size="pageSize"
                       :page-sizes="[50, 100, 200]" :total="shards.length"
                       layout="total, sizes, prev, pager, next" small />
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import request from '@/utils/request'

interface IndexInfo { index: string; health: string; status: string; docs: string; size: string; sizeBytes: number }

const props = defineProps<{ connectionId: number | null; active: boolean; indices?: IndexInfo[] }>()

const loading = ref(false)
const shardsLoading = ref(false)
const error = ref('')
const cluster = ref<any>(null)
const shards = ref<any[]>([])
const meta = ref<any>(null)
const page = ref(1)
const pageSize = ref(50)
const filter = reactive({ index: '', state: '', prirep: '', node: '' })

const health = computed(() => cluster.value?.health || null)
const nodes = computed<any[]>(() => cluster.value?.nodes || [])
const indexOptions = computed<IndexInfo[]>(() => props.indices || [])
const pagedShards = computed(() => {
  const from = (page.value - 1) * pageSize.value
  return shards.value.slice(from, from + pageSize.value)
})

const statusType = computed(() => {
  const s = health.value?.status
  return s === 'green' ? 'success' : s === 'yellow' ? 'warning' : s === 'red' ? 'danger' : 'info'
})
const statusText = computed(() => {
  const s = health.value?.status
  return s === 'green' ? 'green · 正常' : s === 'yellow' ? 'yellow · 副本未分配' : s === 'red' ? 'red · 主分片异常' : (s || '-')
})

function stateType(state: string) {
  if (state === 'STARTED') return 'success'
  if (state === 'UNASSIGNED') return 'danger'
  if (state === 'RELOCATING' || state === 'INITIALIZING') return 'warning'
  return 'info'
}
function clamp(v: any) {
  const n = Number(v)
  return Number.isFinite(n) ? Math.max(0, Math.min(100, Math.round(n))) : 0
}
function human(bytes: any) {
  const n = Number(bytes)
  if (!Number.isFinite(n) || n <= 0) return '-'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let i = 0
  let v = n
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i++ }
  return (i === 0 ? String(v) : v.toFixed(1)) + ' ' + units[i]
}

async function reloadAll() {
  if (!props.connectionId) return
  loading.value = true
  error.value = ''
  try {
    const [c, s] = await Promise.all([
      request.get(`/api/elk/${props.connectionId}/cluster`),
      request.get(`/api/elk/${props.connectionId}/shards`, { params: { ...filter, limit: 2000 } })
    ])
    cluster.value = c.data
    shards.value = s.data?.shards || []
    meta.value = s.data
    page.value = 1
  } catch (e: any) {
    error.value = '获取集群状态失败：' + (e?.message || e)
    cluster.value = null
    shards.value = []
    meta.value = null
  } finally {
    loading.value = false
  }
}

async function loadShards() {
  if (!props.connectionId) return
  shardsLoading.value = true
  error.value = ''
  try {
    const r = await request.get(`/api/elk/${props.connectionId}/shards`, {
      params: { ...filter, limit: 2000 }
    })
    shards.value = r.data?.shards || []
    meta.value = r.data
    page.value = 1
  } catch (e: any) {
    error.value = '获取分片列表失败：' + (e?.message || e)
  } finally {
    shardsLoading.value = false
  }
}

function onFilterChange() { loadShards() }
function filterByIndex(index: string) {
  filter.index = index
  loadShards()
}

watch(() => props.connectionId, () => {
  cluster.value = null
  shards.value = []
  meta.value = null
  error.value = ''
  Object.assign(filter, { index: '', state: '', prirep: '', node: '' })
  if (props.active && props.connectionId) reloadAll()
})
watch(() => props.active, (active) => {
  if (active && props.connectionId && !cluster.value && !loading.value) reloadAll()
})
onMounted(() => {
  if (props.active && props.connectionId) reloadAll()
})
</script>

<style scoped>
.shards-tab { padding-top: 4px; }
.shards-toolbar { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; flex-wrap: wrap; }
.meta { color: #606266; font-size: 13px; }
.stat-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(170px, 1fr)); gap: 12px; margin-bottom: 14px; }
.stat-card { background: #f7f9fc; border: 1px solid #e8edf5; border-radius: 8px; padding: 12px 14px; }
.stat-card.danger { border-color: #fbc4c4; background: #fef5f5; }
.stat-card .k { color: #909399; font-size: 12.5px; }
.stat-card .v { margin-top: 6px; font-size: 20px; font-weight: 700; color: #303133; }
.stat-card .v .sub { font-size: 12px; font-weight: 400; color: #909399; margin-left: 4px; }
.section-title { font-size: 14px; font-weight: 600; color: #303133; margin: 10px 0 8px; }
.hint { color: #909399; font-size: 12px; font-weight: 400; }
.filter-bar { margin-bottom: 4px; }
.pager { display: flex; justify-content: flex-end; margin-top: 10px; }
</style>