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
                    + " metodo TEXT NOT NULL CHECK (metodo IN ('EFECTIVO','NEQUI','DAVIPLATA','TRANSFERENCIA')),"
                    + " valor INTEGER NOT NULL)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_lineas_venta ON venta_lineas(venta_id)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_pagos_venta ON venta_pagos(venta_id)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_ventas_estado ON ventas(estado)");
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
             ResultSet rs = st.executeQuery("SELECT id, usuario, nombre, rol, activo FROM usuarios ORDER BY nombre")) {
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
                "SELECT id, usuario, nombre, rol, activo, sal, hash, iteraciones FROM usuarios WHERE usuario = ?")) {
            ps.setString(1, u);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    // se hace el mismo calculo para no delatar que el usuario no existe
                    Seguridad.hash(clave, Seguridad.crearSal(), iteraciones);
                    return null;
                }
                boolean bien = Seguridad.coincide(clave, Seguridad.deTexto(rs.getString(6)), rs.getInt(8),
                        Seguridad.deTexto(rs.getString(7)));
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
                rs.getInt(5) == 1);
    }

    // ------------------------------------------------------------------
    //  Productos
    // ------------------------------------------------------------------

    private static final String CAMPOS_PRODUCTO =
            "id, codigo, nombre, unidad, precio_venta, precio_compra, stock, minimo, foto, activo, categoria_id";

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
                + " precio_venta, precio_compra, stock, minimo, foto, activo, actualizado, categoria_id)"
                + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
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
                + " actualizado = ?, categoria_id = ? WHERE id = ?")) {
            llenar(ps, p);
            ps.setLong(13, p.getId());
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
    }

    /** Busca por codigo aunque el producto este desactivado. */
    public Producto buscarPorCodigoEnBase(String codigo) throws SQLException {
        return buscarProducto("codigo", codigo);
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
                rs.getObject(11) == null ? null : rs.getLong(11));
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
                            + " creada, total) VALUES('ESPERA',?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
                        ps.setString(1, vacioANull(nombre));
                        ps.setLong(2, usuarioId);
                        ps.setString(3, ahora());
                        ps.setLong(4, c.getTotal());
                        ps.executeUpdate();
                        try (ResultSet rs = ps.getGeneratedKeys()) {
                            rs.next();
                            ventaId = rs.getLong(1);
                        }
                    }
                } else {
                    ventaId = id;
                    try (PreparedStatement ps = con.prepareStatement(
                            "UPDATE ventas SET nombre_espera = ?, total = ? WHERE id = ? AND estado = 'ESPERA'")) {
                        ps.setString(1, vacioANull(nombre));
                        ps.setLong(2, c.getTotal());
                        ps.setLong(3, ventaId);
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
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT nombre_espera FROM ventas WHERE id = ? AND estado = 'ESPERA'")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("Esa venta ya no está en espera");
                }
                nombre = rs.getString(1);
            }
        }
        Carrito c = new Carrito();
        List<String> avisos = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement("SELECT producto_id, nombre, cantidad, precio, descuento,"
                + " subtotal, por_valor FROM venta_lineas WHERE venta_id = ? ORDER BY id")) {
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
                    c.agregarLinea(new LineaVenta(p, rs.getLong(3), rs.getLong(4), rs.getLong(5),
                            porValor ? rs.getLong(6) + rs.getLong(5) : null));
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
                            + " cliente, creada, pagada, total, recibido, vueltas) VALUES(?,'PAGADA',?,?,?,?,?,?,?)",
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
                        ps.executeUpdate();
                        try (ResultSet rs = ps.getGeneratedKeys()) {
                            rs.next();
                            ventaId = rs.getLong(1);
                        }
                    }
                } else {
                    ventaId = idEspera;
                    try (PreparedStatement ps = con.prepareStatement("UPDATE ventas SET numero = ?, estado = 'PAGADA',"
                            + " usuario_id = ?, cliente = ?, pagada = ?, total = ?, recibido = ?, vueltas = ?"
                            + " WHERE id = ? AND estado = 'ESPERA'")) {
                        ps.setLong(1, numero);
                        ps.setLong(2, usuarioId);
                        ps.setString(3, vacioANull(cliente));
                        ps.setString(4, ahora());
                        ps.setLong(5, c.getTotal());
                        ps.setLong(6, comprobado.getRecibidoEfectivo());
                        ps.setLong(7, comprobado.getVueltas());
                        ps.setLong(8, ventaId);
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
                + " cantidad, precio, costo, descuento, subtotal, por_valor) VALUES(?,?,?,?,?,?,?,?,?)")) {
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
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE productos SET stock = stock - ?, actualizado = ? WHERE id = ?")) {
            for (LineaVenta l : c.getLineas()) {
                ps.setLong(1, l.getCantidad());
                ps.setString(2, ahora());
                ps.setLong(3, l.getProducto().getId());
                if (ps.executeUpdate() == 0) {
                    throw new IllegalStateException("«" + l.getProducto().getNombre() + "» ya no existe");
                }
            }
        }
    }

    private static String vacioANull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
