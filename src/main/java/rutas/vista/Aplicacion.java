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
        b.setPrecision("Base por defecto: cámbiela por la dirección del negocio");
        return b;
    }

    public void setBase(Parada nueva) {
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
        if (panelMapa != null) {
            panelMapa.setRutas(rutas, 0);
            panelMapa.encuadrarRutas(rutas);
        }
        avisar();
        return rutas;
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
    public boolean[] getAlcanzable() { return alcanzable; }
    public PanelMapa getPanelMapa() { return panelMapa; }
    public void setPanelMapa(PanelMapa panelMapa) { this.panelMapa = panelMapa; }
}
