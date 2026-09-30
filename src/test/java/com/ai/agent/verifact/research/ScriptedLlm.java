package com.ai.agent.verifact.research;

import com.ai.agent.verifact.ai.ImageInput;
import com.ai.agent.verifact.ai.LlmClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** Returns a scripted answer per output type; records what it was sent. */
class ScriptedLlm implements LlmClient {
    final Map<Class<?>, Function<String, Object>> answers = new ConcurrentHashMap<>();
    final List<String> systemPrompts = Collections.synchronizedList(new ArrayList<>());
    final List<String> userMessages = Collections.synchronizedList(new ArrayList<>());

    <T> ScriptedLlm answer(Class<T> type, Function<String, T> answer) {
        answers.put(type, answer::apply);
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T generate(String systemPrompt, String userMessage, Class<T> type) {
        systemPrompts.add(systemPrompt);
        userMessages.add(userMessage);
        Function<String, Object> f = answers.get(type);
        if (f == null) {
            throw new IllegalStateException("No scripted answer for " + type.getSimpleName());
        }
        return (T) f.apply(userMessage);
    }

    @Override
    public <T> T generateWithImage(String s, String u, ImageInput i, Class<T> type) {
        throw new UnsupportedOperationException();
    }
}
