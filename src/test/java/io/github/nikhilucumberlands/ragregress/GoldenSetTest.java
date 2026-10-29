package io.github.nikhilucumberlands.ragregress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GoldenSetTest {

    @Test
    void loadsListsGradesAndTags(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("golden.jsonl");
        Files.writeString(file, """
                {"id": "q1", "query": "first", "relevant": ["d1", "d2"]}

                {"id": 2, "query": "second", "relevant": {"d3": 2, "d4": 0}, "tags": ["billing"]}
                """);
        GoldenSet golden = GoldenSet.load(file);
        assertEquals(2, golden.size());
        assertEquals(Map.of("d1", 1.0, "d2", 1.0), golden.queries().get(0).relevance());
        GoldenQuery second = golden.queries().get(1);
        assertEquals("2", second.id());
        assertEquals(Set.of("d3"), second.relevantIds());
        assertEquals(List.of("billing"), second.tags());
    }

    @Test
    void reportsTheLineOfTheFirstProblem(@TempDir Path dir) throws IOException {
        assertLoadFails(dir, "{\"id\": \"q1\", \"query\": \"x\", \"relevant\": [\"d\"]}\n{\"id\": \"q2\", \"query\": \"\"}\n", "line 2");
        assertLoadFails(dir, "{\"id\": \"q1\", \"query\": \"x\", \"relevant\": {\"d\": 0}}\n", "no relevant documents");
        assertLoadFails(dir, "{\"id\": \"q1\", \"query\": \"x\", \"relevant\": \"d\"}\n", "\"relevant\" must be");
        assertLoadFails(dir, "{\"id\": \"q1\", \"query\": \"x\", \"relevant\": [\"d\"]}\n{\"id\": \"q1\", \"query\": \"y\", \"relevant\": [\"d\"]}\n",
                "duplicate id");
        assertLoadFails(dir, "not json\n", "line 1");
    }

    private static void assertLoadFails(Path dir, String content, String expected) throws IOException {
        Path file = Files.createTempFile(dir, "golden", ".jsonl");
        Files.writeString(file, content);
        IOException e = assertThrows(IOException.class, () -> GoldenSet.load(file));
        assertTrue(e.getMessage().contains(expected), e.getMessage());
    }
}
