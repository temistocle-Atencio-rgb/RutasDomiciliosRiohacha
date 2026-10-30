package rutas.logica;

import java.io.File;
import java.io.IOException;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import rutas.estructuras.Arista;
import rutas.estructuras.Grafo;
import rutas.modelo.Geo;
import rutas.modelo.Incidente;
import rutas.modelo.Via;

/**
 * Calcula cuantos segundos tarda una moto en recorrer una arista.
 * Tiene en cuenta:
 *  - el tipo de via (troncal, secundaria, calle de barrio...) y su limite de velocidad,
 *  - si la via es destapada,
 *  - semaforos, pares y reductores de velocidad al llegar al nodo,
 *  - la hora pico (mas fuerte en vias principales y en el centro),
 *  - las novedades reportadas (trancon, huecos, obra, via cerrada),
 *  - la lluvia: en Riohacha las calles destapadas se vuelven barro y casi no se puede pasar.
 * Los numeros se leen de datos/trafico.txt para poder calibrarlos.
 */
public class ModeloTrafico {

    private final Map<String, String> p = new HashMap<>();
    private final List<LocalTime[]> franjasPico = new ArrayList<>();
    private final Mapa mapa;
    private double centroX, centroY, centroRadio;
    private boolean lluvia;

    public ModeloTrafico(Mapa mapa, File archivo) throws IOException {
        this.mapa = mapa;
        if (archivo != null && archivo.exists()) {
            for (String l : CargadorMapa.leer(archivo)) {
                int i = l.indexOf('=');
                if (i > 0) {
                    p.put(l.substring(0, i).trim(), l.substring(i + 1).trim());
                }
            }
        }
        for (int k = 1; p.containsKey("pico." + k); k++) {
            String[] h = p.get("pico." + k).split("-");
            franjasPico.add(new LocalTime[]{LocalTime.parse(h[0].trim()), LocalTime.parse(h[1].trim())});
        }
        centroX = Geo.x(num("centro.lon", -72.9096513));
        centroY = Geo.y(num("centro.lat", 11.5513888));
        centroRadio = num("centro.radio", 900);
    }

    private double num(String clave, double porDefecto) {
        try {
            return p.containsKey(clave) ? Double.parseDouble(p.get(clave)) : porDefecto;
        } catch (NumberFormatException e) {
            return porDefecto;
        }
    }

    /** Centro de la ciudad (zona con mas trancones en hora pico). */
    public void setCentro(double lat, double lon, double radioMetros) {
        centroX = Geo.x(lon);
        centroY = Geo.y(lat);
        centroRadio = radioMetros;
    }

    public void setLluvia(boolean lluvia) {
        this.lluvia = lluvia;
    }

    public boolean isLluvia() {
        return lluvia;
    }

    public boolean esHoraPico(LocalTime hora) {
        for (LocalTime[] f : franjasPico) {
            if (!hora.isBefore(f[0]) && hora.isBefore(f[1])) {
                return true;
            }
        }
        return false;
    }

    public String descripcionFranja(LocalTime hora) {
        return esHoraPico(hora) ? "Hora pico" : "Tráfico normal";
    }

    /** Velocidad en km/h para un tramo a cierta hora. 0 si esta cerrado. */
    public double velocidad(Via v, double xMedio, double yMedio, LocalTime hora) {
        String tipo = v.getTipo().replace("_link", "");
        double vel = num("velocidad." + tipo, 22);
        if (v.getVelocidadMax() > 0) {
            vel = Math.min(vel, v.getVelocidadMax());
        }
        if (v.esDestapada()) {
            vel *= num("factor.destapada", 0.55);
            if (lluvia) {
                vel *= num("factor.lluvia.destapada", 0.25);
            }
        }
        if (lluvia) {
            vel *= num("factor.lluvia", 0.85);
        }
        if (esHoraPico(hora)) {
            vel *= v.esPrincipal() ? num("factor.pico.principal", 0.55) : num("factor.pico.barrio", 0.85);
            if (Math.hypot(xMedio - centroX, yMedio - centroY) < centroRadio) {
                vel *= num("factor.pico.centro", 0.75);
            }
        }
        Incidente inc = mapa.getIncidente(v.getIndice());
        if (inc != null) {
            vel *= inc.getTipo().getFactor();
        }
        return vel;
    }

    /** Segundos de espera al llegar a un nodo segun lo que haya en la esquina. */
    public double espera(char marca, LocalTime hora) {
        switch (marca) {
            case 'S': return esHoraPico(hora) ? num("espera.semaforo.pico", 50) : num("espera.semaforo", 25);
            case 'P': return num("espera.pare", 6);
            case 'R': return num("espera.reductor", 4);
            default: return 0;
        }
    }

    /** Tiempo para recorrer la arista. Infinito si la via esta cerrada. */
    public double segundos(Arista a, LocalTime hora) {
        Grafo g = mapa.getGrafo();
        Via v = mapa.getVia(a.getVia());
        double xm = (g.getX(a.getOrigen()) + g.getX(a.getDestino())) / 2;
        double ym = (g.getY(a.getOrigen()) + g.getY(a.getDestino())) / 2;
        double vel = velocidad(v, xm, ym, hora);
        if (vel <= 0) {
            return Double.POSITIVE_INFINITY;
        }
        return a.getMetros() / (vel / 3.6) + espera(g.getMarca(a.getDestino()), hora);
    }

    /**
     * Peso de una arista cuando se busca la ruta mas corta. Son los metros, pero con lluvia
     * una calle destapada cuenta como si fuera varias veces mas larga para que se evite.
     */
    public double pesoDistancia(Arista a) {
        if (lluvia && mapa.getVia(a.getVia()).esDestapada()) {
            return a.getMetros() * num("lluvia.castigo.metros", 4);
        }
        return a.getMetros();
    }

    public boolean estaCerrada(Arista a) {
        Incidente inc = mapa.getIncidente(a.getVia());
        return inc != null && inc.getTipo().getFactor() <= 0;
    }

    /** Franjas de hora pico como texto: "06:30-08:30, 11:30-14:00..." */
    public String textoFranjasPico() {
        StringBuilder sb = new StringBuilder();
        for (LocalTime[] f : franjasPico) {
            sb.append(sb.length() > 0 ? ",  " : "").append(f[0]).append(" a ").append(f[1]);
        }
        return sb.toString();
    }

    public double esperaSemaforo(boolean pico) {
        return pico ? num("espera.semaforo.pico", 50) : num("espera.semaforo", 25);
    }

    public double tiempoEntrega() {
        return num("tiempo.entrega", 180);
    }
}
