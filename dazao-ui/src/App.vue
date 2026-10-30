<script setup>
import { computed, nextTick, onMounted, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { api, streamMessage } from './api'
import StudioDialog from './components/StudioDialog.vue'
import StudioIcon from './components/StudioIcon.vue'

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
const title = computed(() => ({ studio: '让想象，成为一个角色', library: '我的角色', chat: '每次对话，都是新的相遇' })[view.value])
const navTitle = computed(() => ({ studio: '角色工作室', library: '我的角色', chat: '角色试聊' })[view.value])

const fieldTab = ref('essential')
const requiredFields = ['name', 'description', 'personality', 'firstMessage']
const shownFields = computed(() => fields.filter(field => fieldTab.value === 'essential' ? requiredFields.includes(field.key) : !requiredFields.includes(field.key)))
const completeCount = computed(() => requiredFields.filter(key => draft[key]?.trim()).length)
const fieldErrors = reactive({})
const pageError = ref('')
const loading = ref(true)
const requestedView = ref('')
const mobileSessions = ref(false)
const confirmation = reactive({ title: '', description: '', label: '' })
let confirmedAction
const navItems = [{ key: 'studio', icon: 'spark', label: '角色工作室' }, { key: 'library', icon: 'grid', label: '我的角色' }, { key: 'chat', icon: 'chat', label: '角色试聊' }]
const busyLabel = computed(() => ({ generate: '正在生成，请稍候…', save: '正在保存角色…', copy: '正在复制角色…', delete: '正在删除…', export: '正在导出角色卡…', load: '正在打开会话…', stream: '角色正在回复…', logout: '正在退出…' })[busy.value])
watch(view, async () => {
  pageError.value = ''
  mobileSessions.value = false
  await nextTick()
  document.getElementById('page-title')?.focus({ preventScroll: true })
})
watch(draft, () => { for (const key of Object.keys(fieldErrors)) if (draft[key]?.trim()) delete fieldErrors[key] })
function closeModal() {
  if (authBusy.value) return
  modal.value = ''
  confirmedAction = undefined
  requestedView.value = ''
  credentials.password = ''
}
function confirmAction(title, description, label, action) {
  Object.assign(confirmation, { title, description, label })
  confirmedAction = action
  modal.value = 'confirm'
}
function acceptConfirmation() {
  const action = confirmedAction
  confirmedAction = undefined
  modal.value = ''
  action?.()
}
function setView(next, push = true) {
  if (!navItems.some(item => item.key === next)) next = 'studio'
  view.value = next
  if (location.hash !== '#' + next) history[push ? 'pushState' : 'replaceState'](null, '', '#' + next)
}
function restoreNavigation() {
  if (modal.value) closeModal()
  const next = location.hash.slice(1) || 'studio'
  if (busy.value) { history.replaceState(null, '', '#' + view.value); return }
  if (next !== 'studio' && !session.authenticated) { requestedView.value = next; requireLogin(); history.replaceState(null, '', '#studio'); return }
  setView(next, false)
}
function protectDraft(event) {
  if (dirty.value || brief.value.trim() || busy.value) { event.preventDefault(); event.returnValue = '' }
}
async function initialize() {
  loading.value = true
  pageError.value = ''
  try {
    const [identity, model] = await Promise.all([api('/auth/session'), api('/status')])
    Object.assign(session, identity)
    Object.assign(status, model)
    await refreshAssets()
    restoreNavigation()
  } catch (error) { handleError(error) }
  finally { loading.value = false }
}

function notify(text, error = false) {
  clearTimeout(toastTimer)
  toast.message = text
  toast.error = error
  toastTimer = setTimeout(() => { toast.message = '' }, error ? 12000 : 6000)
}
function requireLogin() {
  if (session.authenticated) return true
  modal.value = 'auth'
  authError.value = ''
  return false
}
function handleError(error) {
  pageError.value = error.status === 503 ? 'AI 暂时不可用，输入已保留，请稍后再试。' : ['AbortError', 'TimeoutError'].includes(error.name) ? '请求超时，输入已保留，请稍后重试' : error.message
  if (error.status === 401) {
    session.authenticated = false
    session.username = ''
    characters.value = []
    conversations.value = []
    currentConversation.value = null
    turns.value = []
    modal.value = 'auth'
  }
  notify(pageError.value, true)
}
async function refreshAssets() {
  if (!session.authenticated) return
  const [roles, chats] = await Promise.all([api('/characters'), api('/conversations')])
  characters.value = roles
  conversations.value = chats
}
async function authenticate() {
  if (authBusy.value) return
  authBusy.value = true
  authError.value = ''
  try {
    if (authMode.value === 'register') await api('/auth/register', { method: 'POST', body: credentials })
    await api('/auth/login', { method: 'POST', form: true, body: credentials })
    Object.assign(session, await api('/auth/session'))
    credentials.password = ''
    modal.value = ''
    pageError.value = ''
    await refreshAssets()
    if (requestedView.value) { setView(requestedView.value); requestedView.value = '' }
    notify('欢迎来到角色工作室')
  } catch (error) {
    if (session.authenticated) handleError(error)
    else authError.value = error.message
  }
  finally { authBusy.value = false }
}
function logout() {
  if (busy.value) return
  confirmAction('退出当前账号？', dirty.value ? '未保存的角色草稿会被清空。可以取消并先保存角色。' : '已保存的角色与会话会保留，下次登录后可以继续。', '退出账号', performLogout)
}
async function performLogout() {
  if (busy.value) return
  busy.value = 'logout'
  try {
    await api('/auth/logout', { method: 'POST' })
    Object.assign(session, { authenticated: false, username: '' })
    characters.value = []
    conversations.value = []
    currentConversation.value = null
    turns.value = []
    resetDraft()
    setView('studio')
  } catch (error) { handleError(error) }
  finally { busy.value = '' }
}
function navigate(next) {
  if (busy.value) return
  if (next !== 'studio' && !session.authenticated) { requestedView.value = next; requireLogin(); return }
  setView(next)
}
function resetDraft() {
  Object.assign(draft, empty())
  editing.value = null
  brief.value = ''
  locked.value = []
  selected.value = []
  savedDraft.value = JSON.stringify(draft)
  notice.value = ''
  fieldTab.value = 'essential'
  for (const key of Object.keys(fieldErrors)) delete fieldErrors[key]
}
function newCharacter() {
  if (busy.value) return
  const create = () => { resetDraft(); setView('studio'); pageError.value = '' }
  if (dirty.value || brief.value.trim()) confirmAction('重新开始一个角色？', '当前未保存的草稿将被清空，已保存的角色不受影响。', '清空并新建', create)
  else create()
}
function editCharacter(character) {
  if (busy.value) return
  const open = () => {
    resetDraft()
    Object.assign(draft, empty(), character.definition)
    editing.value = { id: character.id, version: character.version }
    savedDraft.value = JSON.stringify(draft)
    setView('studio')
  }
  if (dirty.value || brief.value.trim()) confirmAction('打开另一个角色？', '当前草稿尚未保存，继续会替换正在编辑的内容。', '继续打开', open)
  else open()
}
function toggleLock(key) {
  locked.value = locked.value.includes(key) ? locked.value.filter(f => f !== key) : [...locked.value, key]
}
async function generate(partial = false) {
  if (busy.value || !requireLogin()) return
  if (!brief.value.trim()) { notify('先描述你想要的角色或修改要求', true); return }
  if (draft.sourceType === 'ip' && !draft.work.trim()) { notify('已有 IP 角色请填写作品名称', true); return }
  if (partial && !selected.value.length) { notify('请勾选需要重生成的字段', true); return }
  pageError.value = ''
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
  for (const key of requiredFields) if (!draft[key]?.trim()) fieldErrors[key] = '请填写' + fields.find(field => field.key === key).label
  if (Object.keys(fieldErrors).length) {
    fieldTab.value = 'essential'
    await nextTick()
    document.getElementById('field-' + Object.keys(fieldErrors)[0])?.focus()
    return
  }
  if (draft.sourceType === 'ip' && !draft.work.trim()) { pageError.value = '已有 IP 角色请填写作品名称'; document.getElementById('work')?.focus(); return }
  if (editing.value && !dirty.value) { notify('当前版本已保存'); return }
  pageError.value = ''
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
function deleteCharacter(character) {
  if (busy.value) return
  confirmAction('删除“' + character.definition.name + '”？', '这个角色及其全部会话都会被删除，此操作不可恢复。', '删除角色', () => performDeleteCharacter(character))
}
async function performDeleteCharacter(character) {
  if (busy.value) return
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
  if (busy.value) return
  busy.value = 'export'
  try {
    const card = await api('/characters/' + character.id + '/export')
    const url = URL.createObjectURL(new Blob([JSON.stringify(card, null, 2)], { type: 'application/json' }))
    const link = document.createElement('a')
    link.href = url
    link.download = character.definition.name.replace(/[\\/:*?"<>|]/g, '_') + '.card.json'
    link.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  } catch (error) { handleError(error) }
  finally { busy.value = '' }
}
async function openConversation(id) {
  const result = await api('/conversations/' + id)
  currentConversation.value = result.conversation
  turns.value = result.turns
  message.value = ''
  mobileSessions.value = false
  pageError.value = ''
  setView('chat')
  await scrollChat()
}
function selectConversation(id) {
  if (busy.value) return
  if (id === currentConversation.value?.id) { mobileSessions.value = false; return }
  if (message.value.trim()) confirmAction('切换会话？', '当前输入的消息尚未发送，切换会话会清空这条输入。', '切换会话', () => loadConversation(id))
  else loadConversation(id)
}
async function loadConversation(id) {
  if (busy.value) return
  busy.value = 'load'
  try { await openConversation(id) } catch (error) { handleError(error) }
  finally { busy.value = '' }
}
async function startChat(character, confirmed = false) {
  if (busy.value || !requireLogin()) return
  if (!character) {
    if (!editing.value || dirty.value) { notify('请先保存当前角色，再开始试聊', true); return }
    character = characters.value.find(c => c.id === editing.value.id)
  }
  if (!confirmed && message.value.trim()) {
    confirmAction('开始新的会话？', '当前会话有尚未发送的输入，继续会清空这条输入。', '开始新会话', () => startChat(character, true))
    return
  }
  busy.value = 'load'
  try {
    const chat = await api('/conversations', { method: 'POST', body: { characterId: character.id } })
    await refreshAssets()
    await openConversation(chat.id)
  } catch (error) { handleError(error) }
  finally { busy.value = '' }
}
function removeConversation() {
  if (!currentConversation.value || busy.value) return
  const id = currentConversation.value.id
  confirmAction('删除当前会话？', '聊天记录将永久删除。角色本身及其他会话不受影响。', '删除会话', async () => {
    if (busy.value) return
    busy.value = 'delete'
    try {
      await api('/conversations/' + id, { method: 'DELETE' })
      currentConversation.value = null
      turns.value = []
      message.value = ''
      await refreshAssets()
      notify('会话已删除')
    } catch (error) { handleError(error) }
    finally { busy.value = '' }
  })
}
async function scrollChat() {
  await nextTick()
  if (messagesElement.value) messagesElement.value.scrollTop = messagesElement.value.scrollHeight
}
async function sendMessage() {
  if (busy.value || !currentConversation.value || !message.value.trim()) return
  pageError.value = ''
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
    if (session.authenticated && currentConversation.value?.id === id) {
      try { turns.value = (await api('/conversations/' + id)).turns } catch (error) { handleError(error) }
    }
    streamingInput.value = ''
    streamingReply.value = ''
    busy.value = ''
    await scrollChat()
  }
}
onMounted(() => {
  window.addEventListener('popstate', restoreNavigation)
  window.addEventListener('beforeunload', protectDraft)
  initialize()
})
onBeforeUnmount(() => {
  clearTimeout(toastTimer)
  window.removeEventListener('popstate', restoreNavigation)
  window.removeEventListener('beforeunload', protectDraft)
})
</script>

<template>
  <a class="skip-link" href="#main-content">跳转到主要内容</a>
  <div class="shell">
    <aside class="sidebar">
      <a class="brand" href="#studio" @click.prevent="navigate('studio')"><span class="brand-mark">枣</span><span><strong>大枣</strong><small>角色工作室</small></span></a>
      <button class="primary new-button" :disabled="!!busy || loading" @click="newCharacter"><StudioIcon name="plus" />创建角色</button>
      <p class="nav-label">创作空间</p>
      <nav class="nav" aria-label="主导航">
        <button v-for="item in navItems" :key="item.key" class="nav-item" :class="{ active: view === item.key }" :aria-current="view === item.key ? 'page' : undefined" :disabled="!!busy || loading" @click="navigate(item.key)"><StudioIcon :name="item.icon" /><span>{{ item.label }}</span><span v-if="item.key === 'library' && characters.length" class="count">{{ characters.length }}</span></button>
      </nav>
      <div class="sidebar-note"><StudioIcon name="book" /><strong>好故事，从一个念头开始</strong><p>写下设定，保留灵感。<br>在对话里，遇见你的角色。</p></div>
      <div class="account"><span class="account-avatar">{{ session.username.slice(0, 1).toUpperCase() || '访' }}</span><div class="account-info"><strong :title="session.username">{{ session.username || '访客' }}</strong><small>{{ session.authenticated ? '我的创作账号' : '登录，留住每个灵感' }}</small></div><button class="account-action" :disabled="!!busy || loading" @click="session.authenticated ? logout() : requireLogin()">{{ session.authenticated ? '退出' : '登录' }}</button></div>
    </aside>
    <main id="main-content" class="main" tabindex="-1">
      <header class="topbar"><div class="breadcrumb">创作空间 <span aria-hidden="true">/</span> <strong>{{ navTitle }}</strong></div><div class="topbar-right"><span class="model-status" :class="{ ready: status.aiConfigured }"><i></i>{{ loading ? '正在连接' : status.aiConfigured ? 'AI 已配置' : '手动创作模式' }}</span><button class="quiet-button" @click="modal = 'about'">使用指南</button></div></header>
      <div class="title-row"><div><p class="eyebrow">{{ view === 'studio' ? '每一个想法，都值得被听见' : view === 'library' ? 'YOUR CHARACTERS' : 'A CONVERSATION BEGINS' }}</p><h1 id="page-title" tabindex="-1">{{ title }}</h1><p class="subtitle">{{ view === 'studio' ? '从一段灵感，到鲜活的设定，再到第一句对话。' : view === 'library' ? '保存每一份灵感，继续打磨，或开始一次新的对话。' : '会话会保留创建时的角色设定，让故事连贯地继续。' }}</p></div><span v-if="view === 'studio'" class="draft-badge"><span :class="{ unsaved: dirty }"></span>{{ dirty ? '未保存的草稿' : editing ? '已保存 · v' + editing.version : '新的创作' }}</span><button v-else-if="view === 'library'" class="primary" :disabled="!!busy || loading" @click="newCharacter"><StudioIcon name="plus" />创建角色</button></div>
      <div v-if="pageError" class="feedback error-banner" role="alert"><span class="feedback-symbol">!</span><div><strong>操作未完成</strong><p>{{ pageError }}</p></div><button class="quiet-button" @click="pageError = ''">关闭提示</button></div>
      <div v-if="busy" class="busy-strip" role="status"><span class="spinner"></span>{{ busyLabel }}</div>
      <section v-if="loading" class="panel loading-state" aria-label="正在加载工作室" aria-busy="true"><span class="spinner"></span><p>正在打开你的创作空间…</p></section>
      <div v-else-if="pageError && !session.authenticated && !status.model" class="panel empty"><StudioIcon name="chat" /><h2>暂时无法连接工作室</h2><p>检查网络后重试，你填写的内容会保留。</p><button class="primary" @click="initialize">重新连接</button></div>
      <div v-else-if="view === 'studio'" class="workbench">
        <section class="panel editor" aria-label="角色编辑器">
          <div class="editor-topline"><span class="step">01</span><div><h2>从一个灵感开始</h2><p>让 AI 帮你起草，或直接填写下方设定。</p></div></div>
          <div class="type-picker" role="group" aria-label="角色类型"><button v-for="(label, type) in types" :key="type" :class="{ active: draft.sourceType === type }" :aria-pressed="draft.sourceType === type" :disabled="!!busy" @click="draft.sourceType = type">{{ label }}</button></div>
          <div v-if="draft.sourceType === 'ip'" class="inline-fields"><div><label for="work">作品名称 <span class="required">*</span></label><input id="work" v-model="draft.work" class="input" maxlength="200" :disabled="!!busy" placeholder="来自哪部作品？"></div><div><label for="canon-version">设定版本</label><input id="canon-version" v-model="draft.canonVersion" class="input" maxlength="200" :disabled="!!busy" placeholder="动画 / 漫画 / 故事时期"></div></div>
          <label for="brief">描述你心中的 TA</label><textarea id="brief" v-model="brief" class="textarea brief-input" rows="3" maxlength="6000" :disabled="!!busy" placeholder="例如：一位在深夜书店修复旧书的猫妖，话不多，有点冷幽默，却总会为晚归的人留一盏灯。"></textarea>
          <div class="brief-footer"><div class="examples"><span>试试这些灵感</span><button :disabled="!!busy" @click="brief = '一位在深夜书店修复旧书的猫妖，外冷内热，擅长倾听。'">深夜书店</button><button :disabled="!!busy" @click="brief = '一位热爱星空的旅行者，用温柔而简短的话分享宇宙见闻。'">星际旅人</button></div><span class="char-count">{{ brief.length }}/6000</span></div>
          <div class="generate-row"><button class="primary" :disabled="!!busy" @click="generate(false)"><StudioIcon name="spark" />{{ busy === 'generate' ? '正在生成…' : '生成角色草稿' }}</button><span class="note">{{ status.aiConfigured ? '生成后仍可自由编辑' : 'AI 待配置，可先手动填写设定' }}</span></div>
          <p v-if="notice" class="feedback success" role="status"><StudioIcon name="check" />{{ notice }}</p>
          <div class="editor-divider"></div>
          <div class="editor-topline"><span class="step">02</span><div><h2>赋予角色独特的个性</h2><p>带 * 的内容为必填，满意的字段可以锁定。</p></div><span class="completion-label">{{ completeCount }}/4 已完成</span></div>
          <div class="field-tabs" role="group" aria-label="设定分组"><button :aria-pressed="fieldTab === 'essential'" :class="{ active: fieldTab === 'essential' }" @click="fieldTab = 'essential'">核心设定 <span>4</span></button><button :aria-pressed="fieldTab === 'details'" :class="{ active: fieldTab === 'details' }" @click="fieldTab = 'details'">更多细节 <span>4</span></button></div>
          <div class="field-grid">
            <div v-for="field in shownFields" :key="field.key" class="field" :class="{ full: field.full || field.short || fieldTab === 'essential', 'has-error': fieldErrors[field.key] }">
              <div class="field-top"><label :for="'field-' + field.key">{{ field.label }}<span v-if="requiredFields.includes(field.key)" class="required"> *</span></label><div class="field-controls"><label class="select-field"><input v-model="selected" type="checkbox" :value="field.key" :aria-label="'选择重生成' + field.label" :disabled="!!busy"><span>重写</span></label><button class="lock-button" :class="{ locked: locked.includes(field.key) }" :aria-label="(locked.includes(field.key) ? '解锁' : '锁定') + field.label" :aria-pressed="locked.includes(field.key)" :disabled="!!busy" @click="toggleLock(field.key)">{{ locked.includes(field.key) ? '已锁定' : '锁定' }}</button></div></div>
              <input v-if="field.short" :id="'field-' + field.key" v-model="draft[field.key]" class="input" :maxlength="field.max" :placeholder="field.placeholder" :disabled="!!busy" :aria-required="requiredFields.includes(field.key)" :aria-invalid="!!fieldErrors[field.key]" :aria-describedby="fieldErrors[field.key] ? 'error-' + field.key : undefined">
              <textarea v-else :id="'field-' + field.key" v-model="draft[field.key]" class="textarea" rows="3" :maxlength="field.max" :placeholder="field.placeholder" :disabled="!!busy" :aria-required="requiredFields.includes(field.key)" :aria-invalid="!!fieldErrors[field.key]" :aria-describedby="fieldErrors[field.key] ? 'error-' + field.key : undefined"></textarea>
              <p v-if="fieldErrors[field.key]" :id="'error-' + field.key" class="error-text">{{ fieldErrors[field.key] }}</p>
            </div>
          </div>
          <div class="regen-row"><span>已选 {{ selected.length }} 个 · 已锁定 {{ locked.length }} 个</span><button class="quiet-button" :disabled="!!busy || !selected.length" @click="generate(true)">重生成所选字段</button></div>
          <div class="field-actions"><span class="save-state"><StudioIcon :name="dirty ? 'spark' : 'check'" />{{ dirty ? '草稿尚未保存' : editing ? '所有更改已保存' : '填写完成后保存角色' }}</span><div class="action-group"><button class="quiet-button" :disabled="!!busy" @click="newCharacter">清空新建</button><button class="primary" :disabled="!!busy" @click="saveCharacter">{{ busy === 'save' ? '保存中…' : '保存角色' }}</button></div></div>
        </section>
        <aside class="preview-column"><section class="panel preview" aria-label="角色预览"><div class="preview-label"><span>实时预览</span><span class="tag">{{ types[draft.sourceType] }}</span></div><div class="preview-canvas"><span class="orbit orbit-one"></span><span class="orbit orbit-two"></span><span class="portrait-monogram">{{ draft.name.slice(0, 1) || '枣' }}</span><span class="canvas-caption">一个独属于你的角色</span></div><div class="preview-body"><h2>{{ draft.name || '等待一个名字' }}</h2><p class="preview-description">{{ draft.description || '写下身份与背景，让一个模糊的念头，慢慢拥有自己的故事。' }}</p><div class="preview-greeting"><span>TA 的第一句话</span><p>{{ draft.firstMessage || '你的开场白会显示在这里。' }}</p></div><div class="completion"><span>核心设定</span><strong>{{ completeCount }}/4</strong><progress :value="completeCount" max="4" aria-label="核心设定完成度"></progress></div><button class="secondary" :disabled="!!busy || !editing || dirty" @click="startChat()">开始试聊<StudioIcon name="arrow" /></button><p class="note centered">{{ editing && !dirty ? '用当前已保存版本，开始新的会话' : '保存角色后，就能与 TA 对话' }}</p></div></section><div class="preview-tip"><StudioIcon name="book" /><p><strong>让设定更具体一点</strong>比起“温柔”，试着写“总会记得别人随口提到的小事”。细节让角色更鲜活。</p></div></aside>
      </div>
      <section v-else-if="view === 'library'" aria-label="我的角色">
        <div class="library-toolbar"><div class="search-box"><StudioIcon name="search" /><label class="sr-only" for="search">搜索角色</label><input id="search" v-model="search" class="input" placeholder="搜索名字或角色设定"><button v-if="search" class="icon-button" aria-label="清空搜索" @click="search = ''">×</button></div><span class="note">{{ search ? '找到 ' + filtered.length + ' 个' : '共 ' + characters.length + ' 个角色' }}</span></div>
        <div v-if="filtered.length" class="cards"><article v-for="(character, index) in filtered" :key="character.id" class="panel character-card" :class="'card-tone-' + index % 3"><div class="card-canvas"><span class="card-letter">{{ character.definition.name.slice(0, 1) }}</span><span class="card-version">版本 {{ character.version }}</span></div><div class="card-body"><span class="tag">{{ types[character.definition.sourceType] }}</span><h2>{{ character.definition.name }}</h2><p>{{ character.definition.description }}</p><div class="card-tools"><button class="primary" :disabled="!!busy" @click="startChat(character)"><StudioIcon name="chat" />开始试聊</button><button class="secondary" :disabled="!!busy" @click="editCharacter(character)">编辑设定</button></div><div class="card-links"><button :disabled="!!busy" @click="copyCharacter(character)">复制</button><button :disabled="!!busy" @click="exportCharacter(character)">导出角色卡</button><button class="danger-text" :disabled="!!busy" @click="deleteCharacter(character)">删除</button></div></div></article></div>
        <div v-else class="panel empty"><div class="empty-illustration"><StudioIcon :name="search ? 'search' : 'spark'" /></div><h2>{{ search ? '没有找到匹配的角色' : '你的第一个角色，还在想象里' }}</h2><p>{{ search ? '试试更短的名字，或设定中的一个关键词。' : '一个有趣的助手、一位熟悉的伙伴，或故事里的新朋友。' }}</p><button v-if="search" class="secondary" @click="search = ''">清空搜索</button><button v-else class="primary" @click="newCharacter"><StudioIcon name="plus" />创作第一个角色</button></div>
      </section>
      <section v-else class="panel chat-layout" :class="{ 'show-sessions': mobileSessions }" aria-label="角色试聊">
        <aside class="sessions"><div class="sessions-heading"><h2>最近会话</h2><span>{{ conversations.length }}</span></div><button v-for="chat in conversations" :key="chat.id" class="session-button" :class="{ active: chat.id === currentConversation?.id }" :aria-current="chat.id === currentConversation?.id ? 'true' : undefined" :disabled="!!busy" @click="selectConversation(chat.id)"><span class="session-avatar">{{ chat.definition.name.slice(0, 1) }}</span><span><strong>{{ chat.definition.name }}</strong><small>v{{ chat.characterVersion }} · {{ new Date(chat.createdAt).toLocaleDateString() }}</small></span></button><p v-if="!conversations.length" class="note session-empty">还没有会话。<br>从角色库选择一位伙伴开始。</p><button class="secondary session-new" :disabled="!!busy" @click="navigate('library')"><StudioIcon name="plus" />开启新对话</button></aside>
        <div v-if="currentConversation" class="chat-main"><div class="chat-heading"><button class="icon-button mobile-back" aria-label="返回会话列表" :disabled="!!busy" @click="mobileSessions = true"><StudioIcon name="back" /></button><span class="chat-avatar">{{ currentConversation.definition.name.slice(0, 1) }}</span><div class="chat-heading-title"><h2>{{ currentConversation.definition.name }}</h2><span>角色 v{{ currentConversation.characterVersion }} · 会话快照</span></div><button class="quiet-button danger-text" :disabled="!!busy" @click="removeConversation">删除会话</button></div><div class="chat-context">这是一次新的相遇。角色修改不会改变本次会话的设定。</div><div ref="messagesElement" class="messages" role="log" aria-label="聊天记录" aria-live="polite" aria-relevant="additions"><div class="message"><span class="speaker">{{ currentConversation.definition.name }}</span><div class="message-bubble">{{ currentConversation.definition.firstMessage }}</div></div><template v-for="turn in turns" :key="turn.id"><div class="message user"><span class="speaker">你</span><div class="message-bubble">{{ turn.userContent }}</div></div><div class="message" :class="{ failed: turn.status !== 'DONE' }"><span class="speaker">{{ currentConversation.definition.name }}</span><div class="message-bubble">{{ turn.assistantContent || (turn.status === 'PENDING' ? '等待回复…' : '本次回复未完成') }}</div><small v-if="turn.status !== 'DONE'">{{ turn.status === 'PENDING' ? '回复尚未结束，请稍后刷新' : '失败记录不进入后续对话，可以重新发送' }}</small></div></template><template v-if="streamingInput"><div class="message user"><span class="speaker">你</span><div class="message-bubble">{{ streamingInput }}</div></div><div class="message"><span class="speaker">{{ currentConversation.definition.name }}</span><div class="message-bubble">{{ streamingReply || '正在组织语言…' }}<span class="stream-cursor" aria-hidden="true">▍</span></div></div></template></div><form class="composer" @submit.prevent="sendMessage"><label class="sr-only" for="message">给角色发送消息</label><textarea id="message" v-model="message" class="textarea" rows="2" maxlength="4000" :disabled="!!busy" placeholder="和 TA 说点什么…" @keydown.ctrl.enter.prevent="sendMessage"></textarea><div class="composer-footer"><span>{{ message.length }}/4000 <span class="keyboard-hint">· Ctrl + Enter 发送</span></span><button class="primary" type="submit" :disabled="!!busy || !message.trim()">{{ busy === 'stream' ? '回复中…' : '发送' }}<StudioIcon name="arrow" /></button></div></form></div>
        <div v-else class="empty"><div class="empty-illustration"><StudioIcon name="chat" /></div><h2>故事，从一句你好开始</h2><p>打开已有会话，或选择一个角色，开始新的相遇。</p><button class="primary" @click="navigate('library')">前往角色库<StudioIcon name="arrow" /></button></div>
      </section>
      <footer class="footer"><span>大枣 · 让想象有回声</span><span>你的角色，你的故事。</span></footer>
    </main>
  </div>
  <StudioDialog :open="!!modal" :busy="authBusy" :title="modal === 'auth' ? '账号登录与注册' : modal === 'confirm' ? confirmation.title : '使用指南'" @close="closeModal">
    <template v-if="modal === 'auth'"><div class="dialog-brand"><span class="brand-mark">枣</span></div><h2>{{ authMode === 'login' ? '欢迎回到创作空间' : '为想象，留一个位置' }}</h2><p class="dialog-intro">{{ authMode === 'login' ? '登录，继续你的角色与故事。' : '创建账号，保存角色，开启你的第一段对话。' }}</p><form @submit.prevent="authenticate"><label for="username">用户名</label><input id="username" v-model="credentials.username" class="input" autofocus autocomplete="username" pattern="[a-zA-Z0-9_]{3,32}" minlength="3" maxlength="32" required :disabled="authBusy" placeholder="3～32 位字母、数字或下划线"><label for="password">密码</label><input id="password" v-model="credentials.password" type="password" class="input" :autocomplete="authMode === 'login' ? 'current-password' : 'new-password'" minlength="8" maxlength="64" required :disabled="authBusy" placeholder="至少 8 位"><p class="note">密码支持 8～64 个字符，多字节字符合计不超过 72 字节。</p><p v-if="authError" class="feedback error-text" role="alert">{{ authError }}</p><button class="primary full-button" type="submit" :disabled="authBusy"><span v-if="authBusy" class="spinner"></span>{{ authBusy ? '请稍候…' : authMode === 'login' ? '登录工作室' : '注册并登录' }}<StudioIcon v-if="!authBusy" name="arrow" /></button></form><button class="text-button auth-toggle" :disabled="authBusy" @click="authMode = authMode === 'login' ? 'register' : 'login'; authError = ''">{{ authMode === 'login' ? '还没有账号？创建账号' : '已有账号？直接登录' }}</button></template>
    <template v-else-if="modal === 'confirm'"><h2>{{ confirmation.title }}</h2><p class="dialog-intro">{{ confirmation.description }}</p><div class="dialog-actions"><button class="secondary" autofocus @click="closeModal">取消</button><button class="primary" @click="acceptConfirmation">{{ confirmation.label }}</button></div></template>
    <template v-else><h2>从构思，到相遇</h2><p class="dialog-intro">三个简单步骤，让角色拥有自己的声音。</p><ol class="guide-steps"><li><strong>写下灵感</strong><p>描述角色，生成草稿；也可以从核心设定开始手动填写。</p></li><li><strong>打磨细节</strong><p>锁定满意的字段，勾选想重写的内容。更多细节里可以补充外观、场景和表达风格。</p></li><li><strong>保存并开始对话</strong><p>保存后开始试聊。角色更新会生成新版本，旧会话保留当时的设定。</p></li></ol><p class="feedback info">{{ status.aiConfigured ? 'AI 已配置。生成的内容仍需要你检查和完善。' : 'AI 暂未配置。你仍可手动创建、编辑和导出角色；配置完成后即可生成和聊天。' }}</p><button class="primary full-button" @click="closeModal">开始创作<StudioIcon name="arrow" /></button></template>
  </StudioDialog>
  <div v-if="toast.message" class="toast" :class="{ error: toast.error }" :role="toast.error ? 'alert' : 'status'"><StudioIcon v-if="!toast.error" name="check" /><span>{{ toast.message }}</span><button class="icon-button" aria-label="关闭通知" @click="toast.message = ''">×</button></div>
</template>
