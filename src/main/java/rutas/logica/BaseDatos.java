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
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import rutas.estructuras.ArbolAVL;
import rutas.estructuras.Arista;
import rutas.estructuras.Grafo;
import rutas.estructuras.NodoAVL;
import rutas.modelo.Cliente;
import rutas.modelo.Geo;
import rutas.modelo.Incidente;
import rutas.modelo.Parada;
import rutas.modelo.Ruta;
import rutas.modelo.TipoIncidente;

/**
 * Base de datos SQLite del usuario (un archivo domiruta.db por ciudad).
 * Guarda clientes, direcciones corregidas, novedades en las vias, el punto de
 * salida y el historial de rutas con sus paradas.
 *
 * SQLite va dentro del programa: no hay que instalar ningun servidor.
 * Al cargar, los clientes se pasan a un arbol AVL para buscarlos rapido por nombre.
 */
public class BaseDatos implements AutoCloseable {

    private static final int VERSION = 1;

    private final Connection con;
    private final File archivo;

    public BaseDatos(File archivo) throws IOException {
        this.archivo = archivo;
        archivo.getParentFile().mkdirs();
        try {
            con = DriverManager.getConnection("jdbc:sqlite:" + archivo.getAbsolutePath());
            try (Statement st = con.createStatement()) {
                st.execute("PRAGMA foreign_keys = ON");
                st.execute("PRAGMA journal_mode = WAL"); // mas seguro si se cierra de golpe
            }
            crearTablas();
        } catch (SQLException e) {
            throw new IOException("No se pudo abrir la base de datos " + archivo + ": " + e.getMessage(), e);
        }
    }

    private void crearTablas() throws SQLException {
        try (Statement st = con.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS configuracion ("
                    + " clave TEXT PRIMARY KEY, valor TEXT NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS clientes ("
                    + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + " clave TEXT NOT NULL UNIQUE,"          // nombre normalizado (sin tildes ni mayusculas)
                    + " nombre TEXT NOT NULL,"
                    + " telefono TEXT NOT NULL DEFAULT '',"
                    + " direccion TEXT NOT NULL,"
                    + " nota TEXT NOT NULL DEFAULT '',"
                    + " lat REAL NOT NULL, lon REAL NOT NULL,"
                    + " creado TEXT NOT NULL DEFAULT (datetime('now','localtime')))");
            st.execute("CREATE TABLE IF NOT EXISTS direcciones_corregidas ("
                    + " texto TEXT PRIMARY KEY, lat REAL NOT NULL, lon REAL NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS novedades ("
                    + " via INTEGER PRIMARY KEY, tipo TEXT NOT NULL, nota TEXT NOT NULL DEFAULT '',"
                    + " fecha TEXT NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS rutas ("
                    + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + " fecha TEXT NOT NULL,"
                    + " lote TEXT NOT NULL,"                   // rutas calculadas juntas (varias motos)
                    + " moto INTEGER NOT NULL DEFAULT 1,"
                    + " criterio TEXT NOT NULL, metodo TEXT NOT NULL,"
                    + " salida TEXT NOT NULL, metros REAL NOT NULL, segundos REAL NOT NULL,"
                    + " regreso INTEGER NOT NULL,"
                    + " base_nombre TEXT NOT NULL, base_direccion TEXT NOT NULL,"
                    + " base_lat REAL NOT NULL, base_lon REAL NOT NULL, base_nodo INTEGER NOT NULL,"
                    + " nodos TEXT NOT NULL)");               // recorrido: indices de nodos separados por coma
            st.execute("CREATE TABLE IF NOT EXISTS paradas_ruta ("
                    + " ruta_id INTEGER NOT NULL REFERENCES rutas(id) ON DELETE CASCADE,"
                    + " orden INTEGER NOT NULL,"
                    + " nombre TEXT NOT NULL, direccion TEXT NOT NULL,"
                    + " lat REAL NOT NULL, lon REAL NOT NULL, nodo INTEGER NOT NULL,"
                    + " urgente INTEGER NOT NULL DEFAULT 0, llegada TEXT NOT NULL,"
                    + " PRIMARY KEY (ruta_id, orden))");
            st.execute("CREATE INDEX IF NOT EXISTS idx_rutas_fecha ON rutas(fecha)");
        }
        if (leerConfig("version") == null) {
            guardarConfig("version", String.valueOf(VERSION));
        }
    }

    public File getArchivo() {
        return archivo;
    }

    @Override
    public void close() {
        try {
            con.close();
        } catch (SQLException e) {
            // ya estaba cerrada
        }
    }

