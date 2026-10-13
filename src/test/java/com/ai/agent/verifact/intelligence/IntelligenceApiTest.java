package com.ai.agent.verifact.intelligence;

import com.ai.agent.verifact.account.SupabaseAdmin;
import com.ai.agent.verifact.ai.LlmClient;
import com.ai.agent.verifact.core.assess.Verdict;
import com.ai.agent.verifact.core.provenance.SourceExcerpt;
import com.ai.agent.verifact.evidence.Evidence;
import com.ai.agent.verifact.evidence.SourceType;
import com.ai.agent.verifact.intelligence.billing.PlanCatalog;
import com.ai.agent.verifact.intelligence.billing.SubscriptionService;
import com.ai.agent.verifact.intelligence.billing.SubscriptionStatus;
import com.ai.agent.verifact.intelligence.monitoring.MentionClaimExtractor;
import com.ai.agent.verifact.intelligence.responses.ResponseStudioService;
import com.ai.agent.verifact.news.NewsCheck;
import com.ai.agent.verifact.news.NewsCheckService;
import com.ai.agent.verifact.search.SearchProvider;
import com.ai.agent.verifact.search.SearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * VeriFact Intelligence end to end over HTTP against the real schema (Flyway on H2): plans and quotas, monitoring
 * runs with de-duplication, incidents, investigations with versions, Response Studio, reports, and tenant isolation.
 * Search, the model and the evidence pipeline are fakes; nothing calls a real provider.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IntelligenceApiTest {

    static final String BRAND = "Acme Foods";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JsonMapper json;
    @Autowired
    private SubscriptionService subscriptions;
    @Autowired
    private PlanCatalog catalog;
    @MockitoBean
    private SearchProvider search;
    @MockitoBean
    private LlmClient llm;
    @MockitoBean
    private NewsCheckService checks;
    @MockitoBean
    private SupabaseAdmin admin;

    private UUID alice;
    private UUID bob;
    private String aliceEmail;

    @BeforeEach
    void users() {
        alice = UUID.randomUUID();
        bob = UUID.randomUUID();
        aliceEmail = "alice-" + alice + "@agency.example";
        when(search.name()).thenReturn("fake");
    }

    // ---------------------------------------------------------------- helpers

    private RequestPostProcessor as(UUID user) {
        String email = user.equals(alice) ? aliceEmail : "user-" + user + "@example.com";
        return jwt().jwt(j -> j.subject(user.toString()).claim("email", email));
    }

    private JsonNode call(MockHttpServletRequestBuilder req, UUID user, Object body, int expected) throws Exception {
        if (body != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        String out = mvc.perform(req.with(as(user))).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return out.isBlank() ? null : json.readTree(out);
    }

    private String workspace(UUID owner, String name) throws Exception {
        return call(post("/api/v2/me/orgs"), owner, Map.of("name", name), 200).path("id").asString();
    }

    private String trialWorkspace(UUID owner) throws Exception {
        String org = workspace(owner, "Agency " + owner);
        call(post("/api/v2/me/orgs/" + org + "/billing/trial"), owner, null, 200);
        return org;
    }

    private Map<String, Object> project(String name) {
        return Map.ofEntries(Map.entry("name", name), Map.entry("brand", BRAND), Map.entry("aliases", List.of("Acme")),
                Map.entry("keywords", List.of("recall")), Map.entry("topics", List.of("salmonella")),
                Map.entry("excludedTerms", List.of("Acme Anvils")), Map.entry("webSearch", true), Map.entry("newsSearch", false),
                Map.entry("lookbackDays", 7), Map.entry("alertThreshold", "HIGH"), Map.entry("checkIntervalHours", 24));
    }

    private JsonNode waitFor(String path, UUID user, String field, String notValue) throws Exception {
        JsonNode n = null;
        for (int i = 0; i < 100; i++) {
            n = call(get(path), user, null, 200);
            JsonNode target = n.isArray() ? n.get(0) : n;
            String v = field.contains(".") ? target.path(field.split("\\.")[0]).path(field.split("\\.")[1]).asString("")
                    : target.path(field).asString("");
            if (!v.equals(notValue)) {
                return n;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Timed out waiting for " + path + " " + field + " to leave " + notValue + ": " + n);
    }

    /** The model's claim extraction: flags the recall mention, quoting it verbatim. */
    private void modelFlagsRecalls() {
        when(llm.generateQuick(anyString(), anyString(), eq(MentionClaimExtractor.Output.class))).thenAnswer(inv -> {
            String user = inv.getArgument(1);
            List<MentionClaimExtractor.Item> items = new ArrayList<>();
            Matcher m = Pattern.compile("([0-9a-f-]{36}) \\| ([^\\n]*)").matcher(user);
            while (m.find()) {
                if (m.group(2).contains("recall")) {
                    items.add(new MentionClaimExtractor.Item(m.group(1), true, "Acme Foods is reported to be recalling peanut butter over salmonella.",
                            "recalls peanut butter after salmonella", "HIGH", "product safety"));
                }
            }
            return new MentionClaimExtractor.Output(items);
        });
    }

    private static SearchResult result(String url, String title, String snippet) {
        return new SearchResult(title, url, snippet, "2026-10-12");
    }

    private static NewsCheck check(Verdict verdict, List<Evidence> sources, SourceExcerpt supporting, SourceExcerpt contradicting) {
        NewsCheck.NewsClaim claim = new NewsCheck.NewsClaim("C1", NewsCheck.ClaimType.FACT, "Acme Foods recalls peanut butter after salmonella",
                "Acme Foods is recalling peanut butter over salmonella.", null, null, verdict, supporting, contradicting,
                NewsCheck.ContextIssue.NONE, NewsCheck.QuoteStatus.NOT_A_QUOTE, null, false, "Explanation from the excerpts.");
        return new NewsCheck(UUID.randomUUID(), Instant.now(), null, null, null, List.of(claim), sources, Map.of(verdict, 1), List.of(),
                "notice", "fake", 1000, null);
    }

    private static Evidence evidence(String id, String url, String title, String snippet) {
        return new Evidence(id, url, url.replaceAll("https://([^/]+)/.*", "$1"), title, snippet, "2026-10-10", Instant.now(), SourceType.NEWS);
    }

    // ---------------------------------------------------------------- tests

    @Test
    void signedOutCallsAreRefusedAndThePriceListIsPublic() throws Exception {
        mvc.perform(get("/api/v2/me/orgs/" + UUID.randomUUID() + "/overview")).andExpect(status().isUnauthorized());
        String plans = mvc.perform(get("/api/v2/plans")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(plans).findValuesAsString("id")).containsExactly("monitor", "intelligence", "business");
    }

    @Test
    void aWorkspaceNeedsAPlanAndATrialUnlocksMonitoringOncePerPerson() throws Exception {
        String org = workspace(alice, "Northwind PR");
        JsonNode refused = call(post("/api/v2/me/orgs/" + org + "/projects"), alice, project("Acme"), 402);
        assertThat(refused.path("detail").asString()).contains("no plan");

        JsonNode trial = call(post("/api/v2/me/orgs/" + org + "/billing/trial"), alice, null, 200);
        assertThat(trial.path("status").asString()).isEqualTo("TRIALING");
        assertThat(trial.path("plan").path("id").asString()).isEqualTo("intelligence");
        call(post("/api/v2/me/orgs/" + org + "/projects"), alice, project("Acme"), 200);

        // A second workspace doesn't get a second trial.
        String second = workspace(alice, "Second agency");
        call(post("/api/v2/me/orgs/" + second + "/billing/trial"), alice, null, 409);
    }

    @Test
    void anotherWorkspaceCannotSeeOrChangeAnything() throws Exception {
        String aliceOrg = trialWorkspace(alice);
        String bobOrg = trialWorkspace(bob);
        String projectId = call(post("/api/v2/me/orgs/" + aliceOrg + "/projects"), alice, project("Acme"), 200).path("id").asString();
        String incidentId = call(post("/api/v2/me/orgs/" + aliceOrg + "/incidents"), alice,
                Map.of("claim", "Acme Foods is said to hide salmonella test results."), 200).path("summary").path("id").asString();

        // Bob isn't a member of Alice's workspace: everything is "not found", never "forbidden" (ids can't be probed).
        call(get("/api/v2/me/orgs/" + aliceOrg + "/overview"), bob, null, 404);
        call(get("/api/v2/me/orgs/" + aliceOrg + "/projects/" + projectId), bob, null, 404);
        call(get("/api/v2/me/orgs/" + aliceOrg + "/incidents/" + incidentId), bob, null, 404);
        call(patch("/api/v2/me/orgs/" + aliceOrg + "/incidents/" + incidentId), bob, Map.of("status", "RESOLVED"), 404);
        call(post("/api/v2/me/orgs/" + aliceOrg + "/billing/checkout"), bob, Map.of("planId", "business"), 404);

        // Alice's ids through Bob's own workspace: not found either.
        call(get("/api/v2/me/orgs/" + bobOrg + "/projects/" + projectId), bob, null, 404);
        call(get("/api/v2/me/orgs/" + bobOrg + "/incidents/" + incidentId), bob, null, 404);
        call(post("/api/v2/me/orgs/" + bobOrg + "/projects/" + projectId + "/run"), bob, null, 404);
        call(post("/api/v2/me/orgs/" + bobOrg + "/investigations"), bob, Map.of("input", "x", "incidentId", incidentId), 404);
        assertThat(call(get("/api/v2/me/orgs/" + bobOrg + "/incidents"), bob, null, 200).path("total").asLong()).isZero();

        // Still intact for Alice.
        assertThat(call(get("/api/v2/me/orgs/" + aliceOrg + "/incidents/" + incidentId), alice, null, 200)
                .path("summary").path("status").asString()).isEqualTo("NEW");
    }

    @Test
    void membersSeeTheWorkspaceButOnlyAdminsManageProjectsAndOnlyOwnersBill() throws Exception {
        String org = trialWorkspace(alice);
        // Bob must have an account before he can be added.
        call(post("/api/v2/me/orgs"), bob, Map.of("name", "Bob's own"), 200);
        call(post("/api/v2/me/orgs/" + org + "/members"), alice, Map.of("email", "user-" + bob + "@example.com", "role", "MEMBER"), 200);

        call(get("/api/v2/me/orgs/" + org + "/overview"), bob, null, 200);
        call(post("/api/v2/me/orgs/" + org + "/projects"), bob, project("Acme"), 403);
        call(post("/api/v2/me/orgs/" + org + "/billing/checkout"), bob, Map.of("planId", "business"), 403);
        call(get("/api/v2/me/orgs/" + org + "/audit"), bob, null, 403);
        // Bob can't remove the only owner, and the owner can't leave a workspace ownerless.
        call(delete("/api/v2/me/orgs/" + org + "/members/" + alice), bob, null, 403);
        call(delete("/api/v2/me/orgs/" + org + "/members/" + alice), alice, null, 409);
        assertThat(call(get("/api/v2/me/orgs/" + org + "/audit"), alice, null, 200).findValuesAsString("action")).contains("member.added");
    }

    @Test
    void monitoringStoresDeduplicatedMentionsAndOpensOneIncident() throws Exception {
        String org = trialWorkspace(alice);
        String projectId = call(post("/api/v2/me/orgs/" + org + "/projects"), alice, project("Acme"), 200).path("id").asString();
        when(search.searchRecent(anyString(), anyBoolean(), anyInt())).thenReturn(List.of(
                result("https://news.example/acme-recall", "Acme Foods recalls peanut butter after salmonella fears",
                        "The company Acme Foods recalls peanut butter after salmonella was found."),
                result("https://news.example/acme-recall?utm_source=x", "Acme Foods recalls peanut butter after salmonella fears",
                        "The company Acme Foods recalls peanut butter after salmonella was found."),
                result("https://copy.example/story", "Acme Foods recalls peanut butter after salmonella fears",
                        "The company Acme Foods recalls peanut butter after salmonella was found."),
                result("https://other.example/anvils", "Acme Anvils launches a new anvil", "Acme Anvils and Acme Foods share a name."),
                result("https://other.example/unrelated", "Weather today", "Sunny with clouds.")));
        modelFlagsRecalls();

        call(post("/api/v2/me/orgs/" + org + "/projects/" + projectId + "/run"), alice, null, 200);
        JsonNode runs = waitFor("/api/v2/me/orgs/" + org + "/runs?projectId=" + projectId, alice, "status", "RUNNING");
        JsonNode run = runs.get(0);
        assertThat(run.path("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(run.path("newMentions").asInt()).isEqualTo(2); // the tracking-parameter duplicate and the excluded/unrelated items are dropped
        assertThat(run.path("newIncidents").asInt()).isEqualTo(1);

        JsonNode mentions = call(get("/api/v2/me/orgs/" + org + "/mentions"), alice, null, 200);
        assertThat(mentions.path("total").asLong()).isEqualTo(2);
        JsonNode incidents = call(get("/api/v2/me/orgs/" + org + "/incidents?status=OPEN"), alice, null, 200);
        assertThat(incidents.path("total").asLong()).isEqualTo(1);
        JsonNode incident = incidents.path("items").get(0);
        assertThat(incident.path("mentionCount").asInt()).isEqualTo(1);
        JsonNode detail = call(get("/api/v2/me/orgs/" + org + "/incidents/" + incident.path("id").asString()), alice, null, 200);
        assertThat(detail.path("risk").path("factors").findValuesAsString("name")).contains("Allegation severity", "Observed spread", "Evidence status");
        assertThat(detail.path("risk").path("method").asString()).contains("not a probability");

        // A second run finds the same pages: nothing new, no second incident.
        call(post("/api/v2/me/orgs/" + org + "/projects/" + projectId + "/run"), alice, null, 200);
        Thread.sleep(50);
        JsonNode again = waitFor("/api/v2/me/orgs/" + org + "/runs?projectId=" + projectId, alice, "status", "RUNNING");
        assertThat(again.get(0).path("newMentions").asInt()).isZero();
        assertThat(call(get("/api/v2/me/orgs/" + org + "/incidents"), alice, null, 200).path("total").asLong()).isEqualTo(1);

        JsonNode overview = call(get("/api/v2/me/orgs/" + org + "/overview"), alice, null, 200);
        assertThat(overview.path("newMentions7d").asInt()).isEqualTo(2);
        assertThat(overview.path("onboarding").asBoolean()).isFalse();
        assertThat(overview.path("sourceHealth").get(0).path("health").asString()).isEqualTo("HEALTHY");
    }

    @Test
    void aFailingSourceIsRecordedNotHidden() throws Exception {
        String org = trialWorkspace(alice);
        String projectId = call(post("/api/v2/me/orgs/" + org + "/projects"), alice, project("Acme"), 200).path("id").asString();
        when(search.searchRecent(anyString(), anyBoolean(), anyInt()))
                .thenThrow(new com.ai.agent.verifact.search.SearchUnavailableException("HTTP 503"));

        call(post("/api/v2/me/orgs/" + org + "/projects/" + projectId + "/run"), alice, null, 200);
        JsonNode run = waitFor("/api/v2/me/orgs/" + org + "/runs?projectId=" + projectId, alice, "status", "RUNNING").get(0);
        assertThat(run.path("status").asString()).isEqualTo("FAILED");
        assertThat(run.path("sources").get(0).path("status").asString()).isEqualTo("FAILED");
        JsonNode project = call(get("/api/v2/me/orgs/" + org + "/projects/" + projectId), alice, null, 200);
        assertThat(project.path("consecutiveFailures").asInt()).isEqualTo(1);
        assertThat(project.path("health").asString()).isEqualTo("DEGRADED");
        assertThat(call(get("/api/v2/me/orgs/" + org + "/mentions"), alice, null, 200).path("total").asLong()).isZero();
    }

    @Test
    void incidentsMoveThroughTheWorkflowWithAnActivityHistory() throws Exception {
        String org = trialWorkspace(alice);
        String id = call(post("/api/v2/me/orgs/" + org + "/incidents"), alice,
                Map.of("claim", "Acme Foods is said to hide salmonella test results.", "severity", "HIGH",
                        "discoveryUrl", "https://forum.example/thread/1"), 200).path("summary").path("id").asString();
        String path = "/api/v2/me/orgs/" + org + "/incidents/" + id;

        call(patch(path), alice, Map.of("status", "SOMETHING"), 400);
        call(patch(path), alice, Map.of("ownerId", UUID.randomUUID().toString()), 400); // not a member
        call(patch(path), alice, Map.of("status", "INVESTIGATING", "ownerId", alice.toString()), 200);
        call(patch(path), alice, Map.of("priority", "CRITICAL"), 200);
        JsonNode d = call(post(path + "/notes"), alice, Map.of("note", "Legal has been told."), 200);
        assertThat(d.path("summary").path("status").asString()).isEqualTo("INVESTIGATING");
        assertThat(d.path("summary").path("priority").asString()).isEqualTo("CRITICAL");
        assertThat(d.path("priorityOverride").asBoolean()).isTrue();
        assertThat(d.path("events").findValuesAsString("kind")).containsSequence("OPENED", "STATUS", "ASSIGNED", "PRIORITY", "NOTE");

        call(patch(path), alice, Map.of("status", "RESOLVED"), 200);
        assertThat(call(get("/api/v2/me/orgs/" + org + "/incidents?status=OPEN"), alice, null, 200).path("total").asLong()).isZero();
        assertThat(call(get("/api/v2/me/orgs/" + org + "/incidents?q=salmonella"), alice, null, 200).path("total").asLong()).isEqualTo(1);
    }

    @Test
    void investigationsKeepVersionsEvidenceAndFeedResponseStudio() throws Exception {
        String org = trialWorkspace(alice);
        String incident = call(post("/api/v2/me/orgs/" + org + "/incidents"), alice,
                Map.of("claim", "Acme Foods is recalling peanut butter over salmonella."), 200).path("summary").path("id").asString();
        Evidence fda = evidence("E1", "https://fda.example/notice", "No recall issued for Acme Foods", "There is no active recall of Acme Foods peanut butter.");
        when(checks.check(anyString(), any(), eq(false))).thenReturn(
                check(Verdict.CONTRADICTED, List.of(fda), null, new SourceExcerpt("E1", "There is no active recall of Acme Foods peanut butter.")));

        String id = call(post("/api/v2/me/orgs/" + org + "/investigations"), alice,
                Map.of("input", "Acme Foods is recalling peanut butter over salmonella.", "incidentId", incident), 200).path("summary").path("id").asString();
        String path = "/api/v2/me/orgs/" + org + "/investigations/" + id;
        JsonNode v1 = waitFor(path, alice, "summary.status", "RUNNING");
        assertThat(v1.path("summary").path("status").asString()).isEqualTo("COMPLETED");
        assertThat(v1.path("result").path("claims").get(0).path("assessment").asString()).isEqualTo("CONTRADICTED");
        assertThat(v1.path("result").path("sources").get(0).path("direction").asString()).isEqualTo("CONTRADICTS");
        JsonNode evidence = call(get("/api/v2/me/orgs/" + org + "/evidence"), alice, null, 200);
        assertThat(evidence.path("total").asLong()).isEqualTo(1);
        assertThat(evidence.path("items").get(0).path("excerpt").asString()).isEqualTo("There is no active recall of Acme Foods peanut butter.");
        JsonNode inc = call(get("/api/v2/me/orgs/" + org + "/incidents/" + incident), alice, null, 200);
        assertThat(inc.path("risk").path("inputs").path("evidence").asString()).isEqualTo("CONTRADICTED");

        // New evidence: the earlier assessment is kept and the change recorded.
        Evidence blog = evidence("E2", "https://blog.example/post", "Rumours about Acme", "People online say Acme Foods may recall products.");
        when(checks.check(anyString(), any(), eq(false))).thenReturn(check(Verdict.INSUFFICIENT_EVIDENCE, List.of(blog), null, null));
        call(post(path + "/rerun"), alice, null, 200);
        JsonNode v2 = waitFor(path, alice, "summary.status", "RUNNING");
        assertThat(v2.path("summary").path("version").asInt()).isEqualTo(2);
        assertThat(v2.path("changes").path("claims").get(0).path("before").asString()).isEqualTo("CONTRADICTED");
        assertThat(v2.path("changes").path("claims").get(0).path("after").asString()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(v2.path("versions")).hasSize(2);
        assertThat(call(get(path + "/versions/1"), alice, null, 200).path("claims").get(0).path("assessment").asString()).isEqualTo("CONTRADICTED");

        // Response Studio: unknown citations and ungrounded "facts" are removed; the warning comes from the assessments.
        when(llm.generate(anyString(), anyString(), eq(ResponseStudioService.Output.class))).thenReturn(new ResponseStudioService.Output(
                "Holding statement", "We are aware of posts about our peanut butter [E2]. We are looking into it [E9]. Over 4000 jars were affected.",
                List.of(new ResponseStudioService.Fact("People online say Acme Foods may recall products.", List.of("E2")),
                        new ResponseStudioService.Fact("Acme sold 9000 jars.", List.of("E2")),
                        new ResponseStudioService.Fact("An uncited fact.", List.of())),
                List.of("Whether a recall exists."), List.of("Contact the regulator.")));
        JsonNode draft = call(post("/api/v2/me/orgs/" + org + "/responses"), alice,
                Map.of("investigationId", id, "kind", "HOLDING_STATEMENT"), 200);
        assertThat(draft.path("body").asString()).contains("[E2]").doesNotContain("[E9]").doesNotContain("4000");
        assertThat(draft.path("meta").path("keyFacts")).hasSize(1);
        assertThat(draft.path("meta").path("evidenceWarning").asString()).contains("inadequate for a definitive response");
        assertThat(draft.path("summary").path("status").asString()).isEqualTo("DRAFT");

        String draftPath = "/api/v2/me/orgs/" + org + "/responses/" + draft.path("summary").path("id").asString();
        call(post(draftPath + "/status"), alice, Map.of("status", "APPROVED"), 200);
        JsonNode edited = call(put(draftPath), alice, Map.of("body", "We are aware of the posts and are looking into them [E2].", "note", "Tightened"), 200);
        assertThat(edited.path("summary").path("status").asString()).isEqualTo("DRAFT"); // edits need a fresh approval
        assertThat(edited.path("revisions")).hasSize(2);
        assertThat(call(get("/api/v2/me/orgs/" + org + "/incidents/" + incident), alice, null, 200).path("summary").path("status").asString())
                .isEqualTo("RESPONSE_DRAFTED");
    }

    @Test
    void planQuotasAreEnforcedOnTheServer() throws Exception {
        String org = workspace(alice, "Small brand");
        // The Monitor plan (as a verified billing event would set it): no Response Studio, two reports a month.
        subscriptions.applyProviderState(UUID.fromString(org), "paddle", "ctm_1", "sub_" + UUID.randomUUID().toString().replace("-", ""),
                catalog.find("monitor").orElseThrow(), SubscriptionStatus.ACTIVE, Instant.now().plusSeconds(86_400), false, Instant.now());

        call(post("/api/v2/me/orgs/" + org + "/projects"), alice, project("One"), 200);
        JsonNode second = call(post("/api/v2/me/orgs/" + org + "/projects"), alice, project("Two"), 403);
        assertThat(second.path("detail").asString()).contains("includes 1 monitoring project");
        Map<String, Object> tooFrequent = new java.util.HashMap<>(project("Fast"));
        tooFrequent.put("checkIntervalHours", 2);
        call(put("/api/v2/me/orgs/" + org + "/projects/" + call(get("/api/v2/me/orgs/" + org + "/projects"), alice, null, 200).get(0).path("id").asString()),
                alice, tooFrequent, 403);

        call(post("/api/v2/me/orgs/" + org + "/reports"), alice, Map.of("days", 7), 200);
        call(post("/api/v2/me/orgs/" + org + "/reports"), alice, Map.of("days", 7), 200);
        JsonNode third = call(post("/api/v2/me/orgs/" + org + "/reports"), alice, Map.of("days", 7), 429);
        assertThat(third.path("detail").asString()).contains("used all 2 reports");

        JsonNode billing = call(get("/api/v2/me/orgs/" + org + "/billing"), alice, null, 200);
        assertThat(billing.path("usage").path("features").findValuesAsString("feature")).contains("REPORT");
        assertThat(billing.path("onlinePayment").asBoolean()).isFalse();
        // Without provider credentials, checkout says so instead of pretending.
        call(post("/api/v2/me/orgs/" + org + "/billing/checkout"), alice, Map.of("planId", "business"), 503);
    }

    @Test
    void reportsAreBuiltFromWorkspaceDataAndExportAsPdf() throws Exception {
        String org = trialWorkspace(alice);
        call(post("/api/v2/me/orgs/" + org + "/incidents"), alice, Map.of("claim", "Acme Foods is said to hide salmonella test results.",
                "severity", "HIGH"), 200);
        JsonNode report = call(post("/api/v2/me/orgs/" + org + "/reports"), alice, Map.of("days", 30), 200);
        assertThat(report.path("incidents").path("opened").asInt()).isEqualTo(1);
        assertThat(report.path("executiveSummary").get(1).asString()).contains("1 incident was opened");
        assertThat(report.path("limitations").toString()).contains("Social media platforms are not monitored");

        byte[] pdf = mvc.perform(get("/api/v2/me/orgs/" + org + "/reports/" + report.path("id").asString() + "/pdf").with(as(alice)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 5, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        assertThat(call(get("/api/v2/me/orgs/" + org + "/reports"), alice, null, 200)).hasSize(1);
    }
}
