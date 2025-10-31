package com.ai.agent.verifact.tool;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Optional;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Component
public class UriContentTool {

    @Tool(description = "Check if the input is a valid URL")
    public boolean isUrl(String input) {
        try {
            new URL(input);
            System.out.println("Valid URL: " + input);
            return true;
        } catch (MalformedURLException e) {
            System.out.println("It is not a URL: " + input);
            return false;
        }
    }

    @Tool(name = "fetchContentFromUrl", description = "Fetches and extracts content from the given URL.")
    public Optional<String> fetchContentFromUrl(String url) {
        try {
            Document document = Jsoup.connect(url).userAgent("Mozilla/5.0").timeout(10000).get();
            String title = document.title();
            String bodyText = document.body().text();

            // Detect restricted access or login pages
            if ((bodyText.toLowerCase().contains("sign in") || bodyText.toLowerCase().contains("login")) &&
                title.toLowerCase().contains("google")) {
                System.out.println("Blocked content - Google login required.");
                return Optional.of("⚠️ This page requires login (e.g., Google sign-in). Cannot analyze private content.");
            }

            System.out.println("Fetching content from URL: " + url);
            return Optional.of(title + " " + bodyText);
        } catch (Exception e) {
            e.printStackTrace();
            return Optional.of("⚠️ Failed to fetch content from URL due to an error: " + e.getMessage());
        }
    }

    // Utility for AiService usage (optional)
    public String extractContent(String url) {
        return fetchContentFromUrl(url).orElse("No content available");
    }
}
