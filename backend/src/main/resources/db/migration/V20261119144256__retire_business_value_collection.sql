-- Business value now derives from platform records. Keep historical batches, files and run logs.
DELETE FROM collection_source
WHERE code IN ('business_value', 'mock_business_value')
   OR JSON_UNQUOTE(JSON_EXTRACT(config_json, '$.worker.categoryCode')) = 'businessValue';
