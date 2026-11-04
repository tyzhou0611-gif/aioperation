<template>
  <div class="m-page">
    <div class="m-page-inner">
      <!-- 引擎状态 -->
      <div class="engine" :class="status.engine === 'LLM' ? 'ok' : 'warn'">
        <el-icon><component :is="status.engine === 'LLM' ? 'MagicStick' : 'Warning'" /></el-icon>
        <div>
          <div class="engine-title">{{ status.engine === 'LLM' ? '大模型诊断' : '本地规则引擎' }}</div>
          <div class="engine-sub">{{ status.engine === 'LLM' ? (status.model || 'AI 大模型') + ' · 采集实时指标与日志' : '大模型不可用，仅支持服务器指标阈值判断' }}</div>
        </div>
      </div>

      <section class="m-card">
        <div class="m-card-head">
          <div class="m-title">诊断对象 <span class="en">TARGET</span></div>
        </div>
        <el-select v-model="target" filterable placeholder="选择服务器 / 中间件 / 应用，或自定义" style="width:100%" @change="onTargetChange">
          <el-option-group v-if="servers.length" label="服务器">
            <el-option v-for="s in servers" :key="'SERVER:' + s.id" :label="`${s.hostname || s.ip} (${s.ip})`" :value="'SERVER:' + s.id" />
          </el-option-group>
          <el-option-group v-if="middlewares.length" label="中间件 / 数据库">
            <el-option v-for="m in middlewares" :key="'MIDDLEWARE:' + m.id" :label="`${m.name} (${m.type} ${m.ip || '-'}:${m.port || '-'})`" :value="'MIDDLEWARE:' + m.id" />
          </el-option-group>
          <el-option-group v-if="apps.length" label="应用">
            <el-option v-for="a in apps" :key="'APP:' + a.id" :label="a.name + (a.code ? ' (' + a.code + ')' : '')" :value="'APP:' + a.id" />
          </el-option-group>
          <el-option-group label="其他">
            <el-option label="自定义诊断（描述问题）" value="CUSTOM:0" />
          </el-option-group>
        </el-select>

        <div class="m-hint" style="margin:10px 0 6px">{{ isCustom ? '问题描述（必填）' : '补充说明（可选）' }}</div>
        <el-input
          v-model="extraPrompt"
          type="textarea"
          :rows="4"
          maxlength="1000"
          show-word-limit
          :placeholder="isCustom ? '例如：Kafka 消费延迟持续升高，消费者组 lag 很大，应该怎么排查？' : '例如：最近 1 小时频繁超时，已重启过一次'"
        />

        <button v-if="canRun" class="run-btn" :disabled="diagnosing" @click="run">
          <el-icon v-if="!diagnosing"><MagicStick /></el-icon>
          <el-icon v-else class="spin"><Loading /></el-icon>
          {{ diagnosing ? '诊断中，通常需要 10-30 秒…' : '开始诊断' }}
        </button>
        <div v-else class="m-hint" style="margin-top:10px">当前角色没有诊断权限</div>
      </section>

      <!-- 结果 -->
      <section v-if="result" class="m-card result">
        <div class="m-card-head">
          <div class="m-title">诊断结果 <span class="en">{{ result.diagnosisType === 'LLM' ? 'LLM' : 'RULE' }}</span></div>
          <span class="m-tag" :class="result.diagnosisType === 'LLM' ? 'green' : 'gray'">{{ result.diagnosisType === 'LLM' ? '大模型' : '规则' }}</span>
        </div>
        <div class="result-tags">
          <span class="m-tag blue">{{ typeLabel(result.targetType) }} · {{ result.targetName || '-' }}</span>
          <span class="m-tag gray">置信度 {{ Math.round((Number(result.confidence) || 0) * 100) }}%</span>
          <span v-if="result.elapsedMs" class="m-tag gray">{{ (result.elapsedMs / 1000).toFixed(1) }}s</span>
        </div>
        <div class="block">
          <div class="block-title"><el-icon><Search /></el-icon> 根因分析</div>
          <div class="block-text">{{ result.diagnosis }}</div>
        </div>
        <div class="block">
          <div class="block-title"><el-icon><Tools /></el-icon> 修复建议</div>
          <div class="block-text">{{ result.suggestion }}</div>
        </div>
      </section>

      <!-- 历史 -->
      <section class="m-card">
        <div class="m-card-head">
          <div class="m-title">诊断历史 <span class="en">HISTORY</span></div>
          <button class="link-btn" @click="loadHistory"><el-icon><Refresh /></el-icon></button>
        </div>
        <div v-if="history.length" class="m-list">
          <div v-for="h in history" :key="h.id" class="history-card" @click="toggle(h.id)">
            <div class="history-top">
              <span class="m-tag" :class="h.diagnosisType === 'LLM' ? 'green' : 'gray'">{{ h.diagnosisType === 'LLM' ? '大模型' : '规则' }}</span>
              <span class="m-tag blue">{{ typeLabel(h.targetType || 'SERVER') }}</span>
              <span class="target">{{ h.targetName || h.serverIp || '-' }}</span>
              <span class="time">{{ timeAgo(h.createTime) }}</span>
            </div>
            <div class="history-text" :class="{ expanded: expandedId === h.id }">{{ h.diagnosisResult }}</div>
            <div v-if="expandedId === h.id" class="history-suggestion">
              <span class="m-hint">修复建议</span>
              <div class="history-text expanded">{{ h.suggestion }}</div>
            </div>
          </div>
        </div>
        <div v-else class="m-empty">暂无诊断记录</div>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import request from '@/utils/request'
