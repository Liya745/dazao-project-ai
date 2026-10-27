<script setup>
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { api, streamMessage } from './api'

// 草稿、角色资产和会话快照分别维护；浏览器不保存模型密钥。
const fields = [
  { key: 'name', label: '角色名称', max: 80, placeholder: '给 TA 一个名字', short: true },
  { key: 'speechStyle', label: '表达风格', max: 2000, placeholder: '措辞、语气、回复长度' },
  { key: 'description', label: '身份与背景', max: 6000, placeholder: 'TA 是谁，从哪里来，又想去哪里？', full: true },
  { key: 'personality', label: '性格与习惯', max: 3000, placeholder: '比起“温柔”，具体的行为更能定义一个角色' },
  { key: 'scenario', label: '互动场景', max: 3000, placeholder: '你们会在怎样的世界里相遇？' },
  { key: 'firstMessage', label: '开场白', max: 2000, placeholder: '第一次见面，TA 会对你说什么？', full: true },
  { key: 'messageExample', label: '示例对白', max: 4000, placeholder: '用户：……\n角色：……' },
  { key: 'appearance', label: '外观描述', max: 2000, placeholder: '外貌、服饰、标志性细节；首版以文字记录' }
]
const types = { original: '原创角色', ip: '已有 IP', assistant: '功能角色' }
const empty = () => ({ name: '', description: '', personality: '', scenario: '', firstMessage: '', messageExample: '', appearance: '', speechStyle: '', sourceType: 'original', work: '', canonVersion: '' })
const draft = reactive(empty())
const brief = ref('')
const locked = ref([])
const selected = ref([])
const editing = ref(null)
const savedDraft = ref(JSON.stringify(empty()))
const dirty = computed(() => JSON.stringify(draft) !== savedDraft.value)
const view = ref('studio')
const session = reactive({ authenticated: false, username: '' })
const status = reactive({ aiConfigured: false, model: '' })
const characters = ref([])
const conversations = ref([])
const search = ref('')
const filtered = computed(() => characters.value.filter(c => (c.definition.name + c.definition.description).toLowerCase().includes(search.value.toLowerCase())))
const busy = ref('')
const notice = ref('')
const toast = reactive({ message: '', error: false })
let toastTimer
const modal = ref('')
watch(modal, async value => {
  if (!value) return
  await nextTick()
  document.querySelector('.modal input, .modal button')?.focus()
})
const authMode = ref('login')
const credentials = reactive({ username: '', password: '' })
const authBusy = ref(false)
const authError = ref('')
const currentConversation = ref(null)
const turns = ref([])
const message = ref('')
const streamingReply = ref('')
const streamingInput = ref('')
const messagesElement = ref(null)
const title = computed(() => ({ studio: '让想象，有自己的声音。', library: '收藏每一个，独特的灵魂。', chat: '从一句你好，开始认识。' })[view.value])
const navTitle = computed(() => ({ studio: '角色工作室', library: '我的角色', chat: '角色试聊' })[view.value])

