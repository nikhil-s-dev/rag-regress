#!/usr/bin/env python3
"""Reproducible lexical, latent-semantic and rank-fusion retrieval evaluation."""
import argparse
import csv
import json
import math
import platform
import time
from pathlib import Path

import numpy as np
from sklearn.decomposition import TruncatedSVD
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.preprocessing import normalize
from compare import ROOT, HERE, KS, query_metrics, rankers, read_jsonl


def load_beir(path):
    """Load a full BEIR corpus and the test questions with positive judgments."""
    docs = [{ 'id': str(d['_id']), 'title': d.get('title', ''), 'text': d['text']} for d in read_jsonl(path / 'corpus.jsonl')]
    questions = {str(q['_id']): q['text'] for q in read_jsonl(path / 'queries.jsonl')}
    labels = {}
    with (path / 'qrels' / 'test.tsv').open(encoding='utf-8', newline='') as f:
        for row in csv.DictReader(f, delimiter='\t'):
            grade = int(row['score'])
            if grade > 0:
                labels.setdefault(str(row['query-id']), {})[str(row['corpus-id'])] = grade
    ids = {d['id'] for d in docs}
    if len(ids) != len(docs):
        raise ValueError('duplicate corpus ID')
    if not labels or not labels.keys() <= questions.keys() or any(not set(v) <= ids for v in labels.values()):
        raise ValueError('qrels must reference known queries and documents')
    return docs, [{'id': k, 'query': questions[k], 'relevant': v, 'tags': []} for k, v in sorted(labels.items())]


def rrf(*rankings, constant=60):
    scores = {}
    for ranking in rankings:
        for rank, doc_id in enumerate(ranking, 1):
            scores[doc_id] = scores.get(doc_id, 0.0) + 1.0 / (constant + rank)
    return [doc_id for doc_id, _ in sorted(scores.items(), key=lambda x: (-x[1], x[0]))]


def make_lsa(documents, dimensions=16):
    vectorizer = TfidfVectorizer(token_pattern=r'(?u)\b[a-zA-Z0-9]+\b', lowercase=True, sublinear_tf=True)
    matrix = vectorizer.fit_transform([d['title']+' '+d['text'] for d in documents])
    n_components = min(dimensions, matrix.shape[0]-1, matrix.shape[1]-1)
    if n_components < 2:
        raise ValueError('corpus too small for LSA')
    svd = TruncatedSVD(n_components=n_components, random_state=42, n_iter=10)
    doc_vectors = normalize(svd.fit_transform(matrix))
    ids = [d['id'] for d in documents]
    def search(query):
        q = normalize(svd.transform(vectorizer.transform([query])))
        if not np.any(q):
            return []
        scores = (doc_vectors @ q.T).ravel()
        return sorted(ids, key=lambda doc_id: (-float(scores[id_index[doc_id]]), doc_id))
    id_index = {doc_id: i for i, doc_id in enumerate(ids)}
    return search, n_components


def make_dense(documents, name):
    try:
        from sentence_transformers import SentenceTransformer
    except ImportError as exc:
        raise SystemExit('Install sentence-transformers to use --dense-model') from exc
    model = SentenceTransformer(name)
    vectors = model.encode([d['title']+' '+d['text'] for d in documents], normalize_embeddings=True, convert_to_numpy=True, show_progress_bar=False)
    ids = [d['id'] for d in documents]
    index = {doc_id:i for i,doc_id in enumerate(ids)}
    def search(query):
        vector = model.encode([query], normalize_embeddings=True, convert_to_numpy=True, show_progress_bar=False)[0]
        scores = vectors @ vector
        return sorted(ids, key=lambda doc_id: (-float(scores[index[doc_id]]), doc_id))
    return search


