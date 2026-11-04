<script setup lang="ts">
import type { KnowledgeCoverage } from "./types";
defineProps<{ coverage: KnowledgeCoverage }>();
</script>

<template>
  <aside class="knowledge-read-scope">
    <p v-if="coverage.truncated" class="muted">仅覆盖当时选取的部分内容。</p>
    <p class="muted">
      {{
        coverage.notice ||
        "仅使用对话文字与正式来源，未直接读取附件原件；对照范围有限，不代表完整冲突检测。"
      }}
    </p>
    <details class="knowledge-coverage">
      <summary>查看当时整理范围</summary>
      <p class="muted">
        对话 {{ coverage.messagesConsidered }}/{{ coverage.messagesTotal }} 条 ·
        来源 {{ coverage.sourcesConsidered }}/{{ coverage.sourcesTotal }} 份 ·
        对照已确认知识 {{ coverage.knowledgeConsidered }}/{{
          coverage.knowledgeTotal
        }}
        条。
      </p>
    </details>
  </aside>
</template>
