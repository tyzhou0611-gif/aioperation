<template>
  <div class="kb-page">
    <div class="page-head">
      <div>
        <h2>知识库管理</h2>
        <div class="sub">Markdown / Obsidian 双链笔记 · 知识树 · 知识图谱</div>
      </div>
      <el-button v-permission="'kb:create'" type="primary" @click="showCreate = true">+ 创建知识库</el-button>
    </div>

    <el-card>
      <div class="toolbar">
        <el-input v-model="keyword" placeholder="搜索知识库" clearable style="width:240px" />
        <span class="hint">导入 Obsidian vault 后，可在「知识树 / 知识图谱」中浏览双链关系</span>
      </div>

      <el-table :data="filteredKbs" stripe style="width:100%" v-loading="kbsLoading">
        <el-table-column prop="name" label="名称" min-width="140" />
        <el-table-column prop="description" label="描述" min-width="220" show-overflow-tooltip />
        <el-table-column prop="embeddingModel" label="检索模式" width="100" />
        <el-table-column prop="docCount" label="文档数" width="80" align="center" />
        <el-table-column prop="updateTime" label="更新时间" width="160">
          <template #default="{ row }">{{ fmt(row.updateTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="250" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openKb(row)">打开</el-button>
            <el-button link type="success" size="small" @click="openRetrieve(row)">检索测试</el-button>
            <el-button v-permission="'kb:delete'" link type="danger" size="small" @click="delKb(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!filteredKbs.length && !kbsLoading" description="暂无知识库，点击右上角创建" />
    </el-card>

    <!-- 创建知识库 -->
    <el-dialog v-model="showCreate" title="创建知识库" width="480px">
      <el-form label-width="80px">
        <el-form-item label="名称"><el-input v-model="newKb.name" placeholder="如：运维知识库" /></el-form-item>
        <el-form-item label="描述"><el-input v-model="newKb.description" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showCreate = false">取消</el-button>
        <el-button type="primary" @click="createKb">创建</el-button>
      </template>
    </el-dialog>

    <!-- 新建/编辑文档 -->
    <el-dialog v-model="docEditorVisible" :title="docEditingId ? '编辑文档' : '新建文档'" width="920px" top="4vh"
               :close-on-click-modal="false" destroy-on-close>
      <el-form :model="docForm" label-width="80px" v-loading="docLoading">
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="标题">
              <el-input v-model="docForm.title" placeholder="文档标题；留空则用正文一级标题或文件名" maxlength="200" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="文件名">
              <el-input v-model="docForm.fileName" placeholder="可选，如 运维/MySQL排查.md；新建留空自动生成" maxlength="200" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="正文">
          <el-input v-model="docForm.content" type="textarea" :rows="22" class="doc-editor"
                    placeholder="# 文档标题&#10;&#10;支持 Markdown、[[双链]]、#标签 和 frontmatter。&#10;&#10;- 要点一&#10;- 要点二" />
          <div class="hint">保存后会立即切块并更新知识树/图谱，可直接用于搜索和 AI 检索。内容上限 2MB。</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <span class="doc-counter">{{ (docForm.content || '').length }} 字符</span>
        <el-button @click="docEditorVisible = false">取消</el-button>
        <el-button type="primary" :loading="docSaving" @click="saveDocEditor">保存文档</el-button>
      </template>
    </el-dialog>

    <!-- 知识库详情 -->
    <el-drawer v-model="detailVisible" :title="currentKb?.name || '知识库'" size="92%" destroy-on-close>
      <el-tabs v-model="activeTab" @tab-change="onTabChange">
        <!-- 文档 -->
        <el-tab-pane label="文档" name="docs">
          <div class="import-bar">
            <el-button v-permission="'kb:upload'" type="primary" @click="pickMd">上传 Markdown（可多选）</el-button>
            <el-button v-permission="'kb:upload'" type="primary" plain @click="openCreateDoc">+ 新建文档</el-button>
            <el-button v-permission="'kb:upload'" type="success" @click="pickFolder">导入文件夹</el-button>
            <el-upload :show-file-list="false" :before-upload="uploadZip" accept=".zip">
              <el-button v-permission="'kb:upload'" type="warning" :loading="importing">导入 Obsidian zip</el-button>
            </el-upload>
            <span class="hint">支持 [[双链]] / #标签 / frontmatter；同名笔记重复导入会更新而不是新增</span>
          </div>
          <input ref="mdInput" type="file" multiple accept=".md,.markdown,.txt" class="hidden-input" @change="onMdFiles" />
          <input ref="folderInput" type="file" multiple webkitdirectory directory class="hidden-input" @change="onFolderFiles" />

          <el-table :data="docs" stripe style="width:100%" v-loading="docsLoading">
            <el-table-column prop="title" label="标题" min-width="160" show-overflow-tooltip />
            <el-table-column prop="fileName" label="路径" min-width="220" show-overflow-tooltip />
            <el-table-column prop="source" label="来源" width="110">
              <template #default="{ row }">
                <el-tag size="small" :type="row.source === 'OBSIDIAN' ? 'warning' : row.source === 'MANUAL' ? 'success' : 'info'">
                  {{ row.source === 'OBSIDIAN' ? 'Obsidian' : row.source === 'MANUAL' ? '页面创建' : '上传' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="chunkCount" label="切块" width="70" align="center" />
            <el-table-column prop="status" label="状态" width="90">
              <template #default="{ row }">
                <el-tag :type="row.status === 'SUCCESS' ? 'success' : row.status === 'FAILED' ? 'danger' : 'warning'" size="small">
                  {{ row.status }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="250" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="openDocById(row.id)">预览</el-button>
                <el-button v-permission="'kb:upload'" link type="primary" size="small" @click="openEditDoc(row)">编辑</el-button>
                <el-button link size="small" @click="openChunks(row.id)">切块</el-button>
                <el-button link type="danger" size="small" @click="delDoc(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-if="!docs.length && !docsLoading" description="暂无文档，请使用上方按钮导入" />
        </el-tab-pane>

        <!-- 知识树 -->
        <el-tab-pane label="知识树" name="tree">
          <div class="tree-layout">
            <div class="tree-panel">
              <el-input v-model="treeKeyword" placeholder="搜索笔记/目录" clearable style="margin-bottom:10px" @input="filterTree" />
              <el-tree ref="treeRef" :data="treeData" node-key="id" :props="treeProps"
                       :filter-node-method="filterTreeNode" highlight-current default-expand-all
                       :expand-on-click-node="false" @node-click="onTreeNodeClick">
                <template #default="{ data }">
                  <span class="tree-node">
                    <el-icon><Folder v-if="data.type === 'folder'" /><Document v-else /></el-icon>
                    <span class="tree-label">{{ data.label }}</span>
                    <el-tag v-if="data.type === 'doc' && data.chunkCount" size="small" type="info">{{ data.chunkCount }}</el-tag>
                  </span>
                </template>
              </el-tree>
              <el-empty v-if="!treeData.length && !treeLoading" description="暂无笔记" :image-size="70" />
            </div>
            <div class="tree-preview" v-loading="previewLoading">
              <DocPreview v-if="treePreview" :doc="treePreview" @open="openTreeDoc" />
              <el-empty v-else description="点击左侧笔记查看内容、标题大纲和反向链接" />
            </div>
          </div>
        </el-tab-pane>

        <!-- 知识图谱 -->
        <el-tab-pane label="知识图谱" name="graph">
          <div class="graph-wrap">
            <div class="graph-bar">
              <span>文档 <b>{{ graphStats.docCount || 0 }}</b></span>
              <span>标签 <b>{{ graphStats.tagCount || 0 }}</b></span>
              <span>关系 <b>{{ graphStats.linkCount || 0 }}</b></span>
              <span class="abn">未创建 <b>{{ graphStats.missingCount || 0 }}</b></span>
              <el-switch v-model="showTagNodes" active-text="显示标签节点" @change="renderGraph" />
              <el-button size="small" :loading="graphLoading" @click="loadGraphData">刷新</el-button>
              <span class="hint">实线=双链，虚线=标签/未创建；点击文档节点查看内容</span>
            </div>
            <div class="graph-canvas" v-loading="graphLoading">
              <v-chart v-if="graphData.nodes?.length" :option="graphOption" autoresize style="height:100%;width:100%" @click="onGraphClick" />
              <el-empty v-else description="暂无图谱数据，导入带 [[双链]] 或 #标签 的笔记后再看" />
            </div>
          </div>
        </el-tab-pane>

        <!-- 检索测试 -->
        <el-tab-pane label="检索测试" name="retrieve">
          <div class="retrieve-wrap">
            <el-input v-model="retrieveQuery" type="textarea" :rows="2" placeholder="输入问题测试检索" />
            <el-button type="primary" size="small" style="margin-top:8px" @click="doRetrieve">检索</el-button>
            <div v-for="r in retrieveResults" :key="r.id" class="chunk-card">
              <div class="chunk-head">{{ r.headingPath || '无标题' }}</div>
              <pre class="chunk-body">{{ r.content }}</pre>
            </div>
            <el-empty v-if="retrieved && !retrieveResults.length" description="未检索到相关内容" />
          </div>
        </el-tab-pane>
      </el-tabs>
    </el-drawer>

    <!-- 文档预览 -->
    <el-dialog v-model="previewVisible" :title="previewData?.title || '文档预览'" width="920px" top="5vh">
      <div style="height:72vh">
        <DocPreview :doc="previewData" @open="openDocById" />
      </div>
    </el-dialog>

    <!-- 切块查看 -->
    <el-drawer v-model="chunksVisible" title="知识切块" size="640px">
      <div v-for="c in chunks" :key="c.id" class="chunk-card">
        <div class="chunk-head">{{ c.headingPath || '无标题' }} <el-tag size="small">#{{ c.chunkIndex }}</el-tag></div>
        <pre class="chunk-body">{{ c.content }}</pre>
      </div>
      <el-empty v-if="!chunks.length" description="无切块" />
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Document, Folder } from '@element-plus/icons-vue'
import VChart from 'vue-echarts'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { GraphChart } from 'echarts/charts'
import { TooltipComponent, LegendComponent } from 'echarts/components'
import request from '@/utils/request'
import DocPreview from '@/components/DocPreview.vue'

use([CanvasRenderer, GraphChart, TooltipComponent, LegendComponent])

interface Kb { id: number; name: string; description?: string; embeddingModel?: string; docCount?: number; updateTime?: string }
interface Doc { id: number; title: string; fileName: string; source: string; chunkCount: number; status: string }

const route = useRoute()
const kbs = ref<Kb[]>([])
const kbsLoading = ref(false)
const keyword = ref('')
const showCreate = ref(false)
const newKb = reactive({ name: '', description: '' })

const detailVisible = ref(false)
const currentKb = ref<Kb | null>(null)
const activeTab = ref('docs')

const docs = ref<Doc[]>([])
const docsLoading = ref(false)
const importing = ref(false)
const mdInput = ref<HTMLInputElement>()
const folderInput = ref<HTMLInputElement>()

const treeData = ref<any[]>([])
const treeKeyword = ref('')
const treeRef = ref<any>()
const treeLoading = ref(false)
const treeLoaded = ref(false)
const treePreview = ref<any>(null)
const previewLoading = ref(false)
const treeProps = { label: 'label', children: 'children' }

const graphData = ref<any>({ nodes: [], edges: [], stats: {} })
const graphStats = computed(() => graphData.value?.stats || {})
const graphOption = ref<any>({})
const graphLoading = ref(false)
const graphLoaded = ref(false)
const showTagNodes = ref(true)

const previewVisible = ref(false)
const previewData = ref<any>(null)

const chunksVisible = ref(false)
const chunks = ref<any[]>([])

const docEditorVisible = ref(false)
const docEditingId = ref<number | null>(null)
const docSaving = ref(false)
const docLoading = ref(false)
const docForm = reactive({ title: '', fileName: '', content: '' })

function openCreateDoc() {
  docEditingId.value = null
  docForm.title = ''
  docForm.fileName = ''
  docForm.content = ''
  docEditorVisible.value = true
}

async function openEditDoc(row: any) {
  docLoading.value = true
  docEditingId.value = row.id
  docForm.title = row.title || ''
  docForm.fileName = row.fileName || ''
  docForm.content = ''
  docEditorVisible.value = true
  try {
    const r = await request.get(`/api/kb/doc/${row.id}/content`)
    docForm.title = r.data?.title || row.title || ''
    docForm.fileName = r.data?.fileName || row.fileName || ''
    docForm.content = r.data?.content || ''
  } catch (e: any) {
    ElMessage.error(e?.message || '读取文档失败')
    docEditorVisible.value = false
  } finally { docLoading.value = false }
}

async function saveDocEditor() {
  if (!currentKb.value) { ElMessage.warning('请先打开知识库'); return }
  if (!docForm.title.trim() && !docForm.fileName.trim() && !docForm.content.trim()) {
    ElMessage.warning('请至少填写标题、文件名或正文'); return
  }
  docSaving.value = true
  try {
    if (docEditingId.value) {
      await request.put(`/api/kb/doc/${docEditingId.value}`, { ...docForm })
      ElMessage.success('文档已保存并重新切块')
    } else {
      await request.post(`/api/kb/${currentKb.value.id}/doc`, { ...docForm })
      ElMessage.success('文档已创建并切块')
    }
    docEditorVisible.value = false
    await afterImport()
  } finally { docSaving.value = false }
}

const retrieveQuery = ref('')
const retrieveResults = ref<any[]>([])
const retrieved = ref(false)

const filteredKbs = computed(() => kbs.value.filter(k => !keyword.value || k.name?.toLowerCase().includes(keyword.value.toLowerCase())))

function fmt(t?: string) { return t ? t.replace('T', ' ').substring(0, 16) : '' }

async function loadKbs() {
  kbsLoading.value = true
  try {
    const r = await request.get('/api/kb')
    kbs.value = r.data || []
    if (currentKb.value) {
      const fresh = kbs.value.find(k => k.id === currentKb.value!.id)
      if (fresh) currentKb.value = { ...fresh }
    }
  } finally { kbsLoading.value = false }
}

async function createKb() {
  if (!newKb.name?.trim()) { ElMessage.warning('请输入名称'); return }
  await request.post('/api/kb', { name: newKb.name.trim(), description: newKb.description })
  ElMessage.success('创建成功')
  showCreate.value = false
  newKb.name = ''
  newKb.description = ''
  await loadKbs()
}

async function delKb(row: Kb) {
  await ElMessageBox.confirm(`确定删除知识库「${row.name}」及所有文档？`, '提示', { type: 'warning' })
  await request.delete(`/api/kb/${row.id}`)
  ElMessage.success('已删除')
  await loadKbs()
}

function openKb(row: Kb) {
  currentKb.value = row
  detailVisible.value = true
  activeTab.value = 'docs'
  docs.value = []
  treeData.value = []
  treePreview.value = null
  treeLoaded.value = false
  graphLoaded.value = false
  graphData.value = { nodes: [], edges: [], stats: {} }
  retrieveResults.value = []
  retrieved.value = false
  loadDocs()
}

function openRetrieve(row: Kb) {
  currentKb.value = row
  detailVisible.value = true
  activeTab.value = 'retrieve'
  retrieveQuery.value = ''
  retrieveResults.value = []
  retrieved.value = false
}

function onTabChange(name: any) {
  if (name === 'tree' && !treeLoaded.value) loadTree()
  if (name === 'graph' && !graphLoaded.value) loadGraphData()
}

async function loadDocs() {
  if (!currentKb.value) return
  docsLoading.value = true
  try {
    const r = await request.get(`/api/kb/${currentKb.value.id}/docs`)
    docs.value = r.data || []
  } finally { docsLoading.value = false }
}

// ==================== 导入 ====================

function pickMd() { mdInput.value?.click() }
function pickFolder() { folderInput.value?.click() }

async function onMdFiles(e: Event) {
  const input = e.target as HTMLInputElement
  const files = Array.from(input.files || [])
  await uploadBatchFiles(files, files.map(f => f.name), 'UPLOAD')
  input.value = ''
}

async function onFolderFiles(e: Event) {
  const input = e.target as HTMLInputElement
  const files = Array.from(input.files || [])
  await uploadBatchFiles(files, files.map(f => (f as any).webkitRelativePath || f.name), 'OBSIDIAN')
  input.value = ''
}

async function uploadBatchFiles(files: File[], paths: string[], source: string) {
  if (!currentKb.value || !files.length) return
  importing.value = true
  try {
    const fd = new FormData()
    files.forEach(f => fd.append('files', f))
    paths.forEach(p => fd.append('paths', p))
    fd.append('source', source)
    const r = await request.post(`/api/kb/${currentKb.value.id}/upload-batch`, fd, {
      headers: { 'Content-Type': 'multipart/form-data' }, timeout: 300000
    })
    const d = r.data || {}
    ElMessage.success(`导入完成：新增 ${d.imported || 0}，更新 ${d.updated || 0}，跳过 ${d.skipped || 0}`)
    await afterImport()
  } finally { importing.value = false }
}

async function uploadZip(file: any) {
  if (!currentKb.value) return false
  importing.value = true
  try {
    const fd = new FormData()
    fd.append('file', file)
    const r = await request.post(`/api/kb/${currentKb.value.id}/import-obsidian`, fd, {
      headers: { 'Content-Type': 'multipart/form-data' }, timeout: 300000
    })
    const d = r.data || {}
    ElMessage.success(`导入完成：新增 ${d.imported || 0}，更新 ${d.updated || 0}，跳过 ${d.skipped || 0}`)
    await afterImport()
  } catch (e: any) {
    ElMessage.error(e?.message || '导入失败')
  } finally { importing.value = false }
  return false
}

async function afterImport() {
  await Promise.all([loadDocs(), loadKbs()])
  treeLoaded.value = false
  graphLoaded.value = false
  if (activeTab.value === 'tree') await loadTree()
  if (activeTab.value === 'graph') await loadGraphData()
}

// ==================== 知识树 ====================

async function loadTree() {
  if (!currentKb.value) return
  treeLoading.value = true
  try {
    const r = await request.get(`/api/kb/${currentKb.value.id}/tree`)
    treeData.value = r.data || []
    treeLoaded.value = true
  } finally { treeLoading.value = false }
}

function filterTree() { treeRef.value?.filter(treeKeyword.value) }

function filterTreeNode(value: string, data: any) {
  if (!value) return true
  return String(data.label || '').toLowerCase().includes(value.toLowerCase())
}

function onTreeNodeClick(data: any) {
  if (data.type === 'doc' && data.docId) openDocById(data.docId, true)
}

function openTreeDoc(docId: number) { openDocById(docId, true) }

async function openDocById(docId: number, inline = false) {
  if (!currentKb.value) return
  previewLoading.value = true
  try {
    const r = await request.get(`/api/kb/doc/${docId}/content`)
    if (inline) {
      treePreview.value = r.data
    } else {
      previewData.value = r.data
      previewVisible.value = true
    }
  } finally { previewLoading.value = false }
}

// ==================== 知识图谱 ====================

const CATEGORY_NAMES = ['文档', '标签', '未创建']
const CATEGORY_COLORS = ['#409eff', '#e6a23c', '#909399']
const CATEGORY_INDEX: Record<string, number> = { DOC: 0, TAG: 1, MISSING: 2 }

async function loadGraphData() {
  if (!currentKb.value) return
  graphLoading.value = true
  try {
    const r = await request.get(`/api/kb/${currentKb.value.id}/graph`)
    graphData.value = r.data || { nodes: [], edges: [], stats: {} }
    graphLoaded.value = true
    renderGraph()
  } finally { graphLoading.value = false }
}

function renderGraph() {
  const rawNodes = graphData.value.nodes || []
  const nodes = rawNodes.filter((n: any) => showTagNodes.value || n.category !== 'TAG')
  const ids = new Set(nodes.map((n: any) => n.id))
  const links = (graphData.value.edges || []).filter((e: any) => ids.has(e.source) && ids.has(e.target))

  graphOption.value = {
    tooltip: {
      confine: true,
      formatter: (p: any) => {
        if (p.dataType === 'node') {
          const r = p.data._raw
          if (!r) return p.name
          let html = `<b>${r.name}</b>`
          if (r.category === 'DOC') {
            html += `<br/>目录：${r.folder || '-'}<br/>切块：${r.chunkCount || 0}`
            if (r.tags?.length) html += `<br/>标签：${r.tags.map((t: string) => '#' + t).join(' ')}`
          } else if (r.category === 'MISSING') {
            html += '<br/><span style="color:#909399">尚未创建该笔记</span>'
          } else {
            html += '<br/><span style="color:#e6a23c">标签节点</span>'
          }
          return html
        }
        if (p.dataType === 'edge') {
          const type = p.data._raw?.type
          return type === 'TAG' ? '标签关联' : type === 'MISSING' ? '指向未创建的笔记' : '双链引用'
        }
        return ''
      }
    },
    legend: [{ data: CATEGORY_NAMES, bottom: 0 }],
    series: [{
      type: 'graph',
      layout: 'force',
      roam: true,
      draggable: true,
      categories: CATEGORY_NAMES.map((name, i) => ({ name, itemStyle: { color: CATEGORY_COLORS[i] } })),
      data: nodes.map((n: any) => ({
        id: n.id,
        name: n.name,
        category: CATEGORY_INDEX[n.category] ?? 0,
        symbolSize: n.symbolSize || 20,
        docId: n.docId,
        _raw: n,
        label: {
          show: n.category !== 'TAG',
          position: 'right',
          formatter: n.name,
          color: n.category === 'MISSING' ? '#909399' : '#303133',
          fontSize: 11
        }
      })),
      links: links.map((e: any) => ({
        source: e.source,
        target: e.target,
        _raw: e,
        lineStyle: {
          color: e.type === 'TAG' ? '#e6a23c' : e.type === 'MISSING' ? '#c0c4cc' : '#4facfe',
          type: e.type === 'WIKILINK' ? 'solid' : 'dashed',
          width: e.type === 'WIKILINK' ? 1.8 : 1.2,
          curveness: 0.12,
          opacity: 0.8
        }
      })),
      force: { repulsion: 280, edgeLength: [80, 160], gravity: 0.1 },
      emphasis: { focus: 'adjacency', lineStyle: { width: 3 } },
      edgeSymbol: ['none', 'arrow'],
      edgeSymbolSize: 6,
      top: 20, bottom: 40, left: 20, right: 20
    }]
  }
}

function onGraphClick(p: any) {
  if (p.dataType !== 'node' || !p.data?._raw) return
  const raw = p.data._raw
  if (raw.docId) openDocById(raw.docId)
  else if (raw.category === 'TAG') ElMessage.info(`标签：${raw.name}`)
  else ElMessage.info(`未创建的笔记：${raw.name}`)
}

// ==================== 切块 / 检索 / 删除 ====================

async function openChunks(docId: number) {
  chunksVisible.value = true
  const r = await request.get(`/api/kb/doc/${docId}/chunks`)
  chunks.value = r.data || []
}

async function delDoc(row: Doc) {
  await ElMessageBox.confirm(`确定删除文档「${row.title}」？`, '提示', { type: 'warning' })
  await request.delete(`/api/kb/doc/${row.id}`)
  ElMessage.success('已删除')
  await afterImport()
}

async function doRetrieve() {
  if (!currentKb.value || !retrieveQuery.value.trim()) return
  const r = await request.post(`/api/kb/${currentKb.value.id}/retrieve`, { query: retrieveQuery.value })
  retrieveResults.value = r.data || []
  retrieved.value = true
}

async function applyRouteQuery() {
  const kbId = Number(route.query.kbId)
  if (!kbId) return
  if (!kbs.value.length) await loadKbs()
  const kb = kbs.value.find(k => k.id === kbId)
  if (!kb) return
  if (currentKb.value?.id !== kb.id) openKb(kb)
  const docId = Number(route.query.docId)
  if (docId) {
    activeTab.value = 'tree'
    if (!treeLoaded.value) await loadTree()
    await openDocById(docId, true)
  }
}

watch(() => [route.query.kbId, route.query.docId], () => { applyRouteQuery() })

onMounted(async () => {
  await loadKbs()
  await applyRouteQuery()
})
</script>

<style scoped>
.kb-page { padding: 0 2px; }
.page-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 14px; }
.page-head h2 { margin: 0; font-size: 22px; color: #333; }
.page-head .sub { color: #909399; font-size: 13px; margin-top: 4px; }
.toolbar { display: flex; align-items: center; gap: 12px; margin-bottom: 14px; flex-wrap: wrap; }
.hint { color: #909399; font-size: 12px; }
.import-bar { display: flex; align-items: center; gap: 10px; margin-bottom: 14px; flex-wrap: wrap; }
.hidden-input { display: none; }

.tree-layout { display: flex; gap: 14px; height: calc(92vh - 160px); min-height: 480px; }
.tree-panel { width: 330px; flex: 0 0 330px; overflow: auto; border-right: 1px solid #ebeef5; padding-right: 14px; }
.tree-preview { flex: 1; min-width: 0; overflow: hidden; }
.tree-node { display: flex; align-items: center; gap: 6px; font-size: 13px; width: 100%; }
.tree-label { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.graph-wrap { display: flex; flex-direction: column; height: calc(92vh - 160px); min-height: 480px; }
.graph-bar { display: flex; align-items: center; gap: 16px; margin-bottom: 10px; color: #606266; font-size: 13px; flex-wrap: wrap; }
.graph-bar b { color: #409eff; font-size: 15px; }
.graph-bar .abn b { color: #f56c6c; }
.graph-canvas { flex: 1; min-height: 380px; border: 1px solid #ebeef5; border-radius: 6px; overflow: hidden; }

.retrieve-wrap { max-width: 900px; }
.chunk-card { border: 1px solid #ebeef5; border-radius: 6px; margin-top: 12px; overflow: hidden; }
.chunk-head { padding: 6px 10px; background: #f5f7fa; font-size: 13px; color: #606266; font-weight: 600; }
.chunk-body { margin: 0; padding: 10px; font-size: 13px; white-space: pre-wrap; word-break: break-all; max-height: 300px; overflow-y: auto; }
.doc-editor :deep(textarea) { font-family: Consolas, 'Courier New', monospace; font-size: 13px; line-height: 1.65; }
.doc-counter { float: left; color: #909399; font-size: 12px; line-height: 32px; }
</style>