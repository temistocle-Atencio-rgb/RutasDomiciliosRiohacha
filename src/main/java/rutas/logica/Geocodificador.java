package rutas.logica;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import rutas.estructuras.ArbolAVL;
import rutas.estructuras.Grafo;
import rutas.estructuras.NodoAVL;
import rutas.modelo.Geo;
import rutas.modelo.Lugar;
import rutas.modelo.Parada;
import rutas.modelo.Via;

/**
 * Convierte direcciones colombianas en un punto del mapa.
 * Ejemplos que entiende: "Calle 15 # 10-20", "Cra 7 No. 12 - 30", "Cl 14E 9-15",
 * "Carrera 7 con Calle 15", "Calle 38" o el nombre de un lugar ("Uniguajira").
 *
 * "Calle 15 # 10-20" quiere decir: sobre la Calle 15, a unos 20 m de la esquina
 * con la Carrera 10 (yendo hacia la Carrera 11).
 *
 * Usa arboles AVL:
 *  - calles: nombre normalizado -> tramos de via con ese nombre.
 *  - numeros de calles y de carreras: para cuando una calle no esta en el mapa,
 *    se buscan con piso() y techo() las calles vecinas que si estan y se interpola.
 */
public class Geocodificador {

    public static final double DISTANCIA_ESQUINA = 45; // metros para decir que dos calles se cruzan

    private static final String TIPOS = "calle|cll|clle|cl|carrera|carerra|cra|cr|kra|kr|k|diagonal|diag|dg|transversal|trans|tv|tr|avenida|av";
    // letra de la calle: de la a a la j y que no siga otra letra (para no confundir "con" o "y")
    private static final String LETRA = "(?:([a-j])(?![a-z]))?";
    private static final Pattern DIRECCION = Pattern.compile(
            "^(" + TIPOS + ")\\.?\\s*(\\d+)\\s*" + LETRA + "\\s*(?:bis)?"
            + "(?:\\s*(?:#|no\\.?|nro\\.?|numero|n|con|x|y|-)?\\s*(?:(?:" + TIPOS + ")\\.?\\s*)?"
            + "(\\d+)\\s*" + LETRA + "\\s*(?:bis)?\\s*(?:-|\\s)?\\s*(\\d+)?)?"
            + ".*$");
    // la misma direccion pero en cualquier parte del texto: "La Ancha, centro, calle 7 # 10-02"
    private static final Pattern DIRECCION_EN_TEXTO = Pattern.compile(
            "(?<![a-z])(" + TIPOS + ")\\.?\\s*(\\d+)\\s*" + LETRA + "\\s*(?:bis)?"
            + "(?:\\s*(?:#|no\\.?|nro\\.?|numero|n|con|x|y|-)?\\s*(?:(?:" + TIPOS + ")\\.?\\s*)?"
            + "(\\d+)\\s*" + LETRA + "\\s*(?:bis)?\\s*(?:-|\\s)?\\s*(\\d+)?)?");

    /** Direcciones que el usuario ubico a mano: texto normalizado -> {lat, lon}. */
    private final ArbolAVL<String, double[]> aprendidas = new ArbolAVL<>();

    /** Nombres populares de vias (archivo datos/alias.txt): "la ancha" -> "calle 7". */
    private final java.util.Map<String, String> alias = new java.util.LinkedHashMap<>();

    private final Mapa mapa;
    private final BuscadorLugares lugares;
    private final ArbolAVL<String, List<Integer>> calles = new ArbolAVL<>();
    private final ArbolAVL<String, String> nombresVisibles = new ArbolAVL<>();
    private final ArbolAVL<Double, String> numerosCalle = new ArbolAVL<>();
    private final ArbolAVL<Double, String> numerosCarrera = new ArbolAVL<>();
    private final List<List<Integer>> viasDeNodo = new ArrayList<>();
    private boolean[] alcanzable; // nodos a los que se llega desde la base (BFS)

    /** Resultado de ubicar una direccion. */
    public static class Resultado {
        public final double x, y;
        public final String precision;
        public final String descripcion;
        public final List<Integer> viasPreferidas;

