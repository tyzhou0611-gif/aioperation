<template>
  <el-dialog v-model="vis" :title="editingId ? '编辑关系' : '新增关系'" width="520px" append-to-body
             @open="onOpen">
    <el-form :model="form" label-width="82px">
      <el-form-item label="源资产">
        <el-select v-model="form.sourceKey" filterable placeholder="选择源资产" style="width:100%" @change="recompute">
          <el-option-group v-for="g in groups" :key="g.label" :label="g.label">
            <el-option v-for="o in g.items" :key="o.key" :label="o.label" :value="o.key" />
          </el-option-group>
        </el-select>
      </el-form-item>
      <el-form-item label="目标资产">
        <el-select v-model="form.targetKey" filterable placeholder="选择目标资产" style="width:100%" @change="recompute">
          <el-option-group v-for="g in groups" :key="g.label" :label="g.label">
            <el-option v-for="o in g.items" :key="o.key" :label="o.label" :value="o.key" />
          </el-option-group>
        </el-select>
      </el-form-item>
      <el-form-item label="关系类型">
        <el-select v-model="form.relationType" placeholder="先选源和目标" style="width:100%" :disabled="!allowedTypes.length">
          <el-option v-for="t in allowedTypes" :key="t" :label="tLabel(t)" :value="t" />
        </el-select>
        <div v-if="form.sourceKey && form.targetKey && !allowedTypes.length" style="color:#f56c6c;font-size:12px">
          该源/目标组合无合法关系类型
        </div>
      </el-form-item>
      <el-form-item label="标签"><el-input v-model="form.label" placeholder="如 读写 / 调用 / 部署" /></el-form-item>
      <el-form-item label="备注"><el-input v-model="form.description" type="textarea" :rows="2" /></el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="vis=false">取消</el-button>
      <el-button type="primary" :disabled="!valid" :loading="saving" @click="save">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import request from '@/utils/request'
import { ElMessage } from 'element-plus'
import { useTopologyStore } from '@/stores/topology'

const props = defineProps<{ modelValue: boolean; preset?: { sourceKey?: string; targetKey?: string } | null }>()
const emit = defineEmits<{ (e: 'update:modelValue', v: boolean): void }>()
const store = useTopologyStore()

const vis = computed({ get: () => props.modelValue, set: v => emit('update:modelValue', v) })
const editingId = ref<number | null>(null)
const saving = ref(false)
const form = reactive({ sourceKey: '', targetKey: '', relationType: '', label: '', description: '' })
const allowedTypes = ref<string[]>([])

const servers = ref<any[]>([]); const mws = ref<any[]>([]); const apps = ref<any[]>([])
const groups = computed(() => ([
  { label: '应用 APP', items: apps.value.map(a => ({ key: 'APP:' + a.id, label: a.name })) },
  { label: '中间件 MIDDLEWARE', items: mws.value.map(m => ({ key: 'MIDDLEWARE:' + m.id, label: m.name + ' (' + m.type + ')' })) },
  { label: '服务器 SERVER', items: servers.value.map(s => ({ key: 'SERVER:' + (s.id), label: (s.hostname || s.ip) })) },
]))

const valid = computed(() => form.sourceKey && form.targetKey && form.relationType)

function tLabel(t: string) {
  return { DEPENDS: '依赖 DEPENDS', CALLS: '调用 CALLS', DEPLOY_ON: '部署 DEPLOY_ON', CONNECTS_TO: '连接 CONNECTS_TO' }[t] || t
}

async function loadAssets() {
  const s = await request.get('/api/server/page', { params: { page: 1, pageSize: 500 } }); servers.value = s.data.records || []
  const m = await request.get('/api/middleware/options'); mws.value = m.data || []
  const a = await request.get('/api/app/options'); apps.value = a.data || []
}
onMounted(loadAssets)

function onOpen() {
  allowedTypes.value = []
  if (props.preset) { form.sourceKey = props.preset.sourceKey || ''; form.targetKey = props.preset.targetKey || '' }
  if (form.sourceKey && form.targetKey) recompute()
}

async function recompute() {
  if (!form.sourceKey || !form.targetKey) { allowedTypes.value = []; form.relationType = ''; return }
  const st = form.sourceKey.split(':')[0], tt = form.targetKey.split(':')[0]
  allowedTypes.value = await store.allowedTypes(st, tt)
  if (!allowedTypes.value.includes(form.relationType)) form.relationType = allowedTypes.value[0] || ''
}

async function save() {
  if (!valid.value) return
  const [st, sid] = form.sourceKey.split(':'); const [tt, tid] = form.targetKey.split(':')
  const body = { sourceType: st, sourceId: Number(sid), targetType: tt, targetId: Number(tid),
                 relationType: form.relationType, label: form.label, description: form.description, lineStyle: 'solid' }
  saving.value = true
  try {
    if (editingId.value) { await store.updateRelation(editingId.value, body); ElMessage.success('已更新') }
    else { await store.createRelation(body); ElMessage.success('已创建') }
    vis.value = false
    form.relationType = ''; form.label = ''; form.description = ''
  } finally { saving.value = false }
}

// 供外部编辑回填
function setEditing(r: any) {
  editingId.value = r.id
  form.sourceKey = r.sourceType + ':' + r.sourceId
  form.targetKey = r.targetType + ':' + r.targetId
  form.label = r.label || ''; form.description = r.description || ''
  recompute().then(() => { form.relationType = r.relationType })
}
defineExpose({ setEditing })
</script>