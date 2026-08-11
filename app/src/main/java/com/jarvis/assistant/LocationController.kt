package com.jarvis.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import androidx.core.content.ContextCompat
import java.util.Locale

object LocationController {

    private var activeLocationListener: LocationListener? = null

    fun getLastKnownLocation(context: Context, callback: (String) -> Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            callback("❌ Permission de géolocalisation non accordée.")
            return
        }

        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (locationManager == null) {
            callback("❌ Service de localisation indisponible.")
            return
        }

        try {
            val gpsLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val networkLocation = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

            val bestLocation = when {
                gpsLocation != null && networkLocation != null -> {
                    if (gpsLocation.time > networkLocation.time) gpsLocation else networkLocation
                }
                gpsLocation != null -> gpsLocation
                networkLocation != null -> networkLocation
                else -> null
            }

            if (bestLocation != null) {
                val address = reverseGeocode(context, bestLocation.latitude, bestLocation.longitude)
                callback("📍 **Position actuelle** :\n• Latitude: ${bestLocation.latitude}\n• Longitude: ${bestLocation.longitude}\n• Précision: ${bestLocation.accuracy}m\n• Adresse: $address")
            } else {
                callback("📍 Emplacement récent non trouvé. Activation de la recherche...")
                startLocationUpdates(context) { updatedMsg ->
                    callback(updatedMsg)
                    stopLocationUpdates(context)
                }
            }
        } catch (e: Exception) {
            callback("❌ Erreur lors de la récupération de la position : ${e.message}")
        }
    }

    fun reverseGeocode(context: Context, lat: Double, lon: Double): String {
        return try {
            @Suppress("DEPRECATION")
            val geocoder = Geocoder(context, Locale.FRENCH)
            @Suppress("DEPRECATION")
            val addresses = geocoder.getFromLocation(lat, lon, 1)
            if (!addresses.isNullOrEmpty()) {
                val addr = addresses[0]
                val addressLines = (0..addr.maxAddressLineIndex).map { addr.getAddressLine(it) }
                addressLines.joinToString(", ")
            } else {
                "Adresse non trouvée ($lat, $lon)"
            }
        } catch (e: Exception) {
            "Coordonnées: $lat, $lon"
        }
    }

    fun startLocationUpdates(context: Context, callback: (String) -> Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            callback("❌ Permission de géolocalisation requise.")
            return
        }

        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return

        activeLocationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val address = reverseGeocode(context, location.latitude, location.longitude)
                callback("📍 **Nouvelle position reçue** :\n• Latitude: ${location.latitude}\n• Longitude: ${location.longitude}\n• Précision: ${location.accuracy}m\n• Adresse: $address")
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5000L, 10f, activeLocationListener!!)
            } else if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 10f, activeLocationListener!!)
            }
        } catch (e: Exception) {
            callback("❌ Impossible de démarrer la mise à jour de localisation : ${e.message}")
        }
    }

    fun stopLocationUpdates(context: Context) {
        activeLocationListener?.let {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            locationManager?.removeUpdates(it)
            activeLocationListener = null
        }
    }

    fun isGpsEnabled(context: Context): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
    }

    fun openMaps(context: Context, query: String): String {
        return try {
            val gmmIntentUri = Uri.parse("geo:0,0?q=" + Uri.encode(query))
            val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri).apply {
                setPackage("com.google.android.apps.maps")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(mapIntent)
            "🗺️ Ouverture de Google Maps pour « $query »..."
        } catch (e: Exception) {
            "❌ Échec de l'ouverture des cartes : ${e.message}"
        }
    }
}
