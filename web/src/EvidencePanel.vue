<script setup lang="ts">
import { computed, ref } from "vue";
import { ChevronRight, FileText } from "@lucide/vue";
import type { Evidence, Source } from "./types";
const props = defineProps<{
  evidence: Evidence[];
  uncertainties?: string[];
  sources: Source[];
  busy: boolean;
}>();
const emit = defineEmits<{ open: [sourceId: string, evidence: Evidence[]] }>();
const allUncertainties = ref(false);
const groups = computed(() => {
  const bySource = new Map<string, Evidence[]>();
  for (const entry of props.evidence) {
    const entries = bySource.get(entry.sourceId) || [];
    if (
      !entries.some(
        (e) => e.quote === entry.quote && e.relation === entry.relation,
      )
    )
      entries.push(entry);
    bySource.set(entry.sourceId, entries);
  }
  return Array.from(bySource, ([id, entries], index) => ({
    id,
    entries,
    title:
      props.sources.find((source) => source.id === id)?.title ||
      `来源 ${index + 1}`,
    relations: Array.from(
      new Set(
        entries.map((e) =>
          e.relation === "supports"
            ? "支持"
            : e.relation === "contradicts"
              ? "不同依据"
              : "背景",
        ),
      ),
    ).join(" / "),
  }));
});
const notes = computed(() =>
  allUncertainties.value
    ? props.uncertainties || []
    : (props.uncertainties || []).slice(0, 2),
);
</script>

<template>
  <details v-if="groups.length || uncertainties?.length" class="evidence-panel">
    <summary>
      <span v-if="groups.length">{{ groups.length }} 个来源</span
      ><span v-if="groups.length && uncertainties?.length" aria-hidden="true">
        · </span
      ><span v-if="uncertainties?.length">有待确认</span>
    </summary>
    <ul v-if="groups.length" class="citation-list">
      <li v-for="group in groups" :key="group.id">
        <button
          class="citation-row"
          :disabled="busy"
          @click="emit('open', group.id, group.entries)"
        >
          <FileText :size="15" />
          <span class="citation-copy"
            ><span class="citation-title">{{ group.title }}</span
            ><span class="citation-excerpt">{{ group.entries[0]?.quote }}</span
            ><span class="citation-meta"
              >{{ group.relations
              }}<span v-if="group.entries.length > 1">
                · {{ group.entries.length }} 处引用</span
              ></span
            ></span
          >
          <ChevronRight :size="14" />
        </button>
      </li>
    </ul>
    <ul v-if="notes.length" class="uncertainty-list">
      <li v-for="(note, index) in notes" :key="index">
        <details v-if="note.length > 80">
          <summary>{{ note.slice(0, 80) }}… <span>展开</span></summary>
          <p>{{ note }}</p>
        </details>
        <span v-else>{{ note }}</span>
      </li>
    </ul>
    <button
      v-if="(uncertainties?.length || 0) > 2"
      class="text-button"
      @click="allUncertainties = !allUncertainties"
    >
      {{
        allUncertainties
          ? "收起"
          : `查看全部 ${uncertainties!.length} 条待确认内容`
      }}
    </button>
  </details>
</template>
