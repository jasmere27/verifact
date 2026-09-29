package com.ai.agent.verifact.fetch;

/** The URL points somewhere the server must not fetch. Message is safe to show users. */
public class UnsafeUrlException extends RuntimeException {
    public UnsafeUrlException(String message) {
        super(message);
    }
}
