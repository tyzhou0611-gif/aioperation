<template>
  <div>
    <h2 style="margin:0 0 20px;font-size:22px;color:#333">FTP 服务器管理</h2>

    <el-card>
      <div class="ftp-toolbar">
        <el-input v-model="keyword" placeholder="搜索名称 / 主机 / 用户名" clearable style="width:240px" />
        <el-button type="primary" v-permission="'asset:ftp:edit'" @click="openCreate">+ 新增 FTP</el-button>
        <el-button @click="reloadAll">刷新</el-button>
        <span class="hint" style="margin-left:auto">
          从 FTP 拉取到平台本地暂存后，可通过 SFTP 分发到服务器资产
        </span>
      </div>

      <el-table :data="filteredServers" v-loading="loading" stripe size="small" style="width:100%">
        <el-table-column prop="name" label="名称" min-width="150" show-overflow-tooltip />
        <el-table-column label="协议" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.protocol === 'SFTP' ? 'success' : 'primary'" effect="plain">{{ row.protocol }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="地址" min-width="190">
          <template #default="{ row }"><code>{{ row.host }}:{{ row.port }}</code></template>
        </el-table-column>
        <el-table-column prop="username" label="账号" width="130" show-overflow-tooltip />
        <el-table-column prop="basePath" label="根目录" min-width="140" show-overflow-tooltip />
        <el-table-column label="密码" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.passwordSet ? 'success' : 'info'">{{ row.passwordSet ? '已配置' : '匿名/无' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="启用" width="80" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.enabled === 1 ? 'success' : 'info'">{{ row.enabled === 1 ? '启用' : '停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="300" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="browse(row)">浏览文件</el-button>
            <el-button v-permission="'asset:ftp:test'" link type="warning" :loading="testingId === row.id" @click="testServer(row)">测试</el-button>
            <el-button v-permission="'asset:ftp:edit'" link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button v-permission="'asset:ftp:edit'" link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card style="margin-top:16px">
      <template #header>
        <div class="staged-head">
          <span>本地暂存 <el-tag size="small" type="info">{{ staged.length }}</el-tag></span>
          <span class="hint">用于中转：FTP → 平台 → 受管服务器</span>
        </div>
      </template>
      <el-table :data="staged" size="small" stripe style="width:100%">
        <el-table-column prop="originalName" label="文件名" min-width="220" show-overflow-tooltip />
        <el-table-column prop="ftpServerId" label="来源 FTP ID" width="110" align="center" />
        <el-table-column label="大小" width="110">
          <template #default="{ row }">{{ fmtSize(row.size) }}</template>
        </el-table-column>
        <el-table-column prop="modifiedAt" label="下载时间" width="180">
          <template #default="{ row }">{{ fmtTime(row.modifiedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="290" fixed="right">
          <template #default="{ row }">
            <el-button v-permission="'asset:ftp:transfer'" link type="primary" @click="downloadStaged(row)">下载</el-button>
            <el-button v-permission="'asset:ftp:transfer'" link type="primary" @click="openDistribute(row)">分发到服务器</el-button>
            <el-button v-permission="'asset:ftp:transfer'" link type="danger" @click="deleteStaged(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!staged.length" description="暂无暂存文件，先在 FTP 服务器中浏览并拉取" :image-size="60" />
    </el-card>

    <!-- 新增/编辑 FTP -->
    <el-dialog v-model="formVisible" :title="isEdit ? '编辑 FTP 服务器' : '新增 FTP 服务器'" width="620px"
               :close-on-click-modal="false" destroy-on-close>
      <el-form :model="form" label-width="100px">
        <el-form-item label="名称" required><el-input v-model="form.name" /></el-form-item>
        <el-row :gutter="12">
          <el-col :span="8">
            <el-form-item label="协议">
              <el-select v-model="form.protocol" style="width:100%" @change="onProtocolChange">
                <el-option label="FTP" value="FTP" />
                <el-option label="SFTP" value="SFTP" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="10">
            <el-form-item label="主机" required><el-input v-model="form.host" /></el-form-item>
          </el-col>
          <el-col :span="6">
            <el-form-item label="端口"><el-input-number v-model="form.port" :min="1" :max="65535" :controls="false" style="width:100%" /></el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="12"><el-form-item label="账号"><el-input v-model="form.username" /></el-form-item></el-col>
          <el-col :span="12">
            <el-form-item label="密码">
              <el-input v-model="form.password" type="password" show-password
                        :placeholder="form.passwordSet ? '已加密存储，留空保持不变' : '可留空（匿名）'" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="根目录"><el-input v-model="form.basePath" placeholder="/" /></el-form-item>
        <el-form-item v-if="form.protocol === 'FTP'" label="被动模式">
          <el-switch v-model="form.passive" />
          <span class="hint" style="margin-left:8px">大多数 FTP 服务器需要被动模式</span>
        </el-form-item>
        <el-form-item label="启用"><el-switch v-model="form.enabled" /></el-form-item>
        <el-form-item label="说明"><el-input v-model="form.description" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button :loading="formTesting" @click="testForm">测试连接</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <!-- 文件浏览 -->
    <el-dialog v-model="browseVisible" :title="'浏览：' + (browseServer?.name || '')" width="900px" top="6vh" destroy-on-close>
      <div class="path-bar">
        <el-button size="small" @click="goUp" :disabled="!canGoUp">上级</el-button>
        <el-input v-model="currentPath" size="small" style="flex:1" @keyup.enter="loadFiles(currentPath)" />
        <el-button size="small" @click="loadFiles(currentPath)">打开</el-button>
        <el-button size="small" @click="loadFiles()">根目录</el-button>
      </div>
      <el-table :data="files" v-loading="filesLoading" size="small" stripe style="width:100%">
        <el-table-column label="名称" min-width="280">
          <template #default="{ row }">
            <span class="file-name" @click="row.directory ? loadFiles(row.path) : null">
              <el-icon><Folder v-if="row.directory" /><Document v-else /></el-icon>
              {{ row.name }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="90">
          <template #default="{ row }">{{ row.directory ? '目录' : '文件' }}</template>
        </el-table-column>
        <el-table-column label="大小" width="110">
          <template #default="{ row }">{{ row.directory ? '-' : fmtSize(row.size) }}</template>
        </el-table-column>
        <el-table-column prop="modifiedAt" label="修改时间" width="170" />
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.directory" link type="primary" @click="loadFiles(row.path)">进入</el-button>
            <el-button v-else v-permission="'asset:ftp:transfer'" link type="primary"
                       :loading="pullingPath === row.path" @click="pull(row)">拉取到本地</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!files.length && !filesLoading" description="目录为空" :image-size="60" />
      <template #footer><el-button @click="browseVisible = false">关闭</el-button></template>
    </el-dialog>

    <!-- 分发 -->
    <el-dialog v-model="distributeVisible" title="分发到受管服务器" width="860px" top="6vh" destroy-on-close>
      <el-alert type="info" :closable="false" show-icon style="margin-bottom:12px"
                :title="'文件：' + (distributeFile?.originalName || '') + '（' + fmtSize(distributeFile?.size) + '）'" />
      <el-form :model="distForm" label-width="110px">
        <el-form-item label="目标服务器" required>
          <el-select v-model="distForm.serverIds" multiple filterable collapse-tags style="width:100%"
                     placeholder="选择服务器（可多选）">
            <el-option v-for="s in servers" :key="s.id" :value="s.id" :label="`${s.hostname || s.ip} (${s.ip})`">
              <span>{{ s.hostname || s.ip }} ({{ s.ip }})</span>
              <el-tag v-if="!s.sshPasswordSet && !s.sshKey" size="small" type="warning" style="float:right">无SSH凭据</el-tag>
            </el-option>
          </el-select>
        </el-form-item>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="远程目录"><el-input v-model="distForm.remoteDir" placeholder="/tmp" /></el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="文件名"><el-input v-model="distForm.fileName" placeholder="默认使用原文件名" /></el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="8">
            <el-form-item label="覆盖"><el-switch v-model="distForm.overwrite" /></el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="权限"><el-input v-model="distForm.mode" placeholder="0644（可选）" /></el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="后置命令">
          <el-input v-model="distForm.postCommand" placeholder="可选，例如 systemctl restart myapp；{file} 会替换成远程路径" />
        </el-form-item>
      </el-form>
      <div style="display:flex;justify-content:flex-end;gap:10px">
        <el-button type="primary" :loading="distributing" :disabled="!distForm.serverIds.length" @click="doDistribute">开始分发</el-button>
      </div>
      <div v-if="distResult" style="margin-top:12px">
        <el-alert :type="distResult.failed ? 'warning' : 'success'" :closable="false" show-icon
                  :title="`分发完成：成功 ${distResult.success} 台，失败 ${distResult.failed} 台`" />
        <el-table :data="distResult.results" size="small" style="margin-top:10px">
          <el-table-column type="expand">
            <template #default="{ row }"><pre class="cmd-output">{{ row.postCommandResult || row.message || '-' }}</pre></template>
          </el-table-column>
          <el-table-column prop="hostname" label="主机" min-width="140" />
          <el-table-column prop="ip" label="IP" width="130" />
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="row.success ? 'success' : 'danger'">{{ row.success ? '成功' : '失败' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="remotePath" label="远程路径" min-width="220" show-overflow-tooltip />
          <el-table-column prop="message" label="结果" min-width="180" show-overflow-tooltip />
        </el-table>
      </div>
      <template #footer><el-button @click="distributeVisible = false">关闭</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '@/utils/request'

const servers = ref<any[]>([])
const staged = ref<any[]>([])
const loading = ref(false)
const keyword = ref('')
const testingId = ref<number | null>(null)
const formVisible = ref(false)
const isEdit = ref(false)
const editId = ref<number | null>(null)
const saving = ref(false)
const formTesting = ref(false)
const browseVisible = ref(false)
const browseServer = ref<any>(null)
const currentPath = ref('/')
const files = ref<any[]>([])
const filesLoading = ref(false)
const pullingPath = ref('')
const distributeVisible = ref(false)
const distributeFile = ref<any>(null)
const distributing = ref(false)
const distResult = ref<any>(null)
const targetServers = ref<any[]>([])

const form = reactive<any>({
  name: '', protocol: 'FTP', host: '', port: 21, username: '', password: '', passwordSet: false,
  basePath: '/', passive: true, enabled: true, description: ''
})
const distForm = reactive<any>({
  serverIds: [], remoteDir: '/tmp', fileName: '', overwrite: true, mode: '', postCommand: ''
})

const filteredServers = computed(() => {
  const k = keyword.value.trim().toLowerCase()
  if (!k) return servers.value
  return servers.value.filter((s: any) =>
    [s.name, s.host, s.username].some(v => (v || '').toLowerCase().includes(k)))
})
const canGoUp = computed(() => currentPath.value && currentPath.value !== '/' && currentPath.value !== (browseServer.value?.basePath || '/'))

async function reloadAll() {
  loading.value = true
  try {
    const [a, b] = await Promise.all([
      request.get('/api/ftp/servers'),
      request.get('/api/ftp/staged')
    ])
    servers.value = a.data || []
    staged.value = b.data?.files || []
  } finally { loading.value = false }
}

function openCreate() {
  isEdit.value = false; editId.value = null
  Object.assign(form, { name: '', protocol: 'FTP', host: '', port: 21, username: '', password: '', passwordSet: false,
    basePath: '/', passive: true, enabled: true, description: '' })
  formVisible.value = true
}

function openEdit(row: any) {
  isEdit.value = true; editId.value = row.id
  Object.assign(form, {
    name: row.name, protocol: row.protocol || 'FTP', host: row.host, port: row.port,
    username: row.username || '', password: '', passwordSet: !!row.passwordSet,
    basePath: row.basePath || '/', passive: row.passiveMode !== 0, enabled: row.enabled === 1,
    description: row.description || ''
  })
  formVisible.value = true
}

function onProtocolChange() {
  form.port = form.protocol === 'SFTP' ? 22 : 21
}

function payload() {
  return {
    name: form.name, protocol: form.protocol, host: form.host, port: form.port,
    username: form.username, password: form.password || null, basePath: form.basePath,
    passiveMode: form.passive ? 1 : 0, enabled: form.enabled ? 1 : 0, description: form.description
  }
}

async function save() {
  if (!form.name || !form.host) { ElMessage.warning('名称和主机不能为空'); return }
  saving.value = true
  try {
    if (isEdit.value && editId.value) await request.put(`/api/ftp/servers/${editId.value}`, payload())
    else await request.post('/api/ftp/servers', payload())
    ElMessage.success('保存成功')
    formVisible.value = false
    reloadAll()
  } finally { saving.value = false }
}

async function testServer(row: any) {
  testingId.value = row.id
  try {
    const r = await request.post(`/api/ftp/servers/${row.id}/test`)
    ElMessage[r.data?.success ? 'success' : 'error'](r.data?.message || '测试完成')
  } finally { testingId.value = null }
}

async function testForm() {
  if (!form.host) { ElMessage.warning('请填写主机'); return }
  formTesting.value = true
  try {
    // 编辑且未修改密码时，用已保存的密文测试；否则用当前表单（含明文密码）测试
    const useSaved = isEdit.value && !form.password
    const t = useSaved
      ? await request.post(`/api/ftp/servers/${editId.value}/test`)
      : await request.post('/api/ftp/test', payload())
    ElMessage[t.data?.success ? 'success' : 'error'](t.data?.message || '测试完成')
  } finally { formTesting.value = false }
}

async function remove(row: any) {
  try { await ElMessageBox.confirm(`确认删除 FTP「${row.name}」？`, '提示', { type: 'warning' }) } catch { return }
  await request.delete(`/api/ftp/servers/${row.id}`)
  ElMessage.success('已删除')
  reloadAll()
}

async function browse(row: any) {
  browseServer.value = row
  browseVisible.value = true
  currentPath.value = row.basePath || '/'
  await loadFiles()
}

async function loadFiles(path?: string) {
  if (!browseServer.value) return
  filesLoading.value = true
  try {
    const r = await request.get(`/api/ftp/servers/${browseServer.value.id}/files`, { params: { path: path || currentPath.value } })
    currentPath.value = r.data?.path || path || currentPath.value
    files.value = r.data?.files || []
  } finally { filesLoading.value = false }
}

function goUp() {
  const p = currentPath.value || '/'
  if (p === '/' ) return
  const idx = p.replace(/\/+$/, '').lastIndexOf('/')
  loadFiles(idx <= 0 ? '/' : p.substring(0, idx))
}

async function pull(row: any) {
  pullingPath.value = row.path
  try {
    const r = await request.post(`/api/ftp/servers/${browseServer.value.id}/pull`, { remotePath: row.path }, { timeout: 600000 })
    ElMessage.success(`已拉取：${r.data?.originalName || row.name}`)
    await reloadAll()
  } finally { pullingPath.value = '' }
}

async function deleteStaged(row: any) {
  try { await ElMessageBox.confirm(`确认删除暂存文件「${row.originalName}」？`, '提示', { type: 'warning' }) } catch { return }
  await request.delete('/api/ftp/staged', { params: { name: row.storedName } })
  ElMessage.success('已删除')
  reloadAll()
}

/** 下载暂存文件到当前浏览器（带 JWT，使用 blob 下载） */
async function downloadStaged(row: any) {
  const blob: any = await request.get('/api/ftp/staged/download', {
    params: { name: row.storedName }, responseType: 'blob', timeout: 600000
  })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = row.originalName || row.storedName
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

async function openDistribute(row: any) {
  distributeFile.value = row
  distResult.value = null
  Object.assign(distForm, {
    serverIds: [], remoteDir: '/tmp', fileName: row.originalName, overwrite: true, mode: '', postCommand: ''
  })
  distributeVisible.value = true
  if (!targetServers.value.length) {
    try {
      const r = await request.get('/api/server/page', { params: { page: 1, pageSize: 500 } })
      targetServers.value = r.data?.records || []
    } catch { }
  }
}

async function doDistribute() {
  if (!distForm.serverIds.length) { ElMessage.warning('请选择目标服务器'); return }
  distributing.value = true
  distResult.value = null
  try {
    const r = await request.post('/api/ftp/distribute', {
      storedName: distributeFile.value.storedName,
      ...distForm
    }, { timeout: 600000 })
    distResult.value = r.data
    ElMessage.success(`分发完成：成功 ${r.data?.success || 0} 台`)
  } finally { distributing.value = false }
}

function fmtSize(n: any) {
  const v = Number(n || 0)
  if (v >= 1024 ** 3) return (v / 1024 ** 3).toFixed(1) + ' GB'
  if (v >= 1024 ** 2) return (v / 1024 ** 2).toFixed(1) + ' MB'
  if (v >= 1024) return (v / 1024).toFixed(1) + ' KB'
  return v + ' B'
}
function fmtTime(t: any) { return t ? String(t).replace('T', ' ').slice(0, 19) : '-' }

onMounted(reloadAll)
</script>

<style scoped>
.ftp-toolbar { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; flex-wrap: wrap; }
.staged-head { display: flex; align-items: center; justify-content: space-between; }
.hint { font-size: 12px; color: #909399; line-height: 1.5; }
.path-bar { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; }
.file-name { display: inline-flex; align-items: center; gap: 6px; cursor: pointer; }
.file-name:hover { color: #409eff; }
.cmd-output { max-height: 180px; overflow: auto; background: #0f172a; color: #cbd5e1; padding: 8px; border-radius: 6px; font-size: 12px; white-space: pre-wrap; }
</style>