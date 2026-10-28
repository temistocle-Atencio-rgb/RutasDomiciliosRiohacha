package rutas.logica;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import rutas.estructuras.ArbolAVL;
import rutas.estructuras.Arista;
import rutas.estructuras.Grafo;
import rutas.modelo.Cliente;
import rutas.modelo.Geo;
import rutas.modelo.Incidente;
import rutas.modelo.Parada;
import rutas.modelo.Ruta;
import rutas.modelo.TipoIncidente;

/**
 * Guarda y lee en archivos de texto lo que registra el usuario:
 * clientes, novedades en las vias e historial de rutas.
 */
public class ArchivosDatos {

    private final File carpeta;

    public ArchivosDatos(File carpeta) {
        this.carpeta = carpeta;
        carpeta.mkdirs();
    }

    private static String limpio(String s) {
        return s == null ? "" : s.replace(";", ",").replace("|", "/").replace("^", " ").replace("\n", " ").trim();
    }

    private void escribirTodo(File f, List<String> lineas) throws IOException {
        Files.write(f.toPath(), lineas, StandardCharsets.UTF_8);
    }

    private List<String> leerSiExiste(File f) throws IOException {
        return f.exists() ? CargadorMapa.leer(f) : new ArrayList<>();
    }

    // ---------------- clientes (AVL por nombre) ----------------

    public ArbolAVL<String, Cliente> cargarClientes() throws IOException {
        ArbolAVL<String, Cliente> arbol = new ArbolAVL<>();
        for (String l : leerSiExiste(new File(carpeta, "clientes.txt"))) {
            String[] c = l.split(";", -1);
            if (c.length < 6) {
                continue;
            }
            try {
                Cliente cl = new Cliente(c[0], c[1], c[2], c[3], Double.parseDouble(c[4]), Double.parseDouble(c[5]));
                arbol.insertar(Geo.normalizar(cl.getNombre()), cl);
            } catch (NumberFormatException e) {
                // linea danada
            }
        }
        return arbol;
    }

    public void guardarClientes(ArbolAVL<String, Cliente> arbol) throws IOException {
        List<String> lineas = new ArrayList<>();
        lineas.add("# nombre;telefono;direccion;nota;lat;lon");
        for (Cliente c : arbol.enOrden()) {
            lineas.add(String.format(Locale.US, "%s;%s;%s;%s;%.7f;%.7f", limpio(c.getNombre()), limpio(c.getTelefono()),
                    limpio(c.getDireccion()), limpio(c.getNota()), c.getLat(), c.getLon()));
        }
        escribirTodo(new File(carpeta, "clientes.txt"), lineas);
    }

    // ---------------- direcciones corregidas a mano ----------------

    public void cargarDirecciones(Geocodificador geo) throws IOException {
        for (String l : leerSiExiste(new File(carpeta, "direcciones_corregidas.txt"))) {
            String[] c = l.split(";", -1);
            try {
                geo.aprender(c[0], Double.parseDouble(c[1]), Double.parseDouble(c[2]));
            } catch (RuntimeException e) {
                // linea danada
            }
        }
    }

    public void guardarDirecciones(Geocodificador geo) throws IOException {
        List<String> lineas = new ArrayList<>();
        lineas.add("# direccion (como se escribio);lat;lon  -  puntos corregidos a mano en el mapa");
        // recorrido inorden del AVL: quedan en orden alfabetico
        guardarInorden(geo.getAprendidas(), lineas);
        escribirTodo(new File(carpeta, "direcciones_corregidas.txt"), lineas);
    }

    private void guardarInorden(ArbolAVL<String, double[]> arbol, List<String> lineas) {
        for (rutas.estructuras.NodoAVL<String, double[]> n : arbol.nodosEnOrden()) {
            lineas.add(String.format(Locale.US, "%s;%.7f;%.7f", limpio(n.getClave()), n.getValor()[0], n.getValor()[1]));
        }
    }

    // ---------------- novedades en la via ----------------

    public void cargarIncidentes(Mapa mapa) throws IOException {
        mapa.getIncidentes().clear();
        for (String l : leerSiExiste(new File(carpeta, "novedades.txt"))) {
            String[] c = l.split(";", -1);
            try {
                int via = Integer.parseInt(c[0]);
                if (via >= 0 && via < mapa.getVias().size()) {
                    mapa.getIncidentes().put(via, new Incidente(via, TipoIncidente.valueOf(c[1]), c[2],
                            LocalDateTime.parse(c[3], Ruta.FORMATO_FECHA)));
                }
            } catch (RuntimeException e) {
                // linea danada
            }
        }
    }

