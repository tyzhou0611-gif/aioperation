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
            <el-button v-permission="'settings:save'" type="primary" @click="saveGroup('notify', notifyConfig)">保存</el-button>
            <el-button @click="testConn('notify')">测试连接</el-button>
          </el-form-item>
        </el-form>
      </el-tab-pane>

      <!-- 外部数据源 -->
      <el-tab-pane label="外部数据源" name="source">
        <el-form :model="sourceConfig" label-width="140px" style="max-width:600px">
          <el-form-item label="指标源">
            <el-select v-model="sourceConfig.metricsSource" style="width:100%">
              <el-option label="本地模拟（无真实数据）" value="default" />
              <el-option label="Prometheus" value="prometheus" />
            </el-select>
          </el-form-item>
          <el-form-item label="Prometheus URL">
            <el-input v-model="sourceConfig.prometheus" placeholder="http://192.0.2.9:9090" />
            <div class="hint">保存后立即生效（无需重启）；页面配置优先于配置文件默认值</div>
          </el-form-item>
          <el-form-item label="Grafana URL">
            <el-input v-model="sourceConfig.grafana" placeholder="http://192.0.2.9:3000（可选）" />
          </el-form-item>
          <el-form-item label="服务器状态来源">
            <el-select v-model="sourceConfig.statusSource" style="width:100%">
              <el-option label="自动（推荐：Prometheus 优先，SSH/TCP/ICMP 兜底）" value="auto" />
              <el-option label="Prometheus（up 指标，未采集时自动兜底）" value="prometheus" />
              <el-option label="仅 SSH（登录探测）" value="ssh" />
              <el-option label="仅 ICMP（ping）" value="icmp" />
            </el-select>
            <div class="hint">
              自动 / Prometheus 模式：有 up 指标时以 Prometheus 为准；Prometheus 未采集到该主机时，自动使用 SSH（有凭据时）/ SSH 端口 TCP / ICMP 判断，新增服务器保存后会立即探测一次。<br />
              SSH 使用服务器资产中保存的密码/私钥；ICMP 需应用服务器能执行 ping。保存后立即生效。
            </div>
          </el-form-item>
          <el-form-item label="日志源">
            <el-select v-model="sourceConfig.logSource" style="width:100%">
              <el-option label="本地数据库（未接入外部日志源）" value="default" />
              <el-option label="Loki（暂未接入）" value="loki" disabled />
              <el-option label="Elasticsearch（暂未接入，请在 ELK 管理查询）" value="elasticsearch" disabled />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-button v-permission="'settings:source'" type="primary" :loading="sourceSaving" @click="saveSource">保存并生效</el-button>
            <el-button @click="testSource('prometheus')">测试 Prometheus</el-button>
            <el-button @click="testSource('grafana')">测试 Grafana</el-button>
            <el-button @click="loadSources">刷新</el-button>
          </el-form-item>
        </el-form>
        <el-alert title="指标源和 Prometheus 地址保存后即时生效；Grafana 用于兼容旧看板，可留空。" type="info" :closable="false" show-icon style="max-width:600px" />
      </el-tab-pane>

      <!-- Prometheus -->
      <el-tab-pane label="Prometheus" name="prometheus">
        <div class="prom-head">
          <div>
            <div class="prom-title">Prometheus 运行模式</div>
            <el-radio-group v-model="promMode">
              <el-radio-button label="embedded">内置 Prometheus（推荐）</el-radio-button>
              <el-radio-button label="external">外部 Prometheus</el-radio-button>
            </el-radio-group>
            <span class="hint" style="margin-left:10px">内置模式随平台启动，配置在页面维护；外部模式只做查询和 targets 展示。</span>
          </div>
          <div class="prom-actions">
            <el-button size="small" @click="loadPrometheus" :loading="promLoading">刷新</el-button>
            <el-button size="small" type="primary" plain :loading="promBusy === 'mode'" @click="applyPromMode">应用模式</el-button>
            <el-button v-if="promStatus?.healthy" size="small" type="success" plain @click="openPromUi">打开 Prometheus UI</el-button>
          </div>
        </div>

        <el-form v-if="promMode === 'external'" :model="{}" label-width="120px" style="max-width:720px;margin-top:14px">
          <el-form-item label="外部地址">
            <el-input v-model="promExternalUrl" placeholder="http://192.0.2.6:9090" />
            <div class="hint">保存后平台指标源会切到该地址；可用「外部数据源」页的测试按钮验证连通性。</div>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="promBusy === 'mode'" @click="applyPromMode">保存并使用外部 Prometheus</el-button>
          </el-form-item>
        </el-form>

        <template v-else>
          <div class="prom-status-card">
            <div class="ps-row">
              <span class="ps-k">状态</span>
              <el-tag :type="promStatus?.healthy ? 'success' : promStatus?.running ? 'warning' : 'danger'" size="small">
                {{ promStatus?.healthy ? '运行中 / 健康' : promStatus?.running ? '进程运行但未就绪' : '未运行' }}
              </el-tag>
              <span class="ps-k">版本</span><code>{{ promStatus?.version || '-' }}</code>
              <span class="ps-k">端口</span><code>{{ promStatus?.listenAddress }}:{{ promStatus?.port }}</code>
              <span class="ps-k">PID</span><code>{{ promStatus?.pid || '-' }}</code>
            </div>
            <div class="ps-row">
              <span class="ps-k">地址</span><code>{{ promStatus?.url || '-' }}</code>
              <span class="ps-k">配置</span><code class="ps-path">{{ promStatus?.configFile || '-' }}</code>
            </div>
            <div class="ps-row">
              <span class="ps-k">数据目录</span><code class="ps-path">{{ promStatus?.dataDir || '-' }}</code>
              <span class="ps-k">二进制</span><code class="ps-path">{{ promStatus?.binaryFound ? promStatus?.binaryPath : (promStatus?.archiveFound ? '未解压（启动时自动解压）' : '未找到内置二进制') }}</code>
            </div>
            <div v-if="promStatus?.archiveFound && !promStatus?.binaryFound" class="ps-row">
              <span class="ps-k">安装包</span><code class="ps-path">{{ promStatus?.archivePath }}</code>
            </div>
            <div v-if="promStatus?.reused" class="ps-row">
              <span class="hint">端口上已有 Prometheus 实例（不是由本平台进程启动），已直接复用；停止/重启请手动处理该进程。</span>
            </div>
            <div v-if="promStatus?.message" class="ps-error">{{ promStatus.message }}</div>
            <div class="prom-actions" style="margin-top:8px">
              <el-button size="small" :loading="promBusy === 'start'" :disabled="!promStatus?.canStart" @click="promAction('start')">启动</el-button>
              <el-button size="small" :loading="promBusy === 'stop'" :disabled="!promStatus?.running" @click="promAction('stop')">停止</el-button>
              <el-button size="small" :loading="promBusy === 'restart'" @click="promAction('restart')">重启</el-button>
              <el-button size="small" :loading="promBusy === 'reload'" :disabled="!promStatus?.healthy" @click="promAction('reload')">热加载配置</el-button>
              <span class="hint">内置 Prometheus 会自动抓取平台安装的 Exporter（HTTP SD），安装插件后无需手改 targets。</span>
            </div>
          </div>

          <el-row :gutter="14" style="margin-top:14px">
            <el-col :span="12">
              <el-card shadow="never" class="prom-card">
                <template #header>
                  <div class="prom-card-head"><span>抓取目标（Targets）</span>
                    <el-button size="small" :loading="promTargetsLoading" @click="loadPromTargets">刷新</el-button>
                  </div>
                </template>
                <el-table :data="promTargets" size="small" stripe style="width:100%" max-height="300">
                  <el-table-column prop="job" label="Job" width="150" show-overflow-tooltip />
                  <el-table-column prop="instance" label="实例" min-width="160" show-overflow-tooltip />
                  <el-table-column label="健康" width="90">
                    <template #default="{ row }">
                      <el-tag size="small" :type="row.health === 'up' ? 'success' : 'danger'">{{ row.health || '-' }}</el-tag>
                    </template>
                  </el-table-column>
                  <el-table-column prop="lastError" label="错误" min-width="160" show-overflow-tooltip />
                </el-table>
                <el-empty v-if="!promTargets.length && !promTargetsLoading" description="暂无 targets（先安装 Exporter 或添加抓取任务）" :image-size="50" />
              </el-card>
            </el-col>
            <el-col :span="12">
              <el-card shadow="never" class="prom-card">
                <template #header>
                  <div class="prom-card-head"><span>抓取任务（静态 targets）</span>
                    <el-button size="small" type="primary" plain @click="openPromJob()">+ 新增任务</el-button>
                  </div>
                </template>
                <el-table :data="promJobs" size="small" stripe style="width:100%" max-height="300">
                  <el-table-column prop="jobName" label="Job" min-width="140" show-overflow-tooltip />
                  <el-table-column label="目标" min-width="180" show-overflow-tooltip>
                    <template #default="{ row }">{{ (row.targets || []).join(', ') || 'HTTP SD 自动发现' }}</template>
                  </el-table-column>
                  <el-table-column prop="scrapeInterval" label="间隔" width="80" />
                  <el-table-column label="操作" width="120">
                    <template #default="{ row }">
                      <template v-if="!row.managed">
                        <el-button link type="primary" size="small" @click="openPromJob(row)">编辑</el-button>
                        <el-button link type="danger" size="small" @click="delPromJob(row)">删除</el-button>
                      </template>
                      <el-tag v-else size="small" type="info">平台自动</el-tag>
                    </template>
                  </el-table-column>
                </el-table>
              </el-card>
            </el-col>
          </el-row>

          <el-card shadow="never" class="prom-card" style="margin-top:14px">
            <template #header>
              <div class="prom-card-head"><span>prometheus.yml（高级配置）</span>
                <div>
                  <el-button size="small" :loading="promBusy === 'config'" @click="loadPromConfig">加载</el-button>
                  <el-button size="small" type="primary" :loading="promBusy === 'save'" @click="savePromConfig">保存并热加载</el-button>
                  <el-button size="small" :loading="promBusy === 'reset'" @click="resetPromConfig">恢复平台默认</el-button>
                </div>
              </div>
            </template>
            <el-input v-model="promConfig" type="textarea" :rows="18" class="prom-yaml" spellcheck="false" />
            <div class="hint">保存前会用包内 promtool 校验；校验失败不会覆盖当前配置。任务表单与 YAML 修改都会立即热加载。</div>
          </el-card>
        </template>

        <el-dialog v-model="promJobVisible" :title="promJobEditing ? '编辑抓取任务' : '新增抓取任务'" width="620px" append-to-body>
          <el-form :model="promJobForm" label-width="100px">
            <el-form-item label="任务名" required>
              <el-input v-model="promJobForm.jobName" :disabled="promJobEditing" placeholder="如 node-static / mysql-prod" />
            </el-form-item>
            <el-form-item label="目标" required>
              <el-input v-model="promJobForm.targets" type="textarea" :rows="4" placeholder="每行一个或逗号分隔，如&#10;192.0.2.2:9100&#10;192.0.2.3:9100" />
            </el-form-item>
            <el-form-item label="metrics 路径">
              <el-input v-model="promJobForm.metricsPath" placeholder="/metrics" />
            </el-form-item>
            <el-form-item label="抓取间隔">
              <el-input v-model="promJobForm.scrapeInterval" placeholder="15s（可选）" />
            </el-form-item>
            <el-form-item label="标签">
              <el-input v-model="promJobForm.labels" type="textarea" :rows="2" placeholder="env=prod,team=db（可选）" />
            </el-form-item>
          </el-form>
          <template #footer>
            <el-button @click="promJobVisible = false">取消</el-button>
            <el-button type="primary" :loading="promBusy === 'job'" @click="savePromJob">保存并热加载</el-button>
          </template>
        </el-dialog>
      </el-tab-pane>

      <!-- AI 服务 -->
      <el-tab-pane label="AI 服务" name="ai">
        <el-form :model="aiConfig" label-width="140px" style="max-width:640px">
          <el-form-item label="AI Provider">
            <el-select v-model="aiConfig.provider" style="width:100%">
              <el-option label="Anthropic 兼容（Claude 消息协议）" value="anthropic" />
              <el-option label="本地规则引擎 (none)" value="none" />
              <el-option label="内网 HiAgent" value="hiagent" />
              <el-option label="DeepSeek" value="deepseek" />
              <el-option label="OpenAI" value="openai" />
              <el-option label="通义千问 (兼容)" value="qwen" />
            </el-select>
            <div v-if="aiConfig.provider !== 'none'" class="hint" style="margin-top:6px">
              切换 Provider 或修改连接参数后，必须先点「测试连接」；测试通过后才能保存并生效。
            </div>
          </el-form-item>
          <template v-if="aiConfig.provider === 'hiagent'">
            <el-form-item label="调用方式">
              <el-radio-group v-model="aiConfig.hiagentMode">
                <el-radio-button label="openapi">OpenAPI（推荐）</el-radio-button>
                <el-radio-button label="bypass">抓包 Cookie</el-radio-button>
              </el-radio-group>
              <div class="hint">OpenAPI 使用 Apikey 调用 /chat_query；抓包模式需要浏览器登录后的 Cookie/CSRF</div>
            </el-form-item>
            <template v-if="aiConfig.hiagentMode !== 'bypass'">
              <el-form-item label="OpenAPI 地址">
                <el-input v-model="aiConfig.hiagentOpenapiUrl" placeholder="http://192.0.2.10:32300/api/proxy/api/v1" />
                <div class="hint">只填到 /api/proxy/api/v1 即可，后端会自动追加 /chat_query</div>
              </el-form-item>
              <el-form-item label="App ID">
                <el-input v-model="aiConfig.hiagentAppId" placeholder="" />
              </el-form-item>
              <el-form-item label="API Key">
                <el-input v-model="aiConfig.hiagentOpenapiKey" type="password" show-password
                          :placeholder="aiSecretFlags.hiagentOpenapiKey ? '已加密存储，留空保持不变' : '留空使用内置 Key'" />
                <div class="hint">OpenAPI Key 与 App ID 必须属于同一个 HiAgent 应用；若提示 API service is disabled，请在 HiAgent 应用里开启“API 服务”</div>
              </el-form-item>
              <el-form-item label="用户标识">
                <el-input v-model="aiConfig.hiagentUserId" placeholder="aiops" />
                <div class="hint">对应请求头 X-APP-User，留空默认 aiops</div>
              </el-form-item>
            </template>
            <template v-else>
              <el-form-item label="HiAgent 地址">
                <el-input v-model="aiConfig.hiagentBaseUrl" placeholder="http://192.0.2.10:32300" />
              </el-form-item>
              <el-form-item label="App ID">
                <el-input v-model="aiConfig.hiagentAppId" placeholder="" />
              </el-form-item>
              <el-collapse>
                <el-collapse-item title="高级：手动指定抓包会话（免握手，推荐）" name="adv">
                  <el-form-item label="Cookie"><el-input v-model="aiConfig.hiagentCookie" type="textarea" :rows="2" :placeholder="aiSecretFlags.hiagentCookie ? '已加密存储，留空保持不变' : '抓包整段 Cookie（含登录会话），配了跳过握手'" /></el-form-item>
                  <el-form-item label="x-csrf-token"><el-input v-model="aiConfig.hiagentCsrf" :placeholder="aiSecretFlags.hiagentCsrf ? '已加密存储，留空保持不变' : '抓包 x-csrf-token'" /></el-form-item>
                  <el-form-item label="app-visitor-key"><el-input v-model="aiConfig.hiagentVisitorKey" :placeholder="aiSecretFlags.hiagentVisitorKey ? '已加密存储，留空保持不变' : '可空，留空则自动提取'" /></el-form-item>
                </el-collapse-item>
              </el-collapse>
            </template>
          </template>
          <template v-else-if="aiConfig.provider !== 'none'">
            <el-form-item label="API URL">
              <el-input v-model="aiConfig.apiUrl" placeholder="https://api.deepseek.com/v1/chat/completions" />
            </el-form-item>
            <el-form-item label="API Key">
              <el-input v-model="aiConfig.apiKey" type="password" show-password
                        :placeholder="aiSecretFlags.apiKey ? '已加密存储，留空保持不变' : 'sk-...'" />
              <div v-if="aiSecretFlags.apiKey && aiConfig.provider === savedProvider" class="hint">已加密存储；如需更换请输入新 Key，留空则保持不变</div>
              <div v-else-if="aiConfig.provider !== savedProvider" class="hint">已切换 Provider：必须填写新服务的 API Key，系统不会复用上一个服务的密钥</div>
            </el-form-item>
            <el-form-item label="Model">
              <el-input v-model="aiConfig.model" :placeholder="aiConfig.provider === 'anthropic' ? 'deepseek-v4.1-flash-1017' : 'deepseek-chat'" />
              <div v-if="aiConfig.provider === 'anthropic'" class="hint">
                可用：deepseek-v4.1-flash-1017（Sonnet）/ qwen3.8-flash（Opus）/ glm-5.3（Haiku）
              </div>
            </el-form-item>
          </template>
          <el-form-item>
            <el-button v-permission="'settings:ai'" type="primary" :loading="aiSaving" :disabled="!aiTestPassed" @click="saveAi">保存并生效</el-button>
            <el-button type="success" :loading="aiTesting" :disabled="aiConfig.provider === 'none'" @click="testAi">测试连接</el-button>
            <el-button @click="loadAiStatus">刷新状态</el-button>
            <el-tag :type="aiTestState.type" size="small" effect="plain" style="margin-left:8px">{{ aiTestState.label }}</el-tag>
          </el-form-item>
          <!-- 当前状态卡片 -->
          <div class="ai-status-card" v-if="aiInfo">
            <div class="sc-row"><span class="sc-k">当前 AI 服务</span><b>{{ aiInfo.provider }}</b><el-tag v-if="aiInfo.mode" size="small" effect="plain">{{ aiInfo.mode }}</el-tag></div>
            <div class="sc-row" v-if="aiInfo.endpoint"><span class="sc-k">端点</span><code>{{ aiInfo.endpoint }}</code></div>
            <div class="sc-row" v-if="aiInfo.appId"><span class="sc-k">App ID</span><code>{{ aiInfo.appId }}</code></div>
            <div class="sc-row"><span class="sc-k">状态</span>
              <span :class="aiInfo.reachable ? 'dot ok' : 'dot warn'"></span>
              {{ aiInfo.reachable
                ? (aiInfo.provider === 'hiagent' && aiInfo.mode === 'openapi' ? '已配置（点测试连接验证）' : '已连接')
                : (aiInfo.fallbackActive ? '已降级本地规则' : '未连接') }}
            </div>
            <div class="sc-row" v-if="aiInfo.mode && aiInfo.mode !== 'openapi' && aiInfo.csrfValid !== undefined"><span class="sc-k">CSRF</span>{{ aiInfo.csrfValid ? '有效' : '未获取' }}</div>
            <div class="sc-row" v-if="aiInfo.lastError"><span class="sc-k">最近错误</span><span style="color:#f56c6c">{{ aiInfo.lastError }}</span></div>
            <div class="sc-row"><span class="sc-k">最后刷新</span>{{ aiInfo.lastRefreshTime || aiInfo.lastCheckTime || '-' }}</div>
          </div>
          <el-alert v-if="aiSaveResult" :type="aiSaveResult.aiReachable ? 'success' : 'warning'" :closable="false" show-icon
                    :title="aiSaveResult.message" style="margin-top:10px" />
          <el-alert v-if="aiTestResult" :type="aiTestResult.success ? 'success' : 'error'" :closable="false" show-icon
                    :title="aiTestResult.success
                      ? ('连接成功，耗时 ' + aiTestResult.elapsedMs + 'ms，回复：' + ((aiTestResult.reply || '')).slice(0, 60))
                      : (aiTestResult.message || '测试失败：网络不通 / CSRF失效 / App未发布')"
                    style="margin-top:10px" />
          <div v-if="aiTestResult && aiTestResult.target" class="test-target">
            实际测试目标：<code>{{ aiTestResult.target }}</code>
          </div>
        </el-form>
      </el-tab-pane>

      <!-- 指标目录（Metric Catalog） -->
      <el-tab-pane label="指标目录" name="catalog">
        <MetricCatalog />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import request from '@/utils/request'
