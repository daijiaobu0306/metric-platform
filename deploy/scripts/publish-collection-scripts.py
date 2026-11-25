"""Publish an immutable collection release; does not change tasks or business data."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=Path(__file__).resolve().parents[2] / "backend/collection-python/plugins/standard")
    parser.add_argument("--category", choices=("external_customer","internal_staff","satisfaction_report","running_efficiency","compliance","resource_input","data_quality"), help="Use the category-owned scripts and profile")
    parser.add_argument("--files-root", required=True, type=Path, help="Actual upload.base_path, not necessarily repository files/")
    parser.add_argument("--version", required=True)
    parser.add_argument("--profile", type=Path, help="Category profile JSON; metadata and parser overrides")
    args = parser.parse_args()
    if not re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}", args.version):
        parser.error("Invalid release version")
    if args.category:
        args.source = Path(__file__).resolve().parents[2] / "backend/collection-python/categories" / args.category
        if args.profile is None:
            args.profile = args.source / "profile.json"
    profile = json.loads(args.profile.read_text(encoding="utf-8")) if args.profile else {}
    source = args.source.resolve(strict=True)
    root = args.files_root.resolve(strict=True) / "collection-scripts"
    releases = root / "releases"
    staging = root / "staging"
    releases.mkdir(parents=True, exist_ok=True)
    staging.mkdir(parents=True, exist_ok=True)
    destination = releases / args.version
    if destination.exists():
        parser.error("Release already exists; publish a new version instead")
    for required in ("reader.py", "config.json", "requirements.txt"):
        if not (source / required).is_file():
            parser.error("Missing release file: " + required)
    json.loads((source / "config.json").read_text(encoding="utf-8"))
    temporary = Path(tempfile.mkdtemp(prefix="publish-", dir=staging))
    try:
        hashes = {}
        for path in sorted(source.rglob("*")):
            relative = path.relative_to(source)
            if "__pycache__" in relative.parts or path.suffix == ".pyc":
                continue
            if path.is_symlink():
                raise ValueError("Release cannot contain symbolic links")
            if not path.is_file() or path.name in ("README.md", "manifest.json"):
                continue
            target = temporary / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(path, target)
            hashes[relative.as_posix()] = hashlib.sha256(target.read_bytes()).hexdigest()
        if profile:
            config = json.loads((temporary / "config.json").read_text(encoding="utf-8"))
            config.update(profile.get("config", {}))
            config["categoryCode"] = profile["categoryCode"]
            (temporary / "config.json").write_text(json.dumps(config, ensure_ascii=False, indent=2), encoding="utf-8")
            hashes["config.json"] = hashlib.sha256((temporary / "config.json").read_bytes()).hexdigest()
        manifest = dict(protocolVersion=1, version=args.version, entry="reader.py", files=hashes)
        if profile:
            manifest.update(categoryCode=profile["categoryCode"], displayName=profile["displayName"])
            if profile.get("sourceCode"):
                manifest["datasetCode"] = profile["sourceCode"]
                manifest["outputProtocol"] = profile.get("outputProtocol", "raw-rows" if profile["sourceCode"] == "compliance_detail" else "metric-values")
        (temporary / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
        os.rename(temporary, destination)
        print(json.dumps({"version": args.version, "directory": str(destination)}, ensure_ascii=False))
    finally:
        if temporary.exists():
            shutil.rmtree(temporary)


if __name__ == "__main__":
    main()
