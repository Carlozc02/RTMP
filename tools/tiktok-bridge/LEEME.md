# Puente de chat de TikTok (para tu servidor)

## Con Docker (CasaOS u otro)
1. Copia esta carpeta (`tiktok-bridge`) a tu servidor.
2. Edita `docker-compose.yml` y pon tu usuario en `TIKTOK_USER`.
3. En una terminal del servidor, dentro de la carpeta:
   docker compose up -d --build
4. Ver si conectó:   docker compose logs -f
   - "Puente escuchando en ws://0.0.0.0:8787" = el puente está arriba.
   - "Conectado al live de @..." = TikTok conectó (solo pasa cuando estás en vivo).
   - Si no estás en vivo reintenta solo cada 15 s.
5. En la app, en la lista de chats, agrega:   ws://IP_DEL_SERVIDOR:8787

## Sin Docker (Node 18+)
   npm install
   node tiktok-bridge.js tio_carlo_zc 8787

## Seguridad
El puente no tiene contraseña. Úsalo dentro de tu red local o con una VPN
(Tailscale, WireGuard). No abras el puerto 8787 directo a internet.