import MetricCatalog from '@/components/MetricCatalog.vue'
import { ElMessage, ElMessageBox } from 'element-plus'

const route = useRoute()
const activeTab = ref((route.query.tab as string) || 'notify')
// AI 助手跳转到 settings?tab=ai/source 时，页面已打开也能切换
watch(() => route.query.tab, (t) => {
  if (t) activeTab.value = String(t)
})

const notifyConfig = reactive({ smtp: '', dingtalk: '', wechat: '' })
const sourceConfig = reactive({ metricsSource: 'prometheus', prometheus: '', grafana: '', logSource: 'default', statusSource: 'prometheus' })
const sourceSaving = ref(false)

/* ===== 内置 / 外部 Prometheus ===== */
const promLoading = ref(false)
const promBusy = ref('')
const promStatus = ref<any>(null)
const promMode = ref<'embedded' | 'external'>('embedded')
const promExternalUrl = ref('')
const promConfig = ref('')
const promJobs = ref<any[]>([])
const promTargets = ref<any[]>([])
const promTargetsLoading = ref(false)
const promJobVisible = ref(false)
const promJobEditing = ref(false)
const promJobForm = reactive({ jobName: '', targets: '', metricsPath: '/metrics', scrapeInterval: '15s', labels: '' })

async function loadPrometheus() {
  promLoading.value = true
  try {
    const [st, cfg, jobs] = await Promise.all([
      request.get('/api/prometheus/status'),
      request.get('/api/prometheus/config').catch(() => null),
      request.get('/api/prometheus/jobs').catch(() => null)
    ])
    promStatus.value = st.data || null
    const mode = String(promStatus.value?.mode || 'embedded')
    promMode.value = mode === 'external' ? 'external' : 'embedded'
    promExternalUrl.value = promStatus.value?.externalUrl || ''
    promConfig.value = cfg?.data?.content || ''
    promJobs.value = jobs?.data || []
    loadPromTargets()
  } finally { promLoading.value = false }
}

