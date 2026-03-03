"""
scripts/generate_synthetic_ambient.py

Generates synthetic "Hard Negative" audio files for metal detector training.
These files mimic environmental noise (EMI, Ground, Threshold) without
containing specific tonal patterns of valid targets.
"""

import os
import numpy as np
import argparse
from scipy.io import wavfile
from scipy import signal

def ensure_dir(path):
    if not os.path.exists(path):
        os.makedirs(path)

def save_wav(filename, data, sample_rate):
    # Normalize to -1.0 to 1.0
    max_val = np.max(np.abs(data))
    if max_val > 0:
        data = data / max_val
    
    # Scale to slightly below clipping for safety (0.9)
    data = data * 0.9
    
    # Convert to 16-bit PCM
    data_pcm = (data * 32767).astype(np.int16)
    wavfile.write(filename, sample_rate, data_pcm)
    print(f"Generated: {filename}")

def generate_pink_noise(length):
    """Approximates Pink Noise (1/f) using a filter."""
    b, a = signal.butter(1, 0.02, btype='low', analog=False)
    white = np.random.randn(length)
    pink = signal.lfilter(b, a, white)
    return pink

def generate_brown_noise(length):
    """Brownian noise (integrated white noise) for ground rumbles."""
    white = np.random.randn(length)
    brown = np.cumsum(white)
    # Remove DC offset drift
    brown = signal.detrend(brown)
    return brown

def generate_spikes(length, density=0.0005):
    """
    Random high-amplitude impulses.
    Spectrogram: Vertical lines (broadband).
    Target: Horizontal lines (tonal).
    """
    data = np.zeros(length)
    # Number of spikes
    num_spikes = int(length * density)
    indices = np.random.choice(length, num_spikes, replace=False)
    # Random amplitude +/-
    data[indices] = np.random.choice([-1.0, 1.0], num_spikes)
    return data

def generate_ground_moan(length, sample_rate):
    """
    Simulates mineralized soil: Low frequency heaving/groaning.
    """
    brown = generate_brown_noise(length)
    # Low pass filter at 60Hz to simulate heavy ground
    sos = signal.butter(4, 60, 'low', fs=sample_rate, output='sos')
    filtered = signal.sosfilt(sos, brown)
    return filtered

def generate_handling_noise(length, sample_rate):
    """
    Simulates cable bumps: Short, thumpy bursts.
    """
    data = np.zeros(length)
    num_bumps = np.random.randint(1, 5)
    
    for _ in range(num_bumps):
        start = np.random.randint(0, length - 4000)
        duration = np.random.randint(1000, 4000) # 0.1s to 0.25s
        
        # Create a low freq "thump"
        t = np.linspace(0, 1, duration)
        thump = np.sin(2 * np.pi * 50 * t) * np.exp(-5 * t) # 50Hz decaying sine
        
        data[start:start+duration] += thump
        
    return data

def main():
    parser = argparse.ArgumentParser(description="Generate synthetic ambient noise.")
    parser.add_argument("--output", type=str, default="assets/synthetic_ambient", help="Output directory")
    parser.add_argument("--count", type=int, default=5, help="Number of files per type")
    parser.add_argument("--duration", type=int, default=10, help="Duration in seconds")
    parser.add_argument("--rate", type=int, default=48000, help="Sample rate")
    args = parser.parse_args()

    ensure_dir(args.output)
    num_samples = args.duration * args.rate

    # 1. EMI Spikes (The "Clicky" Hard Negative)
    for i in range(args.count):
        # Mix silence with spikes
        data = generate_spikes(num_samples, density=0.0002) 
        save_wav(os.path.join(args.output, f"ambient_emi_spikes_{i}.wav"), data, args.rate)

    # 2. Ground Groan (The "Low Freq" Hard Negative)
    for i in range(args.count):
        data = generate_ground_moan(num_samples, args.rate)
        save_wav(os.path.join(args.output, f"ambient_ground_moan_{i}.wav"), data, args.rate)

    # 3. Threshold Hum (The "Constant" Hard Negative)
    for i in range(args.count):
        data = generate_pink_noise(num_samples)
        # Add slight modulation
        modulator = np.sin(np.linspace(0, 20*np.pi, num_samples)) * 0.1 + 0.9
        data = data * modulator
        save_wav(os.path.join(args.output, f"ambient_threshold_hum_{i}.wav"), data, args.rate)

    # 4. Handling Noise (The "Thump" Hard Negative)
    for i in range(args.count):
        data = generate_handling_noise(num_samples, args.rate)
        # Mix with slight floor
        data += generate_pink_noise(num_samples) * 0.05
        save_wav(os.path.join(args.output, f"ambient_handling_noise_{i}.wav"), data, args.rate)

    print(f"\nDone. Generated {args.count * 4} files in {args.output}")
    print("Add these to your training set with class 'AMBIENT' or 'JUNK'.")

if __name__ == "__main__":
    main()