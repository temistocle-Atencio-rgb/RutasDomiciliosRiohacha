package rutas.logica;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import rutas.estructuras.ArbolAVL;
import rutas.modelo.Geo;
import rutas.modelo.Lugar;

/**
 * Buscador de lugares por nombre con un arbol AVL.
 * Cada lugar se guarda varias veces, una por cada palabra con la que empieza
 * una parte de su nombre. Asi "Droguería La Economía" se encuentra
 * escribiendo "drog", "la eco" o "economia".
 */
public class BuscadorLugares {

    private final ArbolAVL<String, Lugar> indice = new ArbolAVL<>();
    private final ArbolAVL<String, Lugar> porNombre = new ArbolAVL<>();

    public BuscadorLugares(List<Lugar> lugares) {
        for (Lugar l : lugares) {
            porNombre.insertar(Geo.normalizar(l.getNombre()) + "#" + l.getId(), l);
            String[] palabras = Geo.normalizar(l.getNombre()).split(" ");
            for (int i = 0; i < palabras.length; i++) {
                if (palabras[i].length() < 2) {
                    continue;
                }
                String sufijo = String.join(" ", java.util.Arrays.copyOfRange(palabras, i, palabras.length));
                // el id al final evita que dos lugares con el mismo nombre se pisen
                indice.insertar(sufijo + "#" + l.getId(), l);
            }
        }
    }

    public List<Lugar> buscar(String texto, int limite) {
        String t = Geo.normalizar(texto);
        if (t.length() < 2) {
            return new ArrayList<>();
        }
        Set<Lugar> sinRepetir = new LinkedHashSet<>(indice.buscarPorPrefijo(t, limite * 3));
        List<Lugar> lista = new ArrayList<>(sinRepetir);
        // primero los que empiezan exactamente con el texto
        lista.sort((a, b) -> Boolean.compare(!Geo.normalizar(a.getNombre()).startsWith(t),
                !Geo.normalizar(b.getNombre()).startsWith(t)));
        return lista.size() > limite ? lista.subList(0, limite) : lista;
    }

    /** Lugares de una categoria en orden alfabetico (recorrido inorden del AVL). null = todas. */
    public List<Lugar> ordenados(rutas.modelo.CategoriaLugar categoria) {
        List<Lugar> lista = new ArrayList<>();
        for (Lugar l : porNombre.enOrden()) {
            if (categoria == null || l.getCategoria() == categoria) {
                lista.add(l);
            }
        }
        return lista;
    }

    public int getTamanoIndice() {
        return indice.getTamano();
    }

    public int getAlturaIndice() {
        return indice.getAltura();
    }
}