async function loadPromConfig() {
  promBusy.value = 'config'
  try {
    const r = await request.get('/api/prometheus/config')
    promConfig.value = r.data?.content || ''
    ElMessage.success('已加载当前 prometheus.yml')
  } finally { promBusy.value = '' }
}

async function loadPromTargets() {
  promTargetsLoading.value = true
  try {
    const r = await request.get('/api/prometheus/targets')
    promTargets.value = r.data?.targets || []
  } catch {
    promTargets.value = []
  } finally { promTargetsLoading.value = false }
}

async function applyPromMode() {
  if (promMode.value === 'external' && !promExternalUrl.value.trim()) {
    ElMessage.warning('请填写外部 Prometheus 地址'); return
  }
  promBusy.value = 'mode'
  try {
    const r = await request.post('/api/prometheus/mode', {
      mode: promMode.value,
      externalUrl: promMode.value === 'external' ? promExternalUrl.value.trim() : ''
    }, { timeout: 60000 })
    promStatus.value = r.data || null
    ElMessage.success(r.data?.message || 'Prometheus 模式已切换')
    await loadPrometheus()
  } finally { promBusy.value = '' }
}

async function promAction(action: 'start' | 'stop' | 'restart' | 'reload') {
  promBusy.value = action
  try {
    const r = await request.post(`/api/prometheus/${action}`, {}, { timeout: 60000 })
    promStatus.value = r.data || promStatus.value
    ElMessage.success((r as any).msg || '操作完成')
    await loadPrometheus()
  } finally { promBusy.value = '' }
}

