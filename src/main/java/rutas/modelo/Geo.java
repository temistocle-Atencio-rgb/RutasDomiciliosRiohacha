package rutas.modelo;

import java.text.Normalizer;

/**
 * Utilidades de geografia y de texto.
 * Las coordenadas (lat, lon) se pasan a metros en un plano local centrado en
 * Riohacha. En una ciudad de este tamano el error de esta proyeccion es minimo.
 */
public final class Geo {

    public static final double LAT0 = 11.54;
    public static final double LON0 = -72.90;
    private static final double M_POR_GRADO_LAT = 110_574.0;
    private static final double M_POR_GRADO_LON = 111_320.0 * Math.cos(Math.toRadians(LAT0));
    private static final double RADIO_TIERRA = 6_371_000.0;

    private Geo() {
    }

    /** Longitud a metros (eje x, positivo hacia el oriente). */
    public static double x(double lon) {
        return (lon - LON0) * M_POR_GRADO_LON;
    }

    /** Latitud a metros (eje y, positivo hacia el norte). */
    public static double y(double lat) {
        return (lat - LAT0) * M_POR_GRADO_LAT;
    }

    public static double lon(double x) {
        return x / M_POR_GRADO_LON + LON0;
    }

    public static double lat(double y) {
        return y / M_POR_GRADO_LAT + LAT0;
    }

    /** Distancia real en metros entre dos coordenadas (formula de Haversine). */
    public static double haversine(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * RADIO_TIERRA * Math.asin(Math.sqrt(a));
    }

    /** Rumbo en grados (0 = norte, 90 = oriente) entre dos puntos del plano. */
    public static double rumbo(double x1, double y1, double x2, double y2) {
        double r = Math.toDegrees(Math.atan2(x2 - x1, y2 - y1));
        return r < 0 ? r + 360 : r;
    }

    /** Minusculas, sin tildes y sin espacios repetidos: "Droguería  Central" -> "drogueria central". */
    public static String normalizar(String texto) {
        if (texto == null) {
            return "";
        }
        String s = Normalizer.normalize(texto, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return s.toLowerCase().replaceAll("\\s+", " ").trim();
    }

    public static String formatoDistancia(double metros) {
        if (metros < 1000) {
            return Math.round(metros / 10.0) * 10 + " m";
        }
        return String.format("%.1f km", metros / 1000.0);
    }

    public static String formatoTiempo(double segundos) {
        long min = Math.round(segundos / 60.0);
        if (min < 1) {
            return "menos de 1 min";
        }
        if (min < 60) {
            return min + " min";
        }
        return (min / 60) + " h " + (min % 60) + " min";
    }
}
