import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * JavaScript / TypeScript pass-1 extractor.
 *
 * Brace walk over masked source, same shape as {@link JavaExtractor}. Template literals are
 * masked as strings via {@link Lex#maskCLike}. Annotation-like literals (route paths, module
 * specifiers) are read back from the raw source at the same offsets.
 */
final class JsTsExtractor implements Extractor {

    private static final Set<String> EXT = Set.of(".ts", ".tsx", ".js", ".jsx", ".mjs", ".cjs");

    private static final Set<String> MODIFIERS = Set.of(
            "public", "private", "protected", "static", "readonly", "abstract", "async",
            "override", "declare", "default", "export", "const", "get", "set", "accessor");

    private static final Set<String> NOT_CALL = Set.of(
            "if", "for", "while", "switch", "catch", "return", "typeof", "function",
            "else", "do", "try", "finally", "throw", "new", "case", "break", "continue",
            "class", "interface", "enum", "type", "const", "let", "var", "import", "export",
            "from", "of", "in", "as", "is", "extends", "implements", "yield", "with",
            "debugger", "delete", "instanceof", "void", "await", "package", "default",
            "true", "false", "null", "undefined", "this");

    private static final Set<String> ROUTE_VERBS = Set.of("get", "post", "put", "delete", "patch", "all");

    private static final Set<String> TYPE_KW = Set.of("class", "interface", "enum", "type");

    public String language() { return "typescript"; }

    public boolean supports(String p) {
        if (p == null) return false;
        String lower = p.toLowerCase(Locale.ROOT);
        for (String e : EXT) if (lower.endsWith(e)) return true;
        return false;
    }

    private static final class Scope {
        String id;
        String qname;
        String type;            // file / class / interface / enum / function / method / constructor / block
        int openDepth;
        int start;
        int end = Integer.MAX_VALUE; // exclusive; set when the matching `}` is seen
        GraphModel.Node node;
        Map<String, String> vars = new LinkedHashMap<>();
        Scope parent;
        boolean isTypeBody;     // class / interface / enum
    }

    public FileFacts extract(String path, String src) {
        String fileLang = fileLanguage(path);
        FileFacts f = new FileFacts(path, fileLang);
        f.namespace = stripExt(path);
        char[] mask;
        try {
            mask = Lex.maskCLike(src == null ? "" : src, false, true);
        } catch (RuntimeException e) {
            f.error = "mask failed: " + e;
            return f;
        }
        try {
            walk(f, path, fileLang, src == null ? "" : src, mask);
        } catch (RuntimeException e) {
            f.error = "parse failed: " + e;
        }
        return f;
    }

    private void walk(FileFacts f, String path, String fileLang, String src, char[] mask) {
        int[] li = Lex.lineIndex(mask);
        char[] raw = src.toCharArray();
        String fileNodeId = GraphModel.fileId(path);
        boolean testFile = isTestPath(path);

        List<Scope> allScopes = new ArrayList<>();
        Deque<Scope> stack = new ArrayDeque<>();
        Scope fileScope = new Scope();
        fileScope.id = fileNodeId;
        fileScope.type = "file";
        fileScope.qname = "";
        fileScope.openDepth = -1;
        fileScope.start = 0;
        stack.push(fileScope);
        allScopes.add(fileScope);

        if (testFile) {
            GraphModel.Node tn = new GraphModel.Node(
                    GraphModel.symbolId(fileLang, "test", path, path, -1),
                    "test", baseName(path), path, fileLang, path);
            tn.startLine = 1;
            tn.endLine = 1;
            tn.attrs.put("test", true);
            f.add(tn);
            f.defines(fileNodeId, tn.id, 1);
        }

        int depth = 0, headStart = 0, n = mask.length;

        for (int i = 0; i < n; i++) {
            char c = mask[i];
            if (c == '{') {
                // import/export brace lists are not scopes: import { a } from "m"
                String preview = new String(mask, headStart, i - headStart).trim();
                if (looksLikeImportExport(preview)) {
                    int close = matchBraceArr(mask, i);
                    if (close > i) { i = close; continue; }
                }
                String head = new String(mask, headStart, i - headStart);
                String rawHead = safeSlice(raw, headStart, i);
                int line = Lex.lineOf(li, headStart + leadingWs(head));
                Scope cur = stack.peek();
                Scope s = declare(f, path, fileLang, head, rawHead, line, cur, i, testFile);
                if (s == null) {
                    s = new Scope();
                    s.type = "block";
                    s.qname = cur.qname;
                    s.parent = cur;
                    s.id = cur.id;
                }
                s.openDepth = depth;
                s.start = i;
                depth++;
                stack.push(s);
                allScopes.add(s);
                headStart = i + 1;
            } else if (c == '}') {
                // flush remaining head for enum bodies (comma-separated, no ';')
                Scope top = stack.peek();
                if (top != null && "enum".equals(top.type) && headStart < i) {
                    String head = new String(mask, headStart, i - headStart);
                    String rawHead = safeSlice(raw, headStart, i);
                    int line = Lex.lineOf(li, headStart + leadingWs(head));
                    emitEnumMembers(f, path, fileLang, head, rawHead, line, top);
                }
                depth = Math.max(0, depth - 1);
                while (stack.size() > 1 && stack.peek().openDepth >= depth) {
                    Scope s = stack.pop();
                    s.end = i + 1;
                    if (s.node != null) s.node.endLine = Lex.lineOf(li, i);
                }
                headStart = i + 1;
            } else if (c == ';') {
                String head = new String(mask, headStart, i - headStart);
                String rawHead = safeSlice(raw, headStart, i);
                int line = Lex.lineOf(li, headStart + leadingWs(head));
                statement(f, path, fileLang, head, rawHead, line, stack.peek(), testFile);
                headStart = i + 1;
            }
        }
        while (!stack.isEmpty()) {
            Scope s = stack.pop();
            if (s.end == Integer.MAX_VALUE) s.end = n;
            if (s.node != null && s.node.endLine == 0) s.node.endLine = Lex.lineOf(li, n > 0 ? n - 1 : 0);
        }

        collectCalls(f, path, fileLang, mask, raw, li, allScopes, fileNodeId);
        collectRoutes(f, path, fileLang, mask, raw, li, allScopes, fileNodeId);
    }

    // ---------- statements ending in `;` ----------

    private void statement(FileFacts f, String path, String fileLang, String head, String rawHead,
                           int line, Scope cur, boolean testFile) {
        String t = head.trim();
        if (t.isEmpty()) return;

        // imports / re-exports / export lists
        if (handleImportOrExport(f, path, fileLang, t, rawHead.trim(), line, cur)) return;

        // require(...) CommonJS
        if (handleRequire(f, t, rawHead.trim(), line, cur)) return;

        // type alias: type Name = ...
        if (isTopLevel(cur) || "block".equals(cur.type)) {
            Decl td = parseTypeAlias(t);
            if (td != null) {
                emitTopDecl(f, path, fileLang, td, line, cur, testFile, true);
                return;
            }
        }

        // function declaration without body already handled via `{`; abstract-style ends here rarely

        // class fields / methods ending with `;` (abstract methods, fields)
        if (cur != null && cur.isTypeBody && cur.node != null) {
            if ("interface".equals(cur.type)) return; // interface props are not graph nodes
            if ("enum".equals(cur.type)) {
                emitEnumMembers(f, path, fileLang, t, rawHead, line, cur);
                return;
            }
            Decl d = parseMember(t, rawHead);
            if (d == null) return;
            if (d.isMethod) {
                emitMethod(f, path, fileLang, d, line, cur, null, testFile);
            } else if (d.name != null && !d.name.isEmpty()) {
                emitField(f, path, fileLang, d, line, cur, testFile);
            }
            return;
        }

        // top-level const/let/var / function expression / arrow
        if (isTopLevel(cur)) {
            Decl d = parseVarOrFunction(t, rawHead);
            if (d != null) {
                emitTopDecl(f, path, fileLang, d, line, cur, testFile, true);
                // remember annotated locals / simple bindings for receiverType
                if (d.name != null) {
                    if (d.typeName != null && !"any".equals(d.typeName) && !"unknown".equals(d.typeName)) {
                        cur.vars.put(d.name, baseType(d.typeName));
                    } else {
                        String ctor = constructedType(t);
                        if (ctor != null) cur.vars.put(d.name, ctor);
                    }
                }
            }
            return;
        }

        // locals inside functions: type annotations, plus `new Type()` which is just as certain
        if (cur != null && (cur.node != null || "block".equals(cur.type))) {
            Decl d = parseVarOrFunction(t, rawHead);
            if (d != null && d.name != null) {
                if (d.typeName != null && !"any".equals(d.typeName) && !"unknown".equals(d.typeName)) {
                    cur.vars.put(d.name, baseType(d.typeName));
                } else {
                    String ctor = constructedType(t);
                    if (ctor != null) cur.vars.put(d.name, ctor);
                }
            }
        }
    }

