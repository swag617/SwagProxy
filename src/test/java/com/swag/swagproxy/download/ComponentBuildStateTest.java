package com.swag.swagproxy.download;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComponentBuildStateTest {

    @Test
    void confirmedGoodStartsFalse() {
        var state = new ComponentBuildState();
        assertFalse(state.confirmedGood());
    }

    @Test
    void confirmGoodMarksTrue() {
        var state = new ComponentBuildState();
        state.setLiveBuild("4.0.0-6");
        state.confirmGood();
        assertTrue(state.confirmedGood());
    }

    @Test
    void changingLiveBuildResetsConfirmedGood() {
        var state = new ComponentBuildState();
        state.setLiveBuild("4.0.0-6");
        state.confirmGood();
        assertTrue(state.confirmedGood());

        state.setLiveBuild("4.1.0-9");

        assertFalse(state.confirmedGood(), "a different build hasn't earned confirmation yet");
    }

    @Test
    void settingSameLiveBuildDoesNotResetConfirmedGood() {
        var state = new ComponentBuildState();
        state.setLiveBuild("4.0.0-6");
        state.confirmGood();

        state.setLiveBuild("4.0.0-6");

        assertTrue(state.confirmedGood(), "re-setting the same build id must not lose confirmation");
    }

    @Test
    void skippedBuildsAreBoundedAndAgeOutOldestFirst() {
        var state = new ComponentBuildState();
        for (int i = 1; i <= 7; i++) {
            state.markSkipped("build-" + i);
        }

        assertEquals(5, state.skippedBuilds().size(), "skip list must be capped");
        assertFalse(state.isSkipped("build-1"), "oldest entries must age out first");
        assertFalse(state.isSkipped("build-2"));
        assertTrue(state.isSkipped("build-7"), "most recent skip must still be present");
    }

    @Test
    void markingAnAlreadySkippedBuildIsANoOp() {
        var state = new ComponentBuildState();
        state.markSkipped("build-1");
        state.markSkipped("build-1");

        assertEquals(1, state.skippedBuilds().size());
    }
}
