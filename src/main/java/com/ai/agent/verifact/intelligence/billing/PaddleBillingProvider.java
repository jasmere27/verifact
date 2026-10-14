package com.ai.agent.verifact.intelligence.billing;

import com.ai.agent.verifact.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Paddle Billing (merchant of record: Paddle collects VAT/sales tax and accepts sellers in the Philippines; see
 * docs/INTEGRATIONS.md). Checkout is a server-created transaction whose {@code custom_data} carries our
 * organisation id, so the browser can't choose which workspace a payment unlocks. Webhooks are verified with
 * {@code Paddle-Signature: ts=...;h1=...} = HMAC-SHA256(secret, ts + ":" + raw body), within a replay window.
 */
@Component
public class PaddleBillingProvider implements BillingProvider {

    private static final Logger log = LoggerFactory.getLogger(PaddleBillingProvider.class);
    static final String ORG_KEY = "organization_id";

    private final RestClient rest;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final String apiKey;
    private final String webhookSecret;
    private final Duration tolerance;

    public PaddleBillingProvider(RestClient.Builder builder, JsonMapper jsonMapper, Clock clock,
                                 @Value("${app.billing.paddle.api-base:https://sandbox-api.paddle.com}") String apiBase,
                                 @Value("${app.billing.paddle.api-key:}") String apiKey,
                                 @Value("${app.billing.paddle.webhook-secret:}") String webhookSecret,
                                 @Value("${app.billing.paddle.signature-tolerance-seconds:300}") long toleranceSeconds) {
        this.rest = builder.clone().baseUrl(apiBase).build();
        this.jsonMapper = jsonMapper;
        this.clock = clock;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.webhookSecret = webhookSecret == null ? "" : webhookSecret.trim();
        this.tolerance = Duration.ofSeconds(toleranceSeconds);
    }

    @Override
    public String name() {
        return "paddle";
    }

    @Override
    public boolean configured() {
        return !apiKey.isBlank() && !webhookSecret.isBlank();
    }

    @Override
    public String createCheckout(UUID orgId, Plan plan, String customerEmail, String customerId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", List.of(Map.of("price_id", plan.providerPriceId(), "quantity", 1)));
        body.put("custom_data", Map.of(ORG_KEY, orgId.toString()));
        if (customerId != null) {
            body.put("customer_id", customerId);
        }
        JsonNode data = call("POST", "/transactions", body).path("data");
        String url = data.path("checkout").path("url").asString("");
        if (url.isBlank()) {
            log.warn("Paddle transaction {} has no checkout URL: set a default payment link in the Paddle dashboard", data.path("id").asString(""));
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Checkout isn't available right now. Please contact us to subscribe.");
        }
        return url;
    }

    @Override
    public void changePlan(String subscriptionId, Plan plan) {
        call("PATCH", "/subscriptions/" + pathId(subscriptionId), Map.of(
                "items", List.of(Map.of("price_id", plan.providerPriceId(), "quantity", 1)),
                "proration_billing_mode", "prorated_immediately"));
    }

    @Override
    public void cancelAtPeriodEnd(String subscriptionId) {
        call("POST", "/subscriptions/" + pathId(subscriptionId) + "/cancel", Map.of("effective_from", "next_billing_period"));
    }

    @Override
    public Optional<String> customerPortalUrl(String customerId, String subscriptionId) {
        if (customerId == null) {
            return Optional.empty();
        }
        Map<String, Object> body = subscriptionId == null ? Map.of() : Map.of("subscription_ids", List.of(subscriptionId));
        String url = call("POST", "/customers/" + pathId(customerId) + "/portal-sessions", body)
                .path("data").path("urls").path("general").path("overview").asString("");
        return url.isBlank() ? Optional.empty() : Optional.of(url);
    }

    @Override
    public WebhookEvent parseVerifiedWebhook(Map<String, String> headers, byte[] rawBody) {
        verifySignature(header(headers, "Paddle-Signature"), rawBody);
        JsonNode root;
        try {
            root = jsonMapper.readTree(rawBody);
        } catch (RuntimeException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Malformed event.");
        }
        String eventId = root.path("event_id").asString("");
        String type = root.path("event_type").asString("");
        if (eventId.isBlank() || type.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Malformed event.");
        }
        Instant occurred = instant(root.path("occurred_at").asString(null));
        SubscriptionUpdate update = type.startsWith("subscription.") ? subscription(root.path("data")) : null;
        return new WebhookEvent(eventId, type, occurred == null ? clock.instant() : occurred, update);
    }

