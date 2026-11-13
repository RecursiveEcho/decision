const invoke = window.__TAURI__.core.invoke;
const status = document.querySelector("#status");
const services = document.querySelector("#services");
const retry = document.querySelector("#retry");
const open = document.querySelector("#open");

async function refresh() {
  try {
    const state = await invoke("desktop_status");
    status.textContent = state.message;
    services.replaceChildren();
    for (const [name, value] of Object.entries(state.services)) {
      const term = document.createElement("dt");
      const description = document.createElement("dd");
      term.textContent = name;
      description.textContent = value;
      services.append(term, description);
    }
    retry.hidden = !state.canRetry;
    open.hidden = !state.ready;
    document.querySelector("#settings").disabled = !state.ready;
  } catch {
    status.textContent = "桌面管理器暂不可用。";
  }
}

retry.onclick = async () => {
  retry.disabled = true;
  try { await invoke("retry_start"); } finally { retry.disabled = false; }
};
open.onclick = () => invoke("open_business");
document.querySelector("#logs").onclick = () => invoke("open_logs");
document.querySelector("#code").onclick = async () => {
  const output = document.querySelector("#owner-code");
  output.textContent = await invoke("owner_code");
  output.hidden = false;
};
document.querySelector("#settings").onclick = async () => {
  const saved = document.querySelector("#saved");
  try {
    await invoke("open_settings");
    saved.textContent = "已打开应用设置。";
  } catch (error) {
    saved.textContent = String(error);
  }
};
refresh();
setInterval(refresh, 1000);
