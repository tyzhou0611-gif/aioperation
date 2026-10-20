<template>
  <div>
    <h2 style="margin:0 0 16px;font-size:22px;color:#333">知识库管理</h2>

    <el-card>
      <!-- 工具栏 -->
      <div style="display:flex;justify-content:space-between;margin-bottom:16px;flex-wrap:wrap;gap:12px">
        <el-input v-model="keyword" placeholder="搜索知识库" clearable style="width:200px" />
        <el-button type="primary" @click="showCreate=true">+ 创建知识库</el-button>
      </div>

      <!-- 知识库列表 -->
      <el-table :data="filteredKbs" stripe style="width:100%">
        <el-table-column prop="name" label="名称" min-width="120" />
        <el-table-column prop="description" label="描述" min-width="200" show-overflow-tooltip />
        <el-table-column prop="embeddingModel" label="检索模式" width="100" />
        <el-table-column prop="docCount" label="文档数" width="80" align="center" />
        <el-table-column prop="updateTime" label="更新时间" width="160">
          <template #default="{ row }">{{ fmt(row.updateTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="280" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDocs(row)">文档</el-button>
            <el-button link type="success" size="small" @click="openRetrieve(row)">检索测试</el-button>
            <el-button link type="danger" size="small" @click="delKb(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 创建知识库 -->
    <el-dialog v-model="showCreate" title="创建知识库" width="460px">
      <el-form label-width="80px">
        <el-form-item label="名称"><el-input v-model="newKb.name" placeholder="如：运维手册" /></el-form-item>
        <el-form-item label="描述"><el-input v-model="newKb.description" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="showCreate=false">取消</el-button><el-button type="primary" @click="createKb">创建</el-button></template>
    </el-dialog>

    <!-- 文档管理 -->
    <el-drawer v-model="docsVisible" :title="'文档管理 - ' + (curKb?.name || '')" size="600px">
      <div style="margin-bottom:12px;display:flex;gap:8px">
        <el-upload :show-file-list="false" :before-upload="uploadMd" accept=".md,.txt">
          <el-button type="primary">上传 Markdown</el-button>
        </el-upload>
        <el-upload :show-file-list="false" :before-upload="uploadZip" accept=".zip">
          <el-button type="warning">导入 Obsidian zip</el-button>
        </el-upload>
      </div>
      <el-table :data="docs" stripe style="width:100%">
        <el-table-column prop="title" label="标题" min-width="180" show-overflow-tooltip />
        <el-table-column prop="source" label="来源" width="90" />
        <el-table-column prop="chunkCount" label="切块" width="60" align="center" />
        <el-table-column prop="status" label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.status==='SUCCESS'?'success':row.status==='FAILED'?'danger':'warning'" size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <el-button link size="small" @click="openChunks(row.id)">切块</el-button>
            <el-button link type="danger" size="small" @click="delDoc(row.id)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-drawer>

    <!-- 切块查看 -->
    <el-drawer v-model="chunksVisible" title="知识块" size="600px">
      <div v-for="c in chunks" :key="c.id" class="chunk-card">
        <div class="chunk-head">{{ c.headingPath || '无标题' }} <el-tag size="small">#{{ c.chunkIndex }}</el-tag></div>
        <pre class="chunk-body">{{ c.content }}</pre>
      </div>
      <el-empty v-if="!chunks.length" description="无切块" />
    </el-drawer>

    <!-- 检索测试 -->
    <el-drawer v-model="retrieveVisible" :title="'检索测试 - ' + (curKb?.name || '')" size="600px">
      <el-input v-model="retrieveQuery" type="textarea" :rows="2" placeholder="输入问题测试检索" />
      <el-button type="primary" size="small" style="margin-top:8px" @click="doRetrieve">检索</el-button>
      <div v-for="r in retrieveResults" :key="r.id" class="chunk-card" style="margin-top:12px">
        <div class="chunk-head">{{ r.headingPath || '无标题' }}</div>
        <pre class="chunk-body">{{ r.content }}</pre>
      </div>
      <el-empty v-if="retrieved && !retrieveResults.length" description="未检索到相关内容" />
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import request from '@/utils/request'
import { ElMessage, ElMessageBox } from 'element-plus'

const kbs = ref<any[]>([])
const keyword = ref('')
const showCreate = ref(false)
const newKb = reactive({ name: '', description: '' })
const docsVisible = ref(false)
const curKb = ref<any>(null)
const docs = ref<any[]>([])
const chunksVisible = ref(false)
const chunks = ref<any[]>([])
const retrieveVisible = ref(false)
const retrieveQuery = ref('')
const retrieveResults = ref<any[]>([])
const retrieved = ref(false)

const filteredKbs = computed(() => kbs.value.filter(k => !keyword.value || k.name?.includes(keyword.value)))

function fmt(t?: string) { return t ? t.replace('T', ' ').substring(0, 16) : '' }

async function loadKbs() { const r = await request.get('/api/kb'); kbs.value = r.data || [] }

async function createKb() {
  if (!newKb.name?.trim()) { ElMessage.warning('请输入名称'); return }
  await request.post('/api/kb', { name: newKb.name, description: newKb.description })
  ElMessage.success('创建成功'); showCreate.value = false; newKb.name = ''; newKb.description = ''; loadKbs()
}

async function delKb(row: any) {
  await ElMessageBox.confirm(`确定删除知识库「${row.name}」及所有文档？`, '提示', { type: 'warning' })
  await request.delete(`/api/kb/${row.id}`); ElMessage.success('已删除'); loadKbs()
}

async function openDocs(kb: any) { curKb.value = kb; docsVisible.value = true; await loadDocs(kb.id) }
async function loadDocs(kbId: number) { const r = await request.get(`/api/kb/${kbId}/docs`); docs.value = r.data || [] }

async function uploadMd(file: any) {
  const fd = new FormData(); fd.append('file', file)
  await request.post(`/api/kb/${curKb.value.id}/upload`, fd, { headers: { 'Content-Type': 'multipart/form-data' } })
  ElMessage.success('上传成功'); loadDocs(curKb.value.id); return false
}

async function uploadZip(file: any) {
  const fd = new FormData(); fd.append('file', file)
  await request.post(`/api/kb/${curKb.value.id}/import-obsidian`, fd, { headers: { 'Content-Type': 'multipart/form-data' } })
  ElMessage.success('导入完成'); loadDocs(curKb.value.id); return false
}

async function delDoc(docId: number) {
  await request.delete(`/api/kb/doc/${docId}`); ElMessage.success('已删除'); loadDocs(curKb.value.id)
}

async function openChunks(docId: number) {
  chunksVisible.value = true
  const r = await request.get(`/api/kb/doc/${docId}/chunks`); chunks.value = r.data || []
}

async function openRetrieve(kb: any) { curKb.value = kb; retrieveVisible.value = true; retrieveResults.value = []; retrieved.value = false }

async function doRetrieve() {
  if (!retrieveQuery.value.trim()) return
  const r = await request.post(`/api/kb/${curKb.value.id}/retrieve`, { query: retrieveQuery.value })
  retrieveResults.value = r.data || []; retrieved.value = true
}

import { reactive } from 'vue'
onMounted(loadKbs)
</script>

<style scoped>
.chunk-card { border: 1px solid #ebeef5; border-radius: 6px; margin-bottom: 10px; overflow: hidden; }
.chunk-head { padding: 6px 10px; background: #f5f7fa; font-size: 13px; color: #606266; font-weight: 600; }
.chunk-body { margin: 0; padding: 10px; font-size: 13px; white-space: pre-wrap; word-break: break-all; max-height: 300px; overflow-y: auto; }
</style>