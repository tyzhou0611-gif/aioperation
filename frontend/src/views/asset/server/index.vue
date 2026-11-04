<template>
  <div>
    <h2 style="margin:0 0 20px;font-size:22px;color:#333">服务器管理</h2>

    <el-card>
      <div style="display:flex;justify-content:space-between;margin-bottom:16px;flex-wrap:wrap;gap:12px">
        <div style="display:flex;gap:12px">
          <el-input v-model="query.keyword" placeholder="搜索 IP / 主机名 / 环境 / 标签" clearable style="width:240px" @keyup.enter="search" />
          <el-select v-model="query.projectId" clearable placeholder="全部项目" style="width:160px" @change="search">
            <el-option v-for="p in projects" :key="p.id" :label="p.name" :value="p.id" />
          </el-select>
          <el-button type="primary" @click="search">查询</el-button>
        </div>
        <div style="display:flex;gap:8px;align-items:center">
          <el-tooltip content="从 Xshell / SecureCRT / SSH config / CSV / JSON 导入服务器" placement="bottom">
            <el-button v-permission="'asset:server:import'" class="toolbar-btn btn-import" type="success" plain size="small" @click="openImport">
              <el-icon style="margin-right:3px"><Upload /></el-icon>导入
            </el-button>
          </el-tooltip>
          <el-tooltip content="从 Prometheus 增量同步节点（新增/更新状态，保留主机名/环境/SSH/标签等人工字段）" placement="bottom">
            <el-button v-permission="'asset:server:sync'" class="toolbar-btn btn-sync" type="warning" plain size="small" @click="syncFromPrometheus" :loading="syncing">
              <el-icon style="margin-right:3px"><Refresh /></el-icon>同步
            </el-button>
          </el-tooltip>
          <div v-permission="'settings:source'" class="source-picker">
            <span class="source-label">状态来源</span>
            <el-tooltip placement="bottom" :content="statusSourceHint">
              <el-select v-model="statusSource" size="small" style="width:158px" :loading="statusSourceSaving"
                         @change="onStatusSourceChange">
                <el-option label="自动（推荐）" value="auto" />
                <el-option label="Prometheus（兜底）" value="prometheus" />
                <el-option label="SSH（登录探测）" value="ssh" />
                <el-option label="ICMP（ping）" value="icmp" />
              </el-select>
            </el-tooltip>
          </div>
          <el-switch v-permission="'asset:server:probe'" v-model="autoProbe" active-text="60s 自动探测" @change="onAutoProbeChange" />
          <span class="hint auto-time">上次自动 {{ lastAutoProbe ? lastAutoProbe.toLocaleTimeString('zh-CN', { hour12: false }) : '--:--:--' }}</span>
          <el-tooltip content="按当前状态来源探测全部可见服务器" placement="bottom">
            <el-button v-permission="'asset:server:probe'" class="toolbar-btn btn-probe" size="small" @click="probeAll" :loading="probing">探测全部</el-button>
          </el-tooltip>
          <el-tooltip content="上传离线包，通过 SSH/SFTP 一键安装 node_exporter 等 Prometheus Exporter" placement="bottom">
            <el-button v-permission="'asset:server:edit'" class="toolbar-btn btn-agent" size="small" @click="agentVisible = true">
              <el-icon style="margin-right:3px"><Box /></el-icon>Agent 安装
            </el-button>
          </el-tooltip>
          <el-button v-permission="'asset:server:add'" type="primary" @click="openDialog()">+ 新增服务器</el-button>
        </div>
      </div>

      <el-table :data="rows" v-loading="loading" stripe style="width:100%">
        <el-table-column prop="ip" label="IP地址" width="140" />
        <el-table-column prop="hostname" label="主机名" width="150" />
        <el-table-column label="SSH连接" min-width="200">
          <template #default="{ row }">
            <div class="ssh-cell">
              <span>{{ row.sshUser || 'root' }}@{{ row.ip }}:{{ row.sshPort || 22 }}</span>
              <el-tag v-if="row.sshPasswordSet" size="small" type="success">密码</el-tag>
              <el-tag v-else-if="row.sshKey" size="small" type="success">私钥</el-tag>
              <el-tag v-else size="small" type="info">未配置</el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="os" label="操作系统" width="130" />
        <el-table-column prop="cpu" label="CPU" width="150" show-overflow-tooltip />
        <el-table-column prop="memory" label="内存" width="110" />
        <el-table-column prop="environment" label="环境" width="110">
          <template #default="{ row }"><EnvTag :env="row.environment" /></template>
        </el-table-column>
        <el-table-column label="所属项目" min-width="150">
          <template #default="{ row }">
            <el-tag v-for="n in row.projectNames || []" :key="n" size="small" style="margin-right:4px">{{ n }}</el-tag>
            <span v-if="!(row.projectNames || []).length" class="hint">未分配</span>
          </template>
        </el-table-column>
        <el-table-column label="标签" min-width="140">
          <template #default="{ row }">
            <el-tag v-for="t in rowTags(row)" :key="t" size="small" effect="plain" style="margin-right:4px">{{ t }}</el-tag>
            <span v-if="!rowTags(row).length" class="hint">-</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tooltip :content="row.statusSource ? ('来源：' + sourceLabel(row.statusSource)) : '尚未探测'" placement="top">
              <el-tag :type="statusType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="最后探测" width="175">
          <template #default="{ row }">
            <div class="probe-cell">
              <span>{{ formatProbeTime(row.lastProbeTime) || '从未' }}</span>
              <el-tag v-if="row.statusSource" size="small" type="info" effect="plain">{{ sourceLabel(row.statusSource) }}</el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="390" fixed="right">
          <template #default="{ row }">
            <el-button v-permission="'asset:server:edit'" type="primary" link size="small" @click="openDialog(row)">编辑</el-button>
            <el-button v-permission="'asset:server:add'" type="info" link size="small" @click="openClone(row)">复制</el-button>
            <el-button v-permission="'asset:server:probe'" type="warning" link size="small" @click="probeSsh(row)" :loading="sshProbingId===row.id">自动探测</el-button>
            <el-button v-permission="'exec:run'" type="warning" link size="small" @click="openCmd(row)">一键命令</el-button>
            <el-button type="info" link size="small" @click="askAi(row)">AI助手</el-button>
            <el-button v-permission="'asset:server:delete'" type="danger" link size="small" @click="del(row.id)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div style="display:flex;justify-content:flex-end;margin-top:16px">
        <el-pagination
          v-model:current-page="query.page"
          v-model:page-size="query.pageSize"
          :total="total"
          :page-sizes="[10,20,50]"
          layout="total, sizes, prev, pager, next"
          @change="search"
        />
      </div>
    </el-card>

    <!-- 新增/编辑弹窗 -->
    <el-dialog v-model="dialogVisible" :title="cloneSourceId ? '复制服务器' : (isEdit ? '编辑服务器' : '新增服务器')" width="560px">
      <el-alert v-if="cloneSourceId" type="info" :closable="false" show-icon style="margin-bottom:12px"
                title="以现有服务器为模板：请填写新 IP；密码留空将沿用源服务器的加密密码（无需查看明文），也可输入新密码覆盖。" />
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="IP地址" prop="ip"><el-input v-model="form.ip" /></el-form-item>
        <el-form-item label="主机名" prop="hostname"><el-input v-model="form.hostname" /></el-form-item>
        <el-form-item label="操作系统"><el-input v-model="form.os" /></el-form-item>
        <el-form-item label="CPU"><el-input v-model="form.cpu" /></el-form-item>
        <el-form-item label="内存"><el-input v-model="form.memory" /></el-form-item>
        <el-form-item label="磁盘"><el-input v-model="form.disk" /></el-form-item>
        <el-form-item label="环境">
          <el-select v-model="form.environment" style="width:100%">
            <el-option label="生产 production" value="production" />
            <el-option label="预发 staging" value="staging" />
            <el-option label="测试 test" value="test" />
            <el-option label="开发 dev" value="dev" />
          </el-select>
        </el-form-item>
        <el-form-item label="标签">
          <el-select v-model="form.tagList" multiple filterable allow-create default-first-option
                     :reserve-keyword="false" placeholder="输入标签后按回车，可添加多个" style="width:100%" />
          <div class="hint">没有“最多 3 个 / 单个 10 字”限制；最多 {{ MAX_TAGS }} 个，单个最多 {{ MAX_TAG_LENGTH }} 字。</div>
        </el-form-item>
        <el-form-item label="所属项目">
          <el-select v-model="form.projectIds" multiple filterable placeholder="选择服务器所属项目" style="width:100%">
            <el-option v-for="p in projects" :key="p.id" :label="p.name" :value="p.id" />
          </el-select>
          <div class="hint">项目 + 角色决定哪些用户能看到这台服务器</div>
        </el-form-item>
        <el-form-item label="SSH端口"><el-input-number v-model="form.sshPort" :min="1" :max="65535" /></el-form-item>
        <el-form-item label="SSH用户"><el-input v-model="form.sshUser" /></el-form-item>
        <el-form-item label="SSH密码">
          <div style="display:flex;width:100%;gap:8px">
            <el-input v-model="form.sshPassword" type="password" show-password
                      :placeholder="pwdPlaceholder" style="flex:1" />
            <el-button v-if="isEdit && pwdSet" @click="clearPwd">清除密码</el-button>
          </div>
        </el-form-item>
        <el-form-item label="连接检测">
          <el-button size="small" :loading="sshTesting" @click="testForm">测试连接</el-button>
          <el-button size="small" type="success" :loading="sshProbing" @click="probeForm">测试并自动探测</el-button>
          <span class="hint">探测成功后会让你确认是否保存主机名/系统/CPU/内存/磁盘；保存后会自动探测一次在线状态</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible=false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
    <!-- 探测结果确认 -->
    <el-dialog v-model="probeDialogVisible" title="服务器探测结果" width="660px">
      <div v-if="probeResult">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="主机名">{{ probeResult.hostname || '-' }}</el-descriptions-item>
          <el-descriptions-item label="操作系统">{{ probeResult.os || '-' }}</el-descriptions-item>
          <el-descriptions-item label="CPU">{{ probeResult.cpu || '-' }}</el-descriptions-item>
          <el-descriptions-item label="内存">{{ probeResult.memory || '-' }}</el-descriptions-item>
          <el-descriptions-item label="磁盘">{{ probeResult.disk || '-' }}</el-descriptions-item>
          <el-descriptions-item label="耗时">{{ probeResult.elapsedMs || 0 }} ms</el-descriptions-item>
        </el-descriptions>
        <el-alert type="info" :closable="false" show-icon style="margin-top:12px"
                  title="确认后会把以上信息补充到服务器资料中；SSH 密码不会出现在探测结果里。" />
      </div>
      <template #footer>
        <el-button @click="probeDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="confirmProbe">确认保存</el-button>
      </template>
    </el-dialog>

    <!-- Shell 工具导入 -->
    <el-dialog v-model="importVisible" title="从 Shell 工具导入服务器" width="820px">
      <div class="import-bar">
        <el-button type="primary" plain :loading="importPreviewing" @click="importInput?.click()">选择文件</el-button>
        <input ref="importInput" type="file" multiple hidden
               accept=".xsh,.ini,.config,.conf,.csv,.json,.zip,.txt" @change="onImportFiles" />
        <span class="hint">支持 Xshell .xsh / SecureCRT .ini / OpenSSH config / CSV / JSON / ZIP</span>
      </div>
      <el-alert type="info" :closable="false" show-icon style="margin:8px 0"
                title="Xshell / SecureCRT 保存的密码是加密格式，无法导入。可在下方填写统一 SSH 密码，或导入后逐个编辑设置。" />
      <div v-if="importCandidates.length">
        <div class="import-tools">
          <el-checkbox v-model="importSelectAll" @change="toggleImportAll">全选</el-checkbox>
          <el-checkbox v-model="importOverwrite">覆盖已存在 IP</el-checkbox>
          <el-input v-model="importPassword" type="password" show-password
                    placeholder="统一 SSH 密码（可选）" style="width:220px" />
          <span class="hint">共 {{ importCandidates.length }} 条，已选 {{ selectedImportCount }} 条</span>
        </div>
        <el-table :data="importCandidates" size="small" max-height="360" stripe>
          <el-table-column width="50">
            <template #default="{ row }"><el-checkbox v-model="row._selected" /></template>
          </el-table-column>
          <el-table-column prop="ip" label="IP/主机" width="150" />
          <el-table-column prop="hostname" label="名称" min-width="150" show-overflow-tooltip />
          <el-table-column prop="port" label="端口" width="70" align="center" />
          <el-table-column prop="user" label="用户" width="90" />
          <el-table-column prop="source" label="来源" min-width="150" show-overflow-tooltip />
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="row.exists ? 'warning' : 'success'" size="small">{{ row.exists ? '已存在' : '新增' }}</el-tag>
            </template>
          </el-table-column>
        </el-table>
      </div>
      <el-empty v-else-if="importPreviewed" description="未解析到可导入的服务器" :image-size="70" />
      <template #footer>
        <el-button @click="importVisible = false">取消</el-button>
        <el-button type="primary" :loading="importConfirming" :disabled="!selectedImportCount" @click="confirmImport">确认导入</el-button>
      </template>
    </el-dialog>

    <!-- 一键命令 -->
    <QuickCommand v-model="cmdVisible" :target-type="'SERVER'" :target-id="cmdTarget?.id || null"
                  :target-name="cmdTarget?.hostname || cmdTarget?.ip || ''" :target-meta="cmdTarget?.ip" />

    <!-- Exporter 一键安装 -->
    <AgentInstallDialog v-model="agentVisible" />
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted, onUnmounted, computed } from 'vue'
import { useRoute } from 'vue-router'
import request from '@/utils/request'
import { ElMessage, ElMessageBox } from 'element-plus'
import QuickCommand from '@/components/QuickCommand.vue'
import AgentInstallDialog from '@/components/AgentInstallDialog.vue'
import EnvTag from '@/components/EnvTag.vue'
import { parseTags, tagsToJson, validateTags, MAX_TAGS, MAX_TAG_LENGTH } from '@/utils/tags'
import { useCopilotStore } from '@/stores/copilot'
import type { FormInstance, FormRules } from 'element-plus'

