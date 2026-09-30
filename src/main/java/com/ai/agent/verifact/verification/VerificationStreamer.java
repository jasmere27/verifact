package com.ai.agent.verifact.verification;

import com.ai.agent.verifact.common.ApiException;
import com.ai.agent.verifact.common.RequestIdFilter;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * Runs a verification off the request thread and streams its milestones as Server-Sent Events:
 * {@code stage}, {@code claims}, {@code sources}, then exactly one {@code result} or {@code error}.
 * Error events carry the same user-safe message and status an ordinary response would.
 */
@Component
public class VerificationStreamer {

    private static final Logger log = LoggerFactory.getLogger(VerificationStreamer.class);
    static final long TIMEOUT_MILLIS = 180_000;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /** Runs {@code task} (a verification, or any other vertical's analysis) and streams its progress and result. */
    public <T> SseEmitter start(Function<VerificationProgress, T> task) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MILLIS);
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);

        executor.submit(() -> {
            if (requestId != null) {
                MDC.put(RequestIdFilter.MDC_KEY, requestId);
            }
            EmittingProgress progress = new EmittingProgress(emitter);
            try {
                T result = task.apply(progress);
                progress.send("result", result);
                emitter.complete();
            } catch (ApiException e) {
                if (e.getStatus().is5xxServerError()) {
                    log.warn("Streamed verification failed with {}: {}", e.getStatus().value(), e.getMessage(), e.getCause());
                }
                progress.sendError(e.getStatus().value(), e.getMessage(), requestId);
                emitter.complete();
            } catch (RuntimeException e) {
                log.error("Streamed verification failed", e);
                progress.sendError(500, "Something went wrong on our side. Please try again.", requestId);
                emitter.complete();
            } finally {
                MDC.remove(RequestIdFilter.MDC_KEY);
            }
        });
        return emitter;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /** Sends milestones; if the client has gone away, later sends are silently skipped. */
    static final class EmittingProgress implements VerificationProgress {
        private final SseEmitter emitter;
        private volatile boolean clientGone;

        EmittingProgress(SseEmitter emitter) {
            this.emitter = emitter;
        }

        @Override
        public void stage(Stage stage) {
            send("stage", Map.of("stage", stage.name()));
        }

        @Override
        public void claims(List<String> claims) {
            send("claims", Map.of("claims", claims));
        }

        @Override
        public void sources(int count, List<String> domains) {
            send("sources", Map.of("count", count, "domains", domains));
        }

        void sendError(int status, String detail, String requestId) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", status);
            body.put("detail", detail);
            if (requestId != null) {
                body.put("requestId", requestId);
            }
            send("error", body);
        }

        void send(String event, Object data) {
            if (clientGone) {
                return;
            }
            try {
                emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException e) {
                clientGone = true;
                log.info("Client disconnected from verification stream");
            }
        }
    }
}
