import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Python pass-1 extractor.
 *
 * Structure comes from an indentation walk over the masked source. A stack of
 * (indent, scope) tracks class/function nesting; blank lines and continuation
 * lines never pop a scope. Declarations, imports and calls are scanned only on
 * the masked text; route path strings are read back from the raw source at the
 * same offsets so literals survive masking.
 */
final class PythonExtractor implements Extractor {

    private static final Set<String> SKIP_CALLS = Set.of(
            "print", "len", "range", "isinstance", "super", "getattr", "setattr",
            "str", "int", "dict", "list", "set", "tuple", "open", "enumerate",
            "zip", "sorted", "type", "format", "min", "max", "sum", "any", "all",
            "bool", "float", "bytes", "bytearray", "object", "property", "classmethod",
            "staticmethod", "abs", "round", "map", "filter", "iter", "next", "id",
            "hash", "repr", "ascii", "ord", "chr", "bin", "hex", "oct", "vars",
            "dir", "locals", "globals", "callable", "hasattr", "delattr", "issubclass",
            "memoryview", "slice", "complex", "divmod", "pow", "input",
            "exec", "eval", "compile", "exit", "quit", "help", "copyright", "credits",
            "license", "breakpoint", "aiter", "anext");

    private static final Set<String> KEYWORDS = Set.of(
            "if", "elif", "else", "for", "while", "try", "except", "finally", "with",
            "def", "class", "return", "yield", "raise", "assert", "import", "from",
            "as", "pass", "break", "continue", "lambda", "and", "or", "not", "in",
            "is", "global", "nonlocal", "del", "await", "async", "match", "case");

    private static final Set<String> HTTP_VERBS = Set.of("get", "post", "put", "delete", "patch");

    private static final class Scope {
        String id;
        String qname;
        String type;            // file | module | class | enum | function | method | constructor
        int indent;             // indent of the declaring line (-1 for file)
        int start;              // char offset of body start
        int end = Integer.MAX_VALUE;
        GraphModel.Node node;
        Map<String, String> vars = new LinkedHashMap<>();
        java.util.Set<String> assigned = new java.util.LinkedHashSet<>();  // names bound locally in this scope
        Scope parent;
        String classQname;      // innermost enclosing class qname, or null
        boolean isEnum;
    }

    private static final class Decorator {
        String name;            // simple name (last segment), e.g. dataclass, get
        String full;            // full receiver.attr text, e.g. router.get
        String rawArgs;         // raw source of (...) including parens, or ""
        int line;
    }

    public String language() { return "python"; }

    public boolean supports(String p) {
        return p != null && (p.endsWith(".py") || p.endsWith(".pyi"));
    }

    public FileFacts extract(String path, String src) {
        FileFacts f = new FileFacts(path, "python");
        if (src == null) src = "";
        char[] mask;
        try {
            mask = Lex.maskPython(src);
        } catch (RuntimeException e) {
            f.error = "mask failed: " + e;
            return f;
        }
        try {
            parse(f, path, src, mask);
        } catch (RuntimeException e) {
            f.error = "parse failed: " + e;
        }
        return f;
    }

