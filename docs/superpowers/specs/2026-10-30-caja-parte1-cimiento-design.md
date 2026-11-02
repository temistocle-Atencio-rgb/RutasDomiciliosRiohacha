# Caja de DomiRuta, parte 1: cimiento (usuarios, login y productos)

Fecha: 30 de octubre de 2026. Estado: para revisar.

## Para qué

DomiRuta pasa a tener dos partes en el mismo programa: **Caja** y **Domicilios**.
Esta parte 1 deja lo que las demás necesitan: saber quién está usando el programa
y tener el catálogo de productos de la tienda, con búsqueda en árbol AVL.

Partes siguientes (cada una con su propio diseño): 2 venta + inventario,
3 cierre de caja y reportes, 4 botón "Es domicilio" y copia de seguridad.

## Decisiones acordadas

- Al abrir el programa **siempre se pide usuario y contraseña**. La primera vez
  (sin usuarios) se pide crear el administrador.
- Roles:
  - **Administrador**: ve todo (Caja, Domicilios, Productos con precio de compra, Usuarios).
  - **Cajero**: ve Caja y Domicilios. No ve precio de compra, ganancias, inventario ni Usuarios.
- Productos: se importan de una lista (CSV) y además se crean o editan uno por uno.
- Enfoque A: SQLite guarda; al abrir se cargan los productos a dos AVL para buscar.

## Dónde se guarda

Hoy la base es una por ciudad (`%APPDATA%\DomiRuta\<ciudad>\domiruta.db`). Los
usuarios y productos son de la **tienda**, no de la ciudad: si se cambia de
municipio no deben desaparecer. Por eso van en una base aparte:
`%APPDATA%\DomiRuta\tienda.db`, manejada por una clase nueva `BaseTienda`.
`BaseDatos` (rutas, clientes, novedades) no cambia.

Tablas:

```sql
CREATE TABLE usuarios (
  id INTEGER PRIMARY KEY,
  usuario TEXT NOT NULL UNIQUE,        -- en minúsculas, sin espacios
  nombre TEXT NOT NULL,                -- como se muestra
  rol TEXT NOT NULL CHECK (rol IN ('ADMIN','CAJERO')),
  sal TEXT NOT NULL,                   -- Base64, 16 bytes aleatorios
  hash TEXT NOT NULL,                  -- Base64, PBKDF2WithHmacSHA256
  iteraciones INTEGER NOT NULL,        -- 210000 (se guarda para poder subirlo luego)
  activo INTEGER NOT NULL DEFAULT 1,
  creado TEXT NOT NULL
);

CREATE TABLE productos (
  id INTEGER PRIMARY KEY,
  codigo TEXT UNIQUE,                  -- código de barras; NULL si es a granel sin código
  nombre TEXT NOT NULL,
  clave_nombre TEXT NOT NULL,          -- nombre normalizado (minúsculas, sin tildes)
  unidad TEXT NOT NULL CHECK (unidad IN ('UNIDAD','LIBRA','KILO')),
  precio_venta INTEGER NOT NULL CHECK (precio_venta >= 0),   -- pesos por unidad de medida
  precio_compra INTEGER NOT NULL CHECK (precio_compra >= 0),
  stock INTEGER NOT NULL DEFAULT 0,    -- en milésimas (1,5 libras = 1500)
  minimo INTEGER NOT NULL DEFAULT 0,   -- en milésimas
  foto TEXT,                           -- ruta del archivo copiado a %APPDATA%\DomiRuta\fotos
  activo INTEGER NOT NULL DEFAULT 1,
  actualizado TEXT NOT NULL
);
```

Los productos no se borran (las ventas de la parte 2 los van a nombrar):
se marcan `activo = 0` y dejan de aparecer en la búsqueda.

Plata siempre en pesos enteros (`long`) y cantidades en milésimas (`long`).
Nunca `double` para plata.

## Piezas

`rutas.modelo`
- `Rol` (enum ADMIN, CAJERO) y `Unidad` (enum UNIDAD, LIBRA, KILO, con su texto corto).
- `Usuario`: id, usuario, nombre, rol, activo. No guarda la contraseña.
- `Producto`: los campos de la tabla. `estaBajoMinimo()`.

`rutas.logica`
- `Seguridad`: `crearSal()`, `hash(char[] clave, byte[] sal, int iteraciones)`,
  `coincide(...)` con comparación de tiempo constante (`MessageDigest.isEqual`).
  Borra el `char[]` de la contraseña después de usarlo. Solo librería estándar de Java.
