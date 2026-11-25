"""Independent collection worker. Only this process receives the ingestion credential."""
import argparse
from contextlib import contextmanager, ExitStack
from datetime import datetime
import fnmatch
import hashlib
import json
import logging
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import threading
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlsplit
from urllib.request import Request, build_opener, ProxyHandler, HTTPRedirectHandler

LOG = logging.getLogger("collection-worker")
MAX_FILE = 10 * 1024 * 1024
# Raw CSVs can be larger than the bounded, aggregated HTTP result. Never split
# one dataset into independent metric batches: counts/averages would be wrong.
MAX_INPUT_FILE = 64 * 1024 * 1024


def encode(value):
    return json.dumps(value, ensure_ascii=False, allow_nan=False, separators=(",", ":")).encode("utf-8")


def digest(path):
    result = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(65536), b""):
            result.update(block)
    return result.hexdigest()


def atomic_json(path, value):
    temporary = path.with_suffix(path.suffix + ".part")
    with temporary.open("wb") as stream:
        stream.write(encode(value))
        stream.flush()
        os.fsync(stream.fileno())
    temporary.replace(path)


class ApiError(RuntimeError):
    pass


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ApiError("Worker API redirects are not allowed")


class Api:
    def __init__(self, base, token):
        parts = urlsplit(base)
        if parts.scheme not in ("http", "https") or not parts.hostname or parts.username or parts.password or parts.query or parts.fragment:
            raise ValueError("Invalid API base URL")
        if parts.scheme != "https" and parts.hostname not in ("localhost", "127.0.0.1", "::1", "backend"):
            raise ValueError("Remote Worker API requires HTTPS")
        if len(token) < 32:
            raise ValueError("COLLECTION_WORKER_TOKEN must contain at least 32 characters")
        self.base = base.rstrip("/") + "/api/collection/worker"
        self.token = token

    def call(self, method, path, data=None, claim=None):
        headers = {"Authorization": "Bearer " + self.token, "Content-Type": "application/json"}
        if claim:
            headers["X-Collection-Lease"] = claim["leaseToken"]
        body = encode(data) if data is not None else None
        if body is not None and len(body) > MAX_FILE:
            raise ValueError("Standard batch exceeds 10MB")
        request = Request(self.base + path, data=body, headers=headers, method=method)
        # Ignore ambient proxy settings, never forward the machine credential on redirects.
        opener = build_opener(ProxyHandler({}), NoRedirect())
        try:
            with opener.open(request, timeout=30) as response:
                contents = response.read(MAX_FILE + 1)
            if len(contents) > MAX_FILE:
                raise ApiError("API response exceeds 10MB")
            result = json.loads(contents)
            if result.get("code") != 200:
                raise ApiError(str(result.get("message", "API request failed")))
            return result.get("data")
        except HTTPError as error:
            # Do not log request headers or credentials.
            message = error.read(4096).decode("utf-8", errors="replace")
            raise ApiError(f"API HTTP {error.code}: {message}") from None
        except (URLError, TimeoutError, OSError) as error:
            raise ApiError("API connection failed: " + str(error)) from None


class Lease:
    def __init__(self, api, claim):
        self.api = api
        self.claim = claim
        self.confirmed = time.monotonic()
        self.stop = threading.Event()
        self.lost = threading.Event()
        self.thread = threading.Thread(target=self.heartbeat, daemon=True)

    def check(self):
        if self.lost.is_set() or time.monotonic() - self.confirmed >= self.claim["leaseSeconds"] - 10:
            raise RuntimeError("Lease confirmation expired; stop processing and reclaim")

    def heartbeat(self):
        while not self.stop.wait(self.claim["heartbeatSeconds"]):
            try:
                self.api.call("POST", f"/runs/{self.claim['runId']}/heartbeat", {}, self.claim)
                self.confirmed = time.monotonic()
            except Exception:
                self.lost.set()
                return

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *args):
        self.stop.set()
        self.thread.join(timeout=32)


def directory(root, relative):
    value = Path(relative)
    target = (root / value).resolve()
    if value.is_absolute() or not target.is_relative_to(root) or target == root:
        raise ValueError("Task directory escapes files root")
    if not target.is_dir():
        raise ValueError("Task directory is missing: " + str(relative))
    return target


