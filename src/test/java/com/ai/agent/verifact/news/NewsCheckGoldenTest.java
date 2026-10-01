package com.ai.agent.verifact.news;

import com.ai.agent.verifact.evidence.EvidenceRetriever;
import com.ai.agent.verifact.fetch.SafeUrlFetcher;
import com.ai.agent.verifact.news.NewsCheckServiceTest.FakeLlm;
import com.ai.agent.verifact.news.NewsCheckServiceTest.FakeSearch;
import com.ai.agent.verifact.news.NewsOutputs.ArticleClaim;
import com.ai.agent.verifact.news.NewsOutputs.ClaimExtraction;
import com.ai.agent.verifact.news.NewsOutputs.ClaimReview;
import com.ai.agent.verifact.news.NewsOutputs.ClaimReviews;
import com.ai.agent.verifact.search.SearchResult;
import com.ai.agent.verifact.verification.VerificationProgress;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Pins NewsFact's behaviour while its reusable parts move into the shared evidence core: the full check result
 * and every prompt sent to the model must stay byte for byte the same, and checks already stored in
 * {@code news_reviews} must still load. A deliberate behaviour change regenerates the golden files with
 * {@code -DupdateGolden=true} and is reviewed in the diff.
 */
class NewsCheckGoldenTest {

    static final Path GOLDEN = Path.of("src", "test", "resources", "golden");
    static final boolean UPDATE = Boolean.getBoolean("updateGolden");

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void checkResultAndPromptsAreUnchanged() throws IOException {
        FakeLlm llm = new FakeLlm();
        FakeSearch search = new FakeSearch();
        NewsCheckService service = new NewsCheckService(llm, new EvidenceRetriever(search), mock(SafeUrlFetcher.class),
                Clock.fixed(Instant.parse("2026-09-30T12:00:00Z"), ZoneOffset.UTC), 20_000, null);
        script(llm, search);

        NewsCheck check = service.check(NewsCheckServiceTest.ARTICLE, VerificationProgress.NONE);

        ObjectNode result = (ObjectNode) json.valueToTree(check);
        result.put("id", "<id>");
        result.put("durationMs", 0);
        matches("news-check-result.json", json.writerWithDefaultPrettyPrinter().writeValueAsString(result));

        StringBuilder prompts = new StringBuilder();
        for (int i = 0; i < llm.systemPrompts.size(); i++) {
            prompts.append("===== SYSTEM ").append(i + 1).append(" =====\n").append(llm.systemPrompts.get(i))
                    .append("\n===== USER ").append(i + 1).append(" =====\n").append(llm.userMessages.get(i)).append('\n');
        }
        matches("news-check-prompts.txt", prompts.toString().replaceAll("[0-9a-f]{32}", "<nonce>"));
    }

    @Test
    void storedChecksStillLoadWithoutLosingAnything() throws IOException {
        for (String file : List.of("stored-news-check-before-videos.json", "stored-news-check-with-videos.json")) {
            JsonNode stored = json.readTree(Files.readString(GOLDEN.resolve(file)));

            JsonNode reloaded = json.valueToTree(json.treeToValue(stored, NewsCheck.class));

            sameAsStored(file, stored, reloaded);
        }
    }

    /** Every stored value survives unchanged at any depth; fields added since are the only extras, and are null. */
    private static void sameAsStored(String path, JsonNode stored, JsonNode reloaded) {
        if (stored.isObject()) {
            assertThat(reloaded.isObject()).as(path).isTrue();
            stored.propertyNames().forEach(n -> sameAsStored(path + "." + n, stored.get(n), reloaded.get(n) == null ? tools.jackson.databind.node.JsonNodeFactory.instance.nullNode() : reloaded.get(n)));
            reloaded.propertyNames().forEach(n -> {
                if (!stored.has(n)) {
                    assertThat(reloaded.get(n).isNull()).as(path + "." + n + " (new field)").isTrue();
                }
            });
        } else if (stored.isArray()) {
            assertThat(reloaded.size()).as(path).isEqualTo(stored.size());
            for (int i = 0; i < stored.size(); i++) {
                sameAsStored(path + "[" + i + "]", stored.get(i), reloaded.get(i));
            }
        } else {
            // As JSON text: a stored 62687 may read back as a long where the file had an int.
            assertThat(reloaded.toString()).as(path).isEqualTo(stored.toString());
        }
    }


