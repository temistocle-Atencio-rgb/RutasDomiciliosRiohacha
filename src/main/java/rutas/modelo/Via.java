package rutas.modelo;

/**
 * Un tramo de via tomado de OpenStreetMap (una calle, carrera, avenida, etc.).
 * Un tramo tiene varios nodos; entre cada par de nodos seguidos hay una arista del grafo.
 */
public class Via {

    private final int indice;
    private String nombre;
    private boolean nombreDeducido; // el nombre se dedujo de los tramos vecinos
    private final String tipo;       // clasificacion de OSM: trunk, secondary, residential...
    private final int sentido;       // 0 doble via, 1 una via, -1 una via al reves
    private final String superficie; // asphalt, paved, unpaved...
    private final int velocidadMax;  // km/h, 0 si no se sabe
    private final int[] nodos;
    private String nombreAlterno = ""; // por ejemplo la Calle 7 tambien se llama "Calle Ancha"

    public Via(int indice, String nombre, String tipo, int sentido, String superficie,
               int velocidadMax, int[] nodos) {
        this.indice = indice;
        this.nombre = nombre;
        this.tipo = tipo;
        this.sentido = sentido;
        this.superficie = superficie;
        this.velocidadMax = velocidadMax;
        this.nodos = nodos;
    }

    /** Tramos importantes: troncales, secundarias y terciarias (se congestionan mas). */
    public boolean esPrincipal() {
        return tipo.startsWith("trunk") || tipo.startsWith("primary")
                || tipo.startsWith("secondary") || tipo.startsWith("tertiary");
    }

    /** Vias sin pavimentar: tierra, destapada, trocha. */
    public boolean esDestapada() {
        switch (superficie) {
            case "unpaved": case "ground": case "dirt": case "earth": case "gravel": case "sand": case "compacted":
                return true;
            default:
                return tipo.equals("track");
        }
    }

    public boolean tieneNombre() {
        return !nombre.isEmpty();
    }

    /** Nombre para mostrar. Si OSM no tiene el nombre se describe el tipo de via. */
    public String nombreVisible() {
        if (!nombre.isEmpty()) {
            return nombre;
        }
        if (esPrincipal()) {
            return "vía principal";
        }
        if (tipo.equals("service")) {
            return "vía de servicio";
        }
        if (esDestapada()) {
            return "vía destapada";
        }
        return "calle sin nombre";
    }

    public String tipoLegible() {
        switch (tipo.replace("_link", "")) {
            case "trunk": return "Troncal";
            case "primary": return "Vía primaria";
            case "secondary": return "Vía secundaria";
            case "tertiary": return "Vía terciaria";
            case "residential": return "Calle de barrio";
            case "living_street": return "Calle peatonal / residencial";
            case "service": return "Vía de servicio";
            case "track": return "Trocha";
            default: return "Calle";
        }
    }

    /** Pone un nombre deducido de los tramos vecinos (ver InferenciaNombres). */
    public void setNombreDeducido(String nombre) {
        this.nombre = nombre;
        this.nombreDeducido = true;
    }

    public boolean isNombreDeducido() { return nombreDeducido; }
    public String getNombreAlterno() { return nombreAlterno; }
    public void setNombreAlterno(String nombreAlterno) { this.nombreAlterno = nombreAlterno; }
    public int getIndice() { return indice; }
    public String getNombre() { return nombre; }
    public String getTipo() { return tipo; }
    public int getSentido() { return sentido; }
    public String getSuperficie() { return superficie; }
    public int getVelocidadMax() { return velocidadMax; }
    public int[] getNodos() { return nodos; }
}
