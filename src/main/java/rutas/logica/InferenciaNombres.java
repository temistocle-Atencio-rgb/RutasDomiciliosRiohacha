package rutas.logica;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.List;
import rutas.estructuras.Cola;
import rutas.estructuras.Grafo;
import rutas.modelo.Geo;
import rutas.modelo.Via;

/**
 * Deduce el nombre de los tramos de via que no tienen nombre en OpenStreetMap.
 *
 * En OSM una calle larga esta partida en muchos tramos y muchas veces solo
 * algunos tienen el nombre. Si un tramo sin nombre sigue en linea recta a un
 * tramo que se llama "Calle 15", casi seguro tambien es la Calle 15.
 * El nombre se propaga con un recorrido en anchura (BFS) sobre los tramos:
 * cada tramo que recibe nombre entra a la cola para pasarlo a sus vecinos.
 */
public final class InferenciaNombres {

    /** Diferencia maxima (grados) para considerar que un tramo sigue derecho al otro. */
    private static final double TOLERANCIA = 22;

    private InferenciaNombres() {
    }

    /**
     * Aplica las dos tecnicas varias veces hasta que ya no se deduzcan mas nombres.
     * Devuelve cuantos tramos recibieron nombre.
     */
    public static int aplicar(Grafo g, List<Via> vias) {
        int total = 0;
        for (int ronda = 0; ronda < 4; ronda++) {
            int nuevos = porContinuidad(g, vias) + porConteoDeCuadras(g, vias);
            total += nuevos;
            if (nuevos == 0) {
                break;
            }
        }
        return total;
    }

    /** Tramos sin nombre que siguen en linea recta a uno con nombre reciben ese nombre. */
    static int porContinuidad(Grafo g, List<Via> vias) {
        // tramos que empiezan o terminan en cada nodo
        List<List<Integer>> extremos = new ArrayList<>(g.getCantidadNodos());
        for (int i = 0; i < g.getCantidadNodos(); i++) {
            extremos.add(null);
        }
        for (Via v : vias) {
            int[] ns = v.getNodos();
            for (int e : new int[]{ns[0], ns[ns.length - 1]}) {
                if (extremos.get(e) == null) {
                    extremos.set(e, new ArrayList<>(3));
                }
                extremos.get(e).add(v.getIndice());
            }
        }

        Cola cola = new Cola();
        for (Via v : vias) {
            if (v.tieneNombre() && esCalleOCarrera(v.getNombre())) {
                cola.encolar(v.getIndice());
            }
        }
        int nombrados = 0;
        while (!cola.estaVacia()) {
            Via v = vias.get(cola.desencolar());
            int[] ns = v.getNodos();
            for (int lado = 0; lado < 2; lado++) {
                int e = lado == 0 ? ns[0] : ns[ns.length - 1];
                double rumboV = rumboDesde(g, v, e);
                // el tramo sin nombre que mejor sigue derecho desde este extremo
                Via mejor = null;
                double mejorDif = TOLERANCIA;
                for (int otra : extremos.get(e)) {
                    Via u = vias.get(otra);
                    if (u == v || u.tieneNombre() || !esVehicular(u) || !esRecto(g, u)) {
                        continue;
                    }
                    double dif = Math.abs(180 - diferencia(rumboV, rumboDesde(g, u, e)));
                    if (dif < mejorDif) {
                        mejorDif = dif;
                        mejor = u;
                    }
                }
                if (mejor != null && !hayOtraConNombreDerecho(g, vias, extremos.get(e), v, e, rumboV)) {
                    mejor.setNombreDeducido(v.getNombre());
                    nombrados++;
                    cola.encolar(mejor.getIndice());
                }
            }
        }
        return nombrados;
    }

    /** Si otra via con nombre ya sigue derecho desde ese extremo, no se inventa nada. */
    private static boolean hayOtraConNombreDerecho(Grafo g, List<Via> vias, List<Integer> enNodo, Via v, int e,
                                                   double rumboV) {
        for (int otra : enNodo) {
            Via u = vias.get(otra);
            if (u != v && u.tieneNombre() && !u.isNombreDeducido()
                    && Math.abs(180 - diferencia(rumboV, rumboDesde(g, u, e))) < TOLERANCIA) {
                return true;
            }
        }
        return false;
    }

    static boolean esCalleOCarrera(String nombre) {
        return Geo.normalizar(nombre).matches("^(calle|carrera|diagonal|transversal) \\d+ ?[a-z]?$");
    }

    /** Solo calles de ciudad: sin vias de servicio, trochas ni carreteras con curvas. */
    private static boolean esVehicular(Via v) {
        return !v.getTipo().equals("service") && !v.getTipo().equals("track") && !v.esDestapada();
    }

