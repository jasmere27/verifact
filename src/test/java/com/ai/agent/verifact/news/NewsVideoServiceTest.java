package com.ai.agent.verifact.news;

import com.ai.agent.verifact.ai.ImageInput;
import com.ai.agent.verifact.ai.LlmClient;
import com.ai.agent.verifact.news.NewsOutputs.VideoNote;
import com.ai.agent.verifact.news.NewsOutputs.VideoReview;
import com.ai.agent.verifact.news.NewsVideos.Stance;
import com.ai.agent.verifact.video.FoundVideo;
import com.ai.agent.verifact.video.VideoSearch;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class NewsVideoServiceTest {

    static final String CLAIM = "Floodwaters reached the second floor of Marikina City Hall on 24 July 2026.";

    static FoundVideo video(String id, String title, String date, String description) {
        return new FoundVideo("youtube", id, "https://www.youtube.com/watch?v=" + id, title, "News Channel", "UC1",
                date == null ? null : Instant.parse(date), 185, description, "https://i.ytimg.com/vi/" + id + "/hqdefault.jpg");
    }

    static class FakeSearch implements VideoSearch {
        boolean enabled = true;
        final List<String> queries = Collections.synchronizedList(new ArrayList<>());
        Function<String, List<FoundVideo>> answer = q -> List.of();

        @Override
        public boolean enabled() {
            return enabled;
        }

        @Override
        public List<FoundVideo> search(String query, int max) {
            queries.add(query);
            return answer.apply(query);
        }
    }

    static class FakeLlm implements LlmClient {
        final List<String> users = Collections.synchronizedList(new ArrayList<>());
        final List<String> systems = Collections.synchronizedList(new ArrayList<>());
        VideoReview review = new VideoReview(List.of());

        @Override
        @SuppressWarnings("unchecked")
        public <T> T generate(String s, String u, Class<T> type) {
            systems.add(s);
            users.add(u);
            return (T) review;
        }

        @Override
        public <T> T generateWithImage(String s, String u, ImageInput i, Class<T> type) {
            throw new UnsupportedOperationException();
        }
    }

    private final FakeSearch search = new FakeSearch();
    private final FakeLlm llm = new FakeLlm();
    private final NewsVideoService service = new NewsVideoService(llm, search, 2);

    @Test
    void videosComeFromSearchAndEveryQuoteClaimAndChapterIsTheVideosOwn() {
        search.answer = q -> List.of(
                video("aaaaaaaaaaa", "Marikina City Hall flooded up to second floor", "2026-07-24T10:00:00Z",
                        "Floodwaters reached the second floor of Marikina City Hall on Thursday.\n0:00 Intro\n1:15 City hall interior\n"),
                video("bbbbbbbbbbb", "Marikina river update", "2026-07-23T08:00:00Z", "The river reached 18 metres; City Hall stayed dry."),
                video("ccccccccccc", "Cooking adobo", "2026-01-01T00:00:00Z", "A recipe."));
        llm.review = new VideoReview(List.of(
                new VideoNote("V1", true, "SUPPORTS", "NEWS_REPORT", "The description says the water reached City Hall's second floor.",
                        "Floodwaters reached the second floor of Marikina City Hall", List.of("Floodwaters reached the second floor of Marikina City Hall on Thursday", "Invented claim not in the text at all"), "City hall interior"),
                new VideoNote("V2", true, "CONTRADICTS", "NEWS_REPORT", "The description says City Hall stayed dry.",
                        "City Hall stayed dry", List.of(), "Made-up chapter"),
                new VideoNote("V3", true, "SUPPORTS", "OTHER", "It proves the flood.", "the flood destroyed everything in Marikina", List.of(), null),
                new VideoNote("V9", true, "SUPPORTS", "OTHER", "Unknown video.", "whatever", List.of(), null)));

        NewsVideos v = service.find(List.of(new NewsVideoService.ClaimInput("C1", CLAIM),
                new NewsVideoService.ClaimInput("C2", "Another claim about Marikina."),
                new NewsVideoService.ClaimInput("C3", "A third claim.")));

        assertThat(search.queries).hasSize(2); // limited to the configured number of claims
        assertThat(v.limitations()).anySatisfy(l -> assertThat(l).contains("first 2 claims"));
        List<NewsVideos.SupportingVideo> c1 = v.claims().get(0).videos();
        assertThat(c1).extracting(NewsVideos.SupportingVideo::videoId).containsExactly("aaaaaaaaaaa", "bbbbbbbbbbb");
        NewsVideos.SupportingVideo first = c1.get(0);
        assertThat(first.stance()).isEqualTo(Stance.SUPPORTS);
        assertThat(first.claimsMade()).containsExactly("Floodwaters reached the second floor of Marikina City Hall on Thursday");
        assertThat(first.relevantAt()).isEqualTo(new NewsVideos.Chapter(75, "City hall interior"));
        assertThat(first.keyFrames()).containsExactly("https://i.ytimg.com/vi/aaaaaaaaaaa/1.jpg",
                "https://i.ytimg.com/vi/aaaaaaaaaaa/2.jpg", "https://i.ytimg.com/vi/aaaaaaaaaaa/3.jpg");
        assertThat(c1.get(1).stance()).isEqualTo(Stance.CONTRADICTS);
        assertThat(c1.get(1).relevantAt()).isNull(); // not one of its chapters
        // Earliest among the relevant results is flagged, and only that one.
        assertThat(c1).extracting(NewsVideos.SupportingVideo::earliestFound).containsExactly(false, true);
        assertThat(llm.users).allSatisfy(u -> assertThat(u).containsPattern("<<<DATA_[0-9a-f]{32}>>>"));
        assertThat(llm.systems.get(0)).contains("UNTRUSTED").contains("NOT seen the videos");
    }

    @Test
    void anOutageOrQuotaNeverFailsTheCheck() {
        search.answer = q -> {
            throw new VideoSearch.VideoSearchException("YouTube returned HTTP 403", true);
        };
        NewsVideos v = service.find(List.of(new NewsVideoService.ClaimInput("C1", CLAIM)));
        assertThat(v.claims()).isEmpty();
        assertThat(v.limitations()).anySatisfy(l -> assertThat(l).contains("limit was reached"));

        search.enabled = false;
        assertThat(service.find(List.of(new NewsVideoService.ClaimInput("C1", CLAIM))).searched()).isFalse();
    }

    @Test
    void chaptersAreParsedFromTheDescription() {
        assertThat(NewsVideoService.chapters("Intro text\n00:00 Start\n2:05 - Mayor speaks\n[1:02:03] Q&A\nnot 12:00pm a chapter"))
                .containsExactly(new NewsVideos.Chapter(0, "Start"), new NewsVideos.Chapter(125, "Mayor speaks"),
                        new NewsVideos.Chapter(3723, "Q&A"));
    }
}
