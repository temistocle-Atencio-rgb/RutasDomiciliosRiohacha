package rutas.modelo;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Un municipio que el programa puede usar: su centro y el radio del area urbana. */
public class Ciudad {

    private final String clave;
    private final String nombre;
    private final double lat;
    private final double lon;
    private final double radioKm;

    public Ciudad(String clave, String nombre, double lat, double lon, double radioKm) {
        this.clave = clave;
        this.nombre = nombre;
        this.lat = lat;
        this.lon = lon;
        this.radioKm = radioKm;
    }

    public double sur() { return lat - radioKm / 110.574; }
    public double norte() { return lat + radioKm / 110.574; }
    public double oeste() { return lon - radioKm / (111.320 * Math.cos(Math.toRadians(lat))); }
    public double este() { return lon + radioKm / (111.320 * Math.cos(Math.toRadians(lat))); }

    public String getClave() { return clave; }
    public String getNombre() { return nombre; }
    public double getLat() { return lat; }
    public double getLon() { return lon; }
    public double getRadioKm() { return radioKm; }

    /** Carpeta donde se guarda el mapa que se descarga (en la carpeta del usuario). */
    public File carpetaDescarga(File usuario) {
        return new File(new File(usuario, "mapas"), clave);
    }

    /**
     * Carpeta con el mapa de la ciudad: primero el descargado por el usuario (mas reciente)
     * y si no, el que viene con el programa en datos/.
     */
    public File carpetaMapa(File datos, File usuario) {
        File descargado = carpetaDescarga(usuario);
        if (new File(descargado, "vias.txt").exists()) {
            return descargado;
        }
        return new File(datos, clave);
    }

    public boolean estaDisponible(File datos, File usuario) {
        return new File(carpetaMapa(datos, usuario), "vias.txt").exists();
    }

    /** Lee datos/ciudades.txt: clave;nombre;lat;lon;radio en km */
    public static List<Ciudad> cargar(File archivo) throws IOException {
        List<Ciudad> lista = new ArrayList<>();
        for (String l : Files.readAllLines(archivo.toPath(), StandardCharsets.UTF_8)) {
            if (l.isBlank() || l.startsWith("#")) {
                continue;
            }
            String[] c = l.split(";");
            lista.add(new Ciudad(c[0].trim(), c[1].trim(), Double.parseDouble(c[2]), Double.parseDouble(c[3]),
                    Double.parseDouble(c[4])));
        }
        return lista;
    }

    @Override
    public String toString() {
        return nombre;
    }
}