        Resultado(double x, double y, String precision, String descripcion, List<Integer> vias) {
            this.x = x;
            this.y = y;
            this.precision = precision;
            this.descripcion = descripcion;
            this.viasPreferidas = vias;
        }
    }

    public Geocodificador(Mapa mapa, BuscadorLugares lugares) {
        this.mapa = mapa;
        this.lugares = lugares;
        Grafo g = mapa.getGrafo();
        for (int i = 0; i < g.getCantidadNodos(); i++) {
            viasDeNodo.add(new ArrayList<>(2));
        }
        for (Via v : mapa.getVias()) {
            for (int nodo : v.getNodos()) {
                List<Integer> l = viasDeNodo.get(nodo);
                if (!l.contains(v.getIndice())) {
                    l.add(v.getIndice());
                }
            }
            if (!v.getNombreAlterno().isEmpty()) {
                String alt = Geo.normalizar(v.getNombreAlterno());
                List<Integer> l2 = calles.buscar(alt);
                if (l2 == null) {
                    l2 = new ArrayList<>();
                    calles.insertar(alt, l2);
                    nombresVisibles.insertar(alt, v.getNombreAlterno() + " (" + v.getNombre() + ")");
                }
                l2.add(v.getIndice());
            }
            if (!v.tieneNombre()) {
                continue;
            }
            String clave = Geo.normalizar(v.getNombre());
            List<Integer> lista = calles.buscar(clave);
            if (lista == null) {
                lista = new ArrayList<>();
                calles.insertar(clave, lista);
            }
            lista.add(v.getIndice());
            if (!nombresVisibles.contiene(clave)) {
                nombresVisibles.insertar(clave, v.getNombre());
            }
            Object[] partes = partirNombre(clave);
            if (partes != null) {
                ("calle".equals(partes[0]) ? numerosCalle : numerosCarrera).insertar((Double) partes[1], clave);
            }
        }
    }

    /** Lee los nombres populares: una linea por alias con el formato  la ancha=Calle 7 */
    public void cargarAlias(java.io.File archivo) throws java.io.IOException {
        if (archivo == null || !archivo.exists()) {
            return;
        }
        for (String l : CargadorMapa.leer(archivo)) {
            int i = l.indexOf('=');
            if (i > 0) {
                alias.put(Geo.normalizar(l.substring(0, i)), Geo.normalizar(l.substring(i + 1)));
            }
        }
    }

    /** Cambia los nombres populares por el nombre oficial: "la ancha" -> "calle 7". */
    String aplicarAlias(String t) {
        for (java.util.Map.Entry<String, String> e : alias.entrySet()) {
            t = t.replaceAll("(?<![a-z])" + Pattern.quote(e.getKey()) + "(?![a-z])", e.getValue());
        }
        return t;
    }

    /** Recuerda donde queda una direccion (cuando el usuario la corrige en el mapa). */
    public void aprender(String texto, double lat, double lon) {
        aprendidas.insertar(Geo.normalizar(texto), new double[]{lat, lon});
    }

    public void olvidar(String texto) {
        aprendidas.eliminar(Geo.normalizar(texto));
    }

    public ArbolAVL<String, double[]> getAprendidas() {
        return aprendidas;
    }

    public void setAlcanzable(boolean[] alcanzable) {
        this.alcanzable = alcanzable;
    }

    /** "calle 14e" -> {"calle", 14.18}. Diagonales cuentan como calles y transversales como carreras. */
    static Object[] partirNombre(String clave) {
        Matcher m = Pattern.compile("^(calle|carrera|diagonal|transversal) (\\d+) ?([a-z])?$").matcher(clave);
        if (!m.matches()) {
            return null;
        }
        String familia = m.group(1).equals("calle") || m.group(1).equals("diagonal") ? "calle" : "carrera";
        return new Object[]{familia, valor(m.group(2), m.group(3))};
    }

    /** Numero de la calle con su letra: 14 -> 14.0, 14A -> 14.037, 14E -> 14.185 */
    static double valor(String numero, String letra) {
        double v = Integer.parseInt(numero);
        if (letra != null && !letra.isEmpty()) {
            v += (letra.charAt(0) - 'a' + 1) / 27.0;
        }
        return v;
    }

