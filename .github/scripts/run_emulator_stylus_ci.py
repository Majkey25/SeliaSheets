from __future__ import annotations

import importlib
import importlib.resources
import os
import re
import subprocess
import sys
import tempfile
import time
from collections.abc import Callable, Iterable
from contextlib import AbstractContextManager
from pathlib import Path
from typing import NamedTuple, Protocol, cast


TAG = "SeliaSheetsStylusQA"
EXPECTED_MARKERS = {"READY_PRESSURE", "READY_PINCH", "READY_AFTER_PINCH"}
MARKER_RE = re.compile(
    r"(READY_PRESSURE|READY_PINCH|READY_AFTER_PINCH) (pen|touch)=([^\s]+)$"
)
TEST_CLASS = "com.majkeylab.seliadocs.editor.PageViewportFlowTest"
TEST_METHODS = (
    "externalTabletStylusPreservesPressureAtZoom",
    "externalTabletStylusDrawsAfterLivePinch",
)


class Marker(NamedTuple):
    name: str
    points: tuple[tuple[int, int], ...]


class Contact(NamedTuple):
    x: int
    y: int
    identifier: int
    pressure: int


class GrpcEndpoint(NamedTuple):
    port: int
    token: str


class ReadyFuture(Protocol):
    def result(self, timeout: float) -> object: ...


class GrpcApi(Protocol):
    def insecure_channel(self, target: str) -> AbstractContextManager[object]: ...

    def channel_ready_future(self, channel: object) -> ReadyFuture: ...


class MessageApi(Protocol):
    Touch: Callable[..., object]
    TouchEvent: Callable[..., object]
    Pen: Callable[..., object]
    PenEvent: Callable[..., object]
    InputEvent: Callable[..., object]


class EmulatorControllerStub(Protocol):
    def streamInputEvent(
        self,
        request_iterator: Iterable[object],
        *,
        timeout: float,
        metadata: tuple[tuple[str, str], ...],
    ) -> object: ...


class ServiceApi(Protocol):
    EmulatorControllerStub: Callable[[object], EmulatorControllerStub]


class ProtocApi(Protocol):
    def main(self, arguments: list[str]) -> int: ...


def parse_marker(line: str) -> Marker | None:
    if TAG not in line:
        return None
    match = MARKER_RE.search(line.strip())
    if match is None:
        if "READY_" in line:
            raise ValueError("Malformed stylus marker")
        return None
    name, field, payload = match.groups()
    expected_field = "touch" if name == "READY_PINCH" else "pen"
    expected_count = 4 if name == "READY_PINCH" else 3
    points: list[tuple[int, int]] = []
    for point in payload.split(";"):
        coordinates = point.split(",")
        if len(coordinates) != 2:
            raise ValueError("Malformed stylus marker coordinates")
        try:
            points.append((int(coordinates[0]), int(coordinates[1])))
        except ValueError as error:
            raise ValueError("Malformed stylus marker coordinates") from error
    if field != expected_field or len(points) != expected_count:
        raise ValueError("Malformed stylus marker")
    return Marker(name, tuple(points))


def validate_marker(marker: Marker, width: int, height: int) -> None:
    if width <= 0 or height <= 0:
        raise ValueError(f"Invalid display size {width}x{height}")
    if any(not (0 <= x < width and 0 <= y < height) for x, y in marker.points):
        raise ValueError(f"Stylus marker coordinate outside {width}x{height}")


def pen_frames(points: tuple[tuple[int, int], ...]) -> tuple[tuple[Contact, ...], ...]:
    if len(points) != 3:
        raise ValueError("Pen marker requires three points")
    contacts = tuple(
        Contact(x, y, 1, pressure) for (x, y), pressure in zip(points, (160, 420, 900))
    )
    return tuple((contact,) for contact in contacts) + ((Contact(*points[-1], 1, 0),),)


def pinch_frames(
    points: tuple[tuple[int, int], ...],
) -> tuple[tuple[Contact, ...], ...]:
    if len(points) != 4:
        raise ValueError("Pinch marker requires four points")
    left_start, right_start, left_end, right_end = points
    left_middle = (
        (left_start[0] + left_end[0]) // 2,
        (left_start[1] + left_end[1]) // 2,
    )
    right_middle = (
        (right_start[0] + right_end[0]) // 2,
        (right_start[1] + right_end[1]) // 2,
    )

    def contacts(
        left: tuple[int, int], right: tuple[int, int], pressure: int
    ) -> tuple[Contact, ...]:
        return Contact(*left, 10, pressure), Contact(*right, 11, pressure)

    return (
        contacts(left_start, right_start, 512),
        contacts(left_middle, right_middle, 512),
        contacts(left_end, right_end, 512),
        contacts(left_end, right_end, 0),
    )


