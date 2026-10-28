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
    for m in re.finditer(r'^(?:@\w+\s*)?(?:private |internal |public )?(?:const |data |sealed |abstract |open |enum |value )*(?:class|object|interface|fun|val|var)\s+(\w+)', src, re.M):
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
    'ChipRow': ('selectedIndex', 1),
    'ToggleRow': ('checked', 1),
}
# AppState properties whose getter reads no snapshot state, worked out from
# AppState itself so this stays true as it changes. `volume` was one: a slider
# bound to it could not move, and no amount of snapshot state elsewhere in the
# same composable would have helped.
_appstate = 'app/src/main/java/app/hypnotica/ui/AppState.kt'
LEAKY = []
if glob.glob(_appstate):
    _src = strip(open(_appstate).read())
    _snap = set(re.findall(r'(?:var|val)\s+(\w+)\s+by\s+mutable', _src))
    for _m in re.finditer(
            r'\n    (?:var|val)\s+(\w+)\s*:\s*[\w<>?, .]+\n?\s*get\(\)\s*=\s*([^\n]+)',
            _src):
        if not any(_s in _m.group(2) for _s in _snap):
            LEAKY.append(_m.group(1))

# Reads Compose cannot observe: plain fields and map lookups.
INVISIBLE = re.compile(
    r'\b(?:paramValue|macroPosition|formulaOn|paramText)\s*\(|'
    r'\b(?:project|mapping|engine|clip|track)\s*\.|'
    r'\.params\s*\.' +
    (r'|\bstate\.(?:' + '|'.join(LEAKY) + r')\b' if LEAKY else ''))
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

# 13. A local function used before it is declared, or marked private.
#
#     Kotlin resolves local declarations in order, so a helper defined below its
#     callers inside the same function body is simply not in scope — and
#     `private` is not applicable to a local declaration at all. Both are
#     compile errors, and a composable in this project is a long body full of
#     local `fun`s, so both are easy to write. This cost a CI round trip, which
#     is the only compiler available here for the UI.
NESTED = re.compile(r'\n(\s{4,})((?:private\s+)?(?:suspend\s+)?)fun\s+(\w+)\s*\(')

for f in files:
    src = strip(open(f).read())
    for fn, body, offset in bodies(src):
        for m in NESTED.finditer(body):
            modifiers, name = m.group(2), m.group(3)
            at = m.start()
            line = src[:offset + at].count('\n') + 2
            if 'private' in modifiers:
                problems.append(
                    f"{f}:{line}: 'private fun {name}' is local to {fn}() — "
                    f"'private' is not applicable to a local declaration")
            # The first call to it anywhere in the enclosing body.
            use = re.search(r'(?<![\w.])' + name + r'\s*\(', body)
            if use and use.start() < at:
                useline = src[:offset + use.start()].count('\n') + 2
                problems.append(
                    f"{f}:{useline}: {name}() is called here but declared "
                    f"below, on line {line}, inside {fn}() — a local function "
                    f"is only in scope after its declaration")

        # The same mistake with a local value rather than a local function,
        # which is what the second round trip was spent on: a `remember(version,
        # ...)` eighty lines above `val version = revision`. Only names this
        # file declares exactly once are considered, so a local that shadows
        # something from an outer scope is left alone.
        for m in re.finditer(r'\n\s{4,}(?:val|var)\s+(\w+)\s*[:=]', body):
            name, at = m.group(1), m.start()
            if len(re.findall(r'(?:val|var)\s+' + name + r'\s*[:=]', src)) != 1:
                continue
            use = re.search(r'(?<![\w.])' + name + r'(?![\w(])', body)
            if use and use.start() < at:
                line = src[:offset + at].count('\n') + 2
                useline = src[:offset + use.start()].count('\n') + 1
                problems.append(
                    f"{f}:{useline}: '{name}' is used here but declared below, "
                    f"on line {line}, inside {fn}() — a local value is only in "
                    f"scope after its declaration")

