package com.ai.agent.verifact.account;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in user's account. Sign-up, sign-in, resets and verification happen in Supabase Auth. */
@RestController
@RequestMapping("/api/v2/me")
public class AccountController {

    private final AccountService accounts;

    public AccountController(AccountService accounts) {
        this.accounts = accounts;
    }

    public record ProfileUpdate(String displayName) {}

    @GetMapping
    public AccountService.AccountView me(@AuthenticationPrincipal Jwt jwt) {
        return accounts.me(SignedInUser.from(jwt));
    }

    @PatchMapping
    public AccountService.AccountView update(@AuthenticationPrincipal Jwt jwt, @RequestBody ProfileUpdate update) {
        return accounts.updateProfile(SignedInUser.from(jwt), update == null ? null : update.displayName());
    }
}