    private static String familia(String tipo) {
        switch (tipo) {
            case "carrera": case "carerra": case "cra": case "cr": case "kra": case "kr": case "k":
            case "transversal": case "trans": case "tv": case "tr":
                return "carrera";
            case "avenida": case "av":
                return "avenida";
            default:
                return "calle";
        }
    }

    // ------------------------------------------------------------------
    //  Busqueda principal
    // ------------------------------------------------------------------

    /** Ubica un texto (direccion o nombre de lugar). Lanza IllegalArgumentException si no se puede. */
    public Parada ubicar(String texto) {
        String t = Geo.normalizar(texto).replace("n°", "#").replace("nº", "#").replace("no.", "#");
        if (t.isEmpty()) {
            throw new IllegalArgumentException("Escriba una dirección o el nombre de un lugar.");
        }
        double[] corregida = aprendidas.buscar(t);
        if (corregida != null) {
            Parada p = new Parada(texto.trim(), texto.trim(), corregida[0], corregida[1]);
            asignarNodo(p, Geo.x(corregida[1]), Geo.y(corregida[0]), null);
            p.setPrecision("Ubicación corregida a mano");
            return p;
        }
        Matcher m = DIRECCION.matcher(t);
        if (m.matches()) {
            Resultado r = ubicarDireccion(m);
            if (r != null) {
                return crearParada(texto.trim(), r);
            }
        }
        // la direccion puede venir con mas cosas: "La Ancha, centro, calle 7 # 10-02"
        Resultado r = buscarDireccionEnTexto(t);
        if (r == null) {
            r = buscarDireccionEnTexto(aplicarAlias(t));
        }
        if (r != null) {
            return crearParada(texto.trim(), r);
        }
        String conAlias = aplicarAlias(t);
        if (!conAlias.equals(t) && calles.buscar(conAlias) != null) {
            t = conAlias; // el texto era solo un nombre popular: "la ancha"
        }
        // nombre de una via completa: "Avenida La Marina", "Vía Riohacha - Maicao"
        List<Integer> vias = calles.buscar(t);
        if (vias != null) {
            double[] p = puntoMedio(vias);
            return crearParada(texto.trim(), new Resultado(p[0], p[1], "Punto medio de la vía", texto, vias));
        }
        // nombre de un lugar
        List<Lugar> encontrados = lugares.buscar(texto, 1);
        if (!encontrados.isEmpty()) {
            Lugar l = encontrados.get(0);
            Parada p = new Parada(l.getNombre(), l.getNombre(), l.getLat(), l.getLon());
            asignarNodo(p, Geo.x(l.getLon()), Geo.y(l.getLat()), null);
            p.setPrecision("Lugar del mapa (" + l.getCategoria() + ")");
            return p;
        }
        throw new IllegalArgumentException("No encontré \"" + texto.trim() + "\" en el mapa de Riohacha.\n"
                + "Pruebe con el formato Calle 15 # 10-20 o con el nombre de un lugar.");
    }

    /**
     * Busca todas las direcciones que aparezcan en el texto y usa la mas completa
     * (la que tiene numero de cruce). Devuelve null si no hay ninguna.
     */
    private Resultado buscarDireccionEnTexto(String t) {
        Matcher m = DIRECCION_EN_TEXTO.matcher(t);
        Resultado soloVia = null;
        while (m.find()) {
            Resultado r = ubicarDireccion(m);
            if (r == null) {
                continue;
            }
            if (m.group(4) != null) {
                return r;
            }
            if (soloVia == null) {
                soloVia = r;
            }
        }
        return soloVia;
    }

    private Parada crearParada(String texto, Resultado r) {
        Parada p = new Parada(texto, texto, Geo.lat(r.y), Geo.lon(r.x));
        asignarNodo(p, r.x, r.y, r.viasPreferidas);
        p.setPrecision(r.precision);
        return p;
    }

