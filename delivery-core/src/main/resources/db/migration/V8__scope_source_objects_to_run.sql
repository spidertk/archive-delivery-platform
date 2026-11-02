SET search_path TO archive_delivery;

ALTER TABLE source_package DROP CONSTRAINT source_package_bucket_name_object_key_object_version_key;

CREATE UNIQUE INDEX uk_source_run_object ON source_package(run_id, bucket_name, object_key, object_version)
WHERE run_id IS NOT NULL;

CREATE UNIQUE INDEX uk_source_unscoped_object ON source_package(bucket_name, object_key, object_version)
WHERE run_id IS NULL;
