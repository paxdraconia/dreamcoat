# Dreamcoat: How to Use

Dreamcoat turns your Pixel into a Bluetooth keyboard and mouse for your laptop, controlled by your
Levi's Jacquard jacket sleeve, the phone's motion (air mouse), and your voice.

```
Jacket cuff tag ──BLE──▶ Pixel (Dreamcoat app) ──Bluetooth keyboard/mouse──▶ Windows laptop
```

Nothing is installed on the laptop. It just sees a normal Bluetooth keyboard + mouse called **Dreamcoat**.

---

## Presenting (no jacket needed)

The main screen is a clicker. The chip at the top right shows the laptop it's connected to
(teal), or **Not connected** (marigold). Tap the chip to reconnect.

| Control | What it does on the laptop |
|---|---|
| Big **Next** button | Right arrow (next slide) |
| **Back** | Left arrow (previous slide) |
| **Black** | `B`: blacks out the screen in PowerPoint / Google Slides; press again to return |
| **Esc** | Escape (leave the slideshow) |
| **Present** | Back into the slideshow at the current slide: Shift+F5 (PowerPoint) or Ctrl+F5 (Google Slides); pick which in the ☰ menu |
| **Volume down** | Next slide (works with the **phone locked** or in your pocket) |
| **Volume up** | Previous slide |
| Lock-screen / notification **Back · Next** buttons | Same as above |
| **Hold to aim** pad | Air mouse: hold it and point the phone; side pads are left/right click |
| Bottom bar: 🎤, text, ➤ | Dictate or type, then ➤ types it into the laptop |

- **Before you present:** open Dreamcoat once and check the chip shows your laptop. Then lock the
  phone; the volume keys keep working.
- While **Volume keys change slides** is on, the volume keys don't change the phone's own
  volume. Turn it off in the ☰ menu after your talk. Media apps that start playing (music,
  podcasts) can take the volume keys back; pause them before presenting.
- The ☰ menu (top left) holds everything else: laptop pairing, **Keep screen on**,
  **Press Enter after typing**, air mouse speed, and the jacket controls.

### Pixel Watch remote
Open **Dreamcoat** on the watch (the phone app must be running). The top line shows the mode and
laptop. Each action buzzes once (twice for a mode swap).

| On the watch | Does (Slides mode) | Same as jacket |
|---|---|---|
| **Double pinch** (tap index finger and thumb twice) or the **Next** button | Next slide | Brush out |
| **Wrist turn** or the **Back** button | Previous slide | Brush in |
| Swipe left / right, or turn the crown | Next / previous | Brush out / in |
| Tap the top status line | Enter (in LLM demo mode, it sends the next queued prompt) | Double tap |
| Long-press the top status line | Swap Slides ↔ LLM demo | Cover |

- These use the same mappings as the jacket, so changing a gesture in the ☰ menu changes the watch too.
- Pinch and wrist turn only work while the watch screen is **on** with Dreamcoat in front. The app
  keeps the screen on while it's open, and raising your wrist brings it back.
- **Wrist turn** is a quick flick: from looking at the watch, rotate your forearm sharply so the
  screen turns away from you, then straight back, in about half a second. A slow turn doesn't count.
  If it's unreliable, use the crown or the Back button instead.
- **Pause** (next to Back) turns off every watch input (pinch, wrist turn, swipes, crown, buttons) so
  you can stop to help someone without moving slides by accident. The top line turns marigold and
  reads **Paused · gestures off**, and the screen is allowed to sleep. Tap **Resume** to pick up
  where you left off. The phone's own buttons and volume keys still work while the watch is paused.

### Quitting
- **Phone:** ☰ menu → **Quit Dreamcoat (phone and watch)**, or tap **Quit** on the Dreamcoat
  notification. This disconnects the laptop, gives the volume keys back to the phone, and closes
  the watch app too. Swiping the app away from Recents does *not* stop it.
- **Watch only:** tap **Exit** at the bottom of the remote. The side button (and a system back)
  only hides the remote; it keeps running so you can raise your wrist to get back to it.
- Pinch and wrist turn need Wear OS 7. Buttons, swipes and the crown work on any version.

**Put the icon on your home screen:** open the app drawer (swipe up), long-press **Dreamcoat** and
drag it onto the home screen.

---

## 1. One-time setup

### Phone
1. Open **Dreamcoat**. Allow **Nearby devices** and **Notifications** when asked.
   - Nearby devices lets the app use Bluetooth.
   - The notification keeps it running with the screen off.
2. A "Dreamcoat" notification appears and stays while the bridge is running.

