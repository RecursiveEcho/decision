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
import DeviceRegistration from "./DeviceRegistration.vue";
import type {
  Decision,
  Device,
  Discussion,
  Evidence,
  Job,
  Memory,
  Message,
  Problem,
  Source,
  Status,
  Topic,
} from "./types";
const logged = ref(!!token()),
  accessCode = ref(""),
  busy = ref(false),
  error = ref(""),
  note = ref(""),
  mock = ref(false);
const tabs = ["讨论", "资料与主题", "记忆", "议题", "决定与复盘", "服务与观察"];
const tab = ref("讨论"),
  problems = ref<Problem[]>([]),
  selected = ref("");
const problem = computed(() =>
  problems.value.find((p) => p.id === selected.value),
);
const messages = ref<Message[]>([]),
  sources = ref<Source[]>([]),
  memories = ref<Memory[]>([]),
  topics = ref<Topic[]>([]),
  discussions = ref<Discussion[]>([]),
  decisions = ref<Decision[]>([]),
  devices = ref<Device[]>([]),
  status = ref<Status | null>(null);
const sourceDetail = ref<Source | null>(null),
  newProblem = ref(false),
  title = ref(""),
  goal = ref(""),
  constraints = ref(""),
  unknowns = ref(""),
  content = ref(""),
  sourceTitle = ref(""),
  sourceText = ref(""),
  sourceUrl = ref(""),
  chosenSources = ref<string[]>([]),
  memoryText = ref(""),
  choice = ref(""),
  rationale = ref(""),
  outcome = ref(""),
  reviewAt = ref("");
const reviewDraft = ref<
  Record<string, { outcome: string; reflection: string }>
