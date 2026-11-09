package rutas.logica;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import rutas.modelo.Salida;

/**
 * Habla con Supabase para que el repartidor marque las entregas desde el celular.
 * La tienda usa la clave secreta (se pega en Configuración y queda solo en este computador);
 * la página del repartidor usa la pública y solo puede llamar las funciones de supabase.sql.
 */
public class NubeRepartidor {

    /** La página publicada en GitHub Pages (se puede cambiar en Configuración). */
    public static final String PAGINA_POR_DEFECTO = "https://temistocle-atencio-rgb.github.io/domiruta-repartidor/";
    public static final String URL_POR_DEFECTO = "https://xivokswlegxetxgovhdw.supabase.co";

    /** Lo que se guarda en Configuración. */
    public static class Ajustes {
        public String url = "";
        public String clave = "";
        public String pagina = "";

        public static Ajustes leer(BaseTienda bd) throws SQLException {
            Ajustes a = new Ajustes();
            a.url = bd.leerConfig("nube.url", URL_POR_DEFECTO);
            a.clave = bd.leerConfig("nube.clave", "");
            a.pagina = bd.leerConfig("nube.pagina", PAGINA_POR_DEFECTO);
            return a;
        }

        public boolean listo() {
            return url.startsWith("https://") && !clave.isBlank() && pagina.startsWith("http");
        }

        public void guardar(BaseTienda bd) throws SQLException {
            String u = url.trim();
            while (u.endsWith("/")) {
                u = u.substring(0, u.length() - 1);
            }
            String k = clave.trim();
            String p = pagina.trim();
            if (!u.isEmpty() && !u.matches("https://[A-Za-z0-9.-]+")) {
                throw new IllegalArgumentException("La dirección del proyecto es como https://xxxx.supabase.co");
            }
            if (k.startsWith("sb_publishable_")) {
                throw new IllegalArgumentException("Esa es la clave pública. Aquí va la secreta (empieza por sb_secret_).");
            }
            if (!k.isEmpty() && !(k.startsWith("sb_secret_") || k.startsWith("eyJ"))) {
                throw new IllegalArgumentException("La clave secreta empieza por sb_secret_");
            }
            if (!p.isEmpty() && !p.startsWith("https://")) {
                throw new IllegalArgumentException("El enlace de la página empieza por https://");
            }
            bd.guardarConfigs(new String[][]{{"nube.url", u}, {"nube.clave", k}, {"nube.pagina", p}});
            url = u;
            clave = k;
            pagina = p;
        }
    }

    /** Respuesta de Supabase. */
    public static class Respuesta {
        public final int codigo;
        public final String cuerpo;
        /** La hora de Supabase (encabezado Date, en milisegundos); 0 si no vino. */
        public final long fecha;

        public Respuesta(int codigo, String cuerpo) {
            this(codigo, cuerpo, 0);
        }

        public Respuesta(int codigo, String cuerpo, long fecha) {
            this.codigo = codigo;
            this.cuerpo = cuerpo == null ? "" : cuerpo;
            this.fecha = fecha;
        }
    }

    /** Quien hace la llamada por internet (en las pruebas se cambia por uno falso). */
    public interface Transporte {
        Respuesta enviar(String metodo, String url, Map<String, String> cabeceras, String cuerpo) throws IOException;
    }

    /** Error con un mensaje claro para la persona de la caja. */
    public static class ErrorNube extends IOException {
        public ErrorNube(String mensaje) {
            super(mensaje);
        }
    }

    /** Lo que se lee de una salida en cada revisión. */
    public static class Estado {
        public boolean regresando;
        public boolean existe = true;
        public double lat = Double.NaN;
        public double lon = Double.NaN;
        public LocalDateTime ubicacionEn;
        public LocalDateTime aceptada;   // null = todavía no responde
        public LocalDateTime rechazada;  // no puede llevarla
        public String motivoRechazo = "";
        public LocalDateTime venceEn;    // si no responde antes, la salida ya no se puede aceptar
        public boolean ubicacionPausada; // el repartidor apagó «Compartir mi ubicación»
        public final Map<Long, String[]> entregas = new LinkedHashMap<>(); // id -> {estado, motivo, conFoto}
    }

    /** Lo de más que se sube con la salida para la página nueva (ruta, tienda, vencimiento, repartidor). */
    public static class Extra {
        public List<double[]> ruta = new ArrayList<>();          // [lat, lon] por las calles
        public List<List<double[]>> calles = new ArrayList<>();  // calles de alrededor
        public double metros;
        public double tiendaLat = Double.NaN;
        public double tiendaLon = Double.NaN;
        public int minutosParaResponder;  // 0 = no vence; se cuenta con la hora de Supabase, no la del computador
        public String repartidorId = "";  // uuid en Supabase ("" = no está en la lista)
    }

