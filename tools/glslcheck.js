#!/usr/bin/env node
/*
 * Compiles the app's GLSL for real.
 *
 * The shaders live in Kotlin as plain strings, so the Android build never looks
 * inside them: a typo in the GLSL survives every compiler we have and shows up
 * on the phone as a black screen. Headless Chromium speaks GLSL ES 3.00 through
 * WebGL2, which is the same language the app hands to the driver, so this
 * compiles and links every program and reports the driver's own log.
 *
 * It finds the shader sources and the program pairs by reading the Kotlin, so
 * adding a program needs no change here.
 *
 *   node tools/glslcheck.js
 */
const fs = require("fs");
const path = require("path");
const { chromium } = require("playwright");

const ROOT = path.resolve(__dirname, "..");
const SRC = path.join(ROOT, "app/src/main/java/app/hypnotica");
const SHADERS = path.join(SRC, "core/render/Shaders.kt");

function trimIndent(body) {
  // Kotlin's trimIndent: drop blank first/last lines, then remove the smallest
  // indent found on any non-blank line.
  let lines = body.split("\n");
  while (lines.length && lines[0].trim() === "") lines.shift();
  while (lines.length && lines[lines.length - 1].trim() === "") lines.pop();
  let min = Infinity;
  for (const l of lines) {
    if (l.trim() === "") continue;
    min = Math.min(min, l.length - l.trimStart().length);
  }
  if (!isFinite(min)) min = 0;
  return lines.map((l) => l.slice(min)).join("\n");
}

function readSources() {
  const text = fs.readFileSync(SHADERS, "utf8");
  const out = {};
  const re = /val\s+([A-Z0-9_]+)\s*=\s*"""([\s\S]*?)"""\s*\.trimIndent\(\)/g;
  let m;
  while ((m = re.exec(text)) !== null) out[m[1]] = trimIndent(m[2]);
  return out;
}

function readPairs() {
  const pairs = [];
  const walk = (dir) => {
    for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
      const p = path.join(dir, e.name);
      if (e.isDirectory()) walk(p);
      else if (e.name.endsWith(".kt")) {
        const t = fs.readFileSync(p, "utf8");
        const re = /Shaders\.link\(\s*Shaders\.([A-Z0-9_]+)\s*,\s*Shaders\.([A-Z0-9_]+)\s*\)/g;
        let m;
        while ((m = re.exec(t)) !== null) {
          pairs.push({ vert: m[1], frag: m[2], where: path.relative(ROOT, p) });
        }
      }
    }
  };
  walk(SRC);
  return pairs;
}

(async () => {
  const sources = readSources();
  const pairs = readPairs();
  if (!pairs.length) {
    console.error("no Shaders.link(...) call sites found");
    process.exit(2);
  }
  for (const p of pairs) {
    for (const k of [p.vert, p.frag]) {
      if (!sources[k]) {
        console.error(`${p.where}: links ${k}, which Shaders.kt does not define`);
        process.exit(2);
      }
    }
  }

  const browser = await chromium.launch({
    args: ["--use-gl=angle", "--use-angle=swiftshader", "--enable-unsafe-swiftshader"],
  });
  const page = await browser.newPage();
  await page.goto("about:blank");

  const results = await page.evaluate(
    ([sources, pairs]) => {
      const canvas = document.createElement("canvas");
      const gl = canvas.getContext("webgl2");
      if (!gl) return [{ fatal: "no webgl2 context" }];
      const out = [];
      for (const p of pairs) {
        const r = { name: p.vert + " + " + p.frag, where: p.where, logs: [] };
        const mk = (type, src, label) => {
          const sh = gl.createShader(type);
          gl.shaderSource(sh, src);
          gl.compileShader(sh);
          const ok = gl.getShaderParameter(sh, gl.COMPILE_STATUS);
          const log = (gl.getShaderInfoLog(sh) || "").trim();
          if (!ok) r.logs.push(label + " compile FAILED:\n" + log);
          else if (log) r.logs.push(label + " warning:\n" + log);
          return ok ? sh : null;
        };
        const v = mk(gl.VERTEX_SHADER, sources[p.vert], p.vert);
        const f = mk(gl.FRAGMENT_SHADER, sources[p.frag], p.frag);
        if (!v || !f) {
          r.ok = false;
          out.push(r);
          continue;
        }
        const prog = gl.createProgram();
        gl.attachShader(prog, v);
        gl.attachShader(prog, f);
        gl.linkProgram(prog);
        const ok = gl.getProgramParameter(prog, gl.LINK_STATUS);
        const log = (gl.getProgramInfoLog(prog) || "").trim();
        if (!ok) r.logs.push("link FAILED:\n" + log);
        else if (log) r.logs.push("link warning:\n" + log);
        r.ok = ok;
        if (ok) {
          r.uniforms = [];
          const n = gl.getProgramParameter(prog, gl.ACTIVE_UNIFORMS);
          for (let i = 0; i < n; i++) r.uniforms.push(gl.getActiveUniform(prog, i).name);
        }
        out.push(r);
      }
      return out;
    },
    [sources, pairs]
  );

  await browser.close();

  let bad = 0;
  for (const r of results) {
    if (r.fatal) {
      console.error("FATAL: " + r.fatal);
      process.exit(2);
    }
    console.log((r.ok ? "ok   " : "FAIL ") + r.name + "   (" + r.where + ")");
    if (r.uniforms) console.log("       uniforms: " + r.uniforms.join(", "));
    for (const l of r.logs) console.log("       " + l.replace(/\n/g, "\n       "));
    if (!r.ok) bad++;
  }
  console.log(bad === 0 ? "\nALL SHADERS COMPILE AND LINK" : `\n${bad} program(s) failed`);
  process.exit(bad === 0 ? 0 : 1);
})().catch((e) => {
  console.error(e);
  process.exit(2);
});