function openPromUi() {
  const url = promStatus.value?.publicUrl
    || `http://${window.location.hostname}:${promStatus.value?.port || 9090}`
  window.open(url, '_blank')
}

async function savePromConfig() {
  if (!promConfig.value.trim()) { ElMessage.warning('配置内容不能为空'); return }
  promBusy.value = 'save'
  try {
    await request.put('/api/prometheus/config', { content: promConfig.value }, { timeout: 60000 })
    ElMessage.success('配置已保存并热加载')
    await loadPrometheus()
  } finally { promBusy.value = '' }
}

async function resetPromConfig() {
  try { await ElMessageBox.confirm('确认恢复平台默认 Prometheus 配置？当前自定义任务会丢失。', '提示', { type: 'warning' }) } catch { return }
  promBusy.value = 'reset'
  try {
    await request.post('/api/prometheus/config/reset', {}, { timeout: 60000 })
    ElMessage.success('已恢复默认配置并热加载')
    await loadPrometheus()
  } finally { promBusy.value = '' }
}

function openPromJob(row?: any) {
  promJobEditing.value = !!row
  promJobForm.jobName = row?.jobName || ''
  promJobForm.targets = (row?.targets || []).join('\n')
  promJobForm.metricsPath = row?.metricsPath || '/metrics'
  promJobForm.scrapeInterval = row?.scrapeInterval || '15s'
  promJobForm.labels = Object.entries(row?.labels || {}).map(([k, v]) => `${k}=${v}`).join(',')
  promJobVisible.value = true
}