    /**
     * The type of `... = new Foo(...)`. An explicit constructor call states the type as firmly
     * as an annotation does, and in JS it is often the only thing that does.
     */
    private static String constructedType(String head) {
        int eq = head.indexOf('=');
        if (eq < 0) return null;
        String rhs = head.substring(eq + 1).trim();
        if (!rhs.startsWith("new ")) return null;
        String rest = rhs.substring(4).trim();
        int end = 0;
        while (end < rest.length() && (Lex.isIdentPart(rest.charAt(end)) || rest.charAt(end) == '.')) end++;
        if (end == 0) return null;
        String t = rest.substring(0, end);
        int dot = t.lastIndexOf('.');
        return dot >= 0 ? t.substring(dot + 1) : t;
    }

    // ---------- declaration heads ending in `{` ----------

    private Scope declare(FileFacts f, String path, String fileLang, String head, String rawHead,
                          int line, Scope cur, int off, boolean testFile) {
        String t = head.trim();
        if (t.isEmpty()) return null;

        // export default function/class or function/class
        Decl d = parseBraceDecl(t, rawHead);
        if (d == null) {
            // arrow/function expression assigned to const: const f = (...) => {
            Decl v = parseVarOrFunction(t, rawHead);
            if (v != null && v.isMethod && isTopLevel(cur)) {
                GraphModel.Node node = emitTopDecl(f, path, fileLang, v, line, cur, testFile, true);
                if (node == null) return null;
                Scope sc = new Scope();
                sc.id = node.id;
                sc.qname = node.qname;
                sc.type = node.type;
                sc.node = node;
                sc.parent = cur;
                bindParams(sc, v);
                return sc;
            }
            return null;
        }

        if (d.typeKeyword != null) {
            String kind = switch (d.typeKeyword) {
                case "interface" -> "interface";
                case "enum" -> "enum";
                case "type" -> "type";
                default -> "class";
            };
            String qn = d.name;
            GraphModel.Node node = new GraphModel.Node(
                    GraphModel.symbolId(fileLang, kind, path, qn, -1),
                    kind, d.name, qn, fileLang, path);
            node.startLine = line;
            node.visibility = visibility(d.modifiers);
            if (testFile) node.attrs.put("test", true);
            f.add(node);
            if (isTopLevel(cur)) f.defines(GraphModel.fileId(path), node.id, line);
            else if (cur.id != null) f.contains(cur.id, node.id, line);

            if (d.isExport) f.exports.put(d.name, node.id);
            if (d.isDefaultExport) f.exports.put("default", node.id);

            for (String s : d.extendsNames) f.supers.add(new TypeRef(node.id, s, "EXTENDS", line));
            for (String s : d.implementsNames) f.supers.add(new TypeRef(node.id, s, "IMPLEMENTS", line));

            Scope sc = new Scope();
            sc.id = node.id;
            sc.qname = qn;
            sc.type = kind;
            sc.node = node;
            sc.parent = cur;
            sc.isTypeBody = "class".equals(kind) || "interface".equals(kind) || "enum".equals(kind);
            return sc;
        }

        // function declaration (top-level or nested)
        if (d.isMethod && d.name != null && (isTopLevel(cur) || "block".equals(cur.type) || cur.node != null)) {
            if (cur != null && cur.isTypeBody && cur.node != null && "class".equals(cur.type)) {
                return emitMethod(f, path, fileLang, d, line, cur, off, testFile);
            }
            // top-level / nested function
            if (isTopLevel(cur) || !cur.isTypeBody) {
                String qn = d.name;
                int arity = d.params.size();
                GraphModel.Node node = new GraphModel.Node(
                        GraphModel.symbolId(fileLang, "function", path, qn, arity),
                        "function", d.name, qn, fileLang, path);
                node.startLine = line;
                node.endLine = line;
                node.visibility = visibility(d.modifiers);
                node.signature = d.name + "(" + String.join(", ", d.params) + ")";
                if (testFile) node.attrs.put("test", true);
                f.add(node);
                if (isTopLevel(cur)) f.defines(GraphModel.fileId(path), node.id, line);
                else if (cur.id != null && cur.node != null) f.contains(cur.id, node.id, line);
                else f.defines(GraphModel.fileId(path), node.id, line);
                if (d.isExport) f.exports.put(d.name, node.id);
                if (d.isDefaultExport) f.exports.put("default", node.id);
                Scope sc = new Scope();
                sc.id = node.id;
                sc.qname = qn;
                sc.type = "function";
                sc.node = node;
                sc.parent = cur;
                bindParams(sc, d);
                return sc;
            }
        }

        // class member method / constructor
        if (d.isMethod && cur != null && cur.isTypeBody && cur.node != null && "class".equals(cur.type)) {
            return emitMethod(f, path, fileLang, d, line, cur, off, testFile);
        }

        return null;
    }

    private Scope emitMethod(FileFacts f, String path, String fileLang, Decl d, int line,
                             Scope cur, Integer off, boolean testFile) {
        boolean ctor = "constructor".equals(d.name);
        String kind = ctor ? "constructor" : "method";
        String qn = cur.qname + "." + d.name;
        int arity = d.params.size();
        GraphModel.Node node = new GraphModel.Node(
                GraphModel.symbolId(fileLang, kind, path, qn, arity),
                kind, d.name, qn, fileLang, path);
        node.startLine = line;
        node.endLine = line;
        node.visibility = visibility(d.modifiers);
        node.signature = d.name + "(" + String.join(", ", d.params) + ")"
                + (d.typeName != null ? " : " + d.typeName : "");
        if (d.modifiers.contains("static")) node.attrs.put("static", true);
        if (testFile) node.attrs.put("test", true);
        f.add(node);
        f.contains(cur.id, node.id, line);

        // parameter properties: constructor(private svc: CaseService)
        if (ctor) {
            for (String p : d.params) {
                String[] kv = splitTsParam(p);
                if (kv == null) continue;
                if (kv.length >= 3 && kv[2] != null) {
                    // access-modifier parameter property -> field
                    Decl fd = new Decl();
                    fd.name = kv[1];
                    fd.typeName = kv[0];
                    fd.modifiers.add(kv[2]);
                    emitField(f, path, fileLang, fd, line, cur, testFile);
                }
                if (kv[0] != null && !"any".equals(kv[0]) && !"unknown".equals(kv[0])) {
                    // bound on method scope below
                }
            }
        }

        if (off == null) {
            // abstract / signature-only
            return null;
        }
        Scope sc = new Scope();
        sc.id = node.id;
        sc.qname = qn;
        sc.type = kind;
        sc.node = node;
        sc.parent = cur;
        bindParams(sc, d);
        // this -> enclosing class for receiverType lookups
        sc.vars.put("this", cur.qname);
        return sc;
    }

