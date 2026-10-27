"""Catch unresolved references in the files the Kotlin type-check cannot reach.

The engine compiles locally; the Compose UI, the engine glue and the exporter
cannot, because there is no Android SDK here. This approximates the compiler's
resolution step: every capitalised symbol used must be imported, declared in the
same package, or part of the Kotlin standard library.
"""
import re, glob, sys

DIRS = ['app/src/main/java/app/hypnotica/ui',
        'app/src/main/java/app/hypnotica/engine',
        'app/src/main/java/app/hypnotica/export',
        'app/src/main/java/app/hypnotica']

STDLIB = set("""
String Int Float Long Double Boolean Byte Short Char Any Unit Nothing
List MutableList ArrayList Set MutableSet HashSet Map MutableMap HashMap LinkedHashMap
IntArray FloatArray BooleanArray ByteArray ShortArray LongArray DoubleArray CharArray Array
Pair Triple Result Comparable Iterable Sequence Regex StringBuilder
Exception Throwable RuntimeException IllegalStateException IllegalArgumentException
InterruptedException IndexOutOfBoundsException NullPointerException
Math System Thread Runnable Volatile JvmStatic JvmField Suppress Deprecated
Override SuppressLint Composable Preview File FileNotFoundException
ArrayDeque Arrays Collections Objects Char Number Enum Lazy Function0 Function1
Context Build Bundle Uri Intent Color
""".split())

# Modifier extensions that are members of a layout scope: no import needed.
SCOPE_MEMBERS = {'weight', 'align', 'matchParentSize', 'alignByBaseline', 'paddingFrom',
                 'animateItemPlacement'}

# Modifier extensions that DO need an import, with where they come from.
NEEDS_IMPORT = {
    'width': 'androidx.compose.foundation.layout.width',
    'height': 'androidx.compose.foundation.layout.height',
    'size': 'androidx.compose.foundation.layout.size',
    'padding': 'androidx.compose.foundation.layout.padding',
    'fillMaxSize': 'androidx.compose.foundation.layout.fillMaxSize',
    'fillMaxWidth': 'androidx.compose.foundation.layout.fillMaxWidth',
    'fillMaxHeight': 'androidx.compose.foundation.layout.fillMaxHeight',
    'offset': 'androidx.compose.foundation.layout.offset',
    'heightIn': 'androidx.compose.foundation.layout.heightIn',
    'widthIn': 'androidx.compose.foundation.layout.widthIn',
    'aspectRatio': 'androidx.compose.foundation.layout.aspectRatio',
    'safeDrawingPadding': 'androidx.compose.foundation.layout.safeDrawingPadding',
    'clip': 'androidx.compose.ui.draw.clip',
    'background': 'androidx.compose.foundation.background',
    'border': 'androidx.compose.foundation.border',
    'clickable': 'androidx.compose.foundation.clickable',
    'verticalScroll': 'androidx.compose.foundation.verticalScroll',
    'horizontalScroll': 'androidx.compose.foundation.horizontalScroll',
    'pointerInput': 'androidx.compose.ui.input.pointer.pointerInput',
    'onFocusChanged': 'androidx.compose.ui.focus.onFocusChanged',
}

files = []
for d in DIRS:
    files += [f for f in glob.glob(d + '/*.kt')]
files = sorted(set(files))

# everything declared anywhere in app.hypnotica.ui (same package = no import needed)
package_decls = {}
for f in glob.glob('app/src/main/java/app/hypnotica/**/*.kt', recursive=True):
    src = open(f).read()
    pkg = re.search(r'^package ([\w.]+)', src, re.M)
    pkg = pkg.group(1) if pkg else ''
    for m in re.finditer(r'^(?:@\w+\s*)?(?:private |internal |public )?(?:data |sealed |abstract |open |enum |value )*(?:class|object|interface|fun|val|var)\s+(\w+)', src, re.M):
        package_decls.setdefault(pkg, set()).add(m.group(1))

