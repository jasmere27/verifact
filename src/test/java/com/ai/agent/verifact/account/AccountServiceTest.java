package com.ai.agent.verifact.account;

import com.ai.agent.verifact.common.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Accounts against the real schema (Flyway V6 on H2): provisioning, profile and organisation isolation. */
@SpringBootTest
@ActiveProfiles("test")
class AccountServiceTest {

    @Autowired
    private AccountService accounts;
    @Autowired
    private OrganizationAccess access;
    @Autowired
    private MembershipRepository memberships;
    @Autowired
    private OrganizationRepository organizations;

    private static SignedInUser newUser(String email) {
        return new SignedInUser(UUID.randomUUID(), email);
    }

    @Test
    void firstSignInCreatesExactlyOnePersonalWorkspaceOwnedByTheUser() {
        SignedInUser user = newUser("first@example.com");

        AccountService.AccountView first = accounts.me(user);
        AccountService.AccountView again = accounts.me(user);

        assertThat(first.organizations()).hasSize(1);
        AccountService.OrganizationView personal = first.organizations().get(0);
        assertThat(personal.personal()).isTrue();
        assertThat(personal.role()).isEqualTo(Role.OWNER);
        assertThat(again.organizations()).containsExactly(personal);
        assertThat(memberships.findByUserId(user.id())).hasSize(1);
    }

    @Test
    void emailFollowsTheTokenAndDisplayNamesAreCleaned() {
        SignedInUser user = newUser("old@example.com");
        accounts.me(user);

        SignedInUser changed = new SignedInUser(user.id(), "new@example.com");
        AccountService.AccountView view = accounts.updateProfile(changed, "  Ana \n\u0007 Reyes  ");

        assertThat(view.email()).isEqualTo("new@example.com");
        assertThat(view.displayName()).isEqualTo("Ana Reyes");
        assertThat(accounts.updateProfile(changed, "   ").displayName()).isNull();
        assertThatThrownBy(() -> accounts.updateProfile(changed, "x".repeat(81)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void usersCanOnlyActInTheirOwnOrganisations() {
        SignedInUser alice = newUser("alice@example.com");
        SignedInUser bob = newUser("bob@example.com");
        UUID aliceOrg = accounts.me(alice).organizations().get(0).id();
        UUID bobOrg = accounts.me(bob).organizations().get(0).id();

        assertThat(access.require(alice, aliceOrg, Role.OWNER).getRole()).isEqualTo(Role.OWNER);
        // Another user's organisation looks like it doesn't exist; so does an unknown one.
        notFound(() -> access.require(alice, bobOrg, Role.MEMBER));
        notFound(() -> access.require(bob, aliceOrg, Role.MEMBER));
        notFound(() -> access.require(alice, UUID.randomUUID(), Role.MEMBER));
        notFound(() -> access.require(alice, null, Role.MEMBER));
        assertThat(organizations.findById(bobOrg)).isPresent();
    }

    @Test
    void aWeakerRoleIsForbiddenNotHidden() {
        SignedInUser owner = newUser("owner@example.com");
        SignedInUser member = newUser("member@example.com");
        UUID org = accounts.me(owner).organizations().get(0).id();
        accounts.me(member);
        memberships.save(new Membership(org, member.id(), Role.MEMBER, java.time.OffsetDateTime.now()));

        assertThat(access.require(member, org, Role.MEMBER).getRole()).isEqualTo(Role.MEMBER);
        assertThatThrownBy(() -> access.require(member, org, Role.ADMIN))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(Role.OWNER.atLeast(Role.ADMIN)).isTrue();
        assertThat(Role.MEMBER.atLeast(Role.OWNER)).isFalse();
    }

    private static void notFound(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
