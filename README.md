# Light Journey

A small Android experiment for eyes-closed light and sound sessions.

## Initial build

- Native Android app
- Smooth full-screen color/brightness modulation
- Pulse rate limited to 0.1–1.0 Hz
- Intensity control limited to 20–70%
- 3, 5, or 10 minute sessions
- Optional soft ambient audio generated on-device
- Persistent STOP button and automatic stop when the app leaves the foreground
- Safety acknowledgement before entering the controls
- No camera-flash/torch strobing
- No network or special permissions

## Safety

Bright or rhythmic light can cause discomfort, migraine, dizziness, nausea, or seizures in susceptible people. Do not use while driving, walking, standing, bathing, or near stairs. Stop immediately if you feel unwell. People with seizure disorders, photosensitivity, unexplained blackouts, or medical advice to avoid flashing light should not use this app.

## APK

GitHub Actions builds `app-debug.apk` automatically from `main` and publishes it as the `Light-Journey-debug-apk` workflow artifact.
