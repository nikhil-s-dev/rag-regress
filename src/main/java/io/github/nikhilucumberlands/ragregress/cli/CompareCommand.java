package io.github.nikhilucumberlands.ragregress.cli;

import io.github.nikhilucumberlands.ragregress.EvalReport;
import io.github.nikhilucumberlands.ragregress.compare.Comparison;
import io.github.nikhilucumberlands.ragregress.report.Outcome;
import io.github.nikhilucumberlands.ragregress.report.Reports;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** {@code rag-regress compare}: compare two saved reports without running retrieval again. */
public final class CompareCommand {

    static final String HELP = """
            Usage: rag-regress compare BASELINE.json CURRENT.json [options]

            Compares two reports written by 'rag-regress run --json-out' query by query and exits 1
            when a gated metric regressed significantly.

            %s
              --markdown-out FILE        Markdown report
              --no-github-summary        do not append to $GITHUB_STEP_SUMMARY
            """.formatted(ComparisonArgs.HELP.stripTrailing());

    private final PrintStream out;
    private final PrintStream err;
    private final Map<String, String> env;

    public CompareCommand(PrintStream out, PrintStream err, Map<String, String> env) {
        this.out = out;
        this.err = err;
        this.env = env;
    }

    public int execute(String[] argv) throws UsageException {
        Set<String> valueOptions = new HashSet<>(ComparisonArgs.VALUE_OPTIONS);
        valueOptions.add("markdown-out");
        Set<String> switches = new HashSet<>(ComparisonArgs.SWITCHES);
        switches.addAll(List.of("help", "no-github-summary"));
        Args args = Args.parse(argv, valueOptions, switches);
        if (args.flag("help")) {
            out.print(HELP);
            return 0;
        }
        if (args.positionals().size() != 2) {
            throw new UsageException("compare needs two report files: BASELINE.json CURRENT.json");
        }
        EvalReport baseline;
        EvalReport current;
        try {
            baseline = EvalReport.load(Path.of(args.positionals().get(0)));
            current = EvalReport.load(Path.of(args.positionals().get(1)));
        } catch (IOException e) {
            throw new UsageException(e.getMessage());
        }
        Comparison comparison;
        try {
            comparison = Comparison.compare(baseline, current, ComparisonArgs.parse(args));
        } catch (IllegalArgumentException e) {
            throw new UsageException(e.getMessage());
        }
        out.print(Reports.comparisonOnly(comparison, baseline, current));

        Outcome outcome = new Outcome(current, List.of(), comparison);
        boolean wrote = Output.write(args.value("markdown-out"), Reports.markdown(outcome), false, err);
        String stepSummary = env.get("GITHUB_STEP_SUMMARY");
        if (!args.flag("no-github-summary") && stepSummary != null && !stepSummary.isBlank()) {
            wrote &= Output.write(stepSummary, Reports.markdown(outcome) + "\n", true, err);
        }
        if (!wrote) {
            return 2;
        }
        return comparison.hasRegression() ? 1 : 0;
    }
}
