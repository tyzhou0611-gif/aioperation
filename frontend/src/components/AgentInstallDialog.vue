<template>
  <el-dialog :model-value="modelValue" title="安装 Prometheus Exporter" width="960px" top="5vh"
             :close-on-click-modal="false" destroy-on-close
             @update:model-value="onVisibleChange">
    <el-alert type="info" :closable="false" show-icon style="margin-bottom:12px"
              title="选择离线包和目标服务器后一键安装。支持官方/社区 Exporter，也可用“自定义”安装任意 tar.gz；安装完成可让 Prometheus 通过 http_sd 自动发现。" />

    <el-tabs v-model="activeTab" @tab-change="onTabChange">
      <el-tab-pane label="安装 Exporter" name="install">
    <el-form :model="form" label-width="110px">
      <el-row :gutter="12">
        <el-col :span="12">
          <el-form-item label="Exporter" required>
            <el-select v-model="form.exporter" filterable style="width:100%" @change="onExporterChange">
              <el-option-group label="官方 Exporter">
                <el-option v-for="c in official" :key="c.key" :label="`${c.name} (${c.key})`" :value="c.key" />
              </el-option-group>
              <el-option-group label="社区 / 其他">
                <el-option v-for="c in community" :key="c.key" :label="`${c.name} (${c.key})`" :value="c.key" />
              </el-option-group>
              <el-option-group label="其它">
                <el-option label="自定义 Exporter（任意 tar.gz 离线包）" value="__custom__" />
              </el-option-group>
            </el-select>
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="安装包" required>
            <div class="pkg-row">
              <el-select v-model="form.packageName" filterable placeholder="选择已上传的安装包" style="flex:1">
                <el-option v-for="p in packages" :key="p.name" :label="`${p.name} (${fmtSize(p.size)})`" :value="p.name" />
              </el-select>
              <el-upload :show-file-list="false" :http-request="uploadPackage" accept=".tar,.gz,.tgz,.xz">
                <el-button :loading="uploading">上传</el-button>
              </el-upload>
            </div>
          </el-form-item>
        </el-col>
      </el-row>

      <el-row v-if="isCustom" :gutter="12">
        <el-col :span="8">
          <el-form-item label="标识" required>
            <el-input v-model="form.customKey" placeholder="custom_exporter" />
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="显示名称">
            <el-input v-model="form.customName" placeholder="如 Apache Exporter" />
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="二进制名" required>
            <el-input v-model="form.binary" placeholder="压缩包内的可执行文件名" />
          </el-form-item>
        </el-col>
      </el-row>
      <el-row v-if="isCustom" :gutter="12">
        <el-col :span="8">
          <el-form-item label="监听参数">
            <el-input v-model="form.listenFlag" placeholder="--web.listen-address" />
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="配置文件">
            <el-input v-model="form.configFile" placeholder="可选，如 apache.yml" />
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="配置来源">
            <span class="hint">包内配置文件会自动复制；也可以粘贴到下方“配置文件”。</span>
          </el-form-item>
        </el-col>
      </el-row>

      <div v-if="current" class="exporter-info">
        <div><b>{{ current.name }}</b> · 默认端口 {{ current.port }} · 二进制 {{ current.binary }}</div>
        <div class="hint">{{ current.description }}</div>
        <div v-if="current.envHint" class="hint">环境变量示例：<code>{{ current.envHint }}</code></div>
        <div v-if="current.notes" class="hint warn">{{ current.notes }}</div>
      </div>
      <div v-else-if="isCustom" class="exporter-info">
        <div><b>自定义 Exporter</b> · 填写安装包内的二进制文件名和监听参数即可</div>
        <div class="hint">适合官网/社区任意以 tar.gz 发布的 Exporter；官方包内的配置文件会被自动复制到安装目录。</div>
      </div>

      <el-row :gutter="12">
        <el-col :span="8">
          <el-form-item label="端口">
            <el-input-number v-model="form.port" :min="1" :max="65535" style="width:100%" />
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="运行用户">
            <el-input v-model="form.runUser" placeholder="prometheus" />
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="安装目录">
            <el-input v-model="form.installDir" placeholder="/opt/prometheus/node_exporter" />
          </el-form-item>
        </el-col>
      </el-row>

      <el-form-item label="额外参数">
        <el-input v-model="form.extraArgs" placeholder='例如 --redis.addr=redis://127.0.0.1:6379；支持 $DIR 占位' />
        <div class="hint">会拼到 ExecStart 命令行；需要引号/换行的参数请谨慎使用。</div>
      </el-form-item>

      <el-form-item label="环境变量">
        <el-input v-model="form.envContent" type="textarea" :rows="2"
                  placeholder="每行一个 KEY=value，如 DATA_SOURCE_NAME=exporter:pass@tcp(127.0.0.1:3306)/" />
        <div class="hint">写入 /etc/default/aiops-&lt;exporter&gt;，systemd 以 600 权限读取；适合数据库/Redis 密码。</div>
      </el-form-item>

      <el-form-item v-if="current?.configFile || (isCustom && form.configFile)" label="配置文件">
        <el-input v-model="form.configContent" type="textarea" :rows="4"
                  :placeholder="'写入 ' + (current?.configFile || form.configFile) + '；留空时使用安装包内自带配置（blackbox 会生成最小 http_2xx 配置）'" />
      </el-form-item>

      <el-row :gutter="12">
        <el-col :span="8">
          <el-form-item label="sudo 安装">
            <el-switch v-model="form.useSudo" />
            <div class="hint">非 root 用户通过 sudo -n 执行；需配置免密 sudo。</div>
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="开放防火墙">
            <el-switch v-model="form.openFirewall" />
            <div class="hint">firewalld/ufw 可用时开放端口。</div>
          </el-form-item>
        </el-col>
      </el-row>

      <el-form-item label="目标服务器" required>
        <el-select v-model="form.serverIds" multiple filterable collapse-tags style="width:100%"
                   placeholder="选择服务器（可多选）">
          <el-option v-for="s in servers" :key="s.id" :value="s.id" :label="`${s.hostname || s.ip} (${s.ip})`">
            <span>{{ s.hostname || s.ip }} ({{ s.ip }})</span>
            <el-tag v-if="!s.sshPasswordSet && !s.sshKey" size="small" type="warning" style="float:right">无SSH凭据</el-tag>
          </el-option>
        </el-select>
        <div class="hint">已选 {{ form.serverIds.length }} 台；单次最多 30 台。</div>
      </el-form-item>
    </el-form>

    <div class="install-actions">
      <el-button type="primary" :loading="installing" :disabled="!form.exporter || !form.packageName || !form.serverIds.length || (isCustom && !form.binary)"
                 @click="install">开始安装</el-button>
      <span class="sd-hint">Prometheus HTTP SD：<code>/api/monitor/targets</code>（安装成功后可用于自动发现）</span>
    </div>

    <div v-if="result" class="install-result">
      <el-alert :type="result.failed ? 'warning' : 'success'" :closable="false" show-icon
                :title="`安装完成：成功 ${result.success} 台，失败 ${result.failed} 台`" />
      <el-table :data="result.results" size="small" style="margin-top:10px">
        <el-table-column type="expand">
          <template #default="{ row }">
            <pre class="install-output">{{ row.output || row.message || '-' }}</pre>
          </template>
        </el-table-column>
        <el-table-column prop="hostname" label="主机" min-width="140" />
        <el-table-column prop="ip" label="IP" width="140" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="row.success ? 'success' : 'danger'">{{ row.success ? '成功' : '失败' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="message" label="结果" min-width="260" show-overflow-tooltip />
      </el-table>
    </div>
      </el-tab-pane>

      <el-tab-pane label="已安装" name="installed">
        <div class="installed-bar">
          <span class="hint">安装记录来自 server_agent；卸载会停止并删除远端 systemd 服务与安装目录。</span>
          <el-button size="small" :loading="installedLoading" @click="loadInstalled">刷新</el-button>
        </div>
        <el-table :data="installed" v-loading="installedLoading" size="small" stripe style="width:100%">
          <el-table-column label="主机" min-width="150">
            <template #default="{ row }">{{ serverLabel(row.serverId) }}</template>
          </el-table-column>
          <el-table-column prop="exporter" label="Exporter" width="150" />
          <el-table-column prop="version" label="版本" width="90" />
          <el-table-column prop="port" label="端口" width="75" align="center" />
          <el-table-column prop="serviceName" label="服务名" min-width="150" show-overflow-tooltip />
          <el-table-column label="状态" width="95">
            <template #default="{ row }">
              <el-tag size="small" :type="agentStatusType(row.status)">{{ agentStatusLabel(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="installedAt" label="安装时间" width="170">
            <template #default="{ row }">{{ fmtTime(row.installedAt) }}</template>
          </el-table-column>
          <el-table-column prop="message" label="最近结果" min-width="200" show-overflow-tooltip />
          <el-table-column label="操作" width="150" fixed="right">
            <template #default="{ row }">
              <el-button link type="danger" :loading="uninstallingId === row.id" @click="uninstall(row)">卸载</el-button>
              <el-button link type="info" @click="removeRecord(row)">删记录</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-if="!installed.length && !installedLoading" description="暂无安装记录" :image-size="60" />
      </el-tab-pane>
    </el-tabs>

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">关闭</el-button>
      <el-button @click="refreshAll">刷新列表</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '@/utils/request'

const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ (e: 'update:modelValue', v: boolean): void }>()

function onVisibleChange(v: boolean) { emit('update:modelValue', v) }

const catalog = ref<any[]>([])
const packages = ref<any[]>([])
const servers = ref<any[]>([])
const installed = ref<any[]>([])
const uploading = ref(false)
const installing = ref(false)
const installedLoading = ref(false)
const uninstallingId = ref<number | null>(null)
const activeTab = ref('install')
const result = ref<any>(null)

const form = reactive<any>({
  exporter: 'node_exporter', packageName: '', port: 9100, runUser: 'node_exporter',
  installDir: '/opt/prometheus/node_exporter', extraArgs: '', envContent: '', configContent: '',
  useSudo: true, openFirewall: false, serverIds: [],
  customKey: 'custom_exporter', customName: '', binary: '', listenFlag: '--web.listen-address', configFile: ''
})

const isCustom = computed(() => form.exporter === '__custom__')
const official = computed(() => catalog.value.filter(c => c.category === 'official'))
const community = computed(() => catalog.value.filter(c => c.category !== 'official'))
const current = computed(() => catalog.value.find(c => c.key === form.exporter))

function fmtSize(n: any) {
  const v = Number(n || 0)
  if (v >= 1024 ** 3) return (v / 1024 ** 3).toFixed(1) + ' GB'
  if (v >= 1024 ** 2) return (v / 1024 ** 2).toFixed(1) + ' MB'
  if (v >= 1024) return (v / 1024).toFixed(1) + ' KB'
  return v + ' B'
}
function fmtTime(t: any) { return t ? String(t).replace('T', ' ').slice(0, 19) : '-' }
function serverLabel(id: any) {
  const s = servers.value.find((x: any) => String(x.id) === String(id))
  return s ? `${s.hostname || s.ip} (${s.ip})` : `服务器 #${id}`
}
function agentStatusType(s: string) {
  return s === 'ONLINE' ? 'success' : s === 'UNINSTALLED' ? 'info' : 'danger'
}
function agentStatusLabel(s: string) {
  return ({ ONLINE: '已安装', UNINSTALLED: '已卸载', FAILED: '失败', UNKNOWN: '未知' } as any)[s] || s || '-'
}

async function reload() {
  try {
    const [c, p, s] = await Promise.all([
      request.get('/api/agents/catalog'),
      request.get('/api/agents/packages'),
      request.get('/api/server/page', { params: { page: 1, pageSize: 500 } })
    ])
    catalog.value = c.data || []
    packages.value = p.data?.packages || []
    servers.value = s.data?.records || []
    const cur = current.value
    if (cur && !form.packageName) {
      const pref = packages.value.find(x => x.name.toLowerCase().includes(String(cur.key).split('_')[0]))
      if (pref) form.packageName = pref.name
    }
  } catch { /* 拦截器已提示 */ }
}

async function loadInstalled() {
  installedLoading.value = true
  try {
    const r = await request.get('/api/agents/installed')
    installed.value = r.data || []
  } finally {
    installedLoading.value = false
  }
}

function onTabChange(name: any) {
  if (name === 'installed') loadInstalled()
}

function refreshAll() {
  reload()
  if (activeTab.value === 'installed') loadInstalled()
}

function onExporterChange() {
  const c = current.value
  if (isCustom.value) {
    form.port = 9100
    form.runUser = 'prometheus'
    form.installDir = '/opt/prometheus/' + (form.customKey || 'custom_exporter')
    form.extraArgs = ''
    form.configContent = ''
    form.packageName = ''
    return
  }
  if (!c) return
  form.port = c.port || 9100
  form.runUser = c.defaultUser || 'prometheus'
  form.installDir = '/opt/prometheus/' + c.key
  form.extraArgs = c.defaultArgs || ''
  form.configContent = ''
  const pref = packages.value.find(x => x.name.toLowerCase().includes(String(c.key).split('_')[0]))
  form.packageName = pref ? pref.name : ''
}

async function uploadPackage(opt: any) {
  uploading.value = true
  try {
    const fd = new FormData()
    fd.append('file', opt.file)
    const r = await request.post('/api/agents/packages', fd, { timeout: 300000 })
    ElMessage.success('安装包已上传')
    form.packageName = r.data?.name || form.packageName
    await reload()
  } finally { uploading.value = false }
}

async function install() {
  if (!form.exporter || !form.packageName) { ElMessage.warning('请选择 Exporter 和安装包'); return }
  if (isCustom.value && !form.binary) { ElMessage.warning('请填写安装包内的二进制文件名'); return }
  if (!form.serverIds.length) { ElMessage.warning('请选择目标服务器'); return }
  installing.value = true
  result.value = null
  try {
    const r = await request.post('/api/agents/install', { ...form }, { timeout: 600000 })
    result.value = r.data
    ElMessage.success(`安装完成：成功 ${r.data?.success || 0} 台`)
    if (r.data?.success) {
      form.serverIds = []
      loadInstalled()
    }
  } finally { installing.value = false }
}

async function uninstall(row: any) {
  try {
    await ElMessageBox.confirm(
      `确认卸载 ${row.exporter}？将停止并删除服务器 ${serverLabel(row.serverId)} 上的 systemd 服务与安装目录。`,
      '卸载 Exporter', { type: 'warning' })
  } catch { return }
  uninstallingId.value = row.id
  try {
    const r = await request.post('/api/agents/uninstall', {
      exporter: row.exporter, serverIds: [row.serverId]
    }, { timeout: 300000 })
    const res = r.data?.results?.[0]
    if (res?.success) ElMessage.success('卸载成功')
    else ElMessage.error(res?.message || '卸载失败')
    await loadInstalled()
  } finally { uninstallingId.value = null }
}

async function removeRecord(row: any) {
  try {
    await ElMessageBox.confirm(
      `只删除「${row.exporter}」的安装记录，不会卸载远端服务。确认继续？`,
      '删除安装记录', { type: 'warning' })
  } catch { return }
  await request.delete(`/api/agents/installed/${row.id}`)
  ElMessage.success('记录已删除')
  await loadInstalled()
}

watch(() => props.modelValue, (v) => {
  if (v) {
    result.value = null
    activeTab.value = 'install'
    reload()
    loadInstalled()
  }
})
</script>

<style scoped>
.pkg-row { display: flex; gap: 8px; width: 100%; align-items: center; }
.exporter-info { background: #f5f7fa; border: 1px solid #e4e7ed; border-radius: 8px; padding: 10px 14px; margin-bottom: 14px; font-size: 13px; }
.hint { font-size: 12px; color: #909399; margin-top: 4px; line-height: 1.5; }
.hint.warn { color: #e6a23c; }
.install-actions { display: flex; align-items: center; gap: 16px; padding: 8px 0; border-top: 1px solid #ebeef5; margin-top: 6px; }
.sd-hint { font-size: 12px; color: #909399; }
.install-output { max-height: 220px; overflow: auto; background: #0f172a; color: #cbd5e1; padding: 10px; border-radius: 6px; font-size: 12px; white-space: pre-wrap; }
.installed-bar { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; gap: 12px; }
</style>