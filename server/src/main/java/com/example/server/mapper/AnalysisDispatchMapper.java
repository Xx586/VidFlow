package com.example.server.mapper;

import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface AnalysisDispatchMapper {
    @Insert("""
      INSERT INTO analysis_dispatches(media_id,goal_digest,action,payload,status)
      VALUES(#{mediaId},#{digest},#{action},#{payload},'PENDING')
      ON DUPLICATE KEY UPDATE payload=VALUES(payload),status='PENDING',updated_at=CURRENT_TIMESTAMP(3)
      """)
    void pending(Long mediaId, String digest, String action, String payload);

    @Update("UPDATE analysis_dispatches SET status=#{status} WHERE media_id=#{mediaId} AND goal_digest=#{digest} AND action=#{action}")
    void status(Long mediaId, String digest, String action, String status);

    @Select("SELECT status FROM analysis_dispatches WHERE media_id=#{mediaId} AND goal_digest=#{digest} AND action=#{action}")
    String findStatus(Long mediaId, String digest, String action);

    @Update("UPDATE analysis_dispatches SET status='SENT' WHERE media_id=#{mediaId} AND goal_digest=#{digest} AND action=#{action} AND status='PENDING'")
    void markSent(Long mediaId, String digest, String action);

    @Select("SELECT payload FROM analysis_dispatches WHERE status='PENDING' AND updated_at < DATE_SUB(NOW(3), INTERVAL 30 SECOND) ORDER BY updated_at LIMIT 30")
    List<String> pendingPayloads();
}
