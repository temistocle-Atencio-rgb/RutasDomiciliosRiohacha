Agrega estas mejoras a la página "DomiRuta Repartidor". No cambies lo que ya funciona, no agregues funciones de inteligencia artificial, y sigue usando SOLO llamadas `supabase.rpc(...)`: nunca leas ni escribas tablas directamente. Las funciones nuevas ya existen en Supabase.

## 1. Cambio en `ver_salida`
Ahora cada parada trae también `tiene_foto` (true/false).

## 2. Mensajes con la tienda
- Botón "Mensajes" en la barra superior, con un número naranja cuando hay mensajes nuevos de la tienda.
- Al tocarlo se abre una hoja desde abajo con la conversación:
  - Mensajes de la tienda a la izquierda, sobre fondo blanco.
  - Mensajes del repartidor a la derecha, sobre fondo #E8F0F8.
  - Debajo de cada mensaje, la hora (ej. "10:42 a. m.").
- Botones rápidos arriba del campo de texto, grandes y fáciles de tocar con una mano: "No encuentro la dirección", "El cliente no contesta", "Necesito cambio" y "Ya voy de regreso". Al tocar uno se envía de una vez.
- Campo de texto (máximo 300 letras) con botón "Enviar".
- Funciones:
  - Enviar: `supabase.rpc('enviar_mensaje', { p_codigo: codigo, p_texto: texto })` devuelve `true` o `false`. Si es `false` o falla, muestra "No se pudo enviar. Revisa el internet" y deja el texto en el campo.
  - Leer: `supabase.rpc('ver_mensajes', { p_codigo: codigo, p_desde: ultimoId })` devuelve `id, de, texto, creado` (`de` es "tienda" o "repartidor"). Pide solo los nuevos usando el `id` más alto que ya tienes (0 la primera vez).
- Revisa mensajes nuevos cada 10 segundos. Cuando llegue uno de la tienda con la hoja cerrada, muestra un aviso arriba ("Tienda: …") durante 5 segundos y haz vibrar el celular si se puede (`navigator.vibrate(200)`).

## 3. "Regresando" automático
Cuando no quede ninguna parada `en_camino`, muestra arriba de la lista un bloque verde de texto (#1E7A44, sin fondo de color): "Terminaste. Regresa a la tienda con $45.000", con la suma de `cobrar` de las entregadas. Supabase marca la salida como "regresando" sola; la página no tiene que llamar nada más.

## 4. Compartir ubicación
- Al abrir la página, muestra un aviso: "Para que la tienda vea por dónde vas, comparte tu ubicación mientras haces la ruta." Tiene dos botones: "Compartir ubicación" y "Ahora no".
- Si acepta, usa `navigator.geolocation.watchPosition` (con `enableHighAccuracy: true`) y manda la última posición cada 30 segundos con `supabase.rpc('enviar_ubicacion', { p_codigo: codigo, p_lat: lat, p_lon: lon })`.
- En la barra superior muestra un texto pequeño "Ubicación activa" (verde) o "Ubicación apagada" (gris), y deja tocarlo para activarla o apagarla.
- Deja de enviar la ubicación cuando no quede ninguna parada `en_camino` o cuando la salida se cierre (lista vacía).
- Si el permiso se niega, no insistas: muestra "Ubicación apagada" y sigue funcionando todo lo demás.

## 5. Paradas nuevas en camino
Si en una recarga aparece una parada que antes no estaba, resáltala con un borde azul #005198 durante 10 segundos. Muestra también el aviso "La tienda agregó una entrega: <nombre>" y haz vibrar el celular.

## 6. Foto de entrega (opcional)
- Al tocar "Entregado", después de guardar, muestra un enlace pequeño "Agregar foto" en esa tarjeta. Nunca es obligatoria.
- Usa `<input type="file" accept="image/*" capture="environment">`.
- Antes de subirla, redúcela en el celular con un canvas: lado mayor de 800 px, JPEG con calidad 0.6. Convierte con `canvas.toDataURL('image/jpeg', 0.6)`. Si el texto pasa de 340.000 caracteres, repite con calidad 0.4.
- Sube con `supabase.rpc('subir_foto', { p_codigo: codigo, p_id: id, p_foto: dataUrl })`, que devuelve `true` o `false`.
- Si la parada ya tiene foto (`tiene_foto`), muestra "Foto enviada" en verde en lugar del enlace.

## Diseño (igual que antes)
Mismos colores, letras Poppins/Inter, bordes de 5 px y zonas táctiles de 48 px. Los estados van como texto de color, sin píldoras, sin degradados y sin emojis. El número de mensajes nuevos puede ser un círculo naranja pequeño con el número en #1F2933.
