package rutas.vista;

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

    public void agregarEntrega(Parada p) {
        entregas.add(p);
        limpiarRuta();
        avisar();
    }

    public void reemplazarEntrega(int i, Parada p) {
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

    /** Nombre del negocio para el mensaje al cliente ("" si no se ha dicho). */
    public String getNegocio() {
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
            entregas.add(p);
        }
        correccionTexto = null;
        limpiarRuta();
        avisar();
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

    public void alCambiar(Runnable r) {
        oyentes.add(r);
    }

    public void avisar() {
        if (panelMapa != null) {
            panelMapa.setBase(base);
            panelMapa.setPendientes(entregas);
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
}
