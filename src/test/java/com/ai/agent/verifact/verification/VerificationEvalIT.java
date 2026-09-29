package com.ai.agent.verifact.verification;

import com.ai.agent.verifact.common.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live evaluation against the REAL model and search provider. Costs money, so it only runs with
 * {@code RUN_EVALS=true} plus OPEN_AI_API_KEY and TAVILY_API_KEY in the environment:
 *
 * <pre>RUN_EVALS=true OPEN_AI_API_KEY=... TAVILY_API_KEY=... ./mvnw test -Dtest=VerificationEvalIT</pre>
 *
 * Checks structural invariants on every case (hard failures) and verdict accuracy against the
 * labels in {@code evals/claims.json} (must reach {@link #MIN_ACCURACY}). Wording is never asserted.
 * Roughly 20 cases × (2 model calls + up to 4 searches).
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "RUN_EVALS", matches = "true")
class VerificationEvalIT {

    static final double MIN_ACCURACY = 0.8;

    record Case(String input, String expect) {}

    @Autowired
    private VerificationService service;

    @Test
    void labeledClaims() throws Exception {
        List<Case> cases;
        try (InputStream in = new ClassPathResource("evals/claims.json").getInputStream()) {
            cases = new JsonMapper().readValue(in, new TypeReference<List<Case>>() {});
        }

        int correct = 0;
        List<String> report = new ArrayList<>();
        for (Case c : cases) {
            String outcome;
            try {
                VerificationResult r = service.verifyText(c.input());
                assertInvariants(r);
                outcome = bucket(r.overallVerdict());
                report.add(String.format("%-8s %-8s %-22s %s", c.expect(), outcome, r.overallVerdict(), c.input()));
            } catch (ApiException e) {
                outcome = e.getStatus() == HttpStatus.UNPROCESSABLE_ENTITY ? "NO_CLAIM" : "ERROR " + e.getStatus();
                report.add(String.format("%-8s %-8s %-22s %s", c.expect(), outcome, "-", c.input()));
            }
            if (outcome.equals(c.expect())) {
                correct++;
            }
        }

        double accuracy = (double) correct / cases.size();
        System.out.println("\nEXPECT   GOT      VERDICT                INPUT\n" + String.join("\n", report));
        System.out.printf("%nAccuracy: %d/%d = %.0f%%%n", correct, cases.size(), accuracy * 100);
        assertThat(accuracy).as("verdict accuracy").isGreaterThanOrEqualTo(MIN_ACCURACY);
    }

    private static String bucket(OverallVerdict v) {
        return switch (v) {
            case SUPPORTED, PARTLY_SUPPORTED -> "TRUE";
            case CONTRADICTED, MISLEADING -> "FALSE";
            case INSUFFICIENT_EVIDENCE -> "UNKNOWN";
            case MIXED -> "MIXED";
        };
    }

    /** Must hold for every report regardless of what the model says. */
    private static void assertInvariants(VerificationResult r) {
        Set<String> ids = r.evidence().stream().map(Evidence::id).collect(Collectors.toSet());
        assertThat(r.claims()).isNotEmpty().hasSizeLessThanOrEqualTo(VerificationService.MAX_CLAIMS);
        for (ClaimAssessment c : r.claims()) {
            assertThat(ids).as("citations resolve").containsAll(c.supportingEvidenceIds()).containsAll(c.contradictingEvidenceIds());
            if (c.verdict() == Verdict.SUPPORTED || c.verdict() == Verdict.PARTLY_SUPPORTED) {
                assertThat(c.supportingEvidenceIds()).as("supported verdicts cite support").isNotEmpty();
            }
            if (c.verdict() == Verdict.CONTRADICTED) {
                assertThat(c.contradictingEvidenceIds()).as("contradicted verdicts cite contradiction").isNotEmpty();
            }
        }
        assertThat(r.evidence()).allSatisfy(e -> assertThat(e.url()).matches("^https?://.*"));
    }
}
