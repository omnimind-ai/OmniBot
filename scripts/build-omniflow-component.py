#!/usr/bin/env python3
"""Build the self-contained OmniFlow Android runtime component."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import subprocess
import urllib.request
from tempfile import TemporaryDirectory
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo


def parse_args() -> argparse.Namespace:
    repository_root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--component-root",
        type=Path,
        default=repository_root / "plugins/omni-vlm-lite",
    )
    parser.add_argument(
        "--omniflow-working-tree",
        action="store_true",
        help="Package the adjacent OmniFlow working tree, including local edits.",
    )
    parser.add_argument(
        "--omnitransfer-working-tree",
        action="store_true",
        help="Package the adjacent OmniTransfer working tree, including local edits.",
    )
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument(
        "--omnitransfer-checkpoint", type=Path,
        help="Package an explicitly selected canonical checkpoint and record its digest.",
    )
    return parser.parse_args()


def excluded(relative: Path) -> bool:
    return (
        "__pycache__" in relative.parts
        or relative.suffix in {".pyc", ".pyo"}
        or relative.name in {".DS_Store", "MARKET_RUNTIME_SKILL", "PACKAGED_RUNTIME_SKILL"}
        or ".venv" in relative.parts
    )


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def copy_tree(source: Path, target: Path) -> None:
    shutil.copytree(
        source,
        target,
        ignore=shutil.ignore_patterns("__pycache__", "*.pyc", "*.pyo", ".DS_Store"),
    )


def copy_file(source: Path, target: Path, label: str) -> None:
    if not source.is_file():
        raise RuntimeError(f"component_file_missing:{label}:{source}")
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, target)


def copy_selected_files(
    source_root: Path,
    target_root: Path,
    relative_paths: set[str],
) -> None:
    for relative in sorted(relative_paths):
        copy_file(
            source_root / relative,
            target_root / relative,
            relative,
        )


def download_verified(url: str, sha256: str, target: Path) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    with urllib.request.urlopen(url) as response:
        contents = response.read()
    if hashlib.sha256(contents).hexdigest() != sha256:
        raise RuntimeError("component_vendor_checksum_mismatch:json-repair")
    target.write_bytes(contents)


def sha256_directory(root: Path) -> str:
    digest = hashlib.sha256()
    for path in sorted(item for item in root.rglob("*") if item.is_file()):
        relative = path.relative_to(root)
        if excluded(relative):
            continue
        digest.update(relative.as_posix().encode("utf-8"))
        digest.update(b"\0")
        digest.update(path.read_bytes())
    return digest.hexdigest()


def require_digest(actual: str, expected: str, label: str) -> None:
    if actual != expected:
        raise RuntimeError(f"component_digest_mismatch:{label}:{expected}:{actual}")


def read_properties(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if line and not line.startswith(("#", "!")) and "=" in line:
            key, value = line.split("=", 1)
            values[key.strip()] = value.strip()
    return values


def replace_properties(text: str, replacements: dict[str, str]) -> str:
    output: list[str] = []
    seen: set[str] = set()
    for raw_line in text.splitlines():
        key = raw_line.split("=", 1)[0].strip() if "=" in raw_line else ""
        if key in replacements:
            output.append(f"{key}={replacements[key]}")
            seen.add(key)
        else:
            output.append(raw_line)
    missing = set(replacements) - seen
    if missing:
        raise RuntimeError(f"runtime_properties_missing:{','.join(sorted(missing))}")
    return "\n".join(output) + "\n"


def replace_schema_properties(text: str, schema_root: Path) -> str:
    output = [
        line
        for line in text.splitlines()
        if not line.strip().startswith("schema.")
    ]
    for path in sorted(item for item in schema_root.iterdir() if item.is_file()):
        output.append(f"schema.{path.name}.sha256={sha256_file(path)}")
    return "\n".join(output) + "\n"


def git_head(repository: Path) -> str:
    return subprocess.check_output(
        ("git", "-C", str(repository), "rev-parse", "HEAD"),
        text=True,
    ).strip()


def git_export_tree(
    repository: Path,
    revision: str,
    prefix: str,
    target: Path,
    include: set[str] | None = None,
) -> None:
    names = subprocess.check_output(
        ("git", "-C", str(repository), "ls-tree", "-r", "--name-only", revision, "--", prefix),
        text=True,
    ).splitlines()
    if not names:
        raise RuntimeError(f"component_git_tree_missing:{repository}:{revision}:{prefix}")
    for name in names:
        relative = Path(name).relative_to(prefix)
        if include is not None and relative.as_posix() not in include:
            continue
        if excluded(relative):
            continue
        contents = subprocess.check_output(
            ("git", "-C", str(repository), "show", f"{revision}:{name}"),
        )
        output = target / relative
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_bytes(contents)


def git_export_file(
    repository: Path,
    revision: str,
    relative: str,
    target: Path,
) -> None:
    contents = subprocess.check_output(
        ("git", "-C", str(repository), "show", f"{revision}:{relative}"),
    )
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(contents)


def write_deterministic_zip(source: Path, target: Path) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_suffix(target.suffix + ".tmp")
    with ZipFile(temporary, "w", compression=ZIP_DEFLATED, compresslevel=9) as archive:
        for path in sorted(item for item in source.rglob("*") if item.is_file()):
            relative = path.relative_to(source)
            if excluded(relative):
                continue
            info = ZipInfo(relative.as_posix(), date_time=(1981, 1, 1, 0, 0, 0))
            info.compress_type = ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            archive.writestr(info, path.read_bytes())
    temporary.replace(target)


def main() -> int:
    args = parse_args()
    component_root = args.component_root.resolve()
    skill_root = component_root / "runtime-skill/omniflow-gui-runtime"
    component_path = component_root / "component.json"
    repository_root = Path(__file__).resolve().parents[1]
    omni_root = repository_root.parent
    # OmniFlow is the sole owner of the runtime and its schemas.  The app
    # repository only packages the canonical export; it must not maintain a
    # second schema tree under plugins/.
    omniflow_repository = omni_root / "OmniFlow-exp"
    omnitransfer_repository = omni_root / "OmniTransfer"
    properties_path = skill_root / "scripts/runtime/runtime.properties"
    properties = read_properties(properties_path)
    for source in ("omniflow", "omnitransfer"):
        if properties[f"{source}.commit"].endswith("-dirty") and not getattr(args, f"{source}_working_tree"):
            raise RuntimeError(
                f"component_source_not_committed:{source}:use --{source}-working-tree for a development build"
            )
    component = json.loads(component_path.read_text(encoding="utf-8"))
    if component.get("schemaVersion") != 1:
        raise RuntimeError("component_schema_version_unsupported")
    if not str(component.get("version") or "").strip():
        raise RuntimeError("component_version_missing")

    with TemporaryDirectory(prefix="omniflow-component-") as temporary:
        staging = Path(temporary) / "component"
        staging.mkdir()

        copy_file(component_path, staging / "component.json", "component.json")
        copy_file(component_root / "README.md", staging / "README.md", "README.md")
        copy_file(component_root / "INSTALL_DIR.json", staging / "INSTALL_DIR.json", "INSTALL_DIR.json")
        copy_file(skill_root / "SKILL.md", staging / "SKILL.md", "SKILL.md")
        wheel_path = staging / "vendor/json_repair-0.61.7-py3-none-any.whl"
        download_verified(
            "https://files.pythonhosted.org/packages/76/da/7f9e2b0a1120b107a204bbab6d0ef7ff2ae37790bddc5ee21c9c1f961f3b/json_repair-0.61.7-py3-none-any.whl",
            "45c99b8cffef404e846b60d3dc21fc6f0fd5a4595cebad169dfab083ffb8246a",
            wheel_path,
        )
        with ZipFile(wheel_path) as wheel:
            wheel.extractall(staging / "vendor/site-packages")
        wheel_path.unlink()
        # Native dependencies are installed by the package prepare entrypoint for
        # the active distribution; a musl Pillow wheel cannot run on Ubuntu.
        if args.omniflow_working_tree:
            copy_tree(
                omniflow_repository / "omniflow",
                staging / "scripts/runtime/python/omniflow",
            )
            config_file = omniflow_repository / "config/paper_androidworld.json"
            if config_file.is_file():
                copy_file(
                    config_file,
                    staging / "scripts/runtime/python/config/paper_androidworld.json",
                    "config/paper_androidworld.json",
                )
            omniflow_revision = f"{git_head(omniflow_repository)}-dirty"
        else:
            git_export_tree(
                omniflow_repository,
                properties["omniflow.commit"],
                "omniflow",
                staging / "scripts/runtime/python/omniflow",
            )
            config_path = "config/paper_androidworld.json"
            config_exists = subprocess.run(
                (
                    "git",
                    "-C",
                    str(omniflow_repository),
                    "cat-file",
                    "-e",
                    f"{properties['omniflow.commit']}:{config_path}",
                ),
                check=False,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            ).returncode == 0
            if config_exists:
                git_export_file(
                    omniflow_repository,
                    properties["omniflow.commit"],
                    config_path,
                    staging / "scripts/runtime/python/config/paper_androidworld.json",
                )
            omniflow_revision = properties["omniflow.commit"]
        if args.omniflow_working_tree:
            copy_tree(
                omniflow_repository / "schemas",
                staging / "scripts/runtime/python/schemas",
            )
        else:
            git_export_tree(
                omniflow_repository,
                properties["omniflow.commit"],
                "schemas",
                staging / "scripts/runtime/python/schemas",
            )
        transfer_files = {
            "__init__.py",
            "learned_matcher.py",
            "numpy_v9_matcher.py",
            "numpy_v10_matcher.py",
            "page_embedding.py",
            "runtime.py",
            "ui_graph.py",
            "unified_alignment.py",
            "visual_descriptor.py",
        }
        transfer_target = staging / "scripts/runtime/.runtime/omnitransfer/src/omnitransfer"
        if args.omnitransfer_working_tree:
            copy_selected_files(
                omnitransfer_repository / "src/omnitransfer",
                transfer_target,
                transfer_files,
            )
            omnitransfer_revision = f"{git_head(omnitransfer_repository)}-dirty"
        else:
            git_export_tree(
                omnitransfer_repository,
                properties["omnitransfer.commit"],
                "src/omnitransfer",
                transfer_target,
                include=transfer_files,
            )
            omnitransfer_revision = properties["omnitransfer.commit"]
        checkpoint_relative = Path(properties["omnitransfer.checkpoint"])
        checkpoint_source = omnitransfer_repository / "src/omnitransfer" / checkpoint_relative
        if args.omnitransfer_checkpoint is not None:
            checkpoint_source = args.omnitransfer_checkpoint.resolve(strict=True)
            checkpoint_relative = Path("checkpoints") / checkpoint_source.name
        generated_checkpoint: Path | None = None
        if not checkpoint_source.is_file():
            torch_python = omnitransfer_repository / ".venv/bin/python"
            torch_checkpoint = (
                omnitransfer_repository
                / "output/point_sparse_graph_original_multimodal_v1/full_seed17/model.pt"
            )
            exporter = omnitransfer_repository / "scripts/export_runtime_checkpoint.py"
            if not torch_python.is_file() or not torch_checkpoint.is_file() or not exporter.is_file():
                raise RuntimeError(
                    "omnitransfer_v10_numpy_export_inputs_missing:"
                    f"python={torch_python}:checkpoint={torch_checkpoint}:exporter={exporter}"
                )
            generated_checkpoint = staging / ".generated" / checkpoint_relative.name
            subprocess.run(
                (
                    str(torch_python),
                    str(exporter),
                    "--input",
                    str(torch_checkpoint),
                    "--output",
                    str(generated_checkpoint),
                    "--semantic-exact-decoder-bonus",
                    "1.5",
                ),
                check=True,
                cwd=omnitransfer_repository,
            )
        copy_file(
            generated_checkpoint or checkpoint_source,
            transfer_target / checkpoint_relative,
            "omnitransfer.checkpoint",
        )
        if generated_checkpoint is not None:
            # The generated checkpoint has already been copied into the
            # runtime tree; keep the staging root free of duplicate artifacts.
            shutil.rmtree(generated_checkpoint.parent)
        require_digest(
            sha256_directory(staging / "scripts/runtime/python/omniflow"),
            properties["omniflow.source.sha256"]
            if not args.omniflow_working_tree
            else sha256_directory(staging / "scripts/runtime/python/omniflow"),
            "omniflow",
        )
        omnitransfer_source_digest = sha256_directory(
            staging / "scripts/runtime/.runtime/omnitransfer/src/omnitransfer"
        )
        if not args.omnitransfer_working_tree and generated_checkpoint is None and args.omnitransfer_checkpoint is None:
            require_digest(
                omnitransfer_source_digest,
                properties["omnitransfer.source.sha256"],
                "omnitransfer",
            )
        if not args.omniflow_working_tree:
            for key, expected in properties.items():
                if key.startswith("schema.") and key.endswith(".sha256"):
                    name = key.removeprefix("schema.").removesuffix(".sha256")
                    require_digest(
                        sha256_file(staging / "scripts/runtime/python/schemas/oob" / name),
                        expected,
                        f"schema:{name}",
                    )
        runtime_properties = properties_path.read_text(encoding="utf-8")
        if args.omniflow_working_tree:
            runtime_properties = replace_schema_properties(
                runtime_properties,
                staging / "scripts/runtime/python/schemas/oob",
            )
            replacements = {
                "omniflow.commit": omniflow_revision,
                "omniflow.source.sha256": sha256_directory(
                    staging / "scripts/runtime/python/omniflow"
                ),
            }
        else:
            replacements = {}
        if args.omnitransfer_working_tree:
            replacements.update(
                {
                    "omnitransfer.commit": omnitransfer_revision,
                    "omnitransfer.source.sha256": omnitransfer_source_digest,
                }
            )
        if generated_checkpoint is not None or args.omnitransfer_checkpoint is not None:
            replacements.update(
                {
                    "omnitransfer.checkpoint": checkpoint_relative.as_posix(),
                    "omnitransfer.source.sha256": omnitransfer_source_digest,
                    "omnitransfer.checkpoint.sha256": sha256_file(
                        transfer_target / checkpoint_relative
                    ),
                }
            )
        if replacements:
            runtime_properties = replace_properties(runtime_properties, replacements)
        (staging / "scripts/runtime/runtime.properties").write_text(
            runtime_properties,
            encoding="utf-8",
        )
        copy_tree(component_root / "host", staging / "host")
        subprocess.run(
            [str(omniflow_repository / ".venv/bin/python"),
             str(staging / "host/describe.py"), str(staging)],
            cwd=staging,
            env={**os.environ, "PYTHONPATH": str(staging / "scripts/runtime/python")},
            check=True,
        )
        write_deterministic_zip(staging, args.output.resolve())

    print("OMNIFLOW_COMPONENT=PASS")
    print(f"component_id={component['id']}")
    print(f"component_version={component['version']}")
    print(f"component={args.output.resolve()}")
    print(f"component_sha256={sha256_file(args.output.resolve())}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