def evaluate(documents, queries, repeat, dense_model=None):
    if len({d['id'] for d in documents}) != len(documents) or len({q['id'] for q in queries}) != len(queries):
        raise ValueError('duplicate IDs')
    doc_ids = {d['id'] for d in documents}
    if not queries or any(not q['relevant'] or not set(q['relevant']) <= doc_ids or any(v <= 0 for v in q['relevant'].values()) for q in queries):
        raise ValueError('positive relevance labels must reference corpus documents')
    lexical = rankers(documents)
    lsa, dimensions = make_lsa(documents)
    methods = {'tfidf': lambda q: lexical(q, 'tfidf'), 'bm25': lambda q: lexical(q, 'bm25'),
               'lsa': lsa, 'bm25_lsa_rrf': lambda q: rrf(lexical(q, 'bm25'), lsa(q))}
    if dense_model:
        dense = make_dense(documents, dense_model)
        methods['dense'] = dense
        methods['bm25_dense_rrf'] = lambda q: rrf(lexical(q, 'bm25'), dense(q))
    results = {'documents': len(documents), 'queries': len(queries), 'lsa_dimensions': dimensions, 'seed':42, 'dense_model':dense_model, 'runs': {}}
    timings = {'clock': 'perf_counter_ns', 'warmup_passes':1, 'timed_passes':repeat, 'samples_per_method':repeat*len(queries),
               'scope':'per-query in-process retrieval; excludes initialization, corpus indexing and metrics; hybrid includes both retrievers',
               'python':platform.python_version(), 'platform':platform.platform(), 'runs':{}}
    for name, search in methods.items():
        for q in queries:
            search(q['query'])
        rows=[]
        for q in queries:
            ranking = search(q['query'])
            rows.append({'id':q['id'], 'query':q['query'], 'relevant':q['relevant'], 'tags':q.get('tags',[]), 'top10':ranking[:10],
                         'metrics':{str(k):query_metrics(ranking,q['relevant'],k) for k in KS}})
        summary={f'{metric}@{k}':sum(row['metrics'][str(k)][metric] for row in rows)/len(rows) for k in KS for metric in ('recall','mrr','ndcg')}
        samples=[]
        for _ in range(repeat):
            for q in queries:
                start=time.perf_counter_ns(); search(q['query']); samples.append((time.perf_counter_ns()-start)/1e6)
        samples.sort()
        timings['runs'][name]={'p50_ms':samples[math.ceil(.50*len(samples))-1], 'p95_ms':samples[math.ceil(.95*len(samples))-1]}
        results['runs'][name]={'summary':summary,'per_query':rows}
    return results,timings


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--beir-dir', type=Path, help='Unpacked BEIR dataset with corpus.jsonl, queries.jsonl, qrels/test.tsv')
    parser.add_argument('--dense-model', help='Optional Sentence Transformers model ID or local path')
    parser.add_argument('--repeat', type=int, default=10, help='Timing passes after warmup (default 10)')
    parser.add_argument('--out-dir', type=Path, default=HERE, help='Where to write report JSON')
    args=parser.parse_args()
    if args.repeat < 1:
        parser.error('--repeat must be positive')
    if args.beir_dir:
        docs, queries=load_beir(args.beir_dir)
    else:
        docs=read_jsonl(ROOT/'examples/corpus.jsonl'); queries=read_jsonl(ROOT/'examples/golden.jsonl')
    results,timings=evaluate(docs,queries,args.repeat,args.dense_model)
    results['dataset'] = str(args.beir_dir) if args.beir_dir else 'examples/corpus.jsonl + examples/golden.jsonl (synthetic)'
    args.out_dir.mkdir(parents=True,exist_ok=True)
    (args.out_dir/'extended-results.json').write_text(json.dumps(results,ensure_ascii=False,separators=(',', ':'))+'\n',encoding='utf-8')
    (args.out_dir/'latency.json').write_text(json.dumps(timings,indent=2,ensure_ascii=False)+'\n',encoding='utf-8')
    for name, data in results['runs'].items():
        s=data['summary']; t=timings['runs'][name]
        print(f"{name:15s} recall@5={s['recall@5']:.4f} mrr@10={s['mrr@10']:.4f} ndcg@10={s['ndcg@10']:.4f} p95={t['p95_ms']:.3f}ms")

if __name__=='__main__':
    main()
