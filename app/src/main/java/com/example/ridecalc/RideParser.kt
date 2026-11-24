package com.example.ridecalc

import kotlin.math.abs
import kotlin.math.ceil

/** One line of OCR text and the vertical centre of its bounding box, in screenshot pixels. */
data class OcrLine(val text: String, val centerY: Float)

/**
 * price     = fare from the "Accept for NPR..." button
 * pickupKm  = the "~1,3 km" value in the bottom card
 * tripKm    = the km value in the map bubble at the destination
 * problem   = set when the ride screen was detected but the numbers can't be trusted.
 *             When problem != null the screenshot is never deleted.
 */
data class ParseResult(
    val price: Int,
    val pickupKm: Double?,
    val tripKm: Double?,
    val problem: String?
)

object RideParser {
    // "Accept for NPR195", "Accept for NPR 1,250", "Accept for NPR1.250"
    private val acceptRe = Regex(
        """accept\s*for\s*(?:npr|rs\.?)?\s*([0-9](?:[0-9,. ]*[0-9])?)""",
        RegexOption.IGNORE_CASE
    )

    // "9,6 km", "9.6km", "10 km"; not "1234 km" (left boundary stops a partial match)
    private val kmRe = Regex("""(?<![\d.,])(\d{1,3}(?:[.,]\d{1,2})?)\s*km""", RegexOption.IGNORE_CASE)

    // "850 m" but not "5 min" / "21 min"
    private val metreRe = Regex("""(?<![\d.,])(\d{2,4})\s*m(?![a-z])""", RegexOption.IGNORE_CASE)

    // Map occupies the top of the ride-request screen, the card with the fare is below.
    private const val MAP_FRACTION = 0.47f

    // The map's pickup bubble must agree with the card's pickup value this closely (km).
    private const val PICKUP_TOLERANCE = 0.15

    private const val MIN_FARE = 20
    private const val MAX_FARE = 50000

    private fun distances(text: String): List<Double> {
        val out = ArrayList<Double>()
        for (m in kmRe.findAll(text)) {
            m.groupValues[1].replace(',', '.').toDoubleOrNull()?.let { out.add(it) }
        }
        for (m in metreRe.findAll(text)) {
            m.groupValues[1].toDoubleOrNull()?.let { out.add(it / 1000.0) }
        }
        return out
    }

    /** Returns null when this is not the ride-request detail screen. */
    fun parse(lines: List<OcrLine>, screenHeight: Int): ParseResult? {
        var price: Int? = null
        for (l in lines) {
            val m = acceptRe.find(l.text) ?: continue
            val p = m.groupValues[1].filter { it.isDigit() }.toIntOrNull()
            if (p != null && p > 0) {
                price = p
                break
            }
        }
        val fare = price ?: return null
        if (fare < MIN_FARE || fare > MAX_FARE) {
            return ParseResult(fare, null, null, "fare looks wrong ($fare)")
        }

        val splitY = screenHeight * MAP_FRACTION
        val cardKm = ArrayList<Double>()
        val mapKm = ArrayList<Double>()
        for (l in lines.sortedBy { it.centerY }) {
            for (v in distances(l.text)) {
                if (l.centerY > splitY) cardKm.add(v) else mapKm.add(v)
            }
        }

        val pickup = cardKm.firstOrNull()
            ?: return ParseResult(fare, null, null, "can't read pickup distance")

        // Fail closed: the map must show exactly two distances, and one of them must match
        // the card's pickup value. Otherwise we would be guessing which one is the trip.
        if (mapKm.size != 2) {
            return ParseResult(fare, pickup, null, "can't read both map distances")
        }
        val pickupIdx = if (abs(mapKm[0] - pickup) <= abs(mapKm[1] - pickup)) 0 else 1
        if (abs(mapKm[pickupIdx] - pickup) > PICKUP_TOLERANCE) {
            return ParseResult(fare, pickup, null, "map pickup doesn't match card")
        }
        val trip = mapKm[1 - pickupIdx]
        if (pickup + trip < 0.1) {
            return ParseResult(fare, pickup, null, "distance looks like zero")
        }
        return ParseResult(fare, pickup, trip, null)
    }

    /** Fare you keep per km after commission, over pickup + trip distance. */
    fun netRatePerKm(price: Int, totalKm: Double, commissionPct: Double): Double =
        price * (1.0 - commissionPct / 100.0) / totalKm

    /** Fare needed (rounded up to the next 5) to reach targetRate per km after commission. */
    fun neededFare(totalKm: Double, targetRate: Double, commissionPct: Double): Int {
        val keep = 1.0 - commissionPct / 100.0
        if (keep <= 0.0) return 0
        return (ceil(targetRate * totalKm / keep / 5.0) * 5.0).toInt()
    }
}
