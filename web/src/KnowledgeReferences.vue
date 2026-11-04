<script setup lang="ts">
import type { KnowledgeRef } from "./types";
defineProps<{ refs: KnowledgeRef[]; disabled?: boolean }>();
defineEmits<{ source: [id: string] }>();
</script>

<template>
  <details v-if="refs.length" class="knowledge-references">
    <summary>查看出处 · {{ refs.length }}</summary>
    <blockquote v-for="(reference, index) in refs" :key="index">
      <small>{{
        reference.kind === "source"
          ? "正式来源"
          : reference.role === "user"
            ? "你的原话"
            : "AI 讨论内容（未经独立证实）"
      }}</small>
      <p class="pre">{{ reference.quote }}</p>
      <button
        v-if="reference.kind === 'source'"
        type="button"
        class="secondary"
        :disabled="disabled"
        @click="$emit('source', reference.id)"
      >
        查看来源
      </button>
    </blockquote>
  </details>
</template>
