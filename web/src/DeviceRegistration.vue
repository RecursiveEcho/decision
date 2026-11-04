<script setup lang="ts">
import { onUnmounted, ref } from "vue";
import { request } from "./api";
import type { Device } from "./types";
defineProps<{ disabled: boolean }>();
const emit = defineEmits<{ registered: [device: Device] }>();
const name = ref(""),
  pending = ref(false),
  error = ref(""),
  message = ref("");
// Credentials exist only in this mounted component. Never emit or persist the token.
const credentials = ref<{ device: Device; deviceToken: string } | null>(null);
let alive = true;
onUnmounted(() => {
  alive = false;
  credentials.value = null;
});
async function register() {
  if (pending.value || credentials.value || !name.value.trim()) return;
  pending.value = true;
  error.value = "";
  message.value = "";
  try {
    const result = await request<{ device: Device; deviceToken: string }>(
      "/devices",
      "POST",
      { name: name.value.trim() },
    );
    if (!alive) return;
    credentials.value = result;
    emit("registered", result.device);
    name.value = "";
  } catch (e) {
    if (alive)
      error.value =
        e instanceof Error ? e.message : "注册失败，请刷新设备列表后重试。";
  } finally {
    if (alive) pending.value = false;
  }
}
async function copy(value: string) {
  try {
    await navigator.clipboard.writeText(value);
    if (alive) message.value = "已复制，请仅保存到本机适配器配置。";
  } catch {
    if (alive) message.value = "无法自动复制，请手动选择上方文本复制。";
  }
}
function close() {
  credentials.value = null;
  message.value =
    "凭据已从页面清除。关闭、离开页面或刷新后不能再次查看；遗失时请重新注册设备。";
}
</script>
<template>
  <section class="card" aria-label="注册本机设备">
    <h3>注册本机设备</h3>
    <p>
      先获取设备凭据，再填入本地观察适配器。注册不会开启观察，新设备保持暂停。
    </p>
    <form v-if="!credentials" @submit.prevent="register">
      <label
        >设备名称<input
          v-model="name"
          required
          maxlength="100"
          autocomplete="off"
          :disabled="pending || disabled"
          placeholder="例如：我的电脑"
      /></label>
      <button :disabled="pending || disabled || !name.trim()">
        {{ pending ? "正在注册…" : "注册本机设备" }}
      </button>
    </form>
    <div v-else>
      <p class="notice">
        凭据仅本次显示，请只保存到本机配置，不要发到聊天或上传仓库。这里的设备令牌不是模型密钥。
      </p>
      <label
        >设备 ID<input
          :value="credentials.device.id"
          readonly
          autocomplete="off"
          spellcheck="false"
      /></label>
      <button class="secondary" @click="copy(credentials!.device.id)">
        复制设备 ID
      </button>
      <label
        >设备令牌（仅本次显示）<textarea
          :value="credentials.deviceToken"
          readonly
          autocomplete="off"
          spellcheck="false"
        />
      </label>
      <button class="secondary" @click="copy(credentials!.deviceToken)">
        复制设备令牌
      </button>
      <p>
        请先保存凭据；关闭后无法找回，遗失时需要重新注册。仍须你明确允许同步并在本地启动，才会开始观察。
      </p>
      <button @click="close">已保存，关闭凭据</button>
    </div>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <p v-if="message" role="status">{{ message }}</p>
  </section>
</template>
