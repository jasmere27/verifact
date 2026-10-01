package com.ai.agent.verifact.common;

import com.ai.agent.verifact.feedback.FeedbackReason;
import com.ai.agent.verifact.feedback.VerificationFeedback;
import com.ai.agent.verifact.feedback.VerificationFeedbackRepository;
import com.ai.agent.verifact.news.NewsReviewRecord;
import com.ai.agent.verifact.news.NewsReviewRepository;
import com.ai.agent.verifact.news.NewsStore;
import com.ai.agent.verifact.repository.FactCheckRetention;
import com.ai.agent.verifact.verification.VerificationRecord;
import com.ai.agent.verifact.verification.VerificationRecordRepository;
import com.ai.agent.verifact.verification.VerificationStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Retention and owner deletion against the real schema (Flyway on H2): what the Privacy Policy promises. */
@SpringBootTest
@ActiveProfiles("test")
class RetentionTest {

    private static final String TOKEN = "k".repeat(43);

    @Autowired
    private VerificationStore reports;
    @Autowired
    private VerificationRecordRepository reportRows;
    @Autowired
    private VerificationFeedbackRepository feedback;
    @Autowired
    private NewsStore news;
    @Autowired
    private NewsReviewRepository newsRows;
    @Autowired
    private FactCheckRetention v1;
    @Autowired
    private JdbcTemplate jdbc;

    private static OffsetDateTime daysAgo(long days) {
        return Instant.now().minus(Duration.ofDays(days)).atOffset(ZoneOffset.UTC);
    }

    private UUID report(OffsetDateTime createdAt) {
        UUID id = UUID.randomUUID();
        reportRows.save(new VerificationRecord(id, createdAt, "TEXT", "SUPPORTED", "test", 1, "{}", null));
        return id;
    }

    private UUID feedbackOn(UUID reportId) {
        UUID id = UUID.randomUUID();
        feedback.save(new VerificationFeedback(id, reportId, true, FeedbackReason.OTHER, "useful", OffsetDateTime.now()));
        return id;
    }

    @Test
    void reportsOlderThan90DaysAreDeletedWithTheirFeedback() {
        UUID old = report(daysAgo(91));
        UUID oldFeedback = feedbackOn(old);
        UUID recent = report(daysAgo(89));
        UUID recentFeedback = feedbackOn(recent);

        reports.deleteExpired();

        assertThat(reportRows.existsById(old)).isFalse();
        assertThat(feedback.existsById(oldFeedback)).isFalse();
        assertThat(reportRows.existsById(recent)).isTrue();
        assertThat(feedback.existsById(recentFeedback)).isTrue();
    }

    @Test
    void newsReviewsAreDeleted90DaysAfterTheirLastChange() {
        UUID stale = UUID.randomUUID();
        newsRows.save(new NewsReviewRecord(stale, daysAgo(91), null, "{}", "{}", EditTokens.hash(TOKEN)));
        UUID fresh = UUID.randomUUID();
        newsRows.save(new NewsReviewRecord(fresh, daysAgo(10), null, "{}", "{}", EditTokens.hash(TOKEN)));

        news.deleteExpired();

        assertThat(newsRows.existsById(stale)).isFalse();
        assertThat(newsRows.existsById(fresh)).isTrue();
    }

    @Test
    void v1ResultsOlderThan90DaysAreDeleted() {
        String insert = "INSERT INTO fact_check_results (input_type, original_input, full_response, created_at) VALUES ('TEXT', ?, 'r', ?)";
        jdbc.update(insert, "retention-old", daysAgo(91));
        jdbc.update(insert, "retention-recent", daysAgo(1));

        v1.deleteExpired();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM fact_check_results WHERE original_input = 'retention-old'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fact_check_results WHERE original_input = 'retention-recent'", Integer.class)).isOne();
    }

    @Test
    void theBrowserThatCreatedAReportCanDeleteItButNobodyElseCan() {
        Instant requestStarted = Instant.now().minusSeconds(1);
        UUID id = report(OffsetDateTime.now());
        UUID fb = feedbackOn(id);
        reports.attachCreator(id, TOKEN, null, requestStarted);

        assertThatThrownBy(() -> reports.delete(id, "x".repeat(43), null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> reports.delete(id, null, null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        reports.delete(id, TOKEN, null);

        assertThat(reportRows.existsById(id)).isFalse();
        assertThat(feedback.existsById(fb)).isFalse();
        assertThatThrownBy(() -> reports.delete(id, TOKEN, null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void aReusedReportCannotBeClaimedByALaterRequest() {
        UUID reused = report(daysAgo(0).minusHours(2));
        reports.attachCreator(reused, TOKEN, null, Instant.now().minusSeconds(1));

        assertThat(reportRows.findById(reused)).hasValueSatisfying(r -> assertThat(r.getEditTokenHash()).isNull());
        assertThatThrownBy(() -> reports.delete(reused, TOKEN, null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void theFirstTokenOnAReportIsKept() {
        Instant requestStarted = Instant.now().minusSeconds(1);
        UUID id = report(OffsetDateTime.now());
        reports.attachCreator(id, TOKEN, null, requestStarted);
        reports.attachCreator(id, "y".repeat(43), null, requestStarted);

        assertThat(reportRows.findById(id)).hasValueSatisfying(r -> assertThat(r.getEditTokenHash()).isEqualTo(EditTokens.hash(TOKEN)));
    }

    @Test
    void newsFactReviewsCanBeDeletedOnlyWithTheirEditToken() {
        UUID id = UUID.randomUUID();
        newsRows.save(new NewsReviewRecord(id, OffsetDateTime.now(), null, "{}", "{}", EditTokens.hash(TOKEN)));

        assertThatThrownBy(() -> news.delete(id, "x".repeat(43)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        news.delete(id, TOKEN);

        assertThat(newsRows.existsById(id)).isFalse();
    }
}
