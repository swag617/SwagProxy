package com.swag.swagproxy.download;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CandidateIteratorTest {

    private static ResolvedBuild rb(String id) {
        return new ResolvedBuild(id, id + ".jar", "https://example.com/" + id, "sha-" + id);
    }

    @Test
    void startsAtFirstCandidateForBothComponents() {
        var it = new CandidateIterator(List.of(rb("v1"), rb("v2")), List.of(rb("g1"), rb("g2")));

        assertEquals(rb("v1"), it.currentVelocity());
        assertEquals(rb("g1"), it.currentGeyser());
        assertFalse(it.isExhausted());
    }

    @Test
    void advancesVelocityBeforeGeyser() {
        // 3 velocity candidates, 2 geyser candidates.
        var it = new CandidateIterator(List.of(rb("v1"), rb("v2"), rb("v3")), List.of(rb("g1"), rb("g2")));

        assertEquals("velocity", it.advance());
        assertEquals(rb("v2"), it.currentVelocity());
        assertEquals(rb("g1"), it.currentGeyser(), "geyser must not change while velocity is still advancing");

        assertEquals("velocity", it.advance());
        assertEquals(rb("v3"), it.currentVelocity());
        assertEquals(rb("g1"), it.currentGeyser());
    }

    @Test
    void resetsVelocityToFirstCandidateWhenGeyserAdvances() {
        var it = new CandidateIterator(List.of(rb("v1"), rb("v2")), List.of(rb("g1"), rb("g2")));

        assertEquals("velocity", it.advance()); // v1 -> v2 (velocity exhausted after this)
        assertEquals("geyser", it.advance());   // velocity candidates exhausted -> advance geyser, reset velocity

        assertEquals(rb("v1"), it.currentVelocity(), "velocity must reset to its best candidate once geyser advances");
        assertEquals(rb("g2"), it.currentGeyser());
    }

    @Test
    void exhaustsOnlyAfterBothDimensionsFullyTried() {
        var it = new CandidateIterator(List.of(rb("v1"), rb("v2")), List.of(rb("g1"), rb("g2")));

        assertEquals("velocity", it.advance()); // (v2, g1)
        assertEquals("geyser", it.advance());   // (v1, g2)
        assertEquals("velocity", it.advance()); // (v2, g2)
        assertFalse(it.isExhausted());

        assertNull(it.advance()); // nothing left
        assertTrue(it.isExhausted());
        assertNull(it.advance(), "advance() after exhaustion must keep returning null, not throw or wrap around");
    }

    @Test
    void singleCandidatePerComponentExhaustsImmediately() {
        var it = new CandidateIterator(List.of(rb("v1")), List.of(rb("g1")));

        assertNull(it.advance());
        assertTrue(it.isExhausted());
    }

    @Test
    void attemptCountersReflectCurrentPosition() {
        var it = new CandidateIterator(List.of(rb("v1"), rb("v2"), rb("v3")), List.of(rb("g1"), rb("g2")));

        assertEquals(1, it.velocityAttempt());
        assertEquals(3, it.velocityTotal());
        assertEquals(1, it.geyserAttempt());
        assertEquals(2, it.geyserTotal());

        it.advance();
        assertEquals(2, it.velocityAttempt());
    }

    @Test
    void rejectsEmptyCandidateLists() {
        assertThrows(IllegalArgumentException.class, () -> new CandidateIterator(List.of(), List.of(rb("g1"))));
        assertThrows(IllegalArgumentException.class, () -> new CandidateIterator(List.of(rb("v1")), List.of()));
    }

    @Test
    void describesCurrentComboForLogging() {
        var it = new CandidateIterator(List.of(rb("v1")), List.of(rb("g1")));
        assertEquals("velocity v1 + geyser g1", it.describeCurrentCombo());
    }
}
