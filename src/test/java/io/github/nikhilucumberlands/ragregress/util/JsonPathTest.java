package io.github.nikhilucumberlands.ragregress.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class JsonPathTest {

    private static final Object RESPONSE = Json.parse("""
            {"results": [
              {"id": "d1", "metadata": {"doc.id": "x1"}},
              {"id": "d2", "metadata": {"doc.id": "x2"}},
              {"score": 0.1}
            ],
             "hits": {"hits": [{"_id": 7}, {"_id": 8}]}}
            """);

    @Test
    void wildcardsKeysAndIndexes() {
        assertEquals(List.of("d1", "d2"), JsonPath.compile("results[*].id").extract(RESPONSE));
        assertEquals(List.of(7L, 8L), JsonPath.compile("$.hits.hits[*]._id").extract(RESPONSE));
        assertEquals(List.of("d2"), JsonPath.compile("results[1].id").extract(RESPONSE));
        assertEquals(List.of("d2"), JsonPath.compile("results[-2].id").extract(RESPONSE));
        assertEquals(List.of("x1", "x2"), JsonPath.compile("results[*].metadata['doc.id']").extract(RESPONSE));
    }

    @Test
    void topLevelArrays() {
        assertEquals(List.of("a", "b"), JsonPath.compile("[*].id").extract(Json.parse("[{\"id\":\"a\"},{\"id\":\"b\"}]")));
    }

    @Test
    void missingBranchesYieldNothing() {
        assertEquals(List.of(), JsonPath.compile("nope[*].id").extract(RESPONSE));
        assertEquals(List.of(), JsonPath.compile("results[9].id").extract(RESPONSE));
    }

    @Test
    void rejectsMalformedPaths() {
        assertThrows(IllegalArgumentException.class, () -> JsonPath.compile("results[*"));
        assertThrows(IllegalArgumentException.class, () -> JsonPath.compile("results[x]"));
        assertThrows(IllegalArgumentException.class, () -> JsonPath.compile("$"));
    }
}
