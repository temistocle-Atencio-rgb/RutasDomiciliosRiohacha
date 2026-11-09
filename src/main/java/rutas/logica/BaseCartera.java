package rutas.logica;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import rutas.estructuras.MonticuloMin;
import rutas.modelo.MetodoPago;

/**
 * Cartera de la tienda, en la misma base:
 * - Cuentas por cobrar: las ventas fiadas a un cliente y sus abonos.
 * - Cuentas por pagar: las compras a crédito (y los gastos a crédito de BaseGastos) y sus abonos.
 * - Presupuesto por categoría de gasto y mes.
 * - Flujo de efectivo: lo que entró y salió de verdad, por actividad y por medio de pago.
 * Nada se borra: un abono se anula con un motivo y deja de contar.
 */
public class BaseCartera {

    /** Días de plazo si no se dice otra cosa. */
    public static final int PLAZO = 30;

    /** Los rangos de edad de la cartera. */
    public enum Rango {
        POR_VENCER("Por vencer"), D1_30("1-30 días"), D31_60("31-60 días"), D61_90("61-90 días"),
        MAS_90("Más de 90");

        public final String texto;

        Rango(String texto) {
            this.texto = texto;
        }

        public static Rango de(long diasVencido) {
            return diasVencido <= 0 ? POR_VENCER : diasVencido <= 30 ? D1_30 : diasVencido <= 60 ? D31_60
                    : diasVencido <= 90 ? D61_90 : MAS_90;
        }
    }

    /** Qué es cada documento de la cartera. */
    public enum Tipo {
        FIADO("Venta fiada"), COMPRA("Compra a crédito"), GASTO("Gasto a crédito");

        public final String texto;

        Tipo(String texto) {
            this.texto = texto;
        }
    }

    /** Una venta fiada, compra o gasto a crédito con lo que falta por pagar a la fecha de corte. */
    public static class Documento {
        public Tipo tipo;
        public long id;                 // id del fiado, de la compra o del gasto
        public long numero;             // número de la venta, compra o gasto
        public String identificacion = "";
        public String nombre = "";
        public String celular = "";
        public long cupo;               // cupo de fiado del cliente (0 = sin tope)
        public LocalDateTime fecha;
        public LocalDate vence;
        public long valor;
        public long abonado;
        public long diasVencido;

        public long saldo() {
            return Math.max(0, valor - abonado);
        }

        public Rango rango() {
            return Rango.de(diasVencido);
        }
    }

    public static class Abono {
        public long id;
        public LocalDateTime fecha;
        public long valor;
        public MetodoPago metodo;
        public boolean saleCaja;
        public String responsable = "";
        public boolean anulado;
        public String motivo = "";
    }

    /** Totales por rango (los seis cuadros de arriba). */
    public static class Edades {
        public final Map<Rango, Long> porRango = new EnumMap<>(Rango.class);
        public long total;

        Edades() {
            for (Rango r : Rango.values()) {
                porRango.put(r, 0L);
            }
        }
    }

    private final Connection con;

    public BaseCartera(BaseTienda bd) {
        this.con = bd.conexion();
    }

