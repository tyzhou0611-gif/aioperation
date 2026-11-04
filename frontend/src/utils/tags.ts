/** 资产标签工具：统一 JSON 数组存储，兼容旧的逗号分隔字符串 */

export const MAX_TAGS = 30
export const MAX_TAG_LENGTH = 50
export const MAX_TAGS_JSON_LENGTH = 500

/** 把后端 tags 字段解析为数组（JSON 数组 / 逗号分隔 / 空） */
export function parseTags(raw: any): string[] {
  if (raw == null) return []
  if (Array.isArray(raw)) return normalizeTags(raw.map(String))
  const text = String(raw).trim()
  if (!text) return []
  if (text.startsWith('[')) {
    try {
      const arr = JSON.parse(text)
      if (Array.isArray(arr)) return normalizeTags(arr.map(String))
    } catch { /* 回退为逗号分隔 */ }
  }
  return normalizeTags(text.split(/[,，;；]/))
}

export function normalizeTags(tags: string[]): string[] {
  const out: string[] = []
  for (const t of tags) {
    const v = String(t || '').trim()
    if (!v) continue
    if (!out.includes(v)) out.push(v)
  }
  return out
}

/** 保存前校验；返回错误文案，null 表示通过 */
export function validateTags(tags: string[]): string | null {
  const list = normalizeTags(tags)
  if (list.length > MAX_TAGS) return `标签最多 ${MAX_TAGS} 个`
  for (const t of list) {
    if (t.length > MAX_TAG_LENGTH) return `单个标签不能超过 ${MAX_TAG_LENGTH} 个字符：${t}`
  }
  if (JSON.stringify(list).length > MAX_TAGS_JSON_LENGTH) {
    return `标签总长度过长（最多 ${MAX_TAGS_JSON_LENGTH} 字符），请精简后再保存`
  }
  return null
}

/** 数组 → 后端存储的 JSON 字符串；空数组返回空串 */
export function tagsToJson(tags: string[]): string {
  const list = normalizeTags(tags)
  return list.length ? JSON.stringify(list) : ''
}