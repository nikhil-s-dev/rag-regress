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
