package com.ai.agent.verifact.tool;

import org.springframework.stereotype.Component;

@Component
public class VoiceToTextTool {

    public String transcribe(byte[] audioData) {
        // You can integrate an actual transcription service here.
        // For example, call Whisper API or Google Speech-to-Text.

        // Dummy placeholder (for demo/testing):
        return "This is a transcribed sample of audio input.";
    }
}
