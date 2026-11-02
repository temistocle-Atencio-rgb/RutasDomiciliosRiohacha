# Caja parte 1 (usuarios, login y productos) — plan de implementación

> **Para quien lo ejecute:** usar superpowers:executing-plans (o subagent-driven-development).
> Los pasos usan casillas `- [ ]`. **Nunca hacer commit ni push**: al final se propone
> un mensaje y el dueño del proyecto lo aprueba y lo hace él.

**Objetivo:** que DomiRuta pida ingreso con usuarios y roles, y tenga el catálogo de
productos de la tienda con búsqueda AVL por código y por nombre, importación CSV y
pantallas de Productos y Usuarios.

**Arquitectura:** base nueva `tienda.db` (clase `BaseTienda`) separada de la base por
ciudad. `Ingreso` valida contra PBKDF2 y entrega una `Sesion` con permisos. `Catalogo`
carga los productos activos a dos `ArbolAVL` y mantiene base y árboles sincronizados.
La ventana arma su menú según la `Sesion`.

**Tecnología:** Java 17, Swing/Java2D, SQLite (`lib/sqlite-jdbc-3.50.3.0.jar`), sin frameworks.

**Spec:** `docs/superpowers/specs/2026-10-30-caja-parte1-cimiento-design.md`

## Reglas globales

- Plata en pesos enteros `long`; cantidades en milésimas `long` (1,5 libras = 1500). Nunca `double` para plata.
- Contraseñas: `PBKDF2WithHmacSHA256`, sal de 16 bytes, 210000 iteraciones, hash de 256 bits, Base64.
- Ruta de la base: `new File(Ubicaciones.carpetaUsuario(), "tienda.db")`; fotos en `new File(Ubicaciones.carpetaUsuario(), "fotos")`.
- Nombres normalizados con `Geo.normalizar` (minúsculas, sin tildes, espacios simples). Usuario: además sin espacios.
- Clave del árbol por nombre: `claveNombre + "#" + id`.
- Mensaje de ingreso fallido, siempre el mismo: `Usuario o contraseña incorrectos`.
- 5 intentos fallidos seguidos → 30 segundos de espera.
- Colores y componentes solo de `Tema` (manual de marca). Textos en español sencillo.
- Comentarios en español sencillo. Sin atribuciones a herramientas en ningún archivo (regla commits-sin-atribucion).
- Las 87 pruebas existentes deben seguir pasando.

## Cómo compilar y probar

En el portátil, el bloque de PowerShell de `README`/traspaso. En Linux:

```bash
out=/tmp/domi_cls; rm -rf $out; mkdir -p $out
javac --release 17 -encoding UTF-8 -cp lib/sqlite-jdbc-3.50.3.0.jar -d $out $(find src/main/java -name '*.java')
java -cp "$out:lib/sqlite-jdbc-3.50.3.0.jar" rutas.PruebasCaja   # solo la caja, rápido
java -cp "$out:lib/sqlite-jdbc-3.50.3.0.jar" rutas.Pruebas        # todo
```

## Lo que más puede fallar (Review Focus)

1. **Códigos con cero adelante o que Excel volvió notación científica** (`0705632441947`, `7,70E+12`): el código siempre es texto; el importador rechaza filas con `E+` en el código con el motivo "Excel cambió el código; formatee la columna como texto". Prueba en Tarea 5.
2. **Precios escritos a la colombiana** (`$2.500`, `2.500,00`, `2,500`, `2500`): todos son 2500; `2.500,50` es error (no hay centavos). Prueba en Tarea 5.
3. **Usuario con mayúsculas o espacios** (`" Ana "` vs `"ana"`): es el mismo usuario; crear el segundo da "Ese usuario ya existe". Prueba en Tarea 3.
4. **Crear un producto con el código de uno desactivado**: no explota por el `UNIQUE`; dice "Ese código es de «X», que está desactivado" y se puede reactivar. Prueba en Tarea 4.
5. **El programa abierto dos veces / base bloqueada**: `PRAGMA busy_timeout = 3000`; si aun así falla, mensaje con la ruta y se cierra. Prueba en Tarea 3 (dos conexiones a la misma base no lanzan error al leer).

---

### Tarea 1: Seguridad y pruebas de la caja

**Archivos:**
- Crear: `src/main/java/rutas/logica/Seguridad.java`
- Crear: `src/main/java/rutas/PruebasCaja.java`
- Modificar: `src/main/java/rutas/Pruebas.java` (`revisar` pasa a `static void revisar` sin `private`; `main` llama `PruebasCaja.correr()` al inicio; nuevo `static void resumen()` que imprime el resultado y sale con 1 si hay fallas)

