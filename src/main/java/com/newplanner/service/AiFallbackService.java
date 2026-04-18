package com.newplanner.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import com.newplanner.entity.Activity;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;

/**
 * AI provider chain with fallback-waterfall:
 *   Groq (5 keys, waterfall) → Gemini → OpenRouter
 */
@Service
public class AiFallbackService {

    private static final Logger log = LoggerFactory.getLogger(AiFallbackService.class);

    private final ApiKeyRotationService keyService;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public AiFallbackService(ApiKeyRotationService keyService) {
        this.keyService = keyService;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
        this.objectMapper = new ObjectMapper();
    }

    public String callAi(String systemPrompt, String userPrompt) {
        // Tier 1: Groq — waterfall through all 5 keys
        try {
            log.info("AI Gateway: Groq (fallback-waterfall, {} keys)...", keyService.getGroqKeys().size());
            return callGroq(systemPrompt, userPrompt);
        } catch (Exception groqEx) {
            log.error("Groq: all keys exhausted. Falling back to Gemini... {}", groqEx.getMessage());
        }
        // Tier 2: Gemini
        try {
            return callGemini(systemPrompt, userPrompt);
        } catch (Exception geminiEx) {
            log.error("Gemini failed. Falling back to OpenRouter... {}", geminiEx.getMessage());
        }
        // Tier 3: OpenRouter
        try {
            return callOpenRouter(systemPrompt, userPrompt);
        } catch (Exception openRouterEx) {
            log.error("CRITICAL: All AI providers failed. Returning emergency mock. {}", openRouterEx.getMessage());
            return buildEmergencyMock();
        }
    }

    // ── Groq — waterfall through all 5 keys ──────────────────────────────────

    private String callGroq(String systemPrompt, String userPrompt) {
        return keyService.tryWithFallback(keyService.getGroqKeys(), key -> {
            Map<String, Object> body = new HashMap<>();
            body.put("model", "llama-3.3-70b-versatile");
            body.put("max_tokens", 4096);
            body.put("response_format", Map.of("type", "json_object"));
            body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user",   "content", userPrompt)
            ));

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.groq.com/openai/v1/chat/completions"))
                .timeout(Duration.ofSeconds(25))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + key)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new RuntimeException("Groq HTTP " + response.statusCode() + " — " + response.body());
            }
            Map<String, Object> resBody = objectMapper.readValue(response.body(), Map.class);
            List<Map<String, Object>> choices = (List<Map<String, Object>>) resBody.get("choices");
            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            return stripMarkdown((String) message.get("content"));
        });
    }

    // ── Gemini — single key fallback ─────────────────────────────────────────

    private String callGemini(String systemPrompt, String userPrompt) throws Exception {
        String key = keyService.getGeminiKey();
        Map<String, Object> body = new HashMap<>();
        body.put("contents", List.of(Map.of("parts", List.of(Map.of("text", systemPrompt + "\n\n" + userPrompt)))));
        body.put("generationConfig", Map.of("responseMimeType", "application/json", "maxOutputTokens", 8192));

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=" + key))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new RuntimeException("Gemini HTTP " + response.statusCode() + " — " + response.body());
        }
        Map<String, Object> resBody = objectMapper.readValue(response.body(), Map.class);
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) resBody.get("candidates");
        Map<String, Object> content = (Map<String, Object>) candidates.get(0).get("content");
        List<Map<String, Object>> parts = (List<Map<String, Object>>) content.get("parts");
        return stripMarkdown((String) parts.get(0).get("text"));
    }

    // ── OpenRouter — single key fallback ─────────────────────────────────────

    private String callOpenRouter(String systemPrompt, String userPrompt) throws Exception {
        String key = keyService.getOpenRouterKey();
        Map<String, Object> body = new HashMap<>();
        body.put("model", "meta-llama/llama-3.1-8b-instruct:free");
        body.put("max_tokens", 4096);
        body.put("response_format", Map.of("type", "json_object"));
        body.put("messages", List.of(
            Map.of("role", "system", "content", systemPrompt),
            Map.of("role", "user",   "content", userPrompt)
        ));

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("https://openrouter.ai/api/v1/chat/completions"))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + key)
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new RuntimeException("OpenRouter HTTP " + response.statusCode() + " — " + response.body());
        }
        Map<String, Object> resBody = objectMapper.readValue(response.body(), Map.class);
        List<Map<String, Object>> choices = (List<Map<String, Object>>) resBody.get("choices");
        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
        return stripMarkdown((String) message.get("content"));
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private String stripMarkdown(String raw) {
        if (raw == null || raw.trim().isEmpty()) throw new RuntimeException("AI returned empty payload");
        return raw.replaceAll("(?s)```json", "").replaceAll("```", "").trim();
    }

    private String buildEmergencyMock() {
        return "{\"days\":[{\"dayNumber\":1,\"theme\":\"Exploration Day\",\"activities\":[" +
               "{\"placeName\":\"City Centre\",\"startTime\":\"09:00\",\"endTime\":\"11:00\"," +
               "\"description\":\"A notable location in the area.\"}]}]}";
    }

    // ─── Emergency POI Generator ─────────────────────────────────────────────

    public List<Activity> generateFallbackPlaces(String destination, double centerLat, double centerLng, int count, String activeKinds) {
        log.warn("Executing Emergency AI POI Generation for {} ({} places needed)", destination, count);
        String sys = "You are an emergency geolocation API. The user needs exactly " + count + " prominent, real tourist attractions or landmarks in/around " + destination + ". " +
                     "CRITICAL: Do NOT return hospitals, clinics, schools, colleges, academies, bus stops, transit stations, ATMs, banks, or corporate offices. Only return places of leisure, culture, nature, or historic interest. " +
                     "Return exactly " + count + " places as JSON. Provide highly accurate GPS coordinates near lat: " + centerLat + ", lng: " + centerLng + ". " +
                     "Requested categories (use as hint): " + activeKinds + ".\n" +
                     "Format MUST BE: {\"places\": [{\"name\":\"String\",\"lat\":0.0,\"lng\":0.0,\"rate\":7.5,\"kinds\":\"historic,cultural\"}]}";
        String prompt = "Generate " + count + " strict tourist attractions for " + destination + ". Spread them out logically. No hospitals. No schools. No bus stops.";
        
        String aiJson = callAi(sys, prompt);
        List<Activity> list = new ArrayList<>();
        
        try {
            JsonNode root = objectMapper.readTree(aiJson);
            JsonNode places = root.path("places");
            if (places.isArray()) {
                for (JsonNode p : places) {
                    Activity act = new Activity();
                    act.setPlaceName(p.path("name").asText("Notable Location"));
                    act.setLatitude(p.path("lat").asDouble(centerLat));
                    act.setLongitude(p.path("lng").asDouble(centerLng));
                    act.setOtmRate(p.path("rate").asDouble(7.0));
                    act.setOtmKinds(p.path("kinds").asText("interesting_places"));
                    list.add(act);
                }
            }
        } catch (Exception e) {
            log.error("Failed to parse AI fallback places json. Returning empty.", e);
        }
        
        // Failsafe in case AI returned fewer
        while (list.size() < count) {
            Activity act = new Activity();
            act.setPlaceName(destination + " Central Landmark " + (list.size() + 1));
            act.setLatitude(centerLat + (Math.random() - 0.5) * 0.05);
            act.setLongitude(centerLng + (Math.random() - 0.5) * 0.05);
            act.setOtmRate(5.0);
            act.setOtmKinds("interesting_places");
            list.add(act);
        }
        
        return list;
    }
}
