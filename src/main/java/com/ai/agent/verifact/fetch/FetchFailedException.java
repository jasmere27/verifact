package com.ai.agent.verifact.fetch;

/** The URL was allowed but its content could not be retrieved or read. Message is safe to show users. */
public class FetchFailedException extends RuntimeException {
    public FetchFailedException(String message) {
        super(message);
    }

    public FetchFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
