#!/usr/bin/env python3
"""Compare two lexical retrievers on the repository's labelled example corpus."""
import json
import math
import re
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
KS = (1, 3, 5, 10)


def read_jsonl(path):
    return [json.loads(line) for line in path.read_text(encoding='utf-8').splitlines() if line.strip()]


def tokenize(text):
    return re.findall(r'[a-z0-9]+', text.lower())


def rankers(documents):
    counters = {d['id']: Counter(tokenize(d['title'] + ' ' + d['text'])) for d in documents}
    if len(counters) != len(documents):
        raise ValueError('duplicate document id')
    n = len(documents)
    df = Counter(term for counts in counters.values() for term in counts)
    lengths = {doc_id: sum(counts.values()) for doc_id, counts in counters.items()}
    avg_length = sum(lengths.values()) / n
    tfidf_idf = {term: math.log((1+n)/(1+freq)) + 1 for term, freq in df.items()}
    vectors = {doc_id: {term: (1+math.log(tf))*tfidf_idf[term] for term, tf in counts.items()} for doc_id, counts in counters.items()}
    norms = {doc_id: math.sqrt(sum(v*v for v in vec.values())) for doc_id, vec in vectors.items()}

    def search(query, method):
        terms = Counter(tokenize(query))
        qvec = {t: (1+math.log(tf))*tfidf_idf[t] for t, tf in terms.items() if t in df}
        qnorm = math.sqrt(sum(v*v for v in qvec.values()))
        scores = []
        for doc_id, counts in counters.items():
            if method == 'bm25':
                score = sum(math.log(1+(n-df[t]+0.5)/(df[t]+0.5)) * counts[t] * 2.2 /
                    (counts[t]+1.2*(1-0.75+0.75*lengths[doc_id]/avg_length))
                    for t in terms if t in df and counts[t])
            else:
                score = sum(weight*vectors[doc_id].get(t, 0) for t, weight in qvec.items()) / (qnorm*norms[doc_id]) if qnorm and norms[doc_id] else 0
            if score > 0:
                scores.append((doc_id, score))
        return [doc_id for doc_id, _ in sorted(scores, key=lambda pair: (-pair[1], pair[0]))]
    return search


def query_metrics(ranking, relevant, k):
    top = ranking[:k]
    hits = [i for i, doc_id in enumerate(top) if doc_id in relevant]
    recall = len(hits) / len(relevant)
    reciprocal_rank = 1/(hits[0]+1) if hits else 0.0
    dcg = sum((2**relevant.get(doc_id, 0)-1)/math.log2(i+2) for i, doc_id in enumerate(top))
    ideal = sum((2**grade-1)/math.log2(i+2) for i, grade in enumerate(sorted(relevant.values(), reverse=True)[:k]))
    return {'recall': recall, 'mrr': reciprocal_rank, 'ndcg': dcg/ideal if ideal else 0.0}


def main():
    documents = read_jsonl(ROOT / 'examples/corpus.jsonl')
    queries = read_jsonl(ROOT / 'examples/golden.jsonl')
    doc_ids = {d['id'] for d in documents}
    if len({q['id'] for q in queries}) != len(queries):
        raise ValueError('duplicate query id')
    if any(not q['relevant'] or not set(q['relevant']).issubset(doc_ids) or any(g <= 0 for g in q['relevant'].values()) for q in queries):
        raise ValueError('labels must be positive and refer to known documents')
    search = rankers(documents)
    output = {'dataset': 'examples/corpus.jsonl + examples/golden.jsonl', 'documents': len(documents), 'queries': len(queries),
              'methodology': 'Document title and body; ASCII alphanumeric lowercase tokens; positive-score results only; ties sorted by document id; graded nDCG uses 2^grade-1.', 'runs': {}}
    for method in ('tfidf', 'bm25'):
        rows = []
        for q in queries:
            ranking = search(q['query'], method)
            rows.append({'id': q['id'], 'query': q['query'], 'relevant': q['relevant'], 'tags': q.get('tags', []),
                         'top10': ranking[:10], 'metrics': {str(k): query_metrics(ranking, q['relevant'], k) for k in KS}})
        summary = {f'{metric}@{k}': sum(row['metrics'][str(k)][metric] for row in rows)/len(rows) for k in KS for metric in ('recall', 'mrr', 'ndcg')}
        output['runs'][method] = {'summary': summary, 'per_query': rows}
    path = HERE / 'results.json'
    path.write_text(json.dumps(output, indent=2, ensure_ascii=False)+'\n', encoding='utf-8')
    for name, run in output['runs'].items():
        print(name, ' '.join(f'{key}={run["summary"][key]:.4f}' for key in ('recall@1','recall@5','mrr@10','ndcg@10')))
    print('Saved', path.relative_to(ROOT))


if __name__ == '__main__':
    main()
