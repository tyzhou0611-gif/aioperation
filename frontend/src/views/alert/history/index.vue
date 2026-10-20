<template>
  <div>
    <h2 style="margin:0 0 20px;font-size:22px;color:#333">告警历史</h2>

    <el-card>
      <!-- 筛选 -->
      <div style="display:flex;gap:12px;margin-bottom:16px">
        <el-select v-model="levelFilter" placeholder="告警级别" clearable style="width:140px" @change="fetchData">
          <el-option label="全部" value="" />
          <el-option label="警告" value="WARNING" />
          <el-option label="严重" value="CRITICAL" />
        </el-select>
        <el-input v-model="query.keyword" placeholder="搜索 IP / 指标 / 消息" clearable style="width:260px" @keyup.enter="fetchData" />
        <el-button type="primary" @click="fetchData">查询</el-button>
      </div>

      <!-- 表格 -->
      <el-table :data="tableData" v-loading="loading" stripe style="width:100%">
        <el-table-column prop="id" label="ID" width="60" />
        <el-table-column prop="serverIp" label="服务器IP" width="130" />
        <el-table-column label="指标" width="100">
          <template #default="{ row }">
            <el-tag size="small">{{ metricLabel(row.metricName) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="触发值" width="100" align="center">
          <template #default="{ row }">{{ row.actualValue }}%</template>
        </el-table-column>
        <el-table-column label="阈值" width="100" align="center">
          <template #default="{ row }">{{ row.comparator }} {{ row.threshold }}%</template>
        </el-table-column>
        <el-table-column label="级别" width="80">
          <template #default="{ row }">
            <el-tag :type="row.level === 'CRITICAL' ? 'danger' : 'warning'" size="small">
              {{ row.level === 'CRITICAL' ? '严重' : '警告' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="message" label="告警消息" min-width="260" show-overflow-tooltip />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="时间" width="160">
          <template #default="{ row }">{{ row.createTime?.replace('T', ' ') }}</template>
        </el-table-column>
      </el-table>

      <!-- 分页 -->
      <div style="display:flex;justify-content:flex-end;margin-top:16px">
        <el-pagination
          v-model:current-page="query.page"
          v-model:page-size="query.pageSize"
          :total="total"
          :page-sizes="[10,20,50]"
          layout="total, sizes, prev, pager, next"
          @change="fetchData"
        />
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import request from '@/utils/request'

const loading = ref(false)
const tableData = ref<any[]>([])
const total = ref(0)
const levelFilter = ref('')

const query = reactive({ page: 1, pageSize: 10, keyword: '', level: '' })

function metricLabel(name: string) {
  const map: Record<string, string> = { CPU_USAGE: 'CPU', MEM_USAGE: '内存', DISK_USAGE: '磁盘' }
  return map[name] || name
}
function statusType(s: string) {
  return s === 'UNREAD' ? 'danger' : s === 'READ' ? 'warning' : 'info'
}
function statusLabel(s: string) {
  return s === 'UNREAD' ? '未读' : s === 'READ' ? '已读' : '已解决'
}

async function fetchData() {
  query.level = levelFilter.value
  loading.value = true
  try {
    const res = await request.get('/api/alert/history/page', { params: query })
    tableData.value = res.data.records
    total.value = res.data.total
  } finally {
    loading.value = false
  }
}

onMounted(fetchData)
</script>