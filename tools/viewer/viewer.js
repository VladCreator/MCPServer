"use strict";
let token = "";
let timer = null;
let paused = false;
let busy = false;
let epoch = 0;
const el = (id) => document.getElementById(id);
function clear(message) {
  epoch++;
  token = "";
  clearTimeout(timer);
  el("token").value = "";
  el("artifact").value = "";
  el("steps").replaceChildren();
  el("metadata").textContent = "";
  el("verdict").textContent = "";
  el("result").hidden = true;
  el("controls").hidden = true;
  el("login").hidden = false;
  el("message").textContent = message;
}
async function request(path, method = "GET") {
  const generation = epoch;
  const response = await fetch(path, {method, headers: {Authorization: "Bearer " + token},
    cache: "no-store", credentials: "omit", redirect: "error", referrerPolicy: "no-referrer"});
  if (response.status === 401) {
    clear("Access expired or revoked. Restart the Viewer for fresh authorization.");
    throw new Error("Authentication required.");
  }
  if (!response.ok) throw new Error("Native request failed or consent denied. Nothing retried.");
  if (generation !== epoch) throw new Error("Viewer access changed.");
  return response;
}
async function refresh() {
  if (busy || !token) return;
  busy = true;
  clearTimeout(timer);
  try {
    const response = await request("/api/run");
    const generation = epoch;
    const run = await response.json();
    if (generation !== epoch) return;
    el("result").hidden = false;
    el("verdict").textContent = "Run " + run.run_id + ": " + run.status;
    el("metadata").textContent = JSON.stringify({primary: run.primary_status, cleanup: run.cleanup_status,
      pending_cleanup: run.pending_cleanup, pending_state_restore: run.pending_state_restore,
      evidence: run.evidence_status, failure_bundle: run.failure_bundle}, null, 2);
    el("steps").replaceChildren();
    for (const step of run.steps || []) {
      const row = document.createElement("tr");
      for (const key of ["id", "phase", "tool", "status", "offset_ms", "duration_ms"]) {
        const cell = document.createElement("td");
        cell.textContent = step[key] ?? "";
        row.appendChild(cell);
      }
      el("steps").appendChild(row);
    }
    el("message").textContent = "Native redacted snapshot fetched at " + new Date().toLocaleTimeString() +
      ". Not a live screen or guarantee of current UI state.";
    if (!paused && ["awaiting_approval", "queued", "running", "cancelling"].includes(run.status))
      timer = setTimeout(refresh, 1000);
  } catch (error) {
    paused = true;
    el("pause").textContent = "Resume polling";
    el("message").textContent = error.message + " Polling paused.";
  } finally { busy = false; }
}
async function download(path, filename) {
  if (busy || !token) return;
  busy = true;
  clearTimeout(timer);
  const generation = epoch;
  try {
    const response = await request(path);
    const blob = await response.blob();
    if (generation !== epoch) return;
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = filename;
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
    el("message").textContent = "Existing native artifact downloaded. Viewer did not capture the screen.";
  } catch (error) {
    paused = true;
    el("pause").textContent = "Resume polling";
    el("message").textContent = error.message + " Polling paused.";
  }
  finally {
    busy = false;
    if (token && !paused) timer = setTimeout(refresh, 1000);
  }
}
el("connect").onclick = () => {
  epoch++;
  token = el("token").value;
  el("token").value = "";
  paused = false;
  el("pause").textContent = "Pause polling";
  el("login").hidden = true;
  el("controls").hidden = false;
  refresh();
};
el("refresh").onclick = refresh;
el("pause").onclick = () => {
  paused = !paused;
  clearTimeout(timer);
  el("pause").textContent = paused ? "Resume polling" : "Pause polling";
  if (!paused) refresh();
};
for (const action of ["revoke", "stop"]) {
  el(action).onclick = async () => {
    clearTimeout(timer);
    try {
      await request("/api/" + action, "POST");
      clear(action === "stop" ? "Viewer stopped." : "Access revoked. Restart the Viewer to authorize again.");
    } catch (error) { clear(error.message); }
  };
}
for (const [format, filename] of [["json", "run.json"], ["markdown", "timeline.md"], ["junit", "junit.xml"]])
  el(format).onclick = () => download("/api/export?format=" + format, filename);
el("download").onclick = () => {
  const id = el("artifact").value;
  if (!/^[a-f0-9-]{36}$/.test(id)) {
    el("message").textContent = "Enter an existing native artifact ID.";
    return;
  }
  download("/api/artifact?id=" + encodeURIComponent(id), "native-artifact");
};
window.addEventListener("pagehide", () => clear("Viewer page closed."));
