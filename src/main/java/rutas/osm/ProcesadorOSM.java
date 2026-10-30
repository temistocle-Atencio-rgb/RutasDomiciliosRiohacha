package rutas.osm;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Convierte las respuestas de OpenStreetMap (JSON de la API Overpass) en los
 * archivos de texto que lee el programa: nodos.txt, vias.txt, lugares.txt y costa.txt.
 */
public final class ProcesadorOSM {

    private static final String FUENTE = "Fuente: OpenStreetMap (c) colaboradores de OpenStreetMap, licencia ODbL";

    private static final Map<String, String> CATEGORIAS = new HashMap<>();

    static {
        String[][] c = {
            {"restaurant", "RESTAURANTE"}, {"fast_food", "RESTAURANTE"}, {"cafe", "RESTAURANTE"}, {"bar", "RESTAURANTE"},
            {"ice_cream", "RESTAURANTE"}, {"food_court", "RESTAURANTE"},
            {"pharmacy", "DROGUERIA"}, {"chemist", "DROGUERIA"},
            {"hospital", "SALUD"}, {"clinic", "SALUD"}, {"doctors", "SALUD"}, {"dentist", "SALUD"},
            {"convenience", "TIENDA"}, {"supermarket", "TIENDA"}, {"bakery", "TIENDA"}, {"alcohol", "TIENDA"},
            {"mall", "TIENDA"}, {"butcher", "TIENDA"}, {"greengrocer", "TIENDA"}, {"beverages", "TIENDA"},
            {"school", "EDUCACION"}, {"university", "EDUCACION"}, {"college", "EDUCACION"},
            {"kindergarten", "EDUCACION"}, {"library", "EDUCACION"}, {"bank", "BANCO"}, {"atm", "BANCO"},
            {"fuel", "GASOLINERA"}, {"hotel", "HOTEL"}, {"hostel", "HOTEL"}, {"guest_house", "HOTEL"},
            {"neighbourhood", "BARRIO"}, {"suburb", "BARRIO"}, {"quarter", "BARRIO"},
            {"townhall", "GOBIERNO"}, {"police", "GOBIERNO"}, {"courthouse", "GOBIERNO"},
        };
        for (String[] x : c) {
            CATEGORIAS.put(x[0], x[1]);
        }
    }

    private ProcesadorOSM() {
    }

    /** Resumen de lo procesado, para mostrarlo al usuario. */
    public static class Resumen {
        public int nodos, vias, lugares, semaforos;

