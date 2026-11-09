package rutas.vista;

import rutas.logica.AjustesRecibo;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import rutas.estructuras.ArbolAVL;
import java.sql.SQLException;
import rutas.logica.BaseDatos;
import rutas.logica.Ubicaciones;
import rutas.logica.BuscadorLugares;
import rutas.logica.CargadorMapa;
import rutas.logica.Cuadre;
import rutas.logica.Dijkstra;
import rutas.logica.Tarifa;
import rutas.logica.Criterio;
import rutas.logica.Geocodificador;
import rutas.logica.Mapa;
import rutas.logica.ModeloTrafico;
import rutas.logica.PlanificadorRutas;
import rutas.modelo.Ciudad;
import rutas.modelo.Cliente;
import rutas.modelo.Geo;
import rutas.modelo.Parada;
import rutas.modelo.Ruta;
import rutas.modelo.Correccion;
import rutas.modelo.Salida;
import rutas.logica.NubeRepartidor;
import rutas.logica.RutaNube;

/**
 * Estado compartido del programa: el mapa, las estructuras de busqueda,
 * la base, las entregas pendientes y la ruta calculada.
 * Los paneles se registran como oyentes para refrescarse cuando algo cambia.
 */
public class Aplicacion {

    private final File carpetaDatos;
    private final File carpetaUsuario;
    private final Ciudad ciudad;
    private final Mapa mapa;
    private final ModeloTrafico trafico;
    private final BuscadorLugares buscador;
    private final Geocodificador geo;
    private final PlanificadorRutas planificador;
    private final BaseDatos bd;
    private final ArbolAVL<String, Cliente> clientes;
    private final List<Ruta> historial;
    private final List<Parada> entregas = new ArrayList<>();
    private final List<Salida> salidas = new ArrayList<>(); // las de hoy y las que siguen abiertas
    private String firmaPendientes = "";
    private final List<Runnable> oyentes = new ArrayList<>();
    private Parada base;
    private List<Ruta> rutas = new ArrayList<>();
    private int rutaActiva;
    private boolean[] alcanzable;
    private PanelMapa panelMapa;

    /**
     * @param carpetaDatos mapas y configuracion (datos/)
     * @param ciudad municipio a cargar
     * @param usuario carpeta donde se guarda lo del usuario (una subcarpeta por ciudad)
     */
    public Aplicacion(File carpetaDatos, Ciudad ciudad, File usuario) throws IOException {
        this.carpetaDatos = carpetaDatos;
        this.ciudad = ciudad;
        this.carpetaUsuario = new File(usuario, ciudad.getClave());
        carpetaUsuario.mkdirs();
        migrarDatosViejos();
        File carpetaMapa = ciudad.carpetaMapa(carpetaDatos, usuario);
        mapa = CargadorMapa.cargar(carpetaMapa);
        trafico = new ModeloTrafico(mapa, new File(carpetaDatos, "trafico.txt"));
        if (!ciudad.getClave().equals("riohacha")) { // el centro de trafico.txt es el de Riohacha
            trafico.setCentro(ciudad.getLat(), ciudad.getLon(), Math.min(900, ciudad.getRadioKm() * 250));
        }
        buscador = new BuscadorLugares(mapa.getLugares());
        geo = new Geocodificador(mapa, buscador);
        geo.cargarAlias(new File(new File(carpetaDatos, ciudad.getClave()), "alias.txt"));
        planificador = new PlanificadorRutas(mapa, trafico);
        bd = new BaseDatos(new File(carpetaUsuario, "domiruta.db"));
        try {
            bd.importarTextos(carpetaUsuario, mapa); // datos de la version anterior (archivos .txt)
            bd.cargarIncidentes(mapa);
            bd.cargarDirecciones(geo);
            clientes = bd.cargarClientes();
            historial = bd.cargarHistorial(mapa);
            entregas.addAll(bd.cargarPendientes()); // las que quedaron sin salir (si se cerró el programa)
            firmaPendientes = firma(entregas);
            salidas.addAll(bd.cargarSalidas(java.time.LocalDate.now().atStartOfDay()));
        } catch (SQLException e) {
            bd.close();
            throw new IOException("Error leyendo la base de datos: " + e.getMessage(), e);
        }
        base = cargarBase();
        actualizarAlcance();
    }

    /** Version anterior guardaba todo en datos/: se copia una sola vez a la carpeta del usuario. */
    private void migrarDatosViejos() {
        if (!ciudad.getClave().equals("riohacha")) {
            return;
        }
        for (String n : new String[]{"base.txt", "clientes.txt", "novedades.txt", "historial_rutas.txt",
            "direcciones_corregidas.txt"}) {
            File viejo = new File(carpetaDatos, n), nuevo = new File(carpetaUsuario, n);
            if (viejo.exists() && !nuevo.exists()) {
                try {
                    Files.copy(viejo.toPath(), nuevo.toPath());
                } catch (IOException e) {
                    // se ignora: el usuario empieza en limpio
                }
            }
        }
    }

    // ---------------- base ----------------

    private Parada cargarBase() {
        try {
            Parada p = bd.cargarBase();
            if (p != null) {
                geo.asignarNodo(p, Geo.x(p.getLon()), Geo.y(p.getLat()), null);
                p.setPrecision("Punto de salida guardado");
                return p;
            }
        } catch (SQLException | RuntimeException e) {
            // se usa la base por defecto
        }
        // base por defecto: el centro de la ciudad (en Riohacha, el Parque Padilla)
        double lat = ciudad.getLat(), lon = ciudad.getLon();
        String nombre = "Centro de " + ciudad.getNombre();
        if (ciudad.getClave().equals("riohacha")) {
            lat = 11.5513888;
            lon = -72.9096513;
            nombre = "Parque Almirante Padilla (centro)";
        }
        Parada b = new Parada("Base", nombre, lat, lon);
        geo.asignarNodo(b, Geo.x(lon), Geo.y(lat), null);
        b.setPrecision("Ponga aquí la dirección de su negocio");
        return b;
    }

