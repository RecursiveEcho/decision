<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from "vue";
import {
  ApiError,
  list,
  logout,
  request,
  sessionId,
  setSession,
  token,
  waitJob,
} from "./api";
import AppSidebar from "./AppSidebar.vue";
import EvidencePanel from "./EvidencePanel.vue";
import AttachmentList from "./AttachmentList.vue";
import ChatRecords from "./ChatRecords.vue";
import MemoryPage from "./MemoryPage.vue";
import KnowledgeConversation from "./KnowledgeConversation.vue";
import LibraryPage from "./LibraryPage.vue";
import {
  readKnowledgeOperations,
  saveKnowledgeOperations,
} from "./knowledgeStorage";
import { useMemoryOverview } from "./memoryOverview";
import {
  ArrowUp,
  Menu,
  RefreshCw,
  LoaderCircle,
  Paperclip,
  ChevronDown,
  Check,
} from "@lucide/vue";
import { FILE_ACCEPT } from "./attachments";
import { renderMarkdown } from "./markdown";
import { useChatFlow } from "./chatFlow";
import type {
  Evidence,
  Decision,
  ConversationChange,
  Job,
  Memory,
  Message,
  Problem,
  Source,
  Topic,
  Thinking,
} from "./types";
const logged = ref(!!token()),
  accessCode = ref(""),
  busy = ref(false),
  error = ref(""),
  note = ref(""),
  mock = ref(false);
const sidebar = ref<InstanceType<typeof AppSidebar> | null>(null);
const libraryPage = ref<InstanceType<typeof LibraryPage> | null>(null);
const sidebarCollapsed = ref(false);
const sidebarModal = ref(false);
const composing = ref(false);
const mainPane = ref<HTMLElement | null>(null);
const fileInput = ref<HTMLInputElement | null>(null);
const thinkingMenu = ref<HTMLElement | null>(null);
const thinkingOpen = ref(false);
const draggingFiles = ref(false);
const thinkingOptions: {
  value: Thinking;
  label: string;
  description: string;
}[] = [
  { value: "off", label: "快速", description: "直接回答" },
  { value: "low", label: "轻度", description: "稍作思考" },
  { value: "high", label: "深度", description: "深入推理" },
  { value: "max", label: "最高", description: "更充分地推敲" },
];
const thinkingLabel = (value: Thinking) =>
  thinkingOptions.find((option) => option.value === value)?.label || "快速";
let scrollInteraction = 0;
const tab = ref("讨论"),
  problems = ref<Problem[]>([]),
  selected = ref("");
const problem = computed(() =>
  newProblem.value
    ? undefined
    : problems.value.find((p) => p.id === selected.value),
);
const knowledgeProblemId = computed(() => problem.value?.id || "");
function storeProblem(id: string, updated: Problem | null) {
  const index = problems.value.findIndex((item) => item.id === id);
  if (index >= 0)
    problems.value.splice(index, 1, ...(updated ? [updated] : []));
  else if (updated) problems.value.unshift(updated);
}
async function readProblem(id: string) {
  try {
    return await request<Problem>(`/problems/${encodeURIComponent(id)}`);
  } catch (failure) {
    if (failure instanceof ApiError && failure.status === 404) return null;
    throw failure;
  }
}
async function openConversation(id: string) {
  if (!id || busy.value || pendingChat.value) return;
  const interaction = scrollInteraction;
  await act(async () => {
    const item = await readProblem(id);
    if (!item) {
      storeProblem(id, null);
      const inLibrary = tab.value === "资料与主题";
      if (selected.value === id) {
        selected.value = "";
        newProblem.value = true;
        await nextTick();
        await load();
      }
      discardConversationState(id);
      throw new Error(
        inLibrary ? "原对话已删除，仍可在这里查看资料。" : "这段对话已删除。",
      );
    }
    storeProblem(id, item);
    displayedProblem.value = id;
    await nextTick();
    await load();
    await nextTick();
    if (
      messages.value.at(-1)?.knowledgeSuggestion?.jobId &&
      interaction === scrollInteraction &&
      mainPane.value
    )
      mainPane.value.scrollTop = mainPane.value.scrollHeight;
  });
}
const messages = ref<Message[]>([]),
  sources = ref<Source[]>([]),
  memories = ref<Memory[]>([]),
  decisions = ref<Decision[]>([]),
  topics = ref<Topic[]>([]);
const sourceDetail = ref<Source | null>(null),
  newProblem = ref(true),
  sourceTitle = ref(""),
  sourceText = ref(""),
  sourceUrl = ref(""),
  chosenSources = ref<string[]>([]);
