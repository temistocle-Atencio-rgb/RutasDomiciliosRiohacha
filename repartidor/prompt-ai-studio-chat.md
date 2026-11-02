Haz estos cambios en la página "DomiRuta Repartidor". Lo que no se nombra aquí no se cambia. No agregues funciones de inteligencia artificial. Sigue usando solo `supabase.rpc(...)` con la misma clave pública. Todo `localStorage` va dentro de try/catch, como ya está.

## 0. Primero aceptar la salida (pantalla nueva)
Hay dos funciones nuevas:
- `info_salida(p_codigo)` devuelve `tienda, repartidor, creada, aceptada, rechazada`. Si viene vacía, la salida está cerrada o el enlace no es válido.
- `responder_salida(p_codigo, p_acepta, p_motivo)` devuelve `true` o `false`.

Al abrir el enlace, llama primero `info_salida`:
- **Si `aceptada` es false y `rechazada` es false**, muestra la pantalla de aceptar **en vez de** las entregas:
  - Cubo de DomiRuta y el título "**<tienda> te asignó una salida**" (si `tienda` está vacía: "La tienda te asignó una salida").
  - Debajo, en tarjeta blanca, el resumen que sale de `ver_salida`: "N entregas", "Cobras en total $X" (suma de `cobrar`) y la lista corta de direcciones (máximo 5; si hay más, "y N más").
  - Botón naranja a todo el ancho "**Acepto**". Llama `responder_salida(codigo, true, '')` y, si da true, pasa directo a las entregas, **sin preguntar "¿estás seguro?"**.
  - Debajo, botón con borde "**No puedo**". Abre una hoja con:
    - "¿Seguro que no puedes llevarla? La tienda buscará a otro.";
    - los motivos rápidos "Estoy ocupado", "Muy lejos", "No tengo moto ahora" y "Otro" (este último con un campo corto);
    - los botones "Sí, no puedo" (rojo #B03030 con texto blanco) y "Volver".
    - "Sí, no puedo" llama `responder_salida(codigo, false, motivo)`.
- **Si `rechazada` es true:** pantalla "Le avisaste a la tienda que no puedes llevar esta salida. Gracias." Sin entregas y sin botones.
- **Si `aceptada` es true:** muestra las entregas como siempre.
- **Antes de aceptar:** los mensajes con la tienda sí funcionan. No se pide la ubicación ni se puede marcar entregas; las funciones devuelven false hasta aceptar.
- Si `info_salida` llega vacía, usa la pantalla del punto 5 (salida cerrada) o el mensaje de enlace no válido.

## 1. Número de la venta y productos en cada tarjeta
`ver_salida` ahora también devuelve `venta` (texto, ej. "00123"; vacío si no salió de una venta) y `productos` (número).

Debajo de la dirección, en gris (#5B6670, 13 px), va la línea "Venta N.º 00123 · 6 productos":
- solo si `venta` no está vacío;
- si `productos` es 0, solo "Venta N.º 00123".

## 2. Entregas organizadas: lo pendiente arriba, lo hecho abajo
- Arriba van las entregas **en camino**, con la tarjeta grande de siempre.
- Debajo, la sección **"Hechas (N)"**, cerrada por defecto y con una flecha para abrirla. Lleva las entregadas y las no entregadas en filas compactas:
  - número y nombre;
  - el estado como texto de color ("Entregado" en #1E7A44, "No entregado · No abrieron" en #B03030);
  - la miniatura de la foto, si hay;
  - el enlace "Deshacer".
- Al marcar "Entregado", la tarjeta baja a "Hechas" con una animación corta (200 ms).

## 3. Foto de entrega
Al tocar "Agregar foto" (después de "Entregado") y subirla bien:
- Se muestra una **miniatura de 56×56 px** (bordes de 5 px) con el texto "Foto enviada" y un ícono de chulo en verde #1E7A44.
- Tocar la miniatura la abre en grande, a pantalla completa con fondo oscuro, con una X para cerrar.
- Al lado, el enlace "Cambiar foto".
- La miniatura sale de la foto ya reducida que se subió (guárdala en memoria). Si `tiene_foto` es true pero ya no está en memoria (por ejemplo, se recargó la página), muestra un ícono de cámara en gris en lugar de la miniatura, con "Foto enviada".
- Mientras sube: barra de progreso indeterminada y "Enviando foto…".

## 4. Chat con la tienda: estilo WhatsApp, con los colores de DomiRuta
Rediseña la hoja de "Mensajes" para que se sienta como un chat de WhatsApp, pero con la marca de DomiRuta. **No uses el verde de WhatsApp ni su logo.**
- **Pantalla completa**, con una barra arriba en azul profundo #003A73:
  - flecha de volver;
  - un círculo blanco con el cubo de DomiRuta;
  - "Tienda" en blanco y negrita y, debajo en pequeño, "Salida de las 10:20 a. m.".
- **Fondo** de la conversación #F2F4F7.
- **Burbujas:**
  - **Mías** (repartidor), a la derecha: fondo #DCEBF7 y "colita" arriba a la derecha.
  - **De la tienda**, a la izquierda: fondo blanco y colita arriba a la izquierda.
  - Bordes de 5 px, ancho máximo 80 %, letra Inter de 15 px y color #1F2933.
  - Mensajes seguidos de la misma persona van juntos (4 px de separación y sin colita, salvo el primero).
- **Hora y chulos** dentro de la burbuja, abajo a la derecha, en 11 px gris:
  - en mis mensajes, **un chulo gris** cuando se envió;
  - **dos chulos azules** (#005198) cuando la tienda ya lo leyó: `ver_mensajes` ahora devuelve `leido` (true o false);
  - mientras se envía, un reloj pequeño;
  - si falla: "No se envió · Tocar para reintentar" en rojo #B03030, y al tocar se reintenta.
- **Separador de día** en el centro ("Hoy", "Ayer"): texto gris pequeño sobre fondo blanco, con bordes de 5 px.
- **Respuestas rápidas** arriba de la caja de escribir, en una fila que se desliza de lado: "No encuentro la dirección", "El cliente no contesta", "Necesito cambio", "Ya voy de regreso". Son botones con borde azul #005198, fondo blanco, bordes de 5 px y 40 px de alto. Al tocar uno, se envía.
- **Caja de escribir** abajo y fija:
  - campo blanco con bordes de 5 px y "Escribe un mensaje" de ejemplo;
  - a la derecha, un **botón redondo azul #005198 de 48 px** con el ícono de enviar (Lucide `Send`) en blanco;
  - Enter también envía;
  - el campo crece hasta 4 renglones.
- **Desplazamiento:**
  - Al abrir, baja hasta el último mensaje.
  - Si el repartidor subió a leer y llega uno nuevo, no lo bajes de golpe: muestra un botón flotante redondo con flecha hacia abajo y el número de mensajes nuevos.
- Mientras el chat está abierto, cada 10 segundos vuelve a pedir **toda** la conversación (`p_desde` = 0) para que se actualicen los chulos de visto. Cerrado, pide solo los nuevos, como ahora.

## 5. Cuando la tienda cierra la salida
- Cada vez que cargue bien, guarda en `localStorage` (clave `domiruta_salida_<código>`) un resumen: cuántas entregadas, cuántas no entregadas y la plata cobrada (suma de `cobrar` de las entregadas).
- Si después `ver_salida` llega vacía y hay un resumen guardado para ese código, muestra una pantalla final en lugar del error:
  - cubo de DomiRuta;
  - "Salida cerrada";
  - "La tienda ya cerró esta salida. ¡Gracias!";
  - y el resumen: "Entregadas: 3 · No entregadas: 1 · Cobraste: $45.000".
- Si no hay resumen guardado, deja el mensaje de siempre.

## 6. Tarjeta del enlace en WhatsApp
En el `<head>` de `index.html` agrega:

```html
<title>DomiRuta · Tus entregas</title>
<meta name="description" content="Marca lo entregado y escríbele a la tienda">
<meta property="og:type" content="website">
<meta property="og:site_name" content="DomiRuta">
<meta property="og:title" content="DomiRuta · Tus entregas">
<meta property="og:description" content="Marca lo entregado y escríbele a la tienda">
<meta property="og:image" content="https://temistocle-atencio-rgb.github.io/domiruta-repartidor/og-domiruta.png">
<meta property="og:image:width" content="1200">
<meta property="og:image:height" content="630">
<meta name="theme-color" content="#003A73">
```

La imagen `og-domiruta.png` va en la carpeta `public/`. No la borres ni la cambies.

## Diseño general (igual que antes)
- Manual de marca: azul #005198, azul profundo #003A73 y un solo naranja #F28C1B por pantalla, el del botón "Entregado". En el chat no hay naranja.
- Letras: Poppins e Inter.
- Bordes de 5 px y toques de mínimo 48 px.
- Sin degradados ni emojis.
