package rutas.logica;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import rutas.estructuras.MonticuloMin;
import rutas.modelo.MetodoPago;

/**
 * Gastos de la tienda (arriendo, servicios, nómina...), en la base de la tienda.
 *
 * - Categorías: nombre, activa, y si se quiere el concepto DIAN del formato 1001 y si es deducible.
 * - Gasto de contado: se paga de una con un medio de pago; si es efectivo puede salir de la caja.
 * - Gasto a crédito: queda por pagar y se le hacen abonos hasta que el saldo llega a cero.
 * - Gastos fijos: se repiten cada mes el mismo día; «pagar» crea el gasto y pasa al mes siguiente.
 * - Nada se borra: un gasto se anula con un motivo (y deja de contar, abonos incluidos).
 * Todos los valores van en pesos enteros.
 */
public class BaseGastos {

    /** Conceptos del formato 1001 de la DIAN que se usan en gastos (opcional). */
    public static final String[][] CONCEPTOS_1001 = {
        {"5002", "Honorarios"}, {"5003", "Comisiones"}, {"5004", "Servicios"}, {"5005", "Arrendamientos"},
        {"5006", "Intereses y rendimientos financieros"}, {"5008", "Compra de activos fijos"},
        {"5010", "Aportes parafiscales"}, {"5011", "Aportes obligatorios a salud"},
        {"5012", "Aportes obligatorios a fondos de pensiones"}, {"5013", "Donaciones"},
        {"5014", "Impuestos solicitados como deducción"}, {"5015", "Consumo en restaurantes"},
        {"5016", "Los demás costos y deducciones"}, {"5020", "Pagos a no residentes"},
        {"5027", "Apoyos económicos no reembolsables"}};

    /** Las que trae una tienda nueva (se pueden cambiar). */
    static final String[][] CATEGORIAS_INICIALES = {
        {"ARRIENDO", "5005"}, {"SERVICIOS PÚBLICOS", "5004"}, {"INTERNET Y TELÉFONO", "5004"}, {"NÓMINA", ""},
        {"DOMICILIOS Y TRANSPORTE", "5004"}, {"BOLSAS Y EMPAQUES", "5016"}, {"MANTENIMIENTO", "5004"},
        {"IMPUESTOS", "5014"}, {"OTROS", "5016"}};

    public enum Renta {
        SIN_CLASIFICAR("Sin clasificar"), DEDUCIBLE("Deducible"), NO_DEDUCIBLE("No deducible");

        public final String texto;

        Renta(String texto) {
            this.texto = texto;
        }

        @Override
        public String toString() {
            return texto;
        }
    }

    public static class Categoria {
        public long id;
        public String nombre = "";
        public String concepto = "";        // "" = sin clasificar
        public Renta renta = Renta.SIN_CLASIFICAR;
        public boolean activa = true;

        /** "5005 - Arrendamientos" o "Sin clasificar". */
        public String textoConcepto() {
            for (String[] c : CONCEPTOS_1001) {
                if (c[0].equals(concepto)) {
                    return c[0] + " - " + c[1];
                }
            }
            return "Sin clasificar";
        }

        @Override
        public String toString() {
            return nombre;
        }
    }

    public static class Gasto {
        public long id;
        public long numero;
        public LocalDateTime fecha;
        public long categoriaId;
        public String categoria = "";
        public long proveedorId;            // 0 = sin proveedor
        public String proveedor = "";
        public String descripcion = "";
        public long valor;
        public boolean credito;             // false = contado
        public MetodoPago metodo;           // contado; null en crédito
        public boolean saleCaja;            // contado en efectivo que sale de la caja
        public long usuarioId;
        public String responsable = "";
        public boolean anulado;
        public String motivo = "";
        public long fijoId;                 // vino de un gasto fijo
        public long abonado;                // lo abonado (crédito)
        public long abonadoCaja;            // de lo abonado, lo que salió de la caja

        /** Lo que falta por pagar (0 en contado). */
        public long saldo() {
            return credito && !anulado ? Math.max(0, valor - abonado) : 0;
        }

        /** PAGADO, POR PAGAR o ANULADO. */
        public String estado() {
            return anulado ? "ANULADO" : saldo() > 0 ? "POR PAGAR" : "PAGADO";
        }

        /** Lo que salió de la caja por este gasto (contado en efectivo, o abonos en efectivo). */
        public long egresoCaja() {
            if (anulado) {
                return 0;
            }
            return credito ? abonadoCaja : saleCaja ? valor : 0;
        }
    }

    public static class Abono {
        public long id;
        public long gastoId;
        public long gastoNumero;
        public LocalDateTime fecha;
        public long valor;
        public MetodoPago metodo;
        public boolean saleCaja;
        public String descripcion = "";
        public String proveedor = "";
        public String responsable = "";
    }

