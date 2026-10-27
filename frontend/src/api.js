// 统一同源会话、CSRF 与 SSE；模型凭据永远不传给浏览器。
const API_BASE = import.meta.env.BASE_URL + 'api'

async function csrf() {
  const response = await fetch(API_BASE + '/auth/csrf', { credentials: 'same-origin' })
  if (!response.ok) throw new Error('无法校验请求，请稍后再试')
  return response.json()
}

async function checked(response) {
  if (response.ok) return response
  let detail = {}
  try { detail = await response.json() } catch {}
  const error = new Error(detail.message || '请求未完成，请稍后重试')
  error.status = response.status
  throw error
}

export async function api(path, { method = 'GET', body, form = false } = {}) {
  const headers = {}
  if (method !== 'GET') {
    const token = await csrf()
    headers[token.headerName] = token.token
  }
  if (body !== undefined) headers['Content-Type'] = form ? 'application/x-www-form-urlencoded' : 'application/json'
  const response = await checked(await fetch(API_BASE + path, {
    method, headers, credentials: 'same-origin',
    body: body === undefined ? undefined : form ? new URLSearchParams(body) : JSON.stringify(body)
  }))
  if (response.status === 204 || response.headers.get('content-length') === '0') return null
  const text = await response.text()
  return text ? JSON.parse(text) : null
}

export async function streamMessage(conversationId, content, onEvent) {
  const token = await csrf()
  const controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(), 155000)
  let reader
  let completed = false
  try {
    const response = await checked(await fetch(API_BASE + '/conversations/' + conversationId + '/messages', {
      method: 'POST', credentials: 'same-origin', signal: controller.signal,
      headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream, application/json', [token.headerName]: token.token },
      body: JSON.stringify({ content })
    }))
    if (!response.body) throw new Error('浏览器无法读取流式回复')
    reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    const dispatch = (block) => {
      const lines = block.split(/\r?\n/)
      const type = lines.find(line => line.startsWith('event:'))?.slice(6).trim()
      const data = lines.filter(line => line.startsWith('data:')).map(line => line.slice(5).trimStart()).join('\n')
      if (!type || !data) return
      const payload = JSON.parse(data)
      onEvent(type, payload)
      if (type === 'done') completed = true
      if (type === 'error') throw new Error(payload.message || '回复未完成')
    }
    while (true) {
      const { done, value } = await reader.read()
      buffer += done ? decoder.decode() : decoder.decode(value, { stream: true })
      const blocks = buffer.split(/\r?\n\r?\n/)
      buffer = blocks.pop() || ''
      blocks.forEach(dispatch)
      if (done) break
    }
    if (buffer.trim()) dispatch(buffer)
    if (!completed) throw new Error('连接已中断，输入已保留，可重试')
  } finally {
    clearTimeout(timeout)
    if (reader) {
      await reader.cancel().catch(() => {})
      reader.releaseLock()
    }
  }
}
