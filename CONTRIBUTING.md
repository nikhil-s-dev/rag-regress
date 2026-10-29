# Contributing

Thanks for taking the time to improve rag-regress.

## Development setup

You need JDK 21 or newer. The Maven wrapper downloads Maven on first use.

```bash
./mvnw verify
java -jar target/rag-regress.jar run --golden examples/golden.jsonl --retriever bm25:examples/corpus.jsonl
```

## Guidelines

- Keep the library free of runtime dependencies. Test-only dependencies are fine.
- Metric changes need a test with values worked out by hand, like `RankingMetricsTest`.
- If you change the JSON report, keep older reports loadable: people commit them as baselines.
- If the example data changes, regenerate `examples/baseline.json` in the same pull request:
  `java -jar target/rag-regress.jar run --golden examples/golden.jsonl --retriever bm25:examples/corpus.jsonl --k 1,3,5,10 --json-out examples/baseline.json`
- Keep pull requests focused; one change per pull request is easiest to review.
