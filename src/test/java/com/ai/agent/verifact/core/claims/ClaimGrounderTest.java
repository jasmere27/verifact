package com.ai.agent.verifact.core.claims;

import com.ai.agent.verifact.evidence.Grounding;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClaimGrounderTest {

    static final String TEXT = "The council voted 7-2 on Tuesday to approve a $4.2 billion transit budget. \"This budget will "
            + "cut commute times in half,\" Mayor Jane Rivera said. Critics say the plan ignores the suburbs.";
    static final String INPUT = Grounding.material(TEXT);
    static final ClaimProfile PROFILE = new ClaimProfile(Set.of("FACT", "QUOTE", "ATTRIBUTION"), "FACT", "QUOTE", "ATTRIBUTION", 3);

    static ClaimCandidate candidate(String type, String quote, String claim, String speaker, String quoted, List<String> queries) {
        return new ClaimCandidate(type, quote, claim, speaker, quoted, queries);
    }

    @Test
    void keepsOnlyClaimsQuotedFromTheInput() {
        List<GroundedClaim> out = ClaimGrounder.ground(List.of(
                candidate("fact", "The council voted 7-2 on Tuesday", "The council approved the budget 7-2", "", "", List.of("council budget vote")),
                candidate("FACT", "The stadium was demolished in 1998", "Invented", "", "", List.of()),
                candidate("FACT", "voted 7-2", "Too short to locate", "", "", List.of())), INPUT, PROFILE);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).type()).isEqualTo("FACT");
        assertThat(out.get(0).searchQueries()).containsExactly("council budget vote");
    }

    @Test
    void quotesNeedTheirWordsInTheInputAndSpeakersMustBeNamedThere() {
        List<GroundedClaim> out = ClaimGrounder.ground(List.of(
                candidate("QUOTE", "This budget will cut commute times in half", "Rivera said the budget halves commutes",
                        "Mayor Jane Rivera", "This budget will cut commute times in half", List.of()),
                candidate("QUOTE", "Critics say the plan ignores the suburbs", "Critics say the plan ignores the suburbs",
                        "Governor Smith", "", List.of())), INPUT, PROFILE);

        assertThat(out.get(0).type()).isEqualTo("QUOTE");
        assertThat(out.get(0).speaker()).isEqualTo("Mayor Jane Rivera");
        // Without verifiable quoted words a quote is only an attribution; an unnamed speaker is dropped.
        assertThat(out.get(1).type()).isEqualTo("ATTRIBUTION");
        assertThat(out.get(1).quotedWords()).isEmpty();
        assertThat(out.get(1).speaker()).isEmpty();
    }

    @Test
    void ungroundedRestatementsFallBackToTheInputsWordsAndQueriesAreCapped() {
        GroundedClaim c = ClaimGrounder.ground(List.of(candidate("UNKNOWN", "The council voted 7-2 on Tuesday",
                "The council voted 9-0 on Tuesday", "", "", List.of("q one", " ", "q two", "q three"))), INPUT, PROFILE).get(0);

        assertThat(c.type()).isEqualTo("FACT");
        assertThat(c.claim()).isEqualTo("The council voted 7-2 on Tuesday");
        assertThat(c.searchQueries()).containsExactly("q one", "q two");
    }

    @Test
    void stopsAtTheProfilesLimitAndRejectsInconsistentProfiles() {
        ClaimCandidate c = candidate("FACT", "The council voted 7-2 on Tuesday", "x", "", "", null);
        assertThat(ClaimGrounder.ground(List.of(c, c, c, c), INPUT, PROFILE)).hasSize(3);
        assertThat(ClaimGrounder.ground(null, INPUT, PROFILE)).isEmpty();
        assertThatThrownBy(() -> new ClaimProfile(Set.of("FACT"), "OTHER", null, null, 3)).isInstanceOf(IllegalArgumentException.class);
    }
}