    public void setBase(Parada nueva) {
        invalidarDistancias();
        base = nueva;
        actualizarAlcance();
        // las entregas se vuelven a pegar a nodos alcanzables desde la nueva base
        for (Parada p : entregas) {
            if (!alcanzable[p.getNodo()]) {
                geo.asignarNodo(p, Geo.x(p.getLon()), Geo.y(p.getLat()), null);
            }
        }
        try {
            bd.guardarBase(nueva);
        } catch (SQLException e) {
            Ubicaciones.registrarError(e); // no es grave: solo no queda guardada para la proxima vez
        }
        limpiarRuta();
        avisar();
    }

    /**
     * BFS desde la base y BFS al reves: nodos a los que se puede ir y desde los que se
     * puede volver respetando el sentido de las calles. Las entregas se pegan solo a esos.
     */
    private void actualizarAlcance() {
        alcanzable = mapa.getGrafo().idaYVuelta(base.getNodo());
        geo.setAlcanzable(alcanzable);
    }

    // ---------------- entregas ----------------

    private Object ventaEnCurso; // mientras la caja arma una venta a domicilio, lo que se agrega es de ella

    /** La venta a domicilio que arma la caja (null si no hay). */
    Object getVentaEnCurso() {
        return ventaEnCurso;
    }

    /** La caja empieza (marca) o termina (null) una venta a domicilio. */
    void setVentaEnCurso(Object marca) {
        ventaEnCurso = marca;
    }

    private void marcar(Parada p) {
        if (ventaEnCurso != null && p.getVenta() == null) {
            p.setVenta(ventaEnCurso);
        }
    }

    public void agregarEntrega(Parada p) {
        marcar(p);
        entregas.add(p);
        limpiarRuta();
        avisar();
    }

    public void reemplazarEntrega(int i, Parada p) {
        if (p.getVenta() == null) { // al editarla sigue siendo de la misma venta (o de ninguna)
            p.setVenta(entregas.get(i).getVenta());
        }
        entregas.set(i, p);
        limpiarRuta();
        avisar();
    }

    public void quitarEntrega(int i) {
        entregas.remove(i);
        limpiarRuta();
        avisar();
    }

    public void quitarTodas() {
        entregas.clear();
        limpiarRuta();
        avisar();
    }

    public void cargarEntregas(Parada nuevaBase, List<Parada> nuevas) {
        entregas.clear();
        entregas.addAll(nuevas);
        if (nuevaBase != null) {
            setBase(nuevaBase);
        } else {
            limpiarRuta();
            avisar();
        }
    }

    public Ruta calcular(Criterio criterio, LocalTime salida, boolean volver) {
        return calcular(criterio, salida, volver, 1).get(0);
    }

    /** Calcula una ruta por repartidor. */
    public List<Ruta> calcular(Criterio criterio, LocalTime salida, boolean volver, int repartidores) {
        rutas = planificador.planificarFlota(base, new ArrayList<>(entregas), repartidores, volver, criterio, salida);
        rutaActiva = 0;
        ultimoCriterio = criterio;
        rutaGuardada = false;
        ultimaSalida = salida;
        if (panelMapa != null) {
            panelMapa.setRutas(rutas, 0);
            animarRutas(); // la animacion lleva la camara hasta la ruta (ya no salta de una)
        }
        avisar();
        return rutas;
    }

    // ---------------- animacion ----------------

    private Criterio ultimoCriterio = Criterio.MAS_RAPIDA;
    private LocalTime ultimaSalida;
    private boolean rutaGuardada;

    // ---------------- la plata del domicilio ----------------

    private Dijkstra distanciasBase; // distancia real por las calles desde la base, para la tarifa
    private int distanciasNodo = -1;
    private boolean distanciasLluvia;

    /** Se calcula una sola vez y se repite solo si cambia la base o la lluvia. */
    private Dijkstra distancias() {
        if (base == null || base.getNodo() < 0) {
            return null;
        }
        if (distanciasBase == null || distanciasNodo != base.getNodo() || distanciasLluvia != trafico.isLluvia()) {
            distanciasBase = new Dijkstra(mapa.getGrafo(), base.getNodo(), trafico, Criterio.MAS_CORTA, LocalTime.now());
            distanciasNodo = base.getNodo();
            distanciasLluvia = trafico.isLluvia();
        }
        return distanciasBase;
    }

    private Tarifa tarifa; // se lee de la base de datos una sola vez

    public Tarifa getTarifa() {
        if (tarifa == null) {
            tarifa = new Tarifa((int) leerNumero("tarifa.base", 3000), leerNumero("tarifa.km_incluidos", 2),
                    (int) leerNumero("tarifa.por_km", 700));
        }
        return tarifa;
    }

    /** Hay que volver a medir las distancias desde la base (cambio la base, la lluvia o una via se cerro). */
    private void invalidarDistancias() {
        distanciasBase = null;
    }

    public void setTarifa(Tarifa t) {
        tarifa = t;
        try {
            bd.guardarConfig("tarifa.base", String.valueOf(t.getBase()));
            bd.guardarConfig("tarifa.km_incluidos", String.valueOf(t.getKmIncluidos()));
            bd.guardarConfig("tarifa.por_km", String.valueOf(t.getPorKm()));
        } catch (SQLException e) {
            Ubicaciones.registrarError(e);
        }
    }

    private double leerNumero(String clave, double porDefecto) {
        try {
            String v = bd.leerConfig(clave);
            return v == null ? porDefecto : Double.parseDouble(v);
        } catch (SQLException | NumberFormatException e) {
            return porDefecto;
        }
    }

    /** Valor del domicilio: segun los metros por las calles desde la base hasta la entrega. */
    public int domicilioDe(Parada p) {
        Dijkstra d = distancias();
        if (d == null || p.getNodo() < 0 || !d.hayCamino(p.getNodo())) {
            return 0;
        }
        return getTarifa().calcular(d.getMetros(p.getNodo()));
    }