    public static class Fijo {
        public long id;
        public long categoriaId;
        public String categoria = "";
        public long proveedorId;
        public String proveedor = "";
        public String descripcion = "";
        public long valor;
        public MetodoPago metodo = MetodoPago.EFECTIVO;
        public int dia;                     // día del mes en que se paga (1 a 31)
        public LocalDate proximo;           // la próxima vez que toca pagarlo
        public boolean activo = true;

        /** Días que faltan (negativo = vencido). */
        public long diasPara(LocalDate hoy) {
            return java.time.temporal.ChronoUnit.DAYS.between(hoy, proximo);
        }
    }

    /** Una fila del informe por categoría. */
    public static class Total {
        public String categoria;
        public int cantidad;
        public long total;
        public long anterior;               // el mismo largo de tiempo justo antes
    }

    private final BaseTienda bd;
    private final Connection con;

    public BaseGastos(BaseTienda bd) {
        this.bd = bd;
        this.con = bd.conexion();
    }

    /** Se llama al abrir la base (crea lo que falte y las categorías iniciales). */
    static void crearTablas(Statement st) throws SQLException {
        st.execute("CREATE TABLE IF NOT EXISTS gasto_categorias ("
                + " id INTEGER PRIMARY KEY, nombre TEXT NOT NULL, clave TEXT NOT NULL UNIQUE,"
                + " concepto TEXT NOT NULL DEFAULT '', renta TEXT NOT NULL DEFAULT 'SIN_CLASIFICAR',"
                + " activa INTEGER NOT NULL DEFAULT 1, creada TEXT NOT NULL)");
        st.execute("CREATE TABLE IF NOT EXISTS gastos ("
                + " id INTEGER PRIMARY KEY, numero INTEGER NOT NULL UNIQUE, fecha TEXT NOT NULL,"
                + " categoria_id INTEGER NOT NULL REFERENCES gasto_categorias(id),"
                + " proveedor_id INTEGER REFERENCES proveedores(id), proveedor TEXT NOT NULL DEFAULT '',"
                + " descripcion TEXT NOT NULL DEFAULT '', valor INTEGER NOT NULL CHECK (valor > 0),"
                + " credito INTEGER NOT NULL DEFAULT 0, metodo TEXT, sale_caja INTEGER NOT NULL DEFAULT 0,"
                + " usuario_id INTEGER NOT NULL REFERENCES usuarios(id), fijo_id INTEGER,"
                + " anulado INTEGER NOT NULL DEFAULT 0, motivo TEXT NOT NULL DEFAULT '', anulado_en TEXT,"
                + " anulado_por INTEGER REFERENCES usuarios(id), creado TEXT NOT NULL)");
        st.execute("CREATE INDEX IF NOT EXISTS idx_gastos_fecha ON gastos(fecha)");
        st.execute("CREATE TABLE IF NOT EXISTS gasto_abonos ("
                + " id INTEGER PRIMARY KEY, gasto_id INTEGER NOT NULL REFERENCES gastos(id),"
                + " fecha TEXT NOT NULL, valor INTEGER NOT NULL CHECK (valor > 0), metodo TEXT NOT NULL,"
                + " sale_caja INTEGER NOT NULL DEFAULT 0, usuario_id INTEGER NOT NULL REFERENCES usuarios(id))");
        st.execute("CREATE TABLE IF NOT EXISTS gastos_fijos ("
                + " id INTEGER PRIMARY KEY, categoria_id INTEGER NOT NULL REFERENCES gasto_categorias(id),"
                + " proveedor_id INTEGER REFERENCES proveedores(id), proveedor TEXT NOT NULL DEFAULT '',"
                + " descripcion TEXT NOT NULL DEFAULT '', valor INTEGER NOT NULL CHECK (valor > 0),"
                + " metodo TEXT NOT NULL DEFAULT 'EFECTIVO', dia INTEGER NOT NULL CHECK (dia BETWEEN 1 AND 31),"
                + " proximo TEXT NOT NULL, activo INTEGER NOT NULL DEFAULT 1, creado TEXT NOT NULL)");
        try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM gasto_categorias")) {
            if (rs.next() && rs.getInt(1) == 0) {
                for (String[] c : CATEGORIAS_INICIALES) {
                    st.execute("INSERT INTO gasto_categorias(nombre, clave, concepto, creada) VALUES('"
                            + c[0] + "','" + clave(c[0]) + "','" + c[1] + "','" + LocalDateTime.now() + "')");
                }
            }
        }
    }

    private static String clave(String nombre) {
        return rutas.modelo.Geo.normalizar(nombre == null ? "" : nombre);
    }

    // ------------------------------------------------------------------
    //  Categorías
    // ------------------------------------------------------------------

    public List<Categoria> categorias(boolean soloActivas) throws SQLException {
        List<Categoria> l = new ArrayList<>();
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, nombre, concepto, renta, activa FROM gasto_categorias"
                     + (soloActivas ? " WHERE activa=1" : "") + " ORDER BY nombre")) {
            while (rs.next()) {
                Categoria c = new Categoria();
                c.id = rs.getLong(1);
                c.nombre = rs.getString(2);
                c.concepto = rs.getString(3);
                try {
                    c.renta = Renta.valueOf(rs.getString(4));
                } catch (IllegalArgumentException e) {
                    c.renta = Renta.SIN_CLASIFICAR;
                }
                c.activa = rs.getInt(5) == 1;
                l.add(c);
            }
        }
        return l;
    }

    /** Nueva (id 0) o cambio. El nombre va en mayúsculas y no se repite. */
    public void guardarCategoria(Categoria c) throws SQLException {
        String nombre = c.nombre == null ? "" : c.nombre.trim().replaceAll("\\s+", " ").toUpperCase();
        if (nombre.isEmpty()) {
            throw new IllegalArgumentException("Escriba el nombre de la categoría");
        }
        if (nombre.length() > 60) {
            throw new IllegalArgumentException("El nombre es muy largo (máximo 60 letras)");
        }
        try (PreparedStatement ps = con.prepareStatement("SELECT id FROM gasto_categorias WHERE clave=? AND id<>?")) {
            ps.setString(1, clave(nombre));
            ps.setLong(2, c.id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    throw new IllegalArgumentException("Ya hay una categoría llamada " + nombre);
                }
            }
        }
        String concepto = c.concepto == null ? "" : c.concepto;
        if (c.id == 0) {
            try (PreparedStatement ps = con.prepareStatement("INSERT INTO gasto_categorias(nombre, clave, concepto,"
                    + " renta, activa, creada) VALUES(?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, nombre);
                ps.setString(2, clave(nombre));
                ps.setString(3, concepto);
                ps.setString(4, c.renta.name());
                ps.setInt(5, c.activa ? 1 : 0);
                ps.setString(6, LocalDateTime.now().toString());
                ps.executeUpdate();
                try (ResultSet k = ps.getGeneratedKeys()) {
                    k.next();
                    c.id = k.getLong(1);
                }
            }
        } else {
            try (PreparedStatement ps = con.prepareStatement("UPDATE gasto_categorias SET nombre=?, clave=?, concepto=?,"
                    + " renta=?, activa=? WHERE id=?")) {
                ps.setString(1, nombre);
                ps.setString(2, clave(nombre));
                ps.setString(3, concepto);
                ps.setString(4, c.renta.name());
                ps.setInt(5, c.activa ? 1 : 0);
                ps.setLong(6, c.id);
                ps.executeUpdate();
            }
        }
        c.nombre = nombre;
    }

    /**
     * Quitar: si no tiene gastos se borra; si ya tiene, solo se desactiva (el historial y el informe
     * no pierden nada). Devuelve true si se borró.
     */
    public boolean quitarCategoria(long id) throws SQLException {
        boolean usada;
        try (PreparedStatement ps = con.prepareStatement("SELECT (SELECT COUNT(*) FROM gastos WHERE categoria_id=?)"
                + " + (SELECT COUNT(*) FROM gastos_fijos WHERE categoria_id=?)")) {
            ps.setLong(1, id);
            ps.setLong(2, id);
            try (ResultSet rs = ps.executeQuery()) {
                usada = rs.next() && rs.getInt(1) > 0;
            }
        }
        try (PreparedStatement ps = con.prepareStatement(usada ? "UPDATE gasto_categorias SET activa=0 WHERE id=?"
                : "DELETE FROM gasto_categorias WHERE id=?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
        return !usada;
    }

    // ------------------------------------------------------------------
    //  Gastos
    // ------------------------------------------------------------------

    /** Registra un gasto nuevo (le pone número). Contado: medio de pago obligatorio. */
    public void registrar(Gasto g) throws SQLException {
        validar(g);
        boolean auto = con.getAutoCommit();
        con.setAutoCommit(false);
        try {
            long numero;
            try (Statement st = con.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(numero), 0) + 1 FROM gastos")) {
                rs.next();
                numero = rs.getLong(1);
            }
            try (PreparedStatement ps = con.prepareStatement("INSERT INTO gastos(numero, fecha, categoria_id, proveedor_id,"
                    + " proveedor, descripcion, valor, credito, metodo, sale_caja, usuario_id, fijo_id, creado)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, numero);
                ps.setString(2, g.fecha.withNano(0).toString());
                ps.setLong(3, g.categoriaId);
                if (g.proveedorId > 0) {
                    ps.setLong(4, g.proveedorId);
                } else {
                    ps.setNull(4, java.sql.Types.INTEGER);
                }
                ps.setString(5, g.proveedor == null ? "" : g.proveedor.trim());
                ps.setString(6, g.descripcion == null ? "" : g.descripcion.trim());
                ps.setLong(7, g.valor);
                ps.setInt(8, g.credito ? 1 : 0);
                ps.setString(9, g.credito ? null : g.metodo.name());
                ps.setInt(10, !g.credito && g.metodo == MetodoPago.EFECTIVO && g.saleCaja ? 1 : 0);
                ps.setLong(11, g.usuarioId);
                if (g.fijoId > 0) {
                    ps.setLong(12, g.fijoId);
                } else {
                    ps.setNull(12, java.sql.Types.INTEGER);
                }
                ps.setString(13, LocalDateTime.now().withNano(0).toString());
                ps.executeUpdate();
                try (ResultSet k = ps.getGeneratedKeys()) {
                    k.next();
                    g.id = k.getLong(1);
                }
            }
            con.commit();
            g.numero = numero;
            g.saleCaja = !g.credito && g.metodo == MetodoPago.EFECTIVO && g.saleCaja;
        } catch (SQLException | RuntimeException e) {
            con.rollback();
            throw e;
        } finally {
            con.setAutoCommit(auto);
        }
    }

    static void validar(Gasto g) {
        if (g.fecha == null) {
            throw new IllegalArgumentException("Escriba la fecha del gasto");
        }
        if (g.fecha.isAfter(LocalDateTime.now().plusDays(1))) {
            throw new IllegalArgumentException("La fecha del gasto no puede ser de días que no han llegado");
        }
        if (g.categoriaId <= 0) {
            throw new IllegalArgumentException("La categoría es requerida");
        }
        if (g.valor <= 0) {
            throw new IllegalArgumentException("Escriba el valor del gasto");
        }
        if (g.valor > 1_000_000_000L) {
            throw new IllegalArgumentException("El valor es muy grande: revíselo");
        }
        if (!g.credito && g.metodo == null) {
            throw new IllegalArgumentException("El método de pago es requerido");
        }
        if (g.usuarioId <= 0) {
            throw new IllegalArgumentException("No hay un usuario con la sesión abierta");
        }
    }

    /** Filtros del historial (null o 0 = todos). */
    public static class Filtro {
        public LocalDateTime desde;
        public LocalDateTime hasta;
        public long usuarioId;
        public long proveedorId;
        public MetodoPago metodo;
        public Boolean credito;             // null todos, false contado, true crédito
        public long categoriaId;
    }

    private static final String SELECT_GASTO = "SELECT g.id, g.numero, g.fecha, g.categoria_id, c.nombre,"
            + " COALESCE(g.proveedor_id, 0), CASE WHEN p.nombre IS NOT NULL THEN p.nombre ELSE g.proveedor END,"
            + " g.descripcion, g.valor, g.credito, g.metodo, g.sale_caja, g.usuario_id, u.nombre, g.anulado, g.motivo,"
            + " COALESCE(g.fijo_id, 0),"
            + " (SELECT COALESCE(SUM(a.valor), 0) FROM gasto_abonos a WHERE a.gasto_id = g.id),"
            + " (SELECT COALESCE(SUM(a.valor), 0) FROM gasto_abonos a WHERE a.gasto_id = g.id AND a.sale_caja = 1)"
            + " FROM gastos g JOIN gasto_categorias c ON c.id = g.categoria_id"
            + " LEFT JOIN proveedores p ON p.id = g.proveedor_id JOIN usuarios u ON u.id = g.usuario_id";

    private static Gasto gasto(ResultSet rs) throws SQLException {
        Gasto g = new Gasto();
        g.id = rs.getLong(1);
        g.numero = rs.getLong(2);
        g.fecha = LocalDateTime.parse(rs.getString(3));
        g.categoriaId = rs.getLong(4);
        g.categoria = rs.getString(5);
        g.proveedorId = rs.getLong(6);
        g.proveedor = rs.getString(7) == null ? "" : rs.getString(7);
        g.descripcion = rs.getString(8);
        g.valor = rs.getLong(9);
        g.credito = rs.getInt(10) == 1;
        String m = rs.getString(11);
        g.metodo = m == null ? null : MetodoPago.valueOf(m);
        g.saleCaja = rs.getInt(12) == 1;
        g.usuarioId = rs.getLong(13);
        g.responsable = rs.getString(14);
        g.anulado = rs.getInt(15) == 1;
        g.motivo = rs.getString(16);
        g.fijoId = rs.getLong(17);
        g.abonado = rs.getLong(18);
        g.abonadoCaja = rs.getLong(19);
        return g;
    }

    /** El historial: los gastos entre dos fechas con los filtros, del más nuevo al más viejo. */
    public List<Gasto> gastos(Filtro f) throws SQLException {
        if (f.desde == null || f.hasta == null) {
            throw new IllegalArgumentException("Escriba la fecha inicial y la fecha final");
        }
        if (f.hasta.isBefore(f.desde)) {
            throw new IllegalArgumentException("La fecha final es antes de la inicial");
        }
        StringBuilder sql = new StringBuilder(SELECT_GASTO).append(" WHERE g.fecha >= ? AND g.fecha <= ?");
        List<Object> p = new ArrayList<>();
        p.add(f.desde.withNano(0).toString());
        p.add(f.hasta.withSecond(59).withNano(0).toString());
        if (f.usuarioId > 0) {
            sql.append(" AND g.usuario_id = ?");
            p.add(f.usuarioId);
        }
        if (f.proveedorId > 0) {
            sql.append(" AND g.proveedor_id = ?");
            p.add(f.proveedorId);
        }
        if (f.metodo != null) { // el de contado, o algún abono hecho con ese medio
            sql.append(" AND (g.metodo = ? OR EXISTS (SELECT 1 FROM gasto_abonos a WHERE a.gasto_id = g.id AND a.metodo = ?))");
            p.add(f.metodo.name());
            p.add(f.metodo.name());
        }
        if (f.credito != null) {
            sql.append(" AND g.credito = ?");
            p.add(f.credito ? 1 : 0);
        }
        if (f.categoriaId > 0) {
            sql.append(" AND g.categoria_id = ?");
            p.add(f.categoriaId);
        }
        sql.append(" ORDER BY g.fecha DESC, g.numero DESC");
        List<Gasto> l = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql.toString())) {
            for (int i = 0; i < p.size(); i++) {
                ps.setObject(i + 1, p.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    l.add(gasto(rs));
                }
            }
        }
        return l;
    }

    public Gasto gasto(long id) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(SELECT_GASTO + " WHERE g.id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("Ese gasto ya no existe");
                }
                return gasto(rs);
            }
        }
    }

    /** Anula el gasto (y sus abonos dejan de contar). No se borra: sigue en el historial. */
    public void anular(long id, long usuarioId, String motivo) throws SQLException {
        String m = motivo == null ? "" : motivo.trim();
        if (m.length() < 4) {
            throw new IllegalArgumentException("Escriba por qué se anula (por ejemplo: «se registró dos veces»)");
        }
        Gasto g = gasto(id);
        if (g.anulado) {
            throw new IllegalStateException("Ese gasto ya estaba anulado");
        }
        try (PreparedStatement ps = con.prepareStatement("UPDATE gastos SET anulado=1, motivo=?, anulado_en=?,"
                + " anulado_por=? WHERE id=? AND anulado=0")) {
            ps.setString(1, m);
            ps.setString(2, LocalDateTime.now().withNano(0).toString());
            ps.setLong(3, usuarioId);
            ps.setLong(4, id);
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------
    //  Abonos (gastos a crédito)
    // ------------------------------------------------------------------

    /** Abona a un gasto a crédito. No puede pasarse del saldo. */
    public Abono abonar(long gastoId, long valor, MetodoPago metodo, boolean saleCaja, long usuarioId) throws SQLException {
        Gasto g = gasto(gastoId);
        if (g.anulado) {
            throw new IllegalStateException("Ese gasto está anulado");
        }
        if (!g.credito) {
            throw new IllegalStateException("Ese gasto fue de contado: no lleva abonos");
        }
        if (metodo == null) {
            throw new IllegalArgumentException("El método de pago es requerido");
        }
        if (valor <= 0) {
            throw new IllegalArgumentException("Escriba el valor del abono");
        }
        if (valor > g.saldo()) {
            throw new IllegalArgumentException("El abono es más que el saldo (" + Tarifa.pesos(g.saldo()) + ")");
        }
        Abono a = new Abono();
        a.gastoId = gastoId;
        a.fecha = LocalDateTime.now().withNano(0);
        a.valor = valor;
        a.metodo = metodo;
        a.saleCaja = metodo == MetodoPago.EFECTIVO && saleCaja;
        try (PreparedStatement ps = con.prepareStatement("INSERT INTO gasto_abonos(gasto_id, fecha, valor, metodo,"
                + " sale_caja, usuario_id) VALUES(?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, gastoId);
            ps.setString(2, a.fecha.toString());
            ps.setLong(3, valor);
            ps.setString(4, metodo.name());
            ps.setInt(5, a.saleCaja ? 1 : 0);
            ps.setLong(6, usuarioId);
            ps.executeUpdate();
            try (ResultSet k = ps.getGeneratedKeys()) {
                k.next();
                a.id = k.getLong(1);
            }
        }
        return a;
    }

    /** Historial de abonos entre dos fechas (de gastos no anulados), con filtros y búsqueda. */
    public List<Abono> abonos(LocalDateTime desde, LocalDateTime hasta, long usuarioId, MetodoPago metodo,
                              String buscar) throws SQLException {
        if (desde == null || hasta == null) {
            throw new IllegalArgumentException("Escriba la fecha inicial y la fecha final");
        }
        StringBuilder sql = new StringBuilder("SELECT a.id, a.gasto_id, g.numero, a.fecha, a.valor, a.metodo, a.sale_caja,"
                + " g.descripcion, CASE WHEN p.nombre IS NOT NULL THEN p.nombre ELSE g.proveedor END, u.nombre"
                + " FROM gasto_abonos a JOIN gastos g ON g.id = a.gasto_id LEFT JOIN proveedores p ON p.id = g.proveedor_id"
                + " JOIN usuarios u ON u.id = a.usuario_id WHERE g.anulado = 0 AND a.fecha >= ? AND a.fecha <= ?");
        List<Object> p = new ArrayList<>();
        p.add(desde.withNano(0).toString());
        p.add(hasta.withSecond(59).withNano(0).toString());
        if (usuarioId > 0) {
            sql.append(" AND a.usuario_id = ?");
            p.add(usuarioId);
        }
        if (metodo != null) {
            sql.append(" AND a.metodo = ?");
            p.add(metodo.name());
        }
        sql.append(" ORDER BY a.fecha DESC, a.id DESC");
        String b = clave(buscar);
        List<Abono> l = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql.toString())) {
            for (int i = 0; i < p.size(); i++) {
                ps.setObject(i + 1, p.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Abono a = new Abono();
                    a.id = rs.getLong(1);
                    a.gastoId = rs.getLong(2);
                    a.gastoNumero = rs.getLong(3);
                    a.fecha = LocalDateTime.parse(rs.getString(4));
                    a.valor = rs.getLong(5);
                    a.metodo = MetodoPago.valueOf(rs.getString(6));
                    a.saleCaja = rs.getInt(7) == 1;
                    a.descripcion = rs.getString(8);
                    a.proveedor = rs.getString(9) == null ? "" : rs.getString(9);
                    a.responsable = rs.getString(10);
                    if (b.isEmpty() || clave(a.descripcion).contains(b) || clave(a.proveedor).contains(b)
                            || String.valueOf(a.gastoNumero).equals(b)) {
                        l.add(a);
                    }
                }
            }
        }
        return l;
    }

    /** Los abonos de un gasto, del primero al último. */
    public List<Abono> abonosDe(long gastoId) throws SQLException {
        List<Abono> l = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement("SELECT a.id, a.fecha, a.valor, a.metodo, a.sale_caja, u.nombre"
                + " FROM gasto_abonos a JOIN usuarios u ON u.id = a.usuario_id WHERE a.gasto_id = ? ORDER BY a.id")) {
            ps.setLong(1, gastoId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Abono a = new Abono();
                    a.id = rs.getLong(1);
                    a.gastoId = gastoId;
                    a.fecha = LocalDateTime.parse(rs.getString(2));
                    a.valor = rs.getLong(3);
                    a.metodo = MetodoPago.valueOf(rs.getString(4));
                    a.saleCaja = rs.getInt(5) == 1;
                    a.responsable = rs.getString(6);
                    l.add(a);
                }
            }
        }
        return l;
    }

    // ------------------------------------------------------------------
    //  Gastos fijos (se repiten cada mes)
    // ------------------------------------------------------------------

    /**
     * La fecha de pago en un mes: el día pedido, o el último día si el mes es más corto
     * (un arriendo del 31 se paga el 30 en noviembre y el 28 o 29 en febrero).
     */
    public static LocalDate fechaEnMes(java.time.YearMonth mes, int dia) {
        return mes.atDay(Math.min(Math.max(1, dia), mes.lengthOfMonth()));
    }

    /** La primera vez que toca: este mes si el día no ha pasado, si no el mes siguiente. */
    public static LocalDate primerPago(LocalDate hoy, int dia) {
        LocalDate esteMes = fechaEnMes(java.time.YearMonth.from(hoy), dia);
        return esteMes.isBefore(hoy) ? fechaEnMes(java.time.YearMonth.from(hoy).plusMonths(1), dia) : esteMes;
    }

    /**
     * Los fijos activos, del que se vence primero al último. Se ordenan con el montículo de mínimos
     * (cola de prioridad): la prioridad es el día en que toca pagar.
     */
    public List<Fijo> fijos() throws SQLException {
        List<Fijo> todos = new ArrayList<>();
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT f.id, f.categoria_id, c.nombre, COALESCE(f.proveedor_id, 0),"
                     + " CASE WHEN p.nombre IS NOT NULL THEN p.nombre ELSE f.proveedor END, f.descripcion, f.valor,"
                     + " f.metodo, f.dia, f.proximo FROM gastos_fijos f JOIN gasto_categorias c ON c.id = f.categoria_id"
                     + " LEFT JOIN proveedores p ON p.id = f.proveedor_id WHERE f.activo = 1")) {
            while (rs.next()) {
                Fijo f = new Fijo();
                f.id = rs.getLong(1);
                f.categoriaId = rs.getLong(2);
                f.categoria = rs.getString(3);
                f.proveedorId = rs.getLong(4);
                f.proveedor = rs.getString(5) == null ? "" : rs.getString(5);
                f.descripcion = rs.getString(6);
                f.valor = rs.getLong(7);
                f.metodo = MetodoPago.valueOf(rs.getString(8));
                f.dia = rs.getInt(9);
                f.proximo = LocalDate.parse(rs.getString(10));
                todos.add(f);
            }
        }
        return ordenarPorFecha(todos);
    }

    /** Del que toca primero al último, sacándolos del montículo uno por uno. */
    static List<Fijo> ordenarPorFecha(List<Fijo> fijos) {
        MonticuloMin m = new MonticuloMin(Math.max(1, fijos.size()));
        for (int i = 0; i < fijos.size(); i++) {
            // la fecha manda; el número de la fila desempata para que el orden sea siempre el mismo
            m.insertar(i, fijos.get(i).proximo.toEpochDay() + i / 1_000_000.0);
        }
        List<Fijo> r = new ArrayList<>();
        while (!m.estaVacio()) {
            r.add(fijos.get(m.extraerMinimo()));
        }
        return r;
    }

    /** Nuevo (id 0) o cambio. Si es nuevo y no trae fecha, el primer pago es el próximo día que toque. */
    public void guardarFijo(Fijo f) throws SQLException {
        if (f.categoriaId <= 0) {
            throw new IllegalArgumentException("La categoría es requerida");
        }
        if (f.valor <= 0) {
            throw new IllegalArgumentException("Escriba el valor del gasto fijo");
        }
        if (f.metodo == null) {
            throw new IllegalArgumentException("El método de pago es requerido");
        }
        if (f.dia < 1 || f.dia > 31) {
            throw new IllegalArgumentException("El día de pago va del 1 al 31");
        }
        if (f.proximo == null) {
            f.proximo = primerPago(LocalDate.now(), f.dia);
        }
        String sql = f.id == 0
                ? "INSERT INTO gastos_fijos(categoria_id, proveedor_id, proveedor, descripcion, valor, metodo, dia, proximo,"
                + " creado) VALUES(?,?,?,?,?,?,?,?,?)"
                : "UPDATE gastos_fijos SET categoria_id=?, proveedor_id=?, proveedor=?, descripcion=?, valor=?, metodo=?,"
                + " dia=?, proximo=? WHERE id=?";
        try (PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, f.categoriaId);
            if (f.proveedorId > 0) {
                ps.setLong(2, f.proveedorId);
            } else {
                ps.setNull(2, java.sql.Types.INTEGER);
            }
            ps.setString(3, f.proveedor == null ? "" : f.proveedor.trim());
            ps.setString(4, f.descripcion == null ? "" : f.descripcion.trim());
            ps.setLong(5, f.valor);
            ps.setString(6, f.metodo.name());
            ps.setInt(7, f.dia);
            ps.setString(8, f.proximo.toString());
            if (f.id == 0) {
                ps.setString(9, LocalDateTime.now().withNano(0).toString());
            } else {
                ps.setLong(9, f.id);
            }
            ps.executeUpdate();
            if (f.id == 0) {
                try (ResultSet k = ps.getGeneratedKeys()) {
                    k.next();
                    f.id = k.getLong(1);
                }
            }
        }
    }

    /** Quitar un fijo: deja de salir (los gastos que ya generó siguen en el historial). */
    public void quitarFijo(long id) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("UPDATE gastos_fijos SET activo=0 WHERE id=?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    /**
     * Pagar el fijo de este mes: crea el gasto de contado (con su medio de pago; si es efectivo puede
     * salir de la caja) y el fijo pasa al mismo día del mes siguiente. Devuelve el gasto creado.
     */
    public Gasto pagarFijo(Fijo f, boolean saleCaja, long usuarioId) throws SQLException {
        Gasto g = new Gasto();
        g.fecha = LocalDateTime.now();
        g.categoriaId = f.categoriaId;
        g.proveedorId = f.proveedorId;
        g.proveedor = f.proveedorId > 0 ? "" : f.proveedor;
        java.time.format.DateTimeFormatter mes = java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy",
                new java.util.Locale("es", "CO"));
        g.descripcion = (f.descripcion.isBlank() ? f.categoria : f.descripcion.trim()) + " · " + f.proximo.format(mes);
        g.valor = f.valor;
        g.metodo = f.metodo;
        g.saleCaja = saleCaja;
        g.usuarioId = usuarioId;
        g.fijoId = f.id;
        boolean auto = con.getAutoCommit();
        registrar(g); // tiene su propia transacción
        LocalDate siguiente = fechaEnMes(java.time.YearMonth.from(f.proximo).plusMonths(1), f.dia);
        try (PreparedStatement ps = con.prepareStatement("UPDATE gastos_fijos SET proximo=? WHERE id=?")) {
            ps.setString(1, siguiente.toString());
            ps.setLong(2, f.id);
            ps.executeUpdate();
        }
        f.proximo = siguiente;
        con.setAutoCommit(auto);
        return g;
    }

    /** Los fijos que vencen en los próximos días (o ya se vencieron): para avisar en Novedades. */
    public List<Fijo> fijosPorVencer(LocalDate hoy, int dias) throws SQLException {
        List<Fijo> l = new ArrayList<>();
        for (Fijo f : fijos()) {
            if (f.diasPara(hoy) <= dias) {
                l.add(f);
            }
        }
        return l;
    }

    // ------------------------------------------------------------------
    //  Informe por categoría
    // ------------------------------------------------------------------

    /**
     * Total por categoría entre dos fechas (sin anulados), con el mismo largo de tiempo justo antes
     * para comparar. Del que más gastó al que menos.
     */
    public List<Total> porCategoria(LocalDateTime desde, LocalDateTime hasta, long categoriaId, long usuarioId,
                                    Boolean credito) throws SQLException {
        if (desde == null || hasta == null) {
            throw new IllegalArgumentException("Escriba la fecha inicial y la fecha final");
        }
        if (hasta.isBefore(desde)) {
            throw new IllegalArgumentException("La fecha final es antes de la inicial");
        }
        java.time.Duration largo = java.time.Duration.between(desde, hasta).plusMinutes(1);
        Map<String, Total> m = new LinkedHashMap<>();
        sumar(m, desde, hasta, categoriaId, usuarioId, credito, false);
        sumar(m, desde.minus(largo), desde.minusSeconds(1), categoriaId, usuarioId, credito, true);
        List<Total> l = new ArrayList<>(m.values());
        l.removeIf(t -> t.total == 0 && t.cantidad == 0 && t.anterior == 0);
        l.sort((a, b) -> Long.compare(b.total, a.total) != 0 ? Long.compare(b.total, a.total)
                : a.categoria.compareTo(b.categoria));
        return l;
    }

    private void sumar(Map<String, Total> m, LocalDateTime desde, LocalDateTime hasta, long categoriaId, long usuarioId,
                       Boolean credito, boolean anterior) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT c.nombre, COUNT(*), SUM(g.valor) FROM gastos g"
                + " JOIN gasto_categorias c ON c.id = g.categoria_id WHERE g.anulado = 0 AND g.fecha >= ? AND g.fecha <= ?");
        List<Object> p = new ArrayList<>();
        p.add(desde.withNano(0).toString());
        p.add(hasta.withSecond(59).withNano(0).toString());
        if (categoriaId > 0) {
            sql.append(" AND g.categoria_id = ?");
            p.add(categoriaId);
        }
        if (usuarioId > 0) {
            sql.append(" AND g.usuario_id = ?");
            p.add(usuarioId);
        }
        if (credito != null) {
            sql.append(" AND g.credito = ?");
            p.add(credito ? 1 : 0);
        }
        sql.append(" GROUP BY c.nombre");
        try (PreparedStatement ps = con.prepareStatement(sql.toString())) {
            for (int i = 0; i < p.size(); i++) {
                ps.setObject(i + 1, p.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Total t = m.computeIfAbsent(rs.getString(1), k -> {
                        Total x = new Total();
                        x.categoria = k;
                        return x;
                    });
                    if (anterior) {
                        t.anterior += rs.getLong(3);
                    } else {
                        t.cantidad += rs.getInt(2);
                        t.total += rs.getLong(3);
                    }
                }
            }
        }
    }

    /** Lo vendido (ventas cobradas) entre dos fechas: para «lo que queda» en el informe. */
    public long ventas(LocalDateTime desde, LocalDateTime hasta) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT COALESCE(SUM(total), 0) FROM ventas"
                + " WHERE estado = 'PAGADA' AND pagada >= ? AND pagada <= ?")) {
            ps.setString(1, desde.withNano(0).toString());
            ps.setString(2, hasta.withSecond(59).withNano(0).toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    /** Lo que salió de la caja en efectivo por gastos entre dos fechas (contado y abonos). */
    public long salioDeCaja(LocalDateTime desde, LocalDateTime hasta) throws SQLException {
        String d = desde.withNano(0).toString(), h = hasta.withSecond(59).withNano(0).toString();
        try (PreparedStatement ps = con.prepareStatement("SELECT"
                + " (SELECT COALESCE(SUM(valor), 0) FROM gastos WHERE anulado = 0 AND sale_caja = 1 AND fecha >= ? AND fecha <= ?)"
                + " + (SELECT COALESCE(SUM(a.valor), 0) FROM gasto_abonos a JOIN gastos g ON g.id = a.gasto_id"
                + "    WHERE g.anulado = 0 AND a.sale_caja = 1 AND a.fecha >= ? AND a.fecha <= ?)")) {
            ps.setString(1, d);
            ps.setString(2, h);
            ps.setString(3, d);
            ps.setString(4, h);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    BaseTienda base() {
        return bd;
    }
}
