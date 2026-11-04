import "fake-indexeddb/auto";
import { IDBObjectStore } from "fake-indexeddb";
import { beforeEach, afterEach, expect, it, vi } from "vitest";
import {
  setSession,
  sessionId,
  sessionExpiry,
  logout,
  attachmentBlob,
} from "../src/api";
import { useChatFlow } from "../src/chatFlow";
import { CHAT_STORAGE } from "../src/chatStorage";
import {
  putMedia,
  getMedia,
  deleteMedia,
  clearMediaSession,
} from "../src/mediaStorage";
import { prepareFile, checkFileLimits } from "../src/attachments";

const ok = () =>
  new Response(
    JSON.stringify({
      problem: {
        id: "p1",
        title: "合成",
        goal: "",
        constraints: [],
        unknowns: [],
      },
      job: { id: "j1", status: "succeeded", mode: "mock", error: null },
    }),
    { status: 202 },
  );
const fail = (status = 502) =>
  new Response(
    JSON.stringify({ error: { message: "合成失败", code: "TEST" } }),
    { status },
  );
const make = () => {
  const flow = useChatFlow({ accepted: vi.fn(), refresh: async () => {} });
  flow.restore();
  return flow;
};
class Reader {
  result = "";
  onload?: () => void;
  onerror?: () => void;
  readAsDataURL(blob: Blob) {
    void blob.arrayBuffer().then((bytes) => {
      this.result = `data:${blob.type};base64,${Buffer.from(bytes).toString("base64")}`;
      this.onload?.();
    });
  }
}
beforeEach(() => {
  const store = new Map<string, string>();
  vi.stubGlobal("sessionStorage", {
    getItem: (key: string) => store.get(key) || null,
    setItem: (key: string, value: string) => store.set(key, value),
    removeItem: (key: string) => store.delete(key),
  });
  vi.stubGlobal("window", { dispatchEvent: vi.fn() });
  vi.stubGlobal("FileReader", Reader);
  setSession({
    accessToken: "fixture",
    expiresAt: new Date(Date.now() + 3600000).toISOString(),
  });
});
afterEach(async () => {
  await clearMediaSession(sessionId());
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

it("freezes file order, bytes and thinking before sending; reload reuses exact key and body", async () => {
  const fetch = vi
    .fn()
    .mockResolvedValueOnce(fail())
    .mockResolvedValueOnce(ok());
  vi.stubGlobal("fetch", fetch);
  const flow = make();
  await flow.addFiles("new", [
    new File(["合成原文"], "notes.md"),
    new File(["a,b\n1,2"], "rows.csv"),
  ]);
  flow.setThinking("new", "max");
  await expect(flow.send(null)).rejects.toThrow("合成失败");
  const original = fetch.mock.calls[0][1];
  const body = JSON.parse(original.body);
  expect(body.content).toBe("");
  expect(body.thinking).toBe("max");
  expect(body.attachments.map((file: { name: string }) => file.name)).toEqual([
    "notes.md",
    "rows.csv",
  ]);
  expect(sessionStorage.getItem(CHAT_STORAGE)).not.toContain("base64");
  const snapshot = flow.pending.value!.mediaKey!;
  flow.dispose();
  const restored = make();
  await restored.resume();
  expect(fetch.mock.calls[1][1].body).toBe(original.body);
  expect(fetch.mock.calls[1][1].headers["Idempotency-Key"]).toBe(
    original.headers["Idempotency-Key"],
  );
  expect(restored.thinking.value.p1).toBe("max");
  await vi.waitFor(async () => {
    await expect(getMedia(sessionId(), snapshot, () => true)).rejects.toThrow(
      "不可用",
    );
  });
});

it("missing pending bytes never degrades to a text request and has an explicit dismiss path", async () => {
  const fetch = vi.fn().mockResolvedValue(fail());
  vi.stubGlobal("fetch", fetch);
  const flow = make();
  await flow.addFiles("new", [new File(["original"], "note.txt")]);
  await expect(flow.send(null)).rejects.toThrow();
  await deleteMedia(sessionId(), [flow.pending.value!.mediaKey!], () => true);
  const restored = make();
  await expect(restored.resume()).rejects.toThrow("不可用");
  expect(fetch).toHaveBeenCalledTimes(1);
  expect(restored.pending.value?.mediaUnavailable).toBe(true);
  restored.leaveMissingMedia();
  expect(restored.pending.value).toBeNull();
  expect(restored.drafts.value.new).toBe("");
});

it("transaction failure keeps the selected files and prevents the first POST", async () => {
  const fetch = vi.fn();
  vi.stubGlobal("fetch", fetch);
  const flow = make();
  await flow.addFiles("new", [new File(["synthetic"], "note.txt")]);
  vi.spyOn(IDBObjectStore.prototype, "put").mockImplementation(() => {
    throw new DOMException("quota", "QuotaExceededError");
  });
  await expect(flow.send(null)).rejects.toThrow("暂存");
  expect(fetch).not.toHaveBeenCalled();
  expect(flow.files.value.new).toHaveLength(1);
  expect(flow.pending.value).toBeNull();
});

it("context conflict restores files and thinking without silently resending", async () => {
  const fetch = vi.fn().mockResolvedValue(fail(409));
  vi.stubGlobal("fetch", fetch);
  const flow = make();
  await flow.addFiles("new", [new File(["synthetic"], "note.txt")]);
  flow.setThinking("new", "high");
  const originalId = flow.files.value.new[0].id;
  await expect(flow.send(null)).rejects.toThrow();
  const restored = make();
  restored.editOriginal();
  expect(restored.files.value.new[0].id).toBe(originalId);
  expect(restored.thinking.value.new).toBe("high");
  expect(
    await (await getMedia(sessionId(), originalId, () => true))[0].blob.text(),
  ).toBe("synthetic");
  expect(fetch).toHaveBeenCalledOnce();
});

it("removed files are reclaimed and logout isolates another valid session", async () => {
  const flow = make();
  await flow.addFiles("new", [new File(["synthetic"], "note.txt")]);
  const owner = sessionId(),
    id = flow.files.value.new[0].id;
  const other = await prepareFile(new File(["other"], "other.txt"));
  await putMedia("other-owner", other.id, [other], sessionExpiry(), () => true);
  flow.removeFile("new", id);
  await vi.waitFor(async () => {
    await expect(getMedia(owner, id, () => true)).rejects.toThrow("不可用");
  });
  await clearMediaSession(owner);
  expect(
    await (await getMedia("other-owner", other.id, () => true))[0].blob.text(),
  ).toBe("other");
  await expect(
    putMedia(owner, "late", [other], sessionExpiry(), () => true),
  ).rejects.toThrow("会话已结束");
  await clearMediaSession("other-owner");
});

it("logout during file encoding cannot write into the next login", async () => {
  let finish!: () => void;
  vi.stubGlobal(
    "FileReader",
    class extends Reader {
      readAsDataURL(blob: Blob) {
        finish = () => super.readAsDataURL(blob);
      }
    },
  );
  const fetch = vi.fn();
  vi.stubGlobal("fetch", fetch);
  const flow = make();
  await flow.addFiles("new", [new File(["private fixture"], "note.txt")]);
  const sending = flow.send(null);
  await vi.waitFor(() => expect(finish).toBeTypeOf("function"));
  logout();
  flow.clear();
  setSession({
    accessToken: "next",
    expiresAt: new Date(Date.now() + 3600000).toISOString(),
  });
  finish();
  await sending;
  expect(fetch).not.toHaveBeenCalled();
  expect(make().pending.value).toBeNull();
  expect(sessionStorage.getItem(CHAT_STORAGE)).toBeNull();
});

it("late attachment 401 cannot log out a newer session", async () => {
  let finish!: (r: Response) => void;
  vi.stubGlobal(
    "fetch",
    vi.fn(
      () =>
        new Promise<Response>((resolve) => {
          finish = resolve;
        }),
    ),
  );
  const reading = attachmentBlob("image-one");
  logout();
  setSession({
    accessToken: "next",
    expiresAt: new Date(Date.now() + 3600000).toISOString(),
  });
  const next = sessionId();
  finish(new Response("", { status: 401 }));
  await expect(reading).rejects.toThrow("会话已结束");
  expect(sessionId()).toBe(next);
});

it("rejects unsupported, empty, over-count and over-size files before upload", async () => {
  await expect(prepareFile(new File(["x"], "app.exe"))).rejects.toThrow("支持");
  await expect(prepareFile(new File([], "empty.txt"))).rejects.toThrow(
    "空文件",
  );
  expect(() =>
    checkFileLimits(Array.from({ length: 5 }, () => ({ size: 1 }))),
  ).toThrow("4 个");
  expect(() => checkFileLimits([{ size: 8 * 1024 * 1024 + 1 }])).toThrow(
    "8 MiB",
  );
  expect(() =>
    checkFileLimits(
      Array.from({ length: 3 }, () => ({ size: 6 * 1024 * 1024 })),
    ),
  ).toThrow("16 MiB");
});
