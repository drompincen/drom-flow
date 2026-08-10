import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Package-manifest pass-1 extractor.
 *
 * Each supported file yields one {@code config} node plus a {@link DepRef} per declared
 * dependency. External package nodes and DEPENDS_ON edges are left to the resolver so the
 * same library named in two manifests collapses to one node.
 *
 * Parsing is text-only: no XML entity resolution, no Gradle evaluation, no npm install.
 */
final class ManifestExtractor implements Extractor {

    private static final Set<String> GRADLE_CONFIGS = Set.of(
            "implementation", "testImplementation", "api", "compileOnly", "runtimeOnly",
            "annotationProcessor", "testCompileOnly", "testRuntimeOnly", "compile", "testCompile",
            "runtime", "testRuntime", "providedCompile", "providedRuntime", "classpath");

    public String language() { return "config"; }

    public boolean supports(String relPath) {
        String name = fileName(relPath);
        if (name.equals("pom.xml")
                || name.equals("build.gradle")
                || name.equals("build.gradle.kts")
                || name.equals("package.json")
                || name.equals("pyproject.toml")
                || name.equals("requirements.txt")
                || name.equals("go.mod")) {
            return true;
        }
        return name.startsWith("requirements-") && name.endsWith(".txt");
    }

    public FileFacts extract(String path, String src) {
        FileFacts f = new FileFacts(path, "config");
        if (src == null) {
            f.error = "null source";
            return f;
        }
        try {
            String name = fileName(path);
            GraphModel.Node cfg = new GraphModel.Node(
                    GraphModel.symbolId("config", "config", path, path, -1),
                    "config", name, path, "config", path);
            cfg.startLine = 1;
            cfg.endLine = 1;
            f.add(cfg);
            f.defines(GraphModel.fileId(path), cfg.id, 1);

            if (name.equals("pom.xml")) extractPom(f, path, src);
            else if (name.equals("build.gradle") || name.equals("build.gradle.kts")) extractGradle(f, path, src);
            else if (name.equals("package.json")) extractPackageJson(f, path, src);
            else if (name.equals("pyproject.toml")) extractPyproject(f, path, src);
            else if (name.equals("requirements.txt") || (name.startsWith("requirements-") && name.endsWith(".txt")))
                extractRequirements(f, path, src);
            else if (name.equals("go.mod")) extractGoMod(f, path, src);
        } catch (RuntimeException e) {
            f.error = "extract failed: " + e;
        }
        return f;
    }

    // ---------- Maven pom.xml ----------

    private void extractPom(FileFacts f, String path, String src) {
        char[] mask;
        try {
            mask = Lex.maskCLike(src, false, false);
        } catch (RuntimeException e) {
            f.error = "mask failed: " + e;
            return;
        }
        blankXmlComments(mask);
        int[] li = Lex.lineIndex(mask);
        char[] raw = src.toCharArray();

        Map<String, String> props = new LinkedHashMap<>();
        int propOpen = indexOfTag(mask, "properties", 0, true);
        if (propOpen >= 0) {
            int propBody = skipTag(mask, propOpen);
            int propClose = indexOfTag(mask, "properties", propBody, false);
            if (propClose > propBody) {
                int i = propBody;
                while (i < propClose) {
                    Tag t = readOpenTag(mask, i);
                    if (t == null) { i++; continue; }
                    if (t.close || t.selfClose) { i = t.end; continue; }
                    int bodyStart = t.end;
                    int close = indexOfTag(mask, t.name, bodyStart, false);
                    if (close < 0 || close > propClose) break;
                    String val = textBetween(raw, bodyStart, close).trim();
                    props.put(t.name, val);
                    i = skipTag(mask, close);
                }
            }
        }

        int depMgmt = 0;
        int i = 0;
        while (i < mask.length) {
            if (mask[i] != '<') { i++; continue; }
            Tag t = readOpenTag(mask, i);
            if (t == null) { i++; continue; }
            i = t.end;
            if ("dependencyManagement".equals(t.name)) {
                if (t.close) depMgmt = Math.max(0, depMgmt - 1);
                else if (!t.selfClose) depMgmt++;
                continue;
            }
            if (!"dependency".equals(t.name) || t.close || t.selfClose) continue;
            if (depMgmt > 0) {
                // still consume the block so nesting stays correct; skip emit
                int close = indexOfTag(mask, "dependency", t.end, false);
                if (close >= 0) i = skipTag(mask, close);
                continue;
            }
            int close = indexOfTag(mask, "dependency", t.end, false);
            if (close < 0) break;
            String body = textBetween(raw, t.end, close);
            int line = Lex.lineOf(li, t.start);
            String groupId = childText(body, "groupId");
            String artifactId = childText(body, "artifactId");
            String version = childText(body, "version");
            String scope = childText(body, "scope");
            if (groupId == null || artifactId == null) {
                i = skipTag(mask, close);
                continue;
            }
            version = resolveProp(version, props);
            if (scope == null || scope.isBlank()) scope = "compile";
            f.deps.add(new DepRef("maven", groupId + ":" + artifactId, version, scope.trim(), false, line));
            i = skipTag(mask, close);
        }
    }

