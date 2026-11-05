<script setup lang="ts">
import "./library.css";
import "./knowledgeGraph.css";
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
  ArrowLeft,
  ArrowUpRight,
  BookOpen,
  BookmarkCheck,
  Check,
  ChevronRight,
  FileText,
  FolderOpen,
  HelpCircle,
  LoaderCircle,
  Network,
  List,
  Plus,
  Search,
  X,
} from "@lucide/vue";
import type { Evidence, Problem, Source, Topic } from "./types";
import {
  filterLibrary,
  libraryKinds,
  libraryRows,
  useLibraryReader,
  type LibraryKind,
  type LibraryRow,
} from "./library";
import { renderMarkdown } from "./markdown";
import EvidencePanel from "./EvidencePanel.vue";
import KnowledgeReadScope from "./KnowledgeReadScope.vue";
import KnowledgeReferences from "./KnowledgeReferences.vue";
import { domainColor } from "./knowledgeGraph";
const KnowledgeGraph = defineAsyncComponent(
  () => import("./KnowledgeGraph.vue"),
);
const graphView = ref(true);
const graph = ref<{ choose: (id: string) => Promise<void> }>();
const props = defineProps<{
  sources: Source[];
  topics: Topic[];
  problems: Problem[];
  contextId: string;
  busy: boolean;
  canNavigate: boolean;
  title: string;
  text: string;
  url: string;
}>();
const emit = defineEmits<{
  source: [id: string, quotes?: Evidence[]];
  conversation: [id: string];
  remove: [id: string];
  reanalyze: [];
  sync: [id: string];
  ingest: [];
  "update:title": [value: string];
  "update:text": [value: string];
  "update:url": [value: string];
}>();
const reader = useLibraryReader();
const kind = ref<LibraryKind>("knowledge"),
  query = ref(""),
  domain = ref(""),
  selected = ref(""),
  detailOpen = ref(false),
  addOpen = ref(false),
  helpOpen = ref(false);
const listPane = ref<HTMLElement | null>(null),
  readingPane = ref<HTMLElement | null>(null),
  searchInput = ref<HTMLInputElement | null>(null),
  helpButton = ref<HTMLButtonElement | null>(null);
const rows = computed(() =>
  libraryRows(props.sources, reader.entries.value, props.topics),
);
const visible = computed(() =>
  filterLibrary(
    rows.value,
    kind.value,
    query.value,
    kind.value === "knowledge" ? domain.value : "",
  ),
);
const current = computed(() =>
  visible.value.find((row) => row.id === selected.value),
);
const currentTopic = computed(() =>
  props.topics.find((t) => kind.value === "topic" && t.id === selected.value),
);
const domains = computed(() =>
  [...new Set(reader.entries.value.flatMap((e) => e.domains))].sort((a, b) =>
    a.localeCompare(b, "zh-CN"),
  ),
);
const contextTitle = computed(
  () =>
    props.problems.find((p) => p.id === props.contextId)?.title || "当前对话",
);
const isOwnContext = computed(
  () => !!props.contextId && current.value?.problemId === props.contextId,
);
const confirmed = computed(() => reader.knowledge.value);
const related = computed(() =>
  reader.entries.value.filter((e) =>
    confirmed.value?.relatedEntryIds.includes(e.id),
  ),
);
const count = (value: LibraryKind) =>
  filterLibrary(rows.value, value, query.value).length;
const problemTitle = (id: string) =>
  props.problems.find((p) => p.id === id)?.title || "原对话";
const date = (value: string) =>
  value
    ? new Date(value).toLocaleDateString("zh-CN", {
        year: "numeric",
        month: "short",
        day: "numeric",
      })
    : "";
const icon = (value: LibraryKind) =>
  value === "source"
    ? FileText
    : value === "knowledge"
      ? BookmarkCheck
      : BookOpen;
const kindLabel = (value: LibraryKind) =>
  libraryKinds.find((k) => k.value === value)?.label || "";
const statusLabel = (status: string) =>
  ({
    ready: "已保存",
    partial: "部分内容",
    failed: "读取失败",
    confirmed: "已确认",
    synced: "已同步",
    pending: "等待同步",
    pending_configuration: "待配置",
  })[status] || status;
