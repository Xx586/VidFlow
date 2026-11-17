package com.example.server.service;

import com.example.server.dto.AnalysisMode;
import com.example.server.dto.AnalysisTaskMsg;
import com.example.server.mapper.AnalysisDispatchMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class DispatchRecoveryServiceTest {
    @Test
    void retriesPersistedPendingMessageAndMarksSent() throws Exception {
        AnalysisDispatchMapper mapper = mock(AnalysisDispatchMapper.class);
        RocketMQTemplate mq = mock(RocketMQTemplate.class);
        AnalysisTaskMsg msg = new AnalysisTaskMsg(7L, AnalysisTaskMsg.START_ANALYSIS,
                "0123456789abcdef0123456789abcdef", "目标", AnalysisMode.GENERAL.name());
        when(mapper.pendingPayloads()).thenReturn(java.util.List.of(new ObjectMapper().writeValueAsString(msg)));
        DispatchRecoveryService service = new DispatchRecoveryService(mapper, new ObjectMapper(), mq, "analysis");
        service.retryPending();
        verify(mq).convertAndSend(eq("analysis"), any(AnalysisTaskMsg.class));
        verify(mapper).markSent(eq(7L), anyString(), eq(AnalysisTaskMsg.START_ANALYSIS));
    }
}