# 14. The same local name declared twice in one scope.
#
#     Rule 5 catches this at package level. It happens just as easily inside a
#     composable, which here is a long body edited in pieces by whoever is
#     adding a control, and a second `val knobs` cost a CI round trip.
#
#     Scope is found by walking the braces, not the indentation. The first
#     draft of this rule compared indentation and immediately flagged the two
#     `val shown` in ControlDrawer's if/else — same column, different branches,
#     entirely legal. Only declarations in the same block are compared, and a
#     name may be shadowed in any block nested inside it.
for f in files:
    src = strip(open(f).read())
    for fn, body, offset in bodies(src):
        # One frame per block, holding the names declared directly in it.
        frames = [{}]
        i = 0
        decl = re.compile(r'(?:val|var)\s+(\w+)\s*[:=]')
        while i < len(body):
            c = body[i]
            if c == '{':
                frames.append({})
                i += 1
                continue
            if c == '}':
                if len(frames) > 1:
                    frames.pop()
                i += 1
                continue
            m = decl.match(body, i)
            # Must start on a word boundary, or "oval x" would match.
            if m and (i == 0 or not (body[i - 1].isalnum() or body[i - 1] == '_')):
                name = m.group(1)
                line = src[:offset + i].count('\n') + 1
                if name in frames[-1]:
                    problems.append(
                        f"{f}:{line}: '{name}' is already declared in this "
                        f"block of {fn}(), on line {frames[-1][name]} — "
                        f"conflicting declarations")
                else:
                    frames[-1][name] = line
                i = m.end()
                continue
            i += 1

# 15. String.format without an explicit locale.
#
#     Every numeric readout in the app goes through it, and the default locale
#     is the phone's: on a comma-decimal or Arabic-Indic device every slider
#     value, time and percentage changes shape, and the replay JSON stateJson()
#     writes stops being parseable by anything expecting a dot.
for f in sorted(glob.glob('app/src/main/java/app/hypnotica/**/*.kt', recursive=True)):
    src = strip(open(f).read())
    for m in re.finditer(r'String\.format\s*\(((?:[^()]|\([^()]*\))*)\)', src, re.S):
        args = m.group(1)
        line = src[:m.start()].count('\n') + 1
        locales = args.count('Locale')
        if locales == 0:
            problems.append(
                f"{f}:{line}: String.format without a locale — the phone's own "
                f"locale decides the decimal separator, so pass "
                f"java.util.Locale.US")
        elif locales > 1:
            # A sweep that added one to every call gave a second to the calls
            # that already had theirs on the following line.
            problems.append(
                f"{f}:{line}: String.format with {locales} locale arguments")
        elif not re.match(r'\s*(?:java\.util\.)?Locale\.', args):
            problems.append(
                f"{f}:{line}: String.format's locale is not its first argument")

# 16. A lowercase Compose function used without its import.
#
#     Rule 1 checks capitalised symbols, so a missing `Text` or `LaunchedEffect`
#     is caught, and rule 2 checks modifier extensions. A bare lowercase call
#     like `remember(` is neither, and a composable that uses one without the
#     import fails to compile with a cascade of unrelated-looking errors — a
#     type that cannot be inferred, a @Composable invocation "outside a
#     @Composable function" — none of which name the real cause. One build was
#     spent reading those.
COMPOSE_CALLS = [
    'remember', 'mutableStateOf', 'mutableIntStateOf', 'mutableFloatStateOf',
    'mutableLongStateOf', 'mutableDoubleStateOf', 'mutableStateMapOf',
    'mutableStateListOf', 'derivedStateOf', 'rememberCoroutineScope',
    'rememberScrollState', 'rememberLazyListState', 'rememberLazyGridState',
    'produceState', 'rememberUpdatedState', 'collectAsState', 'snapshotFlow',
    'rememberSaveable', 'rememberInfiniteTransition', 'rememberTextMeasurer',
]
for f in files:
    raw = open(f).read()
    src = strip(raw)
    if re.search(r'^import androidx\.compose\.\w+\.\*$', raw, re.M):
        continue
    imported = set(re.findall(r'^import (?:.*\.)?(\w+)$', raw, re.M))
    local = set(re.findall(r'\bfun\s+(\w+)\s*\(', src))
    for name in COMPOSE_CALLS:
        if name in imported or name in local:
            continue
        m = re.search(r'(?<![\w.])' + name + r'\s*[({]', src)
        if m:
            line = src[:m.start()].count('\n') + 1
            problems.append(
                f"{f}:{line}: uses {name}( but does not import it")

# 17. Snapshot state declared below an init block.
#
#     Kotlin creates property delegates and runs init blocks in declaration
#     order, so a `by mutableStateOf` property declared BELOW an init block does
#     not exist while that block runs. Writing to it there is a null dereference
#     inside the constructor, and for AppState that meant the app did not launch
#     at all — no screen, no message, just a process that died. It shipped:
#     AppState.init calls load(), load() calls soundChanged(), and soundVersion
#     was declared three hundred lines below the init block.
#
#     The first version of this rule traced what init could reach — its body,
#     the functions it calls, and the functions those call — and flagged only
#     properties actually written. That worked, but it is a heuristic standing
#     between the app and a crash with no message, and one missed indirection
#     makes it silent. Every class in this app happens to declare all of its
#     snapshot state above its init block already, so the rule is the flat
#     thing instead: none of it goes below. A write that does not exist today
#     cannot be added tomorrow by someone who has never heard of this.
STATE_DECL = re.compile(
    r'\n\s+(?:private\s+)?(?:var|val)\s+(\w+)\s+by\s+mutable\w*StateOf')

