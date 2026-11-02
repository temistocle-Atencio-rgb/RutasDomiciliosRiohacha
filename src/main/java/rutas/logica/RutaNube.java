package rutas.logica;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import rutas.estructuras.Arista;
import rutas.estructuras.Grafo;
import rutas.modelo.Geo;
import rutas.modelo.Via;

/**
 * Lo que la página del repartidor necesita para dibujar la ruta sin pedirle nada a nadie:
 * la línea por las calles (la misma que calculó el Dijkstra) y las calles de alrededor, para
 * pegar la moto a la calle cuando el GPS se mueve un poco o el repartidor se desvía.
 */
public final class RutaNube {

    /** Lo que acepta Supabase (ver en-vivo.sql). */
    public static final int MAX_PUNTOS = 5000;
    public static final int MAX_CALLES = 2000;
    /** Puntos en total de las calles de alrededor (para que la subida no pese mucho). */
    static final int MAX_PUNTOS_CALLES = 20000;
    /** Calles a menos de esto de la ruta. */
    static final double RADIO_CALLES_M = 200;
    /** Al simplificar la línea, ningún punto se corre más de esto. */
    static final double TOLERANCIA_M = 2;

    private RutaNube() {
    }

    /** Puntos (lat, lon) de una lista de nodos del grafo, sin repetidos seguidos y simplificada. */
    public static List<double[]> puntos(Grafo g, List<Integer> nodos) {
        List<double[]> p = new ArrayList<>();
        for (int n : nodos) {
            if (n < 0 || n >= g.getCantidadNodos()) {
                continue;
            }
            double[] q = {g.getLat(n), g.getLon(n)};
            if (p.isEmpty() || p.get(p.size() - 1)[0] != q[0] || p.get(p.size() - 1)[1] != q[1]) {
                p.add(q);
            }
        }
        List<double[]> s = simplificar(p, TOLERANCIA_M);
        double tol = TOLERANCIA_M;
        while (s.size() > MAX_PUNTOS) { // una ruta enorme: se simplifica un poco más
            tol *= 2;
            s = simplificar(p, tol);
        }
        return s;
    }

    /**
     * Para volver a mandar una salida (o cuando no se tiene la ruta a mano): une la tienda y las
     * paradas en orden con el Dijkstra. Las paradas sin camino se saltan.
     */
    public static List<Integer> nodosEnOrden(Grafo g, ModeloTrafico trafico, int base, List<Integer> paradas) {
        List<Integer> r = new ArrayList<>();
        int desde = base;
        if (desde >= 0) {
            r.add(desde);
        }
        for (int destino : paradas) {
            if (destino < 0 || destino == desde) {
                continue;
            }
            if (desde < 0) {
                desde = destino;
                r.add(destino);
                continue;
            }
            Dijkstra d = new Dijkstra(g, desde, trafico, Criterio.MAS_RAPIDA, LocalTime.now());
            if (!d.hayCamino(destino)) {
                continue;
            }
            for (Arista a : d.camino(destino)) {
                r.add(a.getDestino());
            }
            desde = destino;
        }
        return r;
    }

    /** Largo de la línea en metros. */
    public static double metros(List<double[]> p) {
        double m = 0;
        for (int i = 1; i < p.size(); i++) {
            m += Geo.haversine(p.get(i - 1)[0], p.get(i - 1)[1], p.get(i)[0], p.get(i)[1]);
        }
        return m;
    }

