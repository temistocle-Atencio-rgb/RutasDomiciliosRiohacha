package rutas.logica;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import rutas.modelo.Categoria;
import rutas.modelo.Geo;
import rutas.modelo.LineaVenta;
import rutas.modelo.MetodoPago;
import rutas.modelo.Pago;
import rutas.modelo.Producto;
import rutas.modelo.Rol;
import rutas.modelo.Unidad;
import rutas.modelo.Usuario;

/**
 * Base de datos de la tienda (usuarios y productos), separada de la base de
 * cada ciudad: si se cambia de municipio, la tienda sigue igual.
 */
public class BaseTienda implements AutoCloseable {

    private final Connection con;
    private final File archivo;
    private final int iteraciones;

    public BaseTienda(File archivo) throws IOException {
        this(archivo, Seguridad.ITERACIONES);
    }

    /** Las pruebas usan menos vueltas para correr rapido. */
    public BaseTienda(File archivo, int iteraciones) throws IOException {
        this.archivo = archivo;
        this.iteraciones = iteraciones;
        archivo.getAbsoluteFile().getParentFile().mkdirs();
        try {
            con = DriverManager.getConnection("jdbc:sqlite:" + archivo.getAbsolutePath());
            try (Statement st = con.createStatement()) {
                st.execute("PRAGMA busy_timeout = 3000"); // si el programa esta abierto dos veces, espera
                st.execute("PRAGMA foreign_keys = ON");
                st.execute("PRAGMA journal_mode = WAL");
            }
            crearTablas();
        } catch (SQLException e) {
            throw new IOException("No se pudo abrir la base de la tienda " + archivo + ": " + e.getMessage(), e);
        }
    }

    private void crearTablas() throws SQLException {
        try (Statement st = con.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS usuarios ("
                    + " id INTEGER PRIMARY KEY,"
                    + " usuario TEXT NOT NULL UNIQUE,"
                    + " nombre TEXT NOT NULL,"
                    + " rol TEXT NOT NULL CHECK (rol IN ('ADMIN','CAJERO')),"
                    + " sal TEXT NOT NULL,"
                    + " hash TEXT NOT NULL,"
                    + " iteraciones INTEGER NOT NULL,"
                    + " activo INTEGER NOT NULL DEFAULT 1,"
                    + " creado TEXT NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS productos ("
                    + " id INTEGER PRIMARY KEY,"
                    + " codigo TEXT UNIQUE,"
                    + " nombre TEXT NOT NULL,"
                    + " clave_nombre TEXT NOT NULL,"
                    + " unidad TEXT NOT NULL CHECK (unidad IN ('UNIDAD','LIBRA','KILO')),"
                    + " precio_venta INTEGER NOT NULL CHECK (precio_venta >= 0),"
                    + " precio_compra INTEGER NOT NULL CHECK (precio_compra >= 0),"
                    + " stock INTEGER NOT NULL DEFAULT 0,"
                    + " minimo INTEGER NOT NULL DEFAULT 0,"
                    + " foto TEXT,"
                    + " activo INTEGER NOT NULL DEFAULT 1,"
                    + " actualizado TEXT NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS categorias ("
                    + " id INTEGER PRIMARY KEY,"
                    + " nombre TEXT NOT NULL,"
                    + " foto TEXT,"
                    + " orden INTEGER NOT NULL DEFAULT 0,"
                    + " activo INTEGER NOT NULL DEFAULT 1)");
            if (!tieneColumna(st, "usuarios", "permisos")) { // bases de antes de los permisos por usuario
                st.execute("ALTER TABLE usuarios ADD COLUMN permisos TEXT NOT NULL DEFAULT ''");
            }
            if (!tieneColumna(st, "productos", "categoria_id")) { // bases creadas antes de las categorias
                st.execute("ALTER TABLE productos ADD COLUMN categoria_id INTEGER REFERENCES categorias(id)");
            }
            st.execute("CREATE TABLE IF NOT EXISTS ventas ("
                    + " id INTEGER PRIMARY KEY,"
                    + " numero INTEGER UNIQUE,"            // se asigna al cobrar: 1, 2, 3...
                    + " estado TEXT NOT NULL CHECK (estado IN ('ESPERA','PAGADA')),"
                    + " nombre_espera TEXT,"
                    + " usuario_id INTEGER NOT NULL REFERENCES usuarios(id),"
                    + " cliente TEXT,"
                    + " creada TEXT NOT NULL,"
                    + " pagada TEXT,"
                    + " total INTEGER NOT NULL DEFAULT 0,"
                    + " recibido INTEGER NOT NULL DEFAULT 0,"
                    + " vueltas INTEGER NOT NULL DEFAULT 0)");
            st.execute("CREATE TABLE IF NOT EXISTS venta_lineas ("
                    + " id INTEGER PRIMARY KEY,"
                    + " venta_id INTEGER NOT NULL REFERENCES ventas(id) ON DELETE CASCADE,"
                    + " producto_id INTEGER NOT NULL REFERENCES productos(id),"
                    + " nombre TEXT NOT NULL,"
                    + " cantidad INTEGER NOT NULL,"
                    + " precio INTEGER NOT NULL,"
                    + " costo INTEGER NOT NULL,"
                    + " descuento INTEGER NOT NULL DEFAULT 0,"
                    + " subtotal INTEGER NOT NULL,"
                    + " por_valor INTEGER NOT NULL DEFAULT 0)");
            st.execute("CREATE TABLE IF NOT EXISTS venta_pagos ("
                    + " id INTEGER PRIMARY KEY,"
                    + " venta_id INTEGER NOT NULL REFERENCES ventas(id) ON DELETE CASCADE,"
                    + " metodo TEXT NOT NULL CHECK (length(metodo) > 0),"
                    + " valor INTEGER NOT NULL)");
            if (!tieneColumna(st, "ventas", "nota")) { // bases de antes de la nota y el descuento en %
                st.execute("ALTER TABLE ventas ADD COLUMN nota TEXT");
            }
            if (!tieneColumna(st, "venta_lineas", "porcentaje")) {
                st.execute("ALTER TABLE venta_lineas ADD COLUMN porcentaje INTEGER NOT NULL DEFAULT 0");
            }
            if (!tieneColumna(st, "productos", "maneja_inventario")) { // bases de antes de la ficha completa
                st.execute("ALTER TABLE productos ADD COLUMN maneja_inventario INTEGER NOT NULL DEFAULT 1");
            }
            ampliarMediosPago(st);
            crearTablasFicha(st);
            // los productos que se venden en la tienda en línea (la página del cliente)
            st.execute("CREATE TABLE IF NOT EXISTS en_linea (producto_id INTEGER PRIMARY KEY REFERENCES productos(id))");
            crearTablasCompras(st);
            BaseOrdenes.crearTablas(st); // proveedores y ordenes de compra
            st.execute("CREATE TABLE IF NOT EXISTS config (clave TEXT PRIMARY KEY, valor TEXT NOT NULL)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_lineas_venta ON venta_lineas(venta_id)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_pagos_venta ON venta_pagos(venta_id)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_ventas_estado ON ventas(estado)");
            codigosEnMayusculas(st);
        }
    }

    /**
     * Los codigos se guardan en mayusculas (abc123 y ABC123 son el mismo). Los de antes se pasan a
     * mayusculas, salvo si chocan con otro (esos se dejan y se cuentan en codigosRepetidos()). La
     * base no deja repetir un codigo sin importar mayusculas (indice unico sobre UPPER(codigo)).
     */
    private void codigosEnMayusculas(Statement st) throws SQLException {
        st.execute("UPDATE productos SET codigo = UPPER(codigo) WHERE codigo IS NOT NULL AND codigo <> UPPER(codigo)"
                + " AND NOT EXISTS (SELECT 1 FROM productos q WHERE q.id <> productos.id"
                + " AND UPPER(q.codigo) = UPPER(productos.codigo))");
        try {
            st.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_codigo_mayus ON productos(UPPER(codigo))");
        } catch (SQLException e) {
            Ubicaciones.registrarError(e); // hay codigos que solo cambian en mayusculas: el programa igual los revisa
        }
    }

