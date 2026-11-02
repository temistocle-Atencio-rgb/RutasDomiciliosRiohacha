package rutas.vista;

import java.awt.Component;
import java.awt.Container;
import java.awt.KeyboardFocusManager;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import javax.swing.JComponent;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import javax.swing.text.JTextComponent;
import rutas.logica.PuenteCelular;
import rutas.logica.Rafagas;
import rutas.logica.ServidorLector;
import rutas.logica.Ubicaciones;

/**
 * Los lectores de codigos de barras, todos juntos:
 * - el celular por cable USB (la app DomiRuta Lector, o Chrome en localhost:8765);
 * - los lectores USB que escriben como un teclado (rafaga de teclas que termina en Enter).
 *
 * El codigo se entrega a la pantalla que se esta viendo (Vender, Compras o Productos) aunque el
 * cursor no este en su buscador. Si el cursor esta en un campo de texto (por ejemplo "Codigo de
 * barras" del formulario), el codigo del celular se escribe ahi, como haria un lector USB.
 */
public final class Lector {

    /** Por donde llego un codigo. */
    public enum Fuente {
        CELULAR("el celular"), USB("el lector USB");

        private final String texto;

        Fuente(String texto) {
            this.texto = texto;
        }

        public String getTexto() {
            return texto;
        }
    }

    private static Lector unico;

    private final Rafagas rafagas = new Rafagas();
    private final List<Runnable> oyentes = new CopyOnWriteArrayList<>();
    private ServidorLector servidor;
    private PuenteCelular puente;
    private volatile PuenteCelular.Estado estadoCelular = PuenteCelular.Estado.BUSCANDO;
    private volatile boolean otroPrograma; // el programa DomiRuta Lector aparte ya atiende el celular
    private volatile boolean usbVisto;
    private BiConsumer<String, Fuente> prueba; // la pantalla "Probar el lector", si esta abierta

    private Lector() {
    }

    public static synchronized Lector get() {
        if (unico == null) {
            unico = new Lector();
        }
        return unico;
    }

