package rutas;

import java.io.File;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import rutas.estructuras.ArbolAVL;
import rutas.estructuras.Grafo;
import rutas.estructuras.MatrizAdyacencia;
import rutas.estructuras.MonticuloMin;
import rutas.logica.BuscadorLugares;
import rutas.logica.CargadorMapa;
import rutas.logica.Criterio;
import rutas.logica.Dijkstra;
import rutas.logica.Geocodificador;
import rutas.logica.Mapa;
import rutas.logica.ModeloTrafico;
import rutas.logica.PlanificadorRutas;
import rutas.modelo.Geo;
import rutas.modelo.Indicacion;
import rutas.modelo.Parada;
import rutas.modelo.Ruta;
import rutas.modelo.Via;

/**
 * Pruebas por consola de las estructuras y del calculo de rutas con el mapa real.
 * En NetBeans: clic derecho sobre este archivo > Run File.
 */
public class Pruebas {

    private static int bien = 0;
    private static int mal = 0;

    public static void main(String[] args) throws Exception {
        probarAvl();
        probarMonticulo();
        probarGrafoPequeno();
        probarMapaReal();
        System.out.println("\nResultado: " + bien + " pruebas bien, " + mal + " mal");
        if (mal > 0) {
            System.exit(1);
        }
    }

    private static void revisar(String nombre, boolean ok) {
        System.out.println((ok ? "[OK]    " : "[FALLA] ") + nombre);
        if (ok) {
            bien++;
        } else {
            mal++;
        }
    }

    // ------------------------------------------------------------------

    private static void probarAvl() {
        System.out.println("--- Arbol AVL ---");
        ArbolAVL<Integer, Integer> avl = new ArbolAVL<>();
        for (int i = 1; i <= 1000; i++) { // en orden: el peor caso de un arbol normal
            avl.insertar(i, i);
        }
        revisar("1000 datos en orden quedan balanceados", avl.estaBalanceado());
        revisar("altura <= 1.44 log2(n) (da " + avl.getAltura() + ")", avl.getAltura() <= 15);

        Random r = new Random(7);
        for (int i = 0; i < 600; i++) {
            avl.eliminar(r.nextInt(1000) + 1);
        }
        revisar("sigue balanceado despues de eliminar", avl.estaBalanceado());
        List<Integer> orden = avl.enOrden();
        boolean ordenado = true;
        for (int i = 1; i < orden.size(); i++) {
            ordenado &= orden.get(i - 1) < orden.get(i);
        }
        revisar("inorden sale ordenado", ordenado);
        revisar("tamano coincide con el recorrido", orden.size() == avl.getTamano());

        ArbolAVL<Double, String> nums = new ArbolAVL<>();
        for (double d : new double[]{10, 12, 15, 20}) {
            nums.insertar(d, "calle " + (int) d);
        }
        revisar("piso(13) = 12", nums.piso(13.0).getClave() == 12);
        revisar("techo(13) = 15", nums.techo(13.0).getClave() == 15);
        revisar("piso(9) no existe", nums.piso(9.0) == null);

        ArbolAVL<String, String> nombres = new ArbolAVL<>();
        for (String s : new String[]{"cooperativo", "coquivacoa", "centro", "comfamiliar", "boca grande"}) {
            nombres.insertar(s, s);
        }
        revisar("prefijo 'co' encuentra 3", nombres.buscarPorPrefijo("co").size() == 3);
    }

    private static void probarMonticulo() {
        System.out.println("--- Monticulo de minimos ---");
        MonticuloMin m = new MonticuloMin(2);
        Random r = new Random(3);
        double[] valores = new double[500];
        for (int i = 0; i < valores.length; i++) {
            valores[i] = r.nextDouble() * 1000;
            m.insertar(i, valores[i]);
        }
        Arrays.sort(valores);
        boolean ok = true;
        for (double v : valores) {
            ok &= Math.abs(m.prioridadMinima() - v) < 1e-12;
            m.extraerMinimo();
        }
        revisar("saca 500 valores en orden ascendente", ok && m.estaVacio());
    }

