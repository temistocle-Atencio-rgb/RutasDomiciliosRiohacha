package rutas.vista;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import rutas.logica.NubeRepartidor;
import rutas.logica.TiendaEnLinea;
import rutas.logica.Ubicaciones;

/**
 * Mantiene al día la tienda en línea sin que nadie tenga que acordarse: revisa cada 30 s
 * (y unos segundos después de guardar un producto o un ajuste) y sube solo lo que cambió.
 * Sin internet no pasa nada: lo pendiente se sube cuando vuelva.
 */
public class SubidaTienda {

    public static final int CADA_MS = 30_000;
    public static final int TRAS_CAMBIO_MS = 2_500;

    private final Aplicacion app;
    private final Timer reloj;
    private final Timer pronto;
    private final ExecutorService hilo = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tienda-en-linea");
        t.setDaemon(true);
        return t;
    });
    private final List<Runnable> oyentes = new ArrayList<>();
    private TiendaEnLinea tienda;
    /** Cómo se arma la conexión (las pruebas ponen una de mentiras). */
    java.util.function.Function<NubeRepartidor.Ajustes, TiendaEnLinea> fabrica = TiendaEnLinea::new;
    private String claveTienda = "";   // url|clave con que se armó (si cambia, se arma otra)
    private boolean ocupado;
    private boolean otraVez;           // llegó un cambio mientras subía
    private boolean revisarNube = true; // la primera vez se mira qué hay en Supabase
    private LocalDateTime ultimaBien;
    private String estado = "Todavía no se ha subido nada.";
    private boolean error;

    // ---- los pedidos que llegan de la página (cada 15 s, como los repartidores) ----
    public static final int PEDIDOS_CADA_MS = 15_000;
    private final Timer relojPedidos;
    private final ExecutorService hiloPedidos = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "pedidos-en-linea");
        t.setDaemon(true);
        return t;
    });
    private List<rutas.modelo.PedidoWeb> pedidos = new ArrayList<>();
    private final List<Runnable> oyentesPedidos = new ArrayList<>();
    private java.util.function.BiConsumer<String, String> alLlegar = (a, b) -> { };
    private boolean mirandoPedidos;
    private String errorPedidos = "";

    public SubidaTienda(Aplicacion app) {
        this.app = app;
        relojPedidos = new Timer(PEDIDOS_CADA_MS, e -> revisarPedidos());
        relojPedidos.setInitialDelay(3000);
        reloj = new Timer(CADA_MS, e -> revisar(false));
        pronto = new Timer(TRAS_CAMBIO_MS, e -> revisar(false));
        pronto.setRepeats(false);
    }

    public void empezar() {
        reloj.start();
        relojPedidos.start();
        pronto.restart(); // al abrir, enseguida (sin trabar el arranque)
    }

    public void parar() {
        reloj.stop();
        pronto.stop();
        relojPedidos.stop();
        hilo.shutdownNow();
        hiloPedidos.shutdownNow();
    }

    /** Los pedidos en línea por atender (los terminados no salen). */
    public List<rutas.modelo.PedidoWeb> getPedidos() {
        return pedidos;
    }

    public String getErrorPedidos() {
        return errorPedidos;
    }

    public void alCambiarPedidos(Runnable r) {
        oyentesPedidos.add(r);
    }

    /** Qué hacer cuando llega un pedido o un comprobante (título, texto): la ventana suena y avisa. */
    public void alLlegarPedido(java.util.function.BiConsumer<String, String> a) {
        alLlegar = a;
    }

    /** La conexión con Supabase (null si falta configurarla). */
    TiendaEnLinea conexion() {
        NubeRepartidor.Ajustes a = app.getAjustesNube();
        if (app.getTienda() == null || a == null || !a.listo()) {
            return null;
        }
        String k = a.url + "|" + a.clave;
        if (tienda == null || !k.equals(claveTienda)) {
            tienda = fabrica.apply(a);
            claveTienda = k;
            revisarNube = true;
        }
        return tienda;
    }

    /** Algo que se le hace a un pedido (aceptar, cancelar...): en otro hilo; después se vuelve a leer la lista. */
    public interface Accion {
        void hacer(TiendaEnLinea t) throws Exception;
    }

    public void accion(Accion a, java.util.function.Consumer<String> alTerminar) {
        TiendaEnLinea t = conexion();
        if (t == null) {
            alTerminar.accept("Falta la conexión con Supabase (Configuración › Conexión).");
            return;
        }
        hiloPedidos.execute(() -> {
            String error = null;
            try {
                a.hacer(t);
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            final String er = error;
            SwingUtilities.invokeLater(() -> {
                alTerminar.accept(er);
                revisarPedidos();
            });
        });
    }

    public void revisarPedidos() {
        TiendaEnLinea t = conexion();
        if (t == null || mirandoPedidos) {
            return;
        }
        mirandoPedidos = true;
        hiloPedidos.execute(() -> {
            List<rutas.modelo.PedidoWeb> l = null;
            String falla = "";
            List<rutas.modelo.PedidoWeb> nuevos = new ArrayList<>();
            try {
                l = t.pedidosActivos();
                List<Long> ids = new ArrayList<>();
                for (rutas.modelo.PedidoWeb p : l) {
                    if (!p.tomado) {
                        nuevos.add(p);
                        ids.add(p.id);
                    }
                }
                t.marcarTomados(ids);
            } catch (Exception e) {
                falla = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            final List<rutas.modelo.PedidoWeb> res = l;
            final String f = falla;
            SwingUtilities.invokeLater(() -> {
                mirandoPedidos = false;
                errorPedidos = f;
                if (res != null) {
                    List<rutas.modelo.PedidoWeb> activos = new ArrayList<>();
                    for (rutas.modelo.PedidoWeb p : res) {
                        if (!p.terminado()) {
                            activos.add(p);
                        }
                    }
                    pedidos = activos;
                    for (rutas.modelo.PedidoWeb p : nuevos) {
                        if (p.terminado()) {
                            continue;
                        }
                        String pesos = plata(p.total);
                        if (p.comprobante != null && !p.pagado) {
                            alLlegar.accept("Llegó el comprobante de " + primerNombre(p.nombre),
                                    "Pedido " + p.codigo + " · " + pesos + " por " + p.metodo().getTexto() + ". Revíselo en «En línea».");
                        } else if (p.estado.equals("nuevo")) {
                            alLlegar.accept("Pedido en línea de " + primerNombre(p.nombre),
                                    p.unidades() + (p.unidades() == 1 ? " producto · " : " productos · ") + pesos
                                    + (p.aDomicilio() ? " · a domicilio" : " · lo recoge"));
                        }
                    }
                }
                for (Runnable r : oyentesPedidos) {
                    r.run();
                }
            });
        });
    }

    static String primerNombre(String n) {
        String t = n == null ? "" : n.trim();
        int i = t.indexOf(' ');
        return t.isEmpty() ? "un cliente" : i > 0 ? t.substring(0, i) : t;
    }

    static String plata(long v) {
        return "$" + String.format(java.util.Locale.ROOT, "%,d", v).replace(',', '.');
    }

    /** Algo cambió (un producto, el logo, los medios de pago): se sube en unos segundos. */
    public void cambio() {
        pronto.restart();
    }

    /** «Actualizar ahora»: mira también qué hay en Supabase. */
    public void ahora() {
        revisar(true);
    }

    public void alCambiar(Runnable r) {
        oyentes.add(r);
    }

    public String getEstado() {
        if (!error && ultimaBien != null) {
            long min = Duration.between(ultimaBien, LocalDateTime.now()).toMinutes();
            return "Todo al día · " + (min < 1 ? "hace un momento" : min == 1 ? "hace 1 min" : "hace " + min + " min");
        }
        return ocupado && ultimaBien == null ? "Subiendo…" : estado;
    }

    public boolean hayError() {
        return error;
    }

    public boolean isOcupado() {
        return ocupado;
    }

    private void revisar(boolean forzar) {
        if (forzar) {
            revisarNube = true;
        }
        if (ocupado) {
            otraVez = true;
            return;
        }
        NubeRepartidor.Ajustes a = app.getAjustesNube();
        if (app.getTienda() == null || a == null || !a.listo()) {
            poner("Falta la conexión con Supabase (Configuración › Conexión).", true);
            return;
        }
        conexion();
        final TiendaEnLinea.Datos datos;
        final Map<String, String> enviado;
        try {
            datos = TiendaEnLinea.Datos.leer(app.getTienda(), app.getAjustesRecibo(), app.getNegocio());
            enviado = TiendaEnLinea.leerEnviado(app.getTienda().leerConfig("tienda.enviado", ""));
        } catch (java.sql.SQLException e) {
            poner("No se pudo leer el catálogo: " + e.getMessage(), true);
            return;
        }
        final boolean mirar = revisarNube;
        final TiendaEnLinea t = tienda;
        ocupado = true;
        otraVez = false;
        avisar();
        hilo.execute(() -> {
            TiendaEnLinea.Resultado r = null;
            String falla = null;
            try {
                r = t.sincronizar(datos, enviado, mirar);
            } catch (Exception e) {
                falla = e.getMessage() == null ? e.toString() : e.getMessage();
                if (!(e instanceof NubeRepartidor.ErrorNube)) {
                    Ubicaciones.registrarError(e);
                }
            }
            final TiendaEnLinea.Resultado res = r;
            final String f = falla;
            SwingUtilities.invokeLater(() -> terminar(res, f, mirar));
        });
    }

    private void terminar(TiendaEnLinea.Resultado r, String falla, boolean mirado) {
        ocupado = false;
        if (r != null) {
            try {
                app.getTienda().guardarConfigs(new String[][]{{"tienda.enviado", TiendaEnLinea.escribirEnviado(r.enviado)}});
            } catch (java.sql.SQLException e) {
                Ubicaciones.registrarError(e);
            }
            if (mirado) {
                revisarNube = false;
            }
            if (r.problemas.isEmpty()) {
                ultimaBien = LocalDateTime.now();
                error = false;
                estado = "Todo al día";
            } else {
                ultimaBien = null;
                error = true;
                estado = r.problemas.get(0) + (r.problemas.size() > 1 ? " (y " + (r.problemas.size() - 1) + " más)" : "");
            }
        } else {
            ultimaBien = null;
            error = true;
            estado = falla != null && falla.startsWith("No hay internet")
                    ? "Sin internet: se sube cuando vuelva." : falla;
        }
        avisar();
        if (otraVez) {
            pronto.restart();
        }
    }

    private void poner(String texto, boolean malo) {
        estado = texto;
        error = malo;
        ultimaBien = null;
        avisar();
    }

    private void avisar() {
        for (Runnable r : oyentes) {
            r.run();
        }
    }
}
