package io.github.nikhilucumberlands.ragregress.metrics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Standard ranking metrics at a cutoff k, computed for one query. Metric names look like
 * {@code recall@5}.
 *
 * <ul>
 *   <li><b>hit_rate@k</b>: 1 if any relevant document is in the top k, else 0.</li>
 *   <li><b>recall@k</b>: share of the relevant documents found in the top k.</li>
 *   <li><b>precision@k</b>: relevant documents in the top k, divided by k.</li>
 *   <li><b>mrr@k</b>: 1 / rank of the first relevant document, 0 if none in the top k.</li>
 *   <li><b>ndcg@k</b>: discounted cumulative gain with linear gain (the grade), normalised by the
 *       ideal ordering, as trec_eval computes it.</li>
 *   <li><b>map@k</b>: average precision over the relevant documents found, divided by
 *       min(relevant count, k) so a perfect top k scores 1.</li>
 * </ul>
 */
public final class RankingMetrics {

    /** Metric families in report order. */
    public static final List<String> FAMILIES = List.of("hit_rate", "recall", "precision", "mrr", "ndcg", "map");

    private RankingMetrics() {}

    public static String name(String family, int k) {
        return family + "@" + k;
    }

    /** Every metric name for the given cutoffs, family first then k. */
    public static List<String> names(List<Integer> ks) {
        List<String> names = new ArrayList<>();
        for (String family : FAMILIES) {
            for (int k : ks) {
                names.add(name(family, k));
            }
        }
        return names;
    }

    /**
     * @param ranked    retrieved document ids, best first, without duplicates
     * @param relevance document id to grade; grades above 0 are relevant
     */
    public static Map<String, Double> compute(List<String> ranked, Map<String, Double> relevance, List<Integer> ks) {
        List<Double> idealGrades = relevance.values().stream().filter(g -> g > 0).sorted((a, b) -> Double.compare(b, a)).toList();
        int relevantCount = idealGrades.size();
        Map<String, Double> metrics = new LinkedHashMap<>();
        Map<Integer, double[]> byK = new LinkedHashMap<>();
        for (int k : ks) {
            int n = Math.min(k, ranked.size());
            int hits = 0;
            double reciprocalRank = 0;
            double precisionSum = 0;
            double dcg = 0;
            for (int i = 0; i < n; i++) {
                double grade = relevance.getOrDefault(ranked.get(i), 0.0);
                if (grade > 0) {
                    hits++;
                    if (reciprocalRank == 0) {
                        reciprocalRank = 1.0 / (i + 1);
                    }
                    precisionSum += (double) hits / (i + 1);
                    dcg += grade / log2(i + 2);
                }
            }
            double idcg = 0;
            for (int i = 0; i < Math.min(k, relevantCount); i++) {
                idcg += idealGrades.get(i) / log2(i + 2);
            }
            byK.put(k, new double[] {
                hits > 0 ? 1 : 0,
                relevantCount == 0 ? 0 : (double) hits / relevantCount,
                (double) hits / k,
                reciprocalRank,
                idcg == 0 ? 0 : dcg / idcg,
                relevantCount == 0 ? 0 : precisionSum / Math.min(relevantCount, k),
            });
        }
        for (int f = 0; f < FAMILIES.size(); f++) {
            for (int k : ks) {
                metrics.put(name(FAMILIES.get(f), k), byK.get(k)[f]);
            }
        }
        return metrics;
    }

    /** Zero for every metric, used for queries whose retrieval failed. */
    public static Map<String, Double> zeros(List<Integer> ks) {
        Map<String, Double> metrics = new LinkedHashMap<>();
        for (String name : names(ks)) {
            metrics.put(name, 0.0);
        }
        return metrics;
    }

    private static double log2(double x) {
        return Math.log(x) / Math.log(2);
    }
}
