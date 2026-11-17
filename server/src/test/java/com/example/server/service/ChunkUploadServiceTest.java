package com.example.server.service;

import com.example.server.common.ErrorCode;
import com.example.server.exception.BusinessException;
import com.example.server.utils.MinioUtils;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChunkUploadServiceTest {
    @Test
    void completedUploadUsesPersistedMediaWithoutReassembling() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RedissonClient redisson = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock()).thenReturn(true);
        MediaService media = mock(MediaService.class);
        com.example.server.entity.MediaFile saved = new com.example.server.entity.MediaFile();
        saved.setId(12L);
        when(media.findUpload(anyString(), eq(3L))).thenReturn(saved);
        MinioUtils minio = mock(MinioUtils.class);
        ChunkUploadService service = new ChunkUploadService(redis, redisson, minio, media);
        assertEquals(saved, service.complete(UUID.randomUUID().toString(), 3L));
        verify(minio, never()).uploadLocalFile(any(), anyString());
    }

    @Test
    void chunkUploadConflictsWithMergeBeforeWritingObject() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hashes);
        when(hashes.entries(anyString())).thenReturn(Map.of("userId", "3", "totalChunks", "2", "filename", "video.mp4"));
        RedissonClient redisson = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock()).thenReturn(false);
        MinioUtils minio = mock(MinioUtils.class);
        ChunkUploadService service = new ChunkUploadService(redis, redisson, minio, mock(MediaService.class));

        BusinessException error = assertThrows(BusinessException.class, () ->
                service.uploadChunk(UUID.randomUUID().toString(), 0, 2,
                        new MockMultipartFile("file", "part", "application/octet-stream", new byte[]{1}), 3L));
        assertEquals(ErrorCode.CONFLICT, error.errorCode());
        verify(minio, never()).uploadObject(anyString(), any(), anyLong(), anyString());
    }
}
