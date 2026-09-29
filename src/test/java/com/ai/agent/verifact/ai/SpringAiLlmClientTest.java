package com.ai.agent.verifact.ai;

import com.ai.agent.verifact.common.ApiException;
import com.ai.agent.verifact.verification.ModelOutputs.ClaimExtraction;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runs the real Spring AI ChatClient against a fake ChatModel (no network). */
class SpringAiLlmClientTest {

    private final AtomicReference<Prompt> lastPrompt = new AtomicReference<>();

    private SpringAiLlmClient clientReplying(String reply) {
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                lastPrompt.set(prompt);
                return new ChatResponse(List.of(new Generation(new AssistantMessage(reply))));
            }
        };
        return new SpringAiLlmClient(ChatClient.builder(model));
    }

    @Test
    void parsesJsonIntoTheRequestedTypeEvenInsideCodeFences() {
        SpringAiLlmClient client = clientReplying("""
                ```json
                {"claims":[{"claim":"Water boils at 100 C at sea level","searchQueries":["boiling point of water"]}]}
                ```""");

        ClaimExtraction result = client.generate("system", "user", ClaimExtraction.class);

        assertThat(result.claims()).hasSize(1);
        assertThat(result.claims().get(0).searchQueries()).containsExactly("boiling point of water");
    }

    @Test
    void untrustedTextWithBracesIsSentVerbatimNotTreatedAsATemplate() {
        SpringAiLlmClient client = clientReplying("{\"claims\":[]}");
        String userText = "Weird {input} text with {{braces}} and <tags> and $var";

        client.generate("system prompt", userText, ClaimExtraction.class);

        List<Message> messages = lastPrompt.get().getInstructions();
        assertThat(messages).hasSize(2);
        assertThat(messages.get(1).getText()).isEqualTo(userText);
        assertThat(messages.get(0).getText()).startsWith("system prompt").contains("JSON");
    }

    @Test
    void unparseableOutputIs502() {
        SpringAiLlmClient client = clientReplying("Sorry, I can't help with that.");
        assertThatThrownBy(() -> client.generate("s", "u", ClaimExtraction.class))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));
    }

    @Test
    void emptyOutputIs502() {
        SpringAiLlmClient client = clientReplying("");
        assertThatThrownBy(() -> client.generate("s", "u", ClaimExtraction.class)).isInstanceOf(ApiException.class);
    }

    @Test
    void providerFailureIs502WithoutLeakingDetails() {
        ChatModel failing = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw new IllegalStateException("401 invalid api key sk-secret");
            }
        };
        SpringAiLlmClient client = new SpringAiLlmClient(ChatClient.builder(failing));

        assertThatThrownBy(() -> client.generate("s", "u", ClaimExtraction.class))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(e.getMessage()).doesNotContain("sk-secret");
                });
    }
}
