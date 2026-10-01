package com.ai.agent.verifact.account;

import com.ai.agent.verifact.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Our side of accounts: the profile, and the personal organisation every user gets on first sign-in. */
@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    static final String PERSONAL_NAME = "Personal workspace";
    static final int MAX_DISPLAY_NAME = 80;

    private final AppUserRepository users;
    private final OrganizationRepository organizations;
    private final MembershipRepository memberships;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final SupabaseAdmin admin;

    public AccountService(AppUserRepository users, OrganizationRepository organizations, MembershipRepository memberships,
                          TransactionTemplate tx, Clock clock, SupabaseAdmin admin) {
        this.users = users;
        this.organizations = organizations;
        this.memberships = memberships;
        this.tx = tx;
        this.clock = clock;
        this.admin = admin;
    }

    public record OrganizationView(UUID id, String name, boolean personal, Role role) {}

    public record AccountView(UUID id, String email, String displayName, List<OrganizationView> organizations) {}

    public AccountView me(SignedInUser user) {
        ensureProvisioned(user);
        return tx.execute(s -> view(user.id()));
    }

    public AccountView updateProfile(SignedInUser user, String displayName) {
        String name = cleanDisplayName(displayName);
        ensureProvisioned(user);
        return tx.execute(s -> {
            users.findById(user.id()).orElseThrow().rename(name, now());
            return view(user.id());
        });
    }

    /**
     * Deletes the account: first the Supabase Auth user (so nobody can sign in as it any more), then our rows.
     * The personal organisation and memberships go with the user (ON DELETE CASCADE).
     */
    public void delete(SignedInUser user) {
        if (!admin.enabled()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Account deletion isn't available right now. Please try again later.");
        }
        try {
            admin.deleteUser(user.id());
        } catch (SupabaseAdmin.AdminException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "We couldn't delete your account just now. Nothing was deleted; please try again.", e);
        }
        tx.executeWithoutResult(s -> {
            organizations.deleteByPersonalOwnerId(user.id());
            users.deleteById(user.id());
        });
        log.info("Account deleted");
    }

    /** Creates the user, their personal organisation and owner membership once; keeps the email current. */
    void ensureProvisioned(SignedInUser user) {
        try {
            tx.executeWithoutResult(s -> provisionOrSync(user));
        } catch (DataIntegrityViolationException e) {
            // Two first requests raced; the other one created the account. Read (and sync) what it stored.
            log.info("Account provisioning raced for a new user; using the stored account");
            tx.executeWithoutResult(s -> provisionOrSync(user));
        }
    }

    private void provisionOrSync(SignedInUser user) {
        OffsetDateTime now = now();
        AppUser existing = users.findById(user.id()).orElse(null);
        if (existing != null) {
            if (!Objects.equals(existing.getEmail(), user.email())) {
                existing.syncEmail(user.email(), now);
            }
            return;
        }
        users.saveAndFlush(new AppUser(user.id(), user.email(), now));
        Organization personal = organizations.saveAndFlush(new Organization(UUID.randomUUID(), PERSONAL_NAME, user.id(), now));
        memberships.saveAndFlush(new Membership(personal.getId(), user.id(), Role.OWNER, now));
        log.info("New account provisioned");
    }

    private AccountView view(UUID userId) {
        AppUser u = users.findById(userId).orElseThrow();
        List<Membership> ms = memberships.findByUserId(userId);
        Map<UUID, Organization> orgs = organizations.findAllById(ms.stream().map(Membership::getOrganizationId).toList())
                .stream().collect(Collectors.toMap(Organization::getId, Function.identity()));
        List<OrganizationView> views = ms.stream().filter(m -> orgs.containsKey(m.getOrganizationId()))
                .map(m -> {
                    Organization o = orgs.get(m.getOrganizationId());
                    return new OrganizationView(o.getId(), o.getName(), o.isPersonal(), m.getRole());
                })
                .sorted(Comparator.comparing((OrganizationView o) -> !o.personal()).thenComparing(OrganizationView::name))
                .toList();
        return new AccountView(u.getId(), u.getEmail(), u.getDisplayName(), views);
    }

    /** Printable text only, whitespace collapsed; blank clears the name. */
    static String cleanDisplayName(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.replaceAll("[\\p{Cc}\\p{Cf}]", " ").replaceAll("\\s+", " ").trim();
        if (s.isEmpty()) {
            return null;
        }
        if (s.length() > MAX_DISPLAY_NAME) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Display names can be at most " + MAX_DISPLAY_NAME + " characters.");
        }
        return s;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
