package rutas.vista;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Image;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import rutas.logica.Catalogo;
import rutas.logica.ImportadorProductos;
import rutas.logica.ListadoProductos;
import rutas.logica.Permiso;
import rutas.logica.Tarifa;
import rutas.logica.Ubicaciones;
import rutas.modelo.Categoria;
import rutas.modelo.Producto;
import rutas.modelo.Unidad;

/**
 * Listado de productos a todo el ancho (solo administrador): filtros, buscador (sirve el
 * escaner), tabla por paginas, exportar a Excel (CSV) o PDF, y el formulario para crear o
 * editar como capa encima de la tabla.
 */
public class PanelProductos extends JPanel {

    private static final Categoria TODAS = new Categoria(0, "Todas", null, 0, true);
    private static final Categoria SIN_CATEGORIA = new Categoria(0, "Sin categoría", null, 0, true);
    private static final Integer[] POR_PAGINA = {10, 25, 50, 100};

    private final Aplicacion app;
    private final Catalogo cat;
    private final boolean verCompra;

    // ---- listado ----
    private final JComboBox<Integer> comboPorPagina = new JComboBox<>(POR_PAGINA);
    private final JComboBox<Categoria> filtroCategoria = new JComboBox<>();
    private final JComboBox<ListadoProductos.Mostrar> filtroMostrar = new JComboBox<>(ListadoProductos.Mostrar.values());
    private final JTextField campoBuscar = Tema.campo("Buscar por nombre o código");
    private final Modelo modelo = new Modelo();
    private final JTable tabla = new JTable(modelo);
    private final CardLayout tarjetasTabla = new CardLayout();
    private final JPanel zonaTabla = new JPanel(tarjetasTabla);
    private final JLabel lblVacia = new JLabel();
    private final JLabel lblPaginas = new JLabel();
    private final JPanel botonesPagina = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 0));
    private List<Producto> filtrados = new ArrayList<>();
    private ListadoProductos.Pagina pagina;
    private int numeroPagina = 1;
    private Map<Long, String> nombresCategoria = new HashMap<>();

    // ---- formulario (capa) ----
    private final JTextField campoNombre = Tema.campo("Ej. Arroz Diana 500 g");
    private final JTextField campoCodigo = Tema.campo("Escanee el código (opcional)");
    private final JComboBox<Unidad> comboUnidad = new JComboBox<>(Unidad.values());
    private final JComboBox<Categoria> comboCategoria = new JComboBox<>();
    private final JTextField campoVenta = Tema.campo("Ej. 2.500");
    private final JTextField campoCompra = Tema.campo("Ej. 2.000");
    private final JTextField campoStock = Tema.campo("Ej. 24 o 10,5");
    private final JTextField campoMinimo = Tema.campo("Ej. 6");
    private final JLabel miniatura = new JLabel();
    private final Tema.Parrafo lblForm = mensaje();
    private Producto editando; // null = producto nuevo
    private File fotoElegida;  // foto escogida que todavia no se ha copiado
    private Capa capaForm;

    public PanelProductos(Aplicacion app) {
        super(new BorderLayout());
        this.app = app;
        this.cat = app.getCatalogo();
        this.verCompra = app.getSesion().puede(Permiso.VER_COMPRA);
        setBackground(Tema.GRIS_CLARO);
        setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));
        putClientProperty(Capa.FOCO_AL_CERRAR, campoBuscar);

        add(encabezado(), BorderLayout.NORTH);
        Tema.Tarjeta tarjeta = new Tema.Tarjeta();
        tarjeta.setLayout(new BorderLayout(0, 12));
        tarjeta.add(barraHerramientas(), BorderLayout.NORTH);
        tarjeta.add(zonaTabla(), BorderLayout.CENTER);
        tarjeta.add(piePaginas(), BorderLayout.SOUTH);
        add(tarjeta, BorderLayout.CENTER);

        Tema.estiloCombo(comboUnidad, PanelProductos::textoUnidad);
        Tema.estiloCombo(comboCategoria, Categoria::getNombre);
        refrescarCategorias();

        // cada vez que se abre la pantalla se lee de nuevo: el stock cambia con cada venta
        addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentShown(java.awt.event.ComponentEvent e) {
                refrescar();
                javax.swing.SwingUtilities.invokeLater(campoBuscar::requestFocusInWindow);
            }
        });
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("control N"), "nuevo");
        getActionMap().put("nuevo", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (isShowing() && !Capa.hayAbierta(PanelProductos.this)) {
                    abrirFormulario(null, null);
                }
            }
        });
        refrescar();
    }

    // ------------------------------------------------------------------
    //  Encabezado: por pagina, titulo y exportar
    // ------------------------------------------------------------------

    private JComponent encabezado() {
        JPanel h = new JPanel(new BorderLayout());
        h.setOpaque(false);
        h.setBorder(BorderFactory.createEmptyBorder(0, 0, 12, 0));

        JPanel izq = columna("Por página", comboPorPagina);
        Tema.estiloCombo(comboPorPagina, String::valueOf);
        comboPorPagina.setPreferredSize(new Dimension(96, 34));
        comboPorPagina.setMaximumSize(new Dimension(96, 34));
        comboPorPagina.getAccessibleContext().setAccessibleName("Productos por página");
        comboPorPagina.addActionListener(e -> {
            numeroPagina = 1;
            mostrarPagina();
        });
        h.add(izq, BorderLayout.WEST);

        JLabel titulo = new JLabel("Listado de productos", SwingConstants.CENTER);
        titulo.setFont(Tema.titulo(24));
        titulo.setForeground(Tema.GRIS_OSCURO);
        h.add(titulo, BorderLayout.CENTER);

        JPanel botones = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        botones.setOpaque(false);
        botones.add(new BotonIcono("Excel", Icono.EXCEL, "Exportar la lista a Excel (CSV)", this::exportarExcel));
        botones.add(new BotonIcono("PDF", Icono.PDF, "Imprimir la lista o guardarla como PDF", this::exportarPdf));
        JPanel der = columna("Exportar", botones);
        h.add(der, BorderLayout.EAST);
        // mismo ancho a los lados para que el titulo quede en el centro de verdad
        int ancho = Math.max(izq.getPreferredSize().width, der.getPreferredSize().width);
        izq.setPreferredSize(new Dimension(ancho, izq.getPreferredSize().height));
        der.setPreferredSize(new Dimension(ancho, der.getPreferredSize().height));
        return h;
    }

    /** Etiqueta pequeña encima de un control (como "Por página" y "Exportar"). */
    private static JPanel columna(String etiqueta, JComponent c) {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        JLabel l = new JLabel(etiqueta);
        l.setFont(Tema.texto(13.5f));
        l.setForeground(Tema.GRIS_TEXTO);
        l.setAlignmentX(LEFT_ALIGNMENT);
        c.setAlignmentX(LEFT_ALIGNMENT);
        p.add(l);
        p.add(Box.createVerticalStrut(4));
        p.add(c);
        return p;
    }

    // ------------------------------------------------------------------
    //  Barra: filtros, acciones, buscador y Nuevo
    // ------------------------------------------------------------------

    private JComponent barraHerramientas() {
        JPanel b = new JPanel(new GridBagLayout());
        b.setOpaque(false);
        GridBagConstraints g = new GridBagConstraints();
        g.gridy = 0;
        g.anchor = GridBagConstraints.SOUTHWEST;
        g.insets = new Insets(0, 0, 0, 16);

        Tema.estiloCombo(filtroCategoria, Categoria::getNombre);
        filtroCategoria.setPreferredSize(new Dimension(220, 36));
        filtroCategoria.setMinimumSize(new Dimension(160, 36));
        filtroCategoria.getAccessibleContext().setAccessibleName("Categoría");
        filtroCategoria.addActionListener(e -> cambioDeFiltro());
        b.add(etiquetado("Categoría", filtroCategoria), g);

        Tema.estiloCombo(filtroMostrar, ListadoProductos.Mostrar::toString);
        filtroMostrar.setPreferredSize(new Dimension(220, 36));
        filtroMostrar.setMinimumSize(new Dimension(160, 36));
        filtroMostrar.getAccessibleContext().setAccessibleName("Mostrar");
        filtroMostrar.addActionListener(e -> cambioDeFiltro());
        b.add(etiquetado("Mostrar", filtroMostrar), g);

        g.weightx = 1;
        b.add(Box.createHorizontalGlue(), g);
        g.weightx = 0;
        b.add(new AccionIcono("Categorías", Icono.CATEGORIAS, "Crear y editar las categorías que se ven en Vender",
                () -> PanelCategorias.mostrar(this, app, this::refrescarCategorias)), g);
        b.add(new AccionIcono("Importar\nproductos", Icono.IMPORTAR, "Importar la lista desde Excel (guardada como CSV)",
                this::importar), g);
        b.add(new AccionIcono("Actualizar", Icono.ACTUALIZAR, "Volver a leer los productos", this::refrescar), g);
        g.weightx = 1;
        b.add(Box.createHorizontalGlue(), g);
        g.weightx = 0;

        campoBuscar.setPreferredSize(new Dimension(280, 40));
        campoBuscar.setMinimumSize(new Dimension(220, 40)); // en GridBag, sin minimo se aplasta
        campoBuscar.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { cambioDeFiltro(); }
            public void removeUpdate(DocumentEvent e) { cambioDeFiltro(); }
            public void changedUpdate(DocumentEvent e) { cambioDeFiltro(); }
        });
        campoBuscar.addActionListener(e -> enterEnBuscador());
        g.insets = new Insets(0, 0, 0, 10);
        b.add(campoBuscar, g);

        Tema.Boton nuevo = Tema.botonAccion("Nuevo");
        nuevo.setToolTipText("Crear un producto (Ctrl+N)");
        nuevo.addActionListener(e -> abrirFormulario(null, null));
        g.insets = new Insets(0, 0, 0, 0);
        b.add(nuevo, g);
        return b;
    }

    private static JPanel etiquetado(String etiqueta, JComponent c) {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        JLabel l = Tema.etiqueta(etiqueta);
        l.setLabelFor(c);
        l.setAlignmentX(LEFT_ALIGNMENT);
        c.setAlignmentX(LEFT_ALIGNMENT);
        p.add(l);
        p.add(c);
        return p;
    }

    // ------------------------------------------------------------------
    //  Tabla
    // ------------------------------------------------------------------

    private JComponent zonaTabla() {
        // la tabla se creo antes de saber si se ve el precio de compra: se arman de nuevo las columnas
        modelo.fireTableStructureChanged();
        tabla.setRowHeight(40);
        tabla.setShowVerticalLines(true);
        tabla.setShowHorizontalLines(true);
        tabla.setGridColor(Tema.BORDE);
        tabla.setIntercellSpacing(new Dimension(1, 1));
        tabla.setFillsViewportHeight(true);
        tabla.setBackground(Color.WHITE);
        tabla.setSelectionBackground(Tema.AZUL_SUAVE);
        tabla.setSelectionForeground(Tema.GRIS_OSCURO);
        tabla.setFont(Tema.texto(14));
        tabla.setAutoCreateRowSorter(false);
        tabla.getAccessibleContext().setAccessibleName("Tabla de productos");
        tabla.setDefaultRenderer(Object.class, new Celda());
        tabla.getTableHeader().setReorderingAllowed(false);

        JTableHeader cab = tabla.getTableHeader();
        cab.setPreferredSize(new Dimension(100, 66)); // caben dos lineas ("Valor de / compra")
        cab.setDefaultRenderer(new Encabezado());
        cab.setBackground(Tema.GRIS_CLARO);

        int[] anchos = modelo.anchos();
        for (int i = 0; i < anchos.length; i++) {
            tabla.getColumnModel().getColumn(i).setPreferredWidth(anchos[i]);
        }
        tabla.getColumnModel().getColumn(modelo.colAcciones()).setCellRenderer(new Acciones());
        tabla.getColumnModel().getColumn(modelo.colAcciones()).setMinWidth(96);
        tabla.getColumnModel().getColumn(modelo.colAcciones()).setMaxWidth(110);
        tabla.getColumnModel().getColumn(0).setMaxWidth(70);

        tabla.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int f = tabla.rowAtPoint(e.getPoint());
                int c = tabla.columnAtPoint(e.getPoint());
                if (f < 0 || pagina == null || f >= pagina.getProductos().size()) {
                    return;
                }
                Producto p = pagina.getProductos().get(f);
                if (c == modelo.colAcciones()) {
                    java.awt.Rectangle r = tabla.getCellRect(f, c, false);
                    if (e.getX() < r.x + r.width / 2) {
                        abrirFormulario(p, null);
                    } else {
                        cambiarActivo(p);
                    }
                } else if (e.getClickCount() == 2) {
                    abrirFormulario(p, null);
                }
            }
        });
        tabla.addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                int c = tabla.columnAtPoint(e.getPoint());
                tabla.setCursor(c == modelo.colAcciones() ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                        : Cursor.getDefaultCursor());
            }
        });
        tabla.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ENTER"), "editar");
        tabla.getActionMap().put("editar", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                int f = tabla.getSelectedRow();
                if (f >= 0 && pagina != null && f < pagina.getProductos().size()) {
                    abrirFormulario(pagina.getProductos().get(f), null);
                }
            }
        });

        JScrollPane sc = new JScrollPane(tabla);
        sc.setBorder(BorderFactory.createLineBorder(Tema.BORDE));
        sc.getViewport().setBackground(Color.WHITE);

        // estado vacio: icono, titulo y una frase de que hacer (no una tabla en blanco)
        JPanel vacia = new JPanel(new GridBagLayout());
        vacia.setBackground(Tema.GRIS_CLARO);
        vacia.setBorder(BorderFactory.createLineBorder(Tema.BORDE));
        JPanel txt = new JPanel();
        txt.setOpaque(false);
        txt.setLayout(new BoxLayout(txt, BoxLayout.Y_AXIS));
        JComponent lupa = new IconoGrande();
        lupa.setAlignmentX(CENTER_ALIGNMENT);
        txt.add(lupa);
        txt.add(Box.createVerticalStrut(12));
        JLabel t = new JLabel("No se encontraron productos");
        t.setFont(Tema.subtitulo(18));
        t.setForeground(Tema.AZUL_PROFUNDO);
        t.setAlignmentX(CENTER_ALIGNMENT);
        txt.add(t);
        txt.add(Box.createVerticalStrut(6));
        lblVacia.setFont(Tema.texto(14));
        // holgura a los lados: con la escala de Windows al 125 % el texto se dibuja un poco mas ancho
        lblVacia.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 12));
        t.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 12));
        lblVacia.setForeground(Tema.GRIS_TEXTO);
        lblVacia.setAlignmentX(CENTER_ALIGNMENT);
        txt.add(lblVacia);
        vacia.add(txt);

        zonaTabla.setOpaque(false);
        zonaTabla.add(sc, "tabla");
        zonaTabla.add(vacia, "vacia");
        return zonaTabla;
    }

    private JComponent piePaginas() {
        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        lblPaginas.setFont(Tema.texto(14));
        lblPaginas.setForeground(Tema.GRIS_OSCURO);
        p.add(lblPaginas, BorderLayout.NORTH);
        botonesPagina.setOpaque(false);
        p.add(botonesPagina, BorderLayout.CENTER);
        return p;
    }

    // ------------------------------------------------------------------
    //  Datos
    // ------------------------------------------------------------------

    private void cambioDeFiltro() {
        numeroPagina = 1;
        refrescar();
    }

    private void refrescar() {
        List<Producto> todos;
        try {
            todos = cat.todosConInactivos();
        } catch (SQLException e) {
            Aviso.mostrar(this, Aviso.Tipo.ERROR, "No se pudieron leer los productos", e.getMessage());
            return;
        }
        Categoria c = (Categoria) filtroCategoria.getSelectedItem();
        Long categoriaId = c == null || c.getId() == 0 ? null : c.getId();
        ListadoProductos.Mostrar m = (ListadoProductos.Mostrar) filtroMostrar.getSelectedItem();
        filtrados = ListadoProductos.filtrar(todos, campoBuscar.getText(), categoriaId,
                m == null ? ListadoProductos.Mostrar.ACTIVOS : m);
        String t = campoBuscar.getText().trim();
        lblVacia.setText(todos.isEmpty() ? "Cree el primero con «Nuevo» o importe la lista desde Excel."
                : !t.isEmpty() ? "No hay productos con «" + t + "». Si es un código nuevo, oprima Enter para crearlo."
                : "Pruebe con otra categoría o cambie lo que se muestra.");
        mostrarPagina();
    }

    private void mostrarPagina() {
        int porPagina = (Integer) comboPorPagina.getSelectedItem();
        pagina = ListadoProductos.pagina(filtrados, porPagina, numeroPagina);
        numeroPagina = pagina.getNumero();
        modelo.fireTableDataChanged();
        tarjetasTabla.show(zonaTabla, filtrados.isEmpty() ? "vacia" : "tabla");
        lblPaginas.setText("Páginas: " + pagina.getTotalPaginas() + "    ·    " + pagina.getTotal()
                + (pagina.getTotal() == 1 ? " producto" : " productos"));
        botonesPagina.removeAll();
        if (pagina.getTotalPaginas() > 1) {
            botonesPagina.add(new BotonPagina("«", "Página anterior", numeroPagina > 1, false, () -> irA(numeroPagina - 1)));
            for (int n : ListadoProductos.botones(numeroPagina, pagina.getTotalPaginas())) {
                if (n < 0) {
                    JLabel puntos = new JLabel("…");
                    puntos.setFont(Tema.texto(14));
                    puntos.setForeground(Tema.GRIS_TEXTO);
                    puntos.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
                    botonesPagina.add(puntos);
                } else {
                    final int destino = n;
                    botonesPagina.add(new BotonPagina(String.valueOf(n), "Página " + n, true, n == numeroPagina,
                            () -> irA(destino)));
                }
            }
            botonesPagina.add(new BotonPagina("»", "Página siguiente", numeroPagina < pagina.getTotalPaginas(), false,
                    () -> irA(numeroPagina + 1)));
        }
        botonesPagina.revalidate();
        botonesPagina.repaint();
    }

    private void irA(int n) {
        numeroPagina = n;
        mostrarPagina();
    }

    /** Enter en el buscador (o el escaner): abre el producto o prepara uno nuevo con ese codigo. */
    private void enterEnBuscador() {
        String t = campoBuscar.getText().trim();
        campoBuscar.selectAll(); // el siguiente escaneo reemplaza el texto
        if (t.isEmpty()) {
            return;
        }
        Producto p = null;
        try {
            p = app.getTienda().buscarPorCodigoEnBase(t);
        } catch (SQLException e) {
            Ubicaciones.registrarError(e);
        }
        if (p != null) {
            abrirFormulario(p, null);
        } else if (t.matches("\\d{6,}")) {
            abrirFormulario(null, t);
        } else if (filtrados.size() == 1) {
            abrirFormulario(filtrados.get(0), null);
        }
    }

    // ------------------------------------------------------------------
    //  Formulario como capa
    // ------------------------------------------------------------------

    private void abrirFormulario(Producto p, String codigoNuevo) {
        if (Capa.hayAbierta(this)) {
            return;
        }
        refrescarCategorias();
        JPanel t = Capa.tarjeta(640, 24, 28);
        t.add(Tema.tituloPantalla(p == null ? "Nuevo producto" : "Editar producto"));
        t.add(Tema.espacio(10));

        JPanel grilla = new JPanel(new GridBagLayout());
        grilla.setOpaque(false);
        grilla.setAlignmentX(LEFT_ALIGNMENT);
        GridBagConstraints g = new GridBagConstraints();
        g.fill = GridBagConstraints.HORIZONTAL;
        g.weightx = 1;
        g.insets = new Insets(0, 0, 10, 12);
        g.gridx = 0;
        g.gridy = 0;
        g.gridwidth = 2;
        grilla.add(etiquetado("Nombre", campoNombre), g);
        g.gridwidth = 1;
        fila(grilla, g, 1, "Código de barras", campoCodigo, "Categoría", comboCategoria);
        fila(grilla, g, 2, "Se vende por", comboUnidad, "Precio de venta", campoVenta);
        if (verCompra) {
            fila(grilla, g, 3, "Precio de compra (para la ganancia)", campoCompra, "Cantidad en la tienda", campoStock);
            fila(grilla, g, 4, "Avisar cuando queden (o menos)", campoMinimo, null, null);
        } else {
            fila(grilla, g, 3, "Cantidad en la tienda", campoStock, "Avisar cuando queden (o menos)", campoMinimo);
        }
        g.gridx = 1;
        g.gridy = 4;
        Tema.Boton foto = Tema.botonPequeno("Elegir foto…");
        foto.addActionListener(e -> elegirFoto());
        miniatura.setPreferredSize(new Dimension(64, 64));
        miniatura.getAccessibleContext().setAccessibleName("Foto del producto");
        JPanel filaFoto = Tema.fila(10, miniatura, foto);
        grilla.add(mitad(etiquetado("Foto", filaFoto)), g);
        t.add(grilla);
        t.add(lblForm);
        t.add(Tema.espacio(10));

        Tema.Boton guardar = Tema.botonPrimario("Guardar");
        Tema.Boton volver = Tema.botonSecundario("Volver");
        JPanel botones = Tema.fila(8, guardar, volver);
        Tema.Boton activo = null;
        if (p != null) {
            activo = Tema.botonPequeno(p.isActivo() ? "Desactivar" : "Reactivar");
            botones.add(activo);
        }
        t.add(botones);

        capaForm = new Capa(this, t, true);
        guardar.addActionListener(e -> guardar());
        volver.addActionListener(e -> capaForm.cerrar());
        if (activo != null) {
            activo.addActionListener(e -> {
                capaForm.cerrar();
                cambiarActivo(p);
            });
        }
        if (p == null) {
            limpiar(codigoNuevo);
        } else {
            cargar(p);
        }
        capaForm.mostrar(campoNombre);
    }

    private void fila(JPanel grilla, GridBagConstraints g, int y, String e1, JComponent c1, String e2, JComponent c2) {
        g.gridy = y;
        g.gridx = 0;
        grilla.add(mitad(etiquetado(e1, c1)), g);
        if (e2 != null) {
            g.gridx = 1;
            grilla.add(mitad(etiquetado(e2, c2)), g);
        }
    }

    /** Las dos columnas del formulario con el mismo ancho. */
    private static JPanel mitad(JPanel p) {
        p.setPreferredSize(new Dimension(280, p.getPreferredSize().height));
        return p;
    }

    private void limpiar(String codigo) {
        editando = null;
        fotoElegida = null;
        for (JTextField f : new JTextField[]{campoNombre, campoVenta, campoCompra, campoStock, campoMinimo}) {
            f.setText("");
        }
        campoCodigo.setText(codigo == null ? "" : codigo);
        comboUnidad.setSelectedItem(Unidad.UNIDAD);
        comboCategoria.setSelectedItem(SIN_CATEGORIA);
        mostrarFoto(null);
        lblForm.setText(codigo == null ? " " : "Ese código no está registrado. Escriba los datos y guarde.");
        lblForm.setForeground(Tema.AVISO_TEXTO);
    }

    private void cargar(Producto p) {
        editando = p;
        fotoElegida = null;
        campoNombre.setText(p.getNombre());
        campoCodigo.setText(p.getCodigo() == null ? "" : p.getCodigo());
        comboUnidad.setSelectedItem(p.getUnidad());
        elegirCategoria(p.getCategoriaId());
        campoVenta.setText(Tarifa.pesos(p.getPrecioVenta()).replace("$", ""));
        campoCompra.setText(Tarifa.pesos(p.getPrecioCompra()).replace("$", ""));
        campoStock.setText(cantidad(p.getStock()));
        campoMinimo.setText(cantidad(p.getMinimo()));
        mostrarFoto(p.getFoto() == null ? null : new File(p.getFoto()));
        lblForm.setText(p.isActivo() ? " " : "Este producto está desactivado: no sale en Vender.");
        lblForm.setForeground(Tema.AVISO_TEXTO);
    }

    private void guardar() {
        try {
            long venta = ImportadorProductos.leerPesos(campoVenta.getText());
            long compra = !verCompra ? (editando == null ? 0 : editando.getPrecioCompra())
                    : campoCompra.getText().isBlank() ? 0 : ImportadorProductos.leerPesos(campoCompra.getText());
            long stock = campoStock.getText().isBlank() ? 0 : ImportadorProductos.leerCantidad(campoStock.getText());
            if (editando != null && campoStock.getText().trim().equals(cantidad(editando.getStock()))) {
                // no tocaron la cantidad: se guarda la de la base (pudo bajar por ventas mientras tanto)
                Producto actual = app.getTienda().buscarProductoPorId(editando.getId());
                if (actual != null) {
                    stock = actual.getStock();
                }
            }
            long minimo = campoMinimo.getText().isBlank() ? 0 : ImportadorProductos.leerCantidad(campoMinimo.getText());
            Unidad u = (Unidad) comboUnidad.getSelectedItem();
            long id = editando == null ? 0 : editando.getId();
            String foto = editando == null ? null : editando.getFoto();
            Categoria c = (Categoria) comboCategoria.getSelectedItem();
            Long categoriaId = c == null || c.getId() == 0 ? null : c.getId();
            boolean activo = editando == null || editando.isActivo();
            Producto p = new Producto(id, campoCodigo.getText(), campoNombre.getText(), u, venta, compra, stock,
                    minimo, foto, true, categoriaId);
            boolean eraNuevo = editando == null;
            p = eraNuevo ? cat.crear(p) : cat.editar(p);
            editando = p; // si lo que sigue falla, guardar otra vez edita este producto en vez de duplicarlo
            if (fotoElegida != null) {
                String ruta = copiarFoto(fotoElegida, "producto-" + p.getId());
                Fotos.olvidar(ruta); // la foto nueva queda con el mismo nombre de archivo
                p = cat.editar(new Producto(p.getId(), p.getCodigo(), p.getNombre(), p.getUnidad(),
                        p.getPrecioVenta(), p.getPrecioCompra(), p.getStock(), p.getMinimo(), ruta, activo, categoriaId));
            }
            capaForm.cerrar();
            refrescar();
            Aviso.mostrar(this, Aviso.Tipo.BIEN, eraNuevo ? "Producto creado" : "Cambios guardados", p.getNombre());
        } catch (IllegalArgumentException e) {
            decir(lblForm, e.getMessage(), false);
        } catch (SQLException | IOException e) {
            decir(lblForm, "No se pudo guardar: " + e.getMessage(), false);
        }
    }

    /** La papelera desactiva (no borra): las ventas guardadas nombran al producto. */
    private void cambiarActivo(Producto p) {
        if (p.isActivo()) {
            Alerta.confirmar(this, "¿Desactivar «" + p.getNombre() + "»?",
                    "Deja de salir en Vender. Las ventas anteriores no cambian y se puede reactivar en «Mostrar: Desactivados».",
                    "Sí, desactivar", "Volver", () -> {
                        try {
                            cat.desactivar(p.getId());
                            refrescar();
                            Aviso.mostrar(this, Aviso.Tipo.BIEN, "Producto desactivado", p.getNombre());
                        } catch (SQLException e) {
                            Alerta.mostrar(this, Alerta.Tipo.AVISO, "No se pudo desactivar", e.getMessage(), null);
                        }
                    });
        } else {
            try {
                cat.reactivar(p.getId());
                refrescar();
                Aviso.mostrar(this, Aviso.Tipo.BIEN, "Producto reactivado", p.getNombre());
            } catch (IllegalArgumentException | SQLException e) {
                Alerta.mostrar(this, Alerta.Tipo.AVISO, "No se pudo reactivar", e.getMessage(), null);
            }
        }
    }

    /** Llena las listas de categorias (filtro y formulario) sin perder lo elegido. */
    private void refrescarCategorias() {
        Categoria antesFiltro = (Categoria) filtroCategoria.getSelectedItem();
        Categoria antesForm = (Categoria) comboCategoria.getSelectedItem();
        List<Categoria> lista = new ArrayList<>();
        try {
            lista = app.getTienda().listarCategorias(false);
        } catch (SQLException e) {
            Ubicaciones.registrarError(e);
        }
        nombresCategoria = new HashMap<>();
        java.awt.event.ActionListener[] oyentes = filtroCategoria.getActionListeners();
        for (java.awt.event.ActionListener o : oyentes) {
            filtroCategoria.removeActionListener(o); // llenar el combo no debe refrescar la tabla cada vez
        }
        filtroCategoria.removeAllItems();
        filtroCategoria.addItem(TODAS);
        comboCategoria.removeAllItems();
        comboCategoria.addItem(SIN_CATEGORIA);
        for (Categoria c : lista) {
            nombresCategoria.put(c.getId(), c.getNombre());
            filtroCategoria.addItem(c);
            comboCategoria.addItem(c);
        }
        seleccionar(filtroCategoria, antesFiltro == null || antesFiltro.getId() == 0 ? null : antesFiltro.getId(), TODAS);
        for (java.awt.event.ActionListener o : oyentes) {
            filtroCategoria.addActionListener(o);
        }
        elegirCategoria(antesForm == null || antesForm.getId() == 0 ? null : antesForm.getId());
        if (pagina != null) {
            refrescar();
        }
    }

    private void elegirCategoria(Long id) {
        seleccionar(comboCategoria, id, SIN_CATEGORIA);
    }

    private static void seleccionar(JComboBox<Categoria> combo, Long id, Categoria porDefecto) {
        for (int i = 0; i < combo.getItemCount(); i++) {
            Categoria c = combo.getItemAt(i);
            if (id != null && c.getId() == id) {
                combo.setSelectedIndex(i);
                return;
            }
        }
        combo.setSelectedItem(porDefecto);
    }

    // ------------------------------------------------------------------
    //  Foto
    // ------------------------------------------------------------------

    private void elegirFoto() {
        File f = Archivos.abrir(this, "Elegir la foto del producto", "jpg", "jpeg", "png");
        if (f != null) {
            fotoElegida = f;
            mostrarFoto(fotoElegida);
            decir(lblForm, "La foto se guarda al oprimir Guardar", true);
        }
    }

    /** Copia la foto a la carpeta del programa, asi no se pierde si la original se borra. */
    static String copiarFoto(File origen, String nombreBase) throws IOException {
        String nombre = origen.getName().toLowerCase();
        String ext = nombre.endsWith(".png") ? "png" : "jpg";
        File carpeta = new File(Ubicaciones.carpetaUsuario(), "fotos");
        carpeta.mkdirs();
        File destino = new File(carpeta, nombreBase + "." + ext);
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

    // ------------------------------------------------------------------
    //  Importar y exportar
    // ------------------------------------------------------------------

    /**
     * Importa en el mismo hilo de la pantalla: asi nadie busca en los arboles mientras se llenan.
     * Con listas de tienda (cientos o pocos miles de filas) toma menos de un segundo.
     */
    private void importar() {
        File archivo = Archivos.abrir(this, "Importar la lista de productos (en Excel: Guardar como > CSV)", "csv", "txt");
        if (archivo == null) {
            return;
        }
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        try {
            ImportadorProductos.Resultado r = ImportadorProductos.importar(archivo, cat);
            refrescarCategorias();
            refrescar();
            String resumen = r.creados + " creados, " + r.actualizados + " actualizados"
                    + (r.errores.isEmpty() ? "" : ", " + r.errores.size() + " con error");
            if (r.errores.isEmpty()) {
                Aviso.mostrar(this, Aviso.Tipo.BIEN, "Lista importada", resumen);
            } else {
                mostrarErrores(resumen, r.errores);
            }
        } catch (IllegalArgumentException | IOException e) {
            Alerta.mostrar(this, Alerta.Tipo.AVISO, "No se pudo importar", e.getMessage(), null);
        } finally {
            setCursor(Cursor.getDefaultCursor());
        }
    }

    private void mostrarErrores(String resumen, List<String> errores) {
        JPanel t = Capa.tarjeta(600, 24, 28);
        t.add(Tema.tituloPantalla("Filas con error"));
        t.add(Tema.parrafo(resumen + ". Las demás filas sí se guardaron. Corrija estas en el archivo y vuelva a importarlo.",
                540));
        t.add(Tema.espacio(10));
        JTextArea area = new JTextArea(String.join("\n", errores), 10, 40);
        area.setEditable(false);
        area.setFont(Tema.texto(13.5f));
        area.setForeground(Tema.GRIS_OSCURO);
        JScrollPane sc = new JScrollPane(area);
        sc.setAlignmentX(LEFT_ALIGNMENT);
        sc.setBorder(Tema.bordeLista());
        t.add(sc);
        t.add(Tema.espacio(14));
        Tema.Boton ok = Tema.botonPrimario("Entendido");
        t.add(Tema.fila(8, ok));
        Capa c = new Capa(this, t, true);
        ok.addActionListener(e -> c.cerrar());
        c.mostrar(ok);
    }

    /** Excel: un CSV que se abre con doble clic y que se puede volver a importar. */
    private void exportarExcel() {
        File f = Archivos.guardar(this, "Exportar la lista para Excel", "productos.csv", "csv");
        if (f == null) {
            return;
        }
        try {
            Files.write(f.toPath(), ListadoProductos.aCsv(filtrados, nombresCategoria).getBytes(StandardCharsets.UTF_8));
            Aviso.mostrar(this, Aviso.Tipo.BIEN, "Lista exportada", filtrados.size() + " productos en " + f.getName());
        } catch (IOException e) {
            Alerta.mostrar(this, Alerta.Tipo.AVISO, "No se pudo guardar el archivo", e.getMessage(), null);
        }
    }

    /** PDF: se imprime la lista filtrada completa; en Windows, "Microsoft Print to PDF" la guarda como PDF. */
    private void exportarPdf() {
        Modelo todo = new Modelo();
        todo.todos = true;
        JTable t = new JTable(todo);
        t.removeColumn(t.getColumnModel().getColumn(todo.colAcciones()));
        t.setFont(Tema.texto(10));
        t.setRowHeight(20);
        t.getTableHeader().setFont(Tema.textoFuerte(10));
        t.setDefaultRenderer(Object.class, new Celda());
        t.setSize(900, t.getPreferredSize().height); // la tabla no esta en pantalla: necesita un tamaño
        try {
            t.print(JTable.PrintMode.FIT_WIDTH, new java.text.MessageFormat("Listado de productos"),
                    new java.text.MessageFormat("DomiRuta · página {0}"));
        } catch (java.awt.print.PrinterException e) {
            Alerta.mostrar(this, Alerta.Tipo.AVISO, "No se pudo imprimir", e.getMessage(), null);
        }
    }

    // ------------------------------------------------------------------
    //  Textos
    // ------------------------------------------------------------------

    private static Tema.Parrafo mensaje() {
        Tema.Parrafo l = Tema.parrafo(" ", 560);
        l.setFont(Tema.texto(13));
        return l;
    }

    private static void decir(Tema.Parrafo l, String texto, boolean bien) {
        l.setForeground(bien ? Tema.EXITO_TEXTO : Tema.ERROR_TEXTO);
        l.setText(texto);
    }

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
        return ListadoProductos.cantidad(milesimas);
    }

    static String precio(Producto p) {
        return Tarifa.pesos(p.getPrecioVenta()) + (p.getUnidad() == Unidad.UNIDAD ? "" : " por " + p.getUnidad().getCorto());
    }

    /** Numero con punto de miles y sin signo de pesos (como en la tabla de referencia). */
    private static String plata(long v) {
        return (v < 0 ? "-" : "") + Tarifa.pesos(Math.abs(v)).replace("$", "");
    }

    // ------------------------------------------------------------------
    //  Modelo y dibujo de la tabla
    // ------------------------------------------------------------------

    private class Modelo extends AbstractTableModel {
        boolean todos; // para imprimir: todas las filas filtradas, no solo la pagina

        private List<Producto> filas() {
            if (todos) {
                return filtrados;
            }
            return pagina == null ? new ArrayList<>() : pagina.getProductos();
        }

        String[] columnas() {
            return verCompra
                    ? new String[]{"Item", "Código de barras", "Nombre", "Stock", "Valor de compra", "Valor de venta",
                        "Categoría", "Estado", "Acciones"}
                    : new String[]{"Item", "Código de barras", "Nombre", "Stock", "Valor de venta", "Categoría",
                        "Estado", "Acciones"};
        }

        int[] anchos() {
            return verCompra ? new int[]{60, 160, 360, 120, 130, 130, 150, 100, 100}
                    : new int[]{60, 160, 400, 120, 140, 160, 100, 100};
        }

        int colAcciones() {
            return columnas().length - 1;
        }

        @Override
        public int getRowCount() {
            return filas().size();
        }

        @Override
        public int getColumnCount() {
            return columnas().length;
        }

        @Override
        public String getColumnName(int c) {
            return columnas()[c];
        }

        @Override
        public Object getValueAt(int r, int c) {
            Producto p = filas().get(r);
            String col = columnas()[c];
            switch (col) {
                case "Item":
                    return String.valueOf((todos ? 1 : pagina.getDesde()) + r);
                case "Código de barras":
                    return p.getCodigo() == null ? "" : p.getCodigo();
                case "Nombre":
                    return p.getNombre();
                case "Stock":
                    return cantidad(p.getStock()) + " " + p.getUnidad().getCorto();
                case "Valor de compra":
                    return plata(p.getPrecioCompra());
                case "Valor de venta":
                    return plata(p.getPrecioVenta());
                case "Categoría":
                    return p.getCategoriaId() == null ? "—" : nombresCategoria.getOrDefault(p.getCategoriaId(), "—");
                case "Estado":
                    return p.isActivo() ? "Activo" : "Desactivado";
                default:
                    return "";
            }
        }

        Producto producto(int r) {
            return filas().get(r);
        }
    }

    /** Celda: centrado como en la tabla de referencia; el nombre a la izquierda y la plata a la derecha. */
    private class Celda extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foco, int r, int c) {
            super.getTableCellRendererComponent(t, v, sel, false, r, c);
            Modelo m = (Modelo) t.getModel();
            String col = m.getColumnName(t.convertColumnIndexToModel(c));
            Producto p = m.producto(r);
            setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10));
            setFont(Tema.texto(t.getFont().getSize2D()));
            setForeground(Tema.GRIS_OSCURO);
            setBackground(sel ? Tema.AZUL_SUAVE : (r % 2 == 1 ? new Color(0xFAFBFC) : Color.WHITE));
            switch (col) {
                case "Nombre":
                    setHorizontalAlignment(LEFT);
                    break;
                case "Stock":
                case "Valor de compra":
                case "Valor de venta":
                    setHorizontalAlignment(RIGHT);
                    break;
                default:
                    setHorizontalAlignment(CENTER);
            }
            if (col.equals("Stock") && p.isActivo()) {
                // estado como texto de color: rojo si quedo negativo, aviso si esta bajo el minimo
                if (p.getStock() < 0) {
                    setForeground(Tema.ERROR_TEXTO);
                } else if (p.estaBajoMinimo()) {
                    setForeground(Tema.AVISO_TEXTO);
                }
            }
            if (col.equals("Estado")) {
                setFont(Tema.textoFuerte(t.getFont().getSize2D() - 0.5f));
                setForeground(p.isActivo() ? Tema.EXITO_TEXTO : Tema.GRIS_TEXTO);
            }
            if (!p.isActivo() && !col.equals("Estado")) {
                setForeground(Tema.GRIS_TEXTO);
            }
            String tip = col.equals("Stock") && p.isActivo() && p.getStock() < 0 ? "Se vendió más de lo que había registrado"
                    : col.equals("Stock") && p.isActivo() && p.estaBajoMinimo()
                    ? "Bajo el mínimo (" + cantidad(p.getMinimo()) + " " + p.getUnidad().getCorto() + ")" : null;
            setToolTipText(tip);
            return this;
        }
    }

    /** Encabezado gris con letra fuerte, en dos lineas si no cabe. */
    private static class Encabezado extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foco, int r, int c) {
            JLabel l = new JLabel("<html><div style='text-align:center'>" + v + "</div></html>", CENTER);
            l.setFont(Tema.textoFuerte(13.5f));
            l.setForeground(Tema.GRIS_OSCURO);
            l.setOpaque(true);
            l.setBackground(new Color(0xE9ECF0));
            l.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 1, Tema.BORDE),
                    BorderFactory.createEmptyBorder(0, 6, 0, 6)));
            return l;
        }
    }

    /** Lapiz para editar y papelera para desactivar (o flecha para reactivar), con linea de 1.8 px. */
    private class Acciones extends DefaultTableCellRenderer {
        private Producto p;
        private boolean sel;
        private int fila;

        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foco, int r, int c) {
            this.p = ((Modelo) t.getModel()).producto(r);
            this.sel = sel;
            this.fila = r;
            setText("");
            setToolTipText("Editar  ·  " + (p.isActivo() ? "Desactivar" : "Reactivar"));
            return this;
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Tema.suavizar(g);
            g.setColor(sel ? Tema.AZUL_SUAVE : (fila % 2 == 1 ? new Color(0xFAFBFC) : Color.WHITE));
            g.fillRect(0, 0, getWidth(), getHeight());
            int cy = getHeight() / 2;
            int x1 = getWidth() / 2 - 22, x2 = getWidth() / 2 + 8;
            Icono.LAPIZ.dibujar(g, x1, cy - 9, 18, Tema.GRIS_OSCURO);
            if (p.isActivo()) {
                Icono.PAPELERA.dibujar(g, x2, cy - 9, 18, Tema.ERROR);
            } else {
                Icono.REACTIVAR.dibujar(g, x2, cy - 9, 18, Tema.AZUL);
            }
            g.dispose();
        }
    }

    /** Iconos de linea (1.8 px, sin cuadro detras), como pide el manual. */
    private enum Icono {
        LAPIZ, PAPELERA, REACTIVAR, CATEGORIAS, IMPORTAR, ACTUALIZAR, EXCEL, PDF, LUPA;

        void dibujar(Graphics2D g, int x, int y, int s, Color color) {
            g.setColor(color);
            g.setStroke(new BasicStroke(1.8f * s / 18f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            double k = s / 18.0;
            Path2D p = new Path2D.Double();
            switch (this) {
                case LAPIZ:
                    g.drawRect(x + (int) (1 * k), y + (int) (3 * k), (int) (13 * k), (int) (13 * k));
                    p.moveTo(x + 7 * k, y + 11 * k);
                    p.lineTo(x + 16 * k, y + 2 * k);
                    p.lineTo(x + 18 * k, y + 4 * k);
                    p.lineTo(x + 9 * k, y + 13 * k);
                    p.lineTo(x + 6.5 * k, y + 13.5 * k);
                    p.closePath();
                    g.setColor(Color.WHITE);
                    g.fill(p);
                    g.setColor(color);
                    g.draw(p);
                    return;
                case PAPELERA:
                    g.drawLine(x + (int) (1 * k), y + (int) (4 * k), x + (int) (17 * k), y + (int) (4 * k));
                    g.drawLine(x + (int) (6 * k), y + (int) (4 * k), x + (int) (7 * k), y + (int) (1 * k));
                    g.drawLine(x + (int) (7 * k), y + (int) (1 * k), x + (int) (11 * k), y + (int) (1 * k));
                    g.drawLine(x + (int) (11 * k), y + (int) (1 * k), x + (int) (12 * k), y + (int) (4 * k));
                    p.moveTo(x + 3 * k, y + 4 * k);
                    p.lineTo(x + 4.5 * k, y + 17 * k);
                    p.lineTo(x + 13.5 * k, y + 17 * k);
                    p.lineTo(x + 15 * k, y + 4 * k);
                    g.draw(p);
                    for (double lx : new double[]{7, 9, 11}) {
                        g.drawLine(x + (int) (lx * k), y + (int) (7 * k), x + (int) (lx * k), y + (int) (14 * k));
                    }
                    return;
                case REACTIVAR:
                case ACTUALIZAR:
                    g.drawArc(x + (int) (2 * k), y + (int) (2 * k), (int) (14 * k), (int) (14 * k), 40, 280);
                    p.moveTo(x + 15 * k, y + 1 * k);
                    p.lineTo(x + 15.5 * k, y + 5.5 * k);
                    p.lineTo(x + 11 * k, y + 5.5 * k);
                    g.draw(p);
                    return;
                case CATEGORIAS:
                    for (int i = 0; i < 2; i++) {
                        for (int j = 0; j < 2; j++) {
                            g.drawRoundRect(x + (int) ((1 + i * 9) * k), y + (int) ((1 + j * 9) * k), (int) (7 * k),
                                    (int) (7 * k), 3, 3);
                        }
                    }
                    return;
                case IMPORTAR:
                    p.moveTo(x + 5 * k, y + 15 * k);
                    p.curveTo(x + 0 * k, y + 15 * k, x + 0 * k, y + 8 * k, x + 5 * k, y + 8 * k);
                    p.curveTo(x + 5 * k, y + 2 * k, x + 14 * k, y + 2 * k, x + 14 * k, y + 7 * k);
                    p.curveTo(x + 19 * k, y + 7 * k, x + 19 * k, y + 15 * k, x + 13 * k, y + 15 * k);
                    g.draw(p);
                    g.drawLine(x + (int) (9 * k), y + (int) (17 * k), x + (int) (9 * k), y + (int) (9 * k));
                    g.drawLine(x + (int) (6 * k), y + (int) (12 * k), x + (int) (9 * k), y + (int) (9 * k));
                    g.drawLine(x + (int) (12 * k), y + (int) (12 * k), x + (int) (9 * k), y + (int) (9 * k));
                    return;
                case EXCEL:
                case PDF:
                    p.moveTo(x + 3 * k, y + 1 * k);
                    p.lineTo(x + 11 * k, y + 1 * k);
                    p.lineTo(x + 15 * k, y + 5 * k);
                    p.lineTo(x + 15 * k, y + 17 * k);
                    p.lineTo(x + 3 * k, y + 17 * k);
                    p.closePath();
                    g.draw(p);
                    g.drawLine(x + (int) (11 * k), y + (int) (1 * k), x + (int) (11 * k), y + (int) (5 * k));
                    g.drawLine(x + (int) (11 * k), y + (int) (5 * k), x + (int) (15 * k), y + (int) (5 * k));
                    if (this == EXCEL) {
                        for (double ly : new double[]{9, 12, 15}) {
                            g.drawLine(x + (int) (6 * k), y + (int) (ly * k), x + (int) (12 * k), y + (int) (ly * k));
                        }
                        g.drawLine(x + (int) (9 * k), y + (int) (8 * k), x + (int) (9 * k), y + (int) (15 * k));
                    } else {
                        g.drawLine(x + (int) (6 * k), y + (int) (10 * k), x + (int) (12 * k), y + (int) (10 * k));
                        g.drawLine(x + (int) (6 * k), y + (int) (13 * k), x + (int) (10 * k), y + (int) (13 * k));
                    }
                    return;
                default: // LUPA
                    g.drawOval(x + (int) (1 * k), y + (int) (1 * k), (int) (12 * k), (int) (12 * k));
                    g.drawLine(x + (int) (11 * k), y + (int) (11 * k), x + (int) (17 * k), y + (int) (17 * k));
            }
        }
    }

    /** Lupa grande en un circulo suave para el estado vacio. */
    private static class IconoGrande extends JComponent {
        IconoGrande() {
            setPreferredSize(new Dimension(76, 76));
            setMaximumSize(new Dimension(76, 76));
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Tema.suavizar(g);
            g.setColor(Tema.AZUL_SUAVE);
            g.fillOval(0, 0, 76, 76);
            Icono.LUPA.dibujar(g, 22, 22, 32, Tema.AZUL);
            g.dispose();
        }
    }

    /** Boton de icono con su texto debajo (Excel, PDF). */
    private static class BotonIcono extends JButton {
        BotonIcono(String texto, Icono icono, String ayuda, Runnable accion) {
            super(texto);
            Color color = icono == Icono.EXCEL ? Tema.EXITO_TEXTO : Tema.ERROR_TEXTO;
            setIcon(new javax.swing.Icon() {
                public void paintIcon(Component c, Graphics g0, int x, int y) {
                    Graphics2D g = (Graphics2D) g0.create();
                    Tema.suavizar(g);
                    icono.dibujar(g, x, y, 24, color);
                    g.dispose();
                }
                public int getIconWidth() { return 26; }
                public int getIconHeight() { return 26; }
            });
            setVerticalTextPosition(BOTTOM);
            setHorizontalTextPosition(CENTER);
            setFont(Tema.texto(11.5f));
            setForeground(Tema.GRIS_OSCURO);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(true);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText(ayuda);
            getAccessibleContext().setAccessibleName(ayuda);
            setMargin(new Insets(0, 4, 0, 4));
            addActionListener(e -> accion.run());
        }
    }

    /** Accion de la barra: texto arriba (en una o dos lineas) e icono azul abajo, como en la referencia. */
    private static class AccionIcono extends JButton {
        AccionIcono(String texto, Icono icono, String ayuda, Runnable accion) {
            super("<html><div style='text-align:center'>" + texto.replace("\n", "<br>") + "</div></html>");
            setIcon(new javax.swing.Icon() {
                public void paintIcon(Component c, Graphics g0, int x, int y) {
                    Graphics2D g = (Graphics2D) g0.create();
                    Tema.suavizar(g);
                    icono.dibujar(g, x + 2, y + 2, 30, Tema.AZUL);
                    g.dispose();
                }
                public int getIconWidth() { return 34; }
                public int getIconHeight() { return 34; }
            });
            setVerticalTextPosition(TOP);
            setHorizontalTextPosition(CENTER);
            setIconTextGap(4);
            setFont(Tema.texto(13));
            setForeground(Tema.GRIS_TEXTO);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText(ayuda);
            getAccessibleContext().setAccessibleName(texto.replace("\n", " "));
            addActionListener(e -> accion.run());
        }
    }

    /** Boton de pagina: cuadro pequeño de 2 px de radio; la actual en azul. */
    private static class BotonPagina extends JButton {
        private final boolean actual;

        BotonPagina(String texto, String ayuda, boolean activo, boolean actual, Runnable accion) {
            super(texto);
            this.actual = actual;
            setEnabled(activo);
            setFont(Tema.texto(14));
            setForeground(actual ? Color.WHITE : activo ? Tema.GRIS_OSCURO : Tema.BORDE_CONTROL);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setPreferredSize(new Dimension(Math.max(36, getPreferredSize().width), 34));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText(ayuda);
            getAccessibleContext().setAccessibleName(ayuda);
            addActionListener(e -> accion.run());
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Tema.suavizar(g);
            if (actual) {
                g.setColor(Tema.AZUL);
                g.fillRoundRect(0, 0, getWidth(), getHeight(), 4, 4);
            } else if (getModel().isRollover() && isEnabled()) {
                g.setColor(Tema.AZUL_SUAVE);
                g.fillRoundRect(0, 0, getWidth(), getHeight(), 4, 4);
            }
            if (isFocusOwner()) {
                g.setColor(actual ? Tema.AZUL_PROFUNDO : Tema.AZUL);
                g.setStroke(new BasicStroke(2f));
                g.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 4, 4);
            }
            g.dispose();
            super.paintComponent(g0);
        }
    }
}