**Interfaces:**
- Produce: `Seguridad.ITERACIONES = 210000`; `static byte[] crearSal()`; `static byte[] hash(char[] clave, byte[] sal, int iteraciones)`; `static boolean coincide(char[] clave, byte[] sal, int iteraciones, byte[] esperado)` (usa `MessageDigest.isEqual`); `static String aTexto(byte[])` / `static byte[] deTexto(String)` (Base64).
- Produce: `PruebasCaja.correr() throws Exception` y `PruebasCaja.main` (corre solo la caja y llama `Pruebas.resumen()`).

- [ ] **Paso 1: prueba que falla** — en `PruebasCaja.probarSeguridad()`:
  - `hash("clave123", sal, 1000)` dos veces → `Arrays.equals` verdadero.
  - con otra sal → distinto.
  - `coincide("clave124", ...)` → falso; `coincide("clave123", ...)` → verdadero.
  - `crearSal().length == 16`; hash de 32 bytes.
  - (en pruebas usar 1000 iteraciones para que sea rápido)
- [ ] **Paso 2:** compilar y correr `rutas.PruebasCaja` → falla por `Seguridad` inexistente.
- [ ] **Paso 3:** implementar `Seguridad` con `SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")`, `PBEKeySpec(clave, sal, iteraciones, 256)` y `spec.clearPassword()`; `crearSal` con `SecureRandom`.
- [ ] **Paso 4:** correr `rutas.PruebasCaja` → todo OK; correr `rutas.Pruebas` → 87 + nuevas OK.

### Tarea 2: Modelos

**Archivos:**
- Crear: `src/main/java/rutas/modelo/Rol.java`, `Unidad.java`, `Usuario.java`, `Producto.java`

**Interfaces:**
- `enum Rol { ADMIN, CAJERO }`
- `enum Unidad { UNIDAD("und"), LIBRA("lb"), KILO("kg") }` con `String getCorto()`.
- `Usuario(long id, String usuario, String nombre, Rol rol, boolean activo)` con getters. Sin contraseña.
- `Producto(long id, String codigo, String nombre, Unidad unidad, long precioVenta, long precioCompra, long stock, long minimo, String foto, boolean activo)` con getters; `String getClaveNombre()` = `Geo.normalizar(nombre)`; `boolean estaBajoMinimo()` = `stock < minimo`; `codigo` vacío o en blanco se guarda como `null`. Inmutable: editar crea otro `Producto` con el mismo id.

- [ ] **Paso 1: prueba que falla** — `probarModelos()`: `new Producto(..., codigo "  ", nombre "Arróz Diana", stock 500, minimo 1000 ...)` → `getCodigo() == null`, `getClaveNombre().equals("arroz diana")`, `estaBajoMinimo()`.
- [ ] **Paso 2:** correr → falla.
- [ ] **Paso 3:** implementar los cuatro archivos.
- [ ] **Paso 4:** correr `rutas.PruebasCaja` → OK.

### Tarea 3: BaseTienda (usuarios), Ingreso y Sesion

**Archivos:**
- Crear: `src/main/java/rutas/logica/BaseTienda.java`, `Ingreso.java`, `Sesion.java`, `Permiso.java`

**Interfaces:**
- Consume: `Seguridad`, `Usuario`, `Rol`.
- `BaseTienda(File archivo) throws IOException implements AutoCloseable`: crea tablas `usuarios` y `productos` exactamente como en la spec, `PRAGMA foreign_keys = ON`, `journal_mode = WAL`, `busy_timeout = 3000`. Métodos de usuarios:
  - `int contarUsuarios()`
  - `Usuario crearUsuario(String usuario, String nombre, Rol rol, char[] clave) throws SQLException` — normaliza el usuario (`Geo.normalizar` y sin espacios); si ya existe lanza `IllegalArgumentException("Ese usuario ya existe")`; clave mínima 6 caracteres o `IllegalArgumentException("La contraseña debe tener al menos 6 caracteres")`.
  - `List<Usuario> listarUsuarios()`
  - `void cambiarClave(long id, char[] clave)`
  - `void cambiarActivo(long id, boolean activo)` — si desactiva al último ADMIN activo lanza `IllegalStateException("Debe quedar al menos un administrador activo")`.
  - `Usuario validar(String usuario, char[] clave)` — devuelve el usuario si la clave coincide y está activo; si no, `null`.
  - Constructor de pruebas `BaseTienda(File archivo, int iteraciones)` para usar 1000 iteraciones.
