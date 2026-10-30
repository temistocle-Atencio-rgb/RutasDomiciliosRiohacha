package rutas.vista;

import java.awt.Component;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import rutas.modelo.Geo;
import rutas.modelo.Parada;
import rutas.modelo.Ruta;

/** Rutas guardadas: se pueden ver en el mapa o repetir con las mismas entregas. */
public class PanelHistorial extends JPanel {

    private final Aplicacion app;
    private final DefaultListModel<Ruta> modelo = new DefaultListModel<>();
    private final JList<Ruta> lista = new Tema.Lista<>(modelo,
            "Aún no hay rutas guardadas. Cada ruta que mande al repartidor por WhatsApp o Google Maps queda aquí.");
    private final Tema.Parrafo detalle = Tema.parrafo("", 262);
    private final Tema.Parrafo resumen = Tema.parrafo("", 262);

    public PanelHistorial(Aplicacion app) {
        this.app = app;
        setOpaque(false);
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createEmptyBorder(16, 16, 20, 16));

        add(Tema.tituloPantalla("Historial de rutas"));
        add(Tema.parrafo("Las rutas, clientes y novedades se guardan en una base de datos SQLite "
                + "(domiruta.db) en la carpeta del usuario:", 272));
        Tema.Parrafo ruta = Tema.parrafo(app.getCarpetaUsuario().getAbsolutePath(), 272);
        ruta.setForeground(Tema.AZUL_PROFUNDO);
        add(ruta);
        Tema.Boton abrir = Tema.botonPequeno("Abrir carpeta");
        abrir.addActionListener(e -> {
            try {
                java.awt.Desktop.getDesktop().open(app.getCarpetaUsuario());
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, app.getCarpetaUsuario().getAbsolutePath());
            }
        });
        add(Tema.espacio(4));
        add(abrir);
        add(Tema.espacio(10));

        Tema.Tarjeta t = new Tema.Tarjeta();
        t.add(resumen);
        t.add(Tema.espacio(6));
        lista.setCellRenderer(new Render());
        lista.addListSelectionListener(e -> {
            Ruta r = lista.getSelectedValue();
            if (r != null && !e.getValueIsAdjusting()) {
                app.mostrarRuta(r);
                StringBuilder sb = new StringBuilder("Salida: " + r.getBase().getDireccion() + "\n");
                for (int i = 0; i < r.getParadas().size(); i++) {
                    sb.append(i + 1).append(". ").append(r.getParadas().get(i).etiqueta()).append("\n");
                }
                sb.append(r.getMetodo()).append(" · ").append(r.getCriterio());
                detalle.setText(sb.toString());
                detalle.setForeground(Tema.GRIS_OSCURO);
                revalidate();
            }
        });
        JScrollPane sc = new JScrollPane(lista);
        sc.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        sc.setBorder(Tema.bordeLista());
        sc.setAlignmentX(LEFT_ALIGNMENT);
        sc.setPreferredSize(new Dimension(300, 240));
        sc.setMaximumSize(new Dimension(Integer.MAX_VALUE, 240));
        t.add(sc);
        t.add(Tema.espacio(8));
        t.add(detalle);
        t.add(Tema.espacio(8));
        Tema.Boton repetir = Tema.botonPrimario("Repetir con estas entregas");
        repetir.addActionListener(e -> repetir());
        repetir.setEnabled(false);
        repetir.setToolTipText("Seleccione una ruta de la lista");
        lista.addListSelectionListener(e -> repetir.setEnabled(lista.getSelectedIndex() >= 0));
        t.add(repetir);
        add(t);

        app.alCambiar(this::refrescar);
        refrescar();
    }

    private void refrescar() {
        if (modelo.size() == app.getHistorial().size()) {
            return;
        }
        modelo.clear();
        double km = 0;
        int entregas = 0;
        for (int i = app.getHistorial().size() - 1; i >= 0; i--) { // la mas reciente primero
            Ruta r = app.getHistorial().get(i);
            modelo.addElement(r);
            km += r.getMetros() / 1000;
            entregas += r.getParadas().size();
        }
        resumen.setText(String.format("%d rutas guardadas · %d entregas · %.1f km recorridos",
                modelo.size(), entregas, km));
        resumen.setForeground(Tema.AZUL_PROFUNDO);
    }

    private void repetir() {
        Ruta r = lista.getSelectedValue();
        if (r == null) {
            JOptionPane.showMessageDialog(this, "Seleccione una ruta.");
            return;
        }
        List<Parada> copia = new ArrayList<>();
        for (Parada p : r.getParadas()) {
            Parada n = new Parada(p.getNombre(), p.getDireccion(), p.getLat(), p.getLon());
            n.setPrecision("Del historial");
            app.getGeo().asignarNodo(n, Geo.x(p.getLon()), Geo.y(p.getLat()), null);
            copia.add(n);
        }
        app.cargarEntregas(null, copia);
        JOptionPane.showMessageDialog(this, "Se cargaron " + copia.size()
                + " entregas en la pestaña Ruta. Calcule de nuevo para usar el tráfico de esta hora.");
    }

    private static class Render extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean sel, boolean foco) {
            Ruta r = (Ruta) value;
            return Tema.celda(list, r.getFecha().format(Ruta.FORMATO_FECHA), Tema.textoFuerte(14), Tema.GRIS_OSCURO,
                    r.getParadas().size() + " entregas · " + Geo.formatoDistancia(r.getMetros()) + " · "
                    + Geo.formatoTiempo(r.getSegundos()), sel, java.awt.Color.WHITE);
        }
    }
}
