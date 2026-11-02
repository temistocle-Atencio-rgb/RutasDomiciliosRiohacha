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

        public Respuesta(int codigo, String cuerpo) {
            this.codigo = codigo;
            this.cuerpo = cuerpo == null ? "" : cuerpo;
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
        public final Map<Long, String[]> entregas = new LinkedHashMap<>(); // id -> {estado, motivo, conFoto}
    }

    private final String url;
    private final String clave;
    private final Transporte transporte;

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
                return new Respuesta(r.statusCode(), r.body());
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
        String p = pagina.endsWith("/") ? pagina : pagina + "/";
        return p + "?s=" + codigo;
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

        Subida(String uuid) {
            this.uuid = uuid;
        }

        /** Se aplica en el hilo de la pantalla, cuando ya terminó la subida. */
        public void aplicar(Salida s) {
            s.uuidNube = uuid;
            for (Map.Entry<Integer, Long> e : ids.entrySet()) {
                if (e.getKey() >= 1 && e.getKey() <= s.entregas.size()) {
                    s.entregas.get(e.getKey() - 1).idNube = e.getValue();
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
        Map<String, Object> cab = new LinkedHashMap<>();
        cab.put("codigo", codigo);
        cab.put("repartidor", repartidor);
        if (tienda != null && !tienda.isBlank()) {
            cab.put("tienda", tienda.trim());
        }
        List<Map<?, ?>> r = filas(llamar("POST", "salidas?select=id", Json.escribir(cab)));
        if (r.isEmpty()) {
            throw new ErrorNube("Supabase no devolvió la salida creada");
        }
        Subida sub = new Subida(Json.texto(r.get(0), "id"));
        try {
            List<Map<String, Object>> lista = new ArrayList<>();
            for (int i = 0; i < entregas.size(); i++) {
                lista.add(fila(sub.uuid, i + 1, entregas.get(i)));
            }
            if (!lista.isEmpty()) {
                for (Map<?, ?> m : filas(llamar("POST", "entregas_salida?select=id,orden", Json.escribir(lista)))) {
                    sub.ids.put((int) Json.entero(m, "orden"), Json.entero(m, "id"));
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
        f.put("paga_con", e.parada.getPagaCon());
        f.put("venta", e.parada.getVentaNumero() > 0 ? String.format("%05d", e.parada.getVentaNumero()) : "");
        f.put("productos", e.parada.getProductos());
        f.put("estado", e.estado);   // por si la tienda ya la marcó antes de subirla
        f.put("motivo", e.motivo);
        return f;
    }

    /** Lee estados, ubicación y si ya terminó. */
    public Estado leer(String uuid) throws IOException {
        Estado est = new Estado();
        List<Map<?, ?>> s = filas(llamar("GET", "salidas?id=eq." + uuid + "&select=regresando,lat,lon,ubicacion_en,aceptada,rechazada,motivo_rechazo", null));
        if (s.isEmpty()) {
            est.existe = false;
            return est;
        }
        Map<?, ?> m = s.get(0);
        est.regresando = m.get("regresando") != null;
        est.lat = Json.decimal(m, "lat");
        est.lon = Json.decimal(m, "lon");
        est.aceptada = hora(Json.texto(m, "aceptada"));
        est.rechazada = hora(Json.texto(m, "rechazada"));
        est.motivoRechazo = Json.texto(m, "motivo_rechazo");
        est.ubicacionEn = hora(Json.texto(m, "ubicacion_en"));
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
                    hora(Json.texto(m, "creado")), Boolean.TRUE.equals(m.get("leido"))));
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

    /** Al cerrar: se borra de internet (las entregas y mensajes se van con ella). */
    public void borrar(String uuid) throws IOException {
        llamar("DELETE", "salidas?id=eq." + uuid, null);
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
