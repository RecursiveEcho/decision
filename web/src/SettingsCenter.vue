<script setup lang="ts">
import { nextTick, onMounted, onUnmounted, ref } from "vue";
import {
  X,
  Settings2,
  Palette,
  Cpu,
  Brain,
  Keyboard,
  Info,
  Check,
  Sun,
  Moon,
  Monitor,
} from "@lucide/vue";
import { request, sessionId } from "./api";
import { preferences, savePreferences, resetPreferences } from "./preferences";
import {
  accent,
  appearanceError,
  colors,
  mode,
  setAppearance,
} from "./appearance";
const dialog = ref<HTMLDialogElement>();
const selected = ref("通用");
const sections = [
  { name: "通用", icon: Settings2 },
  { name: "外观", icon: Palette },
  { name: "模型与连接", icon: Cpu },
  { name: "记忆", icon: Brain },
  { name: "快捷键", icon: Keyboard },
  { name: "关于", icon: Info },
];
const error = ref(""),
  notice = ref(""),
  loading = ref(false),
  key = ref("");
const provider = ref<"deepseek" | "openai">("deepseek"),
  model = ref("");
const providerConfigured = ref({ deepseek: false, openai: false });
const configured = ref(false),
  available = ref<{ id: string }[]>([]),
  memory = ref(""),
  status = ref<any>(null);
