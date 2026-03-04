# On Device Audio App TODO

## Next Steps
- [ ] **Kotlin 2.0 and Gradle 9.0 Upgrades** The current base libraries used here are deprecated and updates are needed
- [ ] **Consolidate Training Scripts** Merge and improve the separate cnn files so training for Android and Desktop apps are consistent.
- [ ] **App load and permissions unittests** Test for issues to assure crash on load (often due to permissions) are not present
- [ ] **Training Data Windows** Investigate the training data flow quality. Current slice logic may select empty windows or windows that don't represent edge cases (chirps falling on window cuts).
- [ ] Optional chirp crop tool for failed auto-segmentation.
- [ ] **Inference:** Investigate increasing window size (0.5s -> 1.0s) to capture full swing dynamics. (Parameter centralized: change `DEFAULT_WINDOW_SIZE_SAMPLES` in `train_starter_model.py` and `INFERENCE_WINDOW_SIZE_SAMPLES` in `AudioConstants.kt`.). Possibly allow window size load from model metadata to allow testing different window sizes live.
- [ ] **Model Architecture:** Evaluate **DS-CNN** (Depthwise Separable CNN) or YAMNet to replace the current vanilla CNN.
- [ ] **Mixed Data** More work needs to be done on deciding how to incorporate mixed signals (junk and target very close to each other or overlapping).
- [ ] **Separate Training File Location** The newer recordings_metadata and rec_*.wav files should be moved to a new location so they are not stored in github (too big). Other files remains as uploaded examples, kept in github.

## On-Device Fine-Tuning - FUTURE EXTENSION
- [ ] Pre-train gate: show sample counts by class and exclusions.
- [ ] Train only on `include_in_training = true` samples.
- [ ] Checkpoint current model before each training run.
- [ ] Show epoch/loss progress and allow safe cancel.
- [ ] Post-train compare: previous vs new metrics; user chooses keep or revert.
