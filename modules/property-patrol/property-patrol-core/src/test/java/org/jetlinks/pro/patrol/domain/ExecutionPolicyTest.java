package org.jetlinks.pro.patrol.domain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.time.Instant;

class ExecutionPolicyTest {
    final Instant start = Instant.parse("2026-11-02T14:00:00Z"), end = start.plusSeconds(28800);

    void check(
            Instant now,
            int completed,
            Instant last,
            boolean assigned,
            ExecutionPolicy.RosterPolicy policy,
            boolean configured,
            boolean onDuty) {
        ExecutionPolicy.check(
                now, start, end, completed, 2, last, 30, assigned, policy, configured, onDuty);
    }

    @Test
    void halfOpenTimeWindow() {
        assertDoesNotThrow(
                () ->
                        check(
                                start,
                                0,
                                null,
                                true,
                                ExecutionPolicy.RosterPolicy.optional,
                                false,
                                false));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        check(
                                end,
                                0,
                                null,
                                true,
                                ExecutionPolicy.RosterPolicy.optional,
                                false,
                                false));
    }

    @Test
    void assignmentAndCompletionCannotBeBypassed() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        check(
                                start,
                                0,
                                null,
                                false,
                                ExecutionPolicy.RosterPolicy.optional,
                                false,
                                false));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        check(
                                start,
                                2,
                                null,
                                true,
                                ExecutionPolicy.RosterPolicy.optional,
                                false,
                                false));
    }

    @Test
    void originalPolicyAllowsNoScheduleAndRequiresCurrentShiftWhenConfigured() {
        assertDoesNotThrow(
                () ->
                        check(
                                start,
                                0,
                                null,
                                true,
                                ExecutionPolicy.RosterPolicy.optional,
                                false,
                                false));
        assertDoesNotThrow(
                () ->
                        check(
                                start,
                                0,
                                null,
                                true,
                                ExecutionPolicy.RosterPolicy.required,
                                false,
                                false));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        check(
                                start,
                                0,
                                null,
                                true,
                                ExecutionPolicy.RosterPolicy.optional,
                                true,
                                false));
        assertDoesNotThrow(
                () ->
                        check(
                                start,
                                0,
                                null,
                                true,
                                ExecutionPolicy.RosterPolicy.required,
                                true,
                                true));
    }

    @Test
    void cooldownExactBoundaryAllowed() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        check(
                                start.plusSeconds(1799),
                                1,
                                start,
                                true,
                                ExecutionPolicy.RosterPolicy.optional,
                                false,
                                false));
        assertDoesNotThrow(
                () ->
                        check(
                                start.plusSeconds(1800),
                                1,
                                start,
                                true,
                                ExecutionPolicy.RosterPolicy.optional,
                                false,
                                false));
    }
}
