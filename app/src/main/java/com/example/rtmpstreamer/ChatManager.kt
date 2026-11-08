package com.example.rtmpstreamer

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import io.github.jwdeveloper.tiktok.TikTokLive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Lee los chats de varias plataformas y (opcionalmente) los lee en voz alta.
 * Una línea por chat. Acepta links o el formato corto:
 *   https://twitch.tv/canal           |  twitch:canal
 *   https://kick.com/canal            |  kick:canal  (o kick:canal:IDchatroom si falla)
 *   https://youtube.com/watch?v=ID    |  https://youtube.com/@canal/live  |  youtube:ID
 *   https://tiktok.com/@usuario/live | tiktok:usuario   (no oficial, ver connectTiktok)
 */
class ChatManager(ctx: Context, private val onLine: (String) -> Unit) : TextToSpeech.OnInitListener {

    private val ui = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS)
        .build()
    private val sockets = CopyOnWriteArrayList<WebSocket>()
    private val tiktokClients = CopyOnWriteArrayList<Any>()
    private val tts = TextToSpeech(ctx, this)
    private var ttsReady = false
    private var pending = 0

    @Volatile
    var speak = false

    @Volatile
    private var gen = 0   // sube en cada desconexión para detener los hilos de YouTube

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            sys("No se pudo iniciar la voz (TTS) del teléfono")
            return
        }
        val r = tts.setLanguage(Locale("es", "MX"))
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.setLanguage(Locale.getDefault())
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = done()
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = done()
        })
        ttsReady = true
    }

    private fun done() {
        synchronized(this) { if (pending > 0) pending-- }
    }

    private fun sys(msg: String) {
        ui.post { onLine("• $msg") }
    }

    private fun push(tag: String, user: String, text: String) {
        ui.post { onLine("[$tag] $user: $text") }
        if (speak && ttsReady) speakMsg(user, text)
    }

    private fun speakMsg(user: String, text: String) {
        val clean = text.replace(Regex("https?://\\S+"), "enlace").trim()
        if (clean.isEmpty() || clean.startsWith("!")) return   // ignora comandos de bots
        synchronized(this) {
            if (pending >= 8) return                            // evita que se acumule la cola
            pending++
        }
        tts.speak("$user dice: ${clean.take(200)}", TextToSpeech.QUEUE_ADD, null, "m${System.nanoTime()}")
    }

    fun connect(lines: List<String>) {
        disconnect()
        for (raw in lines) {
            val l = raw.trim()
            if (l.isEmpty()) continue
            val spec = parseSpec(l)
            if (spec == null) {
                sys("No entendí: $l")
                continue
            }
            val (platform, value) = spec
            when (platform) {
                "twitch" -> connectTwitch(value)
                "kick" -> connectKick(value)
                "youtube" -> connectYoutube(value)
                "tiktok" -> connectTiktok(value)
                else -> sys("Plataforma desconocida: $platform")
            }
        }
    }

    /** Devuelve (plataforma, valor) a partir de un link o de "plataforma:valor". */
    private fun parseSpec(l: String): Pair<String, String>? {
        val lower = l.lowercase()
        fun channelFrom(host: String): String? {
            val path = l.substringAfter("$host/", "").substringBefore("?").trim('/').split("/")
            val first = path.firstOrNull() ?: return null
            val ch = if (first == "popout" || first == "embed") path.getOrNull(1) else first
            return ch?.takeIf { it.isNotBlank() }
        }
        return when {
            "twitch.tv" in lower -> channelFrom("twitch.tv")?.let { "twitch" to it }
            "kick.com" in lower -> channelFrom("kick.com")?.let { "kick" to it }
            "youtube.com" in lower || "youtu.be" in lower -> "youtube" to l
            "tiktok.com" in lower -> "tiktok" to l
            else -> {
                val parts = l.split(":", limit = 2)
                if (parts.size == 2 && parts[1].isNotBlank())
                    parts[0].trim().lowercase() to parts[1].trim().trimStart('#', '@')
                else null
            }
        }
    }

    fun disconnect() {
        gen++
        sockets.forEach { try { it.close(1000, null) } catch (_: Exception) {} }
        sockets.clear()
        tiktokClients.forEach { stopTiktok(it) }
        tiktokClients.clear()
        if (ttsReady) tts.stop()
        synchronized(this) { pending = 0 }
    }

    fun shutdown() {
        disconnect()
        tts.shutdown()
        client.dispatcher.executorService.shutdown()
    }

    // ------------------------------------------------------------------
    // Twitch (IRC por WebSocket, lectura anónima, sin cuenta ni token)
    // ------------------------------------------------------------------
    private fun connectTwitch(channel: String) {
        val ch = channel.lowercase()
        val ws = client.newWebSocket(
            Request.Builder().url("wss://irc-ws.chat.twitch.tv:443").build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("PASS SCHMOOPIIE")
                    webSocket.send("NICK justinfan${(10000..99999).random()}")
                    webSocket.send("JOIN #$ch")
                    sys("Twitch: conectado a $ch")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    text.split("\r\n").forEach { line ->
                        if (line.startsWith("PING")) {
                            webSocket.send("PONG :tmi.twitch.tv")
                        } else if (line.contains(" PRIVMSG #")) {
                            val user = line.substringAfter(":").substringBefore("!")
                            val msg = line.substringAfter(" PRIVMSG #$ch :", "")
                            if (msg.isNotEmpty()) push("T", user, msg)
                        }
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    sys("Twitch $ch: error (${t.message})")
                }
            }
        )
        sockets.add(ws)
    }

    // ------------------------------------------------------------------
    // TikTok LIVE (no oficial) con la librería TikTok-Live-Java. Depende de
    // que TikTok no cambie su protocolo y del servicio de firma de Euler
    // Stream que usa la librería internamente. Puede fallar sin aviso.
    // ------------------------------------------------------------------
    private fun connectTiktok(src: String) {
        val myGen = gen
        val user = Regex("@([A-Za-z0-9._]+)").find(src)?.groupValues?.get(1)
            ?: src.trim().trimStart('@')
        if (user.isBlank()) {
            sys("TikTok: no encontré el usuario en ese link")
            return
        }
        Thread {
            try {
                sys("TikTok: conectando a @$user ...")
                val client = TikTokLive.newClient(user)
                    .onComment { _, event -> push("TT", event.user.name, event.text) }
                    .buildAndConnect()
                if (gen != myGen) {            // se desconectó mientras conectaba
                    stopTiktok(client)
                } else {
                    tiktokClients.add(client)
                    sys("TikTok: conectado a @$user")
                }
            } catch (e: Throwable) {
                if (gen == myGen) sys("TikTok @$user: ${e.message ?: e.javaClass.simpleName} (¿está en vivo?)")
            }
        }.start()
    }

    private fun stopTiktok(client: Any) {
        try { client.javaClass.getMethod("disconnect").invoke(client) } catch (_: Throwable) {}
    }

    // ------------------------------------------------------------------
    // Kick (Pusher). Puede fallar porque Kick bloquea la consulta del id
    // del chatroom; en ese caso usa  kick:canal:ID
    // ------------------------------------------------------------------
    private fun connectKick(value: String) {
        Thread {
            try {
                val parts = value.split(":")
                val slug = parts[0].lowercase()
                val id: Long = parts.getOrNull(1)?.trim()?.toLongOrNull() ?: fetchKickChatroomId(slug)

                val url = "wss://ws-us2.pusher.com/app/32cbd69e4b950bf97679" +
                    "?protocol=7&client=js&version=8.4.0-rc2&flash=false"
                val ws = client.newWebSocket(
                    Request.Builder().url(url).build(),
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            webSocket.send(
                                """{"event":"pusher:subscribe","data":{"auth":"","channel":"chatrooms.$id.v2"}}"""
                            )
                            sys("Kick: conectado a $slug")
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            try {
                                val j = JSONObject(text)
                                when (j.optString("event")) {
                                    "pusher:ping" -> webSocket.send("""{"event":"pusher:pong","data":{}}""")
                                    "App\\Events\\ChatMessageEvent" -> {
                                        val d = JSONObject(j.getString("data"))
                                        val user = d.getJSONObject("sender").getString("username")
                                        push("K", user, d.getString("content"))
                                    }
                                }
                            } catch (_: Exception) {}
                        }

                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            sys("Kick $slug: error (${t.message})")
                        }
                    }
                )
                sockets.add(ws)
            } catch (e: Exception) {
                sys("Kick: ${e.message}")
            }
        }.start()
    }

    // ------------------------------------------------------------------
    // YouTube (chat en vivo sin API key, leyendo la misma página que usa
    // el navegador). Es un método no oficial: si YouTube cambia su página
    // puede dejar de funcionar.
    // ------------------------------------------------------------------
    private val ua = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"

    private fun httpGet(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", ua).header("Accept-Language", "es-MX,es;q=0.9").build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
            return resp.body?.string() ?: ""
        }
    }

    private fun resolveYoutubeVideoId(src: String): String {
        Regex("^[A-Za-z0-9_-]{11}$").matchEntire(src)?.let { return it.value }
        Regex("""(?:[?&]v=|youtu\.be/|/live/|/embed/)([A-Za-z0-9_-]{11})""").find(src)?.let { return it.groupValues[1] }
        // link de canal (@canal/live, /channel/ID/live): busca el video en vivo actual
        val url = if (src.startsWith("http")) src else "https://www.youtube.com/$src"
        val html = httpGet(url)
        Regex("""rel="canonical" href="https://www\.youtube\.com/watch\?v=([A-Za-z0-9_-]{11})""").find(html)?.let { return it.groupValues[1] }
        Regex(""""videoId":"([A-Za-z0-9_-]{11})"""").find(html)?.let { return it.groupValues[1] }
        throw Exception("no encontré un directo en ese link")
    }

    private fun connectYoutube(src: String) {
        val myGen = gen
        Thread {
            try {
                val videoId = resolveYoutubeVideoId(src)
                val html = httpGet("https://www.youtube.com/live_chat?is_popout=1&v=$videoId")
                val apiKey = Regex(""""INNERTUBE_API_KEY":"([^"]+)"""").find(html)?.groupValues?.get(1)
                    ?: throw Exception("no encontré el chat (¿el directo está activo?)")
                var cont = Regex(""""continuation":"([^"]+)"""").find(html)?.groupValues?.get(1)
                    ?: throw Exception("este directo no tiene chat abierto")
                sys("YouTube: conectado ($videoId)")

                val seen = HashSet<String>()
                var first = true   // el primer lote es historial: no se lee ni se muestra
                while (gen == myGen) {
                    val body = JSONObject()
                        .put("context", JSONObject().put("client",
                            JSONObject().put("clientName", "WEB").put("clientVersion", "2.20240101.00.00")))
                        .put("continuation", cont)
                    val req = Request.Builder()
                        .url("https://www.youtube.com/youtubei/v1/live_chat/get_live_chat?key=$apiKey")
                        .header("User-Agent", ua)
                        .post(body.toString().toRequestBody("application/json".toMediaType()))
                        .build()
                    val txt = client.newCall(req).execute().use { it.body?.string() ?: "" }
                    val lc = JSONObject(txt).optJSONObject("continuationContents")
                        ?.optJSONObject("liveChatContinuation") ?: throw Exception("el chat terminó")

                    val actions = lc.optJSONArray("actions")
                    if (actions != null) {
                        for (i in 0 until actions.length()) {
                            val item = actions.optJSONObject(i)
                                ?.optJSONObject("addChatItemAction")?.optJSONObject("item")
                                ?.optJSONObject("liveChatTextMessageRenderer") ?: continue
                            if (!seen.add(item.optString("id"))) continue
                            if (first) continue
                            val author = item.optJSONObject("authorName")?.optString("simpleText") ?: continue
                            val runs = item.optJSONObject("message")?.optJSONArray("runs") ?: continue
                            val msg = buildString {
                                for (j in 0 until runs.length()) append(runs.optJSONObject(j)?.optString("text") ?: "")
                            }.trim()
                            if (msg.isNotEmpty()) push("Y", author, msg)
                        }
                    }
                    first = false

                    val c0 = lc.optJSONArray("continuations")?.optJSONObject(0)
                    val next = c0?.optJSONObject("invalidationContinuationData")
                        ?: c0?.optJSONObject("timedContinuationData")
                        ?: c0?.optJSONObject("reloadContinuationData")
                        ?: throw Exception("el chat terminó")
                    cont = next.optString("continuation").ifEmpty { throw Exception("el chat terminó") }
                    Thread.sleep(next.optLong("timeoutMs", 3000).coerceIn(1000, 8000))
                }
            } catch (e: Exception) {
                if (gen == myGen) sys("YouTube: ${e.message}")
            }
        }.start()
    }

    private fun fetchKickChatroomId(slug: String): Long {
        val req = Request.Builder()
            .url("https://kick.com/api/v2/channels/$slug")
            .header("Accept", "application/json")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw Exception("no pude obtener el chatroom (HTTP ${resp.code}); usa kick:$slug:IDCHATROOM")
            }
            val body = resp.body?.string() ?: throw Exception("respuesta vacía")
            return JSONObject(body).getJSONObject("chatroom").getLong("id")
        }
    }
}
