package com.ai.agent.verifact.video;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.hamcrest.Matchers.startsWith;

class YouTubeVideoSearchTest {

    @Test
    void searchesThenFetchesDetailsInRelevanceOrder() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        YouTubeVideoSearch yt = new YouTubeVideoSearch(builder, JsonMapper.builder().build(), "k");
        server.expect(requestTo(startsWith(YouTubeVideoSearch.API + "/search"))).andExpect(queryParam("type", "video"))
                .andRespond(withSuccess("""
                        {"items":[{"id":{"videoId":"bbbbbbbbbbb"}},{"id":{"videoId":"aaaaaaaaaaa"}},{"id":{"videoId":"bad id"}}]}""",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith(YouTubeVideoSearch.API + "/videos")))
                .andRespond(withSuccess("""
                        {"items":[
                          {"id":"aaaaaaaaaaa","snippet":{"title":"A","channelTitle":"Ch","channelId":"UC1","publishedAt":"2026-07-24T10:00:00Z",
                           "description":"desc","thumbnails":{"high":{"url":"https://i.ytimg.com/vi/aaaaaaaaaaa/hqdefault.jpg"}}},
                           "contentDetails":{"duration":"PT3M5S"}},
                          {"id":"bbbbbbbbbbb","snippet":{"title":"B","channelTitle":"Ch","publishedAt":"2026-07-23T10:00:00Z",
                           "thumbnails":{"high":{"url":"https://evil.example/x.jpg"}}},"contentDetails":{"duration":"PT1H"}}]}""",
                        MediaType.APPLICATION_JSON));

        List<FoundVideo> found = yt.search("flood", 5);

        assertThat(found).extracting(FoundVideo::videoId).containsExactly("bbbbbbbbbbb", "aaaaaaaaaaa");
        assertThat(found.get(1).durationSeconds()).isEqualTo(185);
        assertThat(found.get(1).url()).isEqualTo("https://www.youtube.com/watch?v=aaaaaaaaaaa");
        // Thumbnails only from YouTube's image host.
        assertThat(found.get(0).thumbnailUrl()).isEqualTo("https://i.ytimg.com/vi/bbbbbbbbbbb/hqdefault.jpg");
        server.verify();
    }

    @Test
    void quotaErrorsAreRecognisedAndTheKeyNeverAppearsInMessages() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        YouTubeVideoSearch yt = new YouTubeVideoSearch(builder, JsonMapper.builder().build(), "secret-key-123");
        server.expect(requestTo(startsWith(YouTubeVideoSearch.API + "/search")))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"errors\":[{\"reason\":\"quotaExceeded\"}]}}"));

        assertThatThrownBy(() -> yt.search("flood", 5)).isInstanceOfSatisfying(VideoSearch.VideoSearchException.class, e -> {
            assertThat(e.quotaExceeded()).isTrue();
            assertThat(e.getMessage()).doesNotContain("secret-key-123");
        });
        assertThat(new YouTubeVideoSearch(RestClient.builder(), JsonMapper.builder().build(), " ").enabled()).isFalse();
    }
}
