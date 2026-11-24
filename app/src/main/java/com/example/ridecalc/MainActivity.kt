package com.example.ridecalc

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var targetInput: EditText
    private lateinit var commissionInput: EditText
    private lateinit var debug: TextView
    private lateinit var modeGroup: RadioGroup

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun label(text: String, size: Float = 15f, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(12), 0, dp(4))
        }

    private fun numberInput(value: String): EditText =
        EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(value)
        }

    private fun neededPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(36), dp(20), dp(20))
        }

        root.addView(label("Ride Calc", 24f, true))
        status = label("")
        root.addView(status)

        root.addView(label("Your numbers", 18f, true))
        root.addView(label("Target Rs per km (what you want to earn per km driven, pickup + trip)"))
        targetInput = numberInput(fmt(Prefs.targetRate(this)))
        root.addView(targetInput)
        root.addView(label("inDrive commission % (0 if you don't know yet)"))
        commissionInput = numberInput(fmt(Prefs.commissionPct(this)))
        root.addView(commissionInput)
        root.addView(Button(this).apply {
            text = "Save"
            setOnClickListener { save() }
        })

        root.addView(label("Setup", 18f, true))
        root.addView(Button(this).apply {
            text = "1. Allow notifications + photos"
            setOnClickListener { requestPermissions(neededPermissions(), 100) }
        })
        root.addView(Button(this).apply {
            text = "2. All files access (only to delete screenshots)"
            setOnClickListener {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        })
        root.addView(Button(this).apply {
            text = "3. Start watching"
            setOnClickListener {
                WatcherService.start(this@MainActivity)
                status.postDelayed({ refresh() }, 800)
            }
        })
        root.addView(Button(this).apply {
            text = "Stop watching"
            setOnClickListener {
                WatcherService.stop(this@MainActivity)
                status.postDelayed({ refresh() }, 500)
            }
        })

        root.addView(label("Delete the screenshot after reading it", 18f, true))
        root.addView(label("Keep on 'Dry run' until it has named the right file about ten times."))
        modeGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val modes = listOf(
            Prefs.DELETE_DRY_RUN to "Dry run (recommended first): says which file it would delete, deletes nothing",
            Prefs.DELETE_ON to "On: deletes the screenshot after a successful read",
            Prefs.DELETE_OFF to "Off: never delete"
        )
        for ((mode, text) in modes) {
            modeGroup.addView(RadioButton(this).apply {
                id = 1000 + mode
                this.text = text
            })
        }
        modeGroup.check(1000 + Prefs.deleteMode(this))
        modeGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = checkedId - 1000
            Prefs.saveDeleteMode(this@MainActivity, mode)
            if (mode == Prefs.DELETE_ON) {
                Toast.makeText(
                    this@MainActivity,
                    "Deleting is ON. It only deletes a screenshot it just read.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        root.addView(modeGroup)

        root.addView(label("Last screenshot read (send this to Claude if numbers are wrong)", 15f, true))
        debug = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
        }
        root.addView(debug)
        root.addView(Button(this).apply {
            text = "Refresh"
            setOnClickListener { refresh() }
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    private fun refresh() {
        val permsOk = neededPermissions().all {
            checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
        val allFiles = Environment.isExternalStorageManager()
        status.text = "Watcher: " + (if (WatcherService.running) "RUNNING" else "stopped") +
            "\nNotifications + photos: " + (if (permsOk) "allowed" else "NOT allowed (step 1)") +
            "\nAll files access: " + (if (allFiles) "allowed" else "not allowed (only needed for deleting)")
        val last = Prefs.lastScan(this)
        debug.text = if (last.isEmpty()) "(nothing read yet)" else last
    }

    private fun save() {
        val t = targetInput.text.toString().replace(',', '.').toDoubleOrNull()
        val c = commissionInput.text.toString().replace(',', '.').toDoubleOrNull()
        if (t == null || t <= 0.0 || c == null || c < 0.0 || c >= 100.0) {
            Toast.makeText(this, "Enter valid numbers", Toast.LENGTH_SHORT).show()
            return
        }
        Prefs.save(this, t, c)
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
    }

    private fun fmt(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
}