    private static void probarGrafoPequeno() throws Exception {
        System.out.println("--- Dijkstra en un grafo pequeno ---");
        // A --100-- B --100-- C,  A --500-- C,  D aislado,  C -> A de una via 50 m
        Grafo g = new Grafo(4);
        g.setNodo(0, 11.540, -72.900, '-');
        g.setNodo(1, 11.541, -72.900, '-');
        g.setNodo(2, 11.542, -72.900, '-');
        g.setNodo(3, 11.550, -72.900, '-');
        List<Via> vias = new ArrayList<>();
        vias.add(new Via(0, "Calle 1", "residential", 0, "asphalt", 0, new int[]{0, 1}));
        vias.add(new Via(1, "Calle 1", "residential", 0, "asphalt", 0, new int[]{1, 2}));
        vias.add(new Via(2, "Carrera 9", "residential", 0, "asphalt", 0, new int[]{0, 2}));
        vias.add(new Via(3, "Carrera 8", "residential", 1, "asphalt", 0, new int[]{2, 0}));
        g.agregarArista(0, 1, 100, 0);
        g.agregarArista(1, 0, 100, 0);
        g.agregarArista(1, 2, 100, 1);
        g.agregarArista(2, 1, 100, 1);
        g.agregarArista(0, 2, 500, 2);
        g.agregarArista(2, 0, 500, 2);
        g.agregarArista(2, 0, 50, 3);
        Mapa mapa = new Mapa(g, vias, new ArrayList<>(), new ArrayList<>());
        ModeloTrafico t = new ModeloTrafico(mapa, null);
        LocalTime hora = LocalTime.of(10, 0);

        Dijkstra d = new Dijkstra(g, 0, t, Criterio.MAS_CORTA, hora);
        revisar("A->C mide 200 m pasando por B", d.getMetros(2) == 200 && d.camino(2).size() == 2);
        revisar("D no tiene camino", !d.hayCamino(3));
        revisar("C->A usa la de una via (50 m)", new Dijkstra(g, 2, t, Criterio.MAS_CORTA, hora).getMetros(0) == 50);
        revisar("BFS desde A no llega a D", !g.bfs(0)[3]);

        // matriz de adyacencia no simetrica por la calle de una via
        MatrizAdyacencia m = new MatrizAdyacencia(2);
        m.set(0, 1, 200);
        m.set(1, 0, 50);
        revisar("matriz: ida 200 y vuelta 50", m.get(0, 1) == 200 && m.get(1, 0) == 50
                && m.costoRecorrido(new int[]{0, 1}, true) == 250);
    }

