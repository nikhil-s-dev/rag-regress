package io.github.nikhilucumberlands.ragregress.cli;

import io.github.nikhilucumberlands.ragregress.EvalReport;
import io.github.nikhilucumberlands.ragregress.GoldenSet;
import io.github.nikhilucumberlands.ragregress.RagEval;
import io.github.nikhilucumberlands.ragregress.compare.Comparison;
import io.github.nikhilucumberlands.ragregress.compare.MetricDelta;
import io.github.nikhilucumberlands.ragregress.gate.Threshold;
import io.github.nikhilucumberlands.ragregress.gate.Threshold.ThresholdResult;
import io.github.nikhilucumberlands.ragregress.metrics.RankingMetrics;
import io.github.nikhilucumberlands.ragregress.mlflow.MlflowClient;
import io.github.nikhilucumberlands.ragregress.report.Outcome;
import io.github.nikhilucumberlands.ragregress.report.Reports;
import io.github.nikhilucumberlands.ragregress.retrievers.RetrieverSpecs;
import io.github.nikhilucumberlands.ragregress.util.Json;
import io.github.nikhilucumberlands.ragregress.util.JsonException;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** {@code rag-regress run}: evaluate a retriever, apply thresholds and an optional baseline check. */
public final class RunCommand {

    private static final Set<String> OWN_VALUE_OPTIONS = Set.of(
            "golden", "retriever", "k", "concurrency", "bm25-fields", "http-body", "id-path", "header", "timeout",
            "threshold", "baseline", "json-out", "markdown-out", "mlflow-experiment", "mlflow-tracking-uri",
            "mlflow-run-name");
    private static final Set<String> OWN_SWITCHES = Set.of("help", "no-github-summary", "quiet");

    static final String HELP = """
            Usage: rag-regress run --golden FILE --retriever SPEC [options]

            Scores a retriever against a labelled golden set and fails when quality drops below a
            threshold or regresses significantly against a baseline report.

            Input
              --golden FILE              JSONL golden set: {"id", "query", "relevant", "tags"}
              --retriever SPEC           bm25:CORPUS.jsonl | http(s)://URL | run:RESULTS.jsonl | class:NAME
              --k LIST                   cutoffs, e.g. 1,5,10 (default: 1,5,10)
              --concurrency N            queries in flight (default: 4)

            Retriever settings
              --bm25-fields LIST         corpus fields to index for bm25: (default: title,text)
              --http-body JSON           request template; "{query}" and "{k}" are filled in
                                         (default: {"query": "{query}", "k": "{k}"})
              --id-path PATH             where the ids are in the response (default: results[*].id)
              --header 'Name: value'     extra HTTP header, repeatable
              --timeout SECONDS          per-request HTTP timeout (default: 30)

            Checks
              --threshold EXPR           e.g. 'recall@5>=0.8', 'error_rate<=1%%', 'latency_p95<300ms'
              --baseline FILE            JSON report from an earlier run to compare against
            %s
            Output
              --json-out FILE            full JSON report; reusable as a future --baseline
              --markdown-out FILE        Markdown report
              --no-github-summary        do not append to $GITHUB_STEP_SUMMARY
              --mlflow-experiment NAME   log metrics to MLflow (needs --mlflow-tracking-uri or
                                         $MLFLOW_TRACKING_URI)
              --mlflow-run-name NAME     MLflow run name
              --quiet                    no progress output

            Exit status: 0 passed, 1 a threshold failed or a gated metric regressed, 2 bad usage.
            """.formatted(ComparisonArgs.HELP.stripTrailing());

    private final PrintStream out;
    private final PrintStream err;
    private final Map<String, String> env;

    public RunCommand(PrintStream out, PrintStream err, Map<String, String> env) {
        this.out = out;
        this.err = err;
        this.env = env;
    }

