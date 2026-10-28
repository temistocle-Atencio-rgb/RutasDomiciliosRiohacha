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
 * Ventana principal: encabezado azul con la marca, panel lateral con pestanas
 * y el mapa de Riohacha a la derecha.
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

        JPanel raiz = new JPanel(new BorderLayout());
        raiz.add(encabezado(), BorderLayout.NORTH);
        raiz.add(lateral(), BorderLayout.WEST);
        raiz.add(panelMapa, BorderLayout.CENTER);
        setContentPane(raiz);

        app.avisar();
        setMinimumSize(new Dimension(1100, 680));
        setSize(1440, 860);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        actualizarTrafico();
        app.alCambiar(this::actualizarTrafico);
        new Timer(30_000, e -> actualizarTrafico()).start();
    }

    // ------------------------------------------------------------------

    private JComponent encabezado() {
        JPanel h = new JPanel(new BorderLayout());
        h.setBackground(Tema.AZUL);
        h.setBorder(BorderFactory.createEmptyBorder(10, 20, 10, 20));

        JPanel izq = new JPanel();
        izq.setOpaque(false);
        izq.setLayout(new BoxLayout(izq, BoxLayout.X_AXIS));
        izq.add(new Logo(Logo.Version.BLANCO, 34));
        izq.add(Box.createHorizontalStrut(18));
        JLabel sub = new JLabel("Rutas de domicilios  ·");
        sub.setFont(Tema.texto(13));
        sub.setForeground(new Color(255, 255, 255, 215));
        izq.add(sub);
        izq.add(Box.createHorizontalStrut(6));
        izq.add(selectorCiudad());
        h.add(izq, BorderLayout.WEST);

        JPanel der = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 6));
        der.setOpaque(false);
        lblTrafico.setFont(Tema.textoFuerte(12));
        lblTrafico.setOpaque(true);
        lblTrafico.setBorder(BorderFactory.createEmptyBorder(5, 12, 5, 12));
        der.add(lblTrafico);
        h.add(der, BorderLayout.EAST);
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
                            ? Tema.textoFuerte(12.5f) : Tema.texto(12.5f));
                    it.addActionListener(ev -> cambiarCiudad(c, false));
                    menu.add(it);
                }
                menu.addSeparator();
                javax.swing.JMenuItem act = new javax.swing.JMenuItem("Actualizar el mapa de "
                        + app.getCiudad().getNombre() + " desde OpenStreetMap");
                act.setFont(Tema.texto(12.5f));
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
        dispose();
        rutas.Main.abrir(c.getClave());
    }

    private void actualizarTrafico() {
        LocalTime ahora = LocalTime.now();
        boolean pico = app.getTrafico().esHoraPico(ahora);
        lblTrafico.setText((pico ? "Hora pico" : "Tráfico normal") + "  ·  " + ahora.format(Ruta.FORMATO_HORA)
                + "  ·  " + app.getMapa().getIncidentes().size() + " novedades");
        // naranja con texto oscuro en hora pico (regla de contraste), blanco con texto azul si no
        lblTrafico.setBackground(pico ? Tema.NARANJA : Color.WHITE);
        lblTrafico.setForeground(pico ? Tema.GRIS_OSCURO : Tema.AZUL);
    }

    private JComponent lateral() {
        JPanel lat = new JPanel(new BorderLayout());
        lat.setBackground(Color.WHITE);
        lat.setPreferredSize(new Dimension(420, 100));
        lat.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Tema.BORDE));

        JPanel barra = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        barra.setBackground(Color.WHITE);
        barra.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Tema.BORDE),
                BorderFactory.createEmptyBorder(0, 8, 0, 8)));

        PanelPedidos pedidos = new PanelPedidos(app);
        panelNovedades = new PanelNovedades(app);
        agregarPestana(barra, "Ruta", pedidos);
        agregarPestana(barra, "Buscar", new PanelExplorar(app));
        agregarPestana(barra, "Clientes", new PanelClientes(app));
        agregarPestana(barra, "Novedades", panelNovedades);
        agregarPestana(barra, "Historial", new PanelHistorial(app));
        seleccionar(0);

        lat.add(barra, BorderLayout.NORTH);
        lat.add(contenido, BorderLayout.CENTER);
        return lat;
    }

    private void agregarPestana(JPanel barra, String nombre, JPanel panel) {
        JPanel envoltura = new PanelAjustado();
        envoltura.setBackground(Color.WHITE);
        envoltura.add(panel, BorderLayout.NORTH);
        JScrollPane sc = Tema.scrollSinBorde(envoltura);
        sc.getViewport().setBackground(Color.WHITE);
        contenido.add(sc, nombre);
        Pestana p = new Pestana(nombre, pestanas.size());
        pestanas.add(p);
        barra.add(p);
    }

    private void seleccionar(int i) {
        for (Pestana p : pestanas) {
            p.activa = p.indice == i;
            p.repaint();
        }
        tarjetas.show(contenido, pestanas.get(i).getText());
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

    /** Pestana de texto con subrayado naranja cuando esta activa (acento del manual). */
    private class Pestana extends JLabel {
        final int indice;
        boolean activa;

        Pestana(String texto, int indice) {
            super(texto);
            this.indice = indice;
            setFont(Tema.textoFuerte(13));
            setBorder(BorderFactory.createEmptyBorder(13, 10, 12, 10));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    seleccionar(Pestana.this.indice);
                }
            });
        }

        @Override
        protected void paintComponent(Graphics g0) {
            setForeground(activa ? Tema.AZUL_PROFUNDO : Tema.GRIS_TEXTO);
            super.paintComponent(g0);
            if (activa) {
                Graphics2D g = (Graphics2D) g0.create();
                g.setColor(Tema.NARANJA);
                g.setStroke(new BasicStroke(3));
                g.drawLine(10, getHeight() - 2, getWidth() - 10, getHeight() - 2);
                g.dispose();
            }
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
