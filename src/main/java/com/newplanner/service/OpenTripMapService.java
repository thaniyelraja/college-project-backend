package com.newplanner.service;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * OpenTripMap — fetches tourist places.
 * Accepts a dynamic kinds string built from the user's active interests.
 * Uses fallback-waterfall across all configured OTM keys.
 */
@Service
public class OpenTripMapService {

    private final ApiKeyRotationService keyService;
    private final RestTemplate restTemplate = new RestTemplate();
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper =
            new com.fasterxml.jackson.databind.ObjectMapper();

    public OpenTripMapService(ApiKeyRotationService keyService) {
        this.keyService = keyService;
    }

    /**
     * Fetch places from OTM using a dynamic kinds string.
     * Example kinds: "historic,cultural,natural,catering"
     * Falls back to "interesting_places" if kinds is blank.
     */
    public com.fasterxml.jackson.databind.JsonNode fetchBasePlaces(
            Double lat, Double lng, String radius, String kinds) {

        final String kindsParam = (kinds != null && !kinds.isBlank()) ? kinds : "interesting_places";

        return keyService.tryWithFallback(keyService.getOtmKeys(), key -> {
            String urlStr = String.format(
                "https://api.opentripmap.com/0.1/en/places/radius?radius=%s&lon=%s&lat=%s&kinds=%s&rate=1&limit=200&format=geojson&apikey=%s",
                radius, lng, lat, kindsParam, key);

            System.out.println("OTM fetch → kinds: " + kindsParam);
            java.net.URI uri = java.net.URI.create(urlStr);
            String responseStr = restTemplate.getForObject(uri, String.class);
            if (responseStr == null || responseStr.isBlank()) {
                throw new RuntimeException("OTM returned empty response");
            }
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(responseStr);
            if (!root.has("features") || root.get("features").size() == 0) {
                throw new RuntimeException("OTM returned zero features");
            }
            return root;
        });
    }
}
