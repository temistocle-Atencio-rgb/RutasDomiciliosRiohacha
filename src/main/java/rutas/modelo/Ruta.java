package rutas.modelo;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import rutas.estructuras.Arista;

/** Resultado de planificar una ruta de entregas. */
public class Ruta {

    public static final DateTimeFormatter FORMATO_FECHA = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    /**
     * "9:44 p.m." igual en cualquier computador. Con el patron "h:mm a" cada Windows
     * escribe distinto ("PM", "p. m." con espacios raros) y a veces no cabe en su cuadro.
     */
    public static final DateTimeFormatter FORMATO_HORA = new java.time.format.DateTimeFormatterBuilder()
            .appendPattern("h:mm ")
            .appendText(java.time.temporal.ChronoField.AMPM_OF_DAY, java.util.Map.of(0L, "a.m.", 1L, "p.m."))
            .toFormatter();

    private final Parada base;
    private final List<Parada> paradas;      // entregas en el orden en que se hacen
    private final List<LocalTime> llegadas;  // hora estimada de llegada a cada entrega
    private final List<Parada> sinCamino;    // entregas a las que no se pudo llegar
    private final List<Arista> aristas;      // todo el recorrido sobre la red vial
    private final double metros;
    private final double segundos;           // incluye el tiempo de cada entrega
    private final LocalTime salida;
    private final boolean regreso;
    private final String criterio;
    private final String metodo;             // como se escogio el orden
    private List<Indicacion> indicaciones = new ArrayList<>();
    private LocalDateTime fecha = LocalDateTime.now();
    private int inicioRegreso = -1; // posicion en aristas donde empieza el regreso a la base

    public Ruta(Parada base, List<Parada> paradas, List<LocalTime> llegadas, List<Parada> sinCamino,
                List<Arista> aristas, double metros, double segundos, LocalTime salida,
                boolean regreso, String criterio, String metodo) {
        this.base = base;
        this.paradas = paradas;
        this.llegadas = llegadas;
        this.sinCamino = sinCamino;
        this.aristas = aristas;
        this.metros = metros;
        this.segundos = segundos;
        this.salida = salida;
        this.regreso = regreso;
        this.criterio = criterio;
        this.metodo = metodo;
    }

    /** Nodos del recorrido en orden. */
    public List<Integer> nodos() {
        List<Integer> lista = new ArrayList<>();
        if (aristas.isEmpty()) {
            if (base.getNodo() >= 0) {
                lista.add(base.getNodo());
            }
            return lista;
        }
        lista.add(aristas.get(0).getOrigen());
        for (Arista a : aristas) {
            lista.add(a.getDestino());
        }
        return lista;
    }

    public LocalTime horaFin() {
        return salida.plusSeconds(Math.round(segundos));
    }

    /** Texto para mandar por WhatsApp al repartidor. */
    public String textoParaCompartir() {
        StringBuilder sb = new StringBuilder();
        sb.append("Ruta de entregas - salida ").append(salida.format(FORMATO_HORA)).append("\n");
        sb.append("Sale de: ").append(base.etiqueta()).append("\n\n");
        for (int i = 0; i < paradas.size(); i++) {
            Parada p = paradas.get(i);
            sb.append(i + 1).append(". ").append(p.etiqueta())
              .append("  (llega aprox. ").append(llegadas.get(i).format(FORMATO_HORA)).append(")\n");
        }
        if (regreso) {
            sb.append("Regreso a la base.\n");
        }
        sb.append("\nTotal: ").append(Geo.formatoDistancia(metros)).append(", ")
          .append(Geo.formatoTiempo(segundos)).append("\n");
        if (!sinCamino.isEmpty()) {
            sb.append("Sin camino: ");
            for (Parada p : sinCamino) {
                sb.append(p.etiqueta()).append("; ");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /** Enlace de Google Maps con la base y las paradas en orden, para navegar en el celular. */
    public String enlaceGoogleMaps() {
        StringBuilder sb = new StringBuilder("https://www.google.com/maps/dir/");
        sb.append(coord(base));
        for (Parada p : paradas) {
            sb.append(coord(p));
        }
        if (regreso) {
            sb.append(coord(base));
        }
        return sb.toString();
    }

    private static String coord(Parada p) {
        return String.format(java.util.Locale.US, "%.6f,%.6f/", p.getLat(), p.getLon());
    }

    public Parada getBase() { return base; }
    public List<Parada> getParadas() { return paradas; }
    public List<LocalTime> getLlegadas() { return llegadas; }
    public List<Parada> getSinCamino() { return sinCamino; }
    public List<Arista> getAristas() { return aristas; }
    public double getMetros() { return metros; }
    public double getSegundos() { return segundos; }
    public LocalTime getSalida() { return salida; }
    public boolean isRegreso() { return regreso; }
    public String getCriterio() { return criterio; }
    public String getMetodo() { return metodo; }
    public List<Indicacion> getIndicaciones() { return indicaciones; }
    public void setIndicaciones(List<Indicacion> indicaciones) { this.indicaciones = indicaciones; }
    public int getInicioRegreso() { return inicioRegreso; }
    public void setInicioRegreso(int inicioRegreso) { this.inicioRegreso = inicioRegreso; }
    public LocalDateTime getFecha() { return fecha; }
    public void setFecha(LocalDateTime fecha) { this.fecha = fecha; }
}
