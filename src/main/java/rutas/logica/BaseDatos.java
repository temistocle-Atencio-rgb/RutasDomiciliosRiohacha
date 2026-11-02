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
import rutas.modelo.Salida;
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
            crearTablasSalidas(st);
            // datos de caja del cliente (documento, correo, municipio y tope de credito)
            String[][] nuevas = {{"tipo_doc", "TEXT NOT NULL DEFAULT ''"}, {"documento", "TEXT NOT NULL DEFAULT ''"},
                {"correo", "TEXT NOT NULL DEFAULT ''"}, {"departamento", "TEXT NOT NULL DEFAULT ''"},
                {"municipio", "TEXT NOT NULL DEFAULT ''"}, {"credito_max", "INTEGER NOT NULL DEFAULT 0"},
                {"activo", "INTEGER NOT NULL DEFAULT 1"}};
            for (String[] c : nuevas) {
                boolean hay = false;
                try (ResultSet rs = st.executeQuery("PRAGMA table_info(clientes)")) {
                    while (rs.next()) {
                        hay |= c[0].equalsIgnoreCase(rs.getString("name"));
                    }
                }
                if (!hay) {
                    st.execute("ALTER TABLE clientes ADD COLUMN " + c[0] + " " + c[1]);
                }
            }
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
             ResultSet rs = st.executeQuery("SELECT clave, nombre, telefono, direccion, nota, lat, lon, tipo_doc,"
                     + " documento, correo, departamento, municipio, credito_max, activo FROM clientes")) {
            while (rs.next()) {
                arbol.insertar(rs.getString(1), new Cliente(rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getDouble(6), rs.getDouble(7), rs.getString(8), rs.getString(9),
                        rs.getString(10), rs.getString(11), rs.getString(12), rs.getLong(13), rs.getInt(14) != 0));
            }
        }
        return arbol;
    }

    /** Inserta el cliente o lo actualiza si ya hay uno con el mismo nombre. */
    public void guardarCliente(Cliente c) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "INSERT INTO clientes(clave, nombre, telefono, direccion, nota, lat, lon, tipo_doc, documento, correo,"
                + " departamento, municipio, credito_max, activo) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?) "
                + "ON CONFLICT(clave) DO UPDATE SET nombre = excluded.nombre, telefono = excluded.telefono, "
                + "direccion = excluded.direccion, nota = excluded.nota, lat = excluded.lat, lon = excluded.lon, "
                + "tipo_doc = excluded.tipo_doc, documento = excluded.documento, correo = excluded.correo, "
                + "departamento = excluded.departamento, municipio = excluded.municipio, "
                + "credito_max = excluded.credito_max, activo = excluded.activo")) {
            ps.setString(1, Geo.normalizar(c.getNombre()));
            ps.setString(2, c.getNombre());
            ps.setString(3, c.getTelefono());
            ps.setString(4, c.getDireccion());
            ps.setString(5, c.getNota());
            ps.setDouble(6, c.getLat());
            ps.setDouble(7, c.getLon());
            ps.setString(8, c.getTipoDocumento());
            ps.setString(9, c.getDocumento());
            ps.setString(10, c.getCorreo());
            ps.setString(11, c.getDepartamento());
            ps.setString(12, c.getMunicipio());
            ps.setLong(13, c.getCreditoMaximo());
            ps.setInt(14, c.isActivo() ? 1 : 0);
            ps.executeUpdate();
        }
    }

    /** Cambia el nombre (la clave) de un cliente: se borra el viejo y se guarda el nuevo, juntos. */
    public void renombrarCliente(String nombreViejo, Cliente nuevo) throws SQLException {
        boolean antes = con.getAutoCommit();
        con.setAutoCommit(false);
        try {
            eliminarCliente(nombreViejo);
            guardarCliente(nuevo);
            con.commit();
        } catch (SQLException | RuntimeException e) {
            con.rollback();
            throw e;
        } finally {
            con.setAutoCommit(antes);
        }
    }

    /** Guarda varios clientes juntos (importar): o se guardan todos o ninguno. */
    public void guardarClientes(java.util.List<Cliente> lista) throws SQLException {
        boolean antes = con.getAutoCommit();
        con.setAutoCommit(false);
        try {
            for (Cliente c : lista) {
                guardarCliente(c);
            }
            con.commit();
        } catch (SQLException | RuntimeException e) {
            con.rollback();
            throw e;
        } finally {
            con.setAutoCommit(antes);
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
    // ------------------------------------------------------------------
    //  Entregas pendientes y salidas del repartidor
    // ------------------------------------------------------------------

    private void crearTablasSalidas(Statement st) throws SQLException {
        // las entregas que esperan salir (si se cierra el programa no se pierden)
        st.execute("CREATE TABLE IF NOT EXISTS pendientes ("
                + " orden INTEGER PRIMARY KEY,"
                + " nombre TEXT NOT NULL, direccion TEXT NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL,"
                + " nodo INTEGER NOT NULL, precision_txt TEXT NOT NULL DEFAULT '', telefono TEXT NOT NULL DEFAULT '',"
                + " valor INTEGER NOT NULL DEFAULT 0, pagado INTEGER NOT NULL DEFAULT 0,"
                + " paga_con INTEGER NOT NULL DEFAULT 0, urgente INTEGER NOT NULL DEFAULT 0)");
        st.execute("CREATE TABLE IF NOT EXISTS salidas ("
                + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + " codigo TEXT NOT NULL DEFAULT '', uuid_nube TEXT NOT NULL DEFAULT '',"
                + " repartidor TEXT NOT NULL DEFAULT '', creada TEXT NOT NULL, cerrada TEXT,"
                + " estado TEXT NOT NULL, lat REAL, lon REAL, ubicacion_en TEXT)");
        st.execute("CREATE TABLE IF NOT EXISTS entregas_salida ("
                + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + " salida_id INTEGER NOT NULL REFERENCES salidas(id) ON DELETE CASCADE,"
                + " orden INTEGER NOT NULL,"
                + " nombre TEXT NOT NULL, direccion TEXT NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL,"
                + " nodo INTEGER NOT NULL, telefono TEXT NOT NULL DEFAULT '',"
                + " valor INTEGER NOT NULL DEFAULT 0, pagado INTEGER NOT NULL DEFAULT 0,"
                + " paga_con INTEGER NOT NULL DEFAULT 0, urgente INTEGER NOT NULL DEFAULT 0,"
                + " cobrar INTEGER NOT NULL DEFAULT 0, estado TEXT NOT NULL, motivo TEXT NOT NULL DEFAULT '',"
                + " id_nube INTEGER NOT NULL DEFAULT 0, tiene_foto INTEGER NOT NULL DEFAULT 0,"
                + " foto TEXT NOT NULL DEFAULT '', marca_pendiente INTEGER NOT NULL DEFAULT 0,"
                + " marcada_en INTEGER NOT NULL DEFAULT 0)");
        st.execute("CREATE TABLE IF NOT EXISTS mensajes_salida ("
                + " salida_id INTEGER NOT NULL REFERENCES salidas(id) ON DELETE CASCADE,"
                + " id_nube INTEGER NOT NULL, de_tienda INTEGER NOT NULL, texto TEXT NOT NULL,"
                + " hora TEXT NOT NULL, leido INTEGER NOT NULL DEFAULT 0)");
        st.execute("CREATE INDEX IF NOT EXISTS idx_salidas_creada ON salidas(creada)");
        columnaSiFalta(st, "salidas", "tipo_repartidor", "TEXT NOT NULL DEFAULT ''");
        columnaSiFalta(st, "salidas", "aceptada_en", "TEXT");
        columnaSiFalta(st, "salidas", "rechazada_en", "TEXT");
        columnaSiFalta(st, "salidas", "motivo_rechazo", "TEXT NOT NULL DEFAULT ''");
        columnaSiFalta(st, "salidas", "enviada_en", "TEXT");
        columnaSiFalta(st, "entregas_salida", "domicilio", "INTEGER NOT NULL DEFAULT 0");
        for (String t : new String[]{"pendientes", "entregas_salida"}) { // de qué venta salió y cuántos productos
            columnaSiFalta(st, t, "venta_numero", "INTEGER NOT NULL DEFAULT 0");
            columnaSiFalta(st, t, "productos", "INTEGER NOT NULL DEFAULT 0");
        }
        // quienes llevan los domicilios (sin contraseñas: a cada salida le llega su enlace)
        st.execute("CREATE TABLE IF NOT EXISTS domiciliarios ("
                + " id INTEGER PRIMARY KEY AUTOINCREMENT, nombre TEXT NOT NULL, celular TEXT NOT NULL DEFAULT '',"
                + " tipo TEXT NOT NULL DEFAULT 'FIJO', activo INTEGER NOT NULL DEFAULT 1)");
        columnaSiFalta(st, "entregas_salida", "marca_pendiente", "INTEGER NOT NULL DEFAULT 0");
        columnaSiFalta(st, "entregas_salida", "marcada_en", "INTEGER NOT NULL DEFAULT 0");
        // repartidor en vivo: el cliente ve su pedido, la salida vence y el repartidor tiene enlace personal
        columnaSiFalta(st, "entregas_salida", "codigo_cliente", "TEXT NOT NULL DEFAULT ''");
        columnaSiFalta(st, "entregas_salida", "enlace_cliente", "TEXT NOT NULL DEFAULT ''");
        columnaSiFalta(st, "salidas", "vence_en", "TEXT");
        columnaSiFalta(st, "domiciliarios", "id_nube", "TEXT NOT NULL DEFAULT ''");
        columnaSiFalta(st, "domiciliarios", "enlace", "TEXT NOT NULL DEFAULT ''");
        // ubicaciones que corrigieron los repartidores («La casa no está aquí»)
        st.execute("CREATE TABLE IF NOT EXISTS correcciones_ubicacion ("
                + " id_nube INTEGER PRIMARY KEY, nombre TEXT NOT NULL, direccion TEXT NOT NULL,"
                + " lat_viejo REAL, lon_viejo REAL, lat REAL NOT NULL, lon REAL NOT NULL,"
                + " distancia INTEGER NOT NULL DEFAULT 0, estado TEXT NOT NULL, repartidor TEXT NOT NULL DEFAULT '',"
                + " creada TEXT NOT NULL)");
    }

    /** Las tablas de salidas ya pueden existir de la versión anterior: se les agrega lo nuevo. */
    private static void columnaSiFalta(Statement st, String tabla, String columna, String tipo) throws SQLException {
        try (ResultSet rs = st.executeQuery("PRAGMA table_info(" + tabla + ")")) {
            while (rs.next()) {
                if (columna.equalsIgnoreCase(rs.getString("name"))) {
                    return;
                }
            }
        }
        st.execute("ALTER TABLE " + tabla + " ADD COLUMN " + columna + " " + tipo);
    }

    /** Reemplaza la lista de entregas pendientes. */
    public void guardarPendientes(List<Parada> entregas) throws SQLException {
        boolean antes = con.getAutoCommit();
        con.setAutoCommit(false);
        try (Statement st = con.createStatement();
             PreparedStatement ps = con.prepareStatement("INSERT INTO pendientes(orden, nombre, direccion, lat, lon, nodo,"
                     + " precision_txt, telefono, valor, pagado, paga_con, urgente, venta_numero, productos)"
                     + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            st.execute("DELETE FROM pendientes");
            for (int i = 0; i < entregas.size(); i++) {
                Parada p = entregas.get(i);
                ps.setInt(1, i + 1);
                ps.setString(2, p.getNombre());
                ps.setString(3, p.getDireccion());
                ps.setDouble(4, p.getLat());
                ps.setDouble(5, p.getLon());
                ps.setInt(6, p.getNodo());
                ps.setString(7, p.getPrecision() == null ? "" : p.getPrecision());
                ps.setString(8, p.getTelefono());
                ps.setInt(9, p.getValorPedido());
                ps.setInt(10, p.isPagado() ? 1 : 0);
                ps.setInt(11, p.getPagaCon());
                ps.setInt(12, p.isUrgente() ? 1 : 0);
                ps.setLong(13, p.getVentaNumero());
                ps.setInt(14, p.getProductos());
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

    public List<Parada> cargarPendientes() throws SQLException {
        List<Parada> l = new ArrayList<>();
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT nombre, direccion, lat, lon, nodo, precision_txt, telefono, valor,"
                     + " pagado, paga_con, urgente, venta_numero, productos FROM pendientes ORDER BY orden")) {
            while (rs.next()) {
                Parada p = new Parada(rs.getString(1), rs.getString(2), rs.getDouble(3), rs.getDouble(4));
                p.setNodo(rs.getInt(5));
                p.setPrecision(rs.getString(6));
                p.setTelefono(rs.getString(7));
                p.setValorPedido(rs.getInt(8));
                p.setPagado(rs.getInt(9) == 1);
                p.setPagaCon(rs.getInt(10));
                p.setUrgente(rs.getInt(11) == 1);
                p.setVentaNumero(rs.getLong(12));
                p.setProductos(rs.getInt(13));
                l.add(p);
            }
        }
        return l;
    }

    /** Guarda la salida completa (si es nueva le pone id). Entregas y mensajes se reescriben. */
    public void guardarSalida(Salida s) throws SQLException {
        boolean antes = con.getAutoCommit();
        long idAntes = s.id;
        long[] idsAntes = new long[s.entregas.size()];
        for (int i = 0; i < idsAntes.length; i++) {
            idsAntes[i] = s.entregas.get(i).id;
        }
        con.setAutoCommit(false);
        try {
            if (s.id == 0) {
                try (PreparedStatement ps = con.prepareStatement("INSERT INTO salidas(codigo, uuid_nube, repartidor,"
                        + " creada, cerrada, estado, lat, lon, ubicacion_en, tipo_repartidor, aceptada_en, rechazada_en, motivo_rechazo, enviada_en,"
                        + " vence_en) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        Statement.RETURN_GENERATED_KEYS)) {
                    datosSalida(ps, s);
                    ps.executeUpdate();
                    try (ResultSet k = ps.getGeneratedKeys()) {
                        k.next();
                        s.id = k.getLong(1);
                    }
                }
            } else {
                try (PreparedStatement ps = con.prepareStatement("UPDATE salidas SET codigo=?, uuid_nube=?, repartidor=?,"
                        + " creada=?, cerrada=?, estado=?, lat=?, lon=?, ubicacion_en=?, tipo_repartidor=?, aceptada_en=?, rechazada_en=?, motivo_rechazo=?, enviada_en=?,"
                        + " vence_en=? WHERE id=?")) {
                    datosSalida(ps, s);
                    ps.setLong(16, s.id);
                    ps.executeUpdate();
                }
            }
            try (PreparedStatement d1 = con.prepareStatement("DELETE FROM entregas_salida WHERE salida_id=?");
                 PreparedStatement d2 = con.prepareStatement("DELETE FROM mensajes_salida WHERE salida_id=?")) {
                d1.setLong(1, s.id);
                d1.executeUpdate();
                d2.setLong(1, s.id);
                d2.executeUpdate();
            }
            try (PreparedStatement pe = con.prepareStatement("INSERT INTO entregas_salida(salida_id, orden, nombre,"
                    + " direccion, lat, lon, nodo, telefono, valor, pagado, paga_con, urgente, cobrar, estado, motivo,"
                    + " id_nube, tiene_foto, foto, marca_pendiente, marcada_en, domicilio, venta_numero, productos,"
                    + " codigo_cliente, enlace_cliente) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                for (int i = 0; i < s.entregas.size(); i++) {
                    Salida.Entrega e = s.entregas.get(i);
                    Parada p = e.parada;
                    pe.setLong(1, s.id);
                    pe.setInt(2, i + 1);
                    pe.setString(3, p.getNombre());
                    pe.setString(4, p.getDireccion());
                    pe.setDouble(5, p.getLat());
                    pe.setDouble(6, p.getLon());
                    pe.setInt(7, p.getNodo());
                    pe.setString(8, p.getTelefono());
                    pe.setInt(9, p.getValorPedido());
                    pe.setInt(10, p.isPagado() ? 1 : 0);
                    pe.setInt(11, p.getPagaCon());
                    pe.setInt(12, p.isUrgente() ? 1 : 0);
                    pe.setInt(13, e.cobrar);
                    pe.setString(14, e.estado);
                    pe.setString(15, e.motivo);
                    pe.setLong(16, e.idNube);
                    pe.setInt(17, e.tieneFoto ? 1 : 0);
                    pe.setString(18, e.foto);
                    pe.setInt(19, e.marcaPendiente ? 1 : 0);
                    pe.setLong(20, e.marcadaEn);
                    pe.setInt(21, e.domicilio);
                    pe.setLong(22, p.getVentaNumero());
                    pe.setInt(23, p.getProductos());
                    pe.setString(24, e.codigoCliente);
                    pe.setString(25, e.enlaceCliente);
                    pe.executeUpdate();
                    try (ResultSet k = pe.getGeneratedKeys()) {
                        k.next();
                        e.id = k.getLong(1);
                    }
                }
            }
            try (PreparedStatement pm = con.prepareStatement("INSERT INTO mensajes_salida(salida_id, id_nube, de_tienda,"
                    + " texto, hora, leido) VALUES(?,?,?,?,?,?)")) {
                for (Salida.Mensaje m : s.mensajes) {
                    pm.setLong(1, s.id);
                    pm.setLong(2, m.idNube);
                    pm.setInt(3, m.deTienda ? 1 : 0);
                    pm.setString(4, m.texto);
                    pm.setString(5, m.hora.toString());
                    pm.setInt(6, m.leido ? 1 : 0);
                    pm.addBatch();
                }
                pm.executeBatch();
            }
            con.commit();
        } catch (SQLException e) {
            con.rollback();
            s.id = idAntes; // lo que se deshizo no quedo con id (si no, no se podria volver a guardar)
            for (int i = 0; i < idsAntes.length; i++) {
                s.entregas.get(i).id = idsAntes[i];
            }
            throw e;
        } finally {
            con.setAutoCommit(antes);
        }
    }

    private static LocalDateTime fecha(String t) {
        return t == null || t.isEmpty() ? null : LocalDateTime.parse(t);
    }

    private static void datosSalida(PreparedStatement ps, Salida s) throws SQLException {
        ps.setString(1, s.codigo);
        ps.setString(2, s.uuidNube);
        ps.setString(3, s.repartidor);
        ps.setString(4, s.creada.toString());
        ps.setString(5, s.cerrada == null ? null : s.cerrada.toString());
        ps.setString(6, s.estado.name());
        if (Double.isNaN(s.lat)) {
            ps.setNull(7, java.sql.Types.REAL);
            ps.setNull(8, java.sql.Types.REAL);
        } else {
            ps.setDouble(7, s.lat);
            ps.setDouble(8, s.lon);
        }
        ps.setString(9, s.ubicacionEn == null ? null : s.ubicacionEn.toString());
        ps.setString(10, s.tipoRepartidor);
        ps.setString(11, s.aceptadaEn == null ? null : s.aceptadaEn.toString());
        ps.setString(12, s.rechazadaEn == null ? null : s.rechazadaEn.toString());
        ps.setString(13, s.motivoRechazo);
        ps.setString(14, s.enviadaEn == null ? null : s.enviadaEn.toString());
        ps.setString(15, s.venceEn == null ? null : s.venceEn.toString());
    }

    /** Las salidas abiertas y las cerradas desde la fecha dada (las de hoy, para el apartado Salidas). */
    public List<Salida> cargarSalidas(LocalDateTime cerradasDesde) throws SQLException {
        // las cerradas que siguen en internet se cargan para terminar de borrarlas
        return leerSalidas("estado <> 'CERRADA' OR cerrada >= ? OR uuid_nube <> ''", true, cerradasDesde.toString());
    }

    /**
     * Historial: las salidas cerradas entre esas dos fechas (las dos incluidas), de la más vieja a la más nueva.
     * Las fotos no se cargan (pesan): tieneFoto dice si está guardada aquí y se pide con fotoEntrega.
     */
    public List<Salida> historialSalidas(java.time.LocalDate desde, java.time.LocalDate hasta) throws SQLException {
        List<Salida> l = leerSalidas("estado = 'CERRADA' AND cerrada >= ? AND cerrada < ?", false,
                desde.atStartOfDay().toString(), hasta.plusDays(1).atStartOfDay().toString());
        l.sort(java.util.Comparator.comparing((Salida s) -> s.creada)); // por la hora en que salieron
        return l;
    }

    /** La foto guardada de una entrega ("" si no hay). */
    public String fotoEntrega(long idEntrega) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT foto FROM entregas_salida WHERE id=?")) {
            ps.setLong(1, idEntrega);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getString(1) != null ? rs.getString(1) : "";
            }
        }
    }

    private List<Salida> leerSalidas(String donde, boolean conFotos, String... valores) throws SQLException {
        List<Salida> l = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement("SELECT id, codigo, uuid_nube, repartidor, creada, cerrada,"
                + " estado, lat, lon, ubicacion_en, tipo_repartidor, aceptada_en, rechazada_en, motivo_rechazo, enviada_en,"
                + " vence_en FROM salidas WHERE " + donde + " ORDER BY id")) {
            for (int i = 0; i < valores.length; i++) {
                ps.setString(i + 1, valores[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Salida s = new Salida();
                    s.id = rs.getLong(1);
                    s.codigo = rs.getString(2);
                    s.uuidNube = rs.getString(3);
                    s.repartidor = rs.getString(4);
                    s.creada = LocalDateTime.parse(rs.getString(5));
                    String c = rs.getString(6);
                    s.cerrada = c == null ? null : LocalDateTime.parse(c);
                    s.estado = Salida.Estado.valueOf(rs.getString(7));
                    double lat = rs.getDouble(8);
                    s.lat = rs.wasNull() ? Double.NaN : lat;
                    double lon = rs.getDouble(9);
                    s.lon = rs.wasNull() ? Double.NaN : lon;
                    String u = rs.getString(10);
                    s.ubicacionEn = u == null ? null : LocalDateTime.parse(u);
                    s.tipoRepartidor = rs.getString(11);
                    s.aceptadaEn = fecha(rs.getString(12));
                    s.rechazadaEn = fecha(rs.getString(13));
                    s.motivoRechazo = rs.getString(14);
                    s.enviadaEn = fecha(rs.getString(15));
                    s.venceEn = fecha(rs.getString(16));
                    l.add(s);
                }
            }
        }
        try (PreparedStatement pe = con.prepareStatement("SELECT id, nombre, direccion, lat, lon, nodo, telefono, valor,"
                + " pagado, paga_con, urgente, cobrar, estado, motivo, id_nube, tiene_foto, "
                + (conFotos ? "foto" : "CASE WHEN length(foto) > 0 THEN 'si' ELSE '' END") + ", marca_pendiente,"
                + " marcada_en, domicilio, venta_numero, productos, codigo_cliente, enlace_cliente FROM entregas_salida"
                + " WHERE salida_id=? ORDER BY orden");
             PreparedStatement pm = con.prepareStatement("SELECT id_nube, de_tienda, texto, hora, leido FROM"
                     + " mensajes_salida WHERE salida_id=? ORDER BY rowid")) {
            for (Salida s : l) {
                pe.setLong(1, s.id);
                try (ResultSet rs = pe.executeQuery()) {
                    while (rs.next()) {
                        Parada p = new Parada(rs.getString(2), rs.getString(3), rs.getDouble(4), rs.getDouble(5));
                        p.setNodo(rs.getInt(6));
                        p.setTelefono(rs.getString(7));
                        p.setValorPedido(rs.getInt(8));
                        p.setPagado(rs.getInt(9) == 1);
                        p.setPagaCon(rs.getInt(10));
                        p.setUrgente(rs.getInt(11) == 1);
                        Salida.Entrega e = new Salida.Entrega(p, rs.getInt(12));
                        e.id = rs.getLong(1);
                        e.estado = rs.getString(13);
                        e.motivo = rs.getString(14);
                        e.idNube = rs.getLong(15);
                        e.tieneFoto = rs.getInt(16) == 1;
                        e.foto = rs.getString(17);
                        if (!conFotos) { // en el historial: tieneFoto = la foto está guardada aquí
                            e.tieneFoto = !e.foto.isEmpty();
                            e.foto = "";
                        }
                        e.marcaPendiente = rs.getInt(18) == 1;
                        e.marcadaEn = rs.getLong(19);
                        e.domicilio = rs.getInt(20);
                        p.setVentaNumero(rs.getLong(21));
                        p.setProductos(rs.getInt(22));
                        e.codigoCliente = rs.getString(23);
                        e.enlaceCliente = rs.getString(24);
                        s.entregas.add(e);
                    }
                }
                pm.setLong(1, s.id);
                try (ResultSet rs = pm.executeQuery()) {
                    while (rs.next()) {
                        s.mensajes.add(new Salida.Mensaje(rs.getLong(1), rs.getInt(2) == 1, rs.getString(3),
                                LocalDateTime.parse(rs.getString(4)), rs.getInt(5) == 1));
                    }
                }
            }
        }
        return l;
    }

    // ------------------------------------------------------------------
    //  Domiciliarios
    // ------------------------------------------------------------------

    public List<rutas.modelo.Domiciliario> cargarDomiciliarios() throws SQLException {
        List<rutas.modelo.Domiciliario> l = new ArrayList<>();
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, nombre, celular, tipo, activo, id_nube, enlace FROM domiciliarios"
                     + " ORDER BY CASE tipo WHEN 'FIJO' THEN 0 WHEN 'INDEPENDIENTE' THEN 1 ELSE 2 END, nombre")) {
            while (rs.next()) {
                rutas.modelo.Domiciliario.Tipo t;
                try {
                    t = rutas.modelo.Domiciliario.Tipo.valueOf(rs.getString(4));
                } catch (IllegalArgumentException e) {
                    t = rutas.modelo.Domiciliario.Tipo.FIJO;
                }
                rutas.modelo.Domiciliario d = new rutas.modelo.Domiciliario(rs.getLong(1), rs.getString(2), rs.getString(3), t);
                d.activo = rs.getInt(5) == 1;
                d.idNube = rs.getString(6);
                d.enlace = rs.getString(7);
                l.add(d);
            }
        }
        return l;
    }

    /** Nuevo (id 0: queda con id) o cambio de uno que ya estaba. */
    public void guardarDomiciliario(rutas.modelo.Domiciliario d) throws SQLException {
        if (d.id == 0) {
            try (PreparedStatement ps = con.prepareStatement("INSERT INTO domiciliarios(nombre, celular, tipo, activo,"
                    + " id_nube, enlace) VALUES(?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, d.nombre);
                ps.setString(2, d.celular);
                ps.setString(3, d.tipo.name());
                ps.setInt(4, d.activo ? 1 : 0);
                ps.setString(5, d.idNube);
                ps.setString(6, d.enlace);
                ps.executeUpdate();
                try (ResultSet k = ps.getGeneratedKeys()) {
                    k.next();
                    d.id = k.getLong(1);
                }
            }
        } else {
            // el id y el enlace de Supabase no se tocan aquí (una lista vieja en pantalla los borraría): ver guardarEnlaceNube
            try (PreparedStatement ps = con.prepareStatement("UPDATE domiciliarios SET nombre=?, celular=?, tipo=?,"
                    + " activo=? WHERE id=?")) {
                ps.setString(1, d.nombre);
                ps.setString(2, d.celular);
                ps.setString(3, d.tipo.name());
                ps.setInt(4, d.activo ? 1 : 0);
                ps.setLong(5, d.id);
                ps.executeUpdate();
            }
        }
    }

    /** Guarda una corrección que llegó de Supabase (si ya estaba, no la duplica). True si es nueva. */
    public boolean guardarCorreccion(rutas.modelo.Correccion c) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("INSERT OR IGNORE INTO correcciones_ubicacion(id_nube, nombre,"
                + " direccion, lat_viejo, lon_viejo, lat, lon, distancia, estado, repartidor, creada) VALUES(?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setLong(1, c.idNube);
            ps.setString(2, c.nombre);
            ps.setString(3, c.direccion);
            if (Double.isNaN(c.latViejo)) {
                ps.setNull(4, java.sql.Types.REAL);
                ps.setNull(5, java.sql.Types.REAL);
            } else {
                ps.setDouble(4, c.latViejo);
                ps.setDouble(5, c.lonViejo);
            }
            ps.setDouble(6, c.lat);
            ps.setDouble(7, c.lon);
            ps.setInt(8, c.distanciaM);
            ps.setString(9, c.estado);
            ps.setString(10, c.repartidor);
            ps.setString(11, c.creada.toString());
            return ps.executeUpdate() == 1;
        }
    }

    /** Las que esperan que la tienda las apruebe o las descarte (la más vieja primero). */
    public List<rutas.modelo.Correccion> correccionesPendientes() throws SQLException {
        List<rutas.modelo.Correccion> l = new ArrayList<>();
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT id_nube, nombre, direccion, lat_viejo, lon_viejo, lat, lon, distancia,"
                     + " estado, repartidor, creada FROM correcciones_ubicacion WHERE estado = 'pendiente' ORDER BY creada")) {
            while (rs.next()) {
                rutas.modelo.Correccion c = new rutas.modelo.Correccion();
                c.idNube = rs.getLong(1);
                c.nombre = rs.getString(2);
                c.direccion = rs.getString(3);
                double la = rs.getDouble(4);
                c.latViejo = rs.wasNull() ? Double.NaN : la;
                double lo = rs.getDouble(5);
                c.lonViejo = rs.wasNull() ? Double.NaN : lo;
                c.lat = rs.getDouble(6);
                c.lon = rs.getDouble(7);
                c.distanciaM = rs.getInt(8);
                c.estado = rs.getString(9);
                c.repartidor = rs.getString(10);
                c.creada = LocalDateTime.parse(rs.getString(11));
                l.add(c);
            }
        }
        return l;
    }

    public void marcarCorreccion(long idNube, String estado) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("UPDATE correcciones_ubicacion SET estado=? WHERE id_nube=?")) {
            ps.setString(1, estado);
            ps.setLong(2, idNube);
            ps.executeUpdate();
        }
    }

    /** Solo el id y el enlace personal en Supabase (al crearlo allá o al cambiarle el enlace). */
    public void guardarEnlaceNube(long id, String idNube, String enlace) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("UPDATE domiciliarios SET id_nube=?, enlace=? WHERE id=?")) {
            ps.setString(1, idNube == null ? "" : idNube);
            ps.setString(2, enlace == null ? "" : enlace);
            ps.setLong(3, id);
            ps.executeUpdate();
        }
    }

    public void eliminarDomiciliario(long id) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("DELETE FROM domiciliarios WHERE id=?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }
}
