#!/usr/bin/env bash
# Type-check the Android-free half of the engine, with no Android SDK.
#
# There is no Android SDK in the environment this project is usually worked on
# from, so the only real compiler is CI — which costs a few minutes a go. Most
# of the engine does not touch Android at all, though, and Gradle ships a
# Kotlin compiler: this compiles that half directly, in about forty seconds.
#
# What it cannot reach: everything under ui/, engine/ and export/ (Compose and
# the GL bindings), and the two recipe files (they need the luaj jar, which
# only exists in the Gradle cache on the runner). tools/uicheck.py approximates
# the compiler for those.
#
#   tools/typecheck.sh
set -uo pipefail
cd "$(dirname "$0")/.."
OUT="${TMPDIR:-/tmp}/hypnotica-typecheck"
G=${GRADLE_LIB:-/opt/gradle/lib}
KC="$G/kotlin-compiler-embeddable-2.0.21.jar:$G/kotlin-stdlib-2.0.21.jar:$G/kotlin-reflect-2.0.21.jar:$G/kotlin-script-runtime-2.0.21.jar:$G/kotlin-daemon-embeddable-2.0.21.jar:$G/kotlinx-coroutines-core-jvm-1.6.4.jar:$G/kotlinx-metadata-jvm-0.5.0.jar:$G/annotations-24.0.1.jar:$G/trove4j-1.0.20200330.jar"
STD="$G/kotlin-stdlib-2.0.21.jar:$G/annotations-24.0.1.jar"

if [ ! -f "$G/kotlin-compiler-embeddable-2.0.21.jar" ]; then
  echo "No Kotlin compiler at $G — set GRADLE_LIB to a Gradle lib directory." >&2
  exit 2
fi

BASE=app/src/main/java/app/hypnotica
FILES=$(find "$BASE/core/sim" "$BASE/core/laws" "$BASE/core/recipe" "$BASE/core/safety" \
             "$BASE/core/nd" "$BASE/core/studio" "$BASE/core/director" "$BASE/core/preview" \
             "$BASE/core/perf" "$BASE/sims" -name '*.kt' | grep -vE 'LuaRecipe.kt|RecipeStore.kt')
FILES="$FILES $BASE/core/render/G.kt $BASE/core/render/Camera.kt $BASE/core/render/Palette.kt $BASE/core/audio/Scales.kt"

rm -rf "$OUT"; mkdir -p "$OUT"
java -cp "$KC" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -classpath "$STD" -d "$OUT/classes" -jvm-target 17 $FILES > "$OUT/log" 2>&1
STATUS=$?
grep -v "^Picked up" "$OUT/log"
# The compiler's verdict, not grep's: a clean build prints nothing, and piping
# the output through grep used to turn that silence into a failure.
if [ $STATUS -eq 0 ]; then
  echo "ENGINE TYPE-CHECK PASSED ($(echo $FILES | wc -w) files)"
else
  echo "ENGINE TYPE-CHECK FAILED"
fi
exit $STATUS
