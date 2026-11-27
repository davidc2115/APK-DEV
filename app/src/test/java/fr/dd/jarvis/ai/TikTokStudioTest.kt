package fr.dd.jarvis.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class TikTokStudioTest {
    // ------------------------------------------------------------------ tikwm : lecture des réponses

    private val posts = JSONObject("""
        {"code":0,"msg":"success","data":{"videos":[
          {"video_id":"7300000000000000001","title":"Nettoyage de panneaux solaires 😮 #solaire #photovoltaique","duration":23,"play":"https://v.example/1.mp4",
           "play_count":152000,"digg_count":9800,"comment_count":120,"share_count":340,"collect_count":510,"create_time":1764000000,
           "music_info":{"title":"son original","author":"elec.pro","original":true},"author":{"unique_id":"elec.pro","nickname":"Élec Pro"}},
          {"video_id":"7300000000000000002","title":"3 erreurs sur ton onduleur #onduleur #solaire","duration":41,
           "play_count":8000,"digg_count":400,"comment_count":30,"share_count":12,"collect_count":40,"create_time":1764300000,
           "music_info":{"title":"Espresso","author":"Sabrina Carpenter","original":false},"author":{"unique_id":"elec.pro"}}
        ],"cursor":"1764300000000","hasMore":true}}
    """.trimIndent())

    @Test fun lectureDesVideosTikwm() {
        val v = TikWm.videos(posts)
        assertEquals(2, v.size)
        assertEquals("7300000000000000001", v[0].id)
        assertEquals(152000L, v[0].vues)
        assertEquals("elec.pro", v[0].auteur)
        assertEquals("https://www.tiktok.com/@elec.pro/video/7300000000000000001", v[0].lien)
        assertTrue(v[0].musiqueOriginale)
        assertEquals("Espresso — Sabrina Carpenter", v[1].musique)
        // « data » directement sous forme de liste (fil des tendances).
        val liste = JSONObject("""{"code":0,"data":[{"video_id":"1","title":"a","play_count":5}]}""")
        assertEquals(1, TikWm.videos(liste).size)
    }

    @Test fun lectureCompteEtHashtags() {
        val c = TikWm.compte(JSONObject("""{"code":0,"data":{"user":{"uniqueId":"elec.pro","nickname":"Élec Pro","signature":"Électricien solaire","verified":true},
            "stats":{"followerCount":25400,"heartCount":812000,"videoCount":143}}}"""))
        assertEquals("elec.pro", c.pseudo); assertEquals(25400L, c.abonnes); assertEquals(143L, c.videos); assertTrue(c.verifie)
        val h = TikWm.hashtags(JSONObject("""{"code":0,"data":{"challenge_list":[{"id":"1","cha_name":"photovoltaique","user_count":5300,"view_count":91000000}]}}"""))
        assertEquals("photovoltaique", h.single().nom); assertEquals(91000000L, h.single().vues)
    }

    @Test fun pseudoDepuisSaisie() {
        assertEquals("elec.pro", TikWm.pseudo("@elec.pro"))
        assertEquals("elec.pro", TikWm.pseudo("https://www.tiktok.com/@elec.pro?lang=fr"))
        assertEquals("elec.pro", TikWm.pseudo("https://www.tiktok.com/@elec.pro/video/123"))
        assertEquals(listOf("a.b", "c_d", "e"), TikTokPropositions.comptes("@a.b, c_d ; https://www.tiktok.com/@e"))
    }

    // ------------------------------------------------------------------ calculs

    @Test fun bilanChiffre() {
        val pubs = TikWm.videos(posts).map(TikTokAnalyse::depuisTikWm)
        val b = TikTokAnalyse.bilan(pubs, ZoneId.of("Europe/Paris"))
        assertTrue(b, b.contains("2 vidéos"))
        assertTrue(b, b.contains("médiane 80 k"))   // (152 000 + 8 000) / 2
        assertTrue(b, b.contains("**Durée**") && b.contains("15 à 30 s : 152 k") && b.contains("30 à 60 s : 8 k"))
        assertTrue(b, b.contains("#solaire : 80 k (2)"))
    }

    @Test fun formatsEtOutils() {
        assertEquals("950", TikTokAnalyse.nombre(950))
        assertEquals("1,2 k", TikTokAnalyse.nombre(1234))
        assertEquals("2,5 M", TikTokAnalyse.nombre(2_500_000))
        assertEquals("2 M", TikTokAnalyse.nombre(2_000_000))
        assertEquals(listOf("solaire", "photovoltaique"), TikTokAnalyse.hashtags("Top #Solaire et #photovoltaique #solaire"))
        assertEquals("Nettoyage de panneaux solaires 😮", TikTokAnalyse.accroche("Nettoyage de panneaux solaires 😮 #solaire #photovoltaique"))
        assertEquals(5L, TikTokAnalyse.mediane(listOf(9, 1, 5)))
        assertEquals("soirée (18 h-22 h)", TikTokAnalyse.creneau(19))
    }

    @Test fun tendancesHashtagsEtSons() {
        val t = TikTokAnalyse.tendances(TikWm.videos(posts), "Tendances France")
        assertTrue(t, t.contains("#solaire : 2 vidéo(s)"))
        // Le son original d'un créateur n'est pas une « tendance » ; un son repris oui.
        assertTrue(t, t.contains("Espresso — Sabrina Carpenter : 1 vidéo(s)") && !t.contains("son original — elec.pro :"))
    }

    // ------------------------------------------------------------------ sous-titres

    @Test fun sousTitresCalesSurLaVoix() {
        val p = SousTitres.phrases("Tes panneaux perdent 20 % de rendement. Voici pourquoi ! Et comment l'éviter.")
        assertEquals(listOf("Tes panneaux perdent 20 % de rendement.", "Voici pourquoi !", "Et comment l'éviter."), p)
        val r = SousTitres.reperes(p, listOf(3000, 1000, 1500))
        assertEquals(0L, r.first().debutMs)
        assertEquals(5500L, r.last().finMs)
        // « Tes panneaux perdent 20 » / « % de rendement. » : 4 mots au plus, jamais un mot seul à la fin.
        assertTrue(r.toString(), r.all { it.texte.split(' ').size <= 5 } && r.none { it.texte == "rendement." })
        assertEquals("Voici pourquoi !", SousTitres.a(r, 3500)?.texte)
        assertNull(SousTitres.a(r, 6000))
        // Les repères se suivent sans trou ni chevauchement.
        r.zipWithNext().forEach { (a, b) -> assertEquals(a.finMs, b.debutMs) }
    }

    @Test fun phraseTropLongueCoupeeAuxVirgules() {
        val longue = "Quand tu nettoies tes panneaux solaires au printemps, pense à couper l'onduleur, à vérifier les fixations, à regarder les câbles et surtout à ne jamais utiliser d'eau calcaire sur le verre."
        assertTrue(SousTitres.phrases(longue).size >= 2)
    }

    // ------------------------------------------------------------------ plans libres de droits

    @Test fun choixDuFichierPexelsEtPixabay() {
        val v = JSONObject("""{"id":1,"duration":12,"video_files":[
            {"link":"https://p/uhd.mp4","file_type":"video/mp4","width":2160,"height":3840},
            {"link":"https://p/hd.mp4","file_type":"video/mp4","width":720,"height":1280},
            {"link":"https://p/land.mp4","file_type":"video/mp4","width":1280,"height":720}]}""")
        assertEquals("https://p/hd.mp4", Stock.choisirPexels(v, vertical = true))
        assertEquals("https://p/land.mp4", Stock.choisirPexels(v, vertical = false))
        val h = JSONObject("""{"videos":{"large":{"url":"https://x/l.mp4"},"medium":{"url":"https://x/m.mp4"}}}""")
        assertEquals("https://x/m.mp4", Stock.choisirPixabay(h))
        assertEquals("solar panels roof technician", Stock.motsCles("Cinematic close up shot of solar panels on a roof, technician cleaning, golden light"))
    }

    // ------------------------------------------------------------------ envoi en brouillon et propositions hebdomadaires

    @Test fun decoupageEnvoiTikTok() {
        assertEquals(3_000_000L to 1, TikTok.decoupage(3_000_000))
        assertEquals(60L * 1024 * 1024 to 1, TikTok.decoupage(60L * 1024 * 1024))
        val (morceau, n) = TikTok.decoupage(95L * 1024 * 1024)
        assertEquals(10L * 1024 * 1024, morceau); assertEquals(9, n)   // le dernier morceau absorbe le reste (15 Mo)
    }

    @Test fun propositionsLeLundiUneFoisParSemaine() {
        val lundi8h = LocalDateTime.of(2026, 11, 30, 8, 0)
        val lundi10h = LocalDateTime.of(2026, 11, 30, 10, 0)
        val mercredi = LocalDateTime.of(2026, 12, 2, 14, 0)
        val semaine = TikTokPropositions.semaine(lundi10h.toLocalDate())
        assertEquals("2026-W49", semaine)
        assertFalse(TikTokPropositions.aLancer(lundi8h, "2026-W48", true))
        assertTrue(TikTokPropositions.aLancer(lundi10h, "2026-W48", true))
        assertFalse(TikTokPropositions.aLancer(lundi10h, semaine, true))
        assertTrue(TikTokPropositions.aLancer(mercredi, "2026-W48", true))   // téléphone éteint lundi : rattrapé
        assertFalse(TikTokPropositions.aLancer(lundi10h, "2026-W48", false))
    }

    /** « The user did not authorize the scope required for completing this request » : profil sans statistiques. */
    @Test fun profilSansLeScopeStatistiques() {
        assertTrue(TikTok.refusScope("TikTok : The user did not authorize the scope required for completing this request."))
        assertFalse(TikTok.refusScope("TikTok 500 : erreur serveur"))
        val u = JSONObject().put("display_name", "Dd").put("sans_stats", true).put("videos_listees", 12).put("likes_videos", 340)
        assertEquals("Dd — 12 vidéos, 340 likes sur ces vidéos (abonnés : active « Abonnés et likes du profil » dans les réglages TikTok)", TikTok.resumeProfil(u))
        val complet = JSONObject().put("display_name", "Dd").put("follower_count", 250).put("likes_count", 1200).put("video_count", 12)
        assertEquals("Dd — 250 abonnés, 1200 likes, 12 vidéos", TikTok.resumeProfil(complet))
    }

    /** Montage « avec Pixabay » : Gemini Omni sans quota gratuit (« limit: 0 ») n'est plus retenté à chaque plan. */
    @Test fun quotaVideoNulEtRechercheEnFrancais() {
        assertTrue(VideoProviderGen.sansQuota("HTTP 429 : Rate limit exceeded for model gemini-omni-1.2-flash (limit: 0 requests per day)"))
        assertFalse(VideoProviderGen.sansQuota("HTTP 429 : Rate limit exceeded (limit: 10 requests per minute)"))
        assertEquals(listOf("patriotisme", "enraciné", "hommage"), Stock.motsClesFr("niche du compte : patriotisme enraciné, hommage aux forces vives"))
    }

    /** Vidéo « niche patriotique » : un rouge à lèvres pour « le feu ne meurt jamais » — plans hors sujet refusés. */
    @Test fun planLibreDeDroitsPertinent() {
        assertFalse(Stock.pertinent("phoenix fire", "lipstick, makeup, beauty woman applying lipstick 3373489"))
        assertTrue(Stock.pertinent("firefighters fire truck", "firefighter, fire, truck, emergency"))
        assertTrue(Stock.pertinent("french flag waving", "flag, france, tricolor, wind"))
        assertTrue(Stock.pertinent("pompiers", "pompier, incendie"))
        assertTrue(Stock.pertinent("farmer tractor", "12345"))   // rien de descriptif : on ne peut pas juger
    }

    @Test fun dureeLueDansUnWav() {
        val f = java.io.File.createTempFile("voix", ".wav")
        val donnees = ByteArray(44_100 * 2 * 3 / 2)   // 1,5 s en 16 bits mono 44,1 kHz
        val b = java.nio.ByteBuffer.allocate(44 + donnees.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + donnees.size).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(44_100).putInt(88_200).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(donnees.size).put(donnees)
        f.writeBytes(b.array())
        assertEquals(1500L, Montage.dureeWav(f))
        f.delete()
    }

    /** Voix naturelle dite d'un seul tenant : sous-titres répartis selon la longueur des phrases, sans trou. */
    @Test fun voixNaturelleEtSousTitres() {
        val p = listOf("Le Phénix renaît.", "L'esprit gaulois réfractaire ne s'agenouille jamais.")
        val d = SousTitres.repartir(p, 6000)
        assertEquals(6000L, d.sum())
        assertTrue(d.toString(), d[1] > d[0])
        val f = java.io.File.createTempFile("voix", ".wav")
        Montage.ecrireWav(ByteArray(48_000), 24_000, 1, f)   // 1 s en 16 bits mono 24 kHz
        assertEquals(1000L, Montage.dureeWav(f))
        f.delete()
    }
}