const safeUrl = (value?: string | null) => {
  try {
    const url = new URL(value || "");
    return ["http:", "https:"].includes(url.protocol) &&
      !url.username &&
      !url.password
      ? url.href
      : undefined;
  } catch {
    return undefined;
  }
};
const sourceHost = computed(() => {
  try {
    return new URL(reader.source.value?.originUrl || "").hostname.replace(
      /^www\./,
      "",
    );
  } catch {
    return "";
  }
});
async function select(row: LibraryRow, reveal = true) {
  selected.value = row.id;
  if (reveal) detailOpen.value = true;
  await reader.open(row.kind, row.id);
  if (selected.value !== row.id) return;
  await nextTick();
  readingPane.value?.scrollTo({ top: 0 });
  if (reveal) readingPane.value?.focus({ preventScroll: true });
}
function switchKind(value: LibraryKind) {
  kind.value = value;
  selected.value = "";
  domain.value = "";
  detailOpen.value = false;
}
async function back() {
  detailOpen.value = false;
  await nextTick();
  const item = listPane.value?.querySelector<HTMLButtonElement>(
    '[aria-selected="true"]',
  );
  item?.focus({ preventScroll: true });
}
function keyMove(event: KeyboardEvent, index: number) {
  const last = visible.value.length - 1;
  const next =
    event.key === "ArrowDown"
      ? Math.min(last, index + 1)
      : event.key === "ArrowUp"
        ? Math.max(0, index - 1)
        : event.key === "Home"
          ? 0
          : event.key === "End"
            ? last
            : -1;
  if (next < 0) return;
  event.preventDefault();
  const row = visible.value[next];
  if (!row) return;
  void select(row, false);
  nextTick(() =>
    listPane.value
      ?.querySelectorAll<HTMLButtonElement>(".library-row")
      [next]?.focus(),
  );
}
function revealGraph(id: string) {
  query.value = "";
  domain.value = "";
  void nextTick(() => graph.value?.choose(id));
}
function selectRelated(id: string) {
  const row = rows.value.find((r) => r.kind === "knowledge" && r.id === id);
  if (!row) return;
  query.value = "";
  domain.value = "";
  void select(row);
}
async function refreshKnowledge() {
  await reader.load();
}
defineExpose({ refresh: reader.refresh });
watch([visible, graphView], ([values]) => {
  if (kind.value === "knowledge" && graphView.value) return;
  if (current.value) return;
  detailOpen.value = false;
  if (values.length) void select(values[0], false);
  else {
    selected.value = "";
    void reader.open("topic", "");
  }
});
watch(
  () => props.contextId,
  () => {
    addOpen.value = false;
  },
);
onMounted(() => {
  void refreshKnowledge();
});
onUnmounted(reader.stop);
</script>

