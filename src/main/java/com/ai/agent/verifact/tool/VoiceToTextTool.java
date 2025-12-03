package com.ai.agent.verifact.tool;

import com.google.cloud.speech.v1.*;
import com.google.protobuf.ByteString;
import org.springframework.stereotype.Component;

@Component
public class VoiceToTextTool {

    public String transcribe(byte[] audioData) {
        try (SpeechClient speechClient = SpeechClient.create()) {

            ByteString audioBytes = ByteString.copyFrom(audioData);

            RecognitionConfig config = RecognitionConfig.newBuilder()
                    .setEncoding(RecognitionConfig.AudioEncoding.LINEAR16) // WAV PCM
                    .setLanguageCode("en-US")
                    .build();

            RecognitionAudio audio = RecognitionAudio.newBuilder()
                    .setContent(audioBytes)
                    .build();

            RecognizeResponse response = speechClient.recognize(config, audio);

            if (response.getResultsCount() == 0) {
                return "No speech detected.";
            }

            return response.getResults(0).getAlternatives(0).getTranscript();

        } catch (Exception e) {
            e.printStackTrace();
            return "Speech recognition failed: " + e.getMessage();
        }
    }
}

