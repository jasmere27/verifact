package com.ai.agent.verifact.ai;

import com.ai.agent.verifact.common.ApiException;
import org.springframework.http.HttpStatus;

/** A failed model call (always 502 to the client), with why it failed so callers can decide on a fallback. */
public class LlmException extends ApiException {

    public enum Failure {
        /** Provider down, timed out, rate limited, out of credit, or rejecting our key: another call fails too. */
        PROVIDER_UNAVAILABLE,
        /** The provider refused this particular request, e.g. a model that can't read images. */
        REQUEST_REJECTED,
        /** The call worked but the output was empty or not the requested JSON. */
        UNUSABLE_OUTPUT
    }

    private final Failure failure;

    public LlmException(Failure failure, String userMessage, Throwable cause) {
        super(HttpStatus.BAD_GATEWAY, userMessage, cause);
        this.failure = failure;
    }

    public Failure failure() {
        return failure;
    }
}
