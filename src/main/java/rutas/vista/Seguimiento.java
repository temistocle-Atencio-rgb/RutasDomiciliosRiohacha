package rutas.vista;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import rutas.logica.NubeRepartidor;
import rutas.modelo.Salida;

/**
 * Cada 15 segundos habla con Supabase:
 * - salidas abiertas: pasa las marcas hechas en la tienda, sube las entregas agregadas que falten
 *   y lee qué marcó el repartidor, por dónde va y si escribió;
 * - salidas cerradas que siguen en internet: las cierra allá, baja las fotos y las borra
 *   (si no había internet al cerrar, se reintenta hasta que se pueda).
 * Todo lo de internet va en un hilo aparte; lo que se lee se aplica en el hilo de Swing.
 * Al hilo aparte solo se le pasan copias (textos y números), nunca listas que la pantalla cambia.
 */
public class Seguimiento {

    /** Lo que la ventana quiere saber para avisar. */
    public interface Oyente {
        void mensajeNuevo(Salida s, Salida.Mensaje m);

        /** Marcó todas: si no debía nada ya está cerrada (s.abierta() es false). */
        void terminoRuta(Salida s);

        /** Aceptó (true) o dijo que no puede (false, el motivo queda en la salida). */
        default void respondio(Salida s, boolean acepto) {
        }

        /** Algo para la campana (s puede ser null: un aviso del repartidor, no de una salida). */
        default void aviso(Salida s, String titulo, String texto, boolean grave) {
        }
    }

    /** Sin aceptar después de esto: aviso para reenviar o mandarla con otro. */
    static final int MIN_SIN_RESPUESTA = 3;
    /** Sin ubicación nueva por más de esto (y sin pausarla): aviso. */
    static final int MIN_SIN_UBICACION = 2;

    public static final int CADA_MS = 15000;
    /** Cuánto se espera, después de que marca la última, para cerrar sola una salida que no debe nada. */
    static long ESPERA_CIERRE_MS = 15000;

