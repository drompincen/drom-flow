#!/usr/bin/env python3
"""Structural splitter for single-class Java files.

Parses the class body ONCE into an ordered member table -- literal- and comment-aware, so `'{'`
in a brace-walking extractor cannot desynchronise it -- then moves whole members between files
and rewrites call sites token-aware.

The regex approach this replaces failed three ways: a space inside a modifier character class
matched a call site mid-body, a naive brace counter broke on char literals, and blanket
`private`->`static` substitution produced `static static`. A single structural pass has none of
those failure modes.
"""
import re, sys

IDENT = re.compile(r'[A-Za-z_$][A-Za-z0-9_$]*')


def scan(src, i, stop_at_top_level=None):
    """Advance past literals and comments; return the next index. Never enters a literal body."""
    n = len(src)
    while i < n:
        c = src[i]
        if c == '/' and i + 1 < n and src[i + 1] == '/':
            j = src.find('\n', i)
            i = n if j < 0 else j + 1
            continue
        if c == '/' and i + 1 < n and src[i + 1] == '*':
            j = src.find('*/', i)
            i = n if j < 0 else j + 2
            continue
        if c in '"\'`':
            q = c
            i += 1
            while i < n and src[i] != q:
                if src[i] == '\\':
                    i += 1
                i += 1
            i += 1
            continue
        return i
    return n


def match_brace(src, i):
    """Index of the brace closing src[i], skipping literals and comments."""
    depth = 0
    n = len(src)
    while i < n:
        i = scan(src, i)
        if i >= n:
            return None
        c = src[i]
        if c == '{':
            depth += 1
        elif c == '}':
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return None


class JavaFile:
    """head + ordered members + tail of a single top-level type."""

    def __init__(self, path):
        self.path = path
        src = open(path, encoding='utf-8').read()
        self.src = src
        open_brace = self._class_body_start(src)
        close_brace = match_brace(src, open_brace)
        if close_brace is None:
            raise ValueError(f'{path}: unbalanced class body')
        self.head = src[:open_brace + 1]
        self.tail = src[close_brace:]
        self.members = self._members(src, open_brace + 1, close_brace)

    @staticmethod
    def _class_body_start(src):
        i = 0
        n = len(src)
        while i < n:
            i = scan(src, i)
            if i < n and src[i] == '{':
                return i
            i += 1
        raise ValueError('no class body')

    @staticmethod
    def _members(src, start, end):
        """Split a class body into members: each is leading trivia + declaration + body/;."""
        out = []
        i = start
        chunk_start = i
        while i < end:
            j = scan(src, i)
            if j != i:
                i = j
                continue
            c = src[i]
            if c == '{':
                close = match_brace(src, i)
                if close is None or close > end:
                    break
                nl = src.find('\n', close)
                stop = end if nl < 0 or nl > end else nl + 1
                out.append(src[chunk_start:stop])
                i = stop
                chunk_start = i
                continue
            if c == ';':
                nl = src.find('\n', i)
                stop = end if nl < 0 or nl > end else nl + 1
                out.append(src[chunk_start:stop])
                i = stop
                chunk_start = i
                continue
            i += 1
        if chunk_start < end:
            # keep even a whitespace-only remainder: round-tripping must be byte-identical,
            # or the tool is silently editing files it claims only to reorganise
            out.append(src[chunk_start:end])
        return out

    # ---------- queries ----------

    @staticmethod
    def declared_name(member):
        """The member's own name: the identifier before '(' for a method, after class/record/
        interface/enum for a type, or the last identifier before '=' or ';' for a field."""
        text = member
        # strip leading trivia (comments/annotations) line by line
        lines = []
        for line in text.split('\n'):
            s = line.strip()
            if not s or s.startswith('//') or s.startswith('*') or s.startswith('/*') or s.startswith('@'):
                continue
            lines.append(line)
        if not lines:
            return None
        decl = ' '.join(lines[:4])
        m = re.search(r'\b(?:class|interface|enum|record)\s+([A-Za-z_$][\w$]*)', decl)
        if m:
            return m.group(1)
        m = re.search(r'([A-Za-z_$][\w$]*)\s*\(', decl)
        if m:
            return m.group(1)
        m = re.findall(r'([A-Za-z_$][\w$]*)\s*(?:=|;)', decl)
        if m:
            return m[-1]
        return None

    def by_name(self, name):
        return [m for m in self.members if self.declared_name(m) == name]

    def names(self):
        seen = []
        for m in self.members:
            n = self.declared_name(m)
            if n and n not in seen:
                seen.append(n)
        return seen

    def render(self, members=None):
        body = ''.join(members if members is not None else self.members)
        return self.head + body + self.tail


