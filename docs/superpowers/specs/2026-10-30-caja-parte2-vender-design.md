# Caja de DomiRuta, parte 2: pantalla de Vender

Fecha: 30 de octubre de 2026. Estado: para revisar.

## Para qué

La pantalla donde el cajero vende en el mostrador, tomando como modelo la de
PuveSoft (que el dueño revisó y le gustó): rejilla de productos con foto,
categorías arriba, la venta a la derecha, animación al agregar, alertas claras y
una ventana de cobro con billetes. Con los colores y reglas del manual de marca.

Los domicilios dentro de la venta, Cotizar, Preventa y el cierre de caja quedan
para después (el dueño lo pedirá).

## Decisiones acordadas

- Botones de abajo: **Pagar, Espera y Cancelar**.
- **Categorías con foto**: campo nuevo en el producto y pantalla para crearlas.
- **Sin stock**: aviso corto y deja vender (el stock puede quedar negativo).
- Vender ocupa **todo el ancho** de la ventana, sin mapa. El mapa grande sigue
  solo en las pantallas de Domicilios (Ruta, Buscar, Clientes, Novedades, Historial).
- Lo ven el administrador y el cajero.

## Cómo se ve

```
┌────────┬──────────────────────── barra azul profundo: municipio · usuario · Salir ──┐
│ menú   │ [buscar o escanear ......................]   │ VENTA No. 00012        Cajero │
│ azul   │ [Todos][Dulces][Granos][Aseo][Bebidas] ...    │ Producto     Cant  Precio Sub │
│        │ ┌────┐┌────┐┌────┐┌────┐                     │ Arroz Diana   2   2.500 5.000 │
│ Vender │ │foto││foto││foto││foto│                     │ Azúcar 1 lb   1   2.200 2.200 │
│ ────── │ │Arroz││Café││Pan ││... │                    │ ...                           │
│ Ruta   │ │2.500││5.600│     │    │                    │──────────────────────────────│
│ Buscar │ └────┘└────┘└────┘└────┘                     │ Productos 3        Total     │
│ ...    │ ...                                          │                 $ 7.200      │
│        │                         (aviso ✓ abajo)      │ [ Pagar (naranja) ][Espera][✕]│
│        │ ventas en espera: [Marcela · $16.500 · 5 min] [Venta 2 · $3.000 · 1 min]      │
└────────┴──────────────────────────────────────────────────────────────────────────────┘
```

- **Izquierda (productos)**: buscador arriba (sirve el escáner: código + Enter);
  fila de categorías con su foto redonda y nombre (la primera es "Todos");
  rejilla de tarjetas blancas: foto, nombre, precio (en texto azul, sin píldora)
  y, si aplica, "Quedan 2" en texto de aviso o "Sin existencias" en texto de error.
  Si un producto no tiene foto se dibuja un ícono de paquete de línea gris.
- **Derecha (la venta)**: tabla con Producto, Cantidad, Precio, Descuento,
  Subtotal y quitar. Cantidad, precio y descuento se editan ahí mismo.
  Abajo el número de productos y el **total grande**. Un solo botón naranja: **Pagar**.
- **Abajo, ventas en espera** como pestañas con nombre (o "Venta 2"), total y
  minutos esperando. Solo aparecen si hay alguna.
- Sin bordes de más: paneles blancos sobre gris claro, sin tarjetas dentro de
  tarjetas, mucho aire. Radio de 5 px; 2 px en cuadros menores de 60 px.

## Flujo

1. **Agregar**: clic en la tarjeta, o escanear el código, o escribir y Enter.
   - Si ya está en la venta, suma 1.
   - Producto a granel (libra o kilo): se abre un cuadro pequeño
     "¿Cuánto?" con dos modos: **por peso** (1,5 lb) o **por valor** ($2.000 →
     calcula el peso). Enter agrega.
   - Escáner con código desconocido: pitido distinto y aviso "No hay ningún
     producto con el código 77…". Si la sesión es de administrador, botón
     "Crearlo" que abre Productos con el código puesto.
2. **Confirmación sin interrumpir**: aviso abajo a la derecha de la zona de
   productos (no tapa la venta) con un círculo que se dibuja y un chulito, el
   texto "Arroz Diana agregado" y se desvanece en 1,2 s. Pitido corto del sistema
   al escanear.
