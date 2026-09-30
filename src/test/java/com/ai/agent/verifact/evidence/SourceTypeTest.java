package com.ai.agent.verifact.evidence;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class SourceTypeTest {

    @ParameterizedTest
    @CsvSource({
            "https://www.snopes.com/fact-check/x, FACT_CHECKER",
            "https://www.reuters.com/fact-check/abc, FACT_CHECKER",
            "https://factcheck.afp.com/doc.afp.com.123, FACT_CHECKER",
            "https://www.rappler.com/newsbreak/fact-check/x, FACT_CHECKER",
            "https://www.reuters.com/world/x, NEWS",
            "https://newsinfo.inquirer.net/123/x, NEWS",
            "https://www.gmanetwork.com/news/x, NEWS",
            "https://en.wikipedia.org/wiki/X, REFERENCE",
            "https://www.britannica.com/place/X, REFERENCE",
            "https://www.cdc.gov/x, GOVERNMENT",
            "https://doh.gov.ph/x, GOVERNMENT",
            "https://www.who.int/news, GOVERNMENT",
            "https://www.harvard.edu/x, ACADEMIC",
            "https://upd.edu.ph/x, ACADEMIC",
            "https://www.ox.ac.uk/x, ACADEMIC",
            "https://www.nature.com/articles/x, ACADEMIC",
            "https://www.facebook.com/page/posts/1, SOCIAL",
            "https://m.facebook.com/story, SOCIAL",
            "https://www.reddit.com/r/x, SOCIAL",
            "https://someone.blogspot.com/post, SOCIAL",
            "https://www.tiktok.com/@u/video/1, SOCIAL",
            "https://www.toureiffel.paris/en, OTHER",
    })
    void classifiesByUrl(String url, SourceType expected) {
        String host = Urls.domain(url);
        assertThat(SourceType.classify(url, host, Urls.registrableDomain(host))).isEqualTo(expected);
    }
}
