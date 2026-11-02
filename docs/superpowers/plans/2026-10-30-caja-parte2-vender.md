# Caja parte 2 (pantalla de Vender) — plan de implementación

> Ejecución: en la misma sesión, tarea por tarea, con una revisión independiente al final.
> **Nunca hacer commit ni push**: al final se propone el mensaje al dueño.

**Objetivo:** la pantalla de Vender a todo el ancho (productos con foto, categorías, venta,
cobro con billetes y vueltas, espera, alertas y aviso del chulito), guardando ventas y bajando stock.

**Spec:** `docs/superpowers/specs/2026-10-30-caja-parte2-vender-design.md`

## Reglas globales

- Las de la parte 1 (plata en `long` pesos, cantidades en milésimas, Tema y manual, español sencillo, sin atribuciones).
- La lógica (Carrito, Granel, Cobro, BaseTienda, Catalogo) no usa Swing y tiene pruebas en `PruebasCaja`.
- Las 150 pruebas actuales siguen pasando.

## Lo que más puede fallar (Review Focus)

1. Cobrar con la base bloqueada o un error a mitad: no debe quedar venta PAGADA sin stock bajado, ni stock bajado sin venta. Prueba en Tarea 3.
2. Granel por valor: el subtotal es el valor pedido, no el redondeo del peso. Prueba en Tarea 1.
3. Retomar una venta en espera cuyo producto se editó o desactivó después: se conserva el nombre y precio guardados; se avisa si está desactivado. Prueba en Tarea 3.
4. Base de la parte 1 sin columna `categoria_id`: se agrega sola al abrir. Prueba en Tarea 3.
5. Pago combinado que suma más que el total, o efectivo en 0 con método no efectivo: vueltas solo del efectivo. Prueba en Tarea 2.

---

### Tarea 1: Modelos, Carrito y Granel
- Crear `modelo/Categoria`, `modelo/LineaVenta`, `modelo/MetodoPago` (EFECTIVO, NEQUI, DAVIPLATA, TRANSFERENCIA con texto), `modelo/Pago`, `logica/Carrito`, `logica/Granel`.
- `LineaVenta(Producto p, long cantidad, long precio, long descuento, Long subtotalFijo)`; `getSubtotal()` = subtotalFijo si no es null, si no `round(precio*cantidad/1000) - descuento`.
- `Carrito`: `agregar(Producto)` (+1000 milésimas, o +1 unidad), `agregar(Producto, long cantidad)`, `agregarPorValor(Producto, long pesos)`, `cambiarCantidad(i, long)`, `cambiarPrecio(i, long)`, `cambiarDescuento(i, long)` (rechaza mayor que el bruto o negativo), `quitar(i)`, `vaciar()`, `getLineas()`, `getTotal()`, `estaVacio()`, `cantidadProductos()`.
- `Granel.cantidadPorValor(long pesos, long precioPorUnidad)` → milésimas, redondeo al más cercano.
- Pruebas: suma del mismo producto; quitar; descuento; granel $2.000 a $4.400 → 455 y subtotal 2000; cambiar precio no toca el Producto.

### Tarea 2: Cobro
- `logica/Cobro`: `Resultado calcular(long total, List<Pago> pagos)` con `alcanza`, `faltan`, `vueltas` (solo del efectivo y nunca mayor que el efectivo), `recibidoEfectivo`.
- Pruebas: exacto; con vueltas; no alcanza; combinado Nequi 10.000 + efectivo 20.000 sobre 25.000 → vueltas 5.000; combinado sin efectivo que pasa del total → error "El pago pasa del total" (sin efectivo no hay vueltas).

### Tarea 3: Base y Catálogo (categorías, espera, cobrar)
- `BaseTienda`: tablas `categorias`, `ventas`, `venta_lineas`, `venta_pagos` del spec; `ALTER TABLE productos ADD COLUMN categoria_id` si falta; `listarCategorias()`, `crearCategoria`, `editarCategoria`; `Producto` gana `categoriaId` (Long, null permitido) y se guarda/lee.
- `long guardarEspera(Long idExistente, String nombre, long usuarioId, Carrito c)`; `List<VentaEspera> listarEspera()` (id, nombre, total, creada); `Carrito cargarEspera(long id, Catalogo cat)` (productos desactivados se cargan igual con aviso en `getAvisos()`); `borrarEspera(long id)`.
- `long cobrar(Long idEspera, long usuarioId, String cliente, Carrito c, List<Pago> pagos, Cobro.Resultado r)` en una transacción: inserta o actualiza la venta a PAGADA, líneas con nombre/precio/costo, pagos, y `UPDATE productos SET stock = stock - ?`. Devuelve el número.
- `Catalogo.porCategoria(Long id)` y `Catalogo.recargarProducto(long id)` tras cobrar.
- Pruebas: espera se recupera igual y no baja stock; cobrar baja stock y guarda pagos; números consecutivos; stock 0 → −1; fallo a mitad (producto inexistente) no deja nada; base vieja recibe la columna; producto desactivado en espera se carga con aviso.

### Tarea 4: Alerta y Aviso (componentes de pantalla)
- `vista/Alerta`: capa sobre el `JRootPane` (glass pane) con fondo oscurecido y tarjeta; tipos INFO, EXITO, AVISO, PREGUNTA; `Alerta.mostrar(JComponent dueño, tipo, titulo, mensaje, Runnable alAceptar)` y `confirmar(..., textoSi, textoNo, Runnable)`. Enter acepta, Esc cierra. Animación de 150 ms.
- `vista/Aviso`: toast abajo a la derecha de un panel; círculo que se dibuja (0,4 s) + chulito, texto, se desvanece a los 1,2 s. Variante de aviso (color aviso).
- Verificación: fotos de cada tipo a 1,25.

### Tarea 5: PanelVender
- Izquierda: buscador (escáner: Enter con código exacto agrega), fila de categorías con foto, rejilla de `TarjetaProducto` (foto o ícono de paquete, nombre, precio, estado de stock como texto). Clic agrega.
- Derecha: título "Venta No. N", tabla editable (JTable con editores de Tema), totales, botones Pagar (naranja), Espera, Cancelar (discreto, confirma).
- Abajo: pestañas de espera (nombre · total · minutos), solo si hay.
- `CuadroGranel` (por peso / por valor) como capa.
- Atajos: F2 buscar, F8 espera, F12 / Ctrl+Enter pagar.
- Pitido con `Toolkit.beep()` al escanear.

### Tarea 6: Cobro en pantalla y pantalla de fin
- `vista/CapaCobro`: total enorme, métodos en botones, billetes, campo, combinado (dos líneas), cliente opcional (AVL de clientes), vueltas/faltan en grande, Cobrar (Enter) activo solo si alcanza.
- Pantalla de fin: número, vueltas, Nueva venta (Enter), Imprimir comprobante (`PrinterJob`, dice "no es factura electrónica"), Ver la venta.

### Tarea 7: Categorías y ventana
- `vista/PanelCategorias` dentro de Productos (administrador): crear/editar nombre, foto, orden. Campo Categoría en el formulario de producto e importador (columna opcional `categoria` por nombre).
- `VentanaPrincipal`: grupo **Tienda** con **Vender** primero (todos los roles) + Productos/Usuarios; el contenido tiene dos modos: pantalla completa (Vender) y columna + mapa (Domicilios). Se abre en Vender. Atajos renumerados.

### Tarea 8: Verificación final
- Suite completa; búsqueda de atribuciones; revisión independiente; README (sección Vender); devolver archivos con verificación de huella; proponer commit.
