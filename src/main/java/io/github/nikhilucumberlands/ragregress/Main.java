package io.github.nikhilucumberlands.ragregress;

import io.github.nikhilucumberlands.ragregress.cli.CompareCommand;
import io.github.nikhilucumberlands.ragregress.cli.RunCommand;
import io.github.nikhilucumberlands.ragregress.cli.UsageException;

import java.io.PrintStream;
import java.util.Arrays;
import java.util.Map;

/** Command-line entry point. */
public final class Main {

    private static final String USAGE = """
            rag-regress: regression tests for RAG retrieval quality.

            Usage:
              rag-regress run --golden FILE --retriever SPEC [options]   evaluate and check a retriever
              rag-regress compare BASELINE.json CURRENT.json [options]   compare two saved reports
              rag-regress --version

            Run 'rag-regress <command> --help' for the options of each command.
            """;

    private Main() {}

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err, System.getenv()));
    }

    /** Runs a command and returns the exit status instead of exiting, so it can be tested. */
    public static int run(String[] args, PrintStream out, PrintStream err, Map<String, String> env) {
        if (args.length == 0) {
            err.print(USAGE);
            return 2;
        }
        String command = args[0];
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        try {
            return switch (command) {
                case "run" -> new RunCommand(out, err, env).execute(rest);
                case "compare" -> new CompareCommand(out, err, env).execute(rest);
                case "--version", "version" -> {
                    out.println("rag-regress " + RagEval.version());
                    yield 0;
                }
                case "--help", "-h", "help" -> {
                    out.print(USAGE);
                    yield 0;
                }
                default -> throw new UsageException("unknown command '" + command + "'");
            };
        } catch (UsageException e) {
            err.println("error: " + e.getMessage());
            err.println("Run 'rag-regress --help' for usage.");
            return 2;
        }
    }
}