<template>
  <section
    class="library-page-view"
    aria-label="个人资料库"
    :class="{ 'show-reader': detailOpen }"
  >
    <div class="library-toolbar">
      <div
        v-if="kind === 'knowledge'"
        class="graph-view-switch"
        aria-label="知识视图"
      >
        <button
          :aria-pressed="graphView"
          aria-label="图谱视图"
          @click="graphView = true"
        >
          <Network :size="16" /><span>图谱</span>
        </button>
        <button
          :aria-pressed="!graphView"
          aria-label="列表视图"
          @click="graphView = false"
        >
          <List :size="16" /><span>列表</span>
        </button>
      </div>
      <label class="library-search"
        ><Search :size="18" /><span class="sr-only">搜索资料库</span
        ><input
          ref="searchInput"
          v-model="query"
          type="search"
          placeholder="搜索资料、知识与主题…" /><button
          v-if="query"
          type="button"
          class="icon-button"
          aria-label="清除搜索"
          @click="
            query = '';
            searchInput?.focus();
          "
        >
          <X :size="15" /></button
      ></label>
      <button
        v-if="contextId"
        type="button"
        class="library-add"
        :disabled="busy"
        :aria-expanded="addOpen"
        @click="
          addOpen = !addOpen;
          helpOpen = false;
        "
      >
        <Plus :size="17" /><span>添加资料</span>
      </button>
      <button
        ref="helpButton"
        type="button"
        class="icon-button library-help-toggle"
        aria-label="资料库使用说明"
        :aria-expanded="helpOpen"
        @click="
          helpOpen = !helpOpen;
          addOpen = false;
        "
      >
        <HelpCircle :size="19" />
      </button>
    </div>
    <div v-if="helpOpen" class="library-help">
      <button
        type="button"
        class="icon-button"
        aria-label="关闭资料库说明"
        @click="
          helpOpen = false;
          helpButton?.focus();
        "
      >
        <X :size="15" />
      </button>
      <strong>从资料，到自己的知识</strong>
      <p>
        资料保留原文，主题由 AI
        自动整理。聊清楚后，在对话中点“整理入库”，由你确认后才成为知识。
      </p>
      <details>
        <summary>在 Obsidian 中查看</summary>
        <p>
          打开本机
          <code>~/Documents/Decision Knowledge</code
          >。这里只同步已确认知识；单向同步状态以本机服务为准，入库成功不代表文件已同步。
        </p>
      </details>
    </div>
    <form
      v-if="addOpen && contextId"
      class="library-add-form"
      @submit.prevent="emit('ingest')"
    >
      <div class="library-add-heading">
        <h2>添加到当前对话</h2>
        <button
          type="button"
          class="icon-button"
          aria-label="收起添加资料"
          @click="addOpen = false"
        >
          <X :size="16" />
        </button>
      </div>
      <p class="muted">{{ contextTitle }}</p>
      <div class="library-add-fields">
        <label
          >标题（可选）<input
            :value="title"
            @input="
              emit('update:title', ($event.target as HTMLInputElement).value)
            " /></label
        ><label
          >文章链接<input
            :value="url"
            type="url"
            placeholder="https://…"
            @input="
              emit('update:url', ($event.target as HTMLInputElement).value)
            "
        /></label>
      </div>
      <label
        >或粘贴正文<textarea
          :value="text"
          rows="3"
          placeholder="链接无法读取时可以粘贴正文；同时提供时优先保存正文。"
          @input="
            emit('update:text', ($event.target as HTMLTextAreaElement).value)
          "
        />
      </label>
      <button :disabled="busy || (!text.trim() && !url.trim())">
        保存并整理资料
      </button>
    </form>
    <nav class="library-tabs" aria-label="资料类型">
      <button
        v-for="item in libraryKinds"
        :key="item.value"
        type="button"
        :class="{ active: kind === item.value }"
        :aria-current="kind === item.value ? 'page' : undefined"
        @click="switchKind(item.value)"
      >
        {{ item.label }}<span>{{ count(item.value) }}</span>
      </button>
      <span class="library-global-scope">全部对话</span>
    </nav>
    <div
      v-if="kind === 'knowledge' && domains.length"
      class="library-domains"
      role="group"
      aria-label="知识领域"
    >
      <button
        type="button"
        :class="{ active: !domain }"
        :aria-pressed="!domain"
        @click="domain = ''"
      >
        全部领域</button
      ><button
        v-for="item in domains"
        :key="item"
        type="button"
        :class="{ active: domain === item }"
        :aria-pressed="domain === item"
        @click="domain = item"
      >
        <i
          v-if="graphView"
          class="graph-domain-dot"
          :style="{ background: domainColor(item) }"
        />{{ item }}
      </button>
    </div>
    <KnowledgeGraph
      v-if="kind === 'knowledge' && graphView"
      ref="graph"
      :entries="
        reader.entries.value.filter((entry) =>
          visible.some((row) => row.id === entry.id),
        )
      "
      :all-entries="reader.entries.value"
      :sources="sources"
      :loading="reader.loading.value"
      :error="reader.knowledgeError.value"
      :can-navigate="canNavigate && !busy"
      @source="emit('source', $event)"
      @conversation="emit('conversation', $event)"
      @retry="refreshKnowledge"
      @reveal="revealGraph"
    />
    <div v-else class="library-workspace">
      <section
        ref="listPane"
        class="library-browser"
        :aria-label="kindLabel(kind) + '列表'"
      >
        <div class="library-list-heading">
          <span
            >{{ query.trim() ? "搜索结果" : kindLabel(kind) }}
            <b>{{ visible.length }}</b></span
          ><span>最近更新</span>
        </div>
        <div
          v-if="reader.loading.value && kind === 'knowledge'"
          class="library-empty"
        >
          <LoaderCircle :size="18" class="spinning" />
          <p>正在读取知识…</p>
        </div>
        <div
          v-else-if="
            !visible.length &&
            !(kind === 'knowledge' && reader.knowledgeError.value)
          "
          class="library-empty"
          role="status"
        >
          <component :is="icon(kind)" :size="25" />
          <h3>
            {{
              query.trim() || domain
                ? "没有找到匹配内容"
                : kind === "knowledge"
                  ? "聊清楚，再留下来"
                  : kind === "topic"
                    ? "资料会在这里形成主题"
                    : "把值得留住的资料放进来"
            }}
          </h3>
          <p>
            {{
              query.trim() || domain
                ? "换个词试试，或清除筛选。"
                : kind === "knowledge"
                  ? "先和 AI 讨论，再确认值得保存的内容。"
                  : kind === "topic"
                    ? "保存资料后，AI 整理的主题会出现在这里。"
                    : contextId
                      ? "点上方“添加资料”，保存文章或正文。"
                      : "先打开一个对话，再添加文章或正文。"
            }}
          </p>
          <button
            v-if="query.trim() || domain"
            type="button"
            class="secondary"
            @click="
              query = '';
              domain = '';
            "
          >
            清除筛选
          </button>
        </div>
        <div
          v-else
          class="library-list"
          role="listbox"
          :aria-label="kindLabel(kind)"
        >
          <button
            v-for="(row, index) in visible"
            :key="row.kind + row.id"
            type="button"
            class="library-row"
            role="option"
            :aria-selected="selected === row.id"
            :tabindex="selected === row.id || !selected ? 0 : -1"
            @click="select(row)"
            @keydown="keyMove($event, index)"
          >
            <span class="library-row-main"
              ><component :is="icon(row.kind)" :size="17" /><strong>{{
                row.title
              }}</strong
              ><ChevronRight :size="14"
            /></span>
            <span v-if="row.preview" class="library-row-excerpt">{{
              row.preview
            }}</span>
            <span class="library-row-meta"
              ><span v-if="row.kind === 'knowledge'" class="library-confirmed"
                ><Check :size="11" />已确认</span
              ><span v-else-if="row.kind === 'topic'">自动整理</span
              ><span
                v-else-if="row.status !== 'ready'"
                :class="{ 'library-row-warning': row.status === 'failed' }"
                >{{ statusLabel(row.status) }}</span
              ><time>{{ date(row.date) }}</time
              ><span class="library-row-conversation">{{
                problemTitle(row.problemId)
              }}</span></span
            >
          </button>
        </div>
        <div
          v-if="reader.knowledgeError.value && kind === 'knowledge'"
          class="library-list-error"
        >
          <p role="alert">{{ reader.knowledgeError.value }}</p>
          <button type="button" class="secondary" @click="refreshKnowledge">
            重新读取
          </button>
        </div>
      </section>
      <section
        ref="readingPane"
        class="library-reader"
        tabindex="-1"
        aria-label="阅读区"
        @keydown.esc="back"
      >
        <button type="button" class="library-back secondary" @click="back">
          <ArrowLeft :size="16" />返回列表
        </button>
        <div v-if="!current" class="library-reader-idle">
          <FolderOpen :size="34" />
          <p>选一份内容，安静地读一读。</p>
          <small>原文、出处和整理历史都在这里。</small>
        </div>
        <template v-else>
          <div class="library-reader-kind">
            <component :is="icon(current.kind)" :size="14" />{{
              kindLabel(current.kind)
            }}<span v-if="current.kind === 'topic'">AI 自动整理</span
            ><span v-else-if="current.kind === 'knowledge'">由你确认保存</span>
          </div>
          <h2 class="library-reader-title">{{ current.title }}</h2>
          <div class="library-reader-meta">
            <time>{{ date(current.date) }}</time
            ><span v-if="sourceHost && current.kind === 'source'">{{
              sourceHost
            }}</span
            ><span>{{ problemTitle(current.problemId) }}</span>
          </div>
          <div class="library-reader-actions">
            <button
              type="button"
              class="secondary"
              :disabled="!canNavigate || busy"
              @click="emit('conversation', current.problemId)"
            >
              打开原对话<ArrowUpRight :size="13" />
            </button>
            <a
              v-if="
                current.kind === 'source' &&
                safeUrl(reader.source.value?.originUrl)
              "
              :href="safeUrl(reader.source.value?.originUrl)"
              target="_blank"
              rel="noopener noreferrer"
              >原始链接<ArrowUpRight :size="13"
            /></a>
            <button
              v-if="current.kind === 'source'"
              type="button"
              class="library-text-action"
              :disabled="busy"
              @click="emit('source', current.id)"
            >
              原文详情
            </button>
            <button
              v-if="current.kind === 'source' && isOwnContext"
              type="button"
              class="library-text-action library-delete"
              :disabled="busy"
              @click="emit('remove', current.id)"
            >
              删除资料
            </button>
          </div>
          <div
            v-if="reader.reading.value"
            class="library-reader-loading"
            role="status"
          >
            <LoaderCircle :size="18" class="spinning" />正在读取正文…
          </div>
          <div
            v-if="reader.error.value"
            class="library-reader-error"
            role="alert"
          >
            <p>{{ reader.error.value }}</p>
            <button type="button" class="secondary" @click="select(current)">
              重新读取
            </button>
          </div>
          <article
            v-if="current.kind === 'source' && reader.source.value"
            class="library-article"
          >
            <p
              v-if="reader.source.value.status !== 'ready'"
              class="library-read-notice"
            >
              {{
                statusLabel(reader.source.value.status)
              }}：请以当前可读正文为准。
            </p>
            <div class="library-source-body">
              {{ reader.source.value.text }}
            </div>
          </article>
          <article
            v-else-if="current.kind === 'topic' && currentTopic"
            class="library-article"
          >
            <div class="library-topic-notice">
              主题是 AI 对资料的整理，尚未经你确认入库。
            </div>
            <div
              class="markdown-body"
              v-html="renderMarkdown(currentTopic.markdown)"
            />
            <EvidencePanel
              :evidence="currentTopic.evidence"
              :sources="sources"
              :busy="busy"
              @open="(id, quotes) => emit('source', id, quotes)"
            />
            <details class="library-secondary-actions">
              <summary>整理与 Wiki</summary>
              <p class="muted">
                Wiki：{{
                  currentTopic.syncStatus === "failed"
                    ? "同步失败"
                    : statusLabel(currentTopic.syncStatus)
                }}
              </p>
              <a
                v-if="safeUrl(currentTopic.wikiUrl)"
                :href="safeUrl(currentTopic.wikiUrl)"
                target="_blank"
                rel="noopener noreferrer"
                >查看 Wiki<ArrowUpRight :size="13"
              /></a>
              <div v-if="isOwnContext">
                <button
                  type="button"
                  class="secondary"
                  :disabled="
                    busy || !sources.some((s) => s.problemId === contextId)
                  "
                  @click="emit('reanalyze')"
                >
                  重新分析资料</button
                ><button
                  type="button"
                  class="secondary"
                  :disabled="busy"
                  @click="emit('sync', currentTopic.id)"
                >
                  同步 Wiki
                </button>
              </div>
              <p v-else class="muted">需要重新整理时，请先打开原对话。</p>
            </details>
          </article>
          <article
            v-else-if="current.kind === 'knowledge' && confirmed"
            class="library-article"
          >
            <div class="library-knowledge-domains">
              <span v-for="item in confirmed.domains" :key="item">{{
                item
              }}</span
              ><small>第 {{ confirmed.revision }} 版</small>
            </div>
            <div
              class="markdown-body"
              v-html="renderMarkdown(confirmed.markdown)"
            />
            <KnowledgeReadScope :coverage="confirmed.coverage" />
            <KnowledgeReferences
              :refs="confirmed.refs"
              :disabled="busy"
              @source="emit('source', $event)"
            />
            <div v-if="related.length" class="library-related">
              <h3>相关知识</h3>
              <button
                v-for="entry in related"
                :key="entry.id"
                type="button"
                @click="selectRelated(entry.id)"
              >
                {{ entry.title }}<ChevronRight :size="14" />
              </button>
            </div>
            <details class="library-knowledge-history">
              <summary>
                历史版本 <span>{{ reader.history.value.length }}</span>
              </summary>
              <p v-if="!reader.history.value.length" class="muted">
                这是第一个版本。
              </p>
              <article
                v-for="version in reader.history.value"
                :key="version.revision"
              >
                <h3>第 {{ version.revision }} 版 · {{ version.title }}</h3>
                <small>{{ date(version.updatedAt) }}</small>
                <div
                  class="markdown-body"
                  v-html="renderMarkdown(version.markdown)"
                />
                <KnowledgeReadScope
                  :coverage="version.coverage"
                /><KnowledgeReferences
                  :refs="version.refs"
                  :disabled="busy"
                  @source="emit('source', $event)"
                />
              </article>
            </details>
          </article>
        </template>
      </section>
    </div>
  </section>
</template>