    /** Lo que el repartidor le cobra al cliente en la puerta (0 si ya pago). */
    public int cobrarEnPuerta(Parada p) {
        return p.isPagado() ? 0 : p.getValorPedido() + domicilioDe(p);
    }

    public Cuadre cuadreDe(Ruta r) {
        Cuadre c = new Cuadre();
        for (Parada p : r.getParadas()) {
            c.agregar(p.getValorPedido(), domicilioDe(p), p.isPagado());
        }
        return c;
    }

    /** Datos del negocio y de la impresora (Configuración). Si no se pueden leer, los de siempre. */
    public AjustesRecibo getAjustesRecibo() {
        if (tienda != null) {
            try {
                return AjustesRecibo.leer(tienda);
            } catch (SQLException e) {
                Ubicaciones.registrarError(e);
            }
        }
        return new AjustesRecibo();
    }

    /** Nombre del negocio para el mensaje al cliente ("" si no se ha dicho). Manda el de Configuración. */
    public String getNegocio() {
        String deConfig = getAjustesRecibo().nombre;
        if (!deConfig.isBlank()) {
            return deConfig;
        }
        if (bd == null) {
            return "";
        }
        try {
            String n = bd.leerConfig("nombre_negocio");
            return n == null ? "" : n;
        } catch (SQLException e) {
            return "";
        }
    }

    /** Con lluvia las destapadas casi no se usan; se borra la ruta para volver a calcularla. */
    public void setLluvia(boolean lluvia) {
        trafico.setLluvia(lluvia);
        if (panelMapa != null) {
            panelMapa.setLluvia(lluvia);
        }
        limpiarRuta();
        avisar();
    }

    public void setNegocio(String nombre) {
        try {
            bd.guardarConfig("nombre_negocio", nombre.trim());
        } catch (SQLException e) {
            Ubicaciones.registrarError(e);
        }
    }

    /** Busca un cliente guardado por nombre en el AVL (null si no esta). */
    public Cliente buscarCliente(String nombre) {
        return nombre == null || nombre.isBlank() ? null : clientes.buscar(Geo.normalizar(nombre));
    }

    /**
     * Hora estimada de llegada a una entrega si las motos salieran ahora con las entregas actuales.
     * Sirve para prometerle una hora al cliente apenas hace el pedido.
     */
    public LocalTime estimarLlegada(Parada entrega, Criterio criterio, LocalTime salida, boolean volver, int motos) {
        if (entregas.isEmpty()) {
            return null;
        }
        List<Ruta> plan = planificador.planificarFlota(base, new ArrayList<>(entregas), motos, volver, criterio, salida);
        for (Ruta r : plan) {
            int i = r.getParadas().indexOf(entrega);
            if (i >= 0) {
                return r.getLlegadas().get(i);
            }
        }
        return null;
    }

    /**
     * Muestra las rutas llenandose desde la base. Antes de trazar,
     * se ve en que orden Dijkstra fue revisando las esquinas hasta la primera entrega.
     */
    private void animarRutas() {
        if (panelMapa == null || rutas.isEmpty() || base == null) {
            return;
        }
        panelMapa.setRutas(rutas, rutaActiva);
        int[] orden = null;
        Ruta primera = rutas.get(rutaActiva < rutas.size() ? rutaActiva : 0);
        if (!primera.getParadas().isEmpty()) {
            Dijkstra d = new Dijkstra(mapa.getGrafo(), base.getNodo(), trafico, ultimoCriterio,
                    ultimaSalida == null ? LocalTime.now() : ultimaSalida);
            orden = d.ordenDeVisitaHasta(primera.getParadas().get(0).getNodo());
        }
        panelMapa.animarRutas(orden, ultimoCriterio == Criterio.MAS_CORTA
                ? "Buscando el camino más corto…" : "Buscando el camino más rápido…");
    }

    public void setRutaActiva(int i) {
        rutaActiva = i;
        if (panelMapa != null) {
            panelMapa.setRutas(rutas, i);
        }
    }

    public void limpiarRuta() {
        rutas = new ArrayList<>();
        if (panelMapa != null) {
            panelMapa.setRutas(null, 0);
        }
    }

    public void mostrarRuta(Ruta r) {
        rutas = new ArrayList<>(List.of(r));
        rutaGuardada = true; // viene del historial
        rutaActiva = 0;
        if (panelMapa != null) {
            panelMapa.setRutas(rutas, 0);
            panelMapa.encuadrarRuta(r);
        }
    }

    // ---------------- correccion de ubicaciones ----------------

    /** Que se esta corrigiendo: texto de la direccion, cliente y posicion en la lista (-1 = nueva). */
    private String correccionTexto;
    private String correccionCliente;
    private int correccionIndice = -1;

    public void empezarCorreccion(String texto, String cliente, int indice) {
        correccionTexto = texto;
        correccionCliente = cliente;
        correccionIndice = indice;
        if (panelMapa != null) {
            panelMapa.setTextoCorreccion(texto);
            panelMapa.setModo(PanelMapa.Modo.CORREGIR_UBICACION);
        }
    }

    /** El usuario hizo clic donde queda la direccion: se guarda y se agrega o reemplaza la entrega. */
    public void terminarCorreccion(double lat, double lon) throws IOException {
        if (correccionTexto == null) {
            return;
        }
        geo.aprender(correccionTexto, lat, lon);
        guardar(() -> bd.guardarDireccion(correccionTexto, lat, lon));
        Parada p = new Parada(correccionCliente == null || correccionCliente.isEmpty() ? correccionTexto : correccionCliente,
                correccionTexto, lat, lon);
        geo.asignarNodo(p, Geo.x(lon), Geo.y(lat), null);
        p.setPrecision("Ubicación corregida a mano");
        if (correccionIndice >= 0 && correccionIndice < entregas.size()) {
            p.copiarDatosDe(entregas.get(correccionIndice));
            entregas.set(correccionIndice, p);
        } else {
            marcar(p);
            entregas.add(p);
        }
        correccionTexto = null;
        limpiarRuta();
        avisar();
    }