    private static String resolveProp(String version, Map<String, String> props) {
        if (version == null) return null;
        String v = version.trim();
        if (v.startsWith("${") && v.endsWith("}") && v.length() > 3) {
            String key = v.substring(2, v.length() - 1).trim();
            String resolved = props.get(key);
            if (resolved != null) return resolved;
        }
        return v;
    }

    private static String childText(String body, String tag) {
        String open = "<" + tag;
        int from = 0;
        while (true) {
            int i = indexOfIgnoreCase(body, open, from);
            if (i < 0) return null;
            int after = i + open.length();
            if (after < body.length() && (body.charAt(after) == '>' || Character.isWhitespace(body.charAt(after))
                    || body.charAt(after) == '/')) {
                int gt = body.indexOf('>', after);
                if (gt < 0) return null;
                if (body.charAt(gt - 1) == '/') return "";
                int close = indexOfIgnoreCase(body, "</" + tag + ">", gt + 1);
                if (close < 0) return null;
                return body.substring(gt + 1, close).trim();
            }
            from = after;
        }
    }

    // ---------- Gradle (Groovy + Kotlin DSL) ----------

    private void extractGradle(FileFacts f, String path, String src) {
        char[] mask;
        try {
            mask = Lex.maskCLike(src, false, false);
        } catch (RuntimeException e) {
            f.error = "mask failed: " + e;
            return;
        }
        char[] raw = src.toCharArray();
        int[] li = Lex.lineIndex(mask);
        int n = mask.length;
        int i = 0;
        while (i < n) {
            if (!Lex.isIdentStart(mask[i])) { i++; continue; }
            int start = i;
            while (i < n && Lex.isIdentPart(mask[i])) i++;
            String conf = new String(mask, start, i - start);
            if (!GRADLE_CONFIGS.contains(conf)) continue;
            // Skip horizontal/vertical whitespace on RAW, not mask: mask blanks string
            // bodies to spaces, so walking mask whitespace would jump past the coordinate.
            int j = i;
            while (j < n && Character.isWhitespace(raw[j])) j++;
            if (j >= n) continue;
            char next = raw[j];
            if (next != '(' && next != '"' && next != '\''
                    && !looksLikeMapForm(raw, j)) {
                continue; // bare configuration name, not a dependency declaration
            }

            String scope = conf.regionMatches(true, 0, "test", 0, 4) ? "test" : "compile";
            int line = Lex.lineOf(li, start);

            // map form: implementation group: "g", name: "a", version: "v"
            if (looksLikeMapForm(raw, j)) {
                String group = mapValue(raw, j, "group");
                String art = mapValue(raw, j, "name");
                String ver = mapValue(raw, j, "version");
                if (group != null && art != null) {
                    f.deps.add(new DepRef("maven", group + ":" + art, ver, scope, false, line));
                }
                continue;
            }

            // string form: conf "g:a:v"  or conf("g:a:v")  or conf 'g:a:v'
            String coord = firstStringLiteral(raw, j);
            if (coord == null) continue;
            addMavenCoord(f, coord, scope, line);
        }
    }