>({});
const newProblemForm = ref<HTMLFormElement | null>(null);
const newProblemTitle = ref<HTMLInputElement | null>(null);
const errorBanner = ref<HTMLDivElement | null>(null);
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
  },
});
async function openNewProblem() {
  if (busy.value) return;
  newProblem.value = true;
  editingGoal.value = false;
  sourceDetail.value = null;
  error.value = "";
  note.value = "";
  await nextTick();
  newProblemForm.value?.scrollIntoView({ block: "start", behavior: "auto" });
  newProblemTitle.value?.focus({ preventScroll: true });
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
    if (newProblem.value && error.value) {
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
  title.value = "";
  goal.value = "";
  constraints.value = "";
  unknowns.value = "";
  clearData();
  devices.value = [];
  status.value = null;
  sourceDetail.value = null;
  content.value = "";
  chosenSources.value = [];
  sourceText.value = "";
  sourceUrl.value = "";
  memoryText.value = "";
  choice.value = "";
  rationale.value = "";
  outcome.value = "";
  reviewDraft.value = {};
  mock.value = false;
}
function clearData() {
  messages.value = [];
  sources.value = [];
  memories.value = [];
  topics.value = [];
  discussions.value = [];
  decisions.value = [];
}
async function loadProblems() {
  problems.value = await list<Problem>("/problems");
  if (!problems.value.some((p) => p.id === selected.value))
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
  if (section === "服务与观察") {
    const [s, d] = await Promise.all([
      request<Status>("/status"),
      list<Device>("/devices"),
    ]);
    assign(status, s);
    assign(devices, d);
    return;
  }
  if (!id) return;
  const q = `?problemId=${encodeURIComponent(id)}`;
  if (section === "讨论") {
    const [m, s] = await Promise.all([
      list<Message>(`/problems/${id}/messages`),
      list<Source>("/sources" + q),
    ]);
    assign(messages, m);
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
  if (section === "记忆") assign(memories, await list<Memory>("/memories" + q));
  if (section === "议题")
    assign(discussions, await list<Discussion>("/discussions" + q));
  if (section === "决定与复盘")
    assign(decisions, await list<Decision>("/decisions" + q));
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
    await loadProblems();
    await load();
  });
}
async function createProblem() {
  await act(async () => {
    const p = await request<Problem>("/problems", "POST", {
      title: title.value,
      goal: goal.value,
      constraints: constraints.value.split("\n").filter(Boolean),
      unknowns: unknowns.value.split("\n").filter(Boolean),
    });
    problems.value.unshift(p);
    selected.value = p.id;
    newProblem.value = false;
    title.value = "";
    goal.value = "";
    constraints.value = "";
    unknowns.value = "";
    await load();
  });
}
async function job(path: string, body: unknown) {
  const id = selected.value;
  const j = await request<Job>(path, "POST", body);
  const current = generation;
  await waitJob(
    j,
    () => logged.value && selected.value === id && generation === current,
    (j) => {
      note.value =
        j.status === "succeeded" ? "处理完成" : "正在处理，可稍后刷新查看";
      if (j.mode === "mock") mock.value = true;
    },
  );
  if (logged.value && selected.value === id) await load();
}
async function send() {
  await act(async () => {
    const text = content.value;
    await job(`/problems/${selected.value}/messages`, {
      content: text,
      sourceIds: chosenSources.value,
    });
    content.value = "";
  });
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
async function showSource(id: string) {
  await act(async () => {
    sourceDetail.value = await request<Source>(
      `/sources/${encodeURIComponent(id)}`,
    );
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
async function discussionState(d: Discussion, state: string) {
  await act(async () => {
    await request(`/discussions/${d.id}`, "PATCH", {
      status: state,
      ...(state === "snoozed"
        ? { snoozeUntil: new Date(Date.now() + 3600000).toISOString() }
        : {}),
    });
    await load();
  });
}
async function confirmDecision() {
  await act(async () => {
    await request("/decisions", "POST", {
      problemId: selected.value,
      choice: choice.value,
      rationale: rationale.value,
      expectedOutcome: outcome.value,
      evidence: [],
      ...(reviewAt.value
        ? { reviewAt: new Date(reviewAt.value).toISOString() }
        : {}),
    });
    choice.value = "";
    rationale.value = "";
    outcome.value = "";
    reviewAt.value = "";
    await load();
  });
}
async function recordReview(d: Decision) {
  await act(async () => {
    await request(`/decisions/${d.id}/reviews`, "POST", {
      ...reviewDraft.value[d.id],
      evidence: [],
    });
    delete reviewDraft.value[d.id];
    await load();
  });
}
async function setDevice(d: Device, state: string) {
  if (
    state === "running" &&
    !confirm(
      "允许此设备同步观察？仍需在电脑本地明确运行并选择无敏感页面。原始截图由本地 Pensieve 管理。",
    )
  )
    return;
  await act(async () => {
    await request(`/devices/${d.id}`, "PATCH", { desiredState: state });
    await load();
    note.value =
      state === "paused"
        ? "已请求暂停同步；底层采集状态未确认。"
        : "已允许同步，等待本地明确启动。";
  });
}
function evidenceText(e: Evidence) {
  return `${e.relation === "supports" ? "支持" : e.relation === "contradicts" ? "相反依据" : "背景"}：${e.quote}`;
}
watch([selected, tab], ([id], [old]) => {
  if (id !== old) {
    editingGoal.value = false;
    goalDraft.value = "";
    clearData();
    chosenSources.value = [];
    content.value = "";
    sourceDetail.value = null;
    sourceTitle.value = "";
    sourceText.value = "";
    sourceUrl.value = "";
    memoryText.value = "";
    choice.value = "";
    rationale.value = "";
    outcome.value = "";
    reviewAt.value = "";
    reviewDraft.value = {};
  }
  if (logged.value && !busy.value) void act(load);
});
onMounted(() => {
  window.addEventListener("session-ended", ended);
  if (logged.value)
    void act(async () => {
      await loadProblems();
      await load();
    });
});
onUnmounted(() => {
  generation++;
  window.removeEventListener("session-ended", ended);
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
  <div v-else class="shell">
    <aside>
      <div class="brand">知衡 <span>个人决策工作台</span></div>
      <label
        >当前问题<select v-model="displayedProblem" :disabled="busy">
          <option value="" disabled>
            {{ newProblem ? "新建问题（待创建）" : "选择一个问题" }}
          </option>
          <option v-for="p in problems" :key="p.id" :value="p.id">
            {{ p.title }}
          </option>
        </select></label
      ><button
        class="secondary"
        @click="openNewProblem"
        :disabled="busy"
        :aria-expanded="newProblem"
        aria-controls="new-problem-form"
      >
        ＋ 新建问题
      </button>
      <nav>
        <button
          v-for="item in tabs"
          :key="item"
          :class="{ active: !newProblem && tab === item }"
          :disabled="busy"
          @click="
            newProblem = false;
            tab = item;
          "
        >
          {{ item }}
        </button>
      </nav>
      <div class="aside-foot">建议供你判断<br />决定由你确认</div>
      <button class="secondary" @click="logout">退出登录</button>
    </aside>
    <main>
      <header>
        <div>
          <small>个人空间 / {{ newProblem ? "新建问题" : tab }}</small>
          <h1>
            {{
              newProblem ? "新建问题" : problem?.title || "从一个真实问题开始"
            }}
          </h1>
          <p v-if="!newProblem && problem">{{ problem.goal }}</p>
        </div>
        <button class="secondary" :disabled="busy" @click="act(refresh)">
          刷新
        </button>
      </header>
      <div v-if="error" ref="errorBanner" role="alert" class="error">
        {{ error }}
      </div>
      <div v-if="note" role="status" class="notice">{{ note }}</div>
      <div v-if="mock && !newProblem" class="error">
        当前包含测试替身（mock）结果，不代表真实模型验证。
      </div>
      <div v-if="busy" role="status" class="muted">正在处理…</div>
      <form
        v-if="newProblem"
        id="new-problem-form"
        ref="newProblemForm"
        class="card new-problem-form"
        aria-label="新建问题"
        @submit.prevent="createProblem"
      >
        <h2>新建问题</h2>
        <label
          >问题标题<input
            ref="newProblemTitle"
            v-model="title"
            required
            maxlength="200" /></label
        ><label>希望达到的目标<textarea v-model="goal" required /></label>
        <div class="columns">
          <label>限制条件（每行一条）<textarea v-model="constraints" /></label
          ><label>尚不清楚（每行一条）<textarea v-model="unknowns" /></label>
        </div>
        <button :disabled="busy">创建问题</button>
        <button
          type="button"
          class="secondary"
          :disabled="busy"
          @click="newProblem = false"
        >
          取消
        </button>
      </form>
      <div v-else-if="!selected && tab !== '服务与观察'" class="empty">
        <h2>你正在考虑什么？</h2>
        <p>新建问题，写下目标和还不确定的地方。</p>
      </div>
      <template v-else-if="tab === '讨论'">
        <details v-if="problem" class="card">
          <summary>目标、限制与未知</summary>
          <p>目标：{{ problem.goal }}</p>
          <p>限制：{{ problem.constraints.join("；") || "尚未填写" }}</p>
          <p>未知：{{ problem.unknowns.join("；") || "尚未填写" }}</p>
          <button
            v-if="!editingGoal"
            class="secondary"
            :disabled="busy"
            @click="openGoalEditor"
          >
            修改目标
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
        <div v-if="!messages.length" class="empty">
          <h2>先说说你的处境</h2>
          <p>可以先添加资料，再请助手比较选择与依据。</p>
        </div>
        <article
          v-for="m in messages"
          :key="m.id"
          class="card message"
          :class="m.role"
        >
          <small
            >{{ m.role === "user" ? "你" : "决策助手" }}
            <b v-if="m.mode === 'mock'">· 测试替身</b></small
          >
          <p class="pre">{{ m.content }}</p>
          <details v-if="m.evidence.length || m.uncertainties.length">
            <summary>查看依据与不确定性</summary>
            <button
              v-for="(e, i) in m.evidence"
              :key="i"
              class="evidence"
              :disabled="busy"
              @click="showSource(e.sourceId)"
            >
              {{ evidenceText(e) }}
            </button>
            <p v-for="u in m.uncertainties" :key="u">尚不确定：{{ u }}</p>
          </details>
        </article>
        <form class="card composer" @submit.prevent="send">
          <label
            >说说你的问题<textarea
              v-model="content"
              required
              placeholder="有哪些选择？你最在意什么？"
            />
          </label>
          <details v-if="sources.length">
            <summary>选择参考资料（{{ chosenSources.length }}）</summary>
            <label v-for="s in sources" :key="s.id" class="check"
              ><input type="checkbox" :value="s.id" v-model="chosenSources" />{{
                s.title
              }}</label
            >
          </details>
          <button :disabled="busy || !content.trim()">发送讨论</button>
        </form>
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
          <button class="secondary" :disabled="busy" @click="showSource(s.id)">
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
            同步 Wiki</button
          ><button
            v-for="(e, i) in t.evidence"
            :key="i"
            class="evidence"
            :disabled="busy"
            @click="showSource(e.sourceId)"
          >
            {{ evidenceText(e) }}
          </button>
        </article>
      </template>
      <template v-else-if="tab === '记忆'">
        <form
          class="card"
          @submit.prevent="
            act(async () => {
              await request('/memories', 'POST', {
                problemId: selected,
                content: memoryText,
                kind: 'user_reported',
              });
              memoryText = '';
              await load();
            })
          "
        >
          <h2>补充本人陈述</h2>
          <label
            >希望记住的事实或偏好<textarea
              v-model="memoryText"
              required
            /></label
          ><button :disabled="busy || !memoryText.trim()">保存陈述</button>
        </form>
        <p v-if="!memories.length" class="muted">暂无记忆。</p>
        <article v-for="m in memories" :key="m.id" class="card">
          <small
            >{{ label(m.kind) }} · {{ label(m.status) }} · 版本
            {{ m.revision }}</small
          >
          <p class="pre">{{ m.content }}</p>
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
            撤销
          </button>
          <button
            class="danger"
            :disabled="busy"
            @click="remove(`/memories/${m.id}`)"
          >
            删除</button
          ><button
            v-for="(e, i) in m.evidence"
            :key="i"
            class="evidence"
            :disabled="busy"
            @click="showSource(e.sourceId)"
          >
            {{ evidenceText(e) }}
          </button>
        </article>
      </template>
      <template v-else-if="tab === '议题'"
        ><p class="muted">
          值得再次想一想的事。没有议题，也可能意味着暂时无需打扰。
        </p>
        <article v-for="d in discussions" :key="d.id" class="card">
          <small>{{ label(d.status) }}</small>
          <h2>{{ d.title }}</h2>
          <p class="pre">{{ d.reason }}</p>
          <button
            v-for="(e, i) in d.evidence"
            :key="i"
            class="evidence"
            :disabled="busy"
            @click="showSource(e.sourceId)"
          >
            {{ evidenceText(e) }}</button
          ><button
            class="secondary"
            :disabled="busy"
            @click="discussionState(d, 'read')"
          >
            已读
          </button>
          <button
            class="secondary"
            :disabled="busy"
            @click="discussionState(d, 'snoozed')"
          >
            一小时后
          </button>
          <button
            class="secondary"
            :disabled="busy"
            @click="discussionState(d, 'dismissed')"
          >
            忽略
          </button>
        </article>
        <div v-if="!discussions.length" class="empty">暂无议题</div></template
      >
      <template v-else-if="tab === '决定与复盘'">
        <form class="card" @submit.prevent="confirmDecision">
          <h2>记录我的决定</h2>
          <p>只有你确认后才记录，助手的建议不会自动成为决定。</p>
          <label>我选择<input v-model="choice" required /></label
          ><label>当时的理由<textarea v-model="rationale" required /></label
          ><label>预期结果<textarea v-model="outcome" required /></label
          ><label
            >计划复盘时间（可选）<input
              v-model="reviewAt"
              type="datetime-local" /></label
          ><button :disabled="busy">确认记录决定</button>
        </form>
        <article v-for="d in decisions" :key="d.id" class="card">
          <small>{{ date(d.confirmedAt) }}</small>
          <h2>{{ d.choice }}</h2>
          <p class="pre">当时理由：{{ d.rationale }}</p>
          <p class="pre">预期结果：{{ d.expectedOutcome }}</p>
          <button
            v-for="(e, i) in d.evidence"
            :key="i"
            class="evidence"
            :disabled="busy"
            @click="showSource(e.sourceId)"
          >
            {{ evidenceText(e) }}
          </button>
          <section v-for="r in d.reviews" :key="r.id" class="review">
            <p>结果：{{ r.outcome }}</p>
            <p>反思：{{ r.reflection }}</p>
          </section>
          <button
            v-if="!reviewDraft[d.id]"
            class="secondary"
            @click="reviewDraft[d.id] = { outcome: '', reflection: '' }"
          >
            追加复盘
          </button>
          <form v-else @submit.prevent="recordReview(d)">
            <label
              >实际结果<textarea
                v-model="reviewDraft[d.id]!.outcome"
                required
              /></label
            ><label
              >反思<textarea
                v-model="reviewDraft[d.id]!.reflection"
                required
              /></label
            ><button :disabled="busy">保存复盘</button>
          </form>
        </article>
      </template>
      <template v-else-if="tab === '服务与观察'">
        <div v-if="status" class="columns">
          <article class="card">
            <h2>模型</h2>
            <p>{{ status.model.configured ? "已配置" : "未配置" }}</p>
            <p>最近调用：{{ status.model.lastCallStatus || "暂无" }}</p>
            <p>
              请求 {{ status.usage.requests }} · 输入
              {{ status.usage.inputTokens }} / 输出
              {{ status.usage.outputTokens }} tokens
            </p>
            <p>
              费用估计：{{
                status.usage.costEstimate === null
                  ? "暂无依据"
                  : `${status.usage.costEstimate} ${status.usage.currency || ""}`
              }}
            </p>
          </article>
          <article class="card">
            <h2>Wiki</h2>
            <p>
              {{ status.wiki.configured ? "已配置" : "未配置" }} ·
              {{ label(status.wiki.status) }}
            </p>
            <p>观察最近在线：{{ date(status.observer.lastSeenAt) }}</p>
          </article>
        </div>
        <h2>观察设备</h2>
        <p>
          默认关闭。网页授权同步后，仍须在本地明确启动。暂停同步不能证明底层录屏已经停止。
        </p>
        <p v-if="!devices.length" class="muted">
          没有设备，请先在下方注册，再将凭据填入本地适配器。网页不会自动开启采集。
        </p>
        <DeviceRegistration
          :disabled="busy"
          @registered="
            (device) => {
              devices = [...devices.filter((d) => d.id !== device.id), device];
            }
          "
        />
        <article v-for="d in devices" :key="d.id" class="card">
          <h3>{{ d.name }}</h3>
          <p>
            同步请求：{{ label(d.desiredState) }} · 设备报告：{{
              label(d.reportedState)
            }}
          </p>
          <p>心跳：{{ date(d.lastHeartbeatAt) }}</p>
          <p
            v-if="
              !d.lastHeartbeatAt ||
              Date.now() - Date.parse(d.lastHeartbeatAt) > 90000
            "
            class="error"
          >
            心跳已过期，设备可能离线，实际采集状态未知。
          </p>
          <p v-if="d.lastError" class="error">{{ d.lastError }}</p>
          <button :disabled="busy" @click="setDevice(d, 'running')">
            允许同步（仍需本地启动）
          </button>
          <button
            class="secondary"
            :disabled="busy"
            @click="setDevice(d, 'paused')"
          >
            请求暂停同步
          </button>
        </article>
      </template>
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
