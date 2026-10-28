package rutas.logica;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import rutas.estructuras.Arista;
import rutas.estructuras.MatrizAdyacencia;
import rutas.modelo.Geo;
import rutas.modelo.Parada;
import rutas.modelo.Ruta;

/**
 * Decide el orden de las entregas y arma las rutas completas.
 *
 * 1. Se corre Dijkstra desde cada parada (base + entregas) sobre la red vial.
 * 2. Con eso se llena una MATRIZ DE ADYACENCIA entre paradas: [i][j] = costo de ir de i a j.
 * 3. Con la matriz se escoge el orden de visita:
 *    - Hasta 9 entregas: busqueda exacta con backtracking y poda (ramificacion y acotamiento).
 *    - Mas de 9: heuristica del vecino mas cercano y luego mejora 2-opt.
 *    Encontrar el orden optimo es el problema del agente viajero (NP-dificil), por eso
 *    con muchas entregas se usa una heuristica.
 *    Las entregas urgentes siempre van antes que las demas.
 * 4. Con varios repartidores, primero se reparten las entregas entre ellos (barrido por
 *    zonas alrededor de la base y luego se pasan entregas de la ruta mas larga a las otras
 *    mientras baje el tiempo de la mas larga) y despues se ordena cada ruta.
 * 5. Se unen los caminos de Dijkstra en el orden escogido.
 */
public class PlanificadorRutas {

    public static final int LIMITE_EXACTO = 9;

    private final Mapa mapa;
    private final ModeloTrafico trafico;
    private MatrizAdyacencia ultimaMatriz;
    private List<Parada> ultimosPuntos = new ArrayList<>();

    // datos del calculo actual
    private List<Parada> puntos;
    private Dijkstra[] arboles;
    private MatrizAdyacencia matriz;
    private boolean[] urgente;
    private double costoParada; // costo extra por cada entrega (tiempo de entrega si se mide tiempo)

    public PlanificadorRutas(Mapa mapa, ModeloTrafico trafico) {
        this.mapa = mapa;
        this.trafico = trafico;
    }

    /** Ruta para un solo repartidor. */
    public Ruta planificar(Parada base, List<Parada> entregas, boolean volver,
                           Criterio criterio, LocalTime salida) {
        return planificarFlota(base, entregas, 1, volver, criterio, salida).get(0);
    }

    /** Rutas para varios repartidores que salen de la misma base. */
    public List<Ruta> planificarFlota(Parada base, List<Parada> entregas, int repartidores, boolean volver,
                                      Criterio criterio, LocalTime salida) {
        prepararMatriz(base, entregas, criterio, salida);
        costoParada = criterio == Criterio.MAS_RAPIDA ? trafico.tiempoEntrega() : 0;

        // entregas a las que no se puede ir (y volver, si se pide) desde la base
        List<Integer> validas = new ArrayList<>();
        List<Parada> sinCamino = new ArrayList<>();
        for (int j = 1; j < puntos.size(); j++) {
            boolean ok = matriz.hayCamino(0, j) && (!volver || matriz.hayCamino(j, 0));
            if (ok) {
                validas.add(j);
            } else {
                sinCamino.add(puntos.get(j));
            }
        }

        int k = Math.max(1, Math.min(repartidores, Math.max(1, validas.size())));
        List<List<Integer>> grupos = k == 1 ? List.of(validas) : repartir(validas, k, volver);

        List<Ruta> rutas = new ArrayList<>();
        for (int r = 0; r < grupos.size(); r++) {
            Ruta ruta = armarRuta(grupos.get(r), volver, criterio, salida, r == 0 ? sinCamino : new ArrayList<>());
            rutas.add(ruta);
        }
        return rutas;
    }

