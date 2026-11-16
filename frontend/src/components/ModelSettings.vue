<script setup lang="ts">
import { nextTick, onMounted, onUnmounted, ref } from "vue";
import {
  CheckCircle2,
  Eye,
  EyeOff,
  KeyRound,
  LoaderCircle,
  Save,
  Settings,
  ShieldCheck,
  Trash2,
  X,
  Zap,
} from "lucide-vue-next";
import { api } from "../api";

interface SettingsView {
  baseUrl: string;
  model: string;
  keyConfigured: boolean;
}
const emit = defineEmits<{ close: []; changed: [] }>();
const baseUrl = ref("https://ai-api.m2time.com");
const model = ref("gpt-6.1-sol");
const apiKey = ref("");
const configured = ref(false);
const visible = ref(false);
const loading = ref(true);
const pending = ref<"save" | "test" | "clear" | null>(null);
const error = ref("");
const success = ref("");
const dialog = ref<HTMLElement>();
const closeButton = ref<HTMLButtonElement>();
const previousFocus = document.activeElement as HTMLElement | null;
let active = true;
function apply(view: SettingsView) {
  baseUrl.value = view.baseUrl;
  model.value = view.model;
  configured.value = view.keyConfigured;
}
function input() {
  return {
    baseUrl: baseUrl.value.trim(),
    model: model.value.trim(),
    apiKey: apiKey.value.trim(),
  };
}
function edited() {
  success.value = "";
  error.value = "";
}
async function load() {
  loading.value = true;
  error.value = "";
  try {
    const view = await api<SettingsView>("/settings/chat");
    if (active) apply(view);
  } catch (e) {
    if (active) error.value = e instanceof Error ? e.message : "读取设置失败";
  } finally {
    if (active) loading.value = false;
  }
}
async function save() {
  pending.value = "save";
  error.value = "";
  success.value = "";
  try {
    apply(await api<SettingsView>("/settings/chat", "PUT", input()));
    apiKey.value = "";
    visible.value = false;
    success.value = "设置已保存，立即生效，无需重启。";
    emit("changed");
  } catch (e) {
    error.value = e instanceof Error ? e.message : "保存失败，请重试";
  } finally {
    pending.value = null;
  }
}
async function test() {
  pending.value = "test";
  error.value = "";
  success.value = "";
  try {
    const result = await api<{ message: string }>(
      "/settings/chat/test",
      "POST",
      input(),
    );
    success.value = result.message + "。如修改过配置，请点击保存后应用。";
  } catch (e) {
    error.value = e instanceof Error ? e.message : "连接测试失败";
  } finally {
    pending.value = null;
  }
}
async function clear() {
  if (
    !confirm("确定清除当前对话 API 密钥？清除后仍可检索资料，AI 回答将暂停。")
  )
    return;
  pending.value = "clear";
  error.value = "";
  success.value = "";
  try {
    apply(await api<SettingsView>("/settings/chat/key", "DELETE"));
    apiKey.value = "";
    visible.value = false;
    success.value = "密钥已清除，资料检索仍可使用。";
    emit("changed");
  } catch (e) {
    error.value = e instanceof Error ? e.message : "清除失败，请重试";
  } finally {
    pending.value = null;
  }
}
function close() {
  if (!pending.value) emit("close");
}
function keydown(event: KeyboardEvent) {
  if (event.key === "Escape") {
    event.preventDefault();
    event.stopPropagation();
    close();
  }
  if (event.key === "Tab") {
    const controls = Array.from(
      dialog.value?.querySelectorAll<HTMLElement>(
        "button:not([disabled]), input:not([disabled]), a[href]",
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
  apiKey.value = "";
  window.removeEventListener("keydown", keydown, true);
  void nextTick(() => previousFocus?.isConnected && previousFocus.focus());
});
</script>

<template>
  <div class="modal-backdrop" @click.self="close">
    <section
      ref="dialog"
      class="modal model-settings"
      role="dialog"
      aria-modal="true"
      aria-labelledby="settings-title"
      :aria-busy="loading || !!pending"
    >
      <button
        ref="closeButton"
        class="icon-button modal-close"
        aria-label="关闭设置"
        :disabled="!!pending"
        @click="close"
      >
        <X :size="20" />
      </button>
      <span class="modal-symbol"><Settings :size="25" /></span>
      <div class="settings-title-row">
        <h2 id="settings-title">设置 · 对话模型</h2>
        <span class="settings-status" :class="{ configured }"
          ><i></i>{{ configured ? "密钥已配置" : "等待配置密钥" }}</span
        >
      </div>
      <p class="muted">
        连接团队使用的 AI 服务，让检索到的知识成为有依据的回答。
      </p>
      <div v-if="loading" class="settings-loading" role="status">
        <LoaderCircle :size="22" class="spin" />正在读取配置…
      </div>
      <form v-else @submit.prevent="save" @input="edited">
        <fieldset :disabled="!!pending" class="settings-fields">
          <label for="chat-base-url">API 地址</label
          ><input
            id="chat-base-url"
            v-model="baseUrl"
            type="url"
            placeholder="https://ai-api.m2time.com"
            required
            maxlength="500"
            spellcheck="false"
          />
          <p class="settings-hint">填写服务根地址，不包含 /v1。</p>
          <label for="chat-model">模型名称</label
          ><input
            id="chat-model"
            v-model="model"
            placeholder="gpt-6.1-sol"
            required
            maxlength="120"
            spellcheck="false"
          />
          <label for="chat-api-key"
            >API 密钥<span v-if="configured" class="optional"
              >留空保留已保存的密钥</span
            ></label
          >
          <div class="settings-key-field">
            <KeyRound :size="16" /><input
              id="chat-api-key"
              v-model="apiKey"
              :type="visible ? 'text' : 'password'"
              :placeholder="
                configured ? '已配置，输入新密钥可替换' : '粘贴你的 API 密钥'
              "
              maxlength="4096"
              autocomplete="new-password"
              spellcheck="false"
              autocapitalize="off"
            /><button
              type="button"
              class="icon-button"
              :aria-label="visible ? '隐藏密钥' : '显示密钥'"
              @click="visible = !visible"
            >
              <EyeOff v-if="visible" :size="16" /><Eye v-else :size="16" />
            </button>
          </div>
          <p class="settings-hint">
            <ShieldCheck :size="12" />仅管理员可配置，密钥加密保存且不会回显。
          </p>
          <div class="settings-embedding-note">
            <ShieldCheck :size="17" />
            <div>
              <strong>文档向量继续在本地生成</strong>
              <p>这里仅配置对话服务，本地 BGE-M3 和资料检索可独立运行。</p>
            </div>
          </div>
        </fieldset>
        <p v-if="error" class="form-error" role="alert">{{ error }}</p>
        <p v-if="success" class="settings-success" role="status">
          <CheckCircle2 :size="16" />{{ success }}
        </p>
        <div class="settings-actions">
          <button
            type="button"
            class="secondary"
            :disabled="!!pending || loading || (!configured && !apiKey.trim())"
            @click="test"
          >
            <LoaderCircle
              v-if="pending === 'test'"
              :size="15"
              class="spin"
            /><Zap v-else :size="15" />{{
              pending === "test" ? "正在连接…" : "测试连接"
            }}</button
          ><button class="primary" :disabled="!!pending || loading">
            <LoaderCircle
              v-if="pending === 'save'"
              :size="15"
              class="spin"
            /><Save v-else :size="15" />{{
              pending === "save" ? "保存中…" : "保存设置"
            }}
          </button>
        </div>
        <div class="settings-bottom">
          <span>测试仅发送一条简短消息，不携带知识库资料。</span
          ><button
            v-if="configured"
            type="button"
            :disabled="!!pending"
            @click="clear"
          >
            <Trash2 :size="12" />清除密钥
          </button>
        </div>
      </form>
    </section>
  </div>
</template>
