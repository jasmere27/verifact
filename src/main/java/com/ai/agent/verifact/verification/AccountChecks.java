package com.ai.agent.verifact.verification;

import com.ai.agent.verifact.account.AccountService;
import com.ai.agent.verifact.account.SignedInUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Each account's check history (ADR-20): every report a signed-in user checked, whether their request created it
 * or reused someone else's. Entries disappear with the report (retention, deletion) or the account.
 */
@Component
public class AccountChecks {

    private static final Logger log = LoggerFactory.getLogger(AccountChecks.class);
    static final int MAX_LABEL = 120;

    private final AccountCheckRepository checks;
    private final VerificationRecordRepository reports;
    private final AccountService accounts;
    private final Clock clock;

    public AccountChecks(AccountCheckRepository checks, VerificationRecordRepository reports, AccountService accounts, Clock clock) {
        this.checks = checks;
        this.reports = reports;
        this.accounts = accounts;
        this.clock = clock;
    }

    /** {@code yours}: this account's request created the report, so it may delete it. */
    public record CheckView(UUID id, Instant checkedAt, String overallVerdict, String label, boolean yours) {}

    /**
     * Adds the report to the user's history (or moves it to the top). Best-effort, like saving reports: a
     * database problem must not fail the check.
     *
     * @return true if the user's account exists, so the report may be recorded as theirs
     */
    public boolean record(SignedInUser user, VerificationResult result) {
        try {
            accounts.ensureProvisioned(user);
        } catch (RuntimeException e) {
            log.warn("Could not provision the account for a check: {}", e.getClass().getSimpleName());
            return false;
        }
        try {
            OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC);
            AccountCheck entry = checks.findByUserIdAndVerificationId(user.id(), result.id()).orElse(null);
            if (entry == null) {
                entry = new AccountCheck(UUID.randomUUID(), user.id(), result.id(), now, result.overallVerdict().name(), label(result));
            } else {
                entry.checkedAgain(now);
            }
            checks.save(entry);
        } catch (RuntimeException e) {
            log.warn("Could not add report {} to an account's history: {}", result.id(), e.getClass().getSimpleName());
        }
        return true;
    }

    /** Newest first, at most 100 (reports older than the retention period are already gone). */
    public List<CheckView> list(SignedInUser user) {
        List<AccountCheck> entries = checks.findTop100ByUserIdOrderByCheckedAtDesc(user.id());
        Map<UUID, UUID> owners = reports.findAllById(entries.stream().map(AccountCheck::getVerificationId).toList()).stream()
                .filter(r -> r.getOwnerId() != null)
                .collect(Collectors.toMap(VerificationRecord::getId, VerificationRecord::getOwnerId));
        return entries.stream()
                .map(e -> new CheckView(e.getVerificationId(), e.getCheckedAt().toInstant(), e.getOverallVerdict(), e.getLabel(),
                        Objects.equals(owners.get(e.getVerificationId()), user.id())))
                .toList();
    }

    /** Removes the entry from the user's history only; the report itself stays. Unknown entries are ignored. */
    public void remove(SignedInUser user, UUID reportId) {
        checks.deleteByUserIdAndVerificationId(user.id(), reportId);
    }

    /** What the history shows: the first claim, else the input, on one line. */
    static String label(VerificationResult result) {
        String text = result.claims() == null ? null : result.claims().stream()
                .map(ClaimAssessment::text).filter(t -> t != null && !t.isBlank()).findFirst().orElse(null);
        if (text == null) {
            text = result.input();
        }
        String line = text == null ? "" : text.strip().replaceAll("\\s+", " ");
        if (line.isEmpty()) {
            return "Untitled check";
        }
        return line.length() > MAX_LABEL ? line.substring(0, MAX_LABEL - 1).stripTrailing() + "…" : line;
    }
}
