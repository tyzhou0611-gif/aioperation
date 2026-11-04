<template>
  <div class="m-page">
    <div class="m-page-inner">
      <!-- 用户卡 -->
      <section class="m-card user-card">
        <div class="avatar">{{ initial }}</div>
        <div class="user-main">
          <div class="user-name">{{ userStore.userInfo?.nickname || userStore.userInfo?.username || '运维用户' }}</div>
          <div class="m-hint">{{ userStore.userInfo?.username }} · {{ rolesText }}</div>
        </div>
        <button class="switch-btn" @click="goDesktop">桌面版</button>
      </section>

      <!-- 功能分组 -->
      <section v-for="g in visibleGroups" :key="g.title" class="m-card">
        <div class="m-card-head">
          <div class="m-title">{{ g.title }} <span class="en">{{ g.en }}</span></div>
        </div>
        <div class="more-grid">
          <button v-for="it in g.items" :key="it.label" class="more-item" @click="router.push(it.path)">
            <span class="mi-icon" :style="{ color: it.color, background: it.bg }">
              <el-icon :size="19"><component :is="it.icon" /></el-icon>
            </span>
            <span class="mi-label">{{ it.label }}</span>
          </button>
        </div>
      </section>

      <!-- 设置 -->
      <section class="m-card">
        <div class="setting-row" @click="resetAuto">
          <span>恢复自动识别视图</span>
          <el-icon><Switch /></el-icon>
        </div>
        <div class="setting-row" @click="aboutVisible = true">
          <span>关于 AI-Ops</span>
          <el-icon><InfoFilled /></el-icon>
        </div>
        <div class="setting-row danger" @click="logout">
          <span>退出登录</span>
          <el-icon><SwitchButton /></el-icon>
        </div>
      </section>

      <div class="foot">AI-Ops Platform · MOBILE UI · v1.0.0</div>

      <el-dialog v-model="aboutVisible" title="关于 AI-Ops" width="340px">
        <div class="about">
          <b>AI-Ops Platform</b>
          <p>智能化运维管理平台 · 手机版</p>
          <p class="m-hint">手机版复用原有后端接口，不会改变桌面端任何功能。终端、命令执行等高危操作仍受权限与安全护栏约束。</p>
        </div>
      </el-dialog>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useUserStore } from '@/stores/user'
import { setViewMode } from '@/utils/device'
import type { MenuTreeNode } from '@/types'
import {
  DataLine, Monitor, Cpu, Aim, Bell, Document, Collection, Box, Grid,
  Folder, Files, Coin, Search, Setting, User, Key, Menu, View, Switch,
  InfoFilled, SwitchButton, ChatDotRound
} from '@element-plus/icons-vue'

const router = useRouter()
const userStore = useUserStore()
const aboutVisible = ref(false)

const initial = computed(() => {
  const n = userStore.userInfo?.nickname || userStore.userInfo?.username || 'A'
  return String(n).trim().slice(0, 1).toUpperCase()
})

const rolesText = computed(() => {
  const roles = userStore.roles || []
  if (!roles.length) return '无角色'
  if (userStore.isSuperAdmin) return '超级管理员'
  return roles.join(' / ')
})

function hasMenu(path: string): boolean {
  if (userStore.isSuperAdmin) return true
  const walk = (list: MenuTreeNode[]): boolean => list.some(m => m.path === path || (m.children && walk(m.children)))
  return walk(userStore.menus)
}

type Item = { label: string; path: string; icon: any; color: string; bg: string; menu?: string }

