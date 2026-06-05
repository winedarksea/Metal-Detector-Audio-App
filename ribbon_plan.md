# Design for New Audio Ribbon Visual
Problem: the current waveform for audio shown on the detection screen, while useful (and in need of auto scaling for the y-axis) does not do much to help tell us what we are seeing. It would be nice to supplement this with a visual that gives some indication of ongoing detections, separately from the ML classifier.
Goal: help show good targets from junk in an audio visualization that is agnostic of the metal detector brand.
Goal: make it clear a good potential target is in the mix, even if there is junk nearby
Layout: it would be good to have RMS Level, the new visual, then Current Prediction all together, removing the connection check label (this just becomes a live view of audio and classifier at once). Then beneath these are the various settings and performance stats. This new visual should be able to take up a fair amount of the screen (it and the current model prediction are the two things users mostly look at, and they can scroll down for settings), ideally in such a way that this scales to larger and smaller screens as appropriate.
Constraints: needs to be efficiently computed to run in real time

Streaming Multi-Ribbon Tone-Quality View
Core display: Draw a scrolling set of up to 2–3 simultaneous ribbons. Each ribbon represents a local spectral peak in the detector audio, not the global spectral centroid.
X-axis is time and Y-axis should be a detector-relative tone axis derived from the app’s observed audio distribution.
Frequency range: configurable detector-audio band, default approximately 100–4000 Hz
The ribbon highlights clean, strong, coherent tone components in the detector audio stream and answers questions like: is there a distinct tone component above its local background?

A clean response appears as:
bright, sharp, continuous ribbon

A messy or junky response appears as:
dim, fuzzy, broken ribbon or cloud

A mixed target response can show both at once:
sharp high-tone ribbon + fuzzy low/mid haze

Hue = relative pitch zone
Brightness/glow = quality
Sharpness = tonality
Thickness = SNR/presence

low pitch  → warm hue
mid pitch  → amber/neutral
high pitch → cool/green/cyan hue
Note: we do not try to associate these with specific metals, as calibrating this across brands and profiles is too difficult.

bright/glowing → clean tone component
dim/fuzzy      → unstable/noisy component

Here is a proposed design:

1. Compute log-Mel spectrum, e.g. 40 bins.
2. Estimate rolling per-band noise floor.
3. Normalize each bin by local noise floor.
4. Smooth lightly across frequency.
5. Find local spectral peaks.
6. Keep top K peaks, usually K = 2 or 3.
7. Compute tone-quality metrics for each peak.
8. Link peaks only over a short 100–300 ms window for visual continuity.

For each peak k, calculate state of the art tone quality metrics:
peak_snr_db
local_prominence_db
local_contrast
local_crest (spectral crest factor)
local_flatness or local_entropy
short_window_continuity
short_window_jitter
local_flux

Then:
tone_quality_k =
    0.30 * local_contrast
  + 0.20 * local_crest
  + 0.20 * (1 - local_flatness or entropy)
  + 0.15 * short_window_continuity
  - penalty(jitter, excessive_flux)

clean high component → bright sharp high ribbon
messy low component  → fuzzy low cloud
mixed response       → multiple ribbons visible at once
weak whisper         → thin but visible if SNR is good
constant hum         → suppressed by noise-floor normalization

Style elements to consider (not requirements here, just ideas):
Additive Blending (blendmode.screen or blendmode.plus)
Double-stroke Glow Hack (draw Path twice, a halo with width stroke and core with a thin stroke)
Bezier smoothing
Perceptually uniform color gradients, Low pitch (Bin 0-13): #FF3366 (Neon Pink/Red) → #FF9933 (Orange), mid pitch (Bin 14-26): #FFD54F (Amber) → #4DD0E1 (Light Blue), high pitch (Bin 27-39): #00E676 (Cyan/Teal)
Visualizing "Junk" (The Fuzzy Cloud): Map the calculated "Quality" metric inversely to the strokeWidth and directly to alpha.: High Quality (Good target): Alpha = 1.0, Core width = 2dp, Halo width = 8dp. (Looks like a laser beam) and Low Quality (Junk/Iron): Alpha = 0.3, Core width = 0dp, Halo width = 30dp. (Looks like a diffuse, broken haze)

Performance hints:
Do not recompute an FFT. The visualizer should subscribe to this exact same FloatArray output of the existing spectrograms
Zero-Allocation Ring Buffers: Avoid creating new List or DataClass objects per frame. Back the visualizer with pre-allocated 1D or 2D FloatArray ring buffers (e.g., val history = FloatArray(HISTORY_FRAMES * MAX_PEAKS * ATTRS_PER_PEAK)) and update an offset index