    // ---------------- salidas del repartidor ----------------

    /** Las pendientes se guardan solo si cambiaron (avisar se llama seguido). */
    private void guardarPendientes() {
        String f = firma(entregas);
        if (f.equals(firmaPendientes)) {
            return;
        }
        try {
            bd.guardarPendientes(entregas);
            firmaPendientes = f;
        } catch (SQLException e) {
            Ubicaciones.registrarError(e);
        }
    }

    private static String firma(List<Parada> l) {
        StringBuilder b = new StringBuilder();
        for (Parada p : l) {
            b.append(p.getNombre()).append('|').append(p.getDireccion()).append('|').append(p.getLat()).append('|')
                    .append(p.getLon()).append('|').append(p.getTelefono()).append('|').append(p.getValorPedido())
                    .append('|').append(p.isPagado()).append('|').append(p.getPagaCon()).append('|')
                    .append(p.isUrgente()).append('|').append(p.getVentaNumero()).append('|').append(p.getProductos())
                    .append('\n');
        }
        return b.toString();
    }

    public List<Salida> getSalidas() {
        return salidas;
    }

    /** Salidas cerradas entre dos fechas (para el historial). */
    public List<Salida> historialSalidas(java.time.LocalDate desde, java.time.LocalDate hasta) throws SQLException {
        return bd.historialSalidas(desde, hasta);
    }

    public String fotoEntrega(long idEntrega) throws SQLException {
        return bd.fotoEntrega(idEntrega);
    }

    public List<Salida> salidasAbiertas() {
        List<Salida> l = new ArrayList<>();
        for (Salida s : salidas) {
            if (s.abierta()) {
                l.add(s);
            }
        }
        return l;
    }

    /**
     * El repartidor sale con las entregas de la ruta: dejan de estar pendientes y pasan a una salida
     * En camino. La ruta se guarda en el historial. Devuelve la salida (todavía sin subir a internet).
     */
    public Salida despachar(Ruta r, String repartidor) throws IOException {
        Salida s = new Salida();
        s.repartidor = repartidor == null ? "" : repartidor;
        for (Parada p : r.getParadas()) {
            Salida.Entrega e = new Salida.Entrega(p, cobrarEnPuerta(p));
            e.domicilio = domicilioDe(p);
            s.entregas.add(e);
        }
        guardar(() -> bd.guardarSalida(s)); // primero se guarda: si falla, las entregas siguen pendientes
        for (Parada p : r.getParadas()) {
            quitarDePendientes(p);
        }
        salidas.add(s);
        try {
            guardarRutaEnHistorial();
        } catch (IOException e) {
            Ubicaciones.registrarError(e); // el historial no es lo importante aqui
        }
        // la ruta enviada ya no es de las pendientes; las otras motos (si habia) siguen en el mapa
        List<Ruta> otras = new ArrayList<>(rutas);
        otras.remove(r);
        rutas = otras;
        rutaActiva = 0;
        if (panelMapa != null) {
            panelMapa.setRutas(rutas.isEmpty() ? null : rutas, 0);
        }
        avisar();
        return s;
    }

    /** Una entrega de un cliente que todavía va en camino en otra salida abierta. */
    public static final class EnOtraSalida {
        public final Salida salida;
        public final Salida.Entrega entrega;

        EnOtraSalida(Salida salida, Salida.Entrega entrega) {
            this.salida = salida;
            this.entrega = entrega;
        }
    }

    private static String limpio(String t) {
        return t == null ? "" : t.trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
    }

    /** Mismo cliente: mismo nombre y misma dirección (sin importar mayúsculas ni espacios de más). */
    public static boolean mismoCliente(Parada a, Parada b) {
        return !limpio(a.getNombre()).isEmpty() && limpio(a.getNombre()).equals(limpio(b.getNombre()))
                && limpio(a.getDireccion()).equals(limpio(b.getDireccion()));
    }

    /** Los clientes de estas paradas que ya van en camino en otra salida abierta (para no mandarlos dos veces). */
    public List<EnOtraSalida> yaVanEnOtraSalida(List<Parada> paradas) {
        List<EnOtraSalida> l = new ArrayList<>();
        for (Salida s : salidas) {
            if (!s.abierta() || s.subiendo) {
                continue;
            }
            for (Salida.Entrega e : s.entregas) {
                if (!e.enCamino()) {
                    continue;
                }
                for (Parada p : paradas) {
                    if (mismoCliente(p, e.parada)) {
                        l.add(new EnOtraSalida(s, e));
                        break;
                    }
                }
            }
        }
        return l;
    }

    /**
     * El cliente se pasa a otro repartidor: sale de la salida vieja (en el computador y en internet) y a ese
     * repartidor le llega un mensaje. Si la salida vieja queda sin entregas, se cierra (el enlace deja de servir).
     */
    public void pasarAOtroRepartidor(List<EnOtraSalida> l, String nuevo) throws IOException {
        List<Object[]> nube = new ArrayList<>(); // {uuid, idNube, nombre, quedóVacía}
        for (EnOtraSalida x : l) {
            Salida s = x.salida;
            if (!s.entregas.remove(x.entrega)) {
                continue;
            }
            boolean vacia = s.entregas.isEmpty();
            if (vacia) {
                s.estado = Salida.Estado.CERRADA;
                s.cerrada = java.time.LocalDateTime.now();
            } else {
                s.revisarEstado();
            }
            guardar(() -> bd.guardarSalida(s));
            if (!vacia) {
                cerrarSiTermino(s); // lo que quedó ya estaba entregado
            }
            if (s.enLinea()) {
                nube.add(new Object[]{s.uuidNube, x.entrega.idNube, x.entrega.parada.getNombre(), vacia});
            }
        }
        NubeRepartidor n = getNube();
        if (n != null && !nube.isEmpty()) {
            String quien = nuevo == null || nuevo.isBlank() ? "otro repartidor" : nuevo.trim();
            Thread t = new Thread(() -> {
                for (Object[] o : nube) {
                    try {
                        if ((long) o[1] > 0 && !(boolean) o[3]) {
                            n.quitarEntrega((long) o[1]); // la vacía la cierra y la borra el seguimiento
                        }
                        n.escribir((String) o[0], "La tienda le pasó el pedido de " + o[2] + " a " + quien
                                + ". Ya no lo lleves.");
                    } catch (IOException ex) {
                        Ubicaciones.registrarError(ex);
                    }
                }
            }, "pasar-cliente");
            t.setDaemon(true);
            t.start();
        }
        avisar();
    }

