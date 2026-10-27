#!/usr/bin/env python3
"""Turn a JUnit test class into a runnable main(), so a test can be proven here.

CI is the only real compiler for this project from the environment it is usually
worked on from, and a round trip costs minutes. A test is worth nothing until it
has been seen to pass and, better, seen to fail against the defect it was
written for — so this strips the JUnit scaffolding, supplies small stand-ins for
the assertions, and hands the result to tools/harness.sh.

    tools/as-main.py app/src/test/java/app/hypnotica/MacroTest.kt /tmp/Run.kt MacroTest
    tools/harness.sh /tmp/Run.kt RunKt

It understands @Test, the assertions this project's tests use, and a companion
object inside the class. It is a convenience, not a JUnit implementation: a test
that passes here still has to pass in CI.
"""
import io, re, sys
src_path, out_path, cls = sys.argv[1], sys.argv[2], sys.argv[3]
src = io.open(src_path, encoding="utf-8").read()
imports, body = [], []
for line in src.split("\n"):
    if line.startswith("package "):
        continue
    if line.startswith("import "):
        if "org.junit" not in line:
            imports.append(line)
        continue
    body.append(line)
body = "\n".join(body)
body = re.sub(r"^ *@Test *\n", "", body, flags=re.M)
# Only the top-level test methods, not the private helpers.
tests = [m for m in re.findall(r"^    fun (\w+)\(\)", body, flags=re.M)]
helpers = """
var failures = 0
private fun fail(m: String) { println("FAIL $m"); failures++ }
fun assertTrue(m: String, c: Boolean) { if (!c) fail(m) else println("ok   $m") }
fun assertTrue(c: Boolean) = assertTrue("assertion", c)
fun assertEquals(m: String, a: Float, b: Float, eps: Float) {
    if (!(kotlin.math.abs(a - b) <= eps)) fail("$m: expected $a got $b") else println("ok   $m")
}
fun assertEquals(a: Float, b: Float, eps: Float) = assertEquals("value", a, b, eps)
fun assertEquals(m: String, a: Int, b: Int) {
    if (a != b) fail("$m: expected $a got $b") else println("ok   $m")
}
"""
runner = "\nfun main() {\n    val subject = %s()\n" % cls + "".join(
    '    println("-- %s"); subject.%s()\n' % (t, t) for t in tests
) + '    println(if (failures == 0) "\\n%s BODY PASSES" else "\\n$failures assertion(s) failed")\n}\n' % cls
io.open(out_path, "w", encoding="utf-8").write(
    "\n".join(imports) + "\n" + helpers + body + runner)
print("tests:", ", ".join(tests))
