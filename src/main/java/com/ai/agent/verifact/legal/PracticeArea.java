package com.ai.agent.verifact.legal;

import java.util.Locale;

/** Broad practice areas, close to how legal marketplaces categorise consumer cases. */
public enum PracticeArea {
    EMPLOYMENT, HOUSING, FAMILY, CONSUMER, PERSONAL_INJURY, CRIMINAL, IMMIGRATION, DEBT_AND_BANKRUPTCY,
    WILLS_AND_ESTATES, BUSINESS_AND_CONTRACTS, REAL_ESTATE, INTELLECTUAL_PROPERTY, CIVIL_RIGHTS, OTHER;

    /** Lenient: the model's label, or OTHER if it isn't one of ours. */
    static PracticeArea parse(String value) {
        if (value == null) {
            return OTHER;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT).replace("&", "AND").replaceAll("[\\s/-]+", "_"));
        } catch (IllegalArgumentException e) {
            return OTHER;
        }
    }
}