### Laptop (do this once per laptop)
1. On the phone, open the ☰ menu and tap **Make discoverable** → **Allow**. You have 2 minutes.
2. On Windows: **Settings → Bluetooth & devices → Add device → Bluetooth**.
3. Pick **Dreamcoat** (it may show your phone's name). Confirm the code matches on both screens.
4. The app's *Laptop* line shows **Connected to <laptop>**.

Next time, the phone reconnects automatically when you open the app. If it doesn't, tap **Reconnect laptop**.

### Jacket (do this once)
1. Snap the tag onto the jacket cuff.
2. In the app's ☰ menu tap **Find jacket**. If the tag doesn't appear within ~10 s, hold the tag's button
   for about 4 seconds until its LED flashes (pairing mode).
3. Tap your tag in the list, then confirm Android's "Pair with…" prompt(s).
4. The *Jacket* line shows **Jacket ready**. After this it reconnects by itself whenever it's in range.

If the line says *"Tag connected — snap it onto the jacket cuff"*, the tag is on but not seated on the cuff.

---

## 2. Gestures

Cover the cuff with your palm to switch modes. The tag confirms with 2 buzzes and a 2-second
light: **cyan = Slides**, **purple = LLM demo**.

| Sleeve gesture | Slides mode (default) | LLM demo mode | Feedback |
|---|---|---|---|
| **Brush out** (swipe away from you) | → Right arrow (next slide) | Alt + Tab | Green flash |
| **Brush in** (swipe toward you) | ← Left arrow (previous slide) | Escape | Blue flash |
| **Double tap** | Enter | Type next queued prompt + Enter (just Enter if the queue is empty) | 1 buzz |
| **Cover** (palm over cuff) | Switch to LLM demo | Switch to Slides | 2 buzzes + mode colour |

**Change any of these** in the app under *Gestures — <mode> mode*. The dropdowns edit the
currently selected mode, so switch modes with the Mode buttons to edit the other one. Changes save
automatically.

---

## 3. Air mouse

**On-screen clutch (default):**
- Hold the big **Hold to aim** pad and point/turn the phone. The cursor follows.
- Let go and the cursor stops, so you can lower your hand without it drifting.
- **Left click / Right click** pads: tap to click; hold Left click while aiming to drag.
- **Air mouse speed** slider sets how far the cursor moves per degree of rotation.

The phone can be held flat like a TV remote or upright. Avoid holding it sideways (landscape).

**Sleeve clutch (optional):** turn on **Sleeve air mouse**. Now resting your fingers on the cuff
engages the cursor instead of the on-screen pad.
- While this is on, the tag reports touch instead of gestures, so **sleeve gestures (including
  cover-to-switch-mode) are paused**. Turn the switch off in the app to get gestures back.
- Keep the screen on while using it.

---

## 4. Voice prompts

1. Tap 🎤 in the bottom bar (or the mic on the keyboard) and speak. The text lands in the bar,
   where you can edit it.
2. Then either:
   - **➤ (send)**: types it into whatever has focus on the laptop right now (and presses Enter
     if *Press Enter after typing* is on in the ☰ menu).
   - **Queue** (the list-plus icon, shown only while the jacket is connected): holds it. Pocket
     the phone, walk to the stage, switch to **LLM demo** mode and **double-tap the cuff**. The
     prompt types itself into the focused window and hits Enter.
3. You can queue several prompts; each double tap sends the next one. Long-press the
   "N queued" line under the bar to clear the queue.

**Important:** the laptop's keyboard layout must be **English (US)**. On another layout,
punctuation comes out wrong. Characters a US keyboard can't type (e.g. é, emoji) are skipped.
Curly quotes and dashes from dictation are converted to plain ones automatically.

---

## 5. Troubleshooting

| Problem | Fix |
|---|---|
| Laptop line stuck on "Ready — pair or reconnect" | Tap **Reconnect laptop**. If it still fails, remove "Dreamcoat"/your phone from Windows Bluetooth devices and pair again. |
| Laptop says "Bluetooth is off, or this phone can't act as a keyboard/mouse" | Turn Bluetooth on and reopen the app. |
| Jacket shows "disconnected" | Move closer and make sure the tag is snapped on and charged. It reconnects on its own. |
| Tag never shows up in **Find jacket** | Hold the tag button ~4 s for pairing mode. If it was paired to the old Levi's/Jacquard app, unpair it in Android Bluetooth settings first. |
| Gestures do nothing | Check **Sleeve air mouse** is off, and the *Jacket* line says **Jacket ready**. |
| Some typed characters missing | Rare on busy machines. Tell Claude to raise the inter-key delay (currently 20 ms). |
| Cursor too fast/slow/jittery | Adjust **Air mouse speed**. |
| App stops in the background | Make sure the Dreamcoat notification is present; don't swipe it away. Settings → Apps → Dreamcoat → Battery → **Unrestricted** helps. |

### Rebuilding / reinstalling (for reference)
From the project folder in a terminal:

The installed app is the signed **release** build. Its signing key is
`%USERPROFILE%\.dreamcoat\release.jks`, with its password in `%USERPROFILE%\.gradle\gradle.properties`.
**Back up both files**: without them, future updates can't install over the current app, so you'd
have to uninstall it first and lose its settings.

```bash
./gradlew :dreamcoat:assembleRelease
```

```bash
adb install -r dreamcoat/build/outputs/apk/release/dreamcoat-release.apk
```

The watch app is a separate build in `watch/` (newer Android tooling). It uses the same signing
key, which it must for the watch and phone to talk. Build it from the `watch` folder, then install
it over the watch's wireless debugging (`adb connect <watch ip:port>` first):

```bash
./gradlew :app:assembleRelease
```

```bash
adb -s <watch ip:port> install -r app/build/outputs/apk/release/app-release.apk
```
