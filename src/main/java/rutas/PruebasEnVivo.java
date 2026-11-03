package rutas;

import static rutas.Pruebas.revisar;

import java.io.File;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import rutas.estructuras.Grafo;
import rutas.logica.BaseDatos;
import rutas.logica.CargadorMapa;
import rutas.logica.Json;
import rutas.logica.Mapa;
import rutas.logica.ModeloTrafico;
import rutas.logica.NubeRepartidor;
import rutas.logica.RutaNube;
import rutas.modelo.Domiciliario;
import rutas.modelo.Geo;
import rutas.modelo.Parada;
import rutas.modelo.Salida;

/** Pruebas del repartidor en vivo (etapa B): ruta para la página, vencimiento, repartidores y avisos. */
public class PruebasEnVivo {

    static void correr() throws Exception {
        System.out.println("--- Repartidor en vivo (ruta, vencimiento, repartidores, avisos) ---");
        probarRutaNube();
        probarSubida();
        probarRepartidores();
        probarAvisos();
        probarBaseDatos();
        probarMensajes();
        probarConexion();
        probarCorrecciones();
        probarNotificar();
        probarRastroYPasar();
        probarGruposYCancelar();
    }

    private static void probarGruposYCancelar() throws Exception {
        java.time.LocalDateTime ahora = java.time.LocalDateTime.now();
        rutas.modelo.Parada pa = new rutas.modelo.Parada("Ana", "Calle 1", 11.54, -72.9);
        java.util.function.Supplier<Salida> nueva = () -> {
            Salida x = new Salida();
            x.uuidNube = "u";
            x.entregas.add(new Salida.Entrega(pa, 0));
            return x;
        };
        Salida calle = nueva.get();
        calle.enviadaEn = ahora.minusMinutes(10);
        calle.aceptadaEn = ahora.minusMinutes(9);
        Salida esperando = nueva.get();
        esperando.enviadaEn = ahora.minusMinutes(1);
        esperando.venceEn = ahora.plusMinutes(9);
        Salida noResponde = nueva.get();
        noResponde.enviadaEn = ahora.minusMinutes(4);
        noResponde.venceEn = ahora.plusMinutes(6);
        Salida vencida = nueva.get();
        vencida.enviadaEn = ahora.minusMinutes(12);
        vencida.venceEn = ahora.minusMinutes(2);
        Salida rechazo = nueva.get();
        rechazo.enviadaEn = ahora.minusMinutes(3);
        rechazo.rechazadaEn = ahora.minusMinutes(2);
        Salida sinEnlace = nueva.get();
        sinEnlace.uuidNube = "";
        Salida sinUbic = nueva.get();
        sinUbic.aceptadaEn = ahora.minusMinutes(9);
        sinUbic.avisos.add("sin_ubicacion");
        Salida cerrada = nueva.get();
        cerrada.estado = Salida.Estado.CERRADA;
        cerrada.cerrada = ahora;
        Salida cancelada = nueva.get();
        cancelada.estado = Salida.Estado.CERRADA;
        cancelada.cerrada = ahora;
        cancelada.motivoCancelacion = "Cancelada por la tienda";
        java.util.function.Function<Salida, rutas.vista.PanelSalidas.Grupo> g = x -> rutas.vista.PanelSalidas.grupo(x, ahora);
        revisar("grupos: aceptó y va bien, o esperando hace poco = En la calle",
                g.apply(calle) == rutas.vista.PanelSalidas.Grupo.CALLE && g.apply(esperando) == rutas.vista.PanelSalidas.Grupo.CALLE);
        revisar("grupos: no responde, venció, no puede, sin enlace o sin ubicación = Necesitan atención",
                g.apply(noResponde) == rutas.vista.PanelSalidas.Grupo.ATENCION && g.apply(vencida) == rutas.vista.PanelSalidas.Grupo.ATENCION
                && g.apply(rechazo) == rutas.vista.PanelSalidas.Grupo.ATENCION && g.apply(sinEnlace) == rutas.vista.PanelSalidas.Grupo.ATENCION
                && g.apply(sinUbic) == rutas.vista.PanelSalidas.Grupo.ATENCION);
        revisar("grupos: cerrada = Terminadas; cancelada = Canceladas",
                g.apply(cerrada) == rutas.vista.PanelSalidas.Grupo.TERMINADAS && g.apply(cancelada) == rutas.vista.PanelSalidas.Grupo.CANCELADAS);
        Salida.Entrega e2 = new Salida.Entrega(pa, 0);
        e2.estado = Salida.Entrega.ENTREGADO;
        Salida conEntrega = nueva.get();
        conEntrega.entregas.add(e2);
        revisar("cancelar: se puede si no ha entregado nada; si ya entregó algo, no (se cierra a mano)",
                rutas.vista.Aplicacion.sePuedeCancelar(calle) && !rutas.vista.Aplicacion.sePuedeCancelar(conEntrega)
                && !rutas.vista.Aplicacion.sePuedeCancelar(cerrada));
        File tmp = java.nio.file.Files.createTempDirectory("canc").toFile();
        try (BaseDatos bd = new BaseDatos(new File(tmp, "domiruta.db"))) {
            bd.guardarSalida(cancelada);
            bd.guardarSalida(cerrada);
            List<Salida> l = bd.cargarSalidas(ahora.minusDays(1));
            revisar("cancelar: queda guardado en la base (y la cerrada normal no sale como cancelada)",
                    l.stream().filter(Salida::cancelada).count() == 1 && l.size() == 2);
        }
    }

