package com.ai.agent.verifact.tool;

import com.ai.agent.verifact.common.ApiException;
import com.google.cloud.speech.v1.*;
import com.google.protobuf.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Transcribes audio with Google Cloud Speech. Requires Google Application Default
 * Credentials (e.g. GOOGLE_APPLICATION_CREDENTIALS). Supports LINEAR16 WAV, en-US,
 * up to about one minute (synchronous API limit).
 */
@Component
public class VoiceToTextTool {

    private static final Logger log = LoggerFactory.getLogger(VoiceToTextTool.class);

    public String transcribe(byte[] audioData) {
        RecognizeResponse response;
        try (SpeechClient speechClient = SpeechClient.create()) {
            RecognitionConfig config = RecognitionConfig.newBuilder()
                    .setEncoding(RecognitionConfig.AudioEncoding.LINEAR16) // WAV PCM
                    .setLanguageCode("en-US")
                    .build();

            RecognitionAudio audio = RecognitionAudio.newBuilder()
                    .setContent(ByteString.copyFrom(audioData))
                    .build();

            response = speechClient.recognize(config, audio);
        } catch (Exception e) {
            log.warn("Speech recognition failed: {}", e.getClass().getSimpleName(), e);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Audio transcription is unavailable right now. Try pasting the claim as text instead.", e);
        }

        StringBuilder transcript = new StringBuilder();
        for (SpeechRecognitionResult result : response.getResultsList()) {
            if (result.getAlternativesCount() > 0) {
                transcript.append(result.getAlternatives(0).getTranscript()).append(' ');
            }
        }
        if (transcript.toString().isBlank()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "No speech was detected. Use a clear English WAV recording under one minute.");
        }
        return transcript.toString().trim();
    }
}
