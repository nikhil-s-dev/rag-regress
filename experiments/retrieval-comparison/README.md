# Retrieval comparison on a small support corpus

This experiment compares two lexical retrievers on the example data in this repository. It asks a narrow question: when the same 32 labelled questions are run against the same 34 fictional support documents, how do TF-IDF and BM25 rank the labelled documents?

## Run it

Python 3.9+ is enough; there are no packages to install.

```bash
python3 experiments/retrieval-comparison/test_compare.py
python3 experiments/retrieval-comparison/compare.py
```

The script overwrites `experiments/retrieval-comparison/results.json`. It records the top ten document IDs and per-query metrics as well as the means. The checked-in result was produced with Python 3.12.14 using the corpus and labels at commit `0159e2eac41690f5be6e95bfe145a4c80af691a9`.

## Results

| Metric | TF-IDF | BM25 |
| --- | ---: | ---: |
| Mean recall@1 | 0.8281 | 0.8281 |
| Mean recall@5 | 0.9375 | 0.9375 |
| Mean reciprocal rank@10 | 0.9844 | 0.9766 |
| Mean graded nDCG@10 | 0.9627 | 0.9579 |

Both methods retrieve at least one labelled document in the top five for every question; for four questions they miss the second labelled document at that depth. On the double-charge question (`q11`), TF-IDF ranks the refunds document second and BM25 ranks it fourth. Neither finds the related invoices document in its top five. These are observations about this dataset, not evidence that TF-IDF is generally better than BM25.

## Setup

- Each document is its title plus body. Tokenization lowercases and extracts ASCII letters and digits; there is no stemming, stop-word removal, or chunking.
- TF-IDF uses `1 + ln(tf)`, `1 + ln((N + 1)/(df + 1))`, and cosine similarity. BM25 uses `k1=1.2`, `b=0.75`, and `ln(1 + (N - df + 0.5)/(df + 0.5))`. Repeated query terms do not increase BM25 scores. Both return only positive-score matches; ties use document ID order.
- Recall is the fraction of labelled relevant documents in the top k. Reciprocal rank is the inverse rank of the first relevant document. Graded nDCG uses gain `2^grade - 1` and logarithmic discount; metrics are averaged equally across questions.
- The experiment code is a separate Python implementation, not a run of the Java CLI. Its tokenization, scoring and nDCG conventions may differ from the Java example baseline; do not compare their numbers as a controlled change.

The corpus is synthetic and small, and the labels have not been independently reviewed here. Some question and document wording overlaps. This comparison has no dense embeddings, hybrid search, latency benchmark, or generated-answer assessment. It cannot establish quality on private documents or a production workload. A next experiment should use a larger independently labelled question set, include hard negatives and paraphrases, and evaluate a dense retriever on the same frozen split.

## Extended comparison

The extended run adds latent semantic analysis (LSA) and reciprocal rank fusion (RRF). LSA is a low-rank representation fitted on this corpus; it is **not a pretrained embedding model**. RRF combines BM25 and LSA rankings with `k=60`. Every method uses the same documents, labels, and query set.

```bash
python3 -m pip install -r experiments/retrieval-comparison/requirements.txt
OPENBLAS_NUM_THREADS=1 OMP_NUM_THREADS=1 python3 -m unittest discover -s experiments/retrieval-comparison -p 'test_*.py'
OPENBLAS_NUM_THREADS=1 OMP_NUM_THREADS=1 python3 experiments/retrieval-comparison/extended.py --repeat 10
```

| Method | Recall@5 | MRR@10 | Graded nDCG@10 |
| --- | ---: | ---: | ---: |
| TF-IDF | 0.9375 | 0.9844 | 0.9627 |
| BM25 | 0.9375 | 0.9766 | 0.9579 |
| LSA | 0.9688 | 0.9531 | 0.9531 |
| BM25 + LSA RRF | 0.9531 | 0.9844 | 0.9717 |

LSA improves recall@5 on this sample but has lower MRR@10 than either lexical method. The differences are based on just 32 questions; they should not be generalized. For the uptime and service-credit question (`q25`), BM25's top five missed the labelled refunds document, whereas LSA and fusion included it. For the account-removal question (`q02`), LSA found both labelled documents in its top five; fusion did not. Inspect all rankings in [extended-results.json](extended-results.json).

LSA uses scikit-learn word TF-IDF with sublinear term frequency and 16-component truncated SVD (`random_state=42`, 10 iterations); cosine similarity ranks every document, including documents with a non-positive similarity. Empty-vocabulary queries return no results. The lexical algorithms and metrics are the same as the first experiment. Fusion combines full rankings and uses document ID to break ties. No query labels are used to fit LSA, but the entire document corpus is used to construct it.

[latency.json](latency.json) records an illustrative local run on an Intel Xeon Platinum 8573C CPU with Python 3.12.14, NumPy 2.3.5 and scikit-learn 1.8.0. Each method ran one warmup pass followed by 10 passes over 32 questions (320 samples); p50 and p95 use the nearest-rank method. Timing includes in-process query transformation, ranking, and fusion as applicable; it excludes index construction, model loading, network, concurrency, and evaluation. Latency is sensitive to CPU scheduling and library versions, and the corpus is tiny. These numbers are not server or production SLOs.

### Bring a public benchmark

The script can read a locally unpacked BEIR-format dataset without changing the index to only include labelled documents:

```bash
OPENBLAS_NUM_THREADS=1 OMP_NUM_THREADS=1 python3 experiments/retrieval-comparison/extended.py \
  --beir-dir /path/to/scifact --out-dir /tmp/scifact-results --repeat 1
```

The directory must contain `corpus.jsonl`, `queries.jsonl`, and `qrels/test.tsv`. It retains the full corpus and evaluates positively labelled test queries. See the [BEIR dataset list](https://github.com/beir-cellar/beir/wiki/Datasets-available) for download instructions and check each dataset's terms. This adapter is covered by a small fixture test; a full BEIR dataset was **not run** for this report. The simple lexical implementation scans every document, so a large dataset can take considerable time. Results on a subset are not comparable with full-corpus benchmark results.

### Optional pretrained embeddings

The script also accepts `--dense-model MODEL_ID_OR_LOCAL_PATH` and adds dense cosine and BM25+dense fusion. It needs the separately installed `sentence-transformers` package and model files. Record the package version and immutable model revision when running it; model download and dependencies are not included in `requirements.txt`. This path has **not been executed in the current environment**, so no neural-retrieval scores are reported here. Pretrained model download and indexing are excluded from query latency.
