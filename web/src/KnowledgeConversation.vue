<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from "vue";
import { LoaderCircle } from "@lucide/vue";
import type { Message } from "./types";
import { useKnowledge, knowledgeActions } from "./knowledge";
import KnowledgeProposalCard from "./KnowledgeProposalCard.vue";
import "./chatKnowledge.css";

const props = defineProps<{
  problemId: string;
  disabled: boolean;
  message: Message | undefined;
}>();
const emit = defineEmits<{
  source: [id: string];
  discuss: [];
  library: [];
  beforeReveal: [];
}>();
const flow = useKnowledge();
const suggestionJobId = computed(() =>
  props.message?.role === "assistant"
    ? props.message.knowledgeSuggestion?.jobId || ""
    : "",
);
const manual = ref(false);
const showingHistory = ref(false);
const history = computed(() =>
  flow.proposals.value.filter((item) => item.action !== "skip"),
);
const latest = computed(() =>
  manual.value ? flow.proposals.value[0] : flow.suggestion.value,
);
watch(
  () => latest.value?.id,
  (id, old) => {
    if (id && id !== old) emit("beforeReveal");
  },
);
const unavailable = computed(
  () =>
    props.disabled ||
    flow.loading.value ||
    flow.working.value ||
    flow.uncertain.value,
);
let selection = 0;
watch(
  () => [props.problemId, props.message] as const,
  async ([problemId], prior) => {
    const request = ++selection;
    manual.value = false;
    showingHistory.value = false;
    // Cancel the old draft immediately when discussion moves on.
    void flow.followSuggestion("");
    if (!prior || problemId !== prior[0]) await flow.open(props.problemId);
    else await flow.refresh();
    if (request !== selection) return;
    void flow.followSuggestion(suggestionJobId.value);
  },
  { immediate: true },
);
async function prepare() {
  manual.value = true;
  await flow.prepare();
}
function discuss() {
  emit("discuss");
}
onUnmounted(() => {
  selection++;
  flow.stop();
});
</script>
<template>
  <section
    v-if="
      latest ||
      flow.suggestionLoading.value ||
      flow.suggestionError.value ||
      flow.error.value ||
      flow.uncertain.value ||
      history.length
    "
    class="chat-knowledge"
    aria-label="聊天保存建议"
  >
    <p
      v-if="flow.suggestionLoading.value"
      class="chat-knowledge-loading"
      role="status"
    >
      <LoaderCircle
        :size="13"
        class="spinning"
      />正在把值得留下的内容整理成建议…
    </p>
    <p v-if="flow.error.value" class="error" role="alert">
      {{ flow.error.value }}
    </p>
    <p
      v-if="flow.suggestionError.value"
      class="chat-knowledge-status"
      role="status"
    >
      {{ flow.suggestionError.value }}
    </p>
    <button
      v-if="flow.uncertain.value"
      class="secondary"
      :disabled="disabled || flow.working.value || flow.loading.value"
      @click="flow.resume()"
    >
      重新连接上次保存操作
    </button>
    <button
      v-if="flow.suggestionError.value && suggestionJobId"
      class="chat-knowledge-reconnect"
      :disabled="disabled || flow.suggestionLoading.value"
      @click="flow.followSuggestion(suggestionJobId)"
    >
      重新查看建议
    </button>
    <KnowledgeProposalCard
      v-if="latest && latest.action !== 'skip'"
      :proposal="latest"
      :entries="flow.entries.value"
      :disabled="unavailable"
      :stale="flow.stale.value"
      @accept="flow.accept(latest)"
      @dismiss="flow.dismiss(latest)"
      @source="emit('source', $event)"
      @discuss="discuss"
      @library="emit('library')"
    />
    <details
      v-if="
        flow.stale.value ||
        latest?.requiresRefresh ||
        flow.suggestionError.value ||
        history.length
      "
      :open="showingHistory"
      @toggle="showingHistory = ($event.target as HTMLDetailsElement).open"
      class="chat-knowledge-recovery"
    >
      <summary>保存建议与记录</summary>
      <p v-if="flow.stale.value" class="muted">可以根据现在的讨论重新整理。</p>
      <button class="secondary" :disabled="unavailable" @click="prepare">
        {{ flow.working.value ? "正在整理…" : "重新整理建议" }}
      </button>
      <ul v-if="history.length">
        <li v-for="proposal in history" :key="proposal.id">
          <span>{{ proposal.title || "整理建议" }}</span
          ><small>{{
            proposal.status === "accepted"
              ? "已保存"
              : proposal.status === "dismissed"
                ? "已略过"
                : knowledgeActions[proposal.action] + "建议"
          }}</small>
        </li>
      </ul>
    </details>
  </section>
</template>
