<template>
  <div class="rt">
    <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:12px">
      <span style="color:#606266">共 {{ store.relations.length }} 条关系</span>
      <el-button type="primary" size="small" @click="openCreate">+ 新增关系</el-button>
    </div>
    <el-table :data="store.relations" stripe style="width:100%" v-loading="store.loading">
      <el-table-column prop="sourceName" label="源资产" min-width="130">
        <template #default="{ row }">{{ row.sourceName }} <el-tag size="small" type="info">{{ row.sourceType }}</el-tag></template>
      </el-table-column>
      <el-table-column prop="relationType" label="关系" width="120">
        <template #default="{ row }"><el-tag size="small">{{ tLabel(row.relationType) }}</el-tag></template>
      </el-table-column>
      <el-table-column prop="targetName" label="目标资产" min-width="130">
        <template #default="{ row }">{{ row.targetName }} <el-tag size="small" type="info">{{ row.targetType }}</el-tag></template>
      </el-table-column>
      <el-table-column prop="label" label="标签" width="100" />
      <el-table-column prop="description" label="备注" min-width="160" show-overflow-tooltip />
      <el-table-column label="操作" width="130" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
          <el-button link type="danger" size="small" @click="del(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-empty v-if="!store.relations.length && !store.loading" description="暂无关系，点击【新增关系】或回到图视图用编辑模式连线" />

    <RelationDialog ref="dialogRef" v-model="dialogVisible" />
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessageBox } from 'element-plus'
import { useTopologyStore } from '@/stores/topology'
import RelationDialog from './RelationDialog.vue'
import type { Relation } from '@/types'

const store = useTopologyStore()
const dialogVisible = ref(false)
const dialogRef = ref<any>()

function tLabel(t: string) { return { DEPENDS: '依赖', CALLS: '调用', DEPLOY_ON: '部署', CONNECTS_TO: '连接' }[t] || t }

onMounted(() => { store.loadRelations() })

function openCreate() { dialogRef.value?.setEditing?.({ id: null }); dialogVisible.value = true }
function openEdit(r: Relation) { dialogRef.value?.setEditing(r); dialogVisible.value = true }
function del(r: Relation) {
  ElMessageBox.confirm('确定删除该关系吗？', '提示', { type: 'warning' })
    .then(() => store.deleteRelation(r.id)).catch(() => {})
}
</script>