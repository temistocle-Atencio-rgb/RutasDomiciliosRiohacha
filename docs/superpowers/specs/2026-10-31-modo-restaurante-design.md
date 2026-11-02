# DomiRuta para restaurantes y bares (diseño)

Fecha: 31 de octubre de 2026

## Idea

Los restaurantes, comidas rápidas y bares son los negocios que más domicilios hacen.
DomiRuta ya organiza las entregas (rutas reales de Riohacha, hora prometida, cobro por
distancia, cuadre por moto) y ya tiene la Caja (productos, ventas, inventario). La idea es
que el mismo programa sirva para un restaurante, de principio a fin:

**pedido → cocina → listo → (mesa / para llevar / domicilio en la ruta) → cobro.**

No es un programa aparte: es un **modo de negocio** dentro de DomiRuta.

## Modo de negocio

- En Configuración se escoge: **Tienda** (como hoy) o **Restaurante / bar**.
- En modo restaurante aparecen en la barra lateral: **Pedidos**, **Cocina** y **Mesas**.
  Vender, Productos, Ruta, Clientes e Historial siguen iguales.
- Productos ya tiene lo necesario: tipo *Receta / Servicio / Combo*, *No maneja inventario*,
  insumos, lotes y vencimiento.

## Partes (en orden)

### 1. Pedido con tipo (base de todo)
- Al vender se escoge el tipo: **Mesa**, **Para llevar** o **Domicilio**.
- Domicilio pide cliente (celular, nombre) y dirección (el geocodificador que ya existe)
  y la venta **entra sola a la Ruta** con su valor y si ya está pagada.
- Notas por plato ("sin cebolla", "término medio") — la nota por línea ya existe.
- Estructuras: el cliente se busca en el AVL de clientes que ya existe.

### 2. Cocina (pantalla de comandas)
- Cada pedido enviado a cocina entra a una **Cola** (primero en llegar, primero en salir):
  tarjetas con los platos, notas, tipo de pedido y minutos de espera.
- Estados: **Pendiente → Preparando → Listo**. En rojo si pasa del tiempo esperado.
- Los domicilios urgentes o con hora prometida más cercana van primero (**MonticuloMin**
  por hora prometida, ya existe).
- Cuando un domicilio queda *Listo*, se marca en la Ruta como "listo para salir".

### 3. Recetas y combos (descuento de insumos)
- Una receta dice qué insumos lleva y cuánto (ej. Hamburguesa = 1 pan + 150 g de carne + …).
- Al vender una receta se descuentan sus insumos del inventario (no el plato).
- Combo = varios productos a un precio (ej. Hamburguesa + papas + gaseosa).
- El costo de la receta se calcula con los insumos → ganancia real por plato.

### 4. Mesas
- Plano de mesas en cuadrícula: libre (blanca), ocupada (azul), pidió la cuenta (naranja).
- Cada mesa es una cuenta abierta (se reutiliza la "venta en espera" que ya existe).
- Agregar platos a la mesa, enviarlos a cocina, mover o unir mesas, dividir la cuenta, cobrar.
- Propina opcional (sugerida 10 %).

## Fuera de esta etapa
- Impresora de comandas en cocina (ESC/POS) — después.
- Pedidos desde apps (Rappi, iFood) — no.
- Factura electrónica DIAN — fuera del alcance (comprobante de venta).

## Relación con el proyecto de clase (entrega 10–14 de noviembre)
La parte 1 (domicilio desde la venta a la Ruta) es la que más suma para el Proyecto 2
(grafo, Dijkstra, AVL, historial). La Cocina suma **Cola** y **MonticuloMin**.
Recetas y Mesas pueden quedar para después de la entrega si el tiempo no alcanza.

## Pruebas
Cada parte con sus pruebas en `PruebasCaja` (o `PruebasRestaurante`) antes de la interfaz,
y capturas con `-Dsun.java2d.uiScale=1.25`.
