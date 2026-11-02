package rutas.vista;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javax.swing.JComponent;
import javax.swing.JRootPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import rutas.logica.Ubicaciones;

/**
 * Correos que se mandan por detras (recibos y ordenes de compra), uno detras de otro.
 * La ventanita de enviar se cierra enseguida y la caja sigue vendiendo: Gmail puede tardar
 * 10 o 20 segundos y eso no debe frenar la fila. Al terminar sale un aviso; si fallo, se
 * pregunta si reintentar, pero solo cuando no hay nada abierto (nunca en medio de un cobro).
 */
final class ColaCorreos {

    private static final ExecutorService HILO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "correos");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicInteger PENDIENTES = new AtomicInteger();

    private ColaCorreos() {
    }

    /**
     * Pone un correo en la cola.
     *
     * @param dentroDe   cualquier componente de la ventana (los avisos salen en ella)
     * @param queEs      con su articulo, por ejemplo "el recibo No. 00056" o "la orden No. 12"
     * @param destino    el correo de quien lo recibe (para los avisos)
     * @param trabajo    manda el correo; devuelve null si salio bien o el motivo si fallo
     * @param alSalirBien lo que se hace despues de enviar (en el hilo de la pantalla)
     * @param reintentar abre otra vez la ventanita con lo que se habia escrito y el motivo
     */
    static void enviar(JComponent dentroDe, String queEs, String destino, Callable<String> trabajo,
            Runnable alSalirBien, Consumer<String> reintentar) {
        JComponent ventana = contenido(dentroDe);
        int antes = PENDIENTES.getAndIncrement();
        Aviso.mostrar(ventana, Aviso.Tipo.MENSAJE, "Enviando " + queEs,
                "a " + destino + (antes > 0 ? "  ·  " + antes + " más en cola" : ""));
        HILO.execute(() -> {
            String error;
            try {
                error = trabajo.call();
            } catch (Exception e) {
                Ubicaciones.registrarError(e);
                error = "No se pudo enviar: " + e.getMessage();
            }
            PENDIENTES.decrementAndGet();
            String motivo = error;
            SwingUtilities.invokeLater(() -> {
                if (motivo == null) {
                    alSalirBien.run();
                    Aviso.mostrar(ventana, Aviso.Tipo.BIEN, "Correo enviado", mayuscula(queEs) + " a " + destino);
                } else {
                    java.awt.Toolkit.getDefaultToolkit().beep();
                    Aviso.mostrar(ventana, Aviso.Tipo.ERROR, "No se envió " + queEs, destino, true);
                    cuandoNoHayNadaAbierto(ventana, () -> Alerta.confirmar(ventana, "No se envió " + queEs,
                            sinEtiquetas(motivo) + "\n\nPara: " + destino, "Reintentar", "Ahora no",
                            () -> reintentar.accept(motivo)));
                }
            });
        });
    }

    /** Cuantos correos faltan por salir. */
    static int pendientes() {
        return PENDIENTES.get();
    }

    /** Espera a que se cierre el cobro (o cualquier ventanita) para no interrumpir la venta. */
    private static void cuandoNoHayNadaAbierto(JComponent ventana, Runnable r) {
        if (!Capa.hayAbierta(ventana)) {
            r.run();
            return;
        }
        Timer t = new Timer(700, null);
        t.addActionListener(e -> {
            if (!ventana.isShowing()) {
                t.stop(); // cerraron la ventana: el aviso de error ya salio
            } else if (!Capa.hayAbierta(ventana)) {
                t.stop();
                r.run();
            }
        });
        t.start();
    }

    /** El contenido de la ventana: sigue visible aunque se cierre la venta que pidio el correo. */
    static JComponent contenido(JComponent c) {
        JRootPane raiz = SwingUtilities.getRootPane(c);
        return raiz != null && raiz.getContentPane() instanceof JComponent ? (JComponent) raiz.getContentPane() : c;
    }

    private static String mayuscula(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String sinEtiquetas(String s) {
        return s.replaceAll("<[^>]+>", " ").replaceAll("\\s{2,}", " ").trim();
    }
}