- `enum Permiso { VER_COMPRA, VER_INVENTARIO, ADMIN_USUARIOS, EDITAR_PRODUCTOS }`
- `Sesion(Usuario u)`: `Usuario getUsuario()`, `boolean puede(Permiso p)` (ADMIN todo, CAJERO nada), `boolean esAdmin()`.
- `Ingreso(BaseTienda bd, java.util.function.LongSupplier reloj)`: `Sesion entrar(String usuario, char[] clave) throws IngresoException`; `IngresoException` (clase anidada pública) con mensaje `Usuario o contraseña incorrectos` o `Demasiados intentos. Espere N segundos` tras 5 fallos (30 s contados con `reloj`); un ingreso correcto reinicia el contador. Siempre borra el `char[]` (`Arrays.fill(clave, '\0')`).

- [ ] **Paso 1: pruebas que fallan** — `probarUsuarios()` con base en carpeta temporal y 1000 iteraciones:
  - base nueva: `contarUsuarios() == 0`.
  - crear admin `" Ana "` / "Ana Gómez" / ADMIN / "secreto1"; crear `"ana"` otra vez → `IllegalArgumentException`.
  - clave `"123"` → `IllegalArgumentException`.
  - `Ingreso` con reloj falso: `entrar("ANA", "secreto1")` → sesión admin; `entrar("ana","mala")` → `IngresoException` con el mensaje exacto.
  - cinco fallos → el sexto intento, aun con la clave buena, da "Demasiados intentos"; avanzar el reloj 30001 ms → entra.
  - cajero "luis": `puede(VER_COMPRA)` falso; admin: verdadero.
  - desactivar a luis → `entrar("luis", ...)` falla; desactivar a ana (única admin) → `IllegalStateException`.
  - dos `BaseTienda` abiertas sobre el mismo archivo → `listarUsuarios()` en ambas sin error.
- [ ] **Paso 2:** correr → falla.
- [ ] **Paso 3:** implementar.
- [ ] **Paso 4:** correr → OK.

### Tarea 4: Productos en la base y Catalogo con AVL

**Archivos:**
- Modificar: `src/main/java/rutas/logica/BaseTienda.java` (métodos de productos)
- Crear: `src/main/java/rutas/logica/Catalogo.java`

**Interfaces:**
- `BaseTienda`: `List<Producto> listarProductos(boolean incluirInactivos)`; `Producto insertarProducto(Producto p)` (devuelve con id); `void actualizarProducto(Producto p)`; `Producto buscarPorCodigoEnBase(String codigo)` (activo o no); `void cambiarActivoProducto(long id, boolean activo)`; `<T> T enTransaccion(Callable<T>)`.
- `Catalogo(BaseTienda bd)`: carga los activos.
  - `Producto buscarCodigo(String codigo)` (recorta espacios; `null` si no está)
  - `List<Producto> buscarNombre(String texto, int limite)` (normaliza y usa `buscarPorPrefijo`)
  - `List<Producto> buscar(String texto)` — si hay un código exacto devuelve solo ese; si no, por nombre con límite 30. (Lo usa el buscador y el escáner.)
  - `Producto crear(Producto p)`, `Producto editar(Producto p)`, `void desactivar(long id)`, `Producto reactivar(long id)`
  - `List<Producto> todos()` (en orden por nombre), `List<Producto> bajoMinimo()`
  - `int tamano()`, `boolean balanceado()` (ambos árboles)
  - Errores: código repetido activo → `IllegalArgumentException("Ese código ya es de «nombre»")`; de un inactivo → `IllegalArgumentException("Ese código es de «nombre», que está desactivado")`; nombre vacío → `"Escriba el nombre"`; precio negativo → `"El precio no puede ser negativo"`.
  - Orden en `editar`: valida, guarda en base, y solo si salió bien quita las claves viejas de ambos árboles y pone las nuevas.

- [ ] **Paso 1: pruebas que fallan** — `probarCatalogo()`:
  - crear "Arroz Diana 500g" (código `7702511000014`), "Arepas Doña Paisa" (sin código), "Azúcar" (KILO, sin código), "Arroz Diana 500g" otra vez con otro código.
  - `buscarCodigo(" 7702511000014 ")` → Arroz.
  - `buscarNombre("arr", 0)` → 2 resultados (ambos arroces); `buscarNombre("ar", 0)` → 3 (arroces y arepas), sin azúcar; `buscarNombre("AZU", 0)` → azúcar.
  - código `0705632441947` se guarda y se encuentra con el cero.
  - crear con código repetido → excepción con el mensaje exacto.
  - renombrar arepas a "Arepa de choclo": `buscarNombre("arepas",0)` vacío, `buscarNombre("arepa de",0)` 1.
  - cambiar código del arroz: el viejo ya no se encuentra, el nuevo sí.
  - desactivar azúcar: no sale en `buscarNombre("az",0)`; `reactivar` lo devuelve.
  - crear "Gaseosa 400ml" (código `7702090000016`), desactivarla y crear otro producto con ese código → mensaje exacto de desactivado.
  - nuevo `Catalogo` sobre la misma base → mismo `tamano()`, `balanceado()` verdadero.
  - con 1000 productos insertados en orden alfabético → `balanceado()` verdadero.
