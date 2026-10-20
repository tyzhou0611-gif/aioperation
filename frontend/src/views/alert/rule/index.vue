<template>
  <div>
    <h2 style="margin:0 0 20px;font-size:22px;color:#333">告警规则管理</h2>

    <el-card>
      <!-- 工具栏 -->
      <div style="display:flex;justify-content:space-between;margin-bottom:16px">
        <el-button type="primary" @click="openDialog()">+ 新增规则</el-button>
      </div>

      <!-- 表格 -->
      <el-table :data="tableData" v-loading="loading" stripe style="width:100%">
        <el-table-column prop="id" label="ID" width="60" />
        <el-table-column prop="ruleName" label="规则名称" min-width="140" />
        <el-table-column prop="metricName" label="指标" width="120">
          <template #default="{ row }">
            <el-tag :type="metricTagType(row.metricName)" size="small">{{ metricLabel(row.metricName) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="comparator" label="比较符" width="70" align="center" />
        <el-table-column prop="threshold" label="阈值" width="100">
          <template #default="{ row }">{{ row.threshold }}%</template>
        </el-table-column>
        <el-table-column prop="level" label="级别" width="90">
          <template #default="{ row }">
            <el-tag :type="row.level === 'CRITICAL' ? 'danger' : 'warning'" size="small">
              {{ row.level === 'CRITICAL' ? '严重' : '警告' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="notifyEmail" label="通知邮箱" min-width="160" />
        <el-table-column prop="enabled" label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.enabled === 1 ? 'success' : 'info'" size="small">
              {{ row.enabled === 1 ? '启用' : '禁用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link size="small" @click="openDialog(row)">编辑</el-button>
            <el-button type="danger" link size="small" @click="handleDelete(row.id)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 分页 -->
      <div style="display:flex;justify-content:flex-end;margin-top:16px">
        <el-pagination
          v-model:current-page="query.page"
          v-model:page-size="query.pageSize"
          :total="total"
          :page-sizes="[5,10,20]"
          layout="total, sizes, prev, pager, next"
          @change="fetchData"
        />
      </div>
    </el-card>

    <!-- 弹窗 -->
    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑规则' : '新增规则'" width="520px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="规则名称" prop="ruleName">
          <el-input v-model="form.ruleName" maxlength="100" />
        </el-form-item>
        <el-form-item label="指标名" prop="metricName">
          <el-select v-model="form.metricName" style="width:100%">
            <el-option label="CPU使用率 (CPU_USAGE)" value="CPU_USAGE" />
            <el-option label="内存使用率 (MEM_USAGE)" value="MEM_USAGE" />
            <el-option label="磁盘使用率 (DISK_USAGE)" value="DISK_USAGE" />
          </el-select>
        </el-form-item>
        <el-form-item label="比较符" prop="comparator">
          <el-select v-model="form.comparator" style="width:100%">
            <el-option label="大于 (>)" value=">" />
            <el-option label="小于 (<)" value="<" />
            <el-option label="大于等于 (>=)" value=">=" />
            <el-option label="小于等于 (<=)" value="<=" />
          </el-select>
        </el-form-item>
        <el-form-item label="阈值 (%)" prop="threshold">
          <el-input-number v-model="form.threshold" :min="0" :max="100" :precision="1" style="width:100%" />
        </el-form-item>
        <el-form-item label="告警级别" prop="level">
          <el-select v-model="form.level" style="width:100%">
            <el-option label="警告 (WARNING)" value="WARNING" />
            <el-option label="严重 (CRITICAL)" value="CRITICAL" />
          </el-select>
        </el-form-item>
        <el-form-item label="通知邮箱" prop="notifyEmail">
          <el-input v-model="form.notifyEmail" placeholder="多个用逗号分隔" />
        </el-form-item>
        <el-form-item label="启用" prop="enabled">
          <el-switch v-model="form.enabled" :active-value="1" :inactive-value="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import request from '@/utils/request'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'

const loading = ref(false)
const saving = ref(false)
const tableData = ref<any[]>([])
const total = ref(0)
const dialogVisible = ref(false)
const isEdit = ref(false)
const formRef = ref<FormInstance>()

const query = reactive({ page: 1, pageSize: 10, keyword: '' })
const form = reactive<any>({
  ruleName: '', metricName: 'CPU_USAGE', comparator: '>',
  threshold: 80, level: 'WARNING', notifyEmail: '', enabled: 1
})
const editId = ref<number | null>(null)

const rules: FormRules = {
  ruleName: [{ required: true, message: '请输入规则名称' }],
  metricName: [{ required: true, message: '请选择指标' }],
  comparator: [{ required: true, message: '请选择比较符' }],
  threshold: [{ required: true, message: '请输入阈值' }]
}

function metricLabel(name: string) {
  const map: Record<string, string> = { CPU_USAGE: 'CPU', MEM_USAGE: '内存', DISK_USAGE: '磁盘' }
  return map[name] || name
}
function metricTagType(name: string) {
  const map: Record<string, string> = { CPU_USAGE: '', MEM_USAGE: 'success', DISK_USAGE: 'warning' }
  return map[name] || ''
}

async function fetchData() {
  loading.value = true
  try {
    const res = await request.get('/api/alert/rule/page', { params: query })
    tableData.value = res.data.records
    total.value = res.data.total
  } finally {
    loading.value = false
  }
}

function openDialog(row?: any) {
  if (row) {
    isEdit.value = true
    editId.value = row.id
    Object.assign(form, row)
  } else {
    isEdit.value = false
    editId.value = null
    form.ruleName = ''; form.metricName = 'CPU_USAGE'; form.comparator = '>'
    form.threshold = 80; form.level = 'WARNING'; form.notifyEmail = ''; form.enabled = 1
  }
  dialogVisible.value = true
}

async function handleSave() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    if (isEdit.value && editId.value) {
      await request.put(`/api/alert/rule/${editId.value}`, form)
      ElMessage.success('更新成功')
    } else {
      await request.post('/api/alert/rule', form)
      ElMessage.success('新增成功')
    }
    dialogVisible.value = false
    fetchData()
  } finally {
    saving.value = false
  }
}

async function handleDelete(id: number) {
  await ElMessageBox.confirm('确认删除该规则？', '提示', { type: 'warning' })
  await request.delete(`/api/alert/rule/${id}`)
  ElMessage.success('删除成功')
  fetchData()
}

onMounted(fetchData)
</script>