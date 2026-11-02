package rutas.logica;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import rutas.modelo.Producto;
import rutas.modelo.Proveedor;

/**
 * Proveedores y ordenes de compra en la base de la tienda (tienda.db). Va aparte de BaseTienda para
 * que esa clase no siga creciendo, pero usa la misma conexion.
 */
public class BaseOrdenes {

    private final BaseTienda bd;
    private final Connection con;

    public BaseOrdenes(BaseTienda bd) {
        this.bd = bd;
        this.con = bd.conexion();
    }

    /** Se llama al abrir la base (crea lo que falte). */
    static void crearTablas(Statement st) throws SQLException {
        st.execute("CREATE TABLE IF NOT EXISTS proveedores ("
                + " id INTEGER PRIMARY KEY,"
                + " tipo_documento TEXT NOT NULL DEFAULT '',"
                + " documento TEXT NOT NULL DEFAULT '',"
                + " dv TEXT NOT NULL DEFAULT '',"
                + " nombre TEXT NOT NULL,"
                + " clave_nombre TEXT NOT NULL,"
                + " regimenes TEXT NOT NULL DEFAULT '',"
                + " telefono TEXT NOT NULL DEFAULT '',"
                + " correo TEXT NOT NULL DEFAULT '',"
                + " departamento TEXT NOT NULL DEFAULT '',"
                + " municipio TEXT NOT NULL DEFAULT '',"
                + " direccion TEXT NOT NULL DEFAULT '',"
                + " pagina_web TEXT NOT NULL DEFAULT '',"
                + " vendedor TEXT NOT NULL DEFAULT '',"
                + " celular_vendedor TEXT NOT NULL DEFAULT '',"
                + " dia_visita TEXT NOT NULL DEFAULT '',"
                + " forma_pago TEXT NOT NULL DEFAULT '',"
                + " activo INTEGER NOT NULL DEFAULT 1,"
                + " creado TEXT NOT NULL)");
        st.execute("CREATE TABLE IF NOT EXISTS ordenes_compra ("
                + " id INTEGER PRIMARY KEY,"
                + " numero INTEGER NOT NULL UNIQUE,"
                + " estado TEXT NOT NULL CHECK (estado IN ('BORRADOR','PENDIENTE','PARCIAL','RECIBIDA','CANCELADA')),"
                + " proveedor_id INTEGER REFERENCES proveedores(id),"
                + " proveedor TEXT NOT NULL DEFAULT '',"
                + " bodega_id INTEGER NOT NULL DEFAULT 1,"
                + " usuario_id INTEGER NOT NULL REFERENCES usuarios(id),"
                + " creada TEXT NOT NULL,"
                + " fecha TEXT NOT NULL,"
                + " fecha_estimada TEXT,"
                + " nota TEXT NOT NULL DEFAULT '',"
                + " descuento_general INTEGER NOT NULL DEFAULT 0,"
                + " con_retencion INTEGER NOT NULL DEFAULT 0,"
                + " retencion_pct INTEGER NOT NULL DEFAULT 0,"
                + " bruto INTEGER NOT NULL DEFAULT 0,"
                + " descuentos INTEGER NOT NULL DEFAULT 0,"
                + " impuestos INTEGER NOT NULL DEFAULT 0,"
                + " retencion INTEGER NOT NULL DEFAULT 0,"
                + " total INTEGER NOT NULL DEFAULT 0,"
                + " compras TEXT NOT NULL DEFAULT '',"
                + " cambiada TEXT)");
        st.execute("CREATE TABLE IF NOT EXISTS orden_lineas ("
                + " id INTEGER PRIMARY KEY,"
                + " orden_id INTEGER NOT NULL REFERENCES ordenes_compra(id) ON DELETE CASCADE,"
                + " producto_id INTEGER NOT NULL REFERENCES productos(id),"
                + " nombre TEXT NOT NULL,"
                + " cantidad INTEGER NOT NULL,"
                + " base INTEGER NOT NULL,"
                + " impuesto INTEGER NOT NULL DEFAULT 0,"
                + " descuento_pct INTEGER NOT NULL DEFAULT 0,"
                + " costo_escrito INTEGER,"
                + " recibida INTEGER NOT NULL DEFAULT 0,"
                + " subtotal INTEGER NOT NULL)");
        st.execute("CREATE INDEX IF NOT EXISTS idx_lineas_orden ON orden_lineas(orden_id)");
    }