problems = []
def infer(tail: str):
    """The type of a property written without one, as far as it can be read."""
    m = re.search(r'mutableStateOf<([\w.]+)', tail) or re.search(r'=\s*([A-Z][\w.]*)\(', tail)
    if m:
        return m.group(1).split('.')[0]
    m = re.search(r'(?:mutableStateOf|=)\s*\(?\s*(true|false)\b', tail)
    if m:
        return 'Boolean'
    m = re.search(r'mutableStateOf\(\s*([A-Z][\w]*)\.', tail)
    if m:
        return m.group(1)
    m = re.search(r'mutableStateOf\(\s*([A-Z][\w]*)\(', tail)
    if m:
        return m.group(1)
    if re.search(r'mutableIntStateOf|=\s*-?\d+\s*$', tail):
        return 'Int'
    if re.search(r'mutableFloatStateOf|=\s*-?[\d.]+f', tail):
        return 'Float'
    if re.search(r'mutableStateOf\(\s*"', tail) or re.search(r'=\s*"', tail):
        return 'String'
    return None


def strip(src: str) -> str:
    """Remove comments and string literals; words inside them are not symbols."""
    src = re.sub(r'""".*?"""', '""', src, flags=re.S)
    src = re.sub(r'(?<!\\)"(?:\\.|[^"\\\n])*"', '""', src)
    src = re.sub(r'//[^\n]*', '', src)
    src = re.sub(r'/\*.*?\*/', '', src, flags=re.S)
    return src


for f in files:
    raw = open(f).read()
    src = strip(raw)
    pkg = re.search(r'^package ([\w.]+)', raw, re.M).group(1)
    imports = set(re.findall(r'^import (?:.*\.)?(\w+)$', raw, re.M))
    full_imports = set(re.findall(r'^import ([\w.]+)$', raw, re.M))
    star = set(re.findall(r'^import ([\w.]+)\.\*$', raw, re.M))
    local = set(re.findall(r'\b(?:class|object|interface|enum class|fun|val|var)\s+(\w+)', src))
    # entries of an enum declared in this file are names in scope too
    for body in re.findall(r'enum class \w+[^{]*\{(.*?)\n\}', src, re.S):
        local |= set(re.findall(r'\b([A-Z][A-Z0-9_]*)\s*[(,;]', body))
        local |= set(re.findall(r'\b([A-Z][A-Z0-9_]*)\s*$', body, re.M))
    known = imports | local | STDLIB | package_decls.get(pkg, set())

    # 1. capitalised symbols used as calls or types
    used = set(re.findall(r'(?<![\w.])([A-Z][A-Za-z0-9_]*)\s*[(<]', src))
    used |= set(re.findall(r':\s*([A-Z][A-Za-z0-9_]*)', src))
    used |= set(re.findall(r'(?<![\w.])([A-Z][A-Za-z0-9_]*)\.', src))
    for sym in sorted(used):
        if sym not in known and not star:
            problems.append(f"{f}: '{sym}' is used but not imported or declared")

    # 2. modifier extensions that need an import
    for sym, imp in NEEDS_IMPORT.items():
        if re.search(r'\.' + sym + r'\(', src) and imp not in full_imports:
            problems.append(f"{f}: uses .{sym}( but does not import {imp}")

    # 3. property delegation needs getValue/setValue
    if re.search(r'\bby (remember|mutable|derived)', src):
        if 'androidx.compose.runtime.getValue' not in full_imports:
            problems.append(f"{f}: uses 'by remember/mutableStateOf' without importing getValue")
        if re.search(r'\bvar \w+ by ', src) and 'androidx.compose.runtime.setValue' not in full_imports:
            problems.append(f"{f}: has 'var x by ...' without importing setValue")

# 4. a public `var x` and a `fun setX(sameType)` compile to one JVM method
for f in sorted(set(glob.glob('app/src/main/java/app/hypnotica/**/*.kt', recursive=True))):
    src = strip(open(f).read())
    props = {}
    for m in re.finditer(
            r'^\s*(private |internal )?va(l|r)\s+(\w+)\s*(?::\s*([\w<>?.]+))?([^\n]*)', src, re.M):
        if m.group(1) == 'private ':
            continue          # private properties get no accessors, so no clash
        props[m.group(3)] = (m.group(2), m.group(4) or infer(m.group(5)))
    for m in re.finditer(r'^\s*(?:private |internal )?fun\s+(set|get)([A-Z]\w*)\s*\(([^)]*)\)', src, re.M):
        name = m.group(2)[0].lower() + m.group(2)[1:]
        if name not in props:
            continue
        kind, declared = props[name]
        arg = m.group(3).split(':')[-1].strip().rstrip(',') if ':' in m.group(3) else ''
        if m.group(1) == 'set' and kind != 'r':
            continue          # a val has no setter to clash with
        if declared and arg and declared != arg:
            continue          # different parameter types are different methods
        line = src[:m.start()].count('\n') + 1
        problems.append(
            f"{f}:{line}: fun {m.group(1)}{m.group(2)}() has the same JVM "
            f"signature as the accessor of '{name}'")

