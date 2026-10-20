<template>
  <div>
    <h2 style="margin:0 0 20px;font-size:22px;color:#333">用户管理</h2>

    <el-card>
      <div style="display:flex;justify-content:space-between;margin-bottom:16px">
        <div style="display:flex;gap:12px">
          <el-input v-model="query.keyword" placeholder="搜索用户名/昵称/邮箱" clearable style="width:240px" @keyup.enter="search" />
          <el-button type="primary" @click="search">查询</el-button>
        </div>
        <el-button type="primary" @click="openDialog()">+ 新增用户</el-button>
      </div>

      <el-table :data="rows" v-loading="loading" stripe style="width:100%">
        <el-table-column prop="id" label="ID" width="60" />
        <el-table-column prop="username" label="用户名" width="140" />
        <el-table-column prop="nickname" label="昵称" width="140" />
        <el-table-column prop="email" label="邮箱" min-width="180" />
        <el-table-column label="角色" min-width="160">
          <template #default="{ row }">
            <el-tag v-for="r in (row.roles||[])" :key="r" size="small" style="margin-right:4px">{{ r }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.status===0?'success':'info'" size="small">{{ row.status===0?'正常':'停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button type="danger" link size="small" :disabled="row.id===1" @click="del(row.id)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div style="display:flex;justify-content:flex-end;margin-top:16px">
        <el-pagination
          v-model:current-page="query.page" v-model:page-size="query.pageSize"
          :total="total" :page-sizes="[10,20,50]"
          layout="total, sizes, prev, pager, next" @change="search"
        />
      </div>
    </el-card>

    <el-dialog v-model="dialogVisible" title="新增用户" width="460px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="80px">
        <el-form-item label="用户名" prop="username"><el-input v-model="form.username" /></el-form-item>
        <el-form-item label="昵称"><el-input v-model="form.nickname" /></el-form-item>
        <el-form-item label="邮箱"><el-input v-model="form.email" /></el-form-item>
        <el-form-item label="密码"><el-input v-model="form.password" type="password" show-password placeholder="默认123456" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible=false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
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
const rows = ref<any[]>([])
const total = ref(0)
const dialogVisible = ref(false)
const formRef = ref<FormInstance>()
const query = reactive({ keyword: '', page: 1, pageSize: 10 })
const form = reactive({ username: '', nickname: '', email: '', password: '' })
const rules: FormRules = { username: [{ required: true, message: '请输入用户名' }] }

async function search() {
  loading.value = true
  try {
    const res = await request.get('/api/user/page', { params: query })
    rows.value = res.data.records || []
    total.value = res.data.total || 0
  } finally {
    loading.value = false
  }
}

function openDialog() {
  Object.assign(form, { username: '', nickname: '', email: '', password: '' })
  dialogVisible.value = true
}

async function save() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    await request.post('/api/user', form)
    ElMessage.success('新增成功')
    dialogVisible.value = false
    search()
  } finally {
    saving.value = false
  }
}

async function del(id: number) {
  await ElMessageBox.confirm('确认删除该用户？', '提示', { type: 'warning' })
  await request.delete(`/api/user/${id}`)
  ElMessage.success('删除成功')
  search()
}

onMounted(search)
</script>