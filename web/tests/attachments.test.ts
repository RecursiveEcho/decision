import "fake-indexeddb/auto";
import { IDBObjectStore } from "fake-indexeddb";
import { beforeEach, afterEach, expect, it, vi } from "vitest";
import {
  accountId,
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
  activateMediaSession,
} from "../src/mediaStorage";
import { prepareFile, checkFileLimits } from "../src/attachments";
import * as mediaModule from "../src/mediaStorage";
import * as attachmentsModule from "../src/attachments";

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

it("discards only the managed conversation's draft, media and thinking across reload", async () => {
  const flow = make();
  for (const id of ["target", "other", "new"]) {
    flow.setDraft(id, id);
    flow.setThinking(id, "high");
    await flow.addFiles(id, [new File([id], id + ".txt")]);
  }
  const removed = flow.files.value.target[0].id;
  flow.discardDraft("target");
  const restored = make();
  expect(Object.keys(restored.drafts.value)).toEqual(["other", "new"]);
  expect(restored.files.value.target).toBeUndefined();
  expect(restored.thinking.value.target).toBeUndefined();
  await vi.waitFor(async () => {
    await expect(getMedia(sessionId(), removed, () => true)).rejects.toThrow(
      "不可用",
    );
  });
  for (const id of ["other", "new"]) {
    const [original] = await getMedia(
      sessionId(),
      restored.files.value[id][0].id,
      () => true,
    );
    expect(await original.blob.text()).toBe(id);
  }
});

it("drops an unavailable conversation's pending media only on an exact conversation match", async () => {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(fail()));
  const flow = make();
  await flow.addFiles("target", [new File(["one"], "one.txt")]);
  await expect(flow.send("target")).rejects.toThrow();
  const snapshot = flow.pending.value!.mediaKey!;
  flow.discardMissingConversation("other");
  expect(make().pending.value?.mediaKey).toBe(snapshot);
  flow.discardMissingConversation("target");
  expect(make().pending.value).toBeNull();
  await vi.waitFor(async () => {
    await expect(getMedia(sessionId(), snapshot, () => true)).rejects.toThrow(
      "不可用",
    );
  });
});

it("an account switch revokes old original bytes and gives the next account empty drafts", async () => {
  const old = make();
  old.setDraft("new", "synthetic old account");
  await old.addFiles("new", [new File(["private synthetic bytes"], "old.txt")]);
  const owner = sessionId(),
    id = old.files.value.new[0].id;
  setSession({
    accessToken: "another-account",
    expiresAt: new Date(Date.now() + 60000).toISOString(),
  });
  const next = make();
  expect(next.drafts.value).toEqual({});
  expect(next.files.value).toEqual({});
  await expect(getMedia(owner, id, () => true)).rejects.toThrow();
});

it("cancelling a queued attachment keeps the active message bytes and its recovery snapshot", async () => {
  let finish!: (r: Response) => void;
  const fetch = vi.fn((url: string) =>
    url.endsWith("/messages")
      ? Promise.resolve(
          new Response(
            JSON.stringify({
              id: "active-job",
              status: "running",
              mode: "mock",
              error: null,
            }),
          ),
        )
      : new Promise<Response>((resolve) => {
          finish = resolve;
        }),
  );
  vi.stubGlobal("fetch", fetch);
  const flow = make();
  await flow.addFiles("p1", [new File(["active bytes"], "active.txt")]);
  const activeFile = flow.files.value.p1[0].id;
  const sending = flow.send("p1");
  await vi.waitFor(() => expect(finish).toBeTypeOf("function"));
  const activeSnapshot = flow.pendings.value.p1.mediaKey!;
  await flow.addFiles("p1", [new File(["queued bytes"], "queued.txt")]);
  const queuedFile = flow.files.value.p1[0].id;
  await flow.send("p1", [], true);
  const queued = flow.queues.value.p1[0];
  flow.cancelQueued("p1", queued.operation!.key);
  expect(flow.queues.value.p1).toHaveLength(0);
  expect((await getMedia(sessionId(), activeFile, () => true))[0].name).toBe(
    "active.txt",
  );
  expect(
    (await getMedia(sessionId(), activeSnapshot, () => true))[0].name,
  ).toBe("active.txt");
  await vi.waitFor(async () => {
    await expect(getMedia(sessionId(), queuedFile, () => true)).rejects.toThrow(
      "不可用",
    );
  });
  finish(
    new Response(
      JSON.stringify({
        items: [],
        nextCursor: 1,
        job: {
          id: "active-job",
          status: "succeeded",
          mode: "mock",
          error: null,
        },
      }),
    ),
  );
  await sending;
  expect(
    fetch.mock.calls.filter((call) => call[0].endsWith("/messages")),
  ).toHaveLength(1);
});

