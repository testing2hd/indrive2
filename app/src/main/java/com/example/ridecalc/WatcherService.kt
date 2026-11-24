package com.example.ridecalc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Watches the gallery for a screenshot the USER just took, reads fare + distances from it with
 * on-device OCR and posts a notification. It never touches the inDrive app: no accessibility
 * service, no overlay, no screen capture, no internet permission.
 */
class WatcherService : Service() {

    private data class Candidate(val id: Long, val name: String, val data: String?)

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    // Touched only from the worker thread.
    private val seen = LinkedHashSet<Long>()
    private var retries = 0

    private var observer: ContentObserver? = null
    private val checkRunnable = Runnable { runOnWorker { safeScan() } }

    /** Safe to call after the service started shutting down (Stop pressed mid-OCR). */
    private fun runOnWorker(block: () -> Unit) {
        try {
            if (!worker.isShutdown) worker.execute { block() }
        } catch (e: RejectedExecutionException) {
            // service is stopping; drop the work
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onCreate() {
        super.onCreate()
        createChannels()
        try {
            val n = watchNotification("Watching for screenshots")
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(WATCH_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(WATCH_ID, n)
            }
        } catch (e: Exception) {
            stopSelf()
            return
        }
        val obs = object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) {
                scheduleCheck(1200)
            }
        }
        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, obs
        )
        observer = obs
        running = true
    }

    override fun onDestroy() {
        observer?.let { contentResolver.unregisterContentObserver(it) }
        main.removeCallbacksAndMessages(null)
        running = false
        try {
            recognizer.close()
        } catch (e: Exception) {
            // ignore: closing during shutdown
        }
        worker.shutdown()
        super.onDestroy()
    }

    // ---- finding the screenshot ---------------------------------------------------------------

    private fun scheduleCheck(delayMs: Long) {
        main.removeCallbacks(checkRunnable)
        main.postDelayed(checkRunnable, delayMs)
    }

    private fun safeScan() {
        try {
            scanRecent()
        } catch (e: SecurityException) {
            setStatus("Photos permission missing. Open Ride Calc, step 1.")
        } catch (e: Exception) {
            setStatus("Error: ${e.javaClass.simpleName}")
        }
    }

    private fun scanRecent() {
        val nowSec = System.currentTimeMillis() / 1000
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATA
        )
        val selection = "${MediaStore.Images.Media.DATE_ADDED} >= ?"
        val args = arrayOf((nowSec - ScreenshotGuard.READ_MAX_AGE_SECONDS - 5).toString())
        val order = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        var found: Candidate? = null
        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, selection, args, order
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                if (id in seen) continue
                val name: String? = c.getString(1)
                val rel: String? = c.getString(2)
                val added = c.getLong(3)
                val size = c.getLong(4)
                val data: String? = c.getString(5)
                if (name != null &&
                    ScreenshotGuard.isRecentScreenshot(name, rel, nowSec - added, size)
                ) {
                    found = Candidate(id, name, data)
                    break
                }
            }
        }
        val target = found ?: return
        seen.add(target.id)
        while (seen.size > 200) seen.remove(seen.first())
        processImage(target)
    }

    // ---- reading it ---------------------------------------------------------------------------

    private fun processImage(t: Candidate) {
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, t.id)
        val bmp: Bitmap? = try {
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            null
        }
        if (bmp == null) {
            // File may still be being written. Try again a few times.
            seen.remove(t.id)
            if (retries < 3) {
                retries++
                scheduleCheck(1500)
            } else {
                retries = 0
                setStatus("Couldn't open the screenshot")
            }
            return
        }
        retries = 0
        val height = bmp.height
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { text ->
                val lines = ArrayList<OcrLine>()
                for (block in text.textBlocks) {
                    for (line in block.lines) {
                        val y = line.boundingBox?.centerY()?.toFloat() ?: continue
                        lines.add(OcrLine(line.text, y))
                    }
                }
                runOnWorker { finish(t, lines, height) }
            }
            .addOnFailureListener {
                runOnWorker { setStatus("OCR failed. Screenshot kept.") }
            }
            .addOnCompleteListener { bmp.recycle() }
    }

    private fun finish(t: Candidate, lines: List<OcrLine>, height: Int) {
        val result = RideParser.parse(lines, height)
        saveDebug(t, lines, result)
        if (result == null) {
            setStatus("Last screenshot wasn't a ride request")
            return
        }
        val pickup = result.pickupKm
        val trip = result.tripKm
        if (result.problem != null || pickup == null || trip == null) {
            postResult(
                "Ride Calc: ${result.problem ?: "can't read"}",
                "Fare Rs ${result.price}. Screenshot kept. Open Ride Calc > Last scan to see what was read."
            )
            setStatus("Last: couldn't read all numbers")
            return
        }

        val target = Prefs.targetRate(this)
        val commission = Prefs.commissionPct(this)
        val total = pickup + trip
        val rate = RideParser.netRatePerKm(result.price, total, commission)
        val need = RideParser.neededFare(total, target, commission)
        val good = rate >= target

        val title = String.format(
            Locale.US, "%s Rs %.1f /km  (%.1f km)", if (good) "✅" else "❌", rate, total
        )
        var text = String.format(
            Locale.US,
            "pickup %.1f + trip %.1f. Fare Rs %d. Need Rs %d for Rs %.0f/km.",
            pickup, trip, result.price, need, target
        )
        text += when (Prefs.deleteMode(this)) {
            Prefs.DELETE_ON ->
                if (deleteExact(t)) "\nScreenshot deleted." else "\nCouldn't delete the screenshot."
            Prefs.DELETE_DRY_RUN -> "\nDry run: would delete ${t.name}."
            else -> ""
        }
        postResult(title, text)
        setStatus(String.format(Locale.US, "Last: Rs %.1f/km", rate))
    }

    // ---- deleting exactly the screenshot that was just read -----------------------------------

    private fun deleteExact(t: Candidate): Boolean {
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, t.id)
        val nowSec = System.currentTimeMillis() / 1000

        var name: String? = null
        var rel: String? = null
        var added = 0L
        var size = 0L
        var data: String? = null
        contentResolver.query(
            uri,
            arrayOf(
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.RELATIVE_PATH,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.DATA
            ),
            null, null, null
        )?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0)
                rel = c.getString(1)
                added = c.getLong(2)
                size = c.getLong(3)
                data = c.getString(4)
            }
        }

        // Re-check everything on the row as it is right now. Any mismatch = delete nothing.
        if (name != t.name) return false
        if (!ScreenshotGuard.isRecentScreenshot(
                name, rel, nowSec - added, size, ScreenshotGuard.DELETE_MAX_AGE_SECONDS
            )
        ) return false

        try {
            if (contentResolver.delete(uri, null, null) == 1) return true
        } catch (e: Exception) {
            // fall through to the plain-file route (works with All files access)
        }

        val path = data
        if (path != null && ScreenshotGuard.isSafeFilePath(path, name)) {
            val f = File(path)
            if (f.exists() && f.delete()) {
                MediaScannerConnection.scanFile(this, arrayOf(path), null, null)
                return true
            }
        }
        return false
    }

    // ---- notifications ------------------------------------------------------------------------

    private fun nm(): NotificationManager = getSystemService(NotificationManager::class.java)

    private fun createChannels() {
        nm().createNotificationChannel(
            NotificationChannel(CH_WATCH, "Watcher status", NotificationManager.IMPORTANCE_LOW)
        )
        val result = NotificationChannel(
            CH_RESULT, "Ride result", NotificationManager.IMPORTANCE_HIGH
        )
        result.enableVibration(false)
        result.setSound(null, null)
        nm().createNotificationChannel(result)
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun watchNotification(text: String): Notification =
        Notification.Builder(this, CH_WATCH)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("Ride Calc")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openAppIntent())
            .build()

    private fun setStatus(text: String) {
        val time = SimpleDateFormat("HH:mm", Locale.US).format(Date())
        nm().notify(WATCH_ID, watchNotification("$time  $text"))
    }

    private fun postResult(title: String, text: String) {
        val n = Notification.Builder(this, CH_RESULT)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setTimeoutAfter(60_000L)
            .setContentIntent(openAppIntent())
            .build()
        nm().notify(RESULT_ID, n)
    }

    private fun saveDebug(t: Candidate, lines: List<OcrLine>, result: ParseResult?) {
        val sb = StringBuilder()
        sb.append(t.name).append('\n')
        sb.append("parse: ").append(result?.toString() ?: "not a ride screen").append('\n')
        for (l in lines.sortedBy { it.centerY }) {
            sb.append(l.centerY.toInt()).append(" | ").append(l.text.replace('\n', ' ')).append('\n')
            if (sb.length > 3500) break
        }
        Prefs.saveLastScan(this, sb.toString())
    }

    companion object {
        @Volatile
        var running = false

        private const val CH_WATCH = "watch"
        private const val CH_RESULT = "result"
        private const val WATCH_ID = 1
        private const val RESULT_ID = 2

        fun start(c: Context) {
            c.startForegroundService(Intent(c, WatcherService::class.java))
        }

        fun stop(c: Context) {
            c.stopService(Intent(c, WatcherService::class.java))
        }
    }
}
