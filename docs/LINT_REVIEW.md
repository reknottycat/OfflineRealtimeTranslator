# Lint review for the v4.4 preview

The Android CPU and QNN reports cover the same app sources. Their warning totals
are not additive. The baseline PR #13 CI reports contained 201 CPU and 198 QNN
warnings; both include duplicate reports for some native libraries. The remaining
warnings are left enabled and should be reviewed when their affected area changes:

- **Aligned16KB:** each affected library is reported three times. The ARM64 CPU
  APK contains 4 KiB-aligned Vosk and ONNX Runtime; the QNN APK contains 4 KiB-
  aligned Vosk. These are real compatibility risks on 16 KiB page-size devices.
  The x86_64 JNA warning concerns a library excluded from these ARM64 APKs. Replacing
  these binaries requires separately validated runtime and ABI compatibility.
- **Dependency and plugin versions / target SDK:** newer versions and a newer
  target are suggested. They were not changed in this cleanup: native runtime ABI,
  build plugin, Android behavior and model inference require their own validation.
  Moving existing versions into the catalog does not upgrade them.
- **ChromeOsAbiSupport:** these preview APKs intentionally ship ARM64 only;
  adding x86_64 requires compatible native inference dependencies and testing.
- **UsableSpace:** four checks conservatively use free space during download or
  extraction. Allocating reclaimable cache space would change storage behavior;
  preserve the current safety margin and resumability until that is tested.
- **UnusedAttribute:** the two `clipToOutline` XML attributes are unavailable on
  API 29–30. Changing the layout or adding runtime clipping needs visual testing
  across supported Android versions; the existing behavior is retained.
- **NestedWeights:** the two nested subtitle panels are layout performance
  suggestions. Changing their sizing requires screen and font-scale validation.
- **ClickableViewAccessibility:** both reports refer to the same scroll listener,
  which observes touch movement and returns false to preserve scrolling and text
  selection; it does not handle a click. Calling `performClick` for a drag would
  misrepresent the gesture. Accessibility behavior still needs device testing.
- **NotifyDataSetChanged:** replacement of the entire history query result can
  change order and size. Its full refresh remains until a separately tested diff
  strategy is introduced; selection-only updates now use range notifications.
- **TypographyDashes:** the hyphens are part of the model name `HY-MT2-1.8B`,
  not punctuation between numbers.

No warnings are globally disabled or added to a baseline. Warning counts may
vary with Lint's current version metadata. Device installation, accessibility,
16 KiB-page behavior and actual speech recognition are not covered by JVM tests.
