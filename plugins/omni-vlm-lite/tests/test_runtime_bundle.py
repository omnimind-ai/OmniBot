from __future__ import annotations

import hashlib
from io import BytesIO
import json
import os
from pathlib import Path
import subprocess
import unittest
from zipfile import ZipFile


COMPONENT_ROOT = Path(__file__).resolve().parents[1]
REPOSITORY_ROOT = COMPONENT_ROOT.parents[1]
CATALOG_PATH = REPOSITORY_ROOT / "plugins/catalog.v1.json"
ARCHIVE_PATH = Path(
    os.environ.get(
        "OMNIFLOW_COMPONENT_TEST_ARCHIVE",
        str(REPOSITORY_ROOT / "artifacts/omniflow-gui-runtime-2.2.12.zip"),
    )
)
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
    if revision.endswith("-dirty"):
        return (repository / relative).read_bytes()
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

    @unittest.skipUnless(os.environ.get("OMNIFLOW_APK_TEST_PATH"), "APK not supplied")
    def test_apk_embeds_selected_component_and_matching_local_catalog_digest(self) -> None:
        with ZipFile(os.environ["OMNIFLOW_APK_TEST_PATH"]) as apk:
            catalogs = [name for name in apk.namelist() if name == "assets/catalog.v1.json"]
            self.assertEqual(len(catalogs), 1)
            catalog = json.loads(apk.read(catalogs[0]))
            plugin = next(p for p in catalog["plugins"] if p["id"] == "com.omnimind.omni-vlm-lite")
            runtime = plugin["runtimeSkill"]
            prefix = catalogs[0].removesuffix("catalog.v1.json")
            payload = apk.read(prefix + runtime["packagedArchivePath"])
            self.assertEqual(hashlib.sha256(payload).hexdigest(), hashlib.sha256(self.archive_path.read_bytes()).hexdigest())
            self.assertEqual(hashlib.sha256(payload).hexdigest(), runtime["packagedArchiveSha256"])

    def test_release_asset_matches_catalog(self) -> None:
        if os.environ.get("OMNIFLOW_COMPONENT_TEST_ARCHIVE"):
            self.skipTest("working-tree component is not a catalog release asset")
        catalog = json.loads(CATALOG_PATH.read_text(encoding="utf-8"))["plugins"][0]
        self.assertEqual(self.archive_path.stat().st_size, catalog["downloadSizeBytes"])
        self.assertEqual(
            hashlib.sha256(self.archive_path.read_bytes()).hexdigest(),
            catalog["runtimeSkill"]["packagedArchiveSha256"],
        )
        self.assertIn(f"-{catalog['version']}.zip", catalog["runtimeSkill"]["packagedArchivePath"])

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

    def test_host_contract_declares_tools_and_only_public_entrypoints(self) -> None:
        host = json.loads(self.files["host.json"])
        self.assertEqual(host["interfaceVersion"], 1)
        for key in ("entrypoint", "prepareEntrypoint"):
            self.assertIn(host[key], self.files)
        tools = {tool["name"]: tool for tool in host["tools"]}
        self.assertFalse(tools["save_function"]["interactive"])
        self.assertTrue(tools["run_gui"]["interactive"])
        self.assertIn("run_log", tools["save_function"]["inputSchema"]["properties"])
        self.assertTrue(all(tool["description"] for tool in tools.values()))
        self.assertFalse(any(name.startswith("vendor/site-packages/PIL/") for name in self.files))

    def test_relocated_package_starts_and_reports_real_transfer_ready(self) -> None:
        import tempfile
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with ZipFile(self.archive_path) as archive:
                archive.extractall(root)
            (root / "scripts").rename(root / "engine")
            (root / "vendor").rename(root / "dependencies")
            host = json.loads((root / "host.json").read_text())
            host["sourceRoot"] = host["sourceRoot"].replace("scripts/", "engine/")
            (root / "host.json").write_text(json.dumps(host))
            entrypoint = root / host["entrypoint"]
            source = entrypoint.read_text().replace("scripts/", "engine/").replace("vendor/", "dependencies/")
            source = source.replace("/workspace/.omnibot/omniflow/omniflow.json", str(root / "user-data/store.json"))
            entrypoint.write_text(source)
            messages = [
                {"jsonrpc": "2.0", "id": "init", "method": "initialize", "params": {
                    "protocolVersion": host["protocol"], "capabilities": {}, "clientInfo": {"name": "package-test", "version": "1"}}},
                {"jsonrpc": "2.0", "method": "notifications/initialized"},
                {"jsonrpc": "2.0", "id": "tools", "method": "tools/list", "params": {}},
            ]
            env = {**os.environ, "PATH": str(OMNIFLOW_ROOT / ".venv/bin") + os.pathsep + os.environ["PATH"]}
            result = subprocess.run(["/bin/sh", host["entrypoint"]], cwd=root, env=env,
                input="".join(json.dumps(message) + "\n" for message in messages),
                capture_output=True, text=True, timeout=60)
            self.assertEqual(result.returncode, 0, result.stderr)
            replies = [json.loads(line) for line in result.stdout.splitlines()]
            initialization = next(value for value in replies if value.get("id") == "init")["result"]
            self.assertTrue(initialization["_meta"]["omniflow/runtime"]["omnitransfer_ready"])
            live_tools = next(value for value in replies if value.get("id") == "tools")["result"]["tools"]
            exported = {tool["name"]: tool["inputSchema"] for tool in host["tools"] if not tool.get("hostAction")}
            self.assertEqual(exported, {tool["name"]: tool["inputSchema"] for tool in live_tools})
            # Exercise a real planner action from the archive, with no adjacent
            # experiment source tree available on sys.path.
            env["PYTHONPATH"] = str(root / host["sourceRoot"])
            parsed = subprocess.run([str(OMNIFLOW_ROOT / ".venv/bin/python"), "-c", """
from omniflow.vlm.gui import parse_model_turn_response
call, _ = parse_model_turn_response(
    {"requested_model":"test", "tool_calls":[{"function":{"name":"open_app",
     "arguments":'{"package_name":"com.android.contacts"}'}}]},
    requested_model="test", turn_index=1, installed_apps={"Contacts":"com.android.contacts"})
assert call.arguments["package_name"] == "com.android.contacts"
"""], cwd=root, env=env, capture_output=True, text=True, timeout=30)
            self.assertEqual(parsed.returncode, 0, parsed.stderr)

    def test_release_pins_canonical_omniflow(self) -> None:
        commit = self.properties["omniflow.commit"]
        relatives = (
            "bridge.py",
            "functions/compiler.py",
            "functions/store.py",
            "runtime/core.py",
            "runtime/execution.py",
            "transfer/runtime.py",
            "vlm/gui.py",
        )
        for relative in relatives:
            self.assertEqual(
                committed_file(OMNIFLOW_ROOT, commit, f"omniflow/{relative}"),
                self.files[f"scripts/runtime/python/omniflow/{relative}"],
            )

    def test_manifest_digests_match_actual_packaged_sources_and_checkpoint(self) -> None:
        for key, prefix in (
            ("omniflow.source.sha256", "scripts/runtime/python/omniflow/"),
            ("omnitransfer.source.sha256", "scripts/runtime/.runtime/omnitransfer/src/omnitransfer/"),
        ):
            digest = hashlib.sha256()
            for name in sorted(self.files):
                if name.startswith(prefix):
                    digest.update(name.removeprefix(prefix).encode("utf-8"))
                    digest.update(b"\0")
                    digest.update(self.files[name])
            self.assertEqual(self.properties[key], digest.hexdigest(), key)
        checkpoint = "scripts/runtime/.runtime/omnitransfer/src/omnitransfer/" + self.properties["omnitransfer.checkpoint"]
        self.assertEqual(
            self.properties["omnitransfer.checkpoint.sha256"],
            hashlib.sha256(self.files[checkpoint]).hexdigest(),
        )

    def test_release_pins_canonical_omnitransfer(self) -> None:
        commit = self.properties["omnitransfer.commit"]
        prefix = "scripts/runtime/.runtime/omnitransfer/src/omnitransfer/"
        for relative in ("runtime.py", "numpy_v9_matcher.py", "numpy_v10_matcher.py"):
            if relative == "numpy_v10_matcher.py" and prefix + relative not in self.files:
                continue  # Historical releases predate V10.
            self.assertEqual(
                committed_file(OMNITRANSFER_ROOT, commit, f"src/omnitransfer/{relative}"),
                self.files[prefix + relative],
            )
        checkpoint = prefix + self.properties["omnitransfer.checkpoint"]
        self.assertIn(checkpoint, self.names)
        with ZipFile(BytesIO(self.files[checkpoint])) as weights:
            weight_names = set(weights.namelist())
            checkpoint_config = weights.read("__config_json__.npy")
        self.assertIn(b'"visual_encoder":"deterministic_icon_v1"', checkpoint_config)
        self.assertFalse(any(name.startswith("visual_encoder.") for name in weight_names))
        self.assertIn("missing_visual.npy", weight_names)
        runtime = self.files[prefix + "runtime.py"].decode("utf-8")
        self.assertIn("min_probability=0.0", runtime)
        self.assertIn("min_margin=0.0", runtime)
        self.assertNotIn("coordinate_stretch_fallback", runtime)