const sourceQuotes = ref<Evidence[]>([]);
const memoryOverview = useMemoryOverview();
const composer = ref<HTMLFormElement | null>(null);
const composerInput = ref<HTMLTextAreaElement | null>(null);
const errorBanner = ref<HTMLDivElement | null>(null);
const chat = useChatFlow({
  accepted(id, created) {
    if (created)
      problems.value = [created, ...problems.value.filter((p) => p.id !== id)];
    selected.value = id;
    newProblem.value = false;
    tab.value = "讨论";
  },
  async refresh() {
    const pane = mainPane.value;
    const startingInteraction = scrollInteraction;
    const follow =
      !pane || pane.scrollHeight - pane.scrollTop - pane.clientHeight < 220;
    await refresh();
    await nextTick();
    if (follow && mainPane.value && scrollInteraction === startingInteraction)
      mainPane.value.scrollTop = mainPane.value.scrollHeight;
  },
});
const pendingChat = chat.pending;
const draftTarget = computed(() =>
  newProblem.value ? "new" : selected.value || "new",
);
const draftFiles = computed(() => chat.files.value[draftTarget.value] || []);
const selectedThinking = computed(
  () =>
    pendingChat.value?.thinking ||
    chat.thinking.value[draftTarget.value] ||
    "off",
);
async function addAttachments(incoming: File[]) {
  if (!incoming.length || busy.value || pendingChat.value) return;
  const target = draftTarget.value;
  await act(() => chat.addFiles(target, incoming));
}
function fileChange(event: Event) {
  const input = event.target as HTMLInputElement;
  void addAttachments(Array.from(input.files || []));
  input.value = "";
}
function pasteAttachments(event: ClipboardEvent) {
  const files = Array.from(event.clipboardData?.files || []);
  if (!files.length) return;
  event.preventDefault();
  if (busy.value || pendingChat.value) return;
  const text = event.clipboardData?.getData("text/plain");
  if (text) chatDraft.value += text;
  void addAttachments(files);
}
function dragAttachments(event: DragEvent) {
  if (!event.dataTransfer?.types.includes("Files")) return;
  event.preventDefault();
  draggingFiles.value = !busy.value && !pendingChat.value;
}
function dropAttachments(event: DragEvent) {
  if (!event.dataTransfer?.files.length) return;
  event.preventDefault();
  draggingFiles.value = false;
  void addAttachments(Array.from(event.dataTransfer.files));
}
function chooseThinking(value: Thinking) {
  try {
    chat.setThinking(draftTarget.value, value);
    thinkingOpen.value = false;
  } catch (failure) {
    error.value =
      failure instanceof Error ? failure.message : "未能保存思考档位。";
  }
}
function closeThinking(event: Event) {
  if (!thinkingMenu.value?.contains(event.target as Node))
    thinkingOpen.value = false;
}
function cleanupFailure(event: Event) {
  error.value = (event as CustomEvent<string>).detail;
}
const chatDraft = computed({
  get: () =>
    chat.drafts.value[newProblem.value ? "new" : selected.value || "new"] || "",
  set(value: string) {
    try {
      chat.setDraft(newProblem.value ? "new" : selected.value || "new", value);
    } catch (e) {
      error.value = e instanceof Error ? e.message : "无法暂存这段话。";
    }
  },
});
const showPendingSentence = computed(
  () =>
    pendingChat.value &&
    (newProblem.value || pendingChat.value.problemId === selected.value) &&
    (newProblem.value ||
      !messages.value
        .slice(-1)
        .some(
          (m) => m.role === "user" && m.content === pendingChat.value!.content,
        )),
);
const pendingAction = computed(() =>
  pendingChat.value?.operation
    ? pendingChat.value.operation.path.endsWith("/retry")
      ? "重试回答"
      : "继续发送"
    : pendingChat.value?.job?.status === "failed"
      ? "重试回答"
      : "继续获取回答",
);
const editingGoal = ref(false);
const goalDraft = ref("");
function openGoalEditor() {
  if (busy.value || !problem.value) return;
  goalDraft.value = problem.value.goal;
  editingGoal.value = true;
}
async function saveGoal() {
  if (!problem.value || !goalDraft.value.trim()) return;
  const id = problem.value.id;
  await act(async () => {
    const updated = await request<Problem>(`/problems/${id}`, "PATCH", {
      goal: goalDraft.value.trim(),
    });
    problems.value = problems.value.map((p) => (p.id === id ? updated : p));
    editingGoal.value = false;
    note.value = "问题目标已更新。";
  });
}
const displayedProblem = computed({
  get: () => (newProblem.value ? "" : selected.value),
  set: (id: string) => {
    newProblem.value = false;
    selected.value = id;
    tab.value = "讨论";
    error.value = "";
    note.value = "";
  },
});
const emptyConversation = computed(
  () =>
    tab.value === "讨论" &&
    (newProblem.value || !messages.value.length) &&
    !showPendingSentence.value &&
    !pendingChat.value,
);
function resizeComposer() {
  const input = composerInput.value;
  if (!input) return;
  input.style.height = "auto";
  input.style.height = `${Math.min(input.scrollHeight, 200)}px`;
}
function composerKeydown(event: KeyboardEvent) {
  if (
    event.key !== "Enter" ||
    event.shiftKey ||
    event.isComposing ||
    composing.value ||
    event.keyCode === 229
  )
    return;
  event.preventDefault();
  if (
    !busy.value &&
    !pendingChat.value &&
    (chatDraft.value.trim() || draftFiles.value.length)
  )
    void send();
}
watch(chatDraft, () => void nextTick(resizeComposer));
watch(composerInput, () => void nextTick(resizeComposer));
async function openNewProblem() {
  if (busy.value || pendingChat.value) return;
  await enterNewConversation();
}
async function enterNewConversation() {
  selected.value = "";
  newProblem.value = true;
  tab.value = "讨论";
  editingGoal.value = false;
  sourceDetail.value = null;
  error.value = "";
  note.value = "";
  await nextTick();
  composer.value?.scrollIntoView({ block: "nearest", behavior: "auto" });
  composerInput.value?.focus({ preventScroll: true });
}
async function editOriginal() {
  chat.editOriginal();
  await nextTick();
  composerInput.value?.focus();
}
async function revealKnowledgeSuggestion() {
  const pane = mainPane.value;
  const interaction = scrollInteraction;
  const follow =
    pane && pane.scrollHeight - pane.scrollTop - pane.clientHeight < 220;
  await nextTick();
  if (follow && mainPane.value && interaction === scrollInteraction)
    mainPane.value.scrollTop = mainPane.value.scrollHeight;
}
async function continueKnowledgeDiscussion() {
  await nextTick();
  composerInput.value?.focus({ preventScroll: true });
  composer.value?.scrollIntoView({ block: "nearest", behavior: "smooth" });
}
async function leaveConversation() {
  chat.leaveFinished();
  await openNewProblem();
}
let generation = 0;
const safeUrl = (value: string | null) => {
  try {
    const u = new URL(value || "");
    return ["http:", "https:"].includes(u.protocol) ? u.href : undefined;
  } catch {
    return undefined;
  }
};
const date = (value: string | null) =>
  value ? new Date(value).toLocaleString("zh-CN") : "暂无记录";
