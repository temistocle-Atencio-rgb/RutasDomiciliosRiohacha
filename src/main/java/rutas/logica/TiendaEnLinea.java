package rutas.logica;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import rutas.modelo.MetodoPago;
import rutas.modelo.Unidad;

/**
 * La tienda en línea: DomiRuta sube a Supabase el catálogo (solo lo marcado «Vender en línea»),
 * el logo y los datos del recibo, y los medios de pago activos. Solo se manda lo que cambió:
 * lo último que se subió queda guardado (config «tienda.enviado») y se compara cada vez.
 * El cliente nunca ve costos ni cantidades: solo si hay o no («Agotado»).
 */
public class TiendaEnLinea {

    /** Lo que se configura en Configuración › Tienda en línea. */
    public static class Ajustes {
        public boolean abierta = true;
        public String horario = "";
        public long domicilio;
        public long minimo;

        public static Ajustes leer(BaseTienda bd) throws SQLException {
            Ajustes a = new Ajustes();
            a.abierta = !"false".equals(bd.leerConfig("tienda.abierta", "true"));
            a.horario = bd.leerConfig("tienda.horario", "");
            a.domicilio = numero(bd.leerConfig("tienda.domicilio", "0"));
            a.minimo = numero(bd.leerConfig("tienda.minimo", "0"));
            return a;
        }

        public void validar() {
            if (horario.length() > 120) {
                throw new IllegalArgumentException("El horario es muy largo (máximo 120 letras)");
            }
            if (domicilio < 0 || domicilio > 1_000_000) {
                throw new IllegalArgumentException("El domicilio va de $0 a $1.000.000");
            }
            if (minimo < 0 || minimo > 10_000_000) {
                throw new IllegalArgumentException("El pedido mínimo va de $0 a $10.000.000");
            }
        }

        public void guardar(BaseTienda bd) throws SQLException {
            validar();
            bd.guardarConfigs(new String[][]{{"tienda.abierta", String.valueOf(abierta)},
                {"tienda.horario", horario.trim()}, {"tienda.domicilio", String.valueOf(domicilio)},
                {"tienda.minimo", String.valueOf(minimo)}});
        }

        private static long numero(String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
    }

    /** Lo que se lee de DomiRuta (en el hilo de la pantalla) para armar lo que debe quedar en Supabase. */
    public static class Datos {
        public List<BaseTienda.ProductoEnLinea> productos = new ArrayList<>();
        public AjustesRecibo recibo = new AjustesRecibo();
        public String negocio = "";
        public Ajustes ajustes = new Ajustes();
        public List<MetodoPago> pagos = new ArrayList<>(List.of(MetodoPago.EFECTIVO));
        /** Número o llave y a nombre de quién, de cada medio de pago (para mandárselo al cliente por WhatsApp) */
        public Map<MetodoPago, String[]> cuentas = new java.util.EnumMap<>(MetodoPago.class);

        public static Datos leer(BaseTienda bd, AjustesRecibo recibo, String negocio) throws SQLException {
            Datos d = new Datos();
            d.productos = bd.productosEnLinea();
            d.recibo = recibo == null ? new AjustesRecibo() : recibo;
            d.negocio = negocio == null ? "" : negocio;
            d.ajustes = Ajustes.leer(bd);
            d.pagos = MediosPago.activos(bd);
            d.cuentas = leerCuentas(bd);
            return d;
        }
    }

    /** Los medios que se pagan aparte (no en la puerta): a esos se les mandan los datos para pagar. */
    public static boolean pagaAntes(MetodoPago m) {
        return m != MetodoPago.EFECTIVO && m != MetodoPago.TARJETA_DEBITO && m != MetodoPago.TARJETA_CREDITO;
    }

    public static Map<MetodoPago, String[]> leerCuentas(BaseTienda bd) throws SQLException {
        Map<MetodoPago, String[]> m = new java.util.EnumMap<>(MetodoPago.class);
        for (MetodoPago p : MetodoPago.values()) {
            if (pagaAntes(p)) {
                m.put(p, new String[]{bd.leerConfig("pagos.cuenta." + p.name(), ""), bd.leerConfig("pagos.titular." + p.name(), "")});
            }
        }
        return m;
    }