    private static boolean looksLikeMapForm(char[] raw, int from) {
        int j = from;
        if (j < raw.length && raw[j] == '(') {
            j++;
            while (j < raw.length && Character.isWhitespace(raw[j])) j++;
        }
        return matchWord(raw, j, "group") || matchWord(raw, j, "name") || matchWord(raw, j, "version");
    }

    private static boolean matchWord(char[] s, int i, String w) {
        if (i + w.length() > s.length) return false;
        for (int k = 0; k < w.length(); k++) if (s[i + k] != w.charAt(k)) return false;
        int e = i + w.length();
        return e >= s.length || !Lex.isIdentPart(s[e]);
    }

    private static String mapValue(char[] raw, int from, String key) {
        int n = raw.length;
        int i = from;
        int limit = Math.min(n, from + 400);
        while (i < limit) {
            if (matchWord(raw, i, key)) {
                int j = i + key.length();
                while (j < n && Character.isWhitespace(raw[j])) j++;
                if (j < n && raw[j] == ':') {
                    j++;
                    while (j < n && Character.isWhitespace(raw[j])) j++;
                    return readQuoted(raw, j);
                }
            }
            i++;
        }
        return null;
    }

    private static String firstStringLiteral(char[] raw, int from) {
        int n = raw.length;
        int i = from;
        // optional opening paren for Kotlin/Groovy call form
        while (i < n && Character.isWhitespace(raw[i])) i++;
        if (i < n && raw[i] == '(') {
            i++;
            while (i < n && Character.isWhitespace(raw[i])) i++;
        }
        return readQuoted(raw, i);
    }

    private static String readQuoted(char[] raw, int i) {
        if (i >= raw.length) return null;
        char q = raw[i];
        if (q != '"' && q != '\'') return null;
        i++;
        StringBuilder sb = new StringBuilder();
        while (i < raw.length && raw[i] != q) {
            if (raw[i] == '\\' && i + 1 < raw.length) { sb.append(raw[i + 1]); i += 2; continue; }
            if (raw[i] == '\n') break;
            sb.append(raw[i++]);
        }
        return sb.toString();
    }

    private static void addMavenCoord(FileFacts f, String coord, String scope, int line) {
        String c = coord.trim();
        if (c.isEmpty()) return;
        // GAV: group:artifact:version — take first two segments as name
        int c1 = c.indexOf(':');
        if (c1 <= 0) return;
        int c2 = c.indexOf(':', c1 + 1);
        String name;
        String version = null;
        if (c2 < 0) {
            name = c;
        } else {
            name = c.substring(0, c2);
            int c3 = c.indexOf(':', c2 + 1);
            version = c3 < 0 ? c.substring(c2 + 1) : c.substring(c2 + 1, c3);
            if (version.isEmpty()) version = null;
        }
        if (name.isEmpty()) return;
        f.deps.add(new DepRef("maven", name, version, scope, false, line));
    }

    // ---------- package.json ----------

    private void extractPackageJson(FileFacts f, String path, String src) {
        Object rootObj;
        try {
            rootObj = Json.parse(src);
        } catch (RuntimeException e) {
            f.error = "json parse failed: " + e;
            return;
        }
        Map<String, Object> root = Json.obj(rootObj);
        collectNpmDeps(f, src, Json.obj(root.get("dependencies")), "compile");
        collectNpmDeps(f, src, Json.obj(root.get("devDependencies")), "dev");
        collectNpmDeps(f, src, Json.obj(root.get("peerDependencies")), "optional");
    }

