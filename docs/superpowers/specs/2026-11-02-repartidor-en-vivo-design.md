# DomiRuta: repartidor en vivo y seguimiento del cliente (diseño)

Fecha: 2 de noviembre de 2026 · Entrega del proyecto: 14 de noviembre de 2026

## 1. Idea

Que el domicilio se maneje de punta a punta desde DomiRuta, sin "mensajes a ciegas":

**la caja despacha → el repartidor recibe la salida (notificación + WhatsApp) → acepta con el GPS
activo → sigue la ruta en un mapa → marca "Entregué" → el cliente ve por dónde va su pedido → la
caja ve todo en vivo y recibe avisos si algo se atrasa.**

Sin contraseñas: el repartidor entra con su enlace personal y el cliente con su enlace o su código.

## 2. Lo que ya existe (no se rehace)

- **Supabase** (`repartidor/*.sql`): salida con enlace secreto (≥ 24 caracteres), paradas, aceptar
  o "No puedo" con motivo, marcar entrega (entregado / no entregado / deshacer), foto de entrega,
  chat con la tienda con visto, "Regresando" automático y la última ubicación del repartidor
  (cada 30 s). Tablas cerradas: la página solo usa funciones (`rpc`).
- **Página del repartidor** (repositorio `domiruta-repartidor`, React + TypeScript + Vite, se
  publica sola en GitHub Pages): pantalla de aceptar, tarjetas de paradas, sección "Hechas",
  chat, foto y ubicación.
- **DomiRuta**: sube la salida, consulta cada 15 s, campana de avisos, mapa con la ubicación, ruta
  calculada con el grafo de calles (Dijkstra).

## 3. Decisiones tomadas

1. Mapa con **Leaflet + OpenStreetMap** (gratis, sin tarjeta). La ruta la calcula DomiRuta y se
   sube como lista de puntos. Google Maps / Waze solo se abren con un enlace para que guíen por voz.
2. **Sin contador corriendo.** La salida dice "Responde antes de las 10:30" y **vence a los 10
   minutos**. A los **3 minutos** sin respuesta, la campana de la caja avisa (Reenviar / Mandar con
   otro). Al vencer, la página ya no deja aceptar y la caja recibe el aviso.
3. **GPS obligatorio para aceptar.** Se explica antes de pedir el permiso; si lo bloqueó, pantalla con
   los pasos para activarlo (la página no puede volver a preguntar); continúa sola al activarlo.
4. **"Compartir mi ubicación":** se enciende al aceptar y se apaga solo al terminar. Se puede pausar
   en la ruta, pero la caja recibe el aviso y el cliente ve "Ubicación no disponible por ahora".
   Más de 2 min sin ubicación → aviso a la caja.
5. **La moto se pega a la calle:** se ajusta a la ruta (≤ 40 m); si se desvía, a la calle más cercana
   de la zona; se ignoran lecturas con error > 50 m y saltos imposibles; movimiento suave y girado
   según el rumbo. La casa del cliente se dibuja en la calle, no dentro de la manzana.
6. **"Disponible para salidas"** del repartidor. DomiRuta muestra Disponible / En ruta / No
   disponible y, al despachar, ofrece primero a los disponibles.
7. **App instalable (PWA)** con ícono propio y **notificaciones push** (Android; iPhone con iOS 16.4+
   y la app instalada). **WhatsApp sigue** como respaldo. Notificación y WhatsApp abren la misma salida.
8. **Enlace personal del repartidor:** la app lo recuerda y muestra "Mis salidas". El enlace queda
   amarrado al primer celular; si se abre en otro, pide confirmar y avisa a la caja. La caja puede
   revocarlo y generar otro.
9. **Seguimiento del cliente:** por WhatsApp le llega un **enlace con código largo** (abre directo)
   y un **código corto de 6 caracteres** de respaldo (ese pide además los últimos 4 dígitos del
   celular). Solo ve su entrega: la moto, el estado y "Faltan N entregas antes de la tuya", nunca
   datos de otros. Al entregar, el enlace solo dice "Entregado a las 10:42". El cliente no
   activa nada.
10. **Corrección de ubicaciones por los repartidores:** "La casa no está aquí" + arrastrar el pin; al
    "Entregué" se guarda dónde estaba el celular. Se aplica sola si coinciden (≤ 30 m) o si dos
    entregas confirman; si no, queda para revisar en DomiRuta. Máximo 300 m del punto original.
