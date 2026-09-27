from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path
from subprocess import CompletedProcess
from unittest.mock import Mock, call, patch


SCRIPT = Path(__file__).with_name("run_emulator_stylus_ci.py")
SPEC = importlib.util.spec_from_file_location("run_emulator_stylus_ci", SCRIPT)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Cannot load {SCRIPT}")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class CoordinatorTest(unittest.TestCase):
    @patch.object(MODULE, "_run_adb")
    def test_recovers_only_the_focused_boot_launcher_anr(self, adb: Mock) -> None:
        adb.return_value = CompletedProcess(
            [],
            0,
            "  mCurrentFocus=Window{1f78774 u0 Application Not Responding: com.google.android.apps.nexuslauncher}\n",
            "",
        )

        self.assertTrue(MODULE._recover_boot_launcher_anr("emulator-5554"))

        self.assertEqual(
            adb.call_args_list,
            [
                call(
                    "emulator-5554", "shell", "dumpsys", "window", capture_output=True
                ),
                call(
                    "emulator-5554",
                    "shell",
                    "am",
                    "force-stop",
                    "com.google.android.apps.nexuslauncher",
                ),
            ],
        )

    @patch.object(MODULE, "_run_adb")
    def test_does_not_hide_app_failures_or_unrelated_windows(self, adb: Mock) -> None:
        for focused in (
            "mCurrentFocus=Window{1 u0 Application Not Responding: com.majkeylab.seliadocs.debug}",
            "mCurrentFocus=Window{1 u0 Application Not Responding: com.google.android.apps.nexuslauncher.other}",
            "mCurrentFocus=Window{1 u0 com.google.android.apps.nexuslauncher/.NexusLauncherActivity}",
            "Window #3: Application Not Responding: com.google.android.apps.nexuslauncher",
        ):
            with self.subTest(focused=focused):
                adb.reset_mock()
                adb.return_value = CompletedProcess([], 0, focused, "")
                self.assertFalse(MODULE._recover_boot_launcher_anr("emulator-5554"))
                adb.assert_called_once_with(
                    "emulator-5554", "shell", "dumpsys", "window", capture_output=True
                )

    @patch.object(MODULE, "_run_adb")
    def test_never_stops_a_physical_device_launcher(self, adb: Mock) -> None:
        with self.assertRaisesRegex(ValueError, "restricted"):
            MODULE._recover_boot_launcher_anr("BQLDU19927002646")
        adb.assert_not_called()

    @patch.object(MODULE, "_run_adb")
    def test_launcher_recovery_keeps_adb_errors_visible(self, adb: Mock) -> None:
        adb.side_effect = TimeoutError("device unavailable")
        with self.assertRaisesRegex(TimeoutError, "device unavailable"):
            MODULE._recover_boot_launcher_anr("emulator-5554")

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
