from __future__ import annotations

import json
import hashlib
import zipfile
from pathlib import Path
import unittest


COMPONENT_ROOT = Path(__file__).resolve().parents[1]


class OmniFlowComponentBundleTest(unittest.TestCase):
    MCP_TOOLS = {
        "run_gui",
        "run_function",
        "list_functions",
        "register_function",
    }

    def test_catalog_archive_exists_and_matches_checksum(self) -> None:
        catalog = json.loads((COMPONENT_ROOT.parent / "catalog.v1.json").read_text())
        runtime = next(p for p in catalog["plugins"]
                       if p["id"] == "com.omnimind.omni-vlm-lite")["runtimeSkill"]
        archive = COMPONENT_ROOT.parents[1] / "artifacts" / Path(runtime["packagedArchivePath"]).name
        self.assertTrue(archive.is_file(), f"Missing release input: {archive}")
        self.assertEqual(hashlib.sha256(archive.read_bytes()).hexdigest(), runtime["packagedArchiveSha256"])
        with zipfile.ZipFile(archive) as bundle:
            self.assertIsNone(bundle.testzip())

    def test_component_contract_matches_current_runtime_layout(self) -> None:
        component = json.loads(
            (COMPONENT_ROOT / "component.json").read_text(encoding="utf-8")
        )
        self.assertEqual(component["schemaVersion"], 1)
        self.assertEqual(component["id"], "com.omnimind.omni-vlm-lite")
        self.assertRegex(component["version"], r"^\d+\.\d+\.\d+$")
        self.assertEqual(component["kind"], "runtime_component")
        self.assertEqual(component["androidAdapter"], "omniflow_android_gui")
        self.assertEqual(component["skill"]["id"], "omniflow-gui-runtime")
        self.assertEqual(
            component["install"],
            {
                "manager": "bundled",
                "sitePackages": "vendor/site-packages",
            },
        )
        self.assertFalse(
            (COMPONENT_ROOT / "runtime-skill/omniflow-gui-runtime/uv.lock").exists()
        )
        self.assertTrue((COMPONENT_ROOT / "schemas/oob").is_dir())

        catalog = json.loads(
            (COMPONENT_ROOT.parent / "catalog.v1.json").read_text(encoding="utf-8")
        )["plugins"][0]
        self.assertEqual(
            catalog["runtimeSkill"]["packagedArchivePath"],
            f"runtime-components/omniflow-gui-runtime-{component['version']}.zip",
        )

        mcp = component["mcp"]
        self.assertEqual(mcp["providedBy"], "openomnibot")
        self.assertEqual(mcp["transports"], ["streamable-http"])
        self.assertNotIn("deviceEndpoint", mcp)
        self.assertEqual(set(mcp["tools"]), self.MCP_TOOLS)

if __name__ == "__main__":
    unittest.main()
