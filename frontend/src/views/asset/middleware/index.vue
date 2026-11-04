<template>
  <div>
    <h2 style="margin:0 0 16px;font-size:22px;color:#333">中间件 / 数据库管理</h2>

    <el-tabs v-model="category" @tab-change="onCategoryChange">
      <el-tab-pane label="数据库类" name="DB" />
      <el-tab-pane label="中间件类" name="MW" />
    </el-tabs>

    <el-card>
      <div style="display:flex;justify-content:space-between;flex-wrap:wrap;gap:12px;margin-bottom:12px">
        <div style="display:flex;gap:10px;flex-wrap:wrap">
          <el-select v-model="query.type" placeholder="类型" clearable style="width:150px" @change="search">
            <el-option v-for="t in currentTypes" :key="t" :label="t" :value="t" />
          </el-select>
          <el-select v-model="query.env" placeholder="环境" clearable style="width:120px" @change="search">
            <el-option label="生产" value="production" /><el-option label="预发" value="staging" />
            <el-option label="测试" value="test" /><el-option label="开发" value="dev" />
          </el-select>
          <el-input v-model="query.keyword" placeholder="搜索 名称/IP/集群/标签" clearable style="width:200px" @keyup.enter="search" />
          <el-button type="primary" @click="search">查询</el-button>
        </div>
        <el-button v-permission="'asset:middleware:probe'" type="warning" plain :loading="probingPage" @click="probePage(false)">探测本页</el-button>
        <el-button v-permission="'asset:middleware:add'" type="primary" @click="openDialog()">+ 新增</el-button>
      </div>

      <el-table :data="rows" v-loading="loading" stripe style="width:100%">
        <el-table-column prop="name" label="实例名" min-width="130">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">{{ row.name }}</el-button>
          </template>
        </el-table-column>
        <el-table-column prop="type" label="类型" width="110">
          <template #default="{ row }"><el-tag size="small">{{ row.type }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="version" label="版本" width="90" />
        <el-table-column label="地址" width="180">
          <template #default="{ row }">{{ row.ip }}:{{ row.port }}</template>
        </el-table-column>
        <el-table-column prop="clusterName" label="集群" width="120" />
        <el-table-column label="标签" min-width="150">
          <template #default="{ row }">
            <el-tag v-for="t in rowTags(row)" :key="t" size="small" effect="plain" style="margin-right:4px">{{ t }}</el-tag>
            <span v-if="!rowTags(row).length" class="hint">-</span>
          </template>
        </el-table-column>
        <el-table-column v-if="category === 'DB'" prop="dbUsername" label="连接账号" width="110" />
        <el-table-column v-if="category === 'DB'" label="连接密码" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="row.dbPasswordSet ? 'success' : 'info'">
              {{ row.dbPasswordSet ? '已配置' : '未配置' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="env" label="环境" width="90">
          <template #default="{ row }"><EnvTag :env="row.env" /></template>
        </el-table-column>
        <el-table-column prop="serverId" label="宿主服务器ID" width="110" align="center" />
        <el-table-column prop="status" label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="210" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link size="small" @click="openDetail(row)">管理</el-button>
            <el-button link size="small" @click="showDetail(row)">详情</el-button>
            <el-button v-permission="'asset:middleware:probe'" type="warning" link size="small" :loading="probingId === row.id" @click="probeRow(row)">探测</el-button>
            <el-button v-permission="'asset:middleware:delete'" type="danger" link size="small" @click="del(row.id)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div style="display:flex;justify-content:flex-end;margin-top:14px">
        <el-pagination v-model:current-page="query.page" v-model:page-size="query.pageSize" :total="total"
                       :page-sizes="[10,20,50]" layout="total, sizes, prev, pager, next" @change="search" />
      </div>
    </el-card>

    <!-- 新增/编辑 -->
    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑中间件' : '新增中间件'" width="560px">
      <el-form :model="form" label-width="90px">
        <el-form-item label="实例名"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="类型">
          <el-select v-model="form.type" style="width:100%">
            <el-option v-for="t in ALL_TYPES" :key="t" :label="t" :value="t" />
          </el-select>
        </el-form-item>
        <el-form-item label="版本"><el-input v-model="form.version" /></el-form-item>
        <el-form-item label="IP"><el-input v-model="form.ip" /></el-form-item>
        <el-form-item label="端口"><el-input-number v-model="form.port" :min="0" :max="65535" /></el-form-item>
        <template v-if="isDbType(form.type) || form.type === 'REDIS'">
          <el-form-item :label="isDbType(form.type) ? '数据库账号' : '访问账号'">
            <el-input v-model="form.dbUsername" :placeholder="isDbType(form.type) ? '如 root / aiops' : 'Redis ACL 用户名，可留空'" />
          </el-form-item>
          <el-form-item :label="isDbType(form.type) ? '数据库密码' : '访问密码'">
            <el-input v-model="form.dbPassword" type="password" show-password
                      :placeholder="form.dbPasswordSet ? '已加密存储，留空保持不变' : '请输入密码'" />
            <div class="hint">密码 AES 加密存储、页面不回显；SQL 工作台/Redis 详情页会自动使用该凭据。</div>
          </el-form-item>
          <el-form-item v-if="isDbType(form.type)" label="默认数据库">
            <el-input v-model="form.dbName" placeholder="可选：数据库名 / Schema；Oracle 填 SID" />
          </el-form-item>
        </template>
        <el-form-item label="节点列表">
          <el-input v-model="form.nodeList" type="textarea" :rows="2"
                    placeholder="可选：多个节点用逗号或换行分隔，如 192.0.2.1:9092,192.0.2.4:9092；留空则使用上方 IP:端口" />
          <div class="hint">用于详情页的节点连通性探测（Kafka broker / ZooKeeper peers / Redis 节点）。</div>
        </el-form-item>
        <el-form-item label="控制台地址">
          <el-input v-model="form.consoleUrl" placeholder="可选：如 http://192.0.2.1:8088（YARN）/ http://192.0.2.1:9000（Kafka UI）" />
          <div class="hint">详情页右上角“打开管理控制台”会跳转到该地址。</div>
        </el-form-item>
        <el-form-item label="集群">
          <el-input v-model="form.clusterName" placeholder="可选：逻辑集群名，如 kafka-prod-cluster" />
          <div class="hint">仅用于列表展示、搜索和分组，不影响连接；真实节点请填上面的“节点列表”。</div>
        </el-form-item>
        <el-form-item label="标签">
          <el-select v-model="form.tagList" multiple filterable allow-create default-first-option
                     :reserve-keyword="false" placeholder="输入标签后按回车，可添加多个" style="width:100%" />
          <div class="hint">没有“最多 3 个 / 单个 10 字”限制；最多 {{ MAX_TAGS }} 个，单个最多 {{ MAX_TAG_LENGTH }} 字。</div>
        </el-form-item>
        <el-form-item label="环境">
          <el-select v-model="form.env" style="width:100%"><el-option label="生产" value="production" /><el-option label="预发" value="staging" /><el-option label="测试" value="test" /><el-option label="开发" value="dev" /></el-select>
        </el-form-item>
        <el-form-item label="状态">
          <span class="hint">由端口探测自动更新（在线 / 离线，不再显示未知）</span>
        </el-form-item>
        <el-form-item label="宿主服务器">
          <el-select v-model="form.serverId" clearable placeholder="选择服务器" style="width:100%">
            <el-option v-for="s in servers" :key="s.id" :label="s.hostname + ' (' + s.ip + ')'" :value="s.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer><el-button @click="dialogVisible=false">取消</el-button><el-button type="primary" @click="save">保存</el-button></template>
    </el-dialog>

    </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import request from '@/utils/request'
import { ElMessage, ElMessageBox } from 'element-plus'
import EnvTag from '@/components/EnvTag.vue'
import { parseTags, tagsToJson, validateTags, MAX_TAGS, MAX_TAG_LENGTH } from '@/utils/tags'
import type { MiddlewareAsset } from '@/types'

const route = useRoute()
const router = useRouter()

const DB_TYPES = ['MYSQL', 'POSTGRESQL', 'ORACLE', 'MONGODB', 'DAMENG', 'GREENPLUM', 'GBASE', 'ELASTICSEARCH']
const MW_TYPES = ['KAFKA', 'ZOOKEEPER', 'REDIS', 'NACOS', 'RABBITMQ', 'HADOOP']
const ALL_TYPES = [...DB_TYPES, ...MW_TYPES]

const category = ref('DB')
const currentTypes = computed(() => category.value === 'DB' ? DB_TYPES : MW_TYPES)

const loading = ref(false)
const rows = ref<MiddlewareAsset[]>([])
const total = ref(0)
const servers = ref<any[]>([])
const query = reactive({ keyword: '', type: '', env: '', page: 1, pageSize: 10 })

const dialogVisible = ref(false)
const isEdit = ref(false)
const editId = ref<number | null>(null)
const form = reactive<any>({ name: '', type: 'MYSQL', version: '', ip: '', port: 3306, clusterName: '', env: 'production', status: 'OFFLINE', serverId: null,
  dbUsername: '', dbPassword: '', dbName: '', dbPasswordSet: false, nodeList: '', consoleUrl: '', tagList: [] as string[] })

function rowTags(row: any): string[] { return parseTags(row?.tags) }

const probingId = ref<number | null>(null)
const probingPage = ref(false)
let probeToken = 0

function isDbType(type: string) { return DB_TYPES.includes(type) }

function statusType(s: string) { return s === 'ONLINE' ? 'success' : s === 'OFFLINE' ? 'danger' : 'info' }
function statusLabel(s: string) {
  return s === 'ONLINE' ? '在线' : s === 'OFFLINE' ? '离线' : s === 'MAINTENANCE' ? '维护' : '探测中'
}

function onCategoryChange() { query.type = ''; search() }

async function search() {
  loading.value = true
  try {
    const params: any = {
      page: query.page, pageSize: query.pageSize,
      keyword: query.keyword || undefined, env: query.env || undefined,
      category: category.value
    }
    if (query.type) params.type = query.type
    const res = await request.get('/api/middleware/page', { params })
    rows.value = res.data.records || []
    total.value = res.data.total
  } finally { loading.value = false }
  // 端口状态自动探测：当前页资产探测完成后就地更新状态（不会显示未知）
  probePage(true)
}

async function probePage(silent = true) {
  const ids = rows.value.map(r => r.id).filter(id => !!id)
  if (!ids.length) return
  const token = ++probeToken
  if (!silent) probingPage.value = true
  try {
    const r: any = await request.post('/api/middleware/probe', { ids })
    if (token !== probeToken) return
    const results = r.data?.results || []
    const map = new Map<number, any>()
    for (const x of results) map.set(x.id, x)
    rows.value.forEach(row => {
      const x = map.get(row.id)
      if (x) row.status = x.status
    })
    if (!silent) ElMessage.success(r.msg || '探测完成')
  } catch {
    /* 静默探测失败不打扰用户 */
  } finally {
    if (!silent) probingPage.value = false
  }
}

async function probeRow(row: MiddlewareAsset) {
  probingId.value = row.id
  try {
    const r: any = await request.post(`/api/middleware/${row.id}/probe`)
    const d = r.data || {}
    row.status = d.status || 'OFFLINE'
    const online = row.status === 'ONLINE'
    const latency = d.latencyMs != null ? `（${d.latencyMs}ms）` : ''
    ElMessage[online ? 'success' : 'warning'](
      online ? `端口存活${latency}` : (d.message || r.msg || '端口不可达')
    )
  } finally {
    probingId.value = null
  }
}

async function loadServers() {
  const res = await request.get('/api/server/page', { params: { page: 1, pageSize: 200 } })
  servers.value = res.data.records || []
}

function openDialog(row?: MiddlewareAsset) {
  if (row) {
    isEdit.value = true
    editId.value = row.id
    const targetCategory = isDbType(row.type) ? 'DB' : 'MW'
    if (category.value !== targetCategory) { category.value = targetCategory; query.type = '' }
    Object.assign(form, row)
    form.tagList = parseTags(row.tags)
    form.dbPassword = ''          // 编辑时留空表示不修改密码
    form.nodeList = row.nodeList || ''
    form.consoleUrl = row.consoleUrl || ''
  } else {
    isEdit.value = false
    editId.value = null
    Object.assign(form, { name:'', type: currentTypes.value[0], version:'', ip:'', port:3306, clusterName:'', env:'production', status:'OFFLINE', serverId:null,
      dbUsername:'', dbPassword:'', dbName:'', dbPasswordSet:false, nodeList:'', consoleUrl:'', tagList: [] })
  }
  dialogVisible.value = true
}

function openDetail(row: MiddlewareAsset) {
  router.push('/asset/middleware/' + row.id)
}

async function save() {
  if (!form.name || !form.type) { ElMessage.warning('实例名和类型必填'); return }
  const tagError = validateTags(form.tagList || [])
  if (tagError) { ElMessage.warning(tagError); return }
  const payload = { ...form, tags: tagsToJson(form.tagList || []) }
  if (isEdit.value && editId.value) await request.put(`/api/middleware/${editId.value}`, payload)
  else await request.post('/api/middleware', payload)
  // 保存后停留在该资产所属分类，避免编辑“中间件类”后被切回“数据库类”
  const targetCategory = isDbType(form.type) ? 'DB' : 'MW'
  if (category.value !== targetCategory) {
    category.value = targetCategory
    query.type = ''
  }
  query.page = 1
  ElMessage.success('保存成功'); dialogVisible.value = false; search()
}

function del(id: number) {
  ElMessageBox.confirm('确认删除？', '提示', { type: 'warning' }).then(async () => {
    await request.delete(`/api/middleware/${id}`); ElMessage.success('已删除'); search()
  }).catch(() => {})
}

function showDetail(row: any) {
  ElMessageBox.alert(
    `实例名：${row.name}\n类型：${row.type}\n版本：${row.version || '-'}\n地址：${row.ip}:${row.port}\n集群：${row.clusterName || '-'}\n环境：${row.env}\n状态：${statusLabel(row.status)}\n宿主服务器ID：${row.serverId || '-'}`
      + (isDbType(row.type) ? `\n数据库账号：${row.dbUsername || '-'}\n默认数据库：${row.dbName || '-'}\n连接密码：${row.dbPasswordSet ? '已配置' : '未配置'}` : ''),
    '资产详情', { customStyle: { whiteSpace: 'pre-line' } }
  )
}

async function openFromQuery() {
  const id = route.query.id
  if (!id) return
  try {
    const res = await request.get(`/api/middleware/${id}`)
    if (res.data) showDetail(res.data)
    else ElMessage.warning('未找到该中间件资产')
  } catch { ElMessage.warning('未找到该中间件资产') }
}

/** 从详情页“编辑资产”带 editId 返回时直接打开编辑弹窗 */
async function openEditFromQuery() {
  const id = Number(route.query.editId)
  if (!id) return
  try {
    const res = await request.get(`/api/middleware/${id}`)
    if (res.data) {
      // 详情页“编辑资产”跳回列表时，列表默认在“数据库类”；
      // 先切到该资产所属分类，保存后就不会出现“中间件消失/跳回数据库列表”。
      category.value = isDbType(res.data.type) ? 'DB' : 'MW'
      query.type = ''
      await search()
      openDialog(res.data)
    }
  } catch { /* ignore */ }
  router.replace({ path: '/asset/middleware' })
}

onMounted(async () => {
  const qc = String(route.query.category || '').toUpperCase()
  if (qc === 'DB' || qc === 'MW') category.value = qc
  loadServers()
  await search()
  openFromQuery()
  openEditFromQuery()
})
</script>

<style scoped>
.hint { font-size: 12px; color: #909399; margin-top: 4px; line-height: 1.5; }
</style>