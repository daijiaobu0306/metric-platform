-- Narrow historical row searches before checking the exact metric code and category.
ALTER TABLE collection_row
    ADD COLUMN metric_code_digest BINARY(32)
        GENERATED ALWAYS AS (UNHEX(SHA2(JSON_UNQUOTE(JSON_EXTRACT(data_json,'$.metric_code')),256))) VIRTUAL,
    ADD INDEX idx_collection_row_metric_code (metric_code_digest);
