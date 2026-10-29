package io.github.nikhilucumberlands.ragregress.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Writes report files, creating parent directories. */
final class Output {

    private Output() {}

    /** Returns false (after printing the reason) when the file could not be written. */
    static boolean write(String target, String content, boolean append, PrintStream err) {
        if (target == null) {
            return true;
        }
        try {
            Path path = Path.of(target);
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (append) {
                Files.writeString(path, content, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } else {
                Files.writeString(path, content);
            }
            return true;
        } catch (IOException e) {
            err.println("error: cannot write " + target + ": " + e.getMessage());
            return false;
        }
    }
}
