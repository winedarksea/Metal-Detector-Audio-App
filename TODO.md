# On Device Audio App TODO

## Next Steps
- [ ] **Add Connection Info** Add an info icon or other small way to suggest to users how to connect a detector to the app (the microphone attenuator cable mentioned in readme.md)
- [x] **Confusion over Detection vs Record** Users are having issues on first use understanding the different tabs, and if to use start and stop buttons.
- [x] **Clear Pending** If user hits clear pending, it should also reset the labels. The clear pending button is also not always visible on small screen devices.
- [x] **class_label unset** Don't set a default for the class_label, but make sure it is chosen before saving.
- [x] **Mixed Flag automatically set** Rather than setting mixed_flag with a checkbox, it should be set automatically if more than one label is attached to a recording
- [x] **select microphone** Have users clearly see and be able to select which audio input is being used (built in microphone, microphone from headphone jack, or microphone from usb-c dongle). It might also be helpful to store in the app data which microphone was used, as metadata (but perhaps does not need to be in the csv export).
- [x] **Audio visualization in record tab** The tab used for recording labeled audio should have a small audio visualization to help show that something was recorded (and that it wasn't all zeroes). It might also be nice to have the option to expand a section and there have created and shown a spectrogram and other audio tools.
- [x] **Design for Android Tablets** Support adaptive resize, navigation rail, and more for larger screens in Android 16+.
- [x] **Review tab needs a delete button** Old or unused recordings should be able to be deleted by the user in the review tab.
- [ ] **Kotlin 2.0 and Gradle 9.0 Upgrades** The current base libraries used here are deprecated and updates are needed, also bump to targetSdk=36 or greater.
```
Node.js 20 actions are deprecated. The following actions are running on Node.js 20 and may not work as expected: actions/checkout@v4, actions/setup-java@v4, actions/setup-python@v5, android-actions/setup-android@v3, gradle/actions/setup-gradle@v4. Actions will be forced to run with Node.js 24 by default starting June 2nd, 2026. Node.js 20 will be removed from the runner on September 16th, 2026. Please check if updated versions of these actions are available that support Node.js 24. To opt into Node.js 24 now, set the FORCE_JAVASCRIPT_ACTIONS_TO_NODE24=true environment variable on the runner or in your workflow file. Once Node.js 24 becomes the default, you can temporarily opt out by setting ACTIONS_ALLOW_USE_UNSECURE_NODE_VERSION=true.
```
- [ ] **Consolidate Training Scripts** Merge and improve the separate cnn files so training for Android and Desktop apps are consistent.
- [ ] **NPU or edge TPU Support** Add NnApiDelegate (mel layers may need to be off npu, but this shares code then with Desktop app version) so model can run with TPU or NPU on phones. Use quantization aware training if possible, else static quantization.
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
