package com.ai.agent.verifact.verification;

import com.ai.agent.verifact.model.InputType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The v2 API response and the stored report.
 *
 * @param input       what the user submitted (text, the link, or the file name)
 * @param checkedText the text that was analysed (page text, OCR text, or transcript), truncated
 */
public record VerificationResult(UUID id, Instant createdAt, InputType inputType, String input,
                                 String checkedText, OverallVerdict overallVerdict, String summary,
                                 List<ClaimAssessment> claims, List<Evidence> evidence,
                                 List<String> limitations, String searchProvider, long durationMs) {}
