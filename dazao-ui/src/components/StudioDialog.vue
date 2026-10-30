<script setup>
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'
const props = defineProps({ open: Boolean, busy: Boolean, title: String })
const emit = defineEmits(['close'])
const dialog = ref(null)
let previousFocus
watch(() => props.open, async open => {
  await nextTick()
  if (open && !dialog.value.open) {
    previousFocus = document.activeElement
    dialog.value.showModal()
    document.body.style.overflow = 'hidden'
    const initialFocus = dialog.value.querySelector('[autofocus]') || dialog.value.querySelector('input') || dialog.value.querySelector('button')
    initialFocus?.focus()
  } else if (!open && dialog.value.open) {
    dialog.value.close()
    document.body.style.overflow = ''
    if (previousFocus?.isConnected) previousFocus.focus()
  }
})
function close() {
  if (!props.busy) emit('close')
}
function trapFocus(event) {
  const controls = [...dialog.value.querySelectorAll('button:not(:disabled), input:not(:disabled), textarea:not(:disabled), a[href], [tabindex="0"]')].filter(element => element.getClientRects().length)
  const first = controls[0]
  const last = controls[controls.length - 1]
  if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus() }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus() }
}
function outside(event) {
  if (event.target !== dialog.value) return
  const bounds = dialog.value.getBoundingClientRect()
  if (event.clientX < bounds.left || event.clientX > bounds.right || event.clientY < bounds.top || event.clientY > bounds.bottom) close()
}
onBeforeUnmount(() => { document.body.style.overflow = '' })
</script>

<template>
  <dialog ref="dialog" class="studio-dialog" :aria-label="title" @cancel.prevent="close" @click="outside" @keydown.tab="trapFocus">
    <div class="dialog-heading"><span class="eyebrow">大枣 · 角色工作室</span><button class="icon-button" aria-label="关闭对话框" :disabled="busy" @click="close">×</button></div>
    <slot />
  </dialog>
</template>
