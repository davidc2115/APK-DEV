package com.jarvis.assistant

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.provider.MediaStore

object MediaController {

    private var mediaPlayer: MediaPlayer? = null
    private var lastPlaylist: List<Pair<String, Uri>> = emptyList()
    private var currentTrackIndex = -1

    fun playMusic(context: Context, query: String): String {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST
        )

        val selection = "${MediaStore.Audio.Media.TITLE} LIKE ? OR ${MediaStore.Audio.Media.ARTIST} LIKE ?"
        val selectionArgs = arrayOf("%$query%", "%$query%")

        return try {
            val cursor = context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${MediaStore.Audio.Media.TITLE} ASC"
            )

            cursor?.use { c ->
                if (c.count == 0) return "🎵 Aucune musique trouvée pour la recherche « $query »."

                val playlist = mutableListOf<Pair<String, Uri>>()
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val title = c.getString(1) ?: "Inconnu"
                    val artist = c.getString(2) ?: "Artiste inconnu"
                    val contentUri = Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id.toString())
                    playlist.add("$title — $artist" to contentUri)
                }

                lastPlaylist = playlist
                currentTrackIndex = 0
                val (trackInfo, uri) = playlist[0]

                stopMusic(context)
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(context, uri)
                    prepare()
                    start()
                }

                "▶️ Lecture en cours : **$trackInfo**"
            } ?: "❌ Impossible d'accéder aux fichiers audio."
        } catch (e: Exception) {
            "❌ Erreur lors de la lecture audio : ${e.message}"
        }
    }

    fun stopMusic(context: Context): String {
        return try {
            mediaPlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
            mediaPlayer = null
            "⏹️ Lecture arrêtée."
        } catch (e: Exception) {
            "❌ Erreur lors de l'arrêt audio : ${e.message}"
        }
    }

    fun pauseMusic(context: Context): String {
        return try {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.pause()
                    "⏸️ Lecture mise en pause."
                } else {
                    "ℹ️ Aucune musique en cours de lecture."
                }
            } ?: "ℹ️ Aucune musique en cours de lecture."
        } catch (e: Exception) {
            "❌ Erreur de pause : ${e.message}"
        }
    }

    fun resumeMusic(context: Context): String {
        return try {
            mediaPlayer?.let {
                if (!it.isPlaying) {
                    it.start()
                    "▶️ Reprise de la lecture."
                } else {
                    "ℹ️ La musique est déjà en cours de lecture."
                }
            } ?: "ℹ️ Aucune musique à reprendre."
        } catch (e: Exception) {
            "❌ Erreur de reprise : ${e.message}"
        }
    }

    fun setVolume(context: Context, level: Int): String {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return "❌ Impossible d'accéder aux paramètres audio."

        return try {
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val targetVolume = level.coerceIn(0, maxVolume)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, AudioManager.FLAG_SHOW_UI)
            "🔊 Volume défini à **$targetVolume/$maxVolume**."
        } catch (e: Exception) {
            "❌ Erreur de modification du volume : ${e.message}"
        }
    }

    fun getVolume(context: Context): String {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return "❌ Service audio non disponible."

        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return "🔊 Volume multimédia actuel : **$current/$max**."
    }

    fun nextTrack(context: Context): String {
        if (lastPlaylist.isEmpty()) return "❌ Aucune liste de lecture active."
        currentTrackIndex = (currentTrackIndex + 1) % lastPlaylist.size
        val (trackInfo, uri) = lastPlaylist[currentTrackIndex]

        stopMusic(context)
        return try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(context, uri)
                prepare()
                start()
            }
            "⏭️ Piste suivante : **$trackInfo**"
        } catch (e: Exception) {
            "❌ Impossible de lire la piste suivante : ${e.message}"
        }
    }

    fun playVideo(context: Context, query: String): String {
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.TITLE
        )

        val selection = "${MediaStore.Video.Media.TITLE} LIKE ?"
        val selectionArgs = arrayOf("%$query%")

        return try {
            val cursor = context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null
            )

            cursor?.use { c ->
                if (c.moveToFirst()) {
                    val id = c.getLong(0)
                    val title = c.getString(1) ?: "Vidéo"
                    val contentUri = Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id.toString())

                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(contentUri, "video/*")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    "🎬 Lancement de la vidéo : **$title**"
                } else {
                    "🎬 Aucune vidéo trouvée correspondant à « $query »."
                }
            } ?: "❌ Échec de la recherche de vidéo."
        } catch (e: Exception) {
            "❌ Erreur lors du lancement de la vidéo : ${e.message}"
        }
    }

    fun getRecentMedia(context: Context, type: String = "audio", count: Int = 5): String {
        val uri = if (type == "video") MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val titleCol = if (type == "video") MediaStore.Video.Media.TITLE else MediaStore.Audio.Media.TITLE
        val dateCol = if (type == "video") MediaStore.Video.Media.DATE_ADDED else MediaStore.Audio.Media.DATE_ADDED

        return try {
            val cursor = context.contentResolver.query(
                uri,
                arrayOf(titleCol),
                null,
                null,
                "$dateCol DESC"
            )

            cursor?.use { c ->
                if (c.count == 0) return "📁 Aucun média trouvé."

                val sb = StringBuilder("📁 **Fichiers ${if (type == "video") "vidéo" else "audio"} récents** :\n\n")
                var idx = 0
                while (c.moveToNext() && idx < count) {
                    val title = c.getString(0) ?: "Média"
                    sb.append("${idx + 1}. **$title**\n")
                    idx++
                }
                sb.toString()
            } ?: "❌ Erreur de lecture des médias."
        } catch (e: Exception) {
            "❌ Erreur : ${e.message}"
        }
    }
}
