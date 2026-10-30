package rutas.vista;

import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Image;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.ImageIcon;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import rutas.logica.Catalogo;
import rutas.logica.ImportadorProductos;
import rutas.logica.Permiso;
import rutas.logica.Tarifa;
import rutas.logica.Ubicaciones;
import rutas.modelo.Geo;
import rutas.modelo.Producto;
import rutas.modelo.Unidad;

/**
 * Productos de la tienda (solo administrador). El buscador sirve con el escaner:
 * el escaner escribe el codigo y oprime Enter.
 */
public class PanelProductos extends javax.swing.JPanel {

    private final Aplicacion app;
    private final Catalogo cat;
    private final boolean verCompra;

    private final JTextField campoBuscar = Tema.campo("Escanee o escriba el nombre");
    private final JCheckBox chkInactivos = Tema.casilla("Ver desactivados");
    private final DefaultListModel<Producto> modelo = new DefaultListModel<>();
    private static final String VACIA =
            "Aún no hay productos. Créelos abajo o importe la lista desde Excel (guardada como CSV).";
    private final JList<Producto> lista = new Tema.Lista<>(modelo, VACIA);
    private final Tema.Parrafo lblLista = mensaje();

    private final JLabel tituloForm = Tema.seccion("Nuevo producto");
    private final JTextField campoNombre = Tema.campo("Ej. Arroz Diana 500 g");
    private final JTextField campoCodigo = Tema.campo("Escanee el código (opcional)");
    private final JComboBox<Unidad> comboUnidad = new JComboBox<>(Unidad.values());
    private final JTextField campoVenta = Tema.campo("Ej. 2.500");
    private final JTextField campoCompra = Tema.campo("Ej. 2.000");
    private final JTextField campoStock = Tema.campo("Ej. 24 o 10,5");
    private final JTextField campoMinimo = Tema.campo("Ej. 6");
    private final JLabel miniatura = new JLabel();
    private final Tema.Parrafo lblForm = mensaje();
    private final Tema.Boton btnActivo = Tema.botonPequeno("Desactivar");

    private Producto editando; // null = producto nuevo
    private File fotoElegida;  // foto escogida que todavia no se ha copiado

    public PanelProductos(Aplicacion app) {
        this.app = app;
        this.cat = app.getCatalogo();
        this.verCompra = app.getSesion().puede(Permiso.VER_COMPRA);
        setOpaque(false);
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createEmptyBorder(16, 16, 20, 16));

        add(Tema.tituloPantalla("Productos"));
        add(Tema.parrafo("Busque por nombre o pase el producto por el escáner. Se guardan en dos árboles "
                + "AVL: uno por código de barras y otro por nombre.", 272));
        add(Tema.espacio(10));

