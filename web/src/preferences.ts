import { reactive } from "vue";
import { accountId } from "./api";
export interface Preferences {
  sendKey: "enter" | "mod-enter";
  fontSize: number;
  density: "comfortable" | "compact";
  restoreWork: boolean;
  sidebarWidth: number;
}
const defaults: Preferences = {
  sendKey: "enter",
  fontSize: 15,
  density: "comfortable",
  restoreWork: true,
  sidebarWidth: 260,
};
export const preferences = reactive<Preferences>({ ...defaults });
const storageKey = () => `decision.preferences:${accountId()}`;
export function loadPreferences() {
  Object.assign(preferences, defaults);
  try {
    const p = JSON.parse(localStorage.getItem(storageKey()) || "null");
    if (p) {
      preferences.sendKey = p.sendKey === "mod-enter" ? "mod-enter" : "enter";
      preferences.fontSize = Math.max(
        12,
        Math.min(22, Number(p.fontSize) || 15),
      );
      preferences.sidebarWidth = Math.max(
        220,
        Math.min(380, Number(p.sidebarWidth) || 260),
      );
      preferences.density = p.density === "compact" ? "compact" : "comfortable";
      preferences.restoreWork = p.restoreWork !== false;
    }
  } catch {}
  applyPreferences();
}
export function applyPreferences() {
  document.documentElement.style.setProperty(
    "--chat-font-size",
    `${preferences.fontSize}px`,
  );
  document.documentElement.style.setProperty(
    "--sidebar-size",
    `${preferences.sidebarWidth}px`,
  );
  document.documentElement.dataset.density = preferences.density;
}
export function savePreferences() {
  localStorage.setItem(storageKey(), JSON.stringify(preferences));
  applyPreferences();
}
export function resetPreferences() {
  Object.assign(preferences, defaults);
  savePreferences();
}
export function readWorkspace(): {
  selected?: string;
  collapsed?: boolean;
  scroll?: Record<string, number>;
} {
  try {
    return JSON.parse(
      localStorage.getItem(`decision.workspace:${accountId()}`) || "{}",
    );
  } catch {
    return {};
  }
}
export function writeWorkspace(value: ReturnType<typeof readWorkspace>) {
  if (accountId())
    localStorage.setItem(
      `decision.workspace:${accountId()}`,
      JSON.stringify(value),
    );
}
