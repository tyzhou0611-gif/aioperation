<template>
  <v-chart class="m-mini-chart" :style="{ height: chartHeight }" :option="option" autoresize />
</template>

<script setup lang="ts">
import { computed } from 'vue'
import VChart from 'vue-echarts'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { LineChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components'

use([CanvasRenderer, LineChart, GridComponent, TooltipComponent, LegendComponent])

export interface MiniSeries {
  name: string
  data: (number | null)[]
  color?: string
}

const props = withDefaults(defineProps<{
  times?: (string | number)[]
  series?: MiniSeries[]
  height?: number | string
  unit?: string
  dark?: boolean
  legend?: boolean
}>(), {
  times: () => [],
  series: () => [],
  height: 180,
  unit: '',
  dark: false,
  legend: true
})

const chartHeight = computed(() => typeof props.height === 'number' ? `${props.height}px` : props.height)

const displayTimes = computed(() => {
  const list = props.times || []
  if (!list.length) return []
  const first = list[0]
  const numeric = typeof first === 'number' || /^\d{10,13}$/.test(String(first))
  if (!numeric) return list.map(v => String(v))
  const ms = list.map(v => {
    const n = Number(v)
    return n > 1e12 ? n : n * 1000
  })
  const span = ms.length > 1 ? Math.abs(ms[ms.length - 1] - ms[0]) : 0
  const showDate = span > 24 * 3600 * 1000
  const pad = (n: number) => String(n).padStart(2, '0')
  return ms.map(t => {
    const d = new Date(t)
    if (Number.isNaN(d.getTime())) return ''
    const hm = `${pad(d.getHours())}:${pad(d.getMinutes())}`
    return showDate ? `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${hm}` : hm
  })
})

const palette = ['#1677ff', '#06b6d4', '#8b5cf6', '#10b981', '#f59e0b', '#ef4444']

function areaColor(color: string) {
  return {
    type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
    colorStops: [
      { offset: 0, color: hexToRgba(color, 0.24) },
      { offset: 1, color: hexToRgba(color, 0.02) }
    ]
  }
}

function hexToRgba(hex: string, alpha: number) {
  const h = hex.replace('#', '')
  const full = h.length === 3 ? h.split('').map(c => c + c).join('') : h
  const n = parseInt(full, 16)
  const r = (n >> 16) & 255
  const g = (n >> 8) & 255
  const b = n & 255
  return `rgba(${r}, ${g}, ${b}, ${alpha})`
}

const option = computed(() => {
  const dark = props.dark
  const axisColor = dark ? 'rgba(148,163,184,.35)' : '#e8edf5'
  const labelColor = dark ? '#7f9bc4' : '#8b98ab'
  const series = props.series.map((s, i) => {
    const color = s.color || palette[i % palette.length]
    return {
      name: s.name,
      type: 'line',
      smooth: 0.35,
      showSymbol: false,
      symbol: 'circle',
      symbolSize: 5,
      data: s.data,
      lineStyle: { width: dark ? 2.2 : 2, color },
      itemStyle: { color },
      areaStyle: dark ? { color: areaColor(color) } : undefined
    }
  })
  return {
    backgroundColor: 'transparent',
    color: palette,
    tooltip: {
      trigger: 'axis',
      confine: true,
      appendToBody: true,
      backgroundColor: dark ? 'rgba(7, 13, 27, .94)' : 'rgba(255,255,255,.97)',
      borderColor: dark ? 'rgba(56,189,248,.35)' : '#e2e8f0',
      textStyle: { color: dark ? '#e2e8f0' : '#334155', fontSize: 11 },
      valueFormatter: (v: any) => (v == null ? '-' : `${v}${props.unit}`)
    },
    legend: props.legend ? {
      top: 0,
      right: 0,
      icon: 'roundRect',
      itemWidth: 10,
      itemHeight: 4,
      itemGap: 10,
      textStyle: { color: labelColor, fontSize: 10 }
    } : { show: false },
    grid: { left: 34, right: 8, top: props.legend ? 24 : 8, bottom: 20 },
    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: displayTimes.value,
      axisLine: { lineStyle: { color: axisColor } },
      axisTick: { show: false },
      axisLabel: { color: labelColor, fontSize: 9, hideOverlap: true }
    },
    yAxis: {
      type: 'value',
      scale: false,
      axisLine: { show: false },
      axisTick: { show: false },
      splitLine: { lineStyle: { color: axisColor, type: 'dashed' } },
      axisLabel: { color: labelColor, fontSize: 9 }
    },
    series
  }
})
</script>

<style scoped>
.m-mini-chart {
  width: 100%;
}
</style>