    private static void probarRastroYPasar() throws Exception {
        rutas.modelo.Parada a = new rutas.modelo.Parada("Miguel Angel Belaez", "Calle 12b # 18 - 175", 11.54, -72.9);
        rutas.modelo.Parada b = new rutas.modelo.Parada("  miguel angel  BELAEZ ", "calle 12b # 18 - 175", 11.54, -72.9);
        rutas.modelo.Parada c = new rutas.modelo.Parada("Miguel Angel Belaez", "Calle 15 # 7 - 20", 11.54, -72.9);
        revisar("mismo cliente: igual nombre y dirección aunque cambien mayúsculas o espacios; otra dirección no",
                rutas.vista.Aplicacion.mismoCliente(a, b) && !rutas.vista.Aplicacion.mismoCliente(a, c));
        List<String> visto = new ArrayList<>();
        NubeRepartidor.Transporte t = (m, u, cab, cuerpo) -> {
            visto.add(m + " " + u);
            return u.contains("/ubicaciones?")
                    ? new NubeRepartidor.Respuesta(200, "[{\"id\":11,\"lat\":11.541,\"lon\":-72.901,\"precision_m\":8},"
                            + "{\"id\":12,\"lat\":11.6,\"lon\":-72.8,\"precision_m\":900},"
                            + "{\"id\":13,\"lat\":11.542,\"lon\":-72.902,\"precision_m\":null}]")
                    : new NubeRepartidor.Respuesta(204, "");
        };
        NubeRepartidor n = new NubeRepartidor("https://p.supabase.co", "sb_secret_x", t);
        List<double[]> r = n.rastro("0f8fad5b-d9cb-469f-a165-70867728950e", 10);
        revisar("rastro: pide solo lo nuevo, en orden, y salta las lecturas malas (más de 150 m de error)",
                r.size() == 2 && r.get(0)[2] == 11 && r.get(1)[2] == 13 && visto.get(0).contains("id=gt.10")
                && visto.get(0).contains("order=id"));
        double[] a1 = {11.5400, -72.9000, 1, 1_000_000}, a2 = {11.5403, -72.9000, 2, 1_005_000};
        double[] lejos = {11.5450, -72.9000, 3, 1_010_000}, tarde = {11.5404, -72.9000, 4, 1_200_000};
        revisar("rastro: une puntos seguidos y cercanos; corta si salta más de 150 m o pasa más de 1 min sin ubicación",
                !rutas.vista.PanelMapa.hueco(a1, a2) && rutas.vista.PanelMapa.hueco(a2, lejos) && rutas.vista.PanelMapa.hueco(a2, tarde));
        n.quitarEntrega(77);
        revisar("pasar el cliente a otro: se borra esa entrega de internet (su enlace deja de servir)",
                visto.get(1).equals("DELETE https://p.supabase.co/rest/v1/entregas_salida?id=eq.77"));
    }

    private static void probarNotificar() {
        String id = "0f8fad5b-d9cb-469f-a165-70867728950e";
        List<String> visto = new ArrayList<>();
        NubeRepartidor.Transporte t = (m, u, c, b) -> {
            visto.add(m + " " + u + " " + c.get("apikey") + " " + b);
            return new NubeRepartidor.Respuesta(200, "{\"enviados\":2,\"borrados\":0,\"celulares\":2}");
        };
        NubeRepartidor n = new NubeRepartidor("https://p.supabase.co", "sb_secret_x", t);
        int r = n.notificar(id, "Tienda el Ramiro", 3, "https://x.github.io/domiruta-repartidor/?s=abc");
        String v = visto.isEmpty() ? "" : visto.get(0);
        revisar("aviso al celular: llama a la función notificar con la clave, el repartidor, la tienda y el enlace", r == 2
                && v.startsWith("POST https://p.supabase.co/functions/v1/notificar sb_secret_x ")
                && v.contains("\"repartidor_id\":\"" + id + "\"") && v.contains("Tienda el Ramiro te asignó una salida")
                && v.contains("3 entregas") && v.contains("?s=abc"));
        visto.clear();
        revisar("aviso al celular: sin repartidor en Supabase no llama", n.notificar("", "T", 1, "u") == -1 && visto.isEmpty());
        revisar("aviso al celular: código raro no llama", n.notificar("x'; drop", "T", 1, "u") == -1 && visto.isEmpty());
        NubeRepartidor.Transporte caido = (m, u, c, b) -> { throw new java.net.ConnectException("sin red"); };
        NubeRepartidor.Transporte sinFuncion = (m, u, c, b) -> new NubeRepartidor.Respuesta(404, "{\"error\":\"no\"}");
        revisar("aviso al celular: sin internet o sin la función no se rompe nada",
                new NubeRepartidor("https://p.supabase.co", "k", caido).notificar(id, "T", 1, "u") == -1
                && new NubeRepartidor("https://p.supabase.co", "k", sinFuncion).notificar(id, "T", 1, "u") == -1);
    }