# 5. one name declared twice at the top level of a package
#    A redeclaration is a compile error that no single-file check can see, and
#    the Android build is the only real compiler here, so catching it saves a
#    whole CI run.
declared = {}
for f in sorted(set(
        glob.glob('app/src/main/java/app/hypnotica/**/*.kt', recursive=True) +
        glob.glob('app/src/test/java/app/hypnotica/**/*.kt', recursive=True))):
    src = open(f).read()
    head = re.search(r'^package\s+([\w.]+)', src, re.M)
    package = head.group(1) if head else ''
    for m in re.finditer(
            r'^(?:public |internal |private |abstract |open |sealed |data |enum |annotation |value )*'
            r'(?:class|object|interface)\s+(\w+)', src, re.M):
        declared.setdefault((package, m.group(1)), []).append(f)
for (package, name), where in sorted(declared.items()):
    if len(set(where)) > 1:
        problems.append(
            f"{package}.{name} is declared in " + " and ".join(sorted(set(where))))

# 6. a payoff fired by the clock alone
#    DESIGN.md: "satisfaction is designed, not hoped for", and the payoff is
#    "the moment -- a snap, an escape, a completion". Eight sims used to set
#    payoffFrame from a bare `elapsed > 14f`, which fires whether or not
#    anything has happened and is what the arc is measured against.
CLOCK_OK = {'elapsed', 'dt', 'abs', 'payoffFrame', 'f', 'true', 'false'}
for f in sorted(glob.glob('app/src/main/java/app/hypnotica/sims/**/*.kt', recursive=True)):
    src = open(f).read()
    for m in re.finditer(r'^[ \t]*if\s*\((.+?)\)\s*\{?(.*(?:\n.*){0,2})', src, re.M):
        condition, body = m.group(1), m.group(2)
        if 'payoffFrame = true' not in body and 'payoffFrame = true' not in condition:
            continue
        if 'elapsed' not in condition:
            continue
        names = set(re.findall(r'[A-Za-z_]\w*', condition))
        # A flag the same block latches is bookkeeping, not content: `!reached`
        # only stops the payoff firing twice.
        latches = set(re.findall(r'(\w+)\s*=\s*true', body))
        if names - CLOCK_OK - latches:
            continue          # the condition tests something the sim is doing
        line = src[:m.start()].count('\n') + 1
        problems.append(
            f"{f}:{line}: the payoff fires on the clock alone ({condition.strip()}); "
            f"it has to be a moment in the run")

# 7. a law nothing honours
#    DESIGN.md: "a law nothing honours is a lie in a menu, and gets deleted
#    rather than documented". gravityDirX had no control for a year and
#    electricStrength cancelled out algebraically; neither was noticed.
laws_src = open('app/src/main/java/app/hypnotica/core/laws/Laws.kt').read()
# Constructor fields only: a `val x get() = ...` is derived from them and has
# nothing of its own to set.
law_fields = [m.group(1) for m in re.finditer(r'^    val (\w+):[^\n]*', laws_src, re.M)
              if 'get()' not in m.group(0)]
# The body of Laws, where pairForce reads the force law by bare name.
laws_body = laws_src.split('\n) {', 1)[-1]
readers = ''
for f in (glob.glob('app/src/main/java/app/hypnotica/sims/**/*.kt', recursive=True) +
          glob.glob('app/src/main/java/app/hypnotica/engine/*.kt') +
          glob.glob('app/src/main/java/app/hypnotica/core/laws/*.kt') +
          glob.glob('app/src/main/java/app/hypnotica/core/sim/*.kt') +
          glob.glob('app/src/main/java/app/hypnotica/export/*.kt')):
    readers += open(f).read()
writers = ''
for f in glob.glob('app/src/main/java/app/hypnotica/ui/*.kt'):
    writers += open(f).read()