async function savePromJob() {
  if (!promJobForm.jobName.trim()) { ElMessage.warning('请填写任务名'); return }
  if (!promJobForm.targets.trim()) { ElMessage.warning('请填写至少一个目标'); return }
  promBusy.value = 'job'
  try {
    if (promJobEditing.value) {
      await request.put(`/api/prometheus/jobs/${encodeURIComponent(promJobForm.jobName)}`, { ...promJobForm }, { timeout: 60000 })
      ElMessage.success('任务已更新并热加载')
    } else {
      await request.post('/api/prometheus/jobs', { ...promJobForm }, { timeout: 60000 })
      ElMessage.success('任务已新增并热加载')
    }
    promJobVisible.value = false
    await loadPrometheus()
  } finally { promBusy.value = '' }
}

async function delPromJob(row: any) {
  try { await ElMessageBox.confirm(`确认删除抓取任务「${row.jobName}」？`, '提示', { type: 'warning' }) } catch { return }
  await request.delete(`/api/prometheus/jobs/${encodeURIComponent(row.jobName)}`, { timeout: 60000 })
  ElMessage.success('任务已删除并热加载')
  await loadPrometheus()
}
const aiConfig = reactive({ provider: 'none', apiUrl: '', apiKey: '', model: 'deepseek-chat',
  hiagentMode: 'openapi', hiagentBaseUrl: '', hiagentAppId: '', hiagentOpenapiUrl: '', hiagentOpenapiKey: '',
  hiagentUserId: 'aiops', hiagentVisitorKey: '', hiagentCookie: '', hiagentCsrf: '' })
