package rutas;

import static rutas.Pruebas.revisar;

import java.io.File;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import rutas.logica.BaseDatos;
import rutas.logica.Json;
import rutas.logica.NubeRepartidor;
import rutas.modelo.Parada;
import rutas.modelo.Salida;

/** Pruebas de las salidas del repartidor y de la conexión con Supabase (con un Supabase falso). */
public class PruebasRepartidor {

    static void correr() throws Exception {
        System.out.println("--- Repartidor (salidas y Supabase) ---");
        probarJson();
        probarNube();
        probarBaseDatos();
        probarMensajes();
    }

    private static void probarMensajes() {
        Salida s = salidaDePrueba();
        s.entregas.get(0).parada.setVentaNumero(123);
        s.entregas.get(0).parada.setProductos(6);
        String m = rutas.logica.MensajesWhatsApp.salida("Tienda el Ramiro", java.time.LocalTime.of(10, 20), "José Ruiz",
                s.entregas, "https://x.github.io/rep/?s=abc", "https://www.google.com/maps/dir/1,2/");
        System.out.println("------ mensaje al domiciliario ------\n" + m + "\n-------------------------------------");
        revisar("mensaje: título, saludo y total en negrita", m.startsWith("*TIENDA EL RAMIRO*\nSalida de las 10:20 a.m.")
                && m.contains("Hola *José*, tienes *2 entregas*.") && m.contains("Cobras en total *$12.500*"));
        revisar("mensaje: el enlace de marcar va antes que el del mapa (sale su tarjeta)",
                m.indexOf("?s=abc") > 0 && m.indexOf("?s=abc") < m.indexOf("google.com/maps"));
        revisar("mensaje: venta, productos, celular y cambio", m.contains("Venta N.º 00123 · 6 productos")
                && m.contains("Cel. 312 777 8899") && m.contains("paga con $20.000 → lleva *$7.500* de cambio")
                && m.contains("_Ya pagó_"));
        String sin = rutas.logica.MensajesWhatsApp.salida("", java.time.LocalTime.of(10, 20), "Ana", s.entregas, "", "");
        revisar("mensaje: pide aceptar y sin internet no lleva esa línea", m.contains("toca *Acepto*") && !sin.contains("Acepto") && !sin.contains("Ruta en el mapa"));
        String q = rutas.logica.MensajesWhatsApp.preguntar("Tienda el Ramiro", List.of(s.entregas.get(0).parada), 3000);
        revisar("mensaje: preguntar quién puede", q.startsWith("*Hay domicilio en Tienda el Ramiro*\n1 entrega · domicilios *$3.000*")
                && q.endsWith("¿Quién puede venir?"));
        rutas.modelo.Parada a = s.entregas.get(0).parada;
        rutas.modelo.Parada c = new rutas.modelo.Parada(a.getNombre(), a.getDireccion(), a.getLat(), a.getLon());
        c.copiarDatosDe(a);
        revisar("al editar la entrega no se pierde el número de la venta", c.getVentaNumero() == 123 && c.getProductos() == 6);
    }

