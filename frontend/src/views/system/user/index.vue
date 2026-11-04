<template>
  <div class="user-page">
    <div class="page-head">
      <div>
        <h2>用户管理</h2>
        <p class="sub">角色分配为超级管理员专属能力；其他用户管理员只能维护账号资料。</p>
      </div>
    </div>

    <el-card shadow="never">
      <div class="toolbar">
        <div style="display:flex;gap:12px">
          <el-input v-model="query.keyword" placeholder="搜索用户名/昵称/邮箱" clearable style="width:240px" @keyup.enter="search" />
          <el-button type="primary" @click="search">查询</el-button>
        </div>
        <el-button v-permission="'sys:user:add'" type="primary" @click="openCreate">+ 新增用户</el-button>
      </div>

      <el-table :data="rows" v-loading="loading" stripe style="width:100%">
        <el-table-column prop="id" label="ID" width="60" />
        <el-table-column label="用户名" width="150">
          <template #default="{ row }">
            <div class="user-name">
              <span>{{ row.username }}</span>
              <el-tag v-if="row.username === 'admin'" size="small" type="danger" effect="plain">内置</el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="nickname" label="昵称" width="130" />
        <el-table-column prop="email" label="邮箱" min-width="180" />
        <el-table-column label="角色" min-width="260">
          <template #default="{ row }">
            <div class="role-tags">
              <RoleTag v-for="r in (row.roleDetails || [])" :key="r.id" :label="r.label" :color="r.tagColor" />
              <span v-if="!(row.roleDetails || []).length" class="hint">未分配角色</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status===0?'success':'info'" size="small">{{ row.status===0?'正常':'停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="170">
          <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button v-permission="'sys:user:edit'" type="primary" link size="small" @click="openEdit(row)">编辑</el-button>
            <el-button v-if="userStore.isSuperAdmin && row.username !== 'admin'" type="warning" link size="small" @click="openAssign(row)">分配角色</el-button>
            <el-button v-permission="'sys:user:delete'" type="danger" link size="small" :disabled="row.username === 'admin'" @click="del(row.id)">删除</el-button>
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

    <!-- 新增/编辑资料 -->
    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑用户资料' : '新增用户'" width="500px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="80px">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" :disabled="isEdit" />
        </el-form-item>
        <el-form-item label="昵称"><el-input v-model="form.nickname" /></el-form-item>
        <el-form-item label="邮箱"><el-input v-model="form.email" /></el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.status" :active-value="0" :inactive-value="1" active-text="正常" inactive-text="停用" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="form.password" type="password" show-password
                    :placeholder="isEdit ? '留空表示不修改密码' : '默认 123456'" />
        </el-form-item>
        <el-form-item v-if="!isEdit && userStore.isSuperAdmin" label="角色">
          <el-select v-model="form.roleIds" multiple filterable placeholder="可同时选择多个角色" style="width:100%">
            <el-option v-for="r in roleOptions" :key="r.id" :label="r.label" :value="r.id">
              <div style="display:flex;align-items:center;gap:8px">
                <RoleTag :label="r.label" :color="r.tagColor" />
                <span class="opt-scope">{{ scopeLabel(r.scope) }}</span>
              </div>
            </el-option>
          </el-select>
        </el-form-item>
        <el-alert v-if="!isEdit && !userStore.isSuperAdmin" type="info" :closable="false" show-icon
                  title="仅超级管理员可以分配角色。新用户创建后没有角色，需由超级管理员分配。" />
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible=false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <!-- 分配角色（仅超级管理员） -->
    <el-dialog v-model="assignVisible" title="分配角色" width="760px" top="6vh">
      <div class="assign-head">
        <span>用户：<b>{{ assignUser?.username }}</b></span>
        <span class="hint">可多选；保存后该用户重新登录即可看到对应菜单和功能。</span>
      </div>
      <div class="role-grid">
        <label v-for="r in roleOptions" :key="r.id" class="role-option" :class="{ active: assignRoleIds.includes(r.id) }">
          <el-checkbox v-model="assignRoleIds" :value="r.id" />
          <div class="role-opt-main">
            <div class="role-opt-title">
              <RoleTag :label="r.label" :color="r.tagColor" />
              <el-tag size="small" type="info" effect="plain">{{ r.builtin ? '内置' : '自定义' }}</el-tag>
              <span class="role-opt-scope">{{ scopeLabel(r.scope) }}</span>
            </div>
            <div class="role-opt-desc">{{ r.description || '无描述' }}</div>
          </div>
        </label>
      </div>
      <template #footer>
        <el-button @click="assignVisible=false">取消</el-button>
        <el-button type="primary" :loading="assigning" @click="saveAssign">保存角色</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref, onMounted } from 'vue'
import request from '@/utils/request'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import RoleTag from '@/components/RoleTag.vue'
import { useUserStore } from '@/stores/user'

