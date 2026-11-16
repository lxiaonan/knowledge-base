<script setup lang="ts">
import { computed, onUnmounted, ref } from "vue";
import { renderChatMarkdown } from "../chat-markdown";
import type { Source } from "../types";

const props = withDefaults(
  defineProps<{ content: string; sources?: Source[] }>(),
  { sources: () => [] },
);
const emit = defineEmits<{ source: [source: Source] }>();
const rendered = computed(() =>
  renderChatMarkdown(
    props.content,
    props.sources.map((source) => source.number),
  ),
);
const feedback = ref("");
let feedbackTimer: ReturnType<typeof setTimeout> | undefined;
const resetTimers = new Set<ReturnType<typeof setTimeout>>();
async function clicked(event: MouseEvent) {
  const target = event.target;
  if (!(target instanceof Element)) return;
  const citation = target.closest<HTMLButtonElement>(
    "button[data-source-number]",
  );
  if (citation) {
    const source = props.sources.find(
      (source) => source.number === Number(citation.dataset.sourceNumber),
    );
    if (source) emit("source", source);
    return;
  }
  const button = target.closest<HTMLButtonElement>("button[data-copy-code]");
  if (!button) return;
  const code = rendered.value.codeBlocks[Number(button.dataset.copyCode)];
  if (code === undefined) return;
  try {
    await navigator.clipboard.writeText(code);
    button.textContent = "已复制";
    feedback.value = "代码已复制";
    const timer = setTimeout(() => {
      if (button.isConnected) button.textContent = "复制代码";
      resetTimers.delete(timer);
    }, 1800);
    resetTimers.add(timer);
  } catch {
    feedback.value = "复制失败，请选择代码后手动复制。";
  }
  clearTimeout(feedbackTimer);
  feedbackTimer = setTimeout(() => {
    feedback.value = "";
  }, 2200);
}
onUnmounted(() => {
  clearTimeout(feedbackTimer);
  resetTimers.forEach(clearTimeout);
});
</script>

<template>
  <div class="chat-markdown-wrapper">
    <div
      class="message-content chat-markdown"
      @click="clicked"
      v-html="rendered.html"
    ></div>
    <span class="md-copy-feedback" role="status">{{ feedback }}</span>
  </div>
</template>