11. **Íconos:** el usuario consigue la moto (SVG o PNG 128 px, vista aérea), y si puede, tienda y
    cliente en el mismo estilo. Marca: puntos azules, un solo naranja por pantalla (el botón principal).
12. La página se cambia **directamente en su repositorio** (ya no por AI Studio). El usuario sube
    con `git push`.

## 4. Pantallas

### Repartidor
1. **Mis salidas** (al abrir la app): interruptor "Disponible para salidas", la salida nueva (si
   hay), la que lleva en curso y las de hoy.
2. **Salida nueva** (boceto del usuario): mapa con la ruta, tienda y paradas numeradas; "Tienda X te
   asignó una salida"; total a cobrar grande; distancia total; lista de paradas; "Responde antes de
   las 10:30"; **Acepto** (naranja) / **No puedo**. Si hay GPS: "Estás a 1,2 km de la tienda" (en
   línea recta, aproximado).
3. **Permiso de ubicación:** explicación → permiso → si se bloqueó, pasos con dibujos → continúa solo.
4. **Ruta en curso:** mapa arriba con su moto en vivo; abajo, la **siguiente parada** grande (nombre,
   dirección, nota, cobrar y cambio) con Llamar, WhatsApp, Cómo llegar (Google Maps/Waze),
   **Entregué**, No pude entregar y "La casa no está aquí". Lista de paradas y "Hechas". Chat como hoy.
   Franja roja si no hay ubicación. Interruptor de pausar ubicación (con confirmación).
5. **Terminada:** "Regresa a la tienda con $45.000" y el resumen.
6. **Casos:** salida vencida, la tomó otro, enlace no válido o revocado, abierto en otro celular.

### Cliente (sin instalar nada)
1. **Consulta:** casilla para el código + últimos 4 dígitos (si no entró con el enlace).
2. **Tu pedido:** nombre de la tienda, estado (Preparando / En camino / Faltan N antes de la tuya /
   Entregado), mapa con la moto y su casa, y la hora aproximada. Al entregar: "Entregado a las 10:42",
   sin mapa.

### DomiRuta
- **Domiciliarios:** estado (Disponible, En ruta con N pendientes, No disponible), última conexión,
  "Enviar enlace personal" y "Revocar enlace".
- **¿Quién la lleva?:** disponibles primero; los no disponibles abajo en gris.
- **Mapa de Salidas:** motos en vivo con el ícono; aviso "Se desvió de la ruta".
- **Campana:** sin respuesta (3 min), salida vencida, ubicación pausada o perdida (2 min), desvío,
  enlace abierto en otro celular y corrección de ubicación por revisar.
- **Correcciones de ubicación:** punto viejo y nuevo en el mapa, Aprobar / Descartar.
- **Despachar:** sube la ruta, genera los códigos del cliente y le manda su WhatsApp de seguimiento.
- **"Entregué" en el celular** cierra la entrega en DomiRuta (cuadre e historial).

## 5. Base de datos (Supabase) — archivo `repartidor/en-vivo.sql`

Se corre después de los SQL actuales y se puede correr otra vez sin dañar nada.

**Tablas nuevas**
- `repartidores`: id, nombre, celular, `enlace` (secreto, ≥ 32), `dispositivo` (el celular
  amarrado), disponible, `visto_en`, activo.
- `ubicaciones`: salida, lat, lon, precisión, rumbo, velocidad, hora (recorrido; se borra a los 30 días).
- `correcciones`: entrega, lat/lon propuestos, lat/lon del GPS al entregar, repartidor, estado
  (aplicada / pendiente / descartada), hora.
- `suscripciones_push`: repartidor, endpoint y claves del navegador, creada.
- `intentos`: para limitar intentos de consulta del cliente y envíos de ubicación muy seguidos.

**Columnas nuevas**
- `salidas`: repartidor_id, `ruta` (puntos de la línea), `calles` (calles alrededor, para el
  ajuste), `vence_en`, `ubicacion_pausada`, distancia total.
- `entregas_salida`: `codigo_cliente` (6), `enlace_cliente` (16+), `ultimos4`, `entregada_en`,
  lat/lon del GPS al entregar.

