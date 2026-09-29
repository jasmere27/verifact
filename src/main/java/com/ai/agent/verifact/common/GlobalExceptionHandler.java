package com.ai.agent.verifact.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Returns every error as RFC 9457 {@code application/problem+json}. Standard Spring MVC
 * exceptions (bad request body, upload too large, unsupported media type, ...) are handled
 * by the {@link ResponseEntityExceptionHandler} base class.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApiException(ApiException e) {
        if (e.getStatus().is5xxServerError()) {
            log.warn("Request failed with {}: {}", e.getStatus().value(), e.getMessage(), e.getCause());
        } else {
            log.info("Request rejected with {}: {}", e.getStatus().value(), e.getMessage());
        }
        return ResponseEntity.status(e.getStatus()).body(problem(e.getStatus(), e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception e) {
        log.error("Unhandled exception", e);
        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        return ResponseEntity.status(status)
                .body(problem(status, "Something went wrong on our side. Please try again."));
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problemDetail) {
            addRequestId(problemDetail);
        }
        return super.handleExceptionInternal(ex, body, headers, statusCode, request);
    }

    public static ProblemDetail problem(HttpStatus status, String detail) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, detail);
        addRequestId(problemDetail);
        return problemDetail;
    }

    private static void addRequestId(ProblemDetail problemDetail) {
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        if (requestId != null) {
            problemDetail.setProperty("requestId", requestId);
        }
    }
}
