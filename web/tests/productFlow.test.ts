import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { accountId, logout, sessionId, setSession, token } from "../src/api";
import { useChatFlow } from "../src/chatFlow";
import { readChatSession } from "../src/chatStorage";
const storage = () => {
  const data = new Map<string, string>();
  return {
    getItem: (k: string) => data.get(k) || null,
    setItem: (k: string, v: string) => data.set(k, v),
    removeItem: (k: string) => data.delete(k),
    clear: () => data.clear(),
  };
};
const login = (id = "a") =>
  setSession({
    accessToken: `synthetic-${id}`,
    expiresAt: new Date(Date.now() + 3600000).toISOString(),
    account: { id, username: id, role: "user" },
  });
const json = (value: unknown) => new Response(JSON.stringify(value));
const job = (id: string, status = "running", partialContent = "") => ({
  id,
  status,
  partialContent,
  mode: "mock",
  error: status === "failed" ? { message: "synthetic failure" } : null,
});
const events = (
  id: string,
  status: string,
  partialContent: string,
  cursor: number,
) =>
  json({
    items: [{ id: cursor, type: "delta", data: { text: partialContent } }],
    nextCursor: cursor,
    job: job(id, status, partialContent),
  });
const make = () => {
  const accepted = vi.fn(),
    refresh = vi.fn().mockResolvedValue(undefined);
  const flow = useChatFlow({ accepted, refresh });
  flow.restore();
  return { flow, accepted, refresh };
};
const tick = () => vi.advanceTimersByTimeAsync(1);
beforeEach(() => {
  vi.useFakeTimers();
  vi.stubGlobal("localStorage", storage());
  vi.stubGlobal("sessionStorage", storage());
  vi.stubGlobal("window", { dispatchEvent: vi.fn() });
  login();
});
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