    private void parse(FileFacts f, String path, String src, char[] mask) {
        int[] li = Lex.lineIndex(mask);
        char[] raw = src.toCharArray();
        String fileNodeId = GraphModel.fileId(path);
        f.namespace = modulePath(path);

        // Module node for the file's namespace identity.
        GraphModel.Node mod = new GraphModel.Node(
                GraphModel.symbolId("python", "module", path, f.namespace, -1),
                "module", simpleName(f.namespace), f.namespace, "python", path);
        mod.startLine = 1;
        mod.endLine = Math.max(1, Lex.lineOf(li, mask.length > 0 ? mask.length - 1 : 0));
        mod.visibility = "public";
        f.add(mod);
        f.defines(fileNodeId, mod.id, 1);

        List<Scope> allScopes = new ArrayList<>();
        Deque<Scope> stack = new ArrayDeque<>();
        Scope fileScope = new Scope();
        fileScope.id = fileNodeId;
        fileScope.qname = f.namespace;
        fileScope.type = "file";
        fileScope.indent = -1;
        fileScope.start = 0;
        fileScope.node = null;
        stack.push(fileScope);
        allScopes.add(fileScope);

        List<String> maskLines = Lex.lines(mask);
        List<String> rawLines = Lex.lines(raw);
        int lineCount = Math.max(maskLines.size(), rawLines.size()) - 1;

        List<Decorator> pendingDecos = new ArrayList<>();
        int parenDepth = 0;
        int bracketDepth = 0;
        int braceDepth = 0;
        boolean continuing = false;
        StringBuilder logicalMask = new StringBuilder();
        StringBuilder logicalRaw = new StringBuilder();
        int logicalStartLine = 1;
        int logicalIndent = 0;

        for (int ln = 1; ln <= lineCount; ln++) {
            String ml = ln < maskLines.size() ? maskLines.get(ln) : "";
            String rl = ln < rawLines.size() ? rawLines.get(ln) : "";
            boolean blank = isBlankLine(ml);

            if (!continuing && parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) {
                if (blank) {
                    // Blank / fully-masked line: do not close scopes, drop pending only if not
                    // between decorators and a declaration? Keep decorators across blanks.
                    continue;
                }
                logicalMask.setLength(0);
                logicalRaw.setLength(0);
                logicalStartLine = ln;
                logicalIndent = countIndent(ml);
                logicalMask.append(ml);
                logicalRaw.append(rl);
            } else {
                logicalMask.append('\n').append(ml);
                logicalRaw.append('\n').append(rl);
            }

            // Update depths from this physical line (masked: strings already blanked).
            int[] d = depthDelta(ml);
            parenDepth = Math.max(0, parenDepth + d[0]);
            bracketDepth = Math.max(0, bracketDepth + d[1]);
            braceDepth = Math.max(0, braceDepth + d[2]);
            continuing = endsWithBackslash(ml) || parenDepth > 0 || bracketDepth > 0 || braceDepth > 0;

            if (continuing) continue;

            // Complete logical line.
            String head = logicalMask.toString();
            String rawHead = logicalRaw.toString();
            int line = logicalStartLine;
            int indent = logicalIndent;
            String trimmed = head.trim();
            if (trimmed.isEmpty()) {
                pendingDecos.clear();
                continue;
            }

            // Pop scopes whose indent is >= this line's indent.
            while (stack.size() > 1 && stack.peek().indent >= indent) {
                Scope closed = stack.pop();
                closed.end = lineStart(li, line);
                if (closed.node != null) closed.node.endLine = Math.max(closed.node.startLine, line - 1);
            }
            Scope cur = stack.peek();

            // Decorator line(s)
            if (trimmed.startsWith("@")) {
                pendingDecos.addAll(parseDecorators(trimmed, rawHead.trim(), line));
                continue;
            }

            // Imports
            if (trimmed.startsWith("import ") || trimmed.startsWith("from ")) {
                pendingDecos.clear();
                parseImport(f, trimmed, line, fileScope);
                continue;
            }

            // class
            if (isKeywordAt(trimmed, "class")) {
                Scope s = emitClass(f, path, fileNodeId, trimmed, rawHead, line, indent, cur, pendingDecos, isTestFile(path));
                pendingDecos.clear();
                if (s != null) {
                    s.start = lineStart(li, line);
                    stack.push(s);
                    allScopes.add(s);
                }
                continue;
            }

            // def / async def
            String defLine = trimmed;
            if (defLine.startsWith("async ")) defLine = defLine.substring(6).trim();
            if (isKeywordAt(defLine, "def")) {
                Scope s = emitDef(f, path, fileNodeId, defLine, rawHead, line, indent, cur, pendingDecos, isTestFile(path));
                pendingDecos.clear();
                if (s != null) {
                    s.start = lineStart(li, line);
                    stack.push(s);
                    allScopes.add(s);
                }
                continue;
            }

            pendingDecos.clear();

            // Class body: annotated fields, enum constants, simple assignments for type tracking
            if (cur != null && ("class".equals(cur.type) || "enum".equals(cur.type))) {
                handleClassBodyLine(f, path, head, line, cur);
                continue;
            }

            // Module-level UPPER_CASE constant
            if (cur != null && ("file".equals(cur.type) || "module".equals(cur.type))) {
                String constName = moduleConstantName(head.trim());
                if (constName != null) {
                    emitModuleConstant(f, path, fileNodeId, constName, line);
                }
            }

            // Module / function body: typed assignments and simple constructor assignments
            if (cur != null) {
                handleAssignment(head, cur);
                recordAssignedName(head, cur);
            }
        }

        // Close remaining scopes
        int eof = mask.length;
        while (!stack.isEmpty()) {
            Scope s = stack.pop();
            s.end = eof;
            if (s.node != null && s.node.endLine == 0) {
                s.node.endLine = Lex.lineOf(li, eof > 0 ? eof - 1 : 0);
            }
        }
        mod.endLine = Lex.lineOf(li, eof > 0 ? eof - 1 : 0);

        collectCalls(f, mask, li, allScopes, fileNodeId, mod.id);
    }

    // ---------- declarations ----------

    private Scope emitClass(FileFacts f, String path, String fileNodeId, String trimmed, String rawHead,
                            int line, int indent, Scope cur, List<Decorator> decos, boolean testFile) {
        // class Name(bases):
        String after = trimmed.substring(5).trim();
        String name = firstIdent(after);
        if (name.isEmpty()) return null;

        List<String> bases = new ArrayList<>();
        int lp = after.indexOf('(');
        int colon = after.lastIndexOf(':');
        if (lp >= 0 && (colon < 0 || lp < colon)) {
            int rp = matching(after, lp);
            if (rp > lp) {
                for (String part : splitTop(after.substring(lp + 1, rp), ',')) {
                    String b = part.trim();
                    if (b.isEmpty()) continue;
                    if (b.contains("=")) continue; // keyword arg e.g. metaclass=
                    b = stripGenerics(b).trim();
                    // take dotted name only
                    String bn = takeDottedName(b);
                    if (bn.isEmpty() || "object".equals(bn)) continue;
                    bases.add(bn);
                }
            }
        }

        boolean isEnum = false;
        for (String b : bases) {
            String simple = b.contains(".") ? b.substring(b.lastIndexOf('.') + 1) : b;
            if ("Enum".equals(simple) || "IntEnum".equals(simple) || "StrEnum".equals(simple)
                    || "Flag".equals(simple) || "IntFlag".equals(simple)) {
                isEnum = true;
                break;
            }
        }

        String kind = isEnum ? "enum" : "class";
        String parentQ = cur.qname == null || cur.qname.isEmpty() ? f.namespace : cur.qname;
        // Top-level class: qname is namespace.Name; nested: parent.Name
        String qn;
        if ("file".equals(cur.type) || "module".equals(cur.type) || "function".equals(cur.type)
                || "method".equals(cur.type) || "constructor".equals(cur.type)) {
            if ("file".equals(cur.type) || "module".equals(cur.type)) {
                qn = f.namespace.isEmpty() ? name : f.namespace + "." + name;
            } else {
                qn = parentQ + "." + name;
            }
        } else {
            qn = parentQ + "." + name;
        }

        // Nested class inside class
        if ("class".equals(cur.type) || "enum".equals(cur.type)) {
            qn = cur.qname + "." + name;
        }

        GraphModel.Node node = new GraphModel.Node(
                GraphModel.symbolId("python", kind, path, qn, -1), kind, name, qn, "python", path);
        node.startLine = line;
        node.endLine = line;
        node.visibility = visibility(name);
        applyDecorators(node, decos);
        if (testFile) node.attrs.put("test", true);
        f.add(node);

        if ("file".equals(cur.type) || "module".equals(cur.type)) {
            f.defines(fileNodeId, node.id, line);
        } else if (cur.id != null) {
            f.contains(cur.id, node.id, line);
        } else {
            f.defines(fileNodeId, node.id, line);
        }

        for (String b : bases) {
            f.supers.add(new TypeRef(node.id, b, "EXTENDS", line));
        }

        Scope sc = new Scope();
        sc.id = node.id;
        sc.qname = qn;
        sc.type = kind;
        sc.indent = indent;
        sc.node = node;
        sc.parent = cur;
        sc.classQname = qn;
        sc.isEnum = isEnum;
        return sc;
    }

