package rutas.vista;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.HierarchyEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultCellEditor;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableColumnModel;
import rutas.logica.BaseTienda;
import rutas.logica.Carrito;
import rutas.logica.Catalogo;
import rutas.logica.ImportadorProductos;
import rutas.logica.Tarifa;
import rutas.logica.Ubicaciones;
import rutas.modelo.Categoria;
import rutas.modelo.LineaVenta;
import rutas.modelo.Producto;
import rutas.modelo.Unidad;

/**
 * Pantalla de Vender, a todo el ancho: productos con foto a la izquierda (con
 * categorias y buscador que sirve con el escaner) y la venta a la derecha con el
 * total grande. Abajo, las ventas en espera.
 */
public class PanelVender extends JPanel {

    /** Para que la pantalla no se ponga lenta con miles de productos. */
    private static final int MAX_TARJETAS = 120;

    private final Aplicacion app;
    private final BaseTienda bd;
    private final Catalogo cat;

    private Carrito carrito = new Carrito();
    private Long idEspera;
    private String nombreEspera;

    private final JTextField buscador = Tema.campo("Escanee el código o escriba el nombre   (F2)");
    private final JPanel filaCategorias = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
    private Long categoria; // null = todas
    private final Rejilla rejilla = new Rejilla();
    private final JLabel lblResultados = new JLabel(" ");
    private final JPanel barraEspera = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));

    private final JLabel lblVenta = new JLabel();
    private final JLabel lblSubVenta = new JLabel();
    private final ModeloVenta modelo = new ModeloVenta();
    private final JTable tabla = new JTable(modelo);
    private final JLabel lblCuantos = new JLabel();
    private final JLabel lblTotal = new JLabel();
    private final Tema.Boton btnPagar = Tema.botonAccion("Pagar   (F12)");
    private final Tema.Boton btnEspera = Tema.botonSecundario("En espera  (F8)");
    private final Tema.Boton btnCancelar = Tema.botonPequeno("Cancelar venta");

    public PanelVender(Aplicacion app) {
        super(new BorderLayout());
        this.app = app;
        this.bd = app.getTienda();
        this.cat = app.getCatalogo();
        putClientProperty(Capa.FOCO_AL_CERRAR, buscador); // al cerrar cobro, espera o alertas, vuelve al escaner
        buscador.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusGained(java.awt.event.FocusEvent e) {
                // al entrar al buscador queda todo seleccionado: lo que se escanee reemplaza lo que habia
                javax.swing.SwingUtilities.invokeLater(buscador::selectAll);
            }
        });
        setBackground(Tema.GRIS_CLARO);
        add(ladoProductos(), BorderLayout.CENTER);
        add(ladoVenta(), BorderLayout.EAST);
        atajos();
        // cada vez que se muestra se refresca (por si cambiaron productos en otra pantalla)
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) {
                refrescarTodo();
                enfocar();
            }
        });
        new Timer(30_000, e -> refrescarEspera()).start(); // los minutos de las ventas en espera
        refrescarTodo();
    }

    /** Pone el cursor en el buscador (para el escaner). */
    public void enfocar() {
        javax.swing.SwingUtilities.invokeLater(() -> {
            if (!Capa.hayAbierta(this)) { // con una capa abierta, el foco es de ella (Enter en "Nueva venta")
                buscador.requestFocusInWindow();
            }
        });
    }

    // ------------------------------------------------------------------
    //  Lado izquierdo: productos
    // ------------------------------------------------------------------

    private JComponent ladoProductos() {
        JPanel izq = new JPanel(new BorderLayout());
        izq.setOpaque(false);
        izq.setBorder(BorderFactory.createEmptyBorder(20, 24, 12, 20));

        JPanel arriba = new JPanel();
        arriba.setOpaque(false);
        arriba.setLayout(new BoxLayout(arriba, BoxLayout.Y_AXIS));
        buscador.setFont(Tema.texto(16));
        buscador.setBorder(BorderFactory.createCompoundBorder(new Tema.BordeRedondo(Tema.BORDE_CONTROL, Tema.RADIO),
                BorderFactory.createEmptyBorder(11, 14, 11, 14)));
        Tema.anchoCompleto(buscador);
        arriba.add(buscador);
        arriba.add(Tema.espacio(14));
        filaCategorias.setOpaque(false);
        JScrollPane scCat = Tema.scrollSinBorde(filaCategorias);
        scCat.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_NEVER);
        scCat.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scCat.getViewport().setOpaque(false);
        scCat.setOpaque(false);
        scCat.setAlignmentX(LEFT_ALIGNMENT);
        scCat.setPreferredSize(new Dimension(100, 104));
        scCat.setMaximumSize(new Dimension(Integer.MAX_VALUE, 104));
        arriba.add(scCat);
        arriba.add(Tema.espacio(6));
        lblResultados.setFont(Tema.texto(12.5f));
        lblResultados.setForeground(Tema.GRIS_TEXTO);
        lblResultados.setAlignmentX(LEFT_ALIGNMENT);
        arriba.add(lblResultados);
        arriba.add(Tema.espacio(4));
        izq.add(arriba, BorderLayout.NORTH);

        JScrollPane sc = Tema.scrollSinBorde(rejilla);
        sc.getViewport().setBackground(Tema.GRIS_CLARO);
        sc.getVerticalScrollBar().setUnitIncrement(24);
        izq.add(sc, BorderLayout.CENTER);

        barraEspera.setOpaque(false);
        barraEspera.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
        izq.add(barraEspera, BorderLayout.SOUTH);

        buscador.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { refrescarProductos(); }
            public void removeUpdate(DocumentEvent e) { refrescarProductos(); }
            public void changedUpdate(DocumentEvent e) { refrescarProductos(); }
        });
        buscador.addActionListener(e -> enterEnBuscador());
        buscador.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "aRejilla");
        buscador.getActionMap().put("aRejilla", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (rejilla.getComponentCount() > 0) {
                    rejilla.getComponent(0).requestFocusInWindow();
                }
            }
        });
        return izq;
    }

    private void refrescarTodo() {
        refrescarCategorias();
        refrescarProductos();
        refrescarVenta();
        refrescarEspera();
    }

    private void refrescarCategorias() {
        filaCategorias.removeAll();
        List<Categoria> lista;
        try {
            lista = bd.listarCategorias(false);
        } catch (SQLException e) {
            lista = new ArrayList<>();
        }
        if (categoria != null && lista.stream().noneMatch(c -> c.getId() == categoria)) {
            categoria = null;
        }
        filaCategorias.add(new BotonCategoria(null, "Todos", null));
        for (Categoria c : lista) {
            filaCategorias.add(new BotonCategoria(c.getId(), c.getNombre(), c.getFoto()));
        }
        filaCategorias.setVisible(!lista.isEmpty()); // sin categorias creadas no hace falta la fila
        filaCategorias.getParent().getParent().setVisible(!lista.isEmpty());
        filaCategorias.revalidate();
        filaCategorias.repaint();
    }

    private void refrescarProductos() {
        String t = buscador.getText().trim();
        List<Producto> lista = t.isEmpty() ? cat.porCategoria(categoria) : cat.buscar(t);
        rejilla.removeAll();
        int n = 0;
        for (Producto p : lista) {
            if (n++ >= MAX_TARJETAS) {
                break;
            }
            rejilla.add(new TarjetaProducto(p, this::elegir));
        }
        if (lista.isEmpty()) {
            lblResultados.setText(t.isEmpty() ? (cat.tamano() == 0
                    ? "Aún no hay productos. El administrador los crea en Productos."
                    : "No hay productos en esta categoría.")
                    : "No hay productos con «" + t + "». Si es un código, oprima Enter.");
        } else if (lista.size() > MAX_TARJETAS) {
            lblResultados.setText("Se muestran " + MAX_TARJETAS + " de " + lista.size()
                    + " productos. Escriba el nombre para encontrar el que busca.");
        } else {
            lblResultados.setText(lista.size() + (lista.size() == 1 ? " producto" : " productos"));
        }
        rejilla.revalidate();
        rejilla.repaint();
    }

    /** Enter en el buscador: el escaner (codigo exacto), o un solo resultado. */
    private void enterEnBuscador() {
        String t = buscador.getText().trim();
        if (t.isEmpty()) {
            return;
        }
        Producto p = cat.buscarCodigo(t);
        if (p == null) {
            // el escaner pudo escribir el codigo pegado a un texto que ya estaba ("ceb7702511000014")
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d{6,}").matcher(t);
            while (p == null && m.find()) {
                p = cat.buscarCodigo(m.group());
            }
        }
        if (p != null) {
            Toolkit.getDefaultToolkit().beep();
            elegir(p);
            buscador.setText("");
            return;
        }
        if (t.replaceAll("\\s", "").matches("\\d{6,}")) {
            Toolkit.getDefaultToolkit().beep();
            Timer segundo = new Timer(180, e -> Toolkit.getDefaultToolkit().beep());
            segundo.setRepeats(false);
            segundo.start();
            Aviso.mostrar(rejilla.getParent() instanceof JComponent ? (JComponent) rejilla.getParent() : this,
                    Aviso.Tipo.ERROR, "Código desconocido", "No hay ningún producto con el código " + t);
            buscador.selectAll();
            return;
        }
        List<Producto> l = cat.buscar(t);
        if (l.size() == 1) {
            elegir(l.get(0));
            buscador.setText("");
        } else if (l.size() > 1) {
            rejilla.getComponent(0).requestFocusInWindow();
        }
    }

    /** Agregar un producto a la venta (clic en la tarjeta, escaner o Enter). */
    private void elegir(Producto p) {
        if (Capa.hayAbierta(this)) {
            return;
        }
        if (p.getUnidad() != Unidad.UNIDAD) {
            CuadroGranel.mostrar(this, p, (porValor, valor) -> {
                LineaVenta l = porValor ? carrito.agregarPorValor(p, valor) : carrito.agregar(p, valor);
                agregado(p, l);
            });
            return;
        }
        agregado(p, carrito.agregar(p));
    }

    private void agregado(Producto p, LineaVenta l) {
        refrescarVenta();
        buscador.selectAll(); // lo siguiente que se escriba o escanee reemplaza la busqueda
        enfocar();
        int fila = carrito.getLineas().indexOf(l);
        if (fila >= 0) {
            tabla.setRowSelectionInterval(fila, fila);
            tabla.scrollRectToVisible(tabla.getCellRect(fila, 0, true));
        }
        long enVenta = 0;
        for (LineaVenta x : carrito.getLineas()) {
            if (x.getProducto().getId() == p.getId()) {
                enVenta += x.getCantidad();
            }
        }
        JComponent zona = zonaProductos();
        if (enVenta > p.getStock()) {
            Aviso.mostrar(zona, Aviso.Tipo.AVISO, p.getNombre() + " agregado", "Según el inventario ya no hay");
        } else {
            Aviso.mostrar(zona, Aviso.Tipo.BIEN, "Producto agregado", p.getNombre());
        }
        enfocar();
    }

    private JComponent zonaProductos() {
        Container c = rejilla.getParent();
        while (c != null && !(c instanceof JScrollPane)) {
            c = c.getParent();
        }
        return c == null ? this : (JComponent) c;
    }

    // ------------------------------------------------------------------
    //  Lado derecho: la venta
    // ------------------------------------------------------------------

    private JComponent ladoVenta() {
        JPanel der = new JPanel(new BorderLayout()) {
            @Override
            public Dimension getPreferredSize() {
                // un poco menos de la mitad del ancho, entre 450 y 580 px
                int ancho = PanelVender.this.getWidth();
                int w = ancho <= 0 ? 460 : Math.max(450, Math.min(580, (int) (ancho * 0.42)));
                return new Dimension(w, 100);
            }
        };
        der.setBackground(Tema.BLANCO);
        der.setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, Tema.BORDE));

        JPanel cab = new JPanel();
        cab.setOpaque(false);
        cab.setLayout(new BoxLayout(cab, BoxLayout.Y_AXIS));
        cab.setBorder(BorderFactory.createEmptyBorder(20, 24, 12, 24));
        lblVenta.setFont(Tema.titulo(21));
        lblVenta.setForeground(Tema.GRIS_OSCURO);
        cab.add(lblVenta);
        lblSubVenta.setFont(Tema.texto(13));
        lblSubVenta.setForeground(Tema.GRIS_TEXTO);
        cab.add(lblSubVenta);
        der.add(cab, BorderLayout.NORTH);

        configurarTabla();
        JScrollPane sc = new JScrollPane(tabla);
        sc.setBorder(BorderFactory.createEmptyBorder(0, 16, 0, 16));
        sc.getViewport().setBackground(Tema.BLANCO);
        sc.setBackground(Tema.BLANCO);
        der.add(sc, BorderLayout.CENTER);

        JPanel pie = new JPanel();
        pie.setOpaque(false);
        pie.setLayout(new BoxLayout(pie, BoxLayout.Y_AXIS));
        pie.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Tema.BORDE),
                BorderFactory.createEmptyBorder(14, 24, 20, 24)));
        JPanel filaTotal = new JPanel(new BorderLayout());
        filaTotal.setOpaque(false);
        filaTotal.setAlignmentX(LEFT_ALIGNMENT);
        lblCuantos.setFont(Tema.texto(14));
        lblCuantos.setForeground(Tema.GRIS_TEXTO);
        lblCuantos.setVerticalAlignment(SwingConstants.BOTTOM);
        filaTotal.add(lblCuantos, BorderLayout.WEST);
        JPanel tot = new JPanel();
        tot.setOpaque(false);
        tot.setLayout(new BoxLayout(tot, BoxLayout.Y_AXIS));
        JLabel lt = new JLabel("Total");
        lt.setFont(Tema.texto(14));
        lt.setForeground(Tema.GRIS_TEXTO);
        lt.setAlignmentX(RIGHT_ALIGNMENT);
        tot.add(lt);
        lblTotal.setFont(Tema.titulo(36));
        lblTotal.setForeground(Tema.GRIS_OSCURO);
        lblTotal.setAlignmentX(RIGHT_ALIGNMENT);
        tot.add(lblTotal);
        filaTotal.add(tot, BorderLayout.EAST);
        filaTotal.setMaximumSize(new Dimension(Integer.MAX_VALUE, 80));
        pie.add(filaTotal);
        pie.add(Tema.espacio(14));

        btnPagar.setFont(Tema.subtitulo(17));
        btnPagar.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));
        btnPagar.setAlignmentX(LEFT_ALIGNMENT);
        btnPagar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 50));
        pie.add(btnPagar);
        pie.add(Tema.espacio(10));
        JPanel filaBotones = new JPanel(new BorderLayout(8, 0));
        filaBotones.setOpaque(false);
        filaBotones.setAlignmentX(LEFT_ALIGNMENT);
        filaBotones.add(btnEspera, BorderLayout.CENTER);
        btnCancelar.setForeground(Tema.GRIS_TEXTO);
        filaBotones.add(btnCancelar, BorderLayout.EAST);
        filaBotones.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        pie.add(filaBotones);
        der.add(pie, BorderLayout.SOUTH);

        btnPagar.addActionListener(e -> pagar());
        btnEspera.addActionListener(e -> ponerEnEspera());
        btnCancelar.addActionListener(e -> cancelar());
        return der;
    }

    private void configurarTabla() {
        tabla.setRowHeight(46);
        tabla.setFont(Tema.texto(14));
        tabla.setForeground(Tema.GRIS_OSCURO);
        tabla.setShowVerticalLines(false);
        tabla.setShowHorizontalLines(true);
        tabla.setGridColor(Tema.BORDE);
        tabla.setIntercellSpacing(new Dimension(0, 1));
        tabla.setSelectionBackground(Tema.AZUL_SUAVE);
        tabla.setSelectionForeground(Tema.GRIS_OSCURO);
        tabla.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        tabla.setFillsViewportHeight(true);
        tabla.setBackground(Tema.BLANCO);
        tabla.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        tabla.setSurrendersFocusOnKeystroke(true);
        tabla.getAccessibleContext().setAccessibleName("Productos de la venta");
        JTableHeader h = tabla.getTableHeader();
        h.setReorderingAllowed(false);
        h.setResizingAllowed(false);
        h.setDefaultRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object v, boolean s, boolean f, int r, int c) {
                JLabel l = (JLabel) super.getTableCellRendererComponent(t, v, false, false, r, c);
                l.setFont(Tema.textoFuerte(12.5f));
                l.setForeground(Tema.GRIS_TEXTO);
                l.setBackground(Tema.BLANCO);
                l.setHorizontalAlignment(c == 0 ? LEFT : RIGHT);
                l.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Tema.BORDE),
                        BorderFactory.createEmptyBorder(8, 8, 8, 8)));
                return l;
            }
        });
        DefaultTableCellRenderer celdas = new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object v, boolean s, boolean f, int r, int c) {
                JLabel l = (JLabel) super.getTableCellRendererComponent(t, v, s, false, r, c);
                l.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
                l.setHorizontalAlignment(c == 0 ? LEFT : RIGHT);
                l.setFont(c == 4 ? Tema.textoFuerte(14) : c == 0 ? Tema.textoFuerte(13.5f) : Tema.texto(14));
                boolean editable = c >= 1 && c <= 3;
                l.setForeground(Tema.GRIS_OSCURO);
                l.setToolTipText(editable ? "Doble clic o escriba para cambiar" : null);
                if (editable && !s) {
                    l.setForeground(Tema.AZUL); // lo que se puede cambiar se ve en azul
                }
                return l;
            }
        };
        tabla.setDefaultRenderer(Object.class, celdas);
        TableColumnModel cm = tabla.getColumnModel();
        // las columnas de numeros no se encogen: si falta espacio, se acorta el nombre del producto
        int[] anchos = {150, 70, 74, 56, 82, 36};
        for (int i = 0; i < anchos.length; i++) {
            cm.getColumn(i).setPreferredWidth(anchos[i]);
            if (i > 0) {
                cm.getColumn(i).setMinWidth(anchos[i]);
            }
        }
        cm.getColumn(0).setMinWidth(90);
        cm.getColumn(5).setMaxWidth(36);
        cm.getColumn(5).setCellRenderer(new Quitar());
        JTextField ed = new JTextField();
        ed.setFont(Tema.texto(14));
        ed.setHorizontalAlignment(JTextField.RIGHT);
        ed.setBorder(BorderFactory.createLineBorder(Tema.AZUL, 2));
        DefaultCellEditor editor = new DefaultCellEditor(ed);
        editor.setClickCountToStart(1);
        for (int i = 1; i <= 3; i++) {
            cm.getColumn(i).setCellEditor(editor);
        }
        tabla.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int fila = tabla.rowAtPoint(e.getPoint());
                if (fila >= 0 && tabla.columnAtPoint(e.getPoint()) == 5) {
                    quitarFila(fila);
                }
            }
        });
        tabla.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0),
                "quitar");
        tabla.getActionMap().put("quitar", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (tabla.getSelectedRow() >= 0 && !tabla.isEditing()) {
                    quitarFila(tabla.getSelectedRow());
                }
            }
        });
    }

    private void quitarFila(int fila) {
        if (tabla.isEditing()) {
            tabla.getCellEditor().cancelCellEditing();
        }
        carrito.quitar(fila);
        refrescarVenta();
        enfocar();
    }

    private void refrescarVenta() {
        modelo.fireTableDataChanged();
        long numero;
        try {
            numero = bd.siguienteNumero();
        } catch (SQLException e) {
            numero = 0;
        }
        if (idEspera != null) {
            lblVenta.setText("Venta en espera" + (nombreEspera == null ? "" : " · " + nombreEspera));
        } else {
            lblVenta.setText("Venta No. " + Comprobante.numeroTexto(numero));
        }
        lblSubVenta.setText("Atiende " + app.getSesion().getUsuario().getNombre());
        int n = carrito.cantidadProductos();
        lblCuantos.setText(n == 0 ? "Sin productos" : n + (n == 1 ? " producto" : " productos"));
        lblTotal.setText(Tarifa.pesos(carrito.getTotal()));
        btnPagar.setEnabled(n > 0);
        btnEspera.setEnabled(n > 0);
        btnCancelar.setEnabled(n > 0);
        tabla.repaint();
    }

    /** Texto de la cantidad: "2", "1,5 lb" o "455 g". */
    private static String textoCantidad(LineaVenta l) {
        Producto p = l.getProducto();
        if (p.getUnidad() == Unidad.UNIDAD) {
            return PanelProductos.cantidad(l.getCantidad());
        }
        return CuadroGranel.peso(l.getCantidad(), p);
    }

    /** Las filas de la tabla salen del carrito. */
    private class ModeloVenta extends AbstractTableModel {
        private final String[] columnas = {"Producto", "Cant.", "Precio", "Desc", "Total", ""};

        @Override
        public int getRowCount() {
            return carrito.getLineas().size();
        }

        @Override
        public int getColumnCount() {
            return columnas.length;
        }

        @Override
        public String getColumnName(int c) {
            return columnas[c];
        }

        @Override
        public boolean isCellEditable(int r, int c) {
            return c >= 1 && c <= 3;
        }

        @Override
        public Object getValueAt(int r, int c) {
            LineaVenta l = carrito.getLineas().get(r);
            switch (c) {
                case 0:
                    return l.getProducto().getNombre() + (l.esPorValor() ? "  (por valor)" : "");
                case 1:
                    return textoCantidad(l);
                case 2:
                    return Tarifa.pesos(l.getPrecio());
                case 3:
                    return l.getDescuento() == 0 ? "—" : "-" + Tarifa.pesos(l.getDescuento());
                case 4:
                    return Tarifa.pesos(l.getSubtotal());
                default:
                    return "";
            }
        }

        @Override
        public void setValueAt(Object v, int r, int c) {
            String t = v == null ? "" : v.toString().trim();
            if (r >= carrito.getLineas().size()) {
                return;
            }
            LineaVenta l = carrito.getLineas().get(r);
            if (t.equals(String.valueOf(getValueAt(r, c)).trim())) {
                enfocar(); // no cambio nada: una linea "dos mil de" sigue siendo de dos mil
                return;
            }
            try {
                if (c == 1) {
                    String limpio = t.replaceAll("(?i)\\s*(kg|lb|g|und)$", "");
                    long cant = ImportadorProductos.leerCantidad(limpio);
                    if (t.toLowerCase().endsWith(" g") || (t.toLowerCase().endsWith("g")
                            && !t.toLowerCase().endsWith("kg"))) {
                        cant = cant / 1000; // "455 g" -> 455 milesimas de kilo
                    }
                    if (l.getProducto().getUnidad() == Unidad.UNIDAD && cant % 1000 != 0) {
                        throw new IllegalArgumentException("Este producto se vende por unidades enteras");
                    }
                    if (cant != l.getCantidad()) {
                        carrito.cambiarCantidad(r, cant);
                    }
                } else if (c == 2) {
                    long precio = ImportadorProductos.leerPesos(t);
                    if (precio != l.getPrecio()) {
                        carrito.cambiarPrecio(r, precio);
                    }
                } else if (c == 3) {
                    long desc = t.isEmpty() || t.equals("—") ? 0 : ImportadorProductos.leerPesos(t.replace("-", ""));
                    if (desc != l.getDescuento()) {
                        carrito.cambiarDescuento(r, desc);
                    }
                }
            } catch (IllegalArgumentException e) {
                Aviso.mostrar(zonaProductos(), Aviso.Tipo.ERROR, "No se cambió", e.getMessage());
            }
            refrescarVenta();
            enfocar(); // despues de editar, el escaner vuelve a escribir en el buscador
        }
    }

    /** Columna para quitar el producto: una equis dibujada (sin emojis). */
    private static class Quitar extends DefaultTableCellRenderer {
        Quitar() {
            setToolTipText("Quitar de la venta (Supr)");
        }

        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean s, boolean f, int r, int c) {
            super.getTableCellRendererComponent(t, "", s, false, r, c);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            return this;
        }

        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0.create();
            Tema.suavizar(g);
            g.setColor(Tema.GRIS_TEXTO);
            g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            int cx = getWidth() / 2, cy = getHeight() / 2;
            g.drawLine(cx - 5, cy - 5, cx + 5, cy + 5);
            g.drawLine(cx + 5, cy - 5, cx - 5, cy + 5);
            g.dispose();
        }
    }

    // ------------------------------------------------------------------
    //  Pagar, espera y cancelar
    // ------------------------------------------------------------------

    private void pagar() {
        if (tabla.isEditing()) {
            tabla.getCellEditor().stopCellEditing();
        }
        if (carrito.estaVacio() || Capa.hayAbierta(this)) {
            return;
        }
        CapaCobro.mostrar(this, app, carrito, idEspera, nombreEspera, numero -> {
            for (LineaVenta l : carrito.getLineas()) {
                try {
                    cat.recargarProducto(l.getProducto().getId());
                } catch (SQLException e) {
                    Ubicaciones.registrarError(e);
                }
            }
            nuevaVenta();
            refrescarProductos();
            refrescarEspera();
        });
    }

    private void nuevaVenta() {
        carrito = new Carrito();
        idEspera = null;
        nombreEspera = null;
        buscador.setText(""); // la venta nueva arranca con todos los productos a la vista
        refrescarVenta();
        enfocar();
    }

    /** Guarda la venta en espera (pide un nombre opcional) y deja la pantalla limpia. */
    private void ponerEnEspera() {
        if (tabla.isEditing()) {
            tabla.getCellEditor().stopCellEditing();
        }
        if (carrito.estaVacio() || Capa.hayAbierta(this)) {
            return;
        }
        JPanel t = Capa.tarjeta(460, 24, 28);
        t.add(Tema.tituloPantalla("Dejar la venta en espera"));
        t.add(Tema.parrafo("Queda guardada abajo y se retoma con un clic. No descuenta inventario hasta que se cobre.",
                380));
        t.add(Tema.espacio(12));
        t.add(Tema.etiqueta("¿A nombre de quién? (opcional)"));
        JTextField nombre = Tema.campo("Ej. Marcela, la señora del 4");
        if (nombreEspera != null) {
            nombre.setText(nombreEspera);
        }
        Tema.anchoCompleto(nombre);
        t.add(nombre);
        t.add(Tema.espacio(16));
        Tema.Boton guardar = Tema.botonPrimario("Guardar en espera");
        Tema.Boton volver = Tema.botonSecundario("Volver");
        t.add(Tema.fila(8, guardar, volver));
        Capa capa = new Capa(this, t, true);
        Runnable hacer = () -> {
            capa.cerrar();
            guardarEspera(nombre.getText().trim(), true);
        };
        guardar.addActionListener(e -> hacer.run());
        nombre.addActionListener(e -> hacer.run());
        volver.addActionListener(e -> capa.cerrar());
        capa.mostrar(nombre);
    }

    private boolean guardarEspera(String nombre, boolean avisar) {
        try {
            bd.guardarEspera(idEspera, nombre.isEmpty() ? null : nombre, app.getSesion().getUsuario().getId(), carrito);
            if (avisar) {
                Aviso.mostrar(zonaProductos(), Aviso.Tipo.BIEN, "Venta en espera",
                        nombre.isEmpty() ? "Quedó guardada abajo" : "A nombre de " + nombre);
            }
            nuevaVenta();
            refrescarEspera();
            return true;
        } catch (SQLException | RuntimeException e) {
            Ubicaciones.registrarError(e);
            Alerta.mostrar(this, Alerta.Tipo.AVISO, "No se pudo guardar",
                    "La venta sigue en pantalla. Detalle: " + e.getMessage(), null);
            return false;
        }
    }

    private void cancelar() {
        if (tabla.isEditing()) {
            tabla.getCellEditor().cancelCellEditing();
        }
        if (carrito.estaVacio() || Capa.hayAbierta(this)) {
            return;
        }
        int n = carrito.cantidadProductos();
        String msg = "Se quita" + (n == 1 ? " el producto" : "n los " + n + " productos") + " de la venta"
                + (idEspera != null ? " y deja de estar en espera." : ".");
        Alerta.confirmar(this, "¿Cancelar esta venta?", msg, "Sí, cancelar", "No, seguir vendiendo", () -> {
            if (idEspera != null) {
                try {
                    bd.borrarEspera(idEspera);
                } catch (SQLException e) {
                    Ubicaciones.registrarError(e);
                }
            }
            nuevaVenta();
            refrescarEspera();
        });
    }

    /** Retoma una venta en espera. Si hay una venta a medias, primero la deja en espera. */
    private void retomar(long id) {
        if (Capa.hayAbierta(this) || (idEspera != null && idEspera == id)) {
            return;
        }
        if (!carrito.estaVacio()) {
            if (!guardarEspera(nombreEspera == null ? "" : nombreEspera, false)) {
                return;
            }
            Aviso.mostrar(zonaProductos(), Aviso.Tipo.BIEN, "Venta guardada", "La venta que tenía quedó en espera");
        }
        try {
            BaseTienda.Retomada r = bd.cargarEspera(id, cat);
            carrito = r.getCarrito();
            idEspera = id;
            nombreEspera = r.getNombre();
            refrescarVenta();
            refrescarEspera();
            if (!r.getAvisos().isEmpty()) {
                Aviso.mostrar(zonaProductos(), Aviso.Tipo.AVISO, "Revise la venta", String.join(" · ", r.getAvisos()));
            }
            enfocar();
        } catch (SQLException | RuntimeException e) {
            Ubicaciones.registrarError(e);
            Alerta.mostrar(this, Alerta.Tipo.AVISO, "No se pudo abrir la venta en espera", e.getMessage(), null);
            refrescarEspera();
        }
    }

    private void refrescarEspera() {
        barraEspera.removeAll();
        List<BaseTienda.VentaEspera> lista;
        try {
            lista = bd.listarEspera();
        } catch (SQLException e) {
            lista = new ArrayList<>();
        }
        if (!lista.isEmpty()) {
            JLabel l = new JLabel("En espera:");
            l.setFont(Tema.textoFuerte(13));
            l.setForeground(Tema.GRIS_TEXTO);
            barraEspera.add(l);
            int i = 1;
            for (BaseTienda.VentaEspera v : lista) {
                barraEspera.add(new PestanaEspera(v, i++));
            }
        }
        barraEspera.setVisible(!lista.isEmpty());
        barraEspera.revalidate();
        barraEspera.repaint();
    }

    // ------------------------------------------------------------------
    //  Atajos
    // ------------------------------------------------------------------

    private void atajos() {
        atajo(KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0), "buscar", () -> {
            buscador.requestFocusInWindow();
            buscador.selectAll();
        });
        atajo(KeyStroke.getKeyStroke(KeyEvent.VK_F8, 0), "espera", this::ponerEnEspera);
        atajo(KeyStroke.getKeyStroke(KeyEvent.VK_F12, 0), "pagar", this::pagar);
        atajo(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), "pagar2", this::pagar);
    }

    private void atajo(KeyStroke k, String nombre, Runnable r) {
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(k, nombre);
        getActionMap().put(nombre, new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (isShowing() && !Capa.hayAbierta(PanelVender.this)) {
                    r.run();
                }
            }
        });
    }

    // ------------------------------------------------------------------
    //  Piezas pequeñas
    // ------------------------------------------------------------------

    /** Rejilla de tarjetas que se acomoda al ancho (tantas columnas como quepan). */
    private static class Rejilla extends JPanel implements Scrollable {
        Rejilla() {
            setOpaque(false);
            setLayout(new LayoutManager() {
                private static final int ESPACIO = 12;

                @Override
                public void addLayoutComponent(String n, Component c) {
                }

                @Override
                public void removeLayoutComponent(Component c) {
                }

                private int columnas(Container p) {
                    int w = Math.max(TarjetaProducto.ANCHO, p.getWidth());
                    return Math.max(1, (w + ESPACIO) / (TarjetaProducto.ANCHO + ESPACIO));
                }

                @Override
                public Dimension preferredLayoutSize(Container p) {
                    int col = columnas(p);
                    int filas = (p.getComponentCount() + col - 1) / col;
                    return new Dimension(TarjetaProducto.ANCHO, filas * (TarjetaProducto.ALTO + ESPACIO) + 4);
                }

                @Override
                public Dimension minimumLayoutSize(Container p) {
                    return preferredLayoutSize(p);
                }

                @Override
                public void layoutContainer(Container p) {
                    int col = columnas(p);
                    int ancho = (p.getWidth() - (col - 1) * ESPACIO) / col; // las tarjetas se estiran para llenar
                    Insets in = p.getInsets();
                    for (int i = 0; i < p.getComponentCount(); i++) {
                        int f = i / col, c = i % col;
                        p.getComponent(i).setBounds(in.left + c * (ancho + ESPACIO), in.top + f * (TarjetaProducto.ALTO
                                + ESPACIO), ancho, TarjetaProducto.ALTO);
                    }
                }
            });
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle r, int o, int d) {
            return 24;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle r, int o, int d) {
            return r.height - 40;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    /** Boton de categoria: foto redonda (o la inicial) y el nombre. */
    private class BotonCategoria extends JPanel {
        private final Long id;
        private final String nombre;
        private final String foto;
        private boolean encima;

        BotonCategoria(Long id, String nombre, String foto) {
            this.id = id;
            this.nombre = nombre;
            this.foto = foto;
            setOpaque(false);
            setFocusable(true);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setPreferredSize(new Dimension(Math.max(96, getFontMetrics(Tema.textoFuerte(12.5f)).stringWidth(nombre) + 24),
                    96));
            getAccessibleContext().setAccessibleName("Categoría " + nombre);
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    elegir();
                }

                @Override
                public void mouseEntered(MouseEvent e) {
                    encima = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    encima = false;
                    repaint();
                }
            });
            for (String tecla : new String[]{"ENTER", "SPACE"}) {
                getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(tecla), "elegir");
            }
            getActionMap().put("elegir", new AbstractAction() {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent e) {
                    elegir();
                }
            });
            addFocusListener(new java.awt.event.FocusAdapter() {
                @Override
                public void focusGained(java.awt.event.FocusEvent e) {
                    repaint();
                }

                @Override
                public void focusLost(java.awt.event.FocusEvent e) {
                    repaint();
                }
            });
        }

        private boolean activa() {
            return id == null ? categoria == null : id.equals(categoria);
        }

        private void elegir() {
            categoria = id;
            buscador.setText("");
            filaCategorias.repaint();
            refrescarProductos();
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Tema.suavizar(g);
            int w = getWidth(), h = getHeight() - 4;
            boolean activa = activa();
            g.setColor(activa ? Tema.AZUL_SUAVE : Tema.BLANCO);
            g.fillRoundRect(1, 1, w - 2, h - 2, Tema.ARCO, Tema.ARCO);
            if (isFocusOwner()) {
                g.setColor(Tema.AZUL);
                g.setStroke(new BasicStroke(2.2f));
                g.drawRoundRect(1, 1, w - 3, h - 3, Tema.ARCO, Tema.ARCO);
            } else if (activa || encima) {
                g.setColor(activa ? Tema.AZUL : Tema.BORDE_CONTROL);
                g.setStroke(new BasicStroke(1.2f));
                g.drawRoundRect(1, 1, w - 3, h - 3, Tema.ARCO, Tema.ARCO);
            } else {
                g.setColor(Tema.BORDE);
                g.drawRoundRect(1, 1, w - 3, h - 3, Tema.ARCO, Tema.ARCO);
            }
            int d = 46, cx = w / 2, cy = 12 + d / 2;
            Image img = Fotos.cargar(foto, d, d);
            if (img != null) {
                java.awt.Shape antes = g.getClip();
                g.clip(new java.awt.geom.Ellipse2D.Double(cx - d / 2.0, 12, d, d));
                Fotos.dibujar(g, img, cx - d / 2 - 6, 6, d + 12, d + 12);
                g.setClip(antes);
            } else if (id == null) {
                g.setColor(activa ? Tema.BLANCO : Tema.GRIS_CLARO);
                g.fillOval(cx - d / 2, 12, d, d);
                // cuatro cuadritos: "todos"
                g.setColor(Tema.AZUL);
                g.setStroke(new BasicStroke(1.8f));
                for (int i = 0; i < 4; i++) {
                    g.drawRoundRect(cx - 11 + (i % 2) * 12, cy - 11 + (i / 2) * 12, 9, 9, 3, 3);
                }
            } else {
                g.setColor(activa ? Tema.BLANCO : Tema.GRIS_CLARO);
                g.fillOval(cx - d / 2, 12, d, d);
                g.setColor(Tema.AZUL);
                g.setFont(Tema.subtitulo(18));
                String ini = nombre.substring(0, 1).toUpperCase();
                g.drawString(ini, cx - g.getFontMetrics().stringWidth(ini) / 2, cy + 7);
            }
            g.setFont(Tema.textoFuerte(12.5f));
            g.setColor(activa ? Tema.AZUL : Tema.GRIS_OSCURO);
            java.awt.FontMetrics fm = g.getFontMetrics();
            g.drawString(nombre, (w - fm.stringWidth(nombre)) / 2, h - 12);
            g.dispose();
        }
    }

    /** Pestaña de una venta en espera: nombre, total y cuanto lleva esperando. Sin equis para borrar. */
    private class PestanaEspera extends Tema.Boton {
        PestanaEspera(BaseTienda.VentaEspera v, int i) {
            super("", Tema.BLANCO, Tema.AZUL, Tema.BORDE_CONTROL);
            long min = Math.max(0, Duration.between(v.getCreada(), LocalDateTime.now()).toMinutes());
            String quien = v.getNombre() == null ? "Venta " + i : v.getNombre();
            setText(quien + "  ·  " + Tarifa.pesos(v.getTotal()) + "  ·  " + (min < 1 ? "ahora" : "hace " + min + " min"));
            setFont(Tema.textoFuerte(13));
            boolean actual = idEspera != null && idEspera == v.getId();
            setSelected(actual);
            setToolTipText(actual ? "Es la venta que está en pantalla" : "Retomar esta venta");
            addActionListener(e -> retomar(v.getId()));
        }
    }

}
