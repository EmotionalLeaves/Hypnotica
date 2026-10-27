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

for p in problems:
    print(p)
print(f"\n{len(problems)} problem(s) across {len(files)} files")
sys.exit(1 if problems else 0)
