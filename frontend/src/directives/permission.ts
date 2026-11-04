import type { Directive } from 'vue'
import { useUserStore } from '@/stores/user'

/**
 * v-permission="'asset:server:edit'"
 * v-permission="['asset:server:edit', 'asset:server:delete']"  // 满足任意一个即可
 *
 * 超级管理员（permissions 含 * 或 ROLE_SUPER_ADMIN）始终放行。
 * 不满足时直接移除元素，避免只读角色看到写操作入口。
 */
export const permission: Directive<HTMLElement, string | string[]> = {
  mounted(el, binding) {
    const store = useUserStore()
    const need = Array.isArray(binding.value) ? binding.value : [binding.value]
    const ok = store.isSuperAdmin
      || store.permissions.includes('*')
      || need.filter(Boolean).some(code => store.permissions.includes(code))
    if (!ok && el.parentNode) {
      el.parentNode.removeChild(el)
    }
  }
}