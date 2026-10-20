<template>
  <div>
    <h2 style="margin:0 0 16px;font-size:22px;color:#333">命令执行</h2>
    <el-card>
      <el-form label-width="90px" style="max-width:720px">
        <el-form-item label="目标服务器">
          <el-select v-model="serverId" filterable placeholder="选择服务器" style="width:100%">
            <el-option v-for="s in servers" :key="s.id" :label="s.hostname + ' (' + s.ip + ')'" :value="s.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="命令">
          <el-input v-model="command" type="textarea" :rows="3" placeholder="如 df -h" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="running" :disabled="!serverId || !command.trim()" @click="run">执行</el-button>
        </el-form-item>
      </el-form>
      <div class="term" v-if="output">
        <div v-for="(l, i) in output.split('\n')" :key="i">{{ l }}</div>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import request from '@/utils/request'
import { ElMessage } from 'element-plus'

const route = useRoute()
const servers = ref<any[]>([])
const serverId = ref<number | null>(null)
const command = ref('')
const running = ref(false)
const output = ref('')

async function loadServers() {
  const res = await request.get('/api/server/page', { params: { page: 1, pageSize: 500 } })
  servers.value = res.data.records || []
  if (route.query.serverId) serverId.value = Number(route.query.serverId)
}

async function run() {
  running.value = true; output.value = '$ ' + command.value + '\n执行中…'
  try {
    const r = await request.post('/api/exec/quick', { targetType: 'SERVER', targetId: serverId.value, command: command.value })
    const task = r.data
    if (task.status !== 'RUNNING') { output.value = task.output || task.status; return }
    const timer = setInterval(async () => {
      const p = await request.get(`/api/exec/quick/${task.taskId}`)
      if (p.data.status !== 'RUNNING') {
        clearInterval(timer)
        output.value = `$ ${command.value}\n\n` + (p.data.output || '') + `\n\n# ${p.data.status}`
        running.value = false
      }
    }, 900)
  } catch (e: any) { output.value = '执行失败：' + (e?.message || e); running.value = false }
}

onMounted(loadServers)
</script>

<style scoped>
.term { background:#0d1117; color:#3ddc71; font-family:monospace; font-size:13px; padding:12px; border-radius:8px; min-height:120px; white-space:pre-wrap; }
</style>
