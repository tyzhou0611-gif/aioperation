<template>
  <span class="role-tag" :style="{ color: safeColor, borderColor: safeColor + '66', background: rgba(safeColor, .12) }">
    <i :style="{ background: safeColor, boxShadow: `0 0 6px ${safeColor}` }"></i>
    <span>{{ label || '未命名角色' }}</span>
  </span>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(defineProps<{ label?: string; color?: string }>(), {
  label: '',
  color: '#7c3aed'
})

const safeColor = computed(() => /^#[0-9a-fA-F]{6}$/.test(props.color || '') ? props.color! : '#7c3aed')

function rgba(hex: string, alpha: number) {
  const h = hex.replace('#', '')
  const r = parseInt(h.slice(0, 2), 16)
  const g = parseInt(h.slice(2, 4), 16)
  const b = parseInt(h.slice(4, 6), 16)
  return `rgba(${r}, ${g}, ${b}, ${alpha})`
}
</script>

<style scoped>
.role-tag {
  display: inline-flex; align-items: center; gap: 5px;
  padding: 2px 9px; border-radius: 999px;
  border: 1px solid; font-size: 12px; font-weight: 600; line-height: 1.7;
  white-space: nowrap;
}
.role-tag i { width: 6px; height: 6px; border-radius: 50%; display: inline-block; }
</style>