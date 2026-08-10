package com.jarvis.assistant

import android.content.Context
import org.json.JSONObject

object JarvisCommandParser {

    sealed class CommandResult {
        data class Executed(val outputMessage: String) : CommandResult()
        object None : CommandResult()
    }

    suspend fun parseAndExecute(context: Context, llmResponse: String): CommandResult {
        val regex = Regex("\\[JARVIS_CMD:(.*?)\\]", RegexOption.DOT_MATCHES_ALL)
        val match = regex.find(llmResponse) ?: return CommandResult.None

        val jsonStr = match.groupValues[1].trim()
        return try {
            val json = JSONObject(jsonStr)
            val action = json.optString("action", "").lowercase()
            val resultText = executeAction(context, action, json)
            CommandResult.Executed(resultText)
        } catch (e: Exception) {
            CommandResult.Executed("❌ Erreur d'exécution de la commande système : ${e.message}")
        }
    }

    private suspend fun executeAction(context: Context, action: String, json: JSONObject): String {
        return when (action) {
            "call" -> {
                val target = json.optString("target", "").ifBlank { json.optString("contact", "") }
                if (target.isBlank()) "❌ Aucun destinataire d'appel spécifié."
                else PhoneController.makeCall(context, target)
            }
            "end_call" -> PhoneController.endCall(context)
            "recent_calls" -> PhoneController.getRecentCalls(context, json.optInt("count", 10))

            "send_sms" -> {
                val to = json.optString("to", "").ifBlank { json.optString("contact", "") }
                val body = json.optString("message", "").ifBlank { json.optString("body", "") }
                if (to.isBlank() || body.isBlank()) "❌ Destinataire ou message SMS manquant."
                else SmsController.sendSms(context, to, body)
            }
            "read_sms" -> SmsController.readInboxSms(context, json.optInt("count", 5))
            "read_unread_sms" -> SmsController.readUnreadSms(context)

            "search_contact" -> {
                val name = json.optString("name", "").ifBlank { json.optString("query", "") }
                if (name.isBlank()) ContactsController.getContactList(context)
                else ContactsController.searchContacts(context, name)
            }
            "add_contact" -> {
                val name = json.optString("name", "")
                val phone = json.optString("phone", "")
                val email = json.optString("email", "")
                if (name.isBlank() || phone.isBlank()) "❌ Nom ou numéro manquant pour le contact."
                else ContactsController.addContact(context, name, phone, email)
            }

            "play_music" -> {
                val query = json.optString("query", "")
                MediaController.playMusic(context, query)
            }
            "pause_music" -> MediaController.pauseMusic(context)
            "resume_music" -> MediaController.resumeMusic(context)
            "stop_music" -> MediaController.stopMusic(context)
            "next_track" -> MediaController.nextTrack(context)
            "set_volume" -> {
                val level = json.optInt("level", 5)
                MediaController.setVolume(context, level)
            }

            "today_events" -> CalendarController.getTodayEvents(context)
            "upcoming_events" -> CalendarController.getUpcomingEvents(context, json.optInt("days", 7))
            "create_event" -> {
                val title = json.optString("title", "Événement")
                val start = json.optLong("startTime", System.currentTimeMillis() + 3600000)
                val end = json.optLong("endTime", start + 3600000)
                val desc = json.optString("description", "")
                val loc = json.optString("location", "")
                CalendarController.createEvent(context, title, start, end, desc, loc)
            }

            "read_emails" -> EmailController.readInbox(context, json.optInt("count", 5))
            "read_unread_emails" -> EmailController.readUnread(context)
            "send_email" -> {
                val to = json.optString("to", "")
                val subject = json.optString("subject", "")
                val body = json.optString("body", "")
                if (to.isBlank()) "❌ Adresse email destinataire manquante."
                else EmailController.sendEmail(context, to, subject, body)
            }

            "list_files" -> {
                val path = json.optString("path", "/sdcard")
                StorageController.listFiles(context, path)
            }
            "search_files" -> {
                val query = json.optString("query", "")
                StorageController.searchFiles(context, query)
            }
            "storage_info" -> StorageController.getStorageInfo(context)

            "get_notifications" -> JarvisNotificationListenerService.getRecent(json.optInt("count", 5))

            "get_location" -> {
                var res = ""
                LocationController.getLastKnownLocation(context) { res = it }
                res.ifBlank { "📍 Recherche de localisation lancée..." }
            }
            "open_maps" -> {
                val query = json.optString("query", "")
                LocationController.openMaps(context, query)
            }

            "bluetooth_info" -> BluetoothController.getPairedDevices(context)
            "enable_bluetooth" -> BluetoothController.enableBluetooth(context)

            else -> "❌ Commande système inconnue : « $action »."
        }
    }

    fun cleanResponse(llmResponse: String): String {
        return llmResponse.replace(Regex("\\[JARVIS_CMD:.*?\\]", RegexOption.DOT_MATCHES_ALL), "").trim()
    }
}