    private void quitarDePendientes(Parada p) {
        for (int i = 0; i < entregas.size(); i++) {
            Parada q = entregas.get(i);
            if (q == p || (q.getNombre().equals(p.getNombre()) && q.getDireccion().equals(p.getDireccion())
                    && q.getLat() == p.getLat() && q.getLon() == p.getLon())) {
                entregas.remove(i);
                return;
            }
        }
    }

    /** Una entrega pendiente se manda en una salida que ya va en camino. */
    public Salida.Entrega agregarASalida(Salida s, Parada p) throws IOException {
        if (!s.abierta() || s.subiendo) {
            throw new IOException("Esa salida ya no recibe entregas");
        }
        Salida.Entrega e = new Salida.Entrega(p, cobrarEnPuerta(p));
        e.domicilio = domicilioDe(p);
        e.ganancia = s.gananciaDe(e);
        s.entregas.add(e);
        Salida.Estado antes = s.estado;
        s.revisarEstado();
        try {
            guardar(() -> bd.guardarSalida(s));
        } catch (IOException ex) {
            s.entregas.remove(e);
            s.estado = antes;
            throw ex;
        }
        quitarDePendientes(p);
        avisar();
        return e;
    }

    public void guardarSalida(Salida s) throws IOException {
        guardar(() -> bd.guardarSalida(s));
    }

    /**
     * El repartidor volvió: la salida se cierra. Las entregas que no se pudieron entregar vuelven a
     * las pendientes (con sus datos) para la próxima salida.
     */
    public void cerrarSalida(Salida s, java.util.Set<Salida.Entrega> noEntregadas) throws IOException {
        if (!s.abierta() || s.subiendo) {
            return; // ya se cerró (dos clics) o todavía se está subiendo
        }
        for (Salida.Entrega e : s.entregas) {
            if (noEntregadas.contains(e)) {
                if (!e.noEntregada()) {
                    e.estado = Salida.Entrega.NO_ENTREGADO;
                }
                Parada vuelve = new Parada(e.parada.getNombre(), e.parada.getDireccion(), e.parada.getLat(),
                        e.parada.getLon());
                vuelve.setNodo(e.parada.getNodo());
                vuelve.copiarDatosDe(e.parada);
                vuelve.setVenta(null); // conserva el número de la venta: es la misma bolsa
                vuelve.setPrecision("Volvió sin entregar" + (e.motivo.isEmpty() ? "" : ": " + e.motivo));
                entregas.add(vuelve);
            } else {
                e.estado = Salida.Entrega.ENTREGADO;
            }
        }
        s.estado = Salida.Estado.CERRADA;
        s.cerrada = java.time.LocalDateTime.now();
        guardar(() -> bd.guardarSalida(s));
        if (!noEntregadas.isEmpty()) {
            limpiarRuta(); // volvieron entregas a pendientes: la ruta calculada ya no sirve
        }
        avisar();
    }

    /** Se puede cancelar mientras no haya entregado nada (si ya entregó algo, se cierra con «Cerrar a mano»). */
    public static boolean sePuedeCancelar(Salida s) {
        return s.abierta() && !s.subiendo && s.cuantas(Salida.Entrega.ENTREGADO) == 0;
    }

    /**
     * La tienda cancela la salida: el repartidor ya no va. Todo lo que llevaba vuelve a las pendientes (los
     * pedidos siguen en pie, para mandarlos con otro), su enlace deja de servir y le llega un mensaje.
     */
    public void cancelarSalida(Salida s) throws IOException {
        if (!sePuedeCancelar(s)) {
            throw new IOException(s.subiendo ? "Todavía se está enviando: espere un momento"
                    : "Ya entregó algo: ciérrela con «Cerrar a mano»");
        }
        List<Parada> vuelven = new ArrayList<>();
        for (Salida.Entrega e : s.entregas) {
            Parada vuelve = new Parada(e.parada.getNombre(), e.parada.getDireccion(), e.parada.getLat(),
                    e.parada.getLon());
            vuelve.setNodo(e.parada.getNodo());
            vuelve.copiarDatosDe(e.parada);
            vuelve.setVenta(null); // conserva el número de la venta: es la misma bolsa
            vuelven.add(vuelve);
        }
        Salida.Estado antes = s.estado;
        s.estado = Salida.Estado.CERRADA;
        s.cerrada = java.time.LocalDateTime.now();
        s.motivoCancelacion = "Cancelada por la tienda";
        try {
            guardar(() -> bd.guardarSalida(s));
        } catch (IOException ex) {
            s.estado = antes;
            s.cerrada = null;
            s.motivoCancelacion = "";
            throw ex;
        }
        entregas.addAll(vuelven);
        limpiarRuta();
        NubeRepartidor n = getNube();
        if (n != null && s.enLinea()) { // el seguimiento la cierra y la borra de internet; antes, el aviso
            String uuid = s.uuidNube;
            Thread t = new Thread(() -> {
                try {
                    n.escribir(uuid, "La tienda canceló esta salida. Ya no la lleves.");
                } catch (IOException ex) {
                    Ubicaciones.registrarError(ex);
                }
            }, "cancelar-salida");
            t.setDaemon(true);
            t.start();
        }
        avisar();
    }

