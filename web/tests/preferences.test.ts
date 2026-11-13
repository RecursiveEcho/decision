import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { logout, setSession } from "../src/api";
import {
  loadPreferences,
  preferences,
  readWorkspace,
  savePreferences,
  writeWorkspace,
} from "../src/preferences";
const storage = () => {
  const data = new Map<string, string>();
  return {
    getItem: (k: string) => data.get(k) || null,
    setItem: (k: string, v: string) => data.set(k, v),
    removeItem: (k: string) => data.delete(k),
  };
};
const login = (id: string) =>
  setSession({
    accessToken: "synthetic",
    expiresAt: new Date(Date.now() + 3600000).toISOString(),
    account: { id, username: id, role: "user" },
  });
beforeEach(() => {
  vi.stubGlobal("localStorage", storage());
  vi.stubGlobal("sessionStorage", storage());
  vi.stubGlobal("window", { dispatchEvent: vi.fn() });
  vi.stubGlobal("document", {
    documentElement: { style: { setProperty: vi.fn() }, dataset: {} },
  });
  login("A");
  loadPreferences();
});
afterEach(() => vi.unstubAllGlobals());
it("restores input and layout preferences independently for each signed-in account", () => {
  preferences.sendKey = "mod-enter";
  preferences.fontSize = 20;
  preferences.density = "compact";
  preferences.sidebarWidth = 320;
  preferences.restoreWork = false;
  savePreferences();
  logout(false);
  login("B");
  loadPreferences();
  expect(preferences.sendKey).toBe("enter");
  expect(preferences.fontSize).toBe(15);
  expect(preferences.restoreWork).toBe(true);
  logout(false);
  login("A");
  loadPreferences();
  expect(preferences.sendKey).toBe("mod-enter");
  expect(preferences.density).toBe("compact");
  expect(preferences.sidebarWidth).toBe(320);
  expect(document.documentElement.style.setProperty).toHaveBeenCalledWith(
    "--chat-font-size",
    "20px",
  );
});
it("keeps last conversation and reading position separated across accounts", () => {
  writeWorkspace({
    selected: "private-a",
    collapsed: true,
    scroll: { "private-a": 420 },
  });
  logout(false);
  login("B");
  expect(readWorkspace()).toEqual({});
  writeWorkspace({ selected: "private-b" });
  logout(false);
  login("A");
  expect(readWorkspace()).toEqual({
    selected: "private-a",
    collapsed: true,
    scroll: { "private-a": 420 },
  });
});
it("bounds corrupt preferences and recovers from invalid stored JSON", () => {
  localStorage.setItem(
    "decision.preferences:A",
    JSON.stringify({
      fontSize: 999,
      sidebarWidth: -1,
      sendKey: "unexpected",
      density: "invalid",
    }),
  );
  loadPreferences();
  expect(preferences.fontSize).toBe(22);
  expect(preferences.sidebarWidth).toBe(220);
  expect(preferences.sendKey).toBe("enter");
  localStorage.setItem("decision.preferences:A", "{");
  loadPreferences();
  expect(preferences.fontSize).toBe(15);
  localStorage.setItem("decision.workspace:A", "{");
  expect(readWorkspace()).toEqual({});
  localStorage.setItem("decision.workspace:A", "null");
  expect(readWorkspace()).toEqual({});
});