- `Sesion`: el usuario que entró; `puede(Permiso)` decide qué se muestra.
  Permisos: VER_COMPRA, VER_INVENTARIO, ADMIN_USUARIOS, EDITAR_PRODUCTOS.
  El cajero no tiene ninguno; el administrador los tiene todos.
- `Catalogo`: dueño de los dos árboles y de los cambios.
  - `ArbolAVL<String, Producto> porCodigo`
  - `ArbolAVL<String, Producto> porNombre`, con clave `clave_nombre + "#" + id`
    para que dos productos con el mismo nombre no se pisen.
  - `buscarCodigo(String)`, `buscarNombre(String prefijo, int limite)` (usa
    `buscarPorPrefijo` del AVL), `crear`, `editar`, `desactivar`.
  - Si cambia el nombre o el código, se quita la clave vieja del árbol y se pone
    la nueva. Primero se guarda en la base; si la base falla, los árboles no se tocan.
- `ImportadorProductos`: lee un CSV (separador `;` o `,`, el que tenga la primera
  línea; acepta UTF-8 y el que exporta Excel en Windows). Columnas por encabezado:
  `nombre;codigo;unidad;precio_venta;precio_compra;stock;minimo`. Acepta precios
  como `2500`, `2.500` o `$2.500`, y cantidades como `1,5`. Devuelve cuántos
  creó, cuántos actualizó (mismo código) y la lista de filas con error y su motivo;
  las filas buenas se guardan aunque haya filas malas, todo en una transacción.

`rutas.vista`
- `DialogoIngreso`: usuario y contraseña; modo "crear administrador" si no hay
  usuarios. Tras 5 intentos fallidos espera 30 segundos.
- `PanelProductos` (solo administrador, permiso EDITAR_PRODUCTOS; el cajero busca
  productos desde la pantalla de venta de la parte 2): buscador arriba (sirve el escáner: el código llega como texto
  + Enter), lista, y formulario para crear/editar. El precio de compra solo
  aparece si la sesión tiene VER_COMPRA. "Elegir foto…" copia la imagen a la
  carpeta de fotos (la foto es opcional). Botón "Importar lista…".
- `PanelUsuarios` (solo administrador): crear cajero, cambiar contraseña,
  desactivar. No se puede desactivar al último administrador activo.
- `VentanaPrincipal`: muestra arriba quién entró y un botón "Salir" que vuelve al
  ingreso. El menú lateral se arma según `Sesion`. Todo con los colores y
  componentes de `Tema` (manual de marca).

## Flujo

1. Arranca → abre `tienda.db` → ¿hay usuarios? No: crear administrador. Sí: ingreso.
2. Ingreso correcto → `Sesion` → se carga el `Catalogo` → ventana principal con el
   menú que corresponde.
3. Escanear o escribir en el buscador → si es un código exacto, va directo al
   producto; si no, lista por prefijo del nombre (máximo 30).

## Errores

- Contraseña mala: un solo mensaje "Usuario o contraseña incorrectos" (no dice cuál).
- Código de barras repetido al crear: aviso y no se guarda.
- `tienda.db` dañada o bloqueada (por ejemplo, el programa abierto dos veces): mensaje claro
  con la ruta del archivo y el programa se cierra, porque sin ella no hay ingreso.
- Importación: resumen al final ("120 creados, 8 actualizados, 3 con error") y las
  filas con error se pueden ver.

## Pruebas (en `Pruebas.java`, antes del código)

- `Seguridad`: la misma clave con la misma sal da el mismo hash; otra sal da otro;
  una clave mala no coincide.
- Login con base temporal: crear admin, entrar bien, entrar mal, usuario inactivo no entra,
  no se puede desactivar al último admin.
- `Sesion`: el cajero no tiene VER_COMPRA; el admin sí.
- `Catalogo`: buscar por código; prefijo "arr" trae arroz y arepas y no azúcar;
  dos productos con el mismo nombre conviven; al renombrar, el nombre viejo ya no
  aparece; código repetido se rechaza; un producto desactivado no aparece; al
  recargar desde la base los árboles quedan igual y balanceados.
- `ImportadorProductos`: CSV con `;` y con `,`, precios "$2.500", cantidad "1,5"
  (=1500), fila sin nombre va a errores sin tumbar las demás.
- Las 87 pruebas que ya existen siguen pasando.

## Fuera de esta parte

Vender, vueltas, transferencias, stock que baja, entrada de mercancía, cierre de
turno, reportes, fotos en la venta, domicilio y copia de seguridad (partes 2 a 4).
