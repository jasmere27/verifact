package com.ai.agent.verifact.verification;

import com.ai.agent.verifact.core.assess.Verdict;
import java.util.List;

/**
 * The result for one claim. Evidence IDs are guaranteed to refer to entries in
 * {@link VerificationResult#evidence()}.
 */
public record ClaimAssessment(String id, String text, Verdict verdict, EvidenceStrength evidenceStrength,
                              String explanation, List<String> supportingEvidenceIds,
                              List<String> contradictingEvidenceIds) {}