const route = useRoute()
const copilot = useCopilotStore()
function askAi(row: any) { copilot.openWith({ type: 'SERVER', id: row.id, name: row.hostname || row.ip }) }

const pwdSet = ref(false)
const pwdPlaceholder = ref('')

const loading = ref(false)
const cmdVisible = ref(false)
const agentVisible = ref(false)
const cmdTarget = ref<any>(null)
const saving = ref(false)
const probing = ref(false)
const syncing = ref(false)
const sshTesting = ref(false)
const sshProbing = ref(false)
const sshProbingId = ref<number | null>(null)
const statusSource = ref('auto')
const statusSourceSaving = ref(false)
const statusSourceLabel = computed(() => ({ auto: '自动', prometheus: 'Prometheus（兜底）', ssh: 'SSH', icmp: 'ICMP' } as any)[statusSource.value] || statusSource.value)
const statusSourceHint = computed(() => ({
  auto: '自动：Prometheus 优先；无数据或离线时用 SSH/TCP/ICMP 复核，推荐',
  prometheus: 'Prometheus up 指标优先；未采集到该主机时自动用 SSH/TCP/ICMP 兜底',
  ssh: '仅使用服务器资产中保存的密码/私钥登录探测',
  icmp: '仅使用系统 ping 探测（本机需能执行 ping）'
} as any)[statusSource.value] || '选择服务器在线状态的判断来源')
const autoProbe = ref(localStorage.getItem('server_auto_probe') !== '0')
const autoProbing = ref(false)
const lastAutoProbe = ref<Date | null>(null)
let autoTimer: any = null
let autoInitialTimer: any = null
const probeDialogVisible = ref(false)
const probeResult = ref<any>(null)
const probeSource = ref<'form' | 'row'>('form')
const probeRow = ref<any>(null)