    /** Pega la parada al nodo mas cercano, preferiblemente sobre la via de la direccion. */
    public void asignarNodo(Parada p, double x, double y, List<Integer> viasPreferidas) {
        Grafo g = mapa.getGrafo();
        int mejor = -1;
        double mejorD = Double.MAX_VALUE;
        if (viasPreferidas != null) {
            for (int vi : viasPreferidas) {
                for (int nodo : mapa.getVia(vi).getNodos()) {
                    if (alcanzable != null && !alcanzable[nodo]) {
                        continue;
                    }
                    double d = Math.hypot(g.getX(nodo) - x, g.getY(nodo) - y);
                    if (d < mejorD) {
                        mejorD = d;
                        mejor = nodo;
                    }
                }
            }
        }
        if (mejor == -1 || mejorD > 150) {
            mejor = g.nodoMasCercano(x, y, alcanzable);
        }
        p.setNodo(mejor);
    }

    private Resultado ubicarDireccion(Matcher m) {
        String fam = familia(m.group(1));
        double num = valor(m.group(2), m.group(3));
        String nombreP = (fam.equals("avenida") ? "avenida" : fam) + " " + m.group(2) + (m.group(3) == null ? "" : m.group(3));
        List<Integer> viasP = calles.buscar(nombreP);
        if (viasP == null && fam.equals("avenida")) {
            return null;
        }
        String famCruce = fam.equals("calle") ? "carrera" : "calle";

        // solo la via: "Calle 38"
        if (m.group(4) == null) {
            if (viasP == null) {
                return null;
            }
            double[] p = puntoMedio(viasP);
            return new Resultado(p[0], p[1], "Punto medio de la vía", nombreP, viasP);
        }
        double numCruce = valor(m.group(4), m.group(5));
        double placa = m.group(6) == null ? 0 : Integer.parseInt(m.group(6));

        if (viasP != null) {
            Set<Integer> nodosP = nodosDe(viasP);
            double[] esquina = esquina(nodosP, famCruce, numCruce);
            if (esquina == null) {
                String cruce = famCruce + " " + m.group(4) + (m.group(5) == null ? "" : m.group(5).toUpperCase());
                throw new IllegalArgumentException("La " + mayuscula(nombreP) + " existe en el mapa, pero no llega a la "
                        + mayuscula(cruce) + ".\nRevise la dirección o escriba un lugar cercano.");
            }
            String precision = esquina[2] == 1 ? "Exacta (esquina en el mapa)" : "Aproximada (esquina estimada)";
            if (placa > 0) {
                // se avanza "placa" metros hacia la siguiente esquina
                double[] siguiente = esquinaVecina(nodosP, famCruce, numCruce, true);
                double signo = 1;
                if (siguiente == null) {
                    siguiente = esquinaVecina(nodosP, famCruce, numCruce, false);
                    signo = -1;
                }
                if (siguiente != null) {
                    double dx = siguiente[0] - esquina[0], dy = siguiente[1] - esquina[1];
                    double largo = Math.hypot(dx, dy);
                    if (largo > 1) {
                        double avance = Math.min(placa, largo * 0.95) * signo;
                        esquina[0] += dx / largo * avance;
                        esquina[1] += dy / largo * avance;
                    }
                }
            }
            return new Resultado(esquina[0], esquina[1], precision, nombreP, viasP);
        }

        // la via principal no esta en el mapa: se ubica sobre la via del cruce
        String nombreC = famCruce + " " + m.group(4) + (m.group(5) == null ? "" : m.group(5));
        List<Integer> viasC = calles.buscar(nombreC);
        if (viasC == null) {
            return null;
        }
        double[] p = esquina(nodosDe(viasC), fam, num);
        if (p == null) {
            return null;
        }
        return new Resultado(p[0], p[1], "Aproximada (" + nombreP + " no tiene nombre en el mapa)", nombreP, viasC);
    }

    // ------------------------------------------------------------------
    //  Esquinas
    // ------------------------------------------------------------------

