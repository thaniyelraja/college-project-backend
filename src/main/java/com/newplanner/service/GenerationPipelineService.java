package com.newplanner.service;

import com.newplanner.dto.ItineraryRequest;
import com.newplanner.entity.Itinerary;
import com.newplanner.entity.ItineraryDay;
import com.newplanner.entity.Activity;
import com.newplanner.entity.ExpenseTracker;
import com.newplanner.repository.ItineraryRepository;
import com.newplanner.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * OTM-First Deterministic Pipeline
 * ─────────────────────────────────────────────────────────────────────────────
 * Phase 1 : OTM Fetch + Interest Filter + Place Selection (no AI)
 * Phase 2 : ORS Nearest-Neighbor Route Optimization + Transit Times (no AI)
 * Phase 3 : Per-Place Individual Weather Fetch (most critical)
 * Phase 4 : Single AI Format Call — assigns times/descriptions/themes only
 * ─────────────────────────────────────────────────────────────────────────────
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GenerationPipelineService {

    private final AiFallbackService aiFallbackService;
    private final ItineraryRepository itineraryRepository;
    private final UserRepository userRepository;
    private final WeatherEnrichmentService weatherService;
    private final OrsRoutingService orsService;
    private final OpenTripMapService openTripMapService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ─── Interest Key → OTM kinds mapping (matches frontend Step2Preferences keys) ─
    // Used ONLY for post-fetch filtering: blocked (score=0) and priority boost (score=2).
    // OTM fetch always uses 'interesting_places' as the umbrella — sub-kinds filter the response.
    // Valid OTM sub-category names (dot-notation parents → sub kinds accepted by API):
    //   historic → castles, monuments, churches, archaeological_sites
    //   cultural → museums, theatres, cinemas, libraries
    //   natural  → natural_springs, natural_water, natural_peaks, beaches
    //   amusements → amusements (top-level OK)
    //   sport → sport (top-level OK)
    //   shops → shops (top-level OK)
    //   foods → foods (top-level OK)
    private static final Map<String, String[]> INTEREST_OTM_MAP = Map.of(
        "historyculture", new String[]{"historic", "cultural", "monuments", "archaeological_sites", "castles", "churches", "museums"},
        "nature",         new String[]{"natural", "natural_springs", "natural_water", "natural_peaks", "beaches"},
        "entertainment",  new String[]{"amusements", "theatres", "cinemas"},
        "food",           new String[]{"foods"},
        "sports",         new String[]{"sport"},
        "shopping",       new String[]{"shops"},
        "adventure",      new String[]{"amusements", "sport", "natural"},
        "relaxing",       new String[]{"natural", "beaches", "gardens"}
    );

    @Async
    public CompletableFuture<Itinerary> orchestrateTripGeneration(ItineraryRequest request) {
        try {
        // Calculate optimal pool size mathematically based on available time and companion type constraints
        int dynamicPlacesPerDay = calculatePlacesPerDay(request);
        int poolSize = request.getDurationDays() * dynamicPlacesPerDay;
        
        log.info("=== OTM-First Pipeline START: {} | {} days | {} places/day | Menu Pool: {} | group: {} | budget: {} ===",
                request.getDestination(), request.getDurationDays(), dynamicPlacesPerDay, poolSize,
                request.getGroupType(), request.getBudgetType());

            // =================================================================
            // PHASE 1: OTM FETCH → INTEREST FILTER → SELECTION
            // =================================================================
            log.info("Phase 1: Fetching OTM places...");

            // Build dynamic OTM kinds from active interests (score >= 1)
            String activeKinds = buildActiveKinds(request);
            log.info("Phase 1: OTM kinds param = [{}]", activeKinds);

            // Auto-scale radius: base 30km + 10km per day
            int radius = 30000 + (request.getDurationDays() * 10000);
            List<Activity> candidatePlaces = new ArrayList<>();
            
            try {
                JsonNode otmNode = openTripMapService.fetchBasePlaces(
                        request.getLat(), request.getLng(), String.valueOf(radius), activeKinds);

                candidatePlaces = extractAndFilterOtmPlaces(otmNode, request);

                // If not enough places, widen radius and re-fetch (up to 80km)
                while (candidatePlaces.size() < poolSize && radius < 80000) {
                    radius += 15000;
                    log.info("Phase 1: Only {} places found, widening radius to {}m", candidatePlaces.size(), radius);
                    otmNode = openTripMapService.fetchBasePlaces(
                            request.getLat(), request.getLng(), String.valueOf(radius), activeKinds);
                    candidatePlaces = extractAndFilterOtmPlaces(otmNode, request);
                }
            } catch (Exception e) {
                log.warn("CRITICAL OTM NETWORK FAILURE (e.g. DNS block or keys exhausted): {}. Pipeline triggering emergency AI Place Generator.", e.getMessage());
            }

            // Absolutely ensure we have the required number of places (invoking AI hallucination if OTM failed or under-delivered)
            if (candidatePlaces.size() < poolSize) {
                log.warn("OTM returned {}/{} places. Using AI fallback to generate the rest...", candidatePlaces.size(), poolSize);
                List<Activity> fallbackPlaces = aiFallbackService.generateFallbackPlaces(
                        request.getDestination(), request.getLat(), request.getLng(), poolSize - candidatePlaces.size(), activeKinds);
                candidatePlaces.addAll(fallbackPlaces);
            }

            // Cap to exactly poolSize
            if (candidatePlaces.size() > poolSize) {
                candidatePlaces = candidatePlaces.subList(0, poolSize);
            }
            log.info("Phase 1 complete: {} places selected.", candidatePlaces.size());

            // =================================================================
            // PHASE 2: VRP ROUTE OPTIMIZATION & WEATHER (Pool Pre-processing)
            // =================================================================
            log.info("Phase 2: ORS VRP Route Optimization and per-place weather fetch...");

            // Use real ORS Vehicle Routing Problem optimization API
            List<Activity> orderedPlaces = orsService.optimizeRoute(candidatePlaces);
            
            // Graceful geometry-only fallback if the API limits out or fails
            if (orderedPlaces == null || orderedPlaces.isEmpty()) {
                log.warn("ORS Optimization unavailable. Falling back to geometric Nearest-Neighbor sort.");
                orderedPlaces = nearestNeighborSort(candidatePlaces);
            }
                        LocalDate tripStartDate = LocalDate.parse(request.getStartDate().split("T")[0]);

            for (int i = 0; i < orderedPlaces.size(); i++) {
                Activity act = orderedPlaces.get(i);
                // Distribute weather queries logically across the trip days
                int dayIndex = (int) (((double) i / poolSize) * request.getDurationDays());
                LocalDate activityDate = tripStartDate.plusDays(dayIndex);

                // Use predictive weather for the specific place + day
                try {
                    Thread.sleep(200); // Light pacing for weather API
                    String[] weather = weatherService.derivePredictiveWeather(
                            act.getLatitude(), act.getLongitude(),
                            activityDate, "10:00"); // Use morning time as weather anchor
                    act.setWeatherCondition(weather[0]);
                    act.setCriticalWeatherAlert(Boolean.parseBoolean(weather[1]));
                    log.info("  Weather for {} (Day {}, {}): {} | Critical: {}",
                            act.getPlaceName(), dayIndex + 1, activityDate, weather[0], weather[1]);
                } catch (Exception e) {
                    log.warn("WEATHER API FAILURE for {}. Assigning safe Clear default.", act.getPlaceName());
                    act.setWeatherCondition("Clear");
                    act.setCriticalWeatherAlert(false);
                }
            }
            log.info("Phase 2 complete: per-place weather fetched for all {} menu activities.", orderedPlaces.size());

            // =================================================================
            // PHASE 3: AI FORMATTING & PACING SELECTION
            // =================================================================
            log.info("Phase 3: Single AI formatting call (Selecting from pool)...");

            // We pass 6 so the context matrix uses 6 chunks for Day tagging hints
            String contextMatrix = buildContextMatrix(orderedPlaces, tripStartDate, 6);
            int start = request.getStartTime() != null ? request.getStartTime() : 8;
            int end   = request.getEndTime()   != null ? request.getEndTime()   : 18;
            double availableHours = Math.max(end - start, 4.0);

            String timeBounds = String.format("%02d:00 to %02d:00", start, end);

            // Companion-aware pacing guidance for the AI
            String companionNote = switch (request.getGroupType() != null ? request.getGroupType().toLowerCase() : "couple") {
                case "solo"    -> "Solo traveller — can move at a fast pace, squeeze in more stops.";
                case "family"  -> "Family group — include rest gaps between activities, relaxed pace.";
                case "friends" -> "Group of friends — allow social time at each location, moderate pace.";
                default        -> "Couple — balanced pace with some leisure time at each place.";
            };

            String budgetLabel = switch (request.getBudgetType() != null ? request.getBudgetType().toLowerCase() : "normal") {
                case "economy" -> "Economy (budget-friendly, prefer free/low-cost attractions)";
                case "luxury"  -> "Luxury (premium experiences, high-end venues preferred)";
                default        -> "Normal (mix of free and paid mid-range attractions)";
            };
            double derivedBudget = request.getDerivedBudget();

            String aiSys = "You are a travel itinerary formatter with a deep knowledge of global tourism economics. You receive a fixed ordered list of candidate places " +
                    "with their OTM data and weather. Your ONLY job is to produce a final day-wise " +
                    "JSON itinerary. Rules: " +
                    "(1) You MUST select a realistic subset of the provided places. DO NOT hallucinate places not on the list. Maintain their general geographical sequence if possible. " +
                    "(2) CRITICAL TIMING RULE: Assign realistic startTime and endTime STRICTLY within the given daily window. " +
                    "No activity may start before or end after the window. Factor 15-30min transit gaps between activities. " +
                    "(3) Adjust visit duration per place and total places per day based on the companion type — families and groups need more time per stop and fewer stops in total. " +
                    "(4) Write a 2-sentence description for each place using its OTM kinds and rate for context. " +
                    "(5) Give each day a short creative theme. " +
                    "(6) Dynamically calculate a realistic 'estimatedCost' (in INR) for each Day based on the destination region, budget tier, and places scheduled. Factor in entry fees, average meals, and transport. " +
                    "Return ONLY raw JSON: {\"days\":[{\"dayNumber\":1,\"theme\":\"string\",\"estimatedCost\":2500,\"activities\":[" +
                    "{\"placeName\":\"string\",\"startTime\":\"09:00\",\"endTime\":\"10:30\",\"description\":\"string\"}]}]}";

            String aiPrompt = "Format the following " + request.getDurationDays() + "-day itinerary for "
                    + request.getDestination() + ".\n"
                    + "DAILY WINDOW: " + timeBounds + " | Group: " + request.getGroupType()
                    + " | Budget Tier: " + budgetLabel + " (Note: Destination cost-of-living applies. Provide realistic INR daily costs.)\n"
                    + "COMPANION CONTEXT: " + companionNote + "\n"
                    + "SCHEDULING: All " + orderedPlaces.size() + " places below MUST appear in the output. "
                    + "This list has been mathematically paced for the available hours (" + availableHours + "h) and " + request.getGroupType() + " group pace. "
                    + "Factor proper transit time into scheduling — do not under OR overpack.\n\n"
                    + contextMatrix;

            String rawAiJson = aiFallbackService.callAi(aiSys, aiPrompt);
            log.info("Phase 3 AI raw output length: {} chars", rawAiJson.length());

            String cleanedAiJson = rawAiJson.replaceAll("(?s)```json", "").replaceAll("```", "").trim();

            // =================================================================
            // MAP JSON → JPA ENTITIES (real OTM data fills coords / rate / kinds)
            // =================================================================
            Itinerary itinerary = new Itinerary();
            itinerary.setDestination(request.getDestination());
            itinerary.setDestinationLat(request.getLat());
            itinerary.setDestinationLng(request.getLng());
            itinerary.setNumberOfDays(request.getDurationDays());
            itinerary.setBudget(request.getDerivedBudget());
            itinerary.setGroupType(request.getGroupType() != null ? request.getGroupType() : "Couple");
            itinerary.setStartDate(tripStartDate);
            itinerary.setEndDate(LocalDate.parse(request.getEndDate().split("T")[0]));
            itinerary.setDays(new ArrayList<>());

            JsonNode aiRoot = objectMapper.readTree(cleanedAiJson);
            JsonNode daysNode = aiRoot.has("days") ? aiRoot.get("days") : aiRoot;

            // Build a lookup map: normalised name → ordered Activity (with real OTM data)
            java.util.Map<String, Activity> dataMap = new java.util.LinkedHashMap<>();
            for (Activity a : orderedPlaces) {
                dataMap.put(normalizeName(a.getPlaceName()), a);
            }

            // Track which orderedPlaces index to use as a positional fallback
            int fallbackPointer = 0;

            if (daysNode != null && daysNode.isArray()) {
                for (JsonNode dayNode : daysNode) {
                    ItineraryDay day = new ItineraryDay();
                    day.setItinerary(itinerary);
                    day.setDayNumber(dayNode.has("dayNumber") ? dayNode.get("dayNumber").asInt() : 1);
                    day.setDate(tripStartDate.plusDays(day.getDayNumber() - 1));
                    day.setTheme(dayNode.has("theme") ? dayNode.get("theme").asText() : "Exploration Day");
                    day.setEstimatedCost(dayNode.has("estimatedCost") ? dayNode.get("estimatedCost").asDouble() : request.getDerivedBudget() / request.getDurationDays());
                    day.setActivities(new ArrayList<>());

                    JsonNode actArray = dayNode.get("activities");
                    if (actArray != null && actArray.isArray()) {
                        for (JsonNode aNode : actArray) {
                            String aiName = aNode.has("placeName") ? aNode.get("placeName").asText() : "";

                            // Match AI-assigned name back to real OTM data
                            Activity realData = findBestMatch(aiName, dataMap);

                            // Positional fallback: if AI hallucinates a name, use next unused real place
                            if (realData == null && fallbackPointer < orderedPlaces.size()) {
                                realData = orderedPlaces.get(fallbackPointer);
                                log.warn("AI name '{}' not matched — using positional fallback: {}", aiName, realData.getPlaceName());
                            }
                            if (realData == null) continue;

                            // Remove matched entry so it can't be assigned twice
                            dataMap.remove(normalizeName(realData.getPlaceName()));
                            fallbackPointer++;

                            Activity finalAct = new Activity();
                            finalAct.setDay(day);
                            finalAct.setPlaceName(realData.getPlaceName());      // Real OTM name
                            finalAct.setLatitude(realData.getLatitude());        // Real OTM lat
                            finalAct.setLongitude(realData.getLongitude());      // Real OTM lng
                            finalAct.setOtmRate(realData.getOtmRate());          // OTM rating
                            finalAct.setOtmKinds(realData.getOtmKinds());        // OTM categories
                            finalAct.setWeatherCondition(realData.getWeatherCondition());
                            finalAct.setCriticalWeatherAlert(realData.isCriticalWeatherAlert());
                            finalAct.setRouteGeometry(null); // Calculated in Phase 4
                            finalAct.setNextTransitDurationStr(null); // Calculated in Phase 4
                            finalAct.setStartTime(aNode.has("startTime") ? aNode.get("startTime").asText() : "09:00");
                            finalAct.setEndTime(aNode.has("endTime")     ? aNode.get("endTime").asText()   : "10:30");
                            finalAct.setDescription(aNode.has("description") ? aNode.get("description").asText() : "A curated stop at this location.");
                            finalAct.setFoodBlock(false);

                            day.getActivities().add(finalAct);
                        }
                    }
                    itinerary.getDays().add(day);
                }
            }

            // Note: Leftover places in dataMap are intentionally discarded to respect the AI's paced subset.


            // Sync User + Expense Ledger
            if (request.getFirebaseUid() != null && !request.getFirebaseUid().isBlank()) {
                com.newplanner.entity.User user = userRepository.findById(request.getFirebaseUid()).orElse(null);
                if (user == null) {
                    user = new com.newplanner.entity.User();
                    user.setFirebaseUid(request.getFirebaseUid());
                    user.setName("Voyager");
                    user.setEmail(request.getFirebaseUid() + "@placeholder.com");
                    userRepository.save(user);
                }
                itinerary.setUser(user);
            }

            // =================================================================
            // PHASE 4: PRECISE TRANSIT MATRIX CALCULATION (Post-Selection)
            // =================================================================
            log.info("Phase 4: Calculating exact ORS Transit Matrix for AI-selected places...");
            List<Activity> finalSelectedActivities = new ArrayList<>();
            for (ItineraryDay d : itinerary.getDays()) {
                if (d.getActivities() != null) {
                    finalSelectedActivities.addAll(d.getActivities());
                }
            }
            
            if (!finalSelectedActivities.isEmpty()) {
                List<double[]> finalCoords = new ArrayList<>();
                for (Activity a : finalSelectedActivities) {
                    finalCoords.add(new double[]{a.getLatitude(), a.getLongitude()});
                }
                try {
                    String[] transitLabels = orsService.calculateTransitMatrix(finalCoords);
                    for (int i = 0; i < finalSelectedActivities.size(); i++) {
                        finalSelectedActivities.get(i).setNextTransitDurationStr(transitLabels[i]);
                        log.info("  Transit Chosen [{}→next]: {}", finalSelectedActivities.get(i).getPlaceName(), transitLabels[i]);
                    }
                    log.info("Phase 4 complete: {} precise transit durations resolved.", finalSelectedActivities.size() - 1);
                } catch (Exception e) {
                    log.warn("ORS NETWORK FAILURE inside post-processing. Using rapid fallback transits: {}", e.getMessage());
                    for (int i = 0; i < finalSelectedActivities.size(); i++) {
                        finalSelectedActivities.get(i).setNextTransitDurationStr("15 mins");
                    }
                }
            }

            // ─── Post-Process Dynamic Budget calculation ─────────────────────────────────
            // Aggregate all the daily estimated costs the AI provided into the overall itinerary budget
            double totalCalculatedBudget = 0.0;
            for (ItineraryDay d : itinerary.getDays()) {
                if (d.getEstimatedCost() != null) {
                    totalCalculatedBudget += d.getEstimatedCost();
                } else {
                    totalCalculatedBudget += (request.getDerivedBudget() / request.getDurationDays());
                }
            }
            itinerary.setBudget(totalCalculatedBudget);

            // Expense Tracker logic...
            ExpenseTracker tracker = new ExpenseTracker();
            tracker.setItinerary(itinerary);
            // Link the specific user-provided budget from the request to the financial ledger
            tracker.setBaseBudgetLimit(totalCalculatedBudget);
            tracker.setMemberNames(new ArrayList<>());
            itinerary.setExpenseTracker(tracker);

            Itinerary saved = itineraryRepository.save(itinerary);
            log.info("=== OTM-First Pipeline COMPLETE. Saved ID: {} | Days: {} | Total Activities: {} ===",
                    saved.getId(), saved.getDays().size(),
                    saved.getDays().stream().mapToInt(d -> d.getActivities().size()).sum());
            return CompletableFuture.completedFuture(saved);

        } catch (Exception e) {
            log.error("OTM-First Pipeline FAILED: ", e);
            throw new RuntimeException("OTM-First Pipeline Failed: " + e.getMessage(), e);
        }
    }

    // ─── OTM Extraction + Strict Tourist-Only Filtering ──────────────────────────

    private List<Activity> extractAndFilterOtmPlaces(JsonNode otmNode, ItineraryRequest request) {
        List<Activity> result = new ArrayList<>();
        if (otmNode == null || !otmNode.has("features") || !otmNode.get("features").isArray()) {
            return result;
        }

        java.util.Set<String> seenNames = new java.util.HashSet<>();

        // Build blocked kinds (user score == 0 = Not Interested)
        java.util.Set<String> blockedKinds = new java.util.HashSet<>();
        // Build priority kinds (user score == 2 = Interested → sort to top)
        java.util.Set<String> priorityKinds = new java.util.HashSet<>();

        if (request.getInterests() != null) {
            for (Map.Entry<String, Integer> entry : request.getInterests().entrySet()) {
                String normalizedKey = entry.getKey().toLowerCase().replace(" ", "").replace("_", "").replace("-", "");
                String[] mapped = INTEREST_OTM_MAP.get(normalizedKey);
                if (mapped == null) continue;
                int score = entry.getValue() == null ? 1 : entry.getValue();
                if (score == 0) {
                    for (String k : mapped) blockedKinds.add(k);
                } else if (score == 2) {
                    for (String k : mapped) priorityKinds.add(k);
                }
            }
        }
        log.info("Phase 1 filter — blocked: {} | priority: {}", blockedKinds, priorityKinds);

        int totalRaw = 0, rejectedNoName = 0, rejectedNoRate = 0, rejectedNonTouristKind = 0,
                rejectedNonTouristName = 0, rejectedBlocked = 0, rejectedDuplicate = 0, rejectedNoCoords = 0;

        for (JsonNode feature : otmNode.get("features")) {
            totalRaw++;
            JsonNode props = feature.path("properties");

            // ── 1. Must have a name ──────────────────────────────────────────────
            String name = props.path("name").asText("").trim();
            if (name.isEmpty()) { rejectedNoName++; continue; }

            // ── 2. Must have a non-zero OTM rate (unrated = unknown place) ───────
            double rate = props.path("rate").asDouble(-1.0);
            if (rate <= 0.0) { rejectedNoRate++; continue; }

            // ── 3. Must have valid coordinates ────────────────────────────────────
            double lng = feature.path("geometry").path("coordinates").path(0).asDouble();
            double lat = feature.path("geometry").path("coordinates").path(1).asDouble();
            if (lat == 0.0 && lng == 0.0) { rejectedNoCoords++; continue; }

            String kinds = props.path("kinds").asText("").toLowerCase();

            // ── 4. Reject non-tourist kinds via kinds string (contains check) ─────
            if (isNonTouristKind(kinds)) { rejectedNonTouristKind++; continue; }

            // ── 5. Reject non-tourist / mundane names ────────────────────────────
            if (isNonTouristName(name)) { rejectedNonTouristName++; continue; }

            // ── 6. Reject blocked interest kinds (user score == 0) ───────────────
            boolean blocked = false;
            for (String bk : blockedKinds) {
                if (kinds.contains(bk)) { blocked = true; break; }
            }
            if (blocked) { rejectedBlocked++; continue; }

            // ── 7. Deduplication ─────────────────────────────────────────────────
            String normName = normalizeName(name);
            if (seenNames.contains(normName) || normName.length() < 3) { rejectedDuplicate++; continue; }
            seenNames.add(normName);

            // ── 8. Priority boost for user's Interested categories ───────────────
            boolean isPriority = false;
            for (String pk : priorityKinds) {
                if (kinds.contains(pk)) { isPriority = true; break; }
            }

            Activity act = new Activity();
            act.setPlaceName(name);
            act.setLatitude(lat);
            act.setLongitude(lng);
            act.setOtmRate(isPriority ? rate + 100.0 : rate);
            act.setOtmKinds(kinds.length() > 120 ? kinds.substring(0, 120) : kinds);
            result.add(act);
        }

        log.info("Phase 1 filter stats — raw:{} | passed:{} | noName:{} | noRate:{} | noCoords:{} | nonTouristKind:{} | nonTouristName:{} | blocked:{} | duplicate:{}",
                totalRaw, result.size(), rejectedNoName, rejectedNoRate, rejectedNoCoords,
                rejectedNonTouristKind, rejectedNonTouristName, rejectedBlocked, rejectedDuplicate);

        // Sort: priority (Interested) places first, then by OTM rate descending
        result.sort((a, b) -> Double.compare(
                b.getOtmRate() != null ? b.getOtmRate() : 0.0,
                a.getOtmRate() != null ? a.getOtmRate() : 0.0));

        // Restore real OTM rate (remove the +100 priority boost used for sorting)
        result.forEach(a -> {
            if (a.getOtmRate() != null && a.getOtmRate() > 10.0) {
                a.setOtmRate(a.getOtmRate() - 100.0);
            }
        });
        return result;
    }


    // ─── Dynamic places-per-day calculation ──────────────────────────────────────
    // Based on: available travel hours × companion pace factor ÷ avg slot (1.5 hrs each)
    // Solo moves fastest; Family needs the most buffer time.

    private int calculatePlacesPerDay(ItineraryRequest request) {
        int start = request.getStartTime() != null ? request.getStartTime() : 9;
        int end   = request.getEndTime()   != null ? request.getEndTime()   : 18;
        double availableHours = Math.max(end - start, 4.0); // minimum 4h enforced

        // Reserve ~1 hr for meals/breaks; each activity slot = avg 1.5 hrs (visit + transit)
        double effectiveHours = Math.max(availableHours - 1.0, 2.0);
        int rawCount = (int) (effectiveHours / 1.5);

        // Adjust per companion type
        String group = request.getGroupType() != null ? request.getGroupType().toLowerCase() : "couple";
        rawCount = switch (group) {
            case "family"  -> rawCount - 1;   // slower pace, kids/elders need buffer
            case "friends" -> rawCount;        // social stops, moderate pace
            case "solo"    -> rawCount + 1;    // fast mover, fits extra stops
            default        -> rawCount;        // couple — baseline
        };

        int result = Math.max(2, Math.min(rawCount, 6)); // clamp 2–6 depending on time tightness
        log.info("calculatePlacesPerDay: {}h window | group={} | raw={} | final={}",
                availableHours, group, rawCount, result);
        return result;
    }

    // ─── Build OTM kinds string for the fetch ────────────────────────────────────
    // OTM API IMPORTANT: The API rejects comma-separated mixed top-level kinds (400 error).
    // The correct approach: always fetch with "interesting_places" (OTM umbrella for all tourist POIs),
    // then apply the user's interest filter POST-FETCH via extractAndFilterOtmPlaces().
    // This method logs the incoming interests for debugging only.

    private String buildActiveKinds(ItineraryRequest request) {
        if (request.getInterests() == null || request.getInterests().isEmpty()) {
            log.warn("buildActiveKinds: interests map is NULL or EMPTY");
        } else {
            log.info("buildActiveKinds: raw interests from frontend → {}", request.getInterests());
            for (Map.Entry<String, Integer> entry : request.getInterests().entrySet()) {
                String rawKey = entry.getKey();
                String normalizedKey = rawKey.toLowerCase().replace(" ", "").replace("_", "").replace("-", "");
                int score = entry.getValue() == null ? 1 : entry.getValue();
                String[] mapped = INTEREST_OTM_MAP.get(normalizedKey);
                log.info("  interest key: '{}' → normalized: '{}' | score: {} | filter-kinds: {}",
                        rawKey, normalizedKey, score, mapped != null ? java.util.Arrays.toString(mapped) : "NO MATCH");
            }
        }
        // Always use the OTM umbrella kind — post-fetch filter applies user interests
        log.info("buildActiveKinds: OTM fetch kind → [interesting_places] (post-fetch filter applies interests)");
        return "interesting_places";
    }

    // ─── Nearest-Neighbor TSP (deterministic, no AI) ──────────────────────────────

    private List<Activity> nearestNeighborSort(List<Activity> places) {
        if (places.isEmpty()) return places;
        List<Activity> remaining = new ArrayList<>(places);
        List<Activity> ordered = new ArrayList<>();

        Activity current = remaining.remove(0);
        ordered.add(current);

        while (!remaining.isEmpty()) {
            Activity nearest = null;
            double minDist = Double.MAX_VALUE;
            for (Activity candidate : remaining) {
                double d = haversineDist(current.getLatitude(), current.getLongitude(),
                                         candidate.getLatitude(), candidate.getLongitude());
                if (d < minDist) { minDist = d; nearest = candidate; }
            }
            remaining.remove(nearest);
            ordered.add(nearest);
            current = nearest;
        }
        return ordered;
    }

    // ─── Context matrix for AI format call ────────────────────────────────────

    private String buildContextMatrix(List<Activity> places, LocalDate startDate, int perDay) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < places.size(); i++) {
            Activity a = places.get(i);
            int dayNum = (i / perDay) + 1;
            LocalDate date = startDate.plusDays(dayNum - 1);
            String transitToNext = (i < places.size() - 1) ? a.getNextTransitDurationStr() : "—";
            sb.append(String.format("Day%d | %s | lat:%.5f | lng:%.5f | rate:%.1f | kinds:%s | transit_next:%s | weather:%s(%s)\n",
                    dayNum,
                    a.getPlaceName(),
                    a.getLatitude(),
                    a.getLongitude(),
                    a.getOtmRate() != null ? a.getOtmRate() : 0.0,
                    a.getOtmKinds() != null ? a.getOtmKinds() : "—",
                    transitToNext,
                    a.getWeatherCondition() != null ? a.getWeatherCondition() : "Clear",
                    a.isCriticalWeatherAlert() ? "ALERT" : "ok"
            ));
        }
        return sb.toString();
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private String normalizeName(String name) {
        if (name == null) return "";
        return name.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    private Activity findBestMatch(String aiName, java.util.Map<String, Activity> dataMap) {
        if (aiName == null || aiName.isBlank()) return null;
        String key = normalizeName(aiName);
        if (dataMap.containsKey(key)) return dataMap.get(key);
        // Substring match for minor AI name variations
        for (Map.Entry<String, Activity> e : dataMap.entrySet()) {
            if (key.length() > 5 && e.getKey().contains(key)) return e.getValue();
            if (e.getKey().length() > 5 && key.contains(e.getKey())) return e.getValue();
        }
        return null;
    }

    private double haversineDist(double lat1, double lon1, double lat2, double lon2) {
        final double R = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                 + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                 * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /**
     * Rejects places whose OTM kinds string contains non-tourist infrastructure kinds.
     * Uses contains-check on the full kinds string (OTM returns comma-separated sub-kinds).
     * A place is blocked if ANY of its kinds indicate it is NOT a tourist destination.
     */
    private boolean isNonTouristKind(String kinds) {
        if (kinds == null || kinds.isBlank()) return true; // no kinds = unknown, reject
        // These OTM kind tokens indicate non-tourist places — reject if found anywhere in kinds string
        return kinds.contains("banks") || kinds.contains("atm") ||
               kinds.contains("hospitals") || kinds.contains("emergency") || kinds.contains("health") ||
               kinds.contains("clinics") || kinds.contains("pharmacy") || kinds.contains("medical") ||
               kinds.contains("police") || kinds.contains("fire_station") ||
               kinds.contains("schools") || kinds.contains("colleges") || kinds.contains("universities") ||
               kinds.contains("kindergartens") || kinds.contains("education") ||
               kinds.contains("bus_stops") || kinds.contains("bus_stations") || kinds.contains("railways") ||
               kinds.contains("airports") || kinds.contains("metro") || kinds.contains("transport") ||
               kinds.contains("gas_stations") || kinds.contains("fuel") || kinds.contains("parking") ||
               kinds.contains("supermarkets") || kinds.contains("convenience") || kinds.contains("marketplace") ||
               kinds.contains("industrial") || kinds.contains("factories") || kinds.contains("offices") ||
               kinds.contains("government") || kinds.contains("administrative") ||
               kinds.contains("waste") || kinds.contains("water_utilities") ||
               kinds.contains("cemeteries") || kinds.contains("cemetery") ||
               kinds.contains("guest_houses") || kinds.contains("hostels") || kinds.contains("campsites") ||
               kinds.contains("other");
    }

    /**
     * Rejects places whose NAME contains non-tourist or mundane keywords.
     * This is a secondary safety net over the kinds filter.
     */
    private boolean isNonTouristName(String name) {
        if (name == null) return true;
        String lower = name.toLowerCase();
        // Infrastructure / medical / education / transit
        return lower.contains("hospital")   || lower.contains("clinic")      || lower.contains("nursing home")  ||
               lower.contains("dispensary") || lower.contains("pharmacy")    || lower.contains("medical")       ||
               lower.contains("dental")     || lower.contains("dentist")     || lower.contains("health centre") ||
               lower.contains("health center") ||
               lower.contains("school")     || lower.contains("college")     || lower.contains("university")    ||
               lower.contains("institute")  || lower.contains("academy")     || lower.contains("polytechnic")   ||
               lower.contains("degree")     || lower.contains("tuition")     || lower.contains("coaching")      ||
               lower.contains("training")   || lower.contains("hostel")      ||
               lower.contains("bus stop")   || lower.contains("bus stand")   || lower.contains("bus terminus")  ||
               lower.contains("railway")    || lower.contains("metro station")|| lower.contains("airport")       ||
               lower.contains("bank")       || lower.contains(" atm")        || lower.contains("finance")       ||
               lower.contains("police")     || lower.contains("fire station") || lower.contains("post office")   ||
               lower.contains("government office") || lower.contains("collectorate") ||
               lower.contains("petrol")     || lower.contains("gas station")  || lower.contains("fuel")          ||
               lower.contains("indian oil") || lower.contains("bharat petroleum") || lower.contains("hp petrol") ||
               lower.contains("supermarket")|| lower.contains("grocery")     || lower.contains(" mart")         ||
               lower.contains(" store")     || lower.contains("pvt ltd")      || lower.contains("pvt.")          ||
               lower.contains(" ltd")       || lower.contains("distributor")  || lower.contains("agency")        ||
               lower.contains("typewriting")|| lower.contains("collective farm")|| lower.contains("farm house")  ||
               lower.contains("parking")    || lower.contains("cemetery")    || lower.contains("sewage")        ||
               lower.contains("water tank") || lower.contains("overhead tank")|| lower.contains("transformer");
    }
}