const importVisible = ref(false)
const importInput = ref<HTMLInputElement>()
const importPreviewing = ref(false)
const importConfirming = ref(false)
const importId = ref('')
const importCandidates = ref<any[]>([])
const importSelectAll = ref(true)
const importOverwrite = ref(false)
const importPassword = ref('')
const importPreviewed = ref(false)
const selectedImportCount = computed(() => importCandidates.value.filter(c => c._selected).length)
const rows = ref<any[]>([])
const projects = ref<any[]>([])
const total = ref(0)
const dialogVisible = ref(false)
const isEdit = ref(false)
const editId = ref<number | null>(null)
const cloneSourceId = ref<number | null>(null)
const formRef = ref<FormInstance>()

const query = reactive({ keyword: '', projectId: null as number | null, page: 1, pageSize: 10 })
const form = reactive<any>({
  ip: '', hostname: '', os: '', cpu: '', memory: '', disk: '',
  environment: 'production', sshPort: 22, sshUser: 'root', sshPassword: '', projectIds: [] as number[],
  tagList: [] as string[]
})
const rules: FormRules = {
  ip: [{ required: true, message: '请输入IP地址' }],
  hostname: [{ required: true, message: '请输入主机名' }]
}

function rowTags(row: any): string[] { return parseTags(row?.tags) }

