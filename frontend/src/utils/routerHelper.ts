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
    case 'MIDDLEWARE_DETAIL': return { path: '/asset/middleware', query: q({ id: params.id, type: params.type }) }
    case 'APP_LIST': return { path: '/asset/app' }
    case 'APP_DETAIL': return { path: '/asset/app', query: q({ id: params.id }) }
    case 'TOPOLOGY': return { path: '/topology', query: q({ focusId: params.focusId ?? params.id }) }
    case 'ALERT_HISTORY': return { path: '/alert/history', query: q({ serverId: params.serverId }) }
    case 'ALERT_RULE': return { path: '/alert/rule' }
    case 'LOG_MANAGEMENT': return { path: '/log', query: q({ serverId: params.serverId, keyword: params.keyword }) }
    case 'EXEC_TASK': return { path: '/exec/task', query: q({ serverId: params.serverId }) }
    case 'CRON_JOB': return { path: '/exec/cron' }
    case 'SYSTEM_CONFIG_AI': return { path: '/settings', query: { tab: 'ai' } }
    case 'SYSTEM_CONFIG_MAIL': return { path: '/settings', query: { tab: 'notify' } }
    case 'AUDIT_LOG': return { path: '/audit' }
    default: return null
  }
}

/** 执行跳转；返回是否成功识别目标 */
export function navigateByTarget(router: Router, nav: NavigationHint): boolean {
  if (!nav || !nav.target) return false
  const route = buildRoute(nav.target, nav.params || {})
  if (!route) return false
  router.push(route)
  return true
}

/** 目标是否在字典中（用于决定是否渲染按钮） */
export function isKnownTarget(target: string): boolean {
  return !!buildRoute(target, {})
}