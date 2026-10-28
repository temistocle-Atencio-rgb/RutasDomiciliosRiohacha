package rutas.vista;

import java.awt.Component;
import java.awt.Dimension;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import rutas.estructuras.Grafo;
import rutas.modelo.CategoriaLugar;
import rutas.modelo.Geo;
import rutas.modelo.Lugar;
import rutas.modelo.Parada;

/** Pestana para buscar calles y lugares, y ver datos de las estructuras. */
public class PanelExplorar extends JPanel {

    private final Aplicacion app;
    private final JTextField campo = Tema.campo("Calle, carrera, barrio o negocio");
    private final JComboBox<Object> cbCategoria = new JComboBox<>();
    private final DefaultListModel<Object> modelo = new DefaultListModel<>();
    private final JList<Object> lista = new JList<>(modelo);
    private final JLabel lblConteo = new JLabel();
    private final JLabel lblCobertura = Tema.parrafo("", 262);

    public PanelExplorar(Aplicacion app) {
        this.app = app;
        setOpaque(false);
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createEmptyBorder(16, 16, 20, 16));

        add(Tema.tituloSeccion("Buscar en Riohacha"));
        add(Tema.parrafo("La búsqueda usa árboles AVL: encuentra por cualquier palabra del nombre y "
                + "sin importar tildes ni mayúsculas.", 272));
        add(Tema.espacio(10));

