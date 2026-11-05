<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from "vue";
import cytoscape, { type Core, type Layouts } from "cytoscape";
import {
  ArrowUpRight,
  BookOpen,
  Expand,
  List,
  Minus,
  Network,
  Plus,
  X,
} from "@lucide/vue";
import type { KnowledgeEntry, Source } from "./types";
import { request, sessionId } from "./api";
import {
  domainColor,
  graphBody,
  graphElements,
  referenceKey,
  safeGraphUrl,
  type GraphReference,
} from "./knowledgeGraph";
import { renderMarkdown } from "./markdown";
import KnowledgeReadScope from "./KnowledgeReadScope.vue";
import "./knowledgeGraph.css";

const props = defineProps<{
  entries: KnowledgeEntry[];
  allEntries: KnowledgeEntry[];
  sources: Source[];
  loading: boolean;
  error: string;
  canNavigate: boolean;
}>();
const emit = defineEmits<{
  source: [id: string];
  conversation: [id: string];
  retry: [];
  reveal: [id: string];
}>();
const canvas = ref<HTMLElement>();
const detail = ref<HTMLElement>();
const selected = ref("");
const listing = ref(false);
const narrow = ref(window.matchMedia("(max-width: 760px)").matches);
const current = computed(() =>
  props.entries.find((entry) => entry.id === selected.value),
);
const related = computed(() =>
  props.allEntries.filter((entry) =>
    current.value?.relatedEntryIds.includes(entry.id),
  ),
);
const references = ref(new Map<string, GraphReference>());
const referencesLoaded = ref(false);
const referencesBusy = ref(false);
const referenceError = ref("");
const canvasError = ref("");
let graph: Core | undefined, layout: Layouts | undefined;
let resize: ResizeObserver, theme: MutationObserver;
let generation = 0,
  disposed = false;