    private static void probarCorrecciones() throws Exception {
        NubeRepartidor.Transporte t = (m, u, c, b) -> u.contains("/correcciones?procesada=is.false")
                ? new NubeRepartidor.Respuesta(200, "[{\"id\":7,\"lat\":11.5451,\"lon\":-72.9061,\"distancia_m\":85,"
                        + "\"estado\":\"pendiente\",\"repartidor\":\"Carlos Pérez\",\"creada\":\"2026-11-02T15:00:00+00:00\","
                        + "\"entregas_salida\":{\"nombre\":\"Elianis\",\"direccion\":\"Calle 15 # 7-20\",\"lat\":11.5444,\"lon\":-72.9066}},"
                        + "{\"id\":8,\"lat\":null,\"lon\":null,\"estado\":\"aplicada\"}]")
                : new NubeRepartidor.Respuesta(204, "");
        List<rutas.modelo.Correccion> l = new NubeRepartidor("https://p.supabase.co", "sb_secret_x", t).correccionesNuevas();
        rutas.modelo.Correccion c = l.isEmpty() ? null : l.get(0);
        revisar("correcciones: lee el punto nuevo, el viejo, la dirección y quién la movió (y salta las dañadas)",
                l.size() == 1 && c.idNube == 7 && c.lat == 11.5451 && c.latViejo == 11.5444 && c.nombre.equals("Elianis")
                && c.direccion.equals("Calle 15 # 7-20") && c.distanciaM == 85 && c.pendiente() && c.repartidor.equals("Carlos Pérez"));
        File tmp = java.nio.file.Files.createTempDirectory("corr").toFile();
        try (BaseDatos bd = new BaseDatos(new File(tmp, "domiruta.db"))) {
            boolean nueva = bd.guardarCorreccion(c), otraVez = bd.guardarCorreccion(c);
            revisar("correcciones: se guarda una sola vez aunque llegue dos veces", nueva && !otraVez
                    && bd.correccionesPendientes().size() == 1);
            bd.marcarCorreccion(7, rutas.modelo.Correccion.DESCARTADA);
            revisar("correcciones: al descartarla ya no queda pendiente", bd.correccionesPendientes().isEmpty());
        }
    }

    private static void probarConexion() {
        revisar("conexión: sin error = en línea", rutas.vista.Seguimiento.clasificarParaPruebas("", () -> false).equals("EN_LINEA"));
        revisar("conexión: no conecta y tampoco hay internet = sin internet",
                rutas.vista.Seguimiento.clasificarParaPruebas("No hay internet o Supabase no responde", () -> false).equals("SIN_INTERNET"));
        revisar("conexión: no conecta pero sí hay internet = Supabase no responde",
                rutas.vista.Seguimiento.clasificarParaPruebas("No hay internet o Supabase no responde", () -> true).equals("SIN_BASE"));
        revisar("conexión: la clave mala es de Supabase (sin preguntar por internet)",
                rutas.vista.Seguimiento.clasificarParaPruebas("Supabase no aceptó la clave secreta. Revise Configuración › Repartidor.",
                        () -> { throw new AssertionError("no debía preguntar"); }).equals("SIN_BASE"));
    }

    // ------------------------------------------------------------------