function statusType(s: string) {
  return s === 'ONLINE' ? 'success' : s === 'OFFLINE' ? 'danger' : 'warning'
}
function statusLabel(s: string) {
  return s === 'ONLINE' ? '在线' : s === 'OFFLINE' ? '离线' : s === 'MAINTENANCE' ? '维护' : s === 'UNKNOWN' ? '未知' : s
}
function sourceLabel(s: string) {
  return ({ prometheus: 'Prometheus', ssh: 'SSH', tcp: 'TCP', icmp: 'ICMP' } as any)[s] || s || '-'
}

async function search(silent = false) {
  if (!silent) loading.value = true
  try {
    const res = await request.get('/api/server/page', {
      params: { ...query, projectId: query.projectId || undefined }
    })
    rows.value = res.data.records || []
    total.value = res.data.total || 0
  } finally {
    if (!silent) loading.value = false
  }
}

function formatProbeTime(t: any) {
  if (!t) return ''
  if (t instanceof Date) {
    return t.toLocaleString('zh-CN', { hour12: false }).replace(/\//g, '-')
  }
  return String(t).replace('T', ' ').substring(0, 19)
}

async function loadStatusSource() {
  try {
    const r = await request.get('/api/system/config/metrics')
    statusSource.value = r.data?.serverStatusSource || 'auto'
  } catch { /* 保持默认 */ }
}

/** 直接在服务器列表切换状态来源，保存后立即重新探测一遍 */
async function onStatusSourceChange(val: string) {
  statusSourceSaving.value = true
  try {
    await request.post('/api/system/config/metrics', { serverStatusSource: val })
    ElMessage.success(`状态来源已切换为：${statusSourceLabel.value}`)
    await probeAll()
  } catch {
    await loadStatusSource()
  } finally {
    statusSourceSaving.value = false
  }
}

async function autoProbeNow() {
  if (autoProbing.value || probing.value) return
  if (document.visibilityState !== 'visible') return
  if (dialogVisible.value || importVisible.value || probeDialogVisible.value) return
  autoProbing.value = true
  try {
    await loadStatusSource()
    await request.post('/api/server/probe/all')
    lastAutoProbe.value = new Date()
    await search(true)
  } catch {
    /* 错误提示由 request 拦截器统一处理 */
  } finally {
    autoProbing.value = false
  }
}

function startAutoProbe() {
  stopAutoProbe()
  if (!autoProbe.value) return
  autoTimer = setInterval(autoProbeNow, 60000)
  autoInitialTimer = setTimeout(autoProbeNow, 1500)
}

function stopAutoProbe() {
  if (autoTimer) { clearInterval(autoTimer); autoTimer = null }
  if (autoInitialTimer) { clearTimeout(autoInitialTimer); autoInitialTimer = null }
}

function onAutoProbeChange(v: boolean) {
  localStorage.setItem('server_auto_probe', v ? '1' : '0')
  if (v) { startAutoProbe(); autoProbeNow() } else stopAutoProbe()
}

async function loadProjects() {
  try {
    const r = await request.get('/api/project/options')
    projects.value = r.data || []
  } catch { projects.value = [] }
}

function openDialog(row?: any) {
  form.sshPassword = ''
  form.clearPassword = false
  cloneSourceId.value = null
  if (row) {
    isEdit.value = true; editId.value = row.id
    pwdSet.value = !!row.sshPasswordSet
    pwdPlaceholder.value = pwdSet.value ? '已设置密码，如需修改请输入新密码（留空则不修改）' : '请输入SSH密码'
    Object.assign(form, row, { sshPassword: '', clearPassword: false, projectIds: row.projectIds ? [...row.projectIds] : [] })
    form.tagList = parseTags(row.tags)
  } else {
    isEdit.value = false; editId.value = null; pwdSet.value = false
    pwdPlaceholder.value = '请输入SSH密码'
    Object.assign(form, { ip:'', hostname:'', os:'', cpu:'', memory:'', disk:'', environment:'production', sshPort:22, sshUser:'root', sshPassword:'', clearPassword:false, projectIds: [], tagList: [] })
  }
  dialogVisible.value = true
}

/** 复制服务器：所有非敏感字段预填，密码留空则后端沿用源服务器密文 */
function openClone(row: any) {
  form.sshPassword = ''
  form.clearPassword = false
  isEdit.value = false
  editId.value = null
  cloneSourceId.value = row.id
  pwdSet.value = !!row.sshPasswordSet
  pwdPlaceholder.value = pwdSet.value
    ? '源服务器已设置密码：留空则沿用，如需修改请输入新密码'
    : '请输入SSH密码'
  Object.assign(form, {
    ip: '',
    hostname: (row.hostname || row.ip || '') + '-copy',
    os: row.os || '',
    cpu: row.cpu || '',
    memory: row.memory || '',
    disk: row.disk || '',
    environment: row.environment || 'production',
    sshPort: row.sshPort || 22,
    sshUser: row.sshUser || 'root',
    sshKey: '',
    sshPassword: '',
    clearPassword: false,
    projectIds: row.projectIds ? [...row.projectIds] : [],
    tagList: parseTags(row.tags)
  })
  dialogVisible.value = true
}

function clearPwd() {
  form.sshPassword = ''
  form.clearPassword = true
  ElMessage.info('保存后将清除该服务器已保存的SSH密码')
}

async function save() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  const tagError = validateTags(form.tagList || [])
  if (tagError) { ElMessage.warning(tagError); return }
  form.tags = tagsToJson(form.tagList || [])
  saving.value = true
  try {
    let id = editId.value
    if (cloneSourceId.value) {
      const r = await request.post(`/api/server/${cloneSourceId.value}/clone`, form)
      id = r.data?.id || null
      ElMessage.success('复制成功')
    } else if (isEdit.value && editId.value) {
      await request.put(`/api/server/${editId.value}`, form)
      ElMessage.success('更新成功')
    } else {
      const r = await request.post('/api/server', form)
      id = r.data?.id || null
      ElMessage.success('新增成功')
    }
    if (id) {
      await request.put(`/api/project/server/${id}`, { projectIds: form.projectIds || [] })
      // 后端保存后会立即异步探测一次，这里静默刷新几轮，尽快展示真实状态
      refreshStatusAfterProbe(id)
    }
    dialogVisible.value = false
    cloneSourceId.value = null
    search()
  } finally {
    saving.value = false
  }
}