def snapshot(root, claim, work):
    version = claim["config"]["worker"]["version"]
    import re
    if not re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}", version):
        raise ValueError("Invalid plugin version")
    releases = (root / "collection-scripts/releases").resolve(strict=True)
    release = (releases / version).resolve(strict=True)
    if not release.is_relative_to(releases):
        raise ValueError("Release escapes root")
    manifest_path = release / "manifest.json"
    if manifest_path.is_symlink() or manifest_path.stat().st_size > 100000:
        raise ValueError("Invalid release manifest")
    content = manifest_path.read_bytes()
    if hashlib.sha256(content).hexdigest() != claim["manifestHash"]:
        raise ValueError("Release manifest differs from claimed version")
    manifest = json.loads(content)
    if manifest.get("protocolVersion") != 1 or manifest.get("version") != version:
        raise ValueError("Unsupported release protocol")
    files = manifest.get("files", {})
    if not isinstance(files, dict) or not 1 <= len(files) <= 200 or manifest.get("entry") not in files:
        raise ValueError("Invalid release inventory")
    scripts = work / "scripts"
    scripts.mkdir()
    size = 0
    for name, expected in files.items():
        relative = Path(name)
        source = release / relative
        target = scripts / relative
        if relative.is_absolute() or not source.resolve().is_relative_to(release) or not target.resolve().is_relative_to(scripts):
            raise ValueError("Invalid plugin path")
        if not source.is_file() or source.is_symlink():
            raise ValueError("Invalid plugin file")
        size += source.stat().st_size
        if size > MAX_FILE:
            raise ValueError("Plugin release exceeds 10MB")
        data = source.read_bytes()
        if hashlib.sha256(data).hexdigest() != expected:
            raise ValueError("Plugin digest mismatch: " + name)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
    entry = scripts / manifest["entry"]
    if entry.suffix != ".py":
        raise ValueError("Plugin entry must be Python")
    return entry


def kill(process):
    if process.poll() is not None:
        return
    if os.name == "nt":
        subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    else:
        import signal
        os.killpg(process.pid, signal.SIGKILL)
    process.wait(timeout=5)


def parse(root, claim, source_file, lease):
    config = claim["config"]
    with tempfile.TemporaryDirectory(prefix="metric-plugin-") as temporary:
        work = Path(temporary)
        entry = snapshot(root, claim, work)
        input_path = work / ("input." + config["format"])
        with source_file.open("rb") as stream:
            contents = stream.read(MAX_INPUT_FILE + 1)
        if not 0 < len(contents) <= MAX_INPUT_FILE:
            raise ValueError("Input must contain 1 byte to 64MB; preserve the complete dataset")
        input_path.write_bytes(contents)
        input_hash = hashlib.sha256(contents).hexdigest()
        request = work / "request.json"
        output = work / "result.json"
        request.write_bytes(encode({"protocolVersion": 1, "source": config, "inputPath": str(input_path), "sourceFile": source_file.name}))
        environment = {key: os.environ[key] for key in ("PATH", "SystemRoot", "SYSTEMROOT", "WINDIR", "TEMP", "TMP", "LANG") if key in os.environ}
        environment["TZ"] = "Asia/Shanghai"
        args = [sys.executable, "-E", "-s", "-B", "-X", "utf8", str(entry), "--request", str(request), "--output", str(output)]
        process = subprocess.Popen(args, cwd=work, env=environment, stdin=subprocess.DEVNULL,
                                   stdout=subprocess.PIPE, stderr=subprocess.STDOUT, start_new_session=os.name != "nt")
        captured = bytearray()

        def drain():
            with process.stdout:
                for data in iter(lambda: process.stdout.read(2048), b""):
                    captured.extend(data[:max(0, 16384 - len(captured))])

        reader = threading.Thread(target=drain, daemon=True)
        reader.start()
        deadline = time.monotonic() + config["worker"]["timeoutSeconds"]
        try:
            while process.poll() is None:
                lease.check()
                if time.monotonic() >= deadline:
                    raise RuntimeError("Plugin execution timed out")
                if output.exists() and output.stat().st_size > MAX_FILE:
                    raise ValueError("Plugin output exceeds 10MB")
                time.sleep(0.1)
            reader.join(timeout=2)
            if process.returncode:
                raise ValueError("Plugin failed: " + captured.decode("utf-8", errors="replace"))
            if not output.is_file() or output.is_symlink() or output.stat().st_size > MAX_FILE:
                raise ValueError("Plugin output missing or exceeds 10MB")
            result = json.loads(output.read_text(encoding="utf-8"))
            records = result.get("records")
            output_type = result.get("outputType")
            if result.get("protocolVersion") != 1 or output_type not in ("metric-values", "raw-rows") or not isinstance(records, list):
                raise ValueError("Unsupported plugin output protocol")
            if not 1 <= len(records) <= 10000 or result.get("rowCount") != len(records):
                raise ValueError("Invalid plugin row count or exceeds 10000")
            ingestion_time = config["dateMode"] == "ingestion_time"
            for row in records:
                if not isinstance(row, dict) or "_collection" in row:
                    raise ValueError("Invalid standard record")
                if output_type == "metric-values":
                    if "metric_value" not in row:
                        raise ValueError("Invalid standard record")
                    if row["metric_value"] is not None and not isinstance(row["metric_value"], str):
                        raise ValueError("Metric values must be decimal strings or null")
                    if ingestion_time:
                        continue
                    time_value = str(row.get("data_time", "")).replace(" ", "T")
                    parsed = datetime.fromisoformat(time_value)
                    if parsed.tzinfo:
                        raise ValueError("Data time must use explicit local Beijing time")
                    row["data_time"] = parsed.isoformat()
            if digest(source_file) != input_hash:
                raise ValueError("Input changed during parsing")
            payload = dict(protocolVersion=1, outputType=output_type, sourceFile=source_file.name, fileHash=input_hash,
                           scriptVersion=config["worker"]["version"], manifestHash=claim["manifestHash"],
                           configHash=claim["configHash"], rowCount=len(records), records=records)
            if output_type == "metric-values":
                if not ingestion_time:
                    payload["businessDate"] = max(datetime.fromisoformat(row["data_time"]).date() for row in records).isoformat()
            else:
                business_date = result.get("businessDate")
                if not isinstance(business_date, str) or not business_date:
                    raise ValueError("Raw-rows plugin output missing businessDate")
                payload["businessDate"] = business_date
            return payload
        finally:
            kill(process)
            reader.join(timeout=2)


