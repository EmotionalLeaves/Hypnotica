#!/usr/bin/env node
/*
 * Rasterises the real shape shader and measures what comes out.
 *
 * The edge fix was derived on paper; this runs the actual GLSL through a real
 * rasteriser and reads the pixels back, so the claim "a thin ring now renders at
 * the alpha and thickness it asked for" is measured, not argued.
 */
const fs = require("fs");
const path = require("path");
const { chromium } = require("playwright");

const ROOT = path.resolve(__dirname, "..");
const SHADERS = path.join(ROOT, "app/src/main/java/app/hypnotica/core/render/Shaders.kt");

function trimIndent(body) {
  let lines = body.split("\n");
  while (lines.length && lines[0].trim() === "") lines.shift();
  while (lines.length && lines[lines.length - 1].trim() === "") lines.pop();
  let min = Infinity;
  for (const l of lines) {
    if (l.trim() === "") continue;
    min = Math.min(min, l.length - l.trimStart().length);
  }
  return lines.map((l) => l.slice(isFinite(min) ? min : 0)).join("\n");
}
const text = fs.readFileSync(SHADERS, "utf8");
const sources = {};
const re = /val\s+([A-Z0-9_]+)\s*=\s*"""([\s\S]*?)"""\s*\.trimIndent\(\)/g;
let m;
while ((m = re.exec(text)) !== null) sources[m[1]] = trimIndent(m[2]);

// The fixed-fraction version this replaced, for a side-by-side.
const OLD_POINT_VERT = sources.POINT_VERT.replace(
  [
    "    float want = aPosSize.z * uPixelsPerWorld;",
    "    float size = clamp(want, 1.0, 96.0);",
    "    gl_PointSize = size;",
    "    float shrink = clamp(want / size, 0.0, 1.0);",
    "    float dim = max(shrink * shrink, 0.25);",
    "    vColor = vec4(aColor.rgb, aColor.a * dim);",
  ].join("\n"),
  [
    "    gl_PointSize = clamp(aPosSize.z * uPixelsPerWorld, 1.0, 96.0);",
    "    vColor = aColor;",
  ].join("\n")
);
if (OLD_POINT_VERT === sources.POINT_VERT) {
  console.error("could not build the old point shader for comparison - patterns moved");
  process.exit(2);
}

const OLD_FRAG = sources.SHAPE_FRAG
  .replace("a *= 1.0 - smoothstep(1.0 - feather, 1.0, d);", "a *= 1.0 - smoothstep(0.86, 1.0, d);")
  .replace(
    /a \*= \(1\.0 - smoothstep\(1\.0 - feather, 1\.0, d\)\) \*\s*\n\s*smoothstep\(i0, i1, d\);/,
    "a *= (1.0 - smoothstep(0.93, 1.0, d)) *\n                     smoothstep(max(0.0, inner - 0.04), inner + 0.02, d);"
  );
if (OLD_FRAG === sources.SHAPE_FRAG) {
  console.error("could not build the old shader for comparison - patterns moved");
  process.exit(2);
}

// Cases the audit called out, as the sims actually request them.
const CASES = [
  { name: "sorting ring",   r: 0.9,  thickness: 0.004 },
  { name: "art ring",       r: 0.5,  thickness: 0.0022 },
  { name: "ball-escape",    r: 0.95, thickness: 0.01 },
  { name: "orbit trace",    r: 0.35, thickness: 0.006 },
  { name: "fat ring",       r: 0.2,  thickness: 0.08 },
  { name: "ring as disc",   r: 0.1,  thickness: 0.5 },
];

