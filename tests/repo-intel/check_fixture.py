#!/usr/bin/env python3
"""Score a built graph against an independently written ground-truth file.

Matching is deliberately tolerant about NAMING and strict about SEMANTICS. The ground truth
was written by a different author than the extractor, so it spells qualified names in whatever
way is natural for the language; what must not be tolerated is a relationship that is absent,
or a trap relationship that is present.
"""
import json, sys, os
from collections import defaultdict

def load(p):
    with open(p, encoding='utf-8') as f:
        return json.load(f)

TYPE_ALIAS = {
    'function': {'function', 'method', 'constructor'},
    'method':   {'method', 'function', 'constructor'},
    'constructor': {'constructor', 'method', 'function'},
    'class':    {'class', 'type', 'record'},
    'type':     {'type', 'class', 'interface', 'record', 'enum'},
    'interface':{'interface', 'type', 'class'},
    'record':   {'record', 'class', 'type'},
    'enum':     {'enum', 'type', 'class'},
    'field':    {'field', 'constant', 'property'},
    'constant': {'constant', 'field'},
    'config':   {'config', 'file'},
    'module':   {'module', 'file'},
    'file':     {'file'},
    'test':     {'test', 'class', 'function', 'method'},
    'endpoint': {'endpoint'},
    'variable': {'field', 'constant'},
    'property': {'field', 'constant', 'property'},
}

class G:
    def __init__(self, graph):
        self.nodes = {n['id']: n for n in graph['nodes']}
        self.by_q = defaultdict(list)
        self.by_name = defaultdict(list)
        self.by_file = defaultdict(list)
        for n in graph['nodes']:
            q = n.get('qualified_name') or n.get('name') or ''
            self.by_q[q].append(n)
            self.by_name[n.get('name', '')].append(n)
            if n.get('file'):
                self.by_file[n['file']].append(n)
        self.edges = graph['edges']
        self.out = defaultdict(list)
        for e in self.edges:
            self.out[e['source']].append(e)

    def resolve(self, ref, file_hint=None):
        """A ground-truth symbol reference -> set of plausible node ids."""
        if ref is None:
            return set()
        ref = ref.strip()
        if ref.endswith('.*'):
            ref = ref[:-2]
        hits = set()
        # explicit pkg reference, e.g. npm:express
        for eco in ('maven', 'npm', 'pypi', 'go'):
            if ref.startswith(eco + ':'):
                pid = 'pkg:' + ref
                if pid in self.nodes:
                    return {pid}
                nm = ref.split(':', 1)[1]
                return {i for i, n in self.nodes.items()
                        if n['type'] == 'external_package' and n.get('name') == nm}
        f, sym = (ref.split(':', 1) if ':' in ref else (None, ref))
        if f and (f in self.by_file or f.endswith(('.ts', '.js', '.tsx', '.sh', '.py', '.java'))):
            file_hint = f
        else:
            sym = ref
        # When the reference names a file, that file is authoritative: `a/helper.ts:format`
        # must never match `b/helper.ts:format`, or every same-name trap would score as a hit.
        if file_hint:
            scoped = {n['id'] for n in self.by_file.get(file_hint, [])
                      if (n.get('qualified_name') or n.get('name')) == sym
                      or n.get('name') == sym
                      or (n.get('qualified_name') or '').endswith('.' + sym)
                      or (n.get('qualified_name') or '').endswith(':' + sym)}
            if scoped:
                return scoped
            if f:
                return set()
        for n in self.by_q.get(sym, []):
            hits.add(n['id'])
        if not hits and file_hint:
            for n in self.by_file.get(file_hint, []):
                q = n.get('qualified_name') or n.get('name')
                if q == sym or n.get('name') == sym or (q and q.endswith('.' + sym)):
                    hits.add(n['id'])
        if not hits:
            tail = sym.split('.')[-1]
            for n in self.by_q:
                if n.endswith('.' + sym) or n == sym:
                    for x in self.by_q[n]:
                        hits.add(x['id'])
            if not hits:
                for n in self.by_name.get(tail, []):
                    q = n.get('qualified_name') or ''
                    if sym == tail or q.endswith(sym) or sym.endswith(q):
                        hits.add(n['id'])
        # file references
        if not hits and ('/' in ref or ref.endswith(('.py', '.ts', '.js', '.java', '.sh'))):
            fid = 'file:' + ref
            if fid in self.nodes:
                hits.add(fid)
        return hits

    def has_edge(self, srcs, rel, tgts, confidences=None):
        for s in srcs:
            for e in self.out.get(s, []):
                if e['relation'] != rel:
                    continue
                if e['target'] in tgts:
                    if confidences is None or e['confidence'] in confidences:
                        return e
        return None