    private static void probarJson() {
        Object o = Json.leer("[{\"id\":\"a-1\",\"n\":12,\"x\":-72.9,\"t\":\"Calle 15 # 7-20\\nRef: \\\"azul\\\"\",\"f\":null,"
                + "\"b\":true,\"u\":\"\\u00f1\"}]");
        Map<?, ?> m = (Map<?, ?>) ((List<?>) o).get(0);
        revisar("json: lee texto, números, null, true y \\u00f1", Json.texto(m, "id").equals("a-1")
                && Json.entero(m, "n") == 12 && Json.decimal(m, "x") == -72.9
                && Json.texto(m, "t").equals("Calle 15 # 7-20\nRef: \"azul\"") && m.get("f") == null
                && Boolean.TRUE.equals(m.get("b")) && Json.texto(m, "u").equals("ñ"));
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("texto", "Dijo \"hola\"\ny se fue");
        w.put("cobrar", 12500);
        w.put("lat", 11.5442);
        w.put("nada", null);
        String s = Json.escribir(w);
        revisar("json: escribe y se vuelve a leer igual", s.equals("{\"texto\":\"Dijo \\\"hola\\\"\\ny se fue\","
                + "\"cobrar\":12500,\"lat\":11.5442,\"nada\":null}") && Json.leer(s).equals(w.entrySet().stream()
                .collect(LinkedHashMap::new, (mm, e) -> mm.put(e.getKey(), e.getValue() instanceof Integer
                        ? Double.valueOf((Integer) e.getValue()) : e.getValue()), Map::putAll)));
        boolean malo = false;
        try {
            Json.leer("{\"a\":}");
        } catch (IllegalArgumentException e) {
            malo = true;
        }
        revisar("json: un texto dañado da error claro", malo);
        int cortados = 0;
        for (String t : new String[]{"[", "{", "\"\\u00", "[{\"a\":1", "{\"a\"", "-"}) {
            try {
                Json.leer(t);
            } catch (IllegalArgumentException e) {
                cortados++;
            }
        }
        revisar("json: un texto cortado a medias también da error claro (no se cae)", cortados == 6);
        Map<String, Object> nan = new LinkedHashMap<>();
        nan.put("lat", Double.NaN);
        revisar("json: un número vacío (NaN) se escribe como null", Json.escribir(nan).equals("{\"lat\":null}"));
    }

    /** Supabase falso: guarda lo que se le pide y responde como PostgREST. */
    static class Falso implements NubeRepartidor.Transporte {
        final List<String> llamadas = new ArrayList<>();
        final List<String> cuerpos = new ArrayList<>();
        final List<Map<String, String>> cabeceras = new ArrayList<>();
        int fallarDesde = Integer.MAX_VALUE;
        NubeRepartidor.Respuesta respuestaRpc;
        int codigo = 200;

        @Override
        public NubeRepartidor.Respuesta enviar(String metodo, String url, Map<String, String> cab, String cuerpo)
                throws java.io.IOException {
            llamadas.add(metodo + " " + url);
            cuerpos.add(cuerpo);
            cabeceras.add(cab);
            if (llamadas.size() >= fallarDesde) {
                throw new java.net.ConnectException("sin red");
            }
            if (codigo != 200) {
                return new NubeRepartidor.Respuesta(codigo, "{\"message\":\"Invalid API key\"}");
            }
            String r = url.substring(url.indexOf("/rest/v1/") + 9);
            if (r.startsWith("rpc/") && respuestaRpc != null) {
                return respuestaRpc;
            }
            if (r.equals("rpc/crear_repartidor")) {
                return new NubeRepartidor.Respuesta(200, "\"rep-nuevo\"");
            }
            if (r.startsWith("rpc/")) {
                return new NubeRepartidor.Respuesta(200, "true");
            }
            if (r.startsWith("repartidores")) {
                return new NubeRepartidor.Respuesta(200, "[{\"id\":\"a\",\"usuario\":\"carlos.p\",\"nombre\":\"Carlos Pérez\","
                        + "\"celular\":\"3001112233\",\"activo\":true},{\"id\":\"b\",\"usuario\":\"jose\",\"nombre\":\"José\","
                        + "\"celular\":\"\",\"activo\":false}]");
            }
            if (metodo.equals("POST") && r.startsWith("salidas")) {
                return new NubeRepartidor.Respuesta(201, "[{\"id\":\"uuid-1\"}]");
            }
            if (metodo.equals("POST") && r.startsWith("entregas_salida?select=id,orden")) {
                return new NubeRepartidor.Respuesta(201, "[{\"id\":71,\"orden\":1},{\"id\":72,\"orden\":2}]");
            }
            if (metodo.equals("POST") && r.startsWith("entregas_salida")) {
                return new NubeRepartidor.Respuesta(201, "[{\"id\":73}]");
            }
            if (metodo.equals("GET") && r.startsWith("salidas?id=eq.")) {
                return new NubeRepartidor.Respuesta(200, "[{\"regresando\":\"2026-10-31T21:00:00+00:00\",\"lat\":11.54,"
                        + "\"lon\":-72.91,\"ubicacion_en\":\"2026-10-31T21:00:05.123456+00:00\",\"aceptada\":null,"
                        + "\"rechazada\":\"2026-10-31T21:00:00+00:00\",\"motivo_rechazo\":\"Muy lejos\"}]");
            }
            if (metodo.equals("GET") && r.contains("&orden=eq.")) {
                return new NubeRepartidor.Respuesta(200, r.contains("orden=eq.9") ? "[{\"id\":99}]" : "[]");
            }
            if (metodo.equals("GET") && r.startsWith("entregas_salida?salida=eq.")) {
                return new NubeRepartidor.Respuesta(200, "[{\"id\":71,\"estado\":\"entregado\",\"motivo\":\"\","
                        + "\"foto_en\":\"2026-10-31T21:00:00+00:00\"},{\"id\":72,\"estado\":\"no_entregado\","
                        + "\"motivo\":\"No abrieron\",\"foto_en\":null}]");
            }
            if (metodo.equals("GET") && r.startsWith("mensajes_salida")) {
                return new NubeRepartidor.Respuesta(200, "[{\"id\":5,\"de\":\"repartidor\",\"texto\":\"Necesito cambio\","
                        + "\"creado\":\"2026-10-31T21:01:00+00:00\",\"leido\":false}]");
            }
            if (metodo.equals("POST") && r.startsWith("mensajes_salida")) {
                return new NubeRepartidor.Respuesta(201, "[{\"id\":6,\"creado\":\"2026-10-31T21:02:00+00:00\"}]");
            }
            return new NubeRepartidor.Respuesta(204, "");
        }
    }

