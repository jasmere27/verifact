package com.ai.agent.verifact.service;

import com.ai.agent.verifact.tool.VoiceToTextTool;
import com.ai.agent.verifact.tool.DateTimeTool;
import com.ai.agent.verifact.tool.GoogleSearchTool;
import com.ai.agent.verifact.tool.UriContentTool;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.CallResponseSpec;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class AiService {

    private final ChatClient chatClient;
    private final GoogleSearchTool googleSearchTool;
    private final DateTimeTool dateTimeTool;
    private final UriContentTool uriContentTool;
    private final VoiceToTextTool voiceToTextTool;

    @Autowired
    public AiService(ChatClient.Builder chatClientBuilder,
                     GoogleSearchTool googleSearchTool,
                     DateTimeTool dateTimeTool,
                     UriContentTool uriContentTool,
                     VoiceToTextTool voiceToTextTool) {
        this.chatClient = chatClientBuilder.build();
        this.googleSearchTool = googleSearchTool;
        this.dateTimeTool = dateTimeTool;
        this.uriContentTool = uriContentTool;
        this.voiceToTextTool = voiceToTextTool;
    }

    public String isFakeNews(String input) {
        if (input == null || input.trim().isEmpty()) {
            throw new IllegalArgumentException("News cannot be null or empty");
        }

        String contentToAnalyze = input;

        if (uriContentTool.isUrl(input)) {
            Optional<String> content = uriContentTool.fetchContentFromUrl(input);
            if (content.isPresent() && !content.get().isBlank()) {
                contentToAnalyze = content.get();
            } else {
                return "❗ Unable to fetch content from the URL provided. Please make sure it is accessible and contains readable text.";
            }
        }

        
        
        final PromptTemplate promptTemplate = new PromptTemplate("""
        	    You are a fact-checking assistant. Use DateTimeTool for today’s date.

        	    Instructions:
        	    1. Rewrite or correct the user’s input, if it is jumbled or ungrammatical. Analyze it exactly as given.
        	    2. Determine whether the statement is real, fake, or mixed, based on current web sources.
        	    3. Always check if the user has provided a credible source link such as:
        	       - BBC News (bbc.com)
        	       - CNN (cnn.com)
        	       - Reuters (reuters.com)
        	       - The Guardian (theguardian.com)
        	       - Associated Press (apnews.com)
        	       - New York Times (nytimes.com)
        	       If any of these trusted domains are present and the statement matches the content from that source, 
        	       immediately classify it as "Likely Real" with an accuracy of 100%.
        	    4. If the statement contains both real and fake elements:
        	       - Identify which parts are likely real and which parts are likely fake.
        	       - Provide explanations for each.
        	       - Assign an accuracy percentage between 50% and 70% depending on the strength of real parts.
        	    5. If the statement is entirely real or entirely fake:
        	       Respond only with one of the following:
        	       - "Likely Real"
        	       - "Likely Fake"
        	       - "Uncertain"
        	       Include a short reason for your conclusion.
        	    6. Always provide 2–3 credible sources used for verification.
        	       Each source must include:
        	       - Source name
        	       - Valid URL
        	       - Publication or last update date
        	    7. At the end of your response, write:
        	       Accuracy percentage: [number]%
        	       Then explain briefly why that accuracy level was chosen.
        	    8. If the statement is about cybersecurity topics (phishing, scams, ransomware, data leaks, etc.),
        	       include 1–2 practical tips starting with:
        	       Cybersecurity Tip: [your tip here]

        	    Format your response in plain text only.
        	    Do NOT use bullet points or Markdown symbols.

        	    Original Statement: "{input}"
        	""");








        promptTemplate.add("input", contentToAnalyze);

        // Call the LLM or processing engine
        CallResponseSpec responseSpec = chatClient.prompt(promptTemplate.create())
                .tools(dateTimeTool, uriContentTool, googleSearchTool)
                .call();

        return responseSpec.content();
    }

    
    public String isFakeNewsFromAudio(byte[] audioData) {
        if (audioData == null || audioData.length == 0) {
            throw new IllegalArgumentException("Audio data cannot be empty.");
        }

        String transcribedText = voiceToTextTool.transcribe(audioData);
        return isFakeNews(transcribedText);
    }
}