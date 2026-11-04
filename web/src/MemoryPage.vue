<script setup lang="ts">
import { computed } from "vue";
import MemoryDetails from "./MemoryDetails.vue";
import { checkedOverviewItems } from "./memoryOverview";
import type { Evidence, Memory, MemoryOverview, Source } from "./types";
const props = defineProps<{
  memories: Memory[];
  overview: MemoryOverview | null;
  loading: boolean;
  error: string;
  uncertain: boolean;
  busy: boolean;
  sources: Source[];
}>();
const emit = defineEmits<{
  correct: [memory: Memory];
  forget: [memory: Memory];
  remove: [id: string];
  source: [id: string, evidence: Evidence[]];
  refresh: [];
  retry: [];
  resume: [];
}>();
const items = computed(() =>
  checkedOverviewItems(props.overview, props.memories),
);
const referenced = (refs: { id: string; revision: number }[]) =>
  refs
    .map((ref) => props.memories.find((memory) => memory.id === ref.id)!)
    .filter(Boolean);
</script>

<template>
  <section class="memory-page">
    <h2>记住的重点</h2>
    <p class="muted memory-intro">从日常对话中提炼，细节保留在原记录里。</p>
    <p v-if="loading" class="muted" role="status">
      正在整理重点，你可以继续聊天。
    </p>
    <p v-else-if="overview?.status === 'empty'" class="empty">
      还没有记忆重点，聊天中的偏好会整理在这里。
    </p>
    <div v-else-if="error || items === null" class="memory-state" role="status">
      <p>{{ error || "原记录已更新，请重新查看最新重点。" }}</p>
      <button
        v-if="uncertain"
        type="button"
        class="secondary"
        :disabled="busy"
        @click="emit('resume')"
      >
        重新连接
      </button>
      <button
        v-else-if="overview?.status === 'failed' && overview.error?.retryable"
        type="button"
        class="secondary"
        :disabled="busy"
        @click="emit('retry')"
      >
        重新整理
      </button>
      <button
        v-else
        type="button"
        class="secondary"
        :disabled="busy"
        @click="emit('refresh')"
      >
        重新查看
      </button>
    </div>
    <div v-if="overview?.status === 'ready' && items" class="memory-highlights">
      <details
        v-for="(item, index) in items"
        :key="overview.fingerprint + ':' + index"
        class="memory-highlight"
      >
        <summary>
          <span>{{ item.summary }}</span
          ><small
            >{{ item.scope === "global" ? "用于其他对话" : "仅用于原对话" }} ·
            详情</small
          >
        </summary>
        <MemoryDetails
          v-for="memory in referenced(item.memoryRefs)"
          :key="memory.id + ':' + memory.revision"
          :memory="memory"
          :sources="sources"
          :busy="busy"
          @correct="emit('correct', $event)"
          @forget="emit('forget', $event)"
          @remove="emit('remove', $event)"
          @source="(id, evidence) => emit('source', id, evidence)"
        />
      </details>
      <p v-if="!items.length" class="muted">
        暂时没有可提炼的重点，原记录仍可查看。
      </p>
      <p v-if="overview.coverage.truncated" class="muted memory-coverage">
        本次重点基于部分记录整理，全部记录仍保留在下方。
      </p>
    </div>
    <details class="memory-all">
      <summary>查看全部记录（{{ memories.length }}）</summary>
      <p v-if="!memories.length" class="muted">还没有记录。</p>
      <details
        v-for="(memory, index) in memories"
        :key="memory.id + ':' + memory.revision"
        class="memory-raw"
      >
        <summary>
          记录 {{ index + 1
          }}<span v-if="memory.status !== 'active'"> · 已忘记</span
          ><span v-else-if="memory.kind === 'inferred'"> · 推断</span
          ><span v-else-if="memory.kind === 'observed'"> · 观察所得</span>
        </summary>
        <MemoryDetails
          :memory="memory"
          :sources="sources"
          :busy="busy"
          @correct="emit('correct', $event)"
          @forget="emit('forget', $event)"
          @remove="emit('remove', $event)"
          @source="(id, evidence) => emit('source', id, evidence)"
        />
      </details>
    </details>
  </section>
</template>
