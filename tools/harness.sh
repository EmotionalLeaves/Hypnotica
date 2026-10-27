#!/usr/bin/env bash
# Compile a throwaway main() against the engine and run it.
#
# This is how a claim about the engine gets measured rather than argued: write
# a file with a main(), point this at it, read the numbers. The engine is cached
# as a jar and only rebuilt when a source is newer, so a run costs seconds
# rather than the minute a full compile takes.
#
#   tools/harness.sh path/to/Probe.kt ProbeKt
#
# The second argument is the JVM class name Kotlin gives the file: Probe.kt with
# a top-level main() becomes ProbeKt.
set -uo pipefail
cd "$(dirname "$0")/.."
PROBE=${1:?usage: harness.sh <Probe.kt> <ProbeKt>}
MAIN=${2:?usage: harness.sh <Probe.kt> <ProbeKt>}
OUT="${TMPDIR:-/tmp}/hypnotica-harness"
JAR="$OUT/engine.jar"
G=${GRADLE_LIB:-/opt/gradle/lib}
KC="$G/kotlin-compiler-embeddable-2.0.21.jar:$G/kotlin-stdlib-2.0.21.jar:$G/kotlin-reflect-2.0.21.jar:$G/kotlin-script-runtime-2.0.21.jar:$G/kotlin-daemon-embeddable-2.0.21.jar:$G/kotlinx-coroutines-core-jvm-1.6.4.jar:$G/kotlinx-metadata-jvm-0.5.0.jar:$G/annotations-24.0.1.jar:$G/trove4j-1.0.20200330.jar"
STD="$G/kotlin-stdlib-2.0.21.jar:$G/annotations-24.0.1.jar"

BASE=app/src/main/java/app/hypnotica
FILES=$(find "$BASE/core/sim" "$BASE/core/laws" "$BASE/core/recipe" "$BASE/core/safety" \
             "$BASE/core/nd" "$BASE/core/studio" "$BASE/core/director" "$BASE/core/preview" \
             "$BASE/core/perf" "$BASE/sims" -name '*.kt' | grep -vE 'LuaRecipe.kt|RecipeStore.kt')
FILES="$FILES $BASE/core/render/G.kt $BASE/core/render/Camera.kt $BASE/core/render/Palette.kt"
# The whole of core/audio, against stand-ins for the four android.media classes
# the synth opens its output stream with. The sound itself is arithmetic, so
# this compiles everything that decides what is heard, and lets a probe render
# a sim's audio offline and measure it. SoundMapping used to be compiled only
# by CI, which is how a removed sweep could leave a dangling reference behind.
FILES="$FILES $(find "$BASE/core/audio" -name '*.kt') $(find tools/stubs -name '*.kt')"

mkdir -p "$OUT"
STALE=no
if [ ! -f "$JAR" ]; then STALE=yes; else
  for f in $FILES; do [ "$f" -nt "$JAR" ] && STALE=yes && break; done
fi
if [ "$STALE" = yes ]; then
  echo "(rebuilding engine jar)" >&2
  java -cp "$KC" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -classpath "$STD" -d "$JAR" -jvm-target 17 $FILES > "$OUT/engine.log" 2>&1
  STATUS=$?
  grep -viE "^Picked up|warning:" "$OUT/engine.log" || true
  # Stop here on a broken engine. Carrying on ran the probe against the last jar
  # that compiled, which silently measured the previous version of the code and
  # reported it as the current one.
  if [ $STATUS -ne 0 ]; then
    rm -f "$JAR"
    echo "ENGINE DID NOT COMPILE - probe not run" >&2
    exit 2
  fi
fi

rm -rf "$OUT/probe"; mkdir -p "$OUT/probe"
java -cp "$KC" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -classpath "$STD:$JAR" -d "$OUT/probe" -jvm-target 17 "$PROBE" 2>&1 |
  grep -viE "^Picked up|warning:" || true
java -cp "$OUT/probe:$JAR:$G/kotlin-stdlib-2.0.21.jar" "$MAIN"
