package com.newplanner.controller;

import com.newplanner.service.ApiKeyRotationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * GET /api/v1/hotels/suggestions?lat=&lng=&radius=&destination=
 * Fetches accommodations directly from OTM using key rotation.
 * Returns empty list gracefully when no hotels found — never throws.
 */
@RestController
@RequestMapping("/hotels")
@RequiredArgsConstructor
@Slf4j

public class HotelController {

    private final ApiKeyRotationService keyService;
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @GetMapping("/suggestions")
    public ResponseEntity<List<Map<String, Object>>> getHotelSuggestions(
            @RequestParam Double lat,
            @RequestParam Double lng,
            @RequestParam(defaultValue = "30000") String radius,
            @RequestParam(defaultValue = "") String destination) {

        log.info("Hotel suggestions: lat={} lng={} radius={} dest={}", lat, lng, radius, destination);
        List<Map<String, Object>> hotels = new ArrayList<>();

        // Try each OTM key in order — stop at first success
        List<String> keys = keyService.getOtmKeys();
        for (String key : keys) {
            try {
                String url = String.format(
                    "https://api.opentripmap.com/0.1/en/places/radius?radius=%s&lon=%s&lat=%s&kinds=accomodations&limit=20&format=geojson&apikey=%s",
                    radius, lng, lat, key);

                String responseStr = restTemplate.getForObject(url, String.class);
                if (responseStr == null || responseStr.isBlank()) continue;

                JsonNode root = objectMapper.readTree(responseStr);
                JsonNode features = root.path("features");
                if (!features.isArray() || features.size() == 0) continue;

                int count = 0;
                for (JsonNode feature : features) {
                    if (count >= 6) break;

                    JsonNode props = feature.path("properties");
                    String name = props.path("name").asText("").trim();
                    if (name.isEmpty()) continue;

                    double hotelLat = feature.path("geometry").path("coordinates").path(1).asDouble();
                    double hotelLng = feature.path("geometry").path("coordinates").path(0).asDouble();
                    double rate = props.path("rate").asDouble(0.0);

                    String query = URLEncoder.encode(name + " hotel " + destination, StandardCharsets.UTF_8);
                    String mapsLink = "https://www.google.com/maps/search/" + query;

                    hotels.add(Map.of(
                        "name",     name,
                        "rate",     rate,
                        "lat",      hotelLat,
                        "lng",      hotelLng,
                        "mapsLink", mapsLink
                    ));
                    count++;
                }

                log.info("Hotel suggestions: found {} hotels", hotels.size());
                break; // success — stop trying keys

            } catch (Exception e) {
                log.warn("Hotel fetch failed with key ending ...{}: {}", key.substring(Math.max(0, key.length()-6)), e.getMessage());
            }
        }

        return ResponseEntity.ok(hotels);
    }
}
