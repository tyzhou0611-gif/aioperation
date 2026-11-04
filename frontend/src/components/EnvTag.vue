<template>
  <el-tag :type="meta.type" :size="size" :effect="effect" class="env-tag" disable-transitions>
    {{ meta.label }}
  </el-tag>
</template>

<script setup lang="ts">
import { computed } from 'vue'

type TagType = 'primary' | 'success' | 'info' | 'warning' | 'danger'

const props = withDefaults(defineProps<{
  env?: string | null
  size?: 'large' | 'default' | 'small'
  effect?: 'dark' | 'light' | 'plain'
}>(), {
  env: '',
  size: 'small',
  effect: 'light'
})

/** 环境与颜色约定：生产红、预发橙、测试蓝、开发绿 */
const ENV_META: Record<string, { label: string; type: TagType }> = {
  production: { label: '生产', type: 'danger' },
  prod: { label: '生产', type: 'danger' },
  staging: { label: '预发', type: 'warning' },
  stage: { label: '预发', type: 'warning' },
  test: { label: '测试', type: 'primary' },
  testing: { label: '测试', type: 'primary' },
  dev: { label: '开发', type: 'success' },
  development: { label: '开发', type: 'success' }
}

const meta = computed(() => {
  const key = (props.env || '').trim().toLowerCase()
  return ENV_META[key] || { label: props.env || '未知', type: 'info' as TagType }
})
</script>

<style scoped>
.env-tag { font-weight: 500; }
</style>