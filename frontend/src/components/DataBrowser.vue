<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from "vue";
import {
  ArrowLeft,
  ArrowUpRight,
  Check,
  ChevronLeft,
  ChevronRight,
  Copy,
  Database,
  FileText,
  Layers,
  LoaderCircle,
  MessageSquare,
  RefreshCw,
  Search,
  ShieldCheck,
  Table2,
  X,
} from "lucide-vue-next";
import { api } from "../api";

interface Column {
  key: string;
  label: string;
  kind: string;
}
interface Option {
  value: string;
  label: string;
}
interface Table {
  name: string;
  label: string;
  description: string;
  count: number;
  columns: Column[];
  knowledgeBaseFilter: boolean;
  states: Option[];
}
interface Overview {
  database: string;
  version: string;
  catalog: string;
  vectorEnabled: boolean;
  embeddingModel: string;
  indexedChunks: number;
  tables: Table[];
  knowledgeBases: Option[];
}
interface Row {
  key: string;
  values: Record<string, unknown>;
}
interface Page {
  table: string;
  page: number;
  pageSize: number;
  total: number;
  rows: Row[];
}
interface Detail {
  table: string;
  values: Record<string, unknown>;
  vector?: { dimensions: number; values: number[] };
}
const emit = defineEmits<{ close: [] }>();
const overview = ref<Overview>();
const tableName = ref("document");
const table = computed(() =>
  overview.value?.tables.find((item) => item.name === tableName.value),
);
const q = ref("");
const query = ref("");
const knowledgeBase = ref("");
const state = ref("");
const pageSize = ref(20);
const result = ref<Page>();
const loading = ref(true);
const refreshing = ref(false);
const error = ref("");
const selectedKey = ref("");
const detail = ref<Detail>();
const detailLoading = ref(false);
const detailError = ref("");
const copied = ref(false);
const hoverDimension = ref<number>();
let rowsVersion = 0,
  detailVersion = 0;
let disposed = false;
let copyTimer: ReturnType<typeof setTimeout> | undefined;
const totalPages = computed(() =>
  Math.max(1, Math.ceil((result.value?.total || 0) / pageSize.value)),
);
const count = (name: string) =>
  overview.value?.tables.find((item) => item.name === name)?.count || 0;
const cards = computed(() => [
  {
    label: "知识库",
    value: count("knowledge_base"),
    icon: Database,
    table: "knowledge_base",
  },
  {
    label: "文档资料",
    value: count("document"),
    icon: FileText,
    table: "document",
  },
  {
    label: "已生成向量",
    value: overview.value?.indexedChunks || 0,
    icon: Layers,
    table: "document_chunk",
  },
  {
    label: "聊天消息",
    value: count("chat_message"),
    icon: MessageSquare,
    table: "chat_message",
  },
]);
const vector = computed(() => detail.value?.vector?.values || []);
const vectorScale = computed(() =>
  Math.max(0.001, ...vector.value.map((value) => Math.abs(value))),
);
const vectorPoints = computed(() =>
  vector.value
    .map(
      (value, index) =>
        `${(index / Math.max(1, vector.value.length - 1)) * 680 + 10},${75 - (value / vectorScale.value) * 58}`,
    )
    .join(" "),
);
const vectorNorm = computed(() =>
  Math.sqrt(
    vector.value.reduce((sum, value) => sum + value * value, 0),
  ).toFixed(4),
);
const selectedDimension = computed(() =>
  hoverDimension.value === undefined
    ? undefined
    : vector.value[hoverDimension.value],
);
const labels: Record<string, string> = {
  id: "记录 ID",
  name: "名称",
  knowledge_base_id: "知识库 ID",
  knowledge_base_name: "知识库名称",
  document_id: "文档 ID",
  document_name: "文档名称",
  created_at: "创建时间",
  status: "处理状态",
  size_bytes: "文件大小（字节）",
  chunk_count: "片段数量",
  file_key: "原文存储键",
  content_type: "文件类型",
  error_message: "处理错误",
  ordinal: "片段序号",
  page_number: "原文页码",
  content: "完整内容",
  embedding_dimensions: "向量维度",
  indexed: "是否生成向量",
  title: "对话标题",
  conversation_id: "对话 ID",
  conversation_title: "对话标题",
  user_id: "用户 ID",
  user_name: "用户名称",
  username: "账号",
  display_name: "显示名称",
  role: "角色",
  owner_id: "所有者 ID",
  owner_name: "所有者",
  description: "简介",
  sources_json: "引用快照",
  setting_key: "参数名称",
  value_status: "保存状态",
};
const states: Record<string, string> = {
  READY: "可检索",
  PROCESSING: "解析中",
  QUEUED: "排队中",
  FAILED: "处理失败",
  ADMIN: "管理员",
  MEMBER: "成员",
  user: "用户",
  assistant: "助手",
};
const text = (value: unknown) =>
  value === null || value === undefined
    ? "NULL"
    : typeof value === "object"
      ? JSON.stringify(value)
      : String(value);