def status(api, claim, state):
    key = "businessDate" if "businessDate" in state else "sourceFile"
    query = urlencode({key: state[key]})
    return api.call("GET", f"/runs/{claim['runId']}/batches/{state['fileHash']}?{query}", claim=claim)


def archive_pending(api, claim, lease, inbox, archive, state_path, state):
    lease.check()
    receipt = status(api, claim, state)
    if receipt.get("state") != "COMMITTED":
        raise RuntimeError("No committed receipt; preserve source file")
    name = state["sourceFile"]
    if Path(name).name != name or "/" in name or "\\" in name:
        raise ValueError("Invalid pending source name")
    source = inbox / name
    target = archive / receipt["archiveFile"]
    if target.parent.resolve() != archive or target.is_symlink() or source.is_symlink():
        raise ValueError("Invalid archive path")
    expected = state["fileHash"]
    lease.check()
    if target.exists():
        if digest(target) != expected:
            raise ValueError("Archive collision: content differs")
        if source.exists() and digest(source) == expected:
            source.unlink()
    elif source.is_file() and digest(source) == expected:
        # Rename is atomic within the shared file volume. Do not overwrite existing archives.
        source.rename(target)
    else:
        raise ValueError("Source changed or disappeared before archival; preserve pending state")
    archive_hash = digest(target)
    if archive_hash != expected:
        raise ValueError("Archive digest mismatch")
    lease.check()
    api.call("POST", f"/runs/{claim['runId']}/archive", {
        **state, "archiveHash": archive_hash}, claim)
    state_path.unlink(missing_ok=True)


