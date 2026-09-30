package com.ai.agent.verifact.ai;

/**
 * An image ready to send to the model: already decoded, size-capped and re-encoded by the backend
 * (so it carries no metadata), never the raw upload.
 *
 * @param mimeType e.g. {@code image/jpeg}
 */
public record ImageInput(byte[] bytes, String mimeType) {
}
