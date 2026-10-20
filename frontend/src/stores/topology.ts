import { defineStore } from 'pinia'
import { ref } from 'vue'
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

  async function loadGraph() {
    loading.value = true
    try {
      const res = await request.get('/api/topology/graph')
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
    nodes, edges, stats, relations, editMode, loading, connectFrom,
    loadGraph, loadRelations, allowedTypes,
    createRelation, updateRelation, deleteRelation, confirmEdge,
    toggleEdit, pickNode, resetConnect
  }
})