    /** Cómo está un repartidor según su app (Disponible, cuándo la abrió, si la abrieron en otro celular). */
    public static class EstadoRepartidor {
        public String id = "";
        public boolean disponible;
        public boolean activo = true;
        public LocalDateTime vistoEn;
        public LocalDateTime dispositivoCambio;
        public boolean conClave;          // ya tiene clave para entrar
        public LocalDateTime sesionEn;    // desde cuándo tiene la sesión abierta (null = ninguna)
    }

    /** Clave de 6 números al azar, que no sea fácil (ni 111111 ni 123456 ni 654321). */
    public static String claveNueva() {
        java.security.SecureRandom r = new java.security.SecureRandom();
        while (true) {
            String c = String.format("%06d", r.nextInt(1_000_000));
            if (!claveDebil(c)) {
                return c;
            }
        }
    }

    public static boolean claveDebil(String c) {
        return c.matches("(\\d)\\1{5}") || "01234567890".contains(c) || "09876543210".contains(c);
    }

    /** Le pone esa clave en Supabase (la sesión que tuviera se cierra). Devuelve "ok" o el problema. */
    public String ponerClave(String idRepartidor, String clave) throws IOException {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("p_repartidor", uuidValido(idRepartidor));
        c.put("p_clave", clave);
        Object o = Json.leer(llamar("POST", "rpc/dr_poner_clave", Json.escribir(c)).cuerpo);
        return o == null ? "Supabase no respondió" : o.toString();
    }

    /** Minutos que tiene el repartidor para aceptar una salida. */
    public static final int MINUTOS_PARA_RESPONDER = 10;

    private final String url;
    private final String clave;
    private final Transporte transporte;
    /** Cuánto va adelantado el reloj de Supabase frente al de este computador (ms). */
    private volatile long desfaseMs;

    /** La hora de Supabase (la que usa para decidir si una salida venció). */
    public OffsetDateTime ahoraServidor() {
        return OffsetDateTime.now().plusNanos(desfaseMs * 1_000_000L);
    }

    public long getDesfaseMs() {
        return desfaseMs;
    }

    /** Una hora de Supabase pasada al reloj de este computador (para comparar con LocalDateTime.now()). */
    LocalDateTime horaLocal(String t) {
        LocalDateTime h = hora(t);
        return h == null ? null : h.minusNanos(desfaseMs * 1_000_000L);
    }

    public NubeRepartidor(String url, String clave, Transporte transporte) {
        this.url = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.clave = clave;
        this.transporte = transporte;
    }

    public NubeRepartidor(Ajustes a) {
        this(a.url, a.clave, porInternet());
    }