import { useUserStore } from '@/stores/user'
import { MagicStick, Loading, Search, Tools, Refresh } from '@element-plus/icons-vue'
import { timeAgo } from '@/utils/mobile'

const route = useRoute()
const userStore = useUserStore()
const canRun = computed(() => userStore.permissions.includes('*') || userStore.permissions.includes('diagnosis:run'))

const servers = ref<any[]>([])
const middlewares = ref<any[]>([])
const apps = ref<any[]>([])
const history = ref<any[]>([])
const target = ref('')
const extraPrompt = ref('')
const diagnosing = ref(false)
const result = ref<any>(null)
const status = ref<any>({ engine: 'RULE', provider: 'none', model: '' })
const expandedId = ref<number | null>(null)

const TYPE_LABEL: Record<string, string> = { SERVER: '服务器', MIDDLEWARE: '中间件', APP: '应用', CUSTOM: '自定义' }
const typeLabel = (t: string) => TYPE_LABEL[t] || t || '-'

const targetType = computed(() => (target.value || '').split(':')[0])
const isCustom = computed(() => targetType.value === 'CUSTOM')

function onTargetChange() {
  result.value = null
}

async function loadStatus() {
  try {
    const r = await request.get('/api/diagnosis/status')
    status.value = r.data || status.value
  } catch { /* ignore */ }
}

async function loadTargets() {
  const [s, m, a] = await Promise.allSettled([
    request.get('/api/server/page', { params: { page: 1, pageSize: 500 } }),
    request.get('/api/middleware/options'),
    request.get('/api/app/options')
  ])
  servers.value = s.status === 'fulfilled' ? (s.value.data?.records || []) : []
  middlewares.value = m.status === 'fulfilled' ? (m.value.data || []) : []
  apps.value = a.status === 'fulfilled' ? (a.value.data || []) : []
  applyRouteTarget()
  if (!target.value) {
    if (servers.value.length) target.value = 'SERVER:' + servers.value[0].id
    else if (middlewares.value.length) target.value = 'MIDDLEWARE:' + middlewares.value[0].id
    else if (apps.value.length) target.value = 'APP:' + apps.value[0].id
    else target.value = 'CUSTOM:0'
  }
}

function applyRouteTarget() {
  const type = String(route.query.targetType || '').toUpperCase()
  const id = Number(route.query.targetId)
  if (!type || !id) return
  const pool = type === 'SERVER' ? servers.value : type === 'MIDDLEWARE' ? middlewares.value : type === 'APP' ? apps.value : []
  if (pool.some((x: any) => Number(x.id) === id)) target.value = type + ':' + id
}

async function loadHistory() {
  try {
    const r = await request.get('/api/diagnosis/history')
    history.value = r.data || []
  } catch { history.value = [] }
}

