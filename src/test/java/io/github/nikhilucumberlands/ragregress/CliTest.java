package io.github.nikhilucumberlands.ragregress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.nikhilucumberlands.ragregress.util.Json;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Drives the command line against the example corpus in {@code examples/}. */
class CliTest {

    private record Result(int code, String out, String err) {}

    private static Result cli(Map<String, String> env, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), env);
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static String[] run(String... extra) {
        List<String> args = new ArrayList<>(List.of("run", "--golden", "examples/golden.jsonl",
                "--retriever", "bm25:examples/corpus.jsonl", "--k", "1,5,10", "--quiet", "--no-github-summary"));
        args.addAll(List.of(extra));
        return args.toArray(String[]::new);
    }

    @Test
    void passesWhenThresholdsHoldAndWritesReports(@TempDir Path dir) throws IOException {
        Path json = dir.resolve("report.json");
        Path md = dir.resolve("out/report.md");
        Result result = cli(Map.of(), run("--threshold", "recall@5>=0.8", "--threshold", "error_rate<=0%",
                "--json-out", json.toString(), "--markdown-out", md.toString()));

        assertEquals(0, result.code(), result.out() + result.err());
        assertTrue(result.out().contains("PASS  recall@5>=0.8"), result.out());
        Map<String, Object> report = Json.parseObject(Files.readString(json));
        assertEquals(true, Json.asObject(report.get("check")).get("passed"));
        assertEquals(32, Json.asList(report.get("queries")).size());
        assertTrue(Files.readString(md).startsWith("### rag-regress: PASS"));
    }

    @Test
    void failsAgainstABaselineWhenIndexingLosesTheBodyText(@TempDir Path dir) throws IOException {
        Path baseline = dir.resolve("baseline.json");
        assertEquals(0, cli(Map.of(), run("--json-out", baseline.toString())).code());

        Path summary = dir.resolve("step-summary.md");
        List<String> args = new ArrayList<>(List.of(run("--bm25-fields", "title", "--baseline", baseline.toString(),
                "--gate", "recall@5,ndcg@10")));
        args.remove("--no-github-summary");
        Result result = cli(Map.of("GITHUB_STEP_SUMMARY", summary.toString()), args.toArray(String[]::new));

        assertEquals(1, result.code());
        assertTrue(result.out().contains("REGRESSED"), result.out());
        assertTrue(result.out().contains("16 other metrics not gated: 16 regressed, 0 improved, 0 unchanged"), result.out());
        assertTrue(result.out().contains("lost from top 10:"), result.out());
        assertTrue(result.out().contains("FAIL: 2 metrics regressed significantly (recall@5, ndcg@10)"), result.out());
        assertTrue(Files.readString(summary).contains("❌ regressed"));
    }

    @Test
    void compareCommandWorksOnSavedReports(@TempDir Path dir) {
        Path before = dir.resolve("before.json");
        Path after = dir.resolve("after.json");
        assertEquals(0, cli(Map.of(), run("--json-out", before.toString())).code());
        assertEquals(0, cli(Map.of(), run("--json-out", after.toString())).code());

        Result same = cli(Map.of(), "compare", before.toString(), after.toString(), "--no-github-summary");
        assertEquals(0, same.code(), same.out() + same.err());
        assertTrue(same.out().contains("no significant change"), same.out());

        Path worse = dir.resolve("worse.json");
        cli(Map.of(), run("--bm25-fields", "title", "--json-out", worse.toString()));
        assertEquals(1, cli(Map.of(), "compare", before.toString(), worse.toString(), "--no-github-summary").code());
    }

    @Test
    void usageErrorsExitWithTwo(@TempDir Path dir) {
        assertEquals(2, cli(Map.of(), run("--threshold", "recall@3>=0.5")).code(), "k=3 not computed");
        assertEquals(2, cli(Map.of(), run("--threshold", "latency_p95<300")).code(), "latency needs a unit");
        assertEquals(2, cli(Map.of(), run("--gate", "recall@7")).code());
        assertEquals(2, cli(Map.of(), run("--k", "0")).code());
        assertEquals(2, cli(Map.of(), "run", "--golden", "missing.jsonl", "--retriever", "bm25:examples/corpus.jsonl").code());
        assertEquals(2, cli(Map.of(), "run", "--golden", "examples/golden.jsonl", "--retriever", "ftp://nope").code());
        assertEquals(2, cli(Map.of(), run("--mlflow-experiment", "x")).code(), "no tracking URI");
        assertEquals(2, cli(Map.of(), "compare", dir.resolve("a.json").toString()).code());
        assertEquals(2, cli(Map.of(), "bogus").code());
    }

    @Test
    void helpAndVersion() {
        Result help = cli(Map.of(), "run", "--help");
        assertEquals(0, help.code());
        assertTrue(help.out().contains("--tolerance DELTA"), help.out());
        assertTrue(help.out().contains("'error_rate<=1%'"), help.out());
        assertEquals(0, cli(Map.of(), "--version").code());
    }
}