let statusPollToken = 0

/** 保存后轮询几次列表，等待后端异步探测结果（不阻塞弹窗关闭） */
async function refreshStatusAfterProbe(id: number) {
  const token = ++statusPollToken
  for (const delay of [1200, 2500, 5000]) {
    await new Promise(r => setTimeout(r, delay))
    if (token !== statusPollToken) return
    try {
      await search(true)
    } catch {
      return
    }
    const row = rows.value.find((r: any) => r.id === id)
    if (row && row.status && row.status !== 'UNKNOWN') return
  }
}

async function del(id: number) {
  await ElMessageBox.confirm('确认删除该服务器？', '提示', { type: 'warning' })
  await request.delete(`/api/server/${id}`)
  ElMessage.success('删除成功')
  search()
}

async function probeAll() {
  probing.value = true
  try {
    const res: any = await request.post('/api/server/probe/all')
    ElMessage.success(res.msg)
    search()
  } finally {
    probing.value = false
  }
}

async function probeSsh(row: any) {
  sshProbingId.value = row.id
  try {
    const r = await request.post(`/api/server/${row.id}/ssh/probe`)
    if (r.data?.success) {
      probeResult.value = r.data
      probeSource.value = 'row'
      probeRow.value = row
      probeDialogVisible.value = true
    } else {
      ElMessage.error(r.data?.message || '探测失败')
    }
  } finally { sshProbingId.value = null }
}

