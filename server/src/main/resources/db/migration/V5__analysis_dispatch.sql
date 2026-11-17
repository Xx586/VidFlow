CREATE TABLE analysis_dispatches (
 media_id BIGINT NOT NULL,
 goal_digest CHAR(64) NOT NULL,
 action VARCHAR(32) NOT NULL,
 payload LONGTEXT NOT NULL,
 status VARCHAR(24) NOT NULL,
 updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 PRIMARY KEY (media_id, goal_digest, action),
 KEY idx_dispatch_status_time (status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
