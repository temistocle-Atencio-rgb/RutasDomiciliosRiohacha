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
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import rutas.modelo.Cliente;
import rutas.modelo.Geo;
import rutas.modelo.Parada;

/** Registro de clientes frecuentes (guardados en un AVL ordenado por nombre). */
public class PanelClientes extends JPanel {

    private final Aplicacion app;
    private final JTextField campoBuscar = Tema.campo("Buscar cliente por nombre");
    private final DefaultListModel<Cliente> modelo = new DefaultListModel<>();
    private final JList<Cliente> lista = new JList<>(modelo);
    private final JTextField campoNombre = Tema.campo("Nombre");
    private final JTextField campoTelefono = Tema.campo("Teléfono");
    private final JTextField campoDireccion = Tema.campo("Dirección: Calle 15 # 10-20");
    private final JTextField campoNota = Tema.campo("Nota: casa esquinera, portón verde...");

    public PanelClientes(Aplicacion app) {
        this.app = app;
        setOpaque(false);
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createEmptyBorder(16, 16, 20, 16));

        add(Tema.tituloSeccion("Clientes frecuentes"));
        add(Tema.parrafo("Guarde una vez la dirección del cliente y después agréguelo a la ruta "
                + "escribiendo su nombre. Se guardan ordenados en un árbol AVL.", 272));
        add(Tema.espacio(10));

        Tema.Tarjeta lst = new Tema.Tarjeta();
        Tema.anchoCompleto(campoBuscar);
        lst.add(campoBuscar);
        lst.add(Tema.espacio(6));
        lista.setCellRenderer(new Render());
        lista.addListSelectionListener(e -> {
            Cliente c = lista.getSelectedValue();
            if (c != null && !e.getValueIsAdjusting()) {
                campoNombre.setText(c.getNombre());
                campoTelefono.setText(c.getTelefono());
                campoDireccion.setText(c.getDireccion());
                campoNota.setText(c.getNota());
                app.getPanelMapa().resaltar(c.getLat(), c.getLon(), c.getNombre());
            }
        });
        JScrollPane sc = new JScrollPane(lista);
        sc.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        sc.setBorder(Tema.bordeLista());
        sc.setAlignmentX(LEFT_ALIGNMENT);
        sc.setPreferredSize(new Dimension(300, 200));
        sc.setMaximumSize(new Dimension(Integer.MAX_VALUE, 200));
        lst.add(sc);
        lst.add(Tema.espacio(8));
        Tema.Boton aRuta = Tema.botonPrimario("Agregar a la ruta");
        Tema.Boton eliminar = Tema.botonPequeno("Eliminar");
        aRuta.addActionListener(e -> agregarARuta());
        eliminar.addActionListener(e -> eliminar());
        lst.add(Tema.fila(8, aRuta, eliminar));
        add(lst);
        add(Tema.espacio(12));

        Tema.Tarjeta form = new Tema.Tarjeta();
        form.add(Tema.etiqueta("Registrar o actualizar cliente"));
        for (JTextField f : new JTextField[]{campoNombre, campoTelefono, campoDireccion, campoNota}) {
            Tema.anchoCompleto(f);
            form.add(f);
            form.add(Tema.espacio(6));
        }
        Tema.Boton guardar = Tema.botonPrimario("Guardar cliente");
        Tema.Boton nuevo = Tema.botonSecundario("Limpiar");
        guardar.addActionListener(e -> guardar());
        nuevo.addActionListener(e -> {
            for (JTextField f : new JTextField[]{campoNombre, campoTelefono, campoDireccion, campoNota}) {
                f.setText("");
            }
            lista.clearSelection();
        });
        form.add(Tema.fila(8, guardar, nuevo));
        add(form);

        campoBuscar.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { refrescar(); }
            public void removeUpdate(DocumentEvent e) { refrescar(); }
            public void changedUpdate(DocumentEvent e) { refrescar(); }
        });
        app.alCambiar(this::refrescar);
        refrescar();
    }

    private void refrescar() {
        String t = Geo.normalizar(campoBuscar.getText());
        List<Cliente> l = t.isEmpty() ? app.getClientes().enOrden() : app.getClientes().buscarPorPrefijo(t);
        modelo.clear();
        for (Cliente c : l) {
            modelo.addElement(c);
        }
    }

    private void guardar() {
        String nombre = campoNombre.getText().trim();
        String dir = campoDireccion.getText().trim();
        if (nombre.isEmpty() || dir.isEmpty()) {
            JOptionPane.showMessageDialog(this, "El nombre y la dirección son obligatorios.");
            return;
        }
        try {
            Parada p = app.getGeo().ubicar(dir);
            Cliente c = new Cliente(nombre, campoTelefono.getText().trim(), dir, campoNota.getText().trim(),
                    p.getLat(), p.getLon());
            app.getClientes().insertar(Geo.normalizar(nombre), c); // si ya existe se actualiza
            app.guardarClientes();
            app.getPanelMapa().resaltar(c.getLat(), c.getLon(), c.getNombre());
            JOptionPane.showMessageDialog(this, "Cliente guardado.\nUbicación: " + p.getPrecision()
                    + "\nRevise en el mapa que el punto quedó bien.");
        } catch (IllegalArgumentException e) {
            JOptionPane.showMessageDialog(this, e.getMessage());
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "No se pudo guardar: " + e.getMessage());
        }
    }

    private void eliminar() {
        Cliente c = lista.getSelectedValue();
        if (c == null) {
            return;
        }
        if (JOptionPane.showConfirmDialog(this, "¿Eliminar a " + c.getNombre() + "?", "Eliminar cliente",
                JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
            app.getClientes().eliminar(Geo.normalizar(c.getNombre()));
            try {
                app.guardarClientes();
            } catch (IOException e) {
                JOptionPane.showMessageDialog(this, "No se pudo guardar: " + e.getMessage());
            }
        }
    }

    private void agregarARuta() {
        Cliente c = lista.getSelectedValue();
        if (c == null) {
            JOptionPane.showMessageDialog(this, "Seleccione un cliente.");
            return;
        }
        Parada p = new Parada(c.getNombre(), c.getDireccion(), c.getLat(), c.getLon());
        p.setPrecision("Cliente guardado" + (c.getTelefono().isEmpty() ? "" : " · Tel. " + c.getTelefono()));
        app.getGeo().asignarNodo(p, Geo.x(c.getLon()), Geo.y(c.getLat()), null);
        app.agregarEntrega(p);
        JOptionPane.showMessageDialog(this, c.getNombre() + " quedó en la lista de entregas.");
    }

    private static class Render extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean sel, boolean foco) {
            Cliente c = (Cliente) value;
            String html = "<html><b>" + c.getNombre().replace("<", "&lt;") + "</b><br><span style='color:#5B6675'>"
                    + c.getDireccion().replace("<", "&lt;") + (c.getTelefono().isEmpty() ? "" : " · " + c.getTelefono())
                    + "</span></html>";
            JLabel l = (JLabel) super.getListCellRendererComponent(list, html, index, sel, foco);
            l.setFont(Tema.texto(12));
            l.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
            if (sel) {
                l.setBackground(Tema.AZUL_SUAVE);
                l.setForeground(Tema.GRIS_OSCURO);
            }
            return l;
        }
    }
}
