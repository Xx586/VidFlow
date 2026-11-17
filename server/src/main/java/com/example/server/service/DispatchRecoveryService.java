package com.example.server.service;

import com.example.server.dto.AnalysisMode;
import com.example.server.dto.AnalysisTaskMsg;
import com.example.server.mapper.AnalysisDispatchMapper;
import com.example.server.utils.AnalysisTaskKeys;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class DispatchRecoveryService {
    private static final Logger log = LoggerFactory.getLogger(DispatchRecoveryService.class);
    private final AnalysisDispatchMapper mapper;
    private final ObjectMapper json;
    private final RocketMQTemplate mq;
    private final String topic;

    public DispatchRecoveryService(AnalysisDispatchMapper mapper, ObjectMapper json, RocketMQTemplate mq,
                                   @Value("${rocketmq.topic.video-analysis:video-analysis-topic}") String topic) {
        this.mapper = mapper; this.json = json; this.mq = mq; this.topic = topic;
    }

    public void pending(AnalysisTaskMsg msg) {
        try {
            mapper.pending(msg.getMediaId(), digest(msg), msg.getAction(), json.writeValueAsString(msg));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("分析消息序列化失败", e);
        }
    }

    public void status(AnalysisTaskMsg msg, String status) {
        if ("SENT".equals(status)) mapper.markSent(msg.getMediaId(), digest(msg), msg.getAction());
        else mapper.status(msg.getMediaId(), digest(msg), msg.getAction(), status);
    }

    public String status(Long mediaId, String goal, AnalysisMode mode) {
        String digest = AnalysisTaskKeys.goalDigest(goal, mode);
        String revision = mapper.findStatus(mediaId, digest, AnalysisTaskMsg.REVISE_ANALYSIS);
        if (revision != null && !"COMPLETED".equals(revision)) return revision;
        return mapper.findStatus(mediaId, digest, AnalysisTaskMsg.START_ANALYSIS);
    }

    @Scheduled(fixedDelay = 30000)
    public void retryPending() {
        for (String payload : mapper.pendingPayloads()) {
            try {
                AnalysisTaskMsg msg = json.readValue(payload, AnalysisTaskMsg.class);
                mq.convertAndSend(topic, msg);
                status(msg, "SENT");
            } catch (Exception e) {
                log.warn("analysis_dispatch_recovery_failed", e);
            }
        }
    }

    private String digest(AnalysisTaskMsg msg) {
        return AnalysisTaskKeys.goalDigest(msg.getUserGoal(), AnalysisMode.fromNullable(msg.getMode()));
    }
}
