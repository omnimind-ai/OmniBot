#!/usr/bin/env python3
"""Freeze HEAD and working-tree Flutter sources without modifying either."""
import argparse
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import shutil
import subprocess
import tarfile
from urllib.parse import urljoin

ROOT = Path(__file__).resolve().parents[3]
PACKAGE = "cn.com.omnimind.bot.runtimeperf"


def extract_git_lib(archive, destination):
    """Extract only regular source files/directories, including on Python 3.9."""
    root = destination.resolve()
    members = archive.getmembers()
    for member in members:
        name = PurePosixPath(member.name)
        if name.is_absolute() or ".." in name.parts:
            raise ValueError("Source archive contains an unsafe member path")
        if not member.isfile() and not member.isdir():
            raise ValueError("Source archive must contain only files and directories")
        try:
            (root / member.name).resolve().relative_to(root)
        except ValueError:
            raise ValueError("Source archive escapes the snapshot directory")
    for member in members:
        path = root / member.name
        if member.isdir():
            path.mkdir(parents=True, exist_ok=True)
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            source = archive.extractfile(member)
            if source is None:
                raise ValueError("Source archive file has no contents")
            with source, path.open("wb") as target:
                shutil.copyfileobj(source, target)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True)
    parser.add_argument("--baseline", default="HEAD")
    args = parser.parse_args()
    output = Path(args.output).resolve()
    relative = output.relative_to(ROOT)
    if subprocess.run(["git", "check-ignore", "--no-index", "-q", "--", str(relative)], cwd=ROOT).returncode:
        raise SystemExit("Output must be an ignored directory inside this checkout")
    if output.exists() and any(output.iterdir()):
        raise SystemExit("Choose a fresh output directory; never overwrite snapshots/results")
    revision = subprocess.check_output(["git", "rev-parse", args.baseline], cwd=ROOT, text=True).strip()
    config_file = ROOT / "ui/.dart_tool/package_config.json"
    config = json.loads(config_file.read_text())
    output.mkdir(parents=True, exist_ok=True)
    manifest = {"package": PACKAGE, "baselineCommit": revision, "sources": {}}
    for label in ("baseline", "after"):
        ui = output / label / "ui"
        ui.mkdir(parents=True)
        if label == "baseline":
            archive = subprocess.check_output(["git", "archive", f"{revision}:ui/lib"], cwd=ROOT)
            (ui / "lib").mkdir()
            with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
                extract_git_lib(tar, ui / "lib")
        else:
            shutil.copytree(ROOT / "ui/lib", ui / "lib")
        source_hashes = {str(path.relative_to(ui)): hashlib.sha256(path.read_bytes()).hexdigest()
                         for path in sorted((ui / "lib").rglob("*")) if path.is_file()}
        for name in ("pubspec.yaml", "pubspec.lock", "l10n.yaml", "analysis_options.yaml", ".flutter-plugins-dependencies"):
            if (ROOT / "ui" / name).exists():
                shutil.copyfile(ROOT / "ui" / name, ui / name)
        for name in ("assets", "shaders"):
            (ui / name).symlink_to(ROOT / "ui" / name, target_is_directory=True)
        copied = json.loads(json.dumps(config))
        for package in copied["packages"]:
            package["rootUri"] = (ui.as_uri() + "/") if package["name"] == "ui" else urljoin(config_file.as_uri(), package["rootUri"])
        (ui / ".dart_tool").mkdir()
        (ui / ".dart_tool/package_config.json").write_text(json.dumps(copied, indent=2))
        graph = ROOT / "ui/.dart_tool/package_graph.json"
        if graph.exists():
            shutil.copyfile(graph, ui / ".dart_tool/package_graph.json")
        shutil.copyfile(Path(__file__).with_name("profile_main.dart"), ui / "lib/runtime_perf_main.dart")
        (ui / ".runtime-perf-snapshot").write_text(PACKAGE + "\n")
        manifest["sources"][label] = {"libSha256": source_hashes, "source": str(ui)}
    manifest["probeSha256"] = hashlib.sha256(Path(__file__).with_name("profile_main.dart").read_bytes()).hexdigest()
    manifest["changedDartFiles"] = [path for path in sorted(set(manifest["sources"]["baseline"]["libSha256"]) | set(manifest["sources"]["after"]["libSha256"]))
        if manifest["sources"]["baseline"]["libSha256"].get(path) != manifest["sources"]["after"]["libSha256"].get(path)]
    (output / "runtime-perf.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"Prepared {output}; no device access. Changed Dart files: {len(manifest['changedDartFiles'])}")


if __name__ == "__main__":
    main()
