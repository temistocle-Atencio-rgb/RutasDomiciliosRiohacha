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
    }

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
        NubeRepartidor nube = abiertas.isEmpty() && cerradas.isEmpty() ? null : app.getNube();
        if (nube == null) {
            return;
        }
        revisando = true;
        hilo.execute(() -> {
            String err = "";
            try {
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
                SwingUtilities.invokeLater(() -> terminar(abiertas, cerradas, errFinal));
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
        boolean cambio = false;
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
        return cambio;
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
}
