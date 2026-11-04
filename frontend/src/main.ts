import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'

import App from './App.vue'
import router from './router'
import { useUserStore } from '@/stores/user'
import { permission } from '@/directives/permission'
import './styles/index.scss'
import './styles/mobile.scss'

async function bootstrap() {
  const app = createApp(App)
  const pinia = createPinia()
  app.use(pinia)

  // 注册所有 Element Plus 图标
  for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
    app.component(key, component)
  }

  // 关键：刷新深层路由（如 /asset/server）时，先加载用户信息并注册动态路由，
  // 否则 router 的首次导航在动态路由注册前就已完成，会匹配不到页面而白屏。
  if (localStorage.getItem('token')) {
    const userStore = useUserStore(pinia)
    try {
      await userStore.fetchUserInfo()
    } catch {
      // 失败时由路由守卫统一跳转登录
    }
  }

  app.use(router)
  app.use(ElementPlus, { size: 'default', locale: zhCn })
  app.directive('permission', permission)
  app.mount('#app')
}

bootstrap()