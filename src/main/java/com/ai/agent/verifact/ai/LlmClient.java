package com.ai.agent.verifact.ai;

/**
 * The one seam between VeriFact and an LLM provider: send a system + user message, get back a
 * typed object parsed from JSON, optionally with an image attached. No tools are ever exposed through this interface.
 */
public interface LlmClient {

    /**
     * @throws com.ai.agent.verifact.common.ApiException 502 if the provider fails or the output
     *                                                   can't be parsed into {@code type}
     */
    <T> T generate(String systemPrompt, String userMessage, Class<T> type);

    /**
     * Like {@link #generate}, with an image attached to the user message.
     *
     * @throws com.ai.agent.verifact.common.ApiException 502 if the provider fails (including a model
     *                                                   that can't read images) or the output can't be parsed
     */
    <T> T generateWithImage(String systemPrompt, String userMessage, ImageInput image, Class<T> type);
}