    public static void guardarCuentas(BaseTienda bd, Map<MetodoPago, String[]> cuentas) throws SQLException {
        List<String[]> pares = new ArrayList<>();
        for (Map.Entry<MetodoPago, String[]> e : cuentas.entrySet()) {
            String c = e.getValue()[0] == null ? "" : e.getValue()[0].trim().replaceAll("\\s+", " ");
            String t = e.getValue()[1] == null ? "" : e.getValue()[1].trim().replaceAll("\\s+", " ");
            if (c.length() > 60) {
                throw new IllegalArgumentException("El número o llave de " + e.getKey().getTexto() + " es muy largo (máximo 60)");
            }
            if (t.length() > 80) {
                throw new IllegalArgumentException("El nombre de " + e.getKey().getTexto() + " es muy largo (máximo 80)");
            }
            pares.add(new String[]{"pagos.cuenta." + e.getKey().name(), c});
            pares.add(new String[]{"pagos.titular." + e.getKey().name(), t});
        }
        bd.guardarConfigs(pares.toArray(new String[0][]));
    }

    /** Lo que debe quedar en Supabase. Las fotos van por su nombre en la nube (cambia si cambia la foto). */
    static class Deseado {
        final Map<String, Object> tienda = new LinkedHashMap<>();
        final TreeMap<Long, Map<String, Object>> productos = new TreeMap<>();
        /** nombre en la nube → {archivo local, "logo" o "foto"} */
        final Map<String, String[]> fotos = new LinkedHashMap<>();
        final List<Object> cuentas = new ArrayList<>();
        final List<Object> existencias = new ArrayList<>();
    }

    /** Cómo terminó una subida. */
    public static class Resultado {
        public int subidos;      // productos nuevos o cambiados
        public int quitados;
        public int fotos;
        public boolean tienda;   // cambiaron los datos de la tienda
        public final List<String> problemas = new ArrayList<>();
        public Map<String, String> enviado;

        public boolean nada() {
            return subidos == 0 && quitados == 0 && fotos == 0 && !tienda;
        }
    }

    /** Sube bytes (las fotos). Aparte de lo de JSON para poder probarlo sin internet. */
    public interface Bytes {
        int subir(String url, Map<String, String> cabeceras, byte[] cuerpo) throws IOException;
    }

    public static Bytes bytesPorInternet() {
        HttpClient c = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
        return (url, cab, cuerpo) -> {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(40));
            cab.forEach(b::header);
            b.POST(HttpRequest.BodyPublishers.ofByteArray(cuerpo));
            try {
                return c.send(b.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Se canceló la subida");
            }
        };
    }

    public static final String CARPETA = "catalogo";
    public static final int LADO_FOTO = 600;
    public static final int LADO_LOGO = 256;

    private final String url;
    private final String clave;
    private final NubeRepartidor.Transporte red;
    private final Bytes bytes;
    /** archivo|tamaño|fecha → huella (no se vuelve a leer la foto cada vez) */
    private final Map<String, String> huellas = new HashMap<>();

    public TiendaEnLinea(String url, String clave, NubeRepartidor.Transporte red, Bytes bytes) {
        this.url = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.clave = clave;
        this.red = red;
        this.bytes = bytes;
    }

    public TiendaEnLinea(NubeRepartidor.Ajustes a) {
        this(a.url, a.clave, NubeRepartidor.porInternet(), bytesPorInternet());
    }

    /** El enlace que se comparte con los clientes. */
    public static String enlace(String paginaRepartidor) {
        String p = paginaRepartidor == null ? "" : paginaRepartidor.trim();
        if (p.isEmpty()) {
            return "";
        }
        int q = p.indexOf('?');
        if (q >= 0) {
            p = p.substring(0, q);
        }
        if (p.endsWith(".html")) {
            p = p.substring(0, p.lastIndexOf('/') + 1);
        }
        return (p.endsWith("/") ? p : p + "/") + "tienda.html";
    }

    public String urlFoto(String nombre) {
        return url + "/storage/v1/object/public/" + CARPETA + "/" + nombre;
    }

