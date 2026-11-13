<script setup lang="ts">
import { onUnmounted, ref, watch } from "vue";
import { FileText, X, Download, Image as ImageIcon } from "@lucide/vue";
import { attachmentBlob, sessionId, accountId } from "./api";
import { getMedia, type LocalFile } from "./mediaStorage";
import { fileSize } from "./attachments";
import type { Attachment } from "./types";
const props = defineProps<{
  items: (LocalFile | Attachment)[];
  local?: boolean;
  removable?: boolean;
  disabled?: boolean;
}>();
const emit = defineEmits<{ remove: [id: string] }>();
const urls = ref<Record<string, string>>({});
const errors = ref<Record<string, string>>({});
const preview = ref<HTMLDialogElement | null>(null);
const selected = ref<{ name: string; url: string } | null>(null);
let generation = 0;
const activeOwner = sessionId();
const mediaOwner = accountId();
async function blobFor(item: LocalFile | Attachment) {
  if (props.local)
    return (
      await getMedia(mediaOwner, item.id, () => sessionId() === activeOwner)
    )[0]!.blob;
  return attachmentBlob(item.id);
}
async function load(item: LocalFile | Attachment, current = generation) {
  if (urls.value[item.id]) return urls.value[item.id];
  try {
    const blob = await blobFor(item);
    if (current !== generation || sessionId() !== activeOwner) return;
    if (urls.value[item.id]) return urls.value[item.id];
    const url = URL.createObjectURL(blob);
    urls.value[item.id] = url;
    delete errors.value[item.id];
    return url;
  } catch (error) {
    if (current === generation && sessionId() === activeOwner)
      errors.value[item.id] =
        error instanceof Error ? error.message : "附件暂时无法读取";
  }
}
async function open(item: LocalFile | Attachment) {
  const current = generation;
  const url = await load(item, current);
  if (!url || current !== generation || sessionId() !== activeOwner) return;
  if (item.kind === "image") {
    selected.value = { name: item.name, url };
    preview.value?.showModal();
  } else {
    const link = document.createElement("a");
    link.href = url;
    link.download = item.name;
    link.click();
  }
}
function clearUrls() {
  for (const url of Object.values(urls.value)) URL.revokeObjectURL(url);
  urls.value = {};
  preview.value?.close();
  selected.value = null;
}
watch(
  () =>
    props.items
      .map((item) => `${item.id}:${"status" in item ? item.status : "local"}`)
      .join("|"),
  () => {
    generation++;
    clearUrls();
    errors.value = {};
    for (const item of props.items) if (item.kind === "image") void load(item);
  },
  { immediate: true },
);
onUnmounted(() => {
  generation++;
  clearUrls();
});
</script>

<template>
  <ul v-if="items.length" class="attachment-list" aria-label="附件">
    <li
      v-for="item in items"
      :key="item.id"
      class="attachment-item"
      :class="{ 'image-attachment': item.kind === 'image' }"
    >
      <button
        type="button"
        class="attachment-open"
        :disabled="disabled"
        :aria-label="`${item.kind === 'image' ? '预览' : '下载'} ${item.name}`"
        @click="open(item)"
      >
        <img
          v-if="item.kind === 'image' && urls[item.id]"
          :src="urls[item.id]"
          :alt="item.name"
        />
        <ImageIcon v-else-if="item.kind === 'image'" :size="24" /><FileText
          v-else
          :size="22"
        />
        <span class="attachment-name"
          ><span :title="item.name">{{ item.name }}</span
          ><small
            >{{ fileSize(item.size)
            }}<span
              v-if="
                !local &&
                'status' in item &&
                ['pending', 'processing'].includes(item.status)
              "
            >
              · 正在读取</span
            ></small
          ></span
        >
        <Download v-if="!local && item.kind === 'document'" :size="14" />
      </button>
      <button
        v-if="removable"
        type="button"
        class="attachment-remove icon-button"
        :disabled="disabled"
        :aria-label="`移除 ${item.name}`"
        :title="`移除 ${item.name}`"
        @click="emit('remove', item.id)"
      >
        <X :size="13" />
      </button>
      <p v-if="errors[item.id]" class="attachment-notice">
        {{ errors[item.id] }}
      </p>
      <p v-if="'error' in item && item.error" class="attachment-notice">
        {{ item.error.message }}
      </p>
      <p
        v-if="
          'coverage' in item &&
          (item.coverage?.notice || item.coverage?.truncated)
        "
        class="attachment-notice"
      >
        {{ item.coverage.notice || "此文件仅处理了部分内容。" }}
      </p>
    </li>
  </ul>
  <dialog
    ref="preview"
    class="attachment-preview"
    @click="$event.target === preview && preview?.close()"
  >
    <div v-if="selected">
      <header>
        <span>{{ selected.name }}</span
        ><button
          type="button"
          class="icon-button"
          aria-label="关闭图片预览"
          @click="preview?.close()"
        >
          <X :size="20" />
        </button>
      </header>
      <img :src="selected.url" :alt="selected.name" />
    </div>
  </dialog>
</template>
