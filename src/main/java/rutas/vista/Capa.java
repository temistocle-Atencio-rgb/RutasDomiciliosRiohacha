package rutas.vista;

import java.awt.AlphaComposite;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagLayout;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JLayeredPane;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * Capa que se dibuja encima de la ventana (no es otra ventana): oscurece el fondo,
 * bloquea los clics de abajo y centra una tarjeta. La usan las alertas, el cobro y
 * los cuadros pequeños. Esc la cierra si se permite.
 */
public class Capa extends JPanel {

    private static final Color OSCURO = new Color(15, 23, 32, 120);

    private final JRootPane raiz;
    private final JComponent dentroDe;
    private final JComponent tarjeta;
    private final Envoltura envoltura;
    private Runnable alCerrar;
    private float alfa = 0f;

    public Capa(JComponent dentroDe, JComponent tarjeta, boolean escCierra) {
        super(new GridBagLayout());
        this.raiz = SwingUtilities.getRootPane(dentroDe);
        this.dentroDe = dentroDe;
        this.tarjeta = tarjeta;
        setOpaque(false);
        envoltura = new Envoltura();
        envoltura.add(tarjeta, BorderLayout.CENTER);
        add(envoltura);
        // los clics y la rueda no pasan a lo que esta debajo
        MouseAdapter tragar = new MouseAdapter() { };
        addMouseListener(tragar);
        addMouseMotionListener(tragar);
        addMouseWheelListener(tragar);
        setFocusCycleRoot(true); // Tab no se sale de la capa
        if (escCierra) {
            getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cerrarCapa");
            getActionMap().put("cerrarCapa", new AbstractAction() {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent e) {
                    if (esLaDeArriba()) {
                        cerrar();
                    }
                }
            });
        }
    }

    /**
     * Panel para el contenido de una capa: ancho fijo y alto segun lo que tenga
     * (asi, si aparece algo nuevo, la tarjeta crece en vez de apretar lo de adentro).
     */
    public static JPanel tarjeta(int ancho, int arriba, int lados) {
        JPanel t = new JPanel() {
            @Override
            public java.awt.Dimension getPreferredSize() {
                return new java.awt.Dimension(ancho, super.getPreferredSize().height);
            }
        };
        t.setOpaque(false);
        t.setLayout(new javax.swing.BoxLayout(t, javax.swing.BoxLayout.Y_AXIS));
        t.setBorder(javax.swing.BorderFactory.createEmptyBorder(arriba, lados, arriba - 2, lados));
        return t;
    }

    /** Despues de cambiar el contenido de la tarjeta, la capa vuelve a acomodarse. */
    public void reacomodar() {
        revalidate();
        repaint();
    }

    public void setAlCerrar(Runnable r) {
        this.alCerrar = r;
    }

    /** Muestra la capa con una entrada de 150 ms y pone el foco en el componente indicado. */
    public void mostrar(Component foco) {
        previo = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        JLayeredPane lp = raiz.getLayeredPane();
        setBounds(0, 0, lp.getWidth(), lp.getHeight());
        lp.add(this, JLayeredPane.MODAL_LAYER, 0);
        lp.addComponentListener(ajuste);
        revalidate();
        long inicio = System.currentTimeMillis();
        Timer t = new Timer(15, null);
        t.addActionListener(e -> {
            alfa = Math.min(1f, (System.currentTimeMillis() - inicio) / 150f);
            repaint();
            if (alfa >= 1f) {
                t.stop();
            }
        });
        t.start();
        SwingUtilities.invokeLater(() -> {
            if (foco != null) {
                foco.requestFocusInWindow();
            }
        });
    }

    public void cerrar() {
        JLayeredPane lp = raiz.getLayeredPane();
        lp.removeComponentListener(ajuste);
        lp.remove(this);
        lp.repaint();
        if (alCerrar != null) {
            alCerrar.run();
        }
        // el foco vuelve a donde estaba (si era un campo) o al que pida el panel; si no, el escaner escribe en el vacio
        SwingUtilities.invokeLater(() -> {
            if (hayAbierta(dentroDe)) {
                return; // se abrio otra capa encima: ella maneja el foco
            }
            Object pedido = dentroDe.getClientProperty(FOCO_AL_CERRAR);
            Component destino = previo instanceof javax.swing.text.JTextComponent && previo.isShowing() ? previo
                    : pedido instanceof Component ? (Component) pedido : previo;
            if (destino != null && destino.isShowing()) {
                destino.requestFocusInWindow();
            }
        });
    }

    /** Propiedad del panel con el componente que recibe el foco al cerrar una capa (el buscador en Vender). */
    public static final String FOCO_AL_CERRAR = "capa.focoAlCerrar";
    private Component previo;

    public JComponent getTarjeta() {
        return tarjeta;
    }

    /** True si hay alguna capa abierta en la ventana de este componente. */
    public static boolean hayAbierta(JComponent c) {
        JRootPane r = SwingUtilities.getRootPane(c);
        if (r == null) {
            return false;
        }
        for (Component k : r.getLayeredPane().getComponentsInLayer(JLayeredPane.MODAL_LAYER)) {
            if (k instanceof Capa) {
                return true;
            }
        }
        return false;
    }

    private boolean esLaDeArriba() {
        Component[] capas = raiz.getLayeredPane().getComponentsInLayer(JLayeredPane.MODAL_LAYER);
        return capas.length > 0 && capas[0] == this;
    }

    private final java.awt.event.ComponentAdapter ajuste = new java.awt.event.ComponentAdapter() {
        @Override
        public void componentResized(java.awt.event.ComponentEvent e) {
            JLayeredPane lp = raiz.getLayeredPane();
            setBounds(0, 0, lp.getWidth(), lp.getHeight());
            revalidate();
        }
    };

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setComposite(AlphaComposite.SrcOver.derive(alfa));
        g.setColor(OSCURO);
        g.fillRect(0, 0, getWidth(), getHeight());
        g.dispose();
    }

    /** Tarjeta blanca con la sombra del manual; entra subiendo 8 px y apareciendo. */
    private class Envoltura extends JPanel {
        Envoltura() {
            super(new BorderLayout());
            setOpaque(false);
            setBorder(javax.swing.BorderFactory.createEmptyBorder(6, 6, 10, 6));
        }

        @Override
        public void paint(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setComposite(AlphaComposite.SrcOver.derive(alfa));
            g.translate(0, Math.round((1 - alfa) * 8));
            super.paint(g);
            g.dispose();
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Tema.suavizar(g);
            int x = 6, y = 6, w = getWidth() - 12, h = getHeight() - 16;
            Tema.sombra(g, x, y, w, h);
            g.setColor(Tema.BLANCO);
            g.fillRoundRect(x, y, w, h, Tema.ARCO, Tema.ARCO);
            g.dispose();
        }
    }
}