    private void emitField(FileFacts f, String path, String fileLang, Decl d, int line,
                           Scope cur, boolean testFile) {
        String qn = cur.qname + "." + d.name;
        GraphModel.Node fn = new GraphModel.Node(
                GraphModel.symbolId(fileLang, "field", path, qn, -1),
                "field", d.name, qn, fileLang, path);
        fn.startLine = line;
        fn.endLine = line;
        fn.visibility = visibility(d.modifiers);
        if (d.typeName != null) fn.signature = d.typeName + " " + d.name;
        if (testFile) fn.attrs.put("test", true);
        f.add(fn);
        f.contains(cur.id, fn.id, line);
        if (d.typeName != null && !"any".equals(d.typeName) && !"unknown".equals(d.typeName)) {
            cur.vars.put(d.name, baseType(d.typeName));
        }
    }

    private GraphModel.Node emitTopDecl(FileFacts f, String path, String fileLang, Decl d, int line,
                                        Scope cur, boolean testFile, boolean exportedOk) {
        if (d.name == null || d.name.isEmpty()) return null;
        String kind;
        int arity = -1;
        if (d.typeKeyword != null) {
            kind = switch (d.typeKeyword) {
                case "interface" -> "interface";
                case "enum" -> "enum";
                case "type" -> "type";
                default -> "class";
            };
        } else if (d.isMethod) {
            kind = "function";
            arity = d.params.size();
        } else {
            kind = "constant";
        }
        String qn = d.name;
        GraphModel.Node node = new GraphModel.Node(
                GraphModel.symbolId(fileLang, kind, path, qn, arity),
                kind, d.name, qn, fileLang, path);
        node.startLine = line;
        node.endLine = line;
        node.visibility = visibility(d.modifiers);
        if (d.isMethod) node.signature = d.name + "(" + String.join(", ", d.params) + ")";
        if (testFile) node.attrs.put("test", true);
        f.add(node);
        if (isTopLevel(cur)) f.defines(GraphModel.fileId(path), node.id, line);
        else if (cur != null && cur.id != null) f.contains(cur.id, node.id, line);

        if (exportedOk) {
            if (d.isExport) f.exports.put(d.name, node.id);
            if (d.isDefaultExport) f.exports.put("default", node.id);
        }
        return node;
    }

