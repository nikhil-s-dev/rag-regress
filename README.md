# rag-regress

**Retrieval regression tests for Java RAG applications.** Evaluate a retriever against a labelled set of questions, compare the result with a saved baseline, and fail a build when retrieval quality falls.

Java 21+ · CLI and JUnit API · Apache-2.0  
Maintainer: **Nikhil S**

## The problem

A search index can change without breaking an API. A different embedding model, chunking strategy, or indexing configuration might return less useful documents while the application still responds normally. Unit tests that only check whether an endpoint returns 200 won't catch that.

`rag-regress` runs known questions through the retriever and measures whether the expected documents still appear near the top.

## Try it

Requires JDK 21 or newer. The repository includes a fictional cloud-service corpus (34 documents), 32 labelled questions, and a checked-in BM25 baseline. No vector database or API credentials are needed for this example.

```bash
git clone https://github.com/nikhil-s-dev/rag-regress.git
cd rag-regress
./mvnw verify

java -jar target/rag-regress.jar run \
  --golden examples/golden.jsonl \
  --retriever bm25:examples/corpus.jsonl \
  --k 1,3,5,10 \
  --threshold "recall@5>=0.85" \
  --baseline examples/baseline.json \
  --gate recall@5,ndcg@10 \
  --json-out target/retrieval-report.json
```

An exit code of 0 means the configured checks passed; 1 means a quality gate failed; 2 means invalid inputs or arguments.

The committed [example baseline](examples/baseline.json) records **recall@5 = 0.9375** and **nDCG@10 ≈ 0.9461** on that small synthetic BM25 dataset. These are demo scores, **not** evidence of retrieval quality on a production RAG system. Regenerate them after deliberately changing the example corpus or its labels.

## Connect a real retriever

The evaluation core takes a Java `Retriever` implementation, so an application can supply results from a vector store, OpenSearch, a search API, or its own retrieval pipeline. Return *document IDs* in rank order; stable document IDs make comparisons more useful than chunk IDs that can change after re-indexing.

For working JUnit usage, see [ExampleRetrievalQualityTest](src/test/java/io/github/nikhilucumberlands/ragregress/ExampleRetrievalQualityTest.java). It checks the normal index, then simulates a broken index by searching titles without document bodies and asserts that the recall regression is detected.

From the CLI, `--retriever` supports:

| Input | Use |
| --- | --- |
| `bm25:examples/corpus.jsonl` | Local lexical baseline |
| `run:results.jsonl` | Replay saved ranked results |
| `https://your-search-service/query` | Query a search endpoint over HTTP |
| `class:your.package.Retriever` | Load a Java retriever implementation |

Run `java -jar target/rag-regress.jar run --help` for request templates, authentication, filters, and other flags.

## Checks and design decisions

- **Ranking metrics:** hit rate, recall, precision, MRR, nDCG (including graded relevance), and MAP at configurable `k`.
- **Paired comparisons:** baseline and candidate results are matched by query ID. The default regression check requires a meaningful drop *and* a paired bootstrap confidence interval below zero; `--no-bootstrap` is available for a strict tolerance-only gate.
- **Diagnostics:** reports include failed queries, missing documents, error counts, latency, and per-tag breakdowns.
- **Two integration paths:** use `RagAssertions` in JUnit or run the CLI in CI; results can be emitted as JSON or logged to MLflow.
- **Low integration overhead:** the runtime code uses the JDK rather than a third-party retrieval SDK.

The [CI workflow](.github/workflows/ci.yml) runs Maven tests and an example retrieval gate. Inspect the [Actions runs](https://github.com/nikhil-s-dev/rag-regress/actions) for current results instead of treating documentation as a CI-status claim.

## What this does not tell you

A high score on the included corpus says little about another dataset. The golden set must reflect real queries and independently labelled relevant documents. The bootstrap accounts for paired query variation; it does **not** fix biased labels or an unrepresentative sample. These checks measure **retrieval**, not whether a generated answer is correct or faithful.

For a credible experiment, publish the golden-set construction method, retriever configuration, command, commit SHA, and generated JSON report. Never compare scores from different datasets as though they were controlled runs.

## Development

```bash
./mvnw verify
```

See [CONTRIBUTING.md](CONTRIBUTING.md) for test and baseline-update guidance, [SECURITY.md](SECURITY.md) for reporting issues, and [LICENSE](LICENSE) for licensing.
