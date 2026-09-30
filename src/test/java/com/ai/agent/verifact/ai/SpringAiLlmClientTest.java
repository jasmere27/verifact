package com.ai.agent.verifact.ai;

import com.ai.agent.verifact.common.ApiException;
import com.ai.agent.verifact.verification.ModelOutputs.ClaimExtraction;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
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
    void toleratesProseAroundTheJson() {
        SpringAiLlmClient client = clientReplying("Here is the result:\n{\"claims\":[{\"claim\":\"X\",\"searchQueries\":[\"x\"]}]}\nHope this helps!");
        assertThat(client.generate("s", "u", ClaimExtraction.class).claims()).hasSize(1);
    }

    @Test
    void schemaTellsTheModelTheAllowedVerdicts() {
        SpringAiLlmClient client = clientReplying("{\"summary\":\"s\",\"claims\":[],\"limitations\":[]}");
        client.generate("s", "u", com.ai.agent.verifact.verification.ModelOutputs.Assessment.class);
        assertThat(lastPrompt.get().getInstructions().get(0).getText()).contains("INSUFFICIENT_EVIDENCE");
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

    @Test
    void imagesAreAttachedToTheUserMessage() {
        SpringAiLlmClient client = clientReplying("{\"claims\":[]}");
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, 1, 2};

        client.generateWithImage("system", "Today's date: 2026-09-30", new ImageInput(jpeg, "image/jpeg"),
                ClaimExtraction.class);

        Message user = lastPrompt.get().getInstructions().get(1);
        assertThat(user).isInstanceOf(UserMessage.class);
        assertThat(user.getText()).isEqualTo("Today's date: 2026-09-30");
        assertThat(((UserMessage) user).getMedia()).singleElement().satisfies(media -> {
            assertThat(media.getMimeType().toString()).isEqualTo("image/jpeg");
            assertThat(media.getDataAsByteArray()).isEqualTo(jpeg);
        });
    }
}
