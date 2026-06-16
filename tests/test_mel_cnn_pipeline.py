import unittest

import numpy as np

from scripts.mel_cnn_pipeline import build_mel_cnn_model, convert_keras_model_to_tflite


class TfliteConversionTest(unittest.TestCase):
    def test_spectral_blocks_normalize_before_relu(self):
        try:
            import tensorflow as tf
        except ModuleNotFoundError:
            self.skipTest("tensorflow is not installed")

        model = build_mel_cnn_model(
            num_classes=3,
            window_size=512,
            sample_rate=16_000,
        )

        self.assertIsInstance(model.get_layer("conv1"), tf.keras.layers.Conv2D)
        self.assertIsInstance(model.get_layer("bn1"), tf.keras.layers.BatchNormalization)
        self.assertIsInstance(model.get_layer("relu1"), tf.keras.layers.Activation)
        self.assertEqual("linear", model.get_layer("conv1").activation.__name__)
        self.assertLess(
            model.layers.index(model.get_layer("conv1")),
            model.layers.index(model.get_layer("bn1")),
        )
        self.assertLess(
            model.layers.index(model.get_layer("bn1")),
            model.layers.index(model.get_layer("relu1")),
        )

    def test_multi_input_representative_samples_are_matched_by_name(self):
        try:
            import tensorflow as tf
        except ModuleNotFoundError:
            self.skipTest("tensorflow is not installed")

        spectrogram_input = tf.keras.Input(
            shape=(4, 3, 1),
            name="scaled_log_mel_spectrogram",
        )
        loudness_input = tf.keras.Input(shape=(1,), name="loudness")
        spectral_features = tf.keras.layers.Conv2D(2, 1)(spectrogram_input)
        spectral_features = tf.keras.layers.GlobalAveragePooling2D()(
            spectral_features
        )
        combined_features = tf.keras.layers.Concatenate()(
            [spectral_features, loudness_input]
        )
        outputs = tf.keras.layers.Dense(2)(combined_features)
        model = tf.keras.Model(
            inputs=[spectrogram_input, loudness_input],
            outputs=outputs,
        )

        model_bytes = convert_keras_model_to_tflite(
            model,
            optimizations=["default"],
            representative_inputs=[
                np.zeros((3, 4, 3, 1), dtype=np.float32),
                np.zeros((3, 1), dtype=np.float32),
            ],
            supported_ops=[tf.lite.OpsSet.TFLITE_BUILTINS_INT8],
            inference_input_type=tf.int8,
            inference_output_type=tf.int8,
        )

        interpreter = tf.lite.Interpreter(model_content=model_bytes)
        interpreter.allocate_tensors()
        input_shapes = {
            tuple(input_detail["shape"])
            for input_detail in interpreter.get_input_details()
        }

        self.assertEqual({(1, 4, 3, 1), (1, 1)}, input_shapes)


if __name__ == "__main__":
    unittest.main()