    /** Covers every rule: grounded/ungrounded claims, quote found/not found, each verdict gate, context gate, conflict. */
    static void script(FakeLlm llm, FakeSearch search) {
        llm.extraction = new ClaimExtraction("", List.of(
                claim("STATISTIC", "The council voted 7-2 on Tuesday to approve a $4.2 billion transit budget",
                        "The council approved a $4.2 billion transit budget by 7-2", "", ""),
                claim("QUOTE", "\"This budget will cut commute times in half by 2030,\" Mayor Jane Rivera said",
                        "Mayor Jane Rivera said the budget will cut commute times in half by 2030", "Mayor Jane Rivera",
                        "This budget will cut commute times in half by 2030"),
                claim("STATISTIC", "Ridership fell 12 percent last year, according to the transit authority",
                        "Ridership fell 12 percent last year", "the transit authority", ""),
                claim("FACT", "The stadium was demolished in 1998 after a fire", "Invented", "", ""),
                claim("QUOTE", "Critics say the plan ignores the suburbs", "Critics say the plan ignores the suburbs",
                        "Governor Smith", ""),
                claim("QUOTE", "the largest in the city's history", "The budget is the largest in the city's history",
                        "", "the largest in the city's history"),
                claim("DATE-TIME", "Mayor Jane Rivera said at a press conference",
                        "Mayor Jane Rivera spoke at a press conference", "", "")));
        search.answer = List.of(
                new SearchResult("Council passes budget", "https://localnews.example/budget",
                        "The council voted 7-2 to approve the $4.2 billion transit budget on Tuesday.", "2026-09-29"),
                new SearchResult("Mayor's remarks", "https://tv.example/rivera",
                        "Rivera said: This budget will cut commute times in half by 2030, adding more buses.", "2026-09-29"),
                new SearchResult("Transit ridership report", "https://transit.example/report",
                        "Ridership fell 8 percent last year according to the annual report.", "2026-03-01"),
                new SearchResult("Other ridership", "https://paper.example/riders",
                        "Officials said ridership fell 12 percent last year.", null));
        llm.review = new ClaimReviews(List.of(
                new ClaimReview("C1", "SUPPORTED", "E1", "The council voted 7-2 to approve the $4.2 billion transit budget",
                        "", "", "NONE", "A local report confirms the vote."),
                new ClaimReview("c2", "SUPPORTED", "E2", "the mayor promised faster commutes for everyone", "", "", "NONE", "x"),
                new ClaimReview("C3", "PARTLY_SUPPORTED", "E4", "Officials said ridership fell 12 percent last year",
                        "e3", "Ridership fell 8 percent last year according to the annual report", "outdated",
                        "The annual report gives 8 percent."),
                new ClaimReview("C4", "CONTRADICTED", "", "", "", "", "MISSING_CONTEXT", "x"),
                new ClaimReview("C5", "MISLEADING", "", "", "E9", "Ridership fell 8 percent last year", "OLD_EVENT_AS_NEW",
                        "An unknown source."),
                new ClaimReview("C6", "misleading", "", "", "E3", "Ridership fell 8 percent last year according to the annual report",
                        "SOMETHING_ELSE", "Moon cheese explanation unrelated to anything.")));
    }

    private static ArticleClaim claim(String type, String quote, String claim, String speaker, String quoted) {
        return new ArticleClaim(type, quote, claim, speaker, quoted, List.of(claim));
    }

    private static void matches(String file, String actual) throws IOException {
        Path path = GOLDEN.resolve(file);
        if (UPDATE) {
            Files.writeString(path, actual, StandardCharsets.UTF_8);
        }
        assertThat(actual).as(file + " (regenerate with -DupdateGolden=true only for a deliberate change)")
                .isEqualTo(Files.readString(path, StandardCharsets.UTF_8));
    }
}
