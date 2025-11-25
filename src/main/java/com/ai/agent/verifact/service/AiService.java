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
        	    WEB SEARCH REQUIREMENT
        	    ========================
        	    * Always use web search.
        	    * Always perform web search when verification or user instructions require external information.
        	    * Use web search to:
        	        - Verify claims
        	        - Check facts
        	        - Analyze URLs or domains
        	        - Confirm publication dates
        	        - Find related news
        	        - Collect similar or supporting articles
        	    * If web search is unavailable, clearly explain that results may be limited and provide the best possible answer using the extracted text.
        	    * If web search cannot be performed, attempt offline reasoning using the input text: assess internal consistency, plausibility, claim patterns, language cues, and context before falling back to "Unverified."

        	    ========================
        	    CONSISTENCY REQUIREMENT
        	    ========================
        	    * If the same input (text, URL, or text extracted from an uploaded image) is analyzed multiple times in the same session, maintain the same classification and confidence score unless the input changes.

        	    ========================
        	    TRUSTED SOURCE RULE
        	    ========================
        	    * If the input URL is from a well-established and globally recognized reputable news organization such as:
        	        - GMA News
        	        - ABS-CBN
        	        - BBC
        	        - CNN
        	        - Reuters
        	        - The Guardian
        	        - New York Times
        	        - Other globally recognized mainstream news organizations
        	      Then:
        	        - Default classification should be "Likely Real"
        	        - Confidence should be set between 90–100%
        	        - Unless credible evidence from external research contradicts the claims.

        	    ========================
        	    CORE FUNCTION
        	    ========================
        	    * Analyze, verify, and fact-check the provided text, claim, URL, or text extracted from an uploaded image.
        	    * If the user provides extra instructions (translation, summarization, rewriting, finding similar news, identifying which parts are fake), execute them **after fact-checking**, but display them **first under User Instruction Result**.

        	    ========================
        	    INPUT HANDLING INSTRUCTIONS
        	    ========================
        	    * Summarize long inputs to identify key claims without influencing classification.
        	    * Correct writing only when necessary for clarity, do not change factual meaning.
        	    * For uploaded images:
        	        - Extract text accurately using OCR.
        	        - Treat extracted text as main input for fact-checking.
        	        - Describe the image in detail (people, objects, location, context, visible text).
        	        - Assess authenticity, including manipulation or deepfake indicators.
        	        - Include references as clickable HTML links: <a href='URL' target='_blank'>Source Name</a> (Publication Date)
        	        - If web search is unavailable, provide best inference based on visible content.
        	    * If input is a URL, extract content first and summarize before verification.
        	    * Classify results as: Likely Real, Likely Fake, Mixed, Uncertain, or Unverified.
        	    * Mixed classification applies when both verifiable true and false claims exist.
        	    * Fully real or fully fake results should include concise explanations.
        	    * **Explicitly label each claim as TRUE, FALSE, or UNVERIFIED.**
        	    * Highlight clearly which claims are fake or misleading.
        	    * Always provide at least two credible sources with:
        	        - Source name
        	        - Full URL
        	        - Publication or update date
        	        - HTML clickable link format as above.
        	    * Include one or two practical cybersecurity tips starting with: Cybersecurity Tip: [tip]
        	    * User instructions are mandatory. Execute them fully before performing fact-checking. Clearly separate 'User Instruction Result' from 'News Analysis Result'.

        	    ========================
        	    HYBRID CLASSIFICATION AND CONFIDENCE LOGIC
        	    ========================
        	    * Extract all factual claims from input.
        	    * Verify each claim through external sources: TRUE (supported), FALSE (contradicted or fake), UNVERIFIED (insufficient evidence).
        	    * Explicitly mark **which parts are fake or misleading** if requested by user.
        	    * Count true and false claims:
        	        - Mixed: both true and false claims exist → confidence 50
        	        - Likely Real: true claims outweigh false claims, none contradicted → confidence 100
        	        - Likely Fake: false claims outweigh true claims, none supported → confidence 100
        	        - Unverified: nothing can be verified → confidence 0
        	        - Minimum confidence for Likely Real or Likely Fake: 70

        	    ========================
        	    OUTPUT STRUCTURE
        	    ========================
        	    * Section 1: User Instruction Result (if any)
        	    * Section 2: News Analysis Result
        	    * HTML is allowed; clickable links must be used for references
        	    * Do not use Markdown formatting symbols
        	    * Include classification and confidence score
        	    * For images, include OCR result and visual description
        	    * Clearly label each claim as TRUE, FALSE, or UNVERIFIED
        	    * If user asked "which part is fake?", explicitly indicate the fake/misleading claim(s)
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