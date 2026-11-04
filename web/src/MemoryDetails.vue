<script setup lang="ts">
import EvidencePanel from "./EvidencePanel.vue";
import type { Evidence, Memory, Source } from "./types";
defineProps<{ memory: Memory; sources: Source[]; busy: boolean }>();
const emit = defineEmits<{
  correct: [memory: Memory];
  forget: [memory: Memory];
  remove: [id: string];
  source: [id: string, evidence: Evidence[]];
}>();
</script>

<template>
  <div class="memory-detail">
    <p class="memory-context">
      {{ memory.scope === "global" ? "用于其他对话" : "仅用于原对话"
      }}<span v-if="memory.kind === 'inferred'"> · 推断，未由你确认</span
      ><span v-else-if="memory.kind === 'observed'"> · 观察所得</span
      ><span v-if="memory.status !== 'active'"> · 已忘记</span>
    </p>
    <p class="pre">{{ memory.content }}</p>
    <blockquote v-if="memory.sourceQuote" class="memory-quote">
      <small>你的原话</small>{{ memory.sourceQuote }}
    </blockquote>
    <EvidencePanel
      :evidence="memory.evidence"
      :sources="sources"
      :busy="busy"
      @open="(id, evidence) => emit('source', id, evidence)"
    />
    <div class="memory-actions">
      <button type="button" :disabled="busy" @click="emit('correct', memory)">
        纠正
      </button>
      <button
        type="button"
        :disabled="busy || memory.status !== 'active'"
        @click="emit('forget', memory)"
      >
        忘记
      </button>
      <button type="button" :disabled="busy" @click="emit('remove', memory.id)">
        删除
      </button>
    </div>
  </div>
</template>
