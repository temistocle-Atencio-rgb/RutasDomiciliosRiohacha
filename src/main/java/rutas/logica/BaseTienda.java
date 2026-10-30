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
import java.util.List;
import java.util.concurrent.Callable;
import rutas.modelo.Geo;
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
        }
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
            "id, codigo, nombre, unidad, precio_venta, precio_compra, stock, minimo, foto, activo";

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
                + " precio_venta, precio_compra, stock, minimo, foto, activo, actualizado) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
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
                + " actualizado = ? WHERE id = ?")) {
            llenar(ps, p);
            ps.setLong(12, p.getId());
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
                rs.getLong(5), rs.getLong(6), rs.getLong(7), rs.getLong(8), rs.getString(9), rs.getInt(10) == 1);
    }
}