    private static String clave(String nombre) {
        return rutas.modelo.Geo.normalizar(nombre == null ? "" : nombre);
    }

    // ------------------------------------------------------------------
    //  Proveedores
    // ------------------------------------------------------------------

    /** Los proveedores por nombre (todos, o solo los activos). */
    public List<Proveedor> listarProveedores(boolean soloActivos) throws SQLException {
        List<Proveedor> l = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement("SELECT id, tipo_documento, documento, dv, nombre, regimenes,"
                + " telefono, correo, departamento, municipio, direccion, pagina_web, vendedor, celular_vendedor,"
                + " dia_visita, forma_pago, activo FROM proveedores" + (soloActivos ? " WHERE activo = 1" : "")
                + " ORDER BY clave_nombre")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    l.add(leerProveedor(rs));
                }
            }
        }
        return l;
    }

    public Proveedor buscarProveedor(long id) throws SQLException {
        for (Proveedor p : listarProveedores(false)) {
            if (p.id == id) {
                return p;
            }
        }
        return null;
    }

    private static Proveedor leerProveedor(ResultSet rs) throws SQLException {
        Proveedor p = new Proveedor();
        p.id = rs.getLong(1);
        p.tipoDocumento = rs.getString(2);
        p.documento = rs.getString(3);
        p.dv = rs.getString(4);
        p.nombre = rs.getString(5);
        p.regimenes = rs.getString(6);
        p.telefono = rs.getString(7);
        p.correo = rs.getString(8);
        p.departamento = rs.getString(9);
        p.municipio = rs.getString(10);
        p.direccion = rs.getString(11);
        p.paginaWeb = rs.getString(12);
        p.vendedor = rs.getString(13);
        p.celularVendedor = rs.getString(14);
        p.diaVisita = rs.getString(15);
        p.formaPago = rs.getString(16);
        p.activo = rs.getInt(17) == 1;
        return p;
    }

    /** Crea (id 0) o cambia el proveedor. Revisa el nombre, el documento repetido y el correo. */
    public void guardarProveedor(Proveedor p) throws SQLException {
        p.nombre = p.nombre == null ? "" : p.nombre.trim().replaceAll("\\s+", " ");
        if (p.nombre.length() < 2) {
            throw new IllegalArgumentException("Escriba el nombre del proveedor");
        }
        p.documento = p.documento == null ? "" : p.documento.replaceAll("[\\s.]", "");
        if (!p.documento.isEmpty() && !p.documento.matches("[0-9A-Za-z-]{3,20}")) {
            throw new IllegalArgumentException("El documento solo lleva números (y letras si es extranjero)");
        }
        if (p.tipoDocumento != null && p.tipoDocumento.startsWith("NIT -") && !p.documento.isEmpty()) {
            p.dv = Proveedor.digitoVerificacion(p.documento); // la DIAN lo calcula igual: no se escribe mal
        }
        if (p.correo != null && !p.correo.isBlank() && !CorreoSmtp.esCorreo(p.correo.trim())) {
            throw new IllegalArgumentException("Revise el correo (ej. ventas@proveedor.com)");
        }
        // mismo documento o mismo nombre = el mismo proveedor
        try (PreparedStatement ps = con.prepareStatement("SELECT nombre FROM proveedores WHERE id <> ?"
                + " AND ((documento <> '' AND documento = ?) OR clave_nombre = ?)")) {
            ps.setLong(1, p.id);
            ps.setString(2, p.documento);
            ps.setString(3, clave(p.nombre));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    throw new IllegalArgumentException("Ya existe el proveedor «" + rs.getString(1) + "»");
                }
            }
        }
        String[] v = {nn(p.tipoDocumento), p.documento, nn(p.dv), p.nombre, clave(p.nombre), nn(p.regimenes),
            nn(p.telefono).trim(), nn(p.correo).trim(), nn(p.departamento), nn(p.municipio).trim(), nn(p.direccion).trim(),
            nn(p.paginaWeb).trim(), nn(p.vendedor).trim(), nn(p.celularVendedor).trim(), nn(p.diaVisita), nn(p.formaPago)};
        if (p.id == 0) {
            try (PreparedStatement ps = con.prepareStatement("INSERT INTO proveedores(tipo_documento, documento, dv,"
                    + " nombre, clave_nombre, regimenes, telefono, correo, departamento, municipio, direccion, pagina_web,"
                    + " vendedor, celular_vendedor, dia_visita, forma_pago, activo, creado)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
                for (int i = 0; i < v.length; i++) {
                    ps.setString(i + 1, v[i]);
                }
                ps.setInt(17, p.activo ? 1 : 0);
                ps.setString(18, BaseTienda.ahora());
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    rs.next();
                    p.id = rs.getLong(1);
                }
            }
        } else {
            try (PreparedStatement ps = con.prepareStatement("UPDATE proveedores SET tipo_documento=?, documento=?, dv=?,"
                    + " nombre=?, clave_nombre=?, regimenes=?, telefono=?, correo=?, departamento=?, municipio=?,"
                    + " direccion=?, pagina_web=?, vendedor=?, celular_vendedor=?, dia_visita=?, forma_pago=?, activo=?"
                    + " WHERE id=?")) {
                for (int i = 0; i < v.length; i++) {
                    ps.setString(i + 1, v[i]);
                }
                ps.setInt(17, p.activo ? 1 : 0);
                ps.setLong(18, p.id);
                ps.executeUpdate();
            }
            // las ordenes que siguen abiertas muestran el nombre nuevo
            try (PreparedStatement ps = con.prepareStatement("UPDATE ordenes_compra SET proveedor = ?"
                    + " WHERE proveedor_id = ? AND estado IN ('BORRADOR','PENDIENTE','PARCIAL')")) {
                ps.setString(1, p.nombre);
                ps.setLong(2, p.id);
                ps.executeUpdate();
            }
        }
    }

    private static String nn(String s) {
        return s == null ? "" : s;
    }

    // ------------------------------------------------------------------
    //  Ordenes de compra
    // ------------------------------------------------------------------

    /** Lo que se ve en la lista de ordenes. */
    public static class OrdenResumen {
        public long id;
        public long numero;
        public LocalDateTime creada;
        public LocalDate fecha;
        public LocalDate fechaEstimada;
        public String proveedor = "";
        public String responsable = "";
        public long total;
        public OrdenCompra.Estado estado;

        public boolean atrasada(LocalDate hoy) {
            return estado.porRecibir() && fechaEstimada != null && fechaEstimada.isBefore(hoy);
        }
    }

    /** Por que fecha se filtra la lista. */
    public enum FiltroFecha {
        CREACION("Fecha de creación", "substr(o.creada, 1, 10)"), ORDEN("Fecha de la orden", "o.fecha"),
        ESTIMADA("Fecha estimada", "o.fecha_estimada");

        private final String texto;
        final String columna;

        FiltroFecha(String texto, String columna) {
            this.texto = texto;
            this.columna = columna;
        }

        @Override
        public String toString() {
            return texto;
        }
    }

    public long siguienteNumero() throws SQLException {
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(numero), 0) + 1 FROM ordenes_compra")) {
            return rs.getLong(1);
        }
    }

    /**
     * Las ordenes del filtro. estado null = todas. desde/hasta null = sin limite. Las pendientes
     * atrasadas salen primero; despues la mas nueva arriba.
     */
    public List<OrdenResumen> listarOrdenes(OrdenCompra.Estado estado, FiltroFecha porFecha, LocalDate desde,
            LocalDate hasta, String buscar) throws SQLException {
        if (desde != null && hasta != null && hasta.isBefore(desde)) {
            throw new IllegalArgumentException("La fecha final no puede ser antes de la inicial");
        }
        StringBuilder sql = new StringBuilder("SELECT o.id, o.numero, o.creada, o.fecha, o.fecha_estimada, o.proveedor,"
                + " COALESCE(u.nombre, ''), o.total, o.estado FROM ordenes_compra o"
                + " LEFT JOIN usuarios u ON u.id = o.usuario_id WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (estado != null) {
            sql.append(" AND o.estado = ?");
            args.add(estado.name());
        }
        FiltroFecha f = porFecha == null ? FiltroFecha.CREACION : porFecha;
        if (desde != null) {
            sql.append(" AND ").append(f.columna).append(" >= ?");
            args.add(desde.toString());
        }
        if (hasta != null) {
            sql.append(" AND ").append(f.columna).append(" <= ?");
            args.add(hasta.toString());
        }
        String b = buscar == null ? "" : buscar.trim();
        if (!b.isEmpty()) {
            String num = b.replaceAll("^0+", "");
            sql.append(" AND (o.proveedor LIKE ? ESCAPE '\\' OR CAST(o.numero AS TEXT) = ?)");
            args.add("%" + b.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
            args.add(num.isEmpty() ? "0" : num);
        }
        sql.append(" ORDER BY o.numero DESC");
        List<OrdenResumen> l = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql.toString())) {
            for (int i = 0; i < args.size(); i++) {
                ps.setObject(i + 1, args.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    OrdenResumen r = new OrdenResumen();
                    r.id = rs.getLong(1);
                    r.numero = rs.getLong(2);
                    r.creada = LocalDateTime.parse(rs.getString(3));
                    r.fecha = LocalDate.parse(rs.getString(4));
                    r.fechaEstimada = rs.getString(5) == null ? null : LocalDate.parse(rs.getString(5));
                    r.proveedor = rs.getString(6);
                    r.responsable = rs.getString(7);
                    r.total = rs.getLong(8);
                    r.estado = OrdenCompra.Estado.valueOf(rs.getString(9));
                    l.add(r);
                }
            }
        }
        LocalDate hoy = LocalDate.now();
        l.sort((a, c) -> Boolean.compare(c.atrasada(hoy), a.atrasada(hoy))); // estable: el resto queda igual
        return l;
    }

    /**
     * Guarda la orden (nueva o cambiada) con el estado que trae. Devuelve el numero.
     * Solo se puede cambiar mientras este en borrador o pendiente.
     */
    public long guardarOrden(OrdenCompra o, long usuarioId) throws SQLException {
        if (o.getLineas().isEmpty() && o.getEstado() != OrdenCompra.Estado.BORRADOR) {
            throw new IllegalArgumentException("Agregue al menos un producto a la orden");
        }
        boolean antes = con.getAutoCommit();
        boolean eraNueva = o.getId() == 0;
        con.setAutoCommit(false);
        try {
            if (o.getId() != 0) {
                try (PreparedStatement ps = con.prepareStatement("SELECT estado FROM ordenes_compra WHERE id = ?")) {
                    ps.setLong(1, o.getId());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            throw new IllegalArgumentException("Esa orden ya no existe");
                        }
                        if (!OrdenCompra.Estado.valueOf(rs.getString(1)).editable()) {
                            throw new IllegalStateException("La orden ya no se puede cambiar (está "
                                    + OrdenCompra.Estado.valueOf(rs.getString(1)).toString().toLowerCase() + ")");
                        }
                    }
                }
            }
            if (o.getId() == 0) {
                long numero = siguienteNumero();
                try (PreparedStatement ps = con.prepareStatement("INSERT INTO ordenes_compra(numero, estado, usuario_id,"
                        + " creada, fecha) VALUES(?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
                    ps.setLong(1, numero);
                    ps.setString(2, o.getEstado().name());
                    ps.setLong(3, usuarioId);
                    ps.setString(4, o.getCreada().withNano(0).toString());
                    ps.setString(5, (o.getFecha() == null ? LocalDate.now() : o.getFecha()).toString());
                    ps.executeUpdate();
                    try (ResultSet rs = ps.getGeneratedKeys()) {
                        rs.next();
                        o.setId(rs.getLong(1));
                    }
                }
                o.setNumero(numero);
            }
            try (PreparedStatement ps = con.prepareStatement("UPDATE ordenes_compra SET estado=?, proveedor_id=?,"
                    + " proveedor=?, bodega_id=?, fecha=?, fecha_estimada=?, nota=?, descuento_general=?, con_retencion=?,"
                    + " retencion_pct=?, bruto=?, descuentos=?, impuestos=?, retencion=?, total=?, cambiada=? WHERE id=?")) {
                int i = 1;
                ps.setString(i++, o.getEstado().name());
                ps.setObject(i++, o.getProveedorId());
                ps.setString(i++, o.getProveedor());
                ps.setLong(i++, o.getBodegaId());
                ps.setString(i++, (o.getFecha() == null ? LocalDate.now() : o.getFecha()).toString());
                ps.setString(i++, o.getFechaEstimada() == null ? null : o.getFechaEstimada().toString());
                ps.setString(i++, o.getObservaciones());
                ps.setLong(i++, o.getDescuentoGeneral());
                ps.setInt(i++, o.isConRetencion() ? 1 : 0);
                ps.setLong(i++, o.getRetencionPct());
                ps.setLong(i++, o.getBruto());
                ps.setLong(i++, o.getDescuentos());
                ps.setLong(i++, o.getImpuestos());
                ps.setLong(i++, o.getRetencion());
                ps.setLong(i++, o.getTotalOrden());
                ps.setString(i++, BaseTienda.ahora());
                ps.setLong(i, o.getId());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = con.prepareStatement("DELETE FROM orden_lineas WHERE orden_id = ?")) {
                ps.setLong(1, o.getId());
                ps.executeUpdate();
            }
            insertarLineas(o);
            con.commit();
            return o.getNumero();
        } catch (SQLException | RuntimeException e) {
            con.rollback();
            if (eraNueva) { // no quedo guardada: que el proximo intento la cree de nuevo
                o.setId(0);
                o.setNumero(0);
            }
            throw e;
        } finally {
            con.setAutoCommit(antes);
        }
    }

    private void insertarLineas(OrdenCompra o) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("INSERT INTO orden_lineas(orden_id, producto_id, nombre,"
                + " cantidad, base, impuesto, descuento_pct, costo_escrito, recibida, subtotal) VALUES(?,?,?,?,?,?,?,?,?,?)")) {
            List<Compra.Linea> ls = o.getLineas();
            for (int k = ls.size() - 1; k >= 0; k--) { // al revés: al cargar (id DESC) quedan en el mismo orden
                Compra.Linea l = ls.get(k);
                ps.setLong(1, o.getId());
                ps.setLong(2, l.getProducto().getId());
                ps.setString(3, l.getProducto().getNombre());
                ps.setLong(4, l.getCantidad());
                ps.setLong(5, l.getBase());
                ps.setLong(6, l.getImpuesto());
                ps.setLong(7, l.getDescuento());
                ps.setObject(8, l.getCostoEscrito());
                ps.setLong(9, l.getRecibida());
                ps.setLong(10, l.getSubtotal());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /** La orden completa para editarla, verla, mandarla o recibirla. avisos: productos que ya no existen. */
    public OrdenCompra cargarOrden(long id, Catalogo cat, List<String> avisos) throws SQLException {
        OrdenCompra o = new OrdenCompra();
        try (PreparedStatement ps = con.prepareStatement("SELECT o.numero, o.estado, o.proveedor_id, o.proveedor,"
                + " o.bodega_id, o.creada, o.fecha, o.fecha_estimada, o.nota, o.descuento_general, o.con_retencion,"
                + " o.retencion_pct, COALESCE(u.nombre, ''), o.compras FROM ordenes_compra o"
                + " LEFT JOIN usuarios u ON u.id = o.usuario_id WHERE o.id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("Esa orden ya no existe");
                }
                o.setId(id);
                o.setNumero(rs.getLong(1));
                o.setEstado(OrdenCompra.Estado.valueOf(rs.getString(2)));
                o.setProveedorId(rs.getObject(3) == null ? null : rs.getLong(3));
                o.setProveedor(rs.getString(4));
                o.setBodegaId(rs.getLong(5));
                o.setCreada(LocalDateTime.parse(rs.getString(6)));
                o.setFecha(LocalDate.parse(rs.getString(7)));
                o.setFechaEstimada(rs.getString(8) == null ? null : LocalDate.parse(rs.getString(8)));
                o.setObservaciones(rs.getString(9));
                o.setDescuentoGeneral(rs.getLong(10));
                o.setConRetencion(rs.getInt(11) == 1);
                o.setRetencionPct(rs.getLong(12));
                o.setResponsable(rs.getString(13));
                o.setCompras(rs.getString(14));
            }
        }
        try (PreparedStatement ps = con.prepareStatement("SELECT producto_id, nombre, cantidad, base, impuesto,"
                + " descuento_pct, costo_escrito, recibida FROM orden_lineas WHERE orden_id = ? ORDER BY id DESC")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long pid = rs.getLong(1);
                    Producto p = cat == null ? null : cat.buscarPorId(pid);
                    if (p == null) {
                        p = bd.buscarProductoPorId(pid);
                    }
                    if (p == null) {
                        if (avisos != null) {
                            avisos.add("«" + rs.getString(2) + "» ya no existe y se quitó de la orden");
                        }
                        continue;
                    }
                    Compra.Linea l = new Compra.Linea(p, rs.getLong(3), rs.getLong(4), rs.getLong(5));
                    l.setDescuento(rs.getLong(6));
                    if (rs.getObject(7) != null) {
                        l.setCosto(rs.getLong(7));
                    }
                    l.setRecibida(rs.getLong(8));
                    o.getLineas().add(l);
                }
            }
        }
        return o;
    }

    /** Cancela una orden que todavia no ha llegado (o la que llego en parte: no llega mas). */
    public void cancelarOrden(long id) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("UPDATE ordenes_compra SET estado = 'CANCELADA', cambiada = ?"
                + " WHERE id = ? AND estado IN ('BORRADOR','PENDIENTE','PARCIAL')")) {
            ps.setString(1, BaseTienda.ahora());
            ps.setLong(2, id);
            if (ps.executeUpdate() == 0) {
                throw new IllegalStateException("La orden ya estaba recibida o cancelada");
            }
        }
    }

    /**
     * Llego mercancia de la orden con la compra numeroCompra: se suma lo recibido de cada producto
     * (productoId -> milesimas) y la orden queda Recibida (todo llego) o Recibida en parte.
     */
    public OrdenCompra.Estado registrarRecepcion(long ordenId, Map<Long, Long> recibido, long numeroCompra)
            throws SQLException {
        boolean antes = con.getAutoCommit();
        con.setAutoCommit(false);
        try {
            String compras;
            try (PreparedStatement ps = con.prepareStatement("SELECT estado, compras FROM ordenes_compra WHERE id = ?")) {
                ps.setLong(1, ordenId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalArgumentException("Esa orden ya no existe");
                    }
                    if (!OrdenCompra.Estado.valueOf(rs.getString(1)).porRecibir()) {
                        throw new IllegalStateException("La orden no está pendiente");
                    }
                    compras = rs.getString(2);
                }
            }
            for (Map.Entry<Long, Long> e : recibido.entrySet()) {
                long falta = e.getValue();
                // si el producto esta en varias lineas, se reparte en orden
                try (PreparedStatement sel = con.prepareStatement("SELECT id, cantidad, recibida FROM orden_lineas"
                        + " WHERE orden_id = ? AND producto_id = ? ORDER BY id DESC");
                     PreparedStatement up = con.prepareStatement("UPDATE orden_lineas SET recibida = ? WHERE id = ?")) {
                    sel.setLong(1, ordenId);
                    sel.setLong(2, e.getKey());
                    List<long[]> filas = new ArrayList<>();
                    try (ResultSet rs = sel.executeQuery()) {
                        while (rs.next()) {
                            filas.add(new long[]{rs.getLong(1), rs.getLong(2), rs.getLong(3)});
                        }
                    }
                    for (int i = 0; i < filas.size() && falta > 0; i++) {
                        long[] f = filas.get(i);
                        boolean ultima = i == filas.size() - 1;
                        long cabe = ultima ? falta : Math.min(falta, Math.max(0, f[1] - f[2])); // lo de mas va a la ultima
                        up.setLong(1, f[2] + cabe);
                        up.setLong(2, f[0]);
                        up.executeUpdate();
                        falta -= cabe;
                    }
                }
            }
            boolean todo;
            try (PreparedStatement ps = con.prepareStatement("SELECT COUNT(*) FROM orden_lineas WHERE orden_id = ?"
                    + " AND recibida < cantidad")) {
                ps.setLong(1, ordenId);
                try (ResultSet rs = ps.executeQuery()) {
                    todo = rs.getInt(1) == 0;
                }
            }
            OrdenCompra.Estado nuevo = todo ? OrdenCompra.Estado.RECIBIDA : OrdenCompra.Estado.PARCIAL;
            try (PreparedStatement ps = con.prepareStatement("UPDATE ordenes_compra SET estado = ?, compras = ?,"
                    + " cambiada = ? WHERE id = ?")) {
                ps.setString(1, nuevo.name());
                ps.setString(2, compras.isEmpty() ? String.valueOf(numeroCompra) : compras + ", " + numeroCompra);
                ps.setString(3, BaseTienda.ahora());
                ps.setLong(4, ordenId);
                ps.executeUpdate();
            }
            con.commit();
            return nuevo;
        } catch (SQLException | RuntimeException e) {
            con.rollback();
            throw e;
        } finally {
            con.setAutoCommit(antes);
        }
    }

    /** Productos activos que manejan inventario y estan en su minimo o por debajo (para pedirlos). */
    public static List<Producto> porAcabarse(Catalogo cat) {
        List<Producto> l = new ArrayList<>();
        for (Producto p : cat.porCategoria(null)) {
            if (p.isActivo() && p.isManejaInventario() && p.getMinimo() > 0 && p.getStock() <= p.getMinimo()) {
                l.add(p);
            }
        }
        return l;
    }
}
