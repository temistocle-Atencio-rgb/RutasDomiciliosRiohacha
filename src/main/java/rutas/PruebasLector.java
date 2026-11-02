package rutas;

import static rutas.Pruebas.revisar;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import rutas.logica.PuenteCelular;
import rutas.logica.Rafagas;
import rutas.logica.ServidorLector;

/** Pruebas del lector de codigos: rafagas de teclado, celulares de adb y el servidor del celular. */
public class PruebasLector {

    static void correr() throws Exception {
        System.out.println("--- Lector de codigos ---");
        probarRafagas();
        probarDispositivos();
        probarDecisiones();
        probarServidor();
    }

    /** Escribe un texto letra por letra con {@code paso} milisegundos entre teclas; devuelve el instante final. */
    private static long escribir(Rafagas r, String texto, long desde, long paso) {
        long t = desde;
        for (char c : texto.toCharArray()) {
            r.tecla(c, t);
            t += paso;
        }
        return t - paso;
    }

    private static void probarRafagas() {
        Rafagas r = new Rafagas();
        long fin = escribir(r, "7702004003508", 1000, 8);
        revisar("rafaga: un lector USB (8 ms entre teclas) se reconoce", "7702004003508".equals(r.enter(fin + 8)));

        fin = escribir(r, "7702004003508", 5000, 150);
        revisar("rafaga: una persona escribiendo (150 ms) no es lector", r.enter(fin + 150) == null);

        fin = escribir(r, "pan", 9000, 200);
        fin = escribir(r, "7701234567890", fin + 400, 10);
        revisar("rafaga: lo escrito a mano antes no se pega al codigo", "7701234567890".equals(r.enter(fin + 10)));

        fin = escribir(r, "123", 20000, 5);
        revisar("rafaga: tres teclas rapidas no son codigo", r.enter(fin + 5) == null);

        fin = escribir(r, "7702004003508", 30000, 8);
        revisar("rafaga: un Enter que llega tarde (300 ms) es de una persona", r.enter(fin + 300) == null);

        fin = escribir(r, "7702004003508", 40000, 8);
        r.enter(fin + 8);
        revisar("rafaga: despues de un Enter se empieza de cero", r.enter(fin + 16) == null);
    }

    private static void probarDispositivos() {
        PuenteCelular.Dispositivos d = PuenteCelular.leerDispositivos("* daemon not running; starting now at tcp:5037\n"
                + "* daemon started successfully\nList of devices attached\n6054de3a\tdevice\nR58N12\tunauthorized\n\n");
        revisar("adb: lee el celular listo y omite los avisos del daemon",
                d.listos.equals(Collections.singletonList("6054de3a")));
        revisar("adb: nota el celular que no ha dado permiso", d.sinPermiso);
        PuenteCelular.Dispositivos v = PuenteCelular.leerDispositivos("List of devices attached\r\n\r\n");
        revisar("adb: sin celulares", v.listos.isEmpty() && !v.sinPermiso);
    }

    /** Lo que se respondio a "¿usar este celular como lector?" se recuerda al volver a abrir. */
    private static void probarDecisiones() throws Exception {
        java.io.File f = java.io.File.createTempFile("celulares", ".txt");
        java.util.Map<String, Boolean> m = new java.util.HashMap<>();
        m.put("6054de3a", true);
        m.put("R58N12", false);
        PuenteCelular.guardarDecisiones(f, m);
        java.util.Map<String, Boolean> leido = PuenteCelular.leerDecisiones(f);
        revisar("celulares: se recuerda cual es lector y cual solo carga",
                Boolean.TRUE.equals(leido.get("6054de3a")) && Boolean.FALSE.equals(leido.get("R58N12")) && leido.size() == 2);
        java.nio.file.Files.writeString(f.toPath(), "basura\nsi\nquizas X1\n");
        revisar("celulares: un archivo danado no rompe nada (se vuelve a preguntar)",
                PuenteCelular.leerDecisiones(f).isEmpty());
        f.delete();
        revisar("celulares: sin archivo, todos se preguntan", PuenteCelular.leerDecisiones(f).isEmpty());
    }

    private static void probarServidor() throws Exception {
        // para poder probar un Host falso (lo que haria una pagina web con DNS rebinding)
        System.setProperty("sun.net.http.allowRestrictedHeaders", "true");
        List<String> llegaron = Collections.synchronizedList(new ArrayList<>());
        ServidorLector s = new ServidorLector(0, llegaron::add);
        s.empezar();
        try {
            int p = s.getPuerto();
            String base = "http://127.0.0.1:" + p;
            revisar("servidor: responde el estado", pedir("GET", base + "/api/estado", null, null, null) == 200);
            revisar("servidor: hay otro lector en ese puerto", ServidorLector.hayOtroLector(p));

            int r = pedir("POST", base + "/api/codigo", "a1\n7702004003508", "1", null);
            revisar("servidor: recibe un codigo del celular", r == 200 && llegaron.equals(List.of("7702004003508")));
            pedir("POST", base + "/api/codigo", "a1\n7702004003508", "1", null);
            revisar("servidor: un reintento con el mismo id no cuenta dos veces", llegaron.size() == 1);
            pedir("POST", base + "/api/codigo", "a2\nAB-12\u0007 ", "1", "http://localhost:" + p);
            revisar("servidor: acepta la pagina propia y limpia caracteres raros",
                    llegaron.size() == 2 && llegaron.get(1).equals("AB-12"));

            revisar("servidor: sin la cabecera X-Lector se rechaza",
                    pedir("POST", base + "/api/codigo", "a3\n1", null, null) == 403);
            revisar("servidor: desde otra pagina web se rechaza",
                    pedir("POST", base + "/api/codigo", "a4\n1", "1", "http://malo.com") == 403);
            revisar("servidor: codigo vacio es error", pedir("POST", base + "/api/codigo", "a5\n  ", "1", null) == 400);
            revisar("servidor: con otro nombre de servidor (Host) se rechaza",
                    pedirConHost(base + "/api/estado", "malo.com:" + p) == 403);
            revisar("servidor: no deja leer otros archivos", pedir("GET", base + "/../pom.xml", null, null, null) == 404);
            revisar("servidor: nada de lo rechazado llego a la caja", llegaron.size() == 2);

            boolean ocupado;
            try {
                new ServidorLector(p, c -> { }).empezar();
                ocupado = false;
            } catch (IOException e) {
                ocupado = true;
            }
            revisar("servidor: si el puerto esta ocupado lo dice (no abre dos)", ocupado);
        } finally {
            s.parar();
        }
        revisar("servidor: al parar ya no responde", !ServidorLector.hayOtroLector(s.getPuerto()));
    }

    private static int pedir(String metodo, String url, String cuerpo, String xLector, String origen) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(metodo);
        if (xLector != null) {
            c.setRequestProperty("X-Lector", xLector);
        }
        if (origen != null) {
            c.setRequestProperty("Origin", origen);
        }
        if (cuerpo != null) {
            c.setDoOutput(true);
            try (OutputStream os = c.getOutputStream()) {
                os.write(cuerpo.getBytes(StandardCharsets.UTF_8));
            }
        }
        int r = c.getResponseCode();
        try (InputStream in = r < 400 ? c.getInputStream() : c.getErrorStream()) {
            if (in != null) {
                in.readAllBytes();
            }
        }
        c.disconnect();
        return r;
    }

    private static int pedirConHost(String url, String host) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("Host", host);
        int r = c.getResponseCode();
        c.disconnect();
        return r;
    }
}
