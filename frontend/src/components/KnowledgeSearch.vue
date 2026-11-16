<script setup lang="ts">
import { onUnmounted, ref } from "vue";
import {
  ArrowUpRight,
  FileText,
  LoaderCircle,
  Search,
  ShieldCheck,
} from "lucide-vue-next";
import { api } from "../api";
import type { KnowledgeBase, Source } from "../types";

const props = defineProps<{ base: KnowledgeBase; model: string }>();
const emit = defineEmits<{
  open: [documentId: string];
  ask: [question: string];
}>();
const question = ref("");
const searched = ref("");
const sources = ref<Source[]>([]);
const busy = ref(false);
const error = ref("");
let active = true;
onUnmounted(() => {
  active = false;
});
async function search() {
  if (!question.value.trim() || busy.value) return;
  busy.value = true;
  error.value = "";
  sources.value = [];
  searched.value = question.value.trim();
  try {
    const results = await api<Source[]>(
      `/knowledge-bases/${props.base.id}/search`,
      "POST",
      { question: searched.value },
    );
    if (active) sources.value = results;
  } catch (e) {
    if (active)
      error.value = e instanceof Error ? e.message : "检索失败，请稍后重试";
  } finally {
    if (active) busy.value = false;
  }
}
</script>

<template>
  <section class="knowledge-search-page">
    <div class="section-heading">
      <div>
        <span class="eyebrow">FIND THE KNOWLEDGE BEHIND THE ANSWER</span>
        <h1>语义检索</h1>
        <p>在「{{ base.name }}」中进行语义检索，无需调用对话模型。</p>
      </div>
    </div>
    <form class="semantic-search-form" @submit.prevent="search">
      <Search :size="19" /><input
        v-model="question"
        aria-label="输入语义检索问题"
        placeholder="例如：外地出差住酒店可以报销多少钱？"
        maxlength="2000"
        required
        :disabled="busy"
      /><button class="primary" :disabled="busy || !question.trim()">
        <LoaderCircle v-if="busy" :size="16" class="spin" /><Search
          v-else
          :size="16"
        />{{ busy ? "检索中…" : "检索资料" }}
      </button>
    </form>
    <div class="search-model-note">
      <ShieldCheck :size="14" />仅检索有权限的资料<span
        >{{ model }} · 向量相似度排序</span
      >
    </div>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <div v-if="busy" class="search-empty" role="status">
      <LoaderCircle :size="26" class="spin" />
      <p>正在将问题转换为向量，查找相关资料…</p>
    </div>
    <template v-else-if="searched && !error"
      ><div class="search-results-heading">
        「{{ searched }}」<span>找到 {{ sources.length }} 个相关片段</span>
      </div>
      <div v-if="sources.length" class="search-next-step">
        <span>阅读原文，或让知识助手整理答案。</span
        ><button class="secondary" @click="emit('ask', searched)">
          用这个问题提问<ArrowUpRight :size="14" />
        </button>
      </div>
      <article
        v-for="source in sources"
        :key="source.chunkId"
        class="semantic-result"
      >
        <header>
          <FileText :size="17" /><strong>{{ source.documentName }}</strong
          ><span>相似度 {{ source.score.toFixed(3) }}</span>
        </header>
        <p>{{ source.excerpt }}</p>
        <footer>
          <span
            >{{ source.pageNumber ? `第 ${source.pageNumber} 页 · ` : "" }}片段
            {{ source.ordinal }}</span
          ><button class="text-button" @click="emit('open', source.documentId)">
            阅读全文<ArrowUpRight :size="14" />
          </button>
        </footer>
      </article>
      <div v-if="!sources.length" class="search-empty">
        <Search :size="29" /><strong>没有找到足够相关的资料</strong>
        <p>试着补充业务对象，或上传相关资料后再检索。</p>
      </div></template
    >
    <div v-else-if="!error" class="search-empty">
      <Search :size="31" /><strong>从意思出发，找到答案的出处</strong>
      <p>语义检索可以匹配表达不同、含义相近的问题与段落。</p>
    </div>
  </section>
</template>
