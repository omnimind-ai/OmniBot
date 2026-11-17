"""Synthetic validity checks; no device, VM connection or real chat data."""
import json
from pathlib import Path
import tempfile
import unittest

import summarize
from common import PACKAGE


class SummaryValidationTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.workspace = Path(self.directory.name)
        self.save("runtime-perf.json", {"package": PACKAGE, "probeSha256": "probe", "baselineCommit": "commit"})
        for prefix in ("baseline", "after"):
            rows = []
            for scenario in ("short", "long", "tools"):
                for index, work in ((-1, 999999), (0, 100), (1, 200)):
                    rows.append({
                        "sample": index, "warmup": index < 0, "package": PACKAGE,
                        "measurement": "isolated-production-reducer", "scenario": scenario,
                        "stillResponding": False, "terminalStatus": "success",
                        "events": 2, "batchSize": 1, "workers": 1, "historyRows": 0,
                        "retainedEvents": 2, "semanticSha256": "a" * 64,
                        "reductionWorkUs": work, "wallUs": work + 50,
                    })
            self.save(f"{prefix}_clean-results.json", {
                "package": PACKAGE, "probeSha256": "probe", "baselineCommit": "commit",
                "mode": "profile", "complete": True, "samples": rows,
            })
            for kind in ("text", "tools"):
                self.save(f"{prefix}_stream_{kind}_1-results.json", self.stream_fixture(kind))

    def tearDown(self):
        self.directory.cleanup()

    def save(self, name, data):
        (self.workspace / name).write_text(json.dumps(data))

    def mutate(self, name, transform):
        path = self.workspace / name
        data = json.loads(path.read_text())
        transform(data)
        self.save(name, data)

    def stream_fixture(self, kind):
        owner = {
            "chatPageVisible": True, "routePath": "/home/chat", "runtimeMode": "agent",
            "conversationMode": "agent", "pageUsesOwningRuntime": True, "listUsesOwningRuntime": True,
            "conversationId": 42, "runtimeConversationId": 42,
            "agentId": "xiaowan-acp", "listAgentId": "xiaowan-acp", "listHidesToolCards": False,
            "runtimeMessageCount": 1, "pageMessageCount": 1, "listMessageCount": 1,
        }
        result = {
            "valid": True, "measurement": "production-coordinator-and-real-chat-page", "kind": kind,
            "finalMessageFound": True, "promptResponseHandled": True, "runtimeResponding": False,
            "cancelled": False, "fixturePageVisibleAtEnd": True,
            "firstVisibleOwner": owner, "finalVisibleOwner": owner,
            "emittedEvents": 2, "requestedEvents": 2, "workers": 1, "intervalMs": 10,
            "payloadChars": 128, "updateWorkUs": [3, 5], "updateTimesUs": [110, 310],
            "retainedEvents": 4 if kind == "tools" else None,
            "textMatchesFixture": True, "semanticSha256": "b" * 64,
            "startUs": 100, "projectionEndUs": 500, "wallUs": 420,
        }
        return {
            "stream": {"kind": kind, "fixtureRunning": False, "visibleProjectionVerified": True,
                       "runtimeResponding": False, "emittedEvents": 2, "result": result},
            "capture": {"package": PACKAGE, "visibleOwner": owner, "captureStartUs": 0,
                        "captureEndUs": 1000, "timerGapsUs": [10000, 11000, 20000],
                        "frames": [{"startUs": time, "buildUs": work, "rasterUs": work + 1,
                                    "totalUs": work + 2}
                                   for time, work in ((99, 99999), (100, 10), (300, 20), (500, 99999))]},
        }

    def summary(self, **kwargs):
        return summarize.summarize_workspace(self.workspace, stream_samples=1, **kwargs)

    def test_omits_warmups_and_filters_frames_by_half_open_stream_window(self):
        output = self.summary()
        short = output["isolated"]["before"]["scenarios"]["short"]
        self.assertEqual(150, short["reductionWorkUsMedian"])
        self.assertEqual(2, short["samples"])
        tools = output["streams"]["tools"]["before"]
        self.assertEqual(8, tools["perRunMedians"]["updateWorkUsSum"])
        self.assertEqual(2, tools["pooledFrames"]["count"])
        self.assertEqual({"count": 2, "p95": 10, "p99": 10, "max": 20}, tools["pooledFrames"]["buildUs"])
        self.assertEqual(20000, tools["pooledTimerWholeCaptureUs"]["max"])
        self.assertTrue(output["comparisonComplete"])

    def test_rejects_incomplete_isolated_suite(self):
        self.mutate("after_clean-results.json", lambda data: data.update(complete=False))
        with self.assertRaisesRegex(summarize.InvalidMeasurement, "incomplete"):
            self.summary()

    def test_rejects_false_visible_projection_even_when_valid_claims_true(self):
        self.mutate("after_stream_tools_1-results.json",
                    lambda data: data["stream"].update(visibleProjectionVerified=False))
        with self.assertRaisesRegex(summarize.InvalidMeasurement, "visible projection"):
            self.summary()

    def test_rejects_disconnected_page_runtime_sources(self):
        self.mutate("after_stream_tools_1-results.json",
                    lambda data: data["stream"]["result"]["finalVisibleOwner"].update(listUsesOwningRuntime=False))
        with self.assertRaisesRegex(summarize.InvalidMeasurement, "owning runtime"):
            self.summary()

    def test_rejects_missing_update_timings(self):
        self.mutate("after_stream_tools_1-results.json",
                    lambda data: data["stream"]["result"].update(updateWorkUs=[3]))
        with self.assertRaisesRegex(summarize.InvalidMeasurement, "every emitted event"):
            self.summary()

    def test_rejects_lost_tool_events(self):
        self.mutate("after_stream_tools_1-results.json",
                    lambda data: data["stream"]["result"].update(retainedEvents=3))
        with self.assertRaisesRegex(summarize.InvalidMeasurement, "events were lost"):
            self.summary()

    def test_rejects_semantically_different_before_after_ui_results(self):
        self.mutate("after_stream_text_1-results.json",
                    lambda data: data["stream"]["result"].update(semanticSha256="c" * 64))
        with self.assertRaisesRegex(summarize.InvalidMeasurement, "semanticSha256 mismatch"):
            self.summary()

    def test_before_only_allows_missing_after_and_does_not_claim_equivalence(self):
        for path in self.workspace.glob("after*.json"):
            path.unlink()
        output = self.summary(before_only=True)
        self.assertFalse(output["comparisonComplete"])
        self.assertIsNone(output["isolated"]["after"])
        self.assertIsNone(output["streams"]["text"]["equivalent"])


if __name__ == "__main__":
    unittest.main()
