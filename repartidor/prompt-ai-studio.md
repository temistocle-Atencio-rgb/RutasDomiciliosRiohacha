Crea una página web para celular, en español, llamada "DomiRuta Repartidor". La usa el repartidor de una tienda de barrio en Riohacha (Colombia) para marcar los domicilios que va entregando. No uses ninguna función de inteligencia artificial ni la API de Gemini: es una página sencilla.

## Cómo se abre
El repartidor recibe por WhatsApp un enlace como `https://<sitio>/?s=CODIGO`. El código va en el parámetro `s` de la dirección. Si no hay código, muestra solo: "Este enlace no es válido. Pídele a la tienda que te lo envíe otra vez."
No hay registro ni inicio de sesión.

## Datos (Supabase)
Usa la librería oficial `@supabase/supabase-js` con dos constantes al inicio del código, que yo lleno:
```js
const SUPABASE_URL = "PEGAR_AQUI_LA_URL_DEL_PROYECTO";
const SUPABASE_KEY = "PEGAR_AQUI_LA_CLAVE_PUBLICA"; // publishable / anon, NUNCA la secreta
```
La página NO lee ni escribe tablas directamente. Solo llama estas dos funciones (ya existen):

1. `supabase.rpc('ver_salida', { p_codigo: codigo })` devuelve una lista ordenada de paradas:
   `id, orden, nombre, direccion, referencia, telefono, lat, lon, cobrar, paga_con, estado, motivo`
   - `cobrar`: pesos que debe cobrar en la puerta (0 = ya pagó).
   - `paga_con`: billete con el que va a pagar el cliente (0 = no se sabe).
   - `estado`: `en_camino`, `entregado` o `no_entregado`.
   - Si la lista viene vacía: "Esta salida ya se cerró o el enlace no es válido."
2. `supabase.rpc('marcar_entrega', { p_codigo: codigo, p_id: id, p_estado: estado, p_motivo: motivo })` devuelve `true` si quedó guardado y `false` si no.

## Pantalla
- Barra superior azul profundo con el título "Tus entregas" y debajo "3 de 5 entregadas".
- Una tarjeta por parada, en el orden de `orden`:
  - Número grande de la parada, nombre (negrita), dirección y referencia.
  - Si `cobrar > 0`: "COBRAR $12.500" grande. Si además `paga_con > 0`: "Paga con $20.000 · lleva $7.500 de cambio". Si `cobrar = 0`: "YA PAGÓ" en verde.
  - Botones: "Llamar" (`tel:` con el teléfono; solo si hay teléfono) y "Cómo llegar" (abre `https://www.google.com/maps/dir/?api=1&destination=lat,lon`; solo si hay lat y lon).
  - Botón principal naranja a todo el ancho: "Entregado".
  - Botón secundario: "No pude entregar". Abre una lista de motivos: "No abrieron", "No contestó", "Dirección equivocada", "No quiso recibir" y "Otro", con un campo corto de texto.
  - Ya marcada, la tarjeta muestra el estado como texto de color ("Entregado" en verde, "No entregado · No abrieron" en rojo) y un enlace pequeño "Deshacer", que vuelve a `en_camino`.
- Abajo, un resumen fijo: "Plata a traer: $45.000", la suma de `cobrar` de las paradas entregadas.
- Formato de pesos colombianos: `$12.500`, sin decimales.

## Comportamiento
- Al tocar un botón, muéstralo cargando y no permitas dos toques seguidos. Si falla (sin internet o `false`), muestra "No se pudo guardar. Revisa el internet y toca otra vez" y deja la tarjeta como estaba.
- Recarga la lista cada 30 segundos y al volver a la pestaña.
- Que se vea bien en celulares pequeños (360 px de ancho) y que todo se pueda tocar con el pulgar (mínimo 48 px de alto).

## Diseño (manual de marca, obligatorio)
- Colores: azul #005198, azul profundo #003A73, naranja #F28C1B (solo para el botón "Entregado": un solo acento naranja por tarjeta), fondo #F2F4F7, tarjetas blancas, texto #1F2933, texto secundario #5B6670, líneas #E3E7EC. Éxito: texto #1E7A44. Error: texto #B03030.
- Letras: Poppins para títulos y cifras, Inter para el texto (Google Fonts). Texto 14 px, títulos 18–21 px y "COBRAR" 22 px máximo.
- Bordes redondeados de 5 px. Nada de píldoras ni etiquetas con fondo para los estados: solo texto de color. Sin degradados, sin emojis y sin texto blanco sobre naranja (el texto del botón naranja va en #1F2933).
- Íconos de línea simples (por ejemplo Lucide).
