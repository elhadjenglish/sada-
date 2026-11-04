"""Fails the build if the generated keywords are not valid for the model (same rules as KeywordFile.kt)."""
import sys

def parse(line):
    p = line.split()
    if not p:
        return None
    toks = []
    for q in p:
        if q[0] in ":#@":
            break
        toks.append(q)
    score = next((float(q[1:]) for q in p if q.startswith(":")), None)
    thr = next((float(q[1:]) for q in p if q.startswith("#")), None)
    at = next((i for i, q in enumerate(p) if q.startswith("@")), -1)
    label = " ".join(p[at:])[1:] if at >= 0 else None
    return toks, score, thr, label

keywords_file, tokens_file = sys.argv[1], sys.argv[2]
required = sys.argv[3].split(",")
vocab = {l.split()[0] for l in open(tokens_file, encoding="utf-8") if l.strip()}
issues, labels = [], set()
for n, line in enumerate(open(keywords_file, encoding="utf-8"), 1):
    e = parse(line)
    if not e:
        continue
    toks, score, thr, label = e
    if not toks:
        issues.append(f"line {n}: no tokens")
    unknown = [t for t in toks if t not in vocab]
    if unknown:
        issues.append(f"line {n}: tokens not in the model vocabulary: {' '.join(unknown)}")
    if any(c in t for t in toks for c in ":#@"):
        issues.append(f"line {n}: options were turned into tokens (bad conversion)")
    if label is None:
        issues.append(f"line {n}: no @label")
    else:
        labels.add(label)
    if thr is not None and not (0 < thr < 1):
        issues.append(f"line {n}: threshold {thr} outside 0..1")
    print(f"line {n}: {label}: tokens [{' '.join(toks)}] boost {score} threshold {thr}")
for r in required:
    if not any(l.startswith(r) for l in labels):
        issues.append(f"required keyword {r} is missing")
if issues:
    for i in issues:
        print(f"::error::{i}")
    sys.exit(1)
print(f"OK: {len(labels)} keywords, every token is in the model's vocabulary of {len(vocab)} tokens")