    private static void probarRutaNube() throws Exception {
        List<double[]> recta = new ArrayList<>();
        for (int i = 0; i <= 10; i++) { // puntos en línea recta: sobran los del medio
            recta.add(new double[]{11.54 + i * 0.0001, -72.90});
        }
        List<double[]> s = RutaNube.puntos(grafoDe(recta), rango(recta.size()));
        revisar("ruta: en una recta quedan solo los extremos", s.size() == 2 && s.get(0)[0] == 11.54);

        Mapa mapa = CargadorMapa.cargar(new File("datos/riohacha"));
        Grafo g = mapa.getGrafo();
        ModeloTrafico trafico = new ModeloTrafico(mapa, new File("datos/trafico.txt"));
        int tienda = g.nodoMasCercano(Geo.x(-72.9075), Geo.y(11.5445), null);
        int a = g.nodoMasCercano(Geo.x(-72.9012), Geo.y(11.5400), null);
        int b = g.nodoMasCercano(Geo.x(-72.9062), Geo.y(11.5352), null);
        List<Integer> nodos = RutaNube.nodosEnOrden(g, trafico, tienda, List.of(a, b));
        revisar("ruta: une la tienda con las paradas en orden por las calles", nodos.size() > 10
                && nodos.get(0) == tienda && nodos.get(nodos.size() - 1) == b && nodos.contains(a));
        boolean seguidos = true;
        for (int i = 1; i < nodos.size(); i++) {
            seguidos &= g.buscarArista(nodos.get(i - 1), nodos.get(i)) != null;
        }
        revisar("ruta: cada paso es una calle de verdad (no pasa por encima de las casas)", seguidos);

        List<double[]> pts = RutaNube.puntos(g, nodos);
        double metrosNodos = 0;
        for (int i = 1; i < nodos.size(); i++) {
            metrosNodos += Geo.haversine(g.getLat(nodos.get(i - 1)), g.getLon(nodos.get(i - 1)),
                    g.getLat(nodos.get(i)), g.getLon(nodos.get(i)));
        }
        double m = RutaNube.metros(pts);
        revisar("ruta: simplificada pesa menos y mide casi lo mismo (" + nodos.size() + " -> " + pts.size()
                + " puntos, " + Math.round(m) + " m)", pts.size() <= nodos.size() && Math.abs(m - metrosNodos) < 0.02 * metrosNodos);
        boolean cerca = true; // ningún nodo del recorrido queda a más de 3 m de la línea simplificada
        for (int n : nodos) {
            cerca &= distanciaALinea(g.getLat(n), g.getLon(n), pts) <= 3;
        }
        revisar("ruta: al simplificar ningún punto se corre más de 3 m", cerca);

        List<List<double[]>> calles = RutaNube.calles(g, mapa.getVias(), pts);
        int total = 0;
        boolean todasCerca = true;
        for (List<double[]> c : calles) {
            total += c.size();
            double min = Double.MAX_VALUE; // de la ruta a la calle (una avenida larga puede tener sus puntos lejos)
            for (double[] q : pts) {
                min = Math.min(min, distanciaALinea(q[0], q[1], c));
            }
            for (double[] q : c) {
                min = Math.min(min, distanciaALinea(q[0], q[1], pts));
            }
            todasCerca &= min <= 430; // 200 m redondeado a celdas de 100 m (en diagonal llega a unos 420 m)
        }
        revisar("ruta: calles de alrededor (" + calles.size() + " calles, " + total + " puntos), todas cerca de la ruta",
                calles.size() > 5 && calles.size() <= RutaNube.MAX_CALLES && total <= 20000 && todasCerca);
        String json = Json.escribir(RutaNube.callesComoJson(calles));
        revisar("ruta: lo que se sube no es pesado (" + json.length() / 1024 + " KB)", json.length() < 600_000);
        revisar("ruta: sin paradas queda solo la tienda", RutaNube.nodosEnOrden(g, trafico, tienda, List.of()).equals(List.of(tienda)));
    }

    private static Grafo grafoDe(List<double[]> p) {
        Grafo g = new Grafo(p.size());
        for (int i = 0; i < p.size(); i++) {
            g.setNodo(i, p.get(i)[0], p.get(i)[1], ' ');
        }
        return g;
    }