    /**
     * Punto donde la via (nodosP) se cruza con la calle/carrera numero "numero".
     * Devuelve {x, y, exacta(1/0)} o null.
     */
    double[] esquina(Set<Integer> nodosP, String famCruce, double numero) {
        ArbolAVL<Double, String> numeros = famCruce.equals("calle") ? numerosCalle : numerosCarrera;
        NodoAVL<Double, String> exacta = numeros.piso(numero);
        if (exacta != null && Math.abs(exacta.getClave() - numero) < 1e-9) {
            double[] p = cruce(nodosP, calles.buscar(exacta.getValor()));
            if (p != null) {
                return new double[]{p[0], p[1], 1};
            }
        }
        // la calle del cruce no esta o no toca la via: se interpola entre las vecinas
        double[] abajo = buscarCruce(nodosP, numeros, numero, false);
        double[] arriba = buscarCruce(nodosP, numeros, numero, true);
        if (abajo != null && arriba != null) {
            double f = (numero - abajo[2]) / (arriba[2] - abajo[2]);
            return new double[]{abajo[0] + (arriba[0] - abajo[0]) * f, abajo[1] + (arriba[1] - abajo[1]) * f, 0};
        }
        double[] una = abajo != null ? abajo : arriba;
        return una == null ? null : new double[]{una[0], una[1], 0};
    }

    /** Siguiente (o anterior) esquina sobre la via, para saber hacia donde crece la placa. */
    private double[] esquinaVecina(Set<Integer> nodosP, String famCruce, double numero, boolean haciaArriba) {
        ArbolAVL<Double, String> numeros = famCruce.equals("calle") ? numerosCalle : numerosCarrera;
        double inicio = haciaArriba ? numero + 0.01 : numero - 0.01;
        return buscarCruce(nodosP, numeros, inicio, haciaArriba);
    }

    /**
     * Recorre el AVL de numeros con piso()/techo() desde "numero" hacia abajo o hacia arriba
     * hasta encontrar una calle que si se cruce con la via. Devuelve {x, y, numero}.
     */
    private double[] buscarCruce(Set<Integer> nodosP, ArbolAVL<Double, String> numeros, double numero, boolean haciaArriba) {
        double actual = numero;
        for (int intentos = 0; intentos < 8; intentos++) {
            NodoAVL<Double, String> nodo = haciaArriba ? numeros.techo(actual) : numeros.piso(actual);
            if (nodo == null || Math.abs(nodo.getClave() - numero) > 6) {
                return null;
            }
            double[] p = cruce(nodosP, calles.buscar(nodo.getValor()));
            if (p != null) {
                return new double[]{p[0], p[1], nodo.getClave()};
            }
            actual = haciaArriba ? nodo.getClave() + 0.001 : nodo.getClave() - 0.001;
        }
        return null;
    }

    /** Punto donde se tocan dos vias (el par de nodos mas cercano), o null si no se tocan. */
    private double[] cruce(Set<Integer> nodosP, List<Integer> viasCruce) {
        if (viasCruce == null) {
            return null;
        }
        Grafo g = mapa.getGrafo();
        double mejorD = Double.MAX_VALUE;
        int mejor = -1;
        for (int vi : viasCruce) {
            for (int c : mapa.getVia(vi).getNodos()) {
                for (int p : nodosP) {
                    double d = g.distanciaPlano(c, p);
                    if (d < mejorD) {
                        mejorD = d;
                        mejor = p;
                    }
                }
            }
        }
        if (mejorD > DISTANCIA_ESQUINA) {
            return null;
        }
        return new double[]{g.getX(mejor), g.getY(mejor)};
    }

    private static String mayuscula(String s) {
        String r = s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
        // la letra de la calle va en mayuscula: "calle 14e" -> "Calle 14E"
        return r.replaceAll("(\\d)([a-z])$", "$1") + (r.matches(".*\\d[a-z]$") ? r.substring(r.length() - 1).toUpperCase() : "");
    }

    private Set<Integer> nodosDe(List<Integer> vias) {
        Set<Integer> s = new HashSet<>();
        for (int vi : vias) {
            for (int nodo : mapa.getVia(vi).getNodos()) {
                s.add(nodo);
            }
        }
        return s;
    }

