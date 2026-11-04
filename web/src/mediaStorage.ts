export interface LocalFile {
  id: string;
  name: string;
  mimeType: string;
  size: number;
  kind: "image" | "document";
}
export interface StoredFile extends LocalFile {
  blob: Blob;
}
export class MissingMediaError extends Error {}
interface MediaRow {
  key: string;
  owner: string;
  expiresAt: number;
  value: StoredFile[];
}
const DB = "decision-chat-media";
const STORE = "media";
const revoked = new Set<string>();
const transactions = new Map<string, Set<IDBTransaction>>();
let database: Promise<IDBDatabase> | undefined;
const storageError = () =>
  new Error("附件未能安全暂存，请检查浏览器存储权限后重试。尚未发送。");
function db(): Promise<IDBDatabase> {
  if (!database)
    database = new Promise<IDBDatabase>((resolve, reject) => {
      if (typeof indexedDB === "undefined") return reject(storageError());
      let failed = false;
      const open = indexedDB.open(DB, 1);
      open.onupgradeneeded = () => {
        const store = open.result.createObjectStore(STORE, { keyPath: "key" });
        store.createIndex("owner", "owner");
      };
      open.onsuccess = () => {
        const result = open.result;
        if (failed) {
          result.close();
          return;
        }
        result.onversionchange = () => {
          result.close();
          database = undefined;
        };
        resolve(result);
      };
      open.onerror = open.onblocked = () => {
        failed = true;
        reject(storageError());
      };
    }).catch((error) => {
      database = undefined;
      throw error;
    });
  return database;
}
function check(owner: string, valid: () => boolean) {
  if (!owner || revoked.has(owner) || !valid())
    throw new Error("会话已结束，附件操作已停止。");
}
async function transact<T>(
  owner: string,
  mode: IDBTransactionMode,
  valid: () => boolean,
  work: (store: IDBObjectStore, result: (value: T) => void) => void,
): Promise<T> {
  check(owner, valid);
  const database = await db();
  check(owner, valid);
  const value = await new Promise<T>((resolve, reject) => {
    const tx = database.transaction(STORE, mode);
    const active = transactions.get(owner) || new Set<IDBTransaction>();
    active.add(tx);
    transactions.set(owner, active);
    let output: T;
    const done = () => {
      active.delete(tx);
      if (!active.size) transactions.delete(owner);
    };
    tx.oncomplete = () => {
      done();
      resolve(output);
    };
    tx.onerror = tx.onabort = () => {
      done();
      reject(storageError());
    };
    try {
      work(tx.objectStore(STORE), (result) => {
        output = result;
      });
    } catch {
      tx.abort();
    }
  });
  check(owner, valid);
  return value;
}
export async function putMedia(
  owner: string,
  id: string,
  files: StoredFile[],
  expiresAt: number,
  valid: () => boolean,
) {
  await transact<void>(owner, "readwrite", valid, (store) => {
    store.put({
      key: `${owner}:${id}`,
      owner,
      expiresAt,
      value: files,
    } satisfies MediaRow);
  });
}
export async function getMedia(
  owner: string,
  id: string,
  valid: () => boolean,
): Promise<StoredFile[]> {
  const value = await transact<StoredFile[] | undefined>(
    owner,
    "readonly",
    valid,
    (store, done) => {
      const request = store.get(`${owner}:${id}`);
      request.onsuccess = () =>
        done((request.result as MediaRow | undefined)?.value);
    },
  );
  if (!value || value.some((file) => !(file.blob instanceof Blob)))
    throw new MissingMediaError(
      "本机暂存附件已不可用，请移除后重新选择；不会发送不完整的内容。",
    );
  return value;
}
export async function deleteMedia(
  owner: string,
  ids: string[],
  valid: () => boolean,
) {
  if (!ids.length) return;
  await transact<void>(owner, "readwrite", valid, (store) => {
    for (const id of ids) store.delete(`${owner}:${id}`);
  });
}
// Revocation is synchronous, so queued conversions/transactions cannot resurrect a logged-out session.
export function clearMediaSession(owner: string): Promise<void> {
  if (!owner) return Promise.resolve();
  revoked.add(owner);
  for (const tx of transactions.get(owner) || []) {
    try {
      tx.abort();
    } catch {
      /* Already complete. */
    }
  }
  if (typeof indexedDB === "undefined") return Promise.resolve();
  return db().then(
    (database) =>
      new Promise<void>((resolve, reject) => {
        const tx = database.transaction(STORE, "readwrite");
        const cursor = tx
          .objectStore(STORE)
          .index("owner")
          .openCursor(IDBKeyRange.only(owner));
        cursor.onsuccess = () => {
          const item = cursor.result;
          if (item) {
            item.delete();
            item.continue();
          }
        };
        tx.oncomplete = () => resolve();
        tx.onerror = tx.onabort = () =>
          reject(new Error("本机附件缓存清理失败，请清除此站点的浏览器数据。"));
      }),
  );
}
// Only expired login sessions are swept. Other tabs' valid sessions are untouched.
export async function purgeExpiredMedia() {
  if (typeof indexedDB === "undefined") return;
  const database = await db();
  await new Promise<void>((resolve, reject) => {
    const tx = database.transaction(STORE, "readwrite");
    const request = tx.objectStore(STORE).openCursor();
    request.onsuccess = () => {
      const cursor = request.result;
      if (cursor) {
        if ((cursor.value as MediaRow).expiresAt <= Date.now()) cursor.delete();
        cursor.continue();
      }
    };
    tx.oncomplete = () => resolve();
    tx.onerror = tx.onabort = () => reject(storageError());
  });
}
export const localMetadata = ({
  blob: _blob,
  ...file
}: StoredFile): LocalFile => file;