    public int execute(String[] argv) throws UsageException {
        Set<String> valueOptions = new HashSet<>(OWN_VALUE_OPTIONS);
        valueOptions.addAll(ComparisonArgs.VALUE_OPTIONS);
        Set<String> switches = new HashSet<>(OWN_SWITCHES);
        switches.addAll(ComparisonArgs.SWITCHES);
        Args args = Args.parse(argv, valueOptions, switches);
        if (args.flag("help")) {
            out.print(HELP);
            return 0;
        }
        if (!args.positionals().isEmpty()) {
            throw new UsageException("unexpected argument '" + args.positionals().get(0) + "'");
        }

        int[] ks = parseKs(args.values("k"));
        List<String> known = new ArrayList<>(RankingMetrics.names(java.util.Arrays.stream(ks).boxed().toList()));
        List<Threshold> thresholds = new ArrayList<>();
        for (String expression : args.values("threshold")) {
            Threshold threshold;
            try {
                threshold = Threshold.parse(expression);
            } catch (IllegalArgumentException e) {
                throw new UsageException(e.getMessage());
            }
            if (!known.contains(threshold.metric()) && !EvalReport.OPERATIONAL_METRICS.contains(threshold.metric())) {
                throw new UsageException("unknown metric '" + threshold.metric() + "' in threshold '" + expression
                        + "'; with --k " + joinKs(ks) + " the metrics are " + String.join(", ", known) + ", "
                        + String.join(", ", EvalReport.OPERATIONAL_METRICS));
            }
            thresholds.add(threshold);
        }
        Comparison.Options comparisonOptions = ComparisonArgs.parse(args);
        for (String metric : comparisonOptions.gate()) {
            if (!known.contains(metric)) {
                throw new UsageException("unknown --gate metric '" + metric + "'; available: " + String.join(", ", known));
            }
        }
        if (comparisonOptions.primary() != null && !known.contains(comparisonOptions.primary())) {
            throw new UsageException("unknown --primary metric '" + comparisonOptions.primary() + "'");
        }

        String mlflowExperiment = args.value("mlflow-experiment");
        URI mlflowUri = null;
        if (mlflowExperiment != null) {
            String uri = args.value("mlflow-tracking-uri") != null ? args.value("mlflow-tracking-uri") : env.get("MLFLOW_TRACKING_URI");
            if (uri == null || uri.isBlank()) {
                throw new UsageException("--mlflow-experiment needs --mlflow-tracking-uri or $MLFLOW_TRACKING_URI");
            }
            mlflowUri = URI.create(uri.strip());
        }

        GoldenSet golden;
        try {
            golden = GoldenSet.load(Path.of(args.require("golden")));
        } catch (IOException e) {
            throw new UsageException("cannot load golden set: " + e.getMessage());
        }
        EvalReport baseline = null;
        if (args.has("baseline")) {
            try {
                baseline = EvalReport.load(Path.of(args.value("baseline")));
            } catch (IOException e) {
                throw new UsageException(e.getMessage());
            }
        }
        RetrieverSpecs.Created retriever = createRetriever(args);

        boolean quiet = args.flag("quiet");
        if (!quiet) {
            err.println("evaluating " + retriever.description() + " on " + golden.size() + " queries");
        }
        EvalReport report = RagEval.builder(retriever.retriever())
                .name(retriever.description())
                .ks(ks)
                .concurrency(args.intValue("concurrency", 4, 1))
                .build()
                .evaluate(golden);

        List<ThresholdResult> thresholdResults = thresholds.stream().map(t -> t.evaluate(report)).toList();
        Comparison comparison = null;
        if (baseline != null) {
            try {
                comparison = Comparison.compare(baseline, report, comparisonOptions);
            } catch (IllegalArgumentException e) {
                throw new UsageException("cannot compare with the baseline: " + e.getMessage());
            }
        }
        Outcome outcome = new Outcome(report, thresholdResults, comparison);
        out.print(Reports.console(outcome));

        boolean wrote = Output.write(args.value("json-out"), Json.pretty(Reports.json(outcome)), false, err)
                & Output.write(args.value("markdown-out"), Reports.markdown(outcome), false, err);
        String stepSummary = env.get("GITHUB_STEP_SUMMARY");
        if (!args.flag("no-github-summary") && stepSummary != null && !stepSummary.isBlank()) {
            wrote &= Output.write(stepSummary, Reports.markdown(outcome) + "\n", true, err);
        }
        if (mlflowExperiment != null) {
            logToMlflow(mlflowUri, mlflowExperiment, args.value("mlflow-run-name"), outcome, quiet);
        }
        if (!wrote) {
            return 2;
        }
        return outcome.passed() ? 0 : 1;
    }

