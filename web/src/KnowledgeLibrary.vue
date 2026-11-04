<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from "vue";
import { list, request, sessionId } from "./api";
import { renderMarkdown } from "./markdown";
import type { KnowledgeEntry } from "./types";
import KnowledgeReferences from "./KnowledgeReferences.vue";
import KnowledgeReadScope from "./KnowledgeReadScope.vue";
defineProps<{ disabled: boolean }>();
defineEmits<{ source: [id: string]; conversation: [id: string] }>();
const entries = ref<KnowledgeEntry[]>([]),
  selected = ref<KnowledgeEntry | null>(null),
  history = ref<KnowledgeEntry[]>([]);
const domain = ref(""),
  query = ref(""),
  error = ref(""),
  loading = ref(false),
  detailLoading = ref(false);
let generation = 0,
  selection = 0;
const domains = computed(() =>
  [...new Set(entries.value.flatMap((e) => e.domains))].sort((a, b) =>
    a.localeCompare(b, "zh-CN"),
  ),
);
const filtered = computed(() =>
  entries.value.filter(
    (e) =>
      (!domain.value || e.domains.includes(domain.value)) &&
      (!query.value.trim() ||
        `${e.title}\n${e.markdown}`
          .toLocaleLowerCase()
          .includes(query.value.trim().toLocaleLowerCase())),
  ),
);
const related = computed(() =>
  entries.value.filter((e) => selected.value?.relatedEntryIds.includes(e.id)),
);
const date = (value: string) => new Date(value).toLocaleString("zh-CN");
async function load() {
  const g = ++generation,
    owner = sessionId();
  close();
  const active = () => g === generation && owner === sessionId() && !!owner;
  loading.value = true;
  error.value = "";
  try {
    const value = await list<KnowledgeEntry>("/knowledge-entries");
    if (active()) entries.value = value;
  } catch (value) {
    if (active())
      error.value =
        value instanceof Error ? value.message : "知识暂时无法读取。";
  } finally {
    if (active()) loading.value = false;
  }
}
async function open(id: string) {
  const g = generation,
    s = ++selection,
    owner = sessionId();
  const active = () =>
    g === generation && s === selection && owner === sessionId() && !!owner;
  selected.value = null;
  history.value = [];
  detailLoading.value = true;
  error.value = "";
  try {
    const [entry, previous] = await Promise.all([
      request<KnowledgeEntry>(`/knowledge-entries/${encodeURIComponent(id)}`),
      request<{ items: KnowledgeEntry[] }>(
        `/knowledge-entries/${encodeURIComponent(id)}/history`,
      ),
    ]);
    if (active()) {
      selected.value = entry;
      history.value = previous.items;
    }
  } catch (value) {
    if (active())
      error.value =
        value instanceof Error ? value.message : "条目暂时无法读取。";
  } finally {
    if (active()) detailLoading.value = false;
  }
}
function close() {
  selection++;
  selected.value = null;
  history.value = [];
  detailLoading.value = false;
}
onMounted(() => {
  void load();
});
onUnmounted(() => {
  generation++;
  selection++;
});
</script>

<template>
  <section class="knowledge-library" aria-label="已确认知识库">
    <div class="knowledge-library-header">
      <div>
        <h2>
          已确认知识 <span class="muted">{{ entries.length }}</span>
        </h2>
        <p class="muted">从讨论中沉淀，按领域浏览。</p>
      </div>
      <button
        type="button"
        class="secondary"
        :disabled="disabled || loading"
        @click="load"
      >
        刷新知识
      </button>
    </div>
    <p class="knowledge-mirror muted">
      Obsidian 请打开
      <code>~/Documents/Decision Knowledge</code
      >，查看本机单向镜像。这里的入库状态不代表文件已同步；同步状态以本机服务为准。
    </p>
    <p v-if="loading" role="status" class="muted">正在读取知识…</p>
    <p v-if="error" role="alert" class="error">{{ error }}</p>
    <p
      v-if="!loading && !error && !entries.length"
      class="knowledge-empty muted"
    >
      还没有确认入库的知识。先正常聊天，聊清楚后点“整理入库”，审阅建议再保存。
    </p>
    <template v-if="entries.length">
      <label class="knowledge-search"
        ><span class="sr-only">搜索已确认知识</span
        ><input v-model="query" type="search" placeholder="搜索知识标题或正文…"
      /></label>
      <div class="knowledge-domain-filter" role="group" aria-label="按领域筛选">
        <button
          type="button"
          class="secondary"
          :aria-pressed="!domain"
          @click="domain = ''"
        >
          全部
        </button>
        <button
          v-for="item in domains"
          :key="item"
          type="button"
          class="secondary"
          :aria-pressed="domain === item"
          @click="domain = item"
        >
          {{ item }}
          <span>{{
            entries.filter((e) => e.domains.includes(item)).length
          }}</span>
        </button>
      </div>
      <p v-if="!filtered.length" class="muted">没有符合条件的知识。</p>
      <div class="knowledge-entry-list">
        <button
          v-for="entry in filtered"
          :key="entry.id"
          type="button"
          class="knowledge-entry-button secondary"
          :disabled="disabled"
          :aria-pressed="selected?.id === entry.id"
          @click="open(entry.id)"
        >
          <strong>{{ entry.title }}</strong
          ><span class="muted"
            >{{ entry.domains.join(" · ") }} · 第 {{ entry.revision }} 版</span
          >
        </button>
      </div>
    </template>
    <p v-if="detailLoading" role="status" class="muted">正在读取正文与历史…</p>
    <article v-if="selected" class="knowledge-entry-detail">
      <div class="knowledge-library-header">
        <h3>{{ selected.title }}</h3>
        <button type="button" class="secondary" @click="close">收起正文</button>
      </div>
      <p class="muted">
        第 {{ selected.revision }} 版 · {{ date(selected.updatedAt) }} ·
        {{ selected.domains.join(" / ") }}
      </p>
      <div class="markdown-body" v-html="renderMarkdown(selected.markdown)" />
      <KnowledgeReadScope :coverage="selected.coverage" />
      <KnowledgeReferences
        :refs="selected.refs"
        :disabled="disabled"
        @source="$emit('source', $event)"
      />
      <div v-if="related.length" class="knowledge-related">
        <small>相关知识</small
        ><button
          v-for="entry in related"
          :key="entry.id"
          type="button"
          class="secondary"
          :disabled="disabled"
          @click="open(entry.id)"
        >
          {{ entry.title }}
        </button>
      </div>
      <button
        type="button"
        class="secondary"
        :disabled="disabled"
        @click="$emit('conversation', selected.problemId)"
      >
        回到整理时的对话
      </button>
      <details class="knowledge-history">
        <summary>历史版本 · {{ history.length }}</summary>
        <p v-if="!history.length" class="muted">这是首个版本，还没有旧版本。</p>
        <article
          v-for="version in history"
          :key="version.revision"
          class="knowledge-history-version"
        >
          <h4>第 {{ version.revision }} 版 · {{ version.title }}</h4>
          <p class="muted">
            {{ date(version.updatedAt) }} · {{ version.domains.join(" / ") }}
          </p>
          <div
            class="markdown-body"
            v-html="renderMarkdown(version.markdown)"
          />
          <KnowledgeReadScope :coverage="version.coverage" />
          <KnowledgeReferences
            :refs="version.refs"
            :disabled="disabled"
            @source="$emit('source', $event)"
          />
        </article>
      </details>
    </article>
  </section>
</template>
