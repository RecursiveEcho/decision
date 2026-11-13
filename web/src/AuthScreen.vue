<script setup lang="ts">
import { computed, onUnmounted, ref } from "vue";
import { ArrowRight, Eye, EyeOff, LoaderCircle } from "@lucide/vue";
import { ApiError, request } from "./api";
import type { AuthSession } from "./types";
import "./auth.css";

const emit = defineEmits<{ authenticated: [session: AuthSession] }>();
const mode = ref<"login" | "register" | "admin">("login");
const username = ref("");
const password = ref("");
const confirmPassword = ref("");
const accessCode = ref("");
const visible = ref(false);
const busy = ref(false);
const error = ref("");
const heading = computed(() =>
  mode.value === "register"
    ? "创建你的空间"
    : mode.value === "admin"
      ? "管理员登录"
      : "欢迎回来",
);
const button = computed(() =>
  mode.value === "register" ? "创建账号" : "登录",
);
let generation = 0;
let pending: { path: string; body: string; key: string } | null = null;
function switchMode(next: typeof mode.value) {
  if (busy.value) return;
  mode.value = next;
  password.value = "";
  confirmPassword.value = "";
  accessCode.value = "";
  visible.value = false;
  error.value = "";
  pending = null;
}
async function submit() {
  if (busy.value) return;
  error.value = "";
  if (mode.value === "register" && password.value !== confirmPassword.value) {
    error.value = "两次密码不一致，请再检查一下。";
    return;
  }
  const path = mode.value === "register" ? "/auth/register" : "/auth/login";
  const body =
    mode.value === "admin"
      ? { accessCode: accessCode.value }
      : {
          username: username.value.trim().toLowerCase(),
          password: password.value,
        };
  const serialized = JSON.stringify(body);
  if (!pending || pending.path !== path || pending.body !== serialized)
    pending = { path, body: serialized, key: crypto.randomUUID() };
  const current = ++generation;
  busy.value = true;
  try {
    const session = await request<AuthSession>(path, "POST", body, pending.key);
    if (current !== generation) return;
    password.value = "";
    confirmPassword.value = "";
    accessCode.value = "";
    pending = null;
    emit("authenticated", session);
  } catch (failure) {
    if (current !== generation) return;
    if (failure instanceof ApiError && failure.status && failure.status < 500)
      pending = null;
    error.value =
      failure instanceof Error ? failure.message : "暂时无法连接，请稍后重试。";
  } finally {
    if (current === generation) busy.value = false;
  }
}
onUnmounted(() => {
  generation++;
  pending = null;
  password.value = "";
  accessCode.value = "";
  confirmPassword.value = "";
});
</script>
<template>
  <main class="auth-screen">
    <div class="auth-brand">
      <span class="brand-mark" aria-hidden="true">D</span><span>Decision</span>
    </div>
    <section class="auth-card">
      <h1>{{ heading }}</h1>
      <p class="auth-intro">
        {{
          mode === "register"
            ? "和 AI 聊清楚，慢慢积累自己的判断。"
            : "接着聊聊值得想清楚的事。"
        }}
      </p>
      <form @submit.prevent="submit">
        <template v-if="mode !== 'admin'">
          <label
            >用户名<input
              v-model.trim="username"
              required
              minlength="3"
              maxlength="32"
              pattern="[A-Za-z0-9_][A-Za-z0-9_.\-]{2,31}"
              autocomplete="username"
              autocapitalize="none"
              spellcheck="false"
              :disabled="busy"
              :aria-describedby="
                mode === 'register' ? 'username-hint' : undefined
              "
          /></label>
          <p v-if="mode === 'register'" id="username-hint" class="auth-hint">
            3–32 位字母、数字、下划线，可含点和短横线。
          </p>
          <label
            >密码
            <div class="auth-password">
              <input
                v-model="password"
                :type="visible ? 'text' : 'password'"
                required
                :minlength="mode === 'register' ? 12 : 1"
                maxlength="128"
                :autocomplete="
                  mode === 'register' ? 'new-password' : 'current-password'
                "
                :disabled="busy"
                :aria-describedby="
                  mode === 'register' ? 'password-hint' : undefined
                "
              /><button
                type="button"
                class="icon-button"
                :aria-label="visible ? '隐藏密码' : '显示密码'"
                :disabled="busy"
                @click="visible = !visible"
              >
                <EyeOff v-if="visible" :size="17" /><Eye v-else :size="17" />
              </button></div
          ></label>
          <p v-if="mode === 'register'" id="password-hint" class="auth-hint">
            12–128 位，建议使用一段容易记住的长密码。
          </p>
          <label v-if="mode === 'register'"
            >确认密码<input
              v-model="confirmPassword"
              :type="visible ? 'text' : 'password'"
              required
              minlength="12"
              maxlength="128"
              autocomplete="new-password"
              :disabled="busy"
          /></label>
        </template>
        <label v-else
          >管理员访问码<input
            v-model="accessCode"
            type="password"
            required
            autocomplete="current-password"
            :disabled="busy"
        /></label>
        <p v-if="error" class="auth-error" role="alert">{{ error }}</p>
        <button class="auth-submit" :disabled="busy">
          <LoaderCircle v-if="busy" :size="16" class="spinning" /><template
            v-else
            >{{ button }}<ArrowRight :size="16"
          /></template>
        </button>
      </form>
      <p v-if="mode === 'admin'" class="auth-hint auth-admin-hint">
        旧账号也可用用户名 owner 和原访问码登录。
      </p>
      <p class="auth-switch">
        {{ mode === "register" ? "已有账号？" : "还没有账号？"
        }}<button
          :disabled="busy"
          @click="switchMode(mode === 'register' ? 'login' : 'register')"
        >
          {{ mode === "register" ? "登录" : "创建账号" }}
        </button>
      </p>
    </section>
    <button
      v-if="mode !== 'admin'"
      class="auth-admin"
      :disabled="busy"
      @click="switchMode('admin')"
    >
      原管理员使用访问码登录
    </button>
    <button
      v-else
      class="auth-admin"
      :disabled="busy"
      @click="switchMode('login')"
    >
      返回用户名登录
    </button>
  </main>
</template>