        Tema.Tarjeta lst = new Tema.Tarjeta();
        Tema.anchoCompleto(campoBuscar);
        lst.add(campoBuscar);
        lst.add(Tema.espacio(6));
        lista.setCellRenderer(new Render());
        lista.getAccessibleContext().setAccessibleName("Lista de productos");
        lista.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && lista.getSelectedValue() != null) {
                cargar(lista.getSelectedValue());
            }
        });
        JScrollPane sc = new JScrollPane(lista);
        sc.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        sc.setBorder(Tema.bordeLista());
        sc.setAlignmentX(LEFT_ALIGNMENT);
        sc.setPreferredSize(new Dimension(300, 240));
        sc.setMaximumSize(new Dimension(Integer.MAX_VALUE, 240));
        lst.add(sc);
        lst.add(Tema.espacio(6));
        chkInactivos.addActionListener(e -> refrescar());
        lst.add(chkInactivos);
        lst.add(Tema.espacio(6));
        Tema.Boton nuevo = Tema.botonSecundario("Nuevo producto");
        Tema.Boton importar = Tema.botonSecundario("Importar lista…");
        nuevo.addActionListener(e -> limpiar(null));
        importar.addActionListener(e -> importar());
        lst.add(Tema.fila(8, nuevo, importar));
        lst.add(Tema.espacio(4));
        lst.add(lblLista);
        add(lst);
        add(Tema.espacio(12));

        Tema.Tarjeta form = new Tema.Tarjeta();
        form.add(tituloForm);
        campo(form, "Nombre", campoNombre);
        campo(form, "Código de barras", campoCodigo);
        Tema.estiloCombo(comboUnidad, PanelProductos::textoUnidad);
        campo(form, "Se vende por", comboUnidad);
        campo(form, "Precio de venta", campoVenta);
        if (verCompra) {
            campo(form, "Precio de compra (para la ganancia)", campoCompra);
        }
        campo(form, "Cantidad en la tienda", campoStock);
        campo(form, "Avisar cuando queden menos de", campoMinimo);

        Tema.Boton foto = Tema.botonPequeno("Elegir foto…");
        foto.addActionListener(e -> elegirFoto());
        miniatura.setPreferredSize(new Dimension(64, 64));
        miniatura.getAccessibleContext().setAccessibleName("Foto del producto");
        form.add(Tema.espacio(4));
        form.add(Tema.fila(10, miniatura, foto));
        form.add(Tema.espacio(6));
        form.add(lblForm);
        form.add(Tema.espacio(4));
        Tema.Boton guardar = Tema.botonPrimario("Guardar");
        guardar.addActionListener(e -> guardar());
        btnActivo.addActionListener(e -> cambiarActivo());
        form.add(Tema.fila(8, guardar, btnActivo));
        add(form);

        campoBuscar.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { refrescar(); }
            public void removeUpdate(DocumentEvent e) { refrescar(); }
            public void changedUpdate(DocumentEvent e) { refrescar(); }
        });
        campoBuscar.addActionListener(e -> enterEnBuscador());
        limpiar(null);
        refrescar();
    }

    private void campo(Tema.Tarjeta form, String etiqueta, JComponent c) {
        JLabel l = Tema.etiqueta(etiqueta);
        l.setLabelFor(c);
        c.getAccessibleContext().setAccessibleName(etiqueta);
        form.add(l);
        Tema.anchoCompleto(c);
        form.add(c);
        form.add(Tema.espacio(8));
    }

    /** Mensaje que parte las lineas si no cabe (nunca se corta). */
    private static Tema.Parrafo mensaje() {
        Tema.Parrafo l = Tema.parrafo(" ", 300);
        l.setFont(Tema.texto(13));
        return l;
    }

    private static void decir(Tema.Parrafo l, String texto, boolean bien) {
        l.setForeground(bien ? Tema.EXITO_TEXTO : Tema.ERROR_TEXTO);
        l.setText(texto);
    }

    // ---------------- textos ----------------

    static String textoUnidad(Unidad u) {
        switch (u) {
            case LIBRA:
                return "Libra (a granel)";
            case KILO:
                return "Kilo (a granel)";
            default:
                return "Unidad";
        }
    }

    /** Milesimas a texto: 1500 -> "1,5"; 3000 -> "3". */
    static String cantidad(long milesimas) {
        java.math.BigDecimal b = java.math.BigDecimal.valueOf(milesimas, 3).stripTrailingZeros();
        return (b.scale() < 0 ? b.setScale(0) : b).toPlainString().replace('.', ',');
    }

    static String precio(Producto p) {
        return Tarifa.pesos(p.getPrecioVenta()) + (p.getUnidad() == Unidad.UNIDAD ? "" : " por " + p.getUnidad().getCorto());
    }

    // ---------------- lista ----------------

    private void refrescar() {
        String t = campoBuscar.getText().trim();
        List<Producto> l;
        if (chkInactivos.isSelected()) {
            l = new java.util.ArrayList<>();
            try {
                String clave = Geo.normalizar(t);
                for (Producto p : cat.todosConInactivos()) {
                    if (p.getClaveNombre().startsWith(clave) || t.equals(p.getCodigo())) {
                        l.add(p);
                    }
                }
            } catch (SQLException e) {
                decir(lblLista, "No se pudo leer: " + e.getMessage(), false);
            }
        } else {
            l = t.isEmpty() ? cat.todos() : cat.buscar(t);
        }
        modelo.clear();
        for (Producto p : l) {
            modelo.addElement(p);
        }
        ((Tema.Lista<Producto>) lista).setTextoVacia(t.isEmpty() ? VACIA
                : "No hay productos con «" + t + "». Si es un código nuevo, oprima Enter para crearlo.");
        if (t.isEmpty()) {
            lblLista.setForeground(Tema.GRIS_TEXTO);
            lblLista.setText(cat.tamano() + " productos activos" + (cat.bajoMinimo().isEmpty() ? ""
                    : " · " + cat.bajoMinimo().size() + " bajo el mínimo"));
        }
    }

    /** Enter en el buscador (o el escaner): abre el producto o prepara uno nuevo con ese codigo. */
    private void enterEnBuscador() {
        String t = campoBuscar.getText().trim();
        // queda seleccionado: el siguiente escaneo reemplaza el texto en vez de pegarse al anterior
        campoBuscar.selectAll();
        if (t.isEmpty()) {
            return;
        }
        Producto p = cat.buscarCodigo(t);
        if (p != null) {
            lista.setSelectedValue(p, true);
            cargar(p);
        } else if (t.matches("\\d{6,}")) {
            limpiar(t);
            decir(lblForm, "Ese código no está registrado. Escriba los datos y guarde.", false);
            campoNombre.requestFocusInWindow();
        } else if (modelo.size() == 1) {
            lista.setSelectedIndex(0);
        }
    }

    // ---------------- formulario ----------------

    private void limpiar(String codigo) {
        editando = null;
        fotoElegida = null;
        lista.clearSelection();
        tituloForm.setText("NUEVO PRODUCTO");
        for (JTextField f : new JTextField[]{campoNombre, campoVenta, campoCompra, campoStock, campoMinimo}) {
            f.setText("");
        }
        campoCodigo.setText(codigo == null ? "" : codigo);
        comboUnidad.setSelectedItem(Unidad.UNIDAD);
        mostrarFoto(null);
        btnActivo.setVisible(false);
        lblForm.setText(" ");
    }

    private void cargar(Producto p) {
        editando = p;
        fotoElegida = null;
        tituloForm.setText("EDITAR PRODUCTO");
        campoNombre.setText(p.getNombre());
        campoCodigo.setText(p.getCodigo() == null ? "" : p.getCodigo());
        comboUnidad.setSelectedItem(p.getUnidad());
        campoVenta.setText(Tarifa.pesos(p.getPrecioVenta()).replace("$", ""));
        campoCompra.setText(Tarifa.pesos(p.getPrecioCompra()).replace("$", ""));
        campoStock.setText(cantidad(p.getStock()));
        campoMinimo.setText(cantidad(p.getMinimo()));
        mostrarFoto(p.getFoto() == null ? null : new File(p.getFoto()));
        btnActivo.setVisible(true);
        btnActivo.setText(p.isActivo() ? "Desactivar" : "Reactivar");
        lblForm.setText(" ");
    }

    private void guardar() {
        try {
            long venta = ImportadorProductos.leerPesos(campoVenta.getText());
            long compra = !verCompra ? (editando == null ? 0 : editando.getPrecioCompra())
                    : campoCompra.getText().isBlank() ? 0 : ImportadorProductos.leerPesos(campoCompra.getText());
            long stock = campoStock.getText().isBlank() ? 0 : ImportadorProductos.leerCantidad(campoStock.getText());
            long minimo = campoMinimo.getText().isBlank() ? 0 : ImportadorProductos.leerCantidad(campoMinimo.getText());
            Unidad u = (Unidad) comboUnidad.getSelectedItem();
            long id = editando == null ? 0 : editando.getId();
            String foto = editando == null ? null : editando.getFoto();
            Producto p = new Producto(id, campoCodigo.getText(), campoNombre.getText(), u, venta, compra, stock,
                    minimo, foto, true);
            p = editando == null ? cat.crear(p) : cat.editar(p);
            if (fotoElegida != null) {
                p = cat.editar(new Producto(p.getId(), p.getCodigo(), p.getNombre(), p.getUnidad(),
                        p.getPrecioVenta(), p.getPrecioCompra(), p.getStock(), p.getMinimo(),
                        copiarFoto(fotoElegida, p.getId()), true));
            }
            boolean eraNuevo = editando == null;
            refrescar();
            lista.setSelectedValue(p, true);
            cargar(p);
            decir(lblForm, eraNuevo ? "Producto creado" : "Cambios guardados", true);
        } catch (IllegalArgumentException e) {
            decir(lblForm, e.getMessage(), false);
        } catch (SQLException | IOException e) {
            decir(lblForm, "No se pudo guardar: " + e.getMessage(), false);
        }
    }

    private void cambiarActivo() {
        if (editando == null) {
            return;
        }
        try {
            if (editando.isActivo()) {
                cat.desactivar(editando.getId());
                decir(lblLista, "«" + editando.getNombre() + "» desactivado. Marque «Ver desactivados» para reactivarlo.", true);
                limpiar(null);
            } else {
                Producto p = cat.reactivar(editando.getId());
                cargar(p);
                decir(lblForm, "Producto reactivado", true);
            }
            refrescar();
        } catch (IllegalArgumentException e) {
            decir(lblForm, e.getMessage(), false);
        } catch (SQLException e) {
            decir(lblForm, "No se pudo guardar: " + e.getMessage(), false);
        }
    }

    // ---------------- foto ----------------

    private void elegirFoto() {
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new FileNameExtensionFilter("Imágenes (jpg, png)", "jpg", "jpeg", "png"));
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            fotoElegida = fc.getSelectedFile();
            mostrarFoto(fotoElegida);
            decir(lblForm, "La foto se guarda al oprimir Guardar", true);
        }
    }

    /** Copia la foto a la carpeta del programa, asi no se pierde si la original se borra. */
    private static String copiarFoto(File origen, long id) throws IOException {
        String nombre = origen.getName().toLowerCase();
        String ext = nombre.endsWith(".png") ? "png" : "jpg";
        File carpeta = new File(Ubicaciones.carpetaUsuario(), "fotos");
        carpeta.mkdirs();
        File destino = new File(carpeta, "producto-" + id + "." + ext);
        Files.copy(origen.toPath(), destino.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return destino.getAbsolutePath();
    }

    private void mostrarFoto(File f) {
        if (f == null || !f.isFile()) {
            miniatura.setIcon(null);
            miniatura.setText("Sin foto");
            miniatura.setFont(Tema.texto(12.5f));
            miniatura.setForeground(Tema.GRIS_TEXTO);
            return;
        }
        miniatura.setText(null);
        Image img = new ImageIcon(f.getAbsolutePath()).getImage();
        int w = img.getWidth(null), h = img.getHeight(null);
        double e = w > 0 && h > 0 ? 64.0 / Math.max(w, h) : 1;
        miniatura.setIcon(new ImageIcon(img.getScaledInstance(Math.max(1, (int) (w * e)), Math.max(1, (int) (h * e)),
                Image.SCALE_SMOOTH)));
    }

    // ---------------- importar ----------------

    /**
     * Importa en el mismo hilo de la pantalla: asi nadie busca en los arboles mientras se llenan.
     * Con listas de tienda (cientos o pocos miles de filas) toma menos de un segundo.
     */
    private void importar() {
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new FileNameExtensionFilter("Lista CSV (en Excel: Guardar como > CSV)", "csv", "txt"));
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        try {
            ImportadorProductos.Resultado r = ImportadorProductos.importar(fc.getSelectedFile(), cat);
            refrescar();
            String resumen = r.creados + " creados, " + r.actualizados + " actualizados"
                    + (r.errores.isEmpty() ? "" : ", " + r.errores.size() + " con error");
            decir(lblLista, resumen, r.errores.isEmpty());
            if (!r.errores.isEmpty()) {
                JTextArea t = new JTextArea(String.join("\n", r.errores), 12, 48);
                t.setEditable(false);
                t.setFont(Tema.texto(13));
                t.setForeground(Tema.GRIS_OSCURO);
                JOptionPane.showMessageDialog(this, new Object[]{resumen
                        + ". Las demás filas sí se guardaron. Corrija estas en el archivo y vuelva a importarlo:",
                        new JScrollPane(t)}, "Filas con error", JOptionPane.WARNING_MESSAGE);
            }
        } catch (IllegalArgumentException | IOException e) {
            decir(lblLista, e.getMessage(), false);
        } finally {
            setCursor(Cursor.getDefaultCursor());
        }
    }

    private static class Render extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean sel, boolean foco) {
            Producto p = (Producto) value;
            StringBuilder d = new StringBuilder(precio(p));
            d.append(" · Quedan ").append(cantidad(p.getStock())).append(' ').append(p.getUnidad().getCorto());
            if (!p.isActivo()) {
                d.append(" · Desactivado");
            } else if (p.estaBajoMinimo()) {
                d.append(" · Bajo el mínimo");
            }
            if (p.getCodigo() != null) {
                d.append(" · ").append(p.getCodigo());
            }
            Color titulo = !p.isActivo() ? Tema.GRIS_TEXTO : p.estaBajoMinimo() ? Tema.AVISO_TEXTO : Tema.GRIS_OSCURO;
            return Tema.celda(list, p.getNombre(), Tema.textoFuerte(14), titulo, d.toString(), sel, Color.WHITE);
        }
    }
}