    private RetrieverSpecs.Created createRetriever(Args args) throws UsageException {
        RetrieverSpecs.Settings defaults = RetrieverSpecs.Settings.defaults();
        Object httpBody = null;
        if (args.has("http-body")) {
            try {
                httpBody = Json.parse(args.value("http-body"));
            } catch (JsonException e) {
                throw new UsageException("--http-body is not valid JSON: " + e.getMessage());
            }
        }
        Double timeout = args.positiveDouble("timeout", 0);
        RetrieverSpecs.Settings settings = new RetrieverSpecs.Settings(
                args.has("bm25-fields") ? ComparisonArgs.splitList(args.value("bm25-fields")) : defaults.bm25Fields(),
                httpBody,
                args.value("id-path") != null ? args.value("id-path") : defaults.idPath(),
                parseHeaders(args.values("header")),
                timeout == null ? defaults.timeout() : Duration.ofMillis((long) (timeout * 1000)));
        try {
            return RetrieverSpecs.create(args.require("retriever"), settings);
        } catch (IOException | IllegalArgumentException e) {
            throw new UsageException("cannot create retriever: " + e.getMessage());
        }
    }

    private void logToMlflow(URI uri, String experiment, String runName, Outcome outcome, boolean quiet) {
        EvalReport report = outcome.report();
        Map<String, Double> metrics = new LinkedHashMap<>(report.aggregate());
        metrics.put("error_rate", report.errorRate());
        metrics.put("latency_p50_s", report.latency().p50());
        metrics.put("latency_p95_s", report.latency().p95());
        if (outcome.comparison() != null) {
            for (MetricDelta d : outcome.comparison().metrics()) {
                metrics.put("delta_" + d.metric(), d.delta());
            }
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("retriever", report.retriever());
        params.put("golden_set", report.dataset());
        params.put("queries", String.valueOf(report.size()));
        params.put("ks", report.ks().toString());
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("rag_regress.passed", String.valueOf(outcome.passed()));
        tags.put("rag_regress.version", report.version());
        try {
            String runId = new MlflowClient(uri, env).logRun(experiment, runName, params, metrics, tags);
            if (!quiet) {
                err.println("logged MLflow run " + runId + " in experiment '" + experiment + "'");
            }
        } catch (IOException | IllegalArgumentException e) {
            err.println("warning: MLflow logging failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            err.println("warning: MLflow logging interrupted");
        }
    }

    static int[] parseKs(List<String> values) throws UsageException {
        if (values.isEmpty()) {
            return new int[] {1, 5, 10};
        }
        TreeSet<Integer> ks = new TreeSet<>();
        for (String value : values) {
            for (String part : value.split(",")) {
                if (part.isBlank()) {
                    continue;
                }
                try {
                    int k = Integer.parseInt(part.strip());
                    if (k < 1) {
                        throw new UsageException("--k values must be at least 1");
                    }
                    ks.add(k);
                } catch (NumberFormatException e) {
                    throw new UsageException("--k must be a comma-separated list of whole numbers, got '" + value + "'");
                }
            }
        }
        if (ks.isEmpty()) {
            throw new UsageException("--k needs at least one value");
        }
        return ks.stream().mapToInt(Integer::intValue).toArray();
    }

    private static String joinKs(int[] ks) {
        return String.join(",", java.util.Arrays.stream(ks).mapToObj(String::valueOf).toList());
    }

    static Map<String, String> parseHeaders(List<String> raw) throws UsageException {
        Map<String, String> headers = new LinkedHashMap<>();
        for (String header : raw) {
            int colon = header.indexOf(':');
            if (colon <= 0) {
                throw new UsageException("--header must look like 'Name: value', got '" + header + "'");
            }
            headers.put(header.substring(0, colon).strip(), header.substring(colon + 1).strip());
        }
        return headers;
    }
}
