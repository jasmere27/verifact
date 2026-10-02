package com.ai.agent.verifact.research;

import com.ai.agent.verifact.account.AccountService;
import com.ai.agent.verifact.account.SignedInUser;
import com.ai.agent.verifact.account.SupabaseAdmin;
import com.ai.agent.verifact.common.ApiException;
import com.ai.agent.verifact.research.ResearchProject.ReadingStatus;
import com.ai.agent.verifact.research.ResearchProjectStore.GapInput;
import com.ai.agent.verifact.research.ResearchProjectStore.ItemUpdate;
import com.ai.agent.verifact.research.ResearchProjectStore.NewItem;
import com.ai.agent.verifact.research.ResearchProjectStore.QuestionInput;
import com.ai.agent.verifact.research.ResearchProjectStore.Update;
import com.ai.agent.verifact.research.ResearchWorkspace.Folder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** Capstone projects against the real schema (Flyway on H2), ADR-21: ownership, verified sources, links, retention. */
@SpringBootTest
@ActiveProfiles("test")
class ResearchProjectStoreTest {

    private static final String ABSTRACT = "The flipped classroom improved mathematics achievement of senior high students in the Philippines.";

    @Autowired
    private ResearchProjectStore store;
    @Autowired
    private ResearchWorkspaceStore workspaces;
    @Autowired
    private AccountService accounts;
    @Autowired
    private ResearchProjectRepository repository;
    @Autowired
    private JdbcTemplate jdbc;
    @MockitoBean
    private ScholarlyIndex index;
    @MockitoBean
    private SupabaseAdmin admin;

    private SignedInUser user;

    @BeforeEach
    void setUp() {
        user = newUser();
        when(index.work(anyString())).thenReturn(Optional.empty());
        when(index.work("10.1/real")).thenReturn(Optional.of(ResearchDiscoveryServiceTest.work("10.1/real",
                "Flipped classroom in Philippine senior high mathematics", ABSTRACT, "PH")));
        when(index.work("10.1/other")).thenReturn(Optional.of(ResearchDiscoveryServiceTest.work("10.1/other",
                "Flipped learning in US colleges", "Students in US colleges preferred flipped lectures.", "US")));
    }

    private SignedInUser newUser() {
        SignedInUser u = new SignedInUser(UUID.randomUUID(), "student@example.com");
        accounts.ensureProvisioned(u);
        return u;
    }

    private ResearchProject project() {
        return store.create(user.id(), "Flipped classroom and mathematics achievement", "Education", "ph");
    }

