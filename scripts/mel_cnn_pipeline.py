#!/usr/bin/env python3
"""Shared defaults and helpers for mel-CNN training and export."""

from __future__ import annotations

from typing import Any, Dict, TYPE_CHECKING, Tuple

if TYPE_CHECKING:
    import numpy as np

DEFAULT_SAMPLE_RATE_HZ = 16_000

# Default STFT / mel parameters baked into the model graph.
DEFAULT_STFT_FRAME_LENGTH = 256   # 16 ms @ 16 kHz
DEFAULT_STFT_FRAME_STEP = 128     # 8 ms @ 16 kHz
DEFAULT_STFT_FFT_LENGTH = 256
DEFAULT_NUM_MEL_BINS = 40
DEFAULT_MEL_LOWER_HZ = 80.0
DEFAULT_MEL_UPPER_HZ = 7600.0

# Window / hop sizes in samples.
DEFAULT_WINDOW_SIZE_SAMPLES = 8_000   # 0.5 s @ 16 kHz
DEFAULT_HOP_SIZE_SAMPLES = DEFAULT_WINDOW_SIZE_SAMPLES // 2  # 0.25 s

# Floor for peak-norm divisor and log-RMS loudness feature.  Must match the Kotlin
# feature extractors (MelSpectrogramFeatureExtractor / AndroidMelSpectrogramFeatureExtractor).
LOUDNESS_EPSILON = 1e-6

STARTER_MODEL_VERSION = "0.5.0"


def compute_loudness_features(windows: "np.ndarray") -> "np.ndarray":
    """log(rms + eps) loudness feature for each raw waveform window, shape [N, 1].

    This is the exact value the on-device Kotlin code feeds into the CNN-only
    ``loudness`` input; standardization with (mean, std) happens inside the model graph.
    """
    import numpy as np

    rms = np.sqrt(np.mean(np.square(windows.astype(np.float32)), axis=1))
    return np.log(rms + LOUDNESS_EPSILON).astype(np.float32)[:, None]


def loudness_standardization_stats(windows: "np.ndarray") -> Tuple[float, float]:
    """Return (mean, std) of the log-RMS loudness feature over a window set."""
    import numpy as np

    features = compute_loudness_features(windows)
    mean = float(np.mean(features))
    std = float(np.std(features))
    return mean, (std if std > 1e-8 else 1.0)


def mel_spectrogram_metadata() -> Dict[str, int | float]:
    return {
        "stft_frame_length": DEFAULT_STFT_FRAME_LENGTH,
        "stft_frame_step": DEFAULT_STFT_FRAME_STEP,
        "fft_length": DEFAULT_STFT_FFT_LENGTH,
        "num_mel_bins": DEFAULT_NUM_MEL_BINS,
        "lower_edge_hertz": DEFAULT_MEL_LOWER_HZ,
        "upper_edge_hertz": DEFAULT_MEL_UPPER_HZ,
    }