class PackagePrepareTest(unittest.TestCase):
    def test_dependency_preparation_is_idempotent_on_ubuntu_and_alpine(self):
        import tempfile
        for distribution in ('ubuntu', 'alpine'):
            with self.subTest(distribution=distribution), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                binary = root / 'bin'
                binary.mkdir()
                log = root / 'commands'
                ready = root / 'ready'
                python = binary / 'python3'
                python.write_text(f'#!/bin/sh\ntest -f "{ready}"\n')
                python.chmod(0o755)
                manager = binary / ('apt-get' if distribution == 'ubuntu' else 'apk')
                manager.write_text(f'#!/bin/sh\nprintf "%s\\n" "$*" >> "{log}"\n: > "{ready}"\n')
                manager.chmod(0o755)
                env = {**os.environ, 'PATH': str(binary)}
                script = COMPONENT_ROOT / 'host/prepare.sh'
                for _ in range(2):
                    subprocess.run(['/bin/sh', str(script)], env=env, check=True)
                commands = log.read_text().splitlines()
                self.assertEqual(len(commands), 1)
                self.assertIn('python3-pil' if distribution == 'ubuntu' else 'py3-pillow', commands[0])
                self.assertIn('numpy', commands[0])

if __name__ == "__main__":
    unittest.main()
