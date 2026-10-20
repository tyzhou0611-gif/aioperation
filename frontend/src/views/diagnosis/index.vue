<template>
  <div>
    <h2 style="margin:0 0 20px;font-size:22px;color:#333">智能诊断</h2>

    <el-card>
      <el-alert
        title="诊断引擎：本地规则引擎（未配置大模型 API Key）。配置 app.ai.api-key 后可切换 LLM 智能诊断。"
        type="info" show-icon :closable="false" style="margin-bottom:16px"
      />

      <!-- 诊断表单 -->
      <el-form :inline="true" style="margin-bottom:16px">
        <el-form-item label="服务器">
          <el-select v-model="form.serverId" placeholder="选择服务器" style="width:220px">
            <el-option v-for="s in servers" :key="s.id" :label="s.hostname + ' (' + s.ip + ')'" :value="s.id" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="diagnosing" @click="runDiagnosis">开始诊断</el-button>
        </el-form-item>
      </el-form>

      <!-- 诊断结果 -->
      <el-card v-if="result" shadow="never" style="background:#f5f7fa">
        <template #header><span style="font-weight:600">诊断结果</span></template>
        <el-result :icon="result.confidence >= 0.7 ? 'warning' : 'info'" :title="'置信度 ' + (result.confidence * 100).toFixed(0) + '%'">
          <template #sub-title>
            <div style="text-align:left;line-height:1.8">
              <p style="margin:0"><strong>🔍 根因分析：</strong>{{ result.diagnosis }}</p>
              <p style="margin:8px 0 0"><strong>💡 修复建议：</strong></p>
              <el-descriptions :column="1" border style="margin-top:8px">
                <el-descriptions-item label="建议">{{ result.suggestion }}</el-descriptions-item>
              </el-descriptions>
            </div>
          </template>
        </el-result>
      </el-card>

      <el-divider />

      <!-- 诊断历史 -->
      <h4 style="margin:0 0 12px">诊断历史</h4>
      <el-table :data="history" stripe style="width:100%">
        <el-table-column prop="serverIp" label="服务器IP" width="130" />
        <el-table-column prop="diagnosisType" label="类型" width="80">
          <template #default="{ row }"><el-tag size="small">{{ row.diagnosisType === 'RULE' ? '规则' : 'LLM' }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="diagnosisResult" label="诊断" min-width="260" show-overflow-tooltip />
        <el-table-column prop="confidence" label="置信度" width="90">
          <template #default="{ row }">{{ (row.confidence * 100).toFixed(0) }}%</template>
        </el-table-column>
        <el-table-column label="时间" width="170">
          <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import request from '@/utils/request'
import { ElMessage } from 'element-plus'

const servers = ref<any[]>([])
const history = ref<any[]>([])
const diagnosing = ref(false)
const result = ref<any>(null)

const form = reactive({ serverId: null as number | null })

function formatTime(t: string) {
  return t ? t.replace('T', ' ').substring(0, 19) : ''
}

async function loadServers() {
  const res = await request.get('/api/server/page', { params: { page: 1, pageSize: 100 } })
  servers.value = res.data.records || []
}

async function loadHistory() {
  const res = await request.get('/api/diagnosis/history')
  history.value = res.data || []
}

async function runDiagnosis() {
  if (!form.serverId) { ElMessage.warning('请先选择服务器'); return }
  const server = servers.value.find((s: any) => s.id === form.serverId)
  diagnosing.value = true
  result.value = null
  try {
    const res = await request.post('/api/diagnosis/run', {
      serverId: form.serverId,
      serverIp: server ? server.ip : ''
    })
    result.value = res.data
    ElMessage.success('诊断完成')
    loadHistory()
  } finally {
    diagnosing.value = false
  }
}

onMounted(() => { loadServers(); loadHistory() })
</script>