it("lets another conversation send while the first is generating and keeps draft ownership", async () => {
  let complete!: (value: Response) => void;
  const fetch = vi.fn((url: string) =>
    url.endsWith("/p1/messages")
      ? Promise.resolve(json(job("j1")))
      : url.includes("/j1/events")
        ? new Promise<Response>((r) => (complete = r))
        : Promise.resolve(json(job("j2", "succeeded"))),
  );
  vi.stubGlobal("fetch", fetch);
  const { flow } = make();
  flow.setDraft("p1", "first");
  const first = flow.send("p1");
  await tick();
  flow.setDraft("p1", "keep typing");
  flow.setDraft("p2", "second");
  await flow.send("p2");
  expect(flow.pending.value).toBeNull();
  expect(flow.pendings.value.p1.job?.id).toBe("j1");
  expect(flow.drafts.value.p1).toBe("keep typing");
  complete(events("j1", "succeeded", "answer", 2));
  await first;
  expect(flow.unread.value.p1).toBe(true);
  expect(
    fetch.mock.calls.filter((c) => c[0].endsWith("/messages")),
  ).toHaveLength(2);
});
it("uses full snapshots once and ignores a cursor that moves backwards", async () => {
  let poll = 0;
  let complete!: (value: Response) => void;
  vi.stubGlobal(
    "fetch",
    vi.fn((url: string) =>
      url.endsWith("/messages")
        ? Promise.resolve(json(job("j1")))
        : ++poll === 1
          ? Promise.resolve(events("j1", "running", "Hello", 2))
          : poll === 2
            ? Promise.resolve(events("j1", "running", "Hello", 2))
            : poll === 3
              ? Promise.resolve(events("j1", "running", "H", 1))
              : new Promise<Response>((r) => (complete = r)),
    ),
  );
  const { flow } = make();
  flow.setDraft("p1", "test");
  const sending = flow.send("p1");
  await tick();
  expect(flow.pending.value?.job?.partialContent).toBe("Hello");
  await vi.advanceTimersByTimeAsync(800);
  expect(flow.pending.value?.job?.partialContent).toBe("Hello");
  expect(flow.pending.value?.cursor).toBe(2);
  await vi.advanceTimersByTimeAsync(400);
  complete(events("j1", "succeeded", "Hello world", 3));
  await sending;
  expect(flow.pending.value).toBeNull();
});
it("keeps cancellation terminal when an older running poll arrives late", async () => {
  let complete!: (value: Response) => void;
  vi.stubGlobal(
    "fetch",
    vi.fn((url: string) =>
      url.endsWith("/messages")
        ? Promise.resolve(json(job("j1")))
        : url.endsWith("/cancel")
          ? Promise.resolve(json(job("j1", "cancelled", "half")))
          : new Promise<Response>((r) => (complete = r)),
    ),
  );
  const { flow } = make();
  flow.setDraft("p1", "test");
  const sending = flow.send("p1");
  await tick();
  await flow.cancel();
  complete(events("j1", "running", "old", 1));
  await sending;
  expect(flow.pending.value?.state).toBe("cancelled");
  expect(flow.pending.value?.job?.partialContent).toBe("half");
  expect(flow.pausedQueues.value.p1).toBe(true);
});
it("sends queued follow-ups only after the prior answer succeeds", async () => {
  let complete!: (value: Response) => void;
  let writes = 0;
  const fetch = vi.fn((url: string) =>
    url.endsWith("/messages")
      ? Promise.resolve(
          json(job(`j${++writes}`, writes === 1 ? "running" : "succeeded")),
        )
      : new Promise<Response>((r) => (complete = r)),
  );
  vi.stubGlobal("fetch", fetch);
  const { flow } = make();
  flow.setDraft("p1", "first");
  const sending = flow.send("p1");
  await tick();
  flow.setDraft("p1", "second");
  await flow.send("p1", [], true);
  expect(writes).toBe(1);
  expect(flow.queues.value.p1).toHaveLength(1);
  complete(events("j1", "succeeded", "done", 1));
  await sending;
  await tick();
  expect(writes).toBe(2);
  expect(flow.queues.value.p1).toHaveLength(0);
  expect(
    JSON.parse(
      fetch.mock.calls.filter((c) => c[0].endsWith("/messages"))[1][1].body,
    ).content,
  ).toBe("second");
});
it("pauses a failed queue and restores it paused until explicit continue", async () => {
  let complete!: (value: Response) => void;
  let writes = 0;
  vi.stubGlobal(
    "fetch",
    vi.fn((url: string) =>
      url.endsWith("/messages")
        ? Promise.resolve(
            json(job(`j${++writes}`, writes === 1 ? "running" : "succeeded")),
          )
        : new Promise<Response>((r) => (complete = r)),
    ),
  );
  const { flow } = make();
  flow.setDraft("p1", "first");
  const sending = flow.send("p1").catch((e) => e);
  await tick();
  flow.setDraft("p1", "second");
  await flow.send("p1", [], true);
  complete(events("j1", "failed", "half", 1));
  await sending;
  expect(writes).toBe(1);
  expect(flow.pausedQueues.value.p1).toBe(true);
  flow.dispose();
  const restored = make().flow;
  expect(restored.pausedQueues.value.p1).toBe(true);
  expect(restored.queues.value.p1[0].content).toBe("second");
  await restored.continueQueue("p1");
  expect(writes).toBe(2);
});
it("preserves drafts across expiry and full app reopen under stable account IDs, but clears on explicit exit", () => {
  const first = make().flow;
  first.setDraft("p1", "private A");
  const epoch = sessionId();
  vi.setSystemTime(Date.now() + 3600001);
  expect(token()).toBe("");
  login();
  expect(sessionId()).not.toBe(epoch);
  expect(make().flow.drafts.value.p1).toBe("private A");
  sessionStorage.clear();
  login();
  expect(make().flow.drafts.value.p1).toBe("private A");
  logout();
  login();
  expect(make().flow.drafts.value.p1).toBeUndefined();
});
it("isolates expired A → B → A accounts and never persists a login token", () => {
  make().flow.setDraft("new", "A only");
  logout(false);
  login("b");
  expect(make().flow.drafts.value.new).toBeUndefined();
  make().flow.setDraft("new", "B only");
  logout(false);
  login("a");
  expect(make().flow.drafts.value.new).toBe("A only");
  expect(JSON.stringify(readChatSession(accountId()))).not.toContain(
    "synthetic-",
  );
});
it("ignores an old response after the same account signs out and signs back in", async () => {
  let complete!: (value: Response) => void;
  vi.stubGlobal(
    "fetch",
    vi.fn(() => new Promise<Response>((r) => (complete = r))),
  );
  const { flow, accepted } = make();
  flow.setDraft("p1", "old");
  const sending = flow.send("p1");
  await tick();
  logout();
  login();
  flow.clear();
  flow.restore();
  flow.setDraft("p1", "new");
  complete(json(job("old", "succeeded")));
  await sending;
  expect(accepted).not.toHaveBeenCalled();
  expect(flow.drafts.value.p1).toBe("new");
  expect(flow.pending.value).toBeNull();
});