def acquire(root, claim, inbox, lease):
    """Optional direct acquisition on the server; never pass the platform token to sources."""
    version = claim["config"]["worker"]["version"]
    if "-production-" not in version:
        return
    if claim["config"]["code"] == "internal_staff":
        return  # T-1 files are produced by the independent daily source job.
    configured = os.environ.get("COLLECTION_SOURCE_CONNECTIONS_ROOT")
    if not configured:
        return  # Compatibility: the server's independent producer may deliver CSVs.
    category = claim["config"]["code"]
    if category not in ("internal_staff", "running_efficiency"):
        raise ValueError("Production acquisition category mismatch")
    credentials = Path(configured).resolve(strict=True) / (category + ".json")
    if not credentials.is_file():
        raise ValueError("Missing server source connection configuration for " + category)
    with tempfile.TemporaryDirectory(prefix="metric-acquire-") as temporary:
        entry = snapshot(root, claim, Path(temporary))
        adapter = entry.with_name("source_export.py")
        if not adapter.is_file():
            raise ValueError("Production release has no acquisition adapter")
        environment = {key: os.environ[key] for key in ("PATH", "JAVA_HOME", "JRE_HOME", "SystemRoot", "SYSTEMROOT", "WINDIR", "TEMP", "TMP", "LANG") if key in os.environ}
        environment["TZ"] = "Asia/Shanghai"
        report = Path(temporary) / "acquisition-result.json"
        environment["COLLECTION_ACQUISITION_RESULT"] = str(report)
        args = [sys.executable, "-E", "-s", "-B", "-X", "utf8", str(adapter),
                "--source-config", str(entry.with_name("sources.json")),
                "--connections", str(credentials), "--output-dir", str(inbox)]
        with tempfile.TemporaryFile() as log:
            process = subprocess.Popen(args, cwd=temporary, env=environment, stdin=subprocess.DEVNULL,
                                       stdout=log, stderr=subprocess.STDOUT, start_new_session=os.name != "nt")
            deadline = time.monotonic() + claim["config"]["worker"]["timeoutSeconds"]
            try:
                while process.poll() is None:
                    lease.check()
                    if time.monotonic() >= deadline:
                        raise RuntimeError("Source acquisition timed out")
                    time.sleep(0.2)
                if report.is_file():
                    if report.stat().st_size > 65536:
                        raise RuntimeError("Invalid acquisition report")
                    result = json.loads(report.read_text(encoding="utf-8"))
                    failed = result.get("failedDatasets", [])
                    if failed:
                        # Only dataset names, never connection exception text/credentials.
                        raise RuntimeError("Source datasets failed: " + ", ".join(str(name) for name in failed)[:1500])
                if process.returncode:
                    # Connection exceptions may contain credentials; source logs stay private.
                    raise RuntimeError("Source acquisition failed; check server connection configuration and network")
            finally:
                if process.poll() is None:
                    kill(process)


def execute(api, root, claim, lease):
    config = claim["config"]
    external_codes = {code.strip() for code in os.environ.get("COLLECTION_EXTERNAL_SOURCE_CODES", "").split(",") if code.strip()}
    external = config["code"] in external_codes
    if claim.get("storageRootKey", "default") != ("external" if external else "default"):
        raise ValueError("Platform and Worker storage routing disagree")
    data_root = root
    if external:
        configured = Path(os.environ.get("COLLECTION_EXTERNAL_FILES_ROOT", ""))
        if not configured.is_absolute():
            raise ValueError("External collection root must be explicitly configured")
        data_root = configured.resolve(strict=True)
        if data_root.is_relative_to(root) or root.is_relative_to(data_root):
            raise ValueError("External collection root overlaps normal files root")
    inbox = directory(data_root, config["importPath"])
    archive = directory(data_root, config["archivePath"])
    if inbox.is_relative_to(archive) or archive.is_relative_to(inbox):
        raise ValueError("Input and archive directories overlap")
    state_root = data_root / "collection-worker" / config["code"]
    state_root.mkdir(parents=True, exist_ok=True)
    errors = []
    blocked = set()
    for pending in sorted(state_root.glob("pending-*.json")):
        lease.check()
        state = {}
        try:
            state = json.loads(pending.read_text(encoding="utf-8"))
            receipt = status(api, claim, state)
            if receipt.get("state") == "COMMITTED":
                archive_pending(api, claim, lease, inbox, archive, pending, state)
            else:
                # A crash before submission leaves the original file ready to parse again.
                pending.unlink()
        except Exception as error:
            errors.append(str(error))
            blocked.add(state.get("sourceFile"))
    # Recover committed receipts first. An upstream outage must never prevent
    # processing existing deliveries or the successful part of an acquisition.
    try:
        acquire(root, claim, inbox, lease)
    except Exception as error:
        lease.check()
        errors.append(str(error))
    # Daily runs must collect newly published files in this run, after they settle.
    if (config.get("schedule") or {}).get("type") == "daily":
        deadline = time.monotonic() + config["settleSeconds"]
        while time.monotonic() < deadline:
            lease.check()
            time.sleep(min(0.2, max(0, deadline - time.monotonic())))
    cursor_file = state_root / "cursor.json"
    cursor = json.loads(cursor_file.read_text(encoding="utf-8")) if cursor_file.exists() else ""
    files = sorted(path.name for path in inbox.iterdir() if path.is_file() and not path.is_symlink()
                   and path.suffix.lower() == "." + config["format"] and fnmatch.fnmatchcase(path.name, config["filePattern"]))
    files = [name for name in files if name > cursor] + [name for name in files if name <= cursor]
    for name in files[:100]:
        lease.check()
        atomic_json(cursor_file, name)
        if name in blocked:
            continue
        source = inbox / name
        try:
            before = source.stat()
            if time.time() - before.st_mtime < config["settleSeconds"]:
                continue
            payload = parse(root, claim, source, lease)
            after = source.stat()
            if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns):
                raise ValueError("Input changed during collection")
            pending = state_root / ("pending-" + hashlib.sha256(name.encode("utf-8")).hexdigest() + ".json")
            state = {key: payload[key] for key in ("sourceFile", "fileHash", "businessDate") if key in payload}
            # Persist before HTTP: a crash after commit but before its response is recoverable.
            atomic_json(pending, state)
            receipt = status(api, claim, state)
            if receipt.get("state") != "COMMITTED":
                lease.check()
                try:
                    receipt = api.call("POST", f"/runs/{claim['runId']}/batches", payload, claim)
                except ApiError:
                    receipt = status(api, claim, state)
                    if receipt.get("state") != "COMMITTED":
                        raise
            archive_pending(api, claim, lease, inbox, archive, pending, state)
            LOG.info("Committed and archived source=%s file=%s batch=%s", config["code"], name, receipt["batchId"])
        except Exception as error:
            lease.check()
            errors.append(name + ": " + str(error))
    return "\n".join(errors)[:4000] or None


