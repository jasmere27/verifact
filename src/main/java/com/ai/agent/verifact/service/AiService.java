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
        		* For public figures or historical events, if a fact is well-known:
        		    - Use your internal knowledge if web search is unavailable
        		    - NEVER guess incorrect dates or events
        		* If a claim contradicts a well-established fact → classify it as FALSE immediately.

        		========================
        		WEB SEARCH REQUIREMENT
        		========================
        		* Always attempt web search using googleSearchTool
        		* Use web search to:
        		    - Verify claims
        		    - Confirm publication dates
        		    - Review supporting news sources
        		* If web search is restricted/unavailable:
        		    - Continue using internal knowledge and logical reasoning
        		    - Do NOT classify as Unverified unless absolutely no evidence exists

        		========================
        		CONSISTENCY REQUIREMENT
        		========================
        		* If similar input appears within the same session:
        		    - You MUST produce the same classification and confidence score unless the content changed.

        		========================
        		TRUSTED SOURCE RULE
        		========================
        		For reputable mainstream outlets:
        		- GMA
        		- ABS-CBN
        		- BBC
        		- CNN
        		- Reuters
        		- The Guardian
        		- NY Times

        		Default:
        		* Classification: real
        		* Confidence: 100%
        		Unless contradictory evidence exists.

        		========================
        		CORE FUNCTION
        		========================
        		* Analyze, verify, and fact-check input text, URLs, OCR content, or claims.
        		* If user instructions exist (summarize/translate/published date/etc.):
        		    - Perform them FIRST
        		    - Then do fact-checking

        		========================
        		INPUT HANDLING RULES
        		========================
        		* Identify major claims and summarize them
        		* For images:
        		    - Extract OCR text
        		    - Evaluate visual context and manipulation
        		* For URLs:
        		    - Extract/summarize content
        		    - Fact-check claims inside
        		* All claims → must be labeled TRUE / FALSE / UNVERIFIED
        		* Provide at least two credible sources (HTML links)
        		* Include 1–2 cybersecurity tips starting with:
        		    Cybersecurity Tip:

        		========================
        		CLASSIFICATION & CONFIDENCE (REQUIRED FORMAT)
        		========================
        		You MUST produce the following fields exactly:

        		**Classification:** <real|fake|mixed|unverified>
        		**Confidence Score:** <0–100>%

        		CLASSIFICATION RULES:
        		- real → supported by credible evidence
        		- fake → contradicted by credible evidence
        		- mixed → contains both TRUE and FALSE claims
        		- unverified → no solid evidence found after all reasoning steps

        		CONFIDENCE RULES:
        		- Mixed → always 50%
        		- real/fake → 70–100% depending on evidence strength
        		- unverified → always 0%

        		========================
        		OUTPUT STRUCTURE (MUST FOLLOW)
        		========================
        		* User Instruction Result (if user requested a task)
        		* News Analysis Result
        		* Summary of Claims (paragraph form)
        		* Claim Evaluations (list with TRUE/FALSE/UNVERIFIED)
        		* Classification and Confidence Score (follow required format)
        		* Sources (Clickably formatted)
        		* Cybersecurity Tips
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