async function testForm() {
  if (!form.ip) { ElMessage.warning('请先填写 IP 地址'); return }
  sshTesting.value = true
  try {
    const r = await request.post('/api/server/ssh/test', {
      id: isEdit.value ? editId.value : null,
      ip: form.ip, port: form.sshPort, user: form.sshUser, password: form.sshPassword
    })
    ElMessage[r.data?.success ? 'success' : 'error'](r.data?.message || '测试完成')
  } finally { sshTesting.value = false }
}

async function probeForm() {
  if (!form.ip) { ElMessage.warning('请先填写 IP 地址'); return }
  sshProbing.value = true
  try {
    const r = await request.post('/api/server/ssh/probe', {
      id: isEdit.value ? editId.value : null,
      ip: form.ip, port: form.sshPort, user: form.sshUser, password: form.sshPassword
    })
    if (r.data?.success) {
      probeResult.value = r.data
      probeSource.value = 'form'
      probeRow.value = null
      probeDialogVisible.value = true
    } else {
      ElMessage.error(r.data?.message || '探测失败')
    }
  } finally { sshProbing.value = false }
}

async function confirmProbe() {
  const p = probeResult.value
  if (!p) return
  if (probeSource.value === 'form') {
    Object.assign(form, {
      hostname: p.hostname || form.hostname,
      os: p.os || form.os,
      cpu: p.cpu || form.cpu,
      memory: p.memory || form.memory,
      disk: p.disk || form.disk
    })
    probeDialogVisible.value = false
    await save()
  } else if (probeRow.value) {
    const row = probeRow.value
    await request.put(`/api/server/${row.id}`, {
      ...row,
      hostname: p.hostname || row.hostname,
      os: p.os || row.os,
      cpu: p.cpu || row.cpu,
      memory: p.memory || row.memory,
      disk: p.disk || row.disk
    })
    ElMessage.success('探测信息已保存')
    probeDialogVisible.value = false
    search()
  }
}