let trigger: HTMLElement | undefined;
let generation = 0;
async function run(fn: (current: () => boolean) => Promise<void>) {
  if (loading.value) return;
  const own = ++generation,
    session = sessionId();
  loading.value = true;
  error.value = "";
  notice.value = "";
  try {
    await fn(
      () =>
        own === generation && session === sessionId() && !!dialog.value?.open,
    );
  } catch (e) {
    if (own === generation && session === sessionId())
      error.value = e instanceof Error ? e.message : "设置操作失败";
  } finally {
    if (own === generation) loading.value = false;
  }
}
async function load() {
  const current = sessionId();
  await run(async (valid) => {
    const value = await request<{
      provider: "deepseek" | "openai";
      model: string;
      configured: boolean;
      providers?: {
        deepseek: { configured: boolean };
        openai: { configured: boolean };
      };
    }>("/settings/model");
    if (current !== sessionId() || !valid()) return;
    providerConfigured.value = {
      deepseek: value.providers?.deepseek.configured || false,
      openai: value.providers?.openai.configured || false,
    };
    provider.value = value.provider;
    model.value = value.model;
    configured.value = value.configured;
  });
}
async function open(button?: HTMLElement) {
  trigger = button;
  const url = new URL(location.href);
  if (url.searchParams.has("settings")) {
    url.searchParams.delete("settings");
    history.replaceState(
      history.state,
      "",
      url.pathname + url.search + url.hash,
    );
  }
  if (dialog.value?.open) return;
  dialog.value?.showModal();
  await load();
}
function close() {
  key.value = "";
  generation++;
  loading.value = false;
  void nextTick(() => trigger?.isConnected && trigger.focus());
}
async function select(name: string) {
  selected.value = name;
  error.value = "";
  notice.value = "";
  if (name === "记忆")
    await run(async (valid) => {
      const value = await request<{ notice: string }>("/settings/memory");
      if (valid()) memory.value = value.notice;
    });
  if (name === "关于")
    await run(async (valid) => {
      const [value, health] = await Promise.all([
        request("/status"),
        request("/health"),
      ]);
      if (valid()) status.value = { health, services: value };
    });
}
function saveLocal() {
  try {
    savePreferences();
    notice.value = "已保存";
    error.value = "";
  } catch {
    error.value = "本机设置未能保存，请检查存储空间。";
  }
}
async function saveModel() {
  await run(async (valid) => {
    const value = await request<{ configured: boolean }>(
      "/settings/model",
      "PUT",
      {
        provider: provider.value,
        model: model.value.trim(),
        ...(key.value ? { apiKey: key.value } : {}),
      },
    );
    if (!valid()) return;
    configured.value = value.configured;
    providerConfigured.value[provider.value] = value.configured;
    key.value = "";
    notice.value = "已保存，下一次发送生效；当前任务不受影响。";
    window.dispatchEvent(new Event("decision-model-changed"));
  });
}
async function test() {
  await run(async (valid) => {
    await request("/settings/model/test", "POST", {
      provider: provider.value,
      model: model.value.trim(),
      ...(key.value ? { apiKey: key.value } : {}),
    });
    if (!valid()) return;
    notice.value = "实际推理请求成功。尚未保存的配置请点击保存。";
  });
}
async function catalog() {
  await run(async (valid) => {
    const value = await request<{ items: { id: string }[]; notice?: string }>(
      `/settings/models?provider=${provider.value}`,
    );
    if (!valid()) return;
    available.value = value.items;
    notice.value =
      value.notice || "已获取模型列表；是否可调用仍以实际测试为准。";
  });
}
async function removeKey() {
  await run(async (valid) => {
    await request(`/settings/model/key?provider=${provider.value}`, "DELETE");
    if (!valid()) return;
    providerConfigured.value[provider.value] = false;
    configured.value = false;
    key.value = "";
    notice.value = "已清除该提供商的账号密钥。";
  });
}
function external() {
  void open();
}
onMounted(() => {
  window.addEventListener("decision-open-settings", external);
  if (new URLSearchParams(location.search).has("settings")) {
    void open();
  }
});
onUnmounted(() =>
  window.removeEventListener("decision-open-settings", external),
);
defineExpose({ open });
</script>
<template>
  <dialog
    ref="dialog"
    class="product-settings"
    aria-labelledby="settings-title"
    @close="close"
    @click="
      (e) => {
        if (e.target === dialog) dialog?.close();
      }
    "
  >
    <header>
      <h2 id="settings-title">设置</h2>
      <button
        class="icon-button"
        aria-label="关闭设置"
        @click="dialog?.close()"
      >
        <X :size="20" />
      </button>
    </header>
    <div class="settings-layout">
      <nav aria-label="设置分类">
        <button
          v-for="section in sections"
          :key="section.name"
          :class="{ active: selected === section.name }"
          :disabled="loading"
          @click="select(section.name)"
        >
          <component :is="section.icon" :size="17" />{{ section.name }}
        </button>
      </nav>
      <section class="settings-content">
        <h3>{{ selected }}</h3>
        <template v-if="selected === '通用'">
          <label class="setting-row"
            ><span
              >发送消息<small
                >Shift + Enter 始终换行，中文选词不会触发发送。</small
              ></span
            ><select v-model="preferences.sendKey" @change="saveLocal">
              <option value="enter">Enter</option>
              <option value="mod-enter">⌘ / Ctrl + Enter</option>
            </select></label
          >
          <label class="setting-row"
            ><span
              >恢复上次工作<small
                >重新登录后回到上次对话；草稿保存在此设备。</small
              ></span
            ><input
              type="checkbox"
              v-model="preferences.restoreWork"
              @change="saveLocal"
          /></label>
          <div class="settings-callout">
            生成时可以切换对话。后续消息会进入本机队列；退出应用后尚未发送的消息暂停，重开后由你继续。
          </div>
          <p class="muted">
            明确退出登录会清理本机草稿和待发送队列；登录过期保留草稿，重新登录同一账号后恢复。
          </p>
        </template>
        <template v-else-if="selected === '外观'">
          <fieldset>
            <legend>主题</legend>
            <div class="setting-options">
              <button
                v-for="opt in [
                  { id: 'light', label: '浅色', icon: Sun },
                  { id: 'dark', label: '深色', icon: Moon },
                  { id: 'system', label: '跟随系统', icon: Monitor },
                ]"
                :key="opt.id"
                :aria-pressed="mode === opt.id"
                @click="
                  setAppearance(opt.id as 'light' | 'dark' | 'system', accent)
                "
              >
                <component :is="opt.icon" :size="18" />{{ opt.label }}
              </button>
            </div>
          </fieldset>
          <fieldset>
            <legend>主题颜色</legend>
            <div class="setting-colors">
              <button
                v-for="color in colors"
                :key="color.id"
                :title="color.name"
                :aria-label="color.name"
                :aria-pressed="accent === color.id"
                :style="{ background: color.light }"
                @click="setAppearance(mode, color.id)"
              >
                <Check v-if="accent === color.id" :size="16" />
              </button>
            </div>
          </fieldset>
          <label class="setting-row"
            ><span>正文字号</span
            ><select v-model.number="preferences.fontSize" @change="saveLocal">
              <option
                v-for="size in [13, 14, 15, 16, 18, 20, 22]"
                :value="size"
              >
                {{ size }} px
              </option>
            </select></label
          >
          <label class="setting-row"
            ><span>信息密度</span
            ><select v-model="preferences.density" @change="saveLocal">
              <option value="comfortable">舒适</option>
              <option value="compact">紧凑</option>
            </select></label
          >
          <label class="setting-row"
            ><span>侧栏宽度</span
            ><input
              type="range"
              min="220"
              max="380"
              step="10"
              v-model.number="preferences.sidebarWidth"
              @input="saveLocal"
          /></label>
          <button
            class="secondary"
            @click="
              () => {
                try {
                  resetPreferences();
                  setAppearance('system', 'graphite');
                } catch {
                  error = '本机设置未能保存。';
                }
              }
            "
          >
            恢复默认外观
          </button>
          <p v-if="appearanceError" role="alert">{{ appearanceError }}</p>
        </template>
        <template v-else-if="selected === '模型与连接'">
          <p class="muted">
            配置只属于当前账号。密钥加密保存在服务端，不保存在浏览器草稿中。
          </p>
          <label class="setting-field"
            >提供商<select
              v-model="provider"
              :disabled="loading"
              @change="
                () => {
                  key = '';
                  available = [];
                  configured = providerConfigured[provider];
                  notice = '切换提供商后请填写模型并保存。';
                }
              "
            >
              <option value="deepseek">DeepSeek</option>
              <option value="openai">OpenAI</option>
            </select></label
          >
          <label class="setting-field"
            >默认模型<input
              v-model="model"
              list="model-catalog"
              placeholder="输入模型 ID"
              :disabled="loading"
              autocomplete="off" /></label
          ><datalist id="model-catalog">
            <option v-for="item in available" :key="item.id" :value="item.id" />
          </datalist>
          <button class="secondary" :disabled="loading" @click="catalog">
            获取可用模型列表
          </button>
          <label class="setting-field"
            >API Key<input
              v-model="key"
              type="password"
              autocomplete="new-password"
              :placeholder="
                configured ? '已配置，留空保留原密钥' : '输入该提供商的 API Key'
              "
              :disabled="loading"
          /></label>
          <div class="settings-actions">
            <button :disabled="loading || !model.trim()" @click="saveModel">
              保存配置</button
            ><button
              class="secondary"
              :disabled="loading || !model.trim()"
              @click="test"
            >
              测试实际调用</button
            ><button
              class="text-button danger-text"
              :disabled="loading"
              @click="removeKey"
            >
              清除密钥
            </button>
          </div>
          <p class="muted">
            测试会发送一个最小推理请求，可能产生少量模型用量。测试成功不自动保存。
          </p>
        </template>
        <template v-else-if="selected === '记忆'"
          ><div class="connection-card">
            <Brain :size="24" />
            <div>
              <strong>本地记忆</strong>
              <p>现有记忆、来源与纠错功能继续可用。</p>
            </div>
            <span class="status-badge">使用中</span>
          </div>
          <div class="connection-card muted">
            <div>
              <strong>统一个人记忆中心</strong>
              <p>
                {{
                  memory || "尚未连接。本轮预留统一接口，不迁移或上传真实记忆。"
                }}
              </p>
            </div>
            <span class="status-badge">未连接</span>
          </div>
          <p class="muted">
            未来可由 Decision、Codex 和你的其他个人 Agent
            共用。此处不会自动连接工作项目。
          </p></template
        >
        <template v-else-if="selected === '快捷键'"
          ><div
            v-for="[name, shortcut] in [
              ['新对话', '⌘ / Ctrl + N'],
              ['搜索对话', '⌘ / Ctrl + K'],
              ['设置', '⌘ / Ctrl + ,'],
              ['展开 / 收起侧栏', '⌘ / Ctrl + B'],
              ['查找当前对话', '⌘ / Ctrl + F'],
              ['添加附件', '⌘ / Ctrl + Shift + U'],
              ['停止生成', '⌘ / Ctrl + .'],
              ['输入换行', 'Shift + Enter'],
            ]"
            :key="name"
            class="setting-row"
          >
            <span>{{ name }}</span
            ><kbd>{{ shortcut }}</kbd>
          </div></template
        >
        <template v-else-if="selected === '关于'"
          ><h4>Decision</h4>
          <p>个人决策助手 · 产品预览版 0.2.0</p>
          <p>支持对话、文件与图片、可追溯记忆和知识审阅。</p>
          <p class="muted">
            Decision Next
            使用独立数据目录，尚未迁入旧版历史。桌面菜单中的“运行状态”提供本机服务状态与日志入口。
          </p>
          <details v-if="status">
            <summary>服务诊断</summary>
            <pre>{{ JSON.stringify(status, null, 2) }}</pre>
          </details></template
        >
        <p v-if="loading" role="status" class="muted">正在处理…</p>
        <p v-if="error" role="alert" class="error">{{ error }}</p>
        <p v-if="notice" role="status" class="settings-success">{{ notice }}</p>
      </section>
    </div>
  </dialog>
</template>
