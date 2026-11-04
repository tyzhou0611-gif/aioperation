import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import request from '@/utils/request'
import type { TopoNode, TopoEdge, Relation } from '@/types'

export const useTopologyStore = defineStore('topology', () => {
  const nodes = ref<TopoNode[]>([])
  const edges = ref<TopoEdge[]>([])
  const stats = ref({ appCount: 0, middlewareCount: 0, serverCount: 0, abnormalCount: 0 })
  const relations = ref<Relation[]>([])
  const editMode = ref(false)
  const loading = ref(false)
  const connectFrom = ref<TopoNode | null>(null)   // 点击连线：源节点
  const projectId = ref<number | null>(null)       // 大屏当前筛选的项目，null=全部

  // 当前项目范围内可见的手动关系（两端都在项目节点集合内）
  const filteredRelations = computed(() => {
    if (!projectId.value) return relations.value
    const visible = new Set(nodes.value.map(n => n.id))
    return relations.value.filter(r =>
      visible.has(relationNodeKey(r.sourceType, r.sourceId)) &&
      visible.has(relationNodeKey(r.targetType, r.targetId))
    )
  })

  function relationNodeKey(type: string, id: number) {
    if (type === 'SERVER') return 'SRV:' + id
    if (type === 'MIDDLEWARE') return 'MW:' + id
    return 'APP:' + id
  }

  /** 加载拓扑；pid 省略时沿用当前项目筛选，传 null 表示全部项目 */
  async function loadGraph(pid?: number | null) {
    if (pid !== undefined) projectId.value = pid ?? null
    loading.value = true
    try {
      const res = await request.get('/api/topology/graph', {
        params: projectId.value ? { projectId: projectId.value } : {}
      })
      nodes.value = res.data.nodes
      edges.value = res.data.edges
      stats.value = res.data.stats
    } finally { loading.value = false }
  }

  async function loadRelations() {
    const res = await request.get('/api/topology/relation')
    relations.value = res.data || []
  }

  async function allowedTypes(sourceType: string, targetType: string): Promise<string[]> {
    const res = await request.get('/api/topology/relation/allowed', { params: { sourceType, targetType } })
    return res.data || []
  }

  async function createRelation(data: any) {
    await request.post('/api/topology/relation', data)
    await Promise.all([loadGraph(), loadRelations()])
  }
  async function updateRelation(id: number, data: any) {
    await request.put(`/api/topology/relation/${id}`, data)
    await Promise.all([loadGraph(), loadRelations()])
  }
  async function deleteRelation(id: number) {
    await request.delete(`/api/topology/relation/${id}`)
    await Promise.all([loadGraph(), loadRelations()])
  }
  async function confirmEdge(e: TopoEdge) {
    await request.post('/api/topology/relation/confirm', {
      sourceType: e.sourceKey?.split(':')[0], sourceId: Number(e.sourceKey?.split(':')[1]),
      targetType: e.targetKey?.split(':')[0], targetId: Number(e.targetKey?.split(':')[1]),
      relationType: e.relationType
    })
    await Promise.all([loadGraph(), loadRelations()])
  }

  function toggleEdit() { editMode.value = !editMode.value; connectFrom.value = null }
  function pickNode(n: TopoNode) {
    if (!editMode.value) return false
    if (!connectFrom.value) { connectFrom.value = n; return true }
    return false
  }
  function resetConnect() { connectFrom.value = null }

  return {
    nodes, edges, stats, relations, filteredRelations, projectId, editMode, loading, connectFrom,
    loadGraph, loadRelations, allowedTypes,
    createRelation, updateRelation, deleteRelation, confirmEdge,
    toggleEdit, pickNode, resetConnect
  }
})