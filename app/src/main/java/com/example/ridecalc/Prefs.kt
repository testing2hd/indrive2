package com.example.ridecalc

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    const val DELETE_OFF = 0
    const val DELETE_DRY_RUN = 1
    const val DELETE_ON = 2

    private const val FILE = "ridecalc"
    private const val KEY_TARGET = "target_rate"
    private const val KEY_COMMISSION = "commission_pct"
    private const val KEY_DEBUG = "last_scan"
    private const val KEY_DELETE_MODE = "delete_mode"

    private fun sp(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun targetRate(c: Context): Double = sp(c).getFloat(KEY_TARGET, 25f).toDouble()
    fun commissionPct(c: Context): Double = sp(c).getFloat(KEY_COMMISSION, 0f).toDouble()

    fun save(c: Context, target: Double, commission: Double) {
        sp(c).edit()
            .putFloat(KEY_TARGET, target.toFloat())
            .putFloat(KEY_COMMISSION, commission.toFloat())
            .apply()
    }

    /** Default is dry run: says which file it would delete, deletes nothing. */
    fun deleteMode(c: Context): Int = sp(c).getInt(KEY_DELETE_MODE, DELETE_DRY_RUN)
    fun saveDeleteMode(c: Context, mode: Int) {
        sp(c).edit().putInt(KEY_DELETE_MODE, mode).apply()
    }

    fun lastScan(c: Context): String = sp(c).getString(KEY_DEBUG, "") ?: ""
    fun saveLastScan(c: Context, text: String) {
        sp(c).edit().putString(KEY_DEBUG, text).apply()
    }
}
