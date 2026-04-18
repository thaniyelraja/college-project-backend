package com.newplanner.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OpenRouteService — transit routing.
 * Uses fallback-waterfall across all configured ORS keys.
 */
@Service
public class OrsRoutingService {

    private static final Logger log = LoggerFactory.getLogger(OrsRoutingService.class);

    public static class RoutingResult {
        public String durationStr;
        public String geometryStr;
        public RoutingResult(String durationStr, String geometryStr) {
            this.durationStr = durationStr;
            this.geometryStr = geometryStr;
        }
    }

    private final ApiKeyRotationService keyService;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OrsRoutingService(ApiKeyRotationService keyService) {
        this.keyService = keyService;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10000);
        factory.setReadTimeout(10000);
        this.restTemplate = new RestTemplate(factory);
    }

    /**
     * ORS Optimization API (VRP/TSP)
     * Takes a list of activities and returns a geometrically optimized sequence.
     * Starts the route at the first activity in the list.
     */
    public List<com.newplanner.entity.Activity> optimizeRoute(List<com.newplanner.entity.Activity> places) {
        if (places == null || places.size() <= 2) return places;
        int n = places.size();

        try {
            return keyService.tryWithFallback(keyService.getOrsKeys(), key -> {
                // 1. Build jobs (all places)
                List<Map<String, Object>> jobs = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    jobs.add(Map.of("id", i, "location", List.of(places.get(i).getLongitude(), places.get(i).getLatitude())));
                }

                // 2. Build one vehicle starting at the first place
                List<Map<String, Object>> vehicles = new ArrayList<>();
                vehicles.add(Map.of(
                    "id", 1,
                    "profile", "driving-car",
                    "start", List.of(places.get(0).getLongitude(), places.get(0).getLatitude())
                ));

                Map<String, Object> body = Map.of("jobs", jobs, "vehicles", vehicles);

                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                headers.set("Authorization", key);
                HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

                ResponseEntity<String> response = restTemplate.postForEntity(
                    "https://api.openrouteservice.org/optimization", entity, String.class);

                if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                    throw new RuntimeException("ORS Optimization HTTP " + response.getStatusCode());
                }

                JsonNode root = objectMapper.readTree(response.getBody());
                JsonNode routes = root.path("routes");
                if (!routes.isArray() || routes.isEmpty()) {
                    throw new RuntimeException("ORS Optimization: empty routes array");
                }

                JsonNode steps = routes.get(0).path("steps");
                if (!steps.isArray()) throw new RuntimeException("ORS Optimization: empty steps array");

                List<com.newplanner.entity.Activity> optimizedSequence = new ArrayList<>();
                for (JsonNode step : steps) {
                    if ("job".equals(step.path("type").asText())) {
                        int jobId = step.path("id").asInt();
                        optimizedSequence.add(places.get(jobId));
                    }
                }

                // If some jobs were omitted (unroutable), append them at the end safely
                if (optimizedSequence.size() < places.size()) {
                    for (com.newplanner.entity.Activity p : places) {
                        if (!optimizedSequence.contains(p)) optimizedSequence.add(p);
                    }
                }

                log.info("ORS Optimization successful for {} places.", places.size());
                return optimizedSequence;
            });
        } catch (Exception e) {
            log.warn("ORS Optimization skipped/failed — falling back to pipeline logic. Reason: {}", e.getMessage());
            return null; // Will fallback gracefully in pipeline
        }
    }


    /**
     * ORS Matrix API — ONE call for all N consecutive transit durations.
     * Falls back through all ORS keys if any one fails.
     * Returns String[] where index i = transit label from place[i] → place[i+1].
     * Last element is always "None".
     */
    public String[] calculateTransitMatrix(List<double[]> latLngList) {
        int n = latLngList.size();
        String[] results = new String[n];
        results[n - 1] = "None";
        if (n < 2) return results;

        // Build [lng, lat] coordinate list (ORS format)
        List<List<Double>> coords = new ArrayList<>();
        for (double[] ll : latLngList) coords.add(List.of(ll[1], ll[0]));

        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < n; i++) indices.add(i);

        Map<String, Object> body = Map.of(
            "locations", coords,
            "sources", indices,
            "destinations", indices,
            "metrics", List.of("duration")
        );

        try {
            JsonNode durationsMatrix = keyService.tryWithFallback(keyService.getOrsKeys(), key -> {
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                headers.set("Authorization", key);
                HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
                ResponseEntity<String> response = restTemplate.postForEntity(
                    "https://api.openrouteservice.org/v2/matrix/driving-car", entity, String.class);
                if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                    throw new RuntimeException("ORS Matrix HTTP " + response.getStatusCode());
                }
                JsonNode matrix = objectMapper.readTree(response.getBody()).path("durations");
                if (matrix.isMissingNode()) throw new RuntimeException("ORS Matrix: missing 'durations' field");
                return matrix;
            });

            for (int i = 0; i < n - 1; i++) {
                double secs = durationsMatrix.get(i).get(i + 1).asDouble(-1);
                results[i] = secs < 0
                    ? haversineLabel(latLngList.get(i), latLngList.get(i + 1))
                    : formatDuration(secs, "car");
            }
            log.info("ORS Matrix: {} transit durations resolved in 1 call.", n - 1);

        } catch (Exception e) {
            log.error("ORS Matrix: all keys exhausted — using Haversine fallback. {}", e.getMessage());
            for (int i = 0; i < n - 1; i++) {
                results[i] = haversineLabel(latLngList.get(i), latLngList.get(i + 1));
            }
        }
        return results;
    }

    /**
     * Single-pair directions — returns transit duration + route geometry.
     * Used by TripUpdateService (live reorder). Falls back through all ORS keys.
     */
    public RoutingResult calculateTransitDuration(Double sLat, Double sLng, Double eLat, Double eLng) {
        if (sLat == null || sLng == null || eLat == null || eLng == null) {
            return new RoutingResult("None", null);
        }
        try {
            return keyService.tryWithFallback(keyService.getOrsKeys(), key -> {
                String url = String.format(
                    "https://api.openrouteservice.org/v2/directions/driving-car?api_key=%s&start=%s,%s&end=%s,%s",
                    key, sLng, sLat, eLng, eLat);
                ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
                if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                    throw new RuntimeException("ORS Directions HTTP " + response.getStatusCode());
                }
                return parseDirectionsResponse(response.getBody(), "car");
            });
        } catch (Exception e) {
            log.warn("ORS Directions: all keys exhausted — Haversine fallback. {}", e.getMessage());
            return new RoutingResult(haversineLabel(new double[]{sLat, sLng}, new double[]{eLat, eLng}), null);
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private RoutingResult parseDirectionsResponse(String body, String mode) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        JsonNode features = root.path("features");
        if (features.isArray() && !features.isEmpty()) {
            double secs = features.get(0).path("properties").path("summary").path("duration").asDouble();
            String geometry = features.get(0).path("geometry").toString();
            return new RoutingResult(formatDuration(secs, mode), geometry);
        }
        throw new RuntimeException("ORS Directions: empty features array");
    }

    private String formatDuration(double seconds, String mode) {
        int mins = (int) Math.max(1, Math.round(seconds / 60.0));
        if (mins < 60) return mins + " mins by " + mode;
        return (mins / 60) + "h " + (mins % 60) + "m by " + mode;
    }

    private String haversineLabel(double[] from, double[] to) {
        final double R = 6371.0;
        double dLat = Math.toRadians(to[0] - from[0]);
        double dLon = Math.toRadians(to[1] - from[1]);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                 + Math.cos(Math.toRadians(from[0])) * Math.cos(Math.toRadians(to[0]))
                 * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double distKm = R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        int mins = (int) Math.max(1, Math.round((distKm / 40.0) * 60.0));
        if (mins < 60) return mins + " mins (~est)";
        return (mins / 60) + "h " + (mins % 60) + "m (~est)";
    }
}
