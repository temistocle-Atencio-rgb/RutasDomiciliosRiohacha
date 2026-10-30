package rutas.vista;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.Timer;
import rutas.modelo.Geo;
import rutas.modelo.Parada;
import rutas.modelo.Ruta;

/**
 * Ventana principal segun el manual para programas de escritorio: barra lateral azul con el cubo
 * y el menu, barra de titulo azul profundo, contenido sobre gris claro y el mapa a la derecha.
 */
public class VentanaPrincipal extends JFrame {

    private final Aplicacion app;
    private final PanelMapa panelMapa;
    private final CardLayout tarjetas = new CardLayout();
    private final JPanel contenido = new JPanel(tarjetas);
    private final List<Pestana> pestanas = new ArrayList<>();
    private final JLabel lblTrafico = new JLabel();
    private PanelNovedades panelNovedades;

    private final List<rutas.modelo.Ciudad> ciudades;

    public VentanaPrincipal(Aplicacion app, List<rutas.modelo.Ciudad> ciudades) {
        super("DomiRuta · Rutas de domicilios en " + app.getCiudad().getNombre());
        this.app = app;
        this.ciudades = ciudades;
        setIconImages(Logo.iconos());
        panelMapa = new PanelMapa(app.getMapa());
        app.setPanelMapa(panelMapa);
        panelMapa.setOyente(this::clicEnMapa);

        JPanel derecha = new JPanel(new BorderLayout());
        derecha.add(barraTitulo(), BorderLayout.NORTH);
        derecha.add(panelContenido(), BorderLayout.WEST);
        derecha.add(panelMapa, BorderLayout.CENTER);
        JPanel raiz = new JPanel(new BorderLayout());
        raiz.add(barraLateral(), BorderLayout.WEST);
        raiz.add(derecha, BorderLayout.CENTER);
        setContentPane(raiz);

        app.avisar();
        // la ventana se ajusta a la pantalla (con la escala de Windows al 125 % la pantalla "mide" menos)
        java.awt.Rectangle util = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        setMinimumSize(new Dimension(Math.min(1100, util.width), Math.min(640, util.height)));
        setSize(Math.min(1440, util.width), Math.min(860, util.height));
        setLocationRelativeTo(null);
        if (util.width < 1500) {
            setExtendedState(MAXIMIZED_BOTH);
        }
        // al abrir, cada seccion empieza desde arriba
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowOpened(java.awt.event.WindowEvent e) {
                javax.swing.SwingUtilities.invokeLater(() -> {
                    for (java.awt.Component c : contenido.getComponents()) {
                        if (c instanceof JScrollPane) {
                            ((JScrollPane) c).getViewport().setViewPosition(new java.awt.Point(0, 0));
                        }
                    }
                    pestanas.get(0).requestFocusInWindow();
                });
            }
        });
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                app.cerrar(); // la base de datos se cierra bien antes de salir
            }
        });

        actualizarTrafico();
        app.alCambiar(this::actualizarTrafico);
        new Timer(30_000, e -> actualizarTrafico()).start();
    }

    // ------------------------------------------------------------------

    /** Barra de titulo azul profundo: municipio a la izquierda y estado del trafico a la derecha. */
    private JComponent barraTitulo() {
        JPanel h = new JPanel(new BorderLayout());
        h.setBackground(Tema.AZUL_PROFUNDO);
        h.setBorder(BorderFactory.createEmptyBorder(12, 20, 12, 20));

        JPanel izq = new JPanel();
        izq.setOpaque(false);
        izq.setLayout(new BoxLayout(izq, BoxLayout.X_AXIS));
        JLabel sub = new JLabel("Rutas de domicilios  ·");
        sub.setFont(Tema.texto(14));
        sub.setForeground(Color.WHITE);
        izq.add(sub);
        izq.add(Box.createHorizontalStrut(6));
        izq.add(selectorCiudad());
        h.add(izq, BorderLayout.WEST);

        // el estado va como texto, sin cuadro de color detras (regla del manual)
        lblTrafico.setFont(Tema.textoFuerte(13.5f));
        lblTrafico.setForeground(Color.WHITE);
        h.add(lblTrafico, BorderLayout.EAST);
        return h;
    }

    /** Nombre de la ciudad con menu para cambiar de municipio o actualizar el mapa. */
    private JComponent selectorCiudad() {
        JLabel l = new JLabel(app.getCiudad().getNombre() + ", La Guajira");
        l.setHorizontalTextPosition(JLabel.LEFT);
        l.setIconTextGap(6);
        l.setIcon(new javax.swing.Icon() { // flechita hacia abajo
            public void paintIcon(java.awt.Component c, Graphics g0, int x, int y) {
                Graphics2D g = (Graphics2D) g0.create();
                Tema.suavizar(g);
                g.setColor(Color.WHITE);
                g.fillPolygon(new int[]{x, x + 8, x + 4}, new int[]{y + 2, y + 2, y + 7}, 3);
                g.dispose();
            }
            public int getIconWidth() { return 9; }
            public int getIconHeight() { return 9; }
        });
        l.setFont(Tema.textoFuerte(13));
        l.setForeground(Color.WHITE);
        l.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        l.setToolTipText("Cambiar de municipio o actualizar el mapa");
        l.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
                java.io.File datos = app.getCarpetaDatos();
                java.io.File usuario = app.getCarpetaUsuario().getParentFile();
                for (rutas.modelo.Ciudad c : ciudades) {
                    boolean lista = c.estaDisponible(datos, usuario);
                    javax.swing.JMenuItem it = new javax.swing.JMenuItem(c.getNombre()
                            + (lista ? "" : "   (descargar mapa)"));
                    it.setFont(c == app.getCiudad() || c.getClave().equals(app.getCiudad().getClave())
                            ? Tema.textoFuerte(14) : Tema.texto(14));
                    it.addActionListener(ev -> cambiarCiudad(c, false));
                    menu.add(it);
                }
                menu.addSeparator();
                javax.swing.JMenuItem act = new javax.swing.JMenuItem("Actualizar el mapa de "
                        + app.getCiudad().getNombre() + " desde OpenStreetMap");
                act.setFont(Tema.texto(14));
                act.addActionListener(ev -> cambiarCiudad(app.getCiudad(), true));
                menu.add(act);
                menu.show(l, 0, l.getHeight() + 4);
            }
        });
        return l;
    }

    private void cambiarCiudad(rutas.modelo.Ciudad c, boolean actualizar) {
        java.io.File usuario = app.getCarpetaUsuario().getParentFile();
        if (c.getClave().equals(app.getCiudad().getClave()) && !actualizar) {
            return;
        }
        boolean disponible = c.estaDisponible(app.getCarpetaDatos(), usuario);
        if (actualizar || !disponible) {
            int r = JOptionPane.showConfirmDialog(this, "Se va a descargar el mapa de " + c.getNombre()
                    + " desde OpenStreetMap.\nNecesita internet y puede tardar de 1 a 3 minutos.\n¿Continuar?",
                    "Descargar mapa", JOptionPane.OK_CANCEL_OPTION);
            if (r != JOptionPane.OK_OPTION || !DialogoDescarga.descargar(this, c, usuario)) {
                return;
            }
        }
        app.cerrar();
        dispose();
        rutas.Main.abrir(c.getClave());
    }

    private void actualizarTrafico() {
        LocalTime ahora = LocalTime.now();
        boolean pico = app.getTrafico().esHoraPico(ahora);
        lblTrafico.setText((pico ? "Hora pico: más trancones" : "Tráfico normal")
                + (app.getTrafico().isLluvia() ? "  ·  Lloviendo" : "") + "  ·  "
                + ahora.format(Ruta.FORMATO_HORA) + "  ·  " + app.getMapa().getIncidentes().size() + " novedades");
    }

    /** Barra lateral azul del manual: el cubo arriba, el menu en blanco y los atajos visibles abajo. */
    private JComponent barraLateral() {
        JPanel lat = new JPanel(new BorderLayout());
        lat.setBackground(Tema.AZUL);
        lat.setPreferredSize(new Dimension(184, 100));

        JPanel arriba = new JPanel();
        arriba.setOpaque(false);
        arriba.setLayout(new BoxLayout(arriba, BoxLayout.Y_AXIS));
        arriba.setBorder(BorderFactory.createEmptyBorder(20, 16, 12, 12));
        Logo logo = new Logo(Logo.Version.BLANCO, 30);
        logo.setAlignmentX(LEFT_ALIGNMENT);
        arriba.add(logo);
        arriba.add(Box.createVerticalStrut(24)); // espacio libre de una cara del cubo
        for (Pestana p : pestanas) {
            p.setAlignmentX(LEFT_ALIGNMENT);
            arriba.add(p);
            arriba.add(Box.createVerticalStrut(4));
        }
        lat.add(arriba, BorderLayout.NORTH);

        JPanel atajos = new JPanel();
        atajos.setOpaque(false);
        atajos.setLayout(new BoxLayout(atajos, BoxLayout.Y_AXIS));
        atajos.setBorder(BorderFactory.createEmptyBorder(12, 20, 20, 12));
        JLabel tit = new JLabel("Atajos");
        tit.setFont(Tema.textoFuerte(12.5f));
        tit.setForeground(Color.WHITE);
        atajos.add(tit);
        atajos.add(Box.createVerticalStrut(4));
        for (String[] a : new String[][]{{"Ctrl+Enter", "calcular"}, {"Ctrl+1 a 5", "secciones"},
            {"Esc", "cancelar"}}) {
            JLabel l = new JLabel(a[0] + "  " + a[1]);
            l.setFont(Tema.texto(12.5f));
            l.setForeground(TEXTO_LATERAL);
            atajos.add(l);
            atajos.add(Box.createVerticalStrut(2));
        }
        lat.add(atajos, BorderLayout.SOUTH);
        return lat;
    }

    /** Texto secundario sobre el azul: 6.3:1. */
    private static final Color TEXTO_LATERAL = new Color(0xDCE6F2);

    /** Panel de contenido sobre gris claro, con las tarjetas blancas. */
    private JComponent panelContenido() {
        PanelPedidos pedidos = new PanelPedidos(app);
        panelNovedades = new PanelNovedades(app);
        agregarPestana("Ruta", pedidos);
        agregarPestana("Buscar", new PanelExplorar(app));
        agregarPestana("Clientes", new PanelClientes(app));
        agregarPestana("Novedades", panelNovedades);
        agregarPestana("Historial", new PanelHistorial(app));
        contenido.setPreferredSize(new Dimension(412, 100));
        contenido.setBackground(Tema.GRIS_CLARO);
        contenido.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Tema.BORDE));
        // Ctrl+1 a Ctrl+5 cambian de seccion
        for (int i = 0; i < pestanas.size(); i++) {
            final int indice = i;
            getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(javax.swing.KeyStroke.getKeyStroke(
                    java.awt.event.KeyEvent.VK_1 + i, java.awt.event.InputEvent.CTRL_DOWN_MASK), "seccion" + i);
            getRootPane().getActionMap().put("seccion" + i, new javax.swing.AbstractAction() {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent e) {
                    seleccionar(indice);
                }
            });
        }
        seleccionar(0);
        return contenido;
    }

    private void agregarPestana(String nombre, JPanel panel) {
        JPanel envoltura = new PanelAjustado();
        envoltura.setBackground(Tema.GRIS_CLARO);
        envoltura.add(panel, BorderLayout.NORTH);
        JScrollPane sc = Tema.scrollSinBorde(envoltura);
        sc.getViewport().setBackground(Tema.GRIS_CLARO);
        contenido.add(sc, nombre);
        pestanas.add(new Pestana(nombre, pestanas.size()));
    }

    private void seleccionar(int i) {
        for (Pestana p : pestanas) {
            p.activa = p.indice == i;
            p.getAccessibleContext().setAccessibleDescription(p.activa ? "sección actual" : null);
            p.repaint();
        }
        tarjetas.show(contenido, pestanas.get(i).nombre);
    }

    /** Panel que toma el ancho del scroll (asi no aparece barra horizontal). */
    private static class PanelAjustado extends JPanel implements javax.swing.Scrollable {
        PanelAjustado() {
            super(new BorderLayout());
        }

        public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        public int getScrollableUnitIncrement(java.awt.Rectangle r, int o, int d) { return 16; }
        public int getScrollableBlockIncrement(java.awt.Rectangle r, int o, int d) { return r.height - 40; }
        public boolean getScrollableTracksViewportWidth() { return true; }
        public boolean getScrollableTracksViewportHeight() { return false; }
    }

    /**
     * Opcion del menu lateral. Activa: fondo blanco con texto azul profundo. Al pasar el raton: azul profundo.
     * Con el teclado (Tab, flechas, Enter) se marca con un aro de 2 px.
     */
    private class Pestana extends javax.swing.JButton {
        final int indice;
        final String nombre;
        boolean activa;

        Pestana(String nombre, int indice) {
            super(nombre);
            this.nombre = nombre;
            this.indice = indice;
            setFont(Tema.textoFuerte(14));
            setHorizontalAlignment(LEFT);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setRolloverEnabled(true);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 12));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
            setPreferredSize(new Dimension(150, 40));
            setToolTipText(nombre + " (Ctrl+" + (indice + 1) + ")");
            addActionListener(e -> seleccionar(indice));
            addFocusListener(new java.awt.event.FocusAdapter() {
                @Override
                public void focusGained(java.awt.event.FocusEvent e) { repaint(); }

                @Override
                public void focusLost(java.awt.event.FocusEvent e) { repaint(); }
            });
            // flechas arriba y abajo para moverse por el menu
            getInputMap().put(javax.swing.KeyStroke.getKeyStroke("DOWN"), "bajar");
            getInputMap().put(javax.swing.KeyStroke.getKeyStroke("UP"), "subir");
            getActionMap().put("bajar", new javax.swing.AbstractAction() {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent e) {
                    pestanas.get((indice + 1) % pestanas.size()).requestFocusInWindow();
                }
            });
            getActionMap().put("subir", new javax.swing.AbstractAction() {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent e) {
                    pestanas.get((indice + pestanas.size() - 1) % pestanas.size()).requestFocusInWindow();
                }
            });
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Tema.suavizar(g);
            int w = getWidth() - 1, h = getHeight() - 1;
            if (activa) {
                g.setColor(Color.WHITE);
                g.fillRoundRect(0, 0, w, h, Tema.ARCO, Tema.ARCO);
            } else if (getModel().isRollover()) {
                g.setColor(Tema.AZUL_PROFUNDO);
                g.fillRoundRect(0, 0, w, h, Tema.ARCO, Tema.ARCO);
            }
            if (isFocusOwner()) {
                // sobre el azul ni el azul ni el gris oscuro se ven: el aro va en blanco, o en azul si el fondo es blanco
                g.setColor(activa ? Tema.AZUL : Color.WHITE);
                g.setStroke(new BasicStroke(2f));
                g.drawRoundRect(1, 1, w - 2, h - 2, Tema.ARCO, Tema.ARCO);
            }
            // atajo a la derecha
            g.setFont(Tema.texto(12.5f));
            String atajo = "Ctrl+" + (indice + 1);
            g.setColor(activa ? Tema.GRIS_TEXTO : TEXTO_LATERAL);
            java.awt.FontMetrics fm = g.getFontMetrics();
            g.drawString(atajo, w - 10 - fm.stringWidth(atajo), (getHeight() + fm.getAscent() - fm.getDescent()) / 2);
            g.dispose();
            setForeground(activa ? Tema.AZUL_PROFUNDO : Color.WHITE);
            super.paintComponent(g0);
        }
    }

    // ------------------------------------------------------------------
    //  Clics en el mapa
    // ------------------------------------------------------------------

    private void clicEnMapa(PanelMapa.Modo modo, double x, double y, int nodo, int via) {
        switch (modo) {
            case AGREGAR_ENTREGA: {
                Parada p = paradaEn(x, y, "Entrega marcada en el mapa");
                String nombre = JOptionPane.showInputDialog(this, "Cliente o referencia para\n" + p.getDireccion()
                        + "\n(opcional):", "Nueva entrega", JOptionPane.QUESTION_MESSAGE);
                if (nombre == null) {
                    return; // cancelo
                }
                Parada nueva = new Parada(nombre.trim().isEmpty() ? p.getDireccion() : nombre.trim(),
                        p.getDireccion(), p.getLat(), p.getLon());
                nueva.setNodo(p.getNodo());
                nueva.setPrecision(p.getPrecision());
                app.agregarEntrega(nueva);
                break; // sigue en modo agregar para marcar varias seguidas
            }
            case FIJAR_BASE: {
                Parada p = paradaEn(x, y, "Punto de salida marcado en el mapa");
                Parada base = new Parada("Base", p.getDireccion(), p.getLat(), p.getLon());
                base.setNodo(p.getNodo());
                base.setPrecision(p.getPrecision());
                panelMapa.setModo(PanelMapa.Modo.NORMAL);
                app.setBase(base);
                break;
            }
            case CORREGIR_UBICACION: {
                int n = app.getMapa().getGrafo().nodoMasCercano(x, y, app.getAlcanzable());
                if (n < 0) {
                    return;
                }
                panelMapa.setModo(PanelMapa.Modo.NORMAL);
                try {
                    app.terminarCorreccion(Geo.lat(y), Geo.lon(x));
                    panelMapa.resaltar(Geo.lat(y), Geo.lon(x), null);
                } catch (java.io.IOException ex) {
                    JOptionPane.showMessageDialog(this, "No se pudo guardar la corrección: " + ex.getMessage());
                }
                break;
            }
            case REPORTAR_NOVEDAD:
                if (via < 0) {
                    JOptionPane.showMessageDialog(this, "Haga clic justo encima de una calle.");
                    return;
                }
                panelMapa.setViasSeleccionadas(List.of(via));
                panelMapa.setModo(PanelMapa.Modo.NORMAL);
                panelNovedades.reportar(via);
                seleccionar(3);
                actualizarTrafico();
                break;
            default:
                panelMapa.setViasSeleccionadas(via < 0 ? null : List.of(via));
                break;
        }
    }

    /** Parada en el punto del clic, pegada al nodo alcanzable mas cercano. */
    private Parada paradaEn(double x, double y, String precision) {
        int nodo = app.getMapa().getGrafo().nodoMasCercano(x, y, app.getAlcanzable());
        String dir = app.getGeo().describir(nodo);
        Parada p = new Parada(dir, dir, Geo.lat(y), Geo.lon(x));
        p.setNodo(nodo);
        p.setPrecision(precision);
        return p;
    }
}
