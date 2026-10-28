package rutas.vista;

import java.awt.Component;
import java.awt.Dimension;
import java.io.IOException;
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
import rutas.logica.ModeloTrafico;
import rutas.modelo.Incidente;
import rutas.modelo.Ruta;

/** Novedades en las vias (trancones, huecos, obras, cierres) y datos del modelo de trafico. */
public class PanelNovedades extends JPanel {

    private final Aplicacion app;
    private final DefaultListModel<Incidente> modelo = new DefaultListModel<>();
    private final JList<Incidente> lista = new JList<>(modelo);

    public PanelNovedades(Aplicacion app) {
        this.app = app;
        setOpaque(false);
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createEmptyBorder(16, 16, 20, 16));

        add(Tema.tituloSeccion("Novedades en las vías"));
        add(Tema.parrafo("Reporte trancones, huecos, obras o calles cerradas. La ruta más rápida los evita: "
                + "un trancón baja la velocidad del tramo y una vía cerrada se saca del grafo.", 272));
        add(Tema.espacio(10));

        Tema.Tarjeta t = new Tema.Tarjeta();
        Tema.Boton reportar = Tema.botonAccion("Reportar en el mapa");
        Tema.anchoCompleto(reportar);
        reportar.addActionListener(e -> app.getPanelMapa().setModo(PanelMapa.Modo.REPORTAR_NOVEDAD));
        t.add(reportar);
        t.add(Tema.espacio(8));
        t.add(Tema.etiqueta("Novedades activas"));
        lista.setCellRenderer(new Render(app));
        lista.addListSelectionListener(e -> {
            Incidente i = lista.getSelectedValue();
            if (i != null && !e.getValueIsAdjusting()) {
                app.getPanelMapa().setViasSeleccionadas(List.of(i.getVia()));
                app.getPanelMapa().encuadrarVias(List.of(i.getVia()));
            }
        });
        JScrollPane sc = new JScrollPane(lista);
        sc.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        sc.setBorder(Tema.bordeLista());
        sc.setAlignmentX(LEFT_ALIGNMENT);
        sc.setPreferredSize(new Dimension(300, 170));
        sc.setMaximumSize(new Dimension(Integer.MAX_VALUE, 170));
        t.add(sc);
        t.add(Tema.espacio(8));
        Tema.Boton quitar = Tema.botonPequeno("Quitar la seleccionada");
        Tema.Boton todas = Tema.botonPequeno("Quitar todas");
        quitar.addActionListener(e -> {
            Incidente i = lista.getSelectedValue();
            if (i != null) {
                app.getMapa().getIncidentes().remove(i.getVia());
                guardar();
            }
        });
        todas.addActionListener(e -> {
            app.getMapa().getIncidentes().clear();
            guardar();
        });
        t.add(Tema.fila(6, quitar, todas));
        add(t);
        add(Tema.espacio(12));
        add(tarjetaModelo());

        app.alCambiar(this::refrescar);
        refrescar();
    }

    private JPanel tarjetaModelo() {
        ModeloTrafico tr = app.getTrafico();
        Tema.Tarjeta t = new Tema.Tarjeta();
        t.add(Tema.etiqueta("Cómo se calcula el tiempo"));
        String[] filas = {
            "Velocidad según el tipo de vía (troncal, secundaria, calle de barrio) y su límite.",
            "Vías destapadas: más lentas.",
            String.format("Semáforos: %.0f s de espera (%.0f s en hora pico). Pares y reductores también suman.",
                    tr.esperaSemaforo(false), tr.esperaSemaforo(true)),
            "Hora pico: " + tr.textoFranjasPico() + ". Más lento en vías principales y en el centro.",
            "Novedades reportadas: trancón, huecos u obra bajan la velocidad; vía cerrada no se usa.",
            String.format("%.0f min por cada entrega.", tr.tiempoEntrega() / 60),
        };
        for (String f : filas) {
            Tema.Parrafo l = Tema.parrafo("• " + f, 262);
            l.setForeground(Tema.GRIS_OSCURO);
            l.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
            t.add(l);
        }
        t.add(Tema.espacio(6));
        t.add(Tema.parrafo("Los valores están en datos/trafico.txt para ajustarlos con recorridos reales.", 262));
        return t;
    }

    /** Lo llama la ventana cuando se hace clic sobre una via en modo reportar. */
    public void reportar(int via) {
        String nombre = app.getMapa().getVia(via).nombreVisible();
        Incidente actual = app.getMapa().getIncidente(via);
        Object[] opciones = rutas.modelo.TipoIncidente.values();
        Object tipo = JOptionPane.showInputDialog(this, "Novedad en " + nombre + ":", "Reportar novedad",
                JOptionPane.QUESTION_MESSAGE, null, opciones, actual == null ? opciones[0] : actual.getTipo());
        if (tipo == null) {
            return;
        }
        String nota = JOptionPane.showInputDialog(this, "Nota (opcional):", actual == null ? "" : actual.getNota());
        app.getMapa().getIncidentes().put(via, new Incidente(via, (rutas.modelo.TipoIncidente) tipo,
                nota == null ? "" : nota.trim(), java.time.LocalDateTime.now()));
        guardar();
    }

    private void guardar() {
        try {
            app.guardarNovedades();
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "No se pudo guardar: " + e.getMessage());
        }
        app.getPanelMapa().repaint();
    }

    private void refrescar() {
        modelo.clear();
        for (Incidente i : app.getMapa().listaIncidentes()) {
            modelo.addElement(i);
        }
    }

    private static class Render extends DefaultListCellRenderer {
        private final Aplicacion app;

        Render(Aplicacion app) {
            this.app = app;
        }

        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean sel, boolean foco) {
            Incidente i = (Incidente) value;
            return Tema.celda(list, i.getTipo() + " · " + app.getMapa().getVia(i.getVia()).nombreVisible(),
                    Tema.textoFuerte(12), Tema.ERROR,
                    i.getFecha().format(Ruta.FORMATO_FECHA) + (i.getNota().isEmpty() ? "" : " · " + i.getNota()),
                    sel, java.awt.Color.WHITE);
        }
    }
}
