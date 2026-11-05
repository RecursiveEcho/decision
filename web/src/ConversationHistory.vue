<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from "vue";
import {
  Archive,
  ArchiveRestore,
  ArrowLeft,
  Ellipsis,
  Pencil,
  Trash2,
} from "@lucide/vue";
import { sessionId } from "./api";
import type { ConversationChange, Problem } from "./types";

const props = defineProps<{
  problems: Problem[];
  selected: string;
  disabled: boolean;
}>();
const emit = defineEmits<{
  select: [id: string];
  change: [id: string, value: ConversationChange];
}>();
const archived = ref(history.state?.archivedView === sessionId());
const rows = computed(() =>
  props.problems.filter((p) => (p.status === "archived") === archived.value),
);
const menu = ref("");
const root = ref<HTMLElement>();
const filter = ref<HTMLButtonElement>();
const dialog = ref<HTMLDialogElement>();
const titleInput = ref<HTMLInputElement>();
const action = ref<"rename" | "delete">("rename");
const target = ref<Problem>();
const title = ref("");

function toggleView() {
  archived.value = !archived.value;
  history.replaceState(
    { ...history.state, archivedView: archived.value ? sessionId() : null },
    "",
  );
  menu.value = "";
}
function trigger(id: string) {
  return document.getElementById("actions-" + id);
}
function toggleMenu(id: string) {
  menu.value = menu.value === id ? "" : id;
}
watch(menu, async (id, old) => {
  document.getElementById("menu-" + old)?.hidePopover();
  if (!id) return;
  await nextTick();
  const anchor = trigger(id)?.getBoundingClientRect();
  const popup = document.getElementById("menu-" + id);
  if (!anchor || !popup) return;
  popup.showPopover();
  const height = popup.getBoundingClientRect().height;
  popup.style.left =
    Math.max(8, Math.min(anchor.right - 216, window.innerWidth - 224)) + "px";
  popup.style.top =
    Math.max(
      8,
      anchor.bottom + height + 8 <= window.innerHeight
        ? anchor.bottom + 6
        : anchor.top - height - 6,
    ) + "px";
  popup.querySelector<HTMLButtonElement>("button")?.focus();
});
function closeOnResize() {
  menu.value = "";
}
function closeOutside(event: Event) {
  if (
    menu.value &&
    !(event.target as Element)?.closest?.(".history-row.is-open")
  )
    menu.value = "";
}
function onKey(event: KeyboardEvent) {
  if (!menu.value || dialog.value?.open) return;
  if (event.key === "Escape") {
    event.preventDefault();
    event.stopPropagation();
    trigger(menu.value)?.focus();
    menu.value = "";
  } else if (["ArrowDown", "ArrowUp", "Home", "End"].includes(event.key)) {
    const items = Array.from(
      document
        .getElementById("menu-" + menu.value)
        ?.querySelectorAll<HTMLButtonElement>("button") || [],
    );
    if (!items.length) return;
    event.preventDefault();
    const index = items.indexOf(document.activeElement as HTMLButtonElement);
    const destination =
      event.key === "Home"
        ? 0
        : event.key === "End"
          ? items.length - 1
          : (index + (event.key === "ArrowDown" ? 1 : -1) + items.length) %
            items.length;
    items[destination]?.focus();
  }
}
async function edit(item: Problem, mode: "rename" | "delete") {
  action.value = mode;
  target.value = item;
  title.value = item.title;
  menu.value = "";
  await nextTick();
  dialog.value?.showModal();
  if (mode === "rename") {
    titleInput.value?.focus();
    titleInput.value?.select();
  }
}
function focusAfterDialog() {
  if (target.value) (trigger(target.value.id) || filter.value)?.focus();
}
function submit() {
  if (!target.value || props.disabled) return;
  const item = target.value;
  const change =
    action.value === "delete" ? "delete" : { title: title.value.trim() };
  if (change !== "delete" && !change.title) return;
  dialog.value?.close();
  if (change === "delete" || change.title !== item.title)
    emit("change", item.id, change);
}
function move(item: Problem) {
  menu.value = "";
  filter.value?.focus();
  emit("change", item.id, { status: archived.value ? "active" : "archived" });
}
onMounted(() => {
  window.addEventListener("resize", closeOnResize);
  document.addEventListener("pointerdown", closeOutside);
  document.addEventListener("focusin", closeOutside);
});
onUnmounted(() => {
  window.removeEventListener("resize", closeOnResize);
  document.removeEventListener("pointerdown", closeOutside);
  document.removeEventListener("focusin", closeOutside);
});
</script>
<template>
  <section
    ref="root"
    class="history-section"
    aria-label="历史对话"
    @keydown="onKey"
    @scroll="menu = ''"
  >
    <div class="history-heading">
      <h2>{{ archived ? "已归档对话" : "最近对话" }}</h2>
      <button
        ref="filter"
        class="history-filter"
        :aria-label="archived ? '返回最近对话' : '查看已归档对话'"
        @click="toggleView"
      >
        <component :is="archived ? ArrowLeft : Archive" :size="14" />{{
          archived ? "返回" : "归档"
        }}
      </button>
    </div>
    <p v-if="!rows.length" class="history-empty">
      {{ archived ? "暂无归档对话" : "对话会保存在这里" }}
    </p>
    <ul v-else class="history-list">
      <li
        v-for="item in rows"
        :key="item.id"
        class="history-row"
        :class="{ 'is-open': menu === item.id }"
      >
        <button
          class="history-title"
          :class="{ active: selected === item.id }"
          :aria-current="selected === item.id ? 'page' : undefined"
          :title="item.title"
          :disabled="disabled"
          @click="emit('select', item.id)"
        >
          <span>{{ item.title }}</span>
        </button>
        <button
          :id="'actions-' + item.id"
          class="history-more"
          :aria-label="item.title + '的操作'"
          :aria-expanded="menu === item.id"
          aria-haspopup="true"
          :aria-controls="'menu-' + item.id"
          :disabled="disabled"
          @click="toggleMenu(item.id)"
        >
          <Ellipsis :size="17" />
        </button>
        <div
          :id="'menu-' + item.id"
          popover="manual"
          class="row-actions"
          role="group"
          :aria-label="item.title + '的操作'"
        >
          <button :disabled="disabled" @click="edit(item, 'rename')">
            <Pencil :size="17" />重命名
          </button>
          <button :disabled="disabled" @click="move(item)">
            <component :is="archived ? ArchiveRestore : Archive" :size="17" />{{
              archived ? "恢复" : "归档"
            }}
          </button>
          <button
            class="destructive"
            :disabled="disabled"
            @click="edit(item, 'delete')"
          >
            <Trash2 :size="17" />删除
          </button>
        </div>
      </li>
    </ul>
    <dialog
      ref="dialog"
      class="history-dialog"
      aria-labelledby="history-dialog-title"
      @close="focusAfterDialog"
    >
      <form @submit.prevent="submit">
        <h2 id="history-dialog-title">
          {{ action === "rename" ? "更改对话名称" : "删除对话" }}
        </h2>
        <label v-if="action === 'rename'"
          >对话名称<input
            ref="titleInput"
            v-model="title"
            maxlength="300"
            required
        /></label>
        <p v-else>
          确定删除「{{ target?.title }}」及其聊天记录？此操作无法撤销。
        </p>
        <div class="history-dialog-buttons">
          <button
            type="button"
            class="secondary"
            autofocus
            @click="dialog?.close()"
          >
            取消
          </button>
          <button
            :disabled="disabled || (action === 'rename' && !title.trim())"
            :class="{ danger: action === 'delete' }"
          >
            {{ action === "rename" ? "保存" : "确认删除" }}
          </button>
        </div>
      </form>
    </dialog>
  </section>
</template>