def score(graph_path, gt_path):
    g = G(load(graph_path))
    gt = load(gt_path)
    out = {'fixture': gt.get('fixture'), 'language': gt.get('language')}

    # declarations
    dec_hit, dec_miss = 0, []
    for d in gt.get('declarations', []):
        want = d.get('type', '')
        allowed = TYPE_ALIAS.get(want, {want})
        found = False
        for nid in g.resolve(d.get('qualified_name'), d.get('file')):
            n = g.nodes[nid]
            if n['type'] in allowed or want in ('test',):
                found = True
                break
        if found:
            dec_hit += 1
        else:
            dec_miss.append(f"{d.get('type')} {d.get('qualified_name')} ({d.get('file')})")
    out['declarations'] = {'total': len(gt.get('declarations', [])), 'found': dec_hit,
                           'missing': dec_miss[:12]}

    # relations
    rel_hit, rel_miss = 0, []
    xfile_total, xfile_hit = 0, 0
    for r in gt.get('relations', []):
        rel = r.get('relation')
        src_ref = r.get('from') or r.get('from_file')
        srcs = g.resolve(src_ref, r.get('from_file'))
        if r.get('from_file') and not r.get('from'):
            srcs |= {'file:' + r['from_file']}
        tgts = g.resolve(r.get('to'), r.get('to_file'))
        cross = bool(r.get('cross_file'))
        if cross:
            xfile_total += 1
        e = g.has_edge(srcs, rel, tgts)
        if e:
            rel_hit += 1
            if cross:
                xfile_hit += 1
        else:
            rel_miss.append(f"{rel} {src_ref} -> {r.get('to')}")
    out['relations'] = {'total': len(gt.get('relations', [])), 'found': rel_hit,
                        'missing': rel_miss[:15]}
    out['cross_file'] = {'total': xfile_total, 'found': xfile_hit}

    # negatives: a trap edge emitted as EXTRACTED or INFERRED is a false positive
    fp, fp_list, amb = 0, [], 0
    for n in gt.get('negatives', []):
        srcs = g.resolve(n.get('from') or n.get('from_file'), n.get('from_file'))
        if n.get('from_file') and not n.get('from'):
            srcs |= {'file:' + n['from_file']}
        tgts = g.resolve(n.get('to'), n.get('to_file'))
        e = g.has_edge(srcs, n.get('relation'), tgts, {'EXTRACTED', 'INFERRED'})
        if e:
            fp += 1
            fp_list.append(f"{n.get('relation')} {n.get('from') or n.get('from_file')} -> {n.get('to')}")
        elif g.has_edge(srcs, n.get('relation'), tgts, {'AMBIGUOUS'}):
            amb += 1
    out['negatives'] = {'total': len(gt.get('negatives', [])), 'false_positives': fp,
                        'as_ambiguous': amb, 'detail': fp_list[:10]}

    conf = defaultdict(int)
    for e in g.edges:
        conf[e['confidence']] += 1
    confident = conf['EXTRACTED'] + conf['INFERRED']
    out['graph'] = {'nodes': len(g.nodes), 'edges': len(g.edges), 'confident_edges': confident,
                    'ambiguous_edges': conf['AMBIGUOUS']}
    out['rates'] = {
        'declaration_recall': round(dec_hit / max(1, len(gt.get('declarations', []))), 4),
        'relation_recall': round(rel_hit / max(1, len(gt.get('relations', []))), 4),
        'cross_file_recall': round(xfile_hit / max(1, xfile_total), 4) if xfile_total else None,
        'false_positive_rate': round(fp / max(1, confident), 4),
    }
    return out

if __name__ == '__main__':
    print(json.dumps(score(sys.argv[1], sys.argv[2]), indent=2))
