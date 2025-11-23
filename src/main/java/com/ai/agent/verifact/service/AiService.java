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

					Web Search Requirement:
					
					* Use web search whenever verification or user instructions require external information.
					* Always use web search when verifying claims, checking facts, analyzing URLs or domains, confirming publication dates, finding related news, or collecting similar or supporting articles.
					* If web search is unavailable, clearly explain that results may be limited and provide the best possible answer using the extracted text.
					
					Consistency Requirement:
					
					* If the same input (text, URL, or text extracted from an uploaded image) is analyzed multiple times in the same session, the classification and confidence score must remain consistent unless the input changes.
					
					Core Function:
					
					* Analyze, verify, and fact-check the provided text, claim, URL, or text extracted from an uploaded image.
					* If the user provides extra instructions such as translation, summarization, rewriting, or finding similar news, complete those instructions after fact-checking but show them first under User Instruction Result.
					
					Instructions:
					
					* Summarize long inputs to identify key claims without influencing classification.
					* Correct writing only when needed to improve clarity, but do not change factual meaning.
					* For uploaded images:
					  • Extract text accurately using OCR.
					  • Treat the extracted text as the main input for fact-checking.
					  • Describe the image in detail including people, objects, location, context, and visible text.
					  • Attempt to assess authenticity, including potential manipulation or deepfake indicators.
					  • Include references as clickable HTML links in the format: <a href='URL' target='_blank'>Source Name</a> (Publication Date)
					  • If web search is unavailable, provide the best inference based on visible content.
					* If input is a URL, extract and summarize the content before verification.
					* Classify the final result as Likely Real, Likely Fake, Mixed, Uncertain, or Unverified.
					* Claims consistent with trusted reporting (BBC, CNN, Reuters, AP, Guardian, NYT) should normally be classified as Likely Real with 100% confidence.
					* Mixed classification applies if both verifiable true and false claims appear together.
					* Fully real or fully fake results should clearly state the classification with a concise explanation.
					* Always provide at least two credible sources with:
					  • Source name
					  • Full URL
					  • Publication or update date
					  • HTML clickable link format as described above.
					* For cybersecurity-related analysis, include one or two practical tips beginning with: Cybersecurity Tip: [tip]
					* Additional user instructions must run after fact-checking but appear first in the output.
					
					Hybrid Classification and Confidence Logic:
					
					* Extract all factual claims from the input.
					* Verify each claim through external sources and label them as TRUE (supported), FALSE (contradicted), or UNVERIFIED (insufficient evidence).
					* Count true and false claims.
					  • Mixed applies when both true and false claims exist and should yield confidence 50.
					  • Likely Real applies when true claims clearly outweigh false claims with none contradicted and should yield confidence 100.
					  • Likely Fake applies when false claims outweigh true claims with none supported and should yield confidence 100.
					  • Unverified applies when nothing can be verified and should yield confidence 0.
					  • The minimum confidence for Likely Real or Likely Fake is 70.
					
					Output Structure:
					
					* First section: User Instruction Result if applicable.
					* Second section: News Analysis Result.
					* HTML is allowed and clickable links must be used for references.
					* Do not use Markdown formatting symbols.
					* Include classification and confidence score.
					* For images, include the OCR result and visual description.
					
					Original Input: "{input}"

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