    private final Aplicacion app;
    private final List<Oyente> oyentes = new ArrayList<>();
    private final ExecutorService hilo = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "seguimiento-repartidor");
        t.setDaemon(true);
        return t;
    });
    private final Timer reloj;
    private boolean revisando;
    private boolean otraVez; // se pidió revisar mientras había una revisión en curso
    private String error = "";
    private volatile boolean limpiado; // lo viejo de Supabase se borra una vez cada vez que se abre el programa
    /** Último «se abrió en otro celular» que ya se avisó, por repartidor. */
    private final Map<String, java.time.LocalDateTime> otroCelularVisto = new java.util.HashMap<>();
    private boolean primeraLectura = true;
    // ---- el ícono de conexión de arriba
    private IndicadorConexion indicador;
    private long ultimaPrueba;          // cuándo se probó la conexión sin tener nada que revisar
    private boolean avisadoCaida;       // ya sonó la campana por esta caída
    /** Tiempo sin conexión antes de avisar en la campana. */
    static final long AVISAR_CAIDA_MS = 60_000;
    /** Cuándo se abrió el programa: lo que ya estaba vencido o sin respuesta antes no vuelve a sonar. */
    private final java.time.LocalDateTime inicio = java.time.LocalDateTime.now();

    public Seguimiento(Aplicacion app) {
        this.app = app;
        reloj = new Timer(CADA_MS, e -> revisar());
        reloj.setInitialDelay(2000);
        app.setSeguimiento(this);
    }

    public void empezar() {
        reloj.start();
    }

    public void parar() {
        reloj.stop();
        hilo.shutdownNow();
    }

    public void alCambiar(Oyente o) {
        oyentes.add(o);
    }

    void setIndicador(IndicadorConexion i) {
        indicador = i;
        if (i != null) {
            i.setEstado(app.getNube() == null ? IndicadorConexion.Estado.OCULTO : IndicadorConexion.Estado.REVISANDO, "");
        }
    }

    /** ¿Hay internet aunque Supabase falle? (para no decir «sin internet» cuando el problema es la base) */
    static boolean hayInternet() {
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) java.net.URI
                    .create("https://www.google.com/generate_204").toURL().openConnection();
            c.setConnectTimeout(4000);
            c.setReadTimeout(4000);
            c.setRequestMethod("HEAD");
            int r = c.getResponseCode();
            c.disconnect();
            return r > 0 && r < 500;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Qué mostrar según el error de la revisión ("" = salió bien). Se llama en el hilo de internet. */
    static IndicadorConexion.Estado clasificar(String error, java.util.function.BooleanSupplier internet) {
        if (error == null || error.isEmpty()) {
            return IndicadorConexion.Estado.EN_LINEA;
        }
        if (error.startsWith("No hay internet")) { // no conectó: puede ser el internet o Supabase
            return internet.getAsBoolean() ? IndicadorConexion.Estado.SIN_BASE : IndicadorConexion.Estado.SIN_INTERNET;
        }
        return IndicadorConexion.Estado.SIN_BASE; // respondió mal (clave, tablas, servidor)
    }

    /** Las guarda aquí, aplica las que ya vienen aplicadas y avisa las que hay que revisar. */
    void recibirCorrecciones(List<rutas.modelo.Correccion> l) {
        for (rutas.modelo.Correccion c : l) {
            try {
                if (app.recibirCorreccion(c)) {
                    String quien = c.repartidor.isEmpty() ? "El repartidor" : PanelPedidos.primerNombre(c.repartidor);
                    for (Oyente o : oyentes) {
                        o.aviso(null, "Ubicación por revisar", quien + " movió la casa de " + c.nombre
                                + " (" + c.distanciaM + " m). Revísela en «Salidas».", false);
                    }
                }
            } catch (IOException ex) {
                error = ex.getMessage();
            }
        }
        if (!l.isEmpty()) {
            app.avisar();
        }
    }

    /** Para las pruebas (el enum es de este paquete). */
    public static String clasificarParaPruebas(String error, java.util.function.BooleanSupplier internet) {
        return clasificar(error, internet).name();
    }

        /** Pone el ícono y, si la caída pasa de 1 minuto, avisa una vez (y otra cuando vuelve). */
    private void ponerConexion(IndicadorConexion.Estado e, String detalle) {
        if (indicador == null) {
            return;
        }
        IndicadorConexion.Estado antes = indicador.getEstado();
        indicador.setEstado(e, detalle);
        if (e == IndicadorConexion.Estado.EN_LINEA) {
            if (avisadoCaida) {
                avisadoCaida = false;
                for (Oyente o : oyentes) {
                    o.aviso(null, "Volvió el internet", "Lo pendiente se está mandando", false);
                }
            }
            caidaDesde = 0;
            return;
        }
        if (antes == IndicadorConexion.Estado.EN_LINEA || antes == IndicadorConexion.Estado.REVISANDO || caidaDesde == 0) {
            caidaDesde = System.currentTimeMillis();
        }
        if (!avisadoCaida && System.currentTimeMillis() - caidaDesde >= AVISAR_CAIDA_MS) {
            avisadoCaida = true;
            String hora = rutas.modelo.Ruta.FORMATO_HORA.format(java.time.LocalDateTime.now()
                    .minusSeconds((System.currentTimeMillis() - caidaDesde) / 1000));
            for (Oyente o : oyentes) {
                o.aviso(null, e == IndicadorConexion.Estado.SIN_INTERNET ? "Sin internet desde las " + hora
                        : "Supabase no responde desde las " + hora,
                        "La caja sigue funcionando; lo marcado se manda cuando vuelva.", true);
            }
        }
    }

    private long caidaDesde;

    /** "" si la última revisión salió bien; si no, por qué (por ejemplo sin internet). */
    public String getError() {
        return error;
    }

    // ------------------------------------------------------------------
    //  Lo que se le pasa al hilo de internet (copias hechas en el hilo de Swing)
    // ------------------------------------------------------------------

    /** Una salida abierta para revisar. */
    private static class Abierta {
        Salida salida;
        String uuid;
        long desde;
        long inicio = System.currentTimeMillis(); // marcas hechas después de esto no se pisan
        final List<Object[]> marcas = new ArrayList<>();   // {Entrega, idNube, estado, motivo, marcadaEn}
        final List<Object[]> subir = new ArrayList<>();    // {Entrega, orden}
        // resultados
        final List<Object[]> marcasHechas = new ArrayList<>();
        final Map<Salida.Entrega, Long> subidas = new LinkedHashMap<>();
        NubeRepartidor.Estado estado;
        List<Salida.Mensaje> mensajes = new ArrayList<>();
        long rastroHasta;
        List<double[]> rastro = new ArrayList<>();
    }

    /** Una salida cerrada que sigue en internet. */
    private static class Cerrada {
        Salida salida;
        String uuid;
        Map<Long, String> fotos;
        boolean borrada;
    }

    /** Revisa ya (por ejemplo al abrir los mensajes, al marcar o al agregar una entrega). */
    public void revisar() {
        revisarAvisos(java.time.LocalDateTime.now()); // los de tiempo salen aunque no haya internet
        if (revisando) {
            otraVez = true;
            return;
        }
        List<Abierta> abiertas = new ArrayList<>();
        List<Cerrada> cerradas = new ArrayList<>();
        for (Salida s : app.getSalidas()) {
            if (!s.enLinea() || s.subiendo) {
                continue;
            }
            if (s.abierta()) {
                Abierta a = new Abierta();
                a.salida = s;
                a.uuid = s.uuidNube;
                a.desde = s.leidoNube;
                a.rastroHasta = s.rastroHasta;
                for (int i = 0; i < s.entregas.size(); i++) {
                    Salida.Entrega e = s.entregas.get(i);
                    if (e.idNube == 0) {
                        a.subir.add(new Object[]{e, i + 1});
                    } else if (e.marcaPendiente) {
                        a.marcas.add(new Object[]{e, e.idNube, e.estado, e.motivo, e.marcadaEn});
                    }
                }
                abiertas.add(a);
            } else {
                Cerrada c = new Cerrada();
                c.salida = s;
                c.uuid = s.uuidNube;
                cerradas.add(c);
            }
        }
        boolean leerRepartidores = app.hayRepartidoresEnNube();
        NubeRepartidor configurada = app.getNube();
        if (configurada == null) {
            if (indicador != null) {
                indicador.setEstado(IndicadorConexion.Estado.OCULTO, "");
            }
            return;
        }
        boolean nadaQueRevisar = abiertas.isEmpty() && cerradas.isEmpty() && !leerRepartidores;
        if (nadaQueRevisar && System.currentTimeMillis() - ultimaPrueba < 30_000) {
            return; // sin salidas basta con probar la conexión cada 30 s
        }
        NubeRepartidor nube = configurada;
        ultimaPrueba = System.currentTimeMillis();
        revisando = true;
        Object[] reps = new Object[1]; // lo lee el hilo de internet; se aplica en el de la pantalla
        Object[] conexion = new Object[2]; // {Estado, detalle}
        List<rutas.modelo.Correccion> correcciones = new ArrayList<>();
        boolean leerCorrecciones = !abiertas.isEmpty() || !cerradas.isEmpty();
        hilo.execute(() -> {
            String err = "";
            try {
                if (nadaQueRevisar) {
                    try {
                        nube.probar();
                    } catch (IOException ex) {
                        err = ex.getMessage();
                    }
                }
                if (leerRepartidores) {
                    try {
                        reps[0] = nube.repartidores();
                    } catch (IOException ex) {
                        err = ex.getMessage();
                    }
                }
                if (!limpiado) {
                    try {
                        nube.limpiarViejo();
                        limpiado = true;
                    } catch (IOException ex) {
                        // no importa: se intenta en la próxima revisión
                    }
                }
                for (Abierta a : abiertas) {
                    try {
                        for (Object[] m : a.marcas) {
                            nube.marcar((long) m[1], (String) m[2], (String) m[3]);
                            a.marcasHechas.add(m);
                        }
                        for (Object[] x : a.subir) {
                            Salida.Entrega e = (Salida.Entrega) x[0];
                            a.subidas.put(e, nube.agregar(a.uuid, (int) x[1], e));
                        }
                        a.estado = nube.leer(a.uuid);
                        a.mensajes = nube.mensajes(a.uuid, a.desde);
                        if (a.salida.aceptadaEn != null || a.estado.aceptada != null) {
                            a.rastro = nube.rastro(a.uuid, a.rastroHasta);
                        }
                    } catch (IOException ex) {
                        err = ex.getMessage();
                    }
                }
                if (leerCorrecciones) { // antes de borrar las cerradas: las correcciones se van con la salida
                    try {
                        for (rutas.modelo.Correccion c : nube.correccionesNuevas()) {
                            nube.correccionTomada(c.idNube);
                            correcciones.add(c);
                        }
                    } catch (IOException ex) {
                        err = ex.getMessage();
                    }
                }
                for (Cerrada c : cerradas) {
                    try {
                        nube.cerrar(c.uuid); // desde aquí el enlace ya no muestra nada
                        c.fotos = nube.fotos(c.uuid);
                        nube.borrar(c.uuid);
                        c.borrada = true;
                    } catch (IOException ex) {
                        err = ex.getMessage();
                    }
                }
            } catch (RuntimeException ex) { // nunca se queda trabado el seguimiento
                err = "Error leyendo Supabase: " + ex;
            } finally {
                String errFinal = err;
                conexion[0] = clasificar(errFinal, Seguimiento::hayInternet);
                conexion[1] = errFinal;
                SwingUtilities.invokeLater(() -> {
                    ponerConexion((IndicadorConexion.Estado) conexion[0], (String) conexion[1]);
                    recibirCorrecciones(correcciones);
                    if (reps[0] != null) {
                        @SuppressWarnings("unchecked")
                        Map<String, NubeRepartidor.EstadoRepartidor> m = (Map<String, NubeRepartidor.EstadoRepartidor>) reps[0];
                        aplicarRepartidores(m);
                    }
                    terminar(abiertas, cerradas, errFinal);
                });
            }
        });
    }

    private void terminar(List<Abierta> abiertas, List<Cerrada> cerradas, String err) {
        revisando = false;
        error = err;
        boolean algo = false;
        try {
            for (Abierta a : abiertas) {
                algo |= aplicarAbierta(a);
            }
            for (Cerrada c : cerradas) {
                algo |= aplicarCerrada(c);
            }
        } finally {
            revisarAvisos(java.time.LocalDateTime.now());
            if (algo || !err.isEmpty()) {
                app.avisar();
            }
            if (otraVez) {
                otraVez = false;
                SwingUtilities.invokeLater(this::revisar);
            }
        }
    }

    private boolean aplicarAbierta(Abierta a) {
        Salida s = a.salida;
        boolean cambio = false, rastroNuevo = false;
        for (Object[] m : a.marcasHechas) {
            Salida.Entrega e = (Salida.Entrega) m[0];
            if (e.marcadaEn == (long) m[4]) { // si la volvieron a marcar mientras tanto, falta mandar esa
                e.marcaPendiente = false;
            }
        }
        for (Map.Entry<Salida.Entrega, Long> x : a.subidas.entrySet()) {
            x.getKey().idNube = x.getValue();
            cambio = true;
        }
        for (double[] p : a.rastro) { // solo lo que no se tenía (si dos revisiones se cruzan, no se repite)
            if ((long) p[2] > s.rastroHasta) {
                s.rastro.add(p);
                s.rastroHasta = (long) p[2];
                rastroNuevo = true; // solo se dibuja: no hace falta guardar
            }
        }
        if (a.estado != null) {
            if (!a.estado.existe) {
                // la borraron en Supabase: sigue aquí, pero ya sin enlace (se cierra con «Volvió»)
                s.uuidNube = "";
                error = "La salida de las " + rutas.modelo.Ruta.FORMATO_HORA.format(s.creada)
                        + " ya no está en internet; ciérrela con «Volvió».";
                guardar(s);
                return true;
            }
            cambio |= aplicar(s, a.estado, a.mensajes, a.inicio);
        } else if (cambio) {
            guardar(s);
        }
        return cambio || rastroNuevo;
    }

    private boolean aplicarCerrada(Cerrada c) {
        if (c.fotos == null && !c.borrada) {
            return false; // sin internet: se reintenta en la próxima revisión
        }
        if (c.fotos != null) {
            for (Map.Entry<Long, String> f : c.fotos.entrySet()) {
                Salida.Entrega e = c.salida.buscarNube(f.getKey());
                if (e != null && e.foto.isEmpty()) {
                    e.foto = f.getValue(); // queda como prueba en este computador
                    e.tieneFoto = true;
                }
            }
        }
        if (c.borrada) {
            c.salida.uuidNube = "";
        }
        guardar(c.salida);
        return true;
    }

    private void guardar(Salida s) {
        try {
            app.guardarSalida(s);
        } catch (IOException e) {
            error = e.getMessage();
        }
    }

    /** Pasa a la salida lo que se leyó (sin pisar lo que la tienda marcó a mano). True si cambió algo. */
    boolean aplicar(Salida s, NubeRepartidor.Estado est, List<Salida.Mensaje> nuevos) {
        return aplicar(s, est, nuevos, Long.MAX_VALUE);
    }

    boolean aplicar(Salida s, NubeRepartidor.Estado est, List<Salida.Mensaje> nuevos, long inicio) {
        if (!s.abierta() || !est.existe) {
            return false;
        }
        boolean cambio = false;
        s.leidaEnLinea = true;
        Salida.Estado antes = s.estado;
        for (Map.Entry<Long, String[]> e : est.entregas.entrySet()) {
            Salida.Entrega en = s.buscarNube(e.getKey());
            if (en == null) {
                continue;
            }
            String[] v = e.getValue();
            boolean foto = Boolean.parseBoolean(v[2]);
            if (en.tieneFoto != foto) {
                en.tieneFoto = foto;
                cambio = true;
            }
            if (en.marcaPendiente || en.marcadaEn > inicio) {
                continue; // la tienda la marcó a mano y todavía no llegó a internet: manda lo de aquí
            }
            if (!en.estado.equals(v[0]) || !en.motivo.equals(v[1])) {
                en.estado = v[0];
                en.motivo = v[1];
                cambio = true;
            }
        }
        Boolean respuesta = null;
        if (est.aceptada != null && s.aceptadaEn == null) {
            s.aceptadaEn = est.aceptada;
            respuesta = true;
            cambio = true;
        } else if (est.rechazada != null && s.rechazadaEn == null) {
            s.rechazadaEn = est.rechazada;
            s.motivoRechazo = est.motivoRechazo;
            respuesta = false;
            cambio = true;
        }
        s.revisarEstado();
        if (est.venceEn != null && !est.venceEn.equals(s.venceEn)) {
            s.venceEn = est.venceEn;
            cambio = true;
        }
        if (est.ubicacionPausada != s.ubicacionPausada) {
            s.ubicacionPausada = est.ubicacionPausada;
            cambio = true;
        }
        if (!Double.isNaN(est.lat) && est.ubicacionEn != null && !est.ubicacionEn.equals(s.ubicacionEn)) {
            s.lat = est.lat;
            s.lon = est.lon;
            s.ubicacionEn = est.ubicacionEn;
            cambio = true;
        }
        List<Salida.Mensaje> deRepartidor = new ArrayList<>();
        for (Salida.Mensaje m : nuevos) {
            s.leidoNube = Math.max(s.leidoNube, m.idNube);
            if (!s.tieneMensaje(m.idNube)) { // los de la tienda ya estaban (se agregan al enviarlos)
                s.mensajes.add(m);
                cambio = true;
                if (!m.deTienda) {
                    deRepartidor.add(m);
                }
            }
        }
        if (cambio) {
            s.mensajes.sort(java.util.Comparator.comparingLong(m -> m.idNube));
        }
        cambio |= antes != s.estado;
        if (cambio) {
            guardar(s);
        }
        if (respuesta != null) {
            for (Oyente o : oyentes) {
                o.respondio(s, respuesta);
            }
        }
        for (Salida.Mensaje m : deRepartidor) {
            for (Oyente o : oyentes) {
                o.mensajeNuevo(s, m);
            }
        }
        // terminó y no debe nada: se cierra sola, pero 15 s después de marcar la última (por si toca «Deshacer»)
        if (s.estado == Salida.Estado.REGRESANDO && s.terminoEn == 0) {
            s.terminoEn = System.currentTimeMillis(); // por ejemplo, se abrió el programa con ella ya terminada
        }
        boolean cerro = s.estado == Salida.Estado.REGRESANDO && app != null
                && System.currentTimeMillis() - s.terminoEn >= ESPERA_CIERRE_MS && app.cerrarSiTermino(s);
        // se avisa una vez: al cerrarse sola, o cuando terminó y queda algo por cuadrar
        boolean avisar = cerro || (s.abierta() && s.estado == Salida.Estado.REGRESANDO && !s.cierraSola()
                && !s.avisadoTermino);
        if (avisar && !s.avisadoTermino) {
            s.avisadoTermino = true;
            for (Oyente o : oyentes) {
                o.terminoRuta(s);
            }
        }
        return cambio || cerro;
    }

    // ------------------------------------------------------------------
    //  Avisos de la campana (una sola vez cada uno)
    // ------------------------------------------------------------------

    /** Lo que se leyó de los repartidores: queda para «¿Quién la lleva?» y avisa si abrieron el enlace en otro celular. */
    void aplicarRepartidores(Map<String, NubeRepartidor.EstadoRepartidor> m) {
        app.setEstadosRepartidores(m);
        java.time.LocalDateTime ahora = java.time.LocalDateTime.now();
        for (rutas.modelo.Domiciliario d : app.getDomiciliarios()) {
            NubeRepartidor.EstadoRepartidor e = d.enNube() ? m.get(d.idNube) : null;
            if (e == null || e.dispositivoCambio == null) {
                continue;
            }
            java.time.LocalDateTime antes = otroCelularVisto.put(d.idNube, e.dispositivoCambio);
            boolean nuevo = antes == null ? !primeraLectura || e.dispositivoCambio.isAfter(ahora.minusMinutes(30))
                    : !antes.equals(e.dispositivoCambio);
            if (nuevo) {
                for (Oyente o : oyentes) {
                    o.aviso(null, PanelPedidos.primerNombre(d.nombre) + " entró desde otro celular",
                            "Su sesión en el celular anterior se cerró. ¿No fue " + PanelPedidos.primerNombre(d.nombre)
                                    + "? Póngale una clave nueva en Configuración › Domiciliarios.", true);
                }
            }
        }
        primeraLectura = false;
    }

    /** Sin respuesta, vencida, ubicación pausada o sin ubicación: cada aviso sale una vez por salida. */
    void revisarAvisos(java.time.LocalDateTime ahora) {
        for (Salida s : app.getSalidas()) {
            for (String[] a : avisosNuevos(s, ahora, inicio, error.isEmpty())) {
                for (Oyente o : oyentes) {
                    o.aviso(s, a[1], a[2], Boolean.parseBoolean(a[3]));
                }
            }
        }
    }

    /**
     * Los avisos que le tocan a esta salida ahora y que todavía no habían salido: {clave, título, texto, grave}.
     * Los anota en s.avisos para no repetirlos; los de ubicación se pueden volver a dar si se arregla y vuelve a pasar.
     */
    public static List<String[]> avisosNuevos(Salida s, java.time.LocalDateTime ahora) {
        return avisosNuevos(s, ahora, java.time.LocalDateTime.MIN, true);
    }

    /**
     * desde: cuándo se abrió el programa (lo que ya había pasado antes no suena otra vez: se ve en la tarjeta).
     * conexionBien: la última revisión salió bien (sin internet en la tienda no se sabe nada de la ubicación).
     */
    public static List<String[]> avisosNuevos(Salida s, java.time.LocalDateTime ahora, java.time.LocalDateTime desde,
                                              boolean conexionBien) {
        List<String[]> r = new ArrayList<>();
        if (!s.abierta() || !s.enLinea() || s.subiendo) {
            return r;
        }
        String quien = s.repartidor.isEmpty() ? "El domiciliario" : PanelPedidos.primerNombre(s.repartidor);
        String hora = rutas.modelo.Ruta.FORMATO_HORA.format(s.creada);
        if (s.esperandoRespuesta() && s.venceEn != null && ahora.isAfter(s.venceEn)) {
            if (s.venceEn.isAfter(desde)) {
                unaVez(r, s, "vencida", "Venció la salida de las " + hora,
                        quien + " no respondió a tiempo. Escoja a otro en «Salidas».", true);
            }
        } else if (s.esperandoRespuesta() && s.enviadaEn != null
                && !ahora.isBefore(s.enviadaEn.plusMinutes(MIN_SIN_RESPUESTA))
                && s.enviadaEn.plusMinutes(MIN_SIN_RESPUESTA).isAfter(desde)) {
            unaVez(r, s, "sin_respuesta", quien + " no ha respondido",
                    "Lleva " + MIN_SIN_RESPUESTA + " minutos sin aceptar la salida de las " + hora
                    + ". Reenvíele el enlace o mándela con otro.", false);
        }
        boolean enRuta = s.aceptadaEn != null && s.estado == Salida.Estado.EN_CAMINO;
        if (!enRuta || !s.leidaEnLinea || !conexionBien) {
            return r; // sin una lectura buena no se sabe si pausó o si el que no tiene internet es la tienda
        }
        if (s.ubicacionPausada) {
            unaVez(r, s, "pausada", quien + " pausó su ubicación",
                    "El cliente ve «Ubicación no disponible por ahora».", false);
            return r;
        }
        s.avisos.remove("pausada"); // si la vuelve a pausar, se avisa otra vez
        java.time.LocalDateTime ultima = s.ubicacionEn != null && s.ubicacionEn.isAfter(s.aceptadaEn)
                ? s.ubicacionEn : s.aceptadaEn;
        if (ahora.isAfter(ultima.plusMinutes(MIN_SIN_UBICACION))) {
            unaVez(r, s, "sin_ubicacion", "Sin ubicación de " + quien,
                    "Hace más de " + MIN_SIN_UBICACION + " minutos no llega su ubicación (¿sin internet o sin GPS?).", false);
        } else {
            s.avisos.remove("sin_ubicacion");
        }
        return r;
    }

    private static void unaVez(List<String[]> r, Salida s, String clave, String titulo, String texto, boolean grave) {
        if (s.avisos.add(clave)) {
            r.add(new String[]{clave, titulo, texto, String.valueOf(grave)});
        }
    }
}