const connectionCount = computed(
  () => graphElements(props.entries).length - props.entries.length,
);
function metadata(
  ref: GraphReference | KnowledgeEntry["refs"][number],
): GraphReference | undefined {
  const exported = references.value.get(referenceKey(ref));
  if (exported) return exported;
  const source =
    ref.kind === "source" && props.sources.find((item) => item.id === ref.id);
  return source
    ? {
        ...ref,
        title: source.title,
        problemId: source.problemId,
        originUrl: source.originUrl,
      }
    : undefined;
}
async function loadReferences() {
  if (referencesBusy.value || referencesLoaded.value) return;
  const g = generation,
    owner = sessionId();
  referencesBusy.value = true;
  referenceError.value = "";
  try {
    const value = await request<{ references: GraphReference[] }>(
      "/knowledge-export",
    );
    if (disposed || g !== generation || owner !== sessionId()) return;
    references.value = new Map(
      value.references.map((item) => [referenceKey(item), item]),
    );
    referencesLoaded.value = true;
  } catch (failure) {
    if (!disposed && g === generation && owner === sessionId())
      referenceError.value =
        failure instanceof Error ? failure.message : "出处暂时无法读取。";
  } finally {
    if (!disposed && g === generation) referencesBusy.value = false;
  }
}
async function choose(id: string) {
  selected.value = id;
  listing.value = false;
  highlight();
  void loadReferences();
  await nextTick();
  detail.value?.focus({ preventScroll: true });
}
async function closeDetail() {
  selected.value = "";
  highlight();
  await nextTick();
  canvas.value?.focus({ preventScroll: true });
}
function highlight() {
  if (!graph) return;
  graph.elements().removeClass("dimmed focus");
  if (!selected.value) return;
  const node = graph.getElementById(selected.value);
  graph.elements().difference(node.closedNeighborhood()).addClass("dimmed");
  node.addClass("focus");
}
function themeGraph() {
  if (!graph) return;
  const style = getComputedStyle(document.documentElement);
  graph.style([
    {
      selector: "node",
      style: {
        "background-color": "data(color)",
        width: "data(size)",
        height: "data(size)",
        label: "data(label)",
        color: style.getPropertyValue("--secondary").trim(),
        "font-size": 14,
        "font-family":
          "-apple-system, BlinkMacSystemFont, PingFang SC, sans-serif",
        "text-valign": "bottom",
        "text-margin-y": 9,
        "text-wrap": "wrap",
        "text-overflow-wrap": "anywhere",
        "text-max-width": "115px",
        "border-width": 3,
        "border-color": style.getPropertyValue("--surface").trim(),
        "min-zoomed-font-size": 8,
      },
    },
    {
      selector: "edge",
      style: {
        width: 1.2,
        "line-color": style.getPropertyValue("--muted").trim(),
        opacity: 0.32,
        "curve-style": "bezier",
      },
    },
    { selector: ".dimmed", style: { opacity: 0.16 } },
    {
      selector: "node.focus",
      style: {
        "border-width": 5,
        "border-color": style.getPropertyValue("--brand").trim(),
        color: style.getPropertyValue("--ink").trim(),
        "font-weight": 600,
      },
    },
  ]);
}
function fit() {
  if (!graph?.nodes().length) return;
  graph.fit(undefined, 80);
  if (graph.zoom() > 1) graph.zoom(1);
  graph.center();
}
function zoom(factor: number) {
  if (!graph || !canvas.value) return;
  graph.zoom({
    level: Math.max(0.15, Math.min(3, graph.zoom() * factor)),
    renderedPosition: {
      x: canvas.value.clientWidth / 2,
      y: canvas.value.clientHeight / 2,
    },
  });
}
function keydown(event: KeyboardEvent) {
  if (!graph) return;
  const offsets: Record<string, { x: number; y: number }> = {
    ArrowLeft: { x: 40, y: 0 },
    ArrowRight: { x: -40, y: 0 },
    ArrowUp: { x: 0, y: 40 },
    ArrowDown: { x: 0, y: -40 },
  };
  if (offsets[event.key]) {
    event.preventDefault();
    graph.panBy(offsets[event.key]!);
  } else if (["+", "=", "-"].includes(event.key)) {
    event.preventDefault();
    zoom(event.key === "-" ? 0.8 : 1.25);
  } else if (event.key === "Home") {
    event.preventDefault();
    fit();
  }
}
async function draw() {
  await nextTick();
  if (disposed || !canvas.value) return;
  canvasError.value = "";
  try {
    if (!graph) {
      graph = cytoscape({
        container: canvas.value,
        elements: [],
        minZoom: 0.15,
        maxZoom: 3,
        wheelSensitivity: 0.2,
        boxSelectionEnabled: false,
        autounselectify: true,
      });
      graph.on("tap", "node", (event) => {
        void choose(event.target.id());
      });
      themeGraph();
    }
    layout?.stop();
    graph.batch(() => {
      graph!.elements().remove();
      graph!.add(graphElements(props.entries));
    });
    layout = graph.layout({
      name: "cose",
      animate: false,
      randomize: true,
      nodeRepulsion: () => 80000,
      idealEdgeLength: () => 100,
      componentSpacing: 100,
      nodeOverlap: 30,
      nodeDimensionsIncludeLabels: true,
      numIter: props.entries.length > 300 ? 250 : 650,
      padding: 70,
    });
    layout.run();
    fit();
    highlight();
  } catch {
    canvasError.value = "图谱暂时无法绘制，可以通过节点列表继续阅读。";
    listing.value = true;
  }
}
watch(
  () => props.entries,
  () => {
    if (!current.value) selected.value = "";
    void draw();
  },
);
watch(
  () => props.allEntries,
  () => {
    generation++;
    references.value.clear();
    referencesLoaded.value = false;
    referencesBusy.value = false;
    if (selected.value) void loadReferences();
  },
);
onMounted(() => {
  void draw();
  resize = new ResizeObserver(() => {
    graph?.resize();
    narrow.value = window.matchMedia("(max-width: 760px)").matches;
  });
  if (canvas.value) resize.observe(canvas.value);
  theme = new MutationObserver(themeGraph);
  theme.observe(document.documentElement, {
    attributes: true,
    attributeFilter: ["data-theme", "style"],
  });
});
onUnmounted(() => {
  disposed = true;
  generation++;
  resize?.disconnect();
  theme?.disconnect();
  layout?.stop();
  graph?.destroy();
  graph = undefined;
  references.value.clear();
});
defineExpose({ choose });
</script>

