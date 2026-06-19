# On Device Audio App TODO

## Next Steps
- [ ] **Add Connection Info** Add an info icon or other small way to suggest to users how to connect a detector to the app (the microphone attenuator cable mentioned in readme.md)
- [x] **Confusion over Detection vs Record** Users are having issues on first use understanding the different tabs, and if to use start and stop buttons.
- [x] **Clear Pending** If user hits clear pending, it should also reset the labels. The clear pending button is also not always visible on small screen devices.
- [x] **class_label unset** Don't set a default for the class_label, but make sure it is chosen before saving.
- [x] **Mixed Target and Junk automatically set** Derive `mixed_target_and_junk` from at least one object labeled TARGET and one labeled JUNK.
- [x] **select microphone** Have users clearly see and be able to select which audio input is being used (built in microphone, microphone from headphone jack, or microphone from usb-c dongle). It might also be helpful to store in the app data which microphone was used, as metadata (but perhaps does not need to be in the csv export).
- [x] **Audio visualization in record tab** The tab used for recording labeled audio should have a small audio visualization to help show that something was recorded (and that it wasn't all zeroes). It might also be nice to have the option to expand a section and there have created and shown a spectrogram and other audio tools.
- [x] **Design for Android Tablets** Support adaptive resize, navigation rail, and more for larger screens in Android 16+.
- [x] **Review tab needs a delete button** Old or unused recordings should be able to be deleted by the user in the review tab.
- [x] **Kotlin 2.0 and Gradle 9.0 Upgrades** The current base libraries used here are deprecated and updates are needed, also bump to targetSdk=36 or greater.
- [x] **Separate Training File Location** The newer recordings_metadata and rec_*.wav files should be moved to a new location so they are not stored in github (too big). Other files remains as uploaded examples, kept in github. gitignore might be a simpler solution.
- [x] **Consolidate Training Scripts** Merge and improve the separate cnn files so training for Android and Desktop apps are consistent.
- [x] **NPU or edge TPU Support** Add NnApiDelegate (mel layers may need to be off npu, but this shares code then with Desktop app version) so model can run with TPU or NPU on phones. Use quantization aware training if possible, else static quantization.
- [ ] **App load and permissions unittests** Test for issues to assure crash on load (often due to permissions) are not present
- [ ] **Training Data Windows** Investigate the training data flow quality. Current slice logic may select empty windows or windows that don't represent edge cases (chirps falling on window cuts).
- [ ] Optional chirp crop tool for failed auto-segmentation.
- [x] **Inference:** Increase the production window to 1.0s with a 0.5s hop and keep runtime framing synchronized through model metadata.
- [ ] **Model Architecture:** Evaluate **DS-CNN** variants (Depthwise Separable CNN, depthwise temporal convolutions) or YAMNet to replace the current vanilla CNN. Assess multi-resolution features. Test PCEN or a log-mel plus spectral-delta representation. Test replacing linear resampling with anti-aliased polyphase resampling. Try using the qat_onnx_models code here.
- [ ] **Mixed Data** More work needs to be done on deciding how to incorporate mixed signals (junk and target very close to each other or overlapping).

## On-Device Fine-Tuning - FUTURE EXTENSION
- [ ] Pre-train gate: show sample counts by class and exclusions.
- [ ] Train only on `include_in_training = true` samples.
- [ ] Checkpoint current model before each training run.
- [ ] Show epoch/loss progress and allow safe cancel.
- [ ] Post-train compare: previous vs new metrics; user chooses keep or revert.

## Shared Social Website
- [ ] Users can upload their audio to servers. Expected infrastructure: Cloudflare Workers + Cloudflare R2 + Cloudflare D1 with an emphasis on lightweight storage, compute, and networking. Everything should be constructed to be as minimal as possible in usage of cloud services (full quality audio should still be kept though, but likely with a max upload of 10 seconds, prompt to trim if larger) with the client device doing as much heavy lifting as possible.
- [ ] Users can authenicate with Google/Apple/etc or anonymously (no site managed logins)
- [ ] Users can upload their data with a selection of private location (default, no location shown to others), a simple text string (needs cleaning to prevent spam, URLs), or full exact location (if recorded). Behind the scenes, full location always uploaded if present, but not visible to users if marked private.
- [ ] Users on upload can select from "unconfirmed", "confirmed", and "high quality labels for uncertain labels, certain labels, and for certain labels with perceived high quality recordings respectively
- [ ] Logged in users can upvote or flag recordings. Flags would include bad audio quality, incorrect identification, bad image, and other bad data reports.
- [ ] Users have three main views in this social website. They can see their own uploads (if logged in), they can explore all uploads by metadata, and they can play a "game".
- [ ] The game is a flashcard style game (multiple choice). It also shows them the detector settings used for recording. Results are tracked to help identify good quality audio recordings. Audio that has low accuracy of prediction here is viewable in the admin UI, to help suggest items which may have low quality.
- [ ] Ideally UI would clearly show audio, and user's volume level (if accessible) so users aren't flagging bad data just because they have their volumn turned down off.
- [ ] Logged in Users can get points for uploaded data (primary point earner), upvotes on their data, and 
- [ ] Policy should clearly state that users must upload their own content and are giving permission for the data to be shared and used for model training.
- [ ] Social/Explore UI should clearly state when a user is offline (as the rest of the PWA works fine offline, but this part doesn't).
- [ ] Internal facing dashboard to access and review uploads to use for training the improved model. Admin here would also be able to delete, edit the metadata, and trim the audio of recordings.

### Spam Controls for Website
- [ ] Reject any submission that contains http://, https://, www., or common top-level domains (e.g., .com, .net, .org, .xyz, .info)
- [ ] Block text containing obvious spam flags (ie investing, contact info like telegram, and anything with @)
- [ ] Add a visually hidden form (like 'contact info') which if filled, is always dropped
- [ ] Cap fields at 250 characters
- [ ] Generally focus the upload on coming from the app only, rigidly conforming to app standardized inputs (like correct date format), or else rejected.
- [ ] Also assure WAV files are not malicious (anything that doesn't look like standard audio should be discarded)