    private double[] puntoMedio(List<Integer> vias) {
        Grafo g = mapa.getGrafo();
        // el nodo de la via mas cercano al centro de todos sus nodos
        Set<Integer> nodos = nodosDe(vias);
        double cx = 0, cy = 0;
        for (int n : nodos) {
            cx += g.getX(n);
            cy += g.getY(n);
        }
        cx /= nodos.size();
        cy /= nodos.size();
        int mejor = -1;
        double mejorD = Double.MAX_VALUE;
        for (int n : nodos) {
            double d = Math.hypot(g.getX(n) - cx, g.getY(n) - cy);
            if (d < mejorD) {
                mejorD = d;
                mejor = n;
            }
        }
        return new double[]{g.getX(mejor), g.getY(mejor)};
    }

    // ------------------------------------------------------------------
    //  Direccion aproximada de un punto (al hacer clic en el mapa)
    // ------------------------------------------------------------------

    /** Describe un nodo como direccion: "Calle 15 # 7-40 (aprox.)" o "cerca a Calle 15". */
    public String describir(int nodo) {
        Grafo g = mapa.getGrafo();
        Via principal = null;
        for (int vi : viasDeNodo.get(nodo)) {
            Via v = mapa.getVia(vi);
            if (v.tieneNombre()) {
                principal = v;
                break;
            }
        }
        if (principal == null) {
            // la via no tiene nombre: se busca la calle con nombre mas cercana
            int cerca = g.nodoMasCercano(g.getX(nodo), g.getY(nodo), nodosConNombre());
            if (cerca == -1) {
                return "Punto en el mapa";
            }
            for (int vi : viasDeNodo.get(cerca)) {
                if (mapa.getVia(vi).tieneNombre()) {
                    return "Cerca a " + mapa.getVia(vi).getNombre();
                }
            }
            return "Punto en el mapa";
        }
        Object[] partesP = partirNombre(Geo.normalizar(principal.getNombre()));
        if (partesP == null) {
            return principal.getNombre();
        }
        String famCruce = partesP[0].equals("calle") ? "carrera" : "calle";
        // esquina con nombre mas cercana sobre la misma via
        Set<Integer> nodosP = nodosDe(calles.buscar(Geo.normalizar(principal.getNombre())));
        String mejorCruce = null;
        double mejorD = Double.MAX_VALUE;
        for (int p : nodosP) {
            double d = g.distanciaPlano(p, nodo);
            if (d > 250 || d >= mejorD) {
                continue;
            }
            for (int vi : viasDeNodo.get(p)) {
                Via v = mapa.getVia(vi);
                Object[] partes = v.tieneNombre() ? partirNombre(Geo.normalizar(v.getNombre())) : null;
                if (partes != null && partes[0].equals(famCruce)) {
                    mejorD = d;
                    mejorCruce = v.getNombre();
                }
            }
        }
        if (mejorCruce == null) {
            return principal.getNombre();
        }
        String numeroCruce = mejorCruce.replaceAll("^\\D+", "");
        return principal.getNombre() + " # " + numeroCruce + " - " + Math.round(mejorD) + " (aprox.)";
    }

    private boolean[] conNombre;

    private boolean[] nodosConNombre() {
        if (conNombre == null) {
            conNombre = new boolean[mapa.getGrafo().getCantidadNodos()];
            for (Via v : mapa.getVias()) {
                if (v.tieneNombre()) {
                    for (int n : v.getNodos()) {
                        conNombre[n] = true;
                    }
                }
            }
        }
        return conNombre;
    }

    /** Nombres de calles que empiezan por el texto (busqueda en el AVL). */
    public List<String> buscarCalles(String texto, int limite) {
        return nombresVisibles.buscarPorPrefijo(Geo.normalizar(texto), limite);
    }

    /** Tramos de via que tienen ese nombre (o null). */
    public List<Integer> viasConNombre(String nombre) {
        return calles.buscar(Geo.normalizar(nombre));
    }

    public List<Integer> viasDeNodo(int nodo) {
        return viasDeNodo.get(nodo);
    }

    public int getCantidadCalles() {
        return calles.getTamano();
    }

    public int getAlturaIndiceCalles() {
        return calles.getAltura();
    }
}
