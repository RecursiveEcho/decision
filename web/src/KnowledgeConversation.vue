<script setup lang="ts">
import { computed, onUnmounted, watch } from "vue";
import { BookPlus, LoaderCircle } from "@lucide/vue";
import { useKnowledge, knowledgeActions } from "./knowledge";
import { renderMarkdown } from "./markdown";
import KnowledgeReferences from "./KnowledgeReferences.vue";
const props = defineProps<{ problemId: string; disabled: boolean }>();
defineEmits<{ source: [id: string]; discuss: []; library: [] }>();
const flow = useKnowledge();
const latest = computed(() => flow.proposals.value[0]);
const target = computed(() =>
  flow.entries.value.find((e) => e.id === latest.value?.targetEntryId),
);
const unavailable = computed(
  () =>
    props.disabled ||
    flow.loading.value ||
    flow.working.value ||
    flow.uncertain.value,
);
const related = (id: string) =>
  flow.entries.value.find((e) => e.id === id)?.title || "关联知识";
watch(
  () => props.problemId,
  (id) => {
    void flow.open(id);
  },
  { immediate: true },
);
onUnmounted(flow.stop);
</script>

<template>
  <section class="knowledge-conversation" aria-label="从对话整理知识">
    <div class="knowledge-toolbar">
      <button
        type="button"
        class="secondary knowledge-prepare"
        :disabled="unavailable"
        @click="flow.prepare()"
      >
        <LoaderCircle
          v-if="flow.working.value"
          :size="16"
          class="spinning"
        /><BookPlus v-else :size="16" />
        {{
          flow.working.value
            ? "正在整理…"
            : latest
              ? "重新整理建议"
              : "整理入库"
        }}
      </button>
      <span class="muted">先审阅建议，确认后才保存。</span>
    </div>
    <p
      v-if="flow.loading.value && !flow.working.value"
      class="muted"
      role="status"
    >
      正在读取整理记录…
    </p>
    <p v-if="flow.note.value" class="knowledge-note muted" role="status">
      {{ flow.note.value }}
    </p>
    <p v-if="flow.error.value" class="error" role="alert">
      {{ flow.error.value }}
    </p>
    <button
      v-if="flow.uncertain.value"
      type="button"
      class="secondary"
      :disabled="props.disabled || flow.working.value || flow.loading.value"
      @click="flow.resume()"
    >
      重新连接上次操作
    </button>
    <button
      v-else-if="flow.error.value && !latest && !flow.stale.value"
      type="button"
      class="secondary"
      :disabled="unavailable"
      @click="flow.open(problemId)"
    >
      刷新整理记录
    </button>
    <article
      v-if="latest"
      class="knowledge-proposal"
      :class="{ 'knowledge-proposal-complete': latest.status !== 'pending' }"
    >
      <div class="knowledge-proposal-heading">
        <span class="knowledge-action">{{
          knowledgeActions[latest.action]
        }}</span>
        <span class="muted">{{
          latest.status === "pending"
            ? "待你审阅"
            : latest.status === "accepted"
              ? "已确认入库"
              : "已略过"
        }}</span>
      </div>
      <h3>{{ latest.title || "这次整理建议" }}</h3>
      <p>{{ latest.reason }}</p>
      <template v-if="latest.status === 'pending'">
        <div v-if="latest.domains.length" class="knowledge-tags">
          <span v-for="domain in latest.domains" :key="domain">{{
            domain
          }}</span>
        </div>
        <details v-if="latest.targetEntryId" class="knowledge-comparison">
          <summary>
            对照已有知识：{{ target?.title || "目标条目" }} · 第
            {{ latest.targetRevision }} 版
          </summary>
          <p v-if="!target" class="muted">
            已有条目暂时无法读取，请重新整理后再确认。
          </p>
          <template v-else>
            <p v-if="target.revision !== latest.targetRevision" class="error">
              已有条目已更新为第 {{ target.revision }} 版，需要重新整理。
            </p>
            <div
              class="markdown-body"
              v-html="renderMarkdown(target.markdown)"
            />
          </template>
        </details>
        <div v-if="latest.markdown" class="knowledge-preview">
          <small>拟保存的完整正文</small>
          <div class="markdown-body" v-html="renderMarkdown(latest.markdown)" />
        </div>
        <p v-if="latest.relatedEntryIds.length" class="muted">
          关联：{{ latest.relatedEntryIds.map(related).join("、") }}
        </p>
        <KnowledgeReferences
          :refs="latest.refs"
          :disabled="props.disabled"
          @source="$emit('source', $event)"
        />
        <details class="knowledge-coverage">
          <summary>
            {{
              latest.coverage.truncated
                ? "本次仅整理了部分内容"
                : "本次整理范围"
            }}
          </summary>
          <p class="muted">
            对话 {{ latest.coverage.messagesConsidered }}/{{
              latest.coverage.messagesTotal
            }}
            条 · 来源 {{ latest.coverage.sourcesConsidered }}/{{
              latest.coverage.sourcesTotal
            }}
            份 · 对照已确认知识 {{ latest.coverage.knowledgeConsidered }}/{{
              latest.coverage.knowledgeTotal
            }}
            条。
          </p>
          <p class="muted">
            {{
              latest.coverage.notice ||
              "仅使用对话文字与正式来源，未直接读取附件原件；对照范围有限，不代表完整冲突检测。"
            }}
          </p>
        </details>
        <div class="knowledge-buttons">
          <button
            v-if="latest.action !== 'skip'"
            type="button"
            :disabled="
              unavailable ||
              flow.stale.value ||
              (!!target && target.revision !== latest.targetRevision)
            "
            @click="flow.accept(latest)"
          >
            确认{{ knowledgeActions[latest.action] }}入库
          </button>
          <button
            type="button"
            class="secondary"
            :disabled="unavailable"
            @click="$emit('discuss')"
          >
            继续聊一聊
          </button>
          <button
            type="button"
            class="secondary"
            :disabled="unavailable"
            @click="flow.dismiss(latest)"
          >
            {{ latest.action === "skip" ? "知道了，暂不保存" : "这次不保存" }}
          </button>
        </div>
      </template>
      <button
        v-else-if="latest.status === 'accepted'"
        type="button"
        class="secondary"
        :disabled="props.disabled"
        @click="$emit('library')"
      >
        查看已确认知识
      </button>
    </article>
    <details v-if="flow.proposals.value.length > 1" class="knowledge-previous">
      <summary>之前的整理 · {{ flow.proposals.value.length - 1 }}</summary>
      <div
        v-for="proposal in flow.proposals.value.slice(1)"
        :key="proposal.id"
        class="knowledge-previous-item"
      >
        <span
          >{{ knowledgeActions[proposal.action] }} ·
          {{ proposal.title || "整理建议" }}</span
        >
        <small>{{
          proposal.status === "accepted"
            ? "已入库"
            : proposal.status === "dismissed"
              ? "已略过"
              : "旧建议，请以最新讨论重新整理"
        }}</small>
      </div>
    </details>
  </section>
</template>
