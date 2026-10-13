package com.ai.agent.verifact.intelligence.billing;

import com.ai.agent.verifact.account.AccountService;
import com.ai.agent.verifact.account.SignedInUser;
import com.ai.agent.verifact.account.SupabaseAdmin;
import com.ai.agent.verifact.intelligence.workspace.WorkspaceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Paddle webhooks: only correctly signed, fresh events change a subscription; each event is applied once; an
 * older event never overwrites a newer state.
 */
@SpringBootTest(properties = {"app.billing.paddle.api-key=test-key", "app.billing.paddle.webhook-secret=pdl_ntfset_test_secret",
        "app.billing.plans.business.provider-price-id=pri_business"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PaddleWebhookTest {

    static final String SECRET = "pdl_ntfset_test_secret";
    static final String PATH = "/api/v2/billing/webhooks/paddle";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private SubscriptionService subscriptions;
    @Autowired
    private AccountService accounts;
    @Autowired
    private WorkspaceService workspaces;
    @MockitoBean
    private SupabaseAdmin admin;

    private UUID org() {
        SignedInUser u = new SignedInUser(UUID.randomUUID(), "owner@example.com");
        accounts.ensureProvisioned(u);
        return workspaces.create(u.id(), "Paying agency");
    }

    static String event(String eventId, String status, UUID org, String occurredAt, String scheduledChange) {
        return """
                {"event_id":"%s","event_type":"subscription.updated","occurred_at":"%s","notification_id":"ntf_1",
                 "data":{"id":"sub_%s","status":"%s","customer_id":"ctm_1","custom_data":{"organization_id":"%s"},
                 "items":[{"price":{"id":"pri_business"},"quantity":1}],
                 "current_billing_period":{"starts_at":"2026-10-01T00:00:00Z","ends_at":"2026-11-01T00:00:00Z"},
                 "scheduled_change":%s}}""".formatted(eventId, occurredAt, org.toString().replace("-", ""), status, org, scheduledChange);
    }

    static String sign(String body, long ts) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "ts=" + ts + ";h1=" + HexFormat.of().formatHex(mac.doFinal((ts + ":" + body).getBytes(StandardCharsets.UTF_8)));
    }

    private org.springframework.test.web.servlet.ResultActions send(String body, String signature) throws Exception {
        return mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body).header("Paddle-Signature", signature));
    }

    @Test
    void aSignedEventActivatesThePlanOnceAndDuplicatesAreIgnored() throws Exception {
        UUID org = org();
        long now = Instant.now().getEpochSecond();
        String body = event("evt_" + UUID.randomUUID(), "active", org, "2026-10-01T00:00:00Z", "null");

        send(body, sign(body, now)).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("APPLIED"));
        SubscriptionService.Subscription s = subscriptions.current(org);
        assertThat(s.status()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(s.plan().id()).isEqualTo("business");
        assertThat(s.entitled()).isTrue();

        send(body, sign(body, now)).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("DUPLICATE"));
    }

    @Test
    void forgedStaleOrTamperedEventsChangeNothing() throws Exception {
        UUID org = org();
        long now = Instant.now().getEpochSecond();
        String body = event("evt_" + UUID.randomUUID(), "active", org, "2026-10-01T00:00:00Z", "null");

        send(body, "ts=" + now + ";h1=" + "0".repeat(64)).andExpect(status().isUnauthorized());
        send(body, sign(body, now - 3600)).andExpect(status().isUnauthorized()); // replayed outside the window
        send(body.replace("active", "trialing"), sign(body, now)).andExpect(status().isUnauthorized()); // body changed after signing
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        assertThat(subscriptions.current(org).status()).isEqualTo(SubscriptionStatus.NONE);

        // During secret rotation Paddle sends several h1 values; any valid one is accepted.
        String rotated = sign(body, now).replace(";h1=", ";h1=" + "f".repeat(64) + ";h1=");
        send(body, rotated).andExpect(status().isOk());
    }

    @Test
    void cancellationsAndOutOfOrderDeliveriesAreHandled() throws Exception {
        UUID org = org();
        long now = Instant.now().getEpochSecond();
        String active = event("evt_" + UUID.randomUUID(), "active", org, "2026-10-02T00:00:00Z", "{\"action\":\"cancel\",\"effective_at\":\"2026-11-01T00:00:00Z\"}");
        send(active, sign(active, now)).andExpect(status().isOk());
        assertThat(subscriptions.current(org).cancelAtPeriodEnd()).isTrue();

        String older = event("evt_" + UUID.randomUUID(), "past_due", org, "2026-09-01T00:00:00Z", "null");
        send(older, sign(older, now)).andExpect(status().isOk());
        assertThat(subscriptions.current(org).status()).as("an older event doesn't overwrite").isEqualTo(SubscriptionStatus.ACTIVE);

        String canceled = event("evt_" + UUID.randomUUID(), "canceled", org, "2026-11-01T00:00:01Z", "null");
        send(canceled, sign(canceled, now)).andExpect(status().isOk());
        assertThat(subscriptions.current(org).status()).isEqualTo(SubscriptionStatus.CANCELED);
        assertThat(subscriptions.current(org).entitled()).isFalse();
    }
}