for field in law_fields:
    # Either through a Laws reference, or by bare name inside Laws itself.
    read = (re.search(r'\b(?:laws|env\.laws|it)\.' + field + r'\b', readers) or
            re.search(r'(?<![.\w])' + field + r'\b', laws_body))
    if not read:
        problems.append(
            f"core/laws/Laws.kt: nothing reads '{field}', so the control for it "
            f"is a promise the sims do not keep")
    elif not re.search(r'\b' + field + r'\s*=', writers):
        problems.append(
            f"core/laws/Laws.kt: '{field}' is read by a sim but nothing in the "
            f"UI can set it")

# 8. a sim's declared lawKnobs must match the laws it reads
#    The Laws card offers exactly what the sim declares, so an annotation that
#    drifts from the code puts back the dead control it was added to remove.
KNOB_FIELDS = {
    'GRAVITY': ['gravity'],
    'GRAVITY_DIRECTION': ['gravityDirX', 'gravityDirY'],
    'PAIRWISE_FORCE': ['pairForce', 'massSign', 'gravityConstant', 'falloff', 'forceExpr'],
    'BOUNCE': ['bounce', 'restitution'],
    'FRICTION': ['friction'],
    'CONTACT': ['sticky', 'passThrough'],
    'LIGHT_SPEED': ['lightSpeed'],
    'CHARGE': ['chargeSign', 'electricStrength'],
    'SPACE': ['space'],
}
for f in sorted(glob.glob('app/src/main/java/app/hypnotica/sims/**/*.kt', recursive=True)):
    src = open(f).read()
    declared = set(re.findall(r'LawKnob\.(\w+)', src))
    if not declared:
        continue
    reads = set()
    for field in set(sum(KNOB_FIELDS.values(), [])):
        if re.search(r'\b(?:laws|env\.laws)\.' + field + r'\b', src):
            reads.add(field)
    for knob in sorted(declared):
        fields = KNOB_FIELDS.get(knob)
        if fields is None:
            problems.append(f"{f}: declares LawKnob.{knob}, which this check does not know")
        elif not (set(fields) & reads):
            problems.append(
                f"{f}: declares LawKnob.{knob} but reads none of {', '.join(fields)}")
    for knob, fields in sorted(KNOB_FIELDS.items()):
        if (set(fields) & reads) and knob not in declared:
            problems.append(
                f"{f}: reads {', '.join(sorted(set(fields) & reads))} but no sim here "
                f"declares LawKnob.{knob}, so the control for it is hidden")

# 9. a named argument that the constructor does not have
#    kcheck cannot compile the two Lua files (they need the luaj jar, which is
#    only in the Gradle cache), so a change to SimDef's shape broke the build
#    from a file no local check was reading. These calls are the shape every
#    sim and every recipe is built through, so they are worth checking by hand.
CONSTRUCTORS = {
    'SimDef': 'app/src/main/java/app/hypnotica/core/sim/Sim.kt',
    'Macro': 'app/src/main/java/app/hypnotica/core/recipe/Params.kt',
    'Clip': 'app/src/main/java/app/hypnotica/core/director/ClipDirector.kt',
    'ExportSettings': 'app/src/main/java/app/hypnotica/export/VideoExporter.kt',
}
accepted = {}
for name, path in CONSTRUCTORS.items():
    src = open(path).read()
    m = re.search(r'(?:data )?class ' + name + r'\s*\((.*?)\n\)', src, re.S)
    if not m:
        problems.append(f"{path}: cannot find the declaration of {name}")
        continue
    accepted[name] = set(re.findall(r'\bval (\w+)\s*:', m.group(1)))

for f in sorted(glob.glob('app/src/main/java/app/hypnotica/**/*.kt', recursive=True)):
    src = strip(open(f).read())
    for name, allowed in accepted.items():
        for m in re.finditer(r'\b' + name + r'\s*\(', src):
            # Not the declaration itself: there the token before each '=' is the
            # parameter's type, not an argument name.
            if re.search(r'class\s+$', src[:m.start()]):
                continue
            # Walk to the matching bracket, tracking depth so only the call's
            # own arguments are considered.
            depth, i, used = 0, m.end() - 1, []
            while i < len(src):
                ch = src[i]
                if ch in '([{':
                    depth += 1
                elif ch in ')]}':
                    depth -= 1
                    if depth == 0:
                        break
                elif depth == 1 and ch == '=' and src[i + 1:i + 2] != '=':
                    back = re.search(r'(\w+)\s*$', src[:i])
                    if back:
                        used.append(back.group(1))
                i += 1
            line = src[:m.start()].count('\n') + 1
            for arg in used:
                if arg not in allowed:
                    problems.append(
                        f"{f}:{line}: {name}(...) has no parameter named '{arg}'")