    private Scope emitDef(FileFacts f, String path, String fileNodeId, String defLine, String rawHead,
                          int line, int indent, Scope cur, List<Decorator> decos, boolean testFile) {
        // def name(params) -> ret:
        String after = defLine.substring(3).trim();
        String name = firstIdent(after);
        if (name.isEmpty()) return null;

        List<String> params = new ArrayList<>();
        int lp = after.indexOf('(');
        if (lp >= 0) {
            int rp = matching(after, lp);
            if (rp > lp) {
                for (String p : splitTop(after.substring(lp + 1, rp), ',')) {
                    String t = p.trim();
                    if (!t.isEmpty()) params.add(t);
                }
            }
        }

        boolean inClass = cur != null && ("class".equals(cur.type) || "enum".equals(cur.type));
        boolean isDunderInit = "__init__".equals(name);
        String kind;
        if (inClass) {
            kind = isDunderInit ? "constructor" : "method";
        } else if (testFile && name.startsWith("test")) {
            kind = "test";
        } else {
            kind = "function";
        }

        String qn;
        if (inClass) {
            qn = cur.qname + "." + name;
        } else if ("function".equals(cur.type) || "method".equals(cur.type) || "constructor".equals(cur.type)
                || "test".equals(cur.type)) {
            qn = cur.qname + "." + name;
        } else {
            qn = f.namespace.isEmpty() ? name : f.namespace + "." + name;
        }

        int arity = params.size();
        GraphModel.Node node = new GraphModel.Node(
                GraphModel.symbolId("python", kind, path, qn, arity), kind, name, qn, "python", path);
        node.startLine = line;
        node.endLine = line;
        node.visibility = visibility(name);
        node.signature = name + "(" + String.join(", ", params) + ")";
        applyDecorators(node, decos);
        if (testFile) node.attrs.put("test", true);
        f.add(node);

        if (inClass) {
            f.contains(cur.id, node.id, line);
        } else if ("function".equals(cur.type) || "method".equals(cur.type) || "constructor".equals(cur.type)
                || "test".equals(cur.type)) {
            f.contains(cur.id, node.id, line);
        } else {
            f.defines(fileNodeId, node.id, line);
        }

        // Route endpoints from decorators like @router.get("/cases/{id}")
        for (Decorator d : decos) {
            maybeEndpoint(f, path, fileNodeId, node, d, line);
        }

        Scope sc = new Scope();
        sc.id = node.id;
        sc.qname = qn;
        sc.type = kind;
        sc.indent = indent;
        sc.node = node;
        sc.parent = cur;
        sc.classQname = inClass ? cur.qname : (cur != null ? cur.classQname : null);

        // Bind parameters (and self/cls).
        for (int i = 0; i < params.size(); i++) {
            String[] kv = splitPyParam(params.get(i));
            if (kv == null) continue;
            String pname = kv[0];
            String ptype = kv[1];
            if ("self".equals(pname) && sc.classQname != null) {
                sc.vars.put("self", sc.classQname);
            } else if ("cls".equals(pname) && sc.classQname != null) {
                sc.vars.put("cls", sc.classQname);
            } else if (ptype != null) {
                sc.vars.put(pname, baseType(ptype));
            }
        }
        // classmethod/staticmethod: still bind cls if first param is cls even without decorator check
        return sc;
    }

