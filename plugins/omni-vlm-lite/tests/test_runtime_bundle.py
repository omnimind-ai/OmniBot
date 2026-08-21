from __future__ import annotations

import hashlib
from io import BytesIO
import json
from pathlib import Path
import subprocess
import unittest
from zipfile import ZipFile


COMPONENT_ROOT = Path(__file__).resolve().parents[1]
REPOSITORY_ROOT = COMPONENT_ROOT.parents[1]
CATALOG_PATH = REPOSITORY_ROOT / "plugins/catalog.v1.json"
ARCHIVE_PATH = REPOSITORY_ROOT / "artifacts/omniflow-gui-runtime-2.1.8.zip"
OMNIFLOW_ROOT = REPOSITORY_ROOT.parent / "OmniFlow-exp"
OMNITRANSFER_ROOT = REPOSITORY_ROOT.parent / "OmniTransfer"


def read_properties(contents: str) -> dict[str, str]:
    values: dict[str, str] = {}
    for raw_line in contents.splitlines():
        line = raw_line.strip()
        if line and not line.startswith(("#", "!")) and "=" in line:
            key, value = line.split("=", 1)
            values[key.strip()] = value.strip()
    return values


def committed_file(repository: Path, revision: str, relative: str) -> bytes:
    return subprocess.check_output(
        ("git", "-C", str(repository), "show", f"{revision}:{relative}")
    )


class RuntimeBundleTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.archive_path = ARCHIVE_PATH
        with ZipFile(cls.archive_path) as archive:
            cls.names = set(archive.namelist())
            cls.files = {
                name: archive.read(name)
                for name in cls.names
                if not name.endswith("/")
            }
        cls.properties = read_properties(
            cls.files["scripts/runtime/runtime.properties"].decode("utf-8")
        )

    def test_release_asset_matches_catalog(self) -> None:
        catalog = json.loads(CATALOG_PATH.read_text(encoding="utf-8"))["plugins"][0]
        self.assertEqual(self.archive_path.stat().st_size, catalog["downloadSizeBytes"])
        self.assertEqual(
            hashlib.sha256(self.archive_path.read_bytes()).hexdigest(),
            catalog["runtimeSkill"]["componentArchiveSha256"],
        )
        self.assertIn(f"-{catalog['version']}.zip", catalog["runtimeSkill"]["componentArchiveUrl"])

    def test_release_is_self_contained_mobile_component(self) -> None:
        required = {
            "component.json",
            "README.md",
            "INSTALL_DIR.json",
            "SKILL.md",
            "scripts/runtime/python/omniflow/bridge.py",
            "scripts/runtime/python/config/paper_androidworld.json",
            "vendor/site-packages/json_repair/__init__.py",
            "scripts/runtime/python/schemas/oob/omniflow_android_bridge.v2.json",
            "scripts/runtime/.runtime/omnitransfer/src/omnitransfer/runtime.py",
        }
        self.assertTrue(required <= self.names)
        self.assertIn(
            "scripts/runtime/python/omniflow/catalog/releases/2026.08.06.2/function_store.json",
            self.names,
        )
        self.assertIn(
            "scripts/runtime/python/omniflow/catalog/releases/2026.08.06.2/states.json.xz.b64",
            self.names,
        )
        self.assertFalse(any(name.endswith((".zip", ".whl")) for name in self.names))
        self.assertNotIn("pyproject.toml", self.names)
        self.assertNotIn("uv.lock", self.names)
        self.assertFalse(any(name.startswith("scripts/runtime/python/omniflow_mcp/") for name in self.names))
        self.assertFalse(any("/.venv/" in f"/{name}" for name in self.names))
        self.assertFalse(any(name.startswith(".venv/") for name in self.names))

    def test_release_pins_canonical_omniflow(self) -> None:
        commit = self.properties["omniflow.commit"]
        for relative in (
            "functions/assets.py",
            "runtime/core.py",
            "runtime/execution.py",
            "vlm/context.py",
            "vlm/planner.py",
        ):
            self.assertEqual(
                committed_file(OMNIFLOW_ROOT, commit, f"omniflow/{relative}"),
                self.files[f"scripts/runtime/python/omniflow/{relative}"],
            )
        execution = self.files[
            "scripts/runtime/python/omniflow/runtime/execution.py"
        ].decode("utf-8")
        self.assertIn("_ALIGNMENT_MIN_PROBABILITY = 0.0", execution)

    def test_release_pins_canonical_omnitransfer_v9(self) -> None:
        commit = self.properties["omnitransfer.commit"]
        prefix = "scripts/runtime/.runtime/omnitransfer/src/omnitransfer/"
        for relative in ("runtime.py", "numpy_v9_matcher.py"):
            self.assertEqual(
                committed_file(OMNITRANSFER_ROOT, commit, f"src/omnitransfer/{relative}"),
                self.files[prefix + relative],
            )
        checkpoint = prefix + self.properties["omnitransfer.checkpoint"]
        self.assertIn(checkpoint, self.names)
        with ZipFile(BytesIO(self.files[checkpoint])) as weights:
            weight_names = set(weights.namelist())
        self.assertIn("visual_encoder.0.weight.npy", weight_names)
        self.assertIn("missing_visual.npy", weight_names)
        runtime = self.files[prefix + "runtime.py"].decode("utf-8")
        self.assertIn("min_probability=0.0", runtime)
        self.assertIn("min_margin=0.0", runtime)
        self.assertNotIn("coordinate_stretch_fallback", runtime)

if __name__ == "__main__":
    unittest.main()
