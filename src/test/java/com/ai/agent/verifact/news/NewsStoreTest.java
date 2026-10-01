package com.ai.agent.verifact.news;

import com.ai.agent.verifact.common.ApiException;
import com.ai.agent.verifact.verification.Verdict;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Workspace storage and the edit-token rule. */
class NewsStoreTest {

    private final NewsReviewRepository repository = mock(NewsReviewRepository.class);
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final NewsStore store = new NewsStore(repository, jsonMapper, Clock.fixed(Instant.parse("2026-09-30T12:00:00Z"), ZoneOffset.UTC));

    private static NewsCheck check() {
        return new NewsCheck(UUID.randomUUID(), Instant.parse("2026-09-30T12:00:00Z"), null, "t", null, List.of(), List.of(),
                Map.of(Verdict.SUPPORTED, 0), List.of(), "n", "fake", 1, null);
    }

    @Test
    void creatingReturnsATokenOnceAndStoresOnlyItsHash() {
        ArgumentCaptor<NewsReviewRecord> saved = ArgumentCaptor.forClass(NewsReviewRecord.class);

        NewsWorkspace w = store.create(check());

        verify(repository).save(saved.capture());
        assertThat(w.editToken()).hasSizeGreaterThanOrEqualTo(40);
        assertThat(saved.getValue().getEditTokenHash()).isEqualTo(NewsStore.hash(w.editToken())).doesNotContain(w.editToken());
    }

    @Test
    void onlyTheTokenHolderCanChangeTheReview() {
        NewsWorkspace w = store.create(check());
        NewsReviewRecord record = new NewsReviewRecord(w.check().id(), w.check().createdAt().atOffset(ZoneOffset.UTC), null,
                "{}", "{}", NewsStore.hash(w.editToken()));
        when(repository.findById(w.check().id())).thenReturn(Optional.of(record));
        NewsReview review = new NewsReview(Map.of("C1", new NewsReview.Decision(NewsReview.Status.CONFIRMED, "ok")), null, null);

        assertThatThrownBy(() -> store.updateReview(w.check().id(), "wrong", review))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> store.updateReview(w.check().id(), null, review)).isInstanceOf(ApiException.class);

        NewsReview saved = store.updateReview(w.check().id(), w.editToken(), review);
        assertThat(saved.decisions().get("C1").status()).isEqualTo(NewsReview.Status.CONFIRMED);
        assertThat(saved.updatedAt()).isEqualTo(Instant.parse("2026-09-30T12:00:00Z"));
        assertThat(record.getReviewJson()).contains("CONFIRMED");
    }

    @Test
    void aWorkspaceThatCantBeSavedIsAnErrorNotASilentLoss() {
        when(repository.save(any())).thenThrow(new RuntimeException("db down"));

        assertThatThrownBy(() -> store.create(check()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void unknownIdsAreNotFoundAndTokensAreNeverReturnedOnRead() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> store.updateReview(id, "t", NewsReview.empty(Instant.EPOCH)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(repository, never()).save(any());

        NewsCheck c = check();
        when(repository.findById(c.id())).thenReturn(Optional.of(new NewsReviewRecord(c.id(), c.createdAt().atOffset(ZoneOffset.UTC),
                null, jsonMapper.writeValueAsString(c), jsonMapper.writeValueAsString(NewsReview.empty(Instant.EPOCH)), "h")));
        assertThat(store.find(c.id())).hasValueSatisfying(w -> assertThat(w.editToken()).isNull());
    }
}