it("an attachment 401 preserves stable-account drafts, queued messages and local bytes for reauthentication", async () => {
  const account = {
    id: "attachment-expiry-fixture",
    username: "synthetic",
    role: "user" as const,
  };
  setSession({
    accessToken: "synthetic-a",
    expiresAt: new Date(Date.now() + 3600000).toISOString(),
    account,
  });
  const owner = accountId();
  const flow = make();
  flow.setDraft("new", "unsent draft");
  await flow.addFiles("new", [new File(["saved bytes"], "draft.txt")]);
  const fileId = flow.files.value.new[0].id;
  vi.stubGlobal(
    "fetch",
    vi
      .fn()
      .mockResolvedValueOnce(fail())
      .mockResolvedValueOnce(new Response("", { status: 401 })),
  );
  flow.setDraft("p1", "unknown write");
  await expect(flow.send("p1")).rejects.toThrow();
  flow.setDraft("p1", "queued follow-up");
  await flow.send("p1", [], true);
  await expect(attachmentBlob("remote-preview")).rejects.toThrow("登录已过期");
  expect(sessionId()).toBe("");
  setSession({
    accessToken: "synthetic-b",
    expiresAt: new Date(Date.now() + 3600000).toISOString(),
    account,
  });
  const restored = make();
  expect(restored.drafts.value.new).toBe("unsent draft");
  expect(restored.queues.value.p1[0].content).toBe("queued follow-up");
  expect(restored.pausedQueues.value.p1).toBe(true);
  expect((await getMedia(owner, fileId, () => true))[0].name).toBe("draft.txt");
  await clearMediaSession(owner);
});

it("freezes submission before delayed IDB reads and retains text, files and preferences edited meanwhile", async () => {
  const flow = make();
  flow.setDraft("p1", "submitted text");
  flow.setThinking("p1", "high");
  flow.setModel("p1", { provider: "deepseek", model: "original-model" });
  await flow.addFiles("p1", [new File(["old bytes"], "submitted.txt")]);
  const originalGet = mediaModule.getMedia;
  let release!: () => void;
  const delayed = new Promise<void>((resolve) => (release = resolve));
  const get = vi
    .spyOn(mediaModule, "getMedia")
    .mockImplementationOnce(async (...args) => {
      await delayed;
      return originalGet(...args);
    });
  const fetch = vi.fn().mockResolvedValue(
    new Response(
      JSON.stringify({
        id: "j-frozen",
        status: "succeeded",
        mode: "mock",
        error: null,
      }),
    ),
  );
  vi.stubGlobal("fetch", fetch);
  const sending = flow.send("p1");
  await vi.waitFor(() => expect(get).toHaveBeenCalled());
  flow.setDraft("p1", "new draft during submit");
  flow.setThinking("p1", "off");
  flow.setModel("p1", { provider: "openai", model: "next-model" });
  await flow.addFiles("p1", [new File(["new bytes"], "next.txt")]);
  release();
  await sending;
  const body = JSON.parse(fetch.mock.calls[0][1].body);
  expect(body.content).toBe("submitted text");
  expect(body.thinking).toBe("high");
  expect(body.model).toBe("original-model");
  expect(body.attachments.map((item: { name: string }) => item.name)).toEqual([
    "submitted.txt",
  ]);
  expect(flow.drafts.value.p1).toBe("new draft during submit");
  expect(flow.files.value.p1.map((file) => file.name)).toEqual(["next.txt"]);
  expect(flow.thinking.value.p1).toBe("off");
  expect(flow.models.value.p1.model).toBe("next-model");
});