    private static void collectNpmDeps(FileFacts f, String src, Map<String, Object> map, String scope) {
        if (map == null || map.isEmpty()) return;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            String name = e.getKey();
            if (name == null || name.isEmpty()) continue;
            String version = e.getValue() == null ? null : String.valueOf(e.getValue());
            int line = lineOfJsonKey(src, name);
            f.deps.add(new DepRef("npm", name, version, scope, false, line));
        }
    }

    /** 1-based line of the JSON object key `"name"`, or 1 if not found. */
    private static int lineOfJsonKey(String src, String key) {
        String needle = "\"" + key + "\"";
        int from = 0;
        while (true) {
            int i = src.indexOf(needle, from);
            if (i < 0) return 1;
            int j = i + needle.length();
            while (j < src.length() && Character.isWhitespace(src.charAt(j))) j++;
            if (j < src.length() && src.charAt(j) == ':') {
                int line = 1;
                for (int k = 0; k < i; k++) if (src.charAt(k) == '\n') line++;
                return line;
            }
            from = i + 1;
        }
    }

    // ---------- pyproject.toml ----------

    private void extractPyproject(FileFacts f, String path, String src) {
        char[] mask;
        try {
            mask = Lex.maskPython(src);
        } catch (RuntimeException e) {
            f.error = "mask failed: " + e;
            return;
        }
        List<String> mLines = Lex.lines(mask);
        List<String> rLines = Lex.lines(src.toCharArray());

        String section = "";
        for (int ln = 1; ln < mLines.size(); ln++) {
            String ml = mLines.get(ln).trim();
            String rl = ln < rLines.size() ? rLines.get(ln) : "";
            if (ml.startsWith("[") && ml.endsWith("]")) {
                section = ml.substring(1, ml.length() - 1).trim();
                continue;
            }
            if (section.equals("project") && startsWithKey(ml, "dependencies")) {
                for (TomlItem it : readTomlStringArray(rLines, mLines, ln)) {
                    String pkg = pypiName(it.spec);
                    if (pkg.isEmpty()) continue;
                    f.deps.add(new DepRef("pypi", pkg, pypiVersion(it.spec), "compile", false, it.line));
                }
            } else if (section.equals("project.optional-dependencies")
                    || section.startsWith("project.optional-dependencies.")) {
                if (ml.contains("=")) {
                    for (TomlItem it : readTomlStringArray(rLines, mLines, ln)) {
                        String pkg = pypiName(it.spec);
                        if (pkg.isEmpty()) continue;
                        f.deps.add(new DepRef("pypi", pkg, pypiVersion(it.spec), "optional", false, it.line));
                    }
                }
            } else if (section.equals("tool.poetry.dependencies")
                    || section.equals("tool.poetry.group.dev.dependencies")
                    || (section.startsWith("tool.poetry.group.") && section.endsWith(".dependencies"))) {
                if (ml.isEmpty()) continue;
                int eq = ml.indexOf('=');
                if (eq <= 0) continue;
                String key = ml.substring(0, eq).trim();
                if (key.equals("python")) continue; // interpreter constraint, not a package
                String scope = section.contains(".group.") ? "dev" : "compile";
                String rhs = rl.trim();
                int req = rhs.indexOf('=');
                String ver = null;
                if (req >= 0) ver = unquote(rhs.substring(req + 1).trim());
                f.deps.add(new DepRef("pypi", key, ver, scope, false, ln));
            }
        }
    }

    private static boolean startsWithKey(String line, String key) {
        if (!line.startsWith(key)) return false;
        int i = key.length();
        while (i < line.length() && Character.isWhitespace(line.charAt(i))) i++;
        return i < line.length() && line.charAt(i) == '=';
    }

    private static final class TomlItem {
        final String spec;
        final int line;
        TomlItem(String spec, int line) { this.spec = spec; this.line = line; }
    }

    /**
     * Read a TOML string array starting at {@code startLine} (1-based). String bodies are
     * taken from RAW lines; array brackets that only appear inside a masked comment are
     * ignored because the scan walks the masked line for structure and the raw line for
     * quoted content at matching offsets.
     */
    private static List<TomlItem> readTomlStringArray(List<String> rawLines, List<String> maskLines, int startLine) {
        List<TomlItem> out = new ArrayList<>();
        boolean inArray = false;
        for (int ln = startLine; ln < rawLines.size(); ln++) {
            String raw = rawLines.get(ln);
            String mask = ln < maskLines.size() ? maskLines.get(ln) : raw;
            int n = Math.max(raw.length(), mask.length());
            for (int i = 0; i < n; i++) {
                char mc = i < mask.length() ? mask.charAt(i) : ' ';
                char rc = i < raw.length() ? raw.charAt(i) : ' ';
                if (!inArray) {
                    if (mc == '[') inArray = true;
                    continue;
                }
                if (mc == ']') return out;
                // quoted element: quote char survives masking as space, so detect from raw
                // only when the mask still has non-comment content around this column
                if ((rc == '"' || rc == '\'') && (mc == ' ' || mc == rc)) {
                    // skip if this column is inside a fully blanked comment run with no string
                    // (maskPython blanks string bodies too — opening quote becomes space)
                    char q = rc;
                    StringBuilder sb = new StringBuilder();
                    int j = i + 1;
                    while (j < raw.length() && raw.charAt(j) != q) {
                        if (raw.charAt(j) == '\\' && j + 1 < raw.length()) {
                            sb.append(raw.charAt(j + 1));
                            j += 2;
                            continue;
                        }
                        sb.append(raw.charAt(j++));
                    }
                    out.add(new TomlItem(sb.toString(), ln));
                    i = j; // for-loop will +1
                }
            }
        }
        return out;
    }

    private static String pypiName(String spec) {
        if (spec == null) return "";
        String s = spec.trim();
        if (s.isEmpty()) return "";
        int semi = s.indexOf(';');
        if (semi >= 0) s = s.substring(0, semi).trim();
        // name runs until version op, extra bracket, or whitespace
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '[' || c == '(' || c == ' ' || c == '\t') break;
            if (c == '<' || c == '>' || c == '=' || c == '!' || c == '~') break;
            if (c == ',') break;
            i++;
        }
        return s.substring(0, i).trim();
    }

    private static String pypiVersion(String spec) {
        if (spec == null) return null;
        String s = spec.trim();
        int semi = s.indexOf(';');
        if (semi >= 0) s = s.substring(0, semi).trim();
        int bracket = s.indexOf('[');
        if (bracket >= 0) {
            int close = s.indexOf(']', bracket);
            if (close >= 0) s = (s.substring(0, bracket) + s.substring(close + 1)).trim();
        }
        // find first version operator
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<' || c == '>' || c == '=' || c == '!' || c == '~') {
                return s.substring(i).trim();
            }
        }
        return null;
    }

    private static String unquote(String v) {
        if (v == null) return null;
        String s = v.trim();
        if (s.length() >= 2) {
            char a = s.charAt(0), b = s.charAt(s.length() - 1);
            if ((a == '"' && b == '"') || (a == '\'' && b == '\'')) return s.substring(1, s.length() - 1);
        }
        return s;
    }

    // ---------- requirements.txt ----------

    private void extractRequirements(FileFacts f, String path, String src) {
        char[] mask;
        try {
            mask = Lex.maskPython(src);
        } catch (RuntimeException e) {
            f.error = "mask failed: " + e;
            return;
        }
        List<String> mLines = Lex.lines(mask);
        List<String> rLines = Lex.lines(src.toCharArray());
        for (int ln = 1; ln < mLines.size(); ln++) {
            String ml = mLines.get(ln).trim();
            if (ml.isEmpty()) continue; // blank or fully commented
            String rl = ln < rLines.size() ? rLines.get(ln).trim() : ml;
            if (rl.isEmpty() || rl.startsWith("#")) continue;
            if (rl.startsWith("-r") || rl.startsWith("--requirement")) continue;
            if (rl.startsWith("-") && !rl.startsWith("-e")) {
                // other pip options / includes: not package deps
                if (rl.startsWith("--")) continue;
            }
            // editable installs: -e path/url — skip as non-package name when no egg fragment
            if (rl.startsWith("-e ") || rl.startsWith("--editable")) continue;
            String pkg = pypiName(rl);
            if (pkg.isEmpty() || pkg.startsWith("-")) continue;
            f.deps.add(new DepRef("pypi", pkg, pypiVersion(rl), "compile", false, ln));
        }
    }

    // ---------- go.mod ----------

    private void extractGoMod(FileFacts f, String path, String src) {
        char[] mask;
        try {
            mask = Lex.maskCLike(src, false, false);
        } catch (RuntimeException e) {
            f.error = "mask failed: " + e;
            return;
        }
        List<String> mLines = Lex.lines(mask);
        List<String> rLines = Lex.lines(src.toCharArray());
        boolean inRequire = false;

        for (int ln = 1; ln < mLines.size(); ln++) {
            String ml = mLines.get(ln).trim();
            String rl = ln < rLines.size() ? rLines.get(ln).trim() : "";

            if (!inRequire) {
                if (ml.startsWith("module ") || (ml.equals("module") && rl.startsWith("module "))) {
                    String mod = rl.substring("module".length()).trim();
                    if (!mod.isEmpty()) {
                        f.namespace = mod;
                        String simple = mod;
                        int slash = mod.lastIndexOf('/');
                        if (slash >= 0 && slash + 1 < mod.length()) simple = mod.substring(slash + 1);
                        GraphModel.Node node = new GraphModel.Node(
                                GraphModel.symbolId("config", "module", path, mod, -1),
                                "module", simple, mod, "config", path);
                        node.startLine = ln;
                        node.endLine = ln;
                        f.add(node);
                        f.defines(GraphModel.fileId(path), node.id, ln);
                    }
                    continue;
                }
                if (ml.startsWith("require")) {
                    String rest = ml.substring("require".length()).trim();
                    String rawRest = rl.length() > "require".length() ? rl.substring("require".length()).trim() : rest;
                    if (rest.equals("(") || rawRest.startsWith("(")) {
                        inRequire = true;
                        continue;
                    }
                    // single-line: require path v1.2.3
                    parseGoRequireLine(f, rawRest.isEmpty() ? rest : rawRest, ln);
                }
                continue;
            }

            if (ml.equals(")") || rl.equals(")")) {
                inRequire = false;
                continue;
            }
            if (ml.isEmpty() && (rl.isEmpty() || rl.startsWith("//"))) continue;
            parseGoRequireLine(f, rl, ln);
        }
    }

    private static void parseGoRequireLine(FileFacts f, String line, int ln) {
        String s = line.trim();
        if (s.isEmpty() || s.startsWith("//")) return;
        boolean indirect = s.contains("//") && s.substring(s.indexOf("//") + 2).trim().startsWith("indirect");
        int comment = s.indexOf("//");
        if (comment >= 0) s = s.substring(0, comment).trim();
        if (s.isEmpty()) return;
        // path version
        int sp = -1;
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) { sp = i; break; }
        }
        String name;
        String version = null;
        if (sp < 0) {
            name = s;
        } else {
            name = s.substring(0, sp).trim();
            version = s.substring(sp).trim();
            if (version.isEmpty()) version = null;
        }
        if (name.isEmpty()) return;
        f.deps.add(new DepRef("go", name, version, "compile", indirect, ln));
    }

    // ---------- shared helpers ----------

    private static String fileName(String path) {
        if (path == null) return "";
        int s = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return s < 0 ? path : path.substring(s + 1);
    }

    private static void blankXmlComments(char[] a) {
        int i = 0;
        while (i < a.length) {
            if (a[i] == '<' && i + 3 < a.length && a[i + 1] == '!' && a[i + 2] == '-' && a[i + 3] == '-') {
                a[i++] = ' '; a[i++] = ' '; a[i++] = ' '; a[i++] = ' ';
                while (i < a.length) {
                    if (a[i] == '-' && i + 2 < a.length && a[i + 1] == '-' && a[i + 2] == '>') {
                        a[i++] = ' '; a[i++] = ' '; a[i++] = ' ';
                        break;
                    }
                    if (a[i] != '\n' && a[i] != '\r') a[i] = ' ';
                    i++;
                }
            } else {
                i++;
            }
        }
    }

    private static final class Tag {
        String name;
        int start, end;
        boolean close, selfClose;
    }

    private static Tag readOpenTag(char[] s, int i) {
        if (i >= s.length || s[i] != '<') return null;
        Tag t = new Tag();
        t.start = i;
        i++;
        if (i < s.length && s[i] == '/') { t.close = true; i++; }
        while (i < s.length && Character.isWhitespace(s[i])) i++;
        int ns = i;
        while (i < s.length && (Character.isLetterOrDigit(s[i]) || s[i] == ':' || s[i] == '_' || s[i] == '-' || s[i] == '.')) i++;
        if (i == ns) return null;
        t.name = new String(s, ns, i - ns);
        // strip namespace prefix
        int col = t.name.indexOf(':');
        if (col >= 0) t.name = t.name.substring(col + 1);
        while (i < s.length && s[i] != '>') {
            if (s[i] == '"' || s[i] == '\'') {
                char q = s[i++];
                while (i < s.length && s[i] != q) i++;
                if (i < s.length) i++;
            } else {
                i++;
            }
        }
        if (i < s.length && s[i] == '>') {
            if (i > 0 && s[i - 1] == '/') t.selfClose = true;
            i++;
        }
        t.end = i;
        return t;
    }

    private static int indexOfTag(char[] s, String name, int from, boolean open) {
        String needle = open ? "<" + name : "</" + name;
        int n = s.length;
        for (int i = from; i < n; i++) {
            if (s[i] != '<') continue;
            if (matchesTagAt(s, i, name, open)) return i;
        }
        return -1;
    }

    private static boolean matchesTagAt(char[] s, int i, String name, boolean open) {
        if (s[i] != '<') return false;
        int j = i + 1;
        if (!open) {
            if (j >= s.length || s[j] != '/') return false;
            j++;
        }
        while (j < s.length && Character.isWhitespace(s[j])) j++;
        // optional namespace
        int nameStart = j;
        while (j < s.length && (Character.isLetterOrDigit(s[j]) || s[j] == ':' || s[j] == '_' || s[j] == '-' || s[j] == '.')) j++;
        String raw = new String(s, nameStart, j - nameStart);
        int col = raw.indexOf(':');
        String local = col >= 0 ? raw.substring(col + 1) : raw;
        if (!local.equals(name)) return false;
        if (j < s.length && (s[j] == '>' || Character.isWhitespace(s[j]) || s[j] == '/')) return true;
        return false;
    }

    private static int skipTag(char[] s, int tagStart) {
        int i = tagStart;
        while (i < s.length && s[i] != '>') i++;
        return i < s.length ? i + 1 : i;
    }

    private static String textBetween(char[] raw, int start, int end) {
        if (start < 0) start = 0;
        if (end > raw.length) end = raw.length;
        if (start >= end) return "";
        return new String(raw, start, end - start);
    }

    private static int indexOfIgnoreCase(String s, String needle, int from) {
        return s.toLowerCase(Locale.ROOT).indexOf(needle.toLowerCase(Locale.ROOT), from);
    }
}