function notify(text, error = false) {
  clearTimeout(toastTimer)
  toast.message = text
  toast.error = error
  toastTimer = setTimeout(() => { toast.message = '' }, 6000)
}
function requireLogin() {
  if (session.authenticated) return true
  modal.value = 'auth'
  authError.value = ''
  return false
}
function handleError(error) {
  if (error.status === 401) {
    session.authenticated = false
    session.username = ''
    characters.value = []
    conversations.value = []
    currentConversation.value = null
    turns.value = []
    modal.value = 'auth'
  }
  notify(error.name === 'AbortError' ? '请求超时，输入已保留，请稍后重试' : error.message, true)
}
async function refreshAssets() {
  if (!session.authenticated) return
  const [roles, chats] = await Promise.all([api('/characters'), api('/conversations')])
  characters.value = roles
  conversations.value = chats
}
async function authenticate() {
  authBusy.value = true
  authError.value = ''
  try {
    if (authMode.value === 'register') await api('/auth/register', { method: 'POST', body: credentials })
    await api('/auth/login', { method: 'POST', form: true, body: credentials })
    Object.assign(session, await api('/auth/session'))
    credentials.password = ''
    modal.value = ''
    await refreshAssets()
    notify('欢迎来到角色工作室')
  } catch (error) { authError.value = error.message }
  finally { authBusy.value = false }
}
async function logout() {
  if (busy.value || (dirty.value && !window.confirm('草稿尚未保存，确定退出账号？'))) return
  try {
    await api('/auth/logout', { method: 'POST' })
    Object.assign(session, { authenticated: false, username: '' })
    characters.value = []
    conversations.value = []
    currentConversation.value = null
    turns.value = []
    resetDraft()
    view.value = 'studio'
  } catch (error) { handleError(error) }
}
function navigate(next) {
  if (busy.value) return
  if (next !== 'studio' && !requireLogin()) return
  view.value = next
}
function resetDraft() {
  Object.assign(draft, empty())
  editing.value = null
  brief.value = ''
  locked.value = []
  selected.value = []
  savedDraft.value = JSON.stringify(draft)
  notice.value = ''
}
function newCharacter() {
  if (busy.value || (dirty.value && !window.confirm('新建将清空未保存的草稿，确定继续？'))) return
  resetDraft()
  view.value = 'studio'
}
function editCharacter(character) {
  if (busy.value || (dirty.value && !window.confirm('当前草稿尚未保存，确定打开另一个角色？'))) return
  Object.assign(draft, empty(), character.definition)
  editing.value = { id: character.id, version: character.version }
  savedDraft.value = JSON.stringify(draft)
  brief.value = ''
  locked.value = []
  selected.value = []
  notice.value = ''
  view.value = 'studio'
}
function toggleLock(key) {
  locked.value = locked.value.includes(key) ? locked.value.filter(f => f !== key) : [...locked.value, key]
}
async function generate(partial = false) {
  if (busy.value || !requireLogin()) return
  if (!brief.value.trim()) { notify('先描述你想要的角色或修改要求', true); return }
  if (draft.sourceType === 'ip' && !draft.work.trim()) { notify('已有 IP 角色请填写作品名称', true); return }
  if (partial && !selected.value.length) { notify('请勾选需要重生成的字段', true); return }
  busy.value = 'generate'
  notice.value = ''
  try {
    const result = await api('/characters/generate', { method: 'POST', body: {
      brief: brief.value, current: { ...draft }, selectedFields: partial ? selected.value : fields.map(f => f.key),
      lockedFields: locked.value, sourceType: draft.sourceType, work: draft.work, canonVersion: draft.canonVersion
    } })
    Object.assign(draft, result)
    notice.value = '草稿已生成。检查设定后保存，也可以锁定满意的字段继续打磨。'
  } catch (error) { handleError(error) }
  finally { busy.value = '' }
}
async function saveCharacter() {
  if (busy.value || !requireLogin()) return
  busy.value = 'save'
  try {
    const result = await api(editing.value ? '/characters/' + editing.value.id : '/characters', {
      method: editing.value ? 'PUT' : 'POST',
      body: editing.value ? { version: editing.value.version, definition: { ...draft } } : { ...draft }
    })
    editing.value = { id: result.id, version: result.version }
    savedDraft.value = JSON.stringify(draft)
    await refreshAssets()
    notify('角色已保存 · v' + result.version)
  } catch (error) { handleError(error) }
  finally { busy.value = '' }
}
async function copyCharacter(character) {
  if (busy.value) return
  busy.value = 'copy'
  try {
    await api('/characters/' + character.id + '/copy', { method: 'POST' })
    await refreshAssets()
    notify('已复制为一个独立角色')
  } catch (error) { handleError(error) }
  finally { busy.value = '' }
}
async function deleteCharacter(character) {
  if (busy.value || !window.confirm('删除“' + character.definition.name + '”及其全部会话？此操作不可恢复。')) return
  busy.value = 'delete'
  try {
    await api('/characters/' + character.id, { method: 'DELETE' })
    if (editing.value?.id === character.id) resetDraft()
    if (currentConversation.value?.characterId === character.id) { currentConversation.value = null; turns.value = [] }
    await refreshAssets()
    notify('角色已删除')
  } catch (error) { handleError(error) }
  finally { busy.value = '' }
}
async function exportCharacter(character) {
  try {
    const card = await api('/characters/' + character.id + '/export')
    const url = URL.createObjectURL(new Blob([JSON.stringify(card, null, 2)], { type: 'application/json' }))
    const link = document.createElement('a')
    link.href = url
    link.download = character.definition.name.replace(/[\\/:*?"<>|]/g, '_') + '.card.json'
    link.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  } catch (error) { handleError(error) }
}
async function openConversation(id) {
  const result = await api('/conversations/' + id)
  currentConversation.value = result.conversation
  turns.value = result.turns
  message.value = ''
  view.value = 'chat'
  await scrollChat()
}
async function selectConversation(id) {
  if (busy.value) return
  busy.value = 'load'
  try { await openConversation(id) } catch (error) { handleError(error) }
  finally { busy.value = '' }
}
async function startChat(character) {
  if (busy.value || !requireLogin()) return
  if (!character) {
    if (!editing.value || dirty.value) { notify('请先保存当前角色，再开始试聊', true); return }
    character = characters.value.find(c => c.id === editing.value.id)
  }
  busy.value = 'load'
  try {
    const chat = await api('/conversations', { method: 'POST', body: { characterId: character.id } })
    await refreshAssets()
    await openConversation(chat.id)
  } catch (error) { handleError(error) }
  finally { busy.value = '' }
}
async function removeConversation() {
  if (!currentConversation.value || busy.value || !window.confirm('删除当前会话及聊天记录？')) return
  try {
    await api('/conversations/' + currentConversation.value.id, { method: 'DELETE' })
    currentConversation.value = null
    turns.value = []
    await refreshAssets()
  } catch (error) { handleError(error) }
}
async function scrollChat() {
  await nextTick()
  if (messagesElement.value) messagesElement.value.scrollTop = messagesElement.value.scrollHeight
}
async function sendMessage() {
  if (busy.value || !currentConversation.value || !message.value.trim()) return
  busy.value = 'stream'
  streamingInput.value = message.value
  streamingReply.value = ''
  const id = currentConversation.value.id
  try {
    await streamMessage(id, message.value, (type, payload) => {
      if (type === 'delta') streamingReply.value += payload.text
      if (type === 'done') streamingReply.value = payload.content
      scrollChat()
    })
    message.value = ''
  } catch (error) { handleError(error) }
  finally {
    try { turns.value = (await api('/conversations/' + id)).turns } catch {}
    streamingInput.value = ''
    streamingReply.value = ''
    busy.value = ''
    await scrollChat()
  }
}
onMounted(async () => {
  try {
    const [identity, model] = await Promise.all([api('/auth/session'), api('/status')])
    Object.assign(session, identity)
    Object.assign(status, model)
    await refreshAssets()
  } catch (error) { handleError(error) }
})
</script>

<template>
  <div class="shell" :inert="!!modal">
    <aside class="sidebar">
      <a class="brand" href="#" @click.prevent="navigate('studio')">
        <span class="brand-mark">D</span><div><div class="brand-name">大枣</div><div class="brand-caption">CHARACTER STUDIO</div></div>
      </a>
      <p class="nav-label">你的创作空间</p>
      <nav class="nav" aria-label="主导航">
        <button class="nav-item" :class="{ active: view === 'studio' }" :disabled="!!busy" @click="navigate('studio')"><span aria-hidden="true">✦</span>角色工作室</button>
        <button class="nav-item" :class="{ active: view === 'library' }" :disabled="!!busy" @click="navigate('library')"><span aria-hidden="true">▦</span>我的角色<span class="count">{{ characters.length }}</span></button>
        <button class="nav-item" :class="{ active: view === 'chat' }" :disabled="!!busy" @click="navigate('chat')"><span aria-hidden="true">◌</span>角色试聊</button>
      </nav>
      <div class="sidebar-note"><span aria-hidden="true">“</span><p>一个好角色，<br>从一个小小的念头开始。</p><small>写下设定，让故事继续。</small></div>
      <div class="account"><span class="account-avatar">{{ session.username.slice(0, 1).toUpperCase() || '访' }}</span><div><div class="account-name">{{ session.username || '访客' }}</div><div class="account-sub">{{ session.authenticated ? '独立创作账号' : '登录后保存你的角色' }}</div></div><button :disabled="!!busy" @click="session.authenticated ? logout() : requireLogin()">{{ session.authenticated ? '退出' : '登录' }}</button></div>
    </aside>
    <main class="main">
      <header class="topbar"><div class="breadcrumb">创作空间 / <strong>{{ navTitle }}</strong></div><div class="topbar-right"><span class="model-status"><i class="status-dot" :class="{ ready: status.aiConfigured }"></i>{{ status.aiConfigured ? '模型已配置' : '模型待配置' }}</span><button class="quiet-button" @click="modal = 'about'">使用说明</button></div></header>
      <div class="title-row"><div><span class="eyebrow">{{ view === 'studio' ? 'CREATE A CHARACTER' : view === 'library' ? 'YOUR COLLECTION' : 'MEET YOUR CHARACTER' }}</span><h1>{{ title }}</h1><p class="subtitle">{{ view === 'studio' ? '描述你心中的 TA，从设定到第一句对话，一起慢慢打磨。' : view === 'library' ? '每次保存都是一个新版本，每次相遇都可以有新的开始。' : '用对话感受角色，再回到工作室完善设定。' }}</p></div><span class="edition">STUDIO / 01</span></div>

      <div v-if="view === 'studio'" class="workbench">
        <section class="panel editor" aria-label="角色编辑器">
          <div class="section-heading"><span class="step">01</span><h2>构思一个角色</h2><small>{{ editing ? '编辑 v' + editing.version : '新的灵感' }}</small></div>
          <div class="type-picker"><button v-for="(label, type) in types" :key="type" class="type-pill" :class="{ active: draft.sourceType === type }" :aria-pressed="draft.sourceType === type" :disabled="!!busy" @click="draft.sourceType = type">{{ label }}</button></div>
          <div v-if="draft.sourceType === 'ip'" class="inline-fields"><div><label class="field-label" for="work">作品名称 *</label><input id="work" v-model="draft.work" class="input" maxlength="200" :disabled="!!busy" placeholder="来自哪部作品？"></div><div><label class="field-label" for="version">设定版本</label><input id="version" v-model="draft.canonVersion" class="input" maxlength="200" :disabled="!!busy" placeholder="动画 / 漫画 / 时期"></div></div>
          <label class="field-label" for="brief">你希望 TA 是怎样的人？</label>
          <textarea id="brief" v-model="brief" class="textarea" rows="5" maxlength="6000" :disabled="!!busy" placeholder="例如：一个在深夜经营旧书店的猫妖，嘴上嫌弃人类，却总会偷偷替迷路的人留灯。说话克制，偶尔有一点冷幽默。"></textarea>
          <div class="brief-footer"><div class="examples"><span>试试灵感</span><button :disabled="!!busy" @click="brief = '创造一位在夜晚修复旧书的猫妖，外冷内热，有克制的冷幽默。请设定背景、性格和第一次见面的对白。'">旧书店的猫妖</button><button :disabled="!!busy" @click="brief = '创造一位来自废土世界的机械旅行者，收集人类的故事，擅长倾听，但对日常俗语总是认真误解。'">机械旅行者</button></div><span class="char-count">{{ brief.length }}/6000</span></div>
          <button class="primary generate-button" :disabled="!!busy" @click="generate(false)"><span v-if="busy === 'generate'" class="spinner"></span><span v-else aria-hidden="true">✦</span>{{ busy === 'generate' ? '正在创作角色…' : '生成角色草稿' }}<span aria-hidden="true">↗</span></button>
          <p v-if="notice" class="notice" role="status">{{ notice }}</p>
          <div class="editor-divider"></div>
          <div class="section-heading"><span class="step">02</span><h2>打磨角色设定</h2><small>锁定满意的内容 · 勾选待重生成的字段</small></div>
          <div class="field-grid">
            <div v-for="field in fields" :key="field.key" class="field" :class="{ full: field.full }">
              <div class="field-top"><label class="field-label" :for="'field-' + field.key">{{ field.label }}<span v-if="['name', 'description', 'personality', 'firstMessage'].includes(field.key)"> *</span></label><div class="field-controls"><input v-model="selected" type="checkbox" :value="field.key" :aria-label="'选择重生成' + field.label" :disabled="!!busy"><button class="lock-button" :class="{ locked: locked.includes(field.key) }" :aria-label="(locked.includes(field.key) ? '解锁' : '锁定') + field.label" :aria-pressed="locked.includes(field.key)" :disabled="!!busy" @click="toggleLock(field.key)">{{ locked.includes(field.key) ? '已锁' : '锁定' }}</button></div></div>
              <input v-if="field.short" :id="'field-' + field.key" v-model="draft[field.key]" class="input" :maxlength="field.max" :placeholder="field.placeholder" :disabled="!!busy">
              <textarea v-else :id="'field-' + field.key" v-model="draft[field.key]" class="textarea" rows="3" :maxlength="field.max" :placeholder="field.placeholder" :disabled="!!busy"></textarea>
            </div>
          </div>
          <div class="regen-row"><span>已选 {{ selected.length }} 个字段 · 已锁定 {{ locked.length }} 个</span><button class="text-button" :disabled="!!busy || !selected.length" @click="generate(true)">↻ 重生成所选字段</button></div>
          <div class="field-actions"><span class="note">{{ dirty ? '草稿尚未保存' : editing ? '已保存 · v' + editing.version : '保存后即可开始试聊' }}</span><div class="action-group"><button class="secondary" :disabled="!!busy" @click="newCharacter">清空新建</button><button class="primary" :disabled="!!busy" @click="saveCharacter">{{ busy === 'save' ? '保存中…' : '保存角色' }}</button></div></div>
        </section>
        <aside class="panel preview" aria-label="角色预览"><div class="preview-label"><span>角色预览</span><span class="role-type">{{ types[draft.sourceType] }}</span></div><div class="preview-canvas"><span class="portrait-monogram">{{ draft.name.slice(0, 1) || '枣' }}</span><span class="canvas-caption">文字设定 · 头像待创作</span></div><div class="preview-body"><span class="eyebrow">CHARACTER PROFILE</span><h2>{{ draft.name || '等待一个名字' }}</h2><p class="preview-description">{{ draft.description || '你的角色故事，将从这里开始。写下一个念头，或者直接填写下方的设定。' }}</p><div class="preview-greeting"><span>TA 的第一句话</span><p>{{ draft.firstMessage || '“你好，很高兴在这里遇见你。”\n—— 开场白示意，等待你的创作' }}</p></div><button class="secondary" :disabled="!!busy || !editing || dirty" @click="startChat()">开始试聊 ↗</button><p class="preview-footer">{{ editing && !dirty ? '以已保存版本创建新的会话' : '先保存角色，再认识 TA' }}</p></div></aside>
      </div>

      <section v-else-if="view === 'library'" aria-label="我的角色">
        <div class="library-toolbar"><label class="sr-only" for="search">搜索角色</label><input id="search" v-model="search" class="input" placeholder="搜索名字或设定…"><span class="note">{{ characters.length }} 个角色</span><button class="primary" :disabled="!!busy" @click="newCharacter">＋ 创建角色</button></div>
        <div v-if="filtered.length" class="cards"><article v-for="(character, index) in filtered" :key="character.id" class="panel character-card"><div class="card-canvas" :style="{ background: ['#d9dfcf', '#dfd0bb', '#d8dcde'][index % 3] }"><span class="card-version">v{{ character.version }}</span><span class="card-letter">{{ character.definition.name.slice(0, 1) }}</span></div><div class="card-body"><span class="eyebrow">{{ types[character.definition.sourceType] }}</span><h2>{{ character.definition.name }}</h2><p>{{ character.definition.description }}</p><div class="card-tools"><button class="primary" :disabled="!!busy" @click="startChat(character)">开始试聊</button><button class="secondary" :disabled="!!busy" @click="editCharacter(character)">编辑</button></div><div class="card-links"><button :disabled="!!busy" @click="copyCharacter(character)">复制</button><button :disabled="!!busy" @click="exportCharacter(character)">导出角色卡</button><button :disabled="!!busy" @click="deleteCharacter(character)">删除</button></div></div></article></div>
        <div v-else class="panel empty"><span aria-hidden="true">✦</span><h2>{{ search ? '还没有找到这个角色' : '你的第一个角色，还在想象里' }}</h2><p>{{ search ? '换个名字或关键词试试。' : '从原创故事、熟悉的 IP 或一个有趣的助手开始。' }}</p><button v-if="!search" class="primary" @click="newCharacter">去创作第一个角色</button></div>
      </section>

      <section v-else class="panel chat-layout" aria-label="角色试聊">
        <aside class="sessions"><h2>最近的相遇</h2><button v-for="chat in conversations" :key="chat.id" class="session-button" :class="{ active: chat.id === currentConversation?.id }" :disabled="!!busy" @click="selectConversation(chat.id)">{{ chat.definition.name }}<small>角色 v{{ chat.characterVersion }} · {{ new Date(chat.createdAt).toLocaleDateString() }}</small></button><p v-if="!conversations.length" class="note">保存角色后开始一次新会话。</p></aside>
        <div v-if="currentConversation" class="chat-main"><div class="chat-heading"><div><h2>{{ currentConversation.definition.name }}</h2><span>角色 v{{ currentConversation.characterVersion }} · 本次会话使用创建时的设定</span></div><button class="text-button" :disabled="!!busy" @click="removeConversation">删除会话</button></div><div ref="messagesElement" class="messages" aria-live="polite" aria-relevant="additions text"><div class="message"><span class="speaker">{{ currentConversation.definition.name }}</span>{{ currentConversation.definition.firstMessage }}</div><template v-for="turn in turns" :key="turn.id"><div class="message user"><span class="speaker">你</span>{{ turn.userContent }}</div><div class="message" :class="{ failed: turn.status !== 'DONE' }"><span class="speaker">{{ currentConversation.definition.name }}</span>{{ turn.assistantContent || (turn.status === 'PENDING' ? '等待回复…' : '本次回复未完成') }}<small v-if="turn.status !== 'DONE'">{{ turn.status === 'PENDING' ? '回复尚未结束，请稍后刷新' : '失败记录不进入后续对话，可以重新发送' }}</small></div></template><template v-if="streamingInput"><div class="message user"><span class="speaker">你</span>{{ streamingInput }}</div><div class="message"><span class="speaker">{{ currentConversation.definition.name }}</span>{{ streamingReply || '正在组织语言…' }}<span class="stream-cursor" aria-hidden="true">▍</span></div></template></div><form class="composer" @submit.prevent="sendMessage"><label class="sr-only" for="message">给角色发送消息</label><textarea id="message" v-model="message" class="textarea" rows="2" maxlength="4000" :disabled="!!busy" placeholder="和 TA 说点什么…" @keydown.ctrl.enter.prevent="sendMessage"></textarea><div class="composer-footer"><span>{{ message.length }}/4000 · Ctrl + Enter 发送</span><button class="primary" type="submit" :disabled="!!busy || !message.trim()">{{ busy === 'stream' ? '回复中…' : '发送 ↗' }}</button></div></form></div>
        <div v-else class="empty"><span aria-hidden="true">◌</span><h2>准备好认识一个角色了吗？</h2><p>打开已有会话，或从你的角色库开始新的相遇。</p><button class="primary" @click="navigate('library')">选择一个角色</button></div>
      </section>
      <footer class="footer"><span>大枣 · 角色工作室</span><span>让每一个设定，都有继续生长的空间。</span></footer>
    </main>
  </div>
  <div v-if="modal" class="modal-backdrop" @click.self="!authBusy && (modal = '')" @keydown.esc="!authBusy && (modal = '')">
    <section class="panel modal" role="dialog" aria-modal="true" :aria-label="modal === 'auth' ? '账号登录与注册' : '使用说明'"><button class="modal-close" aria-label="关闭对话框" :disabled="authBusy" @click="modal = ''">×</button>
      <template v-if="modal === 'auth'"><span class="eyebrow">YOUR CREATIVE SPACE</span><h2>{{ authMode === 'login' ? '欢迎回来。' : '给想象，一个家。' }}</h2><p>登录后保存角色与会话，继续你的创作。</p><form @submit.prevent="authenticate"><label class="field-label" for="username">用户名</label><input id="username" v-model="credentials.username" class="input" autocomplete="username" pattern="[a-zA-Z0-9_]{3,32}" minlength="3" maxlength="32" required :disabled="authBusy" placeholder="3～32 位字母、数字或下划线"><label class="field-label" for="password">密码</label><input id="password" v-model="credentials.password" type="password" class="input" :autocomplete="authMode === 'login' ? 'current-password' : 'new-password'" minlength="8" maxlength="64" required :disabled="authBusy" placeholder="8～64 位，UTF-8 不超过 72 字节"><p v-if="authError" class="error-text" role="alert">{{ authError }}</p><button class="primary" type="submit" :disabled="authBusy">{{ authBusy ? '请稍候…' : authMode === 'login' ? '登录工作室' : '注册并登录' }}</button></form><button class="text-button" :disabled="authBusy" @click="authMode = authMode === 'login' ? 'register' : 'login'; authError = ''">{{ authMode === 'login' ? '第一次来？创建账号' : '已有账号？直接登录' }}</button></template>
      <template v-else><span class="eyebrow">A SMALL GUIDE</span><h2>从构思，到相遇。</h2><p>① 描述角色，生成草稿。已有 IP 可补充作品、版本与参考资料。</p><p>② 编辑设定，锁定满意的字段，勾选需要重生成的内容。</p><p>③ 保存后开始试聊。修改角色会生成新版本，旧会话保留原设定。</p><p>首版头像采用名字标记，角色卡可导出为 Character Card V2 JSON。</p><p class="note">{{ status.aiConfigured ? '后端模型配置：' + status.model + '。配置状态不代表供应商连接已经验证。' : '模型待配置：后端启动前设置 AI_API_KEY、AI_BASE_URL、AI_MODEL。具体步骤见根目录 README；配置后重启服务。' }}</p><button class="primary" @click="modal = ''">开始创作</button></template>
    </section>
  </div>
  <div v-if="toast.message" class="toast" :class="{ error: toast.error }" :role="toast.error ? 'alert' : 'status'">{{ toast.message }}</div>
</template>

