"""
Static-analysis smoke tests for the PWA (Kotlin/Wasm + Compose) entry point.

These guard against the "blank white screen" class of bug that has hit production twice:
  1. Silent ONNX model-load exception → screen never rendered anything.
  2. Kotlin const val referenced inside a js() string literal → ReferenceError crashed the
     WASM module on init (blank white screen, mic permission dialog still appeared).

In Kotlin/Wasm, only local function *parameters* are bridged into the JS scope of a js()
block. File-scope `const val`s are NOT in scope and will be `undefined` at runtime, causing
uncaught JS exceptions that prevent the Compose viewport from mounting.
"""

import re
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
WASM_MAIN_ROOT = (
    REPO_ROOT
    / "webApp"
    / "src"
    / "wasmJsMain"
    / "kotlin"
    / "com"
    / "metaldetectoraudioapp"
    / "web"
)
MAIN_KT = WASM_MAIN_ROOT / "Main.kt"
MIC_DEVICES_KT = WASM_MAIN_ROOT / "audio" / "MicDevices.kt"
MIC_CAPTURE_KT = WASM_MAIN_ROOT / "audio" / "MicCapture.kt"
MIC_SELECTOR_KT = WASM_MAIN_ROOT / "ui" / "screen" / "MicSelector.kt"
PHOTO_CAPTURE_PROVIDER_KT = WASM_MAIN_ROOT / "platform" / "WebPhotoCaptureProvider.kt"
LOCATION_PROVIDER_KT = WASM_MAIN_ROOT / "platform" / "WebLocationProvider.kt"
RECORDING_VIEW_MODEL_KT = WASM_MAIN_ROOT / "viewmodel" / "WebRecordingViewModel.kt"
RECORDING_SCREEN_KT = WASM_MAIN_ROOT / "ui" / "screen" / "WebRecordingScreen.kt"
REVIEW_SCREEN_KT = WASM_MAIN_ROOT / "ui" / "screen" / "WebReviewScreen.kt"


def _wasm_kt_files() -> list[Path]:
    return sorted(WASM_MAIN_ROOT.rglob("*.kt"))


def _read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def _js_block_contents(source: str) -> list[str]:
    """Return the raw contents of every js("...") and js(\"\"\"...\"\"\") call."""
    triple = re.findall(r'js\s*\(\s*"""(.*?)"""\s*\)', source, re.DOTALL)
    single = re.findall(r'js\s*\(\s*"((?:[^"\\]|\\.)*?)"\s*\)', source)
    return triple + single


def _const_val_names(source: str) -> list[str]:
    """Return all names declared as `const val` (any visibility) in the file."""
    return re.findall(r'\bconst\s+val\s+(\w+)', source)


class WasmJsInteropSafetyTest(unittest.TestCase):
    """Guard against const-val-in-js() crashes that blank the screen on startup."""

    def test_no_const_val_names_inside_js_blocks(self):
        """
        Kotlin/Wasm: file-scope const vals referenced inside js() blocks are undefined
        at runtime and throw a ReferenceError that kills WASM init → blank white screen.
        Only local function parameters should appear as identifiers in js() bodies.
        """
        violations: list[str] = []
        for path in _wasm_kt_files():
            source = _read(path)
            const_names = _const_val_names(source)
            if not const_names:
                continue
            js_blocks = _js_block_contents(source)
            for name in const_names:
                pattern = re.compile(r'\b' + re.escape(name) + r'\b')
                for block in js_blocks:
                    if pattern.search(block):
                        violations.append(
                            f"{path.relative_to(REPO_ROOT)}: "
                            f"const val '{name}' referenced inside js() block"
                        )
        self.assertFalse(
            violations,
            "const vals used in js() blocks cause ReferenceError → blank white screen:\n"
            + "\n".join(violations),
        )


class PwaStartupRenderTest(unittest.TestCase):
    """Guard against silent failures that leave the screen blank."""

    def test_model_load_failure_is_surfaced_not_swallowed(self):
        """
        If WebInferenceControllerFactory.create() throws, the error must reach the UI as
        visible text (not silently drop, which blanks the screen).
        Regression: prior ONNX 'Ambient 0.0/0ms' silent-exception blank-screen bug.
        """
        source = _read(MAIN_KT)
        self.assertIn(
            "runCatching",
            source,
            "Model init must be wrapped in runCatching so exceptions don't escape silently.",
        )
        self.assertIn(
            "inferenceError",
            source,
            "An inferenceError state variable must exist to display the failure message.",
        )
        self.assertIn(
            "Loading model",
            source,
            "A 'Loading model' message must be shown while the model initialises "
            "(never a blank screen).",
        )

    def test_main_uses_compose_dark_theme_not_js_interop(self):
        """
        Regression: js() interop for theme detection (using const val ThemePreferenceStorageKey
        in a js() block) crashed WASM startup → blank white screen.
        The fix is to use Compose-native isSystemInDarkTheme() instead.
        """
        source = _read(MAIN_KT)
        self.assertIn(
            "isSystemInDarkTheme()",
            source,
            "Main.kt must use isSystemInDarkTheme() for theme detection, "
            "not js() interop (which risks blank-screen crashes).",
        )
        for forbidden in ("browserPrefersDarkTheme", "loadStoredThemePreference", "storeThemePreference"):
            self.assertNotIn(
                forbidden,
                source,
                f"'{forbidden}' is a known-bad js() interop helper that caused a blank "
                f"white screen in production — do not re-introduce it.",
            )


