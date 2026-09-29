package com.ai.agent.verifact.common;

import org.springframework.http.HttpStatus;

/**
 * An error whose message is safe to show to the user. Anything else that escapes a
 * controller is reported to the client as a generic 500 with no internal details.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String userMessage) {
        super(userMessage);
        this.status = status;
    }

    public ApiException(HttpStatus status, String userMessage, Throwable cause) {
        super(userMessage, cause);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
