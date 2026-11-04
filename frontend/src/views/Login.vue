<template>
  <div class="login-container">
    <div class="login-card">
      <div class="login-title">AI-Ops Platform</div>
      <div class="login-subtitle">智能化运维管理平台</div>

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        size="large"
        @keyup.enter="handleLogin"
      >
        <el-form-item prop="username">
          <el-input
            v-model="form.username"
            placeholder="用户名"
            :prefix-icon="User"
          />
        </el-form-item>

        <el-form-item prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="密码"
            :prefix-icon="Lock"
            show-password
          />
        </el-form-item>

        <el-form-item>
          <el-button
            class="login-btn"
            type="primary"
            size="large"
            :loading="loading"
            @click="handleLogin"
          >
            {{ loading ? '登录中...' : '登 录' }}
          </el-button>
        </el-form-item>

        <div style="text-align: center; color: #999; font-size: 12px">
          默认账号：admin / 123456
        </div>
      </el-form>
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { ElMessage } from 'element-plus'
import { User, Lock } from '@element-plus/icons-vue'
import { preferMobile } from '@/utils/device'
import type { FormInstance, FormRules } from 'element-plus'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const formRef = ref<FormInstance>()
const loading = ref(false)

const form = reactive({
  username: 'admin',
  password: '123456'
})

const rules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 4, message: '密码长度至少4位', trigger: 'blur' }
  ]
}

async function handleLogin() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  loading.value = true
  try {
    await userStore.login(form.username, form.password)
    ElMessage.success('登录成功')
    const redirect = String(route.query.redirect || '')
    if (redirect.startsWith('/')) router.push(redirect)
    else router.push(preferMobile() ? '/m/home' : '/')
  } catch (e: any) {
    // 错误已在请求拦截器中处理
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-container {
  min-height: 100vh;
  min-height: 100dvh;
  padding: 20px 0;
}

.login-card {
  width: min(420px, calc(100vw - 32px)) !important;
  padding: 36px 32px !important;
}

@media (max-width: 480px) {
  .login-card {
    padding: 28px 22px !important;
    border-radius: 16px;
  }

  .login-title {
    font-size: 22px;
  }
}
</style>