function cell(value: unknown, kind: string) {
  if (value === null || value === undefined) return "—";
  if (kind === "date")
    return new Intl.DateTimeFormat("zh-CN", {
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
    }).format(new Date(String(value)));
  if (kind === "bytes") {
    const size = Number(value);
    return size < 1024
      ? `${size} B`
      : size < 1024 * 1024
        ? `${(size / 1024).toFixed(1)} KB`
        : `${(size / 1024 / 1024).toFixed(1)} MB`;
  }
  if (kind === "boolean") return value ? "已生成" : "未生成";
  if (kind === "status") return states[String(value)] || String(value);
  if (kind === "id")
    return String(value).length > 14
      ? String(value).slice(0, 8) + "…"
      : String(value);
  return text(value);
}
function formatted(key: string, value: unknown) {
  if (key.endsWith("_json") && typeof value === "string") {
    try {
      return JSON.stringify(JSON.parse(value), null, 2);
    } catch {
      return value;
    }
  }
  return text(value);
}
function clearDetail() {
  detailVersion++;
  selectedKey.value = "";
  detail.value = undefined;
  detailError.value = "";
  copied.value = false;
  hoverDimension.value = undefined;
}
async function loadRows(page = 1) {
  const version = ++rowsVersion;
  loading.value = true;
  error.value = "";
  clearDetail();
  const params = new URLSearchParams({
    page: String(page),
    pageSize: String(pageSize.value),
    q: query.value,
    knowledgeBaseId: knowledgeBase.value,
    state: state.value,
  });
  try {
    const value = await api<Page>(
      `/admin/data/tables/${tableName.value}?${params}`,
    );
    if (!disposed && version === rowsVersion) result.value = value;
  } catch (e) {
    if (!disposed && version === rowsVersion) {
      result.value = undefined;
      error.value = e instanceof Error ? e.message : "查询失败，请重试";
    }
  } finally {
    if (!disposed && version === rowsVersion) loading.value = false;
  }
}
async function refresh() {
  if (refreshing.value) return;
  refreshing.value = true;
  error.value = "";
  try {
    const value = await api<Overview>("/admin/data/overview");
    if (disposed) return;
    overview.value = value;
    await loadRows(result.value?.page || 1);
  } catch (e) {
    if (!disposed) {
      error.value = e instanceof Error ? e.message : "读取数据库信息失败";
      loading.value = false;
    }
  } finally {
    if (!disposed) refreshing.value = false;
  }
}
function select(name: string) {
  if (tableName.value === name) return;
  tableName.value = name;
  q.value = "";
  query.value = "";
  knowledgeBase.value = "";
  state.value = "";
  result.value = undefined;
  void loadRows();
}
function search() {
  query.value = q.value.trim();
  void loadRows();
}
async function inspect(row: Row) {
  const version = ++detailVersion,
    name = tableName.value;
  selectedKey.value = row.key;
  detail.value = undefined;
  detailLoading.value = true;
  detailError.value = "";
  copied.value = false;
  hoverDimension.value = undefined;
  try {
    const value = await api<Detail>(
      `/admin/data/tables/${name}/rows/${row.key}`,
    );
    if (!disposed && version === detailVersion) detail.value = value;
  } catch (e) {
    if (!disposed && version === detailVersion)
      detailError.value = e instanceof Error ? e.message : "读取详情失败";
  } finally {
    if (!disposed && version === detailVersion) detailLoading.value = false;
  }
}
async function copy() {
  if (!detail.value) return;
  try {
    await navigator.clipboard.writeText(JSON.stringify(detail.value, null, 2));
    copied.value = true;
    clearTimeout(copyTimer);
    copyTimer = setTimeout(() => {
      copied.value = false;
    }, 2000);
  } catch {
    detailError.value = "复制失败，可以直接选择详情文字复制。";
  }
}
function hover(event: MouseEvent) {
  const box = (event.currentTarget as SVGElement).getBoundingClientRect();
  const position = (((event.clientX - box.left) / box.width) * 700 - 10) / 680;
  hoverDimension.value = Math.max(
    0,
    Math.min(
      vector.value.length - 1,
      Math.round(position * (vector.value.length - 1)),
    ),
  );
}
watch([knowledgeBase, state, pageSize], () => {
  if (overview.value) void loadRows();
});
onMounted(() => {
  void refresh();
});
onUnmounted(() => {
  disposed = true;
  rowsVersion++;
  detailVersion++;
  clearTimeout(copyTimer);
});
</script>