def make_static(member):
    """`private` -> package-private static, without ever producing `static static`."""
    lines = member.split('\n')
    out = []
    for line in lines:
        # Only a member declaration, which sits at exactly one indent level and never contains
        # `->`. Without both guards a `default ->` switch arm reads as a modifier run.
        m = re.match(r'^(    )((?:(?:private|protected|public|static|final|abstract|synchronized)\s+)+)(.*)$', line)
        if m and '->' not in line and not m.group(3).lstrip().startswith('//'):
            indent, mods, rest = m.groups()
            kept = [w for w in mods.split() if w not in ('private', 'protected', 'public')]
            if 'static' not in kept and not rest.lstrip().startswith('{'):
                kept.append('static')
            order = [w for w in ('static', 'final', 'abstract', 'synchronized', 'default') if w in kept]
            out.append(indent + (' '.join(order) + ' ' if order else '') + rest)
        else:
            out.append(line)
    return '\n'.join(out)


def qualify_calls(src, names, cls):
    """Prefix bare calls to moved methods with the new class, skipping `x.name(` and declarations."""
    out = []
    i = 0
    n = len(src)
    while i < n:
        j = scan(src, i)
        if j != i:
            out.append(src[i:j])
            i = j
            continue
        m = IDENT.match(src, i)
        if not m:
            out.append(src[i])
            i += 1
            continue
        word = m.group(0)
        end = m.end()
        prev = src[:i].rstrip()
        after = src[end:end + 40].lstrip()
        if (word in names and after.startswith('(')
                and not prev.endswith('.') and not prev.endswith('new')):
            out.append(cls + '.' + word)
        else:
            out.append(word)
        i = end
    return ''.join(out)


def qualify_types(text, names, owner):
    """Qualify references to nested types/constants that stay behind in the owner class."""
    out = []
    i = 0
    n = len(text)
    while i < n:
        j = scan(text, i)
        if j != i:
            out.append(text[i:j])
            i = j
            continue
        m = IDENT.match(text, i)
        if not m:
            out.append(text[i])
            i += 1
            continue
        word = m.group(0)
        prev = text[:i].rstrip()
        out.append(owner + '.' + word if word in names and not prev.endswith('.') else word)
        i = m.end()
    return ''.join(out)


def split(src_path, new_path, move_names, header, owner=None, owner_types=()):
    jf = JavaFile(src_path)
    missing = [n for n in move_names if not jf.by_name(n)]
    if missing:
        raise SystemExit(f'{src_path}: no such members: {missing}')

    moved, kept = [], []
    for m in jf.members:
        (moved if jf.declared_name(m) in move_names else kept).append(m)

    body = ''.join(make_static(m) for m in moved)
    body = re.sub(r'(?<![\w.])(FileFacts|ImportRef|Ref|TypeRef|DepRef)\b', r'Extractor.\1', body)
    body = body.replace('Extractor.Extractor.', 'Extractor.')
    if owner and owner_types:
        body = qualify_types(body, set(owner_types), owner)
    open(new_path, 'w', encoding='utf-8').write(header + body + '}\n')

    cls = new_path.split('/')[-1][:-5]
    rest = qualify_calls(jf.render(kept), set(move_names), cls)
    rest = re.sub(r'\n{3,}', '\n\n', rest)
    open(src_path, 'w', encoding='utf-8').write(rest)
    return len(rest.splitlines()), len(open(new_path, encoding='utf-8').read().splitlines())


if __name__ == '__main__':
    jf = JavaFile(sys.argv[1])
    print(f'{sys.argv[1]}: {len(jf.members)} members')
    for m in jf.members:
        print(f'  {len(m.splitlines()):4d}  {jf.declared_name(m)}')
