import { ref } from "vue";

export const colors = [
  { id: "graphite", name: "石墨", light: "#35383d", dark: "#d4d4d8" },
  { id: "green", name: "松绿", light: "#28665a", dark: "#81c9b2" },
  { id: "blue", name: "海蓝", light: "#315eaf", dark: "#91b7ff" },
  { id: "violet", name: "暮紫", light: "#7950ad", dark: "#c5a4f0" },
  { id: "rose", name: "玫瑰", light: "#aa4868", dark: "#efa0bc" },
  { id: "amber", name: "琥珀", light: "#8b610f", dark: "#e8c078" },
] as const;
export type ColorMode = "system" | "light" | "dark";
const key = "decision.appearance";
export const mode = ref<ColorMode>("system");
export const accent = ref("graphite");
export const appearanceError = ref("");
const media = window.matchMedia("(prefers-color-scheme: dark)");
function apply() {
  const dark =
    mode.value === "dark" || (mode.value === "system" && media.matches);
  document.documentElement.dataset.theme = dark ? "dark" : "light";
  const color = colors.find((c) => c.id === accent.value) || colors[0];
  document.documentElement.style.setProperty(
    "--brand",
    dark ? color.dark : color.light,
  );
}
export function setAppearance(nextMode: ColorMode, nextAccent: string) {
  mode.value = nextMode;
  accent.value = colors.some((c) => c.id === nextAccent)
    ? nextAccent
    : "graphite";
  apply();
  try {
    localStorage.setItem(
      key,
      JSON.stringify({ mode: mode.value, accent: accent.value }),
    );
    appearanceError.value = "";
  } catch {
    appearanceError.value =
      "外观已应用，浏览器未能保存。下次打开时需重新选择。";
  }
}
export function initializeAppearance() {
  try {
    const saved = JSON.parse(localStorage.getItem(key) || "null");
    if (["system", "light", "dark"].includes(saved?.mode))
      mode.value = saved.mode;
    if (colors.some((c) => c.id === saved?.accent)) accent.value = saved.accent;
  } catch {
    /* Unavailable or malformed local preferences use defaults. */
  }
  apply();
  media.addEventListener("change", apply);
  window.addEventListener("storage", (event) => {
    if (event.key !== key) return;
    try {
      const value = JSON.parse(event.newValue || "null");
      mode.value = ["system", "light", "dark"].includes(value?.mode)
        ? value.mode
        : "system";
      accent.value = colors.some((c) => c.id === value?.accent)
        ? value.accent
        : "graphite";
      apply();
    } catch {
      /* Ignore an invalid preference from another window. */
    }
  });
}
