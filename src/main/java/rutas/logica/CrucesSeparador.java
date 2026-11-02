package rutas.logica;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import rutas.estructuras.Arista;
import rutas.estructuras.Grafo;
import rutas.modelo.Geo;
import rutas.modelo.Via;

/**
 * Avenidas con separador (dos calzadas de un solo sentido, como la Carrera 7H): en el mapa de OpenStreetMap
 * muchas veces la calle que cruza llega a una calzada y sigue desde la otra, sin el tramito que las une.
 * Asi el programa creia que no se podia cruzar y mandaba la moto a dar la vuelta mas adelante.
 * Aqui se une: si una calle de doble sentido termina en una calzada y una calle con el MISMO nombre empieza
 * en la otra calzada (de la misma avenida) a menos de 30 m, se agrega el cruce en los dos sentidos.
 */
public final class CrucesSeparador {

    static final double MAX_METROS = 30;

    private CrucesSeparador() {
    }

    /** Devuelve cuantos cruces se agregaron. */
    public static int aplicar(Grafo grafo, List<Via> vias) {
        Map<Integer, List<Via>> porNodo = new HashMap<>();
        for (Via v : vias) {
            for (int n : v.getNodos()) {
                porNodo.computeIfAbsent(n, k -> new ArrayList<>()).add(v);
            }
        }
        // puntas de calles de doble sentido que caen sobre una calzada de un solo sentido
        List<int[]> puntas = new ArrayList<>(); // {nodo, calle, calzada}
        for (Via v : vias) {
            if (v.getSentido() != 0 || nombre(v) == null) {
                continue;
            }
            int[] ns = v.getNodos();
            for (int n : new int[]{ns[0], ns[ns.length - 1]}) {
                for (Via c : porNodo.getOrDefault(n, List.of())) {
                    if (c != v && c.getSentido() != 0 && nombre(c) != null) {
                        puntas.add(new int[]{n, v.getIndice(), c.getIndice()});
                    }
                }
            }
        }
        int agregados = 0;
        for (int i = 0; i < puntas.size(); i++) {
            for (int j = i + 1; j < puntas.size(); j++) {
                int[] a = puntas.get(i);
                int[] b = puntas.get(j);
                if (a[0] == b[0] || a[2] == b[2]) {
                    continue; // mismo punto o misma calzada
                }
                if (!nombre(vias.get(a[1])).equals(nombre(vias.get(b[1])))
                        || !nombre(vias.get(a[2])).equals(nombre(vias.get(b[2])))) {
                    continue; // tiene que ser la misma calle cruzando la misma avenida
                }
                double m = Geo.haversine(grafo.getLat(a[0]), grafo.getLon(a[0]), grafo.getLat(b[0]), grafo.getLon(b[0]));
                if (m > MAX_METROS || unidos(grafo, a[0], b[0])) {
                    continue;
                }
                grafo.agregarArista(a[0], b[0], m, a[1]);
                grafo.agregarArista(b[0], a[0], m, b[1]);
                agregados++;
            }
        }
        return agregados;
    }

    private static boolean unidos(Grafo g, int a, int b) {
        for (Arista x = g.primera(a); x != null; x = x.getSiguiente()) {
            if (x.getDestino() == b) {
                return true;
            }
        }
        return false;
    }

    /** Nombre comparable ("Carrera 7h" = "Carrera 7H"); null si no tiene. */
    private static String nombre(Via v) {
        String n = v.getNombre(); // el nombre real (o el deducido de los vecinos), no "vía principal"
        return n == null || n.isBlank() ? null : Geo.normalizar(n);
    }
}