    void verifySignature(String header, byte[] rawBody) {
        if (header == null || header.isBlank() || webhookSecret.isBlank()) {
            throw new InvalidSignatureException("missing signature");
        }
        String ts = null;
        List<String> signatures = new java.util.ArrayList<>();
        for (String part : header.split(";")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            if (kv[0].equals("ts")) {
                ts = kv[1];
            } else if (kv[0].equals("h1")) {
                signatures.add(kv[1].toLowerCase(Locale.ROOT));
            }
        }
        if (ts == null || signatures.isEmpty()) {
            throw new InvalidSignatureException("malformed signature header");
        }
        long seconds;
        try {
            seconds = Long.parseLong(ts);
        } catch (NumberFormatException e) {
            throw new InvalidSignatureException("malformed timestamp");
        }
        if (Duration.between(Instant.ofEpochSecond(seconds), clock.instant()).abs().compareTo(tolerance) > 0) {
            throw new InvalidSignatureException("timestamp outside the replay window");
        }
        byte[] expected;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((ts + ":").getBytes(StandardCharsets.UTF_8));
            mac.update(rawBody);
            expected = HexFormat.of().formatHex(mac.doFinal()).getBytes(StandardCharsets.US_ASCII);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
        for (String sig : signatures) {
            if (MessageDigest.isEqual(expected, sig.getBytes(StandardCharsets.US_ASCII))) {
                return;
            }
        }
        throw new InvalidSignatureException("signature mismatch");
    }

    private SubscriptionUpdate subscription(JsonNode d) {
        String status = d.path("status").asString("");
        SubscriptionStatus mapped = switch (status) {
            case "active" -> SubscriptionStatus.ACTIVE;
            case "trialing" -> SubscriptionStatus.TRIALING;
            case "past_due" -> SubscriptionStatus.PAST_DUE;
            case "canceled", "paused" -> SubscriptionStatus.CANCELED;
            default -> null;
        };
        if (mapped == null) {
            return null;
        }
        UUID org = null;
        String orgText = d.path("custom_data").path(ORG_KEY).asString("");
        try {
            org = orgText.isBlank() ? null : UUID.fromString(orgText);
        } catch (IllegalArgumentException e) {
            log.warn("Paddle subscription event with an unparseable organisation id");
        }
        String priceId = null;
        JsonNode items = d.path("items");
        if (items.isArray() && !items.isEmpty()) {
            priceId = items.get(0).path("price").path("id").asString(null);
        }
        boolean cancelScheduled = "cancel".equals(d.path("scheduled_change").path("action").asString(""));
        return new SubscriptionUpdate(d.path("id").asString(null), d.path("customer_id").asString(null), org, priceId, mapped,
                instant(d.path("current_billing_period").path("ends_at").asString(null)), cancelScheduled);
    }

    private JsonNode call(String method, String path, Object body) {
        if (!configured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Online payment isn't set up yet. Please contact us to subscribe.");
        }
        try {
            String response = rest.method(org.springframework.http.HttpMethod.valueOf(method)).uri(path)
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            return jsonMapper.readTree(response == null ? "{}" : response);
        } catch (RestClientResponseException e) {
            log.warn("Paddle {} {} returned HTTP {}", method, path.replaceAll("/[a-z]+_[A-Za-z0-9]+", "/{id}"), e.getStatusCode().value());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "The payment provider rejected the request. Please try again or contact us.");
        } catch (RestClientException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "The payment provider is unreachable right now. Please try again shortly.");
        }
    }

    private static String pathId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_]{1,80}")) {
            throw new ApiException(HttpStatus.CONFLICT, "This workspace has no subscription with the payment provider.");
        }
        return id;
    }

    private static String header(Map<String, String> headers, String name) {
        return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name)).map(Map.Entry::getValue).findFirst().orElse(null);
    }

    private static Instant instant(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(s);
        } catch (DateTimeParseException e) {
            try {
                return java.time.OffsetDateTime.parse(s).toInstant();
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }
}
