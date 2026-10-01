package com.ai.agent.verifact.account;

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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AccountController.class)
@Import({SecurityConfig.class, SupabaseAuthConfig.class})
class AccountControllerTest {

    static final UUID USER = UUID.fromString("6f1c2a7e-5b9d-4c3e-8a1f-2d3b4c5e6f70");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AccountService accounts;

    @Test
    void signedOutRequestsGetAProblemResponseNotAnAccount() throws Exception {
        mockMvc.perform(get("/api/v2/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Please sign in to continue."));
        // An invalid token is refused (accounts are off in tests, so every real token is invalid).
        mockMvc.perform(get("/api/v2/me").header("Authorization", "Bearer not.a.token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Please sign in again: your session is missing, expired or invalid."));
        verifyNoInteractions(accounts);
    }

    @Test
    void theSignedInUserGetsAndUpdatesTheirOwnAccount() throws Exception {
        SignedInUser me = new SignedInUser(USER, "reader@example.com");
        AccountService.AccountView view = new AccountService.AccountView(USER, "reader@example.com", "Reader",
                List.of(new AccountService.OrganizationView(UUID.randomUUID(), "Personal workspace", true, Role.OWNER)));
        when(accounts.me(me)).thenReturn(view);
        when(accounts.updateProfile(eq(me), any())).thenReturn(view);

        mockMvc.perform(get("/api/v2/me").with(jwt().jwt(j -> j.subject(USER.toString()).claim("email", "reader@example.com"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("reader@example.com"))
                .andExpect(jsonPath("$.organizations[0].personal").value(true))
                .andExpect(jsonPath("$.organizations[0].role").value("OWNER"));
        mockMvc.perform(delete("/api/v2/me").with(jwt().jwt(j -> j.subject(USER.toString()).claim("email", "reader@example.com"))))
                .andExpect(status().isNoContent());
        org.mockito.Mockito.verify(accounts).delete(me);
        mockMvc.perform(delete("/api/v2/me")).andExpect(status().isUnauthorized());
        mockMvc.perform(patch("/api/v2/me").with(jwt().jwt(j -> j.subject(USER.toString()).claim("email", "reader@example.com")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"displayName\":\"Reader\"}"))
                .andExpect(status().isOk());
    }
}
