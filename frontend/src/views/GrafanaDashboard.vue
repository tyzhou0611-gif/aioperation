<template>
  <div style="width:100%;height:100%">
    <el-alert
      type="info"
      show-icon
      :closable="false"
      style="margin-bottom:8px"
    >
      <template #title>
        Grafana 已通过本站同源代理嵌入。请在下方窗口直接登录（<strong>admin / easipass</strong>），登录后会自动跳转看板。
      </template>
    </el-alert>
    <iframe
      :src="grafanaUrl"
      style="width:100%;height:calc(100vh - 190px);border:1px solid #e6e6e6;border-radius:8px;background:#fff"
    />
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'

const props = defineProps<{ url: string }>()
const grafanaUrl = ref(props.url)
</script>