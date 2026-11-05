<script setup lang="ts">
import { nextTick, onMounted, onUnmounted, ref } from "vue";
import {
  PanelLeftClose,
  PanelLeftOpen,
  SquarePen,
  History,
  MessageCircle,
  Library,
  Brain,
  LogOut,
  X,
} from "@lucide/vue";
import ConversationHistory from "./ConversationHistory.vue";
import type { ConversationChange, Problem } from "./types";

const props = defineProps<{
  problems: Problem[];
  selected: string;
  newProblem: boolean;
  activeTab: string;
  busy: boolean;
  pending: boolean;
  collapsed: boolean;
}>();
const emit = defineEmits<{
  select: [id: string];
  change: [id: string, value: ConversationChange];
  new: [];
  tool: [tab: string];
  logout: [];
  "update:collapsed": [value: boolean];
  modal: [value: boolean];
}>();
const tools = [
  { title: "讨论", icon: MessageCircle },
  { title: "资料与主题", icon: Library },
  { title: "记忆", icon: Brain },
];
const isMobile = ref(false);
const mobileOpen = ref(false);
const sidebar = ref<HTMLElement | null>(null);
let returnFocus: HTMLElement | null = null;
let priorOverflow = "";
let media: MediaQueryList;

async function openMenu(trigger: HTMLElement) {
  if (!isMobile.value) return;
  returnFocus = trigger;
  priorOverflow = document.body.style.overflow;
  mobileOpen.value = true;
  emit("modal", true);
  document.body.style.overflow = "hidden";
  await nextTick();
  sidebar.value?.querySelector<HTMLButtonElement>(".mobile-close")?.focus();
}
function closeMenu(restore = true) {
  if (!mobileOpen.value) return;
  mobileOpen.value = false;
  emit("modal", false);
  document.body.style.overflow = priorOverflow;
  if (restore) void nextTick(() => returnFocus?.focus());
}
function navigate(action: () => void) {
  closeMenu();
  action();
}
function keydown(event: KeyboardEvent) {
  if (!mobileOpen.value || sidebar.value?.querySelector("dialog[open]")) return;
  if (event.key === "Escape") {
    event.preventDefault();
    closeMenu();
  }
  if (event.key !== "Tab") return;
  const buttons = Array.from(
    sidebar.value?.querySelectorAll<HTMLElement>(
      "button:not(:disabled), a[href], [tabindex='0']",
    ) || [],
  ).filter((e) => e.getClientRects().length);
  const first = buttons[0],
    last = buttons.at(-1);
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault();
    last?.focus();
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault();
    first?.focus();
  }
}
function breakpoint() {
  const wasMobile = isMobile.value;
  const hadSidebarFocus = sidebar.value?.contains(document.activeElement);
  isMobile.value = media.matches;
  if (wasMobile && !isMobile.value) {
    closeMenu(false);
    if (hadSidebarFocus)
      void nextTick(() =>
        sidebar.value
          ?.querySelector<HTMLButtonElement>(".collapse-toggle")
          ?.focus(),
      );
  }
  // A desktop control must not retain focus inside the hidden mobile drawer.
  if (
    isMobile.value &&
    !mobileOpen.value &&
    sidebar.value?.contains(document.activeElement)
  ) {
    document.querySelector<HTMLButtonElement>(".mobile-menu-toggle")?.focus();
  }
}
onMounted(() => {
  media = window.matchMedia("(max-width: 760px)");
  breakpoint();
  media.addEventListener("change", breakpoint);
  document.addEventListener("keydown", keydown);
});
onUnmounted(() => {
  closeMenu(false);
  media?.removeEventListener("change", breakpoint);
  document.removeEventListener("keydown", keydown);
});
defineExpose({ openMenu });
</script>

<template>
  <div
    v-if="mobileOpen"
    class="sidebar-backdrop"
    aria-hidden="true"
    @click="closeMenu()"
  />
  <aside
    ref="sidebar"
    id="app-sidebar"
    class="sidebar"
    :class="{ collapsed: collapsed && !isMobile, 'mobile-open': mobileOpen }"
    :role="mobileOpen ? 'dialog' : undefined"
    :aria-modal="mobileOpen ? true : undefined"
    aria-label="对话与工具"
    :inert="isMobile && !mobileOpen"
  >
    <div class="sidebar-head">
      <div class="sidebar-brand">
        <span class="brand-mark" aria-hidden="true">知</span
        ><span class="sidebar-wordmark">知衡</span>
      </div>
      <button
        class="icon-button collapse-toggle"
        :title="collapsed ? '展开侧栏' : '收起侧栏'"
        :aria-label="collapsed ? '展开侧栏' : '收起侧栏'"
        :aria-expanded="!collapsed"
        @click="emit('update:collapsed', !collapsed)"
      >
        <PanelLeftOpen v-if="collapsed" :size="19" /><PanelLeftClose
          v-else
          :size="19"
        />
      </button>
      <button
        class="icon-button mobile-close"
        aria-label="关闭侧栏"
        title="关闭侧栏"
        @click="closeMenu()"
      >
        <X :size="20" />
      </button>
    </div>
    <button
      class="new-chat-button"
      aria-label="新对话"
      title="新对话"
      :disabled="busy || pending"
      @click="navigate(() => emit('new'))"
    >
      <SquarePen :size="18" /><span class="sidebar-label">新对话</span>
    </button>
    <button
      v-if="collapsed && !isMobile"
      class="icon-button rail-history"
      title="展开历史对话"
      aria-label="展开历史对话"
      @click="emit('update:collapsed', false)"
    >
      <History :size="19" />
    </button>
    <ConversationHistory
      :problems="problems"
      :selected="newProblem ? '' : selected"
      :disabled="busy || pending"
      @select="(id) => navigate(() => emit('select', id))"
      @change="(id, value) => navigate(() => emit('change', id, value))"
    />
    <div class="sidebar-bottom">
      <nav class="tool-rail" aria-label="工作台工具">
        <span
          v-for="tool in tools"
          :key="tool.title"
          class="tooltip"
          :data-tooltip="tool.title"
        >
          <button
            class="icon-button"
            :aria-label="tool.title"
            :title="tool.title"
            :aria-pressed="
              (tool.title !== '讨论' || !newProblem) && activeTab === tool.title
            "
            :class="{
              active:
                (tool.title !== '讨论' || !newProblem) &&
                activeTab === tool.title,
            }"
            :disabled="busy"
            @click="navigate(() => emit('tool', tool.title))"
          >
            <component :is="tool.icon" :size="18" />
          </button>
        </span>
      </nav>
      <div class="sidebar-account">
        <span class="space-label">个人空间</span>
        <button
          class="icon-button"
          title="退出登录"
          aria-label="退出登录"
          @click="emit('logout')"
        >
          <LogOut :size="18" />
        </button>
      </div>
    </div>
  </aside>
</template>