const aiSecretFlags = reactive({ apiKey: false, hiagentOpenapiKey: false,
  hiagentVisitorKey: false, hiagentCookie: false, hiagentCsrf: false })
/** 已保存（当前生效）的 Provider；切换 Provider 后不再复用它的 URL/Key */
const savedProvider = ref('none')
/** 各 Provider 的表单草稿（内存级），切换回来时恢复 */
const providerDrafts: Record<string, Record<string, string>> = {}
/** 切换 Provider 时用于屏蔽主 watch */
let switchingProvider = false

const PROVIDER_DEFAULTS: Record<string, Record<string, string>> = {
  anthropic: { apiUrl: '', model: 'deepseek-v4.1-flash-1017' },
  deepseek: { apiUrl: 'https://api.deepseek.com/v1/chat/completions', model: 'deepseek-chat' },
  openai: { apiUrl: 'https://api.openai.com/v1/chat/completions', model: 'gpt-4o-mini' },
  qwen: { apiUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions', model: 'qwen-plus' },
  hiagent: {
    hiagentMode: 'openapi',
    hiagentBaseUrl: '',
    hiagentOpenapiUrl: '',
    hiagentAppId: '',
    hiagentUserId: 'aiops'
  },
  none: {}
}
const CONN_FIELDS = ['apiUrl', 'apiKey', 'model', 'hiagentMode', 'hiagentBaseUrl', 'hiagentAppId',
  'hiagentOpenapiUrl', 'hiagentOpenapiKey', 'hiagentUserId', 'hiagentVisitorKey', 'hiagentCookie', 'hiagentCsrf']

function snapshotProvider(provider: string) {
  const d: Record<string, string> = {}
  for (const f of CONN_FIELDS) d[f] = (aiConfig as any)[f] || ''
  providerDrafts[provider] = d
}

/** 切换 Provider：恢复该 Provider 自己的草稿；没有草稿则用该 Provider 默认值，绝不沿用旧 Provider 的 URL/Key */
watch(() => aiConfig.provider, (next, prev) => {
  if (switchingProvider || !prev || next === prev) return
  snapshotProvider(prev)
  const draft = providerDrafts[next]
  if (draft) {
    Object.assign(aiConfig, draft)
  } else {
    for (const f of CONN_FIELDS) (aiConfig as any)[f] = ''
    aiConfig.hiagentMode = 'openapi'
    aiConfig.hiagentUserId = 'aiops'
    Object.assign(aiConfig, PROVIDER_DEFAULTS[next] || {})
    // 不同 Provider 的密钥不互相沿用
    aiConfig.apiKey = ''
    aiConfig.hiagentOpenapiKey = ''
  }
  aiSaveResult.value = null
}, { flush: 'sync' })
const aiInfo = ref<any>(null)
const aiTestResult = ref<any>(null)
const aiSaveResult = ref<any>(null)
const aiTesting = ref(false)
const aiSaving = ref(false)

/** 当前表单指纹：测试通过后记录，任一连接参数变化都会让“已测试”失效 */
const aiFingerprint = computed(() => JSON.stringify({
  provider: aiConfig.provider,
  apiUrl: aiConfig.apiUrl,
  apiKey: aiConfig.apiKey,
  model: aiConfig.model,
  hiagentMode: aiConfig.hiagentMode,
  hiagentBaseUrl: aiConfig.hiagentBaseUrl,
  hiagentAppId: aiConfig.hiagentAppId,
  hiagentOpenapiUrl: aiConfig.hiagentOpenapiUrl,
  hiagentOpenapiKey: aiConfig.hiagentOpenapiKey,
  hiagentUserId: aiConfig.hiagentUserId,
  hiagentVisitorKey: aiConfig.hiagentVisitorKey,
  hiagentCookie: aiConfig.hiagentCookie,
  hiagentCsrf: aiConfig.hiagentCsrf
}))
const aiTestedFingerprint = ref('')
const aiTestPassed = computed(() => aiConfig.provider === 'none'
  || (!!aiTestedFingerprint.value && aiTestedFingerprint.value === aiFingerprint.value))
const aiTestState = computed<{ label: string; type: 'success' | 'danger' | 'warning' | 'info' }>(() => {
  if (aiConfig.provider === 'none') return { label: '本地规则，无需测试', type: 'info' }
  if (aiTestPassed.value) return { label: '测试通过，可以保存', type: 'success' }
  if (aiTestResult.value && aiTestResult.value.success === false) return { label: '测试失败', type: 'danger' }
  return { label: '待测试', type: 'warning' }
})
// 表单任何连接参数变化 → 让上一次测试结果失效，必须重新测试
watch(aiFingerprint, () => {
  aiTestedFingerprint.value = ''
  aiTestResult.value = null
  aiSaveResult.value = null
}, { flush: 'sync' })

async function load() {
  const res = await request.get('/api/settings')
  const map: Record<string, string> = {}
  for (const c of res.data || []) map[c.configKey] = c.configValue
  Object.assign(notifyConfig, { smtp: map['notify.smtp'] || '', dingtalk: map['notify.dingtalk'] || '', wechat: map['notify.wechat'] || '' })
  loadSources()
  loadAiConfig()
  loadAiStatus()
  loadPrometheus()
  setInterval(loadAiStatus, 30000)
}

async function loadSources() {
  try {
    const r = await request.get('/api/system/config/metrics')
    const d = r.data || {}
    Object.assign(sourceConfig, {
      metricsSource: d.source || 'prometheus',
      prometheus: d.prometheusUrl || '',
      grafana: d.grafanaUrl || '',
      logSource: d.logSource || 'default',
      statusSource: d.serverStatusSource || 'auto'
    })
  } catch { }
}

async function saveSource() {
  sourceSaving.value = true
  try {
    const r = await request.post('/api/system/config/metrics', {
      source: sourceConfig.metricsSource,
      prometheusUrl: sourceConfig.prometheus,
      grafanaUrl: sourceConfig.grafana,
      logSource: sourceConfig.logSource,
      serverStatusSource: sourceConfig.statusSource
    })
    ElMessage.success(r.data?.message || '数据源配置已保存并即时生效')
    loadSources()
  } finally {
    sourceSaving.value = false
  }
}

async function testSource(type: 'prometheus' | 'grafana') {
  const url = type === 'prometheus' ? sourceConfig.prometheus : sourceConfig.grafana
  if (!url) { ElMessage.warning('请先填写 ' + (type === 'prometheus' ? 'Prometheus' : 'Grafana') + ' 地址'); return }
  const res = await request.post('/api/system/config/metrics/test', { type, url })
  const d = res.data || {}
  ElMessage[d.reachable ? 'success' : 'error'](
    (d.message || (d.reachable ? '连接成功' : '连接失败')) + (d.elapsedMs != null ? `（${d.elapsedMs}ms）` : ''))
}

async function loadAiConfig() {
  try {
    const r = await request.get('/api/system/config/ai')
    const d = r.data || {}
    switchingProvider = true
    Object.assign(aiConfig, {
      provider: d.provider || 'none',
      apiUrl: d.apiUrl || '',
      apiKey: '',
      model: d.model || '',
      hiagentMode: d.hiagentMode || 'openapi',
      hiagentBaseUrl: d.hiagentBaseUrl || '',
      hiagentAppId: d.hiagentAppId || '',
      hiagentOpenapiUrl: d.hiagentOpenapiUrl || '',
      hiagentOpenapiKey: '',
      hiagentUserId: d.hiagentUserId || 'aiops',
      hiagentVisitorKey: '', hiagentCookie: '', hiagentCsrf: ''
    })
    switchingProvider = false
    savedProvider.value = d.provider || 'none'
    snapshotProvider(savedProvider.value)
    Object.assign(aiSecretFlags, {
      apiKey: !!d.apiKeySet,
      hiagentOpenapiKey: !!d.hiagentOpenapiKeySet,
      hiagentVisitorKey: !!d.hiagentVisitorKeySet,
      hiagentCookie: !!d.hiagentCookieSet,
      hiagentCsrf: !!d.hiagentCsrfSet
    })
    aiTestedFingerprint.value = ''
    aiTestResult.value = null
    aiSaveResult.value = null
  } catch { switchingProvider = false }
}
async function loadAiStatus() { try { const r = await request.get('/api/system/config/ai/status'); aiInfo.value = r.data } catch { } }

async function saveAi() {
  if (!aiTestPassed.value) {
    ElMessage.warning('请先测试连接，测试通过后才能保存')
    return
  }
  aiSaving.value = true; aiSaveResult.value = null
  try {
    const r = await request.post('/api/system/config/ai', { ...aiConfig }, { timeout: 120000 })
    aiSaveResult.value = r.data
    ElMessage.success(r.data?.message || '配置已保存并生效')
    aiConfig.apiKey = ''; aiConfig.hiagentOpenapiKey = ''
    aiConfig.hiagentVisitorKey = ''; aiConfig.hiagentCookie = ''; aiConfig.hiagentCsrf = ''
    aiTestedFingerprint.value = ''
    aiTestResult.value = null
    await loadAiConfig()
    loadAiStatus()
    // 刚保存的配置已在后端真实测试通过，视为“已测试”，避免保存后又显示待测试
    aiTestedFingerprint.value = aiFingerprint.value
  } catch {
    aiTestedFingerprint.value = ''
  } finally { aiSaving.value = false }
}

async function testAi() {
  if (aiConfig.provider === 'none') { ElMessage.info('本地规则引擎无需测试'); return }
  aiTesting.value = true; aiTestResult.value = null
  const tested = aiFingerprint.value
  try {
    // 测试的是当前表单（尚未保存）的配置；同一 Provider 留空密钥时才沿用已保存值
    const r = await request.post('/api/system/config/ai/test', { ...aiConfig, message: '你好' }, { timeout: 120000 })
    aiTestResult.value = r.data
    if (r.data?.success) {
      aiTestedFingerprint.value = tested
      ElMessage.success('连接测试通过，可以保存')
    } else {
      aiTestedFingerprint.value = ''
      ElMessage.error(r.data?.message || '测试失败')
    }
  } catch {
    aiTestedFingerprint.value = ''
  } finally { aiTesting.value = false }
}

async function saveGroup(group: string, cfg: Record<string, any>) {
  const keys: Record<string, string> = {
    notify: { smtp: 'notify.smtp', dingtalk: 'notify.dingtalk', wechat: 'notify.wechat' }
  }[group] || {}
  for (const [field, key] of Object.entries(keys)) {
    await request.put('/api/settings', { configKey: key, configValue: cfg[field] || '' })
  }
  ElMessage.success('已保存')
}

async function testConn(group: string) {
  const url = group === 'notify' ? notifyConfig.dingtalk || notifyConfig.smtp : aiConfig.apiUrl
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
.hint { font-size:12px; color:#909399; margin-top:4px; line-height:1.5; }
.test-target { max-width:640px; margin-top:8px; padding:8px 12px; background:#f0f9eb; border:1px solid #e1f3d8; border-radius:6px; font-size:12px; color:#529b2e; word-break:break-all; }
.test-target code { color:#3a6b1e; font-family:Consolas,Monaco,monospace; }
.prom-head { display:flex; align-items:flex-start; justify-content:space-between; gap:16px; flex-wrap:wrap; margin-bottom:12px; }
.prom-title { font-size:15px; font-weight:600; color:#303133; margin-bottom:8px; }
.prom-actions { display:flex; align-items:center; gap:8px; flex-wrap:wrap; }
.prom-status-card { max-width:1100px; padding:12px 16px; background:#f5f7fa; border:1px solid #e4e7ed; border-radius:8px; }
.prom-status-card .ps-row { display:flex; align-items:center; gap:8px; flex-wrap:wrap; padding:4px 0; font-size:13px; }
.prom-status-card .ps-k { color:#909399; min-width:56px; }
.prom-status-card code { font-family:Consolas,Monaco,monospace; font-size:12px; color:#409eff; word-break:break-all; }
.prom-status-card .ps-path { color:#606266; }
.prom-status-card .ps-error { margin-top:6px; padding:6px 10px; background:#fef0f0; border:1px solid #fde2e2; border-radius:4px; color:#f56c6c; font-size:12px; }
.prom-card { margin-bottom:14px; }
.prom-card-head { display:flex; align-items:center; justify-content:space-between; gap:10px; }
.prom-yaml :deep(textarea) { font-family:Consolas,Monaco,monospace; font-size:12.5px; line-height:1.6; background:#0f172a; color:#dbeafe; }
</style>