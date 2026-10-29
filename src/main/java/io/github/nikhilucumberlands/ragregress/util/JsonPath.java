package io.github.nikhilucumberlands.ragregress.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A small subset of JSONPath for pulling document ids out of a search response:
 * dotted keys, {@code [n]} indexes and {@code [*]} wildcards, for example {@code results[*].id},
 * {@code hits.hits[*]._id} or {@code [*].metadata.doc_id}. Keys containing dots can be quoted:
 * {@code ['doc.id']}.
 */
public final class JsonPath {

    private sealed interface Step permits Key, Index, Wildcard {}

    private record Key(String name) implements Step {}

    private record Index(int index) implements Step {}

    private record Wildcard() implements Step {}

    private final String expression;
    private final List<Step> steps;

    private JsonPath(String expression, List<Step> steps) {
        this.expression = expression;
        this.steps = steps;
    }

    public static JsonPath compile(String expression) {
        String text = expression.strip();
        if (text.startsWith("$")) {
            text = text.substring(1);
        }
        if (text.startsWith(".")) {
            text = text.substring(1);
        }
        List<Step> steps = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '.') {
                i++;
                continue;
            }
            if (c == '[') {
                int close = text.indexOf(']', i);
                if (close < 0) {
                    throw new IllegalArgumentException("unclosed '[' in JSON path '" + expression + "'");
                }
                String inside = text.substring(i + 1, close).strip();
                if (inside.equals("*")) {
                    steps.add(new Wildcard());
                } else if (inside.length() >= 2 && (inside.startsWith("'") && inside.endsWith("'")
                        || inside.startsWith("\"") && inside.endsWith("\""))) {
                    steps.add(new Key(inside.substring(1, inside.length() - 1)));
                } else {
                    try {
                        steps.add(new Index(Integer.parseInt(inside)));
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("bad index '[" + inside + "]' in JSON path '" + expression + "'");
                    }
                }
                i = close + 1;
                continue;
            }
            int end = i;
            while (end < text.length() && text.charAt(end) != '.' && text.charAt(end) != '[') {
                end++;
            }
            steps.add(new Key(text.substring(i, end)));
            i = end;
        }
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("empty JSON path");
        }
        return new JsonPath(expression, List.copyOf(steps));
    }

    /** Every value the path reaches, in document order; missing branches are skipped. */
    public List<Object> extract(Object root) {
        List<Object> current = new ArrayList<>();
        current.add(root);
        for (Step step : steps) {
            List<Object> next = new ArrayList<>();
            for (Object node : current) {
                switch (step) {
                    case Key key -> {
                        if (node instanceof Map<?, ?> map && map.get(key.name()) != null) {
                            next.add(map.get(key.name()));
                        }
                    }
                    case Index index -> {
                        if (node instanceof List<?> list) {
                            int position = index.index() < 0 ? list.size() + index.index() : index.index();
                            if (position >= 0 && position < list.size() && list.get(position) != null) {
                                next.add(list.get(position));
                            }
                        }
                    }
                    case Wildcard wildcard -> {
                        if (node instanceof List<?> list) {
                            list.stream().filter(v -> v != null).forEach(next::add);
                        } else if (node instanceof Map<?, ?> map) {
                            map.values().stream().filter(v -> v != null).forEach(next::add);
                        }
                    }
                }
            }
            current = next;
        }
        return current;
    }

    @Override
    public String toString() {
        return expression;
    }
}
