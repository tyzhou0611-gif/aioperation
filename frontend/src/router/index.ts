import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { preferMobile, setViewMode } from '@/utils/device'

/** 手机版通用嵌入页：直接复用现有桌面页面组件 */
const embedded = () => import('@/views/mobile/Embedded.vue')

// 静态路由（无需登录即可访问）
const staticRoutes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/Login.vue'),
    meta: { title: '登录', noAuth: true }
  },
  // 兼容旧地址：ELK 管理已迁移到资产管理 → 中间件管理下
  {
    path: '/elk',
    redirect: '/asset/middleware/elk',
    meta: { title: 'ELK管理' }
  },
  {
    path: '/',
    name: 'Layout',
    component: () => import('@/layout/index.vue'),
    redirect: '/dashboard',
    meta: { title: '首页' },
    children: [
      {
        path: 'dashboard',
        name: 'Dashboard',
        component: () => import('@/views/Dashboard.vue'),
        meta: { title: '仪表盘', icon: 'Odometer' }
      },
      {
        // 中间件实例详情页（Kafka/ZK/Redis/Hadoop 等），静态路由不受动态菜单影响
        path: 'asset/middleware/:id',
        name: 'MiddlewareDetail',
        component: () => import('@/views/asset/middleware/detail/index.vue'),
        meta: { title: '中间件详情' }
      }
    ]
  },

  // ==========================================================================
  // 手机版（/m）
  // 核心运维页面为手机专属交互；其余模块通过 Embedded 复用现有桌面页面，
  // 桌面端原有路由与功能完全不受影响。
  // ==========================================================================
  {
    path: '/m',
    name: 'MobileLayout',
    component: () => import('@/layout/MobileLayout.vue'),
    redirect: '/m/home',
    children: [
      { path: 'home', name: 'MobileHome', component: () => import('@/views/mobile/Home.vue'), meta: { title: '总览' } },
      { path: 'servers', name: 'MobileServers', component: () => import('@/views/mobile/Servers.vue'), meta: { title: '主机' } },
      { path: 'servers/:id', name: 'MobileServerDetail', component: () => import('@/views/mobile/ServerDetail.vue'), meta: { title: '主机详情' } },
      { path: 'alerts', name: 'MobileAlerts', component: () => import('@/views/mobile/Alerts.vue'), meta: { title: '告警' } },
      { path: 'monitor', name: 'MobileMonitor', component: () => import('@/views/mobile/Monitor.vue'), meta: { title: '监控面板' } },
      { path: 'terminal/:serverId?', name: 'MobileTerminal', component: () => import('@/views/mobile/Terminal.vue'), meta: { title: 'Web 终端', hideTabbar: true } },
      { path: 'exec', name: 'MobileExec', component: () => import('@/views/mobile/Exec.vue'), meta: { title: '命令执行' } },
      { path: 'diagnosis', name: 'MobileDiagnosis', component: () => import('@/views/mobile/Diagnosis.vue'), meta: { title: '智能诊断' } },
      { path: 'logs', name: 'MobileLogs', component: () => import('@/views/mobile/Logs.vue'), meta: { title: '日志查询' } },
      { path: 'kb', name: 'MobileKb', component: () => import('@/views/mobile/Kb.vue'), meta: { title: '知识库' } },
      { path: 'ai', name: 'MobileAi', component: () => import('@/views/mobile/Ai.vue'), meta: { title: '小维 · AI 助手', hideTabbar: true } },
      { path: 'more', name: 'MobileMore', component: () => import('@/views/mobile/More.vue'), meta: { title: '更多' } },

      // 其余模块复用桌面页面
      { path: 'asset/server', name: 'MobileAssetServer', component: embedded, meta: { title: '服务器资产', view: 'asset/server/index' } },
      { path: 'asset/middleware', name: 'MobileAssetMiddleware', component: embedded, meta: { title: '中间件', view: 'asset/middleware/index' } },
      { path: 'asset/middleware/:id', name: 'MobileAssetMiddlewareDetail', component: embedded, meta: { title: '中间件详情', view: 'asset/middleware/detail/index' } },
      { path: 'asset/app', name: 'MobileAssetApp', component: embedded, meta: { title: '应用管理', view: 'asset/app/index' } },
      { path: 'asset/project', name: 'MobileAssetProject', component: embedded, meta: { title: '项目管理', view: 'asset/project/index' } },
      { path: 'asset/ftp', name: 'MobileAssetFtp', component: embedded, meta: { title: 'FTP 资产', view: 'asset/ftp/index' } },
      { path: 'alert-rule', name: 'MobileAlertRule', component: embedded, meta: { title: '告警规则', view: 'alert/rule/index' } },
      { path: 'db', name: 'MobileDb', component: embedded, meta: { title: 'SQL 工作台', view: 'db/index' } },
      { path: 'elk', name: 'MobileElk', component: embedded, meta: { title: 'ELK 管理', view: 'elk/index' } },
      { path: 'topology', name: 'MobileTopology', component: embedded, meta: { title: '智能大屏', view: 'topology/index', embeddedHint: true } },
      { path: 'system/user', name: 'MobileSystemUser', component: embedded, meta: { title: '用户管理', view: 'system/user/index' } },
      { path: 'system/role', name: 'MobileSystemRole', component: embedded, meta: { title: '角色管理', view: 'system/role/index' } },
      { path: 'system/menu', name: 'MobileSystemMenu', component: embedded, meta: { title: '菜单管理', view: 'system/menu/index' } },
      { path: 'audit', name: 'MobileAudit', component: embedded, meta: { title: '审计日志', view: 'audit/index' } },
      { path: 'settings', name: 'MobileSettings', component: embedded, meta: { title: '系统设置', view: 'settings/index' } }
    ]
  },
  { path: '/mobile', redirect: '/m/home' }
]