    /** Si la salida terminó y no debe nada (ni plata ni bolsas), se cierra sola. True si la cerró. */
    public boolean cerrarSiTermino(Salida s) {
        if (!s.cierraSola()) {
            return false;
        }
        try {
            cerrarSalida(s, java.util.Set.of());
            return true;
        } catch (IOException e) { // no se pudo guardar: sigue abierta (se vuelve a intentar en la próxima revisión)
            Ubicaciones.registrarError(e);
            s.estado = Salida.Estado.REGRESANDO;
            s.cerrada = null;
            avisar();
            return false;
        }
    }

    /** Quienes llevan los domicilios (fijos, independientes y grupos), guardados en este computador. */
    public List<rutas.modelo.Domiciliario> getDomiciliarios() {
        try {
            return bd.cargarDomiciliarios();
        } catch (SQLException e) {
            Ubicaciones.registrarError(e);
            return new ArrayList<>();
        }
    }

    public void guardarDomiciliario(rutas.modelo.Domiciliario d) throws IOException {
        if (d.nombre.length() < 2) {
            throw new IOException("Escriba el nombre");
        }
        guardar(() -> bd.guardarDomiciliario(d));
    }

    /** Guarda su id y enlace de Supabase (sin tocar lo demás). */
    public void guardarEnlaceNube(rutas.modelo.Domiciliario d) throws IOException {
        if (d.id > 0) {
            guardar(() -> bd.guardarEnlaceNube(d.id, d.idNube, d.enlace));
        }
    }

    /** El domiciliario como está guardado ahora (la lista de la pantalla puede ser vieja); null si ya no está. */
    public rutas.modelo.Domiciliario domiciliarioActual(long id) {
        for (rutas.modelo.Domiciliario d : getDomiciliarios()) {
            if (d.id == id) {
                return d;
            }
        }
        return null;
    }

    public void eliminarDomiciliario(rutas.modelo.Domiciliario d) throws IOException {
        guardar(() -> bd.eliminarDomiciliario(d.id));
    }

    /** La conexión con Supabase, o null si no se ha configurado (sin internet todo sigue funcionando). */
    public NubeRepartidor getNube() {
        NubeRepartidor.Ajustes a = getAjustesNube();
        if (a == null || !a.listo()) {
            return null;
        }
        if (nube == null) {
            nube = new NubeRepartidor(a); // una sola conexion (se renueva si cambia la configuracion)
        }
        return nube;
    }

    private NubeRepartidor.Ajustes ajustesNube;
    private NubeRepartidor nube;
    private Seguimiento seguimiento;

    public NubeRepartidor.Ajustes getAjustesNube() {
        if (tienda == null) {
            return null;
        }
        if (ajustesNube == null) {
            try {
                ajustesNube = NubeRepartidor.Ajustes.leer(tienda);
            } catch (SQLException e) {
                return null;
            }
        }
        return ajustesNube;
    }

    /** Se guardó otra configuracion de Supabase: la proxima vez se vuelve a leer. */
    public void nubeCambio() {
        ajustesNube = null;
        nube = null;
        tiendaCambio();
    }

    private SubidaTienda subidaTienda;

    void setSubidaTienda(SubidaTienda s) {
        subidaTienda = s;
    }

    /** La que mantiene al día la tienda en línea (null en las pruebas sin ventana). */
    public SubidaTienda getSubidaTienda() {
        return subidaTienda;
    }

    /** Cambió algo que se ve en la tienda en línea: se sube en unos segundos. */
    public void tiendaCambio() {
        if (subidaTienda != null) {
            subidaTienda.cambio();
        }
    }

    void setSeguimiento(Seguimiento s) {
        seguimiento = s;
    }

    // ---------------- correcciones de ubicación (etapa G) ----------------

    /**
     * Guarda la corrección que llegó; si ya viene aplicada (coincidía con el GPS), corrige al cliente de una.
     * Devuelve true si es nueva y queda pendiente (para avisar).
     */
    boolean recibirCorreccion(Correccion c) throws IOException {
        boolean[] nueva = {false};
        guardar(() -> nueva[0] = bd.guardarCorreccion(c));
        if (!nueva[0]) {
            return false;
        }
        if (CORRECCION_APLICADA.equals(c.estado)) {
            aplicarAlCliente(c);
            return false;
        }
        return c.pendiente();
    }

    private static final String CORRECCION_APLICADA = Correccion.APLICADA;

    public List<Correccion> correccionesPendientes() {
        try {
            return bd.correccionesPendientes();
        } catch (SQLException e) {
            Ubicaciones.registrarError(e);
            return new ArrayList<>();
        }
    }

    /** La tienda la aprobó: el cliente queda con el punto nuevo. */
    public void aprobarCorreccion(Correccion c) throws IOException {
        aplicarAlCliente(c);
        guardar(() -> bd.marcarCorreccion(c.idNube, Correccion.APLICADA));
        avisar();
    }

    public void descartarCorreccion(Correccion c) throws IOException {
        guardar(() -> bd.marcarCorreccion(c.idNube, Correccion.DESCARTADA));
        avisar();
    }

    /** Si el cliente está guardado con esa misma dirección, se le pone el punto nuevo. True si lo cambió. */
    boolean aplicarAlCliente(Correccion c) throws IOException {
        Cliente cl = buscarCliente(c.nombre);
        if (cl == null || !Geo.normalizar(cl.getDireccion()).equals(Geo.normalizar(c.direccion))) {
            return false; // sin cliente guardado (o ya tiene otra dirección): no hay nada que corregir aquí
        }
        guardarCliente(cl.conDireccion(cl.getDireccion(), c.lat, c.lon));
        return true;
    }

    // ---------------- repartidor en vivo ----------------