    private void emitEnumMembers(FileFacts f, String path, String fileLang, String head,
                                 String rawHead, int line, Scope cur) {
        for (String part : splitTop(head, ',')) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            // Name = value  or  Name
            String name = firstIdent(p);
            if (name.isEmpty() || MODIFIERS.contains(name)) continue;
            String qn = cur.qname + "." + name;
            GraphModel.Node c = new GraphModel.Node(
                    GraphModel.symbolId(fileLang, "constant", path, qn, -1),
                    "constant", name, qn, fileLang, path);
            c.startLine = line;
            c.endLine = line;
            c.visibility = "public";
            f.add(c);
            f.contains(cur.id, c.id, line);
        }
    }

    // ---------- imports / exports / require ----------

    private boolean handleImportOrExport(FileFacts f, String path, String fileLang, String t,
                                         String raw, int line, Scope cur) {
        String s = collapseWs(t);
        String rawS = collapseWs(raw);

        // re-export: export { a, b as c } from "m"  /  export type { T } from "m"
        // NB: the masked head ends at `from` because the module literal has been blanked, so a
        // `contains(" from ")` test misses every re-export. Match the word, not the spacing.
        if (s.startsWith("export ") && findWord(s, "from") >= 0) {
            int from = findWord(s, "from");
            if (from >= 0) {
                // NB: `s` is masked and `rawS` is not, and collapsing whitespace shortens them
                // by different amounts -- so an offset taken from one cannot index the other.
                String mod = lastStringLit(rawS);
                if (mod == null) mod = stringLit(rawS, from);
                String between = s.substring(0, from).trim();
                // export { ... } from
                int lb = between.indexOf('{');
                int rb = between.lastIndexOf('}');
                if (lb >= 0 && rb > lb && mod != null) {
                    String body = between.substring(lb + 1, rb);
                    for (String part : splitTop(body, ',')) {
                        String p = part.trim();
                        if (p.isEmpty()) continue;
                        String[] ab = parseAs(p);
                        if (ab[0] == null || ab[0].isEmpty()) continue;
                        String member = ab[0];
                        String alias = ab[1] != null && !ab[1].isEmpty() ? ab[1] : ab[0];
                        f.imports.add(new ImportRef(alias, mod, member, "es", false, line, rawS));
                        f.exports.put(alias, ""); // re-export: no local node
                    }
                    return true;
                }
                // export * from "m" / export * as ns from "m"
                if (between.contains("*") && mod != null) {
                    f.imports.add(new ImportRef(null, mod, null, "es", true, line, rawS));
                    return true;
                }
            }
        }

        // export { a, b as c }  (local)
        if (s.startsWith("export ") && s.contains("{") && findWord(s, "from") < 0) {
            int lb = s.indexOf('{');
            int rb = s.lastIndexOf('}');
            if (lb >= 0 && rb > lb) {
                for (String part : splitTop(s.substring(lb + 1, rb), ',')) {
                    String p = part.trim();
                    if (p.isEmpty()) continue;
                    String[] ab = parseAs(p);
                    String name = ab[0];
                    String as = ab[1] != null ? ab[1] : ab[0];
                    // map export name -> existing node id if we know it
                    String id = findNodeIdByName(f, name);
                    f.exports.put(as, id != null ? id : "");
                }
                // bare export { } may also wrap a default-less list after other decls
                if (!s.contains("function") && !s.contains("class") && !s.contains("const")
                        && !s.contains("let") && !s.contains("var") && !s.contains("interface")
                        && !s.contains("enum") && !s.contains("type ") && findWord(s, "type") < 0) {
                    return true;
                }
            }
        }

        // export default <name>;
        if (s.startsWith("export default ") || s.equals("export default")) {
            String rest = s.substring("export default".length()).trim();
            if (!rest.isEmpty() && !rest.startsWith("function") && !rest.startsWith("class")
                    && !rest.startsWith("abstract") && !rest.startsWith("async")) {
                String name = firstIdent(rest);
                if (!name.isEmpty()) {
                    String id = findNodeIdByName(f, name);
                    f.exports.put("default", id != null ? id : "");
                } else {
                    f.exports.put("default", "");
                }
                return true;
            }
            // export default function/class falls through to declaration parsing
        }

        // import forms
        if (s.startsWith("import ") || s.startsWith("import{")) {
            parseEsImport(f, s, rawS, line, cur);
            return true;
        }

        // export const/let/function/class handled as declarations with isExport flag in parse*
        // Fall through for `export const x = ...` etc.
        if (s.startsWith("export ")) return false;

        return false;
    }

    private void parseEsImport(FileFacts f, String s, String rawS, int line, Scope cur) {
        // strip leading import / import type
        String rest = s.substring(6).trim(); // after "import"
        boolean typeOnly = false;
        if (rest.startsWith("type ") || rest.equals("type") || rest.startsWith("type{")) {
            typeOnly = true;
            if (rest.startsWith("type")) rest = rest.substring(4).trim();
        }

        // bare: import "m"
        if (rest.startsWith("\"") || rest.startsWith("'") || rest.startsWith("`")) {
            String mod = stringLit(rawS, 0);
            if (mod == null) mod = unquote(rest);
            f.imports.add(new ImportRef(null, mod, null, "es", false, line, rawS));
            return;
        }

        int from = findWord(rest, "from");
        String mod = null;
        String clause = rest;
        if (from >= 0) {
            mod = stringLit(rawS.substring(rawS.toLowerCase(Locale.ROOT).lastIndexOf("from") >= 0
                    ? Math.max(0, rawS.toLowerCase(Locale.ROOT).lastIndexOf("from")) : 0), 0);
            // more reliable: string after from in rawS
            int rawFrom = findWord(rawS, "from");
            if (rawFrom < 0) rawFrom = findWord(s, "from");
            mod = stringLit(rawS, rawFrom >= 0 ? rawFrom : 0);
            if (mod == null) mod = stringLit(s, from);
            clause = rest.substring(0, from).trim();
        }
        if (mod == null) return;

        // import * as ns from "m"
        if (clause.startsWith("*")) {
            String alias = null;
            int as = findWord(clause, "as");
            if (as >= 0) alias = firstIdent(clause.substring(as + 2).trim());
            f.imports.add(new ImportRef(alias, mod, null, "es", true, line, rawS));
            if (alias != null && cur != null) {
                // module namespace alias — record module specifier as type hint for resolver
                cur.vars.put(alias, mod);
            }
            return;
        }

        // default + optional named: import X from "m"  /  import X, { a } from "m"
        String defaultAlias = null;
        int brace = clause.indexOf('{');
        if (brace < 0) {
            // import X from "m" only
            defaultAlias = firstIdent(clause);
            if (!defaultAlias.isEmpty()) {
                f.imports.add(new ImportRef(defaultAlias, mod, "default", "es", false, line, rawS));
                if (cur != null) cur.vars.put(defaultAlias, defaultAlias);
            }
            return;
        }
        // possible default before brace
        String before = clause.substring(0, brace).trim();
        if (before.endsWith(",")) before = before.substring(0, before.length() - 1).trim();
        if (!before.isEmpty() && !before.equals("type")) {
            defaultAlias = firstIdent(before);
            if (!defaultAlias.isEmpty() && !"type".equals(defaultAlias)) {
                f.imports.add(new ImportRef(defaultAlias, mod, "default", "es", false, line, rawS));
                if (cur != null) cur.vars.put(defaultAlias, defaultAlias);
            }
        }
        int rb = clause.lastIndexOf('}');
        if (rb > brace) {
            String body = clause.substring(brace + 1, rb);
            // drop leading type keyword inside: import { type T, a }
            for (String part : splitTop(body, ',')) {
                String p = part.trim();
                if (p.isEmpty()) continue;
                if (p.startsWith("type ")) p = p.substring(5).trim();
                String[] ab = parseAs(p);
                if (ab[0] == null || ab[0].isEmpty()) continue;
                String member = ab[0];
                String alias = ab[1] != null ? ab[1] : ab[0];
                f.imports.add(new ImportRef(alias, mod, member, "es", false, line, rawS));
                if (cur != null) cur.vars.put(alias, member);
            }
        }
    }

    private boolean handleRequire(FileFacts f, String t, String raw, int line, Scope cur) {
        String s = collapseWs(t);
        // const x = require("m")  /  let x = require('m')
        int req = findWord(s, "require");
        if (req < 0) return false;
        int lp = s.indexOf('(', req);
        if (lp < 0) return false;
        String mod = stringLit(raw, raw.indexOf('(', Math.max(0, findWord(raw, "require"))));
        if (mod == null) mod = stringLit(s, lp);
        if (mod == null) return false;

        String alias = null;
        // leading const/let/var name =
        String before = s.substring(0, req).trim();
        if (before.startsWith("const ") || before.startsWith("let ") || before.startsWith("var ")) {
            String lhs = before;
            int sp = lhs.indexOf(' ');
            lhs = lhs.substring(sp + 1).trim();
            int eq = lhs.indexOf('=');
            if (eq >= 0) lhs = lhs.substring(0, eq).trim();
            // strip type annotation
            int colon = indexOfTop(lhs, ':');
            if (colon >= 0) lhs = lhs.substring(0, colon).trim();
            alias = firstIdent(lhs);
        }
        f.imports.add(new ImportRef(alias, mod, null, "cjs", false, line, raw));
        if (alias != null && cur != null) cur.vars.put(alias, mod);
        return true;
    }

    // ---------- calls ----------

    private void collectCalls(FileFacts f, String path, String fileLang, char[] mask, char[] raw,
                              int[] li, List<Scope> scopes, String fileNodeId) {
        ScopeCursor scopeCursor = new ScopeCursor(scopes);
        for (int i = 0; i < mask.length; i++) {
            if (mask[i] != '(') continue;
            String name = Lex.identBefore(mask, i);
            if (name.isEmpty() || NOT_CALL.contains(name)) continue;
            int nameStart = i - name.length();
            String receiver = null;
            if (nameStart > 0 && mask[nameStart - 1] == '.') {
                receiver = Lex.receiverBefore(mask, nameStart - 1);
                if (receiver.isEmpty()) receiver = null;
            }
            // declaration site: `function foo(`, `async getCase(`, `getName() {`, typed params
            if (receiver == null && isDeclarationSite(mask, nameStart, name, i)) continue;
            Scope owner = scopeCursor.at(i);
            if (owner == null) continue;
            if (owner.node != null && owner.node.startLine == Lex.lineOf(li, i)
                    && name.equals(owner.node.name) && receiver == null) continue;
            // require( is an import, not a call ref
            if ("require".equals(name) && receiver == null) continue;

            boolean isNew = precededByNew(mask, nameStart);
            int arity = arity(raw, i);
            String from = owner.node != null ? owner.node.id : fileNodeId;
            // file-level test node: prefer file id for refs (GT keys off the file)
            if (from != null && from.equals(owner.id) && "test".equals(owner.type)) from = fileNodeId;
            String rt = receiver == null ? null : lookupVar(owner, receiver);
            // never invent a receiverType for `any` / `unknown`
            if ("any".equals(rt) || "unknown".equals(rt)) rt = null;
            f.refs.add(new Ref(from, receiver, rt, name, arity, Lex.lineOf(li, i), isNew ? "new" : "call"));
        }
    }

    private void collectRoutes(FileFacts f, String path, String fileLang, char[] mask, char[] raw,
                               int[] li, List<Scope> scopes, String fileNodeId) {
        ScopeCursor routeCursor = new ScopeCursor(scopes);
        for (int i = 0; i < mask.length; i++) {
            if (mask[i] != '(') continue;
            String verb = Lex.identBefore(mask, i);
            if (verb.isEmpty() || !ROUTE_VERBS.contains(verb.toLowerCase(Locale.ROOT))) continue;
            int verbStart = i - verb.length();
            if (verbStart <= 0 || mask[verbStart - 1] != '.') continue;
            String receiver = Lex.receiverBefore(mask, verbStart - 1);
            if (receiver.isEmpty()) continue;
            // router.get / app.post etc.
            String head = receiver.contains(".")
                    ? receiver.substring(receiver.lastIndexOf('.') + 1) : receiver;
            // accept any receiver; conventional router/app/server

            // first string arg from RAW — must look like a URL path, not Map.get("key")
            String routePath = firstStringArg(raw, i);
            if (routePath == null || routePath.isEmpty()) continue;
            if (routePath.charAt(0) != '/' && routePath.charAt(0) != '*') continue;
            int line = Lex.lineOf(li, i);
            String http = verb.toUpperCase(Locale.ROOT);
            if ("ALL".equals(http)) http = "ALL";
            String eq = http + " " + routePath;
            GraphModel.Node ep = new GraphModel.Node(
                    GraphModel.symbolId(fileLang, "endpoint", path, eq, -1),
                    "endpoint", eq, eq, fileLang, path);
            ep.startLine = line;
            ep.endLine = line;
            ep.attrs.put("http_method", http);
            ep.attrs.put("path", routePath);
            f.add(ep);
            f.defines(fileNodeId, ep.id, line);

            // handler: second arg — identifier vs inline
            int[] argBounds = nthArgBounds(mask, i, 1);
            if (argBounds == null) continue;
            String argText = new String(mask, argBounds[0], argBounds[1] - argBounds[0]).trim();
            if (argText.isEmpty()) continue;
            // named identifier only (no '(' and no '=>')
            if (isSimpleIdent(argText)) {
                Scope owner = routeCursor.at(i);
                String from = owner != null && owner.node != null ? owner.node.id : fileNodeId;
                f.refs.add(new Ref(from, null, null, argText, -1, line, "call"));
                // leave ROUTES_TO to resolver
            } else {
                // inline handler: ROUTES_TO enclosing function (or file-level symbol if any)
                Scope owner = routeCursor.at(i);
                String target = owner != null && owner.node != null ? owner.node.id : fileNodeId;
                f.edges.add(new GraphModel.Edge(ep.id, target, "ROUTES_TO", GraphModel.EXTRACTED,
                        path, line, "express-route"));
            }
        }
    }

    // ---------- head parsing ----------

    private static final class Decl {
        Set<String> modifiers = new LinkedHashSet<>();
        String typeKeyword;
        String name;
        String typeName;
        boolean isMethod;
        boolean isExport;
        boolean isDefaultExport;
        List<String> params = new ArrayList<>();
        List<String> extendsNames = new ArrayList<>();
        List<String> implementsNames = new ArrayList<>();
    }

    private static Decl parseBraceDecl(String head, String rawHead) {
        Decl d = new Decl();
        String t = head.replace('\n', ' ').replace('\r', ' ').trim();
        if (t.isEmpty()) return null;

        // strip leading export / default / declare / async
        t = stripExportPrefix(t, d);

        // class / interface / enum
        for (String kw : new String[]{"class", "interface", "enum"}) {
            int p = findWord(t, kw);
            if (p < 0) continue;
            String before = t.substring(0, p).trim();
            if (before.contains("=") && !before.endsWith("=")) {
                // e.g. const x = class — still ok if = is last meaningful
            }
            for (String m : before.split("\\s+")) if (MODIFIERS.contains(m)) d.modifiers.add(m);
            String after = t.substring(p + kw.length()).trim();
            String name = firstIdent(after);
            if (name.isEmpty()) continue;
            d.typeKeyword = kw;
            d.name = name;
            String rest = after.substring(after.indexOf(name) + name.length());
            rest = stripGenerics(rest);
            int ext = findWord(rest, "extends");
            int imp = findWord(rest, "implements");
            if (ext >= 0) {
                String seg = imp > ext ? rest.substring(ext + 7, imp) : rest.substring(ext + 7);
                for (String s : splitTop(seg, ',')) {
                    String b = baseType(s.trim());
                    if (!b.isEmpty()) {
                        if ("interface".equals(kw)) d.extendsNames.add(b);
                        else d.extendsNames.add(b);
                    }
                }
            }
            if (imp >= 0 && "class".equals(kw)) {
                String seg = rest.substring(imp + 10);
                for (String s : splitTop(seg, ',')) {
                    String b = baseType(s.trim());
                    if (!b.isEmpty()) d.implementsNames.add(b);
                }
            }
            return d;
        }

        // function name(...) {
        int fk = findWord(t, "function");
        if (fk >= 0) {
            String after = t.substring(fk + 8).trim();
            // skip generics on function
            if (after.startsWith("<")) after = stripLeadingGenerics(after).trim();
            String name = firstIdent(after);
            if (name.isEmpty()) {
                // export default function(
                if (d.isDefaultExport) name = "default";
                else return null;
            }
            int lp = after.indexOf('(');
            if (lp < 0) return null;
            int rp = matching(after, lp);
            if (rp < 0) return null;
            for (String p : splitTop(after.substring(lp + 1, rp), ',')) {
                if (!p.isBlank()) d.params.add(p.trim());
            }
            d.name = name;
            d.isMethod = true;
            return d;
        }

        // class member: name(...) {  or  async name(...) {  or  constructor(
        // or get/set name(
        Decl mem = parseMember(t, rawHead);
        if (mem != null && mem.isMethod) return mem;

        return null;
    }

    private static Decl parseMember(String head, String rawHead) {
        Decl d = new Decl();
        String t = head.replace('\n', ' ').replace('\r', ' ').trim();
        if (t.isEmpty()) return null;
        t = stripExportPrefix(t, d);

        // field: [mods] name: Type = ...   or  name: Type
        // method: [mods] name<G>(params): Ret
        // constructor(params)
        int lp = indexOfTop(t, '(');
        if (lp > 0) {
            String before = t.substring(0, lp).trim();
            before = stripGenerics(before).trim();
            String[] toks = before.split("\\s+");
            if (toks.length == 0) return null;
            String name = toks[toks.length - 1];
            if (name.isEmpty() || !Lex.isIdentStart(name.charAt(0))) return null;
            if (NOT_CALL.contains(name) && !"constructor".equals(name)) return null;
            for (int i = 0; i < toks.length - 1; i++) {
                if (MODIFIERS.contains(toks[i])) d.modifiers.add(toks[i]);
            }
            int rp = matching(t, lp);
            if (rp < 0) return null;
            String paramSrc = t.substring(lp + 1, rp);
            for (String p : splitTop(paramSrc, ',')) if (!p.isBlank()) d.params.add(p.trim());
            d.name = name;
            d.isMethod = true;
            // return type after ):
            String after = t.substring(rp + 1).trim();
            if (after.startsWith(":")) {
                String rt = after.substring(1).trim();
                int brace = indexOfTop(rt, '{');
                int arrow = rt.indexOf("=>");
                if (arrow >= 0 && (brace < 0 || arrow < brace)) rt = rt.substring(0, arrow).trim();
                d.typeName = baseType(rt);
            }
            return d;
        }

        // field without (
        String lhs = t;
        int eq = indexOfTop(t, '=');
        if (eq > 0) lhs = t.substring(0, eq).trim();
        // strip definite assignment !
        lhs = lhs.replace("!", " ").trim();
        int colon = indexOfTop(lhs, ':');
        String namePart = colon >= 0 ? lhs.substring(0, colon).trim() : lhs;
        String typePart = colon >= 0 ? lhs.substring(colon + 1).trim() : null;
        String[] toks = namePart.split("\\s+");
        if (toks.length == 0) return null;
        String name = toks[toks.length - 1];
        if (name.isEmpty() || !Lex.isIdentStart(name.charAt(0))) return null;
        if (TYPE_KW.contains(name) || NOT_CALL.contains(name)) return null;
        for (int i = 0; i < toks.length - 1; i++) {
            if (MODIFIERS.contains(toks[i])) d.modifiers.add(toks[i]);
        }
        // must look like a field: has modifier or type annotation or is simple name in class
        d.name = name;
        if (typePart != null) d.typeName = baseType(typePart);
        d.isMethod = false;
        return d;
    }

    private static Decl parseVarOrFunction(String head, String rawHead) {
        Decl d = new Decl();
        String t = head.replace('\n', ' ').replace('\r', ' ').trim();
        if (t.isEmpty()) return null;
        t = stripExportPrefix(t, d);

        // function foo(...)  (no brace — shouldn't happen often)
        int fk = findWord(t, "function");
        if (fk >= 0 && indexOfTop(t, '=') < 0) {
            String after = t.substring(fk + 8).trim();
            if (after.startsWith("<")) after = stripLeadingGenerics(after).trim();
            String name = firstIdent(after);
            int lp = after.indexOf('(');
            if (name.isEmpty() || lp < 0) return null;
            int rp = matching(after, lp);
            if (rp < 0) return null;
            for (String p : splitTop(after.substring(lp + 1, rp), ',')) {
                if (!p.isBlank()) d.params.add(p.trim());
            }
            d.name = name;
            d.isMethod = true;
            return d;
        }

        // const/let/var name: Type = ...
        int kwPos = -1;
        String kw = null;
        for (String k : new String[]{"const", "let", "var"}) {
            int p = findWord(t, k);
            if (p >= 0 && (kwPos < 0 || p < kwPos)) { kwPos = p; kw = k; }
        }
        if (kwPos < 0) return null;
        String after = t.substring(kwPos + kw.length()).trim();
        int eq = indexOfTop(after, '=');
        String lhs = eq >= 0 ? after.substring(0, eq).trim() : after;
        String rhs = eq >= 0 ? after.substring(eq + 1).trim() : "";

        // name: Type
        int colon = indexOfTop(lhs, ':');
        String nameStr = colon >= 0 ? lhs.substring(0, colon).trim() : lhs;
        String typeStr = colon >= 0 ? lhs.substring(colon + 1).trim() : null;
        // destructuring skip
        if (nameStr.startsWith("{") || nameStr.startsWith("[")) return null;
        String name = firstIdent(nameStr);
        if (name.isEmpty()) return null;
        d.name = name;
        if (typeStr != null) d.typeName = baseType(typeStr);

        // arrow or function expression on RHS?
        if (isFunctionExpr(rhs)) {
            d.isMethod = true;
            d.params.addAll(extractParamsFromFn(rhs));
        } else {
            d.isMethod = false;
        }
        return d;
    }

    private static Decl parseTypeAlias(String head) {
        Decl d = new Decl();
        String t = head.replace('\n', ' ').replace('\r', ' ').trim();
        t = stripExportPrefix(t, d);
        int p = findWord(t, "type");
        if (p < 0) return null;
        // avoid `import type` already handled; here `type Name =`
        String before = t.substring(0, p).trim();
        if (before.contains("import")) return null;
        String after = t.substring(p + 4).trim();
        String name = firstIdent(after);
        if (name.isEmpty()) return null;
        // must have = somewhere for alias
        if (indexOfTop(after, '=') < 0) return null;
        d.typeKeyword = "type";
        d.name = name;
        return d;
    }

    // ---------- small helpers ----------

    private static String fileLanguage(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".js") || lower.endsWith(".jsx") || lower.endsWith(".mjs") || lower.endsWith(".cjs")) {
            return "javascript";
        }
        return "typescript";
    }

    private static String stripExt(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        int dot = path.lastIndexOf('.');
        if (dot > slash) return path.substring(0, dot);
        return path;
    }

    private static String baseName(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static boolean isTestPath(String p) {
        String lower = p.replace('\\', '/').toLowerCase(Locale.ROOT);
        return lower.contains(".test.") || lower.contains(".spec.")
                || lower.contains("/tests/") || lower.startsWith("tests/")
                || lower.contains("/__tests__/") || lower.startsWith("__tests__/");
    }

    private static boolean isTopLevel(Scope cur) {
        return cur != null && "file".equals(cur.type);
    }

    /**
     * True when `{` opens an import/export name list, not a declaration body.
     * Matches {@code import { a }}, {@code import type { T }}, {@code export { a } from},
     * {@code export type { T } from}. Does NOT match {@code export class}/{@code function}/{@code enum}.
     */
    private static boolean looksLikeImportExport(String head) {
        String t = head.trim();
        if (t.startsWith("import") && (t.length() == 6 || !Lex.isIdentPart(t.charAt(6)))) {
            // import / import type / import X,  — brace list of bindings
            return true;
        }
        if (t.startsWith("export") && (t.length() == 6 || !Lex.isIdentPart(t.charAt(6)))) {
            String rest = t.substring(6).trim();
            if (rest.isEmpty()) return true;                 // export {
            if (rest.startsWith("{")) return true;            // export { a } / export { a } from "m"
            if (rest.startsWith("type")) {
                String after = rest.substring(4).trim();
                // export type { ... }  vs  export type Name =
                return after.isEmpty() || after.startsWith("{");
            }
            // export default { ... } is an expression, not a name-list — treat as body
            if (rest.startsWith("default")) return false;
            // export const/function/class/enum/interface/async — declaration bodies
            return false;
        }
        return false;
    }

    /**
     * True when {@code name(} is a function/method/constructor declaration head rather than a call.
     * Uses: leading keywords, typed parameter lists, or a body `{` after an optional return type.
     */
    private static boolean isDeclarationSite(char[] mask, int nameStart, String name, int lparen) {
        if ("constructor".equals(name)) return true;
        int i = nameStart;
        while (i > 0 && Character.isWhitespace(mask[i - 1])) i--;
        // generator: function *name(
        if (i > 0 && mask[i - 1] == '*') {
            int k = i - 1;
            while (k > 0 && Character.isWhitespace(mask[k - 1])) k--;
            String prevStar = Lex.identBefore(mask, k);
            if ("function".equals(prevStar)) return true;
        }
        String prev = Lex.identBefore(mask, i);
        if ("function".equals(prev) || "async".equals(prev) || "static".equals(prev)
                || "get".equals(prev) || "set".equals(prev)
                || "public".equals(prev) || "private".equals(prev) || "protected".equals(prev)
                || "readonly".equals(prev) || "abstract".equals(prev) || "override".equals(prev)
                || "declare".equals(prev) || "export".equals(prev) || "default".equals(prev)) {
            return true;
        }
        int rp = matchParenArr(mask, lparen);
        if (rp < 0) return false;
        // TypeScript typed parameters only appear on declarations
        if (hasTopLevelColon(mask, lparen + 1, rp)) return true;
        int j = nextNonWs(mask, rp + 1);
        if (j < mask.length && mask[j] == ':') {
            j = skipTsType(mask, j + 1);
            j = nextNonWs(mask, j);
        }
        return j < mask.length && mask[j] == '{';
    }

    private static int matchParenArr(char[] s, int open) {
        if (open < 0 || open >= s.length || s[open] != '(') return -1;
        int d = 0;
        for (int i = open; i < s.length; i++) {
            if (s[i] == '(') d++;
            else if (s[i] == ')') {
                d--;
                if (d == 0) return i;
            }
        }
        return -1;
    }

    private static boolean hasTopLevelColon(char[] s, int from, int to) {
        int dParen = 0, dBrack = 0, dBrace = 0, dAngle = 0;
        for (int i = from; i < to; i++) {
            char c = s[i];
            if (c == '(') dParen++;
            else if (c == ')') dParen = Math.max(0, dParen - 1);
            else if (c == '[') dBrack++;
            else if (c == ']') dBrack = Math.max(0, dBrack - 1);
            else if (c == '{') dBrace++;
            else if (c == '}') dBrace = Math.max(0, dBrace - 1);
            else if (c == '<') dAngle++;
            else if (c == '>') dAngle = Math.max(0, dAngle - 1);
            else if (c == ':' && dParen == 0 && dBrack == 0 && dBrace == 0 && dAngle == 0) return true;
        }
        return false;
    }

    private static int nextNonWs(char[] s, int i) {
        while (i < s.length && Character.isWhitespace(s[i])) i++;
        return i;
    }

    /** Skip a TypeScript type starting at {@code start}, stopping before a body `{` or terminator. */
    private static int skipTsType(char[] s, int start) {
        int j = nextNonWs(s, start);
        int dParen = 0, dBrack = 0, dBrace = 0, dAngle = 0;
        boolean seen = false;
        while (j < s.length) {
            char c = s[j];
            if (c == '(') { dParen++; seen = true; j++; continue; }
            if (c == ')') {
                if (dParen == 0 && dAngle == 0 && dBrack == 0 && dBrace == 0) return j;
                dParen = Math.max(0, dParen - 1);
                j++;
                continue;
            }
            if (c == '[') { dBrack++; seen = true; j++; continue; }
            if (c == ']') { dBrack = Math.max(0, dBrack - 1); j++; continue; }
            if (c == '<') { dAngle++; seen = true; j++; continue; }
            if (c == '>') { dAngle = Math.max(0, dAngle - 1); j++; continue; }
            if (c == '{') {
                if (dParen == 0 && dAngle == 0 && dBrack == 0 && dBrace == 0 && seen) {
                    // already consumed a type token — this `{` starts the function body
                    return j;
                }
                // object type
                dBrace++;
                seen = true;
                j++;
                continue;
            }
            if (c == '}') {
                dBrace = Math.max(0, dBrace - 1);
                j++;
                if (dBrace == 0 && dParen == 0 && dAngle == 0 && dBrack == 0) {
                    int k = nextNonWs(s, j);
                    if (k < s.length && (s[k] == '&' || s[k] == '|' || s[k] == '[' || s[k] == '.')) {
                        j = k;
                        continue;
                    }
                    return j;
                }
                continue;
            }
            if ((c == ';' || c == ',' || c == '=' || c == '{' || c == ')')
                    && dParen == 0 && dAngle == 0 && dBrack == 0 && dBrace == 0 && seen) {
                return j;
            }
            if (!Character.isWhitespace(c)) seen = true;
            j++;
        }
        return j;
    }

    private static int matchBraceArr(char[] s, int open) {
        if (open < 0 || open >= s.length || s[open] != '{') return -1;
        int d = 0;
        for (int i = open; i < s.length; i++) {
            if (s[i] == '{') d++;
            else if (s[i] == '}') {
                d--;
                if (d == 0) return i;
            }
        }
        return -1;
    }

    private static int leadingWs(String s) {
        int i = 0;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }

    private static String safeSlice(char[] raw, int start, int end) {
        start = Math.max(0, Math.min(start, raw.length));
        end = Math.max(start, Math.min(end, raw.length));
        return new String(raw, start, end - start);
    }

    /** The last quoted literal in a statement -- the module specifier in every `... from "m"`. */
    private static String lastStringLit(String x) {
        for (int i = x.length() - 1; i >= 0; i--) {
            char c = x.charAt(i);
            if (c != '"' && c != '\'' && c != '`') continue;
            int open = x.lastIndexOf(c, i - 1);
            if (open < 0) return null;
            return x.substring(open + 1, i);
        }
        return null;
    }

    private static String collapseWs(String s) {
        return s.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static String stripExportPrefix(String t, Decl d) {
        String s = t;
        while (true) {
            if (s.startsWith("export ")) {
                d.isExport = true;
                s = s.substring(7).trim();
                if (s.startsWith("default ")) {
                    d.isDefaultExport = true;
                    s = s.substring(8).trim();
                }
                continue;
            }
            if (s.startsWith("declare ")) { s = s.substring(8).trim(); continue; }
            if (s.startsWith("async ")) {
                d.modifiers.add("async");
                s = s.substring(6).trim();
                continue;
            }
            break;
        }
        return s;
    }

    private static void bindParams(Scope sc, Decl d) {
        for (String p : d.params) {
            String[] kv = splitTsParam(p);
            if (kv == null) continue;
            if (kv[0] != null && !"any".equals(kv[0]) && !"unknown".equals(kv[0])) {
                sc.vars.put(kv[1], baseType(kv[0]));
            }
        }
    }

    /**
     * Returns [type, name, accessModifierOrNull].
     */
    private static String[] splitTsParam(String p) {
        String s = p.trim();
        if (s.isEmpty()) return null;
        // strip leading modifiers for parameter properties
        String access = null;
        for (String m : new String[]{"private", "public", "protected", "readonly"}) {
            if (s.startsWith(m + " ") || s.startsWith(m + "\t")) {
                if (!"readonly".equals(m)) access = m;
                s = s.substring(m.length()).trim();
            }
        }
        if (s.startsWith("...")) s = s.substring(3).trim();
        // name: type = default
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
        // drop optional ?
        if (name.endsWith("?")) name = name.substring(0, name.length() - 1).trim();
        name = firstIdent(name);
        if (name.isEmpty()) return null;
        return new String[]{type, name, access};
    }

    private static boolean isFunctionExpr(String rhs) {
        String r = rhs.trim();
        if (r.startsWith("async ")) r = r.substring(6).trim();
        if (r.startsWith("function")) return true;
        // arrow: (...) =>  /  (...): T =>  /  id =>
        int arrow = r.indexOf("=>");
        if (arrow < 0) return false;
        String before = r.substring(0, arrow).trim();
        if (before.isEmpty()) return false;
        if (before.startsWith("(")) return true;
        if (Lex.isIdentStart(before.charAt(0)) && before.indexOf(' ') < 0 && before.indexOf(':') < 0) return true;
        // bare name with optional type is rare for arrow; accept ends with )
        return before.endsWith(")") || before.endsWith(">");
    }

    private static List<String> extractParamsFromFn(String rhs) {
        List<String> out = new ArrayList<>();
        String r = rhs.trim();
        if (r.startsWith("async ")) r = r.substring(6).trim();
        if (r.startsWith("function")) {
            int lp = r.indexOf('(');
            if (lp < 0) return out;
            int rp = matching(r, lp);
            if (rp < 0) return out;
            for (String p : splitTop(r.substring(lp + 1, rp), ',')) {
                if (!p.isBlank()) out.add(p.trim());
            }
            return out;
        }
        int arrow = r.indexOf("=>");
        if (arrow < 0) return out;
        String before = r.substring(0, arrow).trim();
        // strip return type on arrow params: (s: string): string =>
        // before is params side
        if (before.startsWith("(")) {
            int rp = matching(before, 0);
            if (rp > 0) {
                for (String p : splitTop(before.substring(1, rp), ',')) {
                    if (!p.isBlank()) out.add(p.trim());
                }
            }
        } else {
            String id = firstIdent(before);
            if (!id.isEmpty()) out.add(id);
        }
        return out;
    }

    private static boolean precededByNew(char[] s, int nameStart) {
        int i = nameStart;
        while (i > 0 && Character.isWhitespace(s[i - 1])) i--;
        return i >= 3 && new String(s, i - 3, 3).equals("new")
                && (i - 4 < 0 || !Lex.isIdentPart(s[i - 4]));
    }

    private static String lookupVar(Scope s, String receiver) {
        String head = receiver.contains(".") ? receiver.substring(0, receiver.indexOf('.')) : receiver;
        if ("this".equals(head)) {
            for (Scope p = s; p != null; p = p.parent) {
                if (p.vars.containsKey("this")) return p.vars.get("this");
                if (p.node != null && ("class".equals(p.type))) return p.qname;
            }
            return null;
        }
        for (Scope p = s; p != null; p = p.parent) {
            String t = p.vars.get(head);
            if (t != null) return t;
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
                if (s.node != null || "file".equals(s.type)) return s;
            }
            return null;
        }
    }

    private static Scope innermost(List<Scope> scopes, int off) {
        Scope best = null;
        for (Scope s : scopes) {
            if (s.node == null && !"file".equals(s.type)) continue;
            if (off < s.start || off >= s.end) continue;
            if (best == null || s.start >= best.start) best = s;
        }
        return best;
    }

    /** Argument count at a call site. Reads RAW so string-literal args are not blanked. */
    static int arity(char[] s, int lparen) {
        int d = 0, count = 0;
        boolean any = false;
        for (int i = lparen; i < s.length; i++) {
            char c = s[i];
            if (c == '"' || c == '\'' || c == '`') {
                if (d >= 1) any = true;
                char q = c;
                i++;
                while (i < s.length && s[i] != q) {
                    if (s[i] == '\\' && i + 1 < s.length) i += 2;
                    else i++;
                }
                continue;
            }
            if (c == '(') d++;
            else if (c == ')') {
                d--;
                if (d == 0) {
                    if (count > 0) return count + 1;
                    return any ? 1 : 0;
                }
            } else if (c == ',' && d == 1) count++;
            else if (!Character.isWhitespace(c) && d >= 1) any = true;
        }
        return -1;
    }

    private static String firstStringArg(char[] raw, int lparen) {
        int d = 0;
        for (int i = lparen; i < raw.length; i++) {
            char c = raw[i];
            if (c == '(') d++;
            else if (c == ')') { d--; if (d == 0) return null; }
            else if ((c == '"' || c == '\'' || c == '`') && d == 1) {
                char q = c;
                int j = i + 1;
                StringBuilder sb = new StringBuilder();
                while (j < raw.length && raw[j] != q) {
                    if (raw[j] == '\\' && j + 1 < raw.length) { sb.append(raw[j + 1]); j += 2; continue; }
                    sb.append(raw[j++]);
                }
                return sb.toString();
            } else if (c == ',' && d == 1) {
                return null; // first arg not a string
            }
        }
        return null;
    }

    /** Start/end offsets of the n-th argument (0-based) inside call at lparen. */
    private static int[] nthArgBounds(char[] s, int lparen, int n) {
        int d = 0, arg = 0, start = lparen + 1;
        for (int i = lparen; i < s.length; i++) {
            char c = s[i];
            if (c == '(') d++;
            else if (c == ')') {
                d--;
                if (d == 0) {
                    if (arg == n) return new int[]{start, i};
                    return null;
                }
            } else if (c == ',' && d == 1) {
                if (arg == n) return new int[]{start, i};
                arg++;
                start = i + 1;
            } else if (c == '{' || c == '[') {
                // depth via matching would be better; approximate with d using same counter by scanning
                int m = matching(new String(s, i, s.length - i), 0);
                if (m > 0) i += m;
            }
        }
        return null;
    }

    private static boolean isSimpleIdent(String s) {
        if (s.isEmpty() || !Lex.isIdentStart(s.charAt(0))) return false;
        for (int i = 1; i < s.length(); i++) if (!Lex.isIdentPart(s.charAt(i))) return false;
        return true;
    }

    private static String findNodeIdByName(FileFacts f, String name) {
        for (GraphModel.Node n : f.nodes) {
            if (name.equals(n.name) && (n.qname.equals(name) || n.qname.endsWith("." + name))) {
                return n.id;
            }
        }
        // also check exports already bound
        if (f.exports.containsKey(name)) {
            String id = f.exports.get(name);
            if (id != null && !id.isEmpty()) return id;
        }
        return null;
    }

    private static String parseExportName(String p) {
        String[] ab = parseAs(p);
        return ab[1] != null ? ab[1] : ab[0];
    }

    private static String[] parseAs(String p) {
        String s = p.trim();
        if (s.startsWith("type ")) s = s.substring(5).trim();
        int as = findWord(s, "as");
        if (as < 0) return new String[]{firstIdent(s), null};
        String left = s.substring(0, as).trim();
        String right = s.substring(as + 2).trim();
        return new String[]{firstIdent(left), firstIdent(right)};
    }

    private static String stringLit(String s, int from) {
        if (s == null) return null;
        for (int i = Math.max(0, from); i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\'' || c == '`') {
                char q = c;
                int j = i + 1;
                StringBuilder sb = new StringBuilder();
                while (j < s.length() && s.charAt(j) != q) {
                    if (s.charAt(j) == '\\' && j + 1 < s.length()) { sb.append(s.charAt(j + 1)); j += 2; continue; }
                    sb.append(s.charAt(j++));
                }
                return sb.toString();
            }
        }
        return null;
    }

    private static String unquote(String s) {
        s = s.trim();
        if (s.length() >= 2) {
            char q = s.charAt(0);
            if ((q == '"' || q == '\'' || q == '`') && s.charAt(s.length() - 1) == q) {
                return s.substring(1, s.length() - 1);
            }
        }
        return s;
    }

    static String visibility(Set<String> mods) {
        if (mods.contains("private")) return "private";
        if (mods.contains("protected")) return "protected";
        if (mods.contains("public")) return "public";
        return "public";
    }

    static String baseType(String t) {
        if (t == null) return null;
        String s = stripGenerics(t).trim();
        // union / intersection: take first type token
        int pipe = indexOfTop(s, '|');
        if (pipe >= 0) s = s.substring(0, pipe).trim();
        int amp = indexOfTop(s, '&');
        if (amp >= 0) s = s.substring(0, amp).trim();
        // drop array []
        while (s.endsWith("[]")) s = s.substring(0, s.length() - 2).trim();
        // Promise already stripped generics -> empty? stripGenerics replaces with spaces
        s = s.trim();
        if (s.isEmpty()) return null;
        // last identifier-ish token
        String[] parts = s.split("\\s+");
        String last = parts[parts.length - 1];
        // qualified Type.Name -> keep simple or full? use simple last segment if dotted
        if (last.contains(".")) last = last.substring(last.lastIndexOf('.') + 1);
        // strip trailing non-ident
        int i = 0;
        while (i < last.length() && Lex.isIdentPart(last.charAt(i))) i++;
        if (i == 0) return null;
        return last.substring(0, i);
    }

    static String firstIdent(String s) {
        int i = 0;
        while (i < s.length() && !Lex.isIdentStart(s.charAt(i))) {
            if (!Character.isWhitespace(s.charAt(i))) return "";
            i++;
        }
        int j = i;
        while (j < s.length() && Lex.isIdentPart(s.charAt(j))) j++;
        return s.substring(i, j);
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

    static String stripGenerics(String s) {
        StringBuilder sb = new StringBuilder();
        int d = 0;
        for (char c : s.toCharArray()) {
            if (c == '<') d++;
            else if (c == '>') { d = Math.max(0, d - 1); sb.append(' '); }
            else if (d == 0) sb.append(c);
        }
        return sb.toString();
    }

    static String stripLeadingGenerics(String s) {
        if (!s.startsWith("<")) return s;
        int d = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<') d++;
            else if (c == '>') {
                d--;
                if (d == 0) return s.substring(i + 1);
            }
        }
        return s;
    }

    static int matching(String s, int open) {
        if (open < 0 || open >= s.length()) return -1;
        char o = s.charAt(open);
        char c = o == '(' ? ')' : o == '[' ? ']' : o == '{' ? '}' : o == '<' ? '>' : 0;
        if (c == 0) return -1;
        int d = 0;
        for (int i = open; i < s.length(); i++) {
            if (s.charAt(i) == o) d++;
            else if (s.charAt(i) == c) { d--; if (d == 0) return i; }
        }
        return -1;
    }

    static int indexOfTop(String s, char target) {
        int dParen = 0, dBrack = 0, dBrace = 0, dAngle = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            // test before mutating depth so target '(' / '[' / '{' / '<' can be found
            if (c == target && dParen == 0 && dBrack == 0 && dBrace == 0 && dAngle == 0) return i;
            if (c == '(') dParen++;
            else if (c == ')') dParen = Math.max(0, dParen - 1);
            else if (c == '[') dBrack++;
            else if (c == ']') dBrack = Math.max(0, dBrack - 1);
            else if (c == '{') dBrace++;
            else if (c == '}') dBrace = Math.max(0, dBrace - 1);
            else if (c == '<') dAngle++;
            else if (c == '>') dAngle = Math.max(0, dAngle - 1);
        }
        return -1;
    }

    static List<String> splitTop(String s, char sep) {
        List<String> out = new ArrayList<>();
        int dParen = 0, dBrack = 0, dBrace = 0, dAngle = 0;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') dParen++;
            else if (c == ')') dParen = Math.max(0, dParen - 1);
            else if (c == '[') dBrack++;
            else if (c == ']') dBrack = Math.max(0, dBrack - 1);
            else if (c == '{') dBrace++;
            else if (c == '}') dBrace = Math.max(0, dBrace - 1);
            else if (c == '<') dAngle++;
            else if (c == '>') dAngle = Math.max(0, dAngle - 1);
            else if (c == sep && dParen == 0 && dBrack == 0 && dBrace == 0 && dAngle == 0) {
                out.add(s.substring(start, i));
                start = i + 1;
            }
        }
        out.add(s.substring(start));
        return out;
    }
}
