/*
 * CLU 3D scene: a small, general 3D point-and-label renderer on a 2D canvas (no WebGL needed). Any application can
 * use it: star maps, solar systems, graphs, galaxies. Nodes have a position (x, y, z), size, colour and label; the
 * camera orbits on drag, zooms on wheel, slowly turns when idle; clicking a node calls onPick(node).
 *
 *   const scene = CluScene3D.create(canvas, { background: "#02040a", stars: 400, onPick: (node) => {} });
 *   scene.setNodes([{ id: "a", label: "Docs", x: 0, y: 0, z: 0, size: 8, color: "#7cf" }]);
 *   scene.focus(nodeId); scene.stats() // { nodes, drawn, angle }
 */
(function () {
  "use strict";
  function create(canvas, opts) {
    opts = opts || {};
    const ctx = canvas.getContext("2d");
    let nodes = [], links = [], yaw = 0.6, pitch = 0.35, dist = 520, drag = null, last = 0, hover = null, drawn = 0, raf = 0;
    const stars = []; let seed = 11;
    const rnd = () => { seed = (seed * 16807) % 2147483647; return seed / 2147483647; };
    for (let i = 0; i < (opts.stars == null ? 300 : opts.stars); i++) stars.push({ x: rnd() * 2 - 1, y: rnd() * 2 - 1, z: rnd(), b: 0.3 + rnd() * 0.7 });

    function size() { const r = canvas.getBoundingClientRect(); return { w: r.width || canvas.width, h: r.height || canvas.height }; }
    function resize() { const r = canvas.getBoundingClientRect(), dpr = window.devicePixelRatio || 1;
      canvas.width = Math.max(1, Math.round((r.width || 300) * dpr)); canvas.height = Math.max(1, Math.round((r.height || 150) * dpr)); ctx.setTransform(dpr, 0, 0, dpr, 0, 0); draw(); }
    function project(n) {
      const cy = Math.cos(yaw), sy = Math.sin(yaw), cp = Math.cos(pitch), sp = Math.sin(pitch);
      const x1 = n.x * cy - n.z * sy, z1 = n.x * sy + n.z * cy, y1 = n.y * cp - z1 * sp, z2 = n.y * sp + z1 * cp;
      const depth = z2 + dist; if (depth < 10) return null;
      const { w, h } = size(), f = Math.min(w, h) * 0.9 / depth * 1.6;
      return { x: w / 2 + x1 * f, y: h / 2 + y1 * f, s: Math.max(1.5, (n.size || 6) * f * 0.6), depth };
    }
    function draw() {
      raf = 0; const { w, h } = size(); if (!w || !h) return;
      ctx.fillStyle = opts.background || "#02040a"; ctx.fillRect(0, 0, w, h);
      for (const s of stars) { ctx.globalAlpha = s.b * 0.8; ctx.fillStyle = "#ffffff"; ctx.fillRect((s.x * 0.5 + 0.5) * w + Math.sin(yaw + s.z * 6) * 6 * s.z, (s.y * 0.5 + 0.5) * h, 1.2, 1.2); }
      ctx.globalAlpha = 1;
      const pts = nodes.map((n) => ({ n, p: project(n) })).filter((e) => e.p).sort((a, b) => b.p.depth - a.p.depth);
      const byId = new Map(pts.map((e) => [e.n.id, e.p]));
      ctx.strokeStyle = opts.linkColor || "rgba(120,180,255,0.25)"; ctx.lineWidth = 1;
      for (const l of links) { const a = byId.get(l[0]), b = byId.get(l[1]); if (a && b) { ctx.beginPath(); ctx.moveTo(a.x, a.y); ctx.lineTo(b.x, b.y); ctx.stroke(); } }
      drawn = 0;
      for (const { n, p } of pts) {
        const g = ctx.createRadialGradient(p.x, p.y, 0, p.x, p.y, p.s * 2.4);
        g.addColorStop(0, n.color || "#9cf"); g.addColorStop(0.35, n.color || "#9cf"); g.addColorStop(1, "rgba(0,0,0,0)");
        ctx.fillStyle = g; ctx.beginPath(); ctx.arc(p.x, p.y, p.s * 2.4, 0, 7); ctx.fill(); drawn++;
        if (n.label && (p.s > 3 || n === hover)) { ctx.fillStyle = n === hover ? "#ffffff" : (opts.labelColor || "#cfe6ff"); ctx.font = `${n === hover ? 13 : 11}px ${opts.font || "system-ui, sans-serif"}`;
          ctx.fillText(n.label, p.x + p.s + 4, p.y + 4); }
        p.node = n; n._p = p;
      }
    }
    function schedule() { if (!raf) raf = requestAnimationFrame(draw); }
    function pick(x, y) { let best = null, bd = 1e9;
      for (const n of nodes) { const p = n._p; if (!p) continue; const d = Math.hypot(p.x - x, p.y - y); if (d < Math.max(10, p.s * 2) && d < bd) { bd = d; best = n; } }
      return best; }
    canvas.addEventListener("pointerdown", (e) => { drag = { x: e.clientX, y: e.clientY, moved: 0 }; last = Date.now(); });
    canvas.addEventListener("pointermove", (e) => {
      const r = canvas.getBoundingClientRect();
      if (drag) { const dx = e.clientX - drag.x, dy = e.clientY - drag.y; drag.moved += Math.abs(dx) + Math.abs(dy); drag.x = e.clientX; drag.y = e.clientY;
        yaw += dx * 0.008; pitch = Math.max(-1.3, Math.min(1.3, pitch + dy * 0.008)); last = Date.now(); draw(); return; }
      const h = pick(e.clientX - r.left, e.clientY - r.top); if (h !== hover) { hover = h; canvas.style.cursor = h ? "pointer" : "grab"; schedule(); }
    });
    canvas.addEventListener("pointerup", (e) => { const was = drag; drag = null; if (!was || was.moved > 4) return;
      const r = canvas.getBoundingClientRect(); const n = pick(e.clientX - r.left, e.clientY - r.top); if (n && opts.onPick) opts.onPick(n); });
    canvas.addEventListener("wheel", (e) => { e.preventDefault(); dist = Math.max(80, Math.min(4000, dist * (e.deltaY > 0 ? 1.12 : 0.89))); last = Date.now(); draw(); }, { passive: false });
    window.addEventListener("resize", resize);
    const spin = opts.autoRotate === false ? 0 : setInterval(() => { if (!drag && Date.now() - last > 3000 && !document.hidden) { yaw += 0.003; schedule(); } }, 40);
    resize();
    return {
      setNodes(list, linkList) { nodes = (list || []).map((n) => Object.assign({}, n)); links = linkList || []; draw(); },
      focus(id) { const n = nodes.find((x) => x.id === id); if (n) { yaw = Math.atan2(n.x, n.z + 1e-6); draw(); } },
      rotate(dYaw, dPitch) { yaw += dYaw || 0; pitch += dPitch || 0; draw(); },
      zoom(f) { dist = Math.max(80, Math.min(4000, dist * f)); draw(); },
      stats() { return { nodes: nodes.length, drawn, yaw, pitch, dist }; },
      redraw: draw, destroy() { clearInterval(spin); window.removeEventListener("resize", resize); },
    };
  }
  window.CluScene3D = { create };
})();
