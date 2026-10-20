<template>
  <div>
    <h2 style="margin:0 0 20px;font-size:22px;color:#333">系统设置</h2>

    <el-tabs v-model="activeTab">
      <!-- 通知渠道 -->
      <el-tab-pane label="通知渠道" name="notify">
        <el-form :model="notifyConfig" label-width="140px" style="max-width:600px">
          <el-form-item label="邮件 SMTP">
            <el-input v-model="notifyConfig.smtp" placeholder="smtp.example.com:465" />
          </el-form-item>
          <el-form-item label="钉钉 Webhook">
            <el-input v-model="notifyConfig.dingtalk" placeholder="https://oapi.dingtalk.com/robot/send?access_token=..." />
          </el-form-item>
          <el-form-item label="企业微信 Webhook">
            <el-input v-model="notifyConfig.wechat" placeholder="https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=..." />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" @click="saveGroup('notify', notifyConfig)">保存</el-button>
            <el-button @click="testConn('notify')">测试连接</el-button>
          </el-form-item>
        </el-form>
      </el-tab-pane>

      <!-- 外部数据源 -->
      <el-tab-pane label="外部数据源" name="source">
        <el-form :model="sourceConfig" label-width="140px" style="max-width:600px">
          <el-form-item label="指标源">
            <el-select v-model="sourceConfig.metricsSource" style="width:100%">
              <el-option label="本地模拟 (default)" value="default" />
              <el-option label="Prometheus" value="prometheus" />
            </el-select>
          </el-form-item>
          <el-form-item label="Prometheus URL">
            <el-input v-model="sourceConfig.prometheus" placeholder="http://localhost:9090" />
          </el-form-item>
          <el-form-item label="Grafana URL">
            <el-input v-model="sourceConfig.grafana" placeholder="http://localhost:3000" />
          </el-form-item>
          <el-form-item label="日志源">
            <el-select v-model="sourceConfig.logSource" style="width:100%">
              <el-option label="模拟数据 (default)" value="default" />
              <el-option label="Loki" value="loki" />
              <el-option label="Elasticsearch" value="elasticsearch" />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" @click="saveGroup('source', sourceConfig)">保存</el-button>
            <el-button @click="testConn('source')">测试连接</el-button>
          </el-form-item>
        </el-form>
        <el-alert title="注：外部数据源切换需重启后端应用生效。" type="warning" :closable="false" show-icon style="max-width:600px" />
      </el-tab-pane>

      <!-- AI 服务 -->
      <el-tab-pane label="AI 服务" name="ai">
        <el-form :model="aiConfig" label-width="140px" style="max-width:640px">
          <el-form-item label="AI Provider">
            <el-select v-model="aiConfig.provider" style="width:100%">
              <el-option label="本地规则引擎 (none)" value="none" />
              <el-option label="内网 HiAgent" value="hiagent" />
              <el-option label="DeepSeek" value="deepseek" />
              <el-option label="OpenAI" value="openai" />
              <el-option label="通义千问 (兼容)" value="qwen" />
            </el-select>
          </el-form-item>
          <template v-if="aiConfig.provider === 'hiagent'">
            <el-form-item label="HiAgent 地址">
              <el-input v-model="aiConfig.hiagentBaseUrl" placeholder="http://192.168.121.245:32300" />
            </el-form-item>
            <el-form-item label="App ID">
              <el-input v-model="aiConfig.hiagentAppId" placeholder="d6p2n16lvndd2j7hjvug" />
            </el-form-item>
            <el-collapse>
              <el-collapse-item title="高级：手动指定抓包会话（免握手，推荐）" name="adv">
                <el-form-item label="Cookie"><el-input v-model="aiConfig.hiagentCookie" type="textarea" :rows="2" placeholder="抓包整段 Cookie（含登录会话），配了跳过握手" /></el-form-item>
                <el-form-item label="x-csrf-token"><el-input v-model="aiConfig.hiagentCsrf" placeholder="抓包 x-csrf-token" /></el-form-item>
                <el-form-item label="app-visitor-key"><el-input v-model="aiConfig.hiagentVisitorKey" placeholder="可空，留空则自动提取" /></el-form-item>
              </el-collapse-item>
            </el-collapse>
          </template>
          <template v-else-if="aiConfig.provider !== 'none'">
            <el-form-item label="API URL">
              <el-input v-model="aiConfig.apiUrl" placeholder="https://api.deepseek.com/v1/chat/completions" />
            </el-form-item>
            <el-form-item label="API Key">
              <el-input v-model="aiConfig.apiKey" type="password" show-password placeholder="sk-..." />
            </el-form-item>
            <el-form-item label="Model">
              <el-input v-model="aiConfig.model" placeholder="deepseek-chat" />
            </el-form-item>
          </template>
          <el-form-item>
            <el-button type="primary" :loading="aiSaving" @click="saveAi">保存并生效</el-button>
            <el-button type="success" :loading="aiTesting" @click="testAi">测试连接</el-button>
            <el-button @click="loadAiStatus">刷新状态</el-button>
          </el-form-item>
          <!-- 当前状态卡片 -->
          <div class="ai-status-card" v-if="aiInfo">
            <div class="sc-row"><span class="sc-k">当前 AI 服务</span><b>{{ aiInfo.provider }}</b></div>
            <div class="sc-row"><span class="sc-k">状态</span>
              <span :class="aiInfo.reachable ? 'dot ok' : 'dot warn'"></span>
              {{ aiInfo.reachable ? '已连接' : (aiInfo.fallbackActive ? '已降级本地规则' : '未连接') }}
            </div>
            <div class="sc-row" v-if="aiInfo.csrfValid !== undefined"><span class="sc-k">CSRF</span>{{ aiInfo.csrfValid ? '有效' : '未获取' }}</div>
            <div class="sc-row"><span class="sc-k">最后刷新</span>{{ aiInfo.lastRefreshTime || aiInfo.lastCheckTime || '-' }}</div>
          </div>
          <el-alert v-if="aiSaveResult" :type="aiSaveResult.aiReachable ? 'success' : 'warning'" :closable="false" show-icon
                    :title="aiSaveResult.message" style="margin-top:10px" />
          <el-alert v-if="aiTestResult" :type="aiTestResult.success ? 'success' : 'error'" :closable="false" show-icon
                    :title="aiTestResult.success
                      ? ('连接成功，耗时 ' + aiTestResult.elapsedMs + 'ms，回复：' + ((aiTestResult.reply || '')).slice(0, 60))
                      : (aiTestResult.message || '测试失败：网络不通 / CSRF失效 / App未发布')"
                    style="margin-top:10px" />
        </el-form>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import request from '@/utils/request'
