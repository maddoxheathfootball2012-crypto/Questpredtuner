# Quest Pred Tuner

Standalone Quest 2 app for nudging Quest debug settings (Phase Sync, refresh rate, or any `debug.*` setting) up and down with +/− buttons. No PC needed: it pairs with the headset's own Wireless debugging, the same way Quest Games Optimizer does.

## Download

Every push to `main` builds a new APK on GitHub Actions. Get the newest one from the Quest browser:

`https://github.com/<your-username>/<repo-name>/releases/latest/download/QuestPredTuner.apk`

## First-time setup

1. Settings → System → Developer → turn on **Wireless debugging** and tap **Always allow**.
2. Tap the words **Wireless debugging**, then **Pair device with pairing code**.
3. Open Quest Pred Tuner next to that window. Type the port (numbers after the colon) and the 6-digit code, then tap **Pair**.

## After every headset restart

Debug settings reset on reboot. Turn Wireless debugging back on, open the app, tap **Connect**. With "Re-apply my saved values" ticked, your settings come straight back.

## Finding what another app changed

Run the other app first, then (without restarting) open this one and tap **Scan Quest settings**. It lists every `debug.*` setting that's currently set. Tap one to load it into a Custom row.

## Notes

- Settings apply to games you open *after* changing them.
- **Reset** clears a setting so the game uses its own value again.
- The signing key in `app/predtuner.keystore` is only there so updates install over old versions. It isn't a secret.
