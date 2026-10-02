package com.ai.agent.verifact.research;

import com.ai.agent.verifact.account.AccountService;
import com.ai.agent.verifact.account.SupabaseAuthConfig;
import com.ai.agent.verifact.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Projects are signed-in only, and library keys (DOIs with "/") travel as query parameters. */
@WebMvcTest(ResearchProjectController.class)
@Import({SecurityConfig.class, SupabaseAuthConfig.class})
class ResearchProjectControllerTest {

    static final UUID USER = UUID.fromString("6f1c2a7e-5b9d-4c3e-8a1f-2d3b4c5e6f70");
    static final UUID PROJECT = UUID.fromString("11111111-2222-4333-8444-555555555555");

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private ResearchProjectStore projects;
    @MockitoBean
    private ResearchWorkspaceStore workspaces;
    @MockitoBean
    private AccountService accounts;
    @MockitoBean
    private DocumentExtractor extractor;
    @MockitoBean
    private DraftAnalysisService drafts;
    @MockitoBean
    private PaperAnalysisService papers;
    @MockitoBean
    private ResearchInsightsService insights;
    @MockitoBean
    private QuestionLinkService links;

    @Test
    void signedOutRequestsAreRefused() throws Exception {
        mockMvc.perform(get("/api/v2/me/projects")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v2/me/projects").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Some topic\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(projects);
    }

    @Test
    void theSignedInUsersOwnProjectsAreListedAndSourcesRemovedByDoi() throws Exception {
        when(projects.list(USER)).thenReturn(List.of());

        mockMvc.perform(get("/api/v2/me/projects").with(jwt().jwt(j -> j.subject(USER.toString())))).andExpect(status().isOk());
        verify(projects).list(USER);

        mockMvc.perform(delete("/api/v2/me/projects/" + PROJECT + "/library").param("key", "10.1234/abc.def")
                        .with(jwt().jwt(j -> j.subject(USER.toString()))))
                .andExpect(status().isOk());
        verify(projects).removeItem(USER, PROJECT, "10.1234/abc.def");

        mockMvc.perform(get("/api/v2/me/projects/not-a-uuid").with(jwt().jwt(j -> j.subject(USER.toString()))))
                .andExpect(status().isNotFound());
    }

    @Test
    void linkSuggestionsAreSignedInOnlyAndAReviewNeedsAnAnswer() throws Exception {
        mockMvc.perform(post("/api/v2/me/projects/" + PROJECT + "/links/suggest")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v2/me/projects/" + PROJECT + "/links/review").with(jwt().jwt(j -> j.subject(USER.toString())))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"key\":\"10.1/a\",\"questionId\":\"q1\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(links);

        mockMvc.perform(post("/api/v2/me/projects/" + PROJECT + "/links/review").with(jwt().jwt(j -> j.subject(USER.toString())))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"key\":\"10.1/a\",\"questionId\":\"q1\",\"accept\":false}"))
                .andExpect(status().isOk());
        verify(projects).reviewLink(USER, PROJECT, "10.1/a", "q1", false);
    }
}
