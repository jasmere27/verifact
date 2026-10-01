package com.ai.agent.verifact.verification;

import com.ai.agent.verifact.account.SignedInUser;
import com.ai.agent.verifact.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** The signed-in user's check history (ADR-20). Signed in only: {@code /api/v2/me/**} requires a token. */
@RestController
@RequestMapping("/api/v2/me/checks")
public class MyChecksController {

    private final AccountChecks checks;

    public MyChecksController(AccountChecks checks) {
        this.checks = checks;
    }

    @GetMapping
    public List<AccountChecks.CheckView> list(@AuthenticationPrincipal Jwt jwt) {
        return checks.list(SignedInUser.from(jwt));
    }

    /** Removes a check from the history only; deleting the report itself is DELETE /api/v2/verifications/{id}. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> remove(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") String id) {
        UUID uuid;
        try {
            uuid = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.NOT_FOUND, "That check isn't in your history.");
        }
        checks.remove(SignedInUser.from(jwt), uuid);
        return ResponseEntity.noContent().build();
    }
}
