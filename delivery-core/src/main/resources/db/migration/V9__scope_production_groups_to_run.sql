SET search_path TO archive_delivery;

ALTER TABLE production_group ADD COLUMN run_id bigint REFERENCES delivery_run(id);

UPDATE production_group g SET run_id=(
  SELECT s.run_id FROM output_package p JOIN source_package s ON s.id=p.source_package_id
  WHERE p.production_group_id=g.id AND s.run_id IS NOT NULL LIMIT 1
) WHERE EXISTS (
  SELECT 1 FROM output_package p JOIN source_package s ON s.id=p.source_package_id
  WHERE p.production_group_id=g.id AND s.run_id IS NOT NULL
);

ALTER TABLE production_group DROP CONSTRAINT production_group_production_line_id_archive_date_group_no_key;

CREATE UNIQUE INDEX uk_production_group_run ON production_group(
  COALESCE(run_id, 0), production_line_id, archive_date, group_no
);