function openImport() {
  importVisible.value = true
  importId.value = ''
  importCandidates.value = []
  importPreviewed.value = false
  importPassword.value = ''
  importOverwrite.value = false
  importSelectAll.value = true
}

async function onImportFiles(e: Event) {
  const input = e.target as HTMLInputElement
  const files = Array.from(input.files || [])
  if (!files.length) return
  importPreviewing.value = true
  try {
    const fd = new FormData()
    files.forEach(f => fd.append('files', f))
    const r = await request.post('/api/server/import/preview', fd, {
      headers: { 'Content-Type': 'multipart/form-data' }, timeout: 120000
    })
    importId.value = r.data?.importId || ''
    importCandidates.value = (r.data?.candidates || []).map((c: any) => ({ ...c, _selected: true }))
    importPreviewed.value = true
    importSelectAll.value = true
    if (!importCandidates.value.length) ElMessage.warning('未解析到服务器，请检查文件格式')
    else ElMessage.success(`解析到 ${importCandidates.value.length} 台服务器`)
  } finally {
    importPreviewing.value = false
    input.value = ''
  }
}

function toggleImportAll(v: any) {
  importCandidates.value.forEach(c => { c._selected = !!v })
}

async function confirmImport() {
  if (!importId.value) return
  const selected = importCandidates.value.filter(c => c._selected).map(c => c.index)
  if (!selected.length) { ElMessage.warning('请选择要导入的服务器'); return }
  importConfirming.value = true
  try {
    const r = await request.post('/api/server/import/confirm', {
      importId: importId.value, selected, overwrite: importOverwrite.value, password: importPassword.value
    })
    const d = r.data || {}
    ElMessage.success(`导入完成：新增 ${d.added || 0}，更新 ${d.updated || 0}，跳过 ${d.skipped || 0}`)
    importVisible.value = false
    search()
    // 后端会异步探测新增机器状态，稍后静默刷新一次
    setTimeout(() => search(true), 5000)
  } finally { importConfirming.value = false }
}