- [ ] **Paso 2:** correr → falla.
- [ ] **Paso 3:** implementar.
- [ ] **Paso 4:** correr → OK.

### Tarea 5: ImportadorProductos

**Archivos:**
- Crear: `src/main/java/rutas/logica/ImportadorProductos.java`

**Interfaces:**
- Consume: `Catalogo.crear/editar`, `BaseTienda.enTransaccion`, `Catalogo.buscarCodigo`.
- `static long leerPesos(String texto)` — quita `$`, espacios y puntos de miles; `,00` final se ignora; coma seguida de exactamente 3 dígitos es de miles; cualquier otro decimal → `IllegalArgumentException("Los precios van sin centavos")`.
- `static long leerCantidad(String texto)` — `1,5` y `1.5` → 1500; `3` → 3000.
- `Resultado importar(File csv, Catalogo cat) throws IOException` con `Resultado { int creados; int actualizados; List<String> errores; }` (cada error: `"Fila 7: motivo"`).
- Detecta separador (`;` o `,`) por la primera línea; columnas por nombre del encabezado (sin importar mayúsculas/tildes): `nombre, codigo, unidad, precio_venta, precio_compra, stock, minimo`. `nombre` y `precio_venta` obligatorias; las demás opcionales (unidad por defecto UNIDAD). Lee UTF-8 (quitando BOM) y si encuentra caracteres inválidos relee en `windows-1252`. Mismo código que uno existente → `editar` (actualizado). Todo en una transacción; las filas malas no detienen las buenas.

- [ ] **Paso 1: pruebas que fallan** — `probarImportador()`:
  - `leerPesos`: `"$2.500"`, `"2.500,00"`, `"2,500"`, `"2500"` → 2500; `"2.500,50"` → excepción.
  - `leerCantidad("1,5") == 1500`, `leerCantidad("3") == 3000`.
  - CSV con `;` (escrito en windows-1252 con "Azúcar"): 3 filas buenas + 1 sin nombre + 1 con código `7,70E+12` → creados 3, errores 2 con "Fila 5" y "Fila 6" y el motivo de Excel; "azúcar" se encuentra por nombre con tilde bien leída.
  - Segundo CSV con `,` y el mismo código de una fila anterior con otro precio → actualizados 1 y el precio cambió.
- [ ] **Paso 2:** correr → falla.
- [ ] **Paso 3:** implementar.
- [ ] **Paso 4:** correr → OK.

### Tarea 6: Ingreso en pantalla y ventana según la sesión

**Archivos:**
- Crear: `src/main/java/rutas/vista/DialogoIngreso.java`
- Modificar: `src/main/java/rutas/Main.java`, `src/main/java/rutas/vista/VentanaPrincipal.java`, `src/main/java/rutas/vista/Aplicacion.java` (guarda `BaseTienda`, `Catalogo`, `Sesion` y sus getters)

**Interfaces:**
- `DialogoIngreso.pedir(Frame dueno, BaseTienda bd) : Sesion` — modal; `null` si se cierra. Si `bd.contarUsuarios() == 0` muestra "Crear administrador" (nombre, usuario, contraseña dos veces) y luego entra. Campo de contraseña `JPasswordField`, Enter envía, mensajes de `IngresoException` en `Tema.ERROR_TEXTO` bajo el formulario. Logo, colores y botones de `Tema`.
- `Main`: abre `BaseTienda` una vez al inicio (si falla: mensaje con la ruta y `System.exit(1)`), pide ingreso (si es `null`, sale) y guarda `Sesion` y `Catalogo` en campos estáticos que `abrir(ciudad)` pasa a `Aplicacion`. Cambiar de ciudad **no** vuelve a pedir ingreso. `Main.cerrarSesion()`: cierra la ventana, pide ingreso de nuevo y reabre la ciudad actual.
- `VentanaPrincipal`: en la barra de título, a la derecha, "nombre · Administrador/Cajero" y botón "Salir" (`Tema.botonPequeno`). En la barra lateral, dos grupos con título pequeño: **Domicilios** (las 5 pestañas de hoy) y **Tienda** (Productos y Usuarios, solo si la sesión tiene EDITAR_PRODUCTOS / ADMIN_USUARIOS). Los atajos Ctrl+número cubren todas las pestañas visibles y el texto de atajos dice "Ctrl+1 a N" con N real. Mientras no existan las tareas 7 y 8, las pestañas nuevas usan un `JPanel` vacío.