    /** Codigos que existen dos veces si no se miran las mayusculas (quedaron de antes; hay que corregirlos a mano). */
    public List<String> codigosRepetidos() throws SQLException {
        List<String> l = new ArrayList<>();
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT UPPER(codigo) FROM productos WHERE codigo IS NOT NULL"
                     + " GROUP BY UPPER(codigo) HAVING COUNT(*) > 1")) {
            while (rs.next()) {
                l.add(rs.getString(1));
            }
        }
        return l;
    }

    /** Tablas de la ficha del producto (lo que va ademas de lo basico) y de las bodegas. */
    private static void crearTablasFicha(Statement st) throws SQLException {
        st.execute("CREATE TABLE IF NOT EXISTS producto_ficha ("
                + " producto_id INTEGER PRIMARY KEY REFERENCES productos(id),"
                + " descripcion TEXT NOT NULL DEFAULT '',"
                + " garantia TEXT NOT NULL DEFAULT '',"
                + " medida TEXT NOT NULL DEFAULT 'Unidad',"
                + " impuestos TEXT NOT NULL DEFAULT '',"      // separados por ;
                + " vencimiento TEXT,"                          // aaaa-mm-dd
                + " por_margen INTEGER NOT NULL DEFAULT 0,"
                + " margen INTEGER NOT NULL DEFAULT 0,"         // centesimas de %
                + " precio_mayor INTEGER NOT NULL DEFAULT 0 CHECK (precio_mayor >= 0),"
                + " es_producto INTEGER NOT NULL DEFAULT 1,"
                + " es_insumo INTEGER NOT NULL DEFAULT 0,"
                + " es_receta INTEGER NOT NULL DEFAULT 0,"
                + " url_imagen TEXT NOT NULL DEFAULT '',"
                + " caracterizar INTEGER NOT NULL DEFAULT 0,"
                + " usa_imei INTEGER NOT NULL DEFAULT 0,"
                + " usa_lotes INTEGER NOT NULL DEFAULT 0)");
        st.execute("CREATE TABLE IF NOT EXISTS bodegas ("
                + " id INTEGER PRIMARY KEY,"
                + " nombre TEXT NOT NULL UNIQUE COLLATE NOCASE,"
                + " activo INTEGER NOT NULL DEFAULT 1)");
        st.execute("INSERT OR IGNORE INTO bodegas(id, nombre) VALUES(1, 'PUNTO DE VENTA')");
        st.execute("CREATE TABLE IF NOT EXISTS producto_bodega ("
                + " producto_id INTEGER NOT NULL REFERENCES productos(id),"
                + " bodega_id INTEGER NOT NULL REFERENCES bodegas(id),"
                + " stock INTEGER NOT NULL DEFAULT 0 CHECK (stock >= 0),"
                + " minimo INTEGER NOT NULL DEFAULT 0 CHECK (minimo >= 0),"
                + " PRIMARY KEY (producto_id, bodega_id))");
        st.execute("CREATE TABLE IF NOT EXISTS producto_caracteristica ("
                + " id INTEGER PRIMARY KEY,"
                + " producto_id INTEGER NOT NULL REFERENCES productos(id),"
                + " nombre TEXT NOT NULL,"
                + " valor TEXT NOT NULL)");
        st.execute("CREATE TABLE IF NOT EXISTS producto_imei ("
                + " imei TEXT PRIMARY KEY,"                     // un IMEI es de un solo equipo
                + " producto_id INTEGER NOT NULL REFERENCES productos(id))");
        st.execute("CREATE TABLE IF NOT EXISTS producto_lote ("
                + " id INTEGER PRIMARY KEY,"
                + " producto_id INTEGER NOT NULL REFERENCES productos(id),"
                + " numero TEXT NOT NULL,"
                + " vence TEXT,"
                + " cantidad INTEGER NOT NULL DEFAULT 0 CHECK (cantidad >= 0),"
                + " UNIQUE (producto_id, numero))");
        st.execute("CREATE INDEX IF NOT EXISTS idx_caract_producto ON producto_caracteristica(producto_id)");
        st.execute("CREATE INDEX IF NOT EXISTS idx_imei_producto ON producto_imei(producto_id)");
        st.execute("CREATE INDEX IF NOT EXISTS idx_lote_producto ON producto_lote(producto_id)");
    }

    /** Compras a proveedores (las en espera y las ya guardadas) con sus lineas. */
    private static void crearTablasCompras(Statement st) throws SQLException {
        st.execute("CREATE TABLE IF NOT EXISTS compras ("
                + " id INTEGER PRIMARY KEY,"
                + " numero INTEGER UNIQUE,"                     // solo las guardadas tienen numero
                + " estado TEXT NOT NULL CHECK (estado IN ('ESPERA','GUARDADA')),"
                + " proveedor TEXT,"
                + " factura TEXT,"
                + " bodega_id INTEGER NOT NULL DEFAULT 1 REFERENCES bodegas(id),"
                + " usuario_id INTEGER NOT NULL REFERENCES usuarios(id),"
                + " creada TEXT NOT NULL,"
                + " guardada TEXT,"
                + " observaciones TEXT,"
                + " descuento_general INTEGER NOT NULL DEFAULT 0,"
                + " bruto INTEGER NOT NULL DEFAULT 0,"
                + " descuentos INTEGER NOT NULL DEFAULT 0,"
                + " impuestos INTEGER NOT NULL DEFAULT 0,"
                + " total INTEGER NOT NULL DEFAULT 0)");
        st.execute("CREATE TABLE IF NOT EXISTS compra_lineas ("
                + " id INTEGER PRIMARY KEY,"
                + " compra_id INTEGER NOT NULL REFERENCES compras(id) ON DELETE CASCADE,"
                + " producto_id INTEGER NOT NULL REFERENCES productos(id),"
                + " nombre TEXT NOT NULL,"
                + " cantidad INTEGER NOT NULL,"
                + " base INTEGER NOT NULL,"
                + " impuesto INTEGER NOT NULL DEFAULT 0,"
                + " descuento_pct INTEGER NOT NULL DEFAULT 0,"
                + " precio_venta INTEGER,"
                + " costo_escrito INTEGER,"                   // valor compra tal como se escribio
                + " subtotal INTEGER NOT NULL)");
        st.execute("CREATE INDEX IF NOT EXISTS idx_lineas_compra ON compra_lineas(compra_id)");
        if (!tieneColumna(st, "compras", "anulada")) { // anular una compra devuelve el stock
            st.execute("ALTER TABLE compras ADD COLUMN anulada INTEGER NOT NULL DEFAULT 0");
            st.execute("ALTER TABLE compras ADD COLUMN anulada_en TEXT");
            st.execute("ALTER TABLE compras ADD COLUMN anulada_por INTEGER REFERENCES usuarios(id)");
            st.execute("ALTER TABLE compras ADD COLUMN motivo_anulacion TEXT");
        }
    }

    /**
     * Las bases de antes solo dejaban guardar 4 medios de pago (EFECTIVO, NEQUI, DAVIPLATA,
     * TRANSFERENCIA). Se rehace la tabla de pagos sin esa regla, copiando todos los pagos.
     */
    private void ampliarMediosPago(Statement st) throws SQLException {
        String sql;
        try (ResultSet rs = st.executeQuery("SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'venta_pagos'")) {
            sql = rs.next() ? rs.getString(1) : "";
        }
        if (sql == null || !sql.contains("'TRANSFERENCIA'")) {
            return; // ya esta ampliada
        }
        boolean propia = con.getAutoCommit();
        if (propia) {
            con.setAutoCommit(false);
        }
        try {
            st.execute("CREATE TABLE venta_pagos_nueva ("
                    + " id INTEGER PRIMARY KEY,"
                    + " venta_id INTEGER NOT NULL REFERENCES ventas(id) ON DELETE CASCADE,"
                    + " metodo TEXT NOT NULL CHECK (length(metodo) > 0),"
                    + " valor INTEGER NOT NULL)");
            st.execute("INSERT INTO venta_pagos_nueva(id, venta_id, metodo, valor) SELECT id, venta_id, metodo, valor FROM venta_pagos");
            st.execute("DROP TABLE venta_pagos");
            st.execute("ALTER TABLE venta_pagos_nueva RENAME TO venta_pagos");
            if (propia) {
                con.commit();
            }
        } catch (SQLException e) {
            if (propia) {
                con.rollback();
            }
            throw e;
        } finally {
            if (propia) {
                con.setAutoCommit(true);
            }
        }
    }

    private static boolean tieneColumna(Statement st, String tabla, String columna) throws SQLException {
        try (ResultSet rs = st.executeQuery("PRAGMA table_info(" + tabla + ")")) {
            while (rs.next()) {
                if (columna.equalsIgnoreCase(rs.getString("name"))) {
                    return true;
                }
            }
        }
        return false;
    }

    public File getArchivo() {
        return archivo;
    }

    Connection conexion() {
        return con;
    }

    @Override
    public void close() {
        try {
            con.close();
        } catch (SQLException e) {
            // al cerrar no hay nada mas que hacer
        }
    }

    static String ahora() {
        return LocalDateTime.now().withNano(0).toString();
    }

    // ------------------------------------------------------------------
    //  Usuarios
    // ------------------------------------------------------------------

    /** Minusculas, sin tildes y sin espacios: " Ana " y "ANA" son el mismo usuario. */
    public static String normalizarUsuario(String usuario) {
        return Geo.normalizar(usuario).replace(" ", "");
    }

    public int contarUsuarios() throws SQLException {
        try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM usuarios")) {
            return rs.getInt(1);
        }
    }

    public Usuario crearUsuario(String usuario, String nombre, Rol rol, char[] clave) throws SQLException {
        String u = normalizarUsuario(usuario);
        if (u.isEmpty()) {
            throw new IllegalArgumentException("Escriba el usuario");
        }
        if (nombre == null || nombre.isBlank()) {
            throw new IllegalArgumentException("Escriba el nombre");
        }
        if (clave == null || clave.length < 6) {
            throw new IllegalArgumentException("La contraseña debe tener al menos 6 caracteres");
        }
        if (buscarFila(u) != null) {
            throw new IllegalArgumentException("Ese usuario ya existe");
        }
        byte[] sal = Seguridad.crearSal();
        byte[] hash = Seguridad.hash(clave, sal, iteraciones);
        try (PreparedStatement ps = con.prepareStatement(
                "INSERT INTO usuarios(usuario, nombre, rol, sal, hash, iteraciones, activo, creado) VALUES(?,?,?,?,?,?,1,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, u);
            ps.setString(2, nombre.trim());
            ps.setString(3, rol.name());
            ps.setString(4, Seguridad.aTexto(sal));
            ps.setString(5, Seguridad.aTexto(hash));
            ps.setInt(6, iteraciones);
            ps.setString(7, ahora());
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return new Usuario(rs.getLong(1), u, nombre.trim(), rol, true);
            }
        }
    }

    public List<Usuario> listarUsuarios() throws SQLException {
        List<Usuario> lista = new ArrayList<>();
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, usuario, nombre, rol, activo, permisos FROM usuarios ORDER BY nombre")) {
            while (rs.next()) {
                lista.add(leerUsuario(rs));
            }
        }
        return lista;
    }

    public void cambiarClave(long id, char[] clave) throws SQLException {
        if (clave == null || clave.length < 6) {
            throw new IllegalArgumentException("La contraseña debe tener al menos 6 caracteres");
        }
        byte[] sal = Seguridad.crearSal();
        byte[] hash = Seguridad.hash(clave, sal, iteraciones);
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE usuarios SET sal = ?, hash = ?, iteraciones = ? WHERE id = ?")) {
            ps.setString(1, Seguridad.aTexto(sal));
            ps.setString(2, Seguridad.aTexto(hash));
            ps.setInt(3, iteraciones);
            ps.setLong(4, id);
            ps.executeUpdate();
        }
    }

    public void cambiarActivo(long id, boolean activo) throws SQLException {
        if (!activo && esAdmin(id)) {
            try (PreparedStatement ps = con.prepareStatement(
                    "SELECT COUNT(*) FROM usuarios WHERE rol = 'ADMIN' AND activo = 1 AND id <> ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.getInt(1) == 0) {
                        throw new IllegalStateException("Debe quedar al menos un administrador activo");
                    }
                }
            }
        }
        try (PreparedStatement ps = con.prepareStatement("UPDATE usuarios SET activo = ? WHERE id = ?")) {
            ps.setInt(1, activo ? 1 : 0);
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    private boolean esAdmin(long id) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT rol FROM usuarios WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && "ADMIN".equals(rs.getString(1));
            }
        }
    }

    /** Devuelve el usuario si la contraseña es la correcta y esta activo; si no, null. */
    public Usuario validar(String usuario, char[] clave) throws SQLException {
        String u = normalizarUsuario(usuario);
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT id, usuario, nombre, rol, activo, permisos, sal, hash, iteraciones FROM usuarios WHERE usuario = ?")) {
            ps.setString(1, u);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    // se hace el mismo calculo para no delatar que el usuario no existe
                    Seguridad.hash(clave, Seguridad.crearSal(), iteraciones);
                    return null;
                }
                boolean bien = Seguridad.coincide(clave, Seguridad.deTexto(rs.getString(7)), rs.getInt(9),
                        Seguridad.deTexto(rs.getString(8)));
                Usuario usu = leerUsuario(rs);
                return bien && usu.isActivo() ? usu : null;
            }
        }
    }

    private String buscarFila(String usuarioNormalizado) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT usuario FROM usuarios WHERE usuario = ?")) {
            ps.setString(1, usuarioNormalizado);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static Usuario leerUsuario(ResultSet rs) throws SQLException {
        return new Usuario(rs.getLong(1), rs.getString(2), rs.getString(3), Rol.valueOf(rs.getString(4)),
                rs.getInt(5) == 1, rs.getString(6));
    }

    /** Lo que el administrador le da a un cajero (las casillas de Configuración › Usuarios). */
    public void guardarPermisos(long id, java.util.Set<rutas.logica.Permiso> permisos) throws SQLException {
        StringBuilder b = new StringBuilder();
        for (rutas.logica.Permiso p : permisos) {
            if (p.seDa()) {
                b.append(b.length() == 0 ? "" : ",").append(p.name());
            }
        }
        try (PreparedStatement ps = con.prepareStatement("UPDATE usuarios SET permisos = ? WHERE id = ?")) {
            ps.setString(1, b.toString());
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------
    //  Productos
    // ------------------------------------------------------------------

    private static final String CAMPOS_PRODUCTO =
            "id, codigo, nombre, unidad, precio_venta, precio_compra, stock, minimo, foto, activo, categoria_id,"
            + " maneja_inventario";

    public List<Producto> listarProductos(boolean incluirInactivos) throws SQLException {
        List<Producto> lista = new ArrayList<>();
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT " + CAMPOS_PRODUCTO + " FROM productos"
                     + (incluirInactivos ? "" : " WHERE activo = 1") + " ORDER BY clave_nombre")) {
            while (rs.next()) {
                lista.add(leerProducto(rs));
            }
        }
        return lista;
    }

    public Producto insertarProducto(Producto p) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("INSERT INTO productos(codigo, nombre, clave_nombre, unidad,"
                + " precio_venta, precio_compra, stock, minimo, foto, activo, actualizado, categoria_id, maneja_inventario)"
                + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            llenar(ps, p);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return p.conId(rs.getLong(1));
            }
        }
    }

    public void actualizarProducto(Producto p) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("UPDATE productos SET codigo = ?, nombre = ?, clave_nombre = ?,"
                + " unidad = ?, precio_venta = ?, precio_compra = ?, stock = ?, minimo = ?, foto = ?, activo = ?,"
                + " actualizado = ?, categoria_id = ?, maneja_inventario = ? WHERE id = ?")) {
            llenar(ps, p);
            ps.setLong(14, p.getId());
            ps.executeUpdate();
        }
    }

    private static void llenar(PreparedStatement ps, Producto p) throws SQLException {
        ps.setString(1, p.getCodigo());
        ps.setString(2, p.getNombre());
        ps.setString(3, p.getClaveNombre());
        ps.setString(4, p.getUnidad().name());
        ps.setLong(5, p.getPrecioVenta());
        ps.setLong(6, p.getPrecioCompra());
        ps.setLong(7, p.getStock());
        ps.setLong(8, p.getMinimo());
        ps.setString(9, p.getFoto());
        ps.setInt(10, p.isActivo() ? 1 : 0);
        ps.setString(11, ahora());
        if (p.getCategoriaId() == null) {
            ps.setNull(12, java.sql.Types.INTEGER);
        } else {
            ps.setLong(12, p.getCategoriaId());
        }
        ps.setInt(13, p.isManejaInventario() ? 1 : 0);
    }

    /** Busca por codigo aunque el producto este desactivado, sin importar mayusculas (abc123 encuentra ABC123). */
    public Producto buscarPorCodigoEnBase(String codigo) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT " + CAMPOS_PRODUCTO + " FROM productos WHERE UPPER(codigo) = UPPER(?)")) {
            ps.setString(1, codigo == null ? null : codigo.trim());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? leerProducto(rs) : null;
            }
        }
    }

    public Producto buscarProductoPorId(long id) throws SQLException {
        return buscarProducto("id", id);
    }

    private Producto buscarProducto(String campo, Object valor) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT " + CAMPOS_PRODUCTO + " FROM productos WHERE " + campo + " = ?")) {
            ps.setObject(1, valor);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? leerProducto(rs) : null;
            }
        }
    }

    public void cambiarActivoProducto(long id, boolean activo) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE productos SET activo = ?, actualizado = ? WHERE id = ?")) {
            ps.setInt(1, activo ? 1 : 0);
            ps.setString(2, ahora());
            ps.setLong(3, id);
            ps.executeUpdate();
        }
    }

    /** Hace todo el trabajo junto: o se guarda todo o no se guarda nada. */
    public <T> T enTransaccion(Callable<T> trabajo) throws Exception {
        con.setAutoCommit(false);
        try {
            T r = trabajo.call();
            con.commit();
            return r;
        } catch (Exception e) {
            con.rollback();
            throw e;
        } finally {
            con.setAutoCommit(true);
        }
    }

    private static Producto leerProducto(ResultSet rs) throws SQLException {
        return new Producto(rs.getLong(1), rs.getString(2), rs.getString(3), Unidad.valueOf(rs.getString(4)),
                rs.getLong(5), rs.getLong(6), rs.getLong(7), rs.getLong(8), rs.getString(9), rs.getInt(10) == 1,
                rs.getObject(11) == null ? null : rs.getLong(11), rs.getInt(12) == 1);
    }

    // ------------------------------------------------------------------
    //  Categorias
    // ------------------------------------------------------------------

    public List<Categoria> listarCategorias(boolean incluirInactivas) throws SQLException {
        List<Categoria> lista = new ArrayList<>();
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, nombre, foto, orden, activo FROM categorias"
                     + (incluirInactivas ? "" : " WHERE activo = 1") + " ORDER BY orden, nombre")) {
            while (rs.next()) {
                lista.add(new Categoria(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getInt(5) == 1));
            }
        }
        return lista;
    }

    public Categoria crearCategoria(String nombre, String foto, int orden) throws SQLException {
        if (nombre == null || nombre.isBlank()) {
            throw new IllegalArgumentException("Escriba el nombre");
        }
        try (PreparedStatement ps = con.prepareStatement(
                "INSERT INTO categorias(nombre, foto, orden, activo) VALUES(?,?,?,1)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, nombre.trim());
            ps.setString(2, foto);
            ps.setInt(3, orden);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return new Categoria(rs.getLong(1), nombre, foto, orden, true);
            }
        }
    }

    /** Crea con un codigo escogido (al importar); si ese codigo ya existe, lanza un error claro. */
    public Categoria crearCategoriaConCodigo(long codigo, String nombre, String foto, int orden, boolean activo)
            throws SQLException {
        if (nombre == null || nombre.isBlank()) {
            throw new IllegalArgumentException("Escriba el nombre");
        }
        if (codigo <= 0) {
            throw new IllegalArgumentException("El código debe ser un número mayor que 0");
        }
        if (buscarCategoria(codigo) != null) {
            throw new IllegalArgumentException("Ya hay una categoría con el código " + codigo);
        }
        try (PreparedStatement ps = con.prepareStatement(
                "INSERT INTO categorias(id, nombre, foto, orden, activo) VALUES(?,?,?,?,?)")) {
            ps.setLong(1, codigo);
            ps.setString(2, nombre.trim());
            ps.setString(3, foto);
            ps.setInt(4, orden);
            ps.setInt(5, activo ? 1 : 0);
            ps.executeUpdate();
        }
        return new Categoria(codigo, nombre.trim(), foto, orden, activo);
    }

    public Categoria buscarCategoria(long id) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT id, nombre, foto, orden, activo FROM categorias WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Categoria(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4),
                        rs.getInt(5) == 1) : null;
            }
        }
    }

    /** Cuantos productos (activos o no) tiene cada categoria. */
    public Map<Long, Integer> productosPorCategoria() throws SQLException {
        Map<Long, Integer> m = new java.util.HashMap<>();
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT categoria_id, COUNT(*) FROM productos"
                     + " WHERE categoria_id IS NOT NULL GROUP BY categoria_id")) {
            while (rs.next()) {
                m.put(rs.getLong(1), rs.getInt(2));
            }
        }
        return m;
    }

    /** Borra una categoria que no tiene productos (si tiene, se desactiva en vez de borrar). */
    public void eliminarCategoria(long id) throws SQLException {
        Integer n = productosPorCategoria().get(id);
        if (n != null && n > 0) {
            throw new IllegalArgumentException("Esa categoría tiene " + n + (n == 1 ? " producto" : " productos")
                    + ": desactívela en vez de borrarla");
        }
        try (PreparedStatement ps = con.prepareStatement("DELETE FROM categorias WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    public void editarCategoria(Categoria c) throws SQLException {
        if (c.getNombre().isBlank()) {
            throw new IllegalArgumentException("Escriba el nombre");
        }
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE categorias SET nombre = ?, foto = ?, orden = ?, activo = ? WHERE id = ?")) {
            ps.setString(1, c.getNombre());
            ps.setString(2, c.getFoto());
            ps.setInt(3, c.getOrden());
            ps.setInt(4, c.isActivo() ? 1 : 0);
            ps.setLong(5, c.getId());
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------
    //  Ventas en espera y cobro
    // ------------------------------------------------------------------

    /** Datos de una venta en espera para mostrarla en las pestañas. */
    public static class VentaEspera {
        private final long id;
        private final String nombre;
        private final long total;
        private final LocalDateTime creada;

        VentaEspera(long id, String nombre, long total, LocalDateTime creada) {
            this.id = id;
            this.nombre = nombre;
            this.total = total;
            this.creada = creada;
        }

        public long getId() {
            return id;
        }

        public String getNombre() {
            return nombre;
        }

        public long getTotal() {
            return total;
        }

        public LocalDateTime getCreada() {
            return creada;
        }
    }

    /** Una venta en espera vuelta a armar, con avisos si algo cambio mientras esperaba. */
    public static class Retomada {
        private final Carrito carrito;
        private final String nombre;
        private final List<String> avisos;

        Retomada(Carrito carrito, String nombre, List<String> avisos) {
            this.carrito = carrito;
            this.nombre = nombre;
            this.avisos = avisos;
        }

        public Carrito getCarrito() {
            return carrito;
        }

        public String getNombre() {
            return nombre;
        }

        public List<String> getAvisos() {
            return avisos;
        }
    }

    /** Guarda (o actualiza) una venta en espera. No baja el stock. Devuelve su id. */
    public long guardarEspera(Long id, String nombre, long usuarioId, Carrito c) throws SQLException {
        try {
            return enTransaccion(() -> {
                long ventaId;
                if (id == null) {
                    try (PreparedStatement ps = con.prepareStatement("INSERT INTO ventas(estado, nombre_espera, usuario_id,"
                            + " creada, total, nota) VALUES('ESPERA',?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
                        ps.setString(1, vacioANull(nombre));
                        ps.setLong(2, usuarioId);
                        ps.setString(3, ahora());
                        ps.setLong(4, c.getTotal());
                        ps.setString(5, c.getNota());
                        ps.executeUpdate();
                        try (ResultSet rs = ps.getGeneratedKeys()) {
                            rs.next();
                            ventaId = rs.getLong(1);
                        }
                    }
                } else {
                    ventaId = id;
                    try (PreparedStatement ps = con.prepareStatement(
                            "UPDATE ventas SET nombre_espera = ?, total = ?, nota = ? WHERE id = ? AND estado = 'ESPERA'")) {
                        ps.setString(1, vacioANull(nombre));
                        ps.setLong(2, c.getTotal());
                        ps.setString(3, c.getNota());
                        ps.setLong(4, ventaId);
                        if (ps.executeUpdate() == 0) {
                            throw new IllegalStateException("Esa venta ya no está en espera");
                        }
                    }
                    borrarDetalle(ventaId);
                }
                guardarLineas(ventaId, c);
                return ventaId;
            });
        } catch (SQLException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new SQLException(e.getMessage(), e);
        }
    }

    public List<VentaEspera> listarEspera() throws SQLException {
        List<VentaEspera> lista = new ArrayList<>();
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, nombre_espera, total, creada FROM ventas"
                     + " WHERE estado = 'ESPERA' ORDER BY id")) {
            while (rs.next()) {
                lista.add(new VentaEspera(rs.getLong(1), rs.getString(2), rs.getLong(3),
                        LocalDateTime.parse(rs.getString(4))));
            }
        }
        return lista;
    }

    /** Vuelve a armar una venta en espera con el precio y la cantidad que tenia. */
    public Retomada cargarEspera(long id, Catalogo cat) throws SQLException {
        String nombre = null;
        String nota = null;
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT nombre_espera, nota FROM ventas WHERE id = ? AND estado = 'ESPERA'")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("Esa venta ya no está en espera");
                }
                nombre = rs.getString(1);
                nota = rs.getString(2);
            }
        }
        Carrito c = new Carrito();
        c.setNota(nota);
        List<String> avisos = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement("SELECT producto_id, nombre, cantidad, precio, descuento,"
                + " subtotal, por_valor, porcentaje FROM venta_lineas WHERE venta_id = ? ORDER BY id")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long pid = rs.getLong(1);
                    Producto p = cat.buscarPorId(pid);
                    if (p == null) {
                        p = buscarProductoPorId(pid);
                        if (p == null) {
                            avisos.add("«" + rs.getString(2) + "» ya no existe y se quitó de la venta");
                            continue;
                        }
                        avisos.add("«" + p.getNombre() + "» está desactivado");
                    }
                    boolean porValor = rs.getInt(7) == 1;
                    LineaVenta l = new LineaVenta(p, rs.getLong(3), rs.getLong(4), rs.getLong(5),
                            porValor ? rs.getLong(6) + rs.getLong(5) : null);
                    if (rs.getLong(8) > 0) {
                        l.setPorcentaje(rs.getLong(8));
                    }
                    c.agregarLinea(l);
                }
            }
        }
        return new Retomada(c, nombre, avisos);
    }

    public void borrarEspera(long id) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("DELETE FROM ventas WHERE id = ? AND estado = 'ESPERA'")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    /** Numero que tendra la proxima venta cobrada. */
    public long siguienteNumero() throws SQLException {
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(numero), 0) + 1 FROM ventas")) {
            return rs.getLong(1);
        }
    }

    /**
     * Cobra la venta: todo en una transaccion (venta, lineas, pagos y stock).
     * Si algo falla no se guarda nada. Devuelve el numero de la venta.
     * El efectivo se guarda neto (lo que de verdad queda en el cajon: recibido - vueltas).
     */
    public long cobrar(Long idEspera, long usuarioId, String cliente, Carrito c, List<Pago> pagos,
            Cobro.Resultado r) throws SQLException {
        if (c.estaVacio()) {
            throw new IllegalArgumentException("La venta no tiene productos");
        }
        Cobro.Resultado comprobado = Cobro.calcular(c.getTotal(), pagos);
        if (!comprobado.isAlcanza() || r == null || !r.isAlcanza()) {
            throw new IllegalArgumentException("El pago no alcanza");
        }
        try {
            return enTransaccion(() -> {
                long numero = siguienteNumero();
                long ventaId;
                if (idEspera == null) {
                    try (PreparedStatement ps = con.prepareStatement("INSERT INTO ventas(numero, estado, usuario_id,"
                            + " cliente, creada, pagada, total, recibido, vueltas, nota) VALUES(?,'PAGADA',?,?,?,?,?,?,?,?)",
                            Statement.RETURN_GENERATED_KEYS)) {
                        String hoy = ahora();
                        ps.setLong(1, numero);
                        ps.setLong(2, usuarioId);
                        ps.setString(3, vacioANull(cliente));
                        ps.setString(4, hoy);
                        ps.setString(5, hoy);
                        ps.setLong(6, c.getTotal());
                        ps.setLong(7, comprobado.getRecibidoEfectivo());
                        ps.setLong(8, comprobado.getVueltas());
                        ps.setString(9, c.getNota());
                        ps.executeUpdate();
                        try (ResultSet rs = ps.getGeneratedKeys()) {
                            rs.next();
                            ventaId = rs.getLong(1);
                        }
                    }
                } else {
                    ventaId = idEspera;
                    try (PreparedStatement ps = con.prepareStatement("UPDATE ventas SET numero = ?, estado = 'PAGADA',"
                            + " usuario_id = ?, cliente = ?, pagada = ?, total = ?, recibido = ?, vueltas = ?, nota = ?"
                            + " WHERE id = ? AND estado = 'ESPERA'")) {
                        ps.setLong(1, numero);
                        ps.setLong(2, usuarioId);
                        ps.setString(3, vacioANull(cliente));
                        ps.setString(4, ahora());
                        ps.setLong(5, c.getTotal());
                        ps.setLong(6, comprobado.getRecibidoEfectivo());
                        ps.setLong(7, comprobado.getVueltas());
                        ps.setString(8, c.getNota());
                        ps.setLong(9, ventaId);
                        if (ps.executeUpdate() == 0) {
                            throw new IllegalStateException("Esa venta ya no está en espera");
                        }
                    }
                    borrarDetalle(ventaId);
                }
                guardarLineas(ventaId, c);
                guardarPagos(ventaId, pagos, comprobado.getVueltas());
                bajarStock(c);
                return numero;
            });
        } catch (SQLException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new SQLException(e.getMessage(), e);
        }
    }

    /** La nota que se escribio en una venta cobrada (null si no tiene). */
    public String notaDeVenta(long numero) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT nota FROM ventas WHERE numero = ?")) {
            ps.setLong(1, numero);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** Lo que entro por cada medio en una venta (efectivo neto). */
    public Map<MetodoPago, Long> pagosDeVenta(long numero) throws SQLException {
        Map<MetodoPago, Long> m = new EnumMap<>(MetodoPago.class);
        try (PreparedStatement ps = con.prepareStatement("SELECT p.metodo, SUM(p.valor) FROM venta_pagos p"
                + " JOIN ventas v ON v.id = p.venta_id WHERE v.numero = ? GROUP BY p.metodo")) {
            ps.setLong(1, numero);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    m.put(MetodoPago.valueOf(rs.getString(1)), rs.getLong(2));
                }
            }
        }
        return m;
    }

    private void borrarDetalle(long ventaId) throws SQLException {
        for (String t : new String[]{"venta_lineas", "venta_pagos"}) {
            try (PreparedStatement ps = con.prepareStatement("DELETE FROM " + t + " WHERE venta_id = ?")) {
                ps.setLong(1, ventaId);
                ps.executeUpdate();
            }
        }
    }

    private void guardarLineas(long ventaId, Carrito c) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("INSERT INTO venta_lineas(venta_id, producto_id, nombre,"
                + " cantidad, precio, costo, descuento, subtotal, por_valor, porcentaje) VALUES(?,?,?,?,?,?,?,?,?,?)")) {
            for (LineaVenta l : c.getLineas()) {
                ps.setLong(1, ventaId);
                ps.setLong(2, l.getProducto().getId());
                ps.setString(3, l.getProducto().getNombre());
                ps.setLong(4, l.getCantidad());
                ps.setLong(5, l.getPrecio());
                ps.setLong(6, l.getProducto().getPrecioCompra());
                ps.setLong(7, l.getDescuento());
                ps.setLong(8, l.getSubtotal());
                ps.setInt(9, l.esPorValor() ? 1 : 0);
                ps.setLong(10, l.getPorcentajeGuardado()); // el derivado de pesos no se guarda: al retomarla se redondearia
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void guardarPagos(long ventaId, List<Pago> pagos, long vueltas) throws SQLException {
        long porDescontar = vueltas; // las vueltas salen del efectivo
        try (PreparedStatement ps = con.prepareStatement(
                "INSERT INTO venta_pagos(venta_id, metodo, valor) VALUES(?,?,?)")) {
            for (Pago p : pagos) {
                long valor = p.getValor();
                if (p.getMetodo() == MetodoPago.EFECTIVO && porDescontar > 0) {
                    long quita = Math.min(valor, porDescontar);
                    valor -= quita;
                    porDescontar -= quita;
                }
                if (valor > 0) {
                    ps.setLong(1, ventaId);
                    ps.setString(2, p.getMetodo().name());
                    ps.setLong(3, valor);
                    ps.addBatch();
                }
            }
            ps.executeBatch();
        }
    }

    private void bajarStock(Carrito c) throws SQLException {
        // se suma por producto (puede estar en varias lineas) y se revisa contra lo que hay AHORA en la base:
        // otra caja pudo vender lo ultimo mientras esta venta se armaba
        java.util.Map<Long, Long> porProducto = new java.util.LinkedHashMap<>();
        java.util.Map<Long, LineaVenta> ejemplo = new java.util.HashMap<>();
        for (LineaVenta l : c.getLineas()) {
            porProducto.merge(l.getProducto().getId(), l.getCantidad(), Long::sum);
            ejemplo.putIfAbsent(l.getProducto().getId(), l);
        }
        try (PreparedStatement ps = con.prepareStatement("SELECT stock, maneja_inventario FROM productos WHERE id = ?")) {
            for (java.util.Map.Entry<Long, Long> e : porProducto.entrySet()) {
                ps.setLong(1, e.getKey());
                try (ResultSet rs = ps.executeQuery()) {
                    LineaVenta l = ejemplo.get(e.getKey());
                    if (!rs.next()) {
                        throw new IllegalStateException("«" + l.getProducto().getNombre() + "» ya no existe");
                    }
                    long hay = rs.getLong(1);
                    if (rs.getInt(2) == 1 && hay < e.getValue()) {
                        throw new IllegalStateException("Ya no hay suficiente «" + l.getProducto().getNombre()
                                + "»: quedan " + ListadoProductos.cantidad(Math.max(0, hay)) + " "
                                + l.getProducto().getUnidad().getCorto());
                    }
                }
            }
        }
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE productos SET stock = stock - (CASE WHEN maneja_inventario = 1 THEN ? ELSE 0 END),"
                + " actualizado = ? WHERE id = ?")) {
            for (LineaVenta l : c.getLineas()) {
                ps.setLong(1, l.getCantidad());
                ps.setString(2, ahora());
                ps.setLong(3, l.getProducto().getId());
                if (ps.executeUpdate() == 0) {
                    throw new IllegalStateException("«" + l.getProducto().getNombre() + "» ya no existe");
                }
            }
        }
        bajarLotes(porProducto);
    }

    // ------------------------------------------------------------------
    //  Tienda en línea: qué productos se venden en la página
    // ------------------------------------------------------------------

    public boolean enLinea(long productoId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT 1 FROM en_linea WHERE producto_id = ?")) {
            ps.setLong(1, productoId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public void ponerEnLinea(long productoId, boolean si) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(si ? "INSERT OR IGNORE INTO en_linea(producto_id) VALUES(?)"
                : "DELETE FROM en_linea WHERE producto_id = ?")) {
            ps.setLong(1, productoId);
            ps.executeUpdate();
        }
    }

    /** Un producto tal como lo necesita la página (sin costos ni proveedores). */
    public static class ProductoEnLinea {
        public long id;
        public String nombre = "";
        public String descripcion = "";
        public String categoria = "";
        public int ordenCategoria;
        public long precio;
        public long stock;
        public boolean manejaInventario;
        public Unidad unidad = Unidad.UNIDAD;
        public String foto;      // archivo en este computador (null = sin foto)
        public String codigo = "";
        /** {nombre, valor} de la ficha («¿Caracterizar producto?» encendido) */
        public List<String[]> caracteristicas = new ArrayList<>();
    }

    /** Los productos activos marcados «Vender en línea», con su categoría y su descripción. */
    public List<ProductoEnLinea> productosEnLinea() throws SQLException {
        List<ProductoEnLinea> l = new ArrayList<>();
        Map<Long, ProductoEnLinea> conCaract = new java.util.HashMap<>();
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT p.id, p.nombre, p.precio_venta, p.stock, p.maneja_inventario,"
                     + " p.unidad, p.foto, COALESCE(c.nombre, ''), COALESCE(c.orden, 0), COALESCE(f.descripcion, ''),"
                     + " COALESCE(p.codigo, ''), COALESCE(f.caracterizar, 0)"
                     + " FROM en_linea e JOIN productos p ON p.id = e.producto_id"
                     + " LEFT JOIN categorias c ON c.id = p.categoria_id AND c.activo = 1"
                     + " LEFT JOIN producto_ficha f ON f.producto_id = p.id"
                     + " WHERE p.activo = 1 ORDER BY p.id")) {
            while (rs.next()) {
                ProductoEnLinea p = new ProductoEnLinea();
                p.id = rs.getLong(1);
                p.nombre = rs.getString(2);
                p.precio = rs.getLong(3);
                p.stock = rs.getLong(4);
                p.manejaInventario = rs.getInt(5) != 0;
                try {
                    p.unidad = Unidad.valueOf(rs.getString(6));
                } catch (IllegalArgumentException | NullPointerException e) {
                    p.unidad = Unidad.UNIDAD;
                }
                p.foto = rs.getString(7);
                p.categoria = rs.getString(8);
                p.ordenCategoria = rs.getInt(9);
                p.descripcion = rs.getString(10);
                p.codigo = rs.getString(11);
                if (rs.getInt(12) != 0) {
                    conCaract.put(p.id, p);
                }
                l.add(p);
            }
        }
        if (!conCaract.isEmpty()) {
            try (Statement st = con.createStatement();
                 ResultSet rs = st.executeQuery("SELECT k.producto_id, k.nombre, k.valor FROM producto_caracteristica k"
                         + " JOIN en_linea e ON e.producto_id = k.producto_id ORDER BY k.producto_id, k.id")) {
                while (rs.next()) {
                    ProductoEnLinea p = conCaract.get(rs.getLong(1));
                    if (p != null) {
                        p.caracteristicas.add(new String[]{rs.getString(2), rs.getString(3)});
                    }
                }
            }
        }
        return l;
    }

    // ------------------------------------------------------------------
    //  Ajustes del negocio (logo, datos del recibo, impresora)
    // ------------------------------------------------------------------

    public String leerConfig(String clave, String porDefecto) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT valor FROM config WHERE clave = ?")) {
            ps.setString(1, clave);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : porDefecto;
            }
        }
    }

    /** Guarda varios ajustes juntos: o quedan todos o ninguno. */
    public void guardarConfigs(String[][] pares) throws SQLException {
        boolean propia = con.getAutoCommit();
        if (propia) {
            con.setAutoCommit(false);
        }
        try (PreparedStatement ps = con.prepareStatement("INSERT OR REPLACE INTO config(clave, valor) VALUES(?,?)")) {
            for (String[] p : pares) {
                ps.setString(1, p[0]);
                ps.setString(2, p[1] == null ? "" : p[1]);
                ps.executeUpdate();
            }
            if (propia) {
                con.commit();
            }
        } catch (SQLException | RuntimeException e) {
            if (propia) {
                con.rollback();
            }
            throw e;
        } finally {
            if (propia) {
                con.setAutoCommit(true);
            }
        }
    }

    // ------------------------------------------------------------------
    //  Ficha del producto y bodegas
    // ------------------------------------------------------------------

    public List<rutas.modelo.Bodega> listarBodegas() throws SQLException {
        List<rutas.modelo.Bodega> lista = new ArrayList<>();
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, nombre FROM bodegas WHERE activo = 1 ORDER BY id")) {
            while (rs.next()) {
                lista.add(new rutas.modelo.Bodega(rs.getLong(1), rs.getString(2)));
            }
        }
        return lista;
    }

    public rutas.modelo.Bodega crearBodega(String nombre) throws SQLException {
        if (nombre == null || nombre.isBlank()) {
            throw new IllegalArgumentException("Escriba el nombre de la bodega");
        }
        String n = nombre.trim().toUpperCase();
        try (PreparedStatement ps = con.prepareStatement("INSERT INTO bodegas(nombre) VALUES(?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, n);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return new rutas.modelo.Bodega(rs.getLong(1), n);
            }
        } catch (SQLException e) {
            if (e.getMessage() != null && e.getMessage().contains("UNIQUE")) {
                throw new IllegalArgumentException("Ya hay una bodega que se llama " + n);
            }
            throw e;
        }
    }

    /**
     * Lo vendido sale de los lotes que vencen primero (FEFO), para que la fecha de
     * vencimiento que se muestra sea la de lo que de verdad queda.
     */
    private void bajarLotes(Map<Long, Long> porProducto) throws SQLException {
        try (PreparedStatement lotes = con.prepareStatement("SELECT l.id, l.cantidad FROM producto_lote l"
                + " JOIN producto_ficha f ON f.producto_id = l.producto_id AND f.usa_lotes = 1"
                + " JOIN productos p ON p.id = l.producto_id AND p.maneja_inventario = 1"
                + " WHERE l.producto_id = ? AND l.cantidad > 0 ORDER BY l.vence IS NULL, l.vence, l.id");
             PreparedStatement bajar = con.prepareStatement("UPDATE producto_lote SET cantidad = ? WHERE id = ?")) {
            for (Map.Entry<Long, Long> e : porProducto.entrySet()) {
                long falta = e.getValue();
                lotes.setLong(1, e.getKey());
                List<long[]> lista = new ArrayList<>();
                try (ResultSet rs = lotes.executeQuery()) {
                    while (rs.next()) {
                        lista.add(new long[]{rs.getLong(1), rs.getLong(2)});
                    }
                }
                for (long[] l : lista) {
                    if (falta <= 0) {
                        break;
                    }
                    long sale = Math.min(falta, l[1]);
                    bajar.setLong(1, l[1] - sale);
                    bajar.setLong(2, l[0]);
                    bajar.executeUpdate();
                    falta -= sale;
                }
            }
        }
    }

    /** Nombre del producto que ya tiene ese IMEI (otro distinto al dado), o null si esta libre. */
    public String productoConImei(String imei, long productoId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT p.nombre FROM producto_imei i JOIN productos p"
                + " ON p.id = i.producto_id WHERE i.imei = ? AND i.producto_id <> ?")) {
            ps.setString(1, imei);
            ps.setLong(2, productoId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** Lee la ficha; si el producto no tiene, devuelve una con los valores de siempre. */
    public rutas.modelo.FichaProducto leerFicha(long productoId) throws SQLException {
        rutas.modelo.FichaProducto f = new rutas.modelo.FichaProducto();
        try (PreparedStatement ps = con.prepareStatement("SELECT descripcion, garantia, medida, impuestos, vencimiento,"
                + " por_margen, margen, precio_mayor, es_producto, es_insumo, es_receta, url_imagen, caracterizar,"
                + " usa_imei, usa_lotes FROM producto_ficha WHERE producto_id = ?")) {
            ps.setLong(1, productoId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    f.descripcion = rs.getString(1);
                    f.garantia = rs.getString(2);
                    f.medida = rs.getString(3);
                    for (String i : rs.getString(4).split(";")) {
                        if (!i.isBlank()) {
                            f.impuestos.add(i);
                        }
                    }
                    f.vencimiento = rs.getString(5) == null ? null : java.time.LocalDate.parse(rs.getString(5));
                    f.porMargen = rs.getInt(6) == 1;
                    f.margenCentesimas = rs.getLong(7);
                    f.precioMayor = rs.getLong(8);
                    f.esProducto = rs.getInt(9) == 1;
                    f.esInsumo = rs.getInt(10) == 1;
                    f.esReceta = rs.getInt(11) == 1;
                    f.urlImagen = rs.getString(12);
                    f.caracterizar = rs.getInt(13) == 1;
                    f.usaImei = rs.getInt(14) == 1;
                    f.usaLotes = rs.getInt(15) == 1;
                } else {
                    Producto p = buscarProductoPorId(productoId);
                    if (p != null) {
                        f.medida = rutas.modelo.FichaProducto.medidaDe(p.getUnidad());
                    }
                }
            }
        }
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT bodega_id, stock, minimo FROM producto_bodega WHERE producto_id = ? ORDER BY bodega_id")) {
            ps.setLong(1, productoId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    f.bodegas.put(rs.getLong(1), new long[]{rs.getLong(2), rs.getLong(3)});
                }
            }
        }
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT nombre, valor FROM producto_caracteristica WHERE producto_id = ? ORDER BY id")) {
            ps.setLong(1, productoId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    f.caracteristicas.add(new String[]{rs.getString(1), rs.getString(2)});
                }
            }
        }
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT imei FROM producto_imei WHERE producto_id = ? ORDER BY rowid")) {
            ps.setLong(1, productoId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    f.imeis.add(rs.getString(1));
                }
            }
        }
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT numero, vence, cantidad FROM producto_lote WHERE producto_id = ? ORDER BY vence IS NULL, vence, id")) {
            ps.setLong(1, productoId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    f.lotes.add(new rutas.modelo.Lote(rs.getString(1),
                            rs.getString(2) == null ? null : java.time.LocalDate.parse(rs.getString(2)), rs.getLong(3)));
                }
            }
        }
        return f;
    }

    /** Guarda la ficha completa (reemplaza la anterior). Todo o nada. */
    public void guardarFicha(long productoId, rutas.modelo.FichaProducto f) throws SQLException {
        boolean propia = con.getAutoCommit();
        if (propia) {
            con.setAutoCommit(false);
        }
        try {
            // los IMEI son unicos en toda la tienda: se avisa claro si otro producto ya lo tiene
            try (PreparedStatement ps = con.prepareStatement(
                    "SELECT p.nombre FROM producto_imei i JOIN productos p ON p.id = i.producto_id"
                    + " WHERE i.imei = ? AND i.producto_id <> ?")) {
                for (String imei : f.usaImei ? f.imeis : List.<String>of()) {
                    ps.setString(1, imei);
                    ps.setLong(2, productoId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            throw new IllegalArgumentException("El IMEI " + imei + " ya está en «" + rs.getString(1) + "»");
                        }
                    }
                }
            }
            try (PreparedStatement ps = con.prepareStatement("INSERT OR REPLACE INTO producto_ficha(producto_id,"
                    + " descripcion, garantia, medida, impuestos, vencimiento, por_margen, margen, precio_mayor,"
                    + " es_producto, es_insumo, es_receta, url_imagen, caracterizar, usa_imei, usa_lotes)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                ps.setLong(1, productoId);
                ps.setString(2, f.descripcion == null ? "" : f.descripcion.trim());
                ps.setString(3, f.garantia == null ? "" : f.garantia.trim());
                ps.setString(4, f.medida);
                ps.setString(5, String.join(";", f.impuestos));
                ps.setString(6, f.vencimiento == null ? null : f.vencimiento.toString());
                ps.setInt(7, f.porMargen ? 1 : 0);
                ps.setLong(8, f.margenCentesimas);
                ps.setLong(9, f.precioMayor);
                ps.setInt(10, f.esProducto ? 1 : 0);
                ps.setInt(11, f.esInsumo ? 1 : 0);
                ps.setInt(12, f.esReceta ? 1 : 0);
                ps.setString(13, f.urlImagen == null ? "" : f.urlImagen.trim());
                ps.setInt(14, f.caracterizar ? 1 : 0);
                ps.setInt(15, f.usaImei ? 1 : 0);
                ps.setInt(16, f.usaLotes ? 1 : 0);
                ps.executeUpdate();
            }
            for (String t : new String[]{"producto_bodega", "producto_caracteristica", "producto_imei", "producto_lote"}) {
                try (PreparedStatement ps = con.prepareStatement("DELETE FROM " + t + " WHERE producto_id = ?")) {
                    ps.setLong(1, productoId);
                    ps.executeUpdate();
                }
            }
            try (PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO producto_bodega(producto_id, bodega_id, stock, minimo) VALUES(?,?,?,?)")) {
                for (Map.Entry<Long, long[]> e : f.bodegas.entrySet()) {
                    if (e.getKey() == rutas.modelo.Bodega.PUNTO_DE_VENTA) {
                        continue; // el punto de venta es el stock del producto
                    }
                    ps.setLong(1, productoId);
                    ps.setLong(2, e.getKey());
                    ps.setLong(3, e.getValue()[0]);
                    ps.setLong(4, e.getValue()[1]);
                    ps.executeUpdate();
                }
            }
            if (f.caracterizar) {
                try (PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO producto_caracteristica(producto_id, nombre, valor) VALUES(?,?,?)")) {
                    for (String[] c : f.caracteristicas) {
                        ps.setLong(1, productoId);
                        ps.setString(2, c[0].trim());
                        ps.setString(3, c[1].trim());
                        ps.executeUpdate();
                    }
                }
            }
            if (f.usaImei) {
                try (PreparedStatement ps = con.prepareStatement("INSERT INTO producto_imei(imei, producto_id) VALUES(?,?)")) {
                    for (String i : f.imeis) {
                        ps.setString(1, i);
                        ps.setLong(2, productoId);
                        ps.executeUpdate();
                    }
                }
            }
            if (f.usaLotes) {
                try (PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO producto_lote(producto_id, numero, vence, cantidad) VALUES(?,?,?,?)")) {
                    for (rutas.modelo.Lote l : f.lotes) {
                        ps.setLong(1, productoId);
                        ps.setString(2, l.getNumero());
                        ps.setString(3, l.getVence() == null ? null : l.getVence().toString());
                        ps.setLong(4, l.getCantidad());
                        ps.executeUpdate();
                    }
                }
            }
            if (propia) {
                con.commit();
            }
        } catch (SQLException | RuntimeException e) {
            if (propia) {
                con.rollback();
            }
            throw e;
        } finally {
            if (propia) {
                con.setAutoCommit(true);
            }
        }
    }

    /** Fecha de vencimiento mas cercana de cada producto (la suya o la de un lote con cantidad). */
    public Map<Long, java.time.LocalDate> vencimientos() throws SQLException {
        Map<Long, java.time.LocalDate> m = new java.util.HashMap<>();
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT producto_id, MIN(v) FROM ("
                     + " SELECT producto_id, vencimiento AS v FROM producto_ficha WHERE vencimiento IS NOT NULL"
                     + " UNION ALL SELECT l.producto_id, l.vence FROM producto_lote l"
                     + " JOIN producto_ficha f ON f.producto_id = l.producto_id AND f.usa_lotes = 1"
                     + " WHERE l.vence IS NOT NULL AND l.cantidad > 0) GROUP BY producto_id")) {
            while (rs.next()) {
                m.put(rs.getLong(1), java.time.LocalDate.parse(rs.getString(2)));
            }
        }
        return m;
    }

    // ------------------------------------------------------------------
    //  Compras a proveedores
    // ------------------------------------------------------------------

    /** Datos de una compra en espera para la lista. */
    public static class CompraEspera {
        private final long id;
        private final String proveedor;
        private final int productos;
        private final long total;
        private final LocalDateTime creada;

        CompraEspera(long id, String proveedor, int productos, long total, LocalDateTime creada) {
            this.id = id;
            this.proveedor = proveedor;
            this.productos = productos;
            this.total = total;
            this.creada = creada;
        }

        public long getId() {
            return id;
        }

        public String getProveedor() {
            return proveedor;
        }

        public int getProductos() {
            return productos;
        }

        public long getTotal() {
            return total;
        }

        public LocalDateTime getCreada() {
            return creada;
        }
    }

    /** Una compra en espera vuelta a armar, con avisos si algun producto cambio. */
    public static class CompraRetomada {
        private final Compra compra;
        private final List<String> avisos;

        CompraRetomada(Compra compra, List<String> avisos) {
            this.compra = compra;
            this.avisos = avisos;
        }

        public Compra getCompra() {
            return compra;
        }

        public List<String> getAvisos() {
            return avisos;
        }
    }

    /** Guarda (o actualiza) una compra en espera. No sube el stock. Devuelve su id. */
    public long guardarCompraEspera(Long id, long usuarioId, Compra c) throws SQLException {
        if (c.getLineas().isEmpty()) {
            throw new IllegalArgumentException("Agregue al menos un producto a la compra");
        }
        try {
            return enTransaccion(() -> {
                long compraId = encabezadoCompra(id, usuarioId, c, false);
                lineasCompra(compraId, c);
                return compraId;
            });
        } catch (SQLException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new SQLException(e.getMessage(), e);
        }
    }

    /** Inserta o actualiza la fila de la compra. Si guardar es true queda con numero y fecha. */
    private long encabezadoCompra(Long id, long usuarioId, Compra c, boolean guardar) throws SQLException {
        long compraId;
        if (id == null) {
            try (PreparedStatement ps = con.prepareStatement("INSERT INTO compras(estado, usuario_id, creada)"
                    + " VALUES('ESPERA',?,?)", Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, usuarioId);
                ps.setString(2, ahora());
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    rs.next();
                    compraId = rs.getLong(1);
                }
            }
        } else {
            compraId = id;
        }
        if (id != null && !guardar) { // se vuelven a escribir las lineas
            try (PreparedStatement ps = con.prepareStatement(
                    "DELETE FROM compra_lineas WHERE compra_id = (SELECT id FROM compras WHERE id = ? AND estado = 'ESPERA')")) {
                ps.setLong(1, compraId);
                ps.executeUpdate();
            }
        }
        try (PreparedStatement ps = con.prepareStatement("UPDATE compras SET proveedor = ?, factura = ?, bodega_id = ?,"
                + " observaciones = ?, descuento_general = ?, bruto = ?, descuentos = ?, impuestos = ?, total = ?"
                + (guardar ? ", estado = 'GUARDADA', guardada = ?, usuario_id = ?,"
                        + " numero = (SELECT COALESCE(MAX(numero), 0) + 1 FROM compras)" : "")
                + " WHERE id = ? AND estado = 'ESPERA'")) {
            int i = 1;
            ps.setString(i++, vacioANull(c.getProveedor()));
            ps.setString(i++, vacioANull(c.getFactura()));
            ps.setLong(i++, c.getBodegaId());
            ps.setString(i++, vacioANull(c.getObservaciones()));
            ps.setLong(i++, c.getDescuentoGeneral());
            ps.setLong(i++, c.getBruto());
            ps.setLong(i++, c.getDescuentos());
            ps.setLong(i++, c.getImpuestos());
            ps.setLong(i++, c.getTotal());
            if (guardar) {
                ps.setString(i++, ahora());
                ps.setLong(i++, usuarioId);
            }
            ps.setLong(i, compraId);
            if (ps.executeUpdate() == 0) {
                throw new IllegalStateException("Esa compra ya no está en espera");
            }
        }
        return compraId;
    }

    private void lineasCompra(long compraId, Compra c) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("INSERT INTO compra_lineas(compra_id, producto_id, nombre,"
                + " cantidad, base, impuesto, descuento_pct, precio_venta, subtotal, costo_escrito)"
                + " VALUES(?,?,?,?,?,?,?,?,?,?)")) {
            // al reves para que al leer por id queden en el mismo orden (lo nuevo arriba)
            for (int k = c.getLineas().size() - 1; k >= 0; k--) {
                Compra.Linea l = c.getLineas().get(k);
                ps.setLong(1, compraId);
                ps.setLong(2, l.getProducto().getId());
                ps.setString(3, l.getProducto().getNombre());
                ps.setLong(4, l.getCantidad());
                ps.setLong(5, l.getBase());
                ps.setLong(6, l.getImpuesto());
                ps.setLong(7, l.getDescuento());
                if (l.getPrecioVenta() == null) {
                    ps.setNull(8, java.sql.Types.INTEGER);
                } else {
                    ps.setLong(8, l.getPrecioVenta());
                }
                ps.setLong(9, l.getSubtotal());
                if (l.getCostoEscrito() == null) {
                    ps.setNull(10, java.sql.Types.INTEGER);
                } else {
                    ps.setLong(10, l.getCostoEscrito());
                }
                ps.executeUpdate();
            }
        }
    }

    public List<CompraEspera> listarComprasEspera() throws SQLException {
        List<CompraEspera> lista = new ArrayList<>();
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT c.id, c.proveedor, COUNT(l.id), c.total, c.creada FROM compras c"
                     + " LEFT JOIN compra_lineas l ON l.compra_id = c.id"
                     + " WHERE c.estado = 'ESPERA' GROUP BY c.id ORDER BY c.id")) {
            while (rs.next()) {
                lista.add(new CompraEspera(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getLong(4),
                        LocalDateTime.parse(rs.getString(5))));
            }
        }
        return lista;
    }

    /** Vuelve a armar una compra en espera tal como se dejo. */
    public CompraRetomada cargarCompraEspera(long id, Catalogo cat) throws SQLException {
        Compra c = new Compra();
        try (PreparedStatement ps = con.prepareStatement("SELECT proveedor, factura, bodega_id, observaciones,"
                + " descuento_general FROM compras WHERE id = ? AND estado = 'ESPERA'")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("Esa compra ya no está en espera");
                }
                c.setProveedor(rs.getString(1));
                c.setFactura(rs.getString(2));
                c.setBodegaId(rs.getLong(3));
                c.setObservaciones(rs.getString(4));
                c.setDescuentoGeneral(rs.getLong(5));
            }
        }
        List<String> avisos = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement("SELECT producto_id, nombre, cantidad, base, impuesto,"
                + " descuento_pct, precio_venta, costo_escrito FROM compra_lineas WHERE compra_id = ? ORDER BY id DESC")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long pid = rs.getLong(1);
                    Producto p = cat == null ? null : cat.buscarPorId(pid);
                    if (p == null) {
                        p = buscarProductoPorId(pid);
                        if (p == null) {
                            avisos.add("«" + rs.getString(2) + "» ya no existe y se quitó de la compra");
                            continue;
                        }
                        if (!p.isActivo()) {
                            avisos.add("«" + p.getNombre() + "» está desactivado");
                        }
                    }
                    Compra.Linea l = new Compra.Linea(p, rs.getLong(3), rs.getLong(4), rs.getLong(5));
                    l.setDescuento(rs.getLong(6));
                    l.setPrecioVenta(rs.getObject(7) == null ? null : rs.getLong(7));
                    if (rs.getObject(8) != null) {
                        l.setCosto(rs.getLong(8));
                    }
                    c.getLineas().add(l);
                }
            }
        }
        return new CompraRetomada(c, avisos);
    }

    public void borrarCompraEspera(long id) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("DELETE FROM compras WHERE id = ? AND estado = 'ESPERA'")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    /**
     * Guarda la compra: todo en una transaccion. Sube el stock en la bodega escogida, deja el costo
     * nuevo como precio de compra y cambia el precio de venta si se escribio otro. Devuelve el numero.
     * Si venia de espera (idEspera) esa misma fila queda guardada.
     */
    public long registrarCompra(Long idEspera, long usuarioId, Compra c) throws SQLException {
        c.validar();
        try {
            return enTransaccion(() -> {
                try (PreparedStatement ps = con.prepareStatement("SELECT 1 FROM bodegas WHERE id = ? AND activo = 1")) {
                    ps.setLong(1, c.getBodegaId());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            throw new IllegalArgumentException("Esa bodega no existe o está desactivada");
                        }
                    }
                }
                for (Compra.Linea l : c.getLineas()) {
                    Producto p = buscarProductoPorId(l.getProducto().getId());
                    if (p == null) {
                        throw new IllegalArgumentException("«" + l.getProducto().getNombre() + "» ya no existe");
                    }
                    if (!p.isActivo()) {
                        throw new IllegalArgumentException("«" + p.getNombre() + "» está desactivado. Actívelo en"
                                + " Productos o quítelo de la compra");
                    }
                }
                long compraId = encabezadoCompra(idEspera, usuarioId, c, false);
                lineasCompra(compraId, c);
                encabezadoCompra(compraId, usuarioId, c, true);
                boolean punto = c.getBodegaId() == rutas.modelo.Bodega.PUNTO_DE_VENTA;
                try (PreparedStatement precio = con.prepareStatement("UPDATE productos SET precio_compra = ?,"
                        + " precio_venta = COALESCE(?, precio_venta), actualizado = ?"
                        + (punto ? ", stock = stock + (CASE WHEN maneja_inventario = 1 THEN ? ELSE 0 END)" : "")
                        + " WHERE id = ?");
                     PreparedStatement bodega = con.prepareStatement("INSERT INTO producto_bodega(producto_id,"
                             + " bodega_id, stock) VALUES(?,?,?) ON CONFLICT(producto_id, bodega_id)"
                             + " DO UPDATE SET stock = stock + excluded.stock")) {
                    for (Compra.Linea l : c.getLineas()) {
                        int i = 1;
                        precio.setLong(i++, l.getCostoNeto());
                        if (l.getPrecioVenta() == null) {
                            precio.setNull(i++, java.sql.Types.INTEGER);
                        } else {
                            precio.setLong(i++, l.getPrecioVenta());
                        }
                        precio.setString(i++, ahora());
                        if (punto) {
                            precio.setLong(i++, l.getCantidad());
                        }
                        precio.setLong(i, l.getProducto().getId());
                        if (precio.executeUpdate() == 0) {
                            throw new IllegalArgumentException("«" + l.getProducto().getNombre() + "» ya no existe");
                        }
                        if (!punto) {
                            bodega.setLong(1, l.getProducto().getId());
                            bodega.setLong(2, c.getBodegaId());
                            bodega.setLong(3, l.getCantidad());
                            bodega.executeUpdate();
                        }
                    }
                }
                try (PreparedStatement ps = con.prepareStatement("SELECT numero FROM compras WHERE id = ?")) {
                    ps.setLong(1, compraId);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        return rs.getLong(1);
                    }
                }
            });
        } catch (SQLException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new SQLException(e.getMessage(), e);
        }
    }

    /** Numero que tendra la proxima compra guardada. */
    public long siguienteNumeroCompra() throws SQLException {
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(numero), 0) + 1 FROM compras")) {
            return rs.getLong(1);
        }
    }

    // ------------------------------------------------------------------
    //  Historial de compras
    // ------------------------------------------------------------------

    /** Una compra ya guardada, como sale en el historial. */
    public static class CompraGuardada {
        private final long id;
        private final long numero;
        private final long bodegaId;
        private final String bodega;
        private final String factura;
        private final LocalDateTime fecha;
        private final String proveedor;
        private final String atendio;
        private final long total;
        private final boolean anulada;

        CompraGuardada(long id, long numero, long bodegaId, String bodega, String factura, LocalDateTime fecha,
                String proveedor, String atendio, long total, boolean anulada) {
            this.id = id;
            this.numero = numero;
            this.bodegaId = bodegaId;
            this.bodega = bodega;
            this.factura = factura == null ? "" : factura;
            this.fecha = fecha;
            this.proveedor = proveedor == null ? "" : proveedor;
            this.atendio = atendio == null ? "" : atendio;
            this.total = total;
            this.anulada = anulada;
        }

        public long getId() { return id; }
        public long getNumero() { return numero; }
        public long getBodegaId() { return bodegaId; }
        public String getBodega() { return bodega; }
        public String getFactura() { return factura; }
        public LocalDateTime getFecha() { return fecha; }
        public String getProveedor() { return proveedor; }
        public String getAtendio() { return atendio; }
        public long getTotal() { return total; }
        public boolean isAnulada() { return anulada; }
    }

    /** Un producto de una compra guardada (los valores que tenia ese dia). */
    public static class LineaGuardada {
        public final String nombre;
        public final String codigo;
        public final Unidad unidad;
        public final long cantidad;
        public final long base;
        public final long impuesto;
        public final long descuento;
        public final long costo;
        public final Long precioVenta;
        public final long subtotal;

        LineaGuardada(String nombre, String codigo, Unidad unidad, long cantidad, long base, long impuesto,
                long descuento, long costo, Long precioVenta, long subtotal) {
            this.nombre = nombre;
            this.codigo = codigo;
            this.unidad = unidad;
            this.cantidad = cantidad;
            this.base = base;
            this.impuesto = impuesto;
            this.descuento = descuento;
            this.costo = costo;
            this.precioVenta = precioVenta;
            this.subtotal = subtotal;
        }
    }

    /** Todo lo de una compra guardada: encabezado, productos, totales y, si se anulo, quien y por que. */
    public static class DetalleCompra {
        public CompraGuardada compra;
        public String observaciones = "";
        public long bruto;
        public long descuentos;
        public long impuestos;
        public long total;
        public final List<LineaGuardada> lineas = new ArrayList<>();
        public LocalDateTime anuladaEn;
        public String anuladaPor = "";
        public String motivo = "";
    }

    private static final String CONSULTA_COMPRAS = "SELECT c.id, c.numero, c.bodega_id, COALESCE(b.nombre, ''),"
            + " c.factura, c.guardada, c.proveedor, u.nombre, c.total, c.anulada FROM compras c"
            + " LEFT JOIN bodegas b ON b.id = c.bodega_id LEFT JOIN usuarios u ON u.id = c.usuario_id"
            + " WHERE c.estado = 'GUARDADA'";

    private static CompraGuardada leerCompraGuardada(ResultSet rs) throws SQLException {
        return new CompraGuardada(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4), rs.getString(5),
                LocalDateTime.parse(rs.getString(6)), rs.getString(7), rs.getString(8), rs.getLong(9), rs.getInt(10) == 1);
    }

    /**
     * Compras guardadas entre dos momentos (los dos incluidos, al minuto), las mas nuevas primero.
     * anuladas: null = todas, false = solo pagadas, true = solo anuladas. bodegaId null = todas.
     * texto busca en el numero, la factura del proveedor y el nombre del proveedor.
     */
    public List<CompraGuardada> listarCompras(LocalDateTime desde, LocalDateTime hasta, Boolean anuladas, Long bodegaId,
            String texto) throws SQLException {
        if (desde != null && hasta != null && desde.isAfter(hasta)) {
            throw new IllegalArgumentException("La fecha inicial es después de la fecha final");
        }
        StringBuilder sql = new StringBuilder(CONSULTA_COMPRAS);
        List<Object> params = new ArrayList<>();
        if (desde != null) {
            sql.append(" AND c.guardada >= ?");
            params.add(desde.withSecond(0).withNano(0).toString());
        }
        if (hasta != null) {
            sql.append(" AND c.guardada < ?"); // hasta las 11:59 incluye todo ese minuto
            params.add(hasta.withSecond(0).withNano(0).plusMinutes(1).toString());
        }
        if (anuladas != null) {
            sql.append(" AND c.anulada = ?");
            params.add(anuladas ? 1 : 0);
        }
        if (bodegaId != null) {
            sql.append(" AND c.bodega_id = ?");
            params.add(bodegaId);
        }
        String t = texto == null ? "" : texto.trim();
        if (!t.isEmpty()) {
            sql.append(" AND (CAST(c.numero AS TEXT) = ? OR c.factura LIKE ? OR c.proveedor LIKE ?)");
            String sinCeros = t.replaceFirst("^0+(?=\\d)", "");
            params.add(sinCeros);
            params.add("%" + t + "%");
            params.add("%" + t + "%");
        }
        sql.append(" ORDER BY c.guardada DESC, c.numero DESC");
        List<CompraGuardada> lista = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    lista.add(leerCompraGuardada(rs));
                }
            }
        }
        return lista;
    }

    /** El detalle completo de una compra guardada. */
    public DetalleCompra detalleCompra(long id) throws SQLException {
        DetalleCompra d = new DetalleCompra();
        try (PreparedStatement ps = con.prepareStatement(CONSULTA_COMPRAS + " AND c.id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("Esa compra no existe");
                }
                d.compra = leerCompraGuardada(rs);
            }
        }
        try (PreparedStatement ps = con.prepareStatement("SELECT c.observaciones, c.bruto, c.descuentos, c.impuestos,"
                + " c.total, c.anulada_en, u.nombre, c.motivo_anulacion FROM compras c"
                + " LEFT JOIN usuarios u ON u.id = c.anulada_por WHERE c.id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                d.observaciones = rs.getString(1) == null ? "" : rs.getString(1);
                d.bruto = rs.getLong(2);
                d.descuentos = rs.getLong(3);
                d.impuestos = rs.getLong(4);
                d.total = rs.getLong(5);
                d.anuladaEn = rs.getString(6) == null ? null : LocalDateTime.parse(rs.getString(6));
                d.anuladaPor = rs.getString(7) == null ? "" : rs.getString(7);
                d.motivo = rs.getString(8) == null ? "" : rs.getString(8);
            }
        }
        try (PreparedStatement ps = con.prepareStatement("SELECT l.nombre, p.codigo, p.unidad, l.cantidad, l.base,"
                + " l.impuesto, l.descuento_pct, l.costo_escrito, l.precio_venta, l.subtotal FROM compra_lineas l"
                + " LEFT JOIN productos p ON p.id = l.producto_id WHERE l.compra_id = ? ORDER BY l.id DESC")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long base = rs.getLong(5);
                    long imp = rs.getLong(6);
                    long costo = rs.getObject(8) != null ? rs.getLong(8) : Math.round(base * (10000.0 + imp) / 10000.0);
                    d.lineas.add(new LineaGuardada(rs.getString(1), rs.getString(2),
                            rs.getString(3) == null ? Unidad.UNIDAD : Unidad.valueOf(rs.getString(3)), rs.getLong(4),
                            base, imp, rs.getLong(7), costo, rs.getObject(9) == null ? null : rs.getLong(9),
                            rs.getLong(10)));
                }
            }
        }
        return d;
    }

    /**
     * Lo que pasaria al anular: productos del punto de venta que quedarian con stock negativo
     * (ya se vendio parte de lo que llego). No cambia nada.
     */
    public List<String> revisarAnulacion(long id) throws SQLException {
        List<String> avisos = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement("SELECT p.nombre, p.stock, SUM(l.cantidad), p.unidad"
                + " FROM compra_lineas l JOIN compras c ON c.id = l.compra_id JOIN productos p ON p.id = l.producto_id"
                + " WHERE c.id = ? AND c.bodega_id = 1 AND p.maneja_inventario = 1"
                + " GROUP BY p.id HAVING p.stock - SUM(l.cantidad) < 0 ORDER BY p.nombre")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    avisos.add("«" + rs.getString(1) + "» queda en " + cantidadTexto(rs.getLong(2) - rs.getLong(3),
                            Unidad.valueOf(rs.getString(4))));
                }
            }
        }
        return avisos;
    }

    private static String cantidadTexto(long milesimas, Unidad u) {
        java.text.DecimalFormat f = new java.text.DecimalFormat("#,##0.###",
                java.text.DecimalFormatSymbols.getInstance(new java.util.Locale("es", "CO")));
        String n = f.format(milesimas / 1000.0);
        return u == Unidad.UNIDAD ? n : n + (u == Unidad.KILO ? " kg" : " lb");
    }

    /**
     * Anula una compra guardada: devuelve (resta) el stock que entro, todo en una transaccion.
     * En el punto de venta el stock puede quedar negativo (ya se vendio); en otra bodega no
     * se puede sacar mas de lo que hay. El costo y el precio de venta del producto no se tocan.
     */
    public void anularCompra(long id, long usuarioId, String motivo) throws SQLException {
        String m = motivo == null ? "" : motivo.trim();
        if (m.isEmpty()) {
            throw new IllegalArgumentException("Escriba por qué se anula la compra");
        }
        try {
            enTransaccion(() -> {
                long bodegaId;
                try (PreparedStatement ps = con.prepareStatement(
                        "SELECT bodega_id, anulada FROM compras WHERE id = ? AND estado = 'GUARDADA'")) {
                    ps.setLong(1, id);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            throw new IllegalArgumentException("Esa compra no existe");
                        }
                        if (rs.getInt(2) == 1) {
                            throw new IllegalStateException("Esa compra ya estaba anulada");
                        }
                        bodegaId = rs.getLong(1);
                    }
                }
                List<long[]> lineas = new ArrayList<>(); // producto, cantidad
                try (PreparedStatement ps = con.prepareStatement("SELECT producto_id, SUM(cantidad) FROM compra_lineas"
                        + " WHERE compra_id = ? GROUP BY producto_id")) {
                    ps.setLong(1, id);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            lineas.add(new long[]{rs.getLong(1), rs.getLong(2)});
                        }
                    }
                }
                boolean punto = bodegaId == rutas.modelo.Bodega.PUNTO_DE_VENTA;
                for (long[] l : lineas) {
                    if (punto) {
                        try (PreparedStatement ps = con.prepareStatement("UPDATE productos SET stock = stock -"
                                + " (CASE WHEN maneja_inventario = 1 THEN ? ELSE 0 END), actualizado = ? WHERE id = ?")) {
                            ps.setLong(1, l[1]);
                            ps.setString(2, ahora());
                            ps.setLong(3, l[0]);
                            ps.executeUpdate();
                        }
                    } else {
                        long hay = 0;
                        try (PreparedStatement ps = con.prepareStatement(
                                "SELECT stock FROM producto_bodega WHERE producto_id = ? AND bodega_id = ?")) {
                            ps.setLong(1, l[0]);
                            ps.setLong(2, bodegaId);
                            try (ResultSet rs = ps.executeQuery()) {
                                hay = rs.next() ? rs.getLong(1) : 0;
                            }
                        }
                        if (hay < l[1]) {
                            Producto p = buscarProductoPorId(l[0]);
                            String nombre = p == null ? "un producto" : "«" + p.getNombre() + "»";
                            throw new IllegalArgumentException("En la bodega solo quedan "
                                    + cantidadTexto(hay, p == null ? Unidad.UNIDAD : p.getUnidad()) + " de " + nombre
                                    + ". Pase la mercancía a esa bodega antes de anular.");
                        }
                        try (PreparedStatement ps = con.prepareStatement("UPDATE producto_bodega SET stock = stock - ?"
                                + " WHERE producto_id = ? AND bodega_id = ?")) {
                            ps.setLong(1, l[1]);
                            ps.setLong(2, l[0]);
                            ps.setLong(3, bodegaId);
                            ps.executeUpdate();
                        }
                    }
                }
                try (PreparedStatement ps = con.prepareStatement("UPDATE compras SET anulada = 1, anulada_en = ?,"
                        + " anulada_por = ?, motivo_anulacion = ? WHERE id = ?")) {
                    ps.setString(1, ahora());
                    ps.setLong(2, usuarioId);
                    ps.setString(3, m);
                    ps.setLong(4, id);
                    ps.executeUpdate();
                }
                return null;
            });
        } catch (SQLException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new SQLException(e.getMessage(), e);
        }
    }

    /** Ids de los productos de una compra (para refrescar el catalogo despues de anular). */
    public List<Long> productosDeCompra(long id) throws SQLException {
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT DISTINCT producto_id FROM compra_lineas WHERE compra_id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
            }
        }
        return ids;
    }

    private static String vacioANull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    // ------------------------------------------------------------------
    //  Clientes: cuantas veces compra cada uno (para «Más frecuentes»)
    // ------------------------------------------------------------------

    /** Compras pagadas de un cliente: cuantas, cuanto en total y la ultima. */
    public static class Frecuencia {
        public int compras;
        public long total;
        public LocalDateTime ultima;
    }

    /** Por cliente (nombre normalizado, como la clave del arbol de clientes). */
    public java.util.Map<String, Frecuencia> comprasPorCliente() throws SQLException {
        java.util.Map<String, Frecuencia> m = new java.util.HashMap<>();
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT cliente, COUNT(*), SUM(total), MAX(pagada) FROM ventas"
                     + " WHERE estado = 'PAGADA' AND cliente IS NOT NULL AND TRIM(cliente) <> '' GROUP BY cliente")) {
            while (rs.next()) {
                String clave = rutas.modelo.Geo.normalizar(rs.getString(1));
                Frecuencia f = m.computeIfAbsent(clave, k -> new Frecuencia());
                f.compras += rs.getInt(2);
                f.total += rs.getLong(3);
                String u = rs.getString(4);
                LocalDateTime d = u == null ? null : LocalDateTime.parse(u);
                if (d != null && (f.ultima == null || d.isAfter(f.ultima))) {
                    f.ultima = d;
                }
            }
        }
        return m;
    }

    /** Una venta de un cliente (para «Ver sus compras»). */
    public static class VentaCliente {
        public long numero;
        public LocalDateTime pagada;
        public long total;
        public int productos;
    }

    /** Las ultimas ventas pagadas de un cliente, la mas nueva primero. */
    public List<VentaCliente> ventasDeCliente(String nombre, int maximo) throws SQLException {
        String clave = rutas.modelo.Geo.normalizar(nombre);
        List<VentaCliente> l = new ArrayList<>();
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT v.numero, v.pagada, v.total, v.cliente,"
                     + " (SELECT COUNT(*) FROM venta_lineas x WHERE x.venta_id = v.id) FROM ventas v"
                     + " WHERE v.estado = 'PAGADA' AND v.cliente IS NOT NULL ORDER BY v.pagada DESC")) {
            while (rs.next() && l.size() < maximo) {
                if (!clave.equals(rutas.modelo.Geo.normalizar(rs.getString(4)))) {
                    continue; // se compara sin tildes ni mayusculas, igual que el arbol
                }
                VentaCliente v = new VentaCliente();
                v.numero = rs.getLong(1);
                v.pagada = LocalDateTime.parse(rs.getString(2));
                v.total = rs.getLong(3);
                v.productos = rs.getInt(5);
                l.add(v);
            }
        }
        return l;
    }

    /** Si al cliente le cambian el nombre, sus ventas de antes siguen siendo suyas. */
    public void renombrarClienteEnVentas(String viejo, String nuevo) throws SQLException {
        String clave = rutas.modelo.Geo.normalizar(viejo);
        List<Long> ids = new ArrayList<>();
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, cliente FROM ventas WHERE cliente IS NOT NULL")) {
            while (rs.next()) {
                if (clave.equals(rutas.modelo.Geo.normalizar(rs.getString(2)))) {
                    ids.add(rs.getLong(1));
                }
            }
        }
        boolean antes = con.getAutoCommit();
        con.setAutoCommit(false);
        try (PreparedStatement ps = con.prepareStatement("UPDATE ventas SET cliente = ? WHERE id = ?")) {
            for (long id : ids) {
                ps.setString(1, nuevo);
                ps.setLong(2, id);
                ps.executeUpdate();
            }
            con.commit(); // todas o ninguna
        } catch (SQLException | RuntimeException e) {
            con.rollback();
            throw e;
        } finally {
            con.setAutoCommit(antes);
        }
    }
}