def _read_properties(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if "=" in line:
            key, value = line.split("=", 1)
            values[key] = value
    return values


def find_grpc_endpoint(registration_dir: Path, serial_port: int) -> GrpcEndpoint:
    matches: list[tuple[int, dict[str, str]]] = []
    for path in registration_dir.glob("pid_*.ini"):
        values = _read_properties(path)
        if values.get("port.serial") == str(serial_port):
            matches.append((path.stat().st_mtime_ns, values))
    if not matches:
        raise RuntimeError(
            f"No emulator gRPC registration found for port {serial_port}"
        )
    values = max(matches, key=lambda match: match[0])[1]
    token = values.get("grpc.token", "")
    if not token:
        raise RuntimeError("Emulator gRPC token is missing")
    try:
        port = int(values["grpc.port"])
    except (KeyError, ValueError) as error:
        raise RuntimeError("Emulator gRPC port is missing or invalid") from error
    if not 1024 <= port <= 65535:
        raise RuntimeError("Emulator gRPC port is outside the allowed range")
    return GrpcEndpoint(port, token)


def _registration_dir() -> Path:
    runtime_dir = os.environ.get("XDG_RUNTIME_DIR")
    if runtime_dir:
        return Path(runtime_dir) / "avd/running"
    return Path("/run/user") / str(os.getuid()) / "avd/running"


def _emulator_serial() -> tuple[str, int]:
    serial = os.environ.get("ANDROID_SERIAL", "")
    match = re.fullmatch(r"emulator-(\d+)", serial)
    if match is None:
        port = os.environ.get("EMULATOR_PORT", "")
        if not port.isdigit():
            raise RuntimeError(
                "ANDROID_SERIAL or EMULATOR_PORT must identify the CI emulator"
            )
        serial = f"emulator-{port}"
        match = re.fullmatch(r"emulator-(\d+)", serial)
    if match is None:
        raise RuntimeError("Invalid emulator serial")
    return serial, int(match.group(1))


def _run_adb(
    serial: str,
    *arguments: str,
    capture_output: bool = False,
) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["adb", "-s", serial, *arguments],
        check=True,
        capture_output=capture_output,
        text=True,
        timeout=10,
    )


def _display_size(serial: str) -> tuple[int, int]:
    result = _run_adb(serial, "shell", "wm", "size", capture_output=True)
    sizes = re.findall(r"(?:Physical|Override) size:\s*(\d+)x(\d+)", result.stdout)
    if not sizes:
        raise RuntimeError("Unable to read emulator display size")
    width, height = sizes[-1]
    return int(width), int(height)


def _load_grpc_api() -> tuple[GrpcApi, MessageApi, ServiceApi]:
    sdk_root = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk_root:
        raise RuntimeError("ANDROID_HOME or ANDROID_SDK_ROOT is required")
    proto = Path(sdk_root) / "emulator/lib/emulator_controller.proto"
    if not proto.is_file():
        raise RuntimeError(f"Emulator controller protocol not found at {proto}")

    grpc = cast(GrpcApi, importlib.import_module("grpc"))
    protoc = cast(ProtocApi, importlib.import_module("grpc_tools.protoc"))
    grpc_include = importlib.resources.files("grpc_tools") / "_proto"
    with tempfile.TemporaryDirectory(prefix="seliasheets-grpc-") as generated:
        result = protoc.main(
            [
                "grpc_tools.protoc",
                f"--proto_path={proto.parent}",
                f"--proto_path={grpc_include}",
                f"--python_out={generated}",
                f"--grpc_python_out={generated}",
                str(proto),
            ],
        )
        if result != 0:
            raise RuntimeError(f"grpc_tools.protoc exited with {result}")
        sys.path.insert(0, generated)
        try:
            messages = cast(
                MessageApi, importlib.import_module("emulator_controller_pb2")
            )
            services = cast(
                ServiceApi, importlib.import_module("emulator_controller_pb2_grpc")
            )
        finally:
            sys.path.remove(generated)
    return grpc, messages, services


