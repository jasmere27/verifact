package com.ai.agent.verifact.ai;

import com.ai.agent.verifact.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@link LlmClient} on Spring AI. Messages are passed as ready-made {@link SystemMessage} /
 * {@link UserMessage} objects rather than templates, so braces in untrusted content can never
 * be interpreted as template syntax. The JSON schema instructions come from Spring AI's
 * {@link BeanOutputConverter}; parsing is done here so failures map to a clean 502.
 */
@Component
public class SpringAiLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(SpringAiLlmClient.class);

    private final ChatClient chatClient;

    public SpringAiLlmClient(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    @Override
    public <T> T generate(String systemPrompt, String userMessage, Class<T> type) {
        BeanOutputConverter<T> converter = new BeanOutputConverter<>(type);
        Prompt prompt = new Prompt(List.of(
                new SystemMessage(systemPrompt + "\n\n" + converter.getFormat()),
                new UserMessage(userMessage)));

        long startedAt = System.nanoTime();
        ChatResponse response;
        try {
            response = chatClient.prompt(prompt).call().chatResponse();
        } catch (RuntimeException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "The analysis service is unavailable right now. Please try again shortly.", e);
        }
        long durationMs = (System.nanoTime() - startedAt) / 1_000_000;

        String text = response == null || response.getResult() == null
                ? null
                : response.getResult().getOutput().getText();
        logUsage(response, type, durationMs);
        if (text == null || text.isBlank()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "The analysis service returned an empty result. Please try again.");
        }
        try {
            T value = converter.convert(text);
            if (value == null) {
                throw new IllegalStateException("null conversion");
            }
            return value;
        } catch (RuntimeException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "The analysis service returned an unreadable result. Please try again.", e);
        }
    }

    private void logUsage(ChatResponse response, Class<?> type, long durationMs) {
        String model = null;
        Integer promptTokens = null;
        Integer completionTokens = null;
        if (response != null && response.getMetadata() != null) {
            model = response.getMetadata().getModel();
            Usage usage = response.getMetadata().getUsage();
            if (usage != null) {
                promptTokens = usage.getPromptTokens();
                completionTokens = usage.getCompletionTokens();
            }
        }
        log.info("LLM call step={} model={} promptTokens={} completionTokens={} durationMs={}",
                type.getSimpleName(), model, promptTokens, completionTokens, durationMs);
    }
}
