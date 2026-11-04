/** 手机版通用格式化 / 工具 */

export function fmtTime(t: any, withSeconds = true): string {
  if (!t) return '-'
  if (t instanceof Date) {
    return t.toLocaleString('zh-CN', { hour12: false }).replace(/\//g, '-')
  }
  const s = String(t).replace('T', ' ')
  return withSeconds ? s.substring(0, 19) : s.substring(0, 16)
}

export function timeAgo(t: any): string {
  if (!t) return '-'
  const d = new Date(t)
  if (Number.isNaN(d.getTime())) return fmtTime(t)
  const diff = Date.now() - d.getTime()
  if (diff < 60_000) return '刚刚'
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} 分钟前`
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)} 小时前`
  if (diff < 7 * 86_400_000) return `${Math.floor(diff / 86_400_000)} 天前`
  return fmtTime(t, false)
}

export function statusLabel(s: string | null | undefined): string {
  return ({ ONLINE: '在线', OFFLINE: '离线', MAINTENANCE: '维护', UNKNOWN: '未知' } as any)[s || ''] || s || '未知'
}

export function statusClass(s: string | null | undefined): 'online' | 'offline' | 'warn' {
  if (s === 'ONLINE') return 'online'
  if (s === 'OFFLINE') return 'offline'
  return 'warn'
}

export function statusTag(s: string | null | undefined): 'green' | 'red' | 'orange' | 'gray' {
  if (s === 'ONLINE') return 'green'
  if (s === 'OFFLINE') return 'red'
  if (s === 'MAINTENANCE') return 'orange'
  return 'gray'
}

export const ENV_LABEL: Record<string, string> = {
  production: '生产', prod: '生产',
  staging: '预发', stage: '预发',
  test: '测试', testing: '测试',
  dev: '开发', development: '开发'
}

export function envLabel(env: string | null | undefined): string {
  return ENV_LABEL[String(env || '').toLowerCase()] || (env || '未知')
}

export function envTagClass(env: string | null | undefined): 'red' | 'orange' | 'blue' | 'green' | 'gray' {
  const e = String(env || '').toLowerCase()
  if (e === 'production' || e === 'prod') return 'red'
  if (e === 'staging' || e === 'stage') return 'orange'
  if (e === 'test' || e === 'testing') return 'blue'
  if (e === 'dev' || e === 'development') return 'green'
  return 'gray'
}

export function serverName(s: any): string {
  return s?.hostname || s?.name || s?.ip || '-'
}

export function pct(v: any): string {
  if (v == null || v === '' || Number.isNaN(Number(v))) return '-'
  return `${Math.round(Number(v) * 10) / 10}%`
}

export function pctColor(v: any): string {
  const n = Number(v)
  if (!Number.isFinite(n)) return '#94a3b8'
  if (n >= 90) return '#ef4444'
  if (n >= 75) return '#f59e0b'
  return '#1677ff'
}

export function uptimeText(sec: any): string {
  const n = Number(sec)
  if (!Number.isFinite(n) || n <= 0) return '-'
  const d = Math.floor(n / 86400)
  const h = Math.floor((n % 86400) / 3600)
  const m = Math.floor((n % 3600) / 60)
  if (d > 0) return `${d}天 ${h}小时`
  if (h > 0) return `${h}小时 ${m}分`
  return `${m}分钟`
}

/** WebSocket 根地址（兼容 VITE_API_BASE_URL 跨域部署） */
export function wsOrigin(): string {
  const base = (import.meta.env.VITE_API_BASE_URL || '').replace(/\/+$/, '')
  if (base) {
    const u = new URL(base)
    return `${u.protocol === 'https:' ? 'wss' : 'ws'}://${u.host}`
  }
  return `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}`
}

export function copyText(text: string): Promise<void> {
  if (navigator.clipboard?.writeText) return navigator.clipboard.writeText(text)
  return new Promise((resolve, reject) => {
    try {
      const ta = document.createElement('textarea')
      ta.value = text
      ta.style.position = 'fixed'
      ta.style.opacity = '0'
      document.body.appendChild(ta)
      ta.select()
      document.execCommand('copy')
      document.body.removeChild(ta)
      resolve()
    } catch (e) {
      reject(e)
    }
  })
}