# 10. the Library mock-up must name real sims
#     docs/images/library.svg is a picture of a screen that renders def.title
#     and def.family verbatim, so a title or family it invents is a mock-up of
#     a screen the code cannot produce. Five of its six rows had drifted.
import html as _html
titles = {}
for f in glob.glob('app/src/main/java/app/hypnotica/sims/**/*.kt', recursive=True):
    src = open(f).read()
    for m in re.finditer(r'title = "([^"]+)",\s*\n\s*shelf = [^\n]*\n\s*family = "([^"]+)"', src):
        titles[m.group(1)] = m.group(2)
    # The thirteen sorts declare their titles in Algorithms.kt and share one
    # family, declared in SortingSim.kt.
    for m in re.finditer(r'override val title = "([^"]+)"', src):
        titles.setdefault(m.group(1), 'Sorting algorithms')
mock = 'docs/images/library.svg'
if titles and glob.glob(mock):
    nodes = [_html.unescape(t) for t in re.findall(r'>([^<>]*)</text>', open(mock).read())]
    for i, node in enumerate(nodes):
        want = titles.get(node)
        if want is None:
            continue
        got = nodes[i + 1] if i + 1 < len(nodes) else ''
        if got != want:
            problems.append(
                f"{mock}: \"{node}\" is shown under \"{got}\", but its family "
                f"is \"{want}\"")

# 11. the Lua files may only use the luaj calls already proven to compile
#     These two files need the luaj jar, which lives in the Gradle cache on the
#     CI runner, so kcheck cannot compile them and a wrong method name costs a
#     whole CI run to discover. Twice now. The allowed set was harvested from
#     the file as it stood in a green build; adding to it is a deliberate act,
#     and the next CI run is what confirms the addition.
LUAJ_PROVEN = set(['abs', 'add', 'arg', 'at', 'bar', 'bool', 'call', 'choice', 'cleanMessage', 'cos', 'delete', 'disc', 'emit', 'endsWith', 'equals', 'exceptionOrNull', 'exists', 'f', 'failure', 'findAll', 'flash', 'float', 'floor', 'fromName', 'get', 'getOrNull', 'glow', 'hsv', 'install', 'int', 'invoke', 'isEmpty', 'isNaN', 'isNotEmpty', 'isnil', 'isnumber', 'istable', 'line', 'load', 'lowercase', 'mkdirs', 'nanoTime', 'nextFloat', 'optdouble', 'optint', 'optjstring', 'parse', 'point', 'range', 'readText', 'rect', 'removeSuffix', 'replace', 'ring', 'sandbox', 'set', 'setRecipes', 'sin', 'sqrt', 'startsWith', 'success', 'take', 'toByteArray', 'toDouble', 'toFloat', 'toInt', 'toSet', 'toSimDef', 'toboolean', 'todouble', 'tojstring', 'trim', 'trimIndent', 'valueOf', 'varargsOf', 'writeText'])
for f in ['app/src/main/java/app/hypnotica/core/recipe/LuaRecipe.kt',
          'app/src/main/java/app/hypnotica/core/recipe/RecipeStore.kt']:
    if not glob.glob(f):
        continue
    src = strip(open(f).read())
    for m in re.finditer(r'\.([a-z][a-zA-Z0-9]*)\(', src):
        name = m.group(1)
        if name in LUAJ_PROVEN:
            continue
        line = src[:m.start()].count('\n') + 1
        problems.append(
            f"{f}:{line}: '.{name}(...)' is not in the set of calls known to "
            f"compile here — CI is the only compiler for this file, so add it "
            f"to LUAJ_PROVEN in tools/uicheck.py only once a build has passed")