    /** El de verdad: java.net.http con tiempos cortos para no trabar la caja. */
    public static Transporte porInternet() {
        HttpClient cliente = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
        return (metodo, url, cabeceras, cuerpo) -> {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15));
            cabeceras.forEach(b::header);
            b.method(metodo, cuerpo == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(cuerpo, StandardCharsets.UTF_8));
            try {
                HttpResponse<String> r = cliente.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                long fecha = 0;
                try {
                    fecha = r.headers().firstValue("date").map(d -> java.time.ZonedDateTime.parse(d,
                            java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()).orElse(0L);
                } catch (RuntimeException ignorado) {
                    // sin hora del servidor se usa la del computador
                }
                return new Respuesta(r.statusCode(), r.body(), fecha);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Se canceló la conexión");
            }
        };
    }

    /** Código secreto del enlace: 32 letras al azar (imposible de adivinar). */
    public static String nuevoCodigo() {
        byte[] b = new byte[24];
        new SecureRandom().nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public static String enlace(String pagina, String codigo) {
        return base(pagina) + "?s=" + codigo;
    }

    /** El enlace personal del repartidor: abre «Mis salidas» (y la app instalada). */
    public static String enlacePersonal(String pagina, String enlaceRepartidor) {
        return base(pagina) + "?r=" + enlaceRepartidor;
    }

    /** El enlace del cliente para ver por dónde va su pedido. */
    public static String enlaceCliente(String pagina, String enlaceCliente) {
        return base(pagina) + "?p=" + enlaceCliente;
    }

    private static String base(String pagina) {
        return pagina.endsWith("/") ? pagina : pagina + "/";
    }

    /** Enlace personal nuevo (32 letras de las mismas que usa Supabase, sin letras que se confundan). */
    public static String nuevoEnlacePersonal() {
        String alfabeto = "abcdefghijkmnpqrstuvwxyz23456789"; // 32 letras: cada byte % 32 sin sesgo
        SecureRandom r = new SecureRandom();
        byte[] b = new byte[32];
        r.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(alfabeto.charAt((x & 0xff) % 32));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    //  Llamadas
    // ------------------------------------------------------------------

    /** Revisa que la dirección y la clave sirvan. */
    public void probar() throws IOException {
        llamar("GET", "salidas?select=id&limit=1", null);
    }

    /** Lo que devuelve Supabase al subir una salida: su uuid y el id de cada entrega (por orden, desde 1). */
    public static class Subida {
        public final String uuid;
        public final Map<Integer, Long> ids = new LinkedHashMap<>();
        /** Por orden: {código corto, enlace} del cliente para ver su pedido. */
        public final Map<Integer, String[]> clientes = new LinkedHashMap<>();
        /** Hasta cuándo puede aceptar, en la hora de este computador (null = no vence). */
        public LocalDateTime venceLocal;

        Subida(String uuid) {
            this.uuid = uuid;
        }

        /** Se aplica en el hilo de la pantalla, cuando ya terminó la subida. */
        public void aplicar(Salida s) {
            s.uuidNube = uuid;
            s.venceEn = venceLocal;
            for (Map.Entry<Integer, Long> e : ids.entrySet()) {
                if (e.getKey() >= 1 && e.getKey() <= s.entregas.size()) {
                    s.entregas.get(e.getKey() - 1).idNube = e.getValue();
                }
            }
            for (Map.Entry<Integer, String[]> e : clientes.entrySet()) {
                if (e.getKey() >= 1 && e.getKey() <= s.entregas.size()) {
                    Salida.Entrega en = s.entregas.get(e.getKey() - 1);
                    en.codigoCliente = e.getValue()[0];
                    en.enlaceCliente = e.getValue()[1];
                }
            }
        }
    }

    /**
     * Sube la salida con sus entregas. El código va en el enlace que le llega al domiciliario.
     * No cambia la salida (se llama desde otro hilo): devuelve el uuid y los ids para aplicarlos
     * después con {@link Subida#aplicar}.
     */
    public Subida subir(String codigo, String repartidor, List<Salida.Entrega> entregas) throws IOException {
        return subir(codigo, repartidor, "", entregas);
    }

    /** tienda: el nombre del negocio (el domiciliario lo ve antes de aceptar). */
    public Subida subir(String codigo, String repartidor, String tienda, List<Salida.Entrega> entregas) throws IOException {
        return subir(codigo, repartidor, tienda, entregas, null);
    }

    /** extra: la ruta, la tienda, hasta cuándo puede aceptar y quién es (null = como antes). */
    public Subida subir(String codigo, String repartidor, String tienda, List<Salida.Entrega> entregas, Extra extra)
            throws IOException {
        Map<String, Object> cab = new LinkedHashMap<>();
        cab.put("codigo", codigo);
        cab.put("repartidor", repartidor);
        if (tienda != null && !tienda.isBlank()) {
            cab.put("tienda", tienda.trim());
        }
        if (extra != null) {
            if (extra.ruta.size() >= 2) {
                cab.put("ruta", RutaNube.comoJson(extra.ruta));
                cab.put("distancia_m", Math.round(extra.metros));
            }
            if (!extra.calles.isEmpty()) {
                cab.put("calles", RutaNube.callesComoJson(extra.calles));
            }
            if (!Double.isNaN(extra.tiendaLat) && !Double.isNaN(extra.tiendaLon)) {
                cab.put("tienda_lat", extra.tiendaLat);
                cab.put("tienda_lon", extra.tiendaLon);
            }
            if (extra.minutosParaResponder > 0) {
                cab.put("vence_en", ahoraServidor().plusMinutes(extra.minutosParaResponder)
                        .truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString());
            }
            if (extra.repartidorId != null && !extra.repartidorId.isEmpty()) {
                cab.put("repartidor_id", extra.repartidorId);
            }
        }
        List<Map<?, ?>> r = filas(llamar("POST", "salidas?select=id", Json.escribir(cab)));
        if (r.isEmpty()) {
            throw new ErrorNube("Supabase no devolvió la salida creada");
        }
        Subida sub = new Subida(Json.texto(r.get(0), "id"));
        if (extra != null && extra.minutosParaResponder > 0) {
            sub.venceLocal = LocalDateTime.now().plusMinutes(extra.minutosParaResponder).withNano(0);
        }
        try {
            List<Map<String, Object>> lista = new ArrayList<>();
            for (int i = 0; i < entregas.size(); i++) {
                lista.add(fila(sub.uuid, i + 1, entregas.get(i)));
            }
            if (!lista.isEmpty()) {
                String campos = extra == null ? "id,orden" : "id,orden,codigo_cliente,enlace_cliente";
                for (Map<?, ?> m : filas(llamar("POST", "entregas_salida?select=" + campos, Json.escribir(lista)))) {
                    int orden = (int) Json.entero(m, "orden");
                    sub.ids.put(orden, Json.entero(m, "id"));
                    if (extra != null) {
                        sub.clientes.put(orden, new String[]{Json.texto(m, "codigo_cliente"), Json.texto(m, "enlace_cliente")});
                    }
                }
            }
        } catch (IOException e) {
            try { // que no quede una salida a medias
                llamar("DELETE", "salidas?id=eq." + sub.uuid, null);
            } catch (IOException ignorado) {
                // queda sin entregas y sin enlace enviado: no la ve nadie
            }
            throw e;
        }
        return sub;
    }

    /** Para las pruebas y para quien tiene la salida en la mano (mismo hilo). */
    public void subir(Salida s) throws IOException {
        subir(s.codigo, s.repartidor, s.entregas).aplicar(s);
    }

    /**
     * Agrega una entrega a una salida que ya va en camino (el repartidor la ve en la próxima recarga).
     * Devuelve su id en internet. Si solo falla quitar el «regresando», no importa: la caja lo calcula.
     */
    public long agregar(String uuid, int orden, Salida.Entrega e) throws IOException {
        // si un intento anterior sí llegó (y solo se perdió la respuesta), no se sube dos veces
        List<Map<?, ?>> ya = filas(llamar("GET", "entregas_salida?salida=eq." + uuid + "&orden=eq." + orden + "&select=id", null));
        if (!ya.isEmpty()) {
            return Json.entero(ya.get(0), "id");
        }
        List<Map<?, ?>> r = filas(llamar("POST", "entregas_salida?select=id", Json.escribir(fila(uuid, orden, e))));
        if (r.isEmpty()) {
            throw new ErrorNube("Supabase no devolvió la entrega agregada");
        }
        long id = Json.entero(r.get(0), "id");
        try {
            llamar("PATCH", "salidas?id=eq." + uuid, "{\"regresando\":null}");
        } catch (IOException ignorado) {
            // la entrega ya quedó; esto solo es un dato de más
        }
        return id;
    }

    public void agregar(Salida s, Salida.Entrega e) throws IOException {
        e.idNube = agregar(s.uuidNube, s.entregas.indexOf(e) + 1, e);
    }

    private static Map<String, Object> fila(String uuid, int orden, Salida.Entrega e) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("salida", uuid);
        f.put("orden", orden);
        f.put("nombre", e.parada.getNombre());
        f.put("direccion", e.parada.getDireccion());
        f.put("referencia", "");
        f.put("telefono", e.parada.getTelefono());
        f.put("lat", e.parada.getLat());
        f.put("lon", e.parada.getLon());
        f.put("cobrar", e.cobrar);
        f.put("ganancia", e.ganancia); // lo que es de él: su página muestra cuánto gana y cuánto entrega
        f.put("paga_con", e.parada.getPagaCon());
        f.put("venta", e.parada.getVentaNumero() > 0 ? String.format("%05d", e.parada.getVentaNumero()) : "");
        f.put("productos", e.parada.getProductos());
        f.put("estado", e.estado);   // por si la tienda ya la marcó antes de subirla
        f.put("motivo", e.motivo);
        return f;
    }

    /**
     * El rastro del repartidor: las ubicaciones buenas (error de 150 m o menos) después de la última que ya se tiene.
     * Cada punto es {lat, lon, id, hora en ms}; el id sirve para pedir solo lo nuevo la próxima vez.
     */
    public List<double[]> rastro(String uuid, long despuesDe) throws IOException {
        List<double[]> l = new ArrayList<>();
        for (Map<?, ?> m : filas(llamar("GET", "ubicaciones?salida=eq." + uuid + "&id=gt." + Math.max(0, despuesDe)
                + "&select=id,lat,lon,precision_m,hora&order=id&limit=1000", null))) {
            Object p = m.get("precision_m");
            if (p instanceof Number && ((Number) p).doubleValue() > 150) {
                continue;
            }
            double lat = Json.decimal(m, "lat"), lon = Json.decimal(m, "lon");
            if (!Double.isNaN(lat) && !Double.isNaN(lon)) {
                long hora = 0;
                try {
                    hora = java.time.OffsetDateTime.parse(Json.texto(m, "hora")).toInstant().toEpochMilli();
                } catch (RuntimeException e) {
                    // sin hora: solo se corta por distancia
                }
                l.add(new double[]{lat, lon, Json.entero(m, "id"), hora});
            }
        }
        return l;
    }

    /** La tienda cambió de nombre: las salidas que están en internet (y lo que ven repartidores y clientes) lo toman. */
    public void renombrarTienda(String nombre) throws IOException {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("tienda", nombre);
        llamar("PATCH", "salidas?tienda=neq." + java.net.URLEncoder.encode(nombre, StandardCharsets.UTF_8)
                .replace("+", "%20") + "&select=id", Json.escribir(c));
    }

    /** Lee estados, ubicación y si ya terminó. */
    public Estado leer(String uuid) throws IOException {
        Estado est = new Estado();
        List<Map<?, ?>> s = filas(llamar("GET", "salidas?id=eq." + uuid
                + "&select=regresando,lat,lon,ubicacion_en,aceptada,rechazada,motivo_rechazo,vence_en,ubicacion_pausada", null));
        if (s.isEmpty()) {
            est.existe = false;
            return est;
        }
        Map<?, ?> m = s.get(0);
        est.regresando = m.get("regresando") != null;
        est.lat = Json.decimal(m, "lat");
        est.lon = Json.decimal(m, "lon");
        est.aceptada = horaLocal(Json.texto(m, "aceptada"));
        est.rechazada = horaLocal(Json.texto(m, "rechazada"));
        est.motivoRechazo = Json.texto(m, "motivo_rechazo");
        est.ubicacionEn = horaLocal(Json.texto(m, "ubicacion_en"));
        est.venceEn = horaLocal(Json.texto(m, "vence_en"));
        est.ubicacionPausada = Boolean.TRUE.equals(m.get("ubicacion_pausada"));
        for (Map<?, ?> e : filas(llamar("GET", "entregas_salida?salida=eq." + uuid + "&select=id,estado,motivo,foto_en", null))) {
            est.entregas.put(Json.entero(e, "id"), new String[]{Json.texto(e, "estado"), Json.texto(e, "motivo"),
                String.valueOf(e.get("foto_en") != null)});
        }
        return est;
    }

    /** Mensajes nuevos (después del id que ya se tiene). */
    public List<Salida.Mensaje> mensajes(String uuid, long desde) throws IOException {
        List<Salida.Mensaje> r = new ArrayList<>();
        for (Map<?, ?> m : filas(llamar("GET", "mensajes_salida?salida=eq." + uuid + "&id=gt." + desde
                + "&select=id,de,texto,creado,leido&order=id", null))) {
            r.add(new Salida.Mensaje(Json.entero(m, "id"), "tienda".equals(Json.texto(m, "de")), Json.texto(m, "texto"),
                    horaLocal(Json.texto(m, "creado")), Boolean.TRUE.equals(m.get("leido"))));
        }
        return r;
    }

    public Salida.Mensaje escribir(String uuid, String texto) throws IOException {
        String t = texto.trim();
        if (t.isEmpty()) {
            throw new ErrorNube("Escriba el mensaje");
        }
        if (t.length() > 300) {
            t = t.substring(0, 300);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("salida", uuid);
        m.put("de", "tienda");
        m.put("texto", t);
        List<Map<?, ?>> r = filas(llamar("POST", "mensajes_salida?select=id,creado", Json.escribir(m)));
        Map<?, ?> f = r.isEmpty() ? Map.of() : r.get(0);
        LocalDateTime h = hora(Json.texto(f, "creado"));
        return new Salida.Mensaje(Json.entero(f, "id"), true, t, h == null ? LocalDateTime.now() : h, true);
    }

    public void marcarLeidos(String uuid) throws IOException {
        llamar("PATCH", "mensajes_salida?salida=eq." + uuid + "&de=eq.repartidor&leido=eq.false", "{\"leido\":true}");
    }

    /** La tienda corrige un estado (por ejemplo cuando el repartidor avisó por llamada). */
    public void marcar(long idEntrega, String estado, String motivo) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("estado", estado);
        m.put("motivo", motivo == null ? "" : motivo);
        llamar("PATCH", "entregas_salida?id=eq." + idEntrega, Json.escribir(m));
    }

    /** La foto de entrega (data:image/jpeg;base64,...) o "" si no hay. */
    public String foto(long idEntrega) throws IOException {
        List<Map<?, ?>> r = filas(llamar("GET", "entregas_salida?id=eq." + idEntrega + "&select=foto", null));
        return r.isEmpty() ? "" : Json.texto(r.get(0), "foto");
    }

    /** Todas las fotos de una salida (id de la entrega -> foto). Se pide justo antes de borrarla. */
    public Map<Long, String> fotos(String uuid) throws IOException {
        Map<Long, String> r = new LinkedHashMap<>();
        for (Map<?, ?> m : filas(llamar("GET", "entregas_salida?salida=eq." + uuid + "&foto=not.is.null&select=id,foto", null))) {
            r.put(Json.entero(m, "id"), Json.texto(m, "foto"));
        }
        return r;
    }

    /** Primero se cierra (el enlace deja de mostrar datos) y luego se borra. */
    public void cerrar(String uuid) throws IOException {
        llamar("PATCH", "salidas?id=eq." + uuid, "{\"cerrada\":true}");
    }

    /** Se quita una entrega de su salida (el cliente se pasó a otro repartidor): su enlace de cliente deja de servir. */
    public void quitarEntrega(long idEntrega) throws IOException {
        llamar("DELETE", "entregas_salida?id=eq." + idEntrega, null);
    }

    /** Al cerrar: se borra de internet (las entregas y mensajes se van con ella). */
    public void borrar(String uuid) throws IOException {
        llamar("DELETE", "salidas?id=eq." + uuid, null);
    }

    // ------------------------------------------------------------------
    //  Repartidores (enlace personal, Disponible, otro celular)
    // ------------------------------------------------------------------

    /** Crea al repartidor en Supabase. Devuelve {id, enlace}. */
    public String[] crearRepartidor(String nombre, String celular) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nombre", recorte(nombre, 80));
        m.put("celular", recorte(celular, 20));
        m.put("enlace", nuevoEnlacePersonal());
        List<Map<?, ?>> r = filas(llamar("POST", "repartidores?select=id,enlace", Json.escribir(m)));
        if (r.isEmpty()) {
            throw new ErrorNube("Supabase no devolvió el repartidor creado");
        }
        return new String[]{Json.texto(r.get(0), "id"), Json.texto(r.get(0), "enlace")};
    }

    /** Nombre, celular y si sigue en la lista (lo que se cambia en Configuración). */
    public void actualizarRepartidor(String id, String nombre, String celular, boolean activo) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nombre", recorte(nombre, 80));
        m.put("celular", recorte(celular, 20));
        m.put("activo", activo);
        if (!activo) {
            m.put("disponible", false);
        }
        llamar("PATCH", "repartidores?id=eq." + uuidValido(id), Json.escribir(m));
    }

    /**
     * El enlace viejo deja de servir (por ejemplo se perdió el celular): se cambia por uno nuevo, se suelta
     * el celular amarrado y se borran sus notificaciones. Devuelve el enlace nuevo.
     */
    public String revocarEnlace(String id) throws IOException {
        String nuevo = nuevoEnlacePersonal();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enlace", nuevo);
        m.put("dispositivo", null);
        m.put("dispositivo_cambio", null);
        m.put("disponible", false);
        if (filas(llamar("PATCH", "repartidores?id=eq." + uuidValido(id) + "&select=id", Json.escribir(m))).isEmpty()) {
            return null; // ya no está en Supabase: hay que crearlo otra vez
        }
        llamar("DELETE", "suscripciones_push?repartidor=eq." + uuidValido(id), null);
        return nuevo;
    }

    /** Sigue en Supabase (lo pudieron borrar desde otra caja o al reiniciar el proyecto). */
    public boolean existeRepartidor(String id) throws IOException {
        return !filas(llamar("GET", "repartidores?id=eq." + uuidValido(id) + "&select=id", null)).isEmpty();
    }

    /** Quitado de la lista: sus salidas viejas se quedan sin repartidor (no se borran). */
    public void borrarRepartidor(String id) throws IOException {
        llamar("DELETE", "repartidores?id=eq." + uuidValido(id), null);
    }

    /** Cómo están todos los repartidores (id -> estado). */
    public Map<String, EstadoRepartidor> repartidores() throws IOException {
        Map<String, EstadoRepartidor> r = new LinkedHashMap<>();
        for (Map<?, ?> m : filas(llamar("GET", "repartidores?select=id,disponible,activo,visto_en,dispositivo_cambio,con_clave,sesion_en", null))) {
            EstadoRepartidor e = new EstadoRepartidor();
            e.id = Json.texto(m, "id");
            e.disponible = Boolean.TRUE.equals(m.get("disponible"));
            e.activo = !Boolean.FALSE.equals(m.get("activo"));
            e.vistoEn = horaLocal(Json.texto(m, "visto_en"));
            e.dispositivoCambio = horaLocal(Json.texto(m, "dispositivo_cambio"));
            e.conClave = Boolean.TRUE.equals(m.get("con_clave"));
            e.sesionEn = horaLocal(Json.texto(m, "sesion_en"));
            r.put(e.id, e);
        }
        return r;
    }

    /**
     * Las correcciones de ubicación que todavía no ha tomado DomiRuta, con el nombre, la dirección y el
     * punto viejo de la entrega (hay que tomarlas antes de borrar la salida: se van con ella).
     */
    public List<rutas.modelo.Correccion> correccionesNuevas() throws IOException {
        List<rutas.modelo.Correccion> r = new ArrayList<>();
        for (Map<?, ?> m : filas(llamar("GET", "correcciones?procesada=is.false&order=id&select=id,lat,lon,distancia_m,"
                + "estado,repartidor,creada,entregas_salida(nombre,direccion,lat,lon)", null))) {
            rutas.modelo.Correccion c = new rutas.modelo.Correccion();
            c.idNube = Json.entero(m, "id");
            c.lat = Json.decimal(m, "lat");
            c.lon = Json.decimal(m, "lon");
            c.distanciaM = (int) Json.entero(m, "distancia_m");
            c.estado = Json.texto(m, "estado");
            c.repartidor = Json.texto(m, "repartidor");
            LocalDateTime h = horaLocal(Json.texto(m, "creada"));
            c.creada = h == null ? LocalDateTime.now() : h;
            Object e = m.get("entregas_salida");
            if (e instanceof Map) {
                Map<?, ?> en = (Map<?, ?>) e;
                c.nombre = Json.texto(en, "nombre");
                c.direccion = Json.texto(en, "direccion");
                c.latViejo = Json.decimal(en, "lat");
                c.lonViejo = Json.decimal(en, "lon");
            }
            if (!Double.isNaN(c.lat) && !Double.isNaN(c.lon)) {
                r.add(c);
            }
        }
        return r;
    }

    /** DomiRuta ya la tomó (queda guardada en este computador). */
    public void correccionTomada(long id) throws IOException {
        llamar("PATCH", "correcciones?id=eq." + id, "{\"procesada\":true}");
    }

    /** Borra lo viejo de Supabase (recorridos de más de 30 días, intentos, salidas cerradas olvidadas). */
    public void limpiarViejo() throws IOException {
        llamar("POST", "rpc/limpiar_viejo", "{}");
    }

    private static String recorte(String t, int max) {
        String s = t == null ? "" : t.trim();
        return s.length() > max ? s.substring(0, max) : s;
    }

    /** Lo que va en la dirección: solo un uuid (nada que cambie la consulta). */
    static String uuidValido(String id) throws ErrorNube {
        if (id == null || !id.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            throw new ErrorNube("El repartidor no tiene un código válido en Supabase");
        }
        return id;
    }

    /**
     * Le suena el celular al repartidor aunque tenga la app cerrada (función «notificar» de Supabase).
     * Es un extra: si falla (sin internet, función sin publicar, celular sin avisos) no pasa nada.
     * Devuelve cuántos celulares recibieron el aviso (-1 si no se pudo).
     */
    public int notificar(String repartidorId, String tienda, int entregas, String enlaceSalida) {
        if (url.isBlank() || clave.isBlank() || repartidorId == null || repartidorId.isEmpty()) {
            return -1;
        }
        try {
            uuidValido(repartidorId);
            String nombre = tienda == null || tienda.isBlank() ? "La tienda" : tienda.trim();
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("repartidor_id", repartidorId);
            c.put("titulo", nombre + " te asignó una salida");
            c.put("texto", entregas == 1 ? "1 entrega. Ábrela para aceptarla." : entregas + " entregas. Ábrela para aceptarla.");
            c.put("url", enlaceSalida == null ? "" : enlaceSalida);
            Map<String, String> cab = new LinkedHashMap<>();
            cab.put("apikey", clave);
            if (clave.startsWith("eyJ")) {
                cab.put("Authorization", "Bearer " + clave);
            }
            cab.put("Content-Type", "application/json");
            Respuesta r = transporte.enviar("POST", url + "/functions/v1/notificar", cab, Json.escribir(c));
            if (r.codigo >= 300) {
                return -1;
            }
            Object o = Json.leer(r.cuerpo);
            return o instanceof Map ? (int) Json.entero((Map<?, ?>) o, "enviados") : -1;
        } catch (IOException | RuntimeException e) {
            return -1;
        }
    }

    /** Igual que notificar, pero sin esperar (no demora el WhatsApp ni la pantalla). */
    public void notificarSinEsperar(String repartidorId, String tienda, int entregas, String enlaceSalida) {
        if (repartidorId == null || repartidorId.isEmpty()) {
            return;
        }
        Thread t = new Thread(() -> notificar(repartidorId, tienda, entregas, enlaceSalida), "aviso-repartidor");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------------

    private Respuesta llamar(String metodo, String ruta, String cuerpo) throws IOException {
        if (url.isBlank() || clave.isBlank()) {
            throw new ErrorNube("Falta configurar Supabase en Configuración › Repartidor");
        }
        Map<String, String> cab = new LinkedHashMap<>();
        cab.put("apikey", clave);
        if (clave.startsWith("eyJ")) { // claves viejas (JWT) van también aquí; las sb_secret_ no
            cab.put("Authorization", "Bearer " + clave);
        }
        cab.put("Accept", "application/json");
        if (cuerpo != null) {
            cab.put("Content-Type", "application/json");
            cab.put("Prefer", "return=representation");
        }
        Respuesta r;
        try {
            r = transporte.enviar(metodo, url + "/rest/v1/" + ruta, cab, cuerpo);
            if (r.fecha > 0) {
                desfaseMs = r.fecha - System.currentTimeMillis();
            }
        } catch (java.net.http.HttpTimeoutException | java.net.ConnectException e) {
            throw new ErrorNube("No hay internet o Supabase no responde");
        } catch (IOException e) {
            if (e instanceof ErrorNube) {
                throw e;
            }
            throw new ErrorNube("No hay internet o Supabase no responde (" + e.getMessage() + ")");
        }
        if (r.codigo == 401 || r.codigo == 403) {
            throw new ErrorNube("Supabase no aceptó la clave secreta. Revise Configuración › Repartidor.");
        }
        if (r.codigo == 404) {
            throw new ErrorNube("No se encontraron las tablas: falta correr supabase.sql y mejoras.sql en Supabase.");
        }
        if (r.codigo >= 300) {
            String codigoPg = "", mensaje = "";
            try {
                Object o = Json.leer(r.cuerpo);
                if (o instanceof Map) {
                    codigoPg = Json.texto((Map<?, ?>) o, "code");
                    mensaje = Json.texto((Map<?, ?>) o, "message");
                }
            } catch (IllegalArgumentException e) {
                // no era JSON
            }
            if (codigoPg.equals("23505")) {
                throw new ErrorNube("Ese dato ya existe en Supabase (repetido)");
            }
            if (codigoPg.equals("23514")) {
                throw new ErrorNube("Supabase no aceptó los datos (revise que no falte nada)");
            }
            if (codigoPg.equals("P0001") && !mensaje.isEmpty()) {
                throw new ErrorNube(mensaje); // los avisos escritos en supabase.sql (ya vienen en español)
            }
            throw new ErrorNube("Supabase respondió " + r.codigo + ": " + recortar(r.cuerpo));
        }
        return r;
    }

    /** Las filas que devuelve Supabase. Si llega otra cosa (por ejemplo una página de un wifi con registro), error claro. */
    private static List<Map<?, ?>> filas(Respuesta r) throws ErrorNube {
        if (r.cuerpo.isBlank()) {
            return List.of();
        }
        Object o;
        try {
            o = Json.leer(r.cuerpo);
        } catch (IllegalArgumentException e) {
            throw new ErrorNube("Respuesta de Supabase que no se entiende (¿el internet pide registrarse?)");
        }
        List<?> l = o instanceof List ? (List<?>) o : List.of(o);
        List<Map<?, ?>> f = new ArrayList<>();
        for (Object x : l) {
            if (!(x instanceof Map)) {
                throw new ErrorNube("Respuesta de Supabase que no se entiende (¿el internet pide registrarse?)");
            }
            f.add((Map<?, ?>) x);
        }
        return f;
    }

    /** Fecha de Supabase (con zona) a la hora de este computador. */
    static LocalDateTime hora(String t) {
        if (t == null || t.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(t.replace(' ', 'T')).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String recortar(String s) {
        String t = s.replaceAll("\\s+", " ");
        return t.length() > 160 ? t.substring(0, 160) + "…" : t;
    }
}
