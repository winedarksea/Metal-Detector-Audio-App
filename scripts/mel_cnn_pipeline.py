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

# Energy gate: minimum RMS for a window to count as "active".
DEFAULT_RMS_GATE_THRESHOLD = 0.015

# Window / hop sizes in samples.
DEFAULT_WINDOW_SIZE_SAMPLES = 8_000   # 0.5 s @ 16 kHz
DEFAULT_HOP_SIZE_SAMPLES = DEFAULT_WINDOW_SIZE_SAMPLES // 2  # 0.25 s
STARTER_MODEL_VERSION = "0.4.0"


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
):
    """Build the waveform-input mel-CNN model used for training and export."""
    import tensorflow as tf

    num_spectrogram_bins = DEFAULT_STFT_FFT_LENGTH // 2 + 1  # 129

    mel_weight_matrix = tf.signal.linear_to_mel_weight_matrix(
        num_mel_bins=DEFAULT_NUM_MEL_BINS,
        num_spectrogram_bins=num_spectrogram_bins,
        sample_rate=float(sample_rate),
        lower_edge_hertz=DEFAULT_MEL_LOWER_HZ,
        upper_edge_hertz=DEFAULT_MEL_UPPER_HZ,
    )

    waveform_input = tf.keras.Input(
        shape=(window_size,), dtype=tf.float32, name="waveform"
    )

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
    )(waveform_input)

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

    x = tf.keras.layers.Conv2D(32, (3, 3), activation="relu", padding="same", name="conv1")(x)
    x = tf.keras.layers.BatchNormalization(name="bn1")(x)
    x = tf.keras.layers.MaxPooling2D((2, 2), name="pool1")(x)

    x = tf.keras.layers.Conv2D(64, (3, 3), activation="relu", padding="same", name="conv2")(x)
    x = tf.keras.layers.BatchNormalization(name="bn2")(x)
    x = tf.keras.layers.MaxPooling2D((2, 2), name="pool2")(x)

    x = tf.keras.layers.Conv2D(64, (3, 3), activation="relu", padding="same", name="conv3")(x)
    x = tf.keras.layers.BatchNormalization(name="bn3")(x)
    x = tf.keras.layers.GlobalAveragePooling2D(name="gap")(x)

    x = tf.keras.layers.Dense(64, activation="relu", name="dense1")(x)
    x = tf.keras.layers.Dropout(0.3, name="dropout")(x)
    outputs = tf.keras.layers.Dense(
        num_classes,
        activation="softmax",
        name="class_probs",
    )(x)

    model = tf.keras.Model(inputs=waveform_input, outputs=outputs)
    model.compile(
        optimizer=tf.keras.optimizers.Adam(learning_rate=1e-3),
        loss="sparse_categorical_crossentropy",
        metrics=["accuracy"],
    )
    return model


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
) -> Tuple[bytes, Dict[str, float], Any]:
    model = build_mel_cnn_model(
        num_classes=num_classes,
        window_size=window_size,
        sample_rate=sample_rate,
    )

    model.summary()

    history = model.fit(
        x_train,
        y_train,
        validation_data=(x_val, y_val),
        epochs=epochs,
        batch_size=batch_size,
        verbose=2,
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
            return model(*inputs, training=False)

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
            representative_inputs_float32 = representative_inputs.astype("float32", copy=False)

            def representative_dataset():
                for sample in representative_inputs_float32:
                    yield [sample[None, ...]]

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
    inputs: "np.ndarray",
) -> "np.ndarray":
    import numpy as np
    import tensorflow as tf

    interpreter = tf.lite.Interpreter(model_content=model_bytes)
    interpreter.allocate_tensors()

    input_detail = interpreter.get_input_details()[0]
    output_detail = interpreter.get_output_details()[0]
    output_classes = int(output_detail["shape"][-1])
    predictions = np.zeros((inputs.shape[0], output_classes), dtype=np.float32)

    input_scale, input_zero_point = input_detail.get("quantization", (0.0, 0))
    output_scale, output_zero_point = output_detail.get("quantization", (0.0, 0))

    for index, sample in enumerate(inputs):
        batched_sample = sample[None, ...].astype(np.float32, copy=False)
        model_input = batched_sample

        if input_detail["dtype"] is not np.float32:
            if not input_scale:
                raise ValueError("Quantized TFLite input is missing quantization parameters")
            dtype_info = np.iinfo(input_detail["dtype"])
            quantized = np.round(batched_sample / input_scale + input_zero_point)
            model_input = np.clip(quantized, dtype_info.min, dtype_info.max).astype(
                input_detail["dtype"],
                copy=False,
            )

        interpreter.set_tensor(input_detail["index"], model_input)
        interpreter.invoke()

        output = interpreter.get_tensor(output_detail["index"])
        if output_detail["dtype"] is not np.float32:
            if not output_scale:
                raise ValueError("Quantized TFLite output is missing quantization parameters")
            output = (output.astype(np.float32) - output_zero_point) * output_scale

        predictions[index] = output[0].astype(np.float32, copy=False)

    return predictions


def build_log_mel_feature_extractor(full_model):
    import tensorflow as tf

    return tf.keras.Model(
        inputs=full_model.input,
        outputs=full_model.get_layer("add_channel").output,
        name="log_mel_feature_extractor",
    )


def extract_cnn_only_model(full_model):
    import tensorflow as tf

    add_channel_layer = full_model.get_layer("add_channel")
    add_channel_output_shape = tuple(add_channel_layer.output.shape[1:])

    mel_input = tf.keras.Input(
        shape=add_channel_output_shape,
        dtype=tf.float32,
        name="log_mel_spectrogram",
    )

    cnn_x = mel_input
    for layer_name in (
        "conv1",
        "bn1",
        "pool1",
        "conv2",
        "bn2",
        "pool2",
        "conv3",
        "bn3",
        "gap",
        "dense1",
        "class_probs",
    ):
        cnn_x = full_model.get_layer(layer_name)(cnn_x)

    return (
        tf.keras.Model(inputs=mel_input, outputs=cnn_x, name="mel_cnn_classifier"),
        add_channel_output_shape,
    )