package com.ai.agent.verifact.intelligence;

import com.ai.agent.verifact.account.AccountService;
import com.ai.agent.verifact.account.Membership;
import com.ai.agent.verifact.account.OrganizationAccess;
import com.ai.agent.verifact.account.Role;
import com.ai.agent.verifact.account.SignedInUser;
import com.ai.agent.verifact.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Turns a request's token and organisation id into an {@link OrgContext}, using {@link OrganizationAccess} (the
 * one membership check): a non-member gets 404, a weaker role 403. Roles come from our database, never the token.
 */
@Component
public class IntelligenceAccess {

    private final AccountService accounts;
    private final OrganizationAccess access;

    public IntelligenceAccess(AccountService accounts, OrganizationAccess access) {
        this.accounts = accounts;
        this.access = access;
    }

    public OrgContext require(Jwt jwt, String orgId, Role required) {
        SignedInUser user = SignedInUser.from(jwt);
        accounts.ensureProvisioned(user);
        Membership m = access.require(user, uuid(orgId), required);
        return new OrgContext(m.getOrganizationId(), user.id(), m.getRole());
    }

    /** A path id that isn't a UUID can't name anything the caller may see. */
    public static UUID uuid(String id) {
        try {
            return id == null ? null : UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Not found.");
        }
    }
}
