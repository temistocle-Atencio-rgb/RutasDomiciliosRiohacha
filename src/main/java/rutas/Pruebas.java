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
        probarLluvia();
        probarPlata();
        probarCasas();
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
        // hora prometida y aviso por WhatsApp
        LocalTime[] v = rutas.logica.Avisos.ventana(LocalTime.of(19, 43));
        revisar("ventana 7:43 pm -> 7:35 a 7:55", v[0].equals(LocalTime.of(19, 35)) && v[1].equals(LocalTime.of(19, 55)));
        revisar("celular dentro del texto", rutas.logica.Avisos.extraerCelular("Ana 300 123-4567, Calle 15 # 10-20").equals("3001234567"));
        revisar("+57 se reconoce", rutas.logica.Avisos.numeroWhatsApp("+57 3015556677").equals("573015556677"));
        revisar("fijo no es celular", rutas.logica.Avisos.numeroWhatsApp("7271234").isEmpty());
        revisar("direccion no se confunde con celular", rutas.logica.Avisos.extraerCelular("Calle 15 # 10-20").isEmpty());
        String enlace = rutas.logica.Avisos.enlaceWhatsApp("3001234567",
                rutas.logica.Avisos.mensaje("Ana María", "Tienda Lupe", LocalTime.of(19, 43)));
        revisar("enlace wa.me con mensaje", enlace.startsWith("https://wa.me/573001234567?text=Hola%20Ana%2C"));
        int[] orden = d.ordenDeVisitaHasta(2);
        revisar("orden de visita va de A hasta C", orden.length >= 2 && orden[0] == 0 && orden[orden.length - 1] == 2);

        // matriz de adyacencia no simetrica por la calle de una via
        MatrizAdyacencia m = new MatrizAdyacencia(2);
        m.set(0, 1, 200);
        m.set(1, 0, 50);
        revisar("matriz: ida 200 y vuelta 50", m.get(0, 1) == 200 && m.get(1, 0) == 50
                && m.costoRecorrido(new int[]{0, 1}, true) == 250);
    }

    /** Con lluvia la ruta debe dejar la calle destapada aunque sea mas corta. */
    private static void probarLluvia() throws Exception {
        System.out.println("--- Lluvia ---");
        Grafo g = new Grafo(3);
        g.setNodo(0, 11.540, -72.900, '-');
        g.setNodo(1, 11.541, -72.900, '-');
        g.setNodo(2, 11.542, -72.900, '-');
        List<Via> vias = new ArrayList<>();
        vias.add(new Via(0, "Calle pavimentada", "residential", 0, "asphalt", 0, new int[]{0, 1, 2}));
        vias.add(new Via(1, "Calle destapada", "residential", 0, "unpaved", 0, new int[]{0, 2}));
        g.agregarArista(0, 1, 300, 0);
        g.agregarArista(1, 2, 300, 0);
        g.agregarArista(0, 2, 250, 1); // mas corta pero destapada
        Mapa mapa = new Mapa(g, vias, new ArrayList<>(), new ArrayList<>());
        ModeloTrafico t = new ModeloTrafico(mapa, null);
        LocalTime hora = LocalTime.of(10, 0);

        revisar("sin lluvia la mas rapida usa la destapada (250 m)",
                new Dijkstra(g, 0, t, Criterio.MAS_RAPIDA, hora).getMetros(2) == 250);
        t.setLluvia(true);
        revisar("con lluvia la mas rapida se va por la pavimentada (600 m)",
                new Dijkstra(g, 0, t, Criterio.MAS_RAPIDA, hora).getMetros(2) == 600);
        revisar("con lluvia la mas corta tambien evita la destapada",
                new Dijkstra(g, 0, t, Criterio.MAS_CORTA, hora).getMetros(2) == 600);
        Dijkstra pav = new Dijkstra(g, 0, t, Criterio.MAS_RAPIDA, hora);
        t.setLluvia(false);
        Dijkstra seco = new Dijkstra(g, 1, t, Criterio.MAS_RAPIDA, hora);
        t.setLluvia(true);
        Dijkstra mojado = new Dijkstra(g, 1, t, Criterio.MAS_RAPIDA, hora);
        revisar("con lluvia todo va un poco mas lento", mojado.getCosto(2) > seco.getCosto(2) && pav.hayCamino(2));
        t.setLluvia(false);
        revisar("al quitar la lluvia vuelve a la destapada",
                new Dijkstra(g, 0, t, Criterio.MAS_RAPIDA, hora).getMetros(2) == 250);
    }

    /** Tarifa por distancia, formato de pesos y cuadre de caja por moto. */
    private static void probarPlata() {
        System.out.println("--- La plata del domicilio ---");
        rutas.logica.Tarifa t = new rutas.logica.Tarifa(3000, 2, 700);
        revisar("1.5 km cuesta la tarifa base ($3.000)", t.calcular(1500) == 3000);
        revisar("2 km justos siguen en la base", t.calcular(2000) == 3000);
        revisar("6 km: 3.000 + 4 x 700 = 5.800, se sube a 6.000", t.calcular(6000) == 6000);
        revisar("2.1 km: 3.070 se sube a 3.500", t.calcular(2100) == 3500);
        revisar("formato de pesos $25.500", rutas.logica.Tarifa.pesos(25500).equals("$25.500"));
        revisar("formato de pesos $1.250.000", rutas.logica.Tarifa.pesos(1250000).equals("$1.250.000"));
        revisar("valor escrito con signo: \"$25.000\" -> 25000", rutas.logica.Tarifa.leerPesos("pedido $25.000 Calle 15") == 25000);
        revisar("sin signo de pesos no se lee (podria ser una direccion)", rutas.logica.Tarifa.leerPesos("Calle 15 # 10-20") == 0);

        rutas.logica.Cuadre c = new rutas.logica.Cuadre();
        c.agregar(20000, 3000, false); // paga en efectivo: el repartidor cobra 23.000
        c.agregar(15000, 4000, true);  // ya pago por transferencia
        c.agregar(0, 3500, false);     // sin valor del pedido: solo el domicilio
        revisar("cuadre: domicilios 10.500", c.getDomicilios() == 10500);
        revisar("cuadre: pedidos 35.000", c.getPedidos() == 35000);
        revisar("cuadre: efectivo que debe entregar 26.500", c.getEfectivo() == 26500);
        revisar("cuadre: por transferencia 19.000", c.getTransferido() == 19000);
        revisar("cuadre: todo suma igual", c.getEfectivo() + c.getTransferido() == c.getDomicilios() + c.getPedidos());

        // una linea pegada de WhatsApp
        rutas.logica.LineaPedido lp = rutas.logica.LineaPedido.leer("Ana 300 123 4567, Calle 15 # 10-20 ya pagó $25.000 URGENTE");
        revisar("linea pegada: ya pagó con tilde", lp.isPagado());
        revisar("linea pegada: urgente, celular y valor", lp.isUrgente() && lp.getCelular().equals("3001234567")
                && lp.getValor() == 25000);
        revisar("linea pegada: queda solo nombre y direccion", lp.getTexto().equals("Ana, Calle 15 # 10-20"));
        revisar("YA PAGÓ en mayusculas", rutas.logica.LineaPedido.leer("Luis - Cra 7 con Calle 12 YA PAGÓ").isPagado());
        revisar("PAGADO y transferencia", rutas.logica.LineaPedido.leer("Cra 7 # 10-20 PAGADO").isPagado()
                && rutas.logica.LineaPedido.leer("Cra 7 # 10-20 pagó por transferencia").isPagado());
        revisar("sin marca de pago queda en efectivo", !rutas.logica.LineaPedido.leer("Ana, Calle 15 # 10-20 $25.000").isPagado());
        revisar("celular pegado a otros numeros no se toma", rutas.logica.Avisos.extraerCelular("casa 13001234567").isEmpty());

        String msg = rutas.logica.Avisos.mensaje("Ana María", "Tienda Lupe", LocalTime.of(19, 43), 3000, 23000);
        revisar("el mensaje al cliente dice cuanto pagar", msg.contains("Domicilio: $3.000") && msg.contains("Total a pagar: $23.000"));
        String pagado = rutas.logica.Avisos.mensaje("Ana", "", LocalTime.of(19, 43), 3000, 0);
        revisar("si ya pago no se le cobra", !pagado.contains("Total a pagar"));
    }

    /** Las casas de OpenStreetMap se guardan en casas.txt y se vuelven a leer igual. */
    private static void probarCasas() throws Exception {
        System.out.println("--- Casas ---");
        String json = "{\"elements\":[{\"type\":\"way\",\"id\":1,\"geometry\":["
                + "{\"lat\":11.5401,\"lon\":-72.9001},{\"lat\":11.5401,\"lon\":-72.9000},"
                + "{\"lat\":11.5400,\"lon\":-72.9000},{\"lat\":11.5400,\"lon\":-72.9001},"
                + "{\"lat\":11.5401,\"lon\":-72.9001}]},"
                + "{\"type\":\"way\",\"id\":2,\"geometry\":[{\"lat\":11.5,\"lon\":-72.9}]}]}";
        File carpeta = java.nio.file.Files.createTempDirectory("casas").toFile();
        int n = rutas.osm.ProcesadorOSM.procesarCasas(json, carpeta);
        revisar("se guarda 1 casa (la de un solo punto se descarta)", n == 1);
        List<double[][]> casas = CargadorMapa.leerCasas(new File(carpeta, "casas.txt"));
        revisar("la casa tiene 4 esquinas (sin repetir la primera)", casas.size() == 1 && casas.get(0).length == 4
                && Math.abs(casas.get(0)[0][0] - 11.5401) < 1e-6 && Math.abs(casas.get(0)[0][1] + 72.9001) < 1e-6);
        revisar("sin archivo de casas el mapa igual carga", CargadorMapa.leerCasas(new File(carpeta, "no_existe.txt")).isEmpty());
    }

    private static double metrosDestapados(Mapa mapa, List<rutas.estructuras.Arista> camino) {
        double total = 0;
        for (rutas.estructuras.Arista a : camino) {
            if (mapa.getVia(a.getVia()).esDestapada()) {
                total += a.getMetros();
            }
        }
        return total;
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

        // lluvia en el mapa real: se busca un destino al que, en seco, la ruta mas rapida
        // pase por mas de 300 m de calle destapada, y se mira que con lluvia use menos
        trafico.setLluvia(false);
        Dijkstra seco = new Dijkstra(g, base.getNodo(), trafico, Criterio.MAS_RAPIDA, LocalTime.of(10, 0));
        trafico.setLluvia(true);
        Dijkstra mojado = new Dijkstra(g, base.getNodo(), trafico, Criterio.MAS_RAPIDA, LocalTime.of(10, 0));
        trafico.setLluvia(false);
        int destino = -1;
        double enSeco = 0, conLluvia = 0;
        for (int n = 0; n < g.getCantidadNodos() && destino < 0; n += 7) {
            if (!seco.hayCamino(n)) {
                continue;
            }
            double dd = metrosDestapados(mapa, seco.camino(n));
            if (dd > 300) {
                destino = n;
                enSeco = dd;
                conLluvia = metrosDestapados(mapa, mojado.camino(n));
            }
        }
        revisar(String.format("con lluvia, hacia %s la destapada baja de %.0f m a %.0f m",
                geo.describir(destino), enSeco, conLluvia), destino >= 0 && conLluvia < enSeco);

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

        probarBaseDatos(mapa, geo, base, flota);

        System.out.println("\nIndicaciones:");
        for (Indicacion i : r.getIndicaciones()) {
            System.out.println("  - " + i.getTexto());
        }
        System.out.println("\n" + r.textoParaCompartir());
        System.out.println(r.enlaceGoogleMaps());
    }

    private static void probarBaseDatos(Mapa mapa, Geocodificador geo, Parada base, List<Ruta> flota) throws Exception {
        System.out.println("--- Base de datos SQLite ---");
        java.io.File carpeta = java.nio.file.Files.createTempDirectory("domiruta_prueba").toFile();
        java.io.File archivo = new java.io.File(carpeta, "prueba.db");
        try (rutas.logica.BaseDatos bd = new rutas.logica.BaseDatos(archivo)) {
            bd.guardarCliente(new rutas.modelo.Cliente("José Pérez", "3001234567", "Calle 15 # 7-20", "", 11.5437, -72.9066));
            bd.guardarCliente(new rutas.modelo.Cliente("Ana María", "", "Cra 15 # 14-30", "portón verde", 11.5427, -72.9159));
            bd.guardarCliente(new rutas.modelo.Cliente("jose perez", "3009999999", "Calle 15 # 7-20", "", 11.5437, -72.9066));
            ArbolAVL<String, rutas.modelo.Cliente> clientes = bd.cargarClientes();
            revisar("clientes guardados y cargados en el AVL (el repetido se actualiza): " + clientes.getTamano(),
                    clientes.getTamano() == 2 && clientes.buscar("jose perez").getTelefono().equals("3009999999"));
            bd.eliminarCliente("Ana María");
            revisar("eliminar cliente", bd.contarClientes() == 1);

            bd.guardarBase(base);
            revisar("punto de salida guardado", bd.cargarBase().getDireccion().equals(base.getDireccion()));

            bd.guardarDireccion("Mz 4 Casa 12", 11.55, -72.90);
            Geocodificador g2 = new Geocodificador(mapa, new BuscadorLugares(mapa.getLugares()));
            bd.cargarDirecciones(g2);
            revisar("direccion corregida a mano queda en la base", g2.ubicar("mz 4 casa 12").getPrecision().startsWith("Ubicación corregida"));

            bd.guardarRutas(flota);
            List<Ruta> leidas = bd.cargarHistorial(mapa);
            revisar("historial: " + leidas.size() + " rutas de 3 motos con sus paradas y recorrido",
                    leidas.size() == flota.size() && leidas.get(0).getParadas().size() == flota.get(0).getParadas().size()
                    && leidas.get(0).getAristas().size() == flota.get(0).getAristas().size());
        }
        // datos de la version anterior en archivos .txt: se importan una vez
        java.nio.file.Files.write(new java.io.File(carpeta, "clientes.txt").toPath(),
                List.of("# nombre;telefono;direccion;nota;lat;lon", "Luis;300;Calle 38 # 7-10;;11.5262;-72.9035"));
        try (rutas.logica.BaseDatos bd = new rutas.logica.BaseDatos(new java.io.File(carpeta, "otra.db"))) {
            int n = bd.importarTextos(carpeta, mapa);
            revisar("importa clientes.txt de la version anterior (" + n + " registro)",
                    n == 1 && bd.cargarClientes().buscar("luis") != null
                    && new java.io.File(carpeta, "clientes.txt.importado").exists());
        }
    }
}
