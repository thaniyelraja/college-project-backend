package com.newplanner.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

/**
 * OpenWeatherMap — per-place weather enrichment.
 * Uses fallback-waterfall across all configured weather keys.
 */
@Service
public class WeatherEnrichmentService {

    private static final Logger log = LoggerFactory.getLogger(WeatherEnrichmentService.class);

    private final ApiKeyRotationService keyService;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public WeatherEnrichmentService(ApiKeyRotationService keyService) {
        this.keyService = keyService;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(5000);
        this.restTemplate = new RestTemplate(factory);
    }

    /**
     * Current weather at a specific lat/lng.
     */
    public String[] deriveWeatherCondition(Double lat, Double lng) {
        if (lat == null || lng == null) return new String[]{"Clear", "false"};
        try {
            return keyService.tryWithFallback(keyService.getWeatherKeys(), key -> {
                String url = String.format(
                    "https://api.openweathermap.org/data/2.5/weather?lat=%s&lon=%s&appid=%s", lat, lng, key);
                ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
                if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                    throw new RuntimeException("Weather HTTP " + response.getStatusCode());
                }
                return parseWeatherNode(objectMapper.readTree(response.getBody()).path("weather"));
            });
        } catch (Exception e) {
            log.warn("Weather (current): all keys failed — defaulting to Clear. {}", e.getMessage());
            return new String[]{"Clear", "false"};
        }
    }

    /**
     * Predictive weather for a specific date + time.
     * Matches the closest 3-hour forecast block.
     * Falls back to current weather if beyond the 5-day API window.
     */
    public String[] derivePredictiveWeather(Double lat, Double lng, java.time.LocalDate targetDate, String timeStr) {
        if (lat == null || lng == null || targetDate == null) return new String[]{"Clear", "false"};

        java.time.LocalDate now = java.time.LocalDate.now();
        if (targetDate.isAfter(now.plusDays(4)) || targetDate.isBefore(now)) {
            return new String[]{"Forecast unavailable", "false"}; // Outside 5-day window
        }

        String formattedTime = (timeStr != null && timeStr.contains(":"))
            ? timeStr.substring(0, 5) : "12:00";
        if (formattedTime.length() == 4) formattedTime = "0" + formattedTime;
        long targetEpoch = java.time.LocalDateTime
            .of(targetDate, java.time.LocalTime.parse(formattedTime))
            .toEpochSecond(java.time.ZoneOffset.UTC);

        final long epochFinal = targetEpoch;
        try {
            return keyService.tryWithFallback(keyService.getWeatherKeys(), key -> {
                String url = String.format(
                    "https://api.openweathermap.org/data/2.5/forecast?lat=%s&lon=%s&appid=%s", lat, lng, key);
                ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
                if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                    throw new RuntimeException("Weather Forecast HTTP " + response.getStatusCode());
                }
                JsonNode listArray = objectMapper.readTree(response.getBody()).path("list");
                if (!listArray.isArray() || listArray.isEmpty()) {
                    throw new RuntimeException("Weather Forecast: empty list");
                }
                JsonNode closest = null;
                long minDelta = Long.MAX_VALUE;
                for (JsonNode node : listArray) {
                    long delta = Math.abs(node.path("dt").asLong() - epochFinal);
                    if (delta < minDelta) { minDelta = delta; closest = node; }
                }
                if (closest == null) throw new RuntimeException("No forecast block found");
                return parseWeatherNode(closest.path("weather"));
            });
        } catch (Exception e) {
            log.warn("Weather (predictive): all keys failed — falling back to current. {}", e.getMessage());
            return deriveWeatherCondition(lat, lng);
        }
    }

    // ─── Helper ──────────────────────────────────────────────────────────────

    private String[] parseWeatherNode(JsonNode weatherArray) {
        if (!weatherArray.isArray() || weatherArray.isEmpty()) return new String[]{"Clear", "false"};
        String main = weatherArray.get(0).path("main").asText("Clear");
        boolean critical = main.equalsIgnoreCase("Rain")
                || main.equalsIgnoreCase("Thunderstorm")
                || main.equalsIgnoreCase("Snow")
                || main.equalsIgnoreCase("Extreme")
                || main.equalsIgnoreCase("Tornado");
        return new String[]{main, String.valueOf(critical)};
    }
}
