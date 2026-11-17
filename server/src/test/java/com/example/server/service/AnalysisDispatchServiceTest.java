package com.example.server.service;

import com.example.server.dto.AnalysisTaskMsg;
import com.example.server.entity.MediaFile;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AnalysisDispatchServiceTest {

    @Test
    void duplicateSubmissionDoesNotConsumeQuotaOrSendMessage() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        MediaService mediaService = mock(MediaService.class);
        when(mediaService.contentHash(7L)).thenReturn("0123456789abcdef0123456789abcdef");
        RocketMQTemplate mq = mock(RocketMQTemplate.class);
        AnalysisDispatchService service = new AnalysisDispatchService(
                mock(AiService.class), mediaService, redis, mq, mock(TaskEventService.class), "analysis", mock(DispatchRecoveryService.class));
        MediaFile media = new MediaFile();
        media.setId(7L);
        media.setUserId(3L);

        assertEquals(AnalysisDispatchService.SubmissionResult.DUPLICATE,
                service.submit(media, "目标", null));
        verify(redis, never()).execute(any(RedisScript.class), anyList(), anyString(), anyString());
        verify(mq, never()).convertAndSend(anyString(), any(AnalysisTaskMsg.class));
    }

    @Test
    void quotaRejectsWithoutSendingMessageAndReleasesActiveKey() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString())).thenReturn(0L);
        MediaService mediaService = mock(MediaService.class);
        when(mediaService.contentHash(7L)).thenReturn("0123456789abcdef0123456789abcdef");
        RocketMQTemplate mq = mock(RocketMQTemplate.class);
        AnalysisDispatchService service = new AnalysisDispatchService(
                mock(AiService.class), mediaService, redis, mq, mock(TaskEventService.class), "analysis", mock(DispatchRecoveryService.class));
        MediaFile media = new MediaFile();
        media.setId(7L);
        media.setUserId(3L);

        assertEquals(AnalysisDispatchService.SubmissionResult.RATE_LIMITED,
                service.submit(media, "目标", null));
        verify(redis).delete(startsWith("analysis:active:"));
        verify(mq, never()).convertAndSend(anyString(), any(AnalysisTaskMsg.class));
    }

    @Test
    void sameContentOnDifferentMediaGetsIndependentSubmissions() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString())).thenReturn(1L);
        MediaService mediaService = mock(MediaService.class);
        when(mediaService.contentHash(anyLong())).thenReturn("0123456789abcdef0123456789abcdef");
        AnalysisDispatchService service = new AnalysisDispatchService(mock(AiService.class), mediaService,
                redis, mock(RocketMQTemplate.class), mock(TaskEventService.class), "analysis",
                mock(DispatchRecoveryService.class));
        for (long id : new long[]{7L, 8L}) {
            MediaFile media = new MediaFile();
            media.setId(id);
            media.setUserId(3L);
            assertEquals(AnalysisDispatchService.SubmissionResult.ACCEPTED, service.submit(media, "目标", null));
        }
        verify(values).setIfAbsent(startsWith("analysis:active:media-7:"), eq("7"), any(Duration.class));
        verify(values).setIfAbsent(startsWith("analysis:active:media-8:"), eq("8"), any(Duration.class));
    }

    @Test
    void luaChecksUserAndGlobalBucketsTogether() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString())).thenAnswer(invocation -> {
            RedisScript<?> script = invocation.getArgument(0);
            List<?> keys = invocation.getArgument(1);
            assertEquals(List.of("limit:ai:lua:user:3", "limit:ai:lua:global"), keys);
            assertEquals("5", invocation.getArgument(2));
            assertEquals("30", invocation.getArgument(3));
            assertTrue(script.getScriptAsString().contains("redis.call('TIME')"));
            assertTrue(script.getScriptAsString().contains("if tokens < 1 then return 0 end"));
            return 1L;
        });
        AnalysisDispatchService service = new AnalysisDispatchService(
                mock(AiService.class), mock(MediaService.class), redis,
                mock(RocketMQTemplate.class), mock(TaskEventService.class), "analysis", mock(DispatchRecoveryService.class));
        service.requireAiQuota(3L);
    }
}
