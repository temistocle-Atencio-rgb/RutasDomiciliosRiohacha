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

    public SubidaTienda(Aplicacion app) {
        this.app = app;
        reloj = new Timer(CADA_MS, e -> revisar(false));
        pronto = new Timer(TRAS_CAMBIO_MS, e -> revisar(false));
        pronto.setRepeats(false);
    }

    public void empezar() {
        reloj.start();
        pronto.restart(); // al abrir, enseguida (sin trabar el arranque)
    }

    public void parar() {
        reloj.stop();
        pronto.stop();
        hilo.shutdownNow();
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
        String k = a.url + "|" + a.clave;
        if (tienda == null || !k.equals(claveTienda)) {
            tienda = fabrica.apply(a);
            claveTienda = k;
            revisarNube = true;
        }
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