class PwaAudioDeviceFlowTest(unittest.TestCase):
    """Guard the mobile PWA permission and hot-plug refresh behavior."""

    def test_microphone_permission_has_one_get_user_media_call_site(self):
        source = _read(MIC_DEVICES_KT)
        self.assertEqual(
            2,
            source.count("navigator.mediaDevices.getUserMedia("),
            "Device enumeration should have exactly one coalesced permission request plus the "
            "explicit refresh stream used to reveal hot-plugged Android USB inputs.",
        )
        self.assertIn("MicrophonePermissionRequestState.REQUESTING", source)
        self.assertIn("useActiveStream", source)

    def test_refresh_uses_one_input_output_snapshot_and_visible_label(self):
        source = _read(MIC_SELECTOR_KT)
        self.assertIn("listAudioDevices(useActiveStream = activeStream)", source)
        self.assertIn("IconButton(", source)
        self.assertIn("Modifier.size(36.dp)", source)
        self.assertNotIn("listMicDevices()", source)
        self.assertNotIn("listOutputDevices()", source)

    def test_selected_microphone_capture_is_strictly_verified(self):
        source = _read(MIC_CAPTURE_KT)
        self.assertIn("first.deviceId = { exact: window.__micDeviceId }", source)
        self.assertIn("tracks[0].getSettings().deviceId", source)
        self.assertIn("!actualId || actualId !== requestedId", source)
        self.assertIn("stopStream(stream)", source)
        self.assertIn("onDeviceRejected(requestedId, actualId)", source)
        self.assertNotIn("selectAudioOutput", source)
        self.assertNotIn("attempt(baseConstraints(), true)", source)
        self.assertNotIn("onFellBackToDefault", source)

    def test_microphone_selector_shows_capture_diagnostics(self):
        selector_source = _read(MIC_SELECTOR_KT)
        state_source = _read(WASM_MAIN_ROOT / "audio" / "MicSelectionState.kt")
        self.assertIn("MicDiagnosticsPanel", selector_source)
        self.assertIn("requestedDeviceId", selector_source)
        self.assertIn("actualDeviceId", selector_source)
        self.assertIn("MicVerificationStatus.REJECTED", selector_source)
        self.assertIn("MicCaptureDiagnostics", state_source)


class PwaPhotoAndLocationCaptureTest(unittest.TestCase):
    """Guard optional photo/GPS capture and persistence wiring."""

    def test_photo_capture_normalizes_native_image_selection(self):
        source = _read(PHOTO_CAPTURE_PROVIDER_KT)
        self.assertIn("input.accept = 'image/*'", source)
        self.assertIn("input.capture = 'environment'", source)
        self.assertIn("var maximumLongEdge = 1600", source)
        self.assertIn("canvas.toBlob", source)
        self.assertIn("'image/jpeg', 0.9", source)

    def test_location_capture_is_explicit_high_accuracy_and_uncached(self):
        provider_source = _read(LOCATION_PROVIDER_KT)
        screen_source = _read(RECORDING_SCREEN_KT)
        self.assertIn("navigator.geolocation.getCurrentPosition", provider_source)
        self.assertIn("enableHighAccuracy: true", provider_source)
        self.assertIn("maximumAge: 0", provider_source)
        self.assertIn("timeout: 15000", provider_source)
        self.assertIn('"Use Current GPS"', screen_source)

    def test_recording_save_persists_optional_photo_and_gps(self):
        source = _read(RECORDING_VIEW_MODEL_KT)
        self.assertIn("gpsLatitude = draft.gpsLatitude", source)
        self.assertIn("gpsLongitude = draft.gpsLongitude", source)
        self.assertIn("imageBytes = pendingImage?.bytes", source)
        self.assertIn("imageExtension = pendingImage?.extension", source)
        self.assertNotIn("gpsLatitude = null,\n                        gpsLongitude = null", source)

    def test_review_displays_saved_photo_and_gps_metadata(self):
        source = _read(REVIEW_SCREEN_KT)
        self.assertIn('Photo: ${recording.imageFileName ?: "none"}', source)
        self.assertIn("recording.gpsLatitude", source)
        self.assertIn("recording.gpsLongitude", source)


if __name__ == "__main__":
    unittest.main()
