import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'
import { useUserStore } from '@/stores/user'

// 静态路由（无需登录即可访问）
const staticRoutes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/Login.vue'),
    meta: { title: '登录', noAuth: true }
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
      }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes: staticRoutes
})

// 路由守卫：Token 校验 + 动态菜单加载
const whiteList = ['/login']

router.beforeEach(async (to, _from, next) => {
  // 设置页面标题
  document.title = `${to.meta.title || 'AI-Ops'} · AI-Ops Platform`

  const token = localStorage.getItem('token')

  if (token) {
    if (to.path === '/login') {
      // 已登录 → 重定向到首页
      next('/')
    } else {
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
      next()
    }
  } else {
    // 未登录 → 白名单路径放行，其余跳转登录
    if (whiteList.includes(to.path)) {
      next()
    } else {
      next('/login')
    }
  }
})

export default router