package ru.measurekit.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Помощник работы с GPS через стандартный Android LocationManager (без GMS).
 *
 * Возвращает Coords (широта, долгота, точность, время, провайдер).
 * Для совместимости со старым UI-кодом также есть метод format(coords).
 */
object GpsHelper {

    /** Координаты с метаданными. */
    data class Coords(
        val latitude: Double,
        val longitude: Double,
        val accuracyM: Float,
        val timestampMs: Long,
        val provider: String,
    ) {
        /** Для PDF: «41.234567° N, 33.456789° E (±15 м, GPS)» */
        fun formatted(): String {
            val ns = if (latitude >= 0) "N" else "S"
            val ew = if (longitude >= 0) "E" else "W"
            return "%.6f° %s, %.6f° %s (±%.0f м, %s)".format(
                Math.abs(latitude), ns, Math.abs(longitude), ew, accuracyM, provider
            )
        }
        fun short(): String = "%.5f, %.5f".format(latitude, longitude)
    }

    fun hasLocationPermission(ctx: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Быстро взять последнюю известную позицию.
     * Возвращает null если её нет, ей более 10 минут или нет разрешения.
     */
    fun getLastKnown(ctx: Context): Coords? {
        if (!hasLocationPermission(ctx)) return null
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null

        var best: Location? = null
        for (provider in listOf(LocationManager.GPS_PROVIDER,
                                LocationManager.NETWORK_PROVIDER,
                                LocationManager.PASSIVE_PROVIDER)) {
            try {
                val loc = lm.getLastKnownLocation(provider) ?: continue
                if (best == null || loc.accuracy < best!!.accuracy) {
                    best = loc
                }
            } catch (e: SecurityException) { /* нет разрешения на провайдер */ }
        }

        val loc = best ?: return null
        val ageMs = System.currentTimeMillis() - loc.time
        if (ageMs > 10 * 60 * 1000) return null

        return Coords(
            latitude = loc.latitude,
            longitude = loc.longitude,
            accuracyM = loc.accuracy,
            timestampMs = loc.time,
            provider = loc.provider ?: "unknown",
        )
    }

    /** Запрос актуальной позиции (single shot) с таймаутом. */
    suspend fun requestSingleUpdate(
        ctx: Context,
        timeoutMs: Long = 10_000L,
    ): Coords? = suspendCancellableCoroutine { cont ->
        if (!hasLocationPermission(ctx)) { cont.resume(null); return@suspendCancellableCoroutine }
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: run { cont.resume(null); return@suspendCancellableCoroutine }

        val providers = mutableListOf<String>().apply {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) add(LocationManager.GPS_PROVIDER)
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) add(LocationManager.NETWORK_PROVIDER)
        }
        if (providers.isEmpty()) {
            cont.resume(getLastKnown(ctx))
            return@suspendCancellableCoroutine
        }

        var resolved = false
        val listener = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                if (resolved) return
                resolved = true
                cont.resume(Coords(
                    latitude = loc.latitude,
                    longitude = loc.longitude,
                    accuracyM = loc.accuracy,
                    timestampMs = loc.time,
                    provider = loc.provider ?: "unknown",
                ))
                try { lm.removeUpdates(this) } catch (_: Exception) {}
            }
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            @Deprecated("API 28")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }

        try {
            for (p in providers) {
                lm.requestLocationUpdates(p, 1000L, 0f, listener)
            }
        } catch (e: SecurityException) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }

        cont.invokeOnCancellation { try { lm.removeUpdates(listener) } catch (_: Exception) {} }

        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (!resolved) {
                resolved = true
                try { lm.removeUpdates(listener) } catch (_: Exception) {}
                cont.resume(getLastKnown(ctx))
            }
        }, timeoutMs)
    }

    /** Форматирование для UI: возвращает строку «GPS: 41.23456, 33.45678 (±15м)» или «GPS: нет». */
    fun format(coords: Coords?): String {
        if (coords == null) return "GPS: координаты не получены"
        return "GPS: ${coords.short()} (±${coords.accuracyM.toInt()}м)"
    }
}
