package rutas.logica;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import rutas.estructuras.Grafo;
import rutas.modelo.Incidente;
import rutas.modelo.Lugar;
import rutas.modelo.Via;

/** Todo lo que se carga de la ciudad: el grafo, las vias, los lugares y la costa. */
public class Mapa {

    private final Grafo grafo;
    private final List<Via> vias;
    private final List<Lugar> lugares;
    private final List<double[][]> costa; // cada linea: arreglo de {lat, lon}
    private final Map<Integer, Incidente> incidentes = new HashMap<>();

    public Mapa(Grafo grafo, List<Via> vias, List<Lugar> lugares, List<double[][]> costa) {
        this.grafo = grafo;
        this.vias = vias;
        this.lugares = lugares;
        this.costa = costa;
    }

    public Grafo getGrafo() { return grafo; }
    public List<Via> getVias() { return vias; }
    public Via getVia(int i) { return vias.get(i); }
    public List<Lugar> getLugares() { return lugares; }
    public List<double[][]> getCosta() { return costa; }

    public Incidente getIncidente(int via) {
        return incidentes.get(via);
    }

    public Map<Integer, Incidente> getIncidentes() {
        return incidentes;
    }

    public List<Incidente> listaIncidentes() {
        return new ArrayList<>(incidentes.values());
    }
}
