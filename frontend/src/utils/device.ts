/**
 * 设备 / 视图模式工具
 * - mobile : 强制手机版（/m）
 * - desktop: 强制桌面版
 * - 未设置  : 自动识别（手机 UA / 窄屏触屏设备使用手机版）
 */
export type ViewMode = 'mobile' | 'desktop' | 'auto'

const VIEW_MODE_KEY = 'aiops_view_mode'

export function getViewMode(): ViewMode {
  const v = localStorage.getItem(VIEW_MODE_KEY)
  return v === 'mobile' || v === 'desktop' ? v : 'auto'
}

export function setViewMode(mode: ViewMode) {
  if (mode === 'auto') localStorage.removeItem(VIEW_MODE_KEY)
  else localStorage.setItem(VIEW_MODE_KEY, mode)
}

/** 手机 UA（Android / iOS / 鸿蒙 / 其他移动端） */
export function isMobileUA(): boolean {
  const ua = navigator.userAgent || ''
  return /Android|iPhone|iPod|iPad|HarmonyOS|Mobile|Windows Phone|BlackBerry|Opera Mini/i.test(ua)
}

/** 是否窄屏（默认 <= 820px） */
export function isNarrowScreen(maxWidth = 820): boolean {
  return window.matchMedia(`(max-width: ${maxWidth}px)`).matches
}

/** 是否应该进入手机版 */
export function preferMobile(): boolean {
  const mode = getViewMode()
  if (mode === 'mobile') return true
  if (mode === 'desktop') return false
  if (isMobileUA()) return true
  // 部分平板/鸿蒙设备 UA 不含 Mobile；触屏 + 窄屏也认为是手机
  const touch = (navigator.maxTouchPoints || 0) > 1
  return touch && isNarrowScreen()
}

/** 当前是否已经在手机版路由中 */
export function isMobileRoute(): boolean {
  return location.pathname === '/m' || location.pathname.startsWith('/m/')
}

/** 桌面路由 → 手机版路由（用于 AI 跳转、旧链接等场景） */
export function toMobilePath(path: string, query: Record<string, any> = {}): string {
  const q = new URLSearchParams()
  Object.keys(query).forEach(k => {
    const v = query[k]
    if (v !== undefined && v !== null && v !== '') q.set(k, String(v))
  })
  const qs = q.toString()
  const qq = qs ? `?${qs}` : ''
  let p = path || '/'
  if (p === '/' || p === '/dashboard') p = '/m/home'
  else if (p === '/asset/server') p = '/m/servers'
  else if (/^\/asset\/middleware\/\d+$/.test(p)) p = '/m' + p
  else if (/^\/asset\/server/.test(p)) p = '/m/asset/server'
  else if (p === '/exec/task') p = '/m/exec'
  else if (p === '/exec/ssh') p = '/m/terminal'
  else if (p === '/exec/cron') p = '/m/exec'
  else if (p === '/alert/history') p = '/m/alerts'
  else if (p === '/alert/rule') p = '/m/alert-rule'
  else if (p === '/log') p = '/m/logs'
  else if (p === '/diagnosis') p = '/m/diagnosis'
  else if (p === '/topology') p = '/m/topology'
  else if (p === '/asset/middleware/elk') p = '/m/elk'
  else if (p === '/settings') p = '/m/settings'
  else if (p === '/system/user') p = '/m/system/user'
  else if (p === '/system/role') p = '/m/system/role'
  else if (p === '/system/menu') p = '/m/system/menu'
  else if (p === '/audit') p = '/m/audit'
  else if (p === '/db') p = '/m/db'
  else if (p === '/kb') p = '/m/kb'
  else if (p === '/asset/app') p = '/m/asset/app'
  else if (p === '/asset/project') p = '/m/asset/project'
  else if (p === '/asset/ftp') p = '/m/asset/ftp'
  else if (p.startsWith('/m')) { /* already mobile */ }
  else p = '/m/home'
  return p + qq
}