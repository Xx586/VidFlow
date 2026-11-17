package com.example.server.service;

import com.example.server.common.ErrorCode;
import com.example.server.dto.AgentFeedback;
import com.example.server.dto.AnalysisMode;
import com.example.server.dto.AnalysisTaskMsg;
import com.example.server.dto.TaskStatus;
import com.example.server.dto.TaskStage;
import com.example.server.entity.MediaFile;
import com.example.server.exception.BusinessException;
import com.example.server.utils.AnalysisTaskKeys;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

@Service
public class AnalysisDispatchService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisDispatchService.class);
    private static final int USER_REQUESTS_PER_MINUTE = 5;
    private static final int GLOBAL_REQUESTS_PER_MINUTE = 30;
    private static final Duration ACTIVE_TTL = Duration.ofHours(6);
    // 两个桶在同一 Lua 调用中检查并扣减，任一桶不足时均不消耗令牌。
    private static final DefaultRedisScript<Long> TOKEN_BUCKET = new DefaultRedisScript<>("""
            local now = redis.call('TIME')
            local ms = tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000)
            local values = {}
            for i = 1, 2 do
                local capacity = tonumber(ARGV[i])
                local state = redis.call('HMGET', KEYS[i], 'tokens', 'updated')
                local tokens = tonumber(state[1]) or capacity
                local updated = tonumber(state[2]) or ms
                tokens = math.min(capacity, tokens + math.max(0, ms - updated) * capacity / 60000)
                if tokens < 1 then return 0 end
                values[i] = tokens - 1
            end
            for i = 1, 2 do
                redis.call('HSET', KEYS[i], 'tokens', values[i], 'updated', ms)
                redis.call('PEXPIRE', KEYS[i], 120000)
            end
            return 1
            """, Long.class);

    private final AiService aiService;
    private final MediaService mediaService;
    private final StringRedisTemplate redisTemplate;
    private final RocketMQTemplate rocketMQTemplate;
    private final TaskEventService taskEventService;
    private final String analysisTopic;
    private final DispatchRecoveryService recoveryService;

    public AnalysisDispatchService(AiService aiService,
                                   MediaService mediaService,
                                   StringRedisTemplate redisTemplate,
                                   RocketMQTemplate rocketMQTemplate,
                                   TaskEventService taskEventService,
                                   @Value("${rocketmq.topic.video-analysis:video-analysis-topic}")
                                   String analysisTopic, DispatchRecoveryService recoveryService) {
        this.aiService = aiService;
        this.mediaService = mediaService;
        this.redisTemplate = redisTemplate;
        this.rocketMQTemplate = rocketMQTemplate;
        this.taskEventService = taskEventService;
        this.analysisTopic = analysisTopic;
        this.recoveryService = recoveryService;
    }

    /** 兼容旧调用方:未指定模式时按 GENERAL 提交。 */
    public SubmissionResult submit(MediaFile mediaFile, String goal, AgentFeedback revision) {
        return submit(mediaFile, goal, revision, AnalysisMode.GENERAL);
    }

    public SubmissionResult submit(MediaFile mediaFile, String goal, AgentFeedback revision, AnalysisMode mode) {
        AnalysisMode resolvedMode = mode == null ? AnalysisMode.GENERAL : mode;
        Long mediaId = mediaFile.getId();
        String action = revision == null
                ? AnalysisTaskMsg.START_ANALYSIS
                : AnalysisTaskMsg.REVISE_ANALYSIS;
        String contentHash = revision == null ? contentHash(mediaId) : "media-" + mediaId;
        String goalDigest = AnalysisTaskKeys.goalDigest(goal, resolvedMode);
        String activeKey = AnalysisTaskKeys.active("media-" + mediaId, goalDigest);
        Boolean accepted = redisTemplate.opsForValue().setIfAbsent(
                activeKey, String.valueOf(mediaId), ACTIVE_TTL);
        if (!Boolean.TRUE.equals(accepted)) return SubmissionResult.DUPLICATE;

        try {
            if (!tryAcquireQuota(mediaFile.getUserId())) {
                redisTemplate.delete(activeKey);
                return SubmissionResult.RATE_LIMITED;
            }
            // 旧结果先留着。消费者真正接手后再切 Checkpoint，MQ 投递失败时用户还有结果可看。
            if (revision != null) aiService.stageRevision(revision, resolvedMode);
            AnalysisTaskMsg msg = new AnalysisTaskMsg(mediaId, action, contentHash, goal, resolvedMode.name());
            recoveryService.pending(msg);
            try {
                rocketMQTemplate.convertAndSend(analysisTopic, msg);
                recoveryService.status(msg, "SENT");
            } catch (RuntimeException uncertain) {
                log.warn("analysis_dispatch_uncertain mediaId={}; pending record will retry", mediaId, uncertain);
            }
        } catch (RuntimeException e) {
            redisTemplate.delete(activeKey);
            if (revision != null) aiService.cancelStagedRevision(mediaId, goal, resolvedMode);
            log.error("analysis_dispatch_failed mediaId={} userId={}", mediaId, mediaFile.getUserId(), e);
            return SubmissionResult.FAILED;
        }

        try {
            taskEventService.publishAnalysis(mediaId, goal, resolvedMode,
                    TaskStatus.of(TaskStatus.State.QUEUED, "任务已进入异步分析队列"), TaskStage.QUEUED);
        } catch (RuntimeException eventError) {
            // MQ 已经接单，通知失败不能把任务伪装成投递失败。
            log.warn("analysis_queued_event_failed mediaId={} userId={}",
                    mediaId, mediaFile.getUserId(), eventError);
        }
        return SubmissionResult.ACCEPTED;
    }

    public boolean isActive(Long mediaId, String goal) {
        return isActive(mediaId, goal, AnalysisMode.GENERAL);
    }

    public boolean isActive(Long mediaId, String goal, AnalysisMode mode) {
        String goalDigest = AnalysisTaskKeys.goalDigest(goal, mode);
        return Boolean.TRUE.equals(redisTemplate.hasKey(
                AnalysisTaskKeys.active(contentHash(mediaId), goalDigest)))
                || Boolean.TRUE.equals(redisTemplate.hasKey(
                AnalysisTaskKeys.active("media-" + mediaId, goalDigest)));
    }

    /**
     * 追问和证据检索同样会触发模型调用，统一复用分析配额，避免绕过成本护栏。
     */
    public void requireAiQuota(Long userId) {
        try {
            if (!tryAcquireQuota(userId)) {
                throw new BusinessException(ErrorCode.RATE_LIMITED, "AI 请求过于频繁，请稍后再试");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("ai_rate_limiter_unavailable userId={}", userId, e);
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "AI 服务限流器暂不可用，请稍后再试");
        }
    }

    private boolean tryAcquireQuota(Long userId) {
        if (userId == null) return false;
        Long result = redisTemplate.execute(TOKEN_BUCKET,
                List.of("limit:ai:lua:user:" + userId, "limit:ai:lua:global"),
                String.valueOf(USER_REQUESTS_PER_MINUTE), String.valueOf(GLOBAL_REQUESTS_PER_MINUTE));
        return Long.valueOf(1).equals(result);
    }

    private String contentHash(Long mediaId) {
        return AnalysisTaskKeys.normalizeContentHash(
                mediaId, mediaService.contentHash(mediaId));
    }

    public enum SubmissionResult {
        ACCEPTED,
        RATE_LIMITED,
        DUPLICATE,
        FAILED
    }
}