    private void prepararMatriz(Parada base, List<Parada> entregas, Criterio criterio, LocalTime salida) {
        puntos = new ArrayList<>();
        puntos.add(base); // 0 = base, 1..n = entregas
        puntos.addAll(entregas);
        int n = puntos.size();
        arboles = new Dijkstra[n];
        urgente = new boolean[n];
        for (int i = 0; i < n; i++) {
            arboles[i] = new Dijkstra(mapa.getGrafo(), puntos.get(i).getNodo(), trafico, criterio, salida);
            urgente[i] = i > 0 && puntos.get(i).isUrgente();
        }
        matriz = new MatrizAdyacencia(n);
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i != j && arboles[i].hayCamino(puntos.get(j).getNodo())) {
                    matriz.set(i, j, arboles[i].getCosto(puntos.get(j).getNodo()));
                }
            }
        }
        ultimaMatriz = matriz;
        ultimosPuntos = new ArrayList<>(puntos);
    }

    /** Escoge el orden de un grupo de entregas y arma la ruta sobre las calles. */
    private Ruta armarRuta(List<Integer> grupo, boolean volver, Criterio criterio, LocalTime salida,
                           List<Parada> sinCamino) {
        int[] orden;
        String metodo;
        if (grupo.size() <= LIMITE_EXACTO) {
            orden = ordenExacto(matriz, grupo, volver);
            metodo = "Orden óptimo (búsqueda exacta)";
        } else {
            orden = vecinoMasCercano(matriz, grupo);
            mejorar2opt(matriz, orden, volver);
            metodo = "Vecino más cercano + mejora 2-opt";
        }
        // si por las calles de una via no se puede seguir el orden, se sacan las que fallen
        List<Integer> ordenFinal = new ArrayList<>();
        ordenFinal.add(0);
        sinCamino = new ArrayList<>(sinCamino);
        for (int k = 1; k < orden.length; k++) {
            int anterior = ordenFinal.get(ordenFinal.size() - 1);
            if (matriz.hayCamino(anterior, orden[k])) {
                ordenFinal.add(orden[k]);
            } else {
                sinCamino.add(puntos.get(orden[k]));
            }
        }

        List<Arista> aristas = new ArrayList<>();
        List<Parada> paradas = new ArrayList<>();
        List<LocalTime> llegadas = new ArrayList<>();
        List<Integer> cortes = new ArrayList<>(); // posicion en aristas donde termina cada entrega
        double metros = 0, segundos = 0;
        for (int k = 1; k < ordenFinal.size(); k++) {
            int de = ordenFinal.get(k - 1), a = ordenFinal.get(k);
            int nodoDestino = puntos.get(a).getNodo();
            aristas.addAll(arboles[de].camino(nodoDestino));
            metros += arboles[de].getMetros(nodoDestino);
            segundos += arboles[de].getSegundos(nodoDestino);
            paradas.add(puntos.get(a));
            llegadas.add(salida.plusSeconds(Math.round(segundos)));
            cortes.add(aristas.size());
            segundos += trafico.tiempoEntrega();
        }
        Parada base = puntos.get(0);
        boolean regreso = false;
        int ultima = ordenFinal.get(ordenFinal.size() - 1);
        int inicioRegreso = -1;
        if (volver && ultima != 0 && matriz.hayCamino(ultima, 0)) {
            inicioRegreso = aristas.size();
            aristas.addAll(arboles[ultima].camino(base.getNodo()));
            metros += arboles[ultima].getMetros(base.getNodo());
            segundos += arboles[ultima].getSegundos(base.getNodo());
            regreso = true;
        }
        Ruta ruta = new Ruta(base, paradas, llegadas, sinCamino, aristas, metros, segundos,
                salida, regreso, criterio.toString(), metodo);
        ruta.setInicioRegreso(inicioRegreso);
        ruta.setIndicaciones(GeneradorIndicaciones.generar(mapa, aristas, paradas, cortes, regreso));
        return ruta;
    }

    public MatrizAdyacencia getUltimaMatriz() {
        return ultimaMatriz;
    }

    /** Paradas de la ultima matriz (0 = base). Sirve para mostrar la matriz con nombres. */
    public List<Parada> getUltimosPuntos() {
        return ultimosPuntos;
    }

    // ------------------------------------------------------------------
    //  Repartir entregas entre varios repartidores
    // ------------------------------------------------------------------

    /**
     * 1. Barrido: se ordenan las entregas por el angulo que forman con la base (como las
     *    agujas de un reloj) y se parten en k pedazos seguidos; asi cada moto toma una zona.
     *    Se prueba empezar el barrido en cada entrega y se queda la mejor particion.
     * 2. Mejora: mientras se pueda, se pasa una entrega de la ruta mas larga a otra ruta
     *    si con eso baja el tiempo de la ruta mas larga.
     */
    List<List<Integer>> repartir(List<Integer> validas, int k, boolean volver) {
        int n = validas.size();
        Parada base = puntos.get(0);
        Integer[] porAngulo = validas.toArray(new Integer[0]);
        java.util.Arrays.sort(porAngulo, (a, b) -> Double.compare(angulo(base, puntos.get(a)), angulo(base, puntos.get(b))));

        List<List<Integer>> mejor = null;
        double mejorCosto = Double.MAX_VALUE;
        for (int inicio = 0; inicio < n; inicio++) {
            List<List<Integer>> grupos = new ArrayList<>();
            int pos = 0;
            for (int g = 0; g < k; g++) {
                int tam = n / k + (g < n % k ? 1 : 0);
                List<Integer> grupo = new ArrayList<>();
                for (int t = 0; t < tam; t++) {
                    grupo.add(porAngulo[(inicio + pos++) % n]);
                }
                grupos.add(grupo);
            }
            double c = costoMaximo(grupos, volver);
            if (c < mejorCosto) {
                mejorCosto = c;
                mejor = grupos;
            }
        }

        // mejora: mover entregas desde la ruta mas larga
        boolean mejoro = true;
        int vueltas = 0;
        while (mejoro && vueltas++ < 200) {
            mejoro = false;
            int larga = 0;
            double[] costos = new double[k];
            for (int g = 0; g < k; g++) {
                costos[g] = costoGrupo(mejor.get(g), volver);
                if (costos[g] > costos[larga]) {
                    larga = g;
                }
            }
            List<Integer> origen = mejor.get(larga);
            for (int i = 0; i < origen.size() && !mejoro && origen.size() > 1; i++) {
                int entrega = origen.get(i);
                for (int g = 0; g < k && !mejoro; g++) {
                    if (g == larga) {
                        continue;
                    }
                    List<Integer> sin = new ArrayList<>(origen);
                    sin.remove(i);
                    List<Integer> con = new ArrayList<>(mejor.get(g));
                    con.add(entrega);
                    double nuevoMax = Math.max(costoGrupo(sin, volver), costoGrupo(con, volver));
                    for (int h = 0; h < k; h++) {
                        if (h != g && h != larga) {
                            nuevoMax = Math.max(nuevoMax, costos[h]);
                        }
                    }
                    if (nuevoMax + 1e-6 < costos[larga]) {
                        mejor.set(larga, sin);
                        mejor.set(g, con);
                        mejoro = true;
                    }
                }
            }
        }
        mejor.removeIf(List::isEmpty);
        return mejor;
    }

    private double angulo(Parada base, Parada p) {
        return Math.atan2(Geo.y(p.getLat()) - Geo.y(base.getLat()), Geo.x(p.getLon()) - Geo.x(base.getLon()));
    }

    private double costoMaximo(List<List<Integer>> grupos, boolean volver) {
        double max = 0;
        for (List<Integer> g : grupos) {
            max = Math.max(max, costoGrupo(g, volver));
        }
        return max;
    }

    /** Costo aproximado de una ruta (vecino mas cercano + 2-opt) incluyendo el tiempo de cada entrega. */
    private double costoGrupo(List<Integer> grupo, boolean volver) {
        if (grupo.isEmpty()) {
            return 0;
        }
        int[] orden = vecinoMasCercano(matriz, grupo);
        mejorar2opt(matriz, orden, volver);
        return matriz.costoRecorrido(orden, volver) + grupo.size() * costoParada;
    }

    // ------------------------------------------------------------------
    //  Busqueda exacta (backtracking con poda)
    // ------------------------------------------------------------------

    private int[] mejorOrden;
    private double mejorCosto;

    int[] ordenExacto(MatrizAdyacencia m, List<Integer> validas, boolean volver) {
        int[] actual = new int[validas.size() + 1];
        actual[0] = 0;
        boolean[] usado = new boolean[m.tamano()];
        int urgentes = 0;
        for (int j : validas) {
            urgentes += esUrgente(j) ? 1 : 0;
        }
        mejorCosto = Double.POSITIVE_INFINITY;
        mejorOrden = null;
        backtracking(m, validas, volver, actual, 1, 0, usado, urgentes);
        if (mejorOrden == null) { // no hubo orden completo posible: se usa el vecino mas cercano
            return vecinoMasCercano(m, validas);
        }
        return mejorOrden;
    }

    private void backtracking(MatrizAdyacencia m, List<Integer> validas, boolean volver,
                              int[] actual, int pos, double costo, boolean[] usado, int urgentesPendientes) {
        if (costo >= mejorCosto) {
            return; // poda: este camino ya es peor que el mejor encontrado
        }
        if (pos == actual.length) {
            double total = costo + (volver ? m.get(actual[pos - 1], 0) : 0);
            if (total < mejorCosto) {
                mejorCosto = total;
                mejorOrden = actual.clone();
            }
            return;
        }
        for (int j : validas) {
            // mientras queden urgentes, solo se puede escoger una urgente
            if (usado[j] || (urgentesPendientes > 0 && !esUrgente(j)) || !m.hayCamino(actual[pos - 1], j)) {
                continue;
            }
            usado[j] = true;
            actual[pos] = j;
            backtracking(m, validas, volver, actual, pos + 1, costo + m.get(actual[pos - 1], j), usado,
                    urgentesPendientes - (esUrgente(j) ? 1 : 0));
            usado[j] = false;
        }
    }

    private boolean esUrgente(int j) {
        return urgente != null && j < urgente.length && urgente[j];
    }

    // ------------------------------------------------------------------
    //  Heuristicas
    // ------------------------------------------------------------------

    /** Desde donde esta el repartidor va a la entrega pendiente mas cercana (urgentes primero). */
    int[] vecinoMasCercano(MatrizAdyacencia m, List<Integer> validas) {
        int[] orden = new int[validas.size() + 1];
        boolean[] usado = new boolean[m.tamano()];
        int urgentes = 0;
        for (int j : validas) {
            urgentes += esUrgente(j) ? 1 : 0;
        }
        orden[0] = 0;
        for (int pos = 1; pos < orden.length; pos++) {
            int actual = orden[pos - 1], elegido = -1;
            for (int j : validas) {
                if (usado[j] || (urgentes > 0 && !esUrgente(j))) {
                    continue;
                }
                if (elegido == -1 || m.get(actual, j) < m.get(actual, elegido)) {
                    elegido = j;
                }
            }
            usado[elegido] = true;
            urgentes -= esUrgente(elegido) ? 1 : 0;
            orden[pos] = elegido;
        }
        return orden;
    }

    /** 2-opt: prueba invertir cada pedazo del recorrido y se queda con el cambio si baja el costo. */
    void mejorar2opt(MatrizAdyacencia m, int[] orden, boolean volver) {
        boolean mejoro = true;
        double costo = m.costoRecorrido(orden, volver);
        while (mejoro) {
            mejoro = false;
            for (int i = 1; i < orden.length - 1; i++) {
                for (int k = i + 1; k < orden.length; k++) {
                    invertir(orden, i, k);
                    double nuevo = m.costoRecorrido(orden, volver);
                    if (nuevo + 1e-9 < costo && urgentesAlPrincipio(orden)) {
                        costo = nuevo;
                        mejoro = true;
                    } else {
                        invertir(orden, i, k); // se deshace
                    }
                }
            }
        }
    }

    private boolean urgentesAlPrincipio(int[] orden) {
        boolean vioNormal = false;
        for (int i = 1; i < orden.length; i++) {
            if (esUrgente(orden[i]) && vioNormal) {
                return false;
            }
            vioNormal |= !esUrgente(orden[i]);
        }
        return true;
    }

    private static void invertir(int[] a, int i, int k) {
        while (i < k) {
            int t = a[i];
            a[i] = a[k];
            a[k] = t;
            i++;
            k--;
        }
    }
}
