package com.newplanner.dto;

import lombok.Data;
import jakarta.validation.constraints.*;
import java.util.Map;

@Data
public class ItineraryRequest {

    @NotBlank(message = "Destination cannot be blank")
    private String destination;

    @NotNull(message = "Latitude is required")
    private Double lat;

    @NotNull(message = "Longitude is required")
    private Double lng;

    @NotBlank(message = "Start date is required")
    private String startDate;

    @NotBlank(message = "End date is required")
    private String endDate;

    @Min(value = 1, message = "Duration must be at least 1")
    @Max(value = 10, message = "Duration cannot exceed 10 days")
    private Integer durationDays;

    @Min(0) @Max(23)
    private Integer startTime;

    @Min(0) @Max(23)
    private Integer endTime;

    @NotBlank
    private String groupType;

    /**
     * Budget tier selected by user: "economy", "normal", or "luxury".
     * OpenTripMap doesn't provide real price data, so exact min budget filtering
     * isn't possible. We estimate costs based on place categories and use the
     * tier as a max budget ceiling hint for the AI formatter.
     * Defaults to "normal" if not provided.
     */
    private String budgetType; // "economy" | "normal" | "luxury"

    @NotNull
    private Map<String, Integer> interests;

    // Firebase UID of the logged-in user — links trip to the user's dashboard
    private String firebaseUid;

    // Optional user text payload
    private String customInstructions;

    /**
     * Derives a max budget ceiling from the budgetType tier.
     * Economy ≈ ₹1,500/day | Normal ≈ ₹4,000/day | Luxury ≈ ₹12,000/day
     */
    public double getDerivedBudget() {
        int days = durationDays != null ? durationDays : 3;
        if (budgetType == null) return 4000.0 * days;
        return switch (budgetType.toLowerCase()) {
            case "economy" -> 1500.0 * days;
            case "luxury"  -> 12000.0 * days;
            default        -> 4000.0 * days; // "normal"
        };
    }
}
