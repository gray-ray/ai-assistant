/**
 * axios 请求封装
 */
import axios, {
  type AxiosInstance,
  type AxiosRequestConfig,
  type AxiosResponse,
  type InternalAxiosRequestConfig,
} from 'axios'
import { API_BASE_URL, REQUEST_TIMEOUT } from './config'

// ========== 类型定义 ==========

export interface ApiResponse<T = unknown> {
  code: number
  data: T
  message: string
}

// ========== axios 实例 ==========

const instance: AxiosInstance = axios.create({
  baseURL: API_BASE_URL,
  timeout: REQUEST_TIMEOUT,
  headers: {
    'Content-Type': 'application/json',
  },
})

// ========== 请求拦截器 ==========

instance.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
    // 添加 token 等通用请求头
    const token = localStorage.getItem('token')
    if (token && config.headers) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  (error) => Promise.reject(error),
)

// ========== 响应拦截器 ==========

instance.interceptors.response.use(
  (response: AxiosResponse<ApiResponse>) => {
    const { data } = response
    // 如果业务码不是 0/200，可以在这里统一处理错误
    if (data && typeof data === 'object' && 'code' in data) {
      if (data.code !== 0 && data.code !== 200) {
        // 统一业务错误提示
        console.error(`[API Error] ${data.code}: ${data.message}`)
        return Promise.reject(new Error(data.message || '请求失败'))
      }
      return response
    }
    return response
  },
  (error) => {
    if (error.response) {
      const { status } = error.response
      switch (status) {
        case 401:
          console.error('[401] 未登录或登录已过期')
          // TODO: 跳转登录
          break
        case 403:
          console.error('[403] 没有权限')
          break
        case 404:
          console.error('[404] 资源不存在')
          break
        case 500:
          console.error('[500] 服务器错误')
          break
        default:
          console.error(`[${status}] ${error.message}`)
      }
    } else if (error.code === 'ECONNABORTED') {
      console.error('[Timeout] 请求超时')
    } else {
      console.error('[Network] 网络错误')
    }
    return Promise.reject(error)
  },
)

// ========== 封装常用方法 ==========

const request = {
  get<T = unknown>(url: string, config?: AxiosRequestConfig): Promise<T> {
    return instance.get<ApiResponse<T>>(url, config).then((res) => res.data.data as T)
  },

  post<T = unknown>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
    return instance.post<ApiResponse<T>>(url, data, config).then((res) => res.data.data as T)
  },

  put<T = unknown>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
    return instance.put<ApiResponse<T>>(url, data, config).then((res) => res.data.data as T)
  },

  delete<T = unknown>(url: string, config?: AxiosRequestConfig): Promise<T> {
    return instance.delete<ApiResponse<T>>(url, config).then((res) => res.data.data as T)
  },

  // 原始 axios 实例（用于特殊场景）
  instance,
}

export default request
