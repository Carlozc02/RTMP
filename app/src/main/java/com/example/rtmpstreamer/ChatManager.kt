package com.example.rtmpstreamer

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.webkit.WebView
import android.webkit.WebViewClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Lee los chats de varias plataformas y (opcionalmente) los lee en voz alta.
 * Una línea por chat. Acepta links o el formato corto:
 *   https://twitch.tv/canal           |  twitch:canal
 *   https://kick.com/canal            |  https://kick.com/popout/canal/chat  |  kick:canal:IDchatroom
 *   https://youtube.com/@canal        |  https://youtube.com/watch?v=ID      |  youtube:ID
 *   https://tiktok.com/@usuario/live  |  tiktok:usuario
 *   ws://IP:8787                      (puente de TikTok en tu PC/servidor, ver tools/tiktok-bridge)
 *
 * Ninguna plataforma pide usuario ni contraseña: solo se LEE el chat público.
 */
class ChatManager(private val ctx: Context, private val onLine: (String) -> Unit) : TextToSpeech.OnInitListener {

    private val ui = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS)
        .build()
    private val sockets = CopyOnWriteArrayList<WebSocket>()
    private val tiktokClients = CopyOnWriteArrayList<Any>()
    private val webViews = CopyOnWriteArrayList<WebView>()
    private val tts = TextToSpeech(ctx, this)
    private var ttsReady = false
    private var pending = 0

    @Volatile
    var speak = false

    @Volatile
    private var gen = 0   // sube en cada desconexión para detener hilos y callbacks viejos

    private val ua = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

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
        sys("Chats v8")
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
                "bridge" -> connectBridge(value)
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
            lower.startsWith("ws://") || lower.startsWith("wss://") -> "bridge" to l
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
        val wv = webViews.toList()
        webViews.clear()
        ui.post { wv.forEach { try { it.stopLoading(); it.destroy() } catch (_: Throwable) {} } }
        if (ttsReady) tts.stop()
        synchronized(this) { pending = 0 }
    }

    fun shutdown() {
        disconnect()
        tts.shutdown()
        client.dispatcher.executorService.shutdown()
    }

    // ------------------------------------------------------------------
    // Utilidades HTTP
    // ------------------------------------------------------------------
    private fun httpGet(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", ua)
            .header("Accept-Language", "es-MX,es;q=0.9")
            .header("Cookie", "CONSENT=YES+1; SOCS=CAI")   // evita la pantalla de cookies de YouTube
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
            return resp.body?.string() ?: ""
        }
    }

    /**
     * Abre una URL que devuelve JSON dentro de un navegador interno (WebView) y entrega el texto.
     * Un navegador real suele pasar la protección anti-bots que bloquea a las peticiones directas.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun webFetch(url: String, cb: (String?) -> Unit) {
        ui.post {
            val wv = try { WebView(ctx) } catch (e: Throwable) { cb(null); return@post }
            webViews.add(wv)
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            wv.settings.userAgentString = ua
            var finished = false
            fun finish(r: String?) {
                if (finished) return
                finished = true
                webViews.remove(wv)
                try { wv.stopLoading(); wv.destroy() } catch (_: Throwable) {}
                cb(r)
            }
            fun poll(left: Int) {
                if (finished) return
                wv.evaluateJavascript("(function(){return document.body?document.body.innerText:''})()") { raw ->
                    val txt = try { JSONArray("[$raw]").getString(0) } catch (_: Exception) { "" }
                    if (txt.trimStart().startsWith("{")) finish(txt)
                    else if (left > 0) ui.postDelayed({ poll(left - 1) }, 1500)
                    else finish(null)
                }
            }
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, u: String?) { poll(8) }
            }
            ui.postDelayed({ finish(null) }, 25000)
            wv.loadUrl(url)
        }
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
    // Kick (Pusher). Primero pide el id del chat de forma directa; Kick suele
    // bloquear eso (HTTP 403), así que si falla lo pide con un navegador interno.
    // Último recurso: kick:canal:ID  (el id se saca de kick.com/api/v2/channels/canal)
    // ------------------------------------------------------------------
    private fun connectKick(value: String) {
        val myGen = gen
        val parts = value.split(":")
        val slug = parts[0].trim().lowercase()
        val manualId = parts.getOrNull(1)?.trim()?.toLongOrNull()
        if (manualId != null) {
            openKick(slug, manualId, myGen)
            return
        }
        Thread {
            try {
                openKick(slug, fetchKickChatroomId(slug), myGen)
            } catch (e: Exception) {
                sys("Kick: bloqueo directo (${e.message}), probando con navegador interno...")
                webFetch("https://kick.com/api/v2/channels/$slug") { json ->
                    if (gen != myGen) return@webFetch
                    val id = try { JSONObject(json ?: "").getJSONObject("chatroom").getLong("id") } catch (_: Exception) { null }
                    if (id != null) {
                        openKick(slug, id, myGen)
                    } else {
                        sys("Kick: no pude sacar el id del chat. Abre kick.com/api/v2/channels/$slug en el navegador, busca \"chatroom\":{\"id\":NÚMERO y pon  kick:$slug:NÚMERO")
                    }
                }
            }
        }.start()
    }

    private fun fetchKickChatroomId(slug: String): Long {
        val req = Request.Builder()
            .url("https://kick.com/api/v2/channels/$slug")
            .header("Accept", "application/json")
            .header("User-Agent", ua)
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
            val body = resp.body?.string() ?: throw Exception("respuesta vacía")
            return JSONObject(body).getJSONObject("chatroom").getLong("id")
        }
    }

    private fun openKick(slug: String, id: Long, myGen: Int) {
        if (gen != myGen) return
        val url = "wss://ws-us2.pusher.com/app/32cbd69e4b950bf97679" +
            "?protocol=7&client=js&version=8.4.0&flash=false"
        // Kick (Pusher) no contesta los pings de protocolo de OkHttp: se desactivan y se
        // manda el ping propio de Pusher cada 30 s.
        val kickClient = client.newBuilder().pingInterval(0, TimeUnit.SECONDS).build()
        var alive = true
        var socket: WebSocket? = null
        val pinger = object : Runnable {
            override fun run() {
                if (alive && gen == myGen) {
                    socket?.send("""{"event":"pusher:ping","data":{}}""")
                    ui.postDelayed(this, 30000)
                }
            }
        }
        val ws = kickClient.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    sys("Kick: socket abierto, esperando confirmación...")
                    ui.postDelayed(pinger, 30000)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val j = JSONObject(text)
                        when (j.optString("event")) {
                            "pusher:ping" -> webSocket.send("""{"event":"pusher:pong","data":{}}""")
                            "pusher:connection_established" -> webSocket.send(
                                """{"event":"pusher:subscribe","data":{"auth":"","channel":"chatrooms.$id.v2"}}"""
                            )
                            "pusher_internal:subscription_succeeded" ->
                                sys("Kick: conectado a $slug (chat $id), esperando mensajes")
                            "pusher:error" -> sys("Kick: error del servidor ${j.optString("data").take(120)}")
                            "App\\Events\\ChatMessageEvent" -> {
                                val d = JSONObject(j.getString("data"))
                                val user = d.getJSONObject("sender").getString("username")
                                push("K", user, d.getString("content"))
                            }
                        }
                    } catch (_: Exception) {}
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    alive = false
                    webSocket.close(1000, null)
                    if (gen == myGen) {
                        sys("Kick: el servidor cerró la conexión ($code ${reason.take(60)}), reconectando...")
                        ui.postDelayed({ openKick(slug, id, myGen) }, 5000)
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    alive = false
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    alive = false
                    if (gen == myGen) {
                        sys("Kick $slug: se cortó (${t.message}), reconectando...")
                        ui.postDelayed({ openKick(slug, id, myGen) }, 5000)
                    }
                }
            }
        )
        socket = ws
        sockets.add(ws)
    }

    // ------------------------------------------------------------------
    // YouTube (chat en vivo sin API key ni login, leyendo la misma página que usa
    // el navegador). Método no oficial. Solo hay chat MIENTRAS estás en vivo.
    // ------------------------------------------------------------------
    private fun resolveYoutubeVideoId(src: String): String {
        Regex("^[A-Za-z0-9_-]{11}$").matchEntire(src)?.let { return it.value }
        Regex("""(?:[?&]v=|youtu\.be/|/live/|/embed/)([A-Za-z0-9_-]{11})""").find(src)?.let { return it.groupValues[1] }
        // Link de canal (@canal, /channel/ID...): se abre su /live, que apunta al directo actual
        var url = if (src.startsWith("http")) src else "https://www.youtube.com/$src"
        url = url.substringBefore("?").trimEnd('/')
        if (!url.endsWith("/live")) url += "/live"
        val html = httpGet(url)
        Regex("""rel="canonical"\s+href="https://www\.youtube\.com/watch\?v=([A-Za-z0-9_-]{11})"""")
            .find(html)?.let { return it.groupValues[1] }
        throw Exception("ese canal no está en vivo ahora (el chat solo existe mientras transmites)")
    }

    /** Saca de la página el primer "continuation" del chat, leyendo ytInitialData. */
    private fun youtubeContinuation(html: String): String? {
        val m = Regex("""ytInitialData"?\]?\s*=\s*\{""").find(html)
        if (m != null) {
            val start = m.range.last
            var depth = 0
            var inStr = false
            var esc = false
            var end = -1
            for (k in start until html.length) {
                val c = html[k]
                if (inStr) {
                    if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false
                } else when (c) {
                    '"' -> inStr = true
                    '{' -> depth++
                    '}' -> { depth--; if (depth == 0) { end = k; break } }
                }
            }
            if (end > 0) {
                try {
                    val c0 = JSONObject(html.substring(start, end + 1))
                        .optJSONObject("contents")?.optJSONObject("liveChatRenderer")
                        ?.optJSONArray("continuations")?.optJSONObject(0)
                    val data = c0?.optJSONObject("invalidationContinuationData")
                        ?: c0?.optJSONObject("timedContinuationData")
                        ?: c0?.optJSONObject("reloadContinuationData")
                    data?.optString("continuation")?.takeIf { it.isNotEmpty() }?.let { return it }
                } catch (_: Exception) {}
            }
        }
        return Regex(""""continuation":"([^"]+)"""").find(html)?.groupValues?.get(1)
    }

    private fun connectYoutube(src: String) {
        val myGen = gen
        Thread {
            try {
                sys("YouTube: buscando directo...")
                val videoId = resolveYoutubeVideoId(src)
                val html = httpGet("https://www.youtube.com/live_chat?is_popout=1&v=$videoId")
                val apiKey = Regex(""""INNERTUBE_API_KEY":"([^"]+)"""").find(html)?.groupValues?.get(1)
                    ?: "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
                val version = Regex(""""INNERTUBE_CONTEXT_CLIENT_VERSION":"([^"]+)"""").find(html)?.groupValues?.get(1)
                    ?: "2.20250101.00.00"
                var cont = youtubeContinuation(html)
                    ?: throw Exception("el directo $videoId no tiene chat disponible (¿chat desactivado?)")
                sys("YouTube: conectado ($videoId)")

                val seen = HashSet<String>()
                var first = true   // el primer lote es historial: no se lee ni se muestra
                while (gen == myGen) {
                    val body = JSONObject()
                        .put("context", JSONObject().put("client",
                            JSONObject().put("clientName", "WEB").put("clientVersion", version)))
                        .put("continuation", cont)
                    val req = Request.Builder()
                        .url("https://www.youtube.com/youtubei/v1/live_chat/get_live_chat?key=$apiKey")
                        .header("User-Agent", ua)
                        .header("Cookie", "CONSENT=YES+1; SOCS=CAI")
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
                if (gen == myGen) sys("YouTube: ${e.message ?: e.javaClass.simpleName}")
            }
        }.start()
    }

    // ------------------------------------------------------------------
    // TikTok LIVE (no oficial) con la librería TikTok-Live-Java, por reflexión
    // para que el proyecto compile aunque la librería cambie. Solo hay chat
    // MIENTRAS estás en vivo. Si no funciona en Android, usa el puente (ws://).
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
                val tried = listOf(
                    "io.github.jwdeveloper.tiktok.TikTokLive",
                    "io.github.jwdeveloper.tiktok.live.TikTokLive",
                    "io.github.jwdeveloper.tiktok.client.TikTokLive"
                )
                val cls = tried.firstNotNullOfOrNull { runCatching { Class.forName(it) }.getOrNull() }
                    ?: throw Exception("la librería de TikTok no se incluyó en la app (clase TikTokLive no encontrada)")

                val newClient = cls.getMethod("newClient", String::class.java)
                var builder: Any = newClient.invoke(null, user)!!
                val builderType = newClient.returnType
                val onComment = builderType.methods.first { it.name == "onComment" && it.parameterCount == 1 }
                val handlerType = onComment.parameterTypes[0]

                val handler = Proxy.newProxyInstance(handlerType.classLoader, arrayOf(handlerType)) { proxy, method, args ->
                    when (method.name) {
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy === args?.firstOrNull()
                        "toString" -> "TikTokCommentHandler"
                        else -> {
                            try {
                                val event = args?.lastOrNull()
                                if (event != null) {
                                    val u = call(event, "getUser")
                                    val name = u?.let { call(it, "getName") } as? String
                                    val text = call(event, "getText") as? String
                                    if (name != null && !text.isNullOrEmpty()) push("TT", name, text)
                                }
                            } catch (_: Throwable) {}
                            null
                        }
                    }
                }
                builder = onComment.invoke(builder, handler) ?: builder

                val connect = builderType.methods.first { it.name == "buildAndConnect" && it.parameterCount == 0 }
                val client = connect.invoke(builder) ?: throw Exception("no se pudo crear el cliente")
                if (gen != myGen) {
                    stopTiktok(client)
                } else {
                    tiktokClients.add(client)
                    sys("TikTok: conectado a @$user (si no llegan mensajes, ¿estás en vivo?)")
                }
            } catch (e: Throwable) {
                val c = (e as? InvocationTargetException)?.targetException ?: e
                if (gen == myGen) {
                    sys("TikTok @$user: ${c.javaClass.simpleName}: ${c.message ?: "sin detalle"}")
                    sys("TikTok: si sigue fallando usa el puente (ws://IP:8787), ver README")
                }
            }
        }.start()
    }

    private fun call(target: Any, name: String): Any? {
        val m: Method = target.javaClass.methods.first { it.name == name && it.parameterCount == 0 }
        m.isAccessible = true
        return m.invoke(target)
    }

    private fun stopTiktok(client: Any) {
        try { client.javaClass.getMethod("disconnect").invoke(client) } catch (_: Throwable) {}
    }

    // ------------------------------------------------------------------
    // Puente: un WebSocket (en tu PC o servidor) que manda {"user":"..","text":".."}
    // ------------------------------------------------------------------
    private fun connectBridge(url: String) {
        val ws = client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    sys("Puente: conectado a $url")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val j = JSONObject(text)
                        val msg = j.optString("text")
                        if (msg.isNotEmpty()) push("TT", j.optString("user", "?"), msg)
                    } catch (_: Exception) {}
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    sys("Puente $url: error (${t.message})")
                }
            }
        )
        sockets.add(ws)
    }
}