3. **Sin existencias**: el producto se agrega igual y el aviso sale en color de
   aviso: "Según el inventario no quedan Galletas".
4. **Pagar** (F12 o Ctrl+Enter): ventana de cobro (ver abajo).
5. **Espera** (F8): la venta se guarda como en espera, se le puede poner un
   nombre ("Marcela") y la pantalla queda limpia. Clic en la pestaña la retoma.
6. **Cancelar** (Esc con la venta vacía no hace nada): alerta de confirmación
   "¿Cancelar esta venta? Se quitan los 3 productos". El botón es discreto, no rojo.

## Ventana de cobro

- Arriba el **total** enorme.
- Método de pago como botones grandes: **Efectivo · Nequi · Daviplata ·
  Transferencia · Combinado**.
- Efectivo: billetes **1.000 · 2.000 · 5.000 · 10.000 · 20.000 · 50.000 ·
  100.000 · Exacto** (cada clic suma) y un campo para escribir. Debajo, grande,
  **Vueltas: $3.700** en verde, o "Faltan $1.000" en error.
- Combinado: dos líneas, método y valor; la segunda se llena sola con lo que falta.
- Cliente opcional (buscar por nombre o celular en el AVL de clientes).
- Botón **Cobrar** (Enter) desactivado hasta que alcance el pago.
- Al cobrar: la venta se guarda, el stock baja, y se pasa a la **pantalla de fin**:
  "Venta No. 00012 · Vueltas $3.700" grande, y tres botones: **Nueva venta**
  (Enter, el principal), **Imprimir comprobante** y **Ver la venta**.
  El comprobante dice "Comprobante de venta — no es factura electrónica".
  Se imprime con la impresora de Windows (de ahí también "Guardar como PDF").

## Alertas

Un componente propio, dibujado sobre la ventana (capa encima del contenido,
fondo oscurecido), nunca una ventana encima de otra:

- Ícono en círculo de 72 px de línea: **i** azul (información), **✓** verde
  (éxito), **!** de aviso (advertencia), **?** azul (confirmación).
- Título corto en Poppins 22 px, mensaje en Inter 15 px, en español sencillo.
- Botón principal azul; en confirmaciones un segundo botón blanco ("Volver").
- Entra con una animación de 150 ms (aparece y crece un poco). Enter acepta, Esc cierra.
- Se usan solo para lo que detiene: cancelar venta, error al guardar, base
  bloqueada. Lo demás va como aviso abajo o mensaje bajo el campo.

## Datos

En `tienda.db`:

```sql
CREATE TABLE categorias (
  id INTEGER PRIMARY KEY,
  nombre TEXT NOT NULL,
  foto TEXT,
  orden INTEGER NOT NULL DEFAULT 0,
  activo INTEGER NOT NULL DEFAULT 1
);
-- productos: columna nueva categoria_id INTEGER REFERENCES categorias(id), puede ser NULL.
-- Se agrega con ALTER TABLE solo si no existe (bases creadas en la parte 1).

CREATE TABLE ventas (
  id INTEGER PRIMARY KEY,                -- es el número de la venta
  estado TEXT NOT NULL CHECK (estado IN ('ESPERA','PAGADA')),
  nombre_espera TEXT,
  usuario_id INTEGER NOT NULL REFERENCES usuarios(id),
  cliente TEXT,                          -- nombre del cliente si se escogió
  creada TEXT NOT NULL,
  pagada TEXT,
  total INTEGER NOT NULL DEFAULT 0,
  recibido INTEGER NOT NULL DEFAULT 0,   -- efectivo entregado
  vueltas INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE venta_lineas (
  id INTEGER PRIMARY KEY,
  venta_id INTEGER NOT NULL REFERENCES ventas(id) ON DELETE CASCADE,
  producto_id INTEGER NOT NULL REFERENCES productos(id),
  nombre TEXT NOT NULL,                  -- como se llamaba al vender
  cantidad INTEGER NOT NULL,             -- milésimas
  precio INTEGER NOT NULL,               -- pesos por unidad de medida, al vender
  costo INTEGER NOT NULL,                -- precio de compra al vender (para la ganancia)
  descuento INTEGER NOT NULL DEFAULT 0,  -- pesos
  subtotal INTEGER NOT NULL
);

CREATE TABLE venta_pagos (
  id INTEGER PRIMARY KEY,
  venta_id INTEGER NOT NULL REFERENCES ventas(id) ON DELETE CASCADE,
  metodo TEXT NOT NULL CHECK (metodo IN ('EFECTIVO','NEQUI','DAVIPLATA','TRANSFERENCIA')),
  valor INTEGER NOT NULL
);
```

