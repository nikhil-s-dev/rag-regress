package io.github.nikhilucumberlands.ragregress.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ThresholdTest {

    @ParameterizedTest
    @CsvSource({
        "'recall@5>=0.8', recall@5, 0.8",
        "'ndcg@10 >= 75%', ndcg@10, 0.75",
        "'error_rate<=1%', error_rate, 0.01",
        "'latency_p95<300ms', latency_p95, 0.3",
        "'LATENCY_MAX<2s', latency_max, 2",
    })
    void parsesIntoBaseUnits(String expression, String metric, double value) {
        Threshold threshold = Threshold.parse(expression);
        assertEquals(metric, threshold.metric());
        assertEquals(value, threshold.value(), 1e-12);
    }

    @ParameterizedTest
    @ValueSource(strings = {"recall@5>=80", "latency_p95<300", "recall@5>=0.8ms", "recall@5 = 0.8", "recall5>=x"})
    void rejectsAmbiguousOrMalformedThresholds(String expression) {
        assertThrows(IllegalArgumentException.class, () -> Threshold.parse(expression));
    }

    @ParameterizedTest
    @CsvSource({"'<', 0.5, 0.6, true", "'<=', 0.6, 0.6, true", "'>', 0.6, 0.6, false", "'>=', 0.7, 0.6, true"})
    void operatorsCompareAsWritten(String symbol, double actual, double limit, boolean expected) {
        Threshold.Operator operator = Threshold.parse("recall@5" + symbol + limit).operator();
        assertEquals(expected, operator.test(actual, limit));
    }

    @org.junit.jupiter.api.Test
    void formatsByMetricKind() {
        assertEquals("250.0 ms", Threshold.parse("latency_p95<1s").format(0.25));
        assertEquals("1.50%", Threshold.parse("error_rate<5%").format(0.015));
        assertEquals("0.812", Threshold.parse("recall@5>0.5").format(0.8123));
        assertTrue(Threshold.parse("latency_p50<1s").latency());
        assertFalse(Threshold.parse("mrr@10>0.5").latency());
    }
}