        Tema.Tarjeta t = new Tema.Tarjeta();
        Tema.anchoCompleto(campo);
        t.add(campo);
        t.add(Tema.espacio(6));
        cbCategoria.addItem("Todas las categorías");
        cbCategoria.addItem("Solo calles y carreras");
        for (CategoriaLugar c : CategoriaLugar.values()) {
            cbCategoria.addItem(c);
        }
        cbCategoria.setFont(Tema.texto(12.5f));
        Tema.anchoCompleto(cbCategoria);
        t.add(cbCategoria);
        t.add(Tema.espacio(6));
        lblConteo.setFont(Tema.texto(11));
        lblConteo.setForeground(Tema.GRIS_TEXTO);
        lblConteo.setAlignmentX(LEFT_ALIGNMENT);
        t.add(lblConteo);
        t.add(Tema.espacio(4));
        lista.setCellRenderer(new Render());
        lista.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                mostrarSeleccion();
            }
        });
        JScrollPane sc = new JScrollPane(lista);
        sc.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        sc.setBorder(Tema.bordeLista());
        sc.setAlignmentX(LEFT_ALIGNMENT);
        sc.setPreferredSize(new Dimension(300, 260));
        sc.setMaximumSize(new Dimension(Integer.MAX_VALUE, 260));
        t.add(sc);
        t.add(Tema.espacio(8));
        Tema.Boton entrega = Tema.botonPrimario("Agregar como entrega");
        Tema.Boton salida = Tema.botonSecundario("Usar como salida");
        entrega.addActionListener(e -> usar(false));
        salida.addActionListener(e -> usar(true));
        t.add(Tema.fila(8, entrega, salida));
        add(t);
        add(Tema.espacio(12));

        add(tarjetaDatos());

        campo.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { buscar(); }
            public void removeUpdate(DocumentEvent e) { buscar(); }
            public void changedUpdate(DocumentEvent e) { buscar(); }
        });
        cbCategoria.addActionListener(e -> buscar());
        buscar();
    }

    private JPanel tarjetaDatos() {
        Tema.Tarjeta t = new Tema.Tarjeta();
        t.add(Tema.etiqueta("Datos del mapa y estructuras"));
        Grafo g = app.getMapa().getGrafo();
        int semaforos = 0, pares = 0;
        for (int i = 0; i < g.getCantidadNodos(); i++) {
            semaforos += g.getMarca(i) == 'S' ? 1 : 0;
            pares += g.getMarca(i) == 'P' ? 1 : 0;
        }
        String[][] filas = {
            {"Grafo (lista de adyacencia)", String.format("%,d nodos · %,d aristas", g.getCantidadNodos(), g.getCantidadAristas())},
            {"Tramos de vía", String.format("%,d (%d calles con nombre)", app.getMapa().getVias().size(), app.getGeo().getCantidadCalles())},
            {"AVL de calles", app.getGeo().getCantidadCalles() + " nombres · altura " + app.getGeo().getAlturaIndiceCalles()},
            {"AVL de lugares", String.format("%,d claves · altura %d", app.getBuscador().getTamanoIndice(), app.getBuscador().getAlturaIndice())},
            {"Lugares con nombre", String.format("%,d", app.getMapa().getLugares().size())},
            {"Semáforos y pares", semaforos + " semáforos · " + pares + " pares"},
        };
        for (String[] f : filas) {
            JLabel l = new JLabel("<html><span style='color:#5B6675'>" + f[0] + ":</span> <b>" + f[1] + "</b></html>");
            l.setFont(Tema.texto(12));
            l.setForeground(Tema.GRIS_OSCURO);
            l.setAlignmentX(LEFT_ALIGNMENT);
            l.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
            t.add(l);
        }
        t.add(Tema.espacio(8));
        Tema.Boton bfs = Tema.botonPequeno("Revisar cobertura (BFS ida y vuelta)");
        Tema.Boton limpiar = Tema.botonPequeno("Limpiar");
        bfs.addActionListener(e -> revisarCobertura());
        limpiar.addActionListener(e -> {
            app.getPanelMapa().setNoAlcanzables(null);
            lblCobertura.setText("");
        });
        t.add(Tema.fila(6, bfs, limpiar));
        t.add(Tema.espacio(6));
        t.add(lblCobertura);
        return t;
    }

    private void revisarCobertura() {
        Grafo g = app.getMapa().getGrafo();
        long t0 = System.nanoTime();
        boolean[] llega = g.idaYVuelta(app.getBase().getNodo());
        long t1 = System.nanoTime();
        boolean[] no = new boolean[llega.length];
        int cuenta = 0;
        for (int i = 0; i < llega.length; i++) {
            no[i] = !llega[i];
            cuenta += llega[i] ? 1 : 0;
        }
        app.getPanelMapa().setNoAlcanzables(no);
        lblCobertura.setText(String.format("<html><div style='width:262px'>BFS desde la salida: se llega a <b>%,d de %,d</b> "
                + "nodos (%.1f %%) en %.1f ms, ida y vuelta. Los puntos rojos son zonas donde no se puede entrar "
                + "o salir respetando el sentido de las calles o las vías cerradas.</div></html>",
                cuenta, g.getCantidadNodos(), 100.0 * cuenta / g.getCantidadNodos(), (t1 - t0) / 1e6));
    }

    private void buscar() {
        modelo.clear();
        String texto = campo.getText().trim();
        Object cat = cbCategoria.getSelectedItem();
        boolean soloCalles = "Solo calles y carreras".equals(cat);
        CategoriaLugar categoria = cat instanceof CategoriaLugar ? (CategoriaLugar) cat : null;

        if (categoria == null && (soloCalles || texto.length() >= 2)) {
            for (String c : app.getGeo().buscarCalles(texto, soloCalles ? 0 : 15)) {
                modelo.addElement(c);
            }
        }
        if (!soloCalles) {
            List<Lugar> lugares = texto.length() >= 2 ? app.getBuscador().buscar(texto, 200)
                    : app.getBuscador().ordenados(categoria);
            for (Lugar l : lugares) {
                if (categoria == null || l.getCategoria() == categoria) {
                    modelo.addElement(l);
                }
            }
        }
        lblConteo.setText(modelo.size() + " resultados" + (texto.length() < 2 && categoria != null
                ? " en orden alfabético (recorrido inorden del AVL)" : ""));
    }

    private void mostrarSeleccion() {
        Object o = lista.getSelectedValue();
        PanelMapa pm = app.getPanelMapa();
        if (o instanceof Lugar) {
            Lugar l = (Lugar) o;
            pm.setViasSeleccionadas(null);
            pm.resaltar(l.getLat(), l.getLon(), l.getNombre());
        } else if (o instanceof String) {
            List<Integer> vias = app.getGeo().viasConNombre((String) o);
            if (vias != null) {
                pm.quitarResaltado();
                pm.setViasSeleccionadas(vias);
                pm.encuadrarVias(vias);
            }
        }
    }

    private void usar(boolean comoSalida) {
        Object o = lista.getSelectedValue();
        if (o == null) {
            JOptionPane.showMessageDialog(this, "Seleccione un resultado de la lista.");
            return;
        }
        Parada p;
        try {
            if (o instanceof Lugar) {
                Lugar l = (Lugar) o;
                p = new Parada(l.getNombre(), l.getNombre(), l.getLat(), l.getLon());
                p.setPrecision("Lugar del mapa (" + l.getCategoria() + ")");
                app.getGeo().asignarNodo(p, Geo.x(l.getLon()), Geo.y(l.getLat()), null);
            } else {
                p = app.getGeo().ubicar((String) o);
            }
        } catch (IllegalArgumentException e) {
            JOptionPane.showMessageDialog(this, e.getMessage());
            return;
        }
        if (comoSalida) {
            Parada b = new Parada("Base", p.getDireccion(), p.getLat(), p.getLon());
            b.setNodo(p.getNodo());
            b.setPrecision(p.getPrecision());
            app.setBase(b);
            JOptionPane.showMessageDialog(this, "Punto de salida cambiado a " + p.getDireccion() + ".");
        } else {
            app.agregarEntrega(p);
            JOptionPane.showMessageDialog(this, "Entrega agregada: " + p.etiqueta() + ".");
        }
    }

    private static class Render extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean sel, boolean foco) {
            String html;
            if (value instanceof Lugar) {
                Lugar l = (Lugar) value;
                html = "<html>" + l.getNombre().replace("<", "&lt;") + " <span style='color:#5B6675'>· "
                        + l.getCategoria() + "</span></html>";
            } else {
                html = "<html><b style='color:#003A73'>" + value + "</b> <span style='color:#5B6675'>· vía</span></html>";
            }
            JLabel l = (JLabel) super.getListCellRendererComponent(list, html, index, sel, foco);
            l.setFont(Tema.texto(12));
            l.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
            if (sel) {
                l.setBackground(Tema.AZUL_SUAVE);
                l.setForeground(Tema.GRIS_OSCURO);
            }
            return l;
        }
    }
}