- [ ] **Paso 1:** compilar; correr `rutas.Pruebas` → todo OK (no hay lógica nueva que probar aquí).
- [ ] **Paso 2:** probar en pantalla con `xvfb-run` y `-Dsun.java2d.uiScale=1.25 -Duser.language=es -Duser.country=CO`: base nueva → crear administrador → entra con menú completo; crear un cajero con `BaseTienda` desde una prueba → salir y entrar como cajero → no se ven Productos ni Usuarios. Una sola captura de la ventana (no de la pantalla completa) para revisar textos cortados.

### Tarea 7: PanelUsuarios

**Archivos:**
- Crear: `src/main/java/rutas/vista/PanelUsuarios.java`; Modificar: `VentanaPrincipal.java` (reemplaza el panel vacío)

**Interfaces:**
- Consume: `BaseTienda.listarUsuarios/crearUsuario/cambiarClave/cambiarActivo`, `Sesion`.
- `PanelUsuarios(Aplicacion app)`: tarjeta con la lista (nombre, usuario, rol, activo); formulario "Nuevo usuario" (nombre, usuario, rol, contraseña dos veces); por fila: "Cambiar contraseña" y "Desactivar/Activar". Los mensajes de `IllegalArgumentException`/`IllegalStateException` se muestran tal cual en `Tema.ERROR_TEXTO`. El usuario que tiene la sesión no puede desactivarse a sí mismo (botón deshabilitado con ayuda "Es su propio usuario").

- [ ] **Paso 1:** compilar y correr `rutas.Pruebas` → OK.
- [ ] **Paso 2:** probar en pantalla: crear cajero, contraseñas que no coinciden → aviso, intentar desactivar la única admin → aviso. Sin captura salvo que algo se vea mal.

### Tarea 8: PanelProductos

**Archivos:**
- Crear: `src/main/java/rutas/vista/PanelProductos.java`; Modificar: `VentanaPrincipal.java`

**Interfaces:**
- Consume: `Catalogo` (todo), `ImportadorProductos.importar/leerPesos/leerCantidad`, `Sesion.puede(VER_COMPRA)`.
- `PanelProductos(Aplicacion app)`:
  - Buscador `Tema.campo("Escanee o escriba el nombre")`: filtra mientras se escribe con `Catalogo.buscar`; Enter con un código exacto abre ese producto en el formulario; si el código no existe, abre "Nuevo producto" con el código ya puesto.
  - Lista: nombre, código, precio de venta formateado (`$2.500` / `$4.000 por lb`), stock en su unidad, marca naranja "bajo el mínimo".
  - Formulario: nombre, código, unidad, precio de venta, precio de compra (solo con VER_COMPRA), stock, mínimo, "Elegir foto…" (copia a la carpeta de fotos con nombre `producto-<id>.<ext>` y muestra miniatura de 64 px). Botones Guardar / Desactivar. Casilla "Ver desactivados" para listar y reactivar.
  - "Importar lista…": `JFileChooser` con filtro `.csv`, importa en un hilo aparte (`SwingWorker`) y muestra el resumen; "Ver errores" abre la lista de filas con error.

- [ ] **Paso 1:** compilar y correr `rutas.Pruebas` → OK.
- [ ] **Paso 2:** probar en pantalla con un CSV de 50 productos de ejemplo: importar, buscar "arr", escribir un código como lo haría el escáner (texto + Enter), editar precio, desactivar y reactivar. Una captura de la ventana a escala 1,25.

### Tarea 9: Verificación final

- [ ] **Paso 1:** compilar desde cero y correr `rutas.Pruebas` → todas OK (87 de antes + las nuevas); anotar el número.
- [ ] **Paso 2:** buscar en todo el proyecto (sin `.git`) las palabras de atribución que prohíbe la regla commits-sin-atribucion → nada.
- [ ] **Paso 3:** revisión independiente (superpowers:requesting-code-review) de todo el cambio contra la spec y la sección "Lo que más puede fallar".
- [ ] **Paso 4:** actualizar el README (sección "Caja": cómo entrar la primera vez, formato del CSV) en lenguaje sencillo.
- [ ] **Paso 5:** proponer al dueño el commit, sin hacerlo: `git add` de los archivos nuevos/cambiados y mensaje sugerido `login de usuarios y catalogo de productos con arbol avl`.
