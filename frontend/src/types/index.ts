/// <reference types="vite/client" />

/** 菜单树节点（对应后端 SysMenuService.MenuTreeNode） */
export interface MenuTreeNode {
  id: number
  parentId: number | null
  name: string
  path: string | null
  component: string | null
  icon: string | null
  sort: number | null
  permission: string | null
  type: number
  children: MenuTreeNode[]
}

/** 用户基本信息 */
export interface UserBasic {
  id: number
  username: string
  nickname: string | null
  email: string | null
  avatar: string | null
  status: number
  createTime: string
}

/** 登录响应 */
export interface LoginResult {
  token: string
  user: UserBasic
  menus: MenuTreeNode[]
  permissions: string[]
}

/** 用户信息响应 */
export interface UserInfoResult {
  user: UserBasic
  roles: string[]
  permissions: string[]
  menus: MenuTreeNode[]
}

/** API 统一响应 */
export interface ApiResult<T> {
  code: number
  msg: string
  data: T
  timestamp: string
}

/** 中间件资产 */
export interface MiddlewareAsset {
  id: number
  name: string
  type: string
  version: string | null
  ip: string | null
  port: number | null
  clusterName: string | null
  env: string
  status: string
  serverId: number | null
  appIds: string | null
  tags: string | null
  createTime: string
}

/** 应用资产 */
export interface AppAsset {
  id: number
  name: string
  code: string
  type: string
  version: string | null
  language: string | null
  deployType: string | null
  env: string
  status: string
  serverId: number | null
  dependsMiddlewareIds: string | null
  dependsAppIds: string | null
  owner: string | null
  tags: string | null
  createTime: string
}

/** 命令模板 */
export interface CommandTemplate {
  id: number
  name: string
  targetType: string
  middlewareType: string | null
  command: string
  description: string | null
  dangerous: number
}

/** 一键命令任务 */
export interface ExecTask {
  taskId: string
  status: string
  output: string
  success: boolean
  targetIp: string | null
}

/** 拓扑图节点 */
export interface TopoNode {
  id: string
  name: string
  category: string
  symbolSize: number
  detail: string
  env?: string | null
  status?: string | null
}

/** 拓扑图边 */
export interface TopoEdge {
  source: string
  target: string
  label: string
  lineStyle: string
  category: number
  origin?: string        // manual | auto
  relationId?: number | null
  sourceKey?: string     // APP:1
  targetKey?: string     // MIDDLEWARE:2
  relationType?: string  // DEPENDS/CALLS/DEPLOY_ON/CONNECTS_TO
}

/** 资产关系（asset_relation） */
export interface Relation {
  id: number
  sourceType: string
  sourceId: number
  sourceName?: string
  targetType: string
  targetId: number
  targetName?: string
  relationType: string
  label: string | null
  description: string | null
  lineStyle: string
}

/** 拓扑图整体 */
export interface TopoGraph {
  nodes: TopoNode[]
  edges: TopoEdge[]
  stats: { appCount: number; middlewareCount: number; serverCount: number; abnormalCount: number }
}

/** 建议执行服务器 */
export interface SuggestedServer {
  id: number
  name: string
  ip: string
  reason: string
}

/** AI 方案代码块 */
export interface CodeBlock {
  language: string
  label: string
  code: string
  suggestedServers?: SuggestedServer[]
}

/** AI 关联资产 */
export interface AssetRef {
  type: string
  name: string
  id: number | null
}

/** AI 智能跳转建议 */
export interface NavigationHint {
  target: string
  params: Record<string, any>
  label: string
  reason?: string | null
  hasWarning?: boolean
  warningMessage?: string | null
}

/** AI 结构化方案 */
export interface Plan {
  sessionId: number | null
  title: string
  summary: string
  steps: string[]
  codeBlocks: CodeBlock[]
  riskLevel: string
  riskTip: string | null
  relatedAssets: AssetRef[]
  navigation?: NavigationHint | null
  mode: string  // LLM | RULE
}

/** 会话 */
export interface ChatSession {
  id: number
  title: string
  username: string | null
  updateTime: string
  messageCount?: number
}

/** 会话消息 */
export interface ChatMessage {
  id: number
  role: string
  content: string
  createTime: string
  plan?: Plan | null
}

/** 上下文资产 */
export interface ContextAsset {
  type: string
  id: number | null
  name?: string
}