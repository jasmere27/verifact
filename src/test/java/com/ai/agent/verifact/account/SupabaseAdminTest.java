package com.ai.agent.verifact.account;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SupabaseAdminTest {

    static final UUID USER = UUID.fromString("6f1c2a7e-5b9d-4c3e-8a1f-2d3b4c5e6f70");
    static final String URL = "https://project.supabase.co/auth/v1/admin/users/" + USER;

    @Test
    void deletesWithTheSecretKeyOnTheApikeyHeaderOnly() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SupabaseAdmin admin = new SupabaseAdmin(builder, "https://project.supabase.co/", "sb_secret_abc");
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.DELETE))
                .andExpect(header("apikey", "sb_secret_abc")).andExpect(headerDoesNotExist("Authorization"))
                .andRespond(withSuccess());

        admin.deleteUser(USER);

        server.verify();
    }

    @Test
    void anAlreadyDeletedUserIsFineOtherFailuresAreReportedWithoutTheKey() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SupabaseAdmin admin = new SupabaseAdmin(builder, "https://project.supabase.co", "sb_secret_abc");
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        admin.deleteUser(USER);
        assertThatThrownBy(() -> admin.deleteUser(USER)).isInstanceOfSatisfying(SupabaseAdmin.AdminException.class,
                e -> assertThat(e.getMessage()).doesNotContain("sb_secret_abc"));
    }

    @Test
    void withoutASecretKeyAdminCallsAreOff() {
        SupabaseAdmin admin = new SupabaseAdmin(RestClient.builder(), "https://project.supabase.co", " ");

        assertThat(admin.enabled()).isFalse();
        assertThatThrownBy(() -> admin.deleteUser(USER)).isInstanceOf(SupabaseAdmin.AdminException.class);
    }
}
