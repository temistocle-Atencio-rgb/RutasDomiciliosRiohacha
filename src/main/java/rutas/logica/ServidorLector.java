package rutas.logica;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Servidor pequeno donde el celular manda los codigos que lee con la camara.
 *
 * El celular va por cable USB y "adb reverse" hace que su localhost:8765 llegue a este computador.
 * Solo escucha en 127.0.0.1: nadie de la red wifi puede mandar codigos. Ademas, cada envio debe
 * traer la cabecera X-Lector (una pagina web cualquiera no puede ponerla) y un id, para que un
 * reintento del celular no cuente el mismo codigo dos veces.
 *
 * Protocolo (el mismo de la app DomiRuta Lector):
 *   GET  /api/estado  -> {"ok":true,...}
 *   POST /api/codigo  cabecera X-Lector: 1, cuerpo "id\ncodigo"  -> 200 "ok"
 *   GET  /            pagina para leer con Chrome si el celular no tiene la app
 */
public final class ServidorLector {

    public static final int PUERTO = 8765;
    private static final int MAXIMO_CODIGO = 128;

    private final int puertoPedido;
    private final Consumer<String> alLeer;
    private final Set<String> vistos = new LinkedHashSet<>();
    private HttpServer servidor;
    private ExecutorService hilos;
    private int puerto;

    /**
     * @param puerto PUERTO para el celular; 0 escoge uno libre (para las pruebas)
     * @param alLeer recibe cada codigo nuevo (en un hilo del servidor, no en el de la pantalla)
     */
    public ServidorLector(int puerto, Consumer<String> alLeer) {
        this.puertoPedido = puerto;
        this.alLeer = alLeer;
    }

    /** Empieza a escuchar. Lanza IOException si el puerto ya esta ocupado. */
    public synchronized void empezar() throws IOException {
        if (servidor != null) {
            return;
        }
        HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), puertoPedido), 0);
        s.createContext("/", this::pagina);
        s.createContext("/api/estado", this::estado);
        s.createContext("/api/codigo", this::codigo);
        hilos = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "lector-servidor");
            t.setDaemon(true);
            return t;
        });
        s.setExecutor(hilos);
        s.start();
        servidor = s;
        puerto = s.getAddress().getPort();
    }

    public synchronized void parar() {
        if (servidor != null) {
            servidor.stop(0);
            hilos.shutdownNow();
            servidor = null;
        }
    }

    public int getPuerto() {
        return puerto;
    }

    /**
     * True si en ese puerto ya responde otro lector (por ejemplo el programa DomiRuta Lector
     * instalado aparte). En ese caso ese programa atiende el celular y escribe los codigos.
     */
    public static boolean hayOtroLector(int puerto) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + puerto + "/api/estado").openConnection();
            c.setConnectTimeout(800);
            c.setReadTimeout(800);
            int r = c.getResponseCode();
            c.disconnect();
            return r == 200;
        } catch (IOException e) {
            return false;
        }
    }

    // ---------------- lo que llega ----------------

    private void codigo(HttpExchange ex) throws IOException {
        try (ex) {
            String origen = ex.getRequestHeaders().getFirst("Origin");
            if (!ex.getRequestMethod().equals("POST") || !hostPropio(ex) || !origenPropio(origen)
                    || !"1".equals(ex.getRequestHeaders().getFirst("X-Lector"))) {
                responder(ex, 403, "text/plain", "No permitido");
                return;
            }
            String cuerpo;
            try (InputStream in = ex.getRequestBody()) {
                cuerpo = new String(in.readNBytes(1024), StandardCharsets.UTF_8);
            }
            int salto = cuerpo.indexOf('\n');
            String id = salto > 0 ? cuerpo.substring(0, salto).trim() : "";
            String codigo = limpiar(salto > 0 ? cuerpo.substring(salto + 1) : cuerpo);
            if (codigo.isEmpty()) {
                responder(ex, 400, "text/plain", "Codigo vacio");
                return;
            }
            boolean nuevo;
            synchronized (vistos) {
                nuevo = id.isEmpty() || vistos.add(id);
                while (vistos.size() > 500) {
                    vistos.remove(vistos.iterator().next());
                }
            }
            if (nuevo) {
                alLeer.accept(codigo);
            }
            responder(ex, 200, "text/plain", "ok");
        }
    }

    private void estado(HttpExchange ex) throws IOException {
        try (ex) {
            if (!hostPropio(ex)) {
                responder(ex, 403, "text/plain", "No permitido");
                return;
            }
            responder(ex, 200, "application/json", "{\"ok\":true,\"programa\":\"DomiRuta\"}");
        }
    }

    private void pagina(HttpExchange ex) throws IOException {
        try (ex) {
            String ruta = ex.getRequestURI().getPath();
            if (!hostPropio(ex) || !ex.getRequestMethod().equals("GET")) {
                responder(ex, 403, "text/plain", "No permitido");
                return;
            }
            if (!ruta.equals("/") && !ruta.equals("/index.html")) {
                responder(ex, 404, "text/plain", "No existe");
                return;
            }
            try (InputStream in = ServidorLector.class.getResourceAsStream("/lector/index.html")) {
                if (in == null) {
                    responder(ex, 404, "text/plain", "Falta la pagina del lector");
                    return;
                }
                ex.getResponseHeaders().set("Cache-Control", "no-cache");
                responder(ex, 200, "text/html; charset=utf-8", in.readAllBytes());
            }
        }
    }

    /** Quita saltos de linea y caracteres de control; corta codigos absurdamente largos. */
    static String limpiar(String s) {
        StringBuilder sb = new StringBuilder();
        s.codePoints().filter(c -> !Character.isISOControl(c)).forEach(sb::appendCodePoint);
        String r = sb.toString().trim();
        return r.length() > MAXIMO_CODIGO ? r.substring(0, MAXIMO_CODIGO) : r;
    }

    // "Host" con otro nombre seria una pagina web disfrazada (DNS rebinding): se rechaza
    private boolean hostPropio(HttpExchange ex) {
        String host = ex.getRequestHeaders().getFirst("Host");
        return host != null && (host.equals("localhost:" + puerto) || host.equals("127.0.0.1:" + puerto));
    }

    // la app del celular no manda Origin; la pagina propia manda el de este mismo puerto
    private boolean origenPropio(String origen) {
        return origen == null || origen.equals("http://localhost:" + puerto) || origen.equals("http://127.0.0.1:" + puerto);
    }

    private static void responder(HttpExchange ex, int codigo, String tipo, String texto) throws IOException {
        responder(ex, codigo, tipo, texto.getBytes(StandardCharsets.UTF_8));
    }

    private static void responder(HttpExchange ex, int codigo, String tipo, byte[] datos) throws IOException {
        ex.getResponseHeaders().set("Content-Type", tipo);
        ex.sendResponseHeaders(codigo, datos.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(datos);
        }
    }
}
