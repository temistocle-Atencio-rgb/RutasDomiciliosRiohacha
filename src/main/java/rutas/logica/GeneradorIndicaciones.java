package rutas.logica;

import java.util.ArrayList;
import java.util.List;
import rutas.estructuras.Arista;
import rutas.estructuras.Grafo;
import rutas.modelo.Geo;
import rutas.modelo.Indicacion;
import rutas.modelo.Parada;
import rutas.modelo.Via;

/**
 * Convierte el recorrido (lista de aristas) en indicaciones para el repartidor:
 * "Siga por Carrera 7 400 m", "Gire a la derecha en Calle 15"...
 *
 * 1. Se agrupan las aristas seguidas que van por la misma calle.
 * 2. Se quitan los tramos muy cortos (menos de 25 m) que solo meten ruido.
 * 3. Se juntan tramos seguidos con el mismo nombre.
 * 4. El giro se saca del cambio de rumbo entre el final de un tramo y el inicio del siguiente.
 */
public final class GeneradorIndicaciones {

    private static final double TRAMO_MINIMO = 25;

    private GeneradorIndicaciones() {
    }

    /** Un tramo por una misma calle, o una entrega (si entrega >= 0). */
    private static class Paso {
        String clave;
        double metros;
        Arista primera, ultima;
        int entrega = -1;
    }

    public static List<Indicacion> generar(Mapa mapa, List<Arista> aristas, List<Parada> paradas,
                                           List<Integer> cortes, boolean regreso) {
        // 1. agrupar por calle, cortando en cada entrega
        List<Paso> pasos = new ArrayList<>();
        int entrega = 0;
        for (int i = 0; i <= aristas.size(); i++) {
            while (entrega < cortes.size() && cortes.get(entrega) == i) {
                Paso e = new Paso();
                e.entrega = entrega++;
                pasos.add(e);
            }
            if (i == aristas.size()) {
                break;
            }
            Arista a = aristas.get(i);
            String clave = clave(mapa, a);
            Paso ult = pasos.isEmpty() ? null : pasos.get(pasos.size() - 1);
            if (ult != null && ult.entrega < 0 && ult.clave.equals(clave)) {
                ult.metros += a.getMetros();
                ult.ultima = a;
            } else {
                Paso p = new Paso();
                p.clave = clave;
                p.metros = a.getMetros();
                p.primera = a;
                p.ultima = a;
                pasos.add(p);
            }
        }
        while (entrega < paradas.size()) { // entregas en el mismo punto de salida
            Paso e = new Paso();
            e.entrega = entrega++;
            pasos.add(e);
        }

        // 2 y 3. quitar tramos cortos (menos el primero) y juntar los de igual nombre
        List<Paso> limpios = new ArrayList<>();
        for (int k = 0; k < pasos.size(); k++) {
            Paso p = pasos.get(k);
            boolean esPrimero = limpios.isEmpty();
            if (p.entrega < 0 && !esPrimero && p.metros < TRAMO_MINIMO) {
                // se suma la distancia al tramo anterior si es de calle
                Paso ant = limpios.get(limpios.size() - 1);
                if (ant.entrega < 0) {
                    ant.metros += p.metros;
                }
                continue;
            }
            Paso ant = esPrimero ? null : limpios.get(limpios.size() - 1);
            if (ant != null && ant.entrega < 0 && p.entrega < 0 && ant.clave.equals(p.clave)) {
                ant.metros += p.metros;
                ant.ultima = p.ultima;
                continue;
            }
            limpios.add(p);
        }

        // 4. textos
        List<Indicacion> lista = new ArrayList<>();
        Grafo g = mapa.getGrafo();
        Arista anterior = null;
        for (Paso p : limpios) {
            if (p.entrega >= 0) {
                Parada par = paradas.get(p.entrega);
                lista.add(new Indicacion("Entrega " + (p.entrega + 1) + ": " + par.etiqueta(), 0, par.getNodo(), true));
                continue;
            }
            Via via = mapa.getVia(p.primera.getVia());
            String calle = via.nombreVisible();
            String texto = anterior == null
                    ? "Salga por " + calle + " hacia el " + puntoCardinal(g, p.primera)
                    : giro(g, anterior, p.primera) + calle;
            lista.add(new Indicacion(texto + " y siga " + Geo.formatoDistancia(p.metros), p.metros,
                    p.primera.getOrigen(), false));
            anterior = p.ultima;
        }
        if (regreso) {
            lista.add(new Indicacion("Llegada a la base", 0,
                    aristas.isEmpty() ? -1 : aristas.get(aristas.size() - 1).getDestino(), true));
        }
        return lista;
    }

    // calles con nombre se agrupan por nombre (sin importar mayusculas); las que no tienen, por tramo
    private static String clave(Mapa mapa, Arista a) {
        Via v = mapa.getVia(a.getVia());
        return v.tieneNombre() ? Geo.normalizar(v.getNombre()) : "#" + v.getIndice();
    }

    private static double rumbo(Grafo g, Arista a) {
        return Geo.rumbo(g.getX(a.getOrigen()), g.getY(a.getOrigen()), g.getX(a.getDestino()), g.getY(a.getDestino()));
    }

    static String giro(Grafo g, Arista antes, Arista despues) {
        double d = rumbo(g, despues) - rumbo(g, antes);
        while (d > 180) {
            d -= 360;
        }
        while (d <= -180) {
            d += 360;
        }
        if (Math.abs(d) < 25) {
            return "Continúe derecho por ";
        }
        if (Math.abs(d) > 150) {
            return "Dé la vuelta en U por ";
        }
        if (d > 0) {
            return d < 60 ? "Gire levemente a la derecha en " : "Gire a la derecha en ";
        }
        return d > -60 ? "Gire levemente a la izquierda en " : "Gire a la izquierda en ";
    }

    private static String puntoCardinal(Grafo g, Arista a) {
        double r = rumbo(g, a);
        String[] nombres = {"norte", "nororiente", "oriente", "suroriente", "sur", "suroccidente", "occidente", "noroccidente"};
        return nombres[(int) Math.round(r / 45.0) % 8];
    }
}