def build_mel_cnn_model(
    num_classes: int,
    window_size: int,
    sample_rate: int,
    loudness_mean: float = 0.0,
    loudness_std: float = 1.0,
):
    """Build the waveform-input mel-CNN model used for training and export.

    Two-branch, loudness-invariant architecture:
      * Spectral branch: peak-normalize the window to 1.0 (0 dBFS) -> STFT -> mel ->
        log -> per-window min-max scale -> CNN.  Fully invariant to absolute loudness;
        only the *mixture of tones/frequencies* survives.
      * Loudness branch: log-RMS of the raw (un-normalized) window, standardized with
        fixed (mean, std), fed straight into the dense head as a depth proxy.

    The whole chain lives in-graph so the full waveform model stays single-input
    (waveform -> probs).  ``extract_cnn_only_model`` later splits it at the two tensors
    Kotlin computes on-device: ``scaled_spectrogram`` and ``loudness``.
    """
    import tensorflow as tf

    num_spectrogram_bins = DEFAULT_STFT_FFT_LENGTH // 2 + 1  # 129

    mel_weight_matrix = tf.signal.linear_to_mel_weight_matrix(
        num_mel_bins=DEFAULT_NUM_MEL_BINS,
        num_spectrogram_bins=num_spectrogram_bins,
        sample_rate=float(sample_rate),
        lower_edge_hertz=DEFAULT_MEL_LOWER_HZ,
        upper_edge_hertz=DEFAULT_MEL_UPPER_HZ,
    )

    loudness_std_safe = float(loudness_std) if abs(float(loudness_std)) > 1e-8 else 1.0

    waveform_input = tf.keras.Input(
        shape=(window_size,), dtype=tf.float32, name="waveform"
    )

    # ---- Spectral branch: peak-normalize, then log-mel, then per-window min-max ----
    peak_normalized = tf.keras.layers.Lambda(
        lambda samples: samples
        / (tf.reduce_max(tf.abs(samples), axis=-1, keepdims=True) + LOUDNESS_EPSILON),
        name="peak_norm",
    )(waveform_input)

    x = tf.keras.layers.Lambda(
        lambda samples: tf.abs(
            tf.signal.stft(
                samples,
                frame_length=DEFAULT_STFT_FRAME_LENGTH,
                frame_step=DEFAULT_STFT_FRAME_STEP,
                fft_length=DEFAULT_STFT_FFT_LENGTH,
            )
        ),
        name="stft_magnitude",
    )(peak_normalized)

    x = tf.keras.layers.Lambda(
        lambda magnitude: tf.tensordot(magnitude, mel_weight_matrix, axes=1),
        name="mel_projection",
    )(x)

    x = tf.keras.layers.Lambda(
        lambda mel: tf.math.log(mel + 1e-6),
        name="log_mel",
    )(x)

    x = tf.keras.layers.Lambda(
        lambda tensor: tf.expand_dims(tensor, axis=-1),
        name="add_channel",
    )(x)

    # Per-window min-max scaling to [0, 1] over the whole [time, mel, 1] matrix.
    scaled_spectrogram = tf.keras.layers.Lambda(
        lambda tensor: (tensor - tf.reduce_min(tensor, axis=[1, 2, 3], keepdims=True))
        / (
            tf.reduce_max(tensor, axis=[1, 2, 3], keepdims=True)
            - tf.reduce_min(tensor, axis=[1, 2, 3], keepdims=True)
            + 1e-6
        ),
        name="scaled_spectrogram",
    )(x)

    # ---- Loudness branch: log-RMS of the raw window (split point Kotlin feeds) ----
    loudness = tf.keras.layers.Lambda(
        lambda samples: tf.math.log(
            tf.sqrt(tf.reduce_mean(tf.square(samples), axis=-1, keepdims=True))
            + LOUDNESS_EPSILON
        ),
        name="loudness",
    )(waveform_input)

    outputs = _build_classifier_head(
        scaled_spectrogram, loudness, num_classes, loudness_mean, loudness_std_safe
    )

    model = tf.keras.Model(inputs=waveform_input, outputs=outputs)
    # Stash standardization constants so extract_cnn_only_model can rebuild the head.
    model.loudness_mean = float(loudness_mean)
    model.loudness_std = float(loudness_std_safe)
    model.compile(
        optimizer=tf.keras.optimizers.Adam(learning_rate=1e-3),
        loss="sparse_categorical_crossentropy",
        metrics=["accuracy"],
    )
    return model