    /** Arma lo que debe quedar en Supabase (lee las fotos para saber si cambiaron). */
    Deseado armar(Datos d) {
        Deseado r = new Deseado();
        AjustesRecibo a = d.recibo;
        String nombre = a.nombre == null || a.nombre.isBlank() ? d.negocio : a.nombre;
        r.tienda.put("id", 1L);
        r.tienda.put("nombre", corto(nombre, 80));
        String logo = null;
        if (a.logo != null) {
            String h = huella(new File(a.logo));
            if (h != null) {
                String n = "logo-" + h + ".png";
                r.fotos.put(n, new String[]{a.logo, "logo"});
                logo = urlFoto(n);
            }
        }
        r.tienda.put("logo", logo);
        r.tienda.put("direccion", corto(a.direccion, 200));
        r.tienda.put("telefono", corto(a.telefono, 20));
        r.tienda.put("horario", corto(d.ajustes.horario, 120));
        r.tienda.put("abierta", d.ajustes.abierta);
        r.tienda.put("domicilio", d.ajustes.domicilio);
        r.tienda.put("minimo", d.ajustes.minimo);
        List<String> pagos = new ArrayList<>();
        for (MetodoPago m : d.pagos) {
            pagos.add(m.name().toLowerCase());
        }
        if (!pagos.contains("efectivo")) {
            pagos.add(0, "efectivo");
        }
        r.tienda.put("pagos", pagos);
        for (MetodoPago m : d.pagos) {
            String[] c = d.cuentas.get(m);
            if (pagaAntes(m) && c != null && c[0] != null && !c[0].isBlank()) {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("medio", m.name().toLowerCase());
                x.put("cuenta", corto(c[0], 60));
                x.put("titular", corto(c[1], 80));
                r.cuentas.add(x);
            }
        }

        for (BaseTienda.ProductoEnLinea p : d.productos) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.id);
            m.put("nombre", corto(p.nombre, 120).isEmpty() ? "Producto " + p.id : corto(p.nombre, 120));
            String desc = p.descripcion == null ? "" : p.descripcion.trim().replaceAll("\\s+", " ");
            if (p.unidad == Unidad.LIBRA || p.unidad == Unidad.KILO) {
                String por = p.unidad == Unidad.LIBRA ? "Precio por libra" : "Precio por kilo";
                desc = desc.isEmpty() ? por : por + " · " + desc;
            }
            m.put("descripcion", corto(desc, 300));
            m.put("marca", "");
            m.put("categoria", corto(p.categoria, 60));
            m.put("precio", Math.max(0, Math.min(100_000_000L, p.precio)));
            String foto = null;
            if (p.foto != null && !p.foto.isBlank()) {
                String h = huella(new File(p.foto));
                if (h != null) {
                    String n = "p/" + p.id + "-" + h + ".jpg";
                    r.fotos.put(n, new String[]{p.foto, "foto"});
                    foto = urlFoto(n);
                }
            }
            m.put("foto", foto);
            m.put("disponible", !p.manejaInventario || p.stock > 0);
            m.put("codigo", corto(p.codigo, 40));
            List<Object> cs = new ArrayList<>();
            for (String[] c : p.caracteristicas) {
                String n = corto(c[0], 60), v = corto(c[1], 120);
                if (!n.isEmpty() && !v.isEmpty() && cs.size() < 20) {
                    Map<String, Object> x = new LinkedHashMap<>();
                    x.put("n", n);
                    x.put("v", v);
                    cs.add(x);
                }
            }
            m.put("caracteristicas", cs);
            m.put("orden", (long) p.ordenCategoria);
            r.productos.put(p.id, m);
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("id", p.id);
            x.put("stock", p.stock % 1000 == 0 ? (Object) (p.stock / 1000) : (Object) (p.stock / 1000.0)); // milésimas -> unidades
            x.put("maneja", p.manejaInventario);
            r.existencias.add(x);
        }
        return r;
    }

    /**
     * Sube lo que cambió desde la última vez. enviado: lo que ya se subió (clave → JSON), se
     * devuelve actualizado en el resultado. revisarNube: además mira qué hay en Supabase y quita
     * lo que sobre (se hace al abrir el programa y con «Actualizar ahora»).
     */
    public Resultado sincronizar(Datos d, Map<String, String> enviado, boolean revisarNube) throws IOException {
        Deseado des = armar(d);
        Map<String, String> env = new LinkedHashMap<>(enviado == null ? Map.of() : enviado);
        Resultado r = new Resultado();

        Set<Long> enNube = new LinkedHashSet<>();
        if (revisarNube) {
            Object o = Json.leer(llamar("GET", "catalogo?select=id", null).cuerpo);
            if (o instanceof List) {
                for (Object x : (List<?>) o) {
                    if (x instanceof Map) {
                        enNube.add(Json.entero((Map<?, ?>) x, "id"));
                    }
                }
            }
            // lo que creíamos subido pero ya no está (alguien borró en Supabase) se vuelve a subir
            env.keySet().removeIf(k -> k.startsWith("p") && !enNube.contains(Long.parseLong(k.substring(1))));
        }

        // 1. las fotos que todavía no están
        Set<String> fotosMalas = new LinkedHashSet<>();
        for (Map.Entry<String, String[]> f : des.fotos.entrySet()) {
            String k = "f" + f.getKey();
            if (env.containsKey(k)) {
                continue;
            }
            try {
                byte[] b = preparar(new File(f.getValue()[0]), "logo".equals(f.getValue()[1]));
                subirFoto(f.getKey(), b, "logo".equals(f.getValue()[1]) ? "image/png" : "image/jpeg");
                env.put(k, "1");
                r.fotos++;
            } catch (IOException e) {
                if (e instanceof NubeRepartidor.ErrorNube && e.getMessage().startsWith("No hay internet")) {
                    throw e;
                }
                fotosMalas.add(urlFoto(f.getKey()));
                r.problemas.add("No se pudo subir la foto " + new File(f.getValue()[0]).getName() + ": " + e.getMessage());
            }
        }
        // una foto que no subió no se anuncia (la página mostraría un hueco): va sin foto y se reintenta
        for (Map<String, Object> m : des.productos.values()) {
            if (fotosMalas.contains(m.get("foto"))) {
                m.put("foto", null);
            }
        }
        if (fotosMalas.contains(des.tienda.get("logo"))) {
            des.tienda.put("logo", null);
        }

        // 2. la tienda
        String t = Json.escribir(des.tienda);
        if (!t.equals(env.get("t"))) {
            llamar("POST", "tienda_info?on_conflict=id", "[" + t + "]");
            env.put("t", t);
            r.tienda = true;
        }

        // 2b. los números de cuenta (solo los usa la función de WhatsApp; no salen en la página)
        String g = Json.escribir(des.cuentas);
        if (!g.equals(env.get("g"))) {
            try {
                llamar("DELETE", "pagos_tienda?medio=not.is.null", null);
                if (!des.cuentas.isEmpty()) {
                    llamar("POST", "pagos_tienda?on_conflict=medio", g);
                }
                env.put("g", g);
            } catch (NubeRepartidor.ErrorNube e) {
                if (!e.getMessage().startsWith("Falta la tienda")) { // sin whatsapp.sql todavía: se sube después
                    throw e;
                }
            }
        }

        // 2c. cuántos quedan (solo lo ve el encargado de pedidos): se manda todo junto si algo cambió
        String ex = Json.escribir(des.existencias);
        if (!ex.equals(env.get("s"))) {
            try {
                llamar("DELETE", "existencias_web?id=not.is.null", null);
                if (!des.existencias.isEmpty()) {
                    llamar("POST", "existencias_web?on_conflict=id", ex);
                }
                env.put("s", ex);
            } catch (NubeRepartidor.ErrorNube e) {
                if (!e.getMessage().startsWith("Falta la tienda")) { // sin encargados.sql todavía
                    throw e;
                }
            }
        }

        // 3. los productos cambiados o nuevos (de a 200)
        List<String> cambiados = new ArrayList<>();
        List<Long> ids = new ArrayList<>();
        for (Map.Entry<Long, Map<String, Object>> e : des.productos.entrySet()) {
            String j = Json.escribir(e.getValue());
            if (!j.equals(env.get("p" + e.getKey()))) {
                cambiados.add(j);
                ids.add(e.getKey());
            }
        }
        for (int i = 0; i < cambiados.size(); i += 200) {
            List<String> lote = cambiados.subList(i, Math.min(cambiados.size(), i + 200));
            llamar("POST", "catalogo?on_conflict=id", "[" + String.join(",", lote) + "]");
            for (int k = i; k < i + lote.size(); k++) {
                env.put("p" + ids.get(k), cambiados.get(k));
            }
        }
        r.subidos = cambiados.size();

        // 4. lo que ya no se vende en línea
        Set<Long> quitar = new LinkedHashSet<>(enNube);
        for (String k : env.keySet()) {
            if (k.startsWith("p")) {
                quitar.add(Long.parseLong(k.substring(1)));
            }
        }
        quitar.removeAll(des.productos.keySet());
        List<Long> lq = new ArrayList<>(quitar);
        for (int i = 0; i < lq.size(); i += 100) {
            List<Long> lote = lq.subList(i, Math.min(lq.size(), i + 100));
            StringBuilder sb = new StringBuilder();
            for (Long id : lote) {
                sb.append(sb.length() == 0 ? "" : ",").append(id);
            }
            llamar("DELETE", "catalogo?id=in.(" + sb + ")", null);
            for (Long id : lote) {
                env.remove("p" + id);
            }
        }
        r.quitados = lq.size();
        // las fotos que ya nadie usa se olvidan (si vuelven, se suben otra vez)
        env.keySet().removeIf(k -> k.startsWith("f") && !des.fotos.containsKey(k.substring(1)));
        r.enviado = env;
        return r;
    }

    /** Lo enviado, guardado como texto en la configuración. */
    public static Map<String, String> leerEnviado(String texto) {
        Map<String, String> m = new LinkedHashMap<>();
        try {
            Object o = Json.leer(texto == null || texto.isBlank() ? "{}" : texto);
            if (o instanceof Map) {
                for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                    if (e.getValue() != null) {
                        m.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                    }
                }
            }
        } catch (IllegalArgumentException e) {
            // dañado: se sube todo otra vez
        }
        return m;
    }

    public static String escribirEnviado(Map<String, String> m) {
        return Json.escribir(new LinkedHashMap<String, Object>(m));
    }



    // ------------------------------------------------------------------
    //  La caja como «cerebro» del encargado de pedidos (página pedidos.html)
    // ------------------------------------------------------------------

    /** Un trabajo que el encargado le dejó a la caja: asignar, imprimir o vender. */
    public static class Trabajo {
        public long id;
        public String tipo = "";
        public Map<?, ?> datos = Map.of();
    }

    /** La caja está prendida (el encargado ve «Caja conectada»). */
    public void latido() throws IOException {
        llamar("PATCH", "caja_estado?id=eq.1", "{\"visto_en\":\"" + java.time.OffsetDateTime.now() + "\"}");
    }

    public List<Trabajo> trabajosPendientes() throws IOException {
        Object o = Json.leer(llamar("GET", "trabajos_caja?estado=eq.pendiente&select=id,tipo,datos&order=id.asc&limit=10", null).cuerpo);
        List<Trabajo> l = new ArrayList<>();
        if (o instanceof List) {
            for (Object x : (List<?>) o) {
                if (x instanceof Map) {
                    Map<?, ?> m = (Map<?, ?>) x;
                    Trabajo t = new Trabajo();
                    t.id = Json.entero(m, "id");
                    t.tipo = Json.texto(m, "tipo");
                    t.datos = m.get("datos") instanceof Map ? (Map<?, ?>) m.get("datos") : Map.of();
                    l.add(t);
                }
            }
        }
        return l;
    }

    public void terminarTrabajo(long id, boolean ok, String resultado) throws IOException {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("estado", ok ? "hecho" : "error");
        c.put("resultado", corto(resultado == null ? "" : resultado, 300));
        c.put("hecho_en", java.time.OffsetDateTime.now().toString());
        llamar("PATCH", "trabajos_caja?id=eq." + id, Json.escribir(c));
    }

    public rutas.modelo.PedidoWeb pedidoPorId(long id) throws IOException {
        Object o = Json.leer(llamar("GET", "pedidos_web?id=eq." + id + "&select=" + CAMPOS + ",faltantes,venta", null).cuerpo);
        if (o instanceof List && !((List<?>) o).isEmpty() && ((List<?>) o).get(0) instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) ((List<?>) o).get(0);
            rutas.modelo.PedidoWeb p = pedido(m);
            if (m.get("faltantes") instanceof List) {
                for (Object f : (List<?>) m.get("faltantes")) {
                    if (f instanceof Number) {
                        p.faltantes.add(((Number) f).longValue());
                    }
                }
            }
            p.venta = m.get("venta") instanceof Number ? ((Number) m.get("venta")).longValue() : 0;
            return p;
        }
        return null;
    }

    public void anotarVenta(long pedido, long numero) throws IOException {
        llamar("PATCH", "pedidos_web?id=eq." + pedido, "{\"venta\":" + numero + "}");
    }

    /** Un encargado de pedidos (entra a pedidos.html con su celular y su clave). */
    public static class Encargado {
        public String id = "";
        public String nombre = "";
        public String celular = "";
        public boolean conClave;
    }

    public List<Encargado> encargados() throws IOException {
        Object o = Json.leer(llamar("GET", "encargados?activo=eq.true&select=id,nombre,celular,con_clave&order=nombre", null).cuerpo);
        List<Encargado> l = new ArrayList<>();
        if (o instanceof List) {
            for (Object x : (List<?>) o) {
                if (x instanceof Map) {
                    Map<?, ?> m = (Map<?, ?>) x;
                    Encargado e = new Encargado();
                    e.id = Json.texto(m, "id");
                    e.nombre = Json.texto(m, "nombre");
                    e.celular = Json.texto(m, "celular");
                    e.conClave = Boolean.TRUE.equals(m.get("con_clave"));
                    l.add(e);
                }
            }
        }
        return l;
    }

    /** Lo crea (o lo vuelve a activar si ese celular ya estuvo). */
    public void crearEncargado(String nombre, String celular) throws IOException {
        String n = corto(nombre, 80);
        String c = celular == null ? "" : celular.replaceAll("[^0-9]", "");
        if (c.matches("573[0-9]{9}")) {
            c = c.substring(2);
        }
        if (n.isEmpty()) {
            throw new IllegalArgumentException("Escriba el nombre");
        }
        if (!c.matches("3[0-9]{9}")) {
            throw new IllegalArgumentException("El celular son 10 números y empieza por 3");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nombre", n);
        m.put("celular", c);
        m.put("activo", true);
        llamar("POST", "encargados?on_conflict=celular", "[" + Json.escribir(m) + "]");
    }

    public void quitarEncargado(String id) throws IOException {
        if (!id.matches("[0-9a-f-]{36}")) {
            throw new IllegalArgumentException("Encargado no válido");
        }
        llamar("PATCH", "encargados?id=eq." + id, "{\"activo\":false}");
    }

    /** Le pone la clave de 6 números (la que tenía y su sesión dejan de servir). Devuelve "ok" o el problema. */
    public String claveEncargado(String id, String clave) throws IOException {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("p_id", id);
        c.put("p_clave", clave);
        Object o = Json.leer(llamar("POST", "rpc/dr_clave_encargado", Json.escribir(c)).cuerpo);
        return o == null ? "Supabase no respondió" : o.toString();
    }

    /** La página del encargado (junto a la del repartidor). */
    public static String enlaceEncargado(String paginaRepartidor) {
        String t = enlace(paginaRepartidor);
        return t.isEmpty() ? "" : t.replace("tienda.html", "pedidos.html");
    }

    // ------------------------------------------------------------------
    //  Los pedidos que llegan de la página
    // ------------------------------------------------------------------

    private static final String CAMPOS = "id,codigo,creado,nombre,celular,entrega,direccion,referencia,lat,lon,pago,paga_con,"
            + "nota,items,subtotal,domicilio,total,estado,motivo,tomado,comprobante,pagado";

    /** Los pedidos por atender (sin terminar, de los últimos 2 días), del más viejo al más nuevo. */
    public List<rutas.modelo.PedidoWeb> pedidosActivos() throws IOException {
        String desde = java.time.OffsetDateTime.now().minusDays(2).toString();
        String ruta = "pedidos_web?select=" + CAMPOS + "&or=(estado.not.in.(entregado,cancelado),tomado.is.false)"
                + "&creado=gte." + java.net.URLEncoder.encode(desde, java.nio.charset.StandardCharsets.UTF_8)
                + "&order=id.asc&limit=100";
        Object o = Json.leer(llamar("GET", ruta, null).cuerpo);
        List<rutas.modelo.PedidoWeb> l = new ArrayList<>();
        if (o instanceof List) {
            for (Object x : (List<?>) o) {
                if (x instanceof Map) {
                    l.add(pedido((Map<?, ?>) x));
                }
            }
        }
        return l;
    }

    public static rutas.modelo.PedidoWeb pedido(Map<?, ?> m) {
        rutas.modelo.PedidoWeb p = new rutas.modelo.PedidoWeb();
        p.id = Json.entero(m, "id");
        p.codigo = Json.texto(m, "codigo");
        try {
            p.creado = java.time.OffsetDateTime.parse(Json.texto(m, "creado"))
                    .atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDateTime();
        } catch (RuntimeException e) {
            p.creado = java.time.LocalDateTime.now();
        }
        p.nombre = Json.texto(m, "nombre");
        p.celular = Json.texto(m, "celular");
        p.entrega = Json.texto(m, "entrega");
        p.direccion = Json.texto(m, "direccion");
        p.referencia = Json.texto(m, "referencia");
        if (m.get("lat") instanceof Number && m.get("lon") instanceof Number) {
            p.lat = ((Number) m.get("lat")).doubleValue();
            p.lon = ((Number) m.get("lon")).doubleValue();
        }
        p.pago = Json.texto(m, "pago");
        p.pagaCon = m.get("paga_con") instanceof Number ? ((Number) m.get("paga_con")).longValue() : 0;
        p.nota = Json.texto(m, "nota");
        if (m.get("items") instanceof List) {
            for (Object x : (List<?>) m.get("items")) {
                if (x instanceof Map) {
                    Map<?, ?> i = (Map<?, ?>) x;
                    rutas.modelo.PedidoWeb.Item it = new rutas.modelo.PedidoWeb.Item(Json.texto(i, "nombre"),
                            Json.entero(i, "precio"), (int) Json.entero(i, "cantidad"));
                    it.id = Json.entero(i, "id");
                    p.items.add(it);
                }
            }
        }
        p.subtotal = Json.entero(m, "subtotal");
        p.domicilio = Json.entero(m, "domicilio");
        p.total = Json.entero(m, "total");
        p.estado = Json.texto(m, "estado");
        p.motivo = Json.texto(m, "motivo");
        p.tomado = Boolean.TRUE.equals(m.get("tomado"));
        p.comprobante = m.get("comprobante") == null ? null : String.valueOf(m.get("comprobante"));
        p.pagado = Boolean.TRUE.equals(m.get("pagado"));
        return p;
    }

    /** Ya se avisó en la caja: no vuelve a sonar (si manda el comprobante, Supabase lo vuelve a marcar). */
    public void marcarTomados(List<Long> ids) throws IOException {
        if (ids.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (Long id : ids) {
            sb.append(sb.length() == 0 ? "" : ",").append(id);
        }
        llamar("PATCH", "pedidos_web?id=in.(" + sb + ")", "{\"tomado\":true}");
    }

    /** Cambia el estado (lo ve el cliente en la página; si es «cancelado» le llega el WhatsApp con el motivo). */
    public void cambiarEstado(long id, String estado, String motivo) throws IOException {
        if (!java.util.Set.of("aceptado", "preparando", "en_camino", "entregado", "cancelado").contains(estado)) {
            throw new IllegalArgumentException("Estado no válido: " + estado);
        }
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("estado", estado);
        c.put("estado_en", java.time.OffsetDateTime.now().toString());
        if (motivo != null) {
            c.put("motivo", corto(motivo, 200));
        }
        llamar("PATCH", "pedidos_web?id=eq." + id, Json.escribir(c));
    }

    /** La caja ya vio la plata: «Pago confirmado» (le llega el WhatsApp al cliente). */
    public void marcarPagado(long id) throws IOException {
        llamar("PATCH", "pedidos_web?id=eq." + id, "{\"pagado\":true}");
    }

    /** Enlace de 10 minutos para ver la foto del comprobante (la carpeta es privada). */
    public String enlaceComprobante(String ruta) throws IOException {
        if (ruta == null || !ruta.matches("[0-9]+-[0-9]+\\.(jpg|png|pdf)")) {
            throw new IllegalArgumentException("No hay comprobante");
        }
        Map<String, String> cab = cabeceras();
        cab.put("Content-Type", "application/json");
        NubeRepartidor.Respuesta r;
        try {
            r = red.enviar("POST", url + "/storage/v1/object/sign/comprobantes/" + ruta, cab, "{\"expiresIn\":600}");
        } catch (java.net.http.HttpTimeoutException | java.net.ConnectException e) {
            throw new NubeRepartidor.ErrorNube("No hay internet o Supabase no responde");
        }
        if (r.codigo >= 300) {
            throw new NubeRepartidor.ErrorNube("No se pudo abrir el comprobante (" + r.codigo + ")");
        }
        Object o = Json.leer(r.cuerpo);
        String firmado = o instanceof Map ? Json.texto((Map<?, ?>) o, "signedURL") : "";
        if (firmado.isEmpty()) {
            throw new NubeRepartidor.ErrorNube("Supabase no dio el enlace del comprobante");
        }
        return url + "/storage/v1" + (firmado.startsWith("/") ? firmado : "/" + firmado);
    }

    // ------------------------------------------------------------------

    private void subirFoto(String nombre, byte[] b, String tipo) throws IOException {
        Map<String, String> cab = cabeceras();
        cab.put("Content-Type", tipo);
        cab.put("x-upsert", "true");
        cab.put("Cache-Control", "max-age=31536000"); // el nombre cambia si cambia la foto
        int c;
        try {
            c = bytes.subir(url + "/storage/v1/object/" + CARPETA + "/" + nombre, cab, b);
        } catch (java.net.http.HttpTimeoutException | java.net.ConnectException e) {
            throw new NubeRepartidor.ErrorNube("No hay internet o Supabase no responde");
        }
        if (c == 401 || c == 403) {
            throw new NubeRepartidor.ErrorNube("Supabase no dejó guardar la foto (revise la clave secreta)");
        }
        if (c == 404 || c == 400) {
            throw new NubeRepartidor.ErrorNube("Falta la carpeta «catalogo»: corra tienda.sql en Supabase");
        }
        if (c >= 300) {
            throw new NubeRepartidor.ErrorNube("Supabase respondió " + c + " al subir la foto");
        }
    }

    private Map<String, String> cabeceras() {
        Map<String, String> cab = new LinkedHashMap<>();
        cab.put("apikey", clave);
        if (clave.startsWith("eyJ")) {
            cab.put("Authorization", "Bearer " + clave);
        }
        return cab;
    }

    private NubeRepartidor.Respuesta llamar(String metodo, String ruta, String cuerpo) throws IOException {
        if (url.isBlank() || clave.isBlank()) {
            throw new NubeRepartidor.ErrorNube("Falta configurar Supabase en Configuración › Conexión");
        }
        Map<String, String> cab = cabeceras();
        cab.put("Accept", "application/json");
        if (cuerpo != null) {
            cab.put("Content-Type", "application/json");
            cab.put("Prefer", "resolution=merge-duplicates,return=minimal");
        }
        NubeRepartidor.Respuesta r;
        try {
            r = red.enviar(metodo, url + "/rest/v1/" + ruta, cab, cuerpo);
        } catch (java.net.http.HttpTimeoutException | java.net.ConnectException e) {
            throw new NubeRepartidor.ErrorNube("No hay internet o Supabase no responde");
        } catch (IOException e) {
            if (e instanceof NubeRepartidor.ErrorNube) {
                throw e;
            }
            throw new NubeRepartidor.ErrorNube("No hay internet o Supabase no responde (" + e.getMessage() + ")");
        }
        if (r.codigo == 401 || r.codigo == 403) {
            throw new NubeRepartidor.ErrorNube("Supabase no aceptó la clave secreta. Revise Configuración › Conexión.");
        }
        if (r.codigo == 404) {
            throw new NubeRepartidor.ErrorNube("Falta la tienda en línea en Supabase: corra tienda.sql");
        }
        if (r.codigo >= 300) {
            String cuerpoR = r.cuerpo.replaceAll("\\s+", " ");
            throw new NubeRepartidor.ErrorNube("Supabase respondió " + r.codigo + ": "
                    + (cuerpoR.length() > 160 ? cuerpoR.substring(0, 160) + "…" : cuerpoR));
        }
        return r;
    }

    /** Huella corta de la foto (10 letras). null si no existe o no se puede leer. */
    String huella(File f) {
        if (!f.isFile()) {
            return null;
        }
        String k = f.getAbsolutePath() + "|" + f.length() + "|" + f.lastModified();
        String h = huellas.get(k);
        if (h == null) {
            try {
                byte[] d = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(f.toPath()));
                h = HexFormat.of().formatHex(d).substring(0, 10);
                huellas.put(k, h);
            } catch (IOException | NoSuchAlgorithmException e) {
                return null;
            }
        }
        return h;
    }

    /** La foto achicada (600 px, JPG sobre blanco) o el logo (256 px, PNG con su fondo). */
    static byte[] preparar(File f, boolean esLogo) throws IOException {
        BufferedImage img = ImageIO.read(f);
        if (img == null) {
            throw new IOException("no es una imagen JPG o PNG");
        }
        int lado = esLogo ? LADO_LOGO : LADO_FOTO;
        double esc = Math.min(1.0, (double) lado / Math.max(img.getWidth(), img.getHeight()));
        int w = Math.max(1, (int) Math.round(img.getWidth() * esc));
        int h = Math.max(1, (int) Math.round(img.getHeight() * esc));
        BufferedImage out = new BufferedImage(w, h, esLogo ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        if (!esLogo) {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, w, h);
        }
        g.drawImage(img, 0, 0, w, h, null);
        g.dispose();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        if (esLogo) {
            ImageIO.write(out, "png", bo);
        } else {
            ImageWriter wr = ImageIO.getImageWritersByFormatName("jpg").next();
            ImageWriteParam p = wr.getDefaultWriteParam();
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionQuality(0.82f);
            try (MemoryCacheImageOutputStream mo = new MemoryCacheImageOutputStream(bo)) {
                wr.setOutput(mo);
                wr.write(null, new IIOImage(out, null, null), p);
            } finally {
                wr.dispose();
            }
        }
        return bo.toByteArray();
    }

    private static String corto(String s, int max) {
        String t = s == null ? "" : s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }
}
