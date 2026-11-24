# Ride Calc (screenshot version)

You take a normal screenshot of an inDrive ride request (power + volume down, **only while stopped**). Ride Calc notices the new screenshot in your gallery, reads the fare and the two distances with on-device OCR, and posts a notification:

    ✅ Rs 17.9 /km  (10.9 km)
    pickup 1.3 + trip 9.6. Fare Rs 195. Need Rs 275 for Rs 25/km.

✅ means at or above your target rate, ❌ means below.

It never touches the inDrive app: no accessibility service, no overlay, no screen capture. It has **no internet permission**, so it cannot send anything anywhere.

## Get the APK (no Android Studio)

1. Create a new **private** GitHub repo.
2. Upload everything in this folder, including the hidden `.github` folder.
3. Open the repo's **Actions** tab. "Build APK" starts by itself and takes about 5 minutes.
4. Open the finished run, download the **ride-calc-apk** artifact (a zip), unzip, install `app-debug.apk`.
5. If the run fails, open it, copy the red error text and send it to Claude. This code has never been compiled, so a first-build error is possible.

## Phone setup (once)

1. Open Ride Calc, set your target Rs/km and commission %, tap Save.
2. Step 1: allow notifications + photos.
3. Step 3: Start watching. A small "Ride Calc" notification stays in the shade while it runs.
4. Take a screenshot of a ride request. The result notification should appear within 2-3 seconds.

## Deleting screenshots (optional, careful)

Default is **Dry run**: the notification says "would delete Screenshot_xxx" and nothing is deleted. Keep it on Dry run until it has named the right file about ten times. Then switch to **On** and, for silent deletion, grant step 2 (All files access).

What it will delete: exactly one file, the screenshot it just read, only if all of these hold:
- the read succeeded and both distances were found (otherwise the screenshot is kept)
- the file is in a Screenshots folder (Pictures/Screenshots or DCIM/Screenshots), named "Screenshot...", an image, under 25 MB
- it is less than 2 minutes old

If a screenshot is not a ride request, nothing happens to it.

## If numbers are wrong

Open Ride Calc, tap Refresh under "Last screenshot read" and send that text to Claude with what the notification said.

## Known limits

- Android 11 or newer.
- It reads distances by screen position: map bubbles in the top ~47% of the screenshot, the pickup distance in the card below. A different layout or an inDrive redesign may need a tweak.
- It refuses to guess: if it cannot read both map distances, or the map pickup doesn't match the card, you get "can't read" and the screenshot is kept.
- OCR can misread a digit. The notification shows pickup and trip km so you can spot it.
- Screenshot folder names differ between phones. If it never reacts, send the "Last screenshot read" text, or tell Claude where your screenshots are saved.
- inDrive's support told the user that overlays and third-party tools on top of the app can trigger account restrictions. This version doesn't run on top of inDrive, but nobody has confirmed in writing that it is allowed. Ask inDrive support before relying on it.