    /** Las calles que pasan cerca de la ruta, cada una como lista de puntos; primero las más cercanas. */
    public static List<List<double[]>> calles(Grafo g, List<Via> vias, List<double[]> ruta) {
        List<List<double[]>> r = new ArrayList<>();
        if (ruta.isEmpty()) {
            return r;
        }
        // celdas de 100 m que toca la ruta (se rellena cada 50 m para no saltarse tramos largos)
        double celda = 100;
        Set<Long> cerca = new HashSet<>();
        int vecinas = (int) Math.ceil(RADIO_CALLES_M / celda);
        for (int i = 0; i < ruta.size(); i++) {
            double[] a = ruta.get(i);
            double[] b = i + 1 < ruta.size() ? ruta.get(i + 1) : a;
            double ax = Geo.x(a[1]), ay = Geo.y(a[0]), bx = Geo.x(b[1]), by = Geo.y(b[0]);
            int pasos = Math.max(1, (int) Math.ceil(Math.hypot(bx - ax, by - ay) / 50));
            for (int k = 0; k <= pasos; k++) {
                double x = ax + (bx - ax) * k / pasos, y = ay + (by - ay) * k / pasos;
                long cx = (long) Math.floor(x / celda), cy = (long) Math.floor(y / celda);
                for (long dx = -vecinas; dx <= vecinas; dx++) {
                    for (long dy = -vecinas; dy <= vecinas; dy++) {
                        cerca.add(clave(cx + dx, cy + dy));
                    }
                }
            }
        }
        // cuántas celdas de la ruta tiene cerca cada calle (para quedarse con las más cercanas si son muchas)
        List<Object[]> candidatas = new ArrayList<>(); // {puntos, nodos dentro}
        for (Via v : vias) {
            int[] ns = v.getNodos();
            int dentro = 0;
            for (int n : ns) {
                if (n >= 0 && n < g.getCantidadNodos()
                        && cerca.contains(clave((long) Math.floor(g.getX(n) / celda), (long) Math.floor(g.getY(n) / celda)))) {
                    dentro++;
                }
            }
            if (dentro == 0) {
                continue;
            }
            List<Integer> lista = new ArrayList<>();
            for (int n : ns) {
                lista.add(n);
            }
            List<double[]> pts = puntos(g, lista);
            if (pts.size() >= 2) {
                candidatas.add(new Object[]{pts, dentro});
            }
        }
        candidatas.sort((x, y) -> Integer.compare((int) y[1], (int) x[1]));
        int total = 0;
        for (Object[] c : candidatas) {
            @SuppressWarnings("unchecked")
            List<double[]> pts = (List<double[]>) c[0];
            if (r.size() >= MAX_CALLES || total + pts.size() > MAX_PUNTOS_CALLES) {
                break;
            }
            r.add(pts);
            total += pts.size();
        }
        return r;
    }

    /** Para Json.escribir: [[lat, lon], ...] con 6 decimales (unos 10 cm). */
    public static List<List<Double>> comoJson(List<double[]> p) {
        List<List<Double>> r = new ArrayList<>();
        for (double[] q : p) {
            r.add(List.of(redondo(q[0]), redondo(q[1])));
        }
        return r;
    }

    public static List<List<List<Double>>> callesComoJson(List<List<double[]>> calles) {
        List<List<List<Double>>> r = new ArrayList<>();
        for (List<double[]> c : calles) {
            r.add(comoJson(c));
        }
        return r;
    }

    static double redondo(double v) {
        return Math.round(v * 1e6) / 1e6;
    }

    private static long clave(long x, long y) {
        return (x << 32) ^ (y & 0xffffffffL);
    }

    /** Douglas–Peucker en metros (sin recursión, para rutas largas). */
    static List<double[]> simplificar(List<double[]> p, double tolerancia) {
        int n = p.size();
        if (n <= 2) {
            return new ArrayList<>(p);
        }
        double[] xs = new double[n], ys = new double[n];
        for (int i = 0; i < n; i++) {
            xs[i] = Geo.x(p.get(i)[1]);
            ys[i] = Geo.y(p.get(i)[0]);
        }
        boolean[] queda = new boolean[n];
        queda[0] = true;
        queda[n - 1] = true;
        java.util.ArrayDeque<int[]> pila = new java.util.ArrayDeque<>();
        pila.push(new int[]{0, n - 1});
        while (!pila.isEmpty()) {
            int[] t = pila.pop();
            int a = t[0], b = t[1];
            double mayor = -1;
            int cual = -1;
            for (int i = a + 1; i < b; i++) {
                double d = distanciaASegmento(xs[i], ys[i], xs[a], ys[a], xs[b], ys[b]);
                if (d > mayor) {
                    mayor = d;
                    cual = i;
                }
            }
            if (cual >= 0 && mayor > tolerancia) {
                queda[cual] = true;
                pila.push(new int[]{a, cual});
                pila.push(new int[]{cual, b});
            }
        }
        List<double[]> r = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (queda[i]) {
                r.add(p.get(i));
            }
        }
        return r;
    }

    static double distanciaASegmento(double px, double py, double ax, double ay, double bx, double by) {
        double dx = bx - ax, dy = by - ay;
        double l2 = dx * dx + dy * dy;
        double t = l2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / l2));
        return Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
    }
}
