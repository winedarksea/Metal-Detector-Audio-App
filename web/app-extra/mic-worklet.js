/**
 * AudioWorklet processor for capturing microphone PCM in the audio thread.
 * Runs in a separate worklet context; sends Float32 chunks to the main thread.
 * This gives lower latency and avoids ScriptProcessorNode deprecation.
 */
class MicProcessor extends AudioWorkletProcessor {
  constructor() {
    super();
    this._buffer = [];
    this._chunkSize = 4096;
  }

  process(inputs) {
    const input = inputs[0];
    if (!input || !input[0]) return true;
    const channel = input[0];
    for (let i = 0; i < channel.length; i++) {
      this._buffer.push(channel[i]);
    }
    if (this._buffer.length >= this._chunkSize) {
      this.port.postMessage(new Float32Array(this._buffer.splice(0, this._chunkSize)));
    }
    return true;
  }
}

registerProcessor('mic-processor', MicProcessor);
