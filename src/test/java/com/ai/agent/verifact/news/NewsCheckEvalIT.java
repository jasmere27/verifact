package com.ai.agent.verifact.news;

import com.ai.agent.verifact.news.NewsCheck.NewsClaim;
import com.ai.agent.verifact.news.NewsCheck.QuoteStatus;
import com.ai.agent.verifact.verification.Verdict;
import com.ai.agent.verifact.verification.VerificationProgress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live NewsFact check against the REAL model and search provider on a short article with known
 * answers. Costs a little, so it only runs with {@code RUN_EVALS=true} plus OPEN_AI_API_KEY and TAVILY_API_KEY
 * (and YOUTUBE_API_KEY to include supporting videos; uses ~4 of the ~100 daily searches):
 *
 * <pre>RUN_EVALS=true OPEN_AI_API_KEY=... TAVILY_API_KEY=... ./mvnw test -Dtest=NewsCheckEvalIT</pre>
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "RUN_EVALS", matches = "true")
class NewsCheckEvalIT {

    static final String ARTICLE = """
            Leaders look back on famous words. In his 1961 inaugural address, President John F. Kennedy said, "Ask not \
            what your country can do for you, ask what you can do for your country." Mahatma Gandhi famously said, "Be \
            the change you wish to see in the world." The Eiffel Tower, completed in 1889, stands in Paris. Today the \
            world's population is 7 billion people.""";

    @Autowired
    private NewsCheckService service;
    @Autowired
    private JsonMapper jsonMapper;

    @Test
    void knownAnswers() throws Exception {
        NewsCheck r = service.check(ARTICLE, VerificationProgress.NONE);
        Path out = Path.of("target", "news-eval", "result.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(r));
        r.claims().forEach(c -> System.out.println("EVAL " + c.type() + " " + c.verdict() + " quote=" + c.quoteStatus()
                + " context=" + c.contextIssue() + " conflict=" + c.sourcesConflict() + " | " + c.claim()));
        if (r.videos() != null) {
            System.out.println("EVAL videos searched=" + r.videos().searched() + " limitations=" + r.videos().limitations());
            r.videos().claims().forEach(cv -> cv.videos().forEach(v -> System.out.println("EVAL video " + cv.claimId() + " "
                    + v.stance() + " " + v.kind() + " " + v.channel() + " | " + v.title() + " | quote=" + v.quote()
                    + " at=" + v.relevantAt() + " earliest=" + v.earliestFound())));
        }

        NewsClaim kennedy = find(r, "ask not");
        assertThat(kennedy.quoteStatus()).isEqualTo(QuoteStatus.FOUND_VERBATIM);
        NewsClaim population = find(r, "7 billion");
        assertThat(population.verdict()).isNotEqualTo(Verdict.SUPPORTED);
        // Every excerpt shown is the source's own words (enforced in code); spot-check non-null ids.
        assertThat(r.claims()).allSatisfy(c -> {
            if (c.supporting() != null) {
                assertThat(r.sources()).anySatisfy(s -> assertThat(s.id()).isEqualTo(c.supporting().sourceId()));
            }
        });
    }

    private static NewsClaim find(NewsCheck r, String text) {
        return r.claims().stream().filter(c -> c.articleQuote().toLowerCase(Locale.ROOT).contains(text)).findFirst()
                .orElseThrow(() -> new AssertionError("no claim containing " + text));
    }
}
