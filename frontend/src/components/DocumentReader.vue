<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from "vue";
import {
  BookOpen,
  Download,
  FileText,
  LoaderCircle,
  RotateCcw,
  X,
} from "lucide-vue-next";
import MarkdownIt from "markdown-it";
import DOMPurify from "dompurify";
import { api } from "../api";
import type { Document, DocumentPreview } from "../types";

const props = defineProps<{ document: Document }>();
const emit = defineEmits<{ close: [] }>();
const preview = ref<DocumentPreview>();
const loading = ref(true);
const error = ref("");
const dialog = ref<HTMLElement>();
const closeButton = ref<HTMLButtonElement>();
const previousFocus = document.activeElement as HTMLElement | null;
const md = new MarkdownIt({
  html: false,
  breaks: true,
  linkify: false,
}).disable("image");
const html = computed(() =>
  preview.value?.format === "md"
    ? DOMPurify.sanitize(
        md.render(preview.value.pages.map((page) => page.content).join("\n\n")),
      )
    : "",
);
let active = true;

async function load() {
  loading.value = true;
  error.value = "";
  try {
    const result = await api<DocumentPreview>(
      `/documents/${props.document.id}/preview`,
    );
    if (active) preview.value = result;
  } catch (e) {
    if (active)
      error.value = e instanceof Error ? e.message : "读取失败，请稍后重试";
  } finally {
    if (active) loading.value = false;
  }
}
function keydown(event: KeyboardEvent) {
  if (event.key === "Escape") {
    event.preventDefault();
    event.stopPropagation();
    emit("close");
  }
  if (event.key === "Tab") {
    const controls = Array.from(
      dialog.value?.querySelectorAll<HTMLElement>(
        "button:not([disabled]), a[href], [tabindex='0']",
      ) || [],
    ).filter((element) => element.offsetParent !== null);
    const first = controls[0],
      last = controls[controls.length - 1];
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last?.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first?.focus();
    }
  }
}
onMounted(async () => {
  window.addEventListener("keydown", keydown, true);
  await nextTick();
  closeButton.value?.focus();
  void load();
});
onUnmounted(() => {
  active = false;
  window.removeEventListener("keydown", keydown, true);
  void nextTick(() => previousFocus?.isConnected && previousFocus.focus());
});
</script>

<template>
  <div class="reader-backdrop" @click.self="emit('close')">
    <section
      ref="dialog"
      class="document-reader"
      role="dialog"
      aria-modal="true"
      aria-labelledby="reader-title"
      :aria-busy="loading"
    >
      <header class="reader-header">
        <div class="reader-file-icon"><FileText :size="23" /></div>
        <div class="reader-heading">
          <span>文档在线浏览</span>
          <h2 id="reader-title">{{ document.name }}</h2>
        </div>
        <a
          class="secondary reader-download"
          aria-label="下载原文"
          title="下载原文"
          :href="`/api/documents/${document.id}/file`"
          ><Download :size="15" /><span>下载原文</span></a
        >
        <button
          ref="closeButton"
          class="icon-button reader-close"
          aria-label="关闭文档预览"
          title="关闭（Esc）"
          @click="emit('close')"
        >
          <X :size="21" />
        </button>
      </header>
      <div class="reader-scroll" tabindex="0" aria-label="文档正文">
        <div v-if="loading" class="reader-state" role="status">
          <LoaderCircle :size="25" class="spin" /><strong>正在读取文档…</strong>
          <p>正在为你准备全文。</p>
        </div>
        <div v-else-if="error" class="reader-state" role="alert">
          <FileText :size="28" /><strong>暂时无法预览</strong>
          <p>{{ error }}</p>
          <button class="secondary" @click="load">
            <RotateCcw :size="14" />重新加载
          </button>
        </div>
        <template v-else-if="preview">
          <div
            v-if="preview.format === 'docx' || preview.format === 'pdf'"
            class="reader-format-note"
          >
            <BookOpen
              :size="14"
            />当前展示文档文字内容，原始排版和图片请下载原文查看。
          </div>
          <article
            v-if="preview.format === 'md'"
            class="reader-paper markdown"
            v-html="html"
          ></article>
          <template v-else
            ><article
              v-for="(page, index) in preview.pages"
              :key="index"
              class="reader-paper"
            >
              <div v-if="page.page" class="reader-page-label">
                第 {{ page.page }} 页
              </div>
              <div class="reader-plain">
                {{ page.content || "（本页未提取到文字）" }}
              </div>
            </article></template
          >
        </template>
      </div>
      <footer class="reader-footer">
        <BookOpen :size="13" /><span
          >全文阅读 ·
          {{
            preview?.format.toUpperCase() ||
            document.name.split(".").pop()?.toUpperCase()
          }}<template v-if="preview?.format === 'pdf'">
            · {{ preview.pages.length }} 页</template
          ></span
        ><span>按 Esc 关闭</span>
      </footer>
    </section>
  </div>
</template>