    static void crearTablas(Statement st) throws SQLException {
        st.execute("CREATE TABLE IF NOT EXISTS fiados ("
                + " id INTEGER PRIMARY KEY, venta_id INTEGER NOT NULL UNIQUE REFERENCES ventas(id),"
                + " cliente TEXT NOT NULL, documento TEXT NOT NULL DEFAULT '', celular TEXT NOT NULL DEFAULT '',"
                + " valor INTEGER NOT NULL CHECK (valor > 0), vence TEXT NOT NULL, creado TEXT NOT NULL)");
        st.execute("CREATE TABLE IF NOT EXISTS fiado_abonos ("
                + " id INTEGER PRIMARY KEY, fiado_id INTEGER NOT NULL REFERENCES fiados(id), fecha TEXT NOT NULL,"
                + " valor INTEGER NOT NULL CHECK (valor > 0), metodo TEXT NOT NULL,"
                + " usuario_id INTEGER NOT NULL REFERENCES usuarios(id), anulado INTEGER NOT NULL DEFAULT 0,"
                + " motivo TEXT NOT NULL DEFAULT '', anulado_por INTEGER REFERENCES usuarios(id), anulado_en TEXT)");
        if (!BaseTienda.tieneColumna(st, "compras", "credito")) { // compras de antes: todas de contado
            st.execute("ALTER TABLE compras ADD COLUMN credito INTEGER NOT NULL DEFAULT 0");
            st.execute("ALTER TABLE compras ADD COLUMN vence TEXT");
            st.execute("ALTER TABLE compras ADD COLUMN metodo TEXT");
        }
        st.execute("CREATE TABLE IF NOT EXISTS compra_abonos ("
                + " id INTEGER PRIMARY KEY, compra_id INTEGER NOT NULL REFERENCES compras(id), fecha TEXT NOT NULL,"
                + " valor INTEGER NOT NULL CHECK (valor > 0), metodo TEXT NOT NULL, sale_caja INTEGER NOT NULL DEFAULT 0,"
                + " usuario_id INTEGER NOT NULL REFERENCES usuarios(id), anulado INTEGER NOT NULL DEFAULT 0,"
                + " motivo TEXT NOT NULL DEFAULT '', anulado_por INTEGER REFERENCES usuarios(id), anulado_en TEXT)");
        // abonos de gastos: también se anulan con motivo; los gastos a crédito tienen fecha para pagar
        if (!BaseTienda.tieneColumna(st, "gasto_abonos", "anulado")) {
            st.execute("ALTER TABLE gasto_abonos ADD COLUMN anulado INTEGER NOT NULL DEFAULT 0");
            st.execute("ALTER TABLE gasto_abonos ADD COLUMN motivo TEXT NOT NULL DEFAULT ''");
            st.execute("ALTER TABLE gasto_abonos ADD COLUMN anulado_por INTEGER REFERENCES usuarios(id)");
            st.execute("ALTER TABLE gasto_abonos ADD COLUMN anulado_en TEXT");
        }
        if (!BaseTienda.tieneColumna(st, "gastos", "vence")) {
            st.execute("ALTER TABLE gastos ADD COLUMN vence TEXT");
        }
        // presupuesto: el de todos los meses (mes = '*') y los cambios de un mes ('2026-11')
        st.execute("CREATE TABLE IF NOT EXISTS presupuestos ("
                + " categoria_id INTEGER NOT NULL REFERENCES gasto_categorias(id), mes TEXT NOT NULL,"
                + " valor INTEGER NOT NULL CHECK (valor >= 0), PRIMARY KEY (categoria_id, mes))");
    }

    private static String fin(LocalDate d) {
        return d.atTime(23, 59, 59).toString();
    }

    private static String ahora() {
        return LocalDateTime.now().withNano(0).toString();
    }

    // ------------------------------------------------------------------
    //  Fiado (lo llama BaseTienda al cobrar, dentro de la misma transacción)
    // ------------------------------------------------------------------

    /** Datos del cliente al que se le fía (se guardan tal como estaban ese día). */
    public static class Fiado {
        public String cliente = "";
        public String documento = "";
        public String celular = "";
        public long cupo;               // 0 = sin tope
        public long debeAntes;          // lo que ya debía (para revisar el cupo)
        public LocalDate vence;
    }

    /** Revisa que se pueda fiar (cliente, fecha y cupo). Lanza el error con un texto claro. */
    public static void validarFiado(Fiado f, long total, LocalDate hoy) {
        if (f == null || f.cliente == null || f.cliente.isBlank()) {
            throw new IllegalArgumentException("Para fiar hay que escoger un cliente guardado");
        }
        if (f.vence == null || f.vence.isBefore(hoy)) {
            throw new IllegalArgumentException("La fecha para pagar no puede ser de días que ya pasaron");
        }
        if (f.vence.isAfter(hoy.plusDays(365))) {
            throw new IllegalArgumentException("El plazo máximo es de un año");
        }
        if (f.cupo > 0 && f.debeAntes + total > f.cupo) {
            throw new IllegalArgumentException("Se pasa del cupo de fiado: debe " + Tarifa.pesos(f.debeAntes)
                    + " y su cupo es " + Tarifa.pesos(f.cupo));
        }
    }