    private static void probarMapaReal() throws Exception {
        System.out.println("--- Mapa real de Riohacha (OpenStreetMap) ---");
        long t0 = System.currentTimeMillis();
        Mapa mapa = CargadorMapa.cargar(new File("datos/riohacha"));
        Grafo g = mapa.getGrafo();
        long t1 = System.currentTimeMillis();
        revisar("carga " + g.getCantidadNodos() + " nodos, " + g.getCantidadAristas() + " aristas, "
                + mapa.getVias().size() + " vias y " + mapa.getLugares().size() + " lugares en " + (t1 - t0) + " ms",
                g.getCantidadNodos() > 10000 && mapa.getLugares().size() > 1000);

        ModeloTrafico trafico = new ModeloTrafico(mapa, new File("datos/trafico.txt"));
        BuscadorLugares buscador = new BuscadorLugares(mapa.getLugares());
        Geocodificador geo = new Geocodificador(mapa, buscador);
        geo.cargarAlias(new File("datos/riohacha/alias.txt"));
        revisar("AVL de calles con " + geo.getCantidadCalles() + " nombres, altura " + geo.getAlturaIndiceCalles(),
                geo.getCantidadCalles() > 100);

        revisar("busca lugar 'uniguajira'", !buscador.buscar("uniguajira", 5).isEmpty());
        revisar("busca sin tildes 'drogueria'", !buscador.buscar("drogueria", 5).isEmpty());

        Parada base = geo.ubicar("Parque Almirante Padilla");
        boolean[] alcanzable = g.bfs(base.getNodo());
        int cuenta = 0;
        for (boolean b : alcanzable) {
            cuenta += b ? 1 : 0;
        }
        revisar(String.format("BFS: desde el Parque Padilla se llega al %.1f%% de los nodos",
                100.0 * cuenta / g.getCantidadNodos()), cuenta > g.getCantidadNodos() * 0.9);
        boolean[] idaVuelta = g.idaYVuelta(base.getNodo());
        int iv = 0;
        for (boolean b : idaVuelta) {
            iv += b ? 1 : 0;
        }
        revisar("BFS ida y vuelta: " + (cuenta - iv) + " nodos se alcanzan pero no tienen regreso", iv <= cuenta && iv > cuenta * 0.95);
        alcanzable = idaVuelta;
        geo.setAlcanzable(alcanzable);
        base = geo.ubicar("Parque Almirante Padilla");

        String[] direcciones = {"Calle 15 # 7-20", "Carrera 7 con Calle 15", "Cra 15 # 14-30",
            "Calle 12 # 11 - 40", "Cl 38 # 7-10", "Uniguajira", "Calle 14E # 25-15", "Hospital Nuestra Señora de los Remedios"};
        List<Parada> paradas = new ArrayList<>();
        for (String d : direcciones) {
            try {
                Parada p = geo.ubicar(d);
                paradas.add(p);
                revisar(String.format("ubica \"%s\" -> %.5f, %.5f [%s] (%s)", d, p.getLat(), p.getLon(),
                        p.getPrecision(), geo.describir(p.getNodo())), p.getNodo() >= 0);
            } catch (IllegalArgumentException e) {
                revisar("ubica \"" + d + "\": " + e.getMessage(), false);
            }
        }
        // la esquina Calle 15 con Carrera 7 debe quedar a menos de 60 m de "Calle 15 # 7-20"
        // direccion escrita con barrio y nombre popular adelante
        Parada conTexto = geo.ubicar("La Ancha, centro, calle 7# 10 - 02");
        Parada limpia = geo.ubicar("Calle 7 # 10-02");
        revisar("\"La Ancha, centro, calle 7# 10 - 02\" queda igual que \"Calle 7 # 10-02\"",
                Geo.haversine(conTexto.getLat(), conTexto.getLon(), limpia.getLat(), limpia.getLon()) < 1);
        Parada ancha = geo.ubicar("La Ancha # 10-02");
        revisar("\"La Ancha # 10-02\" se entiende como Calle 7 # 10-02",
                Geo.haversine(ancha.getLat(), ancha.getLon(), limpia.getLat(), limpia.getLon()) < 1);
        revisar("\"Calle Ancha\" (nombre alterno en OSM) se encuentra", geo.viasConNombre("Calle Ancha") != null);
        // direccion de un barrio sin nombres en el mapa: el usuario la marca una vez y queda aprendida
        geo.aprender("Mz 4 Casa 12 Villa Fatima", 11.5531, -72.8975);
        Parada aprendida = geo.ubicar("mz 4 casa 12 villa fátima");
        revisar("direccion corregida a mano se recuerda (sin importar tildes ni mayusculas)",
                aprendida.getPrecision().startsWith("Ubicación corregida") && Math.abs(aprendida.getLat() - 11.5531) < 1e-6);
        geo.olvidar("Mz 4 Casa 12 Villa Fatima");
        try {
            geo.ubicar("Calle 14E # 9-15");
            revisar("Calle 14E # 9-15 deberia dar error (no se cruzan)", false);
        } catch (IllegalArgumentException e) {
            revisar("Calle 14E # 9-15 da error claro: " + e.getMessage().split("\n")[0], true);
        }
        double dEsquina = Geo.haversine(paradas.get(0).getLat(), paradas.get(0).getLon(),
                paradas.get(1).getLat(), paradas.get(1).getLon());
        revisar(String.format("Calle 15 # 7-20 queda a %.0f m de la esquina", dEsquina), dEsquina > 5 && dEsquina < 60);

        // distancia por calles vs linea recta
        Parada uni = paradas.get(5);
        Dijkstra d = new Dijkstra(g, base.getNodo(), trafico, Criterio.MAS_CORTA, LocalTime.of(10, 0));
        double recta = Geo.haversine(base.getLat(), base.getLon(), uni.getLat(), uni.getLon());
        revisar(String.format("Parque Padilla -> Uniguajira: %.2f km por calles (%.2f km en linea recta)",
                d.getMetros(uni.getNodo()) / 1000, recta / 1000),
                d.getMetros(uni.getNodo()) > recta && d.getMetros(uni.getNodo()) < recta * 1.8);

        Dijkstra normal = new Dijkstra(g, base.getNodo(), trafico, Criterio.MAS_RAPIDA, LocalTime.of(10, 0));
        Dijkstra pico = new Dijkstra(g, base.getNodo(), trafico, Criterio.MAS_RAPIDA, LocalTime.of(12, 15));
        revisar(String.format("a la Uniguajira: %s normal, %s en hora pico",
                Geo.formatoTiempo(normal.getSegundos(uni.getNodo())), Geo.formatoTiempo(pico.getSegundos(uni.getNodo()))),
                pico.getSegundos(uni.getNodo()) > normal.getSegundos(uni.getNodo()));

        // planificador: exacto vs heuristica
        PlanificadorRutas plan = new PlanificadorRutas(mapa, trafico);
        List<Parada> entregas = paradas.subList(0, 7);
        long t2 = System.currentTimeMillis();
        Ruta r = plan.planificar(base, new ArrayList<>(entregas), true, Criterio.MAS_RAPIDA, LocalTime.of(10, 0));
        long t3 = System.currentTimeMillis();
        revisar("ruta con " + r.getParadas().size() + " entregas en " + (t3 - t2) + " ms: "
                + Geo.formatoDistancia(r.getMetros()) + ", " + Geo.formatoTiempo(r.getSegundos()) + " (" + r.getMetodo() + ")",
                r.getParadas().size() == 7 && r.getSinCamino().isEmpty());
        MatrizAdyacencia m = plan.getUltimaMatriz();
        double exacto = r.getSegundos() - 7 * trafico.tiempoEntrega();
        revisar("la ruta empieza y termina en la base", r.nodos().get(0) == base.getNodo()
                && r.nodos().get(r.nodos().size() - 1) == base.getNodo());
        // compara con el orden en que se escribieron las entregas
        int[] ordenEscrito = {0, 1, 2, 3, 4, 5, 6, 7};
        double escrito = m.costoRecorrido(ordenEscrito, true);
        revisar(String.format("el orden optimo (%.0f s) no es peor que el orden escrito (%.0f s)", exacto, escrito),
                exacto <= escrito + 1);

        // entrega urgente: va de primera aunque no sea la mas cercana
        List<Parada> conUrgente = new ArrayList<>();
        for (Parada p : entregas) {
            Parada c = new Parada(p.getNombre(), p.getDireccion(), p.getLat(), p.getLon());
            c.setNodo(p.getNodo());
            conUrgente.add(c);
        }
        conUrgente.get(5).setUrgente(true); // la Uniguajira, la mas lejana
        Ruta ru = plan.planificar(base, conUrgente, true, Criterio.MAS_RAPIDA, LocalTime.of(10, 0));
        revisar("la entrega urgente (Uniguajira) va de primera", ru.getParadas().get(0) == conUrgente.get(5));

        // varios repartidores: 20 lugares reales repartidos en 3 motos
        List<Parada> muchas = new ArrayList<>();
        Random azar = new Random(11);
        List<rutas.modelo.Lugar> lugares = new ArrayList<>();
        for (rutas.modelo.Lugar l : mapa.getLugares()) {
            if (l.getCategoria() == rutas.modelo.CategoriaLugar.RESTAURANTE || l.getCategoria() == rutas.modelo.CategoriaLugar.TIENDA) {
                lugares.add(l);
            }
        }
        for (int i = 0; i < 20; i++) {
            rutas.modelo.Lugar l = lugares.get(azar.nextInt(lugares.size()));
            Parada p = new Parada(l.getNombre(), l.getNombre(), l.getLat(), l.getLon());
            geo.asignarNodo(p, Geo.x(l.getLon()), Geo.y(l.getLat()), null);
            muchas.add(p);
        }
        long t4 = System.currentTimeMillis();
        List<Ruta> flota = plan.planificarFlota(base, muchas, 3, true, Criterio.MAS_RAPIDA, LocalTime.of(10, 0));
        long t5 = System.currentTimeMillis();
        Ruta sola = plan.planificar(base, muchas, true, Criterio.MAS_RAPIDA, LocalTime.of(10, 0));
        int asignadas = 0;
        double peor = 0;
        java.util.Set<Parada> vistas = new java.util.HashSet<>();
        for (Ruta x : flota) {
            asignadas += x.getParadas().size();
            vistas.addAll(x.getParadas());
            peor = Math.max(peor, x.getSegundos());
        }
        revisar("3 repartidores: las 20 entregas quedan asignadas una sola vez (" + (t5 - t4) + " ms)",
                asignadas == 20 && vistas.size() == 20);
        revisar(String.format("con 3 motos la ruta mas larga dura %s; con 1 moto, %s",
                Geo.formatoTiempo(peor), Geo.formatoTiempo(sola.getSegundos())), peor < sola.getSegundos() * 0.6);

        System.out.println("\nIndicaciones:");
        for (Indicacion i : r.getIndicaciones()) {
            System.out.println("  - " + i.getTexto());
        }
        System.out.println("\n" + r.textoParaCompartir());
        System.out.println(r.enlaceGoogleMaps());
    }
}
