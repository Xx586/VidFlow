ALTER TABLE media_files ADD COLUMN upload_id VARCHAR(36) NULL;
ALTER TABLE media_files ADD UNIQUE KEY uk_media_upload_id (upload_id);