    public void guardarIncidentes(Mapa mapa) throws IOException {
        List<String> lineas = new ArrayList<>();
        lineas.add("# indice de via;tipo;nota;fecha");
        for (Incidente i : mapa.listaIncidentes()) {
            lineas.add(i.getVia() + ";" + i.getTipo().name() + ";" + limpio(i.getNota()) + ";"
                    + i.getFecha().format(Ruta.FORMATO_FECHA));
        }
        escribirTodo(new File(carpeta, "novedades.txt"), lineas);
    }

    // ---------------- historial de rutas ----------------
    // fecha|criterio|salida|metros|segundos|regreso|metodo|base|paradas|nodos

    public void guardarRuta(Ruta r) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append(r.getFecha().format(Ruta.FORMATO_FECHA)).append('|')
          .append(r.getCriterio()).append('|')
          .append(r.getSalida()).append('|')
          .append(String.format(Locale.US, "%.1f|%.1f|", r.getMetros(), r.getSegundos()))
          .append(r.isRegreso()).append('|')
          .append(r.getMetodo()).append('|')
          .append(parada(r.getBase())).append('|');
        for (int i = 0; i < r.getParadas().size(); i++) {
            sb.append(i > 0 ? "~" : "").append(parada(r.getParadas().get(i)));
        }
        sb.append('|');
        List<Integer> nodos = r.nodos();
        for (int i = 0; i < nodos.size(); i++) {
            sb.append(i > 0 ? "," : "").append(nodos.get(i));
        }
        try (BufferedWriter bw = Files.newBufferedWriter(new File(carpeta, "historial_rutas.txt").toPath(),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            bw.write(sb.toString());
            bw.newLine();
        }
    }

    private static String parada(Parada p) {
        return String.format(Locale.US, "%s^%s^%.7f^%.7f^%d", limpio(p.getNombre()), limpio(p.getDireccion()),
                p.getLat(), p.getLon(), p.getNodo());
    }

    private static Parada leerParada(String s) {
        String[] c = s.split("\\^", -1);
        Parada p = new Parada(c[0], c[1], Double.parseDouble(c[2]), Double.parseDouble(c[3]));
        p.setNodo(Integer.parseInt(c[4]));
        return p;
    }

    public List<Ruta> cargarHistorial(Mapa mapa) throws IOException {
        List<Ruta> rutas = new ArrayList<>();
        Grafo g = mapa.getGrafo();
        for (String l : leerSiExiste(new File(carpeta, "historial_rutas.txt"))) {
            String[] c = l.split("\\|", -1);
            if (c.length != 10) {
                continue;
            }
            try {
                Parada base = leerParada(c[7]);
                List<Parada> paradas = new ArrayList<>();
                if (!c[8].isEmpty()) {
                    for (String s : c[8].split("~")) {
                        paradas.add(leerParada(s));
                    }
                }
                // se reconstruyen las aristas buscando cada par de nodos en el grafo
                List<Arista> aristas = new ArrayList<>();
                String[] ids = c[9].isEmpty() ? new String[0] : c[9].split(",");
                for (int i = 0; i + 1 < ids.length; i++) {
                    Arista a = g.buscarArista(Integer.parseInt(ids[i]), Integer.parseInt(ids[i + 1]));
                    if (a != null) {
                        aristas.add(a);
                    }
                }
                LocalTime salida = LocalTime.parse(c[2]);
                List<LocalTime> llegadas = new ArrayList<>();
                for (int i = 0; i < paradas.size(); i++) {
                    llegadas.add(salida);
                }
                Ruta r = new Ruta(base, paradas, llegadas, new ArrayList<>(), aristas, Double.parseDouble(c[3]),
                        Double.parseDouble(c[4]), salida, Boolean.parseBoolean(c[5]), c[1], c[6]);
                r.setFecha(LocalDateTime.parse(c[0], Ruta.FORMATO_FECHA));
                rutas.add(r);
            } catch (RuntimeException e) {
                // linea danada, se ignora
            }
        }
        return rutas;
    }
}
