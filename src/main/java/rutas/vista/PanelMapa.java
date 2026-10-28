package rutas.vista;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.font.TextAttribute;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import rutas.estructuras.Arista;
import rutas.estructuras.Grafo;
import rutas.logica.Mapa;
import rutas.modelo.CategoriaLugar;
import rutas.modelo.Geo;
import rutas.modelo.Incidente;
import rutas.modelo.Lugar;
import rutas.modelo.Parada;
import rutas.modelo.Ruta;
import rutas.modelo.TipoIncidente;
import rutas.modelo.Via;

/**
 * Mapa vectorial de Riohacha dibujado con Java2D a partir de los datos de OpenStreetMap.
 * Se mueve arrastrando con el mouse y se acerca o aleja con la rueda.
 */
public class PanelMapa extends JPanel {

    public enum Modo { NORMAL, AGREGAR_ENTREGA, FIJAR_BASE, REPORTAR_NOVEDAD, CORREGIR_UBICACION }

    /** Lo que pasa cuando el usuario hace clic sobre el mapa. */
    public interface Oyente {
        void clic(Modo modo, double x, double y, int nodo, int via);
    }

    private static final Color TIERRA = new Color(0xEEF1F5);
    private static final Color MAR = new Color(0xCFE0F1);
    private static final Color BORDE_CALLE = new Color(0xCBD3DD);
    private static final Color BORDE_PRINCIPAL = new Color(0xA9B6C6);
    private static final Color TRONCAL = new Color(0xFCDDB8);
    private static final Color BORDE_TRONCAL = new Color(0xE7A35A);
    private static final Color DESTAPADA = new Color(0xF4ECDD);
    private static final Color BORDE_DESTAPADA = new Color(0xC9B38C);

    private final Mapa mapa;
    private final Grafo grafo;
    private final List<Path2D> trazos = new ArrayList<>(); // una forma por via, en metros
    private final int[] ordenDibujo;                        // vias de menor a mayor importancia
    private final List<Path2D> costa = new ArrayList<>();
    private Path2D mar;

    // vista: centro en metros y escala en pixeles por metro
    private double centroX, centroY, escala = 0.09;
    private double minX, maxX, minY, maxY;

    private Modo modo = Modo.NORMAL;
    private Oyente oyente;
    private List<Ruta> rutas = new ArrayList<>();
    private int rutaActiva = 0;
    private Parada base;
    private List<Parada> pendientes = new ArrayList<>();
    private double[] resaltado; // {x, y}
    private String textoResaltado;
    private boolean[] noAlcanzables;
    private List<Integer> viasSeleccionadas = new ArrayList<>();
    private int indicacionNodo = -1;
    private String estado = "";

    private Point arrastreInicio;
    private double arrastreCX, arrastreCY;
    private boolean arrastro;

