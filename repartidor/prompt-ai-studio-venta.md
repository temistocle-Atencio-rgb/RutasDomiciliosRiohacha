Haz solo estos dos cambios en la página "DomiRuta Repartidor". No cambies nada más.

## 1. Número de la venta y productos en cada tarjeta
`ver_salida` ahora también devuelve `venta` (texto, por ejemplo "00123"; vacío si no salió de una venta) y `productos` (número).

En cada tarjeta de entrega, debajo de la dirección, agrega una línea pequeña en gris (#5B6670, 13 px): "Venta N.º 00123 · 6 productos". Va solo si `venta` no está vacío; si `productos` es 0, solo "Venta N.º 00123". Sirve para saber qué bolsa es de quién.

## 2. Tarjeta de vista previa al compartir el enlace (WhatsApp)
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

La imagen `og-domiruta.png` va en la carpeta `public/` (te la subo yo). No la borres ni la cambies.
