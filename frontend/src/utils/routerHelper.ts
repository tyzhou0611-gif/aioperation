import type { Router, RouteLocationRaw } from 'vue-router'
import type { NavigationHint } from '@/types'

/**
 * target → 路由映射（与后端 NavigationTarget 枚举一致）
 * 只保留 params 中真实存在的字段，避免 query 里出现 undefined
 */
function buildRoute(target: string, params: Record<string, any> = {}): RouteLocationRaw | null {
  const q = (obj: Record<string, any>) => {
    const out: Record<string, string> = {}
    Object.keys(obj).forEach(k => { if (obj[k] !== undefined && obj[k] !== null && obj[k] !== '') out[k] = String(obj[k]) })
    return out
  }
  switch (target) {
    case 'SERVER_LIST': return { path: '/asset/server' }
    case 'SERVER_DETAIL': return { path: '/asset/server', query: q({ id: params.id }) }
    case 'MIDDLEWARE_LIST': return { path: '/asset/middleware' }
    case 'MIDDLEWARE_DETAIL': return params.id ? { path: '/asset/middleware/' + params.id } : { path: '/asset/middleware' }
    case 'APP_LIST': return { path: '/asset/app' }
    case 'APP_DETAIL': return { path: '/asset/app', query: q({ id: params.id }) }
    case 'TOPOLOGY': return { path: '/topology', query: q({ focusId: params.focusId ?? params.id, projectId: params.projectId }) }
    case 'ALERT_HISTORY': return { path: '/alert/history', query: q({ serverId: params.serverId }) }
    case 'ALERT_RULE': return { path: '/alert/rule' }
    case 'LOG_MANAGEMENT': return { path: '/log', query: q({ serverId: params.serverId, keyword: params.keyword }) }
    case 'EXEC_TASK': return { path: '/exec/task', query: q({ serverId: params.serverId }) }
    case 'CRON_JOB': return { path: '/exec/cron' }
    case 'DASHBOARD': return { path: '/dashboard' }
    case 'MONITOR_PANEL': return { path: '/dashboard', query: q({ tab: 'panels', instance: params.instance, range: params.range }) }
    case 'BUILTIN_CHARTS': return { path: '/dashboard', query: q({ tab: 'echarts' }) }
    case 'PROJECT_LIST': return { path: '/asset/project' }
    case 'KNOWLEDGE_BASE': return { path: '/kb', query: q({ kbId: params.kbId, docId: params.docId }) }
    case 'DB_WORKBENCH': return { path: '/db', query: q({ connectionId: params.connectionId }) }
    case 'ELK_MANAGEMENT': return { path: '/asset/middleware/elk', query: q({ connectionId: params.connectionId }) }
    case 'SYSTEM_CONFIG_AI': return { path: '/settings', query: { tab: 'ai' } }
    case 'SYSTEM_CONFIG_MAIL': return { path: '/settings', query: { tab: 'notify' } }
    case 'SYSTEM_CONFIG_SOURCE': return { path: '/settings', query: { tab: 'source' } }
    case 'AUDIT_LOG': return { path: '/audit' }
    default: return null
  }
}

/** 手机版 target → 路由映射（AI 跳转在手机版内不会被带到桌面布局） */
function buildMobileRoute(target: string, params: Record<string, any> = {}): RouteLocationRaw | null {
  const q = (obj: Record<string, any>) => {
    const out: Record<string, string> = {}
    Object.keys(obj).forEach(k => { if (obj[k] !== undefined && obj[k] !== null && obj[k] !== '') out[k] = String(obj[k]) })
    return out
  }
  switch (target) {
    case 'SERVER_LIST': return { path: '/m/servers' }
    case 'SERVER_DETAIL': return params.id ? { path: '/m/servers/' + params.id } : { path: '/m/servers' }
    case 'MIDDLEWARE_LIST': return { path: '/m/asset/middleware' }
    case 'MIDDLEWARE_DETAIL': return params.id ? { path: '/m/asset/middleware/' + params.id } : { path: '/m/asset/middleware' }
    case 'APP_LIST': return { path: '/m/asset/app' }
    case 'APP_DETAIL': return { path: '/m/asset/app', query: q({ id: params.id }) }
    case 'TOPOLOGY': return { path: '/m/topology', query: q({ focusId: params.focusId ?? params.id, projectId: params.projectId }) }
    case 'ALERT_HISTORY': return { path: '/m/alerts', query: q({ serverId: params.serverId }) }
    case 'ALERT_RULE': return { path: '/m/alert-rule' }
    case 'LOG_MANAGEMENT': return { path: '/m/logs', query: q({ serverId: params.serverId, keyword: params.keyword }) }
    case 'EXEC_TASK': return { path: '/m/exec', query: q({ serverId: params.serverId }) }
    case 'CRON_JOB': return { path: '/m/exec' }
    case 'DASHBOARD': return { path: '/m/home' }
    case 'MONITOR_PANEL': return { path: '/m/monitor', query: q({ instance: params.instance, range: params.range }) }
    case 'BUILTIN_CHARTS': return { path: '/m/home' }
    case 'PROJECT_LIST': return { path: '/m/asset/project' }
    case 'KNOWLEDGE_BASE': return { path: '/m/kb', query: q({ kbId: params.kbId, docId: params.docId }) }
    case 'DB_WORKBENCH': return { path: '/m/db', query: q({ connectionId: params.connectionId }) }
    case 'ELK_MANAGEMENT': return { path: '/m/elk', query: q({ connectionId: params.connectionId }) }
    case 'SYSTEM_CONFIG_AI': return { path: '/m/settings', query: { tab: 'ai' } }
    case 'SYSTEM_CONFIG_MAIL': return { path: '/m/settings', query: { tab: 'notify' } }
    case 'SYSTEM_CONFIG_SOURCE': return { path: '/m/settings', query: { tab: 'source' } }
    case 'AUDIT_LOG': return { path: '/m/audit' }
    default: return null
  }
}

/** 执行跳转；返回是否成功识别目标 */
export function navigateByTarget(router: Router, nav: NavigationHint): boolean {
  if (!nav || !nav.target) return false
  const mobile = typeof location !== 'undefined'
    && (location.pathname === '/m' || location.pathname.startsWith('/m/'))
  const route = mobile
    ? (buildMobileRoute(nav.target, nav.params || {}) || buildRoute(nav.target, nav.params || {}))
    : buildRoute(nav.target, nav.params || {})
  if (!route) return false
  router.push(route)
  return true
}

/** 目标是否在字典中（用于决定是否渲染按钮） */
export function isKnownTarget(target: string): boolean {
  return !!buildRoute(target, {})
}