it("resets attachment preparation on sign-out without letting the old conversion unlock a new one", async () => {
  const originalPrepare = attachmentsModule.prepareFile;
  let finishOld!: () => void, finishNew!: () => void;
  const oldGate = new Promise<void>((resolve) => (finishOld = resolve)),
    newGate = new Promise<void>((resolve) => (finishNew = resolve));
  vi.spyOn(attachmentsModule, "prepareFile")
    .mockImplementationOnce(async (file) => {
      await oldGate;
      return originalPrepare(file);
    })
    .mockImplementationOnce(async (file) => {
      await newGate;
      return originalPrepare(file);
    });
  const flow = make();
  const oldAdding = flow
    .addFiles("new", [new File(["old"], "old.txt")])
    .catch((error) => error);
  expect(flow.preparing.value).toBe(true);
  logout();
  flow.clear();
  expect(flow.preparing.value).toBe(false);
  setSession({
    accessToken: "new",
    expiresAt: new Date(Date.now() + 3600000).toISOString(),
  });
  flow.restore();
  const newAdding = flow.addFiles("new", [new File(["new"], "new.txt")]);
  expect(flow.preparing.value).toBe(true);
  finishOld();
  await oldAdding;
  expect(flow.preparing.value).toBe(true);
  finishNew();
  await newAdding;
  expect(flow.preparing.value).toBe(false);
  expect(flow.files.value.new.map((file) => file.name)).toEqual(["new.txt"]);
});

it("keeps an attachment prepared during first-message acceptance with the newly created conversation", async () => {
  let accepted!: (response: Response) => void;
  vi.stubGlobal(
    "fetch",
    vi.fn(() => new Promise<Response>((resolve) => (accepted = resolve))),
  );
  const flow = make();
  flow.setDraft("new", "first message");
  const sending = flow.send(null);
  await vi.waitFor(() => expect(accepted).toBeTypeOf("function"));
  const originalPrepare = attachmentsModule.prepareFile;
  let finish!: () => void;
  const gate = new Promise<void>((resolve) => (finish = resolve));
  vi.spyOn(attachmentsModule, "prepareFile").mockImplementationOnce(
    async (file) => {
      await gate;
      return originalPrepare(file);
    },
  );
  const adding = flow.addFiles("new", [new File(["next bytes"], "next.txt")]);
  accepted(ok());
  await sending;
  finish();
  await adding;
  expect(flow.files.value.p1.map((file) => file.name)).toEqual(["next.txt"]);
  expect(flow.files.value.new).toBeUndefined();
});

it("does not let an older reactivation undo a second sign-out while IDB cleanup is pending", async () => {
  const owner = "rapid-relogin-fixture",
    file = await prepareFile(new File(["synthetic"], "fixture.txt"));
  await putMedia(owner, file.id, [file], Date.now() + 3600000, () => true);
  const first = clearMediaSession(owner);
  activateMediaSession(owner);
  const second = clearMediaSession(owner);
  await Promise.all([first, second]);
  await expect(
    putMedia(owner, file.id, [file], Date.now() + 3600000, () => true),
  ).rejects.toThrow("会话已结束");
  activateMediaSession(owner);
  await putMedia(owner, file.id, [file], Date.now() + 3600000, () => true);
  expect((await getMedia(owner, file.id, () => true))[0].name).toBe(
    "fixture.txt",
  );
  await clearMediaSession(owner);
});