    // ------------------------------------------------------------------
    //  Configuracion (punto de salida, etc.)
    // ------------------------------------------------------------------

    public String leerConfig(String clave) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT valor FROM configuracion WHERE clave = ?")) {
            ps.setString(1, clave);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    public void guardarConfig(String clave, String valor) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "INSERT INTO configuracion(clave, valor) VALUES(?, ?) ON CONFLICT(clave) DO UPDATE SET valor = excluded.valor")) {
            ps.setString(1, clave);
            ps.setString(2, valor);
            ps.executeUpdate();
        }
    }

    /** Punto de salida guardado, o null si no hay. */
    public Parada cargarBase() throws SQLException {
        String nombre = leerConfig("base.nombre");
        if (nombre == null) {
            return null;
        }
        return new Parada(nombre, leerConfig("base.direccion"), Double.parseDouble(leerConfig("base.lat")),
                Double.parseDouble(leerConfig("base.lon")));
    }

    public void guardarBase(Parada p) throws SQLException {
        boolean antes = con.getAutoCommit();
        con.setAutoCommit(false);
        try {
            guardarConfig("base.nombre", p.getNombre());
            guardarConfig("base.direccion", p.getDireccion());
            guardarConfig("base.lat", String.valueOf(p.getLat()));
            guardarConfig("base.lon", String.valueOf(p.getLon()));
            con.commit();
        } catch (SQLException e) {
            con.rollback();
            throw e;
        } finally {
            con.setAutoCommit(antes);
        }
    }

    // ------------------------------------------------------------------
    //  Clientes
    // ------------------------------------------------------------------

    /** Lee los clientes y los organiza en un arbol AVL por nombre. */
    public ArbolAVL<String, Cliente> cargarClientes() throws SQLException {
        ArbolAVL<String, Cliente> arbol = new ArbolAVL<>();
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT clave, nombre, telefono, direccion, nota, lat, lon FROM clientes")) {
            while (rs.next()) {
                arbol.insertar(rs.getString(1), new Cliente(rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getDouble(6), rs.getDouble(7)));
            }
        }
        return arbol;
    }

    /** Inserta el cliente o lo actualiza si ya hay uno con el mismo nombre. */
    public void guardarCliente(Cliente c) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "INSERT INTO clientes(clave, nombre, telefono, direccion, nota, lat, lon) VALUES(?,?,?,?,?,?,?) "
                + "ON CONFLICT(clave) DO UPDATE SET nombre = excluded.nombre, telefono = excluded.telefono, "
                + "direccion = excluded.direccion, nota = excluded.nota, lat = excluded.lat, lon = excluded.lon")) {
            ps.setString(1, Geo.normalizar(c.getNombre()));
            ps.setString(2, c.getNombre());
            ps.setString(3, c.getTelefono());
            ps.setString(4, c.getDireccion());
            ps.setString(5, c.getNota());
            ps.setDouble(6, c.getLat());
            ps.setDouble(7, c.getLon());
            ps.executeUpdate();
        }
    }

    public void eliminarCliente(String nombre) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("DELETE FROM clientes WHERE clave = ?")) {
            ps.setString(1, Geo.normalizar(nombre));
            ps.executeUpdate();
        }
    }

    public int contarClientes() throws SQLException {
        try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM clientes")) {
            return rs.getInt(1);
        }
    }

    // ------------------------------------------------------------------
    //  Direcciones corregidas a mano
    // ------------------------------------------------------------------

    public void cargarDirecciones(Geocodificador geo) throws SQLException {
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT texto, lat, lon FROM direcciones_corregidas")) {
            while (rs.next()) {
                geo.aprender(rs.getString(1), rs.getDouble(2), rs.getDouble(3));
            }
        }
    }

    public void guardarDireccion(String texto, double lat, double lon) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "INSERT INTO direcciones_corregidas(texto, lat, lon) VALUES(?,?,?) "
                + "ON CONFLICT(texto) DO UPDATE SET lat = excluded.lat, lon = excluded.lon")) {
            ps.setString(1, Geo.normalizar(texto));
            ps.setDouble(2, lat);
            ps.setDouble(3, lon);
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------
    //  Novedades en las vias
    // ------------------------------------------------------------------

    public void cargarIncidentes(Mapa mapa) throws SQLException {
        mapa.getIncidentes().clear();
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT via, tipo, nota, fecha FROM novedades")) {
            while (rs.next()) {
                int via = rs.getInt(1);
                if (via >= 0 && via < mapa.getVias().size()) {
                    try {
                        mapa.getIncidentes().put(via, new Incidente(via, TipoIncidente.valueOf(rs.getString(2)),
                                rs.getString(3), LocalDateTime.parse(rs.getString(4), Ruta.FORMATO_FECHA)));
                    } catch (RuntimeException e) {
                        // registro danado: se ignora
                    }
                }
            }
        }
    }

    /** Reemplaza todas las novedades por las que hay en el mapa (en una transaccion). */
    public void guardarIncidentes(Mapa mapa) throws SQLException {
        boolean antes = con.getAutoCommit();
        con.setAutoCommit(false);
        try (Statement st = con.createStatement();
             PreparedStatement ps = con.prepareStatement("INSERT INTO novedades(via, tipo, nota, fecha) VALUES(?,?,?,?)")) {
            st.executeUpdate("DELETE FROM novedades");
            for (Incidente i : mapa.listaIncidentes()) {
                ps.setInt(1, i.getVia());
                ps.setString(2, i.getTipo().name());
                ps.setString(3, i.getNota());
                ps.setString(4, i.getFecha().format(Ruta.FORMATO_FECHA));
                ps.addBatch();
            }
            ps.executeBatch();
            con.commit();
        } catch (SQLException e) {
            con.rollback();
            throw e;
        } finally {
            con.setAutoCommit(antes);
        }
    }

    // ------------------------------------------------------------------
    //  Historial de rutas
    // ------------------------------------------------------------------

    /** Guarda una o varias rutas calculadas juntas (una por moto) en una sola transaccion. */
    public void guardarRutas(List<Ruta> rutas) throws SQLException {
        String lote = LocalDateTime.now().toString();
        boolean antes = con.getAutoCommit();
        con.setAutoCommit(false);
        try (PreparedStatement pr = con.prepareStatement(
                "INSERT INTO rutas(fecha, lote, moto, criterio, metodo, salida, metros, segundos, regreso, "
                + "base_nombre, base_direccion, base_lat, base_lon, base_nodo, nodos) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS);
             PreparedStatement pp = con.prepareStatement(
                "INSERT INTO paradas_ruta(ruta_id, orden, nombre, direccion, lat, lon, nodo, urgente, llegada) "
                + "VALUES(?,?,?,?,?,?,?,?,?)")) {
            for (int m = 0; m < rutas.size(); m++) {
                Ruta r = rutas.get(m);
                Parada b = r.getBase();
                pr.setString(1, r.getFecha().format(Ruta.FORMATO_FECHA));
                pr.setString(2, lote);
                pr.setInt(3, m + 1);
                pr.setString(4, r.getCriterio());
                pr.setString(5, r.getMetodo());
                pr.setString(6, r.getSalida().toString());
                pr.setDouble(7, r.getMetros());
                pr.setDouble(8, r.getSegundos());
                pr.setInt(9, r.isRegreso() ? 1 : 0);
                pr.setString(10, b.getNombre());
                pr.setString(11, b.getDireccion());
                pr.setDouble(12, b.getLat());
                pr.setDouble(13, b.getLon());
                pr.setInt(14, b.getNodo());
                StringBuilder nodos = new StringBuilder();
                for (int n : r.nodos()) {
                    nodos.append(nodos.length() > 0 ? "," : "").append(n);
                }
                pr.setString(15, nodos.toString());
                pr.executeUpdate();
                long id;
                try (ResultSet k = pr.getGeneratedKeys()) {
                    k.next();
                    id = k.getLong(1);
                }
                for (int i = 0; i < r.getParadas().size(); i++) {
                    Parada p = r.getParadas().get(i);
                    pp.setLong(1, id);
                    pp.setInt(2, i + 1);
                    pp.setString(3, p.getNombre());
                    pp.setString(4, p.getDireccion());
                    pp.setDouble(5, p.getLat());
                    pp.setDouble(6, p.getLon());
                    pp.setInt(7, p.getNodo());
                    pp.setInt(8, p.isUrgente() ? 1 : 0);
                    pp.setString(9, r.getLlegadas().get(i).toString());
                    pp.addBatch();
                }
                pp.executeBatch();
            }
            con.commit();
        } catch (SQLException e) {
            con.rollback();
            throw e;
        } finally {
            con.setAutoCommit(antes);
        }
    }

    /** Lee todas las rutas guardadas (de la mas vieja a la mas nueva). */
    public List<Ruta> cargarHistorial(Mapa mapa) throws SQLException {
        List<Ruta> rutas = new ArrayList<>();
        Grafo g = mapa.getGrafo();
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, fecha, criterio, metodo, salida, metros, segundos, regreso, "
                     + "base_nombre, base_direccion, base_lat, base_lon, base_nodo, nodos FROM rutas ORDER BY id");
             PreparedStatement pp = con.prepareStatement("SELECT nombre, direccion, lat, lon, nodo, urgente, llegada "
                     + "FROM paradas_ruta WHERE ruta_id = ? ORDER BY orden")) {
            while (rs.next()) {
                try {
                    Parada base = new Parada(rs.getString(9), rs.getString(10), rs.getDouble(11), rs.getDouble(12));
                    base.setNodo(rs.getInt(13));
                    List<Parada> paradas = new ArrayList<>();
                    List<LocalTime> llegadas = new ArrayList<>();
                    pp.setLong(1, rs.getLong(1));
                    try (ResultSet rp = pp.executeQuery()) {
                        while (rp.next()) {
                            Parada p = new Parada(rp.getString(1), rp.getString(2), rp.getDouble(3), rp.getDouble(4));
                            p.setNodo(rp.getInt(5));
                            p.setUrgente(rp.getInt(6) == 1);
                            paradas.add(p);
                            llegadas.add(LocalTime.parse(rp.getString(7)));
                        }
                    }
                    List<Arista> aristas = new ArrayList<>();
                    String txt = rs.getString(14);
                    String[] ids = txt.isEmpty() ? new String[0] : txt.split(",");
                    for (int i = 0; i + 1 < ids.length; i++) {
                        int a = Integer.parseInt(ids[i]), b = Integer.parseInt(ids[i + 1]);
                        if (a < g.getCantidadNodos() && b < g.getCantidadNodos()) {
                            Arista ar = g.buscarArista(a, b);
                            if (ar != null) {
                                aristas.add(ar);
                            }
                        }
                    }
                    Ruta r = new Ruta(base, paradas, llegadas, new ArrayList<>(), aristas, rs.getDouble(6),
                            rs.getDouble(7), LocalTime.parse(rs.getString(5)), rs.getInt(8) == 1,
                            rs.getString(3), rs.getString(4));
                    r.setFecha(LocalDateTime.parse(rs.getString(2), Ruta.FORMATO_FECHA));
                    rutas.add(r);
                } catch (RuntimeException e) {
                    // ruta danada o de un mapa distinto: se ignora
                }
            }
        }
        return rutas;
    }

    // ------------------------------------------------------------------
    //  Importar los archivos de texto de versiones anteriores
    // ------------------------------------------------------------------

    /**
     * La version anterior guardaba todo en archivos .txt. Si existen, se pasan a la base
     * de datos una sola vez y se renombran a .importado para no repetir.
     * Devuelve cuantos registros se importaron.
     */
    public int importarTextos(File carpeta, Mapa mapa) throws SQLException, IOException {
        ArchivosDatos viejos = new ArchivosDatos(carpeta);
        int total = 0;
        File f = new File(carpeta, "clientes.txt");
        if (f.exists()) {
            for (NodoAVL<String, Cliente> n : viejos.cargarClientes().nodosEnOrden()) {
                guardarCliente(n.getValor());
                total++;
            }
            renombrar(f);
        }
        f = new File(carpeta, "direcciones_corregidas.txt");
        if (f.exists()) {
            for (String l : CargadorMapa.leer(f)) {
                String[] c = l.split(";", -1);
                try {
                    guardarDireccion(c[0], Double.parseDouble(c[1]), Double.parseDouble(c[2]));
                    total++;
                } catch (RuntimeException e) {
                    // linea danada
                }
            }
            renombrar(f);
        }
        f = new File(carpeta, "novedades.txt");
        if (f.exists()) {
            viejos.cargarIncidentes(mapa);
            total += mapa.getIncidentes().size();
            guardarIncidentes(mapa);
            renombrar(f);
        }
        f = new File(carpeta, "historial_rutas.txt");
        if (f.exists()) {
            for (Ruta r : viejos.cargarHistorial(mapa)) {
                guardarRutas(List.of(r));
                total++;
            }
            renombrar(f);
        }
        f = new File(carpeta, "base.txt");
        if (f.exists()) {
            try {
                String[] c = java.nio.file.Files.readAllLines(f.toPath(), java.nio.charset.StandardCharsets.UTF_8)
                        .get(0).split(";");
                guardarBase(new Parada(c[0], c[1], Double.parseDouble(c[2]), Double.parseDouble(c[3])));
                total++;
            } catch (RuntimeException e) {
                // archivo danado
            }
            renombrar(f);
        }
        return total;
    }

    private static void renombrar(File f) {
        File destino = new File(f.getParentFile(), f.getName() + ".importado");
        if (!f.renameTo(destino)) {
            f.deleteOnExit();
        }
    }
}