    public PanelMapa(Mapa mapa) {
        this.mapa = mapa;
        this.grafo = mapa.getGrafo();
        setBackground(TIERRA);
        setLayout(null);
        prepararFormas();
        ordenDibujo = ordenarVias();
        encuadrarCiudad();
        configurarMouse();
        getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancelar");
        getActionMap().put("cancelar", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                setModo(Modo.NORMAL);
            }
        });
        agregarControles();
    }

    // ------------------------------------------------------------------
    //  Preparacion
    // ------------------------------------------------------------------

    private void prepararFormas() {
        for (Via v : mapa.getVias()) {
            Path2D p = new Path2D.Double();
            int[] ns = v.getNodos();
            p.moveTo(grafo.getX(ns[0]), grafo.getY(ns[0]));
            for (int i = 1; i < ns.length; i++) {
                p.lineTo(grafo.getX(ns[i]), grafo.getY(ns[i]));
            }
            trazos.add(p);
        }
        for (double[][] linea : mapa.getCosta()) {
            Path2D p = new Path2D.Double();
            p.moveTo(Geo.x(linea[0][1]), Geo.y(linea[0][0]));
            for (int i = 1; i < linea.length; i++) {
                p.lineTo(Geo.x(linea[i][1]), Geo.y(linea[i][0]));
            }
            costa.add(p);
        }
        // el mar: en OSM la tierra queda a la izquierda de la linea de costa, el agua a la derecha.
        // Se rellena una franja de 8 km hacia el lado del agua (sirve para cualquier ciudad costera).
        mar = new Path2D.Double();
        for (double[][] l : mapa.getCosta()) {
            if (l.length < 2) {
                continue;
            }
            int n = l.length;
            double[] xs = new double[n], ys = new double[n];
            for (int i = 0; i < n; i++) {
                xs[i] = Geo.x(l[i][1]);
                ys[i] = Geo.y(l[i][0]);
            }
            mar.moveTo(xs[0], ys[0]);
            for (int i = 1; i < n; i++) {
                mar.lineTo(xs[i], ys[i]);
            }
            for (int i = n - 1; i >= 0; i--) { // de vuelta, corrido hacia la derecha
                int a = Math.max(0, i - 3), b = Math.min(n - 1, i + 3);
                double dx = xs[b] - xs[a], dy = ys[b] - ys[a], d = Math.hypot(dx, dy);
                if (d < 1e-6) {
                    continue;
                }
                mar.lineTo(xs[i] + dy / d * 8000, ys[i] - dx / d * 8000);
            }
            mar.closePath();
        }
    }

    private int importancia(Via v) {
        String t = v.getTipo().replace("_link", "");
        switch (t) {
            case "trunk": return 6;
            case "primary": return 5;
            case "secondary": return 4;
            case "tertiary": return 3;
            case "unclassified": case "residential": case "road": return 2;
            default: return 1;
        }
    }

    private int[] ordenarVias() {
        Integer[] idx = new Integer[mapa.getVias().size()];
        for (int i = 0; i < idx.length; i++) {
            idx[i] = i;
        }
        Arrays.sort(idx, (a, b) -> Integer.compare(importancia(mapa.getVia(a)), importancia(mapa.getVia(b))));
        int[] r = new int[idx.length];
        for (int i = 0; i < idx.length; i++) {
            r[i] = idx[i];
        }
        return r;
    }

    /** Encuadre inicial: la zona urbana, donde estan casi todos los lugares con nombre. */
    private void encuadrarCiudad() {
        List<Lugar> ls = mapa.getLugares();
        int n = ls.size();
        if (n < 30) { // pueblo con pocos lugares en el mapa: se encuadra con los nodos
            int m = grafo.getCantidadNodos();
            double[] xs = new double[m], ys = new double[m];
            for (int i = 0; i < m; i++) {
                xs[i] = grafo.getX(i);
                ys[i] = grafo.getY(i);
            }
            Arrays.sort(xs);
            Arrays.sort(ys);
            minX = xs[(int) (m * 0.05)];
            maxX = xs[(int) (m * 0.95)];
            minY = ys[(int) (m * 0.05)];
            maxY = ys[(int) (m * 0.95)];
            centroX = (minX + maxX) / 2;
            centroY = (minY + maxY) / 2;
            return;
        }
        double[] xs = new double[n], ys = new double[n];
        for (int i = 0; i < n; i++) {
            xs[i] = Geo.x(ls.get(i).getLon());
            ys[i] = Geo.y(ls.get(i).getLat());
        }
        Arrays.sort(xs);
        Arrays.sort(ys);
        minX = xs[(int) (n * 0.03)] - 300;
        maxX = xs[(int) (n * 0.97)] + 300;
        minY = ys[(int) (n * 0.03)] - 300;
        maxY = ys[(int) (n * 0.97)] + 300;
        centroX = (minX + maxX) / 2;
        centroY = (minY + maxY) / 2;
    }

    public void encuadrar() {
        if (getWidth() > 0) {
            escala = Math.min(getWidth() / (maxX - minX), getHeight() / (maxY - minY)) * 0.95;
        }
        centroX = (minX + maxX) / 2;
        centroY = (minY + maxY) / 2;
        repaint();
    }

    private boolean primeraVez = true;

    @Override
    public void doLayout() {
        super.doLayout();
        if (primeraVez && getWidth() > 0) {
            primeraVez = false;
            encuadrar();
        }
        if (getComponentCount() >= 3) {
            int x = getWidth() - 46;
            getComponent(0).setBounds(x, 14, 34, 34);
            getComponent(1).setBounds(x, 52, 34, 34);
            getComponent(2).setBounds(x, 96, 34, 34);
        }
    }

    private void agregarControles() {
        Tema.Boton mas = Tema.botonPequeno("+");
        mas.setFont(Tema.titulo(16));
        mas.setToolTipText("Acercar");
        mas.addActionListener(e -> zoom(1.6, getWidth() / 2.0, getHeight() / 2.0));
        Tema.Boton menos = Tema.botonPequeno("−");
        menos.setFont(Tema.titulo(16));
        menos.setToolTipText("Alejar");
        menos.addActionListener(e -> zoom(1 / 1.6, getWidth() / 2.0, getHeight() / 2.0));
        Tema.Boton todo = Tema.botonPequeno("");
        todo.setIcon(new javax.swing.Icon() { // cuadro de encuadre
            public void paintIcon(java.awt.Component c, Graphics g0, int x, int y) {
                Graphics2D g = (Graphics2D) g0.create();
                Tema.suavizar(g);
                g.setColor(Tema.AZUL);
                g.setStroke(new BasicStroke(2f));
                int t = 14, k = 5;
                g.drawLine(x, y + k, x, y);
                g.drawLine(x, y, x + k, y);
                g.drawLine(x + t - k, y, x + t, y);
                g.drawLine(x + t, y, x + t, y + k);
                g.drawLine(x + t, y + t - k, x + t, y + t);
                g.drawLine(x + t, y + t, x + t - k, y + t);
                g.drawLine(x + k, y + t, x, y + t);
                g.drawLine(x, y + t, x, y + t - k);
                g.fillOval(x + 5, y + 5, 5, 5);
                g.dispose();
            }
            public int getIconWidth() { return 15; }
            public int getIconHeight() { return 15; }
        });
        todo.setToolTipText("Ver toda la ciudad");
        todo.addActionListener(e -> encuadrar());
        for (Tema.Boton b : new Tema.Boton[]{mas, menos, todo}) {
            b.setBorder(javax.swing.BorderFactory.createEmptyBorder());
            add(b);
        }
    }

    // ------------------------------------------------------------------
    //  Conversion de coordenadas
    // ------------------------------------------------------------------

    private double sx(double x) {
        return getWidth() / 2.0 + (x - centroX) * escala;
    }

    private double sy(double y) {
        return getHeight() / 2.0 - (y - centroY) * escala;
    }

    private double mx(double px) {
        return centroX + (px - getWidth() / 2.0) / escala;
    }

    private double my(double py) {
        return centroY - (py - getHeight() / 2.0) / escala;
    }

    private AffineTransform transformacion() {
        AffineTransform t = new AffineTransform();
        t.translate(getWidth() / 2.0, getHeight() / 2.0);
        t.scale(escala, -escala);
        t.translate(-centroX, -centroY);
        return t;
    }

    private void zoom(double factor, double px, double py) {
        double nueva = Math.max(0.02, Math.min(12, escala * factor));
        double wx = mx(px), wy = my(py);
        escala = nueva;
        // el punto bajo el mouse se queda quieto
        centroX = wx - (px - getWidth() / 2.0) / escala;
        centroY = wy + (py - getHeight() / 2.0) / escala;
        repaint();
    }

    public void centrarEn(double x, double y, double escalaMinima) {
        centroX = x;
        centroY = y;
        escala = Math.max(escala, escalaMinima);
        repaint();
    }

    /** Ajusta la vista para que se vea toda la ruta. */
    public void encuadrarRuta(Ruta r) {
        encuadrarRutas(List.of(r));
    }

    /** Ajusta la vista para que se vean todas las rutas. */
    public void encuadrarRutas(List<Ruta> lista) {
        double x0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, y0 = Double.MAX_VALUE, y1 = -Double.MAX_VALUE;
        int total = 0;
        for (Ruta r : lista) {
            for (int n : r.nodos()) {
                x0 = Math.min(x0, grafo.getX(n));
                x1 = Math.max(x1, grafo.getX(n));
                y0 = Math.min(y0, grafo.getY(n));
                y1 = Math.max(y1, grafo.getY(n));
                total++;
            }
        }
        if (total < 2) {
            return;
        }
        centroX = (x0 + x1) / 2;
        centroY = (y0 + y1) / 2;
        escala = Math.min(12, Math.min((getWidth() - 120) / Math.max(200, x1 - x0),
                (getHeight() - 120) / Math.max(200, y1 - y0)));
        repaint();
    }

    // ------------------------------------------------------------------
    //  Mouse
    // ------------------------------------------------------------------

    private void configurarMouse() {
        MouseAdapter m = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                arrastreInicio = e.getPoint();
                arrastreCX = centroX;
                arrastreCY = centroY;
                arrastro = false;
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (arrastreInicio == null) {
                    return;
                }
                int dx = e.getX() - arrastreInicio.x, dy = e.getY() - arrastreInicio.y;
                if (Math.abs(dx) + Math.abs(dy) > 3) {
                    arrastro = true;
                    setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                }
                centroX = arrastreCX - dx / escala;
                centroY = arrastreCY + dy / escala;
                repaint();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                actualizarCursor();
                if (!arrastro && e.getButton() == MouseEvent.BUTTON1) {
                    clic(e.getX(), e.getY());
                }
                arrastreInicio = null;
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                zoom(Math.pow(1.25, -e.getPreciseWheelRotation()), e.getX(), e.getY());
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                int via = viaCercana(e.getX(), e.getY());
                String nuevo = via < 0 ? "" : describirVia(via);
                if (!nuevo.equals(estado)) {
                    estado = nuevo;
                    repaint(0, getHeight() - 30, getWidth(), 30);
                }
            }
        };
        addMouseListener(m);
        addMouseMotionListener(m);
        addMouseWheelListener(m);
    }

    private void clic(int px, int py) {
        double x = mx(px), y = my(py);
        int nodo = grafo.nodoMasCercano(x, y, null);
        int via = viaCercana(px, py);
        if (oyente != null) {
            oyente.clic(modo, x, y, nodo, via);
        }
    }

    /** Via mas cercana al pixel (a menos de 12 px), o -1. */
    public int viaCercana(int px, int py) {
        double x = mx(px), y = my(py);
        int nodo = grafo.nodoMasCercano(x, y, null);
        if (nodo < 0) {
            return -1;
        }
        int mejor = -1;
        double mejorD = 12 / escala;
        // revisa las aristas que salen del nodo cercano y de sus vecinos
        List<Integer> revisar = new ArrayList<>();
        revisar.add(nodo);
        for (Arista a = grafo.primera(nodo); a != null; a = a.getSiguiente()) {
            revisar.add(a.getDestino());
        }
        for (int u : revisar) {
            for (Arista a = grafo.primera(u); a != null; a = a.getSiguiente()) {
                double d = distanciaSegmento(x, y, grafo.getX(a.getOrigen()), grafo.getY(a.getOrigen()),
                        grafo.getX(a.getDestino()), grafo.getY(a.getDestino()));
                if (d < mejorD) {
                    mejorD = d;
                    mejor = a.getVia();
                }
            }
        }
        return mejor;
    }

    private static double distanciaSegmento(double px, double py, double x1, double y1, double x2, double y2) {
        double dx = x2 - x1, dy = y2 - y1;
        double l2 = dx * dx + dy * dy;
        double t = l2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - x1) * dx + (py - y1) * dy) / l2));
        return Math.hypot(px - (x1 + t * dx), py - (y1 + t * dy));
    }

    public String describirVia(int i) {
        Via v = mapa.getVia(i);
        StringBuilder sb = new StringBuilder(v.nombreVisible());
        sb.append("  ·  ").append(v.tipoLegible());
        sb.append("  ·  ").append(v.getSentido() == 0 ? "Doble vía" : "Una vía");
        if (v.esDestapada()) {
            sb.append("  ·  Destapada");
        } else if (!v.getSuperficie().equals("-")) {
            sb.append("  ·  Pavimentada");
        }
        if (v.getVelocidadMax() > 0) {
            sb.append("  ·  Máx. ").append(v.getVelocidadMax()).append(" km/h");
        }
        Incidente inc = mapa.getIncidente(i);
        if (inc != null) {
            sb.append("  ·  ¡").append(inc.getTipo()).append("!");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    //  Estado que se dibuja
    // ------------------------------------------------------------------

    private String textoCorreccion = "";

    public void setTextoCorreccion(String texto) {
        this.textoCorreccion = texto;
    }

    public void setOyente(Oyente oyente) {
        this.oyente = oyente;
    }

    public Modo getModo() {
        return modo;
    }

    public void setModo(Modo modo) {
        this.modo = modo;
        actualizarCursor();
        repaint();
    }

    private void actualizarCursor() {
        setCursor(Cursor.getPredefinedCursor(modo == Modo.NORMAL ? Cursor.DEFAULT_CURSOR : Cursor.CROSSHAIR_CURSOR));
    }

    public void setRuta(Ruta ruta) {
        setRutas(ruta == null ? null : List.of(ruta), 0);
    }

    /** Varias rutas (una por repartidor); la activa se dibuja encima y mas fuerte. */
    public void setRutas(List<Ruta> lista, int activa) {
        this.rutas = lista == null ? new ArrayList<>() : new ArrayList<>(lista);
        this.rutaActiva = activa;
        this.indicacionNodo = -1;
        repaint();
    }

    /** Color de la ruta de cada repartidor. */
    public static Color colorRuta(int i) {
        Color[] c = {Tema.AZUL, Tema.EXITO, new Color(0x8A4FB8), new Color(0xB7791F), new Color(0x0E8FA3),
            Tema.AZUL_PROFUNDO, new Color(0xC2416B), new Color(0x4D7C0F)};
        return c[i % c.length];
    }

    public void setBase(Parada base) {
        this.base = base;
        repaint();
    }

    public void setPendientes(List<Parada> pendientes) {
        this.pendientes = new ArrayList<>(pendientes);
        repaint();
    }

    public void resaltar(double lat, double lon, String texto) {
        resaltado = new double[]{Geo.x(lon), Geo.y(lat)};
        textoResaltado = texto;
        centrarEn(resaltado[0], resaltado[1], 0.9);
    }

    public void quitarResaltado() {
        resaltado = null;
        repaint();
    }

    public void setNoAlcanzables(boolean[] marcados) {
        this.noAlcanzables = marcados;
        repaint();
    }

    public void setViasSeleccionadas(List<Integer> vias) {
        this.viasSeleccionadas = vias == null ? new ArrayList<>() : new ArrayList<>(vias);
        repaint();
    }

    /** Centra el mapa sobre un grupo de vias (por ejemplo todos los tramos de la Calle 15). */
    public void encuadrarVias(List<Integer> vias) {
        Rectangle2D r = null;
        for (int v : vias) {
            Rectangle2D b = trazos.get(v).getBounds2D();
            r = r == null ? b : r.createUnion(b);
        }
        if (r == null) {
            return;
        }
        centroX = r.getCenterX();
        centroY = r.getCenterY();
        escala = Math.min(4, Math.min((getWidth() - 120) / Math.max(150, r.getWidth()),
                (getHeight() - 120) / Math.max(150, r.getHeight())));
        repaint();
    }

    public void mostrarPasoIndicacion(int nodo) {
        this.indicacionNodo = nodo;
        if (nodo >= 0) {
            centrarEn(grafo.getX(nodo), grafo.getY(nodo), 1.4);
        }
        repaint();
    }

    // ------------------------------------------------------------------
    //  Dibujo
    // ------------------------------------------------------------------

    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0.create();
        Tema.suavizar(g);
        AffineTransform t = transformacion();
        Rectangle2D visible = new Rectangle2D.Double(mx(-50), my(getHeight() + 50),
                (getWidth() + 100) / escala, (getHeight() + 100) / escala);

        if (mar != null) {
            g.setColor(MAR);
            g.fill(t.createTransformedShape(mar)); // relleno con regla nonzero
            g.setColor(new Color(0xB4CDE6));
            g.setStroke(new BasicStroke(1.2f));
            for (Path2D c : costa) {
                g.draw(t.createTransformedShape(c));
            }
        }
        dibujarVias(g, t, visible);
        dibujarNovedades(g, t);
        if (!viasSeleccionadas.isEmpty()) {
            g.setColor(Tema.conAlfa(Tema.AZUL, 110));
            g.setStroke(new BasicStroke((float) Math.max(6, 14 * escala), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            for (int v : viasSeleccionadas) {
                g.draw(t.createTransformedShape(trazos.get(v)));
            }
        }
        dibujarNoAlcanzables(g);
        dibujarRuta(g);
        dibujarControles(g);
        dibujarEtiquetasBarrios(g);
        dibujarLugares(g);
        dibujarNombresCalles(g, visible);
        dibujarParadas(g);
        dibujarSuperpuestos(g);
        g.dispose();
    }

    private void dibujarVias(Graphics2D g, AffineTransform t, Rectangle2D visible) {
        boolean detalle = escala > 0.25;
        // dos pasadas: primero los bordes y luego el relleno, para que los cruces se vean limpios
        for (int pasada = 0; pasada < 2; pasada++) {
            for (int i : ordenDibujo) {
                Via v = mapa.getVia(i);
                Path2D p = trazos.get(i);
                if (!p.getBounds2D().intersects(visible)) {
                    continue;
                }
                int imp = importancia(v);
                double metros = new double[]{0, 5, 8, 11, 12, 14, 16}[imp];
                float ancho = (float) Math.max(imp >= 3 ? 1.8 : 0.9, metros * escala);
                if (!detalle && imp <= 2 && pasada == 0) {
                    continue; // de lejos las calles de barrio van sin borde
                }
                Shape s = t.createTransformedShape(p);
                if (pasada == 0) {
                    Color borde = imp >= 6 ? BORDE_TRONCAL : imp >= 3 ? BORDE_PRINCIPAL : BORDE_CALLE;
                    if (v.esDestapada()) {
                        borde = BORDE_DESTAPADA;
                    }
                    g.setColor(borde);
                    g.setStroke(new BasicStroke(ancho + 2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.draw(s);
                } else {
                    Color relleno = imp >= 6 ? TRONCAL : Color.WHITE;
                    if (v.esDestapada()) {
                        relleno = DESTAPADA;
                    }
                    if (!detalle && imp <= 2) {
                        relleno = new Color(0xC3CCD7);
                    }
                    g.setColor(relleno);
                    g.setStroke(new BasicStroke(ancho, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.draw(s);
                    if (v.getSentido() != 0 && escala > 1.2) {
                        flechasSentido(g, v);
                    }
                }
            }
        }
    }

    /** Pequenas flechas grises en las calles de una via (solo de cerca). */
    private void flechasSentido(Graphics2D g, Via v) {
        int[] ns = v.getNodos();
        g.setColor(new Color(0x9AA5B1));
        for (int i = 0; i < ns.length - 1; i++) {
            int a = v.getSentido() > 0 ? ns[i] : ns[i + 1];
            int b = v.getSentido() > 0 ? ns[i + 1] : ns[i];
            double x1 = sx(grafo.getX(a)), y1 = sy(grafo.getY(a)), x2 = sx(grafo.getX(b)), y2 = sy(grafo.getY(b));
            if (Math.hypot(x2 - x1, y2 - y1) > 60) {
                flecha(g, (x1 + x2) / 2, (y1 + y2) / 2, Math.atan2(y2 - y1, x2 - x1), 4);
            }
        }
    }

    private static void flecha(Graphics2D g, double x, double y, double ang, double t) {
        Polygon p = new Polygon();
        p.addPoint((int) Math.round(x + t * Math.cos(ang)), (int) Math.round(y + t * Math.sin(ang)));
        p.addPoint((int) Math.round(x + t * Math.cos(ang + 2.4)), (int) Math.round(y + t * Math.sin(ang + 2.4)));
        p.addPoint((int) Math.round(x + t * Math.cos(ang - 2.4)), (int) Math.round(y + t * Math.sin(ang - 2.4)));
        g.fillPolygon(p);
    }

    private void dibujarNovedades(Graphics2D g, AffineTransform t) {
        for (Incidente inc : mapa.listaIncidentes()) {
            Shape s = t.createTransformedShape(trazos.get(inc.getVia()));
            float ancho = (float) Math.max(4, 12 * escala);
            Color c = inc.getTipo() == TipoIncidente.HUECOS ? new Color(0xB7791F) : Tema.ERROR;
            float[] guion = inc.getTipo() == TipoIncidente.OBRA || inc.getTipo() == TipoIncidente.CERRADA
                    ? new float[]{ancho * 1.4f, ancho * 0.9f} : null;
            g.setColor(Tema.conAlfa(c, 200));
            g.setStroke(new BasicStroke(ancho, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10, guion, 0));
            g.draw(s);
        }
    }

    private void dibujarNoAlcanzables(Graphics2D g) {
        if (noAlcanzables == null) {
            return;
        }
        g.setColor(Tema.conAlfa(Tema.ERROR, 180));
        for (int i = 0; i < noAlcanzables.length; i++) {
            if (noAlcanzables[i]) {
                g.fillOval((int) sx(grafo.getX(i)) - 3, (int) sy(grafo.getY(i)) - 3, 6, 6);
            }
        }
    }

    private void dibujarRuta(Graphics2D g) {
        // primero las rutas de los otros repartidores, mas suaves; la activa al final
        for (int i = 0; i < rutas.size(); i++) {
            if (i != rutaActiva) {
                dibujarUnaRuta(g, rutas.get(i), colorRuta(i), false);
            }
        }
        if (rutaActiva < rutas.size()) {
            dibujarUnaRuta(g, rutas.get(rutaActiva), colorRuta(rutaActiva), true);
        }
        if (indicacionNodo >= 0) {
            int x = (int) sx(grafo.getX(indicacionNodo)), y = (int) sy(grafo.getY(indicacionNodo));
            g.setColor(Tema.NARANJA);
            g.setStroke(new BasicStroke(3));
            g.drawOval(x - 11, y - 11, 22, 22);
        }
    }

    private void dibujarUnaRuta(Graphics2D g, Ruta ruta, Color color, boolean activa) {
        if (ruta.getAristas().isEmpty()) {
            return;
        }
        List<Arista> as = ruta.getAristas();
        int corte = ruta.getInicioRegreso() < 0 ? as.size() : ruta.getInicioRegreso();
        Path2D ida = camino(as, 0, corte);
        Path2D vuelta = camino(as, corte, as.size());
        float ancho = (float) Math.max(5, Math.min(10, 9 * escala)) * (activa ? 1f : 0.75f);
        Color c = activa ? color : Tema.conAlfa(color, 150);

        g.setStroke(new BasicStroke(ancho + 4, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(Color.WHITE);
        g.draw(ida);
        g.draw(vuelta);
        g.setStroke(new BasicStroke(ancho, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(c);
        g.draw(ida);
        g.setColor(Tema.mezclar(color, Color.BLACK, 0.3));
        g.setStroke(new BasicStroke(ancho * 0.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10,
                new float[]{ancho * 1.5f, ancho * 1.2f}, 0));
        g.draw(vuelta);

        // flechas blancas indicando el sentido del recorrido
        g.setColor(Color.WHITE);
        double acumulado = 0;
        for (Arista a : as) {
            double x1 = sx(grafo.getX(a.getOrigen())), y1 = sy(grafo.getY(a.getOrigen()));
            double x2 = sx(grafo.getX(a.getDestino())), y2 = sy(grafo.getY(a.getDestino()));
            double largo = Math.hypot(x2 - x1, y2 - y1);
            acumulado += largo;
            if (acumulado > 90 && largo > 12) {
                acumulado = 0;
                flecha(g, (x1 + x2) / 2, (y1 + y2) / 2, Math.atan2(y2 - y1, x2 - x1), ancho * 0.55);
            }
        }
    }

    private Path2D camino(List<Arista> as, int desde, int hasta) {
        Path2D p = new Path2D.Double();
        boolean primero = true;
        for (int i = desde; i < hasta; i++) {
            Arista a = as.get(i);
            if (primero) {
                p.moveTo(sx(grafo.getX(a.getOrigen())), sy(grafo.getY(a.getOrigen())));
                primero = false;
            }
            p.lineTo(sx(grafo.getX(a.getDestino())), sy(grafo.getY(a.getDestino())));
        }
        return p;
    }

    /** Semaforos y pares (solo de cerca). */
    private void dibujarControles(Graphics2D g) {
        if (escala < 0.45) {
            return;
        }
        for (int i = 0; i < grafo.getCantidadNodos(); i++) {
            char m = grafo.getMarca(i);
            if (m == '-') {
                continue;
            }
            int x = (int) sx(grafo.getX(i)), y = (int) sy(grafo.getY(i));
            if (x < -10 || y < -10 || x > getWidth() + 10 || y > getHeight() + 10) {
                continue;
            }
            if (m == 'S') { // semaforo
                g.setColor(Tema.GRIS_OSCURO);
                g.fillRoundRect(x - 4, y - 9, 8, 18, 4, 4);
                g.setColor(new Color(0xF05252));
                g.fillOval(x - 2, y - 7, 4, 4);
                g.setColor(new Color(0xF6C344));
                g.fillOval(x - 2, y - 2, 4, 4);
                g.setColor(new Color(0x3CC27A));
                g.fillOval(x - 2, y + 3, 4, 4);
            } else if (m == 'P') { // pare
                Polygon oct = new Polygon();
                for (int k = 0; k < 8; k++) {
                    double ang = Math.PI / 8 + k * Math.PI / 4;
                    oct.addPoint((int) Math.round(x + 6 * Math.cos(ang)), (int) Math.round(y + 6 * Math.sin(ang)));
                }
                g.setColor(Tema.ERROR);
                g.fillPolygon(oct);
                g.setColor(Color.WHITE);
                g.drawPolygon(oct);
            } else {
                g.setColor(new Color(0xB7791F));
                g.fillRect(x - 4, y - 1, 8, 3);
            }
        }
    }

    private final List<Rectangle> ocupado = new ArrayList<>();

    private boolean libre(Rectangle r) {
        for (Rectangle o : ocupado) {
            if (o.intersects(r)) {
                return false;
            }
        }
        ocupado.add(r);
        return true;
    }

    private void dibujarEtiquetasBarrios(Graphics2D g) {
        ocupado.clear();
        if (escala < 0.11 || escala > 1.8) {
            return;
        }
        Font f = Tema.textoFuerte(escala > 0.5 ? 11 : 9.5f).deriveFont(Map.of(TextAttribute.TRACKING, 0.06f));
        g.setFont(f);
        FontMetrics fm = g.getFontMetrics();
        for (Lugar l : mapa.getLugares()) {
            if (l.getCategoria() != CategoriaLugar.BARRIO) {
                continue;
            }
            String txt = l.getNombre().toUpperCase();
            int x = (int) sx(Geo.x(l.getLon())), y = (int) sy(Geo.y(l.getLat()));
            int w = fm.stringWidth(txt);
            Rectangle r = new Rectangle(x - w / 2 - 6, y - fm.getAscent() - 4, w + 12, fm.getHeight() + 8);
            if (x < -w || x > getWidth() + w || y < 0 || y > getHeight() || !libre(r)) {
                continue;
            }
            textoConHalo(g, txt, x - w / 2, y, new Color(0x7B8794));
        }
    }

    private static Color colorCategoria(CategoriaLugar c) {
        switch (c) {
            case RESTAURANTE: return Tema.NARANJA;
            case DROGUERIA: return Tema.EXITO;
            case SALUD: return Tema.ERROR;
            case TIENDA: return Tema.AZUL;
            case EDUCACION: return new Color(0x6B7FA3);
            case BANCO: return Tema.AZUL_PROFUNDO;
            case GASOLINERA: return Tema.GRIS_OSCURO;
            case HOTEL: return new Color(0x8A6FB0);
            default: return null;
        }
    }

    private void dibujarLugares(Graphics2D g) {
        if (escala < 0.9) {
            return;
        }
        g.setFont(Tema.texto(10.5f));
        FontMetrics fm = g.getFontMetrics();
        for (Lugar l : mapa.getLugares()) {
            Color c = colorCategoria(l.getCategoria());
            if (c == null) {
                continue;
            }
            int x = (int) sx(Geo.x(l.getLon())), y = (int) sy(Geo.y(l.getLat()));
            if (x < -5 || y < -5 || x > getWidth() + 5 || y > getHeight() + 5) {
                continue;
            }
            g.setColor(Color.WHITE);
            g.fillOval(x - 5, y - 5, 10, 10);
            g.setColor(c);
            g.fillOval(x - 4, y - 4, 8, 8);
            if (escala > 1.7) {
                int w = fm.stringWidth(l.getNombre());
                Rectangle r = new Rectangle(x + 7, y - fm.getAscent(), w, fm.getHeight());
                if (libre(r)) {
                    textoConHalo(g, l.getNombre(), x + 7, y + 4, Tema.mezclar(c, Tema.GRIS_OSCURO, 0.4));
                }
            }
        }
    }

    /** Nombres de calles girados en la direccion de la via. */
    private void dibujarNombresCalles(Graphics2D g, Rectangle2D visible) {
        if (escala < 0.55) {
            return;
        }
        g.setFont(Tema.texto(escala > 1.5 ? 11 : 10));
        FontMetrics fm = g.getFontMetrics();
        List<double[]> puestos = new ArrayList<>();
        List<String> nombres = new ArrayList<>();
        for (int k = ordenDibujo.length - 1; k >= 0; k--) {
            Via v = mapa.getVia(ordenDibujo[k]);
            if (!v.tieneNombre() || !trazos.get(v.getIndice()).getBounds2D().intersects(visible)) {
                continue;
            }
            // el segmento mas largo de la via en pantalla
            int[] ns = v.getNodos();
            double mejor = 0, bx1 = 0, by1 = 0, bx2 = 0, by2 = 0;
            for (int i = 0; i < ns.length - 1; i++) {
                double x1 = sx(grafo.getX(ns[i])), y1 = sy(grafo.getY(ns[i]));
                double x2 = sx(grafo.getX(ns[i + 1])), y2 = sy(grafo.getY(ns[i + 1]));
                double l = Math.hypot(x2 - x1, y2 - y1);
                if (l > mejor) {
                    mejor = l;
                    bx1 = x1;
                    by1 = y1;
                    bx2 = x2;
                    by2 = y2;
                }
            }
            String nombre = v.getNombre();
            int w = fm.stringWidth(nombre);
            if (mejor < w + 16) {
                continue;
            }
            double cx = (bx1 + bx2) / 2, cy = (by1 + by2) / 2;
            if (cx < 0 || cy < 0 || cx > getWidth() || cy > getHeight()) {
                continue;
            }
            boolean choca = false;
            for (int i = 0; i < puestos.size() && !choca; i++) {
                double d = Math.hypot(puestos.get(i)[0] - cx, puestos.get(i)[1] - cy);
                choca = d < 45 || (d < 280 && nombres.get(i).equalsIgnoreCase(nombre));
            }
            if (choca) {
                continue;
            }
            puestos.add(new double[]{cx, cy});
            nombres.add(nombre);
            double ang = Math.atan2(by2 - by1, bx2 - bx1);
            if (ang > Math.PI / 2) {
                ang -= Math.PI;
            } else if (ang < -Math.PI / 2) {
                ang += Math.PI;
            }
            AffineTransform antes = g.getTransform();
            g.translate(cx, cy);
            g.rotate(ang);
            textoConHalo(g, nombre, -w / 2, fm.getAscent() / 2 - 1, new Color(0x4A5563));
            g.setTransform(antes);
        }
    }

    private void dibujarParadas(Graphics2D g) {
        // entregas pendientes (antes de calcular)
        if (rutas.isEmpty()) {
            for (int i = 0; i < pendientes.size(); i++) {
                marcadorNumero(g, pendientes.get(i), String.valueOf(i + 1),
                        pendientes.get(i).isUrgente() ? Tema.ERROR : Tema.conAlfa(Tema.NARANJA, 230));
            }
        } else {
            boolean varias = rutas.size() > 1;
            for (int r = 0; r < rutas.size(); r++) {
                Ruta ruta = rutas.get(r);
                for (int i = 0; i < ruta.getParadas().size(); i++) {
                    // con una sola moto los numeros van en naranja; con varias, del color de cada moto
                    Color c = varias ? colorRuta(r) : Tema.NARANJA;
                    marcadorNumero(g, ruta.getParadas().get(i), String.valueOf(i + 1), c);
                }
                for (Parada p : ruta.getSinCamino()) {
                    marcadorNumero(g, p, "!", Tema.ERROR);
                }
            }
        }
        if (base != null) {
            int x = (int) sx(Geo.x(base.getLon())), y = (int) sy(Geo.y(base.getLat()));
            g.setColor(Color.WHITE);
            g.fillOval(x - 15, y - 15, 30, 30);
            g.setColor(Tema.AZUL);
            g.setStroke(new BasicStroke(2.2f));
            g.drawOval(x - 15, y - 15, 30, 30);
            Logo.dibujarCubo(g, x - 8.7, y - 10, 20, Logo.Version.COLOR);
        }
        if (resaltado != null) {
            int x = (int) sx(resaltado[0]), y = (int) sy(resaltado[1]);
            pin(g, x, y);
            if (textoResaltado != null) {
                g.setFont(Tema.textoFuerte(12));
                textoConHalo(g, textoResaltado, x + 12, y - 18, Tema.AZUL_PROFUNDO);
            }
        }
    }

    private void marcadorNumero(Graphics2D g, Parada p, String texto, Color color) {
        int x = (int) sx(Geo.x(p.getLon())), y = (int) sy(Geo.y(p.getLat()));
        g.setColor(Color.WHITE);
        g.fillOval(x - 13, y - 13, 26, 26);
        g.setColor(color);
        g.fillOval(x - 11, y - 11, 22, 22);
        g.setFont(Tema.titulo(12));
        FontMetrics fm = g.getFontMetrics();
        // texto gris oscuro sobre naranja (regla de contraste del manual); blanco sobre colores oscuros
        boolean naranja = color.getRed() > 200 && color.getGreen() > 100 && color.getBlue() < 80;
        g.setColor(naranja ? Tema.GRIS_OSCURO : Color.WHITE);
        g.drawString(texto, x - fm.stringWidth(texto) / 2, y + fm.getAscent() / 2 - 2);
    }

    private void pin(Graphics2D g, int x, int y) {
        Path2D p = new Path2D.Double();
        p.moveTo(x, y);
        p.curveTo(x - 4, y - 8, x - 11, y - 13, x - 11, y - 21);
        p.curveTo(x - 11, y - 28, x - 6, y - 32, x, y - 32);
        p.curveTo(x + 6, y - 32, x + 11, y - 28, x + 11, y - 21);
        p.curveTo(x + 11, y - 13, x + 4, y - 8, x, y);
        g.setColor(Tema.AZUL);
        g.fill(p);
        g.setColor(Color.WHITE);
        g.fillOval(x - 4, y - 25, 8, 8);
    }

    private void textoConHalo(Graphics2D g, String texto, int x, int y, Color color) {
        java.awt.font.TextLayout tl = new java.awt.font.TextLayout(texto, g.getFont(), g.getFontRenderContext());
        Shape s = tl.getOutline(AffineTransform.getTranslateInstance(x, y));
        g.setColor(new Color(255, 255, 255, 220));
        g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(s);
        g.setColor(color);
        g.fill(s);
    }

    private void dibujarSuperpuestos(Graphics2D g) {
        // aviso del modo de clic
        String aviso = null;
        switch (modo) {
            case AGREGAR_ENTREGA: aviso = "Haga clic en el mapa donde va la entrega  ·  Esc para cancelar"; break;
            case FIJAR_BASE: aviso = "Haga clic en el mapa para fijar el punto de salida  ·  Esc para cancelar"; break;
            case REPORTAR_NOVEDAD: aviso = "Haga clic sobre la calle con la novedad  ·  Esc para cancelar"; break;
            case CORREGIR_UBICACION: aviso = "Haga clic donde queda: " + textoCorreccion + "  ·  Esc para cancelar"; break;
            default: break;
        }
        if (aviso != null) {
            g.setFont(Tema.textoFuerte(12.5f));
            int w = g.getFontMetrics().stringWidth(aviso) + 28;
            int x = (getWidth() - w) / 2;
            g.setColor(Tema.AZUL_PROFUNDO);
            g.fillRoundRect(x, 14, w, 34, 17, 17);
            g.setColor(Color.WHITE);
            g.drawString(aviso, x + 14, 36);
        }

        // barra de escala
        double[] opciones = {20, 50, 100, 200, 500, 1000, 2000, 5000};
        double metros = 100;
        for (double o : opciones) {
            if (o * escala <= 120) {
                metros = o;
            }
        }
        int largo = (int) (metros * escala);
        int y = getHeight() - 40;
        g.setColor(Tema.conAlfa(Color.WHITE, 210));
        g.fillRoundRect(10, y - 18, largo + 20, 28, 8, 8);
        g.setColor(Tema.GRIS_OSCURO);
        g.setStroke(new BasicStroke(2));
        g.drawLine(20, y, 20 + largo, y);
        g.drawLine(20, y - 4, 20, y + 4);
        g.drawLine(20 + largo, y - 4, 20 + largo, y + 4);
        g.setFont(Tema.texto(10.5f));
        g.drawString(Geo.formatoDistancia(metros), 22, y - 6);

        // barra de estado inferior: informacion de la calle bajo el mouse y creditos de OSM
        g.setColor(Tema.conAlfa(Color.WHITE, 225));
        g.fillRect(0, getHeight() - 22, getWidth(), 22);
        g.setColor(Tema.GRIS_TEXTO);
        g.setFont(Tema.texto(11));
        g.drawString(estado, 10, getHeight() - 7);
        String creditos = "Datos del mapa © colaboradores de OpenStreetMap (ODbL)";
        g.drawString(creditos, getWidth() - g.getFontMetrics().stringWidth(creditos) - 10, getHeight() - 7);
    }
}
