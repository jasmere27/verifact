package com.ai.agent.verifact.research;

import com.ai.agent.verifact.research.ResearchCheck.CheckedReference;
import com.ai.agent.verifact.research.ResearchCheck.ReferenceStatus;
import com.ai.agent.verifact.research.ResearchCheck.Support;
import com.ai.agent.verifact.verification.VerificationProgress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live ResearchFact check against the REAL model, Crossref and OpenAlex. Costs a little (two model
 * calls, a few OpenAlex searches), so it only runs with {@code RUN_EVALS=true} plus OPEN_AI_API_KEY:
 *
 * <pre>RUN_EVALS=true OPEN_AI_API_KEY=... ./mvnw test -Dtest=ResearchCheckEvalIT</pre>
 *
 * One text with known answers: a real paper by (DataCite) DOI, a retracted paper, a fabricated
 * reference, a real paper without a DOI, and an overstated claim. Output: target/research-eval/result.json.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "RUN_EVALS", matches = "true")
class ResearchCheckEvalIT {

    static final String TEXT = """
            The Transformer architecture relies solely on attention mechanisms, dispensing with recurrence and \
            convolutions entirely (Vaswani et al., 2017). Deep learning allows computational models that are \
            composed of multiple processing layers to learn representations of data with multiple levels of \
            abstraction (LeCun et al., 2015). Deep learning has already solved general artificial intelligence \
            (LeCun et al., 2015). An early study linked the MMR vaccine to autism (Wakefield et al., 1998). \
            Drinking coffee doubles human lifespan (Johnson, 2022).

            References
            LeCun, Y., Bengio, Y., & Hinton, G. (2015). Deep learning. Nature, 521(7553), 436-444.
            Johnson, R. (2022). Quantum coffee effects on human longevity: a randomized trial. Journal of Imaginary Nutrition, 4(2), 12-19.
            Vaswani, A., Shazeer, N., Parmar, N., et al. (2017). Attention is all you need. https://doi.org/10.48550/arXiv.1706.03762
            Wakefield, A. J., Murch, S. H., Anthony, A., et al. (1998). Ileal-lymphoid-nodular hyperplasia, non-specific colitis, \
            and pervasive developmental disorder in children. The Lancet, 351(9103), 637-641. https://doi.org/10.1016/S0140-6736(97)11096-0""";

    @Autowired
    private ResearchCheckService service;
    @Autowired
    private JsonMapper jsonMapper;

    @Test
    void knownAnswers() throws Exception {
        ResearchCheck r = service.check(TEXT, VerificationProgress.NONE);
        Path out = Path.of("target", "research-eval", "result.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(r));

        for (CheckedReference ref : r.references()) {
            System.out.println("EVAL ref " + ref.status() + " | " + ref.textAsWritten().substring(0, Math.min(60, ref.textAsWritten().length()))
                    + " | " + (ref.work() == null ? "-" : ref.work().doi() + " abstract=" + ref.work().hasAbstract()) + " " + ref.differences());
        }
        r.claims().forEach(c -> System.out.println("EVAL claim " + c.support() + " | " + c.claim() + " | " + c.evidenceQuote()
                + " | conflicts=" + c.conflicting().size()));

        assertThat(status(r, "wakefield")).isEqualTo(ReferenceStatus.RETRACTED);
        assertThat(status(r, "johnson")).isEqualTo(ReferenceStatus.NOT_FOUND);
        assertThat(status(r, "vaswani")).isIn(ReferenceStatus.VERIFIED, ReferenceStatus.FOUND_WITH_DIFFERENCES);
        assertThat(status(r, "lecun")).isIn(ReferenceStatus.VERIFIED, ReferenceStatus.FOUND_WITH_DIFFERENCES);
        // The overstated claim must never come back as (partly) supported.
        assertThat(r.claims()).filteredOn(c -> c.claim().toLowerCase(Locale.ROOT).contains("general artificial intelligence"))
                .allSatisfy(c -> assertThat(c.support()).isNotIn(Support.SUPPORTED, Support.PARTIALLY_SUPPORTED));
        // Every quote is verbatim from its abstract: enforced in code, re-checked here via non-null source.
        assertThat(r.claims()).allSatisfy(c -> {
            if (c.evidenceQuote() != null) {
                assertThat(c.evidenceFrom()).isNotNull();
                assertThat(c.evidenceQuote().length()).isLessThanOrEqualTo(301);
            }
        });
    }

    private static ReferenceStatus status(ResearchCheck r, String surname) {
        List<CheckedReference> refs = r.references().stream()
                .filter(x -> x.textAsWritten().toLowerCase(Locale.ROOT).contains(surname)).toList();
        assertThat(refs).as("reference for " + surname).isNotEmpty();
        return refs.get(0).status();
    }
}