    /** Empieza a escuchar el celular y el teclado. Se llama una vez al abrir DomiRuta. */
    public void empezar() {
        if (servidor != null) {
            return;
        }
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(this::tecla);
        servidor = new ServidorLector(ServidorLector.PUERTO, c -> SwingUtilities.invokeLater(() -> entregar(c)));
        // el puerto y adb se revisan aparte: no frenan la apertura del programa
        Thread t = new Thread(() -> {
            try {
                servidor.empezar();
                puente = new PuenteCelular(ServidorLector.PUERTO, e -> {
                    estadoCelular = e;
                    avisar();
                }, (serie, nombre) -> SwingUtilities.invokeLater(() -> preguntar(serie, nombre)));
                puente.empezar();
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    puente.parar();
                    servidor.parar();
                }));
            } catch (IOException e) {
                otroPrograma = ServidorLector.hayOtroLector(ServidorLector.PUERTO);
                if (!otroPrograma) {
                    Ubicaciones.registrarError(e);
                }
                avisar();
            }
        }, "lector-arranque");
        t.setDaemon(true);
        t.start();
    }

    // ---------------- estado (barra de arriba y pantalla de prueba) ----------------

    /** Avisa cuando cambia el estado (en el hilo de la pantalla). */
    public void alCambiar(Runnable r) {
        oyentes.add(r);
    }

    public void quitarOyente(Runnable r) {
        oyentes.remove(r);
    }

    private void avisar() {
        SwingUtilities.invokeLater(() -> oyentes.forEach(Runnable::run));
    }

    /** Lo que se ve arriba, en la barra azul. */
    public String textoCorto() {
        if (otroPrograma) {
            return "Lector: programa aparte";
        }
        switch (estadoCelular) {
            case LISTO:
                return "Lector: celular listo";
            case PREGUNTANDO:
                return "Lector: celular nuevo";
            case INSTALANDO:
                return "Lector: instalando app";
            case SIN_PERMISO:
                return "Lector: acepte en el celular";
            case SIN_APP:
            case ERROR:
                return "Lector: revise el celular";
            default:
                return usbVisto ? "Lector: USB listo" : "Lector";
        }
    }

    /** Explicacion completa del celular (pantalla de prueba). */
    public String textoCelular() {
        if (otroPrograma) {
            return "El programa DomiRuta Lector, abierto aparte, está atendiendo el celular: "
                    + "los códigos llegan igual. Si lo cierra, DomiRuta lo atiende al volver a abrir.";
        }
        return estadoCelular.getTexto();
    }

    public boolean celularListo() {
        return otroPrograma || estadoCelular == PuenteCelular.Estado.LISTO;
    }

    public boolean usbVisto() {
        return usbVisto;
    }

    /**
     * Celular nuevo: se pregunta si usarlo como lector (puede ser de un cliente que solo lo conecto
     * a cargar). Se espera a que no haya nada abierto, para no interrumpir un cobro.
     */
    private void preguntar(String serie, String nombre) {
        Window w = ventanaPrincipal();
        JComponent dentro = w instanceof javax.swing.JFrame ? (JComponent) ((javax.swing.JFrame) w).getContentPane() : null;
        if (dentro == null || Capa.hayAbierta(dentro)) {
            javax.swing.Timer t = new javax.swing.Timer(800, e -> preguntar(serie, nombre));
            t.setRepeats(false);
            t.start();
            return;
        }
        boolean[] usar = {false};
        Capa c = Alerta.confirmar(dentro, "Se conectó un celular", nombre + "\n\n¿Lo va a usar como lector de códigos? "
                + "DomiRuta le instala la app DomiRuta Lector y la abre cada vez que lo conecte. "
                + "Si solo lo conectó para cargar, escoja «No».", "Usarlo como lector", "No, solo está cargando",
                () -> usar[0] = true);
        // "No", Esc o cerrar: solo carga. Se decide despues de que el boton "Usarlo" alcance a marcar
        c.setAlCerrar(() -> SwingUtilities.invokeLater(() -> {
            if (puente != null) {
                puente.decidir(serie, usar[0]);
            }
        }));
    }

    /** "Volver a preguntar": olvida que celulares se usan como lector y cuales no. */
    void olvidarCelulares() {
        if (puente != null) {
            puente.olvidarCelulares();
        }
    }

    /** Mientras la pantalla de prueba esta abierta, los codigos van a ella y no a la venta. */
    void setPrueba(BiConsumer<String, Fuente> p) {
        prueba = p;
    }

    // ---------------- codigos del celular ----------------

    /** Un codigo del celular (en el hilo de la pantalla). */
    void entregar(String codigo) {
        if (prueba != null) {
            prueba.accept(codigo, Fuente.CELULAR);
            return;
        }
        Component foco = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        RecibeCodigo panel = pantallaVisible();
        // en un campo de texto (que no sea el buscador ni una celda de tabla): se escribe ahi
        if (foco instanceof JTextComponent && ((JTextComponent) foco).isEditable() && foco.isShowing()
                && !(panel != null && panel.esBuscador(foco)) && tablaDe(foco) == null) {
            JTextComponent campo = (JTextComponent) foco;
            campo.replaceSelection(codigo);
            if (campo instanceof JTextField) {
                ((JTextField) campo).postActionEvent(); // el Enter del lector
            }
            return;
        }
        if (panel != null && !Capa.hayAbierta((JComponent) panel)) {
            cancelarEdicion(foco);
            panel.codigoEscaneado(codigo);
            return;
        }
        Toolkit.getDefaultToolkit().beep();
        Window v = ventanaPrincipal();
        if (v instanceof javax.swing.JFrame) {
            Aviso.mostrar((JComponent) ((javax.swing.JFrame) v).getContentPane(), Aviso.Tipo.MENSAJE, "Código leído",
                    codigo + "  ·  Abra Vender, Compras o Productos para usarlo");
        }
    }

    // ---------------- lectores USB (teclado) ----------------

    /**
     * Mira todas las teclas antes que la pantalla. Si una rafaga de lector cae donde no debe
     * (una celda de la tabla, un boton, el menu), el codigo se saca de ahi y va al buscador.
     */
    private boolean tecla(KeyEvent e) {
        if (e.getID() == KeyEvent.KEY_TYPED) {
            char c = e.getKeyChar();
            if (c >= 32 && c != 127 && !e.isControlDown() && !e.isAltDown()) {
                rafagas.tecla(c, e.getWhen());
            }
            return false;
        }
        if (e.getID() != KeyEvent.KEY_PRESSED) {
            return false;
        }
        int k = e.getKeyCode();
        if (k != KeyEvent.VK_ENTER) {
            if (k == KeyEvent.VK_BACK_SPACE || k == KeyEvent.VK_DELETE || e.isActionKey()) {
                rafagas.reiniciar(); // flechas o borrar: eso es una persona
            }
            return false;
        }
        String codigo = rafagas.enter(e.getWhen());
        if (codigo == null) {
            return false;
        }
        if (!usbVisto) {
            usbVisto = true;
            avisar();
        }
        Component foco = e.getComponent();
        if (prueba != null) {
            quitarDelCampo(foco, codigo);
            prueba.accept(codigo, Fuente.USB);
            return true;
        }
        return desviar(foco, codigo);
    }

    private boolean desviar(Component foco, String codigo) {
        RecibeCodigo panel = pantallaVisible();
        if (panel == null || foco == null || Capa.hayAbierta((JComponent) panel)) {
            return false; // formularios y cobro: el codigo se queda donde cayo
        }
        if (panel.esBuscador(foco)) {
            return false; // el camino normal: el buscador ya lo atiende con su Enter
        }
        if (SwingUtilities.getWindowAncestor(foco) != SwingUtilities.getWindowAncestor((Component) panel)) {
            return false;
        }
        JTable tabla = tablaDe(foco);
        if (foco instanceof JTextComponent && tabla == null) {
            return false; // un campo de texto aparte (factura, nota): el codigo se queda ahi
        }
        cancelarEdicion(foco); // la rafaga empezo a editar una celda: se deshace
        panel.codigoEscaneado(codigo);
        return true;
    }

    // ---------------- ayudas ----------------

    private static JTable tablaDe(Component c) {
        if (c instanceof JTable) {
            return (JTable) c;
        }
        return c == null ? null : (JTable) SwingUtilities.getAncestorOfClass(JTable.class, c);
    }

    private static void cancelarEdicion(Component foco) {
        JTable t = tablaDe(foco);
        if (t != null && t.isEditing()) {
            t.getCellEditor().cancelCellEditing();
        }
    }

    /** Borra el codigo que el lector alcanzo a escribir justo antes del cursor. */
    private static void quitarDelCampo(Component foco, String codigo) {
        if (!(foco instanceof JTextComponent)) {
            return;
        }
        JTextComponent t = (JTextComponent) foco;
        int fin = t.getCaretPosition();
        int ini = fin - codigo.length();
        try {
            if (ini >= 0 && t.getDocument().getText(ini, codigo.length()).equals(codigo)) {
                t.getDocument().remove(ini, codigo.length());
            }
        } catch (BadLocationException e) {
            // no estaba: no hay nada que borrar
        }
    }

    /** La pantalla de Vender, Compras o Productos que se esta viendo, o null. */
    static RecibeCodigo pantallaVisible() {
        Window activa = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
        if (activa != null) {
            RecibeCodigo r = buscarEn(activa);
            if (r != null) {
                return r;
            }
        }
        Window v = ventanaPrincipal();
        return v == null ? null : buscarEn(v);
    }

    private static RecibeCodigo buscarEn(Container c) {
        if (c instanceof RecibeCodigo && c.isShowing()) {
            return (RecibeCodigo) c;
        }
        for (Component k : c.getComponents()) {
            if (k instanceof Container && k.isShowing()) {
                RecibeCodigo r = buscarEn((Container) k);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    private static Window ventanaPrincipal() {
        for (Window w : Window.getWindows()) {
            if (w instanceof VentanaPrincipal && w.isShowing()) {
                return w;
            }
        }
        return null;
    }
}