<template>
  <section
    class="knowledge-graph"
    aria-label="已确认知识图谱"
    :class="{ 'has-selection': current }"
  >
    <div
      ref="canvas"
      class="graph-canvas"
      :inert="!!current && narrow"
      tabindex="0"
      aria-label="知识关系图，方向键平移，加减键缩放，Home显示全图。也可通过浏览节点选择知识。"
      @keydown="keydown"
    />
    <div class="graph-caption" aria-live="polite">
      <Network :size="15" /><span
        >{{ entries.length }} 条知识<span class="graph-stat-separator">·</span
        >{{ connectionCount }} 个关联</span
      >
    </div>
    <div
      class="graph-controls"
      :inert="!!current && narrow"
      aria-label="图谱操作"
    >
      <button
        class="icon-button"
        title="放大"
        aria-label="放大图谱"
        @click="zoom(1.25)"
      >
        <Plus :size="17" />
      </button>
      <button
        class="icon-button"
        title="缩小"
        aria-label="缩小图谱"
        @click="zoom(0.8)"
      >
        <Minus :size="17" />
      </button>
      <button
        class="icon-button"
        title="显示全图"
        aria-label="显示全图"
        @click="fit"
      >
        <Expand :size="16" />
      </button>
      <span />
      <button
        class="icon-button"
        title="浏览节点"
        aria-label="浏览节点"
        :aria-expanded="listing"
        @click="listing = !listing"
      >
        <List :size="18" />
      </button>
    </div>
    <div
      v-if="loading || error || !entries.length"
      class="graph-empty"
      role="status"
    >
      <Network :size="46" :stroke-width="1" />
      <h2>
        {{
          loading
            ? "正在展开你的知识…"
            : error
              ? "知识暂时无法读取"
              : allEntries.length
                ? "没有匹配的知识"
                : "让想法慢慢连成一片"
        }}
      </h2>
      <p>
        {{
          error ||
          (allEntries.length
            ? "试试其他关键词或领域。"
            : "和 AI 聊清楚，再确认值得留下的内容。")
        }}
      </p>
      <button v-if="error" class="secondary" @click="emit('retry')">
        重新读取
      </button>
    </div>
    <p v-if="canvasError" role="alert" class="graph-drawing-error">
      {{ canvasError }}
    </p>
    <div v-if="listing && entries.length" class="graph-node-list">
      <header>
        <h3>浏览节点</h3>
        <button
          class="icon-button"
          aria-label="关闭节点列表"
          @click="listing = false"
        >
          <X :size="16" />
        </button>
      </header>
      <button
        v-for="entry in entries"
        :key="entry.id"
        :aria-pressed="selected === entry.id"
        @click="choose(entry.id)"
      >
        <i
          :style="{ background: domainColor(entry.domains[0] || '待归类') }"
        /><span>{{ entry.title }}</span>
      </button>
    </div>
    <p v-if="entries.length && !current && !listing" class="graph-hint">
      拖动探索 · 滚动缩放 · 点击知识阅读
    </p>
    <aside
      v-if="current"
      ref="detail"
      tabindex="-1"
      class="graph-details"
      aria-label="知识详情"
      @keydown.esc.stop="closeDetail"
    >
      <header>
        <span><BookOpen :size="14" />已确认知识</span
        ><button
          class="icon-button"
          aria-label="关闭知识详情"
          @click="closeDetail"
        >
          <X :size="18" />
        </button>
      </header>
      <div class="graph-detail-scroll">
        <div class="graph-entry-domains">
          <span v-for="domain in current.domains" :key="domain"
            ><i :style="{ background: domainColor(domain) }" />{{
              domain
            }}</span
          >
        </div>
        <h2>{{ current.title }}</h2>
        <div
          class="markdown-body"
          v-html="renderMarkdown(graphBody(current.title, current.markdown))"
        />
        <div v-if="related.length" class="graph-related">
          <h3>相关知识</h3>
          <button
            v-for="entry in related"
            :key="entry.id"
            @click="emit('reveal', entry.id)"
          >
            {{ entry.title }}<ArrowUpRight :size="13" />
          </button>
        </div>
        <details class="graph-references">
          <summary>
            出处 <span>{{ current.refs.length }}</span>
          </summary>
          <p v-if="referencesBusy" class="muted" role="status">正在读取出处…</p>
          <div v-if="referenceError" role="alert">
            <p>{{ referenceError }}</p>
            <button class="secondary" @click="loadReferences">
              重新读取出处
            </button>
          </div>
          <article
            v-for="(reference, index) in current.refs"
            :key="referenceKey(reference) + index"
          >
            <small>{{
              reference.kind === "source"
                ? "来源资料"
                : reference.role === "user"
                  ? "你的原话"
                  : "AI 讨论内容（未经独立证实）"
            }}</small>
            <p>{{ reference.quote }}</p>
            <a
              v-if="safeGraphUrl(metadata(reference)?.originUrl)"
              :href="safeGraphUrl(metadata(reference)?.originUrl)"
              target="_blank"
              rel="noopener noreferrer"
              >{{ metadata(reference)?.title || "原网页"
              }}<ArrowUpRight :size="13"
            /></a>
            <button
              v-else-if="reference.kind === 'source'"
              class="graph-reference-link"
              :disabled="!canNavigate"
              @click="emit('source', reference.id)"
            >
              查看来源资料<ArrowUpRight :size="13" />
            </button>
            <button
              v-else-if="metadata(reference)?.problemId"
              class="graph-reference-link"
              :disabled="!canNavigate"
              @click="emit('conversation', metadata(reference)!.problemId!)"
            >
              回到原对话<ArrowUpRight :size="13" />
            </button>
            <small v-else-if="referencesLoaded"
              >原对话已不可用，保留当时的引用。</small
            >
          </article>
        </details>
        <details class="graph-read-scope">
          <summary>整理范围</summary>
          <KnowledgeReadScope :coverage="current.coverage" />
        </details>
      </div>
    </aside>
  </section>
</template>