    private static Salida salidaDePrueba() {
        Salida s = new Salida();
        s.codigo = NubeRepartidor.nuevoCodigo();
        s.repartidor = "Moto 1";
        Parada a = new Parada("Elianis", "Calle 15 # 7-20", 11.5442, -72.907);
        a.setTelefono("3127778899");
        a.setPagaCon(20000);
        Parada b = new Parada("Pedro", "Carrera 7H # 25-10", 11.539, -72.915);
        b.setPagado(true);
        s.entregas.add(new Salida.Entrega(a, 12500));
        s.entregas.add(new Salida.Entrega(b, 0));
        return s;
    }

    private static void probarNube() throws Exception {
        String c1 = NubeRepartidor.nuevoCodigo(), c2 = NubeRepartidor.nuevoCodigo();
        revisar("nube: el código del enlace es largo, sin símbolos raros y distinto cada vez",
                c1.length() == 32 && c1.matches("[A-Za-z0-9_-]+") && !c1.equals(c2));
        revisar("nube: enlace del repartidor", NubeRepartidor.enlace("https://x.github.io/rep", "abc")
                .equals("https://x.github.io/rep/?s=abc"));

        Falso f = new Falso();
        NubeRepartidor n = new NubeRepartidor("https://p.supabase.co/", "sb_secret_prueba", f);
        Salida s = salidaDePrueba();
        n.subir(s);
        revisar("nube: subir crea la salida y luego sus entregas", f.llamadas.size() == 2
                && f.llamadas.get(0).equals("POST https://p.supabase.co/rest/v1/salidas?select=id")
                && f.llamadas.get(1).startsWith("POST https://p.supabase.co/rest/v1/entregas_salida"));
        revisar("nube: guarda el uuid y el id de cada entrega", s.uuidNube.equals("uuid-1")
                && s.entregas.get(0).idNube == 71 && s.entregas.get(1).idNube == 72);
        String cuerpo = f.cuerpos.get(1);
        revisar("nube: sube nombre, dirección, celular, cobrar y paga con", cuerpo.contains("\"nombre\":\"Elianis\"")
                && cuerpo.contains("\"cobrar\":12500") && cuerpo.contains("\"paga_con\":20000")
                && cuerpo.contains("\"telefono\":\"3127778899\"") && cuerpo.contains("\"salida\":\"uuid-1\""));
        Map<String, String> cab = f.cabeceras.get(0);
        revisar("nube: la clave secreta va en apikey (no en Authorization)", "sb_secret_prueba".equals(cab.get("apikey"))
                && !cab.containsKey("Authorization") && "return=representation".equals(cab.get("Prefer")));

        NubeRepartidor.Estado est = n.leer("uuid-1");
        revisar("nube: lee estados, motivo, foto y ubicación", est.regresando && est.rechazada != null
                && est.motivoRechazo.equals("Muy lejos") && est.aceptada == null && est.lat == 11.54
                && est.entregas.get(71L)[0].equals("entregado") && est.entregas.get(71L)[2].equals("true")
                && est.entregas.get(72L)[1].equals("No abrieron") && est.ubicacionEn != null);
        List<Salida.Mensaje> msj = n.mensajes("uuid-1", 0);
        revisar("nube: lee los mensajes del repartidor", msj.size() == 1 && !msj.get(0).deTienda
                && msj.get(0).texto.equals("Necesito cambio") && msj.get(0).idNube == 5);
        Salida.Mensaje mio = n.escribir("uuid-1", "  Ya te mando el cambio  ");
        revisar("nube: la tienda escribe (sin espacios de más)", mio.deTienda && mio.idNube == 6
                && f.cuerpos.get(f.cuerpos.size() - 1).contains("\"de\":\"tienda\"")
                && f.cuerpos.get(f.cuerpos.size() - 1).contains("\"texto\":\"Ya te mando el cambio\""));
        int antes = f.llamadas.size();
        Salida.Entrega nueva = new Salida.Entrega(new Parada("Luz", "Calle 12 # 11-40", 11.5447, -72.9077), 8000);
        s.entregas.add(nueva);
        n.agregar(s, nueva);
        revisar("nube: agregar una entrega en camino la sube y quita el «regresando»", nueva.idNube == 73
                && f.llamadas.get(antes).contains("&orden=eq.3")
                && f.llamadas.get(antes + 2).startsWith("PATCH https://p.supabase.co/rest/v1/salidas?id=eq.uuid-1")
                && f.cuerpos.get(antes + 2).equals("{\"regresando\":null}")
                && f.cuerpos.get(antes + 1).contains("\"orden\":3"));
        int antes2 = f.llamadas.size();
        long repetida = n.agregar("uuid-1", 9, nueva);
        revisar("nube: si un intento anterior sí llegó, no la sube dos veces", repetida == 99
                && f.llamadas.size() == antes2 + 1);

        // errores con mensaje claro
        Falso sinRed = new Falso();
        sinRed.fallarDesde = 1;
        String e1 = error(() -> new NubeRepartidor("https://p.supabase.co", "sb_secret_x", sinRed).leer("u"));
        Falso malaClave = new Falso();
        malaClave.codigo = 401;
        String e2 = error(() -> new NubeRepartidor("https://p.supabase.co", "sb_secret_x", malaClave).probar());
        String e3 = error(() -> new NubeRepartidor("", "", new Falso()).probar());
        revisar("nube: sin internet / clave mala / sin configurar dan mensajes claros",
                e1.startsWith("No hay internet") && e2.contains("clave secreta") && e3.contains("Configuración"));
        Falso aMedias = new Falso();
        aMedias.fallarDesde = 2; // la salida se crea pero las entregas no
        Salida s2 = salidaDePrueba();
        String e4 = error(() -> new NubeRepartidor("https://p.supabase.co", "sb_secret_x", aMedias).subir(s2));
        revisar("nube: si fallan las entregas no queda la salida a medias", !e4.isEmpty() && s2.uuidNube.isEmpty()
                && aMedias.llamadas.get(2).startsWith("DELETE"));

        // un wifi con registro responde 200 con una página: error claro, no se cae
        NubeRepartidor.Transporte portal = (m, u, c, b) -> new NubeRepartidor.Respuesta(200, "<html>Inicie sesión</html>");
        NubeRepartidor.Transporte raro = (m, u, c, b) -> new NubeRepartidor.Respuesta(200, "[1,2]");
        String e5 = error(() -> new NubeRepartidor("https://p.supabase.co", "sb_secret_x", portal).leer("u"));
        String e6 = error(() -> new NubeRepartidor("https://p.supabase.co", "sb_secret_x", raro).mensajes("u", 0));
        revisar("nube: una respuesta que no es de Supabase da error claro", e5.contains("no se entiende")
                && e6.contains("no se entiende"));
        Falso cierre = new Falso();
        NubeRepartidor nc = new NubeRepartidor("https://p.supabase.co", "sb_secret_x", cierre);
        nc.cerrar("uuid-1");
        nc.fotos("uuid-1");
        revisar("nube: al cerrar primero marca cerrada y pide las fotos que haya", cierre.llamadas.get(0)
                .equals("PATCH https://p.supabase.co/rest/v1/salidas?id=eq.uuid-1") && cierre.cuerpos.get(0).equals("{\"cerrada\":true}")
                && cierre.llamadas.get(1).contains("foto=not.is.null"));
        Salida s3 = salidaDePrueba();
        NubeRepartidor.Subida sub = new NubeRepartidor("https://p.supabase.co", "sb_secret_x", new Falso())
                .subir("rep-7", "Carlos", s3.entregas);
        revisar("nube: subir desde otro hilo no cambia la salida hasta aplicar", s3.uuidNube.isEmpty()
                && s3.entregas.get(0).idNube == 0 && sub.uuid.equals("uuid-1"));
        sub.aplicar(s3);
        revisar("nube: al aplicar quedan el uuid y los ids", s3.uuidNube.equals("uuid-1")
                && s3.entregas.get(1).idNube == 72);
        Falso asig = new Falso();
        new NubeRepartidor("https://p.supabase.co", "sb_secret_x", asig).subir("cod-123", "Carlos", s3.entregas);
        revisar("nube: la salida se sube con su código y el nombre de quien la lleva", asig.cuerpos.get(0)
                .equals("{\"codigo\":\"cod-123\",\"repartidor\":\"Carlos\"}"));

        // ajustes: no se deja guardar la clave equivocada
        NubeRepartidor.Ajustes a = new NubeRepartidor.Ajustes();
        a.url = "https://p.supabase.co";
        a.pagina = "https://x.github.io/rep/";
        a.clave = "sb_publishable_abc";
        revisar("nube: no acepta la clave pública en el lugar de la secreta",
                errorAjustes(a).contains("clave pública"));
    }

