package com.ai.agent.verifact.intelligence.responses;

import com.ai.agent.verifact.intelligence.investigations.Investigation;

/** Test access to package-private helpers. */
public final class ResponseStudioServiceAccess {

    private ResponseStudioServiceAccess() {
    }

    public static String warning(Investigation.Result r) {
        return ResponseStudioService.warning(r);
    }
}
