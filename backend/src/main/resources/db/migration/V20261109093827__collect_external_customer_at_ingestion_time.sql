-- File hashes remain actual content hashes. The delivery name separates identical files sent in different batches.
ALTER TABLE collection_file
    ADD COLUMN delivery_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT '',
    DROP INDEX uk_collection_content,
    ADD UNIQUE INDEX uk_collection_content (source_code,file_hash,delivery_id,business_date);

UPDATE collection_source
SET config_json=JSON_SET(
    JSON_REMOVE(config_json,REPLACE(COALESCE(
        JSON_UNQUOTE(JSON_SEARCH(config_json,'one','data!_time','!','$.fields[*].target')),
        '$.fields[999]'),'.target','')),
    '$.dateMode','ingestion_time',
    '$.intervalSeconds',5,
    '$.settleSeconds',0,
    '$.schedule',JSON_OBJECT('type','interval'),
    '$.arrival',IF(JSON_TYPE(JSON_EXTRACT(config_json,'$.arrival'))='OBJECT',
                  JSON_SET(JSON_EXTRACT(config_json,'$.arrival'),'$.lagDays',0),NULL)),
    next_scan_at=NOW()
WHERE code='external_customer'
  AND JSON_UNQUOTE(JSON_EXTRACT(config_json,'$.parser'))='python-worker'
  AND JSON_UNQUOTE(JSON_EXTRACT(config_json,'$.format'))='csv'
  AND JSON_UNQUOTE(JSON_EXTRACT(config_json,'$.worker.categoryCode'))='external_customer';