**Funciones (lo único que ven las páginas)**
- Repartidor: `mis_salidas(enlace, dispositivo)`, `cambiar_disponible`, `ver_salida` (ahora con ruta,
  vencimiento y tienda), `responder_salida` (**en un solo paso**: solo si sigue ofrecida y no ha
  vencido), `enviar_ubicacion` (con precisión y rumbo; máximo 1 cada 5 s), `pausar_ubicacion`,
  `marcar_entrega` (con el GPS del momento), `corregir_ubicacion`, `guardar_suscripcion`.
- Cliente: `ver_pedido(enlace_cliente)` y `ver_pedido_codigo(codigo, ultimos4)` (con límite de
  intentos). Solo devuelven su entrega.
- Edge Function `notificar` (push): la llama DomiRuta con la clave secreta; las claves VAPID quedan
  solo en Supabase.

**Seguridad:** RLS cerrado en todas las tablas; nada se lee directo; cada función revisa que lo pedido
sea de quien lo pide; códigos al azar sin letras confusas; límites de intentos; la ubicación del
repartidor solo se ve mientras va hacia ese cliente; datos personales mínimos (Ley 1581).

## 6. Página del repartidor y del cliente (repositorio `domiruta-repartidor`)
- Rutas: `/?s=…` (salida), `/r/…` (enlace personal) y `/pedido/…` o `/pedido` (cliente).
- Leaflet con los mapas de OpenStreetMap (con su crédito); íconos propios.
- Ajuste a la calle hecho en la página con la ruta y las calles que sube DomiRuta.
- PWA: manifiesto (nombre, colores, íconos 192/512), service worker (abre sin internet con lo
  último y recibe las notificaciones) y botón "Instalar app".
- Quitar librerías que no se usan (`@google/genai`, `express`).
- Manual de marca: Poppins/Inter, bordes de 5 px, un solo naranja por pantalla, estados como texto
  de color, toques de 48 px y que funcione en 360 px.

## 7. Casos especiales
- Dos repartidores aceptan a la vez → solo uno (lo decide la base).
- Acepta por la notificación y después abre el WhatsApp → "Ya aceptaste" y entra a la ruta.
- Vence mientras la está mirando → "Esta salida ya no está disponible".
- Sin internet un rato → la página guarda lo pendiente y lo manda al volver.
- La caja agrega o quita una parada en la ruta → la página la muestra resaltada.
- El cliente abre un enlace viejo → "Entregado a las…" o "Enlace no válido".

## 8. Pruebas
- **SQL:** archivo de pruebas que se corre en Supabase (dos repartidores, dos clientes, intentos de
  ver lo de otro, aceptar a la vez, vencimiento, límites).
- **DomiRuta:** pruebas automáticas como siempre (`Pruebas.java`).
- **Página:** compila sin errores, prueba del ajuste a la calle y capturas en celular de 360 px.
- **De punta a punta:** una salida real con dos celulares.

## 9. Orden de trabajo y lo indispensable para el 14 de noviembre

| Etapa | Qué | Para el 14 |
|---|---|---|
| A | SQL base: repartidor, ruta, vencimiento, aceptar en un paso, ubicaciones | Sí |
| B | DomiRuta: subir ruta, vencimiento, avisos de campana, estado de domiciliarios | Sí |
| C | Página: salida nueva (boceto), GPS obligatorio, ruta en curso con mapa y moto ajustada | Sí |
| D | Cliente: enlace y código, página de consulta, WhatsApp de seguimiento | Sí |
| E | PWA instalable + "Mis salidas" + Disponible + amarrar enlace al celular | Sí |
| F | Notificaciones push (Edge Function) | Si alcanza |
| G | Correcciones de ubicación por los repartidores | Si alcanza |

Si no alcanza el tiempo, F y G quedan diseñadas y con su SQL listo para después.

## 10. Lo que necesito del usuario
- Íconos: moto (y si puede tienda y cliente) y el cubo de DomiRuta en 512 × 512 para la app.
- Correr el SQL en Supabase (yo lo dejo listo) y, para F, crear las claves de notificación en
  Supabase siguiendo los pasos que le doy.
- `git push` de los dos repositorios cuando apruebe los commits.
- (Opcional) Cómo lo atendieron en el restaurante donde sí compró, para el WhatsApp del cliente.
