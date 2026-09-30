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
import org.springframework.ai.content.Media;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;

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
        return call(systemPrompt, new UserMessage(userMessage), type);
    }

    @Override
    public <T> T generateWithImage(String systemPrompt, String userMessage, ImageInput image, Class<T> type) {
        Media media = new Media(MimeType.valueOf(image.mimeType()), new ByteArrayResource(image.bytes()));
        return call(systemPrompt, UserMessage.builder().text(userMessage).media(media).build(), type);
    }

    private <T> T call(String systemPrompt, UserMessage userMessage, Class<T> type) {
        BeanOutputConverter<T> converter = new BeanOutputConverter<>(type);
        Prompt prompt = new Prompt(List.of(
                new SystemMessage(systemPrompt + "\n\n" + converter.getFormat()),
                userMessage));

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
            return parse(converter, text);
        } catch (RuntimeException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "The analysis service returned an unreadable result. Please try again.", e);
        }
    }

    /** Parses as-is first; if the model wrapped the JSON in prose, retries on the outermost {...}. */
    static <T> T parse(BeanOutputConverter<T> converter, String text) {
        try {
            return requireNonNull(converter.convert(text));
        } catch (RuntimeException first) {
            int start = text.indexOf('{');
            int end = text.lastIndexOf('}');
            if (start < 0 || end <= start) {
                throw first;
            }
            return requireNonNull(converter.convert(text.substring(start, end + 1)));
        }
    }

    private static <T> T requireNonNull(T value) {
        if (value == null) {
            throw new IllegalStateException("Model output converted to null");
        }
        return value;
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