- Las ventas en espera se guardan en la base: si se cierra el programa o se va
  la luz, siguen ahí. **No bajan el stock**.
- Al cobrar, en **una sola transacción**: la venta pasa a PAGADA, se guardan
  pagos, se baja el stock de cada producto y se actualiza el catálogo en memoria.
  Si algo falla no queda nada a medias.
- Plata en pesos enteros; cantidades en milésimas. El subtotal de una línea a
  granel se redondea al peso: `precio * cantidad / 1000` con redondeo a la unidad.

## Piezas

`rutas.modelo`: `Categoria`, `LineaVenta`, `Venta` (líneas, total, estado), `MetodoPago`, `Pago`.

`rutas.logica`:
- `Carrito`: la venta que se está armando. Agregar (suma si ya está), cambiar
  cantidad/precio/descuento, quitar, total. No toca la base. Sin Swing, con pruebas.
- `Cobro`: valida pagos (alcanza, combinado cuadra, vueltas) y devuelve el resultado.
- `Granel`: convierte "por valor" a cantidad: `$2.000` de azúcar a `$4.400/kg` → 455 g.
  Cuando la línea se agregó por valor, su subtotal queda fijo en ese valor ($2.000),
  aunque 455 g × $4.400 dé $2.002: el cliente pidió "dos mil de azúcar".
- `BaseTienda`: categorías, guardar/listar/borrar ventas en espera, `cobrar(Venta, pagos)` en transacción.
- `Catalogo`: `porCategoria(id)` y `bajarStock` en memoria tras cobrar.

`rutas.vista`:
- `PanelVender` (pantalla completa), `TarjetaProducto`, `DialogoCobro` (como capa, no ventana),
  `CuadroGranel`, `Aviso` (el del chulito), `Alerta` (las que detienen), `PanelCategorias`
  (crear y editar categorías con foto; solo administrador, dentro de Productos).
- `VentanaPrincipal`: el contenido de la derecha pasa a tener dos modos: **pantalla
  completa** (Vender) y **columna + mapa** (las de Domicilios). Vender queda primero
  en el menú y es lo que se abre al entrar. Atajos visibles en la barra lateral.

## Errores

- Base bloqueada o falla al cobrar: alerta "No se pudo guardar la venta. No se
  cobró nada; intente de nuevo." La venta sigue en pantalla.
- Pago que no alcanza: Cobrar desactivado y "Faltan $1.000" en rojo bajo el total.
- Precio editado a 0 o negativo: mensaje bajo la celda, no se acepta.

## Pruebas (antes del código)

- `Carrito`: agregar el mismo producto suma; quitar; descuento no mayor que la línea;
  total con granel redondeado al peso; precio cambiado en la línea no cambia el producto.
- `Granel`: $2.000 a $4.400/kg → 455 g y subtotal $2.000 (no $2.002).
- `Cobro`: efectivo exacto, con vueltas, que no alcanza; combinado que cuadra y que no.
- `BaseTienda`: venta en espera se guarda y se recupera igual y no baja stock;
  cobrar baja el stock, guarda pagos y número consecutivo; si falla en la mitad no queda nada;
  stock en 0 deja vender y queda negativo; categoría nueva en una base de la parte 1 (columna agregada).
- Las 150 pruebas actuales siguen pasando.
- Pantalla: fotos a escala 1,25 de Vender vacía, con productos, el aviso del chulito,
  el cobro con vueltas, la pantalla de fin y una alerta.

## Fuera de esta parte

Domicilio dentro de la venta, Cotizar, Preventa, cierre de caja, reportes,
fiado, devoluciones y anular ventas, fechas de vencimiento, impresora térmica.