def _wait_for_endpoint(serial_port: int, timeout_seconds: float = 15) -> GrpcEndpoint:
    deadline = time.monotonic() + timeout_seconds
    last_error: RuntimeError | None = None
    while time.monotonic() < deadline:
        try:
            return find_grpc_endpoint(_registration_dir(), serial_port)
        except RuntimeError as error:
            last_error = error
            time.sleep(0.25)
    raise RuntimeError(
        "Timed out waiting for emulator gRPC registration"
    ) from last_error


def _input_events(messages: MessageApi, marker: Marker) -> Iterable[object]:
    frames = (
        pinch_frames(marker.points)
        if marker.name == "READY_PINCH"
        else pen_frames(marker.points)
    )
    for frame in frames:
        if marker.name == "READY_PINCH":
            touches = [
                messages.Touch(
                    x=item.x,
                    y=item.y,
                    identifier=item.identifier,
                    pressure=item.pressure,
                )
                for item in frame
            ]
            yield messages.InputEvent(
                touch_event=messages.TouchEvent(display=0, touches=touches)
            )
        else:
            pens = [
                messages.Pen(
                    location=messages.Touch(
                        x=item.x,
                        y=item.y,
                        identifier=item.identifier,
                        pressure=item.pressure,
                        orientation=0,
                    ),
                    button_pressed=False,
                    rubber_pointer=False,
                )
                for item in frame
            ]
            yield messages.InputEvent(
                pen_event=messages.PenEvent(display=0, events=pens)
            )
        time.sleep(0.12)


def _inject(
    stub: EmulatorControllerStub, messages: MessageApi, marker: Marker, token: str
) -> None:
    stub.streamInputEvent(
        _input_events(messages, marker),
        timeout=10,
        metadata=(("authorization", f"Bearer {token}"),),
    )


def _gradle_command(repo: Path) -> list[str]:
    selector = ",".join(f"{TEST_CLASS}#{method}" for method in TEST_METHODS)
    return [
        str(repo / "gradlew"),
        "connectedDebugAndroidTest",
        "--console=plain",
        f"-Pandroid.testInstrumentationRunnerArguments.class={selector}",
        "-Pandroid.testInstrumentationRunnerArguments.externalTabletStylus=true",
    ]


def _logcat(serial: str) -> str:
    result = _run_adb(
        serial,
        "logcat",
        "-d",
        "-v",
        "brief",
        f"{TAG}:I",
        "*:S",
        capture_output=True,
    )
    return result.stdout


def run() -> int:
    repo = Path(__file__).resolve().parents[2]
    serial, serial_port = _emulator_serial()
    width, height = _display_size(serial)
    endpoint = _wait_for_endpoint(serial_port)
    grpc, messages, services = _load_grpc_api()
    _run_adb(serial, "logcat", "-c")

    process = subprocess.Popen(_gradle_command(repo), cwd=repo)
    seen: set[str] = set()
    deadline = time.monotonic() + 900
    try:
        with grpc.insecure_channel(f"127.0.0.1:{endpoint.port}") as channel:
            grpc.channel_ready_future(channel).result(timeout=10)
            stub = services.EmulatorControllerStub(channel)
            while seen != EXPECTED_MARKERS:
                exit_code = process.poll()
                if exit_code is not None:
                    raise RuntimeError(
                        f"Instrumentation exited with {exit_code} before all stylus markers: "
                        f"{sorted(EXPECTED_MARKERS - seen)}",
                    )
                if time.monotonic() >= deadline:
                    raise RuntimeError(
                        f"Timed out waiting for stylus markers: {sorted(EXPECTED_MARKERS - seen)}"
                    )
                for line in _logcat(serial).splitlines():
                    marker = parse_marker(line)
                    if marker is None or marker.name in seen:
                        continue
                    validate_marker(marker, width, height)
                    _inject(stub, messages, marker, endpoint.token)
                    seen.add(marker.name)
                time.sleep(0.25)
        return process.wait(timeout=180)
    finally:
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=10)


if __name__ == "__main__":
    try:
        raise SystemExit(run())
    except Exception as error:
        print(f"Stylus CI failed: {error}", file=sys.stderr)
        raise SystemExit(1) from error