def _build_classifier_head(spectrogram, loudness, num_classes: int, loudness_mean, loudness_std):
    """CNN classifier head shared by the full model and the CNN-only split: a 3-block CNN over
    the scaled spectrogram, concatenated with the standardized loudness scalar, then dense head.

    Defined once and called with FRESH layers in both build_mel_cnn_model and
    extract_cnn_only_model. Reusing the same layer *objects* across two models gives them
    multiple inbound nodes, which makes tf2onnx AND the TFLite converter trace the wrong graph
    (the Keras model is fine, but exported artifacts diverge). Building fresh + copying weights
    by name avoids that entirely.
    """
    import tensorflow as tf

    spectral = tf.keras.layers.Conv2D(32, (3, 3), activation="relu", padding="same", name="conv1")(spectrogram)
    spectral = tf.keras.layers.BatchNormalization(name="bn1")(spectral)
    spectral = tf.keras.layers.MaxPooling2D((2, 2), name="pool1")(spectral)

    spectral = tf.keras.layers.Conv2D(64, (3, 3), activation="relu", padding="same", name="conv2")(spectral)
    spectral = tf.keras.layers.BatchNormalization(name="bn2")(spectral)
    spectral = tf.keras.layers.MaxPooling2D((2, 2), name="pool2")(spectral)

    spectral = tf.keras.layers.Conv2D(64, (3, 3), activation="relu", padding="same", name="conv3")(spectral)
    spectral = tf.keras.layers.BatchNormalization(name="bn3")(spectral)
    spectral = tf.keras.layers.GlobalAveragePooling2D(name="gap")(spectral)

    loudness_standardized = tf.keras.layers.Lambda(
        lambda value: (value - loudness_mean) / loudness_std,
        name="loudness_standardize",
    )(loudness)

    combined = tf.keras.layers.Concatenate(name="concat")([spectral, loudness_standardized])

    head = tf.keras.layers.Dense(64, activation="relu", name="dense1")(combined)
    head = tf.keras.layers.Dropout(0.3, name="dropout")(head)
    return tf.keras.layers.Dense(num_classes, activation="softmax", name="class_probs")(head)


def train_and_convert_tflite(
    x_train: np.ndarray,
    y_train: np.ndarray,
    x_val: np.ndarray,
    y_val: np.ndarray,
    num_classes: int,
    window_size: int,
    sample_rate: int,
    epochs: int,
    batch_size: int,
    sample_weight_train: "np.ndarray | None" = None,
) -> Tuple[bytes, Dict[str, float], Any]:
    # Loudness standardization stats are derived from the (already gain-augmented)
    # training windows and baked into the graph so on-device code only computes log-RMS.
    loudness_mean, loudness_std = loudness_standardization_stats(x_train)

    model = build_mel_cnn_model(
        num_classes=num_classes,
        window_size=window_size,
        sample_rate=sample_rate,
        loudness_mean=loudness_mean,
        loudness_std=loudness_std,
    )

    model.summary()

    fit_kwargs: Dict[str, Any] = {}
    if sample_weight_train is not None:
        fit_kwargs["sample_weight"] = sample_weight_train

    history = model.fit(
        x_train,
        y_train,
        validation_data=(x_val, y_val),
        epochs=epochs,
        batch_size=batch_size,
        verbose=2,
        **fit_kwargs,
    )

    eval_loss, eval_accuracy = model.evaluate(x_val, y_val, verbose=0)

    tflite_bytes = convert_keras_model_to_tflite(
        model,
        optimizations=["default"],
    )

    metrics = {
        "loss": float(eval_loss),
        "accuracy": float(eval_accuracy),
        "train_loss": float(history.history["loss"][-1]),
        "train_accuracy": float(history.history["accuracy"][-1]),
        "val_loss": float(history.history["val_loss"][-1]),
        "val_accuracy": float(history.history["val_accuracy"][-1]),
        "loudness_mean": float(loudness_mean),
        "loudness_std": float(loudness_std),
    }
    return tflite_bytes, metrics, model