@contextmanager
def worker_lock(root):
    state = root / "collection-worker"
    state.mkdir(parents=True, exist_ok=True)
    with (state / "worker.lock").open("a+b") as stream:
        stream.seek(0)
        stream.write(b"0")
        stream.flush()
        stream.seek(0)
        if os.name == "nt":
            import msvcrt
            msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
        else:
            import fcntl
            fcntl.flock(stream.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        try:
            yield
        finally:
            if os.name == "nt":
                stream.seek(0)
                msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--api-base", default=os.environ.get("COLLECTION_WORKER_API", "http://127.0.0.1:18080"))
    parser.add_argument("--files-root", required=True, type=Path)
    parser.add_argument("--worker-id", default=os.environ.get("COLLECTION_WORKER_ID", "local-worker"))
    args = parser.parse_args()
    root = args.files_root.resolve(strict=True)
    token = os.environ.get("COLLECTION_WORKER_TOKEN", "")
    api = Api(args.api_base, token)
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    with ExitStack() as locks:
        locks.enter_context(worker_lock(root))
        if os.environ.get("COLLECTION_EXTERNAL_SOURCE_CODES", "").strip():
            configured = Path(os.environ.get("COLLECTION_EXTERNAL_FILES_ROOT", ""))
            if not configured.is_absolute():
                raise ValueError("External collection root must be explicitly configured")
            external_root = configured.resolve(strict=True)
            if external_root.is_relative_to(root) or root.is_relative_to(external_root):
                raise ValueError("External collection root overlaps normal files root")
            locks.enter_context(worker_lock(external_root))
        LOG.info("Worker started id=%s", args.worker_id)
        while True:
            try:
                claim = api.call("POST", "/claims", {"workerId": args.worker_id})
                if not claim:
                    time.sleep(2)
                    continue
                with Lease(api, claim) as lease:
                    error = None
                    try:
                        error = execute(api, root, claim, lease)
                    except Exception as failure:
                        error = str(failure)[:4000]
                    lease.check()
                    api.call("POST", f"/runs/{claim['runId']}/finish", {"error": error}, claim)
                    if error:
                        LOG.error("Task failed source=%s error=%s", claim["config"]["code"], error)
            except KeyboardInterrupt:
                return
            except Exception as error:
                LOG.error("Worker cycle failed: %s", error)
                time.sleep(5)


if __name__ == "__main__":
    main()
