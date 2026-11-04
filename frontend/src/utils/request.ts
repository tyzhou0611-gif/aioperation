import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '@/router'

// 后端 API 根地址（前后端分离）：
// - 留空：同源部署，由 Nginx 把 /api 反向代理到后端
// - 非空：跨域直连后端，例如 http://192.0.2.6:8181
const apiBase = (import.meta.env.VITE_API_BASE_URL || '').replace(/\/+$/, '')

const request = axios.create({
  baseURL: apiBase,
  timeout: 30000
})

// 请求拦截器：自动携带 Token
request.interceptors.request.use(
  (config) => {
    const token = localStorage.getItem('token')
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  (error) => Promise.reject(error)
)

// 响应拦截器：统一错误处理
request.interceptors.response.use(
  (response) => {
    const res = response.data
    // 后端返回了错误码
    if (res.code && res.code !== 200) {
      ElMessage.error(res.msg || '请求失败')
      return Promise.reject(new Error(res.msg || '请求失败'))
    }
    return res
  },
  (error) => {
    if (error.response) {
      const { status, data } = error.response

      switch (status) {
        case 401:
          // Token 过期或未登录 → 清除本地信息 → 跳转登录
          localStorage.removeItem('token')
          localStorage.removeItem('userInfo')
          router.push('/login')
          ElMessage.error('登录已过期，请重新登录')
          break
        case 403:
          ElMessage.error('权限不足，无法访问')
          break
        case 404:
          ElMessage.error('请求的资源不存在')
          break
        case 500:
          ElMessage.error(data?.msg || '服务器内部错误')
          break
        default:
          ElMessage.error(`请求失败 (${status})`)
      }
    } else if (error.message.includes('timeout')) {
      ElMessage.error('请求超时，请检查网络')
    } else {
      ElMessage.error('网络异常，请稍后重试')
    }
    return Promise.reject(error)
  }
)

export default request