    /** Lo último que se leyó de Supabase sobre cada repartidor (id en Supabase -> estado). */
    private java.util.Map<String, NubeRepartidor.EstadoRepartidor> estadosRepartidores = new java.util.HashMap<>();

    void setEstadosRepartidores(java.util.Map<String, NubeRepartidor.EstadoRepartidor> m) {
        estadosRepartidores = m == null ? new java.util.HashMap<>() : m;
    }

    public NubeRepartidor.EstadoRepartidor estadoNube(rutas.modelo.Domiciliario d) {
        return d == null || !d.enNube() ? null : estadosRepartidores.get(d.idNube);
    }

    /** Lo que se muestra del domiciliario: Disponible, En ruta (con cuántas le faltan) o No disponible. */
    public enum Disponibilidad { DISPONIBLE, EN_RUTA, NO_DISPONIBLE, SIN_APP }

    public Disponibilidad disponibilidad(rutas.modelo.Domiciliario d) {
        if (pendientesDe(d) > 0) {
            return Disponibilidad.EN_RUTA;
        }
        NubeRepartidor.EstadoRepartidor e = estadoNube(d);
        if (e == null) {
            return Disponibilidad.SIN_APP;
        }
        return e.disponible && e.activo ? Disponibilidad.DISPONIBLE : Disponibilidad.NO_DISPONIBLE;
    }

    /** Entregas que le faltan en las salidas que aceptó y siguen en camino. */
    public int pendientesDe(rutas.modelo.Domiciliario d) {
        int n = 0;
        for (Salida s : salidasAbiertas()) {
            if (s.repartidor.equalsIgnoreCase(d.nombre) && (s.aceptadaEn != null || !s.enLinea())) {
                n += s.cuantas(Salida.Entrega.EN_CAMINO);
            }
        }
        return n;
    }

    /** Hay algún domiciliario creado en Supabase (para leer su estado en cada revisión). */
    boolean hayRepartidoresEnNube() {
        for (rutas.modelo.Domiciliario d : getDomiciliarios()) {
            if (d.enNube()) {
                return true;
            }
        }
        return false;
    }

    /** La ruta, las calles de alrededor, la tienda y el vencimiento para subir con una salida nueva. */
    public NubeRepartidor.Extra extraPara(Ruta r) {
        return armarExtra(r.nodos());
    }

    /** Para volver a mandar una salida: se une la tienda con sus paradas en orden. */
    public NubeRepartidor.Extra extraPara(Salida s) {
        List<Integer> paradas = new ArrayList<>();
        for (Salida.Entrega e : s.entregas) {
            paradas.add(e.parada.getNodo());
        }
        int b = base == null ? -1 : base.getNodo();
        return armarExtra(RutaNube.nodosEnOrden(mapa.getGrafo(), trafico, b, paradas));
    }

    private NubeRepartidor.Extra armarExtra(List<Integer> nodos) {
        NubeRepartidor.Extra x = new NubeRepartidor.Extra();
        try {
            x.ruta = RutaNube.puntos(mapa.getGrafo(), nodos);
            x.metros = RutaNube.metros(x.ruta);
            x.calles = RutaNube.calles(mapa.getGrafo(), mapa.getVias(), x.ruta);
        } catch (RuntimeException e) { // sin ruta la página igual muestra las paradas
            Ubicaciones.registrarError(e);
            x.ruta = new ArrayList<>();
            x.calles = new ArrayList<>();
        }
        if (base != null) {
            x.tiendaLat = base.getLat();
            x.tiendaLon = base.getLon();
        }
        x.minutosParaResponder = NubeRepartidor.MINUTOS_PARA_RESPONDER;
        return x;
    }

    /**
     * Para el hilo de subida: el id del domiciliario en Supabase (lo crea si todavía no está).
     * "" si no está guardado en la lista o no se pudo crear (la salida sale igual, sin enlace personal).
     * Cambia el objeto d: después, en el hilo de la pantalla, se guarda con {@link #guardarDomiciliario}.
     */
    static String repartidorEnNube(NubeRepartidor nube, rutas.modelo.Domiciliario d) {
        if (d == null || d.id == 0) {
            return "";
        }
        try {
            if (d.enNube()) {
                if (nube.existeRepartidor(d.idNube)) {
                    return d.idNube;
                }
                d.idNube = ""; // lo borraron en Supabase: se crea otra vez con enlace nuevo
                d.enlace = "";
            }
            String[] r = nube.crearRepartidor(d.nombre, d.celular);
            d.idNube = r[0];
            d.enlace = r[1];
            return d.idNube;
        } catch (IOException e) {
            Ubicaciones.registrarError(e);
            return "";
        }
    }

    /** Pide una revision ya (por ejemplo despues de marcar o agregar algo); null si no hay ventana. */
    void revisarYa() {
        if (seguimiento != null) {
            seguimiento.revisar();
        }
    }

    // ---------------- persistencia ----------------

    /** Una operacion con la base de datos que puede fallar. */
    private interface OperacionBD {
        void ejecutar() throws SQLException;
    }

    private void guardar(OperacionBD op) throws IOException {
        try {
            op.ejecutar();
        } catch (SQLException e) {
            Ubicaciones.registrarError(e);
            throw new IOException("No se pudo guardar en la base de datos: " + e.getMessage(), e);
        }
    }

    public void guardarRutaEnHistorial() throws IOException {
        if (rutaGuardada || rutas.isEmpty()) {
            return;
        }
        rutaGuardada = true;
        List<Ruta> copia = new ArrayList<>(rutas);
        guardar(() -> bd.guardarRutas(copia));
        historial.addAll(copia);
        avisar();
    }

    /** Guarda (o actualiza) un cliente en la base de datos y en el AVL. */
    public void guardarCliente(Cliente c) throws IOException {
        guardar(() -> bd.guardarCliente(c));
        clientes.insertar(Geo.normalizar(c.getNombre()), c);
        avisar();
    }

