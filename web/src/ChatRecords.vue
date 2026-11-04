<script setup lang="ts">
import { computed } from "vue";
import type { Decision, Memory, Message } from "./types";
const props = defineProps<{
  message: Message;
  memories: Memory[];
  decisions: Decision[];
}>();
const changes = computed(() => props.message.memoryChanges || []);
const currentMemory = (id: string) =>
  props.memories.find((memory) => memory.id === id);
const decision = computed(() =>
  props.decisions.find((item) => item.id === props.message.decisionRecord?.id),
);
const action = { ADD: "记住", UPDATE: "更新", DELETE: "忘记" };
</script>

<template>
  <details v-if="changes.length || message.decisionRecord" class="chat-records">
    <summary>
      <span v-if="changes.length">记忆 · {{ changes.length }} 项变化</span
      ><span v-if="changes.length && message.decisionRecord"> · </span
      ><span v-if="message.decisionRecord">决定已记录</span>
    </summary>
    <p class="muted">这次回复时的记录；下方同时显示最新状态。</p>
    <div v-for="(change, i) in changes" :key="i" class="record-item">
      <small
        >当时已{{ action[change.action] }} ·
        {{ change.scope === "global" ? "跨对话" : "本次对话" }}</small
      >
      <p>{{ change.content }}</p>
      <p v-if="!currentMemory(change.memoryId)" class="muted">
        此记录现已不可用。
      </p>
      <p
        v-else-if="currentMemory(change.memoryId)?.status !== 'active'"
        class="muted"
      >
        目前已忘记，不再使用。
      </p>
      <p
        v-else-if="currentMemory(change.memoryId)?.revision !== change.revision"
        class="muted"
      >
        现已更新为：{{ currentMemory(change.memoryId)?.content }}
      </p>
      <blockquote v-if="currentMemory(change.memoryId)?.sourceQuote">
        你的原话：{{ currentMemory(change.memoryId)?.sourceQuote }}
      </blockquote>
    </div>
    <div v-if="message.decisionRecord" class="record-item">
      <p>{{ message.decisionRecord.choice }}</p>
      <p v-if="!decision" class="muted">此记录现已不可用。</p>
      <p v-else-if="decision.status === 'superseded'" class="muted">
        后来已改为其他选择。
      </p>
      <blockquote v-if="message.decisionRecord.quote">
        你的原话：{{ message.decisionRecord.quote }}
      </blockquote>
      <p v-if="message.decisionRecord.rationale">
        当时的理由：{{ message.decisionRecord.rationale }}
      </p>
      <p v-if="message.decisionRecord.expectedOutcome">
        期待：{{ message.decisionRecord.expectedOutcome }}
      </p>
    </div>
  </details>
</template>