<template>
  <section class="data-browser" @keydown.esc="clearDetail">
    <header class="data-browser-heading">
      <div>
        <h1>数据库查询</h1>
        <p>查看文档、知识片段和向量，支持搜索、筛选与分页。</p>
      </div>
      <div class="data-heading-actions">
        <button class="secondary" :disabled="refreshing" @click="refresh">
          <RefreshCw :size="14" :class="{ spin: refreshing }" />刷新数据</button
        ><button class="text-button" @click="emit('close')">
          <ArrowLeft :size="14" />返回知识空间
        </button>
      </div>
    </header>
    <div class="data-summary">
      <button
        v-for="card in cards"
        :key="card.label"
        :aria-label="`查看${card.label}，${card.value} 条`"
        @click="select(card.table)"
      >
        <component :is="card.icon" :size="18" />
        <div>
          <span>{{ card.label }}</span
          ><strong>{{ card.value.toLocaleString() }}</strong>
        </div>
        <ArrowUpRight :size="14" />
      </button>
    </div>
    <div class="data-connection">
      <span class="status-dot"></span
      ><strong
        >{{ overview?.database || "正在连接数据库" }}
        {{ overview?.version?.split(" ")[0] }}</strong
      ><span v-if="overview"
        >{{ overview.catalog }}<i>·</i
        >{{
          overview.vectorEnabled
            ? overview.embeddingModel + " / pgvector"
            : "演示数据"
        }}</span
      ><span class="data-readonly"
        ><ShieldCheck :size="12" />管理员只读查询</span
      >
    </div>
    <div class="data-workbench" :class="{ 'has-inspector': selectedKey }">
      <aside class="data-table-list">
        <div class="data-table-list-title">
          <Table2 :size="14" />数据表<span>{{
            overview?.tables.length || 0
          }}</span>
        </div>
        <button
          v-for="item in overview?.tables || []"
          :key="item.name"
          :class="{ active: tableName === item.name }"
          @click="select(item.name)"
        >
          <span
            ><strong>{{ item.label }}</strong
            ><code>{{ item.name }}</code></span
          ><b>{{ item.count }}</b>
        </button>
        <p><ShieldCheck :size="13" />密码与密钥值已排除</p>
      </aside>
      <div class="data-records">
        <div class="data-records-heading">
          <div>
            <h2>
              {{ table?.label || "读取数据表…" }}<code>{{ tableName }}</code>
            </h2>
            <p>{{ table?.description || "正在读取数据表信息" }}</p>
          </div>
          <span class="data-record-count"
            >{{ result?.total ?? table?.count ?? 0 }} 条记录</span
          >
        </div>
        <form class="data-filters" @submit.prevent="search">
          <div class="data-search-input">
            <Search :size="15" /><input
              v-model="q"
              aria-label="搜索数据库记录"
              placeholder="搜索名称、内容或 ID"
              maxlength="200"
            />
          </div>
          <select
            v-if="table?.knowledgeBaseFilter"
            v-model="knowledgeBase"
            aria-label="按知识库筛选"
          >
            <option value="">全部知识库</option>
            <option
              v-for="item in overview?.knowledgeBases"
              :key="item.value"
              :value="item.value"
            >
              {{ item.label }}
            </option></select
          ><select
            v-if="table?.states.length"
            v-model="state"
            aria-label="按状态筛选"
          >
            <option value="">全部状态</option>
            <option
              v-for="item in table.states"
              :key="item.value"
              :value="item.value"
            >
              {{ item.label }}
            </option></select
          ><button class="primary" :disabled="loading">
            <Search :size="14" />查询
          </button>
        </form>
        <div v-if="error" class="data-query-error" role="alert">
          <p>{{ error }}</p>
          <button class="secondary" @click="refresh">重新读取</button>
        </div>
        <div v-else class="data-grid-wrap" :aria-busy="loading">
          <div v-if="loading" class="data-loading" role="status">
            <LoaderCircle :size="24" class="spin" />正在读取记录…
          </div>
          <table v-else class="data-grid">
            <thead>
              <tr>
                <th class="data-row-number">#</th>
                <th v-for="column in table?.columns" :key="column.key">
                  <strong>{{ column.label }}</strong
                  ><code>{{ column.key }}</code>
                </th>
                <th>详情</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="(row, index) in result?.rows || []"
                :key="row.key"
                :class="{ selected: selectedKey === row.key }"
                tabindex="0"
                :aria-label="`查看第 ${index + 1} 条记录`"
                @click="inspect(row)"
                @keydown.enter.self.prevent="inspect(row)"
              >
                <td class="data-row-number">
                  {{ ((result?.page || 1) - 1) * pageSize + index + 1 }}
                </td>
                <td
                  v-for="column in table?.columns"
                  :key="column.key"
                  :class="`data-cell-${column.kind}`"
                  :title="text(row.values[column.key])"
                >
                  <span
                    v-if="column.kind === 'status' || column.kind === 'boolean'"
                    class="data-state"
                    :class="{
                      good:
                        row.values[column.key] === true ||
                        row.values[column.key] === 'READY',
                      bad: row.values[column.key] === 'FAILED',
                    }"
                    >{{ cell(row.values[column.key], column.kind) }}</span
                  ><span v-else>{{
                    cell(row.values[column.key], column.kind)
                  }}</span>
                </td>
                <td>
                  <button
                    class="icon-button"
                    :aria-label="`查看第 ${index + 1} 条记录详情`"
                    @click.stop="inspect(row)"
                  >
                    <ChevronRight :size="16" />
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
          <div v-if="!loading && !result?.rows.length" class="data-empty">
            <Database :size="29" /><strong>没有找到记录</strong>
            <p>可以调整搜索词或筛选条件后重试。</p>
          </div>
        </div>
        <footer class="data-pagination">
          <span>共 {{ result?.total || 0 }} 条</span
          ><label
            >每页<select v-model="pageSize" aria-label="每页显示条数">
              <option :value="20">20</option>
              <option :value="50">50</option>
              <option :value="100">100</option>
            </select></label
          >
          <div>
            <button
              class="icon-button"
              aria-label="上一页"
              :disabled="loading || (result?.page || 1) <= 1"
              @click="loadRows((result?.page || 1) - 1)"
            >
              <ChevronLeft :size="16" /></button
            ><span>{{ result?.page || 1 }} / {{ totalPages }}</span
            ><button
              class="icon-button"
              aria-label="下一页"
              :disabled="loading || (result?.page || 1) >= totalPages"
              @click="loadRows((result?.page || 1) + 1)"
            >
              <ChevronRight :size="16" />
            </button>
          </div>
        </footer>
      </div>
      <aside v-if="selectedKey" class="data-inspector">
        <header>
          <strong><FileText :size="15" />记录详情</strong
          ><button
            class="icon-button"
            aria-label="关闭记录详情"
            @click="clearDetail"
          >
            <X :size="18" />
          </button>
        </header>
        <div v-if="detailLoading" class="data-loading" role="status">
          <LoaderCircle :size="22" class="spin" />读取详情中…
        </div>
        <p v-if="detailError" class="form-error" role="alert">
          {{ detailError }}
        </p>
        <template v-if="detail"
          ><div class="data-inspector-meta">
            <code>{{ detail.table }}</code
            ><button class="text-button" @click="copy">
              <Check v-if="copied" :size="13" /><Copy v-else :size="13" />{{
                copied ? "已复制" : "复制 JSON"
              }}
            </button>
          </div>
          <div v-if="detail.vector" class="data-vector">
            <div>
              <strong>Embedding 向量</strong
              ><span>{{ detail.vector.dimensions }} 维</span>
            </div>
            <svg
              viewBox="0 0 700 150"
              role="img"
              :aria-label="`${detail.vector.dimensions} 维向量分布`"
              @mousemove="hover"
              @mouseleave="hoverDimension = undefined"
            >
              <line
                x1="10"
                x2="690"
                y1="75"
                y2="75"
                stroke="#d9e4cd"
                stroke-dasharray="4 4"
              />
              <polyline
                :points="vectorPoints"
                fill="none"
                stroke="#70965b"
                stroke-width="1.2"
                vector-effect="non-scaling-stroke"
              />
            </svg>
            <p>
              {{
                selectedDimension !== undefined
                  ? `第 ${(hoverDimension || 0) + 1} 维：${selectedDimension.toFixed(7)}`
                  : `L2 范数 ${vectorNorm} · 悬停查看每一维`
              }}
            </p>
            <details>
              <summary>查看全部向量数值</summary>
              <pre>{{ JSON.stringify(detail.vector.values, null, 2) }}</pre>
            </details>
          </div>
          <dl>
            <div v-for="(value, key) in detail.values" :key="key">
              <dt>
                {{ labels[key] || key }}<code>{{ key }}</code>
              </dt>
              <dd>
                <pre :class="{ null: value === null }">{{
                  formatted(key, value)
                }}</pre>
              </dd>
            </div>
          </dl></template
        >
      </aside>
    </div>
  </section>
</template>
