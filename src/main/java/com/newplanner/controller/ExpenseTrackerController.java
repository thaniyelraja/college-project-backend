package com.newplanner.controller;

import com.newplanner.entity.Expense;
import com.newplanner.entity.ExpenseTracker;
import com.newplanner.service.ExpenseTrackerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/trips/{tripId}/tracker")
@RequiredArgsConstructor
@Slf4j

public class ExpenseTrackerController {

    private final ExpenseTrackerService service;

    @GetMapping
    public ResponseEntity<ExpenseTracker> getTracker(@PathVariable String tripId) {
        log.info("Fetching tracker for trip: {}", tripId);
        ExpenseTracker tracker = service.getTrackerByTripId(tripId);
        return ResponseEntity.ok(tracker);
    }

    @PutMapping("/currency")
    public ResponseEntity<ExpenseTracker> updateCurrency(@PathVariable String tripId, @RequestBody String currency) {
        log.info("Updating currency for trip {} to {}", tripId, currency);
        ExpenseTracker tracker = service.updateCurrency(tripId, currency.replace("\"", "").trim());
        return ResponseEntity.ok(tracker);
    }

    @PutMapping("/budget")
    public ResponseEntity<ExpenseTracker> updateBudget(@PathVariable String tripId, @RequestBody Double newLimit) {
        log.info("Updating budget limit for trip {} to {}", tripId, newLimit);
        ExpenseTracker tracker = service.updateBudgetLimit(tripId, newLimit);
        return ResponseEntity.ok(tracker);
    }

    @PutMapping("/members")
    public ResponseEntity<ExpenseTracker> updateMembers(@PathVariable String tripId, @RequestBody List<String> memberNames) {
        ExpenseTracker tracker = service.updateMembers(tripId, memberNames);
        return ResponseEntity.ok(tracker);
    }

    @PostMapping("/expenses")
    public ResponseEntity<Expense> addExpense(@PathVariable String tripId, @RequestBody Expense expense) {
        Expense savedExpense = service.addExpense(tripId, expense);
        return ResponseEntity.ok(savedExpense);
    }

    @DeleteMapping("/expenses/{expenseId}")
    public ResponseEntity<Void> removeExpense(@PathVariable String tripId, @PathVariable String expenseId) {
        service.removeExpense(expenseId);
        return ResponseEntity.ok().build();
    }
}