    private static List<Integer> rango(int n) {
        List<Integer> l = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            l.add(i);
        }
        return l;
    }

    private static double distanciaALinea(double lat, double lon, List<double[]> p) {
        double px = Geo.x(lon), py = Geo.y(lat), min = Double.MAX_VALUE;
        for (int i = 1; i < p.size(); i++) {
            double ax = Geo.x(p.get(i - 1)[1]), ay = Geo.y(p.get(i - 1)[0]), bx = Geo.x(p.get(i)[1]), by = Geo.y(p.get(i)[0]);
            double dx = bx - ax, dy = by - ay, l2 = dx * dx + dy * dy;
            double t = l2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / l2));
            min = Math.min(min, Math.hypot(px - (ax + t * dx), py - (ay + t * dy)));
        }
        return p.size() == 1 ? Math.hypot(px - Geo.x(p.get(0)[1]), py - Geo.y(p.get(0)[0])) : min;
    }

    // ------------------------------------------------------------------

    /** Supabase falso: guarda lo que se le pide y contesta como el de verdad con en-vivo.sql. */
    static class Falso implements NubeRepartidor.Transporte {
        final List<String> llamadas = new ArrayList<>();
        final List<String> cuerpos = new ArrayList<>();
        long adelanto; // cuánto va adelantado el reloj de Supabase (ms)

        @Override
        public NubeRepartidor.Respuesta enviar(String metodo, String url, Map<String, String> cab, String cuerpo) {
            NubeRepartidor.Respuesta r = responder(metodo, url, cuerpo);
            return new NubeRepartidor.Respuesta(r.codigo, r.cuerpo, adelanto == 0 ? 0 : System.currentTimeMillis() + adelanto);
        }

        private NubeRepartidor.Respuesta responder(String metodo, String url, String cuerpo) {
            llamadas.add(metodo + " " + url.substring(url.indexOf("/rest/v1/") + 9));
            cuerpos.add(cuerpo);
            String r = url.substring(url.indexOf("/rest/v1/") + 9);
            if (metodo.equals("POST") && r.startsWith("salidas")) {
                return new NubeRepartidor.Respuesta(201, "[{\"id\":\"uuid-1\"}]");
            }
            if (metodo.equals("PATCH") && r.startsWith("repartidores?id=eq.")) {
                String id = r.substring(19, 19 + 36);
                return new NubeRepartidor.Respuesta(200, id.startsWith("9") ? "[]" : "[{\"id\":\"" + id + "\"}]");
            }
            if (metodo.equals("GET") && r.startsWith("repartidores?id=eq.")) {
                String id = r.substring(19, 19 + 36);
                return new NubeRepartidor.Respuesta(200, id.startsWith("9") ? "[]" : "[{\"id\":\"" + id + "\"}]");
            }
            if (metodo.equals("POST") && r.startsWith("entregas_salida")) {
                return new NubeRepartidor.Respuesta(201, "[{\"id\":71,\"orden\":1,\"codigo_cliente\":\"K7M2QX\","
                        + "\"enlace_cliente\":\"abcdefghijkmnpqrstuvwxyz\"},{\"id\":72,\"orden\":2,"
                        + "\"codigo_cliente\":\"P3R8TZ\",\"enlace_cliente\":\"zyxwvutsrqpnmkjihgfedcba\"}]");
            }
            if (metodo.equals("GET") && r.startsWith("salidas?id=eq.")) {
                return new NubeRepartidor.Respuesta(200, "[{\"regresando\":null,\"lat\":11.54,\"lon\":-72.91,"
                        + "\"ubicacion_en\":null,\"aceptada\":null,\"rechazada\":null,\"motivo_rechazo\":\"\","
                        + "\"vence_en\":\"2026-11-02T15:10:00+00:00\",\"ubicacion_pausada\":true}]");
            }
            if (metodo.equals("GET") && r.startsWith("entregas_salida")) {
                return new NubeRepartidor.Respuesta(200, "[]");
            }
            if (metodo.equals("POST") && r.startsWith("repartidores")) {
                return new NubeRepartidor.Respuesta(201, "[{\"id\":\"1b4e28ba-2fa1-41d2-883f-0016d3cca427\","
                        + "\"enlace\":\"" + Json.texto((Map<?, ?>) Json.leer(cuerpo), "enlace") + "\"}]");
            }
            if (metodo.equals("GET") && r.startsWith("repartidores")) {
                return new NubeRepartidor.Respuesta(200, "[{\"id\":\"1b4e28ba-2fa1-41d2-883f-0016d3cca427\",\"disponible\":true,"
                        + "\"activo\":true,\"visto_en\":\"2026-11-02T14:00:00+00:00\",\"dispositivo_cambio\":null},"
                        + "{\"id\":\"2c5f39cb-3ab2-42e3-994a-1127e4ddb538\",\"disponible\":false,\"activo\":true,"
                        + "\"visto_en\":null,\"dispositivo_cambio\":\"2026-11-02T14:05:00+00:00\"}]");
            }
            return new NubeRepartidor.Respuesta(204, "");
        }
    }

    private static Salida salida() {
        Salida s = new Salida();
        s.repartidor = "Carlos Pérez";
        Parada a = new Parada("Elianis", "Calle 15 # 7-20", 11.5442, -72.907);
        a.setTelefono("3127778899");
        Parada b = new Parada("Pedro", "Carrera 7H # 25-10", 11.539, -72.915);
        s.entregas.add(new Salida.Entrega(a, 12500));
        s.entregas.add(new Salida.Entrega(b, 0));
        return s;
    }

    private static void probarSubida() throws Exception {
        Falso f = new Falso();
        NubeRepartidor n = new NubeRepartidor("https://p.supabase.co", "sb_secret_prueba", f);
        Salida s = salida();
        NubeRepartidor.Extra x = new NubeRepartidor.Extra();
        x.ruta = List.of(new double[]{11.5445123456, -72.9075}, new double[]{11.54, -72.9012});
        x.metros = 690.4;
        x.calles = List.of(List.of(new double[]{11.5, -72.9}, new double[]{11.51, -72.91}));
        x.tiendaLat = 11.5445;
        x.tiendaLon = -72.9075;
        x.minutosParaResponder = 10;
        x.repartidorId = "1b4e28ba-2fa1-41d2-883f-0016d3cca427";
        n.subir("codigo-largo", s.repartidor, "Tienda Prueba", s.entregas, x).aplicar(s);
        String c = f.cuerpos.get(0);
        revisar("subida: lleva la ruta redondeada, la distancia y las calles", c.contains("\"ruta\":[[11.544512,-72.9075],[11.54,-72.9012]]")
                && c.contains("\"distancia_m\":690") && c.contains("\"calles\":[[[11.5,-72.9],[11.51,-72.91]]]"));
        java.time.OffsetDateTime vence = java.time.OffsetDateTime.parse(Json.texto((Map<?, ?>) Json.leer(c), "vence_en"));
        revisar("subida: lleva la tienda, el vencimiento (10 min, con su zona) y el repartidor", c.contains("\"tienda_lat\":11.5445")
                && Math.abs(java.time.Duration.between(java.time.OffsetDateTime.now().plusMinutes(10), vence).getSeconds()) < 5
                && c.contains("\"repartidor_id\":\"1b4e28ba-2fa1-41d2-883f-0016d3cca427\"")
                && s.venceEn != null && Math.abs(java.time.Duration.between(LocalDateTime.now().plusMinutes(10), s.venceEn).getSeconds()) < 5);
        revisar("subida: pide los códigos del cliente y los guarda en cada entrega",
                f.llamadas.get(1).contains("select=id,orden,codigo_cliente,enlace_cliente")
                && s.entregas.get(0).codigoCliente.equals("K7M2QX") && s.entregas.get(1).enlaceCliente.equals("zyxwvutsrqpnmkjihgfedcba")
                && s.entregas.get(0).idNube == 71);

        Falso f2 = new Falso();
        Salida viejo = salida();
        new NubeRepartidor("https://p.supabase.co", "sb_secret_prueba", f2).subir("c", "Ana", "", viejo.entregas, null);
        revisar("subida sin lo nuevo: igual que antes (sin ruta ni códigos)", !f2.cuerpos.get(0).contains("ruta")
                && !f2.cuerpos.get(0).contains("vence_en") && f2.llamadas.get(1).endsWith("select=id,orden"));
        NubeRepartidor.Extra vacio = new NubeRepartidor.Extra();
        Falso f3 = new Falso();
        new NubeRepartidor("https://p.supabase.co", "sb_secret_prueba", f3).subir("c", "Ana", "", salida().entregas, vacio);
        revisar("subida sin ruta (no se pudo calcular): no manda ruta, tienda ni vencimiento vacíos",
                !f3.cuerpos.get(0).contains("ruta") && !f3.cuerpos.get(0).contains("tienda_lat")
                && !f3.cuerpos.get(0).contains("repartidor_id") && !f3.cuerpos.get(0).contains("NaN"));

        // reloj del computador atrasado 15 min: el vencimiento se cuenta con la hora de Supabase
        Falso f4 = new Falso();
        f4.adelanto = 15 * 60_000L;
        NubeRepartidor n4 = new NubeRepartidor("https://p.supabase.co", "sb_secret_prueba", f4);
        n4.leer("uuid-1"); // cualquier respuesta trae la hora de Supabase
        NubeRepartidor.Extra x4 = new NubeRepartidor.Extra();
        x4.minutosParaResponder = 10;
        Salida s4 = salida();
        n4.subir("c4", "Ana", "", s4.entregas, x4).aplicar(s4);
        java.time.OffsetDateTime v4 = java.time.OffsetDateTime.parse(Json.texto((Map<?, ?>) Json.leer(f4.cuerpos.get(2)), "vence_en"));
        revisar("reloj del computador atrasado: el vencimiento sale de la hora de Supabase y aquí se ve a 10 min",
                Math.abs(java.time.Duration.between(java.time.OffsetDateTime.now().plusMinutes(25), v4).getSeconds()) < 5
                && Math.abs(java.time.Duration.between(LocalDateTime.now().plusMinutes(10), s4.venceEn).getSeconds()) < 5);
        NubeRepartidor.Estado e4 = n4.leer("uuid-1");
        revisar("las horas de Supabase se pasan al reloj de este computador", e4.venceEn != null
                && e4.venceEn.equals(java.time.OffsetDateTime.parse("2026-11-02T15:10:00Z")
                        .atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDateTime().minusNanos(n4.getDesfaseMs() * 1_000_000L)));

        NubeRepartidor.Estado est = n.leer("uuid-1");
        revisar("lee hasta cuándo puede aceptar y si pausó la ubicación", est.venceEn != null
                && est.venceEn.equals(java.time.OffsetDateTime.parse("2026-11-02T15:10:00Z")
                        .atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDateTime()) && est.ubicacionPausada);
        revisar("enlaces: personal (?r=) y del cliente (?p=)",
                NubeRepartidor.enlacePersonal("https://x.github.io/rep", "abc").equals("https://x.github.io/rep/?r=abc")
                && NubeRepartidor.enlaceCliente("https://x.github.io/rep/", "xyz").equals("https://x.github.io/rep/?p=xyz"));
    }

    private static void probarRepartidores() throws Exception {
        String e1 = NubeRepartidor.nuevoEnlacePersonal(), e2 = NubeRepartidor.nuevoEnlacePersonal();
        revisar("enlace personal: 32 letras sin confusas (sin l, o, 0, 1) y distinto cada vez",
                e1.length() == 32 && e1.matches("[a-km-np-z2-9]+") && !e1.equals(e2));
        Falso f = new Falso();
        NubeRepartidor n = new NubeRepartidor("https://p.supabase.co", "sb_secret_prueba", f);
        String[] r = n.crearRepartidor("  Carlos Pérez  ", "300 111 2233");
        revisar("crear repartidor: devuelve su id y su enlace", r[0].equals("1b4e28ba-2fa1-41d2-883f-0016d3cca427")
                && r[1].length() == 32 && f.cuerpos.get(0).contains("\"nombre\":\"Carlos Pérez\""));
        f.llamadas.clear();
        f.cuerpos.clear();
        String nuevo = n.revocarEnlace(r[0]);
        revisar("cambiar enlace: pone uno nuevo, suelta el celular y borra sus notificaciones",
                nuevo.length() == 32 && !nuevo.equals(r[1]) && f.llamadas.size() == 2
                && f.llamadas.get(0).equals("PATCH repartidores?id=eq." + r[0] + "&select=id")
                && f.cuerpos.get(0).contains("\"dispositivo\":null") && f.cuerpos.get(0).contains("\"enlace\":\"" + nuevo + "\"")
                && f.llamadas.get(1).equals("DELETE suscripciones_push?repartidor=eq." + r[0]));
        boolean rechazo = false;
        try {
            n.revocarEnlace("x&enlace=eq.abc");
        } catch (java.io.IOException ex) {
            rechazo = true;
        }
        revisar("un id raro no llega a la dirección de Supabase", rechazo && f.llamadas.size() == 2);
        revisar("si ya no está en Supabase, cambiar enlace avisa (null) y no borra nada más",
                n.revocarEnlace("9b4e28ba-2fa1-41d2-883f-0016d3cca427") == null && f.llamadas.size() == 3);
        revisar("existe en Supabase", n.existeRepartidor(r[0]) && !n.existeRepartidor("9b4e28ba-2fa1-41d2-883f-0016d3cca427"));
        f.llamadas.clear();
        n.actualizarRepartidor(r[0], "Carlos", "300", false);
        revisar("desactivarlo: tampoco sale disponible", f.cuerpos.get(f.cuerpos.size() - 1).contains("\"disponible\":false")
                && f.cuerpos.get(f.cuerpos.size() - 1).contains("\"activo\":false"));
        Map<String, NubeRepartidor.EstadoRepartidor> m = n.repartidores();
        NubeRepartidor.EstadoRepartidor c = m.get("1b4e28ba-2fa1-41d2-883f-0016d3cca427");
        NubeRepartidor.EstadoRepartidor o = m.get("2c5f39cb-3ab2-42e3-994a-1127e4ddb538");
        revisar("lee quién está disponible, cuándo abrió la app y si la abrieron en otro celular",
                m.size() == 2 && c.disponible && c.vistoEn != null && c.dispositivoCambio == null
                && !o.disponible && o.vistoEn == null && o.dispositivoCambio != null);
    }

    // ------------------------------------------------------------------

    private static void probarAvisos() {
        LocalDateTime t0 = LocalDateTime.of(2026, 11, 2, 10, 0);
        Salida s = salida();
        s.uuidNube = "uuid-1";
        s.creada = t0;
        s.enviadaEn = t0;
        s.venceEn = t0.plusMinutes(10);
        revisar("avisos: al minuto no hay nada", rutas.vista.Seguimiento.avisosNuevos(s, t0.plusMinutes(1)).isEmpty());
        List<String[]> a3 = rutas.vista.Seguimiento.avisosNuevos(s, t0.plusMinutes(3));
        revisar("avisos: a los 3 minutos sin responder (una sola vez)", a3.size() == 1 && a3.get(0)[0].equals("sin_respuesta")
                && a3.get(0)[1].equals("Carlos no ha respondido")
                && rutas.vista.Seguimiento.avisosNuevos(s, t0.plusMinutes(4)).isEmpty());
        List<String[]> a11 = rutas.vista.Seguimiento.avisosNuevos(s, t0.plusMinutes(11));
        revisar("avisos: al vencer, aviso grave", a11.size() == 1 && a11.get(0)[0].equals("vencida") && a11.get(0)[3].equals("true"));

        Salida r = salida();
        r.uuidNube = "uuid-2";
        r.creada = t0;
        r.aceptadaEn = t0.plusMinutes(1);
        r.ubicacionEn = t0.plusMinutes(2);
        r.leidaEnLinea = true;
        revisar("avisos: aceptó y manda ubicación: nada", rutas.vista.Seguimiento.avisosNuevos(r, t0.plusMinutes(3)).isEmpty());
        List<String[]> sin = rutas.vista.Seguimiento.avisosNuevos(r, t0.plusMinutes(5));
        revisar("avisos: más de 2 min sin ubicación", sin.size() == 1 && sin.get(0)[0].equals("sin_ubicacion"));
        r.ubicacionEn = t0.plusMinutes(6);
        revisar("avisos: vuelve la ubicación y se puede volver a avisar", rutas.vista.Seguimiento.avisosNuevos(r, t0.plusMinutes(7)).isEmpty()
                && rutas.vista.Seguimiento.avisosNuevos(r, t0.plusMinutes(9)).size() == 1);
        r.ubicacionPausada = true;
        List<String[]> p = rutas.vista.Seguimiento.avisosNuevos(r, t0.plusMinutes(10));
        revisar("avisos: pausó la ubicación (y no se suma «sin ubicación»)", p.size() == 1 && p.get(0)[0].equals("pausada")
                && rutas.vista.Seguimiento.avisosNuevos(r, t0.plusMinutes(20)).isEmpty());
        r.entregas.forEach(e -> e.estado = Salida.Entrega.ENTREGADO);
        r.revisarEstado();
        r.ubicacionPausada = false;
        revisar("avisos: ya entregó todo: no se avisa de la ubicación", rutas.vista.Seguimiento.avisosNuevos(r, t0.plusMinutes(30)).isEmpty());

        Salida reinicio = salida();
        reinicio.uuidNube = "u";
        reinicio.creada = t0;
        reinicio.enviadaEn = t0;
        reinicio.venceEn = t0.plusMinutes(10);
        revisar("al abrir el programa no vuelve a sonar lo que ya había vencido antes",
                rutas.vista.Seguimiento.avisosNuevos(reinicio, t0.plusMinutes(30), t0.plusMinutes(20), true).isEmpty());
        Salida sinLeer = salida();
        sinLeer.uuidNube = "u";
        sinLeer.creada = t0;
        sinLeer.aceptadaEn = t0;
        revisar("sin una lectura de Supabase no avisa de la ubicación (no se sabe si la pausó)",
                rutas.vista.Seguimiento.avisosNuevos(sinLeer, t0.plusMinutes(30)).isEmpty());
        sinLeer.leidaEnLinea = true;
        revisar("si la tienda no tiene internet, no culpa al repartidor de la ubicación",
                rutas.vista.Seguimiento.avisosNuevos(sinLeer, t0.plusMinutes(30), LocalDateTime.MIN, false).isEmpty()
                && rutas.vista.Seguimiento.avisosNuevos(sinLeer, t0.plusMinutes(30), LocalDateTime.MIN, true).size() == 1);
        Salida sinInternet = salida();
        sinInternet.enviadaEn = t0;
        revisar("avisos: una salida sin enlace no avisa", rutas.vista.Seguimiento.avisosNuevos(sinInternet, t0.plusMinutes(30)).isEmpty());
        Salida v = salida();
        v.uuidNube = "u";
        v.venceEn = LocalDateTime.now().minusMinutes(1);
        Salida w = salida();
        w.uuidNube = "u";
        w.venceEn = LocalDateTime.now().minusMinutes(1);
        w.aceptadaEn = LocalDateTime.now().minusMinutes(5);
        revisar("vencida: solo si no ha respondido", v.vencida() && !w.vencida());
    }

    private static void probarBaseDatos() throws Exception {
        File tmp = java.nio.file.Files.createTempDirectory("vivo").toFile();
        File archivo = new File(tmp, "domiruta.db");
        Salida s = salida();
        s.uuidNube = "uuid-3";
        s.venceEn = LocalDateTime.of(2026, 11, 2, 10, 10);
        s.entregas.get(0).codigoCliente = "K7M2QX";
        s.entregas.get(0).enlaceCliente = "abcdefghijkmnpqrstuvwxyz";
        Domiciliario d = new Domiciliario(0, "Carlos", "3001112233", Domiciliario.Tipo.FIJO);
        d.idNube = "1b4e28ba-2fa1-41d2-883f-0016d3cca427";
        d.enlace = "pruebaenlace0000000000000000000000";
        try (BaseDatos bd = new BaseDatos(archivo)) {
            bd.guardarSalida(s);
            bd.guardarDomiciliario(d);
        }
        try (BaseDatos bd = new BaseDatos(archivo)) {
            Salida l = bd.cargarSalidas(LocalDateTime.now().minusDays(1)).get(0);
            revisar("base: guarda el vencimiento y los códigos del cliente", s.venceEn.equals(l.venceEn)
                    && l.entregas.get(0).codigoCliente.equals("K7M2QX")
                    && l.entregas.get(0).enlaceCliente.equals("abcdefghijkmnpqrstuvwxyz")
                    && l.entregas.get(1).codigoCliente.isEmpty());
            Domiciliario c = bd.cargarDomiciliarios().get(0);
            revisar("base: guarda el id y el enlace personal del domiciliario", c.enNube()
                    && c.idNube.equals(d.idNube) && c.enlace.equals(d.enlace));
            Domiciliario viejo = new Domiciliario(c.id, c.nombre, "3009998877", c.tipo); // como una lista vieja en pantalla
            bd.guardarDomiciliario(viejo);
            Domiciliario c2 = bd.cargarDomiciliarios().get(0);
            revisar("base: editarlo desde una lista vieja no le borra su enlace", c2.enlace.equals(d.enlace)
                    && c2.idNube.equals(d.idNube) && c2.celular.equals("3009998877"));
            bd.guardarEnlaceNube(c.id, d.idNube, "enlacenuevo00000000000000000000000");
            revisar("base: cambiar solo el enlace", bd.cargarDomiciliarios().get(0).enlace.equals("enlacenuevo00000000000000000000000")
                    && bd.cargarDomiciliarios().get(0).celular.equals("3009998877"));
        }
    }

    private static void probarMensajes() {
        Salida s = salida();
        String m = rutas.logica.MensajesWhatsApp.salida("Tienda", LocalTime.of(10, 0), "Carlos", s.entregas,
                "https://x/?s=abc", "", LocalTime.of(10, 10));
        revisar("mensaje: dice hasta qué hora puede aceptar", m.contains("toca *Acepto* (o *No puedo*) antes de las *10:10 a.m.*:\nhttps://x/?s=abc"));
        String p = rutas.logica.MensajesWhatsApp.enlacePersonal("Tienda el Ramiro", "Carlos Pérez", "https://x/?r=abc");
        String c = rutas.logica.MensajesWhatsApp.seguimiento("Tienda el Ramiro", "Elianis Pérez",
                "https://x/?p=abcdefghijkmnpqrstuvwxyz", "https://x/?p=", "K7M2QX");
        revisar("mensaje: seguimiento al cliente con enlace y código, sin datos de otros",
                c.startsWith("*TIENDA EL RAMIRO*") && c.contains("Hola *Elianis*") && c.contains("https://x/?p=abcdefghijkmnpqrstuvwxyz")
                && c.contains("código *K7M2QX*") && !c.contains("3127778899"));
        revisar("mensaje: enlace personal", p.startsWith("*TIENDA EL RAMIRO*") && p.contains("Hola *Carlos*")
                && p.contains("https://x/?r=abc") && p.contains("No lo compartas"));
    }
}
