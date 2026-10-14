package com.ai.agent.verifact.intelligence.incidents;

import com.ai.agent.verifact.evidence.Grounding;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/** Word-overlap similarity between claims, used to link repeated reports of one allegation to one incident. */
public final class Similarity {

    private static final Set<String> STOP = Set.of("the", "and", "for", "that", "with", "from", "this", "are", "was", "were",
            "has", "have", "had", "its", "their", "his", "her", "they", "reported", "report", "reports", "said", "says",
            "claim", "claims", "about", "after", "into", "over", "been", "will", "would", "not", "but", "also");

    private Similarity() {
    }

    public static double jaccard(String a, String b) {
        Set<String> x = tokens(a);
        Set<String> y = tokens(b);
        if (x.isEmpty() || y.isEmpty()) {
            return 0;
        }
        Set<String> inter = new HashSet<>(x);
        inter.retainAll(y);
        Set<String> union = new HashSet<>(x);
        union.addAll(y);
        return (double) inter.size() / union.size();
    }

    static Set<String> tokens(String s) {
        if (s == null) {
            return Set.of();
        }
        return Arrays.stream(Grounding.words(s).split(" ")).filter(w -> w.length() >= 3 && !STOP.contains(w))
                .collect(Collectors.toSet());
    }
}
