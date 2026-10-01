package com.ai.agent.verifact.verification;

import com.ai.agent.verifact.account.AccountService;
import com.ai.agent.verifact.account.SignedInUser;
import com.ai.agent.verifact.account.SupabaseAdmin;
import com.ai.agent.verifact.common.ApiException;
import com.ai.agent.verifact.core.assess.Verdict;
import com.ai.agent.verifact.model.InputType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/** Account check history and report ownership against the real schema (Flyway on H2), ADR-20. */
@SpringBootTest
@ActiveProfiles("test")
class AccountChecksTest {

    @Autowired
    private AccountChecks checks;
    @Autowired
    private VerificationStore store;
    @Autowired
    private VerificationRecordRepository reports;
    @Autowired
    private AccountService accounts;
    @MockitoBean
    private SupabaseAdmin admin;

    private static SignedInUser newUser() {
        return new SignedInUser(UUID.randomUUID(), "student@example.com");
    }

    private VerificationResult saved(Instant createdAt, String claim) {
        VerificationResult result = new VerificationResult(UUID.randomUUID(), createdAt, InputType.TEXT, claim, claim,
                OverallVerdict.SUPPORTED, "Summary",
                List.of(new ClaimAssessment("C1", claim, Verdict.SUPPORTED, EvidenceStrength.MODERATE, "Why", List.of(), List.of())),
                List.of(), List.of(), "test", 1, null);
        store.save(result);
        return result;
    }

    /** What VerificationController does after a signed-in check. */
    private void check(SignedInUser user, VerificationResult result, Instant requestStartedAt) {
        boolean recorded = checks.record(user, result);
        store.attachCreator(result.id(), null, recorded ? user.id() : null, requestStartedAt);
    }

    @Test
    void aSignedInCheckIsInTheHistoryAndTheReportIsTheirs() {
        SignedInUser user = newUser();
        Instant started = Instant.now().minusSeconds(1);
        VerificationResult result = saved(Instant.now(), "The bridge opened in 1998.");

        check(user, result, started);

        assertThat(checks.list(user)).singleElement().satisfies(c -> {
            assertThat(c.id()).isEqualTo(result.id());
            assertThat(c.label()).isEqualTo("The bridge opened in 1998.");
            assertThat(c.overallVerdict()).isEqualTo("SUPPORTED");
            assertThat(c.yours()).isTrue();
        });
        assertThat(checks.list(newUser())).isEmpty();
    }

    @Test
    void aReusedReportIsInTheHistoryButNotTheirs() {
        VerificationResult earlier = saved(Instant.now().minus(Duration.ofHours(2)), "A viral claim.");
        SignedInUser user = newUser();

        check(user, earlier, Instant.now().minusSeconds(1));

        assertThat(checks.list(user)).singleElement().satisfies(c -> assertThat(c.yours()).isFalse());
        assertThat(reports.findById(earlier.id())).hasValueSatisfying(r -> assertThat(r.getOwnerId()).isNull());
        assertThatThrownBy(() -> store.delete(earlier.id(), null, user.id()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void checkingAgainMovesTheEntryToTheTopWithoutDuplicating() throws Exception {
        SignedInUser user = newUser();
        VerificationResult first = saved(Instant.now().minusSeconds(60), "First claim.");
        VerificationResult second = saved(Instant.now().minusSeconds(30), "Second claim.");
        check(user, first, Instant.now().minusSeconds(120));
        Thread.sleep(5);
        check(user, second, Instant.now().minusSeconds(120));
        Thread.sleep(5);
        check(user, first, Instant.now());

        assertThat(checks.list(user)).extracting(AccountChecks.CheckView::id).containsExactly(first.id(), second.id());
    }

    @Test
    void removingFromTheHistoryKeepsTheReport() {
        SignedInUser user = newUser();
        VerificationResult result = saved(Instant.now(), "Claim.");
        check(user, result, Instant.now().minusSeconds(1));

        checks.remove(user, result.id());

        assertThat(checks.list(user)).isEmpty();
        assertThat(reports.existsById(result.id())).isTrue();
    }

    @Test
    void theOwnerCanDeleteTheReportFromAnyDeviceAndNobodyElseCan() {
        SignedInUser owner = newUser();
        SignedInUser other = newUser();
        VerificationResult result = saved(Instant.now(), "Claim.");
        check(owner, result, Instant.now().minusSeconds(1));
        check(other, result, Instant.now());

        assertThatThrownBy(() -> store.delete(result.id(), null, other.id()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        store.delete(result.id(), null, owner.id());

        assertThat(reports.existsById(result.id())).isFalse();
        assertThat(checks.list(owner)).isEmpty();
        assertThat(checks.list(other)).isEmpty();
    }

    @Test
    void deletingTheAccountDeletesTheReportsItCreatedAndItsHistory() {
        SignedInUser user = newUser();
        VerificationResult own = saved(Instant.now(), "Own claim.");
        check(user, own, Instant.now().minusSeconds(1));
        VerificationResult reused = saved(Instant.now().minus(Duration.ofHours(3)), "Someone else's claim.");
        check(user, reused, Instant.now());

        when(admin.enabled()).thenReturn(true);
        accounts.delete(user);

        assertThat(reports.existsById(own.id())).isFalse();
        assertThat(reports.existsById(reused.id())).isTrue();
        assertThat(checks.list(user)).isEmpty();
    }

    @Test
    void labelsUseTheFirstClaimOnOneLineAndAreCapped() {
        VerificationResult result = new VerificationResult(UUID.randomUUID(), Instant.now(), InputType.TEXT, "input text", "x",
                OverallVerdict.SUPPORTED, "s", List.of(), List.of(), List.of(), "test", 1, null);
        assertThat(AccountChecks.label(result)).isEqualTo("input text");

        String long1 = "word ".repeat(60);
        VerificationResult longer = new VerificationResult(UUID.randomUUID(), Instant.now(), InputType.TEXT, "x", "x",
                OverallVerdict.SUPPORTED, "s",
                List.of(new ClaimAssessment("C1", "  first\n\nclaim  " + long1, Verdict.SUPPORTED, EvidenceStrength.LIMITED, "w", List.of(), List.of())),
                List.of(), List.of(), "test", 1, null);
        String label = AccountChecks.label(longer);
        assertThat(label).startsWith("first claim word").endsWith("…").hasSizeLessThanOrEqualTo(AccountChecks.MAX_LABEL);
    }
}
