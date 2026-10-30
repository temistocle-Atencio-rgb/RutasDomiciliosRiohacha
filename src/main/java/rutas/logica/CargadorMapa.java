package rutas.logica;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import rutas.estructuras.Grafo;
import rutas.modelo.CategoriaLugar;
import rutas.modelo.Geo;
import rutas.modelo.Lugar;
import rutas.modelo.Via;

/**
 * Lee los archivos de la carpeta datos/riohacha (sacados de OpenStreetMap)
 * y arma el grafo de la ciudad.
 */
public class CargadorMapa {

    public static Mapa cargar(File carpeta) throws IOException {
        List<String> lineasNodos = leer(new File(carpeta, "nodos.txt"));
        Grafo grafo = new Grafo(lineasNodos.size());
        for (String l : lineasNodos) {
            String[] c = l.split(";");
            grafo.setNodo(Integer.parseInt(c[0]), Double.parseDouble(c[1]), Double.parseDouble(c[2]), c[3].charAt(0));
        }

        List<Via> vias = new ArrayList<>();
        for (String l : leer(new File(carpeta, "vias.txt"))) {
            String[] c = l.split(";", -1);
            String[] ids = c[6].split(",");
            int[] nodos = new int[ids.length];
            for (int i = 0; i < ids.length; i++) {
                nodos[i] = Integer.parseInt(ids[i]);
            }
            int vel = c[4].equals("-") ? 0 : Integer.parseInt(c[4]);
            Via v = new Via(vias.size(), c[0], c[1], Integer.parseInt(c[2]), c[3], vel, nodos);
            if (c.length > 7) {
                v.setNombreAlterno(c[7]);
            }
            vias.add(v);

            // cada par de nodos seguidos es una arista (o dos si es doble via)
            for (int i = 0; i < nodos.length - 1; i++) {
                int a = nodos[i], b = nodos[i + 1];
                double m = Geo.haversine(grafo.getLat(a), grafo.getLon(a), grafo.getLat(b), grafo.getLon(b));
                if (v.getSentido() >= 0) {
                    grafo.agregarArista(a, b, m, v.getIndice());
                }
                if (v.getSentido() <= 0) {
                    grafo.agregarArista(b, a, m, v.getIndice());
                }
            }
        }

        // muchos tramos no tienen nombre en OSM: se deduce de los tramos vecinos en linea recta
        InferenciaNombres.aplicar(grafo, vias);

        List<Lugar> lugares = new ArrayList<>();
        for (String l : leer(new File(carpeta, "lugares.txt"))) {
            String[] c = l.split(";");
            lugares.add(new Lugar(lugares.size(), c[0], CategoriaLugar.desde(c[1]),
                    Double.parseDouble(c[2]), Double.parseDouble(c[3])));
        }

        List<double[][]> costa = new ArrayList<>();
        File archivoCosta = new File(carpeta, "costa.txt");
        if (archivoCosta.exists()) {
            for (String l : leer(archivoCosta)) {
                String[] pts = l.trim().split(" ");
                double[][] linea = new double[pts.length][2];
                for (int i = 0; i < pts.length; i++) {
                    String[] ll = pts[i].split(",");
                    linea[i][0] = Double.parseDouble(ll[0]);
                    linea[i][1] = Double.parseDouble(ll[1]);
                }
                costa.add(linea);
            }
        }
        Mapa mapa = new Mapa(grafo, vias, lugares, costa);
        mapa.setCasas(leerCasas(new File(carpeta, "casas.txt")));
        return mapa;
    }

    /** Formas de las casas: cada una es un arreglo de esquinas {lat, lon}. Vacio si no hay archivo. */
    public static List<double[][]> leerCasas(File archivo) throws IOException {
        List<double[][]> casas = new ArrayList<>();
        if (!archivo.exists()) {
            return casas;
        }
        for (String l : leer(archivo)) {
            String[] pts = l.trim().split(" ");
            if (pts.length < 3) {
                continue;
            }
            double[][] casa = new double[pts.length][2];
            for (int i = 0; i < pts.length; i++) {
                int coma = pts[i].indexOf(',');
                casa[i][0] = Double.parseDouble(pts[i].substring(0, coma));
                casa[i][1] = Double.parseDouble(pts[i].substring(coma + 1));
            }
            casas.add(casa);
        }
        return casas;
    }

    /** Lineas del archivo sin comentarios ni lineas vacias. */
    static List<String> leer(File f) throws IOException {
        List<String> lineas = new ArrayList<>();
        try (BufferedReader br = Files.newBufferedReader(f.toPath(), StandardCharsets.UTF_8)) {
            String l;
            while ((l = br.readLine()) != null) {
                if (!l.isBlank() && !l.startsWith("#")) {
                    lineas.add(l);
                }
            }
        }
        return lineas;
    }
}
