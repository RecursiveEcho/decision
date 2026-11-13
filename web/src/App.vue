<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from "vue";
import {
  ApiError,
  list,
  logout,
  request,
  sessionId,
  accountId,
  setSessionAccount,
  setSession,
  token,
  waitJob,
} from "./api";
import AuthScreen from "./AuthScreen.vue";
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
  Copy,
  Square,
  ArrowDown,
  Search,
  X,
} from "@lucide/vue";
import {
  preferences,
  loadPreferences,
  readWorkspace,
  writeWorkspace,
} from "./preferences";
import { FILE_ACCEPT } from "./attachments";
import { renderMarkdown } from "./markdown";
import { useChatFlow } from "./chatFlow";
import type {
  Account,
  AuthSession,
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
  busy = ref(false),
  error = ref(""),
  note = ref(""),
  mock = ref(false);
const account = ref<Account | null>(null);
const sessionKey = ref(logged.value ? sessionId() : "");
const sidebar = ref<InstanceType<typeof AppSidebar> | null>(null);
const libraryPage = ref<InstanceType<typeof LibraryPage> | null>(null);
const sidebarCollapsed = ref(false);
const sidebarModal = ref(false);
const composing = ref(false);
const restoringChat = ref(false);
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
  if (!id || busy.value) return;
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
    restoreScroll();
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
  accepted(id, created, sourceTarget) {
    if (created)
      problems.value = [created, ...problems.value.filter((p) => p.id !== id)];
    if (
      sourceTarget === draftTarget.value ||
      (sourceTarget === "new" && newProblem.value)
    ) {
      selected.value = id;
      newProblem.value = false;
      tab.value = "讨论";
    }
  },
  async refresh(id) {
    if (id && selected.value !== id) {
      await loadProblems();
      return;
    }
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
  () => chat.thinking.value[draftTarget.value] || "off",
);
async function addAttachments(incoming: File[]) {
  if (!incoming.length || busy.value) return;
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
  if (busy.value) return;
  const text = event.clipboardData?.getData("text/plain");
  if (text) chatDraft.value += text;
  void addAttachments(files);
}
function dragAttachments(event: DragEvent) {
  if (!event.dataTransfer?.types.includes("Files")) return;
  event.preventDefault();
  draggingFiles.value = !busy.value;
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
    event.keyCode === 229 ||
    (preferences.sendKey === "mod-enter" && !event.metaKey && !event.ctrlKey)
  )
    return;
  event.preventDefault();
  if (!busy.value && (chatDraft.value.trim() || draftFiles.value.length))
    void send();
}
watch(chatDraft, () => void nextTick(resizeComposer));
watch(composerInput, () => void nextTick(resizeComposer));
async function openNewProblem() {
  if (busy.value) return;
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
let actionSequence = 0;
async function act(fn: () => Promise<void>) {
  if (busy.value) return;
  const action = ++actionSequence;
  busy.value = true;
  error.value = "";
  note.value = "";
  try {
    await fn();
  } catch (e) {
    if (action !== actionSequence) return;
    note.value = "";
    if (!(e instanceof ApiError && e.status === 499))
      error.value = e instanceof Error ? e.message : "操作失败";
    if (error.value) {
      await nextTick();
      errorBanner.value?.scrollIntoView({ block: "nearest", behavior: "auto" });
    }
  } finally {
    if (action === actionSequence) busy.value = false;
  }
}
function ended() {
  generation++;
  actionSequence++;
  busy.value = false;
  account.value = null;
  sessionKey.value = "";
  tab.value = "讨论";
  error.value = "";
  note.value = "";
  sourceTitle.value = "";
  sourceQuotes.value = [];
  thinkingOpen.value = false;
  draggingFiles.value = false;
  restoringChat.value = false;
  memoryOverview.stop();
  logged.value = false;
  problems.value = [];
  selected.value = "";
  newProblem.value = false;
  editingGoal.value = false;
  goalDraft.value = "";
  chat.clear();
  findOpen.value = false;
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
const historyCursors = ref<{ active: string | null; archived: string | null }>({
  active: null,
  archived: null,
});
const hasMoreHistory = computed(
  () => !!historyCursors.value.active || !!historyCursors.value.archived,
);
async function loadProblems(more = false) {
  const current = sessionId();
  const states = ["active", "archived"] as const;
  const pages = await Promise.all(
    states.map(async (status) => {
      const cursor = historyCursors.value[status];
      if (more && !cursor) return { items: [] as Problem[], nextCursor: null };
      return request<{ items: Problem[]; nextCursor: string | null }>(
        `/problems?status=${status}&limit=30${more && cursor ? "&cursor=" + encodeURIComponent(cursor) : ""}`,
      );
    }),
  );
  if (current !== sessionId()) return;
  const items = pages.flatMap((p) => p.items);
  const keep = more
    ? problems.value
    : problems.value.filter((p) => p.id === selected.value);
  problems.value = [
    ...new Map([...keep, ...items].map((p) => [p.id, p])).values(),
  ];
  historyCursors.value = {
    active: pages[0].nextCursor,
    archived: pages[1].nextCursor,
  };
}
function discardConversationState(id: string) {
  chat.discardDraft(id);
  const owner = sessionId();
  const operations = readKnowledgeOperations(owner);
  if (operations.delete(id)) saveKnowledgeOperations(owner, operations);
}
async function changeConversation(id: string, change: ConversationChange) {
  if (busy.value || chat.preparing.value) return;
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
      if (!updated) {
        chat.discardMissingConversation(id);
        discardConversationState(id);
      }
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
      if (!current) discardConversationState(previous.id);
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
async function enterAccount(session: AuthSession) {
  setSession(session);
  sessionKey.value = sessionId();
  logged.value = true;
  await act(initialize);
}
async function initialize() {
  account.value = await request<Account>("/auth/me");
  setSessionAccount(account.value);
  loadPreferences();
  sidebarCollapsed.value = readWorkspace().collapsed || false;
  chat.restore();
  tab.value = "讨论";
  const restoringKnowledgeId = [
    ...readKnowledgeOperations(sessionId()).keys(),
  ].at(-1);
  const restoringProblemId =
    (preferences.restoreWork ? readWorkspace().selected : undefined) ||
    pendingChat.value?.problemId ||
    restoringKnowledgeId;
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
  chat.setTarget(newProblem.value ? "new" : selected.value);
  void chat.recover().catch((failure) => {
    if (logged.value)
      note.value =
        failure instanceof Error ? failure.message : "任务状态暂时无法恢复。";
  });
  await refreshModel();
  if (settingsRequested) {
    settingsRequested = false;
    await nextTick();
    sidebar.value?.openSettings();
  }
  restoreScroll();
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
    chat.preparing.value
  )
    return;
  thinkingOpen.value = false;
  error.value = "";
  const current = sessionId();
  void chat
    .send(
      newProblem.value ? null : selected.value || null,
      chosenSources.value,
      true,
    )
    .catch((failure) => {
      if (
        current === sessionId() &&
        !(failure instanceof ApiError && failure.status === 499)
      )
        error.value = failure instanceof Error ? failure.message : "发送失败";
    });
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
const defaultModel = ref({ provider: "", model: "", configured: false });
const conversationProvider = ref("deepseek"),
  conversationModel = ref("");
const findOpen = ref(false),
  findQuery = ref(""),
  findIndex = ref(-1),
  findInput = ref<HTMLInputElement>();
const showBottom = ref(false),
  copyStatus = ref("");
let settingsRequested = false;
const currentQueue = computed(() => chat.queues.value[draftTarget.value] || []);
const runStates = computed(() =>
  Object.fromEntries(
    Object.entries(chat.pendings.value).map(([id, p]) => [
      id,
      p.error
        ? "需处理"
        : p.job?.status === "queued"
          ? "排队中"
          : p.state === "cancelled"
            ? "已停止"
            : p.state === "failed"
              ? "失败"
              : p.state === "done"
                ? "已完成"
                : "运行中",
    ]),
  ),
);
const currentModel = computed(
  () =>
    chat.models.value[draftTarget.value]?.model ||
    defaultModel.value.model ||
    "配置模型",
);
const isRunning = computed(
  () =>
    !!pendingChat.value?.job &&
    ["queued", "running"].includes(pendingChat.value.job.status),
);
async function refreshModel() {
  const current = sessionId();
  try {
    const value = await request<typeof defaultModel.value>("/settings/model");
    if (current === sessionId()) defaultModel.value = value;
  } catch {
    /* Settings presents the actionable error. */
  }
}
function useConversationModel() {
  chat.setModel(
    draftTarget.value,
    conversationModel.value.trim()
      ? {
          provider: conversationProvider.value,
          model: conversationModel.value.trim(),
        }
      : {},
  );
}
async function copyMessage(id: string, content: string) {
  try {
    await navigator.clipboard.writeText(content);
    copyStatus.value = id;
    setTimeout(() => {
      if (copyStatus.value === id) copyStatus.value = "";
    }, 1800);
  } catch {
    error.value = "复制失败，请选择文字后使用系统复制。";
  }
}
async function searchSelect(id: string, messageId: string | null) {
  await openConversation(id);
  await nextTick();
  if (messageId) {
    const element = document.getElementById("message-" + messageId);
    element?.scrollIntoView({ block: "center" });
    element?.classList.add("search-highlight");
    setTimeout(() => element?.classList.remove("search-highlight"), 3000);
  }
}
function findNext() {
  const matches = messages.value.filter((m) =>
    m.content.toLocaleLowerCase().includes(findQuery.value.toLocaleLowerCase()),
  );
  if (!findQuery.value || !matches.length) {
    note.value = "当前对话没有匹配内容。";
    return;
  }
  findIndex.value = (findIndex.value + 1) % matches.length;
  document
    .getElementById("message-" + matches[findIndex.value].id)
    ?.scrollIntoView({ block: "center" });
  note.value = `找到 ${matches.length} 处，当前第 ${findIndex.value + 1} 处。`;
}
function saveScroll() {
  if (!logged.value || busy.value || !selected.value || !mainPane.value) return;
  const state = readWorkspace();
  state.scroll = {
    ...state.scroll,
    [selected.value]: mainPane.value.scrollTop,
  };
  state.selected = newProblem.value ? "" : selected.value;
  state.collapsed = sidebarCollapsed.value;
  try {
    writeWorkspace(state);
  } catch {}
  showBottom.value =
    mainPane.value.scrollHeight -
      mainPane.value.scrollTop -
      mainPane.value.clientHeight >
    300;
}
function restoreScroll() {
  void nextTick(() => {
    if (mainPane.value && selected.value)
      mainPane.value.scrollTop =
        readWorkspace().scroll?.[selected.value] ?? mainPane.value.scrollHeight;
  });
}
function toBottom() {
  mainPane.value?.scrollTo({
    top: mainPane.value.scrollHeight,
    behavior: "smooth",
  });
}
function onSettingsRequested() {
  if (!logged.value) settingsRequested = true;
}
function shortcut(event: KeyboardEvent) {
  if (!logged.value || (!event.metaKey && !event.ctrlKey) || event.altKey)
    return;
  const key = event.key.toLowerCase();
  if (document.querySelector("dialog[open]")) return;
  if (key === "n") {
    event.preventDefault();
    void openNewProblem();
  } else if (key === "k") {
    event.preventDefault();
    sidebar.value?.openSearch();
  } else if (key === ",") {
    event.preventDefault();
    sidebar.value?.openSettings();
  } else if (key === "b") {
    event.preventDefault();
    sidebarCollapsed.value = !sidebarCollapsed.value;
  } else if (key === "f") {
    event.preventDefault();
    findOpen.value = true;
    void nextTick(() => findInput.value?.focus());
  } else if (key === "u" && event.shiftKey) {
    event.preventDefault();
    fileInput.value?.click();
  } else if (key === "." && isRunning.value) {
    event.preventDefault();
    void chat.cancel().catch((e) => (error.value = e.message));
  }
}
watch(sidebarCollapsed, () => {
  if (logged.value)
    try {
      writeWorkspace({ ...readWorkspace(), collapsed: sidebarCollapsed.value });
    } catch {}
});
watch(
  () => pendingChat.value?.job?.partialContent,
  async () => {
    const pane = mainPane.value;
    const follow =
      pane && pane.scrollHeight - pane.scrollTop - pane.clientHeight < 220;
    await nextTick();
    if (follow && mainPane.value)
      mainPane.value.scrollTop = mainPane.value.scrollHeight;
  },
);
watch(draftTarget, (id) => {
  chat.setTarget(id);
  const model = chat.models.value[id];
  conversationProvider.value =
    model?.provider || defaultModel.value.provider || "deepseek";
  conversationModel.value = model?.model || "";
});
watch([selected, tab, newProblem], ([id], [old]) => {
  memoryOverview.stop();
  if (id !== old) {
    if (logged.value)
      try {
        writeWorkspace({
          ...readWorkspace(),
          selected: newProblem.value ? "" : id,
        });
      } catch {}
    editingGoal.value = false;
    goalDraft.value = "";
    clearData();
    chosenSources.value = [];
    sourceDetail.value = null;
    sourceTitle.value = "";
    sourceText.value = "";
    sourceUrl.value = "";
  }
  if (logged.value && !busy.value)
    void act(async () => {
      await load();
      restoreScroll();
    });
});
onMounted(() => {
  window.addEventListener("session-ended", ended);
  window.addEventListener("decision-open-settings", onSettingsRequested);
  window.addEventListener("decision-model-changed", refreshModel);
  document.addEventListener("keydown", shortcut);
  window.addEventListener("media-cleanup-error", cleanupFailure);
  document.addEventListener("pointerdown", closeThinking);
  if (logged.value) void act(initialize);
});
onUnmounted(() => {
  generation++;
  memoryOverview.stop();
  chat.dispose();
  window.removeEventListener("session-ended", ended);
  window.removeEventListener("decision-open-settings", onSettingsRequested);
  window.removeEventListener("decision-model-changed", refreshModel);
  document.removeEventListener("keydown", shortcut);
  window.removeEventListener("media-cleanup-error", cleanupFailure);
  document.removeEventListener("pointerdown", closeThinking);
});
</script>

<template>
  <AuthScreen v-if="!logged" @authenticated="enterAccount" />
  <div
    v-else
    :key="sessionKey"
    class="shell"
    :class="{ 'sidebar-collapsed': sidebarCollapsed }"
  >
    <AppSidebar
      ref="sidebar"
      v-model:collapsed="sidebarCollapsed"
      :problems="problems"
      :account-name="account?.username || ''"
      :selected="selected"
      :new-problem="newProblem"
      :active-tab="tab"
      :busy="busy || chat.preparing.value"
      :pending="false"
      :has-more="hasMoreHistory"
      :runs="runStates"
      :unread="chat.unread.value"
      @more="act(() => loadProblems(true))"
      @search-select="searchSelect"
      @new="openNewProblem"
      @select="openConversation"
      @change="changeConversation"
      @tool="tab = $event"
      @logout="() => logout()"
      @modal="sidebarModal = $event"
    />
    <main
      ref="mainPane"
      @scroll.passive="saveScroll"
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
      <form v-if="findOpen" class="find-chat" @submit.prevent="findNext">
        <Search :size="16" /><input
          ref="findInput"
          v-model="findQuery"
          placeholder="查找当前对话"
          aria-label="查找当前对话"
          @input="findIndex = -1"
        /><button class="secondary">下一处</button
        ><button
          type="button"
          class="icon-button"
          aria-label="关闭查找"
          @click="findOpen = false"
        >
          <X :size="16" />
        </button>
      </form>
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
                ? "Decision"
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
            <span class="welcome-mark" aria-hidden="true">D</span>
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
            :id="'message-' + m.id"
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
            <div class="message-actions">
              <button class="text-button" @click="copyMessage(m.id, m.content)">
                <Copy :size="14" />{{
                  copyStatus === m.id ? "已复制" : "复制"
                }}</button
              ><span v-if="m.model" class="muted">{{ m.model }}</span>
            </div>
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
          <article
            v-if="pendingChat?.job?.partialContent"
            class="message assistant streaming-message"
          >
            <small
              >决策助手 ·
              {{
                isRunning
                  ? "正在生成"
                  : pendingChat.state === "cancelled"
                    ? "已停止 · 未完成"
                    : "未完成回答"
              }}</small
            >
            <div
              class="markdown-body"
              v-html="renderMarkdown(pendingChat.job.partialContent)"
            />
          </article>
          <div v-if="pendingChat" class="run-status" role="status">
            <span v-if="isRunning && !pendingChat.error"
              ><LoaderCircle :size="14" class="spinning" />{{
                pendingChat.stageLabel ||
                (pendingChat.job?.status === "queued"
                  ? "等待执行"
                  : "正在生成回答…")
              }}</span
            >
            <span v-else-if="pendingChat.error"
              >{{ pendingChat.error }}。已保留任务，可继续获取结果。</span
            ><span v-else-if="pendingChat.state === 'cancelled'"
              >已停止。保留的内容是未完成回答。</span
            >
            <span v-else-if="pendingChat.mediaUnavailable"
              >本机附件缓存不可用。请先核对历史，再移除本次等待。</span
            >
            <span v-else>{{
              pendingChat.error || "原话已保留，可以继续获取回答。"
            }}</span>
            <button
              v-if="isRunning"
              class="secondary"
              @click="chat.cancel().catch((e) => (error = e.message))"
            >
              <Square :size="12" />停止
            </button>
            <button
              v-if="isRunning && pendingChat.error"
              class="secondary"
              @click="chat.resume().catch((e) => (error = e.message))"
            >
              重新连接</button
            ><button
              v-else-if="pendingChat.mediaUnavailable"
              class="secondary"
              @click="stopMissingMedia"
            >
              停止等待并查看历史
            </button>
            <button
              v-else-if="pendingChat.state === 'stale'"
              class="secondary"
              @click="editOriginal"
            >
              调整原话
            </button>
            <button
              v-else-if="!isRunning && pendingChat.state !== 'cancelled'"
              class="secondary"
              @click="chat.resume(true).catch((e) => (error = e.message))"
            >
              {{ pendingAction }}
            </button>
            <button
              v-if="
                pendingChat.job &&
                ['failed', 'succeeded', 'cancelled'].includes(
                  pendingChat.job.status,
                )
              "
              class="text-button"
              @click="chat.leaveFinished()"
            >
              收起状态，继续聊天
            </button>
          </div>
          <div v-if="currentQueue.length" class="queue-panel">
            <strong>本机待发送 · {{ currentQueue.length }}</strong>
            <p class="muted">
              {{
                chat.pausedQueues.value[draftTarget]
                  ? "队列已暂停，尚未发送。"
                  : "当前回答完成后依次发送；关闭应用后暂停。"
              }}
            </p>
            <div
              v-for="queued in currentQueue"
              :key="queued.operation?.key"
              class="queue-row"
            >
              <span
                >{{ queued.content || "附件消息"
                }}<small v-if="queued.files?.length">
                  · {{ queued.files.length }} 个附件</small
                ></span
              ><button
                class="icon-button"
                aria-label="取消这条排队消息"
                @click="chat.cancelQueued(draftTarget, queued.operation!.key)"
              >
                <X :size="15" />
              </button>
            </div>
            <button
              v-if="chat.pausedQueues.value[draftTarget] && !isRunning"
              class="secondary"
              @click="chat.continueQueue().catch((e) => (error = e.message))"
            >
              继续发送队列
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
          <button
            v-if="showBottom"
            class="jump-bottom"
            aria-label="回到最新消息"
            @click="toBottom"
          >
            <ArrowDown :size="16" />最新消息
          </button>
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
                :disabled="busy"
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
                  :disabled="busy"
                  :placeholder="
                    newProblem ? '说说你的想法，或现在困扰你的事…' : '接着说…'
                  "
                />
              </label>
              <div class="composer-actions">
                <details class="model-picker">
                  <summary>
                    {{ currentModel }}<ChevronDown :size="12" />
                  </summary>
                  <div class="model-picker-panel">
                    <strong>本次对话模型</strong
                    ><label
                      >提供商<select v-model="conversationProvider">
                        <option value="deepseek">DeepSeek</option>
                        <option value="openai">OpenAI</option>
                      </select></label
                    ><label
                      >模型<input
                        v-model="conversationModel"
                        placeholder="留空使用账号默认模型" /></label
                    ><button type="button" @click="useConversationModel">
                      应用</button
                    ><button
                      type="button"
                      class="text-button"
                      @click="sidebar?.openSettings()"
                    >
                      管理模型配置
                    </button>
                  </div>
                </details>
                <button
                  type="button"
                  class="icon-button attachment-add"
                  aria-label="添加图片或文件"
                  title="添加图片或文件 · 最多4个"
                  :disabled="busy || chat.preparing.value"
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
                    :disabled="busy"
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
                  :aria-label="pendingChat ? '加入待发送队列' : '发送'"
                  :title="pendingChat ? '加入本机待发送队列' : '发送'"
                  :disabled="
                    busy ||
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
          :can-use-wiki="account?.role === 'admin'"
          :topics="topics"
          :problems="problems"
          :context-id="knowledgeProblemId"
          :busy="busy"
          :can-navigate="!busy"
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