def convert_keras_model_to_tflite(
    model: Any,
    *,
    optimizations: list[Any] | None = None,
    representative_inputs: "np.ndarray | None" = None,
    supported_ops: list[Any] | None = None,
    inference_input_type: Any | None = None,
    inference_output_type: Any | None = None,
) -> bytes:
    import tensorflow as tf

    def build_concrete_function() -> Any:
        input_signature = [
            tf.TensorSpec(
                shape=tuple(dim if dim is not None else None for dim in model_input.shape),
                dtype=model_input.dtype,
                name=model_input.name.split(":")[0],
            )
            for model_input in model.inputs
        ]

        @tf.function(input_signature=input_signature)
        def serving_default(*inputs: Any) -> Any:
            # Multi-input Keras models must be called with a single list arg; passing them
            # positionally collides with the `training` kwarg (inputs, training, mask).
            if len(inputs) == 1:
                return model(inputs[0], training=False)
            return model(list(inputs), training=False)

        return serving_default.get_concrete_function()

    def configure_converter(converter: Any) -> None:
        if optimizations:
            resolved_optimizations = []
            for optimization in optimizations:
                if optimization == "default":
                    resolved_optimizations.append(tf.lite.Optimize.DEFAULT)
                else:
                    resolved_optimizations.append(optimization)
            converter.optimizations = resolved_optimizations

        if representative_inputs is not None:
            # Accept either a single array (one model input) or a list/tuple of arrays
            # (one per model input, e.g. [scaled_spectrogram, loudness]).
            if isinstance(representative_inputs, (list, tuple)):
                arrays = [arr.astype("float32", copy=False) for arr in representative_inputs]
            else:
                arrays = [representative_inputs.astype("float32", copy=False)]

            if len(arrays) != len(model.inputs):
                raise ValueError(
                    "Representative input count does not match model input count: "
                    f"{len(arrays)} != {len(model.inputs)}"
                )

            sample_counts = {array.shape[0] for array in arrays}
            if len(sample_counts) != 1:
                raise ValueError(
                    "Representative inputs must contain the same number of samples"
                )

            input_names = [
                model_input.name.split(":")[0] for model_input in model.inputs
            ]

            def representative_dataset():
                for index in range(arrays[0].shape[0]):
                    # TFLite may reorder multi-input graphs (for example alphabetically).
                    # Names preserve the model contract when input ranks differ.
                    yield {
                        input_name: array[index][None, ...]
                        for input_name, array in zip(input_names, arrays)
                    }

            converter.representative_dataset = representative_dataset

        if supported_ops:
            converter.target_spec.supported_ops = list(supported_ops)
        if inference_input_type is not None:
            converter.inference_input_type = inference_input_type
        if inference_output_type is not None:
            converter.inference_output_type = inference_output_type

    try:
        converter = tf.lite.TFLiteConverter.from_keras_model(model)
        configure_converter(converter)
        return converter.convert()
    except TypeError as error:
        # Keras 3 + some TensorFlow builds can fail in from_keras_model() with:
        # TypeError: 'NoneType' object is not callable (keras call context).
        # Fallback to a traced concrete function, which avoids that Keras call path
        # without going through SavedModel export.
        if "NoneType" not in str(error) or "callable" not in str(error):
            raise

        concrete_function = build_concrete_function()
        converter = tf.lite.TFLiteConverter.from_concrete_functions(
            [concrete_function],
            model,
        )
        configure_converter(converter)
        return converter.convert()


