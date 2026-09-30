package com.ai.agent.verifact.legal;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** US states and DC by two-letter code, for resolving the model's jurisdiction guess. */
final class UsStates {

    private UsStates() {
    }

    static final Map<String, String> NAMES = new LinkedHashMap<>();

    static {
        String[] pairs = {
                "AL", "Alabama", "AK", "Alaska", "AZ", "Arizona", "AR", "Arkansas", "CA", "California",
                "CO", "Colorado", "CT", "Connecticut", "DE", "Delaware", "DC", "District of Columbia",
                "FL", "Florida", "GA", "Georgia", "HI", "Hawaii", "ID", "Idaho", "IL", "Illinois", "IN", "Indiana",
                "IA", "Iowa", "KS", "Kansas", "KY", "Kentucky", "LA", "Louisiana", "ME", "Maine", "MD", "Maryland",
                "MA", "Massachusetts", "MI", "Michigan", "MN", "Minnesota", "MS", "Mississippi", "MO", "Missouri",
                "MT", "Montana", "NE", "Nebraska", "NV", "Nevada", "NH", "New Hampshire", "NJ", "New Jersey",
                "NM", "New Mexico", "NY", "New York", "NC", "North Carolina", "ND", "North Dakota", "OH", "Ohio",
                "OK", "Oklahoma", "OR", "Oregon", "PA", "Pennsylvania", "RI", "Rhode Island", "SC", "South Carolina",
                "SD", "South Dakota", "TN", "Tennessee", "TX", "Texas", "UT", "Utah", "VT", "Vermont",
                "VA", "Virginia", "WA", "Washington", "WV", "West Virginia", "WI", "Wisconsin", "WY", "Wyoming"};
        for (int i = 0; i < pairs.length; i += 2) {
            NAMES.put(pairs[i], pairs[i + 1]);
        }
    }

    /** "California", "CA" or "ca" → "CA"; null if not a US state. */
    static String code(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim();
        String upper = v.toUpperCase(Locale.ROOT);
        if (NAMES.containsKey(upper)) {
            return upper;
        }
        for (Map.Entry<String, String> e : NAMES.entrySet()) {
            if (e.getValue().equalsIgnoreCase(v)) {
                return e.getKey();
            }
        }
        return null;
    }
}
