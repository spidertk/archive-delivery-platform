SET search_path TO archive_delivery;

ALTER TABLE output_package ADD COLUMN run_id bigint REFERENCES delivery_run(id);

UPDATE output_package p SET run_id=(
  SELECT s.run_id FROM source_package s WHERE s.id=p.source_package_id
);

ALTER TABLE output_package DROP CONSTRAINT output_package_production_line_id_archive_date_new_package__key;

CREATE UNIQUE INDEX uk_output_package_run_name ON output_package(
  COALESCE(run_id, 0), production_line_id, archive_date, new_package_name
);