function resolveTarget() {
  const [type, rawId] = (target.value || '').split(':')
  const id = Number(rawId) || null
  if (type === 'CUSTOM') return { type, id: null, name: '自定义问题', ip: '' }
  if (type === 'SERVER') {
    const s = servers.value.find(x => x.id === id)
    return { type, id, name: s ? (s.hostname || s.ip) : '', ip: s?.ip || '' }
  }
  if (type === 'MIDDLEWARE') {
    const m = middlewares.value.find(x => x.id === id)
    return { type, id, name: m?.name || '', ip: m?.ip || '' }
  }
  if (type === 'APP') {
    const a = apps.value.find(x => x.id === id)
    return { type, id, name: a?.name || '', ip: '' }
  }
  return { type: '', id: null, name: '', ip: '' }
}

async function run() {
  const t = resolveTarget()
  if (!t.type) { ElMessage.warning('请选择诊断对象'); return }
  if (t.type === 'CUSTOM' && !extraPrompt.value.trim()) { ElMessage.warning('请填写问题描述'); return }
  if (t.type !== 'CUSTOM' && !t.id) { ElMessage.warning('请选择有效的诊断对象'); return }
  diagnosing.value = true
  result.value = null
  try {
    const r = await request.post('/api/diagnosis/run', {
      targetType: t.type,
      targetId: t.id,
      targetName: t.name,
      serverId: t.type === 'SERVER' ? t.id : null,
      serverIp: t.ip,
      extraPrompt: extraPrompt.value.trim()
    }, { timeout: 180000 })
    result.value = r.data
    if (r.data?.diagnosisType === 'RULE' && status.value.engine === 'LLM') {
      ElMessage.warning('大模型调用失败，本次已回退本地规则引擎')
    } else {
      ElMessage.success('诊断完成')
    }
    loadHistory()
  } catch { /* 拦截器已提示 */ } finally {
    diagnosing.value = false
  }
}

function toggle(id: number) {
  expandedId.value = expandedId.value === id ? null : id
}

onMounted(async () => {
  loadStatus()
  await loadTargets()
  loadHistory()
})
</script>

<style scoped>
.engine {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 10px 12px;
  border-radius: 13px;
  background: #fff7e8;
  border: 1px solid #ffe1b8;
  color: #b26a00;
}

.engine.ok {
  background: #eafaf3;
  border-color: #bfeeda;
  color: #0d9f6e;
}

.engine .el-icon {
  font-size: 20px;
  flex: 0 0 auto;
}

.engine-title {
  font-size: 13px;
  font-weight: 700;
}

.engine-sub {
  margin-top: 2px;
  font-size: 10.5px;
  opacity: 0.82;
}

.run-btn {
  margin-top: 14px;
  width: 100%;
  height: 42px;
  border: none;
  border-radius: 12px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  color: #fff;
  font-size: 14px;
  font-weight: 600;
  background: linear-gradient(135deg, #1677ff, #8b5cf6);
  box-shadow: 0 8px 18px rgba(22, 119, 255, 0.28);
  cursor: pointer;
}

.run-btn:disabled {
  opacity: 0.7;
  box-shadow: none;
}

.spin {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  100% { transform: rotate(360deg); }
}

.result-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 10px;
}

.block {
  margin-top: 10px;
}

.block-title {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: 13px;
  font-weight: 700;
  color: #1f2a37;
  margin-bottom: 5px;
}

.block-text {
  font-size: 13px;
  line-height: 1.75;
  color: #334155;
  white-space: pre-wrap;
  word-break: break-word;
  padding: 10px;
  border-radius: 11px;
  background: #f8fafd;
}

.link-btn {
  border: none;
  background: transparent;
  color: var(--m-muted);
  font-size: 14px;
  cursor: pointer;
  padding: 2px;
}

.history-card {
  padding: 10px;
  border-radius: 12px;
  background: #f8fafd;
  border: 1px solid #eef2f7;
  cursor: pointer;
}

.history-top {
  display: flex;
  align-items: center;
  gap: 5px;
  flex-wrap: wrap;
}

.history-top .target {
  font-size: 12px;
  font-weight: 600;
  color: #334155;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 120px;
}

.history-top .time {
  margin-left: auto;
  font-size: 10px;
  color: var(--m-muted);
}

.history-text {
  margin-top: 6px;
  font-size: 11.5px;
  line-height: 1.6;
  color: #64748b;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.history-text.expanded {
  -webkit-line-clamp: unset;
  display: block;
  white-space: pre-wrap;
  color: #334155;
}

.history-suggestion {
  margin-top: 8px;
  padding-top: 7px;
  border-top: 1px dashed #e2e8f0;
}
</style>