const labels: Record<string, string> = {
  user_reported: "本人陈述",
  observed: "观察记录",
  inferred: "推断",
  active: "有效",
  retracted: "已撤销",
  ready: "已就绪",
  partial: "部分完成",
  failed: "失败",
  pending_configuration: "待配置",
  pending: "等待同步",
  synced: "已同步",
  unread: "未读",
  read: "已读",
  snoozed: "稍后提醒",
  dismissed: "已忽略",
  paused: "暂停",
  running: "运行中",
  offline: "离线",
  error: "异常",
};
const label = (value: string) => labels[value] || value;
async function act(fn: () => Promise<void>) {
  if (busy.value) return;
  busy.value = true;
  error.value = "";
  note.value = "";
  try {
    await fn();
  } catch (e) {
    note.value = "";
    if (!(e instanceof ApiError && e.status === 499))
      error.value = e instanceof Error ? e.message : "操作失败";
    if (error.value) {
      await nextTick();
      errorBanner.value?.scrollIntoView({ block: "nearest", behavior: "auto" });
    }
  } finally {
    busy.value = false;
  }
}
function ended() {
  generation++;
  memoryOverview.stop();
  logged.value = false;
  problems.value = [];
  selected.value = "";
  newProblem.value = false;
  editingGoal.value = false;
  goalDraft.value = "";
  chat.clear();
  clearData();
  sourceDetail.value = null;
  chosenSources.value = [];
  sourceText.value = "";
  sourceUrl.value = "";
  mock.value = false;
}
function clearData() {
  messages.value = [];
  sources.value = [];
  memories.value = [];
  decisions.value = [];
  topics.value = [];
}
async function loadProblems() {
  const [recent, archived] = await Promise.all([
    list<Problem>("/problems"),
    list<Problem>("/problems?status=archived"),
  ]);
  problems.value = [
    ...recent,
    ...archived.filter((item) => item.status === "archived"),
  ];
}
function discardConversationState(id: string) {
  chat.discardDraft(id);
  const owner = sessionId();
  const operations = readKnowledgeOperations(owner);
  if (operations.delete(id)) saveKnowledgeOperations(owner, operations);
}
async function changeConversation(id: string, change: ConversationChange) {
  if (busy.value || pendingChat.value || chat.preparing.value) return;
  await act(async () => {
    const path = `/problems/${encodeURIComponent(id)}`;
    let updated: Problem | null = null;
    try {
      if (change === "delete") await request(path, "DELETE");
      else updated = await request<Problem>(path, "PATCH", change);
    } catch (failure) {
      if (!(failure instanceof ApiError && failure.status === 404))
        throw failure;
    }
    storeProblem(id, updated);
    if (
      !updated ||
      (typeof change === "object" &&
        "status" in change &&
        change.status === "archived")
    ) {
      if (selected.value === id) await enterNewConversation();
      discardConversationState(id);
    }
    if (!updated && change !== "delete") throw new Error("这段对话已删除。");
    await loadProblems();
  });
}
async function refresh() {
  const currentLibrary = libraryPage.value;
  const previous = problem.value;
  await loadProblems();
  if (previous && selected.value === previous.id) {
    const current =
      problems.value.find((item) => item.id === previous.id) ||
      (await readProblem(previous.id));
    if (
      !current ||
      (previous.status !== "archived" &&
        current.status === "archived" &&
        !pendingChat.value)
    ) {
      if (!current) chat.discardMissingConversation(previous.id);
      discardConversationState(previous.id);
      await enterNewConversation();
      note.value = current ? "这段对话已归档。" : "这段对话已删除。";
    } else if (!problem.value) storeProblem(previous.id, current);
  }
  await load();
  if (
    logged.value &&
    tab.value === "资料与主题" &&
    currentLibrary &&
    currentLibrary === libraryPage.value
  )
    await currentLibrary.refresh();
}
async function load() {
  const g = ++generation,
    id = selected.value,
    section = tab.value;
  const assign = <T,>(target: { value: T }, value: T) => {
    if (g === generation && logged.value) target.value = value;
  };
  if (section === "记忆") {
    memoryOverview.stop();
    assign(memories, await list<Memory>("/memories"));
    if (g === generation && logged.value && tab.value === "记忆")
      void memoryOverview.open();
    return;
  }
  if (section === "资料与主题") {
    const [s, t] = await Promise.all([
      list<Source>("/sources"),
      list<Topic>("/topics"),
    ]);
    assign(sources, s);
    assign(topics, t);
    return;
  }
  if (newProblem.value) return;
  if (!id) return;
  const q = `?problemId=${encodeURIComponent(id)}`;
  if (section === "讨论") {
    const [m, s, mem, dec] = await Promise.all([
      list<Message>(`/problems/${id}/messages`),
      list<Source>("/sources" + q),
      list<Memory>("/memories" + q),
      list<Decision>("/decisions" + q),
    ]);
    assign(messages, m);
    assign(memories, mem);
    assign(decisions, dec);
    assign(sources, s);
    if (g === generation) mock.value = m.some((x) => x.mode === "mock");
  }
}
async function login() {
  await act(async () => {
    const s = await request<{ accessToken: string; expiresAt: string }>(
      "/auth/login",
      "POST",
      { accessCode: accessCode.value },
    );
    setSession(s);
    accessCode.value = "";
    logged.value = true;
    await initialize();
  });
}
async function initialize() {
  chat.restore();
  tab.value = "讨论";
  const restoringKnowledgeId = [
    ...readKnowledgeOperations(sessionId()).keys(),
  ].at(-1);
  const restoringProblemId =
    pendingChat.value?.problemId || restoringKnowledgeId;
  newProblem.value = !restoringProblemId;
  if (restoringProblemId) selected.value = restoringProblemId;
  await loadProblems();
  if (
    restoringProblemId &&
    !problems.value.some((item) => item.id === restoringProblemId)
  ) {
    const restored = await readProblem(restoringProblemId);
    if (restored) storeProblem(restoringProblemId, restored);
    else {
      chat.discardMissingConversation(restoringProblemId);
      discardConversationState(restoringProblemId);
      await enterNewConversation();
      note.value = "原对话已删除。";
    }
  }
  await load();
  if (
    pendingChat.value?.job &&
    !pendingChat.value.operation &&
    pendingChat.value.state !== "stale"
  )
    await chat.resume();
}
async function job(path: string, body: unknown) {
  const id = selected.value;
  const j = await request<Job>(path, "POST", body);
  const current = generation;
  const completed = await waitJob(
    j,
    () => logged.value && selected.value === id && generation === current,
    (j) => {
      note.value =
        j.status === "succeeded" ? "处理完成" : "正在处理，可稍后刷新查看";
      if (j.mode === "mock") mock.value = true;
    },
  );
  if (!completed) throw new ApiError("已停止等待。", 499);
  if (logged.value && selected.value === id) await load();
}
async function send() {
  if (
    (!chatDraft.value.trim() && !draftFiles.value.length) ||
    pendingChat.value
  )
    return;
  thinkingOpen.value = false;
  await act(() =>
    chat.send(
      newProblem.value ? null : selected.value || null,
      chosenSources.value,
    ),
  );
}
async function stopMissingMedia() {
  if (
    !confirm(
      "此前请求可能已被服务端接收。停止本地等待后，请先查看历史，避免重复发送。继续？",
    )
  )
    return;
  chat.leaveMissingMedia();
  await act(refresh);
}
async function ingest() {
  const id = knowledgeProblemId.value;
  if (!id) return;
  await act(async () => {
    await job("/sources", {
      problemId: id,
      kind: "article",
      title: sourceTitle.value || undefined,
      text: sourceText.value || undefined,
      url: sourceUrl.value || undefined,
    });
    sourceTitle.value = "";
    sourceText.value = "";
    sourceUrl.value = "";
    note.value = "资料已保存。摘要与 Wiki 同步可能仍在处理，请稍后刷新查看。";
  });
}
async function reanalyze() {
  const id = knowledgeProblemId.value;
  if (!id) return;
  await act(async () => {
    try {
      await job(`/problems/${id}/analyze`, {});
      note.value = "资料分析已完成；Wiki 同步状态请查看下方主题。";
    } catch (failure) {
      // Refresh topic status without replacing the original analysis error.
      if (logged.value) await load().catch(() => {});
      throw failure;
    }
  });
}
async function showSource(id: string, quotes: Evidence[] = []) {
  await act(async () => {
    sourceDetail.value = await request<Source>(
      `/sources/${encodeURIComponent(id)}`,
    );
    sourceQuotes.value = quotes;
  });
}
async function remove(path: string) {
  if (!confirm("确认删除？删除后不再用于当前检索；外部备份不保证同步删除。"))
    return;
  await act(async () => {
    if (path.startsWith("/memories/")) await mutateMemory(path, "DELETE");
    else {
      await request(path, "DELETE");
      await load();
    }
  });
}
async function changeMemory(m: Memory) {
  const text = prompt("纠正这条记忆（保留历史与原类型）", m.content);
  if (text === null || !text.trim()) return;
  await act(async () => {
    await mutateMemory(`/memories/${m.id}`, "PATCH", {
      content: text,
      expectedRevision: m.revision,
    });
  });
}
async function mutateMemory(path: string, method: string, body?: unknown) {
  memoryOverview.stop();
  try {
    await request(path, method, body);
  } catch (failure) {
    if (logged.value) await load().catch(() => {});
    throw failure;
  }
  if (logged.value) await load();
}
async function forgetMemory(memory: Memory) {
  await act(() =>
    mutateMemory(`/memories/${memory.id}`, "PATCH", {
      status: "retracted",
      expectedRevision: memory.revision,
    }),
  );
}
watch([selected, tab, newProblem], ([id], [old]) => {
  memoryOverview.stop();
  if (id !== old) {
    editingGoal.value = false;
    goalDraft.value = "";
    clearData();
    chosenSources.value = [];
    sourceDetail.value = null;
    sourceTitle.value = "";
    sourceText.value = "";
    sourceUrl.value = "";
  }
  if (logged.value && !busy.value) void act(load);
});
onMounted(() => {
  window.addEventListener("session-ended", ended);
  window.addEventListener("media-cleanup-error", cleanupFailure);
  document.addEventListener("pointerdown", closeThinking);
  if (logged.value) void act(initialize);
});
onUnmounted(() => {
  generation++;
  memoryOverview.stop();
  chat.dispose();
  window.removeEventListener("session-ended", ended);
  window.removeEventListener("media-cleanup-error", cleanupFailure);
  document.removeEventListener("pointerdown", closeThinking);
});
</script>