import { ElMessage } from 'element-plus'

const route = useRoute()
const activeTab = ref((route.query.tab as string) || 'notify')

const notifyConfig = reactive({ smtp: '', dingtalk: '', wechat: '' })
const sourceConfig = reactive({ metricsSource: 'default', prometheus: '', grafana: '', logSource: 'default' })
const aiConfig = reactive({ provider: 'none', apiUrl: '', apiKey: '', model: 'deepseek-chat',
  hiagentBaseUrl: '', hiagentAppId: '', hiagentVisitorKey: '', hiagentCookie: '', hiagentCsrf: '' })
const aiInfo = ref<any>(null)
const aiTestResult = ref<any>(null)
const aiSaveResult = ref<any>(null)
const aiTesting = ref(false)
const aiSaving = ref(false)

async function load() {
  const res = await request.get('/api/settings')
  const map: Record<string, string> = {}
  for (const c of res.data || []) map[c.configKey] = c.configValue
  Object.assign(notifyConfig, { smtp: map['notify.smtp'] || '', dingtalk: map['notify.dingtalk'] || '', wechat: map['notify.wechat'] || '' })
  Object.assign(sourceConfig, { metricsSource: map['metrics.source'] || 'default', prometheus: map['prometheus.url'] || '', grafana: map['grafana.url'] || '', logSource: map['log.source'] || 'default' })
  loadAiConfig()
  loadAiStatus()
  setInterval(loadAiStatus, 30000)
}

async function loadAiConfig() {
  try { const r = await request.get('/api/system/config/ai'); const d = r.data || {}
    Object.assign(aiConfig, { provider: d.provider || 'none', apiUrl: d.apiUrl || '', apiKey: d.apiKey || '',
      model: d.model || '', hiagentBaseUrl: d.hiagentBaseUrl || '', hiagentAppId: d.hiagentAppId || '',
      hiagentVisitorKey: d.hiagentVisitorKey || '', hiagentCookie: d.hiagentCookie || '', hiagentCsrf: d.hiagentCsrf || '' })
  } catch { }
}
async function loadAiStatus() { try { const r = await request.get('/api/system/config/ai/status'); aiInfo.value = r.data } catch { } }

async function saveAi() {
  aiSaving.value = true; aiSaveResult.value = null
  try {
    const r = await request.post('/api/system/config/ai', { ...aiConfig })
    aiSaveResult.value = r.data
    ElMessage[r.data.aiReachable ? 'success' : 'warning'](r.data.message)
    loadAiStatus()
  } finally { aiSaving.value = false }
}

async function testAi() {
  aiTesting.value = true; aiTestResult.value = null
  try { const r = await request.post('/api/system/config/ai/test', { message: '你好' }); aiTestResult.value = r.data; loadAiStatus() }
  finally { aiTesting.value = false }
}

async function saveGroup(group: string, cfg: Record<string, string>) {
  const keys: Record<string, string> = {
    notify: { smtp: 'notify.smtp', dingtalk: 'notify.dingtalk', wechat: 'notify.wechat' },
    source: { metricsSource: 'metrics.source', prometheus: 'prometheus.url', grafana: 'grafana.url', logSource: 'log.source' }
  }[group]!
  for (const [field, key] of Object.entries(keys)) {
    await request.put('/api/settings', { configKey: key, configValue: cfg[field] || '' })
  }
  ElMessage.success('已保存')
}

async function testConn(group: string) {
  const url = group === 'notify' ? notifyConfig.dingtalk || notifyConfig.smtp
    : group === 'source' ? sourceConfig.prometheus || sourceConfig.grafana
    : aiConfig.apiUrl
  if (!url) { ElMessage.warning('请先填写目标地址'); return }
  const res = await request.post('/api/settings/test-connection', { type: group, url })
  ElMessage[res.data.reachable ? 'success' : 'error'](res.data.message)
}

onMounted(load)
</script>

<style scoped>
.ai-status-card { max-width:600px; margin:4px 0 8px; padding:12px 16px; background:#f5f7fa; border:1px solid #e4e7ed; border-radius:8px; }
.ai-status-card .sc-row { display:flex; align-items:center; gap:8px; padding:3px 0; font-size:14px; }
.ai-status-card .sc-k { color:#909399; width:80px; }
.dot { width:9px; height:9px; border-radius:50%; display:inline-block; }
.dot.ok { background:#67c23a; box-shadow:0 0 4px #67c23a; }
.dot.warn { background:#e6a23c; }
</style>