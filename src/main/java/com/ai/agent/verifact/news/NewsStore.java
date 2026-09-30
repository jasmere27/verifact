package com.ai.agent.verifact.news;

import com.ai.agent.verifact.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Saves and loads NewsFact workspaces. Creating one returns a random edit token (shown once); only
 * its SHA-256 is stored, and review updates must present the token. Unlike VeriFact's best-effort
 * report saving, a workspace that can't be saved is an error: the editor's work depends on it.
 */
@Component
public class NewsStore {

    private static final Logger log = LoggerFactory.getLogger(NewsStore.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final NewsReviewRepository repository;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public NewsStore(NewsReviewRepository repository, JsonMapper jsonMapper, Clock clock) {
        this.repository = repository;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    /** @return the workspace including its new edit token */
    public NewsWorkspace create(NewsCheck check) {
        String token = newToken();
        NewsReview review = NewsReview.empty(clock.instant());
        try {
            repository.save(new NewsReviewRecord(check.id(), check.createdAt().atOffset(ZoneOffset.UTC), check.articleUrl(),
                    jsonMapper.writeValueAsString(check), jsonMapper.writeValueAsString(review), hash(token)));
        } catch (RuntimeException e) {
            log.error("Failed to store news check {}", check.id(), e);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "The check finished but couldn't be saved, so it can't be reviewed. Please try again.", e);
        }
        return new NewsWorkspace(check, review, token);
    }

    public Optional<NewsWorkspace> find(UUID id) {
        return repository.findById(id).flatMap(r -> {
            try {
                return Optional.of(new NewsWorkspace(jsonMapper.readValue(r.getResultJson(), NewsCheck.class),
                        jsonMapper.readValue(r.getReviewJson(), NewsReview.class), null));
            } catch (RuntimeException e) {
                log.error("Stored news check {} could not be read", r.getId(), e);
                return Optional.empty();
            }
        });
    }

    /** @throws ApiException 404 unknown id, 403 wrong or missing token */
    @Transactional
    public NewsReview updateReview(UUID id, String token, NewsReview review) {
        NewsReviewRecord record = repository.findById(id).orElseThrow(NewsStore::notFound);
        if (token == null || !MessageDigest.isEqual(hash(token).getBytes(StandardCharsets.UTF_8),
                record.getEditTokenHash().getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only the person who ran this check can change its review.");
        }
        NewsReview saved = new NewsReview(review.decisions(), review.editorNote(), clock.instant());
        record.updateReview(jsonMapper.writeValueAsString(saved), saved.updatedAt().atOffset(ZoneOffset.UTC));
        repository.save(record);
        return saved;
    }

    static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "That NewsFact review doesn't exist or is no longer available.");
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
