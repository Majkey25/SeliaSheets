from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path
from subprocess import CompletedProcess
from unittest.mock import Mock, patch


SCRIPT = Path(__file__).with_name("run_emulator_stylus_ci.py")
SPEC = importlib.util.spec_from_file_location("run_emulator_stylus_ci", SCRIPT)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Cannot load {SCRIPT}")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class CoordinatorTest(unittest.TestCase):
    def test_parses_marker_and_rejects_bad_coordinates(self) -> None:
        marker = MODULE.parse_marker(
            "I/SeliaSheetsStylusQA: READY_PRESSURE pen=100,200;120,210;140,220"
        )

        self.assertEqual(
            marker,
            MODULE.Marker("READY_PRESSURE", ((100, 200), (120, 210), (140, 220))),
        )
        with self.assertRaisesRegex(ValueError, "outside 140x220"):
            MODULE.validate_marker(marker, width=140, height=220)

    def test_pen_and_pinch_release_every_contact(self) -> None:
        pen = MODULE.pen_frames(((100, 200), (120, 210), (140, 220)))
        pinch = MODULE.pinch_frames(((80, 200), (160, 200), (40, 200), (200, 200)))

        self.assertEqual([frame[0].pressure for frame in pen], [160, 420, 900, 0])
        self.assertEqual([contact.pressure for contact in pinch[-1]], [0, 0])

    @patch.object(MODULE.subprocess, "run")
    def test_adb_commands_have_a_timeout(self, run: Mock) -> None:
        run.return_value = CompletedProcess([], 0, "", "")

        MODULE._run_adb("emulator-5554", "logcat", "-c")

        run.assert_called_once_with(
            ["adb", "-s", "emulator-5554", "logcat", "-c"],
            check=True,
            capture_output=False,
            text=True,
            timeout=10,
        )


if __name__ == "__main__":
    unittest.main()
