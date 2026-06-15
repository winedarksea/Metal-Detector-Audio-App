import unittest

import numpy as np

from scripts.export_onnx_cnn_only import classification_outcome_metrics


class ClassificationOutcomeMetricsTest(unittest.TestCase):
    def test_reports_confusion_and_per_class_metrics(self):
        label_order = ["TARGET", "JUNK", "AMBIENT"]
        labels = np.array([0, 0, 1, 1, 2, 2], dtype=np.int64)
        predictions = np.array(
            [
                [0.9, 0.1, 0.0],
                [0.1, 0.8, 0.1],
                [0.1, 0.8, 0.1],
                [0.7, 0.2, 0.1],
                [0.1, 0.2, 0.7],
                [0.1, 0.2, 0.7],
            ],
            dtype=np.float32,
        )

        metrics = classification_outcome_metrics(predictions, labels, label_order)

        self.assertAlmostEqual(4 / 6, metrics["top1_accuracy"])
        self.assertEqual(
            {"TARGET": 1, "JUNK": 1, "AMBIENT": 0},
            metrics["confusion_matrix"]["TARGET"],
        )
        self.assertEqual(
            {"TARGET": 1, "JUNK": 1, "AMBIENT": 0},
            metrics["confusion_matrix"]["JUNK"],
        )
        self.assertEqual(
            {"TARGET": 0, "JUNK": 0, "AMBIENT": 2},
            metrics["confusion_matrix"]["AMBIENT"],
        )
        self.assertEqual(2, metrics["per_class"]["TARGET"]["support"])
        self.assertEqual(1, metrics["per_class"]["TARGET"]["false_positive"])
        self.assertEqual(1, metrics["per_class"]["TARGET"]["false_negative"])
        self.assertEqual(2, metrics["per_class"]["AMBIENT"]["true_positive"])


if __name__ == "__main__":
    unittest.main()
