window.CluApp = { mount: async function (kit) {
  const list = kit.$("gxList"), msg = document.querySelector("[data-role=message]"), pathEl = document.querySelector("[data-role=path]");
  let path = "";
  const scene = CluScene3D.create(document.querySelector("canvas[data-role=scene]"), { onPick: (n) => n.dir && open(n.id) });
  function show(entries) {
    list.replaceChildren(...entries.map((e) => { const li = kit.el("li", e.type, (e.type === "dir" ? "📁 " : "") + e.name); li.dataset.entry = e.name;
      li.addEventListener("click", () => { if (e.type !== "file") open(e.path); else msg.textContent = e.name + " (" + e.size + " bytes)"; }); return li; }));
    scene.setNodes(entries.map((e, i) => { const a = i * 2.399, r = 40 + 12 * Math.sqrt(i); return { id: e.path, label: e.name, dir: e.type !== "file", x: Math.cos(a) * r * 2, y: (i % 5 - 2) * 18, z: Math.sin(a) * r * 2, size: e.type === "dir" ? 10 : 6, color: e.type === "dir" ? "#7cf" : "#fc7" }; }));
  }
  async function open(p) {
    try { const r = await kit.fs.list(p); path = r.path; pathEl.textContent = "/" + path; msg.textContent = r.entries.length ? "" : "This folder is empty."; show(r.entries); }
    catch (e) { msg.textContent = "Cannot open that folder: " + e.message; }
  }
  document.querySelector("[data-action=back]").addEventListener("click", () => open(path.split("/").slice(0, -1).join("/")));
  BACKHOOK
  await open("");
} };