for f in files:
    src = strip(open(f).read())
    init = re.search(r'\n    init\s*\{', src)
    if not init:
        continue
    init_at = src[:init.start()].count('\n') + 2
    for m in STATE_DECL.finditer(src):
        line = src[:m.start()].count('\n') + 2
        if line <= init_at:
            continue
        problems.append(
            f"{f}:{line}: '{m.group(1)}' is snapshot state declared below the "
            f"init block on line {init_at} — delegates are created in "
            f"declaration order, so anything the constructor reaches that "
            f"writes it throws before the app can start. Move it above init")

# 18. Spacing and radius off the design system.
#
#     DESIGN: "4 dp grid, 16 dp gutters, 8 dp between related controls and 16 dp
#     between groups, 48 dp minimum touch target, 52 dp primary actions. Radius
#     12 dp cards, 24 dp sheets, full pill for chips."
#
#     The code had fourteen distinct off-grid values across 76 uses and nine
#     different corner radii, so page gutters shifted by up to 6 dp as you moved
#     between screens and the same gap was written as 6, 10, 12 or 14 depending
#     on who wrote it. Individually invisible; together, it is most of what
#     "cobbled together" means. There is one scale now, and this keeps it.
RADII_ALLOWED = {'12', '24'}
HAIRLINES = {'1', '1.5', '2', '3'}

for f in files:
    if '/ui/' not in f and not f.endswith('MainActivity.kt'):
        continue
    src = strip(open(f).read())
    for m in re.finditer(r'RoundedCornerShape\(\s*([\d.]+)\.dp', src):
        v = m.group(1)
        # The progress bar is 6 dp tall; a card radius on it would be a lozenge.
        if v in RADII_ALLOWED or v == '3':
            continue
        line = src[:m.start()].count('\n') + 1
        problems.append(
            f"{f}:{line}: corner radius {v}.dp — the contract is 12 for cards "
            f"and inputs, 24 for sheets, and a pill for chips")
    for m in re.finditer(r'(?<![\w.])(\d+(?:\.\d+)?)\.dp', src):
        v = m.group(1)
        if v in HAIRLINES:
            continue
        if float(v) % 4 == 0:
            continue
        line = src[:m.start()].count('\n') + 1
        problems.append(
            f"{f}:{line}: {v}.dp is off the 4 dp grid")

# 19. A scope-sensitive Compose function called without its scope.
#
#     AnimatedVisibility has a plain overload and ColumnScope/RowScope
#     extensions in the same package. Inside a Box there is no such receiver, and
#     Kotlin reports it as "cannot be called in this context with an implicit
#     receiver" followed by two unrelated-looking "@Composable invocations can
#     only happen from the context of a @Composable function" errors several
#     lines away. A build went on reading those. Using it directly inside a Box
#     is the case that bites; in a Column or Row it is what you want.
for f in files:
    src = strip(open(f).read())
    for m in re.finditer(r'(?<![\w.])Box\s*\(', src):
        # The Box's own body, brace-balanced from its opening brace.
        close = src.find('{', m.end())
        if close < 0:
            continue
        depth, i = 0, close
        while i < len(src):
            if src[i] == '{':
                depth += 1
            elif src[i] == '}':
                depth -= 1
                if depth == 0:
                    break
            i += 1
        body = src[close:i]
        # Only the Box's own level: a Column nested inside it is fine, and
        # that is where the player's AnimatedVisibility calls live. Stripping
        # innermost braces ONCE leaves anything two levels down still visible,
        # which flagged those as broken when they compile. Repeat to a fixed
        # point so every nested block goes.
        shallow = body
        while True:
            stripped = re.sub(r'\{[^{}]*\}', '', shallow)
            if stripped == shallow:
                break
            shallow = stripped
        hit = re.search(r'(?<![\w.])AnimatedVisibility\s*\(', shallow)
        if hit:
            line = src[:m.start()].count('\n') + 1
            problems.append(
                f"{f}:{line}: AnimatedVisibility directly inside a Box — it "
                f"resolves to the ColumnScope overload and will not compile; "
                f"animate an alpha, or put it in a Column")