const router = createRouter({
  history: createWebHistory(),
  routes: staticRoutes
})

// 路由守卫：Token 校验 + 动态菜单加载 + 手机版跳转
const whiteList = ['/login']

router.beforeEach(async (to, _from, next) => {
  // 设置页面标题
  document.title = `${to.meta.title || 'AI-Ops'} · AI-Ops Platform`

  const token = localStorage.getItem('token')

  if (token) {
    // 手工切换到手机版：任意页面 ?mobile=1
    if (String(to.query.mobile || '') === '1') {
      setViewMode('mobile')
      next('/m/home')
      return
    }
    // 手工切换到桌面版：手机版页面 ?desktop=1
    if (String(to.query.desktop || '') === '1') {
      setViewMode('desktop')
      next(to.path.startsWith('/m') ? '/dashboard' : to.path)
      return
    }

    if (to.path === '/login') {
      const redirect = String(to.query.redirect || '')
      if (redirect.startsWith('/')) next(redirect)
      else next(preferMobile() ? '/m/home' : '/')
      return
    }

    const userStore = useUserStore()
    // 如果用户信息尚未加载（刷新页面后），先获取用户信息
    if (!userStore.userInfo || userStore.menus.length === 0) {
      try {
        await userStore.fetchUserInfo()
      } catch {
        // 获取失败 → token 无效 → 跳转登录
        next('/login')
        return
      }
    }

    // 手机设备访问首页/仪表盘时自动进入手机版（可通过 ?desktop=1 或「更多 → 桌面版」关闭）
    if (preferMobile() && (to.path === '/' || to.path === '/dashboard')) {
      next('/m/home')
      return
    }

    // 兜底：动态路由是在 fetchUserInfo 之后才注册的，
    // 首屏导航若在注册前已完成匹配，这里重新解析一次。
    if (to.matched.length === 0) {
      const resolved = router.resolve(to.fullPath)
      if (resolved.matched.length > 0) {
        next({ path: to.fullPath, replace: true })
      } else {
        next(preferMobile() ? '/m/home' : '/dashboard')
      }
      return
    }
    next()
  } else {
    // 未登录 → 白名单路径放行；手机版页面带 redirect 回登录页
    if (whiteList.includes(to.path)) {
      next()
    } else if (to.path === '/m' || to.path.startsWith('/m/')) {
      next(`/login?redirect=${encodeURIComponent(to.fullPath)}`)
    } else {
      next('/login')
    }
  }
})

export default router