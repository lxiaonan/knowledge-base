<script setup lang="ts">
import {
  computed,
  defineAsyncComponent,
  nextTick,
  onMounted,
  onUnmounted,
  ref,
  watch,
} from "vue";
import {
  ArrowUp,
  ArrowDown,
  ArrowUpRight,
  BookOpen,
  Check,
  Copy,
  CheckCircle2,
  ChevronDown,
  ChevronRight,
  CircleHelp,
  Download,
  Database,
  Eye,
  FileText,
  FolderClosed,
  Layers,
  LoaderCircle,
  LogOut,
  Menu,
  MessageSquare,
  Plus,
  Search,
  Settings,
  ShieldCheck,
  Sparkles,
  Trash2,
  Upload,
  Users,
  X,
  RotateCcw,
  RefreshCw,
  PanelRightClose,
} from "lucide-vue-next";
import DocumentReader from "./components/DocumentReader.vue";
import KnowledgeSearch from "./components/KnowledgeSearch.vue";
import WorkspaceMenu from "./components/WorkspaceMenu.vue";
import { api, ApiError, csrf, streamChat } from "./api";
import type {
  Chunk,
  Conversation,
  Document,
  KnowledgeBase,
  Message,
  Source,
  System,
  User,
} from "./types";

const ChatMarkdown = defineAsyncComponent(
  () => import("./components/ChatMarkdown.vue"),
);
const ModelSettings = defineAsyncComponent(
  () => import("./components/ModelSettings.vue"),
);
const DataBrowser = defineAsyncComponent(
  () => import("./components/DataBrowser.vue"),
);

const system = ref<System>();
const user = ref<User>();
const loading = ref(true);
const loginBusy = ref(false);
const loginError = ref("");
const username = ref("");
const password = ref("");
const bases = ref<KnowledgeBase[]>([]);
const selectedBaseId = ref("");
const base = computed(() =>
  bases.value.find((b) => b.id === selectedBaseId.value),
);
const documents = ref<Document[]>([]);
const conversations = ref<Conversation[]>([]);
const conversationId = ref("");
const messages = ref<Message[]>([]);
const tab = ref<"chat" | "documents" | "search">("chat");
const question = ref("");
const useContext = ref(false);
const busy = ref(false);
const uploading = ref(false);
const uploadProgress = ref({ current: 0, total: 0, name: "" });
const dragDepth = ref(0);
const search = ref("");
const documentFilter = ref("all");
const documentSort = ref("newest");
const baseLoading = ref(false);
const baseError = ref("");
const refreshingDocuments = ref(false);
const followingChat = ref(true);
const copiedMessageId = ref("");
const sidebarOpen = ref(false);
const mobileQuery = window.matchMedia("(max-width: 760px)");
const isMobile = ref(mobileQuery.matches);
const toast = ref("");
const modal = ref<"base" | "members" | "user" | "help" | null>(null);
const modalBusy = ref(false);
const formError = ref("");
const baseName = ref("");
const baseDescription = ref("");
const members = ref<User[]>([]);
const memberUsername = ref("");
const newUsername = ref(""),
  newDisplayName = ref(""),
  newPassword = ref("");
const inspected = ref<{ source: Source; chunk: Chunk }>();
const readingDocument = ref<Document>();
const settingsOpen = ref(false);
const dataOpen = ref(false);
async function settingsChanged() {
  try {
    system.value = await api<System>("/system");
  } catch (e) {
    notify(errorText(e));
  }
}
function openSettings() {
  settingsOpen.value = true;
  sidebarOpen.value = false;
}
function openData() {
  dataOpen.value = true;
  sidebarOpen.value = false;
}
function selectWorkspaceBase(id: string) {
  dataOpen.value = false;
  selectedBaseId.value = id;
  tab.value = "chat";
  sidebarOpen.value = false;
}
const fileInput = ref<HTMLInputElement>();
const chatScroll = ref<HTMLElement>();
const questionInput = ref<HTMLTextAreaElement>();
let refreshTimer: ReturnType<typeof setInterval> | undefined;
let toastTimer: ReturnType<typeof setTimeout> | undefined;
let copyTimer: ReturnType<typeof setTimeout> | undefined;
let scrollFrame = 0;
let refreshDocumentsAgain = false;
let workspaceVersion = 0;
let sourceVersion = 0;
let historyVersion = 0;
let controller: AbortController | undefined;
let selection = 0;
let focusBeforeModal: HTMLElement | null = null;
const filteredDocuments = computed(() => {
  const query = search.value.trim().toLowerCase();
  return documents.value
    .filter(
      (d) =>
        d.name.toLowerCase().includes(query) &&
        (documentFilter.value === "all" ||
          (documentFilter.value === "processing"
            ? ["PROCESSING", "QUEUED"].includes(d.status)
            : d.status === documentFilter.value)),
    )
    .sort((a, b) =>
      documentSort.value === "name"
        ? a.name.localeCompare(b.name, "zh-CN", { numeric: true })
        : documentSort.value === "size"
          ? b.sizeBytes - a.sizeBytes
          : Date.parse(b.createdAt) - Date.parse(a.createdAt),
    );
});
const readyCount = computed(
  () => documents.value.filter((d) => d.status === "READY").length,
);
const processingCount = computed(
  () =>
    documents.value.filter((d) => ["PROCESSING", "QUEUED"].includes(d.status))
      .length,
);
const failedCount = computed(
  () => documents.value.filter((d) => d.status === "FAILED").length,
);
const documentFilters = computed(() => [
  { value: "all", label: "全部资料", count: documents.value.length },
  { value: "READY", label: "可检索", count: readyCount.value },
  { value: "processing", label: "处理中", count: processingCount.value },
  { value: "FAILED", label: "需处理", count: failedCount.value },
]);
const recentDocuments = computed(() =>
  documents.value
    .filter((d) => d.status === "READY")
    .sort((a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt))
    .slice(0, 3),
);
const totalChunks = computed(() =>
  documents.value.reduce((sum, d) => sum + d.chunkCount, 0),
);
const conversationTitle = computed(
  () => conversations.value.find((c) => c.id === conversationId.value)?.title,
);
const examples = [
  { label: "费用与报销", question: "出差住宿费的标准是多少？", icon: "01" },
  { label: "假勤与福利", question: "年假需要提前几天申请？", icon: "02" },
  { label: "入职与协作", question: "新员工第一周需要完成什么？", icon: "03" },
];
const errorText = (e: unknown) =>
  e instanceof Error ? e.message : "操作失败，请稍后重试";
const formatSize = (size: number) =>
  size < 1024
    ? `${size} B`
    : size < 1024 * 1024
      ? `${(size / 1024).toFixed(1)} KB`
      : `${(size / 1024 / 1024).toFixed(1)} MB`;