<template>
  <div v-if="!logged" class="login">
    <div class="brand">知衡 <span>DECISION / PERSONAL</span></div>
    <h1>把问题想清楚，<br />再做自己的决定。</h1>
    <p>整理依据，留住未知，回看每一次选择。</p>
    <form @submit.prevent="login" class="card">
      <h2>进入个人空间</h2>
      <label
        >访问码<input
          v-model="accessCode"
          type="password"
          required
          autocomplete="current-password"
          autofocus /></label
      ><button :disabled="busy">{{ busy ? "连接中…" : "登录" }}</button>
      <p v-if="error" role="alert" class="error">{{ error }}</p>
    </form>
  </div>
  <div v-else class="shell" :class="{ 'sidebar-collapsed': sidebarCollapsed }">
    <AppSidebar
      ref="sidebar"
      v-model:collapsed="sidebarCollapsed"
      :problems="problems"
      :selected="selected"
      :new-problem="newProblem"
      :active-tab="tab"
      :busy="busy || chat.preparing.value"
      :pending="!!pendingChat"
      @new="openNewProblem"
      @select="openConversation"
      @change="changeConversation"
      @tool="tab = $event"
      @logout="logout"
      @modal="sidebarModal = $event"
    />
    <main
      ref="mainPane"
      @wheel.passive="scrollInteraction++"
      @touchmove.passive="scrollInteraction++"
      @pointerdown="scrollInteraction++"
      @keydown="scrollInteraction++"
      :inert="sidebarModal"
      :class="{
        'chat-page': tab === '讨论',
        'empty-chat': emptyConversation,
        'library-page': tab === '资料与主题',
      }"
    >
      <header class="page-header">
        <button
          class="icon-button mobile-menu-toggle"
          aria-label="打开侧栏"
          title="打开侧栏"
          aria-controls="app-sidebar"
          :aria-expanded="sidebarModal"
          @click="sidebar?.openMenu($event.currentTarget as HTMLElement)"
        >
          <Menu :size="21" />
        </button>
        <h1 :class="{ 'empty-page-title': emptyConversation }">
          {{
            tab !== "讨论"
              ? tab
              : newProblem
                ? "知衡"
                : problem?.title || "对话"
          }}
        </h1>
        <button
          class="icon-button refresh-button"
          aria-label="刷新"
          title="刷新"
          :disabled="busy"
          @click="act(refresh)"
        >
          <RefreshCw :size="17" :class="{ spinning: busy }" />
        </button>
      </header>
      <div class="page-content">
        <div v-if="error" ref="errorBanner" role="alert" class="error">
          {{ error }}
        </div>
        <div v-if="note" role="status" class="notice">{{ note }}</div>
        <div v-if="mock && !newProblem" class="error">
          当前包含测试替身（mock）结果，不代表真实模型验证。
        </div>
        <div v-if="busy" role="status" class="loading-status muted">
          <LoaderCircle :size="15" class="spinning" />
          {{ tab === "讨论" && pendingChat ? "正在想一想…" : "正在加载…" }}
        </div>
        <template v-if="tab === '讨论'">
          <div v-if="emptyConversation" class="chat-welcome">
            <span class="welcome-mark" aria-hidden="true">知</span>
            <h2>从你现在想到的说起。</h2>
            <p>不用整理好，一句话就可以。</p>
          </div>
          <details v-if="problem" class="conversation-details">
            <summary>对话信息</summary>
            <p v-if="problem.goal">目标：{{ problem.goal }}</p>
            <p v-if="problem.constraints.length">
              限制：{{ problem.constraints.join("；") }}
            </p>
            <p v-if="problem.unknowns.length">
              待确认：{{ problem.unknowns.join("；") }}
            </p>
            <button
              v-if="!editingGoal"
              class="secondary"
              :disabled="busy"
              @click="openGoalEditor"
            >
              {{ problem.goal ? "修改目标" : "补充目标（可选）" }}
            </button>
            <form v-else @submit.prevent="saveGoal">
              <label
                >更新问题目标<textarea
                  v-model="goalDraft"
                  required
                  :disabled="busy"
                />
              </label>
              <button :disabled="busy || !goalDraft.trim()">保存目标</button>
              <button
                type="button"
                class="secondary"
                :disabled="busy"
                @click="editingGoal = false"
              >
                取消修改
              </button>
            </form>
          </details>
          <article
            v-for="m in newProblem ? [] : messages"
            :key="m.id"
            class="message"
            :class="m.role"
          >
            <small
              >{{ m.role === "user" ? "你" : "决策助手" }}
              <b v-if="m.mode === 'mock'">· 测试替身</b></small
            >
            <AttachmentList
              v-if="m.attachments?.length"
              :items="m.attachments"
            />
            <span
              v-if="m.thinking && m.thinking !== 'off'"
              class="thinking-tag"
              >{{ thinkingLabel(m.thinking) }}</span
            >
            <div
              v-if="m.role === 'assistant'"
              class="markdown-body"
              v-html="renderMarkdown(m.content)"
            />
            <p v-else class="pre">{{ m.content }}</p>
            <ChatRecords
              v-if="m.role === 'assistant'"
              :message="m"
              :memories="memories"
              :decisions="decisions"
            />
            <EvidencePanel
              :evidence="m.evidence"
              :uncertainties="m.uncertainties"
              :sources="sources"
              :busy="busy"
              @open="showSource"
            />
          </article>
          <article v-if="showPendingSentence" class="message user">
            <small>你</small>
            <AttachmentList
              v-if="pendingChat?.files?.length"
              :items="pendingChat.files"
              local
            />
            <p v-if="pendingChat!.content" class="pre">
              {{ pendingChat!.content }}
            </p>
          </article>
          <div
            v-if="pendingChat && !busy"
            class="pending-recovery"
            role="status"
          >
            <p v-if="pendingChat.mediaUnavailable">
              本机附件暂存已丢失，无法继续这次发送。此前的请求可能已收到，请先查看历史；停止等待不会重新发送或删除服务端记录。
            </p>
            <p v-else-if="pendingChat.state === 'stale'">
              原话还在。对话内容已有变化，请调整后再发。
            </p>
            <p
              v-else-if="
                !pendingChat.operation && pendingChat.job?.status === 'failed'
              "
            >
              回答暂时没生成，原话已经保存。
            </p>
            <p v-else-if="pendingChat.state === 'done'">
              回答已经生成，正在恢复显示。
            </p>
            <p v-else>原话还在，可以接着获取回答。</p>
            <button
              v-if="pendingChat.mediaUnavailable"
              @click="stopMissingMedia"
            >
              停止等待并查看历史
            </button>
            <button
              v-else-if="pendingChat.state === 'stale'"
              @click="editOriginal"
            >
              调整原话
            </button>
            <button v-else @click="act(() => chat.resume(true))">
              {{ pendingAction }}
            </button>
            <button
              v-if="
                !pendingChat.operation &&
                pendingChat.job &&
                ['failed', 'succeeded'].includes(pendingChat.job.status)
              "
              class="secondary"
              @click="leaveConversation"
            >
              先聊别的
            </button>
          </div>
          <KnowledgeConversation
            v-if="!newProblem && problem && messages.length"
            :problem-id="problem.id"
            :message="messages.at(-1)"
            :disabled="busy || !!pendingChat || chat.preparing.value"
            @source="showSource"
            @discuss="continueKnowledgeDiscussion"
            @before-reveal="revealKnowledgeSuggestion"
            @library="tab = '资料与主题'"
          />
          <div class="composer-dock">
            <form
              ref="composer"
              class="composer"
              :class="{ 'dragging-files': draggingFiles }"
              aria-label="对话输入"
              @submit.prevent="send"
              @paste="pasteAttachments"
              @dragover="dragAttachments"
              @dragleave="draggingFiles = false"
              @drop="dropAttachments"
            >
              <input
                ref="fileInput"
                type="file"
                hidden
                multiple
                :accept="FILE_ACCEPT"
                aria-label="选择图片或文件"
                @change="fileChange"
              />
              <AttachmentList
                :items="draftFiles"
                local
                removable
                :disabled="busy || !!pendingChat"
                @remove="
                  (id) => {
                    try {
                      chat.removeFile(draftTarget, id);
                    } catch (failure) {
                      error =
                        failure instanceof Error
                          ? failure.message
                          : '未能移除附件';
                    }
                  }
                "
              />
              <p v-if="draggingFiles" class="drop-hint">松开以添加图片或文件</p>
              <label>
                <span class="sr-only">说说你的问题</span
                ><textarea
                  ref="composerInput"
                  v-model="chatDraft"
                  rows="2"
                  @input="resizeComposer"
                  @keydown="composerKeydown"
                  @compositionstart="composing = true"
                  @compositionend="composing = false"
                  maxlength="12000"
                  :disabled="busy || !!pendingChat"
                  :placeholder="
                    newProblem ? '说说你的想法，或现在困扰你的事…' : '接着说…'
                  "
                />
              </label>
              <div class="composer-actions">
                <button
                  type="button"
                  class="icon-button attachment-add"
                  aria-label="添加图片或文件"
                  title="添加图片或文件 · 最多4个"
                  :disabled="busy || !!pendingChat || chat.preparing.value"
                  @click="fileInput?.click()"
                >
                  <Paperclip :size="19" />
                </button>
                <div
                  ref="thinkingMenu"
                  class="thinking-picker"
                  @keydown.esc.prevent="thinkingOpen = false"
                >
                  <button
                    type="button"
                    class="thinking-trigger"
                    aria-label="思考等级"
                    :aria-expanded="thinkingOpen"
                    :disabled="busy || !!pendingChat"
                    @click="thinkingOpen = !thinkingOpen"
                  >
                    {{ thinkingLabel(selectedThinking)
                    }}<ChevronDown :size="13" />
                  </button>
                  <div
                    v-if="thinkingOpen"
                    class="thinking-options"
                    role="group"
                    aria-label="选择思考等级"
                  >
                    <button
                      v-for="option in thinkingOptions"
                      :key="option.value"
                      type="button"
                      :aria-pressed="selectedThinking === option.value"
                      @click="chooseThinking(option.value)"
                    >
                      <span
                        >{{ option.label
                        }}<small>{{ option.description }}</small></span
                      ><Check
                        v-if="selectedThinking === option.value"
                        :size="15"
                      />
                    </button>
                  </div>
                </div>
                <details
                  v-if="!newProblem && sources.length"
                  class="source-picker"
                >
                  <summary>
                    <Paperclip :size="16" />参考资料<span
                      v-if="chosenSources.length"
                      >{{ chosenSources.length }}</span
                    >
                  </summary>
                  <div class="source-options">
                    <label v-for="s in sources" :key="s.id" class="check"
                      ><input
                        type="checkbox"
                        :value="s.id"
                        v-model="chosenSources"
                      />{{ s.title }}</label
                    >
                  </div>
                </details>
                <button
                  class="send-button"
                  aria-label="发送"
                  :title="busy ? '正在处理' : '发送'"
                  :disabled="
                    busy ||
                    !!pendingChat ||
                    chat.preparing.value ||
                    (!chatDraft.trim() && !draftFiles.length)
                  "
                >
                  <LoaderCircle
                    v-if="busy"
                    :size="19"
                    class="spinning"
                  /><ArrowUp v-else :size="21" />
                </button>
              </div>
            </form>
            <p class="composer-footnote">建议供你判断，决定由你确认。</p>
          </div>
        </template>
        <LibraryPage
          ref="libraryPage"
          v-else-if="tab === '资料与主题'"
          :sources="sources"
          :topics="topics"
          :problems="problems"
          :context-id="knowledgeProblemId"
          :busy="busy"
          :can-navigate="!busy && !pendingChat"
          v-model:title="sourceTitle"
          v-model:text="sourceText"
          v-model:url="sourceUrl"
          @source="showSource"
          @conversation="openConversation"
          @remove="(id) => remove(`/sources/${id}`)"
          @reanalyze="reanalyze"
          @sync="(id) => act(() => job(`/topics/${id}/sync`, {}))"
          @ingest="ingest"
        />
        <MemoryPage
          v-else-if="tab === '记忆'"
          :memories="memories"
          :overview="memoryOverview.overview.value"
          :loading="memoryOverview.loading.value"
          :error="memoryOverview.error.value"
          :uncertain="memoryOverview.uncertain.value"
          :busy="busy"
          :sources="sources"
          @correct="changeMemory"
          @forget="forgetMemory"
          @remove="(id) => remove(`/memories/${id}`)"
          @source="showSource"
          @refresh="act(load)"
          @retry="memoryOverview.open({ retry: true })"
          @resume="memoryOverview.open({ resume: true })"
        />
      </div>
    </main>
    <div v-if="sourceDetail" class="overlay" @click.self="sourceDetail = null">
      <section
        class="drawer"
        role="dialog"
        aria-modal="true"
        aria-label="来源详情"
        @keydown.esc="sourceDetail = null"
        tabindex="0"
      >
        <button class="secondary" @click="sourceDetail = null">关闭来源</button>
        <h2>{{ sourceDetail.title }}</h2>
        <div v-if="sourceQuotes.length" class="source-quotes">
          <h3>本次引用</h3>
          <blockquote v-for="(entry, index) in sourceQuotes" :key="index">
            <span class="citation-meta">{{
              entry.relation === "supports"
                ? "支持"
                : entry.relation === "contradicts"
                  ? "不同依据"
                  : "背景"
            }}</span>
            <p class="pre">{{ entry.quote }}</p>
          </blockquote>
          <h3>来源全文</h3>
        </div>
        <p>
          {{ label(sourceDetail.status) }} · {{ date(sourceDetail.capturedAt) }}
        </p>
        <a
          v-if="safeUrl(sourceDetail.originUrl)"
          :href="safeUrl(sourceDetail.originUrl)"
          target="_blank"
          rel="noopener noreferrer"
          >打开原始链接</a
        >
        <p class="pre">{{ sourceDetail.text }}</p>
      </section>
    </div>
  </div>
</template>
