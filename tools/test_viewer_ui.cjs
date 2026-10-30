const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const path = require("node:path");

function browser(fetch) {
  const elements = new Map();
  function element() {
    return {value: "", hidden: false, textContent: "", children: [],
      replaceChildren() { this.children = []; },
      appendChild(child) { this.children.push(child); },
      click() {}};
  }
  const document = {getElementById(id) {
    if (!elements.has(id)) elements.set(id, element());
    return elements.get(id);
  }, createElement: element};
  const timers = new Map();
  let nextTimer = 0;
  const events = {};
  const context = vm.createContext({document, fetch,
    setTimeout(fn) { timers.set(++nextTimer, fn); return nextTimer; },
    clearTimeout(id) { timers.delete(id); }, Date, URL,
    window: {addEventListener(name, callback) { events[name] = callback; }}});
  vm.runInContext(fs.readFileSync(path.join(__dirname, "viewer/viewer.js"), "utf8"), context);
  return {el: document.getElementById, timers, events};
}
const settle = () => new Promise(resolve => setImmediate(resolve));
function response(data, status = 200) {
  return {status, ok: status === 200, json: async () => data, blob: async () => new Blob(["native"])};
}

test("auth stays in memory; canonical text is rendered inert and terminal run stops polling", async () => {
  const calls = [];
  const b = browser(async (url, options) => {
    calls.push({url, options});
    return response({run_id: "native-id", status: "completed", cleanup_status: "completed", steps: [{
      id: "<img src=x onerror=alert(1)>", phase: "steps", tool: "ui_assert", status: "completed",
      offset_ms: 10, duration_ms: 20}]});
  });
  b.el("token").value = "private-viewer-token";
  b.el("connect").onclick();
  await settle();
  assert.equal(b.el("token").value, "");
  assert.equal(calls[0].url, "/api/run");
  assert.equal(calls[0].options.headers.Authorization, "Bearer private-viewer-token");
  assert.equal(calls[0].options.credentials, "omit");
  assert.equal(b.el("steps").children[0].children[0].textContent, "<img src=x onerror=alert(1)>");
  assert.equal(b.el("verdict").textContent, "Run native-id: completed");
  assert.equal(b.timers.size, 0);
});

test("active run schedules bounded polling and pause/resume is explicit", async () => {
  let count = 0;
  const b = browser(async () => { count++; return response({run_id: "id", status: "running", steps: []}); });
  b.el("token").value = "token";
  b.el("connect").onclick();
  await settle();
  assert.equal(count, 1);
  assert.equal(b.timers.size, 1);
  b.el("pause").onclick();
  assert.equal(b.timers.size, 0);
  b.el("pause").onclick();
  await settle();
  assert.equal(count, 2);
});

test("native failure pauses polling instead of retrying automatically", async () => {
  const b = browser(async () => response({}, 502));
  b.el("token").value = "token";
  b.el("connect").onclick();
  await settle();
  assert.match(b.el("message").textContent, /Nothing retried.*Polling paused/);
  assert.equal(b.timers.size, 0);
});

test("expiry clears current page data and token without persisting credentials", async () => {
  let expired = false;
  const b = browser(async () => response({run_id: "id", status: "completed", steps: []}, expired ? 401 : 200));
  b.el("token").value = "token";
  b.el("connect").onclick();
  await settle();
  expired = true;
  await b.el("refresh").onclick();
  assert.equal(b.el("result").hidden, true);
  assert.equal(b.el("controls").hidden, true);
  assert.equal(b.el("steps").children.length, 0);
  assert.equal(b.el("metadata").textContent, "");
  assert.equal(b.el("token").value, "");
});

test("revoke clears display and a late run response cannot repopulate it", async () => {
  let release;
  const calls = [];
  const b = browser((url, options) => {
    calls.push({url, options});
    if (url === "/api/run") return new Promise(resolve => { release = resolve; });
    return Promise.resolve(response({revoked: true}));
  });
  b.el("token").value = "token";
  b.el("connect").onclick();
  await settle();
  await b.el("revoke").onclick();
  release(response({run_id: "private-native-id", status: "completed", steps: [{id: "late"}]}));
  await settle();
  assert.equal(calls[1].url, "/api/revoke");
  assert.equal(calls[1].options.method, "POST");
  assert.equal(b.el("result").hidden, true);
  assert.equal(b.el("verdict").textContent, "");
  assert.equal(b.el("steps").children.length, 0);
});

test("page exit drops token and data without cancelling the native run", async () => {
  const calls = [];
  const b = browser(async url => { calls.push(url); return response({run_id: "id", status: "running", steps: []}); });
  b.el("token").value = "token";
  b.el("connect").onclick();
  await settle();
  b.events.pagehide();
  assert.equal(b.el("controls").hidden, true);
  assert.equal(b.timers.size, 0);
  assert.deepEqual(calls, ["/api/run"]);
});

test("export downloads use canonical endpoints and resume timeline refresh without recapturing", async () => {
  const calls = [];
  const b = browser(async url => {
    calls.push(url);
    return response({run_id: "id", status: "running", steps: []});
  });
  b.el("token").value = "token";
  b.el("connect").onclick();
  await settle();
  await b.el("json").onclick();
  assert.deepEqual(calls, ["/api/run", "/api/export?format=json"]);
  assert.match(b.el("message").textContent, /did not capture/);
  assert.equal(b.timers.size, 2); // Blob URL revocation and next timeline refresh.
});

test("failed artifact consent pauses polling and never retries the download", async () => {
  const calls = [];
  const b = browser(async url => {
    calls.push(url);
    return response({run_id: "id", status: "running", steps: []}, url.startsWith("/api/artifact") ? 502 : 200);
  });
  b.el("token").value = "token";
  b.el("connect").onclick();
  await settle();
  b.el("artifact").value = "abcdef01-1234-1234-1234-123456789abc";
  b.el("download").onclick();
  await settle();
  assert.equal(calls.length, 2);
  assert.match(b.el("message").textContent, /Polling paused/);
  assert.equal(b.timers.size, 0);
});