# 20. A stacking slot handed a column's worth of siblings.
#
# AnimatedContent and Crossfade lay their content out the way a Box does, so
# everything the slot emits is drawn at the same position. That is invisible
# when the slot holds one composable and catastrophic when it holds a call that
# emits a card: Simple mode's whole drawer drew on one line — the macro slider,
# its caption, the section label and the next slider all at the same y, two
# thumbs on top of each other, the readouts overlapping in the corner. It
# compiles, so only this or a screenshot can catch it.
#
# The test is what the slot OPENS with. A layout first is always fine. Anything
# else, with more than one composable call anywhere inside, is the broken shape
# — including a `when` over cards, which is what hid this: strip the nested
# braces and a `when` leaves nothing capitalised behind to count.
LAYOUTS = ('Column', 'Row', 'Box', 'LazyColumn', 'LazyRow', 'LazyVerticalGrid',
           'Scaffold', 'FlowRow', 'BoxWithConstraints')
for f in files:
    src = strip(open(f).read())
    for m in re.finditer(r'(?<![\w.])(AnimatedContent|Crossfade)\s*\(', src):
        i = src.find('(', m.end() - 1)
        depth, j = 0, i
        while j < len(src):
            if src[j] == '(':
                depth += 1
            elif src[j] == ')':
                depth -= 1
                if depth == 0:
                    break
            j += 1
        k = src.find('{', j)
        if k < 0:
            continue
        depth, e = 0, k
        while e < len(src):
            if src[e] == '{':
                depth += 1
            elif src[e] == '}':
                depth -= 1
                if depth == 0:
                    break
            e += 1
        body = src[k + 1:e]
        # The lambda's own parameter list ("card ->") is not content.
        body = re.sub(r'\A[^\n{}]*->', '', body, count=1)
        head = body.strip()
        if head.startswith(LAYOUTS):
            continue
        # More than one composable call inside means the slot is being asked to
        # stack them. One call may still emit many, but this cannot know that.
        calls = re.findall(r'(?<![\w.])([A-Z]\w*)\s*[({]', body)
        if len(calls) > 1:
            line = src[:m.start()].count('\n') + 1
            problems.append(
                f"{f}:{line}: {m.group(1)} lays its content out like a Box, and "
                f"this slot emits more than one thing — open it with a Column or "
                f"Row, or everything inside draws on top of everything else")

# 21. A gesture handler holding a value from the composition it started in.
#
# pointerInput's block is a coroutine that lives until its keys change. A
# plain `val` read from state above it is captured once, when the block first
# runs, and never seen again. The feed did exactly this: `val index =
# state.feedIndex`, then a swipe handler keyed on the list size computing
# `index + 1` — so every swipe after the first computed "next" from the sim
# the feed opened on, the second swipe landed on the sim already showing, and
# the infinite feed reached three sims. Read the state inside the handler, or
# key the block on the value.
for f in files:
    src = strip(open(f).read())
    for m in re.finditer(r'(?:\.pointerInput|(?<![\w.])LaunchedEffect|(?<![\w.])DisposableEffect)\(([^)]*)\)\s*\{', src):
        keys = set(re.findall(r'\w+', m.group(1)))
        # The enclosing composable: from its `fun` to this point.
        start = src.rfind('\nfun ', 0, m.start())
        start = max(start, src.rfind('\n    fun ', 0, m.start()), src.rfind('@Composable', 0, m.start()))
        prefix = src[start:m.start()]
        captured = re.findall(r'(?m)^\s*val (\w+)\s*=\s*state\.\w+\s*$', prefix)
        if not captured:
            continue
        depth, i = 0, m.end() - 1
        while i < len(src):
            if src[i] == '{': depth += 1
            elif src[i] == '}':
                depth -= 1
                if depth == 0: break
            i += 1
        body = src[m.end():i]
        for name in captured:
            if name in keys:
                continue
            if re.search(r'(?<![\w.])' + name + r'(?!\w)', body):
                line = src[:m.start()].count('\n') + 1
                problems.append(
                    f"{f}:{line}: a long-lived block (gesture handler or effect) reads `{name}`, a value taken from "
                    f"state when the composable ran — the handler keeps the first "
                    f"one forever. Read the state inside the handler, or add "
                    f"`{name}` to the keys")

for p in problems:
    print(p)
print(f"\n{len(problems)} problem(s) across {len(files)} files")
sys.exit(1 if problems else 0)
