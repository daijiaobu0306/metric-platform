"""Apply a reviewed, local-only simulation reset plan after a verified complete backup.

Never infers provenance from dates, thresholds or metric names. The plan enumerates
individual collection rows; protected production metrics and unrelated categories
are excluded. No schema or metric rule changes are performed.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re

from database_support import ROOT, environment, mysql


def literal(value):
    return "CONVERT(0x" + str(value).encode("utf-8").hex() + " USING utf8mb4)"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--plan", required=True, type=Path)
    parser.add_argument("--backup-version", required=True)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    plan = json.loads(args.plan.read_text(encoding="utf-8"))
    values, target, env, connection = environment(ROOT / "deploy/config/.env.local")
    database = target.path[1:]
    if target.hostname not in ("localhost", "127.0.0.1") or database != "system_operations_metrics":
        raise ValueError("This reviewed plan is for the local development database only")
    if plan["target"] != {"host": target.hostname, "database": database}:
        raise ValueError("Target differs from the reviewed plan")
    if not re.fullmatch(r"V[1-9][0-9]*", args.backup_version):
        raise ValueError("Invalid backup version")
    backup_dir = ROOT / "deploy/db_export" / args.backup_version
    backup = json.loads((backup_dir / "manifest.json").read_text(encoding="utf-8"))
    if backup["host"] != target.hostname or backup["database"] != database or backup["scope"] != "schema_and_data":
        raise ValueError("Backup belongs to a different database or is incomplete")
    if hashlib.sha256((backup_dir / backup["file"]).read_bytes()).hexdigest() != backup["sha256"]:
        raise ValueError("Backup checksum mismatch")
    protected = set(plan["protectedMetricIds"])
    ids = set(plan["cleanupMetricIds"])
    if not ids or ids & protected or any(not isinstance(i, int) for i in ids):
        raise ValueError("Unsafe metric cleanup scope")
    metrics = {m["id"]: m for m in plan["metrics"]}
    allowed = {"businessValue", "resourceInput", "external_customer", "system_run"}
    if any(metrics[i]["categoryCode"] not in allowed for i in ids):
        raise ValueError("Cleanup contains an excluded category")
    for row in plan["cleanupRows"]:
        if not re.fullmatch(r"[a-f0-9-]{36}", row["batchId"]) or not isinstance(row["rowNumber"], int):
            raise ValueError("Invalid frozen row identity")
    tuples = ",".join(f"({literal(r['batchId'])},{r['rowNumber']})" for r in plan["cleanupRows"])
    metric_ids = ",".join(map(str, sorted(ids)))
    batch_ids = ",".join(literal(i) for i in sorted({r["batchId"] for r in plan["cleanupRows"]}))
    preamble = [
        "CREATE TEMPORARY TABLE reset_rows(batch_id VARCHAR(36),source_row_num INT,PRIMARY KEY(batch_id,source_row_num)) ENGINE=InnoDB;",
        "INSERT INTO reset_rows VALUES " + tuples + ";",
    ]
    count_sql = "SELECT COUNT(*) FROM collection_row r JOIN reset_rows p ON p.batch_id=r.batch_id AND p.source_row_num=r.data_row_number;"
    count = int(mysql(connection, env, database, "\n".join(preamble) + count_sql).strip())
    if count != len(plan["cleanupRows"]):
        raise ValueError(f"Frozen simulation rows changed: expected {len(plan['cleanupRows'])}, found {count}")
    statements = ["START TRANSACTION;", *preamble]
    def operation(label, sql):
        statements.extend([sql, f"SELECT {literal(label)},ROW_COUNT();"])
    operation("old_simulated_collection_rows", "DELETE r FROM collection_row r JOIN reset_rows p ON p.batch_id=r.batch_id AND p.source_row_num=r.data_row_number;")
    operation("old_simulated_batches", f"DELETE FROM collection_file WHERE id IN ({batch_ids}) AND NOT EXISTS (SELECT 1 FROM collection_row r WHERE r.batch_id=collection_file.id);")
    operation("mixed_batch_retained_count", f"UPDATE collection_file f SET row_count=(SELECT COUNT(*) FROM collection_row r WHERE r.batch_id=f.id) WHERE id IN ({batch_ids});")
    for table in ("metric_realtime", "metric_data", "metric_value_history", "metric_health_history",
                  "health_snapshot_1h", "health_snapshot_6h", "health_snapshot_1d", "health_snapshot_7d", "health_snapshot_30d"):
        operation(table, f"DELETE FROM {table} WHERE metric_id IN ({metric_ids});")
    # Dedicated obsolete mock stores contain no production records; catalog/rules remain.
    operation("mock_external_customer_data", "DELETE FROM mock_external_customer_data;")
    for table in ("file_collection_raw", "file_collection_value"):
        operation(table, f"DELETE FROM {table} WHERE batch_id IN (SELECT id FROM file_collection_batch WHERE config_code='external_customer_simulation');")
    operation("legacy_external_simulation_batches", "DELETE FROM file_collection_batch WHERE config_code='external_customer_simulation';")
    operation("legacy_simulation_channel_disabled", "UPDATE file_collection_config SET enabled=0 WHERE code='external_customer_simulation';")
    csv_files = json.loads(args.plan.with_name("csv-manifest.json").read_text(encoding="utf-8"))
    expected_sources = {"business_value", "resource_input", "external_customer", "running_efficiency", "satisfaction_report"}
    if {f["sourceCode"] for f in csv_files} != expected_sources:
        raise ValueError("CSV source scope differs from the approved five categories")
    for file in csv_files:
        task = next(t for t in plan["tasks"] if t["code"] == file["sourceCode"])
        config = json.loads(task["config_json"]) if isinstance(task["config_json"], str) else task["config_json"]
        if config["worker"]["categoryCode"] != file["categoryCode"]:
            raise ValueError("Task category mismatch")
        actual = Path(file["file"]).resolve(strict=True)
        expected = (ROOT / "files" / config["importPath"]).resolve(strict=True)
        if actual.parent != expected or not actual.is_relative_to((ROOT / "files").resolve()):
            raise ValueError("CSV is outside the reviewed scan directory")
        config.update(enabled=True, format="csv", encoding="UTF-8", delimiter=",",
                      filePattern=actual.name, dateMode="data_time", intervalSeconds=300, settleSeconds=5)
        config["fields"] = [f for f in config["fields"] if f["target"] != "provenance"] + [
            dict(source="数据来源标记", target="provenance", type="string", required=True)]
        operation("task_" + file["sourceCode"], "UPDATE collection_source SET enabled=1,config_json=" + literal(json.dumps(config, ensure_ascii=False)) + ",next_scan_at=DATE_ADD(NOW(),INTERVAL 5 MINUTE),last_error=NULL WHERE code=" + literal(file["sourceCode"]) + ";")
    statements.append("COMMIT;")
    sql = "\n".join(statements) + "\n"
    args.plan.with_name("cleanup.sql").write_text(sql, encoding="utf-8")
    if not args.apply:
        print(json.dumps({"mode": "review", "rows": count, "metrics": len(ids), "tasks": sorted(expected_sources)}, ensure_ascii=False))
        return
    result = mysql(connection, env, database, sql)
    args.plan.with_name("cleanup-result.txt").write_text(result, encoding="utf-8")
    print(result)


if __name__ == "__main__":
    main()