const userStore = useUserStore()
const loading = ref(false)
const saving = ref(false)
const assigning = ref(false)
const rows = ref<any[]>([])
const roleOptions = ref<any[]>([])
const total = ref(0)
const dialogVisible = ref(false)
const isEdit = ref(false)
const editId = ref<number | null>(null)
const formRef = ref<FormInstance>()
const query = reactive({ keyword: '', page: 1, pageSize: 10 })
const form = reactive<any>({
  username: '', nickname: '', email: '', password: '', status: 0, roleIds: [] as number[]
})
const rules: FormRules = { username: [{ required: true, message: '请输入用户名' }] }

const assignVisible = ref(false)
const assignUser = ref<any>(null)
const assignRoleIds = ref<number[]>([])

function formatTime(t: string) {
  return t ? t.replace('T', ' ').substring(0, 19) : ''
}
function scopeLabel(s: string) {
  return ({ GLOBAL: '全局', TENANT: '租户', PROJECT: '项目', READONLY: '只读' } as any)[s] || s || '全局'
}

async function loadRoles() {
  try {
    const res = await request.get('/api/role/options')
    roleOptions.value = res.data || []
  } catch { roleOptions.value = [] }
}

async function search() {
  loading.value = true
  try {
    const res: any = await request.get('/api/user/page', { params: query })
    rows.value = res.data.records || []
    total.value = res.data.total || 0
  } finally {
    loading.value = false
  }
}

function openCreate() {
  isEdit.value = false
  editId.value = null
  Object.assign(form, { username: '', nickname: '', email: '', password: '', status: 0, roleIds: [] })
  dialogVisible.value = true
}

function openEdit(row: any) {
  isEdit.value = true
  editId.value = row.id
  Object.assign(form, {
    username: row.username,
    nickname: row.nickname || '',
    email: row.email || '',
    password: '',
    status: row.status ?? 0,
    roleIds: row.roleIds || []
  })
  dialogVisible.value = true
}

async function save() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    if (isEdit.value && editId.value) {
      await request.put(`/api/user/${editId.value}`, {
        nickname: form.nickname,
        email: form.email,
        status: form.status,
        password: form.password
      })
      ElMessage.success('用户资料已更新')
    } else {
      const payload: any = {
        username: form.username,
        nickname: form.nickname,
        email: form.email,
        password: form.password,
        status: form.status
      }
      if (userStore.isSuperAdmin) payload.roleIds = form.roleIds
      await request.post('/api/user', payload)
      ElMessage.success('用户已创建')
    }
    dialogVisible.value = false
    search()
  } finally {
    saving.value = false
  }
}

function openAssign(row: any) {
  assignUser.value = row
  assignRoleIds.value = [...(row.roleIds || [])]
  assignVisible.value = true
}

async function saveAssign() {
  if (!assignUser.value) return
  assigning.value = true
  try {
    await request.put(`/api/user/${assignUser.value.id}/roles`, { roleIds: assignRoleIds.value })
    ElMessage.success('角色已分配')
    assignVisible.value = false
    search()
  } finally {
    assigning.value = false
  }
}

async function del(id: number) {
  await ElMessageBox.confirm('确认删除该用户？', '提示', { type: 'warning' })
  await request.delete(`/api/user/${id}`)
  ElMessage.success('删除成功')
  search()
}

onMounted(async () => {
  await Promise.all([loadRoles(), search()])
})
</script>

<style scoped>
.user-page h2 { margin: 0; font-size: 22px; color: #303133; }
.page-head { margin-bottom: 14px; }
.sub { margin: 6px 0 0; font-size: 12.5px; color: #909399; }
.toolbar { display: flex; justify-content: space-between; margin-bottom: 16px; }
.user-name { display: flex; align-items: center; gap: 6px; }
.role-tags { display: flex; flex-wrap: wrap; gap: 5px; }
.hint { color: #909399; font-size: 12px; }
.opt-scope { color: #909399; font-size: 11px; }
.assign-head { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; color: #606266; }
.role-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; max-height: 56vh; overflow: auto; padding-right: 4px; }
.role-option {
  display: flex; align-items: flex-start; gap: 10px;
  padding: 10px 12px; border: 1px solid #ebeef5; border-radius: 10px;
  cursor: pointer; transition: all .15s ease; background: #fff;
}
.role-option:hover { border-color: #c7d2fe; background: #f8faff; }
.role-option.active { border-color: #6366f1; background: #f5f7ff; box-shadow: 0 4px 14px rgba(99,102,241,.08); }
.role-opt-main { min-width: 0; }
.role-opt-title { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.role-opt-scope { font-size: 10.5px; color: #a0a8b8; }
.role-opt-desc { margin-top: 4px; font-size: 11.5px; color: #909399; line-height: 1.45; }
</style>