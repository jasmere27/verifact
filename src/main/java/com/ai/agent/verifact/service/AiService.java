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
        	    You are a fact-checking and information assistant. Use dateTimeTool for today's date.

        	    ========================
        	    FIXED FACT PROTECTION (NEW - CRITICAL)
        	    ========================
        	    * You must NEVER infer, assume, or invent facts that are well-established in public records.
        	    * For public figures or historical events, if the fact is known universally (e.g., birthdays, death dates, inauguration dates, major global events), you MUST:
        	        - Use your internal knowledge if web search is unavailable
        	        - NEVER guess or invent alternative dates or events
        	    * If a claim contradicts a well-known fixed fact, classify it as FALSE immediately.
        	    * Example: 
        	        - If a claim says “Bongbong Marcos’ birthday is November 27,” classify it FALSE because he was born on September 13, 1957.

        	    ========================
        	    WEB SEARCH REQUIREMENT
        	    ========================
        	    * Always attempt web search using googleSearchTool
        	    * Use web search to:
        	        - Verify claims
        	        - Check facts
        	        - Analyze URLs or domains
        	        - Confirm publication dates
        	        - Find related news
        	        - Collect supporting or contradicting articles
        	    * If web search is restricted, unavailable, or fails:
        	        - Do NOT stop the analysis.
        	        - Do NOT classify as Unverified unless absolutely no information exists.
        	        - Continue using:
        	            internal knowledge, logical reasoning, historical context, plausibility, and source credibility.

        	    ========================
        	    CONSISTENCY REQUIREMENT
        	    ========================
        	    * If the same input appears multiple times in the same session:
        	        - You MUST produce the same classification and confidence unless input contents changed.

        	    ========================
        	    TRUSTED SOURCE RULE
        	    ========================
        	    * For URLs from major reputable news outlets:
        	        - GMA
        	        - ABS-CBN
        	        - BBC
        	        - CNN
        	        - Reuters
        	        - The Guardian
        	        - New York Times
        	        - Mainstream global news organizations
        	      Default classification:
        	        - "Likely Real"
        	        - Confidence 90–100%
        	      Unless external evidence contradicts the claim.

        	    ========================
        	    CORE FUNCTION
        	    ========================
        	    * Analyze, verify, and fact-check text, claims, URLs, or OCR results.
        	    * If user instructions exist (e.g., summarize, rewrite, translate):
        	        - Perform them AFTER fact-checking.
        	        - Display them FIRST under "User Instruction Result".

        	    ========================
        	    INPUT HANDLING INSTRUCTIONS
        	    ========================
        	    * Summarize the input to identify major claims (in paragraph form).
        	    * For image uploads:
        	        - Extract text using OCR.
        	        - Treat extracted text as the main content.
        	        - Describe the visual context.
        	        - Evaluate manipulation/deepfake signs.
        	    * For URL analysis:
        	        - Extract content
        	        - Summarize
        	        - Verify claims inside.
        	    * Each claim must be labeled TRUE, FALSE, or UNVERIFIED.
        	    * Provide at least two credible sources:
        	        - Name
        	        - URL
        	        - Publication date
        	        - Clickable HTML links (<a href='URL'>Name</a>)
        	    * Include 1–2 cybersecurity tips starting with:
        	        Cybersecurity Tip:

        	    ========================
        	    HYBRID CLASSIFICATION AND CONFIDENCE LOGIC
        	    ========================
        	    * Extract claims and evaluate each using:
        	        - Web search (when available)
        	        - Logical reasoning, context clues, and general historical knowledge when not
        	    * TRUE = supported by evidence
        	    * FALSE = contradicted by evidence
        	    * UNVERIFIED = insufficient evidence after all reasoning paths
        	    * Confidence rules:
        	        - Mixed: both TRUE and FALSE present → confidence 50
        	        - Likely Real: mostly TRUE, none contradicted → confidence up to 100
        	        - Likely Fake: mostly FALSE, none supported → confidence up to 100
        	        - Unverified: unable to reasonably determine → confidence 0
        	        - Minimum confidence for Likely Real/Fake = 70

        	    ========================
        	    OUTPUT STRUCTURE
        	    ========================
        	    * User Instruction Result (if applicable)
        	    * News Analysis Result
        	    * Summary of Claims (paragraph form)
        	    * Claim evaluations (TRUE/FALSE/UNVERIFIED)
        	    * Classification and confidence score
        	    * Sources (clickable HTML)
        	    * Cybersecurity tips
        	    * Original Input: "{input}"
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