    /**
     * Guarda un cliente editado. Si le cambiaron el nombre se quita el viejo del arbol y sus ventas
     * pasan al nombre nuevo (asi no pierde sus compras).
     */
    public void reemplazarCliente(String nombreViejo, Cliente c) throws IOException {
        String kv = Geo.normalizar(nombreViejo), kn = Geo.normalizar(c.getNombre());
        if (kv.equals(kn)) {
            guardarCliente(c);
            return;
        }
        if (clientes.buscar(kn) != null) {
            throw new IllegalArgumentException("Ya hay un cliente llamado «" + clientes.buscar(kn).getNombre() + "»");
        }
        guardar(() -> bd.renombrarCliente(nombreViejo, c));
        clientes.eliminar(kv);
        clientes.insertar(kn, c);
        if (tienda != null) {
            try {
                tienda.renombrarClienteEnVentas(nombreViejo, c.getNombre());
            } catch (SQLException e) {
                Ubicaciones.registrarError(e);
                avisar();
                throw new IOException("El cliente quedó con el nombre nuevo, pero sus compras de antes siguen con el"
                        + " nombre viejo: " + e.getMessage(), e);
            }
        }
        avisar();
    }

    /** Importar: todos los clientes se guardan juntos (si uno falla no se guarda ninguno). */
    public void guardarClientes(List<Cliente> lista) throws IOException {
        guardar(() -> bd.guardarClientes(lista));
        for (Cliente c : lista) {
            clientes.insertar(Geo.normalizar(c.getNombre()), c);
        }
        avisar();
    }

    /** La nota para el domiciliario del cliente guardado con ese nombre ("" si no hay). */
    public String notaDe(String nombre) {
        Cliente c = buscarCliente(nombre);
        return c == null ? "" : c.getNota();
    }

    public void eliminarCliente(String nombre) throws IOException {
        guardar(() -> bd.eliminarCliente(nombre));
        clientes.eliminar(Geo.normalizar(nombre));
        avisar();
    }

    public void guardarNovedades() throws IOException {
        invalidarDistancias(); // una via cerrada cambia el camino y por tanto el valor del domicilio
        guardar(() -> bd.guardarIncidentes(mapa));
        limpiarRuta(); // los tiempos cambiaron
        avisar();
    }

    /** Cierra la base de datos (al cambiar de ciudad o salir). */
    public void cerrar() {
        bd.close();
    }

    public BaseDatos getBaseDatos() {
        return bd;
    }

    // ---------------- oyentes ----------------

    /** Al cerrar la ventana (por ejemplo al salir de la sesion) sus paneles dejan de escuchar. */
    public void quitarOyentes() {
        oyentes.clear();
    }

    public void alCambiar(Runnable r) {
        oyentes.add(r);
    }

    /** Deja de avisarle a un oyente (el cobro a domicilio escucha solo mientras esta abierto). */
    public void quitarOyente(Runnable r) {
        oyentes.remove(r);
    }

    private VentanaPrincipal ventana; // para prestar el mapa y la ruta al cobro a domicilio

    void setVentana(VentanaPrincipal v) {
        ventana = v;
    }

    VentanaPrincipal getVentana() {
        return ventana;
    }

    public void avisar() {
        guardarPendientes();
        if (panelMapa != null) {
            panelMapa.setBase(base);
            panelMapa.setPendientes(entregas);
            panelMapa.setSalidas(salidas);
        }
        for (Runnable r : oyentes) {
            r.run();
        }
    }

    // ---------------- getters ----------------

    public Mapa getMapa() { return mapa; }
    public Ciudad getCiudad() { return ciudad; }
    public File getCarpetaUsuario() { return carpetaUsuario; }
    public File getCarpetaDatos() { return carpetaDatos; }
    public ModeloTrafico getTrafico() { return trafico; }
    public BuscadorLugares getBuscador() { return buscador; }
    public Geocodificador getGeo() { return geo; }
    public PlanificadorRutas getPlanificador() { return planificador; }
    public ArbolAVL<String, Cliente> getClientes() { return clientes; }
    public List<Ruta> getHistorial() { return historial; }
    public List<Parada> getEntregas() { return entregas; }
    public Parada getBase() { return base; }
    public Ruta getRuta() { return rutas.isEmpty() ? null : rutas.get(Math.min(rutaActiva, rutas.size() - 1)); }
    public List<Ruta> getRutas() { return rutas; }
    public int getRutaActiva() { return rutaActiva; }
    public boolean[] getAlcanzable() { return alcanzable; }
    public PanelMapa getPanelMapa() { return panelMapa; }
    public void setPanelMapa(PanelMapa panelMapa) { this.panelMapa = panelMapa; }

    // ---------------- tienda (no cambia al cambiar de municipio) ----------------

    private rutas.logica.BaseTienda tienda;
    private rutas.logica.Catalogo catalogo;
    private rutas.logica.Sesion sesion;

    public void setTienda(rutas.logica.BaseTienda tienda, rutas.logica.Catalogo catalogo, rutas.logica.Sesion sesion) {
        this.tienda = tienda;
        this.catalogo = catalogo;
        this.sesion = sesion;
        if (catalogo != null) {
            catalogo.setSesion(sesion); // los permisos se revisan tambien en el catalogo, no solo en la pantalla
        }
        this.ordenes = null; // otra base: se vuelve a crear
    }

    public rutas.logica.BaseTienda getTienda() { return tienda; }

    private rutas.logica.BaseOrdenes ordenes;

    /** Proveedores y ordenes de compra (en la misma base de la tienda). */
    public rutas.logica.BaseOrdenes getOrdenes() {
        if (ordenes == null && tienda != null) {
            ordenes = new rutas.logica.BaseOrdenes(tienda);
        }
        return ordenes;
    }

    public rutas.logica.Catalogo getCatalogo() { return catalogo; }

    public rutas.logica.Sesion getSesion() { return sesion; }
}