def run_tflite_predictions(
    model_bytes: bytes,
    inputs: "np.ndarray | list",
) -> "np.ndarray":
    """Run a TFLite model over a batch.

    ``inputs`` is either a single array (single-input model) or a list of arrays, one
    per model input.  Each array is matched to the interpreter input tensor with the
    same per-sample rank (e.g. the [61, 40, 1] spectrogram vs the [1] loudness scalar).
    """
    import numpy as np
    import tensorflow as tf

    interpreter = tf.lite.Interpreter(model_content=model_bytes)
    interpreter.allocate_tensors()

    input_arrays = list(inputs) if isinstance(inputs, (list, tuple)) else [inputs]
    input_details = interpreter.get_input_details()
    if len(input_arrays) != len(input_details):
        raise ValueError(
            f"Model expects {len(input_details)} inputs but got {len(input_arrays)}"
        )

    # Map each provided array to the input detail with matching per-sample rank.
    detail_for_array = []
    remaining = list(input_details)
    for array in input_arrays:
        sample_rank = array.ndim - 1
        match = next(
            (d for d in remaining if len(d["shape"]) - 1 == sample_rank), None
        )
        if match is None:
            raise ValueError(f"No TFLite input tensor matches array rank {sample_rank}")
        remaining.remove(match)
        detail_for_array.append(match)

    output_detail = interpreter.get_output_details()[0]
    output_classes = int(output_detail["shape"][-1])
    sample_count = input_arrays[0].shape[0]
    predictions = np.zeros((sample_count, output_classes), dtype=np.float32)
    output_scale, output_zero_point = output_detail.get("quantization", (0.0, 0))

    for index in range(sample_count):
        for array, detail in zip(input_arrays, detail_for_array):
            batched = array[index][None, ...].astype(np.float32, copy=False)
            model_input = batched
            if detail["dtype"] is not np.float32:
                input_scale, input_zero_point = detail.get("quantization", (0.0, 0))
                if not input_scale:
                    raise ValueError("Quantized TFLite input is missing quantization parameters")
                dtype_info = np.iinfo(detail["dtype"])
                quantized = np.round(batched / input_scale + input_zero_point)
                model_input = np.clip(quantized, dtype_info.min, dtype_info.max).astype(
                    detail["dtype"], copy=False
                )
            interpreter.set_tensor(detail["index"], model_input)

        interpreter.invoke()

        output = interpreter.get_tensor(output_detail["index"])
        if output_detail["dtype"] is not np.float32:
            if not output_scale:
                raise ValueError("Quantized TFLite output is missing quantization parameters")
            output = (output.astype(np.float32) - output_zero_point) * output_scale

        predictions[index] = output[0].astype(np.float32, copy=False)

    return predictions


def build_log_mel_feature_extractor(full_model):
    """waveform -> (scaled_spectrogram, loudness): the two tensors the CNN-only model
    consumes and that on-device Kotlin computes.  Used for parity checks and PTQ."""
    import tensorflow as tf

    return tf.keras.Model(
        inputs=full_model.input,
        outputs=[
            full_model.get_layer("scaled_spectrogram").output,
            full_model.get_layer("loudness").output,
        ],
        name="log_mel_feature_extractor",
    )


def extract_cnn_only_model(full_model):
    """Split the full waveform model into the two-input CNN-only model fed on-device:
    inputs (scaled_log_mel_spectrogram, loudness), output class_probs.
    Returns (model, spectrogram_input_shape).

    The head is rebuilt with FRESH layers (via _build_classifier_head) and weights are copied
    by name from the full model. Reusing the full model's layer objects gives them multiple
    inbound nodes, which makes both tf2onnx and the TFLite converter export a wrong graph even
    though the reused Keras model predicts correctly.
    """
    import tensorflow as tf

    spectrogram_shape = tuple(full_model.get_layer("scaled_spectrogram").output.shape[1:])
    num_classes = int(full_model.get_layer("class_probs").output.shape[-1])
    loudness_mean = getattr(full_model, "loudness_mean", 0.0)
    loudness_std = getattr(full_model, "loudness_std", 1.0)

    spectrogram_input = tf.keras.Input(
        shape=spectrogram_shape, dtype=tf.float32, name="scaled_log_mel_spectrogram",
    )
    loudness_input = tf.keras.Input(shape=(1,), dtype=tf.float32, name="loudness")

    outputs = _build_classifier_head(
        spectrogram_input, loudness_input, num_classes, loudness_mean, loudness_std
    )
    cnn_model = tf.keras.Model(
        inputs=[spectrogram_input, loudness_input], outputs=outputs, name="mel_cnn_classifier",
    )

    # Copy trained weights from the full model into the fresh head (matched by layer name).
    for layer in cnn_model.layers:
        weights = layer.get_weights()
        if weights:
            cnn_model.get_layer(layer.name).set_weights(full_model.get_layer(layer.name).get_weights())

    return cnn_model, spectrogram_shape