        @Override
        public String toString() {
            return String.format("%,d nodos, %,d tramos de vía, %,d lugares, %d semáforos", nodos, vias, lugares, semaforos);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> elementos(String json) {
        Map<String, Object> raiz = (Map<String, Object>) Json.leer(json);
        Object e = raiz.get("elements");
        return e == null ? new ArrayList<>() : (List<Map<String, Object>>) (List<?>) e;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> tags(Map<String, Object> e) {
        Object t = e.get("tags");
        return t == null ? new HashMap<>() : (Map<String, String>) (Map<?, ?>) t;
    }

    private static long id(Map<String, Object> e) {
        return ((Double) e.get("id")).longValue();
    }

    private static String limpio(String s) {
        return s == null ? "" : s.replace(";", ",").replace("|", "/").trim();
    }

    private static Character marca(Map<String, String> t) {
        if ("traffic_signals".equals(t.get("highway"))) {
            return 'S';
        }
        if ("stop".equals(t.get("highway"))) {
            return 'P';
        }
        if (t.containsKey("traffic_calming")) {
            return 'R';
        }
        return null;
    }

    /**
     * Guarda la forma de cada construccion (casas, edificios, colegios...) en casas.txt:
     * una por linea, sus esquinas como "lat,lon" separadas por espacio.
     * Devuelve cuantas se guardaron.
     */
    @SuppressWarnings("unchecked")
    public static int procesarCasas(String json, File salida) throws IOException {
        List<String> lineas = new ArrayList<>();
        lineas.add("# Construcciones (forma vista desde arriba). " + FUENTE);
        int n = 0;
        for (Map<String, Object> e : elementos(json)) {
            List<Object> geom = (List<Object>) e.get("geometry");
            if (geom == null || geom.size() < 3) {
                continue;
            }
            StringBuilder sb = new StringBuilder();
            int puntos = geom.size();
            Map<String, Object> primero = (Map<String, Object>) geom.get(0);
            Map<String, Object> ultimo = (Map<String, Object>) geom.get(puntos - 1);
            if (primero.get("lat").equals(ultimo.get("lat")) && primero.get("lon").equals(ultimo.get("lon"))) {
                puntos--; // el poligono viene cerrado: no se repite la primera esquina
            }
            if (puntos < 3) {
                continue;
            }
            for (int k = 0; k < puntos; k++) {
                Map<String, Object> p = (Map<String, Object>) geom.get(k);
                if (k > 0) {
                    sb.append(' ');
                }
                sb.append(String.format(java.util.Locale.US, "%.6f,%.6f", (Double) p.get("lat"), (Double) p.get("lon")));
            }
            lineas.add(sb.toString());
            n++;
        }
        salida.mkdirs();
        Files.write(new File(salida, "casas.txt").toPath(), lineas, StandardCharsets.UTF_8);
        return n;
    }

    @SuppressWarnings("unchecked")
    public static Resumen procesar(String jsonVias, String jsonLugares, String jsonCosta, File salida) throws IOException {
        salida.mkdirs();
        Resumen res = new Resumen();
        Map<Long, double[]> nodos = new HashMap<>();
        Map<Long, Character> marcas = new HashMap<>();
        List<Map<String, Object>> vias = new ArrayList<>();
        for (Map<String, Object> e : elementos(jsonVias)) {
            if ("node".equals(e.get("type"))) {
                nodos.put(id(e), new double[]{(Double) e.get("lat"), (Double) e.get("lon")});
                Character m = marca(tags(e));
                if (m != null) {
                    marcas.put(id(e), m);
                }
            } else if ("way".equals(e.get("type"))) {
                vias.add(e);
            }
        }
        List<Map<String, Object>> lugares = elementos(jsonLugares);
        for (Map<String, Object> e : lugares) {
            if ("node".equals(e.get("type"))) {
                Character m = marca(tags(e));
                if (m != null && marcas.get(id(e)) == null) {
                    marcas.put(id(e), m);
                }
            }
        }

        // componente conexa mas grande (sin islas sueltas)
        Map<Long, List<Long>> vecinos = new HashMap<>();
        for (Map<String, Object> w : vias) {
            List<Object> ns = (List<Object>) w.get("nodes");
            for (int i = 0; i + 1 < ns.size(); i++) {
                long a = ((Double) ns.get(i)).longValue(), b = ((Double) ns.get(i + 1)).longValue();
                vecinos.computeIfAbsent(a, k -> new ArrayList<>()).add(b);
                vecinos.computeIfAbsent(b, k -> new ArrayList<>()).add(a);
            }
        }
        Map<Long, Integer> componente = new HashMap<>();
        Map<Integer, Integer> tam = new HashMap<>();
        int c = 0;
        for (long inicio : vecinos.keySet()) {
            if (componente.containsKey(inicio)) {
                continue;
            }
            c++;
            Deque<Long> pila = new ArrayDeque<>();
            pila.push(inicio);
            componente.put(inicio, c);
            int t = 0;
            while (!pila.isEmpty()) {
                long x = pila.pop();
                t++;
                for (long y : vecinos.get(x)) {
                    if (!componente.containsKey(y)) {
                        componente.put(y, c);
                        pila.push(y);
                    }
                }
            }
            tam.put(c, t);
        }
        int mayor = 0;
        for (Map.Entry<Integer, Integer> e : tam.entrySet()) {
            if (mayor == 0 || e.getValue() > tam.get(mayor)) {
                mayor = e.getKey();
            }
        }

        Map<Long, Integer> indice = new LinkedHashMap<>();
        List<String> lineasVias = new ArrayList<>();
        lineasVias.add("# Tramos de via. " + FUENTE);
        lineasVias.add("# nombre;tipo;sentido (0 doble, 1 una via, -1 una via al reves);superficie;velocidad max;carriles;nodos;nombre alterno");
        for (Map<String, Object> w : vias) {
            List<Object> ns = (List<Object>) w.get("nodes");
            long primero = ((Double) ns.get(0)).longValue();
            if (!Integer.valueOf(mayor).equals(componente.get(primero))) {
                continue;
            }
            StringBuilder ids = new StringBuilder();
            boolean ok = true;
            for (Object o : ns) {
                long n = ((Double) o).longValue();
                if (!nodos.containsKey(n)) {
                    ok = false;
                    break;
                }
                indice.putIfAbsent(n, indice.size());
                ids.append(ids.length() > 0 ? "," : "").append(indice.get(n));
            }
            if (!ok) {
                continue;
            }
            Map<String, String> t = tags(w);
            String ow = t.getOrDefault("oneway", "no");
            int sentido = ow.equals("yes") || ow.equals("1") || ow.equals("true") ? 1 : ow.equals("-1") ? -1 : 0;
            if ("roundabout".equals(t.get("junction")) && !ow.equals("no")) {
                sentido = 1;
            }
            String nombre = limpio(t.getOrDefault("name", t.get("alt_name")));
            String alterno = limpio(t.getOrDefault("alt_name", t.getOrDefault("old_name", t.get("official_name"))));
            if (alterno.equals(nombre)) {
                alterno = "";
            }
            String vel = t.getOrDefault("maxspeed", "").replaceAll("\\D", "");
            lineasVias.add(String.join(";", nombre, t.get("highway"), String.valueOf(sentido),
                    t.getOrDefault("surface", "-"), vel.isEmpty() ? "-" : vel, t.getOrDefault("lanes", "-"),
                    ids.toString(), alterno));
            res.vias++;
        }

        List<String> lineasNodos = new ArrayList<>();
        lineasNodos.add("# Nodos de la red vial. " + FUENTE);
        lineasNodos.add("# indice;lat;lon;marca (S=semaforo, P=pare, R=reductor, -=nada)");
        for (Map.Entry<Long, Integer> e : indice.entrySet()) {
            double[] ll = nodos.get(e.getKey());
            Character m = marcas.get(e.getKey());
            lineasNodos.add(String.format(Locale.US, "%d;%.7f;%.7f;%s", e.getValue(), ll[0], ll[1], m == null ? "-" : m));
            res.semaforos += m != null && m == 'S' ? 1 : 0;
        }
        res.nodos = indice.size();

        List<String> lineasLugares = new ArrayList<>();
        lineasLugares.add("# Lugares con nombre. " + FUENTE);
        lineasLugares.add("# nombre;categoria;lat;lon");
        Set<String> vistos = new HashSet<>();
        for (Map<String, Object> e : lugares) {
            Map<String, String> t = tags(e);
            String nombre = limpio(t.get("name"));
            if (nombre.isEmpty()) {
                continue;
            }
            Double lat, lon;
            if ("node".equals(e.get("type"))) {
                lat = (Double) e.get("lat");
                lon = (Double) e.get("lon");
            } else if (e.get("center") != null) {
                Map<String, Object> ce = (Map<String, Object>) e.get("center");
                lat = (Double) ce.get("lat");
                lon = (Double) ce.get("lon");
            } else {
                continue;
            }
            String clave = t.get("place");
            for (String k : new String[]{"amenity", "shop", "tourism", "healthcare"}) {
                if (clave == null) {
                    clave = t.get(k);
                }
            }
            String cat = CATEGORIAS.getOrDefault(clave == null ? "" : clave, "OTRO");
            if (cat.equals("OTRO") && t.containsKey("leisure")) {
                cat = "RECREACION";
            }
            if (cat.equals("OTRO") && t.containsKey("tourism")) {
                cat = "TURISMO";
            }
            String k = nombre.toLowerCase() + String.format(Locale.US, "%.4f%.4f", lat, lon);
            if (!vistos.add(k)) {
                continue;
            }
            lineasLugares.add(String.format(Locale.US, "%s;%s;%.7f;%.7f", nombre, cat, lat, lon));
            res.lugares++;
        }

        // linea de costa: se unen los pedazos por sus extremos
        List<List<double[]>> pedazos = new ArrayList<>();
        if (jsonCosta != null) {
            for (Map<String, Object> e : elementos(jsonCosta)) {
                List<Object> geom = (List<Object>) e.get("geometry");
                if (geom == null) {
                    continue;
                }
                List<double[]> l = new ArrayList<>();
                for (Object o : geom) {
                    Map<String, Object> p = (Map<String, Object>) o;
                    l.add(new double[]{(Double) p.get("lat"), (Double) p.get("lon")});
                }
                pedazos.add(l);
            }
        }
        List<List<double[]>> lineas = unirLineas(pedazos);
        List<String> lineasCosta = new ArrayList<>();
        lineasCosta.add("# Linea de costa (tierra a la izquierda). " + FUENTE);
        for (List<double[]> l : lineas) {
            StringBuilder sb = new StringBuilder();
            for (double[] p : l) {
                sb.append(sb.length() > 0 ? " " : "").append(String.format(Locale.US, "%.6f,%.6f", p[0], p[1]));
            }
            lineasCosta.add(sb.toString());
        }

        Files.write(new File(salida, "nodos.txt").toPath(), lineasNodos, StandardCharsets.UTF_8);
        Files.write(new File(salida, "vias.txt").toPath(), lineasVias, StandardCharsets.UTF_8);
        Files.write(new File(salida, "lugares.txt").toPath(), lineasLugares, StandardCharsets.UTF_8);
        Files.write(new File(salida, "costa.txt").toPath(), lineasCosta, StandardCharsets.UTF_8);
        return res;
    }

    private static boolean igual(double[] a, double[] b) {
        return a[0] == b[0] && a[1] == b[1];
    }

    static List<List<double[]>> unirLineas(List<List<double[]>> pedazos) {
        List<List<double[]>> lineas = new ArrayList<>();
        List<List<double[]>> pendientes = new ArrayList<>(pedazos);
        if (pendientes.isEmpty()) {
            return lineas;
        }
        lineas.add(new ArrayList<>(pendientes.remove(0)));
        while (!pendientes.isEmpty()) {
            List<double[]> l = lineas.get(lineas.size() - 1);
            boolean unido = false;
            for (int i = 0; i < pendientes.size(); i++) {
                List<double[]> s = pendientes.get(i);
                if (igual(s.get(0), l.get(l.size() - 1))) {
                    l.addAll(s.subList(1, s.size()));
                } else if (igual(s.get(s.size() - 1), l.get(0))) {
                    List<double[]> nueva = new ArrayList<>(s);
                    nueva.addAll(l.subList(1, l.size()));
                    lineas.set(lineas.size() - 1, nueva);
                } else {
                    continue;
                }
                pendientes.remove(i);
                unido = true;
                break;
            }
            if (!unido) {
                lineas.add(new ArrayList<>(pendientes.remove(0)));
            }
        }
        return lineas;
    }
}
