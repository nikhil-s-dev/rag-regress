# rag-regress

Created and maintained by Nikhil Sunku.

Regression tests for RAG retrieval quality in Java. Score any retriever (a vector store, Elasticsearch or OpenSearch, a search API, Spring AI or LangChain4j) against a labelled golden set, and fail the build when recall, MRR or nDCG drop.

![Java 21+](https://img.shields.io/badge/java-21%2B-blue)
![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-green)

Retrieval is the part of a RAG system that breaks quietly. A new embedding model, a different chunk size, a reindex that drops a field or a changed HNSW setting can cut recall in half while the assistant keeps answering fluently, just with the wrong sources. `rag-regress` turns retrieval quality into a test: a golden set of real questions labelled with the documents that answer them, standard ranking metrics, and a comparison against the last known-good run that fails only when a drop is larger than the noise.

- Works as a library in JUnit tests and as a CLI in any pipeline
- Hit rate, recall, precision, MRR, nDCG (with graded relevance) and MAP at any k
- Baseline comparison paired by query, with bootstrap confidence intervals, so small golden sets do not produce flaky failures
- Names the queries that broke and the documents that fell out of the top k
- Breakdown by tag (billing, security, ...), retrieval latency and error rate
- Retrievers: any Java lambda, HTTP search services, precomputed result files, a built-in BM25 baseline
- Logs runs to MLflow through its REST API
- No runtime dependencies beyond Java 21 (verify using the Maven build)

> **Validation:** GitHub Actions builds and tests this project on Java 21 and 25 and runs the example retrieval quality gate. See the [CI runs](https://github.com/nikhilsunku/rag-regress/actions/workflows/ci.yml) for results. Sample outputs remain illustrative.

## Use it in JUnit tests

Requires Java 21 or later.

After publishing a release tag and confirming that JitPack successfully builds it, add the dependency through JitPack:

```xml
<repositories>
  <repository>
    <id>jitpack.io</id>
    <url>https://jitpack.io</url>
  </repository>
</repositories>

<dependency>
  <groupId>com.github.nikhilsunku</groupId>
  <artifactId>rag-regress</artifactId>
  <version>v0.1.1</version>
  <scope>test</scope>
</dependency>
```

Wrap your retriever in a lambda and assert on the report. With Spring AI (imports omitted):

```java
@SpringBootTest
class RetrievalQualityTest {

    @Autowired
    VectorStore vectorStore;

    @Test
    void retrievalDoesNotRegress() throws IOException {
        Retriever retriever = (query, k) -> vectorStore
                .similaritySearch(SearchRequest.builder().query(query).topK(k).build())
                .stream()
                .map(doc -> (String) doc.getMetadata().get("source_id"))
                .toList();

        EvalReport report = RagEval.builder(retriever)
                .ks(1, 5, 10)
                .build()
                .evaluate(GoldenSet.load(Path.of("src/test/resources/golden.jsonl")));

        RagAssertions.assertThat(report)
                .hasNoErrors()
                .metric("recall@5").isAtLeast(0.80);

        RagAssertions.assertThat(report)
                .comparedTo(EvalReport.load(Path.of("src/test/resources/retrieval-baseline.json")))
                .hasNoSignificantRegression("recall@5", "ndcg@10");
    }
}
```

With LangChain4j the lambda searches the embedding store instead:

```java
Retriever retriever = (query, k) -> embeddingStore
        .search(EmbeddingSearchRequest.builder()
                .queryEmbedding(embeddingModel.embed(query).content())
                .maxResults(k)
                .build())
        .matches().stream()
        .map(match -> match.embedded().metadata().getString("doc_id"))
        .toList();
```

When a check fails, the message says what broke. Illustrative failure output (values are examples, not independently verified benchmark results):

```text
retrieval quality regressed against the baseline (32 matched queries):
  recall@5 0.938 -> 0.516 (-0.422, 95% CI [-0.563, -0.281])
  ndcg@10 0.946 -> 0.505 (-0.441, 95% CI [-0.583, -0.305])
Largest drops in ndcg@10:
  q04 1.000 -> 0.000  "Let a contractor deploy services without seeing billing"  lost: doc-roles
  q05 1.000 -> 0.000  "Do you support hardware security keys?"  lost: doc-mfa
  q09 1.000 -> 0.000  "Is inbound traffic charged?"  lost: doc-pricing
  q19 1.000 -> 0.000  "Is there an official Java library?"  lost: doc-sdks
  q20 1.000 -> 0.000  "Install the command-line tool on a Mac"  lost: doc-cli
```

To create the baseline, save a report from a run you trust (`report.save(Path.of(...))`, or the CLI's `--json-out`) and commit it. Refresh it deliberately, in its own pull request, when a change is supposed to move the numbers. [ExampleRetrievalQualityTest](src/test/java/io/github/nikhilucumberlands/ragregress/ExampleRetrievalQualityTest.java) runs this exact pattern in this repository's build.

## Use it from the command line

The repository ships a small example: 34 help-center documents for a fictional cloud product and 32 labelled questions.

```bash
git clone https://github.com/nikhilsunku/rag-regress.git
cd rag-regress
./mvnw -q package                      # builds target/rag-regress.jar

java -jar target/rag-regress.jar run \
  --golden examples/golden.jsonl \
  --retriever bm25:examples/corpus.jsonl \
  --k 1,5,10 \
  --threshold "recall@5>=0.8" \
  --json-out baseline.json
```

```text
rag-regress 0.1.1
golden set  examples/golden.jsonl (32 queries)
retriever   bm25:examples/corpus.jsonl (fields title,text)

metric            @1      @5     @10
hit_rate       0.969   1.000   1.000
recall         0.828   0.938   0.938
precision      0.969   0.238   0.119
mrr            0.969   0.984   0.984
ndcg           0.953   0.946   0.946
map            0.969   0.914   0.914

latency     p50 0.2 ms, p95 2.4 ms, max 14.2 ms
errors      0 of 32 queries

by tag (ndcg@10)
  billing        0.960  (6 queries)
  data           0.891  (4 queries)
  integrations   1.000  (7 queries)
  ops            0.993  (7 queries)
  security       0.875  (8 queries)

thresholds
  PASS  recall@5>=0.8            0.938

result      PASS
```

Now simulate a refactor that stops indexing document bodies, and compare with the baseline:

```bash
java -jar target/rag-regress.jar run \
  --golden examples/golden.jsonl \
  --retriever bm25:examples/corpus.jsonl --bm25-fields title \
  --k 1,5,10 \
  --threshold "recall@5>=0.8" \
  --baseline baseline.json --gate recall@5,ndcg@10
```

```text
thresholds
  FAIL  recall@5>=0.8            0.516

compared with baseline (32 matched queries; tolerance 0.010; 95% bootstrap CI, 2000 resamples)
  metric          baseline   current     delta   95% CI               status
  recall@5           0.938     0.516    -0.422   [-0.563, -0.281]     REGRESSED
  ndcg@10            0.946     0.505    -0.441   [-0.583, -0.305]     REGRESSED
  16 other metrics not gated: 16 regressed, 0 improved, 0 unchanged (all in the JSON report)

largest drops in ndcg@10
  q04      1.000 -> 0.000  Let a contractor deploy services without seeing billing
           lost from top 10: doc-roles
  q05      1.000 -> 0.000  Do you support hardware security keys?
           lost from top 10: doc-mfa
  q09      1.000 -> 0.000  Is inbound traffic charged?
           lost from top 10: doc-pricing
  q19      1.000 -> 0.000  Is there an official Java library?
           lost from top 10: doc-sdks
  q20      1.000 -> 0.000  Install the command-line tool on a Mac
           lost from top 10: doc-cli

result      FAIL: 1 of 1 thresholds failed; 2 metrics regressed significantly (recall@5, ndcg@10)
```

The exit status is 1, so the pipeline stops. The by-tag table (omitted above) shows `security` falling from 0.875 to 0.298, which tells you where to look first.

## Golden set format

One JSON object per line:

```json
{"id": "q01", "query": "How do I rotate an API key without downtime?", "relevant": ["doc-api-keys"]}
{"id": "q13", "query": "Recover a table someone dropped an hour ago", "relevant": {"doc-restore": 2, "doc-backups": 1}, "tags": ["data"]}
```

- `relevant` is a list of document ids, or an object of id to grade. Grades above 0 count as relevant; higher grades matter for nDCG (2 for the document that answers the question, 1 for one that helps).
- `tags` are optional and give per-slice metrics.
- 30 to 200 real questions, taken from search logs, support tickets or user interviews, are enough to catch most regressions.
- Label documents (or sections), not chunks. Chunk ids change every time chunking changes; return the parent document id from your retriever instead. Duplicate ids are dropped keeping the first occurrence, so several chunks from one document count once, at the rank of the best one.

## Retrievers on the command line

| `--retriever` | What it does |
|:--|:--|
| `bm25:corpus.jsonl` | In-memory BM25 over a JSONL corpus with `id` and text fields (`--bm25-fields title,text`). A lexical baseline to compare a vector retriever against. |
| `https://search.internal/query` | POSTs a JSON body to your search service. `--http-body '{"q": "{query}", "top_k": "{k}"}'` sets the template (`"{k}"` becomes a number), `--id-path 'hits[*].doc.id'` says where the ids are, `--header` adds auth. |
| `run:results.jsonl` | Replays precomputed results, one `{"id": "q01", "retrieved": ["doc-3", ...]}` per line. Handy for notebooks and batch jobs. Missing queries count as errors. |
| `class:com.acme.MyRetriever` | Loads a `Retriever` implementation from the classpath: `java -cp rag-regress.jar:my.jar io.github.nikhilucumberlands.ragregress.Main run ...` |

## How a regression is decided

Golden sets are small, so a metric moving by a few points can be one or two queries changing. A metric counts as regressed only when both hold:

1. its mean dropped by more than `--tolerance` (default 0.01, one point), and
2. the paired bootstrap interval of the drop lies entirely below zero.

The bootstrap resamples queries with replacement 2,000 times (`--resamples`) and recomputes the mean per-query difference each time; all metrics share the same resamples. With 40 queries, one query flipping from hit to miss is a -0.025 change in recall whose interval straddles zero, so it does not fail the build. Twelve queries breaking is a -0.3 change with an interval well below zero, and it does. Use `--gate` to choose which metrics can fail the check (the rest are still reported) and `--no-bootstrap` to fail on any drop beyond the tolerance.

Queries are paired by `id`. Queries present in only one report are listed in the JSON report and left out of the comparison, and a query whose retrieval throws scores 0 and counts toward `error_rate`, so an outage shows up as a regression rather than as a smaller, better-looking golden set.

## Thresholds and exit codes

`--threshold` sets an absolute floor or ceiling: `recall@5>=0.8`, `ndcg@10>=75%`, `error_rate<=1%`, `latency_p95<300ms`. Latency thresholds need a unit.

| Exit code | Meaning |
|:--|:--|
| 0 | all thresholds held and no gated metric regressed |
| 1 | a threshold failed, a gated metric regressed, or every query failed |
| 2 | invalid arguments or unreadable input |

## In CI

This repository's [CI workflow](.github/workflows/ci.yml) evaluates the example corpus on every push and compares it with the committed [examples/baseline.json](examples/baseline.json). When `GITHUB_STEP_SUMMARY` is set, a Markdown report is added to the job summary. [examples/github-actions/retrieval-gate.yml](examples/github-actions/retrieval-gate.yml) shows the same gate for a RAG service repository, calling the service over HTTP.

`rag-regress compare baseline.json current.json` compares two saved reports without running retrieval again, for pipelines where evaluation happens in a separate job.

## MLflow

```bash
export MLFLOW_TRACKING_URI=http://mlflow.internal:5000
java -jar target/rag-regress.jar run \
  --golden eval/golden.jsonl --retriever https://search.internal/query \
  --baseline eval/baseline.json \
  --mlflow-experiment retrieval-quality --mlflow-run-name "bge-m3, 512-token chunks"
```

Each run logs every metric (`recall@5` becomes `recall_at_5`, since MLflow keys cannot contain `@`), the deltas against the baseline, latency and error rate, the retriever and golden set as params, and a `rag_regress.passed` tag. Auth uses `MLFLOW_TRACKING_TOKEN` or `MLFLOW_TRACKING_USERNAME`/`MLFLOW_TRACKING_PASSWORD`, like the official clients. A logging failure prints a warning and does not change the exit status.

## Metrics

| Metric | Definition |
|:--|:--|
| `hit_rate@k` | 1 if any relevant document is in the top k |
| `recall@k` | share of the relevant documents found in the top k |
| `precision@k` | relevant documents in the top k, divided by k |
| `mrr@k` | 1 / rank of the first relevant document (0 if none in the top k) |
| `ndcg@k` | DCG with linear gain (the grade) and a log2 discount, normalised by the ideal order, as trec_eval computes it |
| `map@k` | average precision over relevant documents found, divided by min(relevant count, k) |

Every metric is computed per query and averaged. `error_rate`, `latency_p50`, `latency_p95` and `latency_max` are available for thresholds too.

## Design notes

- **Library first.** The CLI is a thin layer over `RagEval`, `Comparison` and `RagAssertions`, so the checks in a unit test and in a pipeline are the same code.
- **Reports are baselines.** The JSON report keeps per-query results, which is what makes paired comparison and the "lost documents" view possible. Scores are compared at the six decimals reports are saved with, so a baseline loaded from disk and an identical run compare as exactly equal.
- **Virtual threads.** Queries run concurrently (`--concurrency`, default 4) on virtual threads, which suits retrievers that call the network.
- **No dependencies.** JSON, the JSONPath subset, BM25 and the MLflow client are small in-tree classes, so adding the library to a test classpath cannot cause version conflicts.

## Building and testing

```bash
./mvnw verify
```

The suite covers the metric math against hand-computed values, the bootstrap, JSON round trips, the HTTP and run-file retrievers, the MLflow REST calls against a fake server, and the CLI end to end on the example corpus.

## Roadmap

- Answer-level checks (faithfulness and answer relevance with an LLM judge) next to the retrieval metrics
- Recall versus latency sweeps for ANN index settings such as `ef_search` and `nprobe`
- A Maven plugin goal so the gate runs without writing a test

## Contributing

Issues and pull requests are welcome; see [CONTRIBUTING.md](CONTRIBUTING.md).

## License

[Apache License 2.0](LICENSE)

## Scope and limitations

This project is a focused regression-testing tool, not a replacement for production monitoring or a guarantee of application quality. Review the implementation and tests before adopting it in a deployment gate. Measurements and outcomes depend on the input data, workload, environment, and configuration.

For a reproducible report, record the commit SHA, Java version, command, test dataset or workload, and the generated output. Do not treat the example numbers in this README as independent performance claims.

## Contributing and security

See [CONTRIBUTING.md](CONTRIBUTING.md) for development steps and [SECURITY.md](SECURITY.md) for vulnerability reporting. Changes to metrics or regression thresholds should include a focused test demonstrating the intended behavior.
