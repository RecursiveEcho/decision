import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { logout, list, request, setSession, token, waitJob } from "../src/api";
let ended: ReturnType<typeof vi.fn>;
beforeEach(() => {
  vi.useFakeTimers();
  const store = new Map();
  ended = vi.fn();
  vi.stubGlobal("sessionStorage", {
    getItem: (k: string) => store.get(k),
    setItem: (k: string, v: string) => store.set(k, v),
    removeItem: (k: string) => store.delete(k),
  });
  vi.stubGlobal("window", { dispatchEvent: ended });
});
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});
const json = (data: unknown, status = 200) =>
  new Response(JSON.stringify(data), {
    status,
    headers: { "Content-Type": "application/json" },
  });
describe("API boundary", () => {
  it("aborts in-flight writes on logout without retrying", async () => {
    const fetch = vi.fn(
      (_url, options) =>
        new Promise((_resolve, reject) => {
          options.signal.addEventListener("abort", () =>
            reject(new Error("aborted")),
          );
        }),
    );
    vi.stubGlobal("fetch", fetch);
    const pending = request("/problems", "POST", { title: "synthetic" });
    const rejection = expect(pending).rejects.toMatchObject({ status: 499 });
    logout();
    await rejection;
    await vi.runAllTimersAsync();
    expect(fetch).toHaveBeenCalledOnce();
  });
  it("does not retry an old request after logout during backoff", async () => {
    const fetch = vi.fn().mockRejectedValue(new Error("offline"));
    vi.stubGlobal("fetch", fetch);
    const pending = request("/problems", "POST", { title: "synthetic" });
    const rejection = expect(pending).rejects.toMatchObject({ status: 499 });
    await vi.advanceTimersByTimeAsync(10);
    logout();
    await vi.runAllTimersAsync();
    await rejection;
    expect(fetch).toHaveBeenCalledOnce();
  });
  it("reuses POST key after network failure", async () => {
    const fetch = vi
      .fn()
      .mockRejectedValueOnce(new Error("offline"))
      .mockResolvedValueOnce(json({ id: "ok" }));
    vi.stubGlobal("fetch", fetch);
    const p = request("/problems", "POST", { title: "fictional" });
    await vi.runAllTimersAsync();
    expect(await p).toEqual({ id: "ok" });
    expect(fetch.mock.calls[0][1].headers["Idempotency-Key"]).toBe(
      fetch.mock.calls[1][1].headers["Idempotency-Key"],
    );
  });
  it("clears expired authentication on 401 without retries", async () => {
    setSession({
      accessToken: "test-only",
      expiresAt: new Date(Date.now() + 60000).toISOString(),
    });
    const fetch = vi.fn().mockResolvedValue(json({}, 401));
    vi.stubGlobal("fetch", fetch);
    await expect(request("/sources")).rejects.toMatchObject({ status: 401 });
    expect(token()).toBe("");
    expect(ended).toHaveBeenCalledOnce();
    expect(fetch).toHaveBeenCalledOnce();
  });
  it("logout and a new login clear knowledge recovery metadata", () => {
    sessionStorage.setItem(
      "decision.knowledge-operations",
      "synthetic pending metadata",
    );
    logout();
    expect(
      sessionStorage.getItem("decision.knowledge-operations"),
    ).toBeUndefined();
    sessionStorage.setItem(
      "decision.knowledge-operations",
      "synthetic old metadata",
    );
    setSession({
      accessToken: "test-only",
      expiresAt: new Date(Date.now() + 60000).toISOString(),
    });
    expect(
      sessionStorage.getItem("decision.knowledge-operations"),
    ).toBeUndefined();
  });
  it("does not replay POST on server failure", async () => {
    const fetch = vi
      .fn()
      .mockResolvedValue(json({ error: { message: "provider failed" } }, 502));
    vi.stubGlobal("fetch", fetch);
    await expect(request("/sources", "POST", {})).rejects.toThrow(
      "provider failed",
    );
    expect(fetch).toHaveBeenCalledOnce();
  });
  it("follows encoded pagination and rejects looping cursors", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValueOnce(json({ items: [1], nextCursor: "a+b" }))
        .mockResolvedValueOnce(json({ items: [2], nextCursor: "a+b" })),
    );
    await expect(list("/sources?problemId=one")).rejects.toThrow("重复游标");
    expect(vi.mocked(fetch).mock.calls[1][0]).toContain("cursor=a%2Bb");
  });
  it("shows failed jobs instead of success", async () => {
    await expect(
      waitJob(
        {
          id: "j",
          mode: "real",
          status: "failed",
          error: { message: "model unavailable" },
        },
        () => true,
        () => {},
      ),
    ).rejects.toThrow("model unavailable");
  });
  it("stops polling when session or problem changes", async () => {
    let active = true;
    const fetch = vi.fn();
    vi.stubGlobal("fetch", fetch);
    const p = waitJob(
      { id: "j", mode: "real", status: "queued", error: null },
      () => active,
      () => {},
    );
    active = false;
    await vi.runAllTimersAsync();
    await p;
    expect(fetch).not.toHaveBeenCalled();
  });
});