    interface Llamada {
        void hacer() throws Exception;
    }

    private static String error(Llamada l) {
        try {
            l.hacer();
            return "";
        } catch (Exception e) {
            return String.valueOf(e.getMessage());
        }
    }

    private static String errorAjustes(NubeRepartidor.Ajustes a) {
        try {
            File tmp = java.nio.file.Files.createTempDirectory("aj").toFile();
            try (rutas.logica.BaseTienda bd = new rutas.logica.BaseTienda(new File(tmp, "t.db"), 1000)) {
                a.guardar(bd);
            }
            return "";
        } catch (Exception e) {
            return String.valueOf(e.getMessage());
        }
    }

    private static void probarBaseDatos() throws Exception {
        File tmp = java.nio.file.Files.createTempDirectory("sal").toFile();
        File archivo = new File(tmp, "domiruta.db");
        Salida s = salidaDePrueba();
        s.uuidNube = "uuid-9";
        s.mensajes.add(new Salida.Mensaje(5, false, "No encuentro la dirección", LocalDateTime.now(), false));
        try (BaseDatos bd = new BaseDatos(archivo)) {
            bd.guardarSalida(s);
            s.entregas.get(0).estado = Salida.Entrega.ENTREGADO;
            s.entregas.get(1).estado = Salida.Entrega.NO_ENTREGADO;
            s.entregas.get(1).motivo = "No abrieron";
            s.revisarEstado();
            s.lat = 11.54;
            s.lon = -72.91;
            s.ubicacionEn = LocalDateTime.now();
            bd.guardarSalida(s); // segunda vez: actualiza, no duplica
            List<Parada> pend = new ArrayList<>();
            Parada p = new Parada("Luz", "Calle 12 # 11-40", 11.5447, -72.9077);
            p.setValorPedido(8000);
            p.setPagaCon(10000);
            p.setUrgente(true);
            p.setNodo(42);
            pend.add(p);
            bd.guardarPendientes(pend);
        }
        try (BaseDatos bd = new BaseDatos(archivo)) {
            List<Salida> l = bd.cargarSalidas(LocalDateTime.now().minusDays(1));
            Salida r = l.isEmpty() ? null : l.get(0);
            revisar("salidas: se guardan y se leen al abrir el programa", l.size() == 1 && r.uuidNube.equals("uuid-9")
                    && r.entregas.size() == 2 && r.mensajes.size() == 1 && r.repartidor.equals("Moto 1"));
            revisar("salidas: queda Regresando con lo marcado y la ubicación", r != null
                    && r.estado == Salida.Estado.REGRESANDO && r.entregas.get(1).motivo.equals("No abrieron")
                    && r.entregas.get(0).cobrar == 12500 && r.lat == 11.54 && r.plataATraer() == 12500);
            List<Parada> pend = bd.cargarPendientes();
            revisar("pendientes: no se pierden al cerrar el programa", pend.size() == 1
                    && pend.get(0).getPagaCon() == 10000 && pend.get(0).isUrgente() && pend.get(0).getNodo() == 42);
            r.estado = Salida.Estado.CERRADA;
            r.cerrada = LocalDateTime.now().minusDays(2);
            bd.guardarSalida(r);
            boolean sigue = bd.cargarSalidas(LocalDateTime.now().minusDays(1)).size() == 1;
            r.uuidNube = "";
            bd.guardarSalida(r);
            revisar("salidas: una cerrada vieja se carga solo si falta borrarla de internet",
                    sigue && bd.cargarSalidas(LocalDateTime.now().minusDays(1)).isEmpty());
            r.entregas.get(0).marcaPendiente = true;
            r.entregas.get(0).marcadaEn = 1234;
            bd.guardarSalida(r);
        }
        try (BaseDatos bd = new BaseDatos(archivo)) {
            List<Salida> l = bd.cargarSalidas(LocalDateTime.now().minusDays(9));
            revisar("salidas: una marca a mano sin internet no se pierde al cerrar el programa",
                    l.size() == 1 && l.get(0).entregas.get(0).marcaPendiente && l.get(0).entregas.get(0).marcadaEn == 1234);
        }
        try (BaseDatos bd = new BaseDatos(archivo)) {
            // historial: la salida de arriba quedó cerrada hace 2 días; se agregan una de hoy (con foto) y una abierta
            Salida hoy = salidaDePrueba();
            hoy.repartidor = "Domicilios Express";
            hoy.tipoRepartidor = "GRUPO";
            hoy.entregas.get(0).estado = Salida.Entrega.ENTREGADO;
            hoy.entregas.get(0).domicilio = 3000;
            hoy.entregas.get(0).foto = "data:image/jpeg;base64,AAAA";
            hoy.estado = Salida.Estado.CERRADA;
            hoy.cerrada = LocalDateTime.now();
            bd.guardarSalida(hoy);
            bd.guardarSalida(salidaDePrueba()); // abierta: no sale en el historial
            java.time.LocalDate d = java.time.LocalDate.now();
            List<Salida> h2 = bd.historialSalidas(d.minusDays(2), d);
            List<Salida> h0 = bd.historialSalidas(d, d);
            revisar("historial: solo las cerradas dentro de las fechas", h2.size() == 2 && h0.size() == 1
                    && h0.get(0).repartidor.equals("Domicilios Express") && bd.historialSalidas(d.minusDays(1), d).size() == 1);
            Salida.Entrega conFoto = h0.get(0).entregas.get(0);
            revisar("historial: la foto no se carga, pero se sabe que está y se pide aparte", conFoto.foto.isEmpty()
                    && conFoto.tieneFoto && !h0.get(0).entregas.get(1).tieneFoto
                    && bd.fotoEntrega(conFoto.id).equals("data:image/jpeg;base64,AAAA"));
            java.util.Map<String, rutas.vista.PanelHistorialSalidas.Cuenta> c = rutas.vista.PanelHistorialSalidas.contar(h2);
            rutas.vista.PanelHistorialSalidas.Cuenta g = c.get("Domicilios Express");
            revisar("historial: suma por domiciliario (entregadas, cobro y domicilios del grupo)", c.size() == 2
                    && c.get("Moto 1").entregadas == 1 && c.get("Moto 1").noEntregadas == 1
                    && g.entregadas == 1 && g.cobro == hoy.plataATraer() && g.domicilios == 3000 && g.seQueda());
            revisar("historial: los periodos", rutas.vista.PanelHistorialSalidas.Periodo.MES_PASADO
                    .rango(java.time.LocalDate.of(2026, 3, 15))[1].equals(java.time.LocalDate.of(2026, 2, 28))
                    && rutas.vista.PanelHistorialSalidas.Periodo.SEMANA.rango(d)[0].equals(d.minusDays(6)));
        }
        try (BaseDatos bd = new BaseDatos(archivo)) {
            rutas.modelo.Domiciliario a = new rutas.modelo.Domiciliario(0, "Domicilios Express", "3001112233",
                    rutas.modelo.Domiciliario.Tipo.GRUPO);
            rutas.modelo.Domiciliario b = new rutas.modelo.Domiciliario(0, "Carlos", "3004445566",
                    rutas.modelo.Domiciliario.Tipo.FIJO);
            bd.guardarDomiciliario(a);
            bd.guardarDomiciliario(b);
            b.celular = "3009998877";
            bd.guardarDomiciliario(b); // editar no duplica
            List<rutas.modelo.Domiciliario> l = bd.cargarDomiciliarios();
            revisar("domiciliarios: se guardan, se editan y los fijos salen primero", l.size() == 2
                    && l.get(0).nombre.equals("Carlos") && l.get(0).celular.equals("3009998877")
                    && l.get(1).tipo == rutas.modelo.Domiciliario.Tipo.GRUPO);
            bd.eliminarDomiciliario(a.id);
            revisar("domiciliarios: se pueden quitar", bd.cargarDomiciliarios().size() == 1);
        }
        // cuándo se cierra sola: todo marcado, nada sin entregar y sin plata que cuadrar
        Salida pagada = salidaDePrueba();
        pagada.tipoRepartidor = "INDEPENDIENTE";
        for (Salida.Entrega e : pagada.entregas) {
            e.domicilio = 3000;
            e.cobrar = 3000; // pagó en la caja: solo cobra el domicilio, que es de él
        }
        boolean enCamino = !pagada.cierraSola();
        pagada.entregas.forEach(e -> e.estado = Salida.Entrega.ENTREGADO);
        pagada.revisarEstado();
        revisar("salida: el independiente que solo cobró sus domicilios no debe nada y se cierra sola",
                enCamino && pagada.debeEntregar() == 0 && pagada.cierraSola());
        pagada.tipoRepartidor = "FIJO";
        revisar("salida: un fijo trae los domicilios, así que no se cierra sola", pagada.debeEntregar() == 6000
                && !pagada.cierraSola());
        pagada.tipoRepartidor = "INDEPENDIENTE";
        pagada.entregas.get(1).estado = Salida.Entrega.NO_ENTREGADO;
        revisar("salida: si una no se entregó, la bolsa tiene que volver (no se cierra sola)",
                pagada.bolsasPorDevolver().size() == 1 && !pagada.cierraSola());
        Salida ind = salidaDePrueba();
        ind.tipoRepartidor = "INDEPENDIENTE";
        ind.entregas.get(0).domicilio = 3000;  // Elianis: cobra 12.500 (pedido 9.500 + domicilio 3.000)
        ind.entregas.get(1).domicilio = 3000;  // Pedro ya pagó todo en la tienda
        ind.entregas.get(0).estado = Salida.Entrega.ENTREGADO;
        ind.entregas.get(1).estado = Salida.Entrega.ENTREGADO;
        revisar("independiente: cobró 12.500 y se queda con 6.000 de domicilios (entrega 6.500)",
                ind.seQuedaConDomicilios() && ind.plataATraer() == 12500 && ind.domiciliosEntregados() == 6000);
        Salida t = salidaDePrueba();
        Set<Salida.Entrega> nada = new HashSet<>();
        t.entregas.get(0).estado = Salida.Entrega.ENTREGADO;
        t.revisarEstado();
        boolean sigue = t.estado == Salida.Estado.EN_CAMINO;
        t.entregas.get(1).estado = Salida.Entrega.ENTREGADO;
        t.revisarEstado();
        revisar("salida: pasa a Regresando solo cuando ya no queda ninguna en camino", sigue && nada.isEmpty()
                && t.estado == Salida.Estado.REGRESANDO && t.plataATraer() == 12500);
    }
}