    /** Un tramo de la cuadricula es casi recto: largo real / distancia entre extremos < 1.12 */
    private static boolean esRecto(Grafo g, Via v) {
        int[] ns = v.getNodos();
        double real = 0;
        for (int i = 0; i < ns.length - 1; i++) {
            real += g.distanciaPlano(ns[i], ns[i + 1]);
        }
        double recta = g.distanciaPlano(ns[0], ns[ns.length - 1]);
        return recta > 1 && real / recta < 1.12;
    }

    /** Rumbo del primer pedazo del tramo saliendo desde el extremo e. */
    private static double rumboDesde(Grafo g, Via v, int e) {
        int[] ns = v.getNodos();
        int a = e, b;
        if (ns[0] == e) {
            b = ns[Math.min(2, ns.length - 1)];
        } else {
            b = ns[Math.max(0, ns.length - 3)];
        }
        return Geo.rumbo(g.getX(a), g.getY(a), g.getX(b), g.getY(b));
    }

    /** Diferencia entre dos rumbos, de 0 a 180 grados. */
    private static double diferencia(double r1, double r2) {
        double d = Math.abs(r1 - r2) % 360;
        return d > 180 ? 360 - d : d;
    }

    // ------------------------------------------------------------------
    //  Conteo de cuadras
    // ------------------------------------------------------------------

    private static final Pattern NOMBRE = Pattern.compile("^(calle|carrera|diagonal|transversal) (\\d+) ?([a-z])?$");
    private static final double CELDA = 100;
    private static final double ALCANCE = 900; // metros que se mira a cada lado

    /**
     * Para un tramo sin nombre se traza una linea perpendicular desde su punto medio
     * y se miran las calles paralelas que corta. Si a un lado esta la Calle 10, al otro
     * la Calle 13 y en medio hay exactamente dos paralelas (contando esta), esas son la
     * 11 y la 12. Si hay calles con letra (11A, 11B) el conteo no cuadra y no se adivina.
     */
    static int porConteoDeCuadras(Grafo g, List<Via> vias) {
        // indice de segmentos por celdas para no revisar toda la ciudad en cada linea
        Map<Long, List<int[]>> celdas = new HashMap<>();
        for (Via v : vias) {
            int[] ns = v.getNodos();
            for (int i = 0; i < ns.length - 1; i++) {
                int a = ns[i], b = ns[i + 1];
                int cx0 = (int) Math.floor(Math.min(g.getX(a), g.getX(b)) / CELDA);
                int cx1 = (int) Math.floor(Math.max(g.getX(a), g.getX(b)) / CELDA);
                int cy0 = (int) Math.floor(Math.min(g.getY(a), g.getY(b)) / CELDA);
                int cy1 = (int) Math.floor(Math.max(g.getY(a), g.getY(b)) / CELDA);
                for (int cx = cx0; cx <= cx1; cx++) {
                    for (int cy = cy0; cy <= cy1; cy++) {
                        celdas.computeIfAbsent(clave(cx, cy), k -> new ArrayList<>()).add(new int[]{v.getIndice(), a, b});
                    }
                }
            }
        }

        // se decide primero y se aplica al final, para que el orden no afecte
        Map<Integer, String> decididos = new HashMap<>();
        for (Via u : vias) {
            if (u.tieneNombre() || !esVehicular(u) || !esRecto(g, u)) {
                continue;
            }
            int[] ns = u.getNodos();
            int a = ns[0], b = ns[ns.length - 1];
            double dx = g.getX(b) - g.getX(a), dy = g.getY(b) - g.getY(a);
            double largo = Math.hypot(dx, dy);
            if (largo < 40) {
                continue;
            }
            double ux = dx / largo, uy = dy / largo;     // direccion del tramo
            double px = -uy, py = ux;                    // perpendicular
            int medio = ns[ns.length / 2];
            double mx = g.getX(medio), my = g.getY(medio);

            List<double[]> cortes = cortesParalelos(g, vias, celdas, mx, my, px, py, ux, uy);
            String nombre = deducirPorConteo(vias, cortes, u.getIndice());
            if (nombre != null) {
                decididos.put(u.getIndice(), nombre);
            }
        }
        for (Map.Entry<Integer, String> e : decididos.entrySet()) {
            vias.get(e.getKey()).setNombreDeducido(e.getValue());
        }
        return decididos.size();
    }

    private static long clave(long cx, long cy) {
        return (cx << 32) ^ (cy & 0xffffffffL);
    }

