"""Real UI regression for a two-step Settings search recording on PJE110.

Requires a genuine successful RunLog: click Settings search, input_text. Pass
--recorded-run-id; set OOB_GUI_SERIAL, OOB_ALLOW_PHYSICAL_DEVICE=1 and
OOB_GUI_OUTPUT. Start on Execution Center. Creates/deletes only its own named
fixture using the public canonical save_function tool. Authoring is explicit,
not evidence that automatic model authoring succeeded. No source coordinates
are executed by this driver; canonical run_function owns transfer and fallback.
"""

import argparse
import importlib.util
from pathlib import Path
from types import SimpleNamespace

spec = importlib.util.spec_from_file_location(
    "journey", Path(__file__).with_name("verify-execution-center-device.py")
)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def run(recorded_run_id):
    j = module.Journey(SimpleNamespace())
    fixture_id = "acceptance_settings_query_20260917"
    name = "Acceptance Settings query"
    outcomes = []

    def passed(case, **evidence):
        outcomes.append(dict(case=case, passed=True, **evidence))
        j.device.persist("arguments-controls.json", outcomes)

    try:
        source = j.device.run_log(recorded_run_id)
        assert source["status"] == "succeeded"
        assert [s["action"]["tool"] for s in source["steps"]] == ["click", "input_text"]
        steps = []
        for i, step in enumerate(source["steps"]):
            action = dict(step["action"])
            action["args"] = {
                k: v for k, v in action["args"].items() if k in ("x", "y", "text")
            }
            steps.append(
                dict(
                    step_index=i, source_state_id=step["before_state_id"], action=action
                )
            )
        function = dict(
            schema_version="omniflow.function.v2",
            function_id=fixture_id,
            name=name,
            description="Open Settings search and type the query. Do not modify settings.",
            input_schema={
                "type": "object",
                "properties": {"query": {"type": "string"}},
                "required": ["query"],
                "additionalProperties": False,
            },
            bindings=[
                {"source": "$.arguments.query", "target": "$.steps[1].action.args.text"}
            ],
            steps=steps,
            agent_visible=True,
        )
        assert j.device.tool(
            "save_function",
            run_id=recorded_run_id,
            function=function,
            arguments={"query": "battery"},
            enhance=False,
        )["success"]
        j.settings_home()
        j.center()
        j.tap("复用指令")
        j.tap("刷新")
        baseline = j.device.tool("list_run_logs", limit=1)["runs"][0]["run_id"]
        j.tap(name)
        j.tap("执行")
        j.tap("开始执行")
        j.device.wait(lambda: j.matching("请填写此参数"), bool)
        j.tap("取消")
        assert j.device.tool("list_run_logs", limit=1)["runs"][0]["run_id"] == baseline
        passed("required-input-and-cancel", no_run_created=True)
        j.tap(name)
        j.tap("执行")
        fields = j.device.wait(
            lambda: [
                n for n in j.nodes() if n.get("class") == "android.widget.EditText"
            ],
            bool,
        )
        assert len(fields) == 1
        j.tap_node(fields[0])
        j.device.adb("shell", "input", "text", "battery")
        j.tap("开始执行")
        result = j.device.wait(
            lambda: j.device.tool("list_run_logs", limit=1)["runs"][0],
            lambda r: r["run_id"] != baseline
            and r["status"] in ("succeeded", "failed", "cancelled"),
        )
        assert result["status"] == "succeeded", result.get("diagnostics")
        assert result["diagnostics"]["function_id"] == fixture_id
        assert any(
            n.get("resource-id") == "com.android.settings:id/search_src_text"
            and j.label(n) == "battery"
            for n in j.nodes()
        )
        passed(
            "bound-query-replay", run_id=result["run_id"], destination_text="battery"
        )
        # Every recorded pre-action state must remain visually inspectable,
        # including the initial XML-only planner observation before open_app.
        recorded = j.device.run_log(result["run_id"])
        for step in recorded["steps"]:
            state = j.device.tool("get_run_log_state", state_id=step["before_state_id"])
            image_path = state.get("screenshot_path")
            assert image_path, f"Missing screenshot: {step['before_state_id']}"
            j.device.adb("shell", "run-as", j.device.package, "test", "-s", image_path)
        passed("replay-screenshots-persisted", states=len(recorded["steps"]))
        j.center()
        j.tap("运行记录")
        j.tap(result["run_id"])
        j.device.wait(lambda: j.matching("对应复用指令"), bool)
        assert j.matching(name)
        j.tap("查看复用指令")
        j.device.wait(lambda: j.matching(fixture_id), bool)
        passed("canonical-function-link", function_id=fixture_id)
        j.tap("删除")
        j.device.wait(lambda: j.matching("删除复用指令"), bool)
        j.tap("删除")
        remaining = j.device.tool("list_functions")["functions"]
        assert all(f["function_id"] != fixture_id for f in remaining)
        passed("delete-test-fixture", function_id=fixture_id)
    except Exception as error:
        outcomes.append(dict(case="remaining-journey", passed=False, error=str(error)))
        j.device.persist("arguments-controls.json", outcomes)
        raise


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--recorded-run-id", required=True)
    run(parser.parse_args().recorded_run_id)
