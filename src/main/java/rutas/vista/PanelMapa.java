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
import javax.swing.Timer;
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
    private static final Color TRONCAL = new Color(0xFFFFFF); // troncales blancas y anchas: el naranja queda para la ruta
    private static final Color BORDE_TRONCAL = new Color(0x8FA3BC);
    private static final Color DESTAPADA = new Color(0xF4ECDD);
    private static final Color BORDE_DESTAPADA = new Color(0xC9B38C);
    // con lluvia las destapadas se pintan como barro para ver que calles se estan evitando
    private static final Color BARRO = new Color(0xE2BF8F);
    private boolean lluvia;

    public void setLluvia(boolean lluvia) {
        this.lluvia = lluvia;
        repaint();
    }

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

    // ---- animacion de la ruta ----
    // fase 1: se encienden las esquinas en el orden en que Dijkstra las va cerrando
    // fase 2: la linea sale de la base y se va trazando hasta el final
    private static final int MS_BUSQUEDA = 1600;
    private final Timer animador = new Timer(16, e -> avanzarAnimacion());
    private boolean animando;
    private long inicioAnimacion;
    private int msTrazo;
    private int[] exploracion;     // nodos en orden de visita (puede ser null)
    private double[][] acumulados; // por ruta: metros recorridos al final de cada arista
    private double[][] llegadas;   // por ruta: metros en los que se llega a cada parada
    private double metrosMax;      // largo de la ruta mas larga
    private double metrosTrazados; // cuanto se ha dibujado (igual para todas las motos)
    private String textoBusqueda = "Buscando el camino…";
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
        prepararCasas();
        ordenDibujo = ordenarVias();
        encuadrarCiudad();
        configurarMouse();
        getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancelar");
        getActionMap().put("cancelar", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                terminarAnimacion(); // Esc tambien salta la animacion de la ruta
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
        Tema.nombreAccesible(mas, "Acercar el mapa");
        Tema.nombreAccesible(menos, "Alejar el mapa");
        Tema.nombreAccesible(todo, "Ver toda la ciudad");
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
        double[] v = vistaDeRutas(lista);
        if (v != null) {
            centroX = v[0];
            centroY = v[1];
            escala = v[2];
            repaint();
        }
    }

    /** Centro y escala {x, y, escala} para que todas las rutas quepan con margen; null si no hay ruta. */
    private double[] vistaDeRutas(List<Ruta> lista) {
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
        if (total < 2 || getWidth() <= 0) {
            return null;
        }
        return new double[]{(x0 + x1) / 2, (y0 + y1) / 2,
            Math.min(12, Math.min((getWidth() - 120) / Math.max(200, x1 - x0),
                    (getHeight() - 120) / Math.max(200, y1 - y0)))};
    }

    // ------------------------------------------------------------------
    //  Mouse
    // ------------------------------------------------------------------

    private void configurarMouse() {
        MouseAdapter m = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                if (animando) {
                    // tocar el mapa toma el control: la animacion termina y la vista queda donde esta
                    terminarAnimacion(false);
                    ignorarClic = true;
                }
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
                if (ignorarClic) {
                    ignorarClic = false;
                } else if (!arrastro && e.getButton() == MouseEvent.BUTTON1) {
                    clic(e.getX(), e.getY());
                }
                arrastreInicio = null;
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                terminarAnimacion(false);
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
        List<Ruta> nuevas = lista == null ? new ArrayList<>() : new ArrayList<>(lista);
        if (animando && !nuevas.equals(rutas)) {
            terminarAnimacion();
        }
        this.rutas = nuevas;
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

    // mientras se anima, el fondo (mar y calles) se guarda en una imagen y no se redibuja en cada cuadro
    private java.awt.image.BufferedImage fondoGuardado;
    private String claveFondo;
    private String claveCuadroAnterior;

    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0.create();
        Tema.suavizar(g);
        AffineTransform t = transformacion();
        Rectangle2D visible = new Rectangle2D.Double(mx(-50), my(getHeight() + 50),
                (getWidth() + 100) / escala, (getHeight() + 100) / escala);

        String clave = centroX + "|" + centroY + "|" + escala + "|" + getWidth() + "|" + getHeight() + "|" + lluvia;
        boolean camaraQuieta = clave.equals(claveCuadroAnterior);
        claveCuadroAnterior = clave;
        camaraMoviendose = animando && !camaraQuieta;
        // el fondo se guarda solo si la camara esta quieta; si se mueve, cada cuadro es distinto
        if (animando && camaraQuieta && getWidth() > 0 && getHeight() > 0) {
            if (fondoGuardado == null || !clave.equals(claveFondo)) {
                fondoGuardado = getGraphicsConfiguration() == null
                        ? new java.awt.image.BufferedImage(getWidth(), getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB)
                        : getGraphicsConfiguration().createCompatibleImage(getWidth(), getHeight());
                Graphics2D gf = fondoGuardado.createGraphics();
                gf.setColor(getBackground());
                gf.fillRect(0, 0, getWidth(), getHeight());
                Tema.suavizar(gf);
                dibujarFondo(gf, t, visible);
                gf.dispose();
                claveFondo = clave;
            }
            g.drawImage(fondoGuardado, 0, 0, null);
        } else {
            fondoGuardado = null;
            dibujarFondo(g, t, visible);
        }
        dibujarExploracion(g);
        dibujarRuta(g);
        dibujarControles(g);
        dibujarEtiquetasBarrios(g);
        dibujarLugares(g);
        dibujarNombresCalles(g, visible);
        dibujarParadas(g);
        dibujarSuperpuestos(g);
        g.dispose();
    }

    /** Mar, calles, novedades y vias marcadas: lo que va debajo de la ruta. */
    // ---- casas (construcciones de OpenStreetMap) ----
    private static final Color CASA = new Color(0xDCE2EA);
    private static final Color BORDE_CASA = new Color(0xC6CED9);
    private static final double CELDA_CASAS = 200; // metros
    /** Desde esta escala (px por metro) empiezan a verse; de lejos serian manchas y harian lento el mapa. */
    private static final double ESCALA_CASAS = 0.35;
    private float[][] casaX, casaY;   // esquinas de cada casa en metros
    private final Map<Long, int[]> celdasCasas = new java.util.HashMap<>(); // cuadro de 200 m -> casas

    /** Cada casa se pasa a metros una sola vez y se guarda en el cuadro de 200 m donde queda. */
    private void prepararCasas() {
        List<double[][]> casas = mapa.getCasas();
        casaX = new float[casas.size()][];
        casaY = new float[casas.size()][];
        Map<Long, List<Integer>> temp = new java.util.HashMap<>();
        for (int i = 0; i < casas.size(); i++) {
            double[][] c = casas.get(i);
            casaX[i] = new float[c.length];
            casaY[i] = new float[c.length];
            for (int k = 0; k < c.length; k++) {
                casaX[i][k] = (float) Geo.x(c[k][1]);
                casaY[i][k] = (float) Geo.y(c[k][0]);
            }
            temp.computeIfAbsent(celda(casaX[i][0], casaY[i][0]), z -> new ArrayList<>()).add(i);
        }
        for (Map.Entry<Long, List<Integer>> e : temp.entrySet()) {
            celdasCasas.put(e.getKey(), e.getValue().stream().mapToInt(Integer::intValue).toArray());
        }
    }

    private boolean camaraMoviendose; // se calcula al empezar cada cuadro

    private static long celda(double x, double y) {
        long cx = (long) Math.floor(x / CELDA_CASAS), cy = (long) Math.floor(y / CELDA_CASAS);
        return (cx << 32) ^ (cy & 0xffffffffL);
    }

    private void dibujarCasas(Graphics2D g, Rectangle2D visible) {
        if (escala < ESCALA_CASAS || celdasCasas.isEmpty()) {
            return;
        }
        // aparecen poco a poco al acercarse, no de golpe
        double f = Math.min(1, (escala - ESCALA_CASAS) / 0.35);
        g.setColor(Tema.conAlfa(CASA, (int) (90 + 165 * f)));
        // con la camara en movimiento se dibujan mas livianas (sin borde y sin las diminutas) para no trabar
        boolean moviendo = camaraMoviendose;
        boolean conBorde = escala >= 1.0 && !moviendo;
        double minimo = moviendo ? 3 / escala : 0; // metros que miden 3 px en pantalla
        int[] xs = new int[64], ys = new int[64];
        long c0x = (long) Math.floor(visible.getMinX() / CELDA_CASAS) - 1, c1x = (long) Math.floor(visible.getMaxX() / CELDA_CASAS) + 1;
        long c0y = (long) Math.floor(visible.getMinY() / CELDA_CASAS) - 1, c1y = (long) Math.floor(visible.getMaxY() / CELDA_CASAS) + 1;
        for (long cx = c0x; cx <= c1x; cx++) {
            for (long cy = c0y; cy <= c1y; cy++) {
                int[] lista = celdasCasas.get((cx << 32) ^ (cy & 0xffffffffL));
                if (lista == null) {
                    continue;
                }
                for (int i : lista) {
                    float[] px = casaX[i], py = casaY[i];
                    int n = px.length;
                    if (minimo > 0 && Math.abs(px[0] - px[n / 2]) < minimo && Math.abs(py[0] - py[n / 2]) < minimo) {
                        continue;
                    }
                    if (n > xs.length) {
                        xs = new int[n];
                        ys = new int[n];
                    }
                    for (int k = 0; k < n; k++) {
                        xs[k] = (int) Math.round(sx(px[k]));
                        ys[k] = (int) Math.round(sy(py[k]));
                    }
                    g.fillPolygon(xs, ys, n);
                    if (conBorde) {
                        g.setColor(BORDE_CASA);
                        g.drawPolygon(xs, ys, n);
                        g.setColor(CASA);
                    }
                }
            }
        }
    }

    private void dibujarFondo(Graphics2D g, AffineTransform t, Rectangle2D visible) {
        if (mar != null) {
            g.setColor(MAR);
            g.fill(t.createTransformedShape(mar)); // relleno con regla nonzero
            g.setColor(new Color(0xB4CDE6));
            g.setStroke(new BasicStroke(1.2f));
            for (Path2D c : costa) {
                g.draw(t.createTransformedShape(c));
            }
        }
        dibujarCasas(g, visible);
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
                        borde = lluvia ? Tema.AVISO_TEXTO : BORDE_DESTAPADA;
                    }
                    g.setColor(borde);
                    g.setStroke(new BasicStroke(ancho + 2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.draw(s);
                } else {
                    Color relleno = imp >= 6 ? TRONCAL : Color.WHITE;
                    if (v.esDestapada()) {
                        relleno = lluvia ? BARRO : DESTAPADA;
                    }
                    if (!detalle && imp <= 2 && !(lluvia && v.esDestapada())) {
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
            g.setColor(Tema.AZUL_PROFUNDO);
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
        double[] acum = animando ? acumuladosDe(ruta) : null;
        if (acum != null && metrosTrazados <= 0) {
            return; // todavia buscando el camino
        }
        Path2D ida = camino(as, 0, corte, acum);
        Path2D vuelta = camino(as, corte, as.size(), acum);
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

        if (acum != null && metrosTrazados < acum[acum.length - 1]) {
            cabeza(g, as, acum, color, ancho, activa);
            return; // las flechas se ponen cuando termina de trazar
        }

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

    /** Une las aristas [desde, hasta). Si acum no es null, corta donde va la animacion. */
    private Path2D camino(List<Arista> as, int desde, int hasta, double[] acum) {
        Path2D p = new Path2D.Double();
        boolean primero = true;
        for (int i = desde; i < hasta; i++) {
            Arista a = as.get(i);
            double inicio = i == 0 || acum == null ? 0 : acum[i - 1];
            if (acum != null && inicio >= metrosTrazados) {
                break;
            }
            if (primero) {
                p.moveTo(sx(grafo.getX(a.getOrigen())), sy(grafo.getY(a.getOrigen())));
                primero = false;
            }
            if (acum != null && acum[i] > metrosTrazados) {
                double[] q = puntoEnArista(a, (metrosTrazados - inicio) / Math.max(1e-9, acum[i] - inicio));
                p.lineTo(sx(q[0]), sy(q[1]));
                break;
            }
            p.lineTo(sx(grafo.getX(a.getDestino())), sy(grafo.getY(a.getDestino())));
        }
        return p;
    }

    private double[] puntoEnArista(Arista a, double f) {
        double x1 = grafo.getX(a.getOrigen()), y1 = grafo.getY(a.getOrigen());
        double x2 = grafo.getX(a.getDestino()), y2 = grafo.getY(a.getDestino());
        return new double[]{x1 + (x2 - x1) * f, y1 + (y2 - y1) * f};
    }

    /** La moto que va al frente de la linea mientras se traza. */
    private void cabeza(Graphics2D g, List<Arista> as, double[] acum, Color color, float ancho, boolean activa) {
        int i = 0;
        while (i < as.size() - 1 && acum[i] < metrosTrazados) {
            i++;
        }
        double inicio = i == 0 ? 0 : acum[i - 1];
        double[] q = puntoEnArista(as.get(i), Math.min(1, (metrosTrazados - inicio) / Math.max(1e-9, acum[i] - inicio)));
        int x = (int) sx(q[0]), y = (int) sy(q[1]);
        int r = (int) (ancho + (activa ? 6 : 4));
        // halo que late
        double pulso = (System.currentTimeMillis() % 700) / 700.0;
        int rh = (int) (r + 10 * pulso);
        g.setColor(Tema.conAlfa(color, (int) (110 * (1 - pulso))));
        g.fillOval(x - rh, y - rh, rh * 2, rh * 2);
        g.setColor(Color.WHITE);
        g.fillOval(x - r - 2, y - r - 2, (r + 2) * 2, (r + 2) * 2);
        g.setColor(color);
        g.fillOval(x - r, y - r, r * 2, r * 2);
    }

    // ------------------------------------------------------------------
    //  Animacion
    // ------------------------------------------------------------------

    /**
     * Muestra las rutas actuales llenandose desde la base.
     * @param orden nodos en el orden en que Dijkstra los visito (null = sin fase de busqueda)
     */
    public void animarRutas(int[] orden, String textoBusqueda) {
        this.textoBusqueda = textoBusqueda;
        animador.stop();
        if (rutas.isEmpty()) {
            return;
        }
        exploracion = orden == null || orden.length == 0 ? null : orden;
        acumulados = new double[rutas.size()][];
        llegadas = new double[rutas.size()][];
        metrosMax = 0;
        for (int r = 0; r < rutas.size(); r++) {
            Ruta ruta = rutas.get(r);
            List<Arista> as = ruta.getAristas();
            double[] acum = new double[as.size()];
            double total = 0;
            for (int i = 0; i < as.size(); i++) {
                Arista a = as.get(i);
                total += Math.hypot(grafo.getX(a.getDestino()) - grafo.getX(a.getOrigen()),
                        grafo.getY(a.getDestino()) - grafo.getY(a.getOrigen()));
                acum[i] = total;
            }
            acumulados[r] = acum;
            // cada parada aparece cuando la linea llega a su esquina (se buscan en orden)
            double[] lleg = new double[ruta.getParadas().size()];
            int desde = 0;
            for (int k = 0; k < lleg.length; k++) {
                int nodo = ruta.getParadas().get(k).getNodo();
                int j = desde;
                while (j < as.size() && as.get(j).getDestino() != nodo) {
                    j++;
                }
                if (j < as.size()) {
                    lleg[k] = acum[j];
                    desde = j + 1;
                } else {
                    lleg[k] = desde == 0 ? 0 : acum[desde - 1];
                }
            }
            llegadas[r] = lleg;
            metrosMax = Math.max(metrosMax, total);
        }
        if (metrosMax <= 0) {
            encuadrarRutas(rutas); // nada que animar: se muestra de una
            return;
        }
        // camara: vista general de todas las rutas y, con una sola moto, una vista mas cercana para seguirla
        vistaGeneral = vistaDeRutas(rutas);
        seguir = false;
        if (vistaGeneral != null && rutas.size() == 1) {
            escalaSeguir = Math.min(1.3, Math.max(vistaGeneral[2] * 2.6, 0.45));
            seguir = escalaSeguir > vistaGeneral[2] * 1.6; // una ruta corta ya se ve bien completa
        }
        // entre 2 y 4,5 segundos; si la camara sigue a la moto, algo mas para que no maree
        msTrazo = seguir ? (int) Math.max(3200, Math.min(7000, metrosMax * 0.75))
                : (int) Math.max(2000, Math.min(4500, metrosMax * 0.45));
        cerrando = false;
        ultimoCuadro = 0;
        metrosTrazados = 0;
        animando = true;
        inicioAnimacion = System.currentTimeMillis();
        animador.start();
        repaint();
    }

    public boolean isAnimando() {
        return animando;
    }

    // ---- camara ----
    private double[] vistaGeneral;  // {x, y, escala} con todas las rutas
    private boolean seguir;         // la camara acompana a la moto (solo con una ruta)
    private double escalaSeguir;
    private boolean cerrando;       // ya se trazo todo; la camara vuelve a la vista general
    private long inicioCierre;
    private boolean ignorarClic;

    private long ultimoCuadro;

    /**
     * La camara se acerca poco a poco a la vista pedida. 'tau' es cuanto tarda (en ms) en recorrer
     * casi dos tercios del camino: se calcula con el tiempo real, asi se mueve igual de suave en un
     * computador rapido o en uno lento.
     */
    private void moverCamara(double x, double y, double s, double tau) {
        long ahora = System.currentTimeMillis();
        long dt = ultimoCuadro == 0 ? 16 : Math.min(100, Math.max(1, ahora - ultimoCuadro));
        double rapidez = 1 - Math.exp(-dt / tau);
        centroX += (x - centroX) * rapidez;
        centroY += (y - centroY) * rapidez;
        // la escala se mueve en logaritmo para que acercar y alejar se sientan igual de suaves
        escala = Math.exp(Math.log(escala) + (Math.log(s) - Math.log(escala)) * rapidez);
    }

    private boolean camaraLlego(double[] v) {
        return Math.hypot(centroX - v[0], centroY - v[1]) * escala < 2 && Math.abs(Math.log(escala / v[2])) < 0.01;
    }

    /** Punto del mapa donde va la moto de la ruta r. */
    private double[] posicionMoto(int r) {
        List<Arista> as = rutas.get(r).getAristas();
        double[] acum = acumulados[r];
        if (as.isEmpty()) {
            return new double[]{centroX, centroY};
        }
        int i = 0;
        while (i < as.size() - 1 && acum[i] < metrosTrazados) {
            i++;
        }
        double inicio = i == 0 ? 0 : acum[i - 1];
        return puntoEnArista(as.get(i), Math.max(0, Math.min(1, (metrosTrazados - inicio) / Math.max(1e-9, acum[i] - inicio))));
    }

    /** Esc o terminar solo: se ve la ruta completa. */
    public void terminarAnimacion() {
        terminarAnimacion(true);
    }

    /** @param verTodo true: la vista salta a la ruta completa; false: se queda donde la dejo el usuario */
    public void terminarAnimacion(boolean verTodo) {
        if (!animando) {
            return;
        }
        if (verTodo && vistaGeneral != null) {
            centroX = vistaGeneral[0];
            centroY = vistaGeneral[1];
            escala = vistaGeneral[2];
        }
        animador.stop();
        animando = false;
        exploracion = null;
        repaint();
    }

    private int msBusqueda() {
        return exploracion == null ? 0 : MS_BUSQUEDA;
    }

    private void avanzarAnimacion() {
        long t = System.currentTimeMillis() - inicioAnimacion - msBusqueda();
        if (cerrando) {
            // al final la camara se aleja hasta ver la ruta completa
            moverCamara(vistaGeneral[0], vistaGeneral[1], vistaGeneral[2], 260);
            if (camaraLlego(vistaGeneral) || System.currentTimeMillis() - inicioCierre > 2500) {
                terminarAnimacion(true);
                return;
            }
        } else if (t <= 0) {
            // mientras Dijkstra busca: vista general, para ver como se abre la busqueda
            metrosTrazados = 0;
            if (vistaGeneral != null) {
                moverCamara(vistaGeneral[0], vistaGeneral[1], vistaGeneral[2], 140);
            }
        } else {
            // arranca suave y frena al final
            double f = Math.min(1, t / (double) msTrazo);
            double suave = f < 0.5 ? 2 * f * f : 1 - Math.pow(-2 * f + 2, 2) / 2;
            metrosTrazados = suave * metrosMax;
            if (seguir) {
                double[] p = posicionMoto(0);
                moverCamara(p[0], p[1], escalaSeguir, 160);
            } else if (vistaGeneral != null) {
                moverCamara(vistaGeneral[0], vistaGeneral[1], vistaGeneral[2], 140);
            }
            if (f >= 1) {
                metrosTrazados = metrosMax;
                if (vistaGeneral != null && !camaraLlego(vistaGeneral)) {
                    cerrando = true;
                    inicioCierre = System.currentTimeMillis();
                } else {
                    terminarAnimacion(true);
                    return;
                }
            }
        }
        ultimoCuadro = System.currentTimeMillis();
        repaint();
    }

    private double[] acumuladosDe(Ruta ruta) {
        if (acumulados == null) {
            return null;
        }
        int r = rutas.indexOf(ruta);
        return r < 0 || r >= acumulados.length ? null : acumulados[r];
    }

    /** True si la parada k de la ruta r ya debe verse. */
    private boolean paradaVisible(int r, int k) {
        if (!animando || llegadas == null || r >= llegadas.length || k >= llegadas[r].length) {
            return true;
        }
        return metrosTrazados > 0 && metrosTrazados >= llegadas[r][k] - 1;
    }

    /** Fase 1: las esquinas que el algoritmo va revisando, mas fuertes las recientes. */
    private void dibujarExploracion(Graphics2D g) {
        if (!animando || exploracion == null) {
            return;
        }
        long t = System.currentTimeMillis() - inicioAnimacion;
        double f = Math.min(1, t / (double) MS_BUSQUEDA);
        // al empezar el trazo los puntos se van apagando
        double apagar = t <= MS_BUSQUEDA ? 1 : Math.max(0, 1 - (t - MS_BUSQUEDA) / 600.0);
        if (apagar <= 0) {
            return;
        }
        int hasta = (int) (f * exploracion.length);
        int frente = Math.max(1, exploracion.length / 12);
        int lado = escala > 1.2 ? 5 : 4;
        for (int i = 0; i < hasta; i++) {
            int n = exploracion[i];
            boolean reciente = hasta - i < frente;
            int alfa = (int) ((reciente ? 230 : 90) * apagar);
            g.setColor(Tema.conAlfa(reciente ? Tema.AZUL_PROFUNDO : Tema.AZUL, alfa));
            int x = (int) sx(grafo.getX(n)), y = (int) sy(grafo.getY(n));
            g.fillOval(x - lado / 2, y - lado / 2, lado, lado);
        }
        if (t <= MS_BUSQUEDA) {
            String texto = textoBusqueda + "  " + hasta + " esquinas revisadas";
            g.setFont(Tema.texto(13));
            FontMetrics fm = g.getFontMetrics();
            int w = fm.stringWidth(texto) + 24;
            int x = (getWidth() - w) / 2, y = 16;
            g.setColor(Tema.conAlfa(Color.WHITE, 235));
            g.fillRoundRect(x, y, w, 30, Tema.ARCO, Tema.ARCO);
            g.setColor(Tema.AZUL);
            g.drawString(texto, x + 12, y + 20);
        }
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
        Font f = Tema.textoFuerte(11).deriveFont(Map.of(TextAttribute.TRACKING, 0.06f));
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
            case RESTAURANTE: return Tema.AVISO_TEXTO;
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
        g.setFont(Tema.texto(11));
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
                    if (!paradaVisible(r, i)) {
                        continue;
                    }
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
            // el cubo va solo, sin circulo ni cuadro (regla del logo); un borde blanco lo separa del mapa
            double alto = 28, ancho = alto * 0.866;
            g.setColor(Color.WHITE);
            g.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(Logo.silueta(x - ancho / 2, y - alto / 2, alto));
            Logo.dibujarCubo(g, x - ancho / 2, y - alto / 2, alto, Logo.Version.COLOR);
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
            g.fillRoundRect(x, 14, w, 34, Tema.ARCO, Tema.ARCO);
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
        g.fillRoundRect(10, y - 18, largo + 20, 28, Tema.ARCO, Tema.ARCO);
        g.setColor(Tema.GRIS_OSCURO);
        g.setStroke(new BasicStroke(2));
        g.drawLine(20, y, 20 + largo, y);
        g.drawLine(20, y - 4, 20, y + 4);
        g.drawLine(20 + largo, y - 4, 20 + largo, y + 4);
        g.setFont(Tema.texto(11));
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
