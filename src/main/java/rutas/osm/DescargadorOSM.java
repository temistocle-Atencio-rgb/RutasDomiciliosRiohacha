package rutas.osm;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.function.Consumer;
import rutas.modelo.Ciudad;

/**
 * Descarga de OpenStreetMap (API Overpass) las calles, lugares y costa de una ciudad
 * y los convierte con ProcesadorOSM. Asi el programa sirve para cualquier municipio.
 */
public final class DescargadorOSM {

    private static final String[] SERVIDORES = {
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
    };
    private static final String TIPOS_VIA = "^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|"
            + "living_street|service|road|track|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link)$";

    private DescargadorOSM() {
    }

    /**
     * Descarga y procesa la ciudad en la carpeta indicada.
     * @param progreso recibe mensajes para mostrar al usuario
     */
    public static ProcesadorOSM.Resumen descargar(Ciudad c, File carpeta, Consumer<String> progreso)
            throws IOException, InterruptedException {
        String caja = String.format(Locale.US, "(%.5f,%.5f,%.5f,%.5f)", c.sur(), c.oeste(), c.norte(), c.este());
        String cajaCosta = String.format(Locale.US, "(%.5f,%.5f,%.5f,%.5f)",
                c.sur() - 0.03, c.oeste() - 0.03, c.norte() + 0.03, c.este() + 0.03);

        progreso.accept("Descargando calles y carreras de " + c.getNombre() + "...");
        String vias = consultar("[out:json][timeout:170];(way[\"highway\"~\"" + TIPOS_VIA + "\"]" + caja
                + ";);(._;>;);out body;");

        progreso.accept("Descargando lugares, barrios y semáforos...");
        String b = caja;
        String lugares = consultar("[out:json][timeout:170];(nwr[name][amenity]" + b + ";nwr[name][shop]" + b
                + ";nwr[name][tourism]" + b + ";nwr[name][leisure]" + b + ";nwr[place~\"neighbourhood|suburb|quarter\"]" + b
                + ";nwr[name][office]" + b + ";nwr[name][healthcare]" + b
                + ";node[highway~\"traffic_signals|stop\"]" + b + ";node[traffic_calming]" + b + ";);out center tags;");

        progreso.accept("Descargando la línea de costa...");
        String costa;
        try {
            // consulta corta: en municipios sin mar no debe quedarse esperando
            costa = consultar("[out:json][timeout:45];way[\"natural\"=\"coastline\"]" + cajaCosta + ";out geom;", 60, 1);
        } catch (IOException e) {
            costa = null; // sin costa el mapa igual sirve
        }

        String casas;
        progreso.accept("Descargando las casas y edificios...");
        try {
            casas = consultar("[out:json][timeout:170];way[\"building\"]" + caja + ";out geom;", 180, 1);
        } catch (IOException e) {
            casas = null; // sin casas el mapa igual sirve
        }

        progreso.accept("Armando el grafo de " + c.getNombre() + "...");
        File temporal = new File(carpeta.getParentFile(), carpeta.getName() + ".nuevo");
        ProcesadorOSM.Resumen r = ProcesadorOSM.procesar(vias, lugares, costa, temporal);
        if (casas != null) {
            ProcesadorOSM.procesarCasas(casas, temporal);
        }
        if (r.nodos < 50) {
            throw new IOException("OpenStreetMap devolvió muy pocas calles para " + c.getNombre() + " (" + r.nodos + " nodos).");
        }
        // se reemplaza la carpeta solo cuando todo salio bien
        carpeta.mkdirs();
        for (String f : new String[]{"nodos.txt", "vias.txt", "lugares.txt", "costa.txt", "casas.txt"}) {
            if (!new File(temporal, f).exists()) {
                continue;
            }
            java.nio.file.Files.move(new File(temporal, f).toPath(), new File(carpeta, f).toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        temporal.delete();
        return r;
    }

    /** Descarga solo las casas de una ciudad que ya tiene mapa. Devuelve cuantas se guardaron. */
    public static int descargarCasas(Ciudad c, File carpeta) throws IOException, InterruptedException {
        String caja = String.format(Locale.US, "(%.5f,%.5f,%.5f,%.5f)", c.sur(), c.oeste(), c.norte(), c.este());
        String json = consultar("[out:json][timeout:170];way[\"building\"]" + caja + ";out geom;", 180, 2);
        return ProcesadorOSM.procesarCasas(json, carpeta);
    }

    static String consultar(String consulta) throws IOException, InterruptedException {
        return consultar(consulta, 200, 2);
    }

    /** Envia la consulta a Overpass probando varios servidores. */
    static String consultar(String consulta, int segundos, int intentos) throws IOException, InterruptedException {
        HttpClient cliente = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        String cuerpo = "data=" + URLEncoder.encode(consulta, StandardCharsets.UTF_8);
        IOException ultimo = null;
        long limite = System.currentTimeMillis() + (long) segundos * 1000 * 3; // tope total
        for (String servidor : SERVIDORES) {
            if (System.currentTimeMillis() > limite) {
                break;
            }
            for (int intento = 0; intento < intentos; intento++) {
                try {
                    HttpRequest req = HttpRequest.newBuilder(URI.create(servidor))
                            .timeout(Duration.ofSeconds(segundos))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .header("User-Agent", "DomiRuta/2.0 (rutas de domicilios, La Guajira)")
                            .POST(HttpRequest.BodyPublishers.ofString(cuerpo))
                            .build();
                    HttpResponse<String> resp = cliente.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                    if (resp.statusCode() == 200 && resp.body().trim().startsWith("{")) {
                        return resp.body();
                    }
                    ultimo = new IOException("El servidor " + servidor + " respondió " + resp.statusCode());
                    if (resp.statusCode() == 429 || resp.statusCode() == 504) {
                        Thread.sleep(4000); // servidor ocupado: se espera un poco
                    }
                } catch (IOException e) {
                    ultimo = e;
                }
            }
        }
        throw new IOException("No se pudo descargar de OpenStreetMap. Revise la conexión a internet.\n"
                + (ultimo == null ? "" : ultimo.getMessage()));
    }
}