(async () => {
  const browser = await chromium.launch({
    args: ["--use-gl=angle", "--use-angle=swiftshader", "--enable-unsafe-swiftshader"],
  });
  const page = await browser.newPage();
  await page.goto("about:blank");

  const out = await page.evaluate(
    ([vert, newFrag, oldFrag, cases, pointVert, oldPointVert, pointFrag]) => {
      const SIZE = 1024;                 // a plausible phone viewport height
      const canvas = document.createElement("canvas");
      canvas.width = SIZE;
      canvas.height = SIZE;
      const gl = canvas.getContext("webgl2", { antialias: false, premultipliedAlpha: true });
      if (!gl) return { fatal: "no webgl2" };

      const link = (vs, fs) => {
        const mk = (t, s) => {
          const sh = gl.createShader(t);
          gl.shaderSource(sh, s);
          gl.compileShader(sh);
          if (!gl.getShaderParameter(sh, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(sh));
          return sh;
        };
        const p = gl.createProgram();
        gl.attachShader(p, mk(gl.VERTEX_SHADER, vs));
        gl.attachShader(p, mk(gl.FRAGMENT_SHADER, fs));
        gl.linkProgram(p);
        if (!gl.getProgramParameter(p, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(p));
        return p;
      };

      const quadVbo = gl.createBuffer();
      gl.bindBuffer(gl.ARRAY_BUFFER, quadVbo);
      gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.STATIC_DRAW);
      const instVbo = gl.createBuffer();

      const STRIDE = 12;
      const draw = (prog, inst) => {
        gl.viewport(0, 0, SIZE, SIZE);
        gl.clearColor(0, 0, 0, 0);
        gl.clear(gl.COLOR_BUFFER_BIT);
        gl.enable(gl.BLEND);
        gl.blendFunc(gl.ONE, gl.ONE_MINUS_SRC_ALPHA);
        gl.useProgram(prog);
        // zoom 1, square viewport: the view shows world y in [-1, 1].
        gl.uniform4f(gl.getUniformLocation(prog, "uCam"), 0, 0, 1, 1);
        gl.uniform2f(gl.getUniformLocation(prog, "uCamRot"), 1, 0);
        const ppw = gl.getUniformLocation(prog, "uPixelsPerWorld");
        if (ppw) gl.uniform1f(ppw, SIZE * 0.5);

        gl.bindBuffer(gl.ARRAY_BUFFER, quadVbo);
        gl.enableVertexAttribArray(0);
        gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 8, 0);
        gl.vertexAttribDivisor(0, 0);

        gl.bindBuffer(gl.ARRAY_BUFFER, instVbo);
        gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(inst), gl.DYNAMIC_DRAW);
        for (let loc = 1; loc <= 3; loc++) {
          gl.enableVertexAttribArray(loc);
          gl.vertexAttribPointer(loc, 4, gl.FLOAT, false, STRIDE * 4, (loc - 1) * 16);
          gl.vertexAttribDivisor(loc, 1);
        }
        gl.drawArraysInstanced(gl.TRIANGLE_STRIP, 0, 4, inst.length / STRIDE);
        for (let loc = 1; loc <= 3; loc++) {
          gl.vertexAttribDivisor(loc, 0);
          gl.disableVertexAttribArray(loc);
        }
        const px = new Uint8Array(SIZE * SIZE * 4);
        gl.readPixels(0, 0, SIZE, SIZE, gl.RGBA, gl.UNSIGNED_BYTE, px);
        return px;
      };

      // G.ring: inner = ((r - thickness) / r) clamped to 0..0.999, white, alpha 1.
      const ringInstance = (r, thickness) => {
        const inner = Math.min(0.999, Math.max(0, (r - thickness) / r));
        return [0, 0, r, r, 0, 2, inner, 0, 1, 1, 1, 1];
      };

      // Walk the centre row outward and describe the band we hit.
      const measure = (px) => {
        const mid = SIZE >> 1;
        let peak = 0, lit = 0, first = -1, last = -1;
        for (let x = mid; x < SIZE; x++) {
          const a = px[(mid * SIZE + x) * 4 + 3] / 255;
          if (a > 0.02) {
            if (first < 0) first = x;
            last = x;
            lit++;
            if (a > peak) peak = a;
          }
        }
        return { peak, litPx: lit, innerPx: first - mid, outerPx: last - mid };
      };

      const res = [];
      let field = [];
      try {
        const progNew = link(vert, newFrag);
        const progOld = link(vert, oldFrag);
        for (const c of cases) {
          const inst = ringInstance(c.r, c.thickness);
          const o = measure(draw(progOld, inst));
          const n = measure(draw(progNew, inst));
          // A ring thicker than its radius is a filled disc: that is what
          // G.ring's inner clamp makes it, so ask for the disc.
          const want = Math.min(c.thickness, c.r) * SIZE * 0.5;
          res.push({ ...c, old: o, now: n, wantPx: want });
        }
        // A disc, to be sure the fix did not eat it.
        for (const r of [0.9, 0.05, 0.004]) {
          const inst = [0, 0, r, r, 0, 1, 0, 0, 1, 1, 1, 1];
          const o = measure(draw(progOld, inst));
          const n = measure(draw(progNew, inst));
          res.push({ name: "disc r=" + r, disc: true, r, old: o, now: n, wantPx: r * SIZE * 0.5 });
        }
        // The huge-count path. A dot smaller than a pixel still fills a whole
        // one, so a field of forty thousand used to get brighter the further you
        // zoomed out instead of fainter. Measure the ink, not the look.
        const pointProgNew = link(pointVert, pointFrag);
        const pointProgOld = link(oldPointVert, pointFrag);
        const N = 40000;
        const side = Math.ceil(Math.sqrt(N));
        const pos = new Float32Array(N * 3);
        const col = new Uint8Array(N * 4);
        for (let i = 0; i < N; i++) {
          const gx = i % side, gy = Math.floor(i / side);
          pos[i * 3] = (gx / (side - 1)) * 1.8 - 0.9;
          pos[i * 3 + 1] = (gy / (side - 1)) * 1.8 - 0.9;
          pos[i * 3 + 2] = 0.004;                   // a typical particle size
          col[i * 4] = col[i * 4 + 1] = col[i * 4 + 2] = 255;
          col[i * 4 + 3] = 255;
        }
        const posVbo = gl.createBuffer();
        const colVbo = gl.createBuffer();
        const drawField = (prog, zoom) => {
          gl.viewport(0, 0, SIZE, SIZE);
          gl.clearColor(0, 0, 0, 0);
          gl.clear(gl.COLOR_BUFFER_BIT);
          gl.enable(gl.BLEND);
          gl.blendFunc(gl.ONE, gl.ONE_MINUS_SRC_ALPHA);
          gl.useProgram(prog);
          gl.uniform4f(gl.getUniformLocation(prog, "uCam"), 0, 0, zoom, zoom);
          gl.uniform2f(gl.getUniformLocation(prog, "uCamRot"), 1, 0);
          gl.uniform1f(gl.getUniformLocation(prog, "uPixelsPerWorld"), SIZE * 0.5 * zoom);
          gl.bindBuffer(gl.ARRAY_BUFFER, posVbo);
          gl.bufferData(gl.ARRAY_BUFFER, pos, gl.DYNAMIC_DRAW);
          gl.enableVertexAttribArray(0);
          gl.vertexAttribPointer(0, 3, gl.FLOAT, false, 12, 0);
          gl.vertexAttribDivisor(0, 0);
          gl.bindBuffer(gl.ARRAY_BUFFER, colVbo);
          gl.bufferData(gl.ARRAY_BUFFER, col, gl.DYNAMIC_DRAW);
          gl.enableVertexAttribArray(1);
          gl.vertexAttribPointer(1, 4, gl.UNSIGNED_BYTE, true, 4, 0);
          gl.vertexAttribDivisor(1, 0);
          gl.drawArrays(gl.POINTS, 0, N);
          const px = new Uint8Array(SIZE * SIZE * 4);
          gl.readPixels(0, 0, SIZE, SIZE, gl.RGBA, gl.UNSIGNED_BYTE, px);
          let ink = 0;
          for (let i = 3; i < px.length; i += 4) ink += px[i];
          return ink / 255;
        };
        field = [];
        for (const zoom of [1, 0.5, 0.25, 0.1]) {
          field.push({
            zoom,
            wantPx: 0.004 * SIZE * 0.5 * zoom,
            old: drawField(pointProgOld, zoom),
            now: drawField(pointProgNew, zoom),
          });
        }
      } catch (e) {
        return { fatal: String(e) };
      }
      return { res, field };
    },
    [sources.SHAPE_VERT, sources.SHAPE_FRAG, OLD_FRAG, CASES,
     sources.POINT_VERT, OLD_POINT_VERT, sources.POINT_FRAG]
  );

  await browser.close();
  if (out.fatal) {
    console.error("FATAL " + out.fatal);
    process.exit(2);
  }

  let bad = 0;
  for (const r of out.res) {
    if (r.disc) {
      const errOld = Math.abs(r.old.outerPx - r.wantPx);
      const errNew = Math.abs(r.now.outerPx - r.wantPx);
      console.log(
        `${r.name.padEnd(16)} want radius ${r.wantPx.toFixed(1)}px  ` +
          `old ${r.old.outerPx}px a=${r.old.peak.toFixed(3)}  ` +
          `new ${r.now.outerPx}px a=${r.now.peak.toFixed(3)}`
      );
      if (errNew > errOld + 1.5 || r.now.peak < 0.9) bad++;
      continue;
    }
    const thickOld = r.old.litPx;
    const thickNew = r.now.litPx;
    console.log(
      `${r.name.padEnd(16)} r=${r.r} t=${r.thickness}  want ${r.wantPx.toFixed(2)}px  ` +
        `old ${thickOld}px a=${r.old.peak.toFixed(3)} (${(thickOld / Math.max(1, r.wantPx)).toFixed(1)}x)  ` +
        `new ${thickNew}px a=${r.now.peak.toFixed(3)} (${(thickNew / Math.max(1, r.wantPx)).toFixed(1)}x)`
    );
    // A band at least a pixel and a half thick should reach the alpha it asked
    // for. A thinner one physically cannot: a hairline covers part of a pixel,
    // so correct antialiasing gives partial alpha. It still has to be clearly
    // visible, and no band may be much wider than asked.
    const floor = r.wantPx >= 1.5 ? 0.9 : 0.5;
    if (r.now.peak < floor) { console.log("    ^ still dim"); bad++; }
    if (thickNew > r.wantPx + 2.5) { console.log("    ^ still fat"); bad++; }
  }
  console.log("");
  // Ink at full zoom is the honest reference: nothing is clamped there.
  const ref = out.field.find((f) => f.wantPx >= 1.5) || out.field[0];
  for (const f of out.field) {
    const ideal = ref.now * Math.pow(f.zoom / ref.zoom, 2);
    const oldX = f.old / Math.max(1, ideal);
    const newX = f.now / Math.max(1, ideal);
    console.log(
      `40k field zoom ${String(f.zoom).padEnd(5)} dot ${f.wantPx.toFixed(2)}px  ` +
        `ideal ink ${ideal.toFixed(0)}  old ${f.old.toFixed(0)} (${oldX.toFixed(1)}x)  ` +
        `new ${f.now.toFixed(0)} (${newX.toFixed(1)}x)`
    );
    // Zoomed far out the dot cannot shrink below a pixel, so some excess is
    // unavoidable; it must at least be a long way under the old behaviour.
    if (f.wantPx < 1 && f.now > f.old * 0.75) {
      console.log("    ^ zooming out still floods the canvas");
      bad++;
    }
  }
  console.log(bad === 0 ? "\nRING, DISC AND POINT PIXELS ARE CORRECT" : `\n${bad} measurement(s) still wrong`);
  process.exit(bad === 0 ? 0 : 1);
})().catch((e) => { console.error(e); process.exit(2); });
