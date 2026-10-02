package com.ai.agent.verifact.research;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live check of paper analysis (ADR-23) with the real model and indexes. Costs about a cent, so it only runs with
 * {@code RUN_EVALS=true} plus OPEN_AI_API_KEY:
 * <pre>RUN_EVALS=true OPEN_AI_API_KEY=... ./mvnw test -Dtest=PaperAnalysisEvalIT</pre>
 * Checks that the real model's quotes survive the verbatim checks (or students would see nothing), and that a made-up
 * paper is never matched to a real record.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "RUN_EVALS", matches = "true")
class PaperAnalysisEvalIT {

    /** A made-up study: no real record exists, so it must not be VERIFIED. */
    static final String SYNTHETIC = """
            Effects of Peer Tutoring on the Reading Comprehension of Grade 8 Learners in a Rural Philippine High School
            Ana Dela Cruz, Ben Villanueva
            Abstract
            This study examined whether structured peer tutoring improves the reading comprehension of Grade 8 learners.
            A quasi-experimental pretest-posttest design was used with 84 learners from a rural public high school in
            Quezon province, assigned by section to a peer tutoring group (n = 42) or a regular instruction group (n = 42).
            Reading comprehension was measured with a 30-item researcher-made test with a reliability coefficient of 0.86.
            Data were analysed using analysis of covariance with pretest scores as the covariate.
            Results showed that learners in the peer tutoring group obtained significantly higher posttest scores than
            learners in the regular instruction group, F(1, 81) = 9.42, p = .003.
            Learners also reported higher motivation to read during the tutoring sessions.
            The study was conducted in a single school over six weeks, which limits the generalizability of the findings.
            Future studies may involve more schools and a longer intervention period.
            """;

    /** The real first page of a well-known paper, with its DOI printed. */
    static final String REAL_FRONT = """
            Deep learning
            Yann LeCun, Yoshua Bengio & Geoffrey Hinton
            Nature volume 521, pages 436-444 (2015). https://doi.org/10.1038/nature14539
            Deep learning allows computational models that are composed of multiple processing layers to learn
            representations of data with multiple levels of abstraction. These methods have dramatically improved the
            state-of-the-art in speech recognition, visual object recognition, object detection and many other domains
            such as drug discovery and genomics. Deep learning discovers intricate structure in large data sets by using
            the backpropagation algorithm to indicate how a machine should change its internal parameters that are used
            to compute the representation in each layer from the representation in the previous layer.
            """;

    @Autowired
    private PaperAnalysisService service;

    private static DocumentExtractor.Extracted doc(String text) {
        return new DocumentExtractor.Extracted(DocumentExtractor.Kind.TXT, text, 1, false);
    }

    @Test
    void aMadeUpStudyGetsQuotedFindingsButNoVerifiedRecord() {
        long start = System.currentTimeMillis();
        PaperAnalysis a = service.analyze(doc(SYNTHETIC), "PH");
        System.out.println("SYNTHETIC (" + (System.currentTimeMillis() - start) + " ms): match=" + a.match().status() + " " + a.match().basis());
        System.out.println("  summary: " + a.plainSummary());
        a.findings().forEach(f -> System.out.println("  finding: " + f.statement() + "  <= \"" + f.quote() + "\""));
        a.method().forEach(m -> System.out.println("  method " + m.aspect() + ": " + m.statement() + "  <= \"" + m.quote() + "\""));
        a.authorLimitations().forEach(l -> System.out.println("  limitation: " + l.statement() + "  <= \"" + l.quote() + "\""));
        System.out.println("  limitations: " + a.limitations());

        assertThat(a.match().status()).isNotEqualTo(PaperAnalysis.MatchStatus.VERIFIED);
        assertThat(a.findings()).hasSizeGreaterThanOrEqualTo(1);
        assertThat(a.method()).hasSizeGreaterThanOrEqualTo(2);
        a.findings().forEach(f -> assertThat(SYNTHETIC.replaceAll("\\s+", " ")).contains(f.quote().replace("…", "").strip()));
        assertThat(a.plainSummary()).isNotBlank();
    }

    @Test
    void aRealPaperWithItsDoiPrintedIsVerified() {
        PaperAnalysis a = service.analyze(doc(REAL_FRONT), null);
        System.out.println("REAL: match=" + a.match().status() + " " + a.match().basis()
                + (a.match().source() == null ? "" : " -> " + a.match().source().title() + " / " + a.match().source().doi()));
        System.out.println("  summary: " + a.plainSummary());
        assertThat(a.match().status()).isIn(PaperAnalysis.MatchStatus.VERIFIED, PaperAnalysis.MatchStatus.LOOKUP_FAILED);
    }
}
