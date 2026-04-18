package com.newplanner.controller;

import com.newplanner.entity.Itinerary;
import com.newplanner.repository.ItineraryRepository;
import com.newplanner.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * DELETE /api/v1/users/{firebaseUid}
 * Permanently deletes the user row (and all cascade-linked trips, days,
 * activities, expense trackers) from MySQL.
 *
 * Firebase account deletion is handled on the client side via the Firebase SDK.
 */
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
@Slf4j

public class UserController {

    private final UserRepository userRepository;
    private final ItineraryRepository itineraryRepository;

    @DeleteMapping("/{firebaseUid}")
    @Transactional
    public ResponseEntity<Void> deleteAccount(@PathVariable String firebaseUid) {
        log.info("Delete account request for uid: {}", firebaseUid);
        if (!userRepository.existsById(firebaseUid)) {
            log.warn("User {} already missing from DB. Approving deletion idempotently to clear auth.", firebaseUid);
            return ResponseEntity.noContent().build();
        }
        
        List<Itinerary> userTrips = itineraryRepository.findByUserFirebaseUidOrderByCreatedAtDesc(firebaseUid);
        itineraryRepository.deleteAll(userTrips);
        log.info("Deleted {} itineraries for user {}", userTrips.size(), firebaseUid);
        
        userRepository.deleteById(firebaseUid);
        log.info("Account deleted from DB: {}", firebaseUid);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/exists/{firebaseUid}")
    public ResponseEntity<Boolean> checkUserExists(@PathVariable String firebaseUid) {
        return ResponseEntity.ok(userRepository.existsById(firebaseUid));
    }

    @PostMapping
    public ResponseEntity<com.newplanner.entity.User> createUser(@RequestBody com.newplanner.entity.User user) {
        if (userRepository.existsById(user.getFirebaseUid())) {
            log.warn("User {} already exists in DB", user.getFirebaseUid());
            return ResponseEntity.badRequest().build();
        }
        log.info("Created new user in DB: {}", user.getFirebaseUid());
        return ResponseEntity.ok(userRepository.save(user));
    }
}
