package com.ai.agent.verifact.controller;

import com.ai.agent.verifact.common.ApiException;
import com.ai.agent.verifact.model.FactCheckResult;
import com.ai.agent.verifact.repository.FactCheckResultRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * Fact-check history. There are no user accounts yet, so this would expose every visitor's
 * submissions to everyone; it is therefore disabled unless HISTORY_API_ENABLED=true
 * (intended for local development only).
 */
@RestController
@RequestMapping("/api/v1/history")
public class HistoryController {

    private final FactCheckResultRepository factCheckResultRepository;
    private final boolean enabled;

    public HistoryController(FactCheckResultRepository factCheckResultRepository,
                             @Value("${app.history.enabled:false}") boolean enabled) {
        this.factCheckResultRepository = factCheckResultRepository;
        this.enabled = enabled;
    }

    @GetMapping
    public Page<FactCheckResult> history(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        requireEnabled();
        int safeSize = Math.min(Math.max(size, 1), 100);
        return factCheckResultRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(Math.max(page, 0), safeSize));
    }

    @GetMapping("/{id}")
    public FactCheckResult historyById(@PathVariable("id") Long id) {
        requireEnabled();
        return factCheckResultRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No fact-check found with that id."));
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new ApiException(HttpStatus.NOT_FOUND, "History is not available yet.");
        }
    }
}