# 12. A control that shows a value Compose cannot see changing.
#
#     This is the defect the user actually hit: "not all buttons and sliders
#     work". Every sim parameter lives in a ConcurrentHashMap the sim thread
#     reads each step, not in snapshot state, so a row that shows one and does
#     not also read a version counter never recomposes. The Kotlin is correct,
#     the handler runs, the value changes -- and the thumb springs back to where
#     it was, because the row was never told to redraw. Three rows shipped like
#     that, which is most of the sliders in both modes.
#
#     The check: if a control is handed a value that comes from one of these
#     invisible sources, the composable it sits in has to read something
#     snapshot-backed in its own body.
CONTROLS = {
    'ValueSlider': ('value', 1),
    'ChipRow': ('selected', 1),
    'ToggleRow': ('on', 1),
}
# Reads Compose cannot observe: plain fields and map lookups.
INVISIBLE = re.compile(
    r'\b(?:paramValue|macroPosition|formulaOn|paramText)\s*\(|'
    r'\b(?:project|mapping|engine|clip|track)\s*\.|'
    r'\.params\s*\.')
# Reading one of these in the body is what subscribes the composable. A bare
# `remember {}` is not on the list: with no keys it never re-evaluates, so it
# is not evidence of anything. Nor is a local called `version` -- the thing
# that counts is the snapshot property it was read from, which is a match in
# its own right.
SUBSCRIBED = re.compile(
    r'\b(?:paramVersion|soundVersion|exprEpoch|macroRevisions|favourites)\b|'
    r'\bmutable(?:State|IntState|FloatState|StateMap|StateList)Of\b|'
    r'\bcollectAsState(?:WithLifecycle)?\s*\(')


def args_of(src: str, open_paren: int):
    """Split one argument list into its top-level arguments."""
    depth, start, out = 0, open_paren + 1, []
    i = open_paren
    while i < len(src):
        c = src[i]
        if c in '([{':
            depth += 1
        elif c in ')]}':
            depth -= 1
            if depth == 0:
                out.append(src[start:i])
                return out, i
        elif c == ',' and depth == 1:
            out.append(src[start:i])
            start = i + 1
        i += 1
    return out, len(src)


def bodies(src: str):
    """Every function in the file, as (name, body). Brace-balanced."""
    for m in re.finditer(r'\bfun\s+(\w+)\s*\(', src):
        name = m.group(1)
        _, close = args_of(src, m.end() - 1)
        brace = src.find('{', close)
        if brace < 0 or '\n\n' in src[close:brace]:
            continue
        depth, i = 0, brace
        while i < len(src):
            if src[i] == '{':
                depth += 1
            elif src[i] == '}':
                depth -= 1
                if depth == 0:
                    break
            i += 1
        yield name, src[brace:i], brace


for f in files:
    src = strip(open(f).read())
    for fn, body, offset in bodies(src):
        subscribed = SUBSCRIBED.search(body) is not None
        for control, (named, position) in CONTROLS.items():
            for m in re.finditer(r'(?<![\w.])' + control + r'\s*\(', body):
                args, _ = args_of(body, m.end() - 1)
                shown = None
                for a in args:
                    kw = re.match(r'\s*(\w+)\s*=(?!=)', a)
                    if kw and kw.group(1) == named:
                        shown = a[kw.end():]
                if shown is None:
                    positional = [a for a in args if not re.match(r'\s*\w+\s*=(?!=)', a)]
                    if len(positional) > position:
                        shown = positional[position]
                if shown is None:
                    continue
                # The row usually hands over a local, not the call itself:
                #   val v = state.paramValue(p.key)
                #   ValueSlider(value = v, ...)
                # so resolve one level of local before judging it. Without this
                # the rule sees a bare 'v' and waves the defect through, which
                # is exactly what it did on its first draft.
                seen = set()
                while True:
                    bare = re.fullmatch(r'\s*(\w+)\s*', shown)
                    if not bare or bare.group(1) in seen:
                        break
                    seen.add(bare.group(1))
                    rhs = re.search(
                        r'\bval\s+' + bare.group(1) + r'\s*(?::[^=\n]+)?=([^\n]+)',
                        body)
                    if not rhs:
                        break
                    shown = rhs.group(1)
                if not INVISIBLE.search(shown):
                    continue
                if subscribed:
                    continue
                line = src[:offset + m.start()].count('\n') + 1
                problems.append(
                    f"{f}:{line}: {control} in {fn}() shows"
                    f" '{shown.strip()[:44]}', which Compose cannot see change,"
                    f" and {fn}() reads no snapshot state — the control will"
                    f" spring back instead of moving")

for p in problems:
    print(p)
print(f"\n{len(problems)} problem(s) across {len(files)} files")
sys.exit(1 if problems else 0)