    @Test
    void projectsAreOnlyVisibleToTheirOwner() {
        ResearchProject p = project();
        SignedInUser other = newUser();

        assertThat(store.get(user.id(), p.id()).title()).isEqualTo("Flipped classroom and mathematics achievement");
        assertThat(store.list(user.id())).extracting(ResearchProject.Summary::id).containsExactly(p.id());
        assertThat(store.list(other.id())).isEmpty();
        assertThatThrownBy(() -> store.get(other.id(), p.id()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> store.delete(other.id(), p.id())).isInstanceOf(ApiException.class);
    }

    @Test
    void aNewProjectComesWithProgressAndNextSteps() {
        ResearchProject p = project();

        assertThat(p.country()).isEqualTo("PH");
        assertThat(p.progress().milestones()).isNotEmpty();
        assertThat(p.nextSteps()).extracting(ResearchProject.NextStep::id).containsExactly("questions", "start-library");
        assertThat(p.deletesAt()).isAfter(Instant.now().plusSeconds(360L * 86400));
    }

    @Test
    void savedSourcesComeFromTheIndexAndUnknownOnesAreRefused() {
        ResearchProject p = project();
        p = store.update(user.id(), p.id(), new Update(null, null, null, null, List.of(new QuestionInput(null, "Does flipped learning help?")), null));
        String rq = p.questions().get(0).id();

        p = store.addSource(user.id(), p.id(), new NewItem("10.1/real", Folder.RRS, "Same setting as mine.",
                "improved mathematics achievement of senior high students", null, rq));

        assertThat(p.library()).singleElement().satisfies(i -> {
            assertThat(i.source().title()).isEqualTo("Flipped classroom in Philippine senior high mathematics");
            assertThat(i.source().local()).isTrue();
            assertThat(i.questionIds()).containsExactly(rq);
            assertThat(i.status()).isEqualTo(ReadingStatus.TO_READ);
        });
        ResearchProject fixed = p;
        assertThatThrownBy(() -> store.addSource(user.id(), fixed.id(), new NewItem("10.1/fabricated", Folder.RRS, null, null, null, null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        // Saving twice keeps one copy.
        assertThat(store.addSource(user.id(), p.id(), new NewItem("10.1/real", Folder.RRL, null, null, null, null)).library()).hasSize(1);
    }

    @Test
    void studentFieldsAreSavedAndQuestionLinksFollowTheQuestions() {
        ResearchProject p = project();
        p = store.update(user.id(), p.id(), new Update(null, null, null, null,
                List.of(new QuestionInput(null, "RQ one?"), new QuestionInput(null, "RQ two?")), null));
        String q1 = p.questions().get(0).id();
        String q2 = p.questions().get(1).id();
        p = store.addSource(user.id(), p.id(), new NewItem("10.1/real", Folder.RRS, null, null, null, null));

        p = store.updateItem(user.id(), p.id(), new ItemUpdate("10.1/real", Folder.RRL, ReadingStatus.READ, "Matches my setting",
                "Achievement improved", "Quasi-experimental", List.of(q1, q2, "not-a-question")));
        assertThat(p.library().get(0)).satisfies(i -> {
            assertThat(i.folder()).isEqualTo(Folder.RRL);
            assertThat(i.keyFindings()).isEqualTo("Achievement improved");
            assertThat(i.method()).isEqualTo("Quasi-experimental");
            assertThat(i.questionIds()).containsExactly(q1, q2);
        });

        // Removing RQ1 keeps RQ2's id and unlinks RQ1 everywhere.
        p = store.update(user.id(), p.id(), new Update(null, null, null, null, List.of(new QuestionInput(q2, "RQ two, reworded?")), null));
        assertThat(p.questions()).extracting(ResearchProject.Question::id).containsExactly(q2);
        assertThat(p.library().get(0).questionIds()).containsExactly(q2);
    }

    @Test
    void gapsCiteOnlySavedSourcesAndRemovingASourceUncitesIt() {
        ResearchProject p = project();
        p = store.addSource(user.id(), p.id(), new NewItem("10.1/real", Folder.RRS, null, null, null, null));
        p = store.addSource(user.id(), p.id(), new NewItem("10.1/other", Folder.RRS, null, null, null, null));

        p = store.update(user.id(), p.id(), new Update(null, null, null, null, null,
                List.of(new GapInput(null, "Few studies on public senior high schools.", List.of("10.1/real", "10.1/other", "10.1/unsaved")))));
        assertThat(p.gaps()).singleElement().satisfies(g -> assertThat(g.sourceKeys()).containsExactly("10.1/real", "10.1/other"));

        p = store.removeItem(user.id(), p.id(), "10.1/other");
        assertThat(p.library()).extracting(ResearchProject.LibraryItem::key).containsExactly("10.1/real");
        assertThat(p.gaps().get(0).sourceKeys()).containsExactly("10.1/real");
    }

    @Test
    void aQuickWorkspaceCanBeMovedIntoAProject() {
        ResearchWorkspace.View ws = workspaces.create("Flipped classroom and mathematics achievement", "Education", "PH");
        workspaces.addSource(ws.workspace().id(), ws.editToken(), new ResearchWorkspaceStore.NewSource("10.1/real", Folder.RRS, null, null, null));
        ResearchWorkspace editable = workspaces.editable(ws.workspace().id(), ws.editToken());

        ResearchProject p = store.importWorkspace(user.id(), editable);

        assertThat(p.title()).isEqualTo("Flipped classroom and mathematics achievement");
        assertThat(p.library()).extracting(ResearchProject.LibraryItem::key).containsExactly("10.1/real");
        assertThatThrownBy(() -> workspaces.editable(ws.workspace().id(), "wrong-token")).isInstanceOf(ApiException.class);
    }

    @Test
    void projectsUnchangedFor12MonthsAreDeletedAndRecentOnesKept() {
        ResearchProject old = project();
        ResearchProject recent = project();
        jdbc.update("UPDATE research_projects SET updated_at = ? WHERE id = ?",
                OffsetDateTime.now(ZoneOffset.UTC).minusDays(366), old.id());

        store.deleteExpired();

        assertThat(repository.existsById(old.id())).isFalse();
        assertThat(repository.existsById(recent.id())).isTrue();
    }

    @Test
    void deletingTheAccountDeletesItsProjects() {
        ResearchProject p = project();
        when(admin.enabled()).thenReturn(true);

        accounts.delete(user);

        assertThat(repository.existsById(p.id())).isFalse();
    }
}
