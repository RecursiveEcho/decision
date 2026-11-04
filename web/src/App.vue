<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from "vue";
import {
  ApiError,
  list,
  logout,
  request,
  setSession,
  token,
  waitJob,
} from "./api";
import AppSidebar from "./AppSidebar.vue";
import EvidencePanel from "./EvidencePanel.vue";
import AttachmentList from "./AttachmentList.vue";
import ChatRecords from "./ChatRecords.vue";
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
  problems.value = await list<Problem>("/problems");
  if (
    !pendingChat.value?.problemId &&
    !problems.value.some((p) => p.id === selected.value)
  )
    selected.value = problems.value[0]?.id || "";
}
async function refresh() {
  await loadProblems();
  await load();
}
async function load() {
  const g = ++generation,
    id = selected.value,
    section = tab.value;
  const assign = <T,>(target: { value: T }, value: T) => {
    if (g === generation && logged.value) target.value = value;
  };
  if (section === "记忆") {
    assign(memories, await list<Memory>("/memories"));
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
  if (section === "资料与主题") {
    const [s, t] = await Promise.all([
      list<Source>("/sources" + q),
      list<Topic>("/topics" + q),
    ]);
    assign(sources, s);
    assign(topics, t);
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
  newProblem.value = !pendingChat.value?.problemId;
  if (pendingChat.value?.problemId)
    selected.value = pendingChat.value.problemId;
  await loadProblems();
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
  await act(async () => {
    await job("/sources", {
      problemId: selected.value,
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
  await act(async () => {
    try {
      await job(`/problems/${selected.value}/analyze`, {});
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
    await request(path, "DELETE");
    await load();
  });
}
async function changeMemory(m: Memory) {
  const text = prompt("纠正这条记忆（保留历史与原类型）", m.content);
  if (text === null || !text.trim()) return;
  await act(async () => {
    await request(`/memories/${m.id}`, "PATCH", {
      content: text,
      expectedRevision: m.revision,
    });
    await load();
  });
}
watch([selected, tab, newProblem], ([id], [old]) => {
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
      @select="displayedProblem = $event"
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
      :class="{ 'chat-page': tab === '讨论', 'empty-chat': emptyConversation }"
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
        <template v-else-if="tab === '资料与主题'">
          <form class="card" @submit.prevent="ingest">
            <h2>添加资料</h2>
            <label>标题（可选）<input v-model="sourceTitle" /></label
            ><label
              >文章链接<input
                v-model="sourceUrl"
                type="url"
                placeholder="https://…" /></label
            ><label
              >或粘贴正文<textarea
                v-model="sourceText"
                placeholder="链接无法读取时，可以粘贴正文；同时提供时优先保存正文。"
              /></label
            ><button
              :disabled="busy || (!sourceText.trim() && !sourceUrl.trim())"
            >
              保存并整理资料
            </button>
          </form>
          <h2>来源 · {{ sources.length }}</h2>
          <p v-if="!sources.length" class="muted">还没有资料。</p>
          <article v-for="s in sources" :key="s.id" class="card">
            <h3>{{ s.title }}</h3>
            <p>{{ label(s.status) }} · {{ date(s.capturedAt) }}</p>
            <button
              class="secondary"
              :disabled="busy"
              @click="showSource(s.id)"
            >
              查看原文
            </button>
            <button
              class="danger"
              :disabled="busy"
              @click="remove(`/sources/${s.id}`)"
            >
              删除
            </button>
          </article>
          <h2>知识主题</h2>
          <div v-if="sources.length" class="card">
            <p>
              摘要未完成或资料、记忆有变化时，可重新分析已有资料，无需重复上传。Wiki
              同步仅负责把已有主题写入 Wiki。
            </p>
            <button :disabled="busy" @click="reanalyze">重新分析资料</button>
          </div>
          <p v-if="!topics.length" class="muted">暂无主题，等待资料处理。</p>
          <article v-for="t in topics" :key="t.id" class="card">
            <h3>{{ t.title }}</h3>
            <p class="pre">{{ t.markdown }}</p>
            <p>Wiki：{{ label(t.syncStatus) }}</p>
            <a
              v-if="safeUrl(t.wikiUrl)"
              :href="safeUrl(t.wikiUrl)"
              target="_blank"
              rel="noopener noreferrer"
              >查看 Wiki</a
            >
            <button
              class="secondary"
              :disabled="busy"
              @click="act(() => job(`/topics/${t.id}/sync`, {}))"
            >
              同步 Wiki
            </button>
            <EvidencePanel
              :evidence="t.evidence"
              :sources="sources"
              :busy="busy"
              @open="showSource"
            />
          </article>
        </template>
        <template v-else-if="tab === '记忆'">
          <h2>记住的事</h2>
          <p class="muted">从聊天中积累。记错了，可以随时纠正或忘记。</p>
          <p v-if="!memories.length" class="empty">
            还没有记忆，照常聊天就好。
          </p>
          <article v-for="m in memories" :key="m.id" class="memory-row">
            <small
              >{{ m.scope === "global" ? "用于其他对话" : "仅本次对话"
              }}<span v-if="m.status !== 'active'"> · 已忘记</span></small
            >
            <p class="pre">{{ m.content }}</p>
            <details v-if="m.sourceQuote" class="record-origin">
              <summary>查看你的原话</summary>
              <blockquote>{{ m.sourceQuote }}</blockquote>
            </details>
            <button class="secondary" :disabled="busy" @click="changeMemory(m)">
              纠正
            </button>
            <button
              class="secondary"
              :disabled="busy || m.status === 'retracted'"
              @click="
                act(async () => {
                  await request(`/memories/${m.id}`, 'PATCH', {
                    status: 'retracted',
                    expectedRevision: m.revision,
                  });
                  await load();
                })
              "
            >
              忘记
            </button>
            <button
              class="danger"
              :disabled="busy"
              @click="remove(`/memories/${m.id}`)"
            >
              删除
            </button>
            <EvidencePanel
              :evidence="m.evidence"
              :sources="sources"
              :busy="busy"
              @open="showSource"
            />
          </article>
        </template>
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