    private void maybeEndpoint(FileFacts f, String path, String fileNodeId, GraphModel.Node fn,
                               Decorator d, int line) {
        // full like "router.get" or "app.post"
        if (d.full == null || !d.full.contains(".")) return;
        int dot = d.full.lastIndexOf('.');
        String verb = d.full.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!HTTP_VERBS.contains(verb)) return;
        String routePath = pathArg(d.rawArgs);
        if (routePath == null) routePath = "";
        String http = verb.toUpperCase(Locale.ROOT);
        String eq = http + " " + routePath;
        GraphModel.Node ep = new GraphModel.Node(
                GraphModel.symbolId("python", "endpoint", path, eq, -1),
                "endpoint", eq, eq, "python", path);
        ep.startLine = line;
        ep.endLine = line;
        ep.attrs.put("http_method", http);
        ep.attrs.put("path", routePath);
        f.add(ep);
        f.edges.add(new GraphModel.Edge(ep.id, fn.id, "ROUTES_TO", GraphModel.EXTRACTED, path, line, "decorator"));
        f.defines(fileNodeId, ep.id, line);
    }

    private void handleClassBodyLine(FileFacts f, String path, String head, int line, Scope cur) {
        String t = head.trim();
        if (t.startsWith("#") || t.isEmpty()) return;
        // Skip nested control-flow headers if any slip through
        if (isKeywordAt(t, "def") || isKeywordAt(t, "class") || t.startsWith("async ")) return;

        // Annotated field: name: Type or name: Type = ...
        int colon = indexOfTop(t, ':');
        int eq = indexOfTop(t, '=');
        if (colon > 0 && (eq < 0 || colon < eq)) {
            String namePart = t.substring(0, colon).trim();
            String name = firstIdent(namePart);
            if (name.isEmpty() || !name.equals(namePart)) {
                // not a simple field (could be something else)
            } else {
                String rest = t.substring(colon + 1).trim();
                String typePart = rest;
                if (eq > colon) {
                    // type is between : and =
                    typePart = t.substring(colon + 1, eq).trim();
                }
                String typeName = baseType(typePart);
                String qn = cur.qname + "." + name;
                String kind = "field";
                GraphModel.Node fn = new GraphModel.Node(
                        GraphModel.symbolId("python", kind, path, qn, -1), kind, name, qn, "python", path);
                fn.startLine = line;
                fn.endLine = line;
                fn.visibility = visibility(name);
                if (typeName != null && !typeName.isEmpty()) {
                    fn.signature = typeName + " " + name;
                    cur.vars.put(name, typeName);
                }
                f.add(fn);
                f.contains(cur.id, fn.id, line);
                return;
            }
        }

        // Assignment: NAME = ...  (enum constant or class-level constant)
        if (eq > 0) {
            String lhs = t.substring(0, eq).trim();
            String name = firstIdent(lhs);
            if (name.isEmpty() || !name.equals(lhs)) return;
            if (isUpperName(name) || cur.isEnum) {
                String qn = cur.qname + "." + name;
                GraphModel.Node cn = new GraphModel.Node(
                        GraphModel.symbolId("python", "constant", path, qn, -1),
                        "constant", name, qn, "python", path);
                cn.startLine = line;
                cn.endLine = line;
                cn.visibility = visibility(name);
                f.add(cn);
                f.contains(cur.id, cn.id, line);
            }
            String rhs = t.substring(eq + 1).trim();
            String ctor = constructorName(rhs);
            if (ctor != null) cur.vars.put(name, ctor);
        }
    }

    private void handleAssignment(String head, Scope cur) {
        String t = head.trim();
        if (t.isEmpty() || t.startsWith("#")) return;
        int eq = indexOfTop(t, '=');
        if (eq <= 0) return;
        // Ignore ==, !=, <=, >=, :=
        if (eq + 1 < t.length() && t.charAt(eq + 1) == '=') return;
        if (eq > 0 && (t.charAt(eq - 1) == '!' || t.charAt(eq - 1) == '<' || t.charAt(eq - 1) == '>'
                || t.charAt(eq - 1) == ':')) return;

        String lhs = t.substring(0, eq).trim();
        String rhs = t.substring(eq + 1).trim();

        // self.repo = CaseRepository()
        if (lhs.startsWith("self.") && lhs.indexOf('.', 5) < 0) {
            String field = lhs.substring(5).trim();
            if (isIdent(field)) {
                String ctor = constructorName(rhs);
                if (ctor != null) {
                    Scope classScope = cur;
                    while (classScope != null && !"class".equals(classScope.type)
                            && !"enum".equals(classScope.type)) {
                        classScope = classScope.parent;
                    }
                    if (classScope != null) classScope.vars.put(field, ctor);
                    cur.vars.put("self." + field, ctor);
                }
            }
            return;
        }

        // name: Type = ...  or name = ...
        int colon = indexOfTop(lhs, ':');
        String name;
        String ann = null;
        if (colon > 0) {
            name = firstIdent(lhs.substring(0, colon).trim());
            ann = baseType(lhs.substring(colon + 1).trim());
            if (!isIdent(lhs.substring(0, colon).trim())) return;
        } else {
            name = firstIdent(lhs);
            if (!isIdent(lhs)) return;
        }
        if (name == null || name.isEmpty()) return;

        if (ann != null) {
            cur.vars.put(name, ann);
        } else {
            String ctor = constructorName(rhs);
            if (ctor != null) cur.vars.put(name, ctor);
            else if (isIdent(rhs) || isDottedName(rhs)) {
                // alias = other_name (e.g. format_id = str)
                String b = baseType(rhs);
                if (b != null) cur.vars.put(name, b);
            }
        }
    }

    /** Detect module-level UPPER_CASE = ... assignment; returns the name or null. */
    static String moduleConstantName(String t) {
        if (t == null || t.isEmpty()) return null;
        int eq = indexOfTop(t, '=');
        if (eq <= 0) return null;
        if (eq + 1 < t.length() && t.charAt(eq + 1) == '=') return null;
        if (t.charAt(eq - 1) == '!' || t.charAt(eq - 1) == '<' || t.charAt(eq - 1) == '>'
                || t.charAt(eq - 1) == ':') return null;
        String lhs = t.substring(0, eq).trim();
        int colon = indexOfTop(lhs, ':');
        if (colon > 0) lhs = lhs.substring(0, colon).trim();
        if (!isIdent(lhs) || !isUpperName(lhs)) return null;
        return lhs;
    }

    private void emitModuleConstant(FileFacts f, String path, String fileNodeId, String name, int line) {
        String qn = f.namespace.isEmpty() ? name : f.namespace + "." + name;
        GraphModel.Node cn = new GraphModel.Node(
                GraphModel.symbolId("python", "constant", path, qn, -1),
                "constant", name, qn, "python", path);
        cn.startLine = line;
        cn.endLine = line;
        cn.visibility = visibility(name);
        f.add(cn);
        f.defines(fileNodeId, cn.id, line);
    }

    // ---------- imports ----------

    private void parseImport(FileFacts f, String trimmed, int line, Scope fileScope) {
        String raw = trimmed;
        if (trimmed.startsWith("from ")) {
            // from X import a, b as c
            String rest = trimmed.substring(5).trim();
            int importAt = findWord(rest, "import");
            if (importAt < 0) return;
            String modPart = rest.substring(0, importAt).trim();
            String namesPart = rest.substring(importAt + 6).trim();
            if (namesPart.startsWith("(") && namesPart.endsWith(")")) {
                namesPart = namesPart.substring(1, namesPart.length() - 1).trim();
            }

            int level = 0;
            while (level < modPart.length() && modPart.charAt(level) == '.') level++;
            String modPath = modPart.substring(level).trim();
            String abs = resolveRelative(f.namespace, level, modPath);

            if ("*".equals(namesPart)) {
                f.imports.add(new ImportRef("*", abs, "*", "python", true, line, raw));
                return;
            }
            for (String part : splitTop(namesPart, ',')) {
                String p = part.trim();
                if (p.isEmpty()) continue;
                String member = p;
                String alias = p;
                int asAt = findWord(p, "as");
                if (asAt >= 0) {
                    member = p.substring(0, asAt).trim();
                    alias = p.substring(asAt + 2).trim();
                }
                member = firstIdent(member);
                alias = firstIdent(alias);
                if (member.isEmpty()) continue;
                if (alias.isEmpty()) alias = member;
                f.imports.add(new ImportRef(alias, abs, member, "python", false, line, raw));
                // Bind alias for receiver typing: module.member or just the imported name
                String bound = abs == null || abs.isEmpty() ? member : abs + "." + member;
                fileScope.vars.put(alias, bound);
            }
        } else if (trimmed.startsWith("import ")) {
            String rest = trimmed.substring(7).trim();
            for (String part : splitTop(rest, ',')) {
                String p = part.trim();
                if (p.isEmpty()) continue;
                String module = p;
                String alias = null;
                int asAt = findWord(p, "as");
                if (asAt >= 0) {
                    module = p.substring(0, asAt).trim();
                    alias = p.substring(asAt + 2).trim();
                }
                module = takeDottedName(module);
                if (module.isEmpty()) continue;
                if (alias == null || alias.isEmpty()) {
                    // import a.b.c binds `a`
                    alias = module.contains(".") ? module.substring(0, module.indexOf('.')) : module;
                } else {
                    alias = firstIdent(alias);
                }
                f.imports.add(new ImportRef(alias, module, null, "python", false, line, raw));
                fileScope.vars.put(alias, module);
            }
        }
    }

    /**
     * Resolve a relative import against the current module namespace.
     * level=0 absolute; level=1 current package; level=2 parent package; etc.
     */
    static String resolveRelative(String namespace, int level, String modPath) {
        if (level <= 0) return modPath == null ? "" : modPath;
        // Package of this module: for acme.service.case_service -> acme.service
        // for package module acme (__init__) -> acme
        String pkg = namespace == null ? "" : namespace;
        // Always treat namespace as the module; package is parent unless this is a package root
        // Relative imports are relative to the package containing the module.
        List<String> parts = new ArrayList<>();
        if (pkg != null && !pkg.isEmpty()) {
            for (String s : pkg.split("\\.")) if (!s.isEmpty()) parts.add(s);
        }
        // Drop the module leaf to get the containing package (for non-package modules).
        // For `acme.service.case_service`, containing package is `acme.service`.
        // For a namespace that is itself a package (from __init__.py), path ends with the package
        // name and modulePath already dropped __init__, so namespace IS the package.
        // Heuristic: we always drop one component for relative base when level>=1, matching
        // Python's rule that the current package is the parent of a plain module file.
        // When the file is __init__.py, modulePath() already yields the package, and Python
        // treats that package as the current package — so we should NOT drop.
        // Callers pass namespace from modulePath which drops __init__. We cannot see the path
        // here; use: if level>=1, base = parts with (level) components removed from the end
        // starting from the package. Python: go up `level` from the *package*, not the module.
        // For module acme.service.case_service, package = acme.service, level 1 stays, level 2 -> acme.
        if (!parts.isEmpty()) {
            // Assume namespace is a module (not package) when it has 2+ parts OR always drop one?
            // Safer approach matching fixtures: drop last component to get package, then go up level-1.
            parts.remove(parts.size() - 1); // now containing package
        }
        int up = level - 1;
        while (up > 0 && !parts.isEmpty()) {
            parts.remove(parts.size() - 1);
            up--;
        }
        if (modPath != null && !modPath.isEmpty()) {
            for (String s : modPath.split("\\.")) if (!s.isEmpty()) parts.add(s);
        }
        return String.join(".", parts);
    }

    // ---------- calls ----------

    private void collectCalls(FileFacts f, char[] mask, int[] li, List<Scope> scopes,
                              String fileNodeId, String moduleId) {
        ScopeCursor scopeCursor = new ScopeCursor(scopes);
        for (int i = 0; i < mask.length; i++) {
            if (mask[i] != '(') continue;
            String name = Lex.identBefore(mask, i);
            if (name.isEmpty()) continue;
            if (KEYWORDS.contains(name) || SKIP_CALLS.contains(name)) continue;
            int nameStart = i - name.length();
            // decorator application: @foo( or @router.get( — handled as declarations, not calls
            if (nameStart > 0 && mask[nameStart - 1] == '@') continue;
            if (isOnDecoratorLine(mask, nameStart)) continue;

            String receiver = null;
            if (nameStart > 0 && mask[nameStart - 1] == '.') {
                receiver = Lex.receiverBefore(mask, nameStart - 1);
                if (receiver.isEmpty()) receiver = null;
                // super().method( — Lex cannot see through the call parens; treat as super receiver
                if (receiver == null && isSuperDotCall(mask, nameStart - 1)) {
                    receiver = "super";
                }
            }

            Scope owner = scopeCursor.at(i);
            if (owner == null) continue;
            // Skip the def/class header call-like tokens: `def foo(` on the declaration line
            if (isDeclHeader(mask, nameStart)) continue;

            int arity = arity(mask, i);
            String from;
            if (owner.node != null && !"file".equals(owner.type) && !"module".equals(owner.type)) {
                from = owner.node.id;
            } else {
                // Module-level: prefer module node so ground-truth qname maps cleanly
                from = moduleId != null ? moduleId : fileNodeId;
            }
            // A name bound by a local assignment shadows any module-level function of the same
            // name. `format_id = str` followed by `format_id(x)` is a call to a local, and
            // linking it to the module function would be a confidently wrong edge.
            if (receiver == null && shadowedLocally(owner, name)) continue;
            String rt = lookupReceiverType(owner, receiver, scopes, i);
            f.refs.add(new Ref(from, receiver, rt, name, arity, Lex.lineOf(li, i), "call"));
        }
    }

    /** Record a plain `name = ...` binding so later calls know the name is shadowed. */
    private static void recordAssignedName(String head, Scope cur) {
        String t = head.trim();
        int eq = t.indexOf('=');
        if (eq <= 0) return;
        if (eq + 1 < t.length() && (t.charAt(eq + 1) == '=' )) return;
        char prev = t.charAt(eq - 1);
        if (prev == '!' || prev == '<' || prev == '>' || prev == '=') return;
        String lhs = t.substring(0, eq).trim();
        if (lhs.isEmpty() || lhs.contains(" ") || lhs.contains(".") || lhs.contains("(") || lhs.contains("[")) return;
        if (!Lex.isIdentStart(lhs.charAt(0))) return;
        for (int i = 1; i < lhs.length(); i++) if (!Lex.isIdentPart(lhs.charAt(i))) return;
        cur.assigned.add(lhs);
    }

    /** Walk enclosing function scopes looking for a local binding of this name. */
    private static boolean shadowedLocally(Scope owner, String name) {
        for (Scope s = owner; s != null; s = s.parent) {
            if (s.assigned.contains(name)) return true;
            if ("class".equals(s.type) || "file".equals(s.type) || "module".equals(s.type)) return false;
        }
        return false;
    }

    /** True when the call sits on a physical line whose first non-ws char is `@`. */
    private static boolean isOnDecoratorLine(char[] s, int pos) {
        int i = pos;
        while (i > 0 && s[i - 1] != '\n') i--;
        while (i < s.length && (s[i] == ' ' || s[i] == '\t')) i++;
        return i < s.length && s[i] == '@';
    }

    /** Detect `super().name` where `pos` is the index of the `.` before name. */
    private static boolean isSuperDotCall(char[] s, int dotPos) {
        int i = dotPos;
        if (i <= 0 || s[i] != '.') return false;
        i--;
        while (i >= 0 && Character.isWhitespace(s[i])) i--;
        if (i < 0 || s[i] != ')') return false;
        // match back over balanced parens of super(...)
        int d = 0;
        for (; i >= 0; i--) {
            if (s[i] == ')') d++;
            else if (s[i] == '(') {
                d--;
                if (d == 0) {
                    String id = Lex.identBefore(s, i);
                    return "super".equals(id);
                }
            }
        }
        return false;
    }

    private static boolean isDeclHeader(char[] s, int nameStart) {
        int i = nameStart;
        while (i > 0 && Character.isWhitespace(s[i - 1])) i--;
        // look for def / class / async before the name
        String prev = Lex.identBefore(s, i);
        return "def".equals(prev) || "class".equals(prev) || "async".equals(prev);
    }

    private static String lookupReceiverType(Scope s, String receiver, List<Scope> scopes, int off) {
        if (receiver == null) {
            return null;
        }
        // self / cls
        if ("self".equals(receiver) || "cls".equals(receiver)) {
            for (Scope p = s; p != null; p = p.parent) {
                String t = p.vars.get(receiver);
                if (t != null) return t;
                if (p.classQname != null) return p.classQname;
            }
            return null;
        }
        // super().method — receiver type is the declared base when there is exactly one useful base;
        // otherwise leave null and let the resolver use EXTENDS edges.
        if ("super".equals(receiver)) {
            return null;
        }

        String head = receiver;
        String rest = null;
        int dot = receiver.indexOf('.');
        if (dot >= 0) {
            head = receiver.substring(0, dot);
            rest = receiver.substring(dot + 1);
        }

        if ("self".equals(head) || "cls".equals(head)) {
            // self.repo -> look up repo on class scope
            String field = rest == null ? null : (rest.contains(".") ? rest.substring(0, rest.indexOf('.')) : rest);
            if (field == null) {
                for (Scope p = s; p != null; p = p.parent) {
                    if (p.classQname != null) return p.classQname;
                }
                return null;
            }
            for (Scope p = s; p != null; p = p.parent) {
                if ("class".equals(p.type) || "enum".equals(p.type)) {
                    String t = p.vars.get(field);
                    if (t != null) return t;
                }
                String t = p.vars.get(field);
                if (t != null && ("class".equals(p.type) || "enum".equals(p.type))) return t;
            }
            // Also check class scope vars for field stored under field name
            for (Scope p = s; p != null; p = p.parent) {
                String t = p.vars.get(field);
                if (t != null) return t;
            }
            return null; // untyped attribute — leave null (mystery.save trap)
        }

        for (Scope p = s; p != null; p = p.parent) {
            String t = p.vars.get(head);
            if (t != null) return t;
        }
        // Walk all scopes that contain this offset for file-level import bindings
        for (Scope sc : scopes) {
            if ("file".equals(sc.type) || "module".equals(sc.type)) {
                String t = sc.vars.get(head);
                if (t != null) return t;
            }
        }
        return null;
    }


    /**
     * Innermost scope containing an offset, in linear total time.
     *
     * The previous linear scan per call site was quadratic in the number of scopes, which is
     * fine for a fixture and fatal for a large generated file. Offsets are visited in
     * increasing order, so a stack of currently open scopes answers the same question.
     */
    private static final class ScopeCursor {
        private final List<Scope> sorted;
        private final java.util.ArrayDeque<Scope> open = new java.util.ArrayDeque<>();
        private int cursor = 0;

        ScopeCursor(List<Scope> scopes) {
            this.sorted = new ArrayList<>(scopes);
            this.sorted.sort((a, b) -> Integer.compare(a.start, b.start));
        }

        Scope at(int off) {
            while (!open.isEmpty() && open.peek().end <= off) open.pop();
            while (cursor < sorted.size() && sorted.get(cursor).start <= off) {
                Scope s = sorted.get(cursor++);
                if (s.end > off) open.push(s);
            }
            for (Scope s : open) {
                if (true) return s;
            }
            return null;
        }
    }

    private static Scope innermost(List<Scope> scopes, int off) {
        Scope best = null;
        for (Scope s : scopes) {
            if (s.start <= off && off < s.end) {
                if (best == null || s.start >= best.start) best = s;
            }
        }
        return best;
    }

    static int arity(char[] s, int lparen) {
        int d = 0, count = 0;
        boolean any = false;
        for (int i = lparen; i < s.length; i++) {
            char c = s[i];
            if (c == '(') d++;
            else if (c == ')') {
                d--;
                if (d == 0) return any ? count + 1 : 0;
            } else if (c == ',' && d == 1) count++;
            else if (!Character.isWhitespace(c) && d >= 1) any = true;
        }
        return -1;
    }

    // ---------- decorators ----------

    private List<Decorator> parseDecorators(String trimmedMask, String trimmedRaw, int line) {
        List<Decorator> out = new ArrayList<>();
        // One logical line may only have one decorator in Python.
        if (!trimmedMask.startsWith("@")) return out;
        String body = trimmedMask.substring(1).trim();
        String rawBody = trimmedRaw.startsWith("@") ? trimmedRaw.substring(1).trim() : trimmedRaw;
        Decorator d = new Decorator();
        d.line = line;
        int lp = body.indexOf('(');
        String target = lp >= 0 ? body.substring(0, lp).trim() : body.trim();
        // strip trailing junk
        target = takeDottedName(target);
        d.full = target;
        d.name = target.contains(".") ? target.substring(target.lastIndexOf('.') + 1) : target;
        if (lp >= 0) {
            int rp = matching(body, lp);
            if (rp > lp && rp <= rawBody.length()) {
                d.rawArgs = rawBody.substring(lp, Math.min(rp + 1, rawBody.length()));
            } else if (lp < rawBody.length()) {
                // fall back: find matching in raw
                int rp2 = matching(rawBody, lp);
                d.rawArgs = rp2 > lp ? rawBody.substring(lp, rp2 + 1) : "";
            } else {
                d.rawArgs = "";
            }
        } else {
            d.rawArgs = "";
        }
        out.add(d);
        return out;
    }

    private void applyDecorators(GraphModel.Node node, List<Decorator> decos) {
        if (decos == null || decos.isEmpty()) return;
        List<String> names = new ArrayList<>();
        for (Decorator d : decos) {
            names.add(d.name);
            if ("dataclass".equals(d.name)) node.attrs.put("dataclass", true);
            if ("staticmethod".equals(d.name) || "classmethod".equals(d.name)) {
                node.attrs.put("static", true);
            }
        }
        node.attrs.put("decorators", names);
    }

    // ---------- module path ----------

    static String modulePath(String relPath) {
        if (relPath == null) return "";
        String p = relPath.replace('\\', '/');
        if (p.startsWith("./")) p = p.substring(2);
        if (p.endsWith(".py")) p = p.substring(0, p.length() - 3);
        else if (p.endsWith(".pyi")) p = p.substring(0, p.length() - 4);
        String[] parts = p.split("/");
        List<String> out = new ArrayList<>();
        for (String s : parts) {
            if (s.isEmpty() || ".".equals(s)) continue;
            out.add(s);
        }
        if (!out.isEmpty() && "__init__".equals(out.get(out.size() - 1))) {
            out.remove(out.size() - 1);
        }
        return String.join(".", out);
    }

    static boolean isTestFile(String path) {
        if (path == null) return false;
        String p = path.replace('\\', '/');
        String base = p.contains("/") ? p.substring(p.lastIndexOf('/') + 1) : p;
        return p.startsWith("tests/") || p.contains("/tests/")
                || base.startsWith("test_") || base.endsWith("_test.py") || base.endsWith("_test.pyi");
    }

    // ---------- small helpers ----------

    static String visibility(String name) {
        return name != null && name.startsWith("_") ? "private" : "public";
    }

    static String simpleName(String qname) {
        if (qname == null || qname.isEmpty()) return "";
        int i = qname.lastIndexOf('.');
        return i < 0 ? qname : qname.substring(i + 1);
    }

    static boolean isUpperName(String name) {
        if (name == null || name.isEmpty()) return false;
        boolean hasLetter = false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isLetter(c)) {
                hasLetter = true;
                if (Character.isLowerCase(c)) return false;
            } else if (c != '_' && !Character.isDigit(c)) {
                return false;
            }
        }
        return hasLetter;
    }

    static boolean isIdent(String s) {
        if (s == null || s.isEmpty() || !Lex.isIdentStart(s.charAt(0))) return false;
        for (int i = 1; i < s.length(); i++) if (!Lex.isIdentPart(s.charAt(i))) return false;
        return true;
    }

    static boolean isDottedName(String s) {
        if (s == null || s.isEmpty()) return false;
        for (String p : s.split("\\.")) if (!isIdent(p)) return false;
        return true;
    }

    static String takeDottedName(String s) {
        if (s == null) return "";
        s = s.trim();
        int i = 0;
        while (i < s.length() && (Lex.isIdentPart(s.charAt(i)) || s.charAt(i) == '.')) i++;
        String t = s.substring(0, i);
        while (t.endsWith(".")) t = t.substring(0, t.length() - 1);
        return t;
    }

    static String firstIdent(String s) {
        if (s == null) return "";
        int i = 0;
        while (i < s.length() && !Lex.isIdentStart(s.charAt(i))) {
            if (!Character.isWhitespace(s.charAt(i))) return "";
            i++;
        }
        int j = i;
        while (j < s.length() && Lex.isIdentPart(s.charAt(j))) j++;
        return s.substring(i, j);
    }

    static boolean isKeywordAt(String t, String kw) {
        if (!t.startsWith(kw)) return false;
        if (t.length() == kw.length()) return true;
        char c = t.charAt(kw.length());
        return !Lex.isIdentPart(c);
    }

    static boolean isBlankLine(String ml) {
        for (int i = 0; i < ml.length(); i++) {
            char c = ml.charAt(i);
            if (c != ' ' && c != '\t' && c != '\r') return false;
        }
        return true;
    }

    static int countIndent(String line) {
        int i = 0, n = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (c == ' ') { n++; i++; }
            else if (c == '\t') { n += 4; i++; }
            else break;
        }
        return n;
    }

    static boolean endsWithBackslash(String line) {
        int i = line.length() - 1;
        while (i >= 0 && (line.charAt(i) == ' ' || line.charAt(i) == '\t' || line.charAt(i) == '\r')) i--;
        return i >= 0 && line.charAt(i) == '\\';
    }

    static int[] depthDelta(String line) {
        int p = 0, b = 0, c = 0;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '(') p++;
            else if (ch == ')') p--;
            else if (ch == '[') b++;
            else if (ch == ']') b--;
            else if (ch == '{') c++;
            else if (ch == '}') c--;
        }
        return new int[]{p, b, c};
    }

    static int lineStart(int[] li, int line) {
        if (line < 0) return 0;
        if (line >= li.length) return li[li.length - 1];
        return li[line];
    }

    /**
     * If RHS is a simple constructor-like Call {@code TypeName(...)}, return the type name.
     * Only Capitalized callees count — {@code untyped_factory()} must not become a known type
     * (see ground-truth negative for mystery.save).
     */
    static String constructorName(String rhs) {
        if (rhs == null) return null;
        String t = rhs.trim();
        int lp = t.indexOf('(');
        if (lp <= 0) return null;
        String callee = t.substring(0, lp).trim();
        if (!isDottedName(callee) && !isIdent(callee)) return null;
        String simple = callee.contains(".") ? callee.substring(callee.lastIndexOf('.') + 1) : callee;
        if (simple.isEmpty() || !Character.isUpperCase(simple.charAt(0))) return null;
        int rp = matching(t, lp);
        if (rp < 0) return null;
        String after = t.substring(rp + 1).trim();
        if (!after.isEmpty() && !after.startsWith("#")) return null;
        return baseType(callee);
    }

    /** Split "name: Type = default" -> [name, type or null]. */
    static String[] splitPyParam(String p) {
        String s = p.trim();
        if (s.startsWith("**")) s = s.substring(2).trim();
        else if (s.startsWith("*")) s = s.substring(1).trim();
        if (s.isEmpty()) return null;
        int eq = indexOfTop(s, '=');
        if (eq >= 0) s = s.substring(0, eq).trim();
        int colon = indexOfTop(s, ':');
        String name;
        String type = null;
        if (colon >= 0) {
            name = s.substring(0, colon).trim();
            type = s.substring(colon + 1).trim();
        } else {
            name = s.trim();
        }
        name = firstIdent(name);
        if (name.isEmpty()) return null;
        if (type != null) type = baseType(type);
        return new String[]{name, type};
    }

    static String baseType(String t) {
        if (t == null) return null;
        String s = t.trim();
        if (s.startsWith("\"") || s.startsWith("'")) {
            s = s.substring(1);
            if (s.endsWith("\"") || s.endsWith("'")) s = s.substring(0, s.length() - 1);
        }
        // drop Callable[...] / list[...] style
        s = stripGenerics(s).trim();
        // Union / Optional leftovers: take first segment before |
        int pipe = s.indexOf('|');
        if (pipe >= 0) s = s.substring(0, pipe).trim();
        s = takeDottedName(s);
        return s.isEmpty() ? null : s;
    }

    static String stripGenerics(String s) {
        StringBuilder sb = new StringBuilder();
        int d = 0;
        for (char c : s.toCharArray()) {
            if (c == '[') d++;
            else if (c == ']') { d = Math.max(0, d - 1); sb.append(' '); }
            else if (d == 0) sb.append(c);
        }
        return sb.toString();
    }

    static int matching(String s, int open) {
        if (open < 0 || open >= s.length()) return -1;
        char o = s.charAt(open);
        char c = o == '(' ? ')' : o == '[' ? ']' : o == '{' ? '}' : 0;
        if (c == 0) return -1;
        int d = 0;
        for (int i = open; i < s.length(); i++) {
            if (s.charAt(i) == o) d++;
            else if (s.charAt(i) == c) {
                d--;
                if (d == 0) return i;
            }
        }
        return -1;
    }

    static int indexOfTop(String s, char target) {
        int p = 0, b = 0, c = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '(') p++;
            else if (ch == ')') p--;
            else if (ch == '[') b++;
            else if (ch == ']') b--;
            else if (ch == '{') c++;
            else if (ch == '}') c--;
            else if (ch == target && p == 0 && b == 0 && c == 0) return i;
        }
        return -1;
    }

    static List<String> splitTop(String s, char sep) {
        List<String> out = new ArrayList<>();
        int p = 0, b = 0, c = 0, start = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '(') p++;
            else if (ch == ')') p--;
            else if (ch == '[') b++;
            else if (ch == ']') b--;
            else if (ch == '{') c++;
            else if (ch == '}') c--;
            else if (ch == sep && p == 0 && b == 0 && c == 0) {
                out.add(s.substring(start, i));
                start = i + 1;
            }
        }
        out.add(s.substring(start));
        return out;
    }

    static int findWord(String s, String w) {
        int from = 0;
        while (true) {
            int i = s.indexOf(w, from);
            if (i < 0) return -1;
            boolean lb = i == 0 || !Lex.isIdentPart(s.charAt(i - 1));
            int e = i + w.length();
            boolean rb = e >= s.length() || !Lex.isIdentPart(s.charAt(e));
            if (lb && rb) return i;
            from = i + 1;
        }
    }

    /** First string literal inside decorator/call arguments. */
    static String pathArg(String rawArgs) {
        if (rawArgs == null) return null;
        for (int i = 0; i < rawArgs.length(); i++) {
            char q = rawArgs.charAt(i);
            if (q != '"' && q != '\'') continue;
            int e = i + 1;
            while (e < rawArgs.length() && rawArgs.charAt(e) != q) {
                if (rawArgs.charAt(e) == '\\' && e + 1 < rawArgs.length()) e += 2;
                else e++;
            }
            if (e < rawArgs.length()) return rawArgs.substring(i + 1, e);
        }
        return null;
    }
}
