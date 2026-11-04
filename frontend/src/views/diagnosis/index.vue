<template>
  <div class="diag-page">
    <h2 class="page-title">智能诊断</h2>

    <!-- 引擎状态 -->
    <el-alert
      v-if="status.engine === 'LLM'"
      type="success" show-icon :closable="false" class="engine-alert"
      :title="'诊断引擎：大模型' + (status.model ? '（' + status.model + '）' : '')"
      description="按所选对象采集服务器实时指标/错误日志、中间件节点与协议状态、应用信息，由大模型给出根因分析与建议；调用失败会自动回退本地规则引擎。"
    />
    <el-alert
      v-else
      type="warning" show-icon :closable="false" class="engine-alert"
      title="诊断引擎：本地规则引擎（大模型不可用）"
      description="请到 系统设置 → AI 配置 检查模型地址与 API Key。规则引擎仅支持服务器指标阈值判断，中间件/应用/自定义问题只能给出兜底指引。"
    />

    <el-card>
      <el-form label-position="top" style="margin-bottom:8px">
        <el-form-item label="诊断对象">
          <el-select v-model="form.target" filterable placeholder="选择服务器 / 中间件 / 应用，或自定义" style="width:420px">
            <el-option-group label="服务器" v-if="servers.length">
              <el-option v-for="s in servers" :key="'SERVER:' + s.id"
                         :label="(s.hostname || s.ip) + ' (' + s.ip + ')'" :value="'SERVER:' + s.id" />
            </el-option-group>
            <el-option-group label="中间件 / 数据库" v-if="middlewares.length">
              <el-option v-for="m in middlewares" :key="'MIDDLEWARE:' + m.id"
                         :label="m.name + ' (' + m.type + ' ' + (m.ip || '-') + ':' + (m.port || '-') + ')'"
                         :value="'MIDDLEWARE:' + m.id" />
            </el-option-group>
            <el-option-group label="应用" v-if="apps.length">
              <el-option v-for="a in apps" :key="'APP:' + a.id"
                         :label="a.name + (a.code ? ' (' + a.code + ')' : '')" :value="'APP:' + a.id" />
            </el-option-group>
            <el-option-group label="其他">
              <el-option label="自定义诊断（填写补充说明）" value="CUSTOM:0" />
            </el-option-group>
          </el-select>
          <div class="hint">支持服务器、Kafka/ZooKeeper/Redis/数据库/Hadoop 等中间件、应用；也可不选资产，直接描述问题。</div>
        </el-form-item>
        <el-form-item :label="isCustom ? '问题描述（必填）' : '补充说明（可选）'">
          <el-input v-model="form.extraPrompt" type="textarea" :rows="2" maxlength="1000" show-word-limit
                    :placeholder="isCustom
                      ? '例如：Kafka 消费延迟持续升高，消费者组 lag 很大，应该怎么排查？'
                      : '例如：最近 1 小时频繁超时，已重启过一次；可补充现象、时间范围、已尝试操作等'" />
        </el-form-item>
        <el-form-item>
          <el-button v-permission="'diagnosis:run'" type="primary" :loading="diagnosing" @click="runDiagnosis">
            {{ diagnosing ? '诊断中…' : '开始诊断' }}
          </el-button>
          <span v-if="!servers.length && !middlewares.length && !apps.length" class="no-server">
            暂无资产，可选择「自定义诊断」直接描述问题。
          </span>
        </el-form-item>
      </el-form>

      <!-- 诊断结果 -->
      <el-card v-if="result" shadow="never" class="result-card">
        <div class="result-head">
          <el-tag :type="result.diagnosisType === 'LLM' ? 'success' : 'info'" effect="dark" size="small">
            {{ result.diagnosisType === 'LLM' ? '大模型诊断' : '规则诊断' }}
          </el-tag>
          <el-tag v-if="result.targetType" size="small" effect="plain">
            {{ targetTypeLabel(result.targetType) }} · {{ result.targetName || '-' }}
          </el-tag>
          <span v-if="result.model" class="meta">模型：{{ result.model }}</span>
          <span class="meta">置信度：{{ (result.confidence * 100).toFixed(0) }}%</span>
          <span v-if="result.elapsedMs" class="meta">耗时：{{ (result.elapsedMs / 1000).toFixed(1) }}s</span>
        </div>

        <div class="result-block">
          <div class="rb-label"><el-icon><Search /></el-icon> 根因分析</div>
          <div class="rb-text">{{ result.diagnosis }}</div>
        </div>
        <div class="result-block">
          <div class="rb-label"><el-icon><MagicStick /></el-icon> 修复建议</div>
          <div class="rb-text">{{ result.suggestion }}</div>
        </div>
      </el-card>

      <el-divider />

      <div class="history-head">
        <h4>诊断历史</h4>
        <el-button link size="small" @click="loadHistory">刷新</el-button>
      </div>
      <el-table :data="history" stripe style="width:100%" empty-text="暂无诊断记录">
        <el-table-column label="诊断对象" min-width="220">
          <template #default="{ row }">
            <el-tag size="small" effect="plain" style="margin-right:6px">
              {{ targetTypeLabel(row.targetType || 'SERVER') }}
            </el-tag>
            <span>{{ row.targetName || row.serverIp || '-' }}</span>
            <div v-if="row.serverIp && row.targetName" class="hint">{{ row.serverIp }}</div>
          </template>
        </el-table-column>
        <el-table-column prop="diagnosisType" label="引擎" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.diagnosisType === 'LLM' ? 'success' : 'info'">
              {{ row.diagnosisType === 'LLM' ? '大模型' : '规则' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="diagnosisResult" label="诊断" min-width="300" show-overflow-tooltip />
        <el-table-column prop="confidence" label="置信度" width="90">
          <template #default="{ row }">{{ row.confidence != null ? (row.confidence * 100).toFixed(0) + '%' : '-' }}</template>
        </el-table-column>
        <el-table-column label="时间" width="170">
          <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Search, MagicStick } from '@element-plus/icons-vue'
import request from '@/utils/request'

const route = useRoute()

const servers = ref<any[]>([])
const middlewares = ref<any[]>([])
const apps = ref<any[]>([])
const history = ref<any[]>([])
const diagnosing = ref(false)
const result = ref<any>(null)
const status = ref<any>({ engine: 'RULE', provider: 'none', model: '' })

const form = reactive({ target: '', extraPrompt: '' })
const targetType = computed(() => (form.target || '').split(':')[0])
const isCustom = computed(() => targetType.value === 'CUSTOM')

const TYPE_LABEL: Record<string, string> = {
  SERVER: '服务器', MIDDLEWARE: '中间件', APP: '应用', CUSTOM: '自定义'
}
function targetTypeLabel(t: string) { return TYPE_LABEL[t] || t }

function formatTime(t: string) {
  return t ? t.replace('T', ' ').substring(0, 19) : ''
}

async function loadStatus() {
  try {
    const res = await request.get('/api/diagnosis/status')
    status.value = res.data || status.value
  } catch { /* 状态获取失败时保持默认（规则引擎） */ }
}

async function loadTargets() {
  const [s, m, a] = await Promise.allSettled([
    request.get('/api/server/page', { params: { page: 1, pageSize: 500 } }),
    request.get('/api/middleware/options'),
    request.get('/api/app/options')
  ])
  servers.value = s.status === 'fulfilled' ? (s.value.data.records || []) : []
  middlewares.value = m.status === 'fulfilled' ? (m.value.data || []) : []
  apps.value = a.status === 'fulfilled' ? (a.value.data || []) : []
  if (!form.target) {
    if (servers.value.length) form.target = 'SERVER:' + servers.value[0].id
    else if (middlewares.value.length) form.target = 'MIDDLEWARE:' + middlewares.value[0].id
    else if (apps.value.length) form.target = 'APP:' + apps.value[0].id
    else form.target = 'CUSTOM:0'
  }
}

async function loadHistory() {
  const res = await request.get('/api/diagnosis/history')
  history.value = res.data || []
}

function resolveTarget() {
  const [type, rawId] = (form.target || '').split(':')
  const id = Number(rawId) || null
  if (type === 'CUSTOM') return { type, id: null, name: '自定义问题', ip: '' }
  if (type === 'SERVER') {
    const s = servers.value.find((x: any) => x.id === id)
    return { type, id, name: s ? (s.hostname || s.ip) : '', ip: s ? s.ip : '' }
  }
  if (type === 'MIDDLEWARE') {
    const m = middlewares.value.find((x: any) => x.id === id)
    return { type, id, name: m ? m.name : '', ip: m ? (m.ip || '') : '' }
  }
  if (type === 'APP') {
    const a = apps.value.find((x: any) => x.id === id)
    return { type, id, name: a ? a.name : '', ip: '' }
  }
  return { type: '', id: null, name: '', ip: '' }
}

async function runDiagnosis() {
  const t = resolveTarget()
  if (!t.type) { ElMessage.warning('请选择诊断对象'); return }
  if (t.type === 'CUSTOM') {
    if (!form.extraPrompt.trim()) { ElMessage.warning('自定义诊断请填写问题描述'); return }
  } else if (!t.id) {
    ElMessage.warning('请选择有效的诊断对象'); return
  }
  diagnosing.value = true
  result.value = null
  try {
    const res = await request.post('/api/diagnosis/run', {
      targetType: t.type,
      targetId: t.id,
      targetName: t.name,
      serverId: t.type === 'SERVER' ? t.id : null,
      serverIp: t.ip,
      extraPrompt: form.extraPrompt.trim()
    }, { timeout: 180000 })
    result.value = res.data
    if (res.data?.diagnosisType === 'RULE' && status.value.engine === 'LLM') {
      ElMessage.warning('大模型调用失败，本次已回退本地规则引擎')
    } else {
      ElMessage.success('诊断完成')
    }
    loadHistory()
  } catch {
    /* 错误提示由 request 拦截器统一处理 */
  } finally {
    diagnosing.value = false
  }
}

/** 支持从大屏/其他页面带参跳转：?targetType=SERVER&targetId=1 */
function applyRouteTarget() {
  const type = String(route.query.targetType || '').toUpperCase()
  if (!type || type === 'CUSTOM') return
  const id = Number(route.query.targetId)
  if (!id) return
  const pool = type === 'SERVER' ? servers.value : type === 'MIDDLEWARE' ? middlewares.value : type === 'APP' ? apps.value : []
  if (pool.some((x: any) => Number(x.id) === id)) form.target = type + ':' + id
}

onMounted(async () => {
  loadStatus()
  await loadTargets()
  applyRouteTarget()
  loadHistory()
})
</script>

<style scoped>
.diag-page { padding-bottom: 24px; }
.page-title { margin: 0 0 20px; font-size: 22px; color: #333; }
.engine-alert { margin-bottom: 16px; }
.no-server { color: #909399; font-size: 13px; margin-left: 10px; }
.hint { color: #909399; font-size: 12px; line-height: 1.5; margin-top: 4px; }
.result-card { background: #fafbfc; }
.result-head { display: flex; align-items: center; flex-wrap: wrap; gap: 14px; padding-bottom: 10px; border-bottom: 1px dashed #e4e7ed; }
.result-head .meta { font-size: 13px; color: #606266; }
.result-block { margin-top: 14px; }
.rb-label { display: flex; align-items: center; gap: 6px; font-weight: 600; color: #303133; margin-bottom: 6px; }
.rb-text { font-size: 14px; line-height: 1.8; color: #303133; white-space: pre-wrap; word-break: break-word; }
.history-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }
.history-head h4 { margin: 0; }
</style>