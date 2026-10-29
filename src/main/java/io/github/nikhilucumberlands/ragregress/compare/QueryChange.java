package io.github.nikhilucumberlands.ragregress.compare;

import java.util.List;

/**
 * A query whose score on the primary metric dropped, with the relevant documents that fell out of
 * (or came into) the top k. This is usually where debugging a regression starts.
 */
public record QueryChange(String id, String query, double baseline, double current,
                          List<String> lostRelevant, List<String> gainedRelevant) {

    public QueryChange {
        lostRelevant = List.copyOf(lostRelevant);
        gainedRelevant = List.copyOf(gainedRelevant);
    }

    public double delta() {
        return current - baseline;
    }
}
