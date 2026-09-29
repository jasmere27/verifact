package com.ai.agent.verifact.ai;

/**
 * The one seam between VeriFact and an LLM provider: send a system + user message, get back a
 * typed object parsed from JSON. No tools are ever exposed through this interface.
 */
public interface LlmClient {

    /**
     * @throws com.ai.agent.verifact.common.ApiException 502 if the provider fails or the output
     *                                                   can't be parsed into {@code type}
     */
    <T> T generate(String systemPrompt, String userMessage, Class<T> type);
}
