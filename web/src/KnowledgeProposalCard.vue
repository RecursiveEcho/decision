<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { BookOpen, Check, ArrowUpRight } from "@lucide/vue";
import type { KnowledgeEntry, KnowledgeProposal } from "./types";
import { graphBody } from "./knowledgeGraph";
import { renderMarkdown } from "./markdown";
import KnowledgeReferences from "./KnowledgeReferences.vue";
import KnowledgeReadScope from "./KnowledgeReadScope.vue";
const props = defineProps<{
  proposal: KnowledgeProposal;
  entries: KnowledgeEntry[];
  disabled: boolean;
  stale: boolean;
}>();
defineEmits<{
  source: [id: string];
  accept: [];
  dismiss: [];
  discuss: [];
  library: [];
}>();
const expanded = ref(false);
watch(
  () => props.proposal.id,
  () => {
    expanded.value = false;
  },
);
const longPreview = computed(() => props.proposal.markdown.length > 360);
const target = computed(() =>
  props.entries.find((entry) => entry.id === props.proposal.targetEntryId),
);
const targetChanged = computed(
  () =>
    !!props.proposal.targetEntryId &&
    target.value?.revision !== props.proposal.targetRevision,
);
const action = computed(
  () =>
    ({
      create: "新增知识",
      merge: "合并到已有知识",
      revise: "更新已有知识",
      skip: "暂不保存",
    })[props.proposal.action],
);
</script>
<template>
  <article
    class="chat-knowledge-card"
    :class="{ 'knowledge-complete': proposal.status !== 'pending' }"
    aria-label="知识保存建议"
  >
    <template v-if="proposal.status === 'pending'">
      <header>
        <BookOpen :size="16" /><span>这段内容值得留下</span
        ><small>{{ action }}</small>
      </header>
      <h3>{{ proposal.title }}</h3>
      <p v-if="proposal.reason" class="chat-knowledge-reason">
        {{ proposal.reason }}
      </p>
      <p v-if="proposal.targetEntryId" class="chat-knowledge-target">
        将更新「{{ target?.title || "已有知识" }}」
      </p>
      <div
        class="markdown-body chat-knowledge-body"
        :class="{ 'preview-collapsed': longPreview && !expanded }"
        v-html="renderMarkdown(graphBody(proposal.title, proposal.markdown))"
      />
      <button
        v-if="longPreview"
        class="chat-knowledge-expand"
        :aria-expanded="expanded"
        @click="expanded = !expanded"
      >
        {{ expanded ? "收起内容" : "展开完整内容" }}
      </button>
      <div v-if="proposal.domains.length" class="chat-knowledge-domains">
        <span v-for="domain in proposal.domains" :key="domain">{{
          domain
        }}</span>
      </div>
      <details
        v-if="proposal.targetEntryId && target"
        class="knowledge-comparison"
      >
        <summary>查看原来的内容</summary>
        <div class="markdown-body" v-html="renderMarkdown(target.markdown)" />
      </details>
      <KnowledgeReferences
        :refs="proposal.refs"
        :disabled="disabled"
        @source="$emit('source', $event)"
      />
      <details v-if="proposal.coverage.truncated" class="knowledge-coverage">
        <summary>这次整理的范围</summary>
        <KnowledgeReadScope :coverage="proposal.coverage" />
      </details>
      <p
        v-if="stale || proposal.requiresRefresh || targetChanged"
        class="chat-knowledge-outdated"
      >
        讨论或已有知识有了变化，继续聊后会重新判断值得保留的内容。
      </p>
      <footer>
        <button
          v-if="!stale && !proposal.requiresRefresh && !targetChanged"
          :disabled="
            disabled || stale || targetChanged || proposal.action === 'skip'
          "
          @click="$emit('accept')"
        >
          确认入库
        </button>
        <button
          class="secondary"
          :disabled="disabled"
          @click="$emit('discuss')"
        >
          继续聊
        </button>
        <button
          class="chat-knowledge-skip"
          :disabled="disabled"
          @click="$emit('dismiss')"
        >
          暂不保存
        </button>
      </footer>
    </template>
    <template v-else-if="proposal.status === 'accepted'">
      <Check :size="16" /><span>已保存「{{ proposal.title }}」</span
      ><button :disabled="disabled" @click="$emit('library')">
        查看知识<ArrowUpRight :size="13" />
      </button>
    </template>
    <template v-else><span>已略过这次建议</span></template>
  </article>
</template>