const formatDate = (date: string) =>
  new Intl.DateTimeFormat("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  }).format(new Date(date));
const statusLabel = (status: Document["status"]) =>
  ({
    QUEUED: "等待处理",
    PROCESSING: "解析中",
    READY: "可检索",
    FAILED: "处理失败",
  })[status];

function notify(message: string) {
  toast.value = message;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => {
    toast.value = "";
  }, 4500);
}
async function loadWorkspace() {
  const version = ++workspaceVersion;
  const value = await api<KnowledgeBase[]>("/knowledge-bases");
  if (version !== workspaceVersion || !user.value) return;
  bases.value = value;
  if (!bases.value.some((b) => b.id === selectedBaseId.value))
    selectedBaseId.value = bases.value[0]?.id || "";
}
async function loadBase(id: string) {
  const version = ++selection;
  historyVersion++;
  sourceVersion++;
  baseLoading.value = !!id;
  baseError.value = "";
  search.value = "";
  documentFilter.value = "all";
  dragDepth.value = 0;
  question.value = "";
  followingChat.value = true;
  documents.value = [];
  conversations.value = [];
  messages.value = [];
  conversationId.value = "";
  inspected.value = undefined;
  if (!id) return;
  try {
    const [docs, chats] = await Promise.all([
      api<Document[]>(`/knowledge-bases/${id}/documents`),
      api<Conversation[]>(`/knowledge-bases/${id}/conversations`),
    ]);
    if (version !== selection) return;
    documents.value = docs;
    conversations.value = chats;
  } catch (e) {
    if (version === selection) baseError.value = errorText(e);
  } finally {
    if (version === selection) baseLoading.value = false;
  }
}
watch(selectedBaseId, (id) => {
  dataOpen.value = false;
  void loadBase(id);
  sidebarOpen.value = false;
});
watch(tab, (value) => {
  if (value === "documents") void refreshDocuments();
});
watch(modal, async (value, previous) => {
  if (value && !previous)
    focusBeforeModal = document.activeElement as HTMLElement;
  await nextTick();
  if (value)
    document
      .querySelector<HTMLElement>(".modal input, .modal textarea, .modal-close")
      ?.focus();
  else focusBeforeModal?.focus();
});
async function login() {
  loginBusy.value = true;
  loginError.value = "";
  try {
    await csrf();
    user.value = await api<User>("/auth/login", "POST", {
      username: username.value,
      password: password.value,
    });
    password.value = "";
    await csrf();
    await loadWorkspace();
  } catch (e) {
    loginError.value = errorText(e);
  } finally {
    loginBusy.value = false;
  }
}
function demoLogin() {
  username.value = "admin";
  password.value = "DemoAdmin123!";
  void login();
}
async function logout() {
  if (busy.value || uploading.value) return;
  try {
    await api("/auth/logout", "POST");
    user.value = undefined;
    selectedBaseId.value = "";
    bases.value = [];
    await csrf();
  } catch (e) {
    notify(errorText(e));
  }
}
function expired() {
  workspaceVersion++;
  user.value = undefined;
  selectedBaseId.value = "";
  controller?.abort();
}
async function refreshDocuments(manual = false) {
  const id = selectedBaseId.value;
  if (!id || !user.value || baseLoading.value) return;
  if (refreshingDocuments.value) {
    refreshDocumentsAgain = true;
    return;
  }
  const version = selection;
  refreshingDocuments.value = true;
  try {
    const docs = await api<Document[]>(`/knowledge-bases/${id}/documents`);
    if (id === selectedBaseId.value && version === selection) {
      documents.value = docs;
      const current = bases.value.find((item) => item.id === id);
      if (current) {
        current.documentCount = docs.length;
        current.readyCount = docs.filter((d) => d.status === "READY").length;
      }
    }
  } catch (e) {
    if (manual) notify(errorText(e));
    if (!(e instanceof ApiError && e.status === 401))
      console.warn("Document status refresh failed");
  } finally {
    refreshingDocuments.value = false;
    if (refreshDocumentsAgain) {
      refreshDocumentsAgain = false;
      void refreshDocuments();
    }
  }
}
async function uploadFiles(files: FileList | File[] | null) {
  if (!files?.length || uploading.value || !base.value) return;
  const id = base.value.id;
  uploading.value = true;
  uploadProgress.value = { current: 0, total: files.length, name: "" };
  let count = 0;
  const failures: string[] = [];
  try {
    for (const file of Array.from(files)) {
      uploadProgress.value.name = file.name;
      try {
        if (!/\.(pdf|docx|md|txt)$/i.test(file.name))
          throw new Error(`${file.name}：仅支持 PDF、DOCX、MD、TXT`);
        if (file.size > 20 * 1024 * 1024)
          throw new Error(`${file.name}：文件不能超过 20 MB`);
        if (!file.size) throw new Error(`${file.name}：文件内容为空`);
        const form = new FormData();
        form.append("file", file);
        await api(`/knowledge-bases/${id}/documents`, "POST", form);
        count++;
      } catch (e) {
        failures.push(errorText(e));
        if (e instanceof ApiError && [401, 403].includes(e.status)) break;
      }
      uploadProgress.value.current++;
    }
    notify(
      failures.length
        ? `已上传 ${count} 份，${files.length - count} 份未上传。${failures[0]}`
        : `${count} 份文档已上传，正在后台处理`,
    );
  } finally {
    uploading.value = false;
    if (fileInput.value) fileInput.value.value = "";
    await refreshDocuments();
    if (user.value) {
      try {
        await loadWorkspace();
      } catch (e) {
        notify(errorText(e));
      }
    }
  }
}
function dropped(event: DragEvent) {
  event.preventDefault();
  dragDepth.value = 0;
  void uploadFiles(event.dataTransfer?.files || null);
}
async function retryDocument(document: Document) {
  try {
    await api(`/documents/${document.id}/retry`, "POST");
    await refreshDocuments();
    notify("已重新加入处理队列");
  } catch (e) {
    notify(errorText(e));
  }
}
async function deleteDocument(document: Document) {
  if (
    !confirm(
      `确定删除「${document.name}」？文档及其检索片段将被移除，历史回答保留。`,
    )
  )
    return;
  try {
    await api(`/documents/${document.id}`, "DELETE");
    await refreshDocuments();
    await loadWorkspace();
    notify("文档已删除");
  } catch (e) {
    notify(errorText(e));
  }
}
function newChat() {
  if (busy.value) return;
  historyVersion++;
  sourceVersion++;
  dataOpen.value = false;
  messages.value = [];
  conversationId.value = "";
  inspected.value = undefined;
  question.value = "";
  followingChat.value = true;
  tab.value = "chat";
  sidebarOpen.value = false;
  void nextTick(() => questionInput.value?.focus());
}
async function openConversation(conversation: Conversation) {
  if (busy.value) return;
  dataOpen.value = false;
  const version = ++historyVersion;
  const baseId = selectedBaseId.value;
  try {
    const history = await api<Message[]>(
      `/conversations/${conversation.id}/messages`,
    );
    if (version !== historyVersion || baseId !== selectedBaseId.value) return;
    messages.value = history;
    conversationId.value = conversation.id;
    tab.value = "chat";
    sidebarOpen.value = false;
    inspected.value = undefined;
    sourceVersion++;
    await scrollBottom(true);
  } catch (e) {
    if (version === historyVersion && baseId === selectedBaseId.value)
      notify(errorText(e));
  }
}
function trackChatScroll() {
  const el = chatScroll.value;
  if (el)
    followingChat.value = el.scrollHeight - el.scrollTop - el.clientHeight < 96;
}
async function scrollBottom(force = false) {
  if (force) followingChat.value = true;
  if (!followingChat.value) return;
  await nextTick();
  if (scrollFrame) return;
  scrollFrame = requestAnimationFrame(() => {
    scrollFrame = 0;
    if (followingChat.value && chatScroll.value)
      chatScroll.value.scrollTop = chatScroll.value.scrollHeight;
  });
}
async function copyMessage(message: Message) {
  try {
    await navigator.clipboard.writeText(message.content);
    copiedMessageId.value = message.id;
    clearTimeout(copyTimer);
    copyTimer = setTimeout(() => {
      copiedMessageId.value = "";
    }, 2000);
  } catch {
    notify("复制失败，请选择回答文字复制。");
  }
}
function retryMessage(index: number) {
  const previous = messages.value[index - 1];
  if (busy.value || previous?.role !== "user") return;
  question.value = previous.content;
  void nextTick(() => questionInput.value?.focus());
}
function clearDocumentFilters() {
  search.value = "";
  documentFilter.value = "all";
}
async function browseDocuments(upload = false) {
  tab.value = "documents";
  await nextTick();
  if (upload) fileInput.value?.click();
}
async function askSearchQuestion(value: string) {
  if (busy.value) {
    notify("当前回答正在生成，请完成后再提问。");
    return;
  }
  newChat();
  question.value = value;
  await nextTick();
  questionInput.value?.focus();
}
async function send(value = question.value) {
  if (
    !value.trim() ||
    busy.value ||
    !base.value ||
    baseLoading.value ||
    baseError.value
  )
    return;
  if (value.trim().length > 2000) {
    notify("问题请控制在 2000 字以内");
    return;
  }
  busy.value = true;
  historyVersion++;
  sourceVersion++;
  followingChat.value = true;
  question.value = "";
  inspected.value = undefined;
  tab.value = "chat";
  const id = base.value.id;
  try {
    if (!conversationId.value) {
      const conversation = await api<Conversation>(
        `/knowledge-bases/${id}/conversations`,
        "POST",
      );
      conversationId.value = conversation.id;
    }
    messages.value.push({
      id: crypto.randomUUID(),
      role: "user",
      content: value.trim(),
      sources: [],
    });
    const response = ref<Message>({
      id: crypto.randomUUID(),
      role: "assistant",
      content: "",
      sources: [],
    });
    messages.value.push(response.value);
    controller = new AbortController();
    await scrollBottom();
    try {
      await streamChat(
        conversationId.value,
        value.trim(),
        controller.signal,
        {
          context: (context) => {
            response.value.context = context;
          },
          status: (message) => {
            response.value.phase = message;
          },
          sources: (sources) => {
            response.value.sources = sources;
          },
          token: (text) => {
            response.value.content += text;
            void scrollBottom();
          },
          done: (message) => {
            Object.assign(response.value, message);
          },
        },
        useContext.value,
      );
    } catch (e) {
      response.value.error = errorText(e);
    }
    const chats = await api<Conversation[]>(
      `/knowledge-bases/${id}/conversations`,
    );
    if (id === selectedBaseId.value && user.value) conversations.value = chats;
  } catch (e) {
    notify(errorText(e));
    question.value = value;
  } finally {
    busy.value = false;
    controller = undefined;
    await scrollBottom();
    await nextTick();
    questionInput.value?.focus();
  }
}
async function inspect(source: Source) {
  const version = ++sourceVersion;
  try {
    const chunk = await api<Chunk>(`/sources/${source.chunkId}`);
    if (version === sourceVersion) inspected.value = { source, chunk };
  } catch (e) {
    if (version === sourceVersion) notify(errorText(e));
  }
}
function showModal(value: typeof modal.value) {
  modal.value = value;
  formError.value = "";
}
async function createBase() {
  modalBusy.value = true;
  formError.value = "";
  try {
    const created = await api<KnowledgeBase>("/knowledge-bases", "POST", {
      name: baseName.value,
      description: baseDescription.value,
    });
    await loadWorkspace();
    selectedBaseId.value = created.id;
    baseName.value = "";
    baseDescription.value = "";
    modal.value = null;
    tab.value = "documents";
  } catch (e) {
    formError.value = errorText(e);
  } finally {
    modalBusy.value = false;
  }
}
async function showMembers() {
  if (!base.value) return;
  showModal("members");
  try {
    members.value = await api<User[]>(
      `/knowledge-bases/${base.value.id}/members`,
    );
  } catch (e) {
    formError.value = errorText(e);
  }
}
async function addMember() {
  if (!base.value) return;
  modalBusy.value = true;
  formError.value = "";
  try {
    await api(`/knowledge-bases/${base.value.id}/members`, "POST", {
      username: memberUsername.value,
    });
    memberUsername.value = "";
    await showMembers();
  } catch (e) {
    formError.value = errorText(e);
  } finally {
    modalBusy.value = false;
  }
}
async function removeMember(member: User) {
  try {
    await api(
      `/knowledge-bases/${base.value!.id}/members/${member.id}`,
      "DELETE",
    );
    await showMembers();
  } catch (e) {
    formError.value = errorText(e);
  }
}
async function createUser() {
  modalBusy.value = true;
  formError.value = "";
  try {
    await api("/users", "POST", {
      username: newUsername.value,
      displayName: newDisplayName.value,
      password: newPassword.value,
    });
    modal.value = null;
    newUsername.value = "";
    newDisplayName.value = "";
    newPassword.value = "";
    notify("成员账号已创建，可在知识库中授予访问权限");
  } catch (e) {
    formError.value = errorText(e);
  } finally {
    modalBusy.value = false;
  }
}
function keydown(event: KeyboardEvent) {
  if (event.key === "Enter" && !event.shiftKey && !event.isComposing) {
    event.preventDefault();
    void send();
  }
}
function escape(event: KeyboardEvent) {
  if (event.key === "Escape") {
    if (!modalBusy.value) modal.value = null;
    sourceVersion++;
    inspected.value = undefined;
    sidebarOpen.value = false;
  }
  if (event.key === "Tab" && modal.value) {
    const controls = Array.from(
      document.querySelectorAll<HTMLElement>(
        ".modal button:not([disabled]), .modal input, .modal textarea, .modal a[href]",
      ),
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
function viewportChanged(event: MediaQueryListEvent) {
  isMobile.value = event.matches;
}
onMounted(async () => {
  window.addEventListener("session-expired", expired);
  window.addEventListener("keydown", escape);
  mobileQuery.addEventListener("change", viewportChanged);
  try {
    system.value = await api<System>("/system");
    await csrf();
    try {
      user.value = await api<User>("/auth/me");
      await loadWorkspace();
    } catch (e) {
      if (!(e instanceof ApiError && e.status === 401)) throw e;
    }
  } catch (e) {
    loginError.value = errorText(e);
  } finally {
    loading.value = false;
  }
  refreshTimer = setInterval(() => {
    if (!document.hidden && processingCount.value > 0) void refreshDocuments();
  }, 4000);
  window.addEventListener("focus", refreshOnFocus);
});
function refreshOnFocus() {
  void refreshDocuments();
}
onUnmounted(() => {
  clearInterval(refreshTimer);
  clearTimeout(toastTimer);
  clearTimeout(copyTimer);
  cancelAnimationFrame(scrollFrame);
  controller?.abort();
  window.removeEventListener("session-expired", expired);
  window.removeEventListener("keydown", escape);
  mobileQuery.removeEventListener("change", viewportChanged);
  window.removeEventListener("focus", refreshOnFocus);
});
</script>

<template>
  <div v-if="loading" class="boot-screen">
    <div class="brand-mark"><BookOpen :size="26" /></div>
    <LoaderCircle class="spin" :size="22" /><span>正在连接你的知识空间…</span>
  </div>
  <div v-else-if="!user" class="login-page">
    <section class="login-story">
      <a href="/" class="brand"
        ><span class="brand-mark"><BookOpen :size="25" /></span
        ><span>知序<span class="brand-en">ZHIXU</span></span></a
      >
      <div class="login-story-body">
        <span class="eyebrow light">KNOWLEDGE, CONNECTED.</span>
        <h1>让沉淀的知识，<br />开始流动。</h1>
        <p>把散落在文档中的经验与答案，<br />连接到每一次需要它的时刻。</p>
        <div class="story-graphic" aria-hidden="true">
          <div class="graphic-sheet sheet-back">
            <span></span><span></span><span></span>
          </div>
          <div class="graphic-sheet sheet-front">
            <BookOpen :size="30" /><span></span><span></span><span></span
            ><i>让每一个答案，有据可循。</i>
          </div>
          <div class="graphic-dot"><Sparkles :size="24" /></div>
        </div>
      </div>
      <span class="login-foot">企业知识，从有序开始。</span>
    </section>
    <section class="login-form-area">
      <form class="login-form" @submit.prevent="login">
        <span class="eyebrow">YOUR TEAM'S SECOND BRAIN</span>
        <h2>欢迎回到知识空间</h2>
        <p class="muted">登录后，开启有依据的每一次探索。</p>
        <label for="username">账号</label
        ><input
          id="username"
          v-model="username"
          autocomplete="username"
          placeholder="输入你的账号"
          required
          maxlength="64"
        /><label for="password">密码</label
        ><input
          id="password"
          v-model="password"
          type="password"
          autocomplete="current-password"
          placeholder="输入你的密码"
          required
          maxlength="128"
        />
        <p v-if="loginError" role="alert" class="form-error">
          {{ loginError }}
        </p>
        <button class="primary login-submit" :disabled="loginBusy">
          <LoaderCircle v-if="loginBusy" :size="17" class="spin" /><span>{{
            loginBusy ? "正在登录" : "进入知识空间"
          }}</span
          ><ArrowUpRight :size="18" /></button
        ><template v-if="system?.demo"
          ><div class="login-divider"><span>先看看它如何工作</span></div>
          <button
            type="button"
            class="secondary demo-login"
            :disabled="loginBusy"
            @click="demoLogin"
          >
            体验演示空间 <ArrowUpRight :size="16" />
          </button>
          <p class="login-demo-note">
            演示空间包含虚构的企业资料。回答为原文摘录，<br />无需模型密钥，上传的文档保存在本机。
          </p></template
        >
      </form>
      <span class="login-bottom"
        ><ShieldCheck :size="14" /> 资料按知识库权限访问</span
      >
    </section>
  </div>
  <div v-else class="workspace">
    <button
      v-if="sidebarOpen"
      class="sidebar-scrim"
      aria-label="关闭导航"
      @click="sidebarOpen = false"
    ></button>
    <aside
      class="sidebar"
      :class="{ open: sidebarOpen }"
      :inert="
        (isMobile && !sidebarOpen) ||
        modal !== null ||
        !!readingDocument ||
        settingsOpen
      "
    >
      <a href="/" class="brand sidebar-brand"
        ><span class="brand-mark"><BookOpen :size="24" /></span
        ><span>知序<span class="brand-en">ZHIXU</span></span
        ><span class="brand-badge">团队版</span></a
      >
      <WorkspaceMenu
        :bases="bases"
        :selected-id="selectedBaseId"
        :disabled="busy || uploading"
        @select="selectWorkspaceBase"
        @create="showModal('base')"
      />
      <button class="new-chat" :disabled="busy || !base" @click="newChat">
        <Plus :size="18" /> 开启新对话 <span>↵</span>
      </button>
      <div class="nav-section-label">
        <span>我的知识库</span
        ><button
          class="icon-button"
          title="新建知识库"
          aria-label="新建知识库"
          :disabled="busy"
          @click="showModal('base')"
        >
          <Plus :size="16" />
        </button>
      </div>
      <nav class="base-nav">
        <button
          v-for="item in bases"
          :key="item.id"
          :class="{ active: item.id === selectedBaseId && !dataOpen }"
          :disabled="busy || uploading"
          @click="
            dataOpen = false;
            selectedBaseId = item.id;
          "
        >
          <FolderClosed :size="17" /><span>{{ item.name }}</span
          ><span class="count">{{ item.documentCount }}</span></button
        ><button
          v-if="!bases.length"
          class="empty-nav"
          @click="showModal('base')"
        >
          <Plus :size="16" /> 创建第一个知识库
        </button>
      </nav>
      <div class="nav-section-label history-label">
        <span>最近对话</span><MessageSquare :size="14" />
      </div>
      <nav class="history-nav">
        <button
          v-for="conversation in conversations.slice(0, 12)"
          :key="conversation.id"
          :class="{ active: conversationId === conversation.id }"
          :disabled="busy"
          @click="openConversation(conversation)"
        >
          <MessageSquare :size="14" /><span>{{ conversation.title }}</span>
        </button>
        <p v-if="!conversations.length" class="no-history">
          你的探索，会留在这里。
        </p>
      </nav>
      <div class="sidebar-bottom">
        <button
          v-if="user.role === 'ADMIN'"
          class="help-button"
          :class="{ 'data-nav-active': dataOpen }"
          :disabled="busy"
          @click="openData"
        >
          <Database :size="17" /> 数据查询 <ArrowUpRight :size="14" />
        </button>
        <button
          v-if="user.role === 'ADMIN'"
          class="help-button"
          @click="openSettings"
        >
          <Settings :size="17" /> 设置 <ArrowUpRight :size="14" />
        </button>
        <button class="help-button" @click="showModal('help')">
          <CircleHelp :size="17" /> 使用指南 <ArrowUpRight :size="14" /></button
        ><button
          v-if="user.role === 'ADMIN'"
          class="help-button"
          @click="showModal('user')"
        >
          <Users :size="17" /> 创建成员账号 <Plus :size="14" />
        </button>
        <div class="user-card">
          <div class="avatar">{{ user.displayName.slice(0, 1) }}</div>
          <div>
            <strong>{{ user.displayName }}</strong
            ><span>{{
              user.role === "ADMIN" ? "空间管理员" : "团队成员"
            }}</span>
          </div>
          <button
            class="icon-button"
            title="退出登录"
            aria-label="退出登录"
            :disabled="busy || uploading"
            @click="logout"
          >
            <LogOut :size="17" />
          </button>
        </div>
      </div>
    </aside>
    <div
      class="main-area"
      :inert="
        modal !== null ||
        (isMobile && sidebarOpen) ||
        !!readingDocument ||
        settingsOpen
      "
    >
      <header class="topbar">
        <div class="breadcrumb">
          <button
            class="icon-button mobile-menu"
            aria-label="展开导航"
            @click="sidebarOpen = true"
          >
            <Menu :size="20" /></button
          ><span class="breadcrumb-home">知识空间</span
          ><ChevronRight :size="14" /><strong>{{
            dataOpen ? "数据库可视化查询" : base?.name || "欢迎使用知序"
          }}</strong>
        </div>
        <div class="topbar-right">
          <span
            class="mode-badge"
            :class="{ live: !system?.demo && system?.chatConfigured }"
            ><i></i
            >{{
              system?.demo
                ? "演示空间"
                : system?.chatConfigured
                  ? "真实知识问答"
                  : "待配置对话密钥"
            }}</span
          ><button
            v-if="base?.canManage"
            class="text-button manage-members"
            @click="showMembers"
          >
            <Users :size="15" /> 成员权限
          </button>
        </div>
      </header>
      <div v-if="!dataOpen" class="page-bar">
        <div class="tabs">
          <button :class="{ active: tab === 'chat' }" @click="tab = 'chat'">
            <Sparkles :size="16" /> 知识问答</button
          ><button
            :class="{ active: tab === 'documents' }"
            @click="tab = 'documents'"
          >
            <Layers :size="16" /> 文档资料<span>{{ documents.length }}</span>
          </button>
          <button
            v-if="!system?.demo"
            :class="{ active: tab === 'search' }"
            @click="tab = 'search'"
          >
            <Search :size="16" />语义检索
          </button>
        </div>
        <div class="knowledge-status">
          <span class="status-dot"></span>{{ readyCount }} 份资料已就绪<span
            v-if="processingCount"
            class="processing-caption"
            >· {{ processingCount }} 份处理中</span
          >
        </div>
      </div>
      <DataBrowser
        v-if="dataOpen && user.role === 'ADMIN'"
        @close="dataOpen = false"
      />
      <template v-else-if="!base"
        ><div class="blank-state">
          <div class="blank-icon"><FolderClosed :size="35" /></div>
          <h2>为团队建立第一座知识库</h2>
          <p>创建一个知识库，上传资料，让每次提问都有据可查。</p>
          <button class="primary" @click="showModal('base')">
            <Plus :size="17" /> 创建知识库
          </button>
        </div></template
      >
      <div
        v-else-if="baseLoading || baseError"
        class="workspace-state"
        :role="baseError ? 'alert' : 'status'"
      >
        <LoaderCircle v-if="baseLoading" class="spin" :size="28" />
        <BookOpen v-else :size="28" />
        <h2>{{ baseLoading ? "正在打开知识库" : "知识库暂时无法加载" }}</h2>
        <p>{{ baseError || "正在准备资料和最近对话…" }}</p>
        <button
          v-if="baseError"
          class="secondary"
          @click="loadBase(selectedBaseId)"
        >
          <RotateCcw :size="16" />重新加载
        </button>
      </div>
      <section
        v-else-if="tab === 'chat'"
        class="chat-page"
        :class="{ 'with-source': inspected }"
      >
        <div class="chat-body">
          <div v-if="messages.length === 0" class="chat-welcome">
            <div class="welcome-kicker">
              <span class="tiny-line"></span> 知识有序，答案有据
              <span>ZHIXU / WORKSPACE</span>
            </div>
            <h1>今天，想了解<span class="serif-emphasis">什么？</span></h1>
            <p class="welcome-description">
              {{
                base.description ||
                "把问题交给知序，从团队资料中寻找有依据的答案。"
              }}
            </p>
            <div class="knowledge-overview">
              <div>
                <span>可检索资料</span
                ><strong>{{ readyCount }}<small>份</small></strong>
              </div>
              <div>
                <span>知识片段</span
                ><strong
                  >{{ totalChunks.toLocaleString() }}<small>个</small></strong
                >
              </div>
              <div>
                <span>当前知识库</span
                ><strong class="overview-base" :title="base.name">{{
                  base.name
                }}</strong>
              </div>
            </div>
            <div v-if="!system?.demo" class="welcome-actions">
              <button @click="browseDocuments()">
                <span class="welcome-action-icon"><BookOpen :size="20" /></span
                ><strong>浏览资料</strong><span>从原文开始探索</span
                ><ArrowUpRight :size="16" />
              </button>
              <button @click="tab = 'search'">
                <span class="welcome-action-icon"><Search :size="20" /></span
                ><strong>检索知识</strong><span>按含义寻找相关内容</span
                ><ArrowUpRight :size="16" />
              </button>
              <button :disabled="uploading" @click="browseDocuments(true)">
                <span class="welcome-action-icon"><Upload :size="20" /></span
                ><strong>添加文档</strong><span>让团队经验持续积累</span
                ><ArrowUpRight :size="16" />
              </button>
            </div>
            <div v-if="system?.demo" class="question-suggestions">
              <div class="suggestion-heading">
                <span>从一个问题开始</span
                ><span>试着问问 <ArrowUpRight :size="13" /></span>
              </div>
              <button
                v-for="example in examples"
                :key="example.icon"
                :disabled="busy"
                @click="send(example.question)"
              >
                <span class="suggestion-number">{{ example.icon }}</span>
                <div>
                  <span>{{ example.label }}</span
                  ><strong>{{ example.question }}</strong>
                </div>
                <ArrowUpRight :size="18" />
              </button>
            </div>
            <div v-else class="welcome-recent">
              <div class="welcome-recent-heading">
                <strong>最近添加的资料</strong
                ><button class="text-button" @click="browseDocuments()">
                  查看全部<ArrowUpRight :size="14" />
                </button>
              </div>
              <button
                v-for="document in recentDocuments"
                :key="document.id"
                class="welcome-document"
                @click="readingDocument = document"
              >
                <FileText :size="16" /><span>{{ document.name }}</span
                ><small>{{ document.chunkCount }} 个片段</small
                ><ChevronRight :size="15" />
              </button>
              <div v-if="!recentDocuments.length" class="welcome-empty">
                <BookOpen :size="20" />
                <p>
                  {{
                    processingCount
                      ? "资料正在处理中，就绪后会出现在这里。"
                      : "还没有可检索的资料，上传第一份文档开始探索。"
                  }}
                </p>
              </div>
              <button
                v-if="failedCount"
                class="failed-document-link"
                @click="
                  tab = 'documents';
                  documentFilter = 'FAILED';
                "
              >
                {{ failedCount }} 份文档处理失败，前往查看<ArrowUpRight
                  :size="14"
                />
              </button>
            </div>
          </div>
          <div
            v-else
            ref="chatScroll"
            class="chat-scroll"
            aria-live="polite"
            aria-relevant="additions text"
            @scroll.passive="trackChatScroll"
          >
            <div class="conversation-content">
              <div class="conversation-intro">
                <BookOpen :size="14" /> {{ conversationTitle || base.name
                }}<span>{{
                  useContext
                    ? "结合最近对话理解追问，再检索资料"
                    : "每次提问独立检索当前资料"
                }}</span>
              </div>
              <article
                v-for="(message, messageIndex) in messages"
                :key="message.id"
                class="message"
                :class="message.role"
              >
                <div class="message-avatar">
                  <Sparkles
                    v-if="message.role === 'assistant'"
                    :size="18"
                  /><span v-else>{{ user.displayName.slice(0, 1) }}</span>
                </div>
                <div class="message-main">
                  <div class="message-author">
                    {{ message.role === "assistant" ? "知序" : user.displayName
                    }}<span v-if="message.role === 'assistant'">{{
                      system?.demo ? "原文摘录" : "知识助手"
                    }}</span>
                  </div>
                  <details v-if="message.context?.used" class="context-query">
                    <summary>
                      已结合最近 {{ message.context.turns }} 轮对话理解问题
                    </summary>
                    <p>{{ message.context.query }}</p>
                    <span>答案依据本次重新检索的资料</span>
                  </details>
                  <ChatMarkdown
                    v-if="message.content"
                    :content="message.content"
                    :sources="
                      message.role === 'assistant' ? message.sources : []
                    "
                    @source="inspect"
                  />
                  <div v-else-if="!message.error" class="thinking">
                    <span></span><span></span><span></span>
                    {{ message.phase || "正在查找相关资料" }}
                  </div>
                  <div v-if="message.error" role="alert" class="message-error">
                    {{ message.error }}<span>未完成的回答不会保存。</span>
                    <button
                      v-if="messageIndex === messages.length - 1"
                      class="text-button"
                      :disabled="busy"
                      @click="retryMessage(messageIndex)"
                    >
                      <RotateCcw :size="14" />重新编辑问题
                    </button>
                  </div>
                  <div v-if="message.sources.length" class="message-sources">
                    <span class="sources-label"
                      ><Layers :size="12" /> 参考来源</span
                    ><button
                      v-for="source in message.sources"
                      :key="source.chunkId"
                      @click="inspect(source)"
                    >
                      <span>{{ source.number }}</span
                      >{{ source.documentName }}<ArrowUpRight :size="12" />
                    </button>
                  </div>
                  <div
                    v-if="
                      message.role === 'assistant' &&
                      message.content &&
                      (!busy || messageIndex < messages.length - 1)
                    "
                    class="message-tools"
                  >
                    <button class="text-button" @click="copyMessage(message)">
                      <Check
                        v-if="copiedMessageId === message.id"
                        :size="14"
                      /><Copy v-else :size="14" />{{
                        copiedMessageId === message.id ? "已复制" : "复制回答"
                      }}
                    </button>
                  </div>
                </div>
              </article>
              <div v-if="busy" class="streaming-note">
                <span class="status-dot"></span> 正在生成，请稍候…
              </div>
            </div>
          </div>
          <div class="composer-wrap">
            <button
              v-if="messages.length && !followingChat"
              type="button"
              class="jump-to-latest"
              @click="scrollBottom(true)"
            >
              <ArrowDown :size="15" />回到最新回答
            </button>
            <div
              v-if="!system?.demo && !system?.chatConfigured"
              class="model-setup-note"
            >
              <ShieldCheck :size="14" /><span
                >本地语义检索已启用，对话模型等待配置密钥。</span
              ><button @click="tab = 'search'">
                先测试检索<ArrowUpRight :size="12" />
              </button>
            </div>
            <div v-if="processingCount && !readyCount" class="composer-notice">
              <LoaderCircle :size="13" class="spin" />
              资料正在解析，就绪后即可检索。
            </div>
            <div class="context-control">
              <button
                type="button"
                role="switch"
                :aria-checked="useContext"
                :disabled="busy || !!system?.demo"
                class="context-switch"
                :class="{ on: useContext }"
                aria-label="结合上下文"
                @click="useContext = !useContext"
              >
                <span class="context-switch-track"><i></i></span
                ><strong>结合上下文</strong
                ><span>{{ useContext ? "已开启" : "已关闭" }}</span>
              </button>
              <p>
                {{
                  useContext
                    ? "结合当前对话最近 3 轮问答理解追问，会增加一次模型调用。"
                    : "开启后可继续问“它”“刚才那个”；关闭时每个问题独立检索。"
                }}
              </p>
            </div>
            <form class="composer" @submit.prevent="send()">
              <textarea
                ref="questionInput"
                v-model="question"
                aria-label="输入企业知识问题"
                :placeholder="
                  useContext
                    ? `向「${base.name}」提问，也可以继续追问…`
                    : `向「${base.name}」提问…`
                "
                rows="2"
                maxlength="2000"
                :disabled="busy"
                @keydown="keydown"
              ></textarea>
              <div class="composer-bottom">
                <button
                  type="button"
                  class="composer-scope"
                  @click="tab = 'documents'"
                >
                  <FolderClosed :size="14" /> {{ base.name
                  }}<ChevronDown :size="13" />
                </button>
                <div class="composer-actions">
                  <span>{{
                    question.length
                      ? `${question.length}/2000`
                      : "Enter 发送 · Shift + Enter 换行"
                  }}</span
                  ><button
                    class="send-button"
                    aria-label="发送问题"
                    :disabled="busy || !question.trim()"
                  >
                    <LoaderCircle v-if="busy" class="spin" :size="19" /><ArrowUp
                      v-else
                      :size="21"
                    />
                  </button>
                </div>
              </div>
            </form>
            <p class="composer-footnote">
              {{
                system?.demo
                  ? "演示模式仅摘录文档原文；示例制度为虚构。"
                  : system?.chatConfigured
                    ? "回答由 AI 基于资料生成，请结合引用来源核实重要信息。"
                    : "真实向量检索已启用；对话密钥待配置，可先在「语义检索」中测试资料查找。"
              }}
            </p>
          </div>
        </div>
        <aside v-if="inspected" class="source-panel">
          <div class="source-header">
            <span><FileText :size="17" /> 来源详情</span
            ><button
              class="icon-button"
              aria-label="关闭来源详情"
              @click="
                sourceVersion++;
                inspected = undefined;
              "
            >
              <PanelRightClose :size="18" />
            </button>
          </div>
          <div class="source-title">
            <span class="eyebrow"
              >REFERENCE
              {{ String(inspected.source.number).padStart(2, "0") }}</span
            >
            <h3>{{ inspected.chunk.documentName }}</h3>
            <span class="source-location"
              >{{
                inspected.chunk.pageNumber
                  ? `第 ${inspected.chunk.pageNumber} 页 · `
                  : ""
              }}第 {{ inspected.chunk.ordinal }} 个片段</span
            >
          </div>
          <div class="source-excerpt">{{ inspected.chunk.content }}</div>
          <a
            class="secondary source-download"
            :href="`/api/documents/${inspected.chunk.documentId}/file`"
            ><Download :size="15" /> 下载原始文档</a
          >
          <div class="source-note">
            <ShieldCheck :size="14" /> 来源已通过知识库访问权限校验
          </div>
        </aside>
      </section>
      <KnowledgeSearch
        v-else-if="tab === 'search'"
        :key="base.id"
        :base="base"
        :model="system?.embeddingModel || ''"
        @ask="askSearchQuestion"
        @open="
          (id) =>
            (readingDocument = documents.find((document) => document.id === id))
        "
      />
      <section v-else class="documents-page">
        <div class="section-heading">
          <div>
            <span class="eyebrow">KNOWLEDGE LIBRARY</span>
            <h1>文档资料</h1>
            <p>管理 {{ base.name }} 的资料，构建团队共同的知识底座。</p>
          </div>
          <button
            class="primary"
            :disabled="uploading"
            @click="fileInput?.click()"
          >
            <LoaderCircle v-if="uploading" class="spin" :size="17" /><Upload
              v-else
              :size="17"
            />{{ uploading ? "上传中…" : "上传文档" }}
          </button>
        </div>
        <div class="document-summary" aria-label="资料概览">
          <span
            ><FileText :size="16" /><strong>{{ documents.length }}</strong>
            份资料</span
          >
          <span
            ><CheckCircle2 :size="16" /><strong>{{ readyCount }}</strong>
            份可检索</span
          >
          <span
            ><Layers :size="16" /><strong>{{
              totalChunks.toLocaleString()
            }}</strong>
            个知识片段</span
          >
        </div>
        <input
          ref="fileInput"
          hidden
          type="file"
          accept=".pdf,.docx,.md,.txt"
          multiple
          @change="uploadFiles(($event.target as HTMLInputElement).files)"
        />
        <div
          class="upload-zone"
          :class="{ uploading, 'drag-active': dragDepth > 0 }"
          @dragenter.prevent="dragDepth++"
          @dragleave.prevent="dragDepth = Math.max(0, dragDepth - 1)"
          @dragover.prevent
          @drop="dropped"
        >
          <div class="upload-icon"><Upload :size="23" /></div>
          <div>
            <strong>{{
              uploading
                ? `正在上传 ${Math.min(uploadProgress.current + 1, uploadProgress.total)} / ${uploadProgress.total}`
                : dragDepth
                  ? "松开鼠标，添加文档"
                  : "把团队的经验，放进知识库"
            }}</strong>
            <p v-if="uploading" class="upload-current-name">
              {{ uploadProgress.name }}
            </p>
            <p v-else>
              拖拽文件到这里，或<button
                class="inline-link"
                :disabled="uploading"
                @click="fileInput?.click()"
              >
                选择文件
              </button>
            </p>
            <span
              >PDF、Word（.docx）、Markdown、TXT · 单个文件不超过 20 MB ·
              文字型文档</span
            >
          </div>
          <span class="upload-zone-mark" aria-hidden="true">↗</span>
          <progress
            v-if="uploading"
            class="upload-progress"
            :value="uploadProgress.current"
            :max="uploadProgress.total"
            aria-label="文档上传进度"
          ></progress>
        </div>
        <div class="document-toolbar">
          <div class="document-list-title">
            <div class="document-filters" aria-label="按文档状态筛选">
              <button
                v-for="filter in documentFilters"
                :key="filter.value"
                :class="{ active: documentFilter === filter.value }"
                :aria-pressed="documentFilter === filter.value"
                @click="documentFilter = filter.value"
              >
                {{ filter.label }}<span>{{ filter.count }}</span>
              </button>
            </div>
          </div>
        </div>
        <div class="document-list-controls">
          <div class="search-input">
            <Search :size="15" /><input
              v-model="search"
              aria-label="搜索文档名称"
              placeholder="搜索文档名称"
            />
            <button
              v-if="search"
              class="icon-button"
              aria-label="清除文档搜索"
              @click="search = ''"
            >
              <X :size="14" />
            </button>
          </div>
          <span class="document-match-count"
            >{{ filteredDocuments.length }} 份资料</span
          >
          <select v-model="documentSort" aria-label="文档排序">
            <option value="newest">最近上传</option>
            <option value="name">名称排序</option>
            <option value="size">文件大小</option>
          </select>
          <button
            class="icon-button"
            aria-label="刷新资料"
            title="刷新资料"
            :disabled="refreshingDocuments"
            @click="refreshDocuments(true)"
          >
            <RefreshCw :size="16" :class="{ spin: refreshingDocuments }" />
          </button>
        </div>
        <div class="document-table">
          <div class="document-row table-head">
            <span>文档名称</span><span>处理状态</span><span>上传时间</span
            ><span>操作</span>
          </div>
          <div
            v-for="document in filteredDocuments"
            :key="document.id"
            class="document-row"
          >
            <div class="document-info">
              <div
                class="file-icon"
                :class="document.name.split('.').pop()?.toLowerCase()"
              >
                <FileText :size="22" />
              </div>
              <div>
                <button
                  class="document-name"
                  :title="`在线浏览 ${document.name}`"
                  @click="readingDocument = document"
                >
                  <strong>{{ document.name }}</strong></button
                ><span
                  >{{ formatSize(document.sizeBytes) }} <i>·</i>
                  {{
                    document.status === "READY"
                      ? `${document.chunkCount} 个知识片段`
                      : "等待建立检索索引"
                  }}</span
                >
                <p v-if="document.errorMessage" class="document-error">
                  {{ document.errorMessage }}
                </p>
              </div>
            </div>
            <div>
              <span
                class="document-status"
                :class="document.status.toLowerCase()"
                ><CheckCircle2
                  v-if="document.status === 'READY'"
                  :size="13"
                /><LoaderCircle
                  v-else-if="['PROCESSING', 'QUEUED'].includes(document.status)"
                  :size="13"
                  class="spin"
                /><span v-else class="failed-dot">!</span
                >{{ statusLabel(document.status) }}</span
              >
            </div>
            <span class="document-date">{{
              formatDate(document.createdAt)
            }}</span>
            <div class="document-actions">
              <button
                class="icon-button"
                title="在线浏览"
                :aria-label="`在线浏览 ${document.name}`"
                @click="readingDocument = document"
              >
                <Eye :size="16" />
              </button>
              <button
                v-if="document.status === 'FAILED'"
                class="icon-button"
                title="重试处理"
                aria-label="重试处理"
                @click="retryDocument(document)"
              >
                <RotateCcw :size="16" /></button
              ><a
                class="icon-button"
                :href="`/api/documents/${document.id}/file`"
                title="下载文档"
                aria-label="下载文档"
                ><Download :size="16" /></a
              ><button
                class="icon-button danger"
                title="删除文档"
                :aria-label="`删除 ${document.name}`"
                @click="deleteDocument(document)"
              >
                <Trash2 :size="16" />
              </button>
            </div>
          </div>
          <div v-if="!filteredDocuments.length" class="empty-documents">
            <FolderClosed :size="30" /><strong>{{
              search || documentFilter !== "all"
                ? "没有符合条件的文档"
                : "这里还没有资料"
            }}</strong>
            <p>
              {{
                search || documentFilter !== "all"
                  ? "试试其他关键词或处理状态。"
                  : "上传第一份文档，让知识开始积累。"
              }}
            </p>
            <button
              v-if="search || documentFilter !== 'all'"
              class="secondary"
              @click="clearDocumentFilters"
            >
              清除筛选
            </button>
            <button
              v-else
              class="secondary"
              :disabled="uploading"
              @click="fileInput?.click()"
            >
              <Upload :size="16" />上传第一份文档
            </button>
          </div>
        </div>
        <div class="documents-footer">
          <ShieldCheck :size="14" /> 当前知识库的资料仅对有权限的成员可见。<span
            >更新资料时，请删除旧版本后重新上传。</span
          >
        </div>
      </section>
    </div>
    <ModelSettings
      v-if="settingsOpen"
      @close="settingsOpen = false"
      @changed="settingsChanged"
    />
    <DocumentReader
      v-if="readingDocument"
      :key="readingDocument.id"
      :document="readingDocument"
      @close="readingDocument = undefined"
    />
    <div
      v-if="modal"
      class="modal-backdrop"
      @click.self="!modalBusy && (modal = null)"
    >
      <section
        class="modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="modal-title"
      >
        <button
          class="icon-button modal-close"
          aria-label="关闭窗口"
          :disabled="modalBusy"
          @click="modal = null"
        >
          <X :size="20" /></button
        ><template v-if="modal === 'base'"
          ><span class="modal-symbol"><FolderClosed :size="25" /></span>
          <h2 id="modal-title">为知识，建立一个空间</h2>
          <p class="muted">按团队、项目或主题组织资料，知识库之间独立检索。</p>
          <form @submit.prevent="createBase">
            <label for="base-name">知识库名称</label
            ><input
              id="base-name"
              v-model="baseName"
              placeholder="例如：产品研发中心"
              required
              maxlength="80"
            /><label for="base-description"
              >简介 <span class="optional">可选</span></label
            ><textarea
              id="base-description"
              v-model="baseDescription"
              placeholder="这里将收录哪些知识？"
              maxlength="500"
              rows="3"
            ></textarea>
            <p v-if="formError" class="form-error" role="alert">
              {{ formError }}
            </p>
            <button class="primary modal-submit" :disabled="modalBusy">
              <Plus :size="16" />{{ modalBusy ? "创建中…" : "创建知识库" }}
            </button>
          </form></template
        ><template v-else-if="modal === 'members'"
          ><span class="modal-symbol"><Users :size="25" /></span>
          <h2 id="modal-title">共同分享，有序访问</h2>
          <p class="muted">
            {{ base?.name }} · 成员可以查看、上传、删除资料并提问。
          </p>
          <div class="member-list">
            <div v-for="member in members" :key="member.id" class="member-row">
              <div class="avatar light-avatar">
                {{ member.displayName.slice(0, 1) }}
              </div>
              <div>
                <strong>{{ member.displayName }}</strong
                ><span>{{ member.username }}</span>
              </div>
              <span v-if="member.id === base?.ownerId" class="owner-tag"
                >所有者</span
              ><button
                v-else
                class="icon-button danger"
                :aria-label="`移除 ${member.displayName}`"
                @click="removeMember(member)"
              >
                <X :size="16" />
              </button>
            </div>
          </div>
          <form @submit.prevent="addMember">
            <label for="member-name">添加已有成员</label>
            <div class="inline-form">
              <input
                id="member-name"
                v-model="memberUsername"
                placeholder="输入成员账号"
                required
                maxlength="64"
              /><button class="primary" :disabled="modalBusy">
                <Plus :size="16" /> 添加
              </button>
            </div>
          </form>
          <p class="small-note">
            管理员可访问全部知识库。新账号由空间管理员创建。
          </p>
          <p v-if="formError" class="form-error" role="alert">
            {{ formError }}
          </p></template
        ><template v-else-if="modal === 'user'"
          ><span class="modal-symbol"><Users :size="25" /></span>
          <h2 id="modal-title">邀请更多知识同行者</h2>
          <p class="muted">创建账号后，再到对应知识库添加访问权限。</p>
          <form @submit.prevent="createUser">
            <label for="new-username">账号</label
            ><input
              id="new-username"
              v-model="newUsername"
              placeholder="3–64 位英文、数字、下划线或点"
              pattern="[a-zA-Z0-9_.\-]{3,64}"
              required
              maxlength="64"
            /><label for="new-name">显示名称</label
            ><input
              id="new-name"
              v-model="newDisplayName"
              required
              maxlength="80"
              placeholder="成员姓名"
            /><label for="new-password">初始密码</label
            ><input
              id="new-password"
              v-model="newPassword"
              type="password"
              autocomplete="new-password"
              required
              minlength="10"
              maxlength="72"
              placeholder="至少 10 个字符"
            />
            <p v-if="formError" class="form-error" role="alert">
              {{ formError }}
            </p>
            <button class="primary modal-submit" :disabled="modalBusy">
              <Check :size="16" /> {{ modalBusy ? "创建中…" : "创建成员账号" }}
            </button>
          </form></template
        ><template v-else
          ><span class="modal-symbol"><BookOpen :size="25" /></span>
          <h2 id="modal-title">三步，找到团队的答案</h2>
          <div class="help-steps">
            <div>
              <b>01</b>
              <section>
                <h3>建立知识空间</h3>
                <p>按主题创建知识库，按需添加可以访问的成员。</p>
              </section>
            </div>
            <div>
              <b>02</b>
              <section>
                <h3>上传文档资料</h3>
                <p>
                  支持文字型
                  PDF、DOCX、MD、TXT。处理状态变为「可检索」后即可提问。扫描件需先做
                  OCR。
                </p>
              </section>
            </div>
            <div>
              <b>03</b>
              <section>
                <h3>提问，并核实来源</h3>
                <p>
                  点击回答下方的引用，可以查看原文片段并下载文档。输入区的「结合上下文」开关打开后，可结合当前对话最近
                  3 轮已完成问答理解“它”“刚才那个”等追问；关闭时每次独立检索。
                </p>
              </section>
            </div>
          </div>
          <div v-if="system?.demo" class="demo-help-note">
            当前为演示模式，采用文本匹配和原文摘录。正式模式配置模型服务后，启用向量检索与
            AI 生成。
          </div>
          <button class="primary modal-submit" @click="modal = null">
            开始探索 <ArrowUpRight :size="16" /></button
        ></template>
      </section>
    </div>
    <Transition name="toast"
      ><div v-if="toast" class="toast" role="status">
        <CheckCircle2 :size="17" />{{ toast
        }}<button class="icon-button" aria-label="关闭提示" @click="toast = ''">
          <X :size="14" />
        </button></div
    ></Transition>
  </div>
</template>
