package com.ai.agent.verifact.search;

/** Search failed for operational reasons (network, auth, quota). Never carries secrets. */
public class SearchUnavailableException extends RuntimeException {
    public SearchUnavailableException(String message) {
        super(message);
    }
}