const groups: { title: string; en: string; items: Item[] }[] = [
  {
    title: '运维工具',
    en: 'OPERATIONS',
    items: [
      { label: '监控面板', path: '/m/monitor', icon: DataLine, color: '#1677ff', bg: '#e8f1ff' },
      { label: 'Web 终端', path: '/m/terminal', icon: Monitor, color: '#06b6d4', bg: '#e6f9fc', menu: '/exec/ssh' },
      { label: '命令执行', path: '/m/exec', icon: Cpu, color: '#8b5cf6', bg: '#f2edff', menu: '/exec/task' },
      { label: '智能诊断', path: '/m/diagnosis', icon: Aim, color: '#f59e0b', bg: '#fff4e5', menu: '/diagnosis' },
      { label: 'AI 助手', path: '/m/ai', icon: ChatDotRound, color: '#0ea5e9', bg: '#e6f6fe' },
      { label: '日志查询', path: '/m/logs', icon: Document, color: '#10b981', bg: '#e7f8f1', menu: '/log' }
    ]
  },
  {
    title: '监控告警',
    en: 'ALERTING',
    items: [
      { label: '告警历史', path: '/m/alerts', icon: Bell, color: '#ef4444', bg: '#fdecec', menu: '/alert/history' },
      { label: '告警规则', path: '/m/alert-rule', icon: Setting, color: '#f97316', bg: '#fff1e6', menu: '/alert/rule' }
    ]
  },
  {
    title: '资产管理',
    en: 'ASSETS',
    items: [
      { label: '服务器', path: '/m/servers', icon: Monitor, color: '#1677ff', bg: '#e8f1ff', menu: '/asset/server' },
      { label: '中间件', path: '/m/asset/middleware', icon: Box, color: '#8b5cf6', bg: '#f2edff', menu: '/asset/middleware' },
      { label: '应用管理', path: '/m/asset/app', icon: Grid, color: '#06b6d4', bg: '#e6f9fc', menu: '/asset/app' },
      { label: '项目管理', path: '/m/asset/project', icon: Folder, color: '#f59e0b', bg: '#fff4e5', menu: '/asset/project' },
      { label: 'FTP 资产', path: '/m/asset/ftp', icon: Files, color: '#10b981', bg: '#e7f8f1', menu: '/asset/ftp' },
      { label: '智能大屏', path: '/m/topology', icon: View, color: '#0ea5e9', bg: '#e6f6fe', menu: '/topology' }
    ]
  },
  {
    title: '数据与知识',
    en: 'DATA',
    items: [
      { label: 'SQL 工作台', path: '/m/db', icon: Coin, color: '#f59e0b', bg: '#fff4e5', menu: '/db' },
      { label: 'ELK 日志', path: '/m/elk', icon: Search, color: '#8b5cf6', bg: '#f2edff', menu: '/asset/middleware/elk' },
      { label: '知识库', path: '/m/kb', icon: Collection, color: '#10b981', bg: '#e7f8f1', menu: '/kb' }
    ]
  },
  {
    title: '系统管理',
    en: 'SYSTEM',
    items: [
      { label: '用户管理', path: '/m/system/user', icon: User, color: '#1677ff', bg: '#e8f1ff', menu: '/system/user' },
      { label: '角色管理', path: '/m/system/role', icon: Key, color: '#8b5cf6', bg: '#f2edff', menu: '/system/role' },
      { label: '菜单管理', path: '/m/system/menu', icon: Menu, color: '#06b6d4', bg: '#e6f9fc', menu: '/system/menu' },
      { label: '审计日志', path: '/m/audit', icon: Document, color: '#64748b', bg: '#f1f5f9', menu: '/audit' },
      { label: '系统设置', path: '/m/settings', icon: Setting, color: '#f59e0b', bg: '#fff4e5', menu: '/settings' }
    ]
  }
]

const visibleGroups = computed(() => groups
  .map(g => ({ ...g, items: g.items.filter(i => !i.menu || hasMenu(i.menu)) }))
  .filter(g => g.items.length))

function goDesktop() {
  setViewMode('desktop')
  router.push('/dashboard')
}

function resetAuto() {
  setViewMode('auto')
  ElMessage.success('已恢复自动识别，下次打开按设备自动选择')
}

async function logout() {
  try {
    await ElMessageBox.confirm('确认退出登录？', '提示', { type: 'warning', confirmButtonText: '退出', cancelButtonText: '取消' })
    userStore.logout()
  } catch { /* cancel */ }
}
</script>

<style scoped>
.user-card {
  display: flex;
  align-items: center;
  gap: 12px;
}

.avatar {
  width: 50px;
  height: 50px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-size: 20px;
  font-weight: 700;
  background: linear-gradient(135deg, #1677ff, #06b6d4);
  box-shadow: 0 6px 16px rgba(22, 119, 255, 0.28);
  flex: 0 0 auto;
}

.user-main {
  flex: 1;
  min-width: 0;
}

.user-name {
  font-size: 16px;
  font-weight: 700;
  color: var(--m-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.switch-btn {
  border: 1px solid var(--m-border);
  background: #f8fafd;
  color: #475569;
  font-size: 11.5px;
  padding: 6px 10px;
  border-radius: 9px;
  cursor: pointer;
}

.more-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px 6px;
}

.more-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  border: none;
  background: transparent;
  padding: 2px 0;
  cursor: pointer;
  -webkit-tap-highlight-color: transparent;
}

.mi-icon {
  width: 42px;
  height: 42px;
  border-radius: 13px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.mi-label {
  font-size: 11.5px;
  color: #475569;
  line-height: 1.2;
  text-align: center;
}

.more-item:active .mi-icon {
  transform: scale(0.94);
}

.setting-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 2px;
  border-bottom: 1px solid #f1f5f9;
  font-size: 13.5px;
  color: #334155;
  cursor: pointer;
}

.setting-row:last-child {
  border-bottom: none;
}

.setting-row .el-icon {
  color: #b6c2d1;
}

.setting-row.danger {
  color: #d92d2d;
}

.setting-row.danger .el-icon {
  color: #e57373;
}

.foot {
  text-align: center;
  font-size: 9.5px;
  letter-spacing: 1.2px;
  color: var(--m-muted);
  padding: 4px 0 10px;
  font-family: Consolas, monospace;
}

.about b {
  font-size: 15px;
  color: #1f2a37;
}

.about p {
  margin: 6px 0 0;
  font-size: 12.5px;
  color: #475569;
  line-height: 1.6;
}
</style>