<script setup lang="ts">
import { nextTick, ref } from "vue";
import { Search, X } from "@lucide/vue";
import { request, sessionId } from "./api";
import type { Problem } from "./types";
const emit = defineEmits<{ select: [id: string, messageId: string | null] }>();
const dialog = ref<HTMLDialogElement>(),
  input = ref<HTMLInputElement>();
const query = ref(""),
  loading = ref(false),
  error = ref(""),
  cursor = ref<string | null>(null);
const rows = ref<
  {
    problem: Problem;
    messageId: string | null;
    snippet: string;
    createdAt: string;
  }[]
>([]);
let sequence = 0,
  trigger: HTMLElement | undefined;
async function open(button?: HTMLElement) {
  trigger = button;
  dialog.value?.showModal();
  await nextTick();
  input.value?.focus();
}
async function search(more = false) {
  const q = query.value.trim();
  if (!q) {
    rows.value = [];
    return;
  }
  const current = ++sequence,
    owner = sessionId();
  loading.value = true;
  error.value = "";
  if (!more) rows.value = [];
  try {
    const result = await request<{
      items: typeof rows.value;
      nextCursor: string | null;
    }>(
      `/conversations/search?q=${encodeURIComponent(q)}&limit=30${more && cursor.value ? "&cursor=" + encodeURIComponent(cursor.value) : ""}`,
    );
    if (current !== sequence || owner !== sessionId()) return;
    rows.value = more ? [...rows.value, ...result.items] : result.items;
    cursor.value = result.nextCursor;
  } catch (e) {
    if (current === sequence)
      error.value = e instanceof Error ? e.message : "搜索失败";
  } finally {
    if (current === sequence) loading.value = false;
  }
}
function choose(id: string, messageId: string | null) {
  dialog.value?.close();
  emit("select", id, messageId);
}
defineExpose({ open });
</script>
<template>
  <dialog
    ref="dialog"
    class="conversation-search"
    aria-labelledby="search-title"
    @close="
      () => {
        sequence++;
        loading = false;
        trigger?.focus();
      }
    "
  >
    <header>
      <h2 id="search-title">搜索对话</h2>
      <button
        class="icon-button"
        aria-label="关闭搜索"
        @click="dialog?.close()"
      >
        <X :size="19" />
      </button>
    </header>
    <form @submit.prevent="search()" class="search-form">
      <Search :size="19" /><input
        ref="input"
        v-model="query"
        maxlength="200"
        placeholder="搜索标题和消息正文…"
        aria-label="搜索标题和消息正文"
      /><button :disabled="loading || !query.trim()">搜索</button>
    </form>
    <p class="muted">包含最近和已归档对话，结果仅属于当前账号。</p>
    <p v-if="error" role="alert" class="error">{{ error }}</p>
    <p v-if="loading" role="status">正在搜索…</p>
    <div class="search-results">
      <button
        v-for="(row, i) in rows"
        :key="row.problem.id + ':' + row.messageId + ':' + i"
        @click="choose(row.problem.id, row.messageId)"
      >
        <strong
          >{{ row.problem.title
          }}<small v-if="row.problem.status === 'archived'">
            已归档</small
          ></strong
        ><span>{{ row.snippet }}</span
        ><time>{{ new Date(row.createdAt).toLocaleDateString("zh-CN") }}</time>
      </button>
    </div>
    <button v-if="cursor && !loading" class="secondary" @click="search(true)">
      更多结果
    </button>
    <p v-if="query && !loading && !error && !rows.length" class="muted">
      输入关键词并搜索，匹配结果会显示在这里。
    </p>
  </dialog>
</template>
