package com.newplanner.controller;

import com.newplanner.dto.ItineraryRequest;
import com.newplanner.entity.Itinerary;
import com.newplanner.entity.ItineraryDay;
import com.newplanner.entity.Activity;
import com.newplanner.repository.ItineraryRepository;
import com.newplanner.service.GenerationPipelineService;
import com.newplanner.service.TripUpdateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/trips")
@RequiredArgsConstructor
@Slf4j

public class TripController {

    private final GenerationPipelineService pipelineService;
    private final TripUpdateService updateService;
    private final ItineraryRepository itineraryRepository;

    /** GET /trips?firebaseUid=xyz — fetch all trips for a user's dashboard */
    @GetMapping
    public ResponseEntity<List<Itinerary>> getTripsForUser(@RequestParam String firebaseUid) {
        log.info("Fetching all trips for user: {}", firebaseUid);
        List<Itinerary> trips = itineraryRepository.findByUserFirebaseUidOrderByCreatedAtDesc(firebaseUid);
        return ResponseEntity.ok(trips);
    }

    /**
     * GET /trips/{id} — fetch a single trip by ID (also enables page-refresh
     * recovery)
     */
    @GetMapping("/{id}")
    public ResponseEntity<Itinerary> getTripById(@PathVariable String id) {
        log.info("Fetching trip by ID: {}", id);
        return itineraryRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * DELETE /trips/{id} — permanently deletes the trip and ALL cascaded data from
     * MySQL
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTrip(@PathVariable String id) {
        log.info("Deleting trip and all cascaded data for ID: {}", id);
        if (!itineraryRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        itineraryRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * PUT /trips/days/{dayId}/activities/weather/sync — triggers a mathematically
     * rigid live synchronization of predictive meteorological loops
     */
    @PutMapping("/days/{dayId}/activities/weather/sync")
    public ResponseEntity<ItineraryDay> syncDayWeather(@PathVariable String dayId) {
        log.info("Processing absolute Live Weather Matrix Sync for Day UUID: {}", dayId);
        try {
            ItineraryDay syncedDay = updateService.syncWeatherForDay(dayId);
            return ResponseEntity.ok(syncedDay);
        } catch (Exception e) {
            log.error("Failed to geometrically sync meteorological payload", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * PUT /trips/days/{dayId}/activities — reorder day activities with ORS
     * recalculation
     */
    @PutMapping("/days/{dayId}/activities")
    public ResponseEntity<ItineraryDay> updateDayActivities(
            @PathVariable String dayId,
            @RequestBody List<Activity> updatedActivities) {
        log.info("Processing live reorder for Day UUID: {}", dayId);
        try {
            ItineraryDay updatedDay = updateService.updateDayActivities(dayId, updatedActivities);
            return ResponseEntity.ok(updatedDay);
        } catch (Exception e) {
            log.error("Failed to update day activities", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * GET /trips/date-conflict?firebaseUid=&startDate=&endDate=
     * Checks if the requested date range overlaps with any existing trip for the user.
     * Returns {conflict: false} or {conflict: true, destination, startDate, endDate}.
     */
    @GetMapping("/date-conflict")
    public ResponseEntity<Map<String, Object>> checkDateConflict(
            @RequestParam String firebaseUid,
            @RequestParam String startDate,
            @RequestParam String endDate) {

        if (firebaseUid == null || firebaseUid.isBlank()) {
            return ResponseEntity.ok(Map.of("conflict", false)); // unauthenticated — skip check
        }

        LocalDate newStart = LocalDate.parse(startDate);
        LocalDate newEnd   = LocalDate.parse(endDate);

        List<com.newplanner.entity.Itinerary> overlapping =
                itineraryRepository.findOverlappingTrips(firebaseUid, newStart, newEnd);

        if (overlapping.isEmpty()) {
            return ResponseEntity.ok(Map.of("conflict", false));
        }

        com.newplanner.entity.Itinerary clash = overlapping.get(0);
        return ResponseEntity.ok(Map.of(
            "conflict",    true,
            "destination", clash.getDestination(),
            "startDate",   clash.getStartDate().toString(),
            "endDate",     clash.getEndDate().toString()
        ));
    }

    /** POST /trips/generate — triggers the full AI ML pipeline */
    @PostMapping("/generate")
    public CompletableFuture<ResponseEntity<?>> generateTrip(
            @Validated @RequestBody ItineraryRequest request) {

        log.info("Processing trip generation request for: {}", request.getDestination());

        // ── Server-side date conflict guard ────────────────────────────────────
        // Run BEFORE the async pipeline starts so we can return a synchronous 409.
        if (request.getFirebaseUid() != null && !request.getFirebaseUid().isBlank()) {
            try {
                LocalDate newStart = LocalDate.parse(request.getStartDate().split("T")[0]);
                LocalDate newEnd   = LocalDate.parse(request.getEndDate().split("T")[0]);

                List<com.newplanner.entity.Itinerary> clashing =
                        itineraryRepository.findOverlappingTrips(request.getFirebaseUid(), newStart, newEnd);

                if (!clashing.isEmpty()) {
                    com.newplanner.entity.Itinerary clash = clashing.get(0);
                    log.warn("Date conflict for uid={}: clashes with trip '{}' ({} → {})",
                            request.getFirebaseUid(), clash.getDestination(),
                            clash.getStartDate(), clash.getEndDate());

                    return CompletableFuture.completedFuture(
                        ResponseEntity.status(409).body(Map.of(
                            "error",       "DATE_CONFLICT",
                            "message",     "You already have a trip scheduled during these dates.",
                            "destination", clash.getDestination(),
                            "startDate",   clash.getStartDate().toString(),
                            "endDate",     clash.getEndDate().toString()
                        ))
                    );
                }
            } catch (Exception e) {
                log.warn("Date conflict pre-check failed (non-fatal): {}", e.getMessage());
                // fail-open — let the pipeline proceed if the check itself errors
            }
        }

        return pipelineService.orchestrateTripGeneration(request)
                .handle((itinerary, ex) -> {
                    if (ex != null) {
                        log.error("ABSOLUTE PIPELINE CRASH DETECTED: ", ex);
                        return ResponseEntity.status(503).body(Map.of(
                            "error", "PIPELINE_FAILED",
                            "message", "Trip generation failed. External travel matching services are currently unavailable or overloaded. Please try again in a few moments."
                        ));
                    }
                    return ResponseEntity.ok(itinerary);
                });
    }


}
