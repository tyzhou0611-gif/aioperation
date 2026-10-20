import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { MenuTreeNode, UserBasic } from '@/types'
import request from '@/utils/request'
import router from '@/router'

// Vite 构建时可解析的全部视图组件映射（相对路径 glob，避免动态 import 别名解析失败）
const viewModules = import.meta.glob('../views/**/*.vue')
const routerView = () => import('@/layout/RouterView.vue')

export const useUserStore = defineStore('user', () => {
  // ===== 状态 =====
  const token = ref(localStorage.getItem('token') || '')
  const userInfo = ref<UserBasic | null>(
    JSON.parse(localStorage.getItem('userInfo') || 'null')
  )
  const menus = ref<MenuTreeNode[]>([])
  const permissions = ref<string[]>([])
  const roles = ref<string[]>([])

  // ===== 计算属性 =====
  const isLoggedIn = () => !!token.value

  // ===== 操作 =====

  /** 登录 */
  async function login(username: string, password: string) {
    const res = await request.post('/api/auth/login', { username, password })
    const data = res.data

    // 保存 token
    token.value = data.token
    localStorage.setItem('token', data.token)

    // 保存用户基本信息
    userInfo.value = data.user
    localStorage.setItem('userInfo', JSON.stringify(data.user))

    // 保存菜单和权限
    menus.value = data.menus || []
    permissions.value = data.permissions || []

    // 动态添加路由
    addDynamicRoutes(data.menus)

    return data
  }

  /** 获取用户信息（初始化/刷新时调用） */
  async function fetchUserInfo() {
    try {
      const res = await request.get('/api/auth/info')
      const data = res.data

      userInfo.value = data.user
      localStorage.setItem('userInfo', JSON.stringify(data.user))

      menus.value = data.menus || []
      permissions.value = data.permissions || []
      roles.value = data.roles || []

      // 动态添加路由
      addDynamicRoutes(data.menus)

      return data
    } catch (e) {
      // 获取用户信息失败 → 清除登录状态
      logout()
      throw e
    }
  }

  /** 注销 */
  function logout() {
    token.value = ''
    userInfo.value = null
    menus.value = []
    permissions.value = []
    roles.value = []
    localStorage.removeItem('token')
    localStorage.removeItem('userInfo')
    router.push('/login')
  }

  /** 将后端菜单树转换为 Vue Router 路由并动态添加 */
  function addDynamicRoutes(menuTree: MenuTreeNode[]) {
    const layoutRoute = router.getRoutes().find(r => r.path === '/')

    for (const menu of menuTree) {
      addRouteFromMenu(menu, (layoutRoute?.name as string) || 'Layout')
    }
  }

  function addRouteFromMenu(menu: MenuTreeNode, parentName: string) {
    // 只处理 type=0(目录) 或 type=1(菜单)
    if (menu.type > 1) return

    // 跳过没有 path 的项
    if (!menu.path) return

    // 构建路由记录
    const routeRecord: any = {
      path: menu.path,
      name: `Menu_${menu.id}`,
      meta: {
        title: menu.name,
        icon: menu.icon,
        permission: menu.permission
      }
    }

    // 解析组件：用 glob 映射查找真实组件；找不到则用目录占位组件
    if (menu.component) {
      const key = `../views/${menu.component}.vue`
      routeRecord.component = viewModules[key] || routerView
    } else {
      routeRecord.component = routerView
    }

    // 添加当前路由到父路由
    router.addRoute(parentName, routeRecord)

    // 递归处理子菜单
    if (menu.children && menu.children.length > 0) {
      for (const child of menu.children) {
        addRouteFromMenu(child, routeRecord.name)
      }
    }
  }

  return {
    token,
    userInfo,
    menus,
    permissions,
    roles,
    isLoggedIn,
    login,
    fetchUserInfo,
    logout,
    addDynamicRoutes
  }
})