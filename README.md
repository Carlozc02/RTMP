# RTMP Streamer (tipo Larix, gratis)

App Android que transmite cámara y micrófono a cualquier servidor RTMP/RTMPS
(Twitch, YouTube, Kick, nginx-rtmp, MediaMTX, OBS Studio server, etc.).

Usa la librería open source RootEncoder (Apache 2.0). Sin anuncios, sin límites.

## Funciones
- URL RTMP/RTMPS con clave de stream (se guarda sola)
- Resolución: 1080p / 720p / 480p / 360p, FPS: 15–60
- Bitrate ajustable, incluso en vivo
- Cámara frontal/trasera, pantalla siempre encendida
- **Doble cámara**: trasera y delantera a la vez (una grande y la otra en recuadro abajo a la derecha). Requiere Android 11+ y un teléfono que soporte cámaras concurrentes. "Cambiar cámara" intercambia cuál es la grande.
- **Chat en pantalla + voz**: pega un link por línea (Twitch, Kick, YouTube). Overlay solo visible en tu pantalla (NO sale en la transmisión) y lectura en voz alta con el TTS del teléfono. TikTok (no oficial, puede fallar). Los menús son laterales: se deslizan desde el borde izquierdo (transmisión) y derecho (chats), o con los botones ☰ y 💬.
- **Sigue transmitiendo al minimizar**: usa un servicio en primer plano con notificación ("Transmitiendo en vivo"); puedes abrir el navegador u otras apps sin cortar la cámara. Toca la notificación para volver. El chat/voz también siguen activos.

## Chats: qué pegar y cómo funciona cada uno
Solo se LEE el chat público: no se pide usuario ni contraseña.
- Twitch: `https://twitch.tv/canal`
- Kick: `https://kick.com/canal` (o `kick.com/popout/canal/chat`). Si Kick bloquea, `kick:canal:ID`
  (el ID sale de `kick.com/api/v2/channels/canal`, campo `"chatroom":{"id":...}`).
- YouTube: `https://www.youtube.com/@canal` o el link del directo. Solo hay chat MIENTRAS estás en vivo.
- TikTok: `https://www.tiktok.com/@usuario/live`. Solo hay chat MIENTRAS estás en vivo.
  Si falla en el teléfono, corre `tools/tiktok-bridge` en una PC/servidor y pega `ws://IP:8787`.