function openCmd(row: any) { cmdTarget.value = row; cmdVisible.value = true }

async function syncFromPrometheus() {
  syncing.value = true
  try {
    const res: any = await request.post('/api/server/sync')
    ElMessage.success(res.msg)
    query.page = 1
    search()
  } finally {
    syncing.value = false
  }
}

async function openFromQuery() {
  const id = route.query.id
  if (!id) return
  try {
    const res = await request.get(`/api/server/${id}`)
    if (res.data) openDialog(res.data)
    else ElMessage.warning('未找到该服务器')
  } catch { ElMessage.warning('未找到该服务器') }
}

onMounted(() => {
  loadProjects()
  loadStatusSource()
  search()
  openFromQuery()
  startAutoProbe()
})

onUnmounted(() => {
  stopAutoProbe()
  statusPollToken++
})
</script>

<style scoped>
.hint { color: #909399; font-size: 12px; }
.source-picker { display: flex; align-items: center; gap: 6px; }
.source-label { color: #606266; font-size: 13px; white-space: nowrap; }
.auto-time { display: inline-block; min-width: 132px; white-space: nowrap; }
.toolbar-btn { display: inline-flex; align-items: center; justify-content: center; padding-left: 0; padding-right: 0; }
.btn-import, .btn-sync { width: 76px; }
.btn-probe { width: 88px; }
.btn-agent { width: 96px; }
.ssh-cell { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; font-size: 13px; }
.probe-cell { display: flex; flex-direction: column; align-items: flex-start; gap: 2px; font-size: 12px; }
.import-bar { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.import-tools { display: flex; align-items: center; gap: 14px; margin: 8px 0; flex-wrap: wrap; }
</style>