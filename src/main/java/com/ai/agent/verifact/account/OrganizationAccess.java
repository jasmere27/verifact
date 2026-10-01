package com.ai.agent.verifact.account;

import com.ai.agent.verifact.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * The one place that decides whether a user may act in an organisation. Every query for organisation-owned
 * data must go through it (or filter by an organisation id it returned). Someone who isn't a member gets
 * "not found", so organisation ids can't be probed.
 */
@Service
public class OrganizationAccess {

    private final MembershipRepository memberships;

    public OrganizationAccess(MembershipRepository memberships) {
        this.memberships = memberships;
    }

    public Membership require(SignedInUser user, UUID organizationId, Role required) {
        Membership m = organizationId == null ? null
                : memberships.findByOrganizationIdAndUserId(organizationId, user.id()).orElse(null);
        if (m == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Workspace not found.");
        }
        if (!m.getRole().atLeast(required)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You don't have permission to do that in this workspace.");
        }
        return m;
    }
}