    /** Guarda el fiado de una venta (va en la transacción del cobro). */
    static void guardarFiado(Connection con, long ventaId, long total, Fiado f) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("INSERT INTO fiados(venta_id, cliente, documento, celular, valor,"
                + " vence, creado) VALUES(?,?,?,?,?,?,?)")) {
            ps.setLong(1, ventaId);
            ps.setString(2, f.cliente.trim());
            ps.setString(3, f.documento == null ? "" : f.documento.trim());
            ps.setString(4, f.celular == null ? "" : f.celular.trim());
            ps.setLong(5, total);
            ps.setString(6, f.vence.toString());
            ps.setString(7, ahora());
            ps.executeUpdate();
        }
    }

    /** Lo que debe hoy un cliente (por el nombre con el que se le fió). */
    public long debe(String cliente) throws SQLException {
        long t = 0;
        for (Documento d : cobrar(LocalDate.now(), "")) {
            if (rutas.modelo.Geo.normalizar(d.nombre).equals(rutas.modelo.Geo.normalizar(cliente))) {
                t += d.saldo();
            }
        }
        return t;
    }

    // ------------------------------------------------------------------
    //  Cuentas por cobrar y por pagar
    // ------------------------------------------------------------------

    /** Las ventas fiadas con saldo a la fecha de corte (buscar: nombre, cédula o número). */
    public List<Documento> cobrar(LocalDate corte, String buscar) throws SQLException {
        String c = fin(corte);
        List<Documento> l = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement("SELECT f.id, v.numero, f.documento, f.cliente, f.celular,"
                + " v.pagada, f.vence, f.valor, (SELECT COALESCE(SUM(a.valor), 0) FROM fiado_abonos a"
                + "  WHERE a.fiado_id = f.id AND a.anulado = 0 AND a.fecha <= ?)"
                + " FROM fiados f JOIN ventas v ON v.id = f.venta_id WHERE f.creado <= ?")) {
            ps.setString(1, c);
            ps.setString(2, c);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Documento d = new Documento();
                    d.tipo = Tipo.FIADO;
                    d.id = rs.getLong(1);
                    d.numero = rs.getLong(2);
                    d.identificacion = rs.getString(3);
                    d.nombre = rs.getString(4);
                    d.celular = rs.getString(5);
                    d.fecha = LocalDateTime.parse(rs.getString(6));
                    d.vence = LocalDate.parse(rs.getString(7));
                    d.valor = rs.getLong(8);
                    d.abonado = rs.getLong(9);
                    agregar(l, d, corte, buscar);
                }
            }
        }
        return ordenar(l);
    }

    /** Las compras a crédito y los gastos a crédito con saldo a la fecha de corte. */
    public List<Documento> pagar(LocalDate corte, String buscar) throws SQLException {
        String c = fin(corte);
        List<Documento> l = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement("SELECT c.id, c.numero, '', COALESCE(c.proveedor, ''), '',"
                + " c.guardada, COALESCE(c.vence, date(c.guardada, '+30 days')), c.total,"
                + " (SELECT COALESCE(SUM(a.valor), 0) FROM compra_abonos a WHERE a.compra_id = c.id AND a.anulado = 0"
                + "  AND a.fecha <= ?)"
                + " FROM compras c WHERE c.estado = 'GUARDADA' AND c.anulada = 0 AND c.credito = 1 AND c.guardada <= ?")) {
            ps.setString(1, c);
            ps.setString(2, c);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Documento d = new Documento();
                    d.tipo = Tipo.COMPRA;
                    d.id = rs.getLong(1);
                    d.numero = rs.getLong(2);
                    d.nombre = rs.getString(4).isEmpty() ? "Sin proveedor" : rs.getString(4);
                    d.fecha = LocalDateTime.parse(rs.getString(6));
                    d.vence = LocalDate.parse(rs.getString(7).substring(0, 10));
                    d.valor = rs.getLong(8);
                    d.abonado = rs.getLong(9);
                    agregar(l, d, corte, buscar);
                }
            }
        }
        try (PreparedStatement ps = con.prepareStatement("SELECT g.id, g.numero,"
                + " CASE WHEN p.nombre IS NOT NULL THEN p.nombre ELSE g.proveedor END, g.fecha,"
                + " COALESCE(g.vence, date(g.fecha, '+30 days')), g.valor,"
                + " (SELECT COALESCE(SUM(a.valor), 0) FROM gasto_abonos a WHERE a.gasto_id = g.id AND a.anulado = 0"
                + "  AND a.fecha <= ?), COALESCE(p.documento, ''), g.descripcion"
                + " FROM gastos g LEFT JOIN proveedores p ON p.id = g.proveedor_id"
                + " WHERE g.credito = 1 AND g.anulado = 0 AND g.fecha <= ?")) {
            ps.setString(1, c);
            ps.setString(2, c);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Documento d = new Documento();
                    d.tipo = Tipo.GASTO;
                    d.id = rs.getLong(1);
                    d.numero = rs.getLong(2);
                    String prov = rs.getString(3) == null ? "" : rs.getString(3);
                    d.nombre = prov.isEmpty() ? (rs.getString(9).isEmpty() ? "Sin proveedor" : rs.getString(9)) : prov;
                    d.fecha = LocalDateTime.parse(rs.getString(4));
                    d.vence = LocalDate.parse(rs.getString(5).substring(0, 10));
                    d.valor = rs.getLong(6);
                    d.abonado = rs.getLong(7);
                    d.identificacion = rs.getString(8);
                    agregar(l, d, corte, buscar);
                }
            }
        }
        // el documento y el celular del proveedor de las compras (se guardó por nombre)
        Map<String, String[]> provs = new LinkedHashMap<>();
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT nombre, documento, CASE WHEN celular_vendedor <> '' THEN"
                     + " celular_vendedor ELSE telefono END FROM proveedores")) {
            while (rs.next()) {
                provs.put(rutas.modelo.Geo.normalizar(rs.getString(1)), new String[]{rs.getString(2), rs.getString(3)});
            }
        }
        for (Documento d : l) {
            String[] p = provs.get(rutas.modelo.Geo.normalizar(d.nombre));
            if (p != null) {
                if (d.identificacion.isEmpty()) {
                    d.identificacion = p[0] == null ? "" : p[0];
                }
                d.celular = p[1] == null ? "" : p[1];
            }
        }
        return ordenar(l);
    }

    private static void agregar(List<Documento> l, Documento d, LocalDate corte, String buscar) {
        if (d.saldo() <= 0) {
            return;
        }
        d.diasVencido = ChronoUnit.DAYS.between(d.vence, corte);
        String b = rutas.modelo.Geo.normalizar(buscar == null ? "" : buscar);
        if (b.isEmpty() || rutas.modelo.Geo.normalizar(d.nombre).contains(b)
                || d.identificacion.replaceAll("\\D", "").contains(b.replaceAll("\\D", "x"))
                || String.valueOf(d.numero).equals(b)) {
            l.add(d);
        }
    }

    /**
     * Del que lleva más días vencido al que menos (con el montículo de mínimos: la prioridad es
     * menos los días; a igual días, el de más saldo primero).
     */
    static List<Documento> ordenar(List<Documento> l) {
        MonticuloMin m = new MonticuloMin(Math.max(1, l.size()));
        for (int i = 0; i < l.size(); i++) {
            Documento d = l.get(i);
            m.insertar(i, -d.diasVencido * 1e12 - d.saldo() + i / 1e6);
        }
        List<Documento> r = new ArrayList<>();
        while (!m.estaVacio()) {
            r.add(l.get(m.extraerMinimo()));
        }
        return r;
    }

    /** Los seis cuadros: cuánto hay en cada rango y el total. */
    public static Edades edades(List<Documento> l) {
        Edades e = new Edades();
        for (Documento d : l) {
            e.porRango.merge(d.rango(), d.saldo(), Long::sum);
            e.total += d.saldo();
        }
        return e;
    }

    /** Lo que debe en total ese nombre (todas sus filas) y en cuántas. */
    public static long[] totalDe(List<Documento> l, String nombre) {
        long t = 0, n = 0;
        String k = rutas.modelo.Geo.normalizar(nombre);
        for (Documento d : l) {
            if (rutas.modelo.Geo.normalizar(d.nombre).equals(k)) {
                t += d.saldo();
                n++;
            }
        }
        return new long[]{t, n};
    }

    // ------------------------------------------------------------------
    //  Abonos (fiados y compras; los gastos usan BaseGastos con su anulado)
    // ------------------------------------------------------------------

    private static String tablaAbonos(Tipo t) {
        return t == Tipo.FIADO ? "fiado_abonos" : t == Tipo.COMPRA ? "compra_abonos" : "gasto_abonos";
    }

    private static String columna(Tipo t) {
        return t == Tipo.FIADO ? "fiado_id" : t == Tipo.COMPRA ? "compra_id" : "gasto_id";
    }

    /** Los abonos de un documento (también los anulados, marcados). */
    public List<Abono> abonos(Tipo t, long id) throws SQLException {
        List<Abono> l = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement("SELECT a.id, a.fecha, a.valor, a.metodo,"
                + (t == Tipo.FIADO ? " 0," : " a.sale_caja,") + " u.nombre, a.anulado, a.motivo FROM " + tablaAbonos(t)
                + " a JOIN usuarios u ON u.id = a.usuario_id WHERE a." + columna(t) + " = ? ORDER BY a.fecha, a.id")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Abono a = new Abono();
                    a.id = rs.getLong(1);
                    a.fecha = LocalDateTime.parse(rs.getString(2));
                    a.valor = rs.getLong(3);
                    a.metodo = MetodoPago.valueOf(rs.getString(4));
                    a.saleCaja = rs.getInt(5) == 1;
                    a.responsable = rs.getString(6);
                    a.anulado = rs.getInt(7) == 1;
                    a.motivo = rs.getString(8);
                    l.add(a);
                }
            }
        }
        return l;
    }

    /** El documento con su saldo de hoy. */
    public Documento documento(Tipo t, long id) throws SQLException {
        List<Documento> l = t == Tipo.FIADO ? cobrar(LocalDate.now().plusDays(1), "") : pagar(LocalDate.now().plusDays(1), "");
        for (Documento d : l) {
            if (d.tipo == t && d.id == id) {
                return d;
            }
        }
        throw new IllegalStateException("Ya no debe nada: está pagado");
    }

    /**
     * Abona a una venta fiada o a una compra a crédito (el gasto va por BaseGastos.abonar).
     * La fecha no puede ser futura ni antes del documento; el valor no se puede pasar del saldo.
     */
    public Abono abonar(Tipo t, long id, LocalDate fecha, long valor, MetodoPago metodo, boolean saleCaja, long usuarioId)
            throws SQLException {
        if (t == Tipo.GASTO) {
            throw new IllegalArgumentException("Los gastos se abonan con BaseGastos");
        }
        if (metodo == null) {
            throw new IllegalArgumentException("Escoja el método de pago");
        }
        if (valor <= 0) {
            throw new IllegalArgumentException("Escriba el valor del abono");
        }
        LocalDate hoy = LocalDate.now();
        if (fecha == null || fecha.isAfter(hoy)) {
            throw new IllegalArgumentException("La fecha del abono no puede ser de días que no han llegado");
        }
        Documento d = documento(t, id);
        if (fecha.isBefore(d.fecha.toLocalDate())) {
            throw new IllegalArgumentException("La fecha del abono es antes de la " + (t == Tipo.FIADO ? "venta" : "compra"));
        }
        if (valor > d.saldo()) {
            throw new IllegalArgumentException("El abono es más que el saldo (" + Tarifa.pesos(d.saldo()) + ")");
        }
        Abono a = new Abono();
        a.fecha = fecha.equals(hoy) ? LocalDateTime.now().withNano(0) : fecha.atTime(12, 0);
        a.valor = valor;
        a.metodo = metodo;
        a.saleCaja = t == Tipo.COMPRA && metodo == MetodoPago.EFECTIVO && saleCaja;
        String sql = t == Tipo.FIADO
                ? "INSERT INTO fiado_abonos(fiado_id, fecha, valor, metodo, usuario_id) VALUES(?,?,?,?,?)"
                : "INSERT INTO compra_abonos(compra_id, fecha, valor, metodo, usuario_id, sale_caja) VALUES(?,?,?,?,?,?)";
        try (PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, id);
            ps.setString(2, a.fecha.toString());
            ps.setLong(3, valor);
            ps.setString(4, metodo.name());
            ps.setLong(5, usuarioId);
            if (t == Tipo.COMPRA) {
                ps.setInt(6, a.saleCaja ? 1 : 0);
            }
            ps.executeUpdate();
            try (ResultSet k = ps.getGeneratedKeys()) {
                k.next();
                a.id = k.getLong(1);
            }
        }
        return a;
    }

    /** Anula un abono con su motivo (no se borra: queda tachado en la lista y deja de contar). */
    public void anularAbono(Tipo t, long abonoId, long usuarioId, String motivo) throws SQLException {
        String m = motivo == null ? "" : motivo.trim();
        if (m.length() < 4) {
            throw new IllegalArgumentException("Escriba por qué se anula el abono");
        }
        try (PreparedStatement ps = con.prepareStatement("UPDATE " + tablaAbonos(t) + " SET anulado = 1, motivo = ?,"
                + " anulado_por = ?, anulado_en = ? WHERE id = ? AND anulado = 0")) {
            ps.setString(1, m);
            ps.setLong(2, usuarioId);
            ps.setString(3, ahora());
            ps.setLong(4, abonoId);
            if (ps.executeUpdate() == 0) {
                throw new IllegalStateException("Ese abono ya estaba anulado");
            }
        }
    }

    // ------------------------------------------------------------------
    //  Compra a crédito o de contado (se marca después de guardarla)
    // ------------------------------------------------------------------

    /** Contado: con qué se pagó. Crédito: hasta cuándo hay plazo. */
    public void formaDePagoCompra(long numeroCompra, boolean credito, MetodoPago metodo, LocalDate vence) throws SQLException {
        if (credito && vence == null) {
            throw new IllegalArgumentException("Escriba hasta cuándo hay que pagar la compra");
        }
        if (!credito && metodo == null) {
            throw new IllegalArgumentException("Escoja con qué se pagó la compra");
        }
        try (PreparedStatement ps = con.prepareStatement("UPDATE compras SET credito = ?, metodo = ?, vence = ?"
                + " WHERE numero = ?")) {
            ps.setInt(1, credito ? 1 : 0);
            ps.setString(2, credito ? null : metodo.name());
            ps.setString(3, credito ? vence.toString() : null);
            ps.setLong(4, numeroCompra);
            ps.executeUpdate();
        }
    }

    /** Los días de plazo de la forma de pago del proveedor («Crédito 15 días» = 15; contado = 0). */
    public static int diasDe(String formaPago) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)").matcher(formaPago == null ? "" : formaPago);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    // ------------------------------------------------------------------
    //  Presupuesto por categoría y mes
    // ------------------------------------------------------------------

    public static class Presupuesto {
        public long categoriaId;
        public String categoria = "";
        public long valor;              // lo presupuestado ese mes
        public boolean todosLosMeses;   // viene del valor que se repite
        public long gastado;

        public long queda() {
            return valor - gastado;
        }

        /** Porcentaje usado (0 si no tiene presupuesto). */
        public double usado() {
            return valor <= 0 ? 0 : 100.0 * gastado / valor;
        }
    }

    /** El presupuesto de cada categoría activa ese mes y lo gastado; del más pasado al menos (montículo). */
    public List<Presupuesto> presupuesto(YearMonth mes) throws SQLException {
        List<Presupuesto> l = new ArrayList<>();
        String desde = mes.atDay(1).atStartOfDay().toString();
        String hasta = fin(mes.atEndOfMonth());
        try (PreparedStatement ps = con.prepareStatement("SELECT c.id, c.nombre,"
                + " (SELECT valor FROM presupuestos WHERE categoria_id = c.id AND mes = ?),"
                + " (SELECT valor FROM presupuestos WHERE categoria_id = c.id AND mes = '*'),"
                + " (SELECT COALESCE(SUM(g.valor), 0) FROM gastos g WHERE g.categoria_id = c.id AND g.anulado = 0"
                + "  AND g.fecha >= ? AND g.fecha <= ?)"
                + " FROM gasto_categorias c WHERE c.activa = 1 ORDER BY c.nombre")) {
            ps.setString(1, mes.toString());
            ps.setString(2, desde);
            ps.setString(3, hasta);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Presupuesto p = new Presupuesto();
                    p.categoriaId = rs.getLong(1);
                    p.categoria = rs.getString(2);
                    long esteMes = rs.getLong(3);
                    boolean hay = !rs.wasNull();
                    long siempre = rs.getLong(4);
                    p.valor = hay ? esteMes : siempre;
                    p.todosLosMeses = !hay && siempre > 0;
                    p.gastado = rs.getLong(5);
                    if (p.valor > 0 || p.gastado > 0) {
                        l.add(p);
                    }
                }
            }
        }
        MonticuloMin m = new MonticuloMin(Math.max(1, l.size()));
        for (int i = 0; i < l.size(); i++) {
            Presupuesto p = l.get(i);
            // los que no tienen presupuesto pero sí gasto van de primeros (no se controlan)
            m.insertar(i, -(p.valor <= 0 ? 1e6 : p.usado()) + i / 1e6);
        }
        List<Presupuesto> r = new ArrayList<>();
        while (!m.estaVacio()) {
            r.add(l.get(m.extraerMinimo()));
        }
        return r;
    }

    /** Pone el presupuesto de una categoría: solo ese mes, o todos los meses desde ese. */
    public void asignar(long categoriaId, YearMonth mes, long valor, boolean todosLosMeses) throws SQLException {
        if (categoriaId <= 0) {
            throw new IllegalArgumentException("Escoja la categoría");
        }
        if (valor < 0 || valor > 1_000_000_000L) {
            throw new IllegalArgumentException("Revise el valor del presupuesto");
        }
        try (PreparedStatement ps = con.prepareStatement("INSERT INTO presupuestos(categoria_id, mes, valor) VALUES(?,?,?)"
                + " ON CONFLICT(categoria_id, mes) DO UPDATE SET valor = excluded.valor")) {
            ps.setLong(1, categoriaId);
            ps.setString(2, todosLosMeses ? "*" : mes.toString());
            ps.setLong(3, valor);
            ps.executeUpdate();
        }
        if (todosLosMeses) { // desde este mes manda el valor de siempre
            try (PreparedStatement ps = con.prepareStatement("DELETE FROM presupuestos WHERE categoria_id = ? AND mes <> '*'"
                    + " AND mes >= ?")) {
                ps.setLong(1, categoriaId);
                ps.setString(2, mes.toString());
                ps.executeUpdate();
            }
        }
    }

    /** Copia lo del mes anterior a este mes (solo las categorías que tenían algo). Devuelve cuántas. */
    public int copiarMesAnterior(YearMonth mes) throws SQLException {
        int n = 0;
        for (Presupuesto p : presupuesto(mes.minusMonths(1))) {
            if (p.valor > 0 && !p.todosLosMeses) {
                asignar(p.categoriaId, mes, p.valor, false);
                n++;
            }
        }
        return n;
    }

    /** Cómo va una categoría este mes después de un gasto (para el aviso al registrarlo); null si no tiene. */
    public Presupuesto deCategoria(long categoriaId, YearMonth mes) throws SQLException {
        for (Presupuesto p : presupuesto(mes)) {
            if (p.categoriaId == categoriaId && p.valor > 0) {
                return p;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    //  Flujo de efectivo
    // ------------------------------------------------------------------

    public enum Actividad {
        OPERACION("Operación"), INVERSION("Inversión"), FINANCIACION("Financiación");

        public final String texto;

        Actividad(String texto) {
            this.texto = texto;
        }
    }

    /** Una fila del flujo: actividad, concepto y la plata que entró y salió (por medio de pago). */
    public static class Movimiento {
        public Actividad actividad;
        public String concepto;
        public long entradas;
        public long salidas;
        public final Map<MetodoPago, long[]> porMedio = new EnumMap<>(MetodoPago.class); // [entró, salió]

        public long neto() {
            return entradas - salidas;
        }
    }

    /**
     * El flujo entre dos momentos. Solo cuenta plata que se movió de verdad: lo fiado no entra hasta
     * que lo abonan, una compra a crédito no sale hasta que se abona y lo anulado no cuenta.
     */
    public List<Movimiento> flujo(LocalDateTime desde, LocalDateTime hasta) throws SQLException {
        if (desde == null || hasta == null) {
            throw new IllegalArgumentException("Escriba la fecha inicial y la fecha final");
        }
        if (hasta.isBefore(desde)) {
            throw new IllegalArgumentException("La fecha final es antes de la inicial");
        }
        String d = desde.withNano(0).toString(), h = hasta.withSecond(59).withNano(0).toString();
        List<Movimiento> l = new ArrayList<>();
        sumar(l, Actividad.OPERACION, "Ventas cobradas", true, "SELECT p.metodo, SUM(p.valor) FROM venta_pagos p"
                + " JOIN ventas v ON v.id = p.venta_id WHERE v.estado = 'PAGADA' AND v.pagada >= ? AND v.pagada <= ?"
                + " GROUP BY p.metodo", d, h);
        sumar(l, Actividad.OPERACION, "Abonos de clientes (fiado)", true, "SELECT metodo, SUM(valor) FROM fiado_abonos"
                + " WHERE anulado = 0 AND fecha >= ? AND fecha <= ? GROUP BY metodo", d, h);
        sumar(l, Actividad.OPERACION, "Compras de contado", false, "SELECT COALESCE(metodo, 'EFECTIVO'), SUM(total)"
                + " FROM compras WHERE estado = 'GUARDADA' AND anulada = 0 AND credito = 0 AND guardada >= ? AND guardada <= ?"
                + " GROUP BY 1", d, h);
        sumar(l, Actividad.OPERACION, "Abonos a compras a crédito", false, "SELECT a.metodo, SUM(a.valor)"
                + " FROM compra_abonos a JOIN compras c ON c.id = a.compra_id WHERE a.anulado = 0 AND c.anulada = 0"
                + " AND a.fecha >= ? AND a.fecha <= ? GROUP BY a.metodo", d, h);
        String inversion = "c.concepto = '5008'";
        sumar(l, Actividad.OPERACION, "Gastos de contado", false, "SELECT g.metodo, SUM(g.valor) FROM gastos g"
                + " JOIN gasto_categorias c ON c.id = g.categoria_id WHERE g.anulado = 0 AND g.credito = 0 AND NOT ("
                + inversion + ") AND g.fecha >= ? AND g.fecha <= ? GROUP BY g.metodo", d, h);
        sumar(l, Actividad.OPERACION, "Abonos a gastos a crédito", false, "SELECT a.metodo, SUM(a.valor)"
                + " FROM gasto_abonos a JOIN gastos g ON g.id = a.gasto_id JOIN gasto_categorias c ON c.id = g.categoria_id"
                + " WHERE a.anulado = 0 AND g.anulado = 0 AND NOT (" + inversion + ") AND a.fecha >= ? AND a.fecha <= ?"
                + " GROUP BY a.metodo", d, h);
        // inversión: lo que se gastó en equipos (categorías con el concepto DIAN 5008, compra de activos fijos)
        sumar(l, Actividad.INVERSION, "Compra de equipos y muebles", false, "SELECT m, SUM(v) FROM ("
                + " SELECT g.metodo m, g.valor v FROM gastos g JOIN gasto_categorias c ON c.id = g.categoria_id"
                + "  WHERE g.anulado = 0 AND g.credito = 0 AND " + inversion + " AND g.fecha >= ?1 AND g.fecha <= ?2"
                + " UNION ALL SELECT a.metodo, a.valor FROM gasto_abonos a JOIN gastos g ON g.id = a.gasto_id"
                + "  JOIN gasto_categorias c ON c.id = g.categoria_id WHERE a.anulado = 0 AND g.anulado = 0 AND "
                + inversion + " AND a.fecha >= ?1 AND a.fecha <= ?2) GROUP BY m", d, h);
        return l;
    }

    private void sumar(List<Movimiento> l, Actividad act, String concepto, boolean entra, String sql, String d, String h)
            throws SQLException {
        Movimiento m = new Movimiento();
        m.actividad = act;
        m.concepto = concepto;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, d);
            ps.setString(2, h);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    MetodoPago mp;
                    try {
                        mp = MetodoPago.valueOf(rs.getString(1));
                    } catch (RuntimeException e) {
                        mp = MetodoPago.EFECTIVO;
                    }
                    long v = rs.getLong(2);
                    long[] x = m.porMedio.computeIfAbsent(mp, k -> new long[2]);
                    if (entra) {
                        m.entradas += v;
                        x[0] += v;
                    } else {
                        m.salidas += v;
                        x[1] += v;
                    }
                }
            }
        }
        if (m.entradas != 0 || m.salidas != 0) {
            l.add(m);
        }
    }

    /** Las mismas filas, pero sumadas por medio de pago (lo de efectivo es lo que debe haber en el cajón). */
    public static List<Movimiento> porMedio(List<Movimiento> l) {
        Map<MetodoPago, Movimiento> r = new EnumMap<>(MetodoPago.class);
        for (Movimiento m : l) {
            for (Map.Entry<MetodoPago, long[]> e : m.porMedio.entrySet()) {
                Movimiento x = r.computeIfAbsent(e.getKey(), k -> {
                    Movimiento y = new Movimiento();
                    y.actividad = null;
                    y.concepto = k.toString();
                    return y;
                });
                x.entradas += e.getValue()[0];
                x.salidas += e.getValue()[1];
            }
        }
        return new ArrayList<>(r.values());
    }
}
