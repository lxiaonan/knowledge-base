<script setup lang="ts">
import { nextTick, onMounted, onUnmounted, ref } from "vue";
import { Check, ChevronDown, FolderClosed, Plus } from "lucide-vue-next";
import type { KnowledgeBase } from "../types";

defineProps<{
  bases: KnowledgeBase[];
  selectedId: string;
  disabled: boolean;
}>();
const emit = defineEmits<{ select: [id: string]; create: [] }>();
const open = ref(false);
const root = ref<HTMLElement>();
const trigger = ref<HTMLButtonElement>();
const panel = ref<HTMLElement>();
function select(id: string) {
  open.value = false;
  emit("select", id);
}
function create() {
  open.value = false;
  emit("create");
}
function outside(event: PointerEvent) {
  if (event.target instanceof Node && !root.value?.contains(event.target))
    open.value = false;
}
function escaped(event: KeyboardEvent) {
  if (open.value && event.key === "Escape") {
    event.preventDefault();
    event.stopPropagation();
    open.value = false;
    trigger.value?.focus();
  }
}
function focusOut(event: FocusEvent) {
  if (
    event.relatedTarget instanceof Node &&
    !root.value?.contains(event.relatedTarget)
  )
    open.value = false;
}
async function focusMenu() {
  open.value = true;
  await nextTick();
  panel.value?.querySelector<HTMLButtonElement>("button")?.focus();
}
onMounted(() => {
  document.addEventListener("pointerdown", outside);
  window.addEventListener("keydown", escaped, true);
});
onUnmounted(() => {
  document.removeEventListener("pointerdown", outside);
  window.removeEventListener("keydown", escaped, true);
});
</script>

<template>
  <div ref="root" class="workspace-menu" @focusout="focusOut">
    <button
      ref="trigger"
      type="button"
      class="workspace-switch"
      :class="{ expanded: open }"
      aria-label="企业知识空间，切换知识库"
      :aria-expanded="open"
      aria-controls="workspace-knowledge-options"
      :disabled="disabled"
      @click="open = !open"
      @keydown.down.prevent="focusMenu"
    >
      <span class="workspace-icon">企</span
      ><span class="workspace-switch-info"
        ><strong>企业知识空间</strong
        ><small>切换知识库 · 共 {{ bases.length }} 个</small></span
      ><ChevronDown :size="15" />
    </button>
    <section
      v-if="open"
      id="workspace-knowledge-options"
      ref="panel"
      class="workspace-dropdown"
      aria-label="知识库切换"
    >
      <div class="workspace-dropdown-heading">
        <strong>当前企业空间</strong><span>当前仅有一个企业空间</span>
      </div>
      <div class="workspace-dropdown-label">切换知识库</div>
      <nav aria-label="可切换的知识库">
        <button
          v-for="base in bases"
          :key="base.id"
          type="button"
          :class="{ selected: base.id === selectedId }"
          :aria-current="base.id === selectedId ? 'true' : undefined"
          @click="select(base.id)"
        >
          <FolderClosed :size="16" /><span>{{ base.name }}</span
          ><Check v-if="base.id === selectedId" :size="14" />
        </button>
        <p v-if="!bases.length">还没有可访问的知识库</p>
      </nav>
      <button type="button" class="workspace-create" @click="create">
        <Plus :size="16" />创建知识库
      </button>
    </section>
  </div>
</template>