    /**
     * Vias casi paralelas a (ux, uy) que corta la linea M + t*(px, py), con t entre -ALCANCE y ALCANCE.
     * Devuelve pares {t, indiceVia} ordenados por t, sin repetir la misma via seguida.
     */
    private static List<double[]> cortesParalelos(Grafo g, List<Via> vias, Map<Long, List<int[]>> celdas,
                                                  double mx, double my, double px, double py, double ux, double uy) {
        List<double[]> cortes = new ArrayList<>();
        java.util.Set<Long> vistas = new java.util.HashSet<>();
        for (double t = -ALCANCE; t <= ALCANCE; t += CELDA / 2) {
            long c = clave((long) Math.floor((mx + t * px) / CELDA), (long) Math.floor((my + t * py) / CELDA));
            if (!vistas.add(c) || !celdas.containsKey(c)) {
                continue;
            }
            for (int[] seg : celdas.get(c)) {
                double x1 = g.getX(seg[1]), y1 = g.getY(seg[1]), x2 = g.getX(seg[2]), y2 = g.getY(seg[2]);
                double sx = x2 - x1, sy = y2 - y1, ls = Math.hypot(sx, sy);
                if (ls < 1) {
                    continue;
                }
                // solo segmentos casi paralelos al tramo
                if (Math.abs(sx / ls * ux + sy / ls * uy) < Math.cos(Math.toRadians(25))) {
                    continue;
                }
                // interseccion de la linea M + t*p con el segmento
                double den = px * sy - py * sx;
                if (Math.abs(den) < 1e-9) {
                    continue;
                }
                double tt = ((x1 - mx) * sy - (y1 - my) * sx) / den;
                double s = ((x1 - mx) * py - (y1 - my) * px) / den;
                if (s >= -0.001 && s <= 1.001 && Math.abs(tt) <= ALCANCE) {
                    cortes.add(new double[]{tt, seg[0]});
                }
            }
        }
        cortes.sort((p, q) -> Double.compare(p[0], q[0]));
        // quita repetidos: misma via o cortes a menos de 15 m (dos tramos de la misma calle)
        List<double[]> limpios = new ArrayList<>();
        for (double[] c : cortes) {
            if (!limpios.isEmpty()) {
                double[] ult = limpios.get(limpios.size() - 1);
                if (ult[1] == c[1] || c[0] - ult[0] < 15) {
                    // si uno de los dos tiene nombre, se queda ese
                    if (!vias.get((int) ult[1]).tieneNombre() && vias.get((int) c[1]).tieneNombre()) {
                        limpios.set(limpios.size() - 1, c);
                    }
                    continue;
                }
            }
            limpios.add(c);
        }
        return limpios;
    }

    /** Busca la via con nombre mas cercana a cada lado y revisa si el conteo de cuadras cuadra. */
    private static String deducirPorConteo(List<Via> vias, List<double[]> cortes, int propia) {
        int pos = -1;
        for (int i = 0; i < cortes.size(); i++) {
            if ((int) cortes.get(i)[1] == propia) {
                pos = i;
                break;
            }
        }
        if (pos < 0) {
            return null;
        }
        int[] antes = conNombre(vias, cortes, pos, -1);
        int[] despues = conNombre(vias, cortes, pos, +1);
        if (antes == null || despues == null) {
            return null;
        }
        // antes/despues = {posicion en cortes, numero, familia (0 calle, 1 carrera)}
        if (antes[2] != despues[2]) {
            return null;
        }
        int diferencia = despues[1] - antes[1];
        int entre = despues[0] - antes[0] - 1; // paralelas entre las dos con nombre
        if (diferencia == 0 || Math.abs(diferencia) - 1 != entre) {
            return null; // hay calles con letra o el conteo no cuadra: mejor no adivinar
        }
        int paso = Integer.signum(diferencia);
        int numero = antes[1] + paso * (pos - antes[0]);
        return (antes[2] == 0 ? "Calle " : "Carrera ") + numero;
    }

    /** Primera via con nombre de calle o carrera SIN letra hacia un lado. {posicion, numero, familia} */
    private static int[] conNombre(List<Via> vias, List<double[]> cortes, int desde, int direccion) {
        for (int i = desde + direccion; i >= 0 && i < cortes.size(); i += direccion) {
            Via v = vias.get((int) cortes.get(i)[1]);
            if (!v.tieneNombre()) {
                continue;
            }
            Matcher m = NOMBRE.matcher(Geo.normalizar(v.getNombre()));
            if (!m.matches() || m.group(3) != null) {
                return null; // calle con letra o via con otro nombre: no se puede contar
            }
            int familia = m.group(1).equals("calle") || m.group(1).equals("diagonal") ? 0 : 1;
            return new int[]{i, Integer.parseInt(m.group(2)), familia};
        }
        return null;
    }
}
