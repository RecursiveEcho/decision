import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { logout, setSession, token } from "../src/api";
import { useChatFlow } from "../src/chatFlow";
import { CHAT_STORAGE } from "../src/chatStorage";

const problem = {
  id: "p1",
  title: "自动名称",
  goal: "",
  constraints: [],
  unknowns: [],
};
const job = (status = "succeeded", id = "j1") => ({
  id,
  status,
  mode: "mock",
  error: status === "failed" ? { message: "合成模型失败" } : null,
});
const json = (data: unknown, status = 200) =>
  new Response(JSON.stringify(data), { status });
const failure = (code = "TEMPORARY", status = 502) =>
  json({ error: { code, message: "合成失败" } }, status);
const make = (refresh = vi.fn().mockResolvedValue(undefined)) => {
  const accepted = vi.fn();
  const flow = useChatFlow({ accepted, refresh });
  flow.restore();
  return { flow, accepted, refresh };
};
beforeEach(() => {
  vi.useFakeTimers();
  const store = new Map<string, string>();
  vi.stubGlobal("sessionStorage", {
    getItem: (k: string) => store.get(k) || null,
    setItem: (k: string, v: string) => store.set(k, v),
    removeItem: (k: string) => store.delete(k),
  });
  vi.stubGlobal("window", { dispatchEvent: vi.fn() });
  setSession({
    accessToken: "test-only",
    expiresAt: new Date(Date.now() + 3600000).toISOString(),
  });
});
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe("recoverable chat writes", () => {
  it("clears saved drafts when the login expires before page restoration", () => {
    const { flow } = make();
    flow.setDraft("new", "过期会话的原话");
    vi.setSystemTime(Date.now() + 3600001);
    expect(token()).toBe("");
    expect(sessionStorage.getItem(CHAT_STORAGE)).toBeNull();
  });
  it("can leave a confirmed failed response without deleting or resending its message, but not an unknown write", async () => {
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(failure())
      .mockResolvedValueOnce(json({ problem, job: job("failed") }, 202));
    vi.stubGlobal("fetch", fetch);
    const { flow } = make();
    flow.setDraft("new", "原话还在历史里");
    await expect(flow.send(null)).rejects.toThrow();
    expect(() => flow.leaveFinished()).toThrow("未确认");
    await expect(flow.resume()).rejects.toThrow("合成模型失败");
    flow.leaveFinished();
    expect(flow.pending.value).toBeNull();
    expect(fetch).toHaveBeenCalledTimes(2);
    expect(
      fetch.mock.calls.every((c) => c[0] === "/api/v1/conversations"),
    ).toBe(true);
  });
  it("replays the exact first sentence and key after unknown HTTP outcome and reload", async () => {
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(failure())
      .mockResolvedValueOnce(json({ problem, job: job() }, 202));
    vi.stubGlobal("fetch", fetch);
    const { flow } = make();
    flow.setDraft("new", "合成困扰原话");
    await expect(flow.send(null)).rejects.toThrow("合成失败");
    const original = flow.pending.value!.operation!;
    expect(flow.pending.value!.content).toBe("合成困扰原话");
    flow.dispose();
    const restored = make().flow;
    expect(restored.pending.value!.operation!.key).toBe(original.key);
    await restored.resume();
    expect(fetch.mock.calls.map((c) => c[0])).toEqual([
      "/api/v1/conversations",
      "/api/v1/conversations",
    ]);
    expect(fetch.mock.calls[1][1].headers["Idempotency-Key"]).toBe(
      original.key,
    );
    expect(fetch.mock.calls[1][1].body).toBe(fetch.mock.calls[0][1].body);
    expect(restored.pending.value).toBeNull();
  });
  it("does not replace an in-flight first sentence on repeated send", async () => {
    let finish!: (r: Response) => void;
    const fetch = vi.fn(() => new Promise<Response>((r) => (finish = r)));
    vi.stubGlobal("fetch", fetch);
    const { flow } = make();
    flow.setDraft("new", "第一句");
    const sending = flow.send(null);
    await expect(flow.send(null)).rejects.toThrow("上一段话");
    finish(json({ problem, job: job() }, 202));
    await sending;
    expect(fetch).toHaveBeenCalledOnce();
  });
  it("persists the retry operation key and never re-creates the conversation after retry response loss", async () => {
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(json({ problem, job: job("failed") }, 202))
      .mockResolvedValueOnce(failure())
      .mockResolvedValueOnce(json(job("succeeded", "retry1"), 202));
    vi.stubGlobal("fetch", fetch);
    const { flow } = make();
    flow.setDraft("new", "保留原话");
    await expect(flow.send(null)).rejects.toThrow("合成模型失败");
    await expect(flow.resume(true)).rejects.toThrow("合成失败");
    const retryKey = flow.pending.value!.operation!.key;
    flow.dispose();
    const restored = make().flow;
    await restored.resume();
    expect(fetch.mock.calls.map((c) => c[0])).toEqual([
      "/api/v1/conversations",
      "/api/v1/jobs/j1/retry",
      "/api/v1/jobs/j1/retry",
    ]);
    expect(fetch.mock.calls[2][1].headers["Idempotency-Key"]).toBe(retryKey);
    expect(fetch.mock.calls[2][1].body).toBe("{}");
    expect(restored.pending.value).toBeNull();
  });
  it("stopping polling preserves a known job; reload resumes by GET only", async () => {
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(json({ problem, job: job("queued") }, 202))
      .mockResolvedValueOnce(json(job()));
    vi.stubGlobal("fetch", fetch);
    const { flow } = make();
    flow.setDraft("new", "等待中的原话");
    const sending = flow.send(null);
    await vi.advanceTimersByTimeAsync(1);
    flow.dispose();
    await vi.advanceTimersByTimeAsync(2100);
    await sending;
    expect(flow.pending.value?.job?.id).toBe("j1");
    const restored = make().flow;
    await restored.resume();
    expect(fetch.mock.calls[1][0]).toBe("/api/v1/jobs/j1");
    expect(fetch.mock.calls[1][1].method).toBe("GET");
    expect(restored.pending.value).toBeNull();
  });
  it("keeps succeeded state when view refresh fails and never replays the write", async () => {
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(json({ problem, job: job() }, 202))
      .mockResolvedValueOnce(json(job()));
    vi.stubGlobal("fetch", fetch);
    const refresh = vi.fn().mockRejectedValue(new Error("view offline"));
    const { flow } = make(refresh);
    flow.setDraft("new", "原话");
    await expect(flow.send(null)).rejects.toThrow("view offline");
    expect(flow.pending.value?.state).toBe("done");
    refresh.mockResolvedValue(undefined);
    await flow.resume();
    expect(fetch.mock.calls.filter((c) => c[1].method === "POST")).toHaveLength(
      1,
    );
    expect(flow.pending.value).toBeNull();
  });
  it("context conflict survives reload and restores the original sentence for explicit editing", async () => {
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(json({ problem, job: job("failed") }, 202))
      .mockResolvedValueOnce(failure("CONTEXT_CHANGED", 409));
    vi.stubGlobal("fetch", fetch);
    const { flow } = make();
    flow.setDraft("new", "需要调整的原话");
    await expect(flow.send(null)).rejects.toThrow();
    await expect(flow.resume(true)).rejects.toThrow();
    const restored = make().flow;
    expect(restored.pending.value?.state).toBe("stale");
    restored.editOriginal();
    expect(restored.drafts.value.p1).toBe("需要调整的原话");
    expect(restored.pending.value).toBeNull();
    expect(fetch).toHaveBeenCalledTimes(2);
  });
  it("storage failure prevents a POST and keeps the sentence in memory", async () => {
    const { flow } = make();
    flow.setDraft("new", "必须先暂存");
    const fetch = vi.fn();
    vi.stubGlobal("fetch", fetch);
    vi.spyOn(sessionStorage, "setItem").mockImplementation(() => {
      throw new Error("quota");
    });
    await expect(flow.send(null)).rejects.toThrow("浏览器未能暂存");
    expect(fetch).not.toHaveBeenCalled();
    expect(flow.pending.value?.content).toBe("必须先暂存");
  });
  it("logout clears recovery data and a late response cannot repopulate it", async () => {
    let finish!: (r: Response) => void;
    vi.stubGlobal(
      "fetch",
      vi.fn(() => new Promise<Response>((r) => (finish = r))),
    );
    const { flow, accepted } = make();
    flow.setDraft("new", "退出前原话");
    const sending = flow.send(null);
    logout();
    flow.clear();
    finish(json({ problem, job: job() }, 202));
    await sending;
    expect(sessionStorage.getItem(CHAT_STORAGE)).toBeNull();
    expect(accepted).not.toHaveBeenCalled();
    setSession({
      accessToken: "different-test-session",
      expiresAt: new Date(Date.now() + 3600000).toISOString(),
    });
    expect(make().flow.pending.value).toBeNull();
  });
});
