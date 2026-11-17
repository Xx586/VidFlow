package com.example.server.service;

import com.example.server.dto.AnalysisMode;
import com.example.server.utils.AnalysisTaskKeys;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AnalysisTaskKeysTest {
    @Test
    void contentAndGoalHaveSeparateIdentities() {
        String content = AnalysisTaskKeys.normalizeContentHash(7L, "0123456789ABCDEF0123456789ABCDEF");
        assertEquals("0123456789abcdef0123456789abcdef", content);
        String general = AnalysisTaskKeys.goalDigest("目标", AnalysisMode.GENERAL);
        String learning = AnalysisTaskKeys.goalDigest("目标", AnalysisMode.LEARNING);
        assertNotEquals(general, learning);
        assertNotEquals(AnalysisTaskKeys.active(content, general), AnalysisTaskKeys.active(content, learning));
        assertNotEquals(AnalysisTaskKeys.contextOwner(content), AnalysisTaskKeys.contextOwner("media-8"));
    }
}
