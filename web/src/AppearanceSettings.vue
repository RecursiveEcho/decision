<script setup lang="ts">
import { nextTick, ref } from "vue";
import { Check, Monitor, Moon, Sun, X } from "@lucide/vue";
import {
  accent,
  appearanceError,
  colors,
  mode,
  setAppearance,
} from "./appearance";
const dialog = ref<HTMLDialogElement>();
const trigger = ref<HTMLElement>();
const modes = [
  { id: "light", label: "浅色", icon: Sun },
  { id: "dark", label: "深色", icon: Moon },
  { id: "system", label: "跟随系统", icon: Monitor },
] as const;
function open(button: HTMLElement) {
  trigger.value = button;
  dialog.value?.showModal();
}
function close() {
  void nextTick(() => {
    const button = trigger.value;
    if (button?.getClientRects().length && !button.closest("[inert]"))
      button.focus();
    else
      document.querySelector<HTMLButtonElement>(".mobile-menu-toggle")?.focus();
  });
}
defineExpose({ open });
</script>
<template>
  <dialog
    ref="dialog"
    class="settings-dialog"
    aria-labelledby="settings-heading"
    @close="close"
    @click="
      (event) => {
        if (event.target === dialog) dialog?.close();
      }
    "
  >
    <header>
      <h2 id="settings-heading">设置</h2>
      <button
        class="icon-button"
        aria-label="关闭设置"
        @click="dialog?.close()"
      >
        <X :size="19" />
      </button>
    </header>
    <div class="settings-body">
      <fieldset>
        <legend>外观</legend>
        <div class="mode-options">
          <button
            v-for="option in modes"
            :key="option.id"
            :aria-pressed="mode === option.id"
            @click="setAppearance(option.id, accent)"
          >
            <component :is="option.icon" :size="22" /><span>{{
              option.label
            }}</span
            ><Check v-if="mode === option.id" :size="13" class="mode-check" />
          </button>
        </div>
      </fieldset>
      <fieldset>
        <legend>主题颜色</legend>
        <div class="accent-options">
          <button
            v-for="color in colors"
            :key="color.id"
            :aria-label="color.name"
            :aria-pressed="accent === color.id"
            @click="setAppearance(mode, color.id)"
          >
            <span
              class="color-swatch"
              :style="{ background: color.light, color: '#fff' }"
              ><Check v-if="accent === color.id" :size="16" /></span
            ><span>{{ color.name }}</span>
          </button>
        </div>
      </fieldset>
      <p v-if="appearanceError" role="alert" class="error">
        {{ appearanceError }}
      </p>
      <p class="settings-caption">外观偏好保存在这台设备的浏览器中。</p>
    </div>
    <footer>
      <button class="secondary" @click="setAppearance('system', 'graphite')">
        恢复默认</button
      ><button @click="dialog?.close()">完成</button>
    </footer>
  </dialog>
</template>
