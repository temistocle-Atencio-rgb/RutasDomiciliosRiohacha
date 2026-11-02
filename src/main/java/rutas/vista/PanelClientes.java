package rutas.vista;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import rutas.logica.BaseTienda;
import rutas.logica.ListadoProductos;
import rutas.logica.Tarifa;
import rutas.logica.Ubicaciones;
import rutas.logica.Xlsx;
import rutas.modelo.Cliente;
import rutas.modelo.Geo;

/**
 * Clientes, como la referencia: «Por página» y Exportar arriba; Buscar, «Importar clientes» y
 * «Nuevo cliente»; la tabla (Item, Nombre, Tipo de doc, Número Nit/Doc, Teléfono, Correo, Dirección,
 * Municipio, Fía hasta, Estado, Acciones) y el paginador. Ajustado a la tienda: «Ordenar por» los
 * más frecuentes, y las columnas Compras y Última compra (salen de las ventas de la caja).
 * Los clientes estan en un arbol AVL por nombre: «Nombre (A–Z)» es su recorrido en orden y el
 * buscador usa su busqueda por prefijo.
 */
public class PanelClientes extends JPanel {

    private static final Integer[] POR_PAGINA = {10, 25, 50, 100};
    private static final String[] ORDEN = {"Nombre (A–Z)", "Más frecuentes", "Última compra"};
    private static final String[] MOSTRAR = {"Todos", "Activos", "Inactivos"};
    private static final String[] COLUMNAS = {"Item", "Nombre del cliente", "Tipo de doc", "Número Nit/Doc",
        "Teléfono", "Correo electrónico", "Dirección", "Municipio", "Fía hasta", "Compras", "Última compra",
        "Estado", "Acciones"};
    private static final int COL_NOMBRE = 1, COL_FIA = 8, COL_COMPRAS = 9, COL_ESTADO = 11, COL_ACCIONES = 12;
    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final Aplicacion app;
    private final JComboBox<Integer> comboPorPagina = new JComboBox<>(POR_PAGINA);
    private final JTextField campoBuscar = Tema.campo("Nombre, documento o celular");
    private final JComboBox<String> comboOrden = new JComboBox<>(ORDEN);
    private final JComboBox<String> comboMostrar = new JComboBox<>(MOSTRAR);
    private final Modelo modelo = new Modelo();
    private final JTable tabla = new JTable(modelo) {
        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            if (getRowCount() > 0) {
                return;
            }
            Graphics2D g = (Graphics2D) g0.create();
            Tema.suavizar(g);
            int alto = 64;
            g.setColor(Tema.GRIS_CLARO);
            g.fillRect(0, 0, getWidth(), alto);
            g.setColor(Tema.BORDE);
            g.drawLine(0, alto, getWidth(), alto);
            g.setFont(Tema.texto(19));
            g.setColor(Tema.GRIS_TEXTO);
            String t = hayClientes ? "Ningún cliente coincide con la búsqueda" : "No hay clientes registrados";
            java.awt.FontMetrics fm = g.getFontMetrics();
            g.drawString(t, (getWidth() - fm.stringWidth(t)) / 2, (alto + fm.getAscent() - fm.getDescent()) / 2);
            g.dispose();
        }
    };
    private final JLabel lblPaginas = new JLabel();
    private final JLabel lblTotal = new JLabel();
    private final JPanel botonesPagina = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 0));
    private final List<javax.swing.table.TableColumn> columnas = new ArrayList<>();
    private List<Cliente> clientes = new ArrayList<>();
    private List<Cliente> pagina = new ArrayList<>();
    private Map<String, BaseTienda.Frecuencia> frecuencias = new HashMap<>();
    private boolean hayClientes;
    private int numeroPagina = 1;

    public PanelClientes(Aplicacion app) {
        super(new BorderLayout());
        this.app = app;
        setBackground(Tema.GRIS_CLARO);
        setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));
        putClientProperty(Capa.FOCO_AL_CERRAR, campoBuscar);

        Tema.Tarjeta tarjeta = new Tema.Tarjeta();
        tarjeta.setLayout(new BorderLayout(0, 12));
        JPanel arriba = new JPanel();
        arriba.setOpaque(false);
        arriba.setLayout(new BoxLayout(arriba, BoxLayout.Y_AXIS));
        JLabel titulo = new JLabel("Clientes");
        titulo.setFont(Tema.titulo(24));
        titulo.setForeground(Tema.GRIS_OSCURO);
        titulo.setAlignmentX(LEFT_ALIGNMENT);
        arriba.add(titulo);
        arriba.add(Box.createVerticalStrut(10));
        arriba.add(encabezado());
        arriba.add(Box.createVerticalStrut(12));
        arriba.add(barra());
        tarjeta.add(arriba, BorderLayout.NORTH);
        tarjeta.add(zonaTabla(), BorderLayout.CENTER);
        tarjeta.add(pie(), BorderLayout.SOUTH);
        add(tarjeta, BorderLayout.CENTER);

        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("control N"), "nuevo");
        getActionMap().put("nuevo", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (isShowing() && !Capa.hayAbierta(PanelClientes.this)) {
                    nuevo();
                }
            }
        });
        addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentShown(java.awt.event.ComponentEvent e) {
                refrescar(); // pudo comprar alguien en la caja
            }
        });
        app.alCambiar(() -> {
            if (isShowing()) {
                refrescar();
            }
        });
        refrescar();
    }

    // ------------------------------------------------------------------
    //  Partes de la pantalla
    // ------------------------------------------------------------------

    /** «Por página» a la izquierda y Exportar (Excel, PDF) a la derecha. */
    private JComponent encabezado() {
        JPanel h = new JPanel(new BorderLayout());
        h.setOpaque(false);
        h.setAlignmentX(LEFT_ALIGNMENT);
        Tema.estiloCombo(comboPorPagina, String::valueOf);
        comboPorPagina.setPreferredSize(new Dimension(110, 36));
        comboPorPagina.getAccessibleContext().setAccessibleName("Clientes por página");
        comboPorPagina.addActionListener(e -> {
            numeroPagina = 1;
            mostrarPagina();
        });
        h.add(PanelProductos.columna("Por página", comboPorPagina), BorderLayout.WEST);
        JPanel botones = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        botones.setOpaque(false);
        botones.add(new PanelProductos.BotonIcono("Excel", PanelProductos.Icono.EXCEL, "Exportar estos clientes a Excel",
                this::exportarExcel));
        botones.add(new PanelProductos.BotonIcono("PDF", PanelProductos.Icono.PDF,
                "Ver, imprimir o guardar estos clientes como PDF", this::exportarPdf));
        h.add(PanelProductos.columna("Exportar", botones), BorderLayout.EAST);
        h.setMaximumSize(new Dimension(Integer.MAX_VALUE, h.getPreferredSize().height));
        return h;
    }

    /** Buscar, Ordenar por y Mostrar; a la derecha «Importar clientes» y «Nuevo cliente». */
    private JComponent barra() {
        JPanel f = new JPanel(new GridBagLayout());
        f.setOpaque(false);
        f.setAlignmentX(LEFT_ALIGNMENT);
        GridBagConstraints g = new GridBagConstraints();
        g.gridy = 0;
        g.anchor = GridBagConstraints.SOUTHWEST;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.insets = new Insets(0, 0, 0, 14);

        campoBuscar.getDocument().addDocumentListener(PanelProductos.alCambiar(() -> {
            numeroPagina = 1;
            refrescar();
        }));
        Tema.estiloCombo(comboOrden, String::valueOf);
        Tema.estiloCombo(comboMostrar, String::valueOf);
        comboOrden.setToolTipText("«Más frecuentes»: los que más compran en la caja, primero");
        comboOrden.addActionListener(e -> {
            numeroPagina = 1;
            refrescar();
        });
        comboMostrar.addActionListener(e -> {
            numeroPagina = 1;
            refrescar();
        });
        g.gridx = 0;
        g.weightx = 0.5;
        f.add(etiquetado("Buscar", campoBuscar, 260), g);
        g.gridx = 1;
        g.weightx = 0.25;
        f.add(etiquetado("Ordenar por", comboOrden, 170), g);
        g.gridx = 2;
        g.weightx = 0.25;
        f.add(etiquetado("Mostrar", comboMostrar, 130), g);

        Tema.Boton importar = Tema.botonSecundario("Importar clientes");
        importar.setToolTipText("Subir una lista de clientes desde Excel");
        importar.addActionListener(e -> abrirImportar());
        Tema.Boton nuevo = Tema.botonAccion("Nuevo cliente");
        nuevo.setToolTipText("Agregar un cliente (Ctrl+N)");
        nuevo.addActionListener(e -> nuevo());
        g.gridx = 3;
        g.weightx = 0;
        g.fill = GridBagConstraints.NONE;
        g.insets = new Insets(0, 0, 0, 8);
        f.add(importar, g);
        g.gridx = 4;
        g.insets = new Insets(0, 0, 0, 0);
        f.add(nuevo, g);
        f.setMaximumSize(new Dimension(Integer.MAX_VALUE, f.getPreferredSize().height));
        return f;
    }

    private static JComponent etiquetado(String texto, JComponent c, int ancho) {
        JPanel p = new JPanel(new BorderLayout(0, 5));
        p.setOpaque(false);
        JLabel l = new JLabel(texto);
        l.setFont(Tema.textoFuerte(13.5f));
        l.setForeground(Tema.GRIS_OSCURO);
        l.setLabelFor(c);
        p.add(l, BorderLayout.NORTH);
        c.setPreferredSize(new Dimension(ancho, 38));
        c.getAccessibleContext().setAccessibleName(texto);
        p.add(c, BorderLayout.CENTER);
        return p;
    }

    private JComponent zonaTabla() {
        tabla.setRowHeight(44);
        tabla.setShowVerticalLines(true);
        tabla.setShowHorizontalLines(true);
        tabla.setGridColor(Tema.BORDE);
        tabla.setIntercellSpacing(new Dimension(1, 1));
        tabla.setFillsViewportHeight(true);
        tabla.setBackground(Color.WHITE);
        tabla.setSelectionBackground(Tema.AZUL_SUAVE);
        tabla.setSelectionForeground(Tema.GRIS_OSCURO);
        tabla.setFont(Tema.texto(14));
        tabla.getAccessibleContext().setAccessibleName("Tabla de clientes");
        tabla.setDefaultRenderer(Object.class, new Celda());
        tabla.getTableHeader().setReorderingAllowed(false);
        // alto segun la letra real: dos renglones + margen (asi no se corta con la escala de Windows)
        int renglon = tabla.getFontMetrics(Tema.textoFuerte(13.5f)).getHeight();
        tabla.getTableHeader().setPreferredSize(new Dimension(100, Math.max(60, renglon * 2 + 24)));
        tabla.getTableHeader().setDefaultRenderer(new PanelProductos.Encabezado());
        int[] anchos = {46, 190, 62, 118, 104, 180, 160, 110, 92, 76, 96, 80, 104};
        for (int i = 0; i < anchos.length; i++) {
            tabla.getColumnModel().getColumn(i).setPreferredWidth(anchos[i]);
        }
        tabla.getColumnModel().getColumn(0).setMaxWidth(56);
        tabla.getColumnModel().getColumn(COL_ACCIONES).setCellRenderer(new Acciones());
        tabla.getColumnModel().getColumn(COL_ACCIONES).setMinWidth(100);
        tabla.getColumnModel().getColumn(COL_ESTADO).setMinWidth(78);
        tabla.getColumnModel().getColumn(COL_COMPRAS).setMinWidth(82);
        tabla.getColumnModel().getColumn(COL_NOMBRE).setMinWidth(150);
        tabla.getColumnModel().getColumn(2).setMinWidth(74);   // «Tipo de doc» en dos renglones completos
        tabla.getColumnModel().getColumn(3).setMinWidth(104);
        tabla.getColumnModel().getColumn(4).setMinWidth(112);  // un celular de 10 numeros completo
        for (int i = 0; i < COLUMNAS.length; i++) {
            columnas.add(tabla.getColumnModel().getColumn(i));
        }
        tabla.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int f = tabla.rowAtPoint(e.getPoint());
                int c = modeloDe(tabla.columnAtPoint(e.getPoint()));
                if (f < 0 || f >= pagina.size()) {
                    return;
                }
                if (c == COL_ACCIONES) {
                    java.awt.Rectangle r = tabla.getCellRect(f, tabla.columnAtPoint(e.getPoint()), false);
                    menuAcciones(pagina.get(f), r.x, r.y + r.height);
                } else if (e.getClickCount() == 2) {
                    editar(pagina.get(f));
                }
            }
        });
        tabla.addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                tabla.setCursor(modeloDe(tabla.columnAtPoint(e.getPoint())) == COL_ACCIONES
                        ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
            }
        });
        tabla.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ENTER"), "acciones");
        tabla.getActionMap().put("acciones", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                int f = tabla.getSelectedRow();
                if (f >= 0 && f < pagina.size()) {
                    java.awt.Rectangle r = tabla.getCellRect(f, tabla.convertColumnIndexToView(COL_ACCIONES), false);
                    menuAcciones(pagina.get(f), r.x, r.y + r.height);
                }
            }
        });
        JScrollPane sc = new JScrollPane(tabla);
        sc.setBorder(BorderFactory.createLineBorder(Tema.BORDE));
        sc.getViewport().setBackground(Color.WHITE);
        sc.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                acomodarColumnas(sc.getWidth());
            }
        });
        return sc;
    }

    /**
     * Con la ventana angosta (portatil de 1366) no caben las 13 columnas: se esconden primero
     * Municipio y Correo, y si es mas angosta Tipo de doc y Última compra. Lo escondido se ve en
     * «Editar», en «Copiar datos» y en el Excel.
     */
    private void acomodarColumnas(int ancho) {
        Set<Integer> esconder = new HashSet<>();
        if (ancho < 1300) {
            esconder.add(7);
            esconder.add(5);
        }
        if (ancho < 1000) {
            esconder.add(2);
            esconder.add(10);
        }
        javax.swing.table.TableColumnModel cm = tabla.getColumnModel();
        boolean igual = cm.getColumnCount() == COLUMNAS.length - esconder.size();
        for (int i = 0; igual && i < cm.getColumnCount(); i++) {
            igual = !esconder.contains(cm.getColumn(i).getModelIndex());
        }
        if (igual) {
            return;
        }
        while (cm.getColumnCount() > 0) {
            cm.removeColumn(cm.getColumn(0));
        }
        for (javax.swing.table.TableColumn col : columnas) {
            if (!esconder.contains(col.getModelIndex())) {
                cm.addColumn(col);
            }
        }
    }

    private int modeloDe(int vista) {
        return vista < 0 ? -1 : tabla.convertColumnIndexToModel(vista);
    }

    private JComponent pie() {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        for (JLabel l : new JLabel[]{lblPaginas, lblTotal}) {
            l.setFont(Tema.texto(14));
            l.setForeground(Tema.GRIS_OSCURO);
            l.setAlignmentX(LEFT_ALIGNMENT);
        }
        botonesPagina.setOpaque(false);
        botonesPagina.setAlignmentX(LEFT_ALIGNMENT);
        p.add(botonesPagina);
        p.add(Box.createVerticalStrut(6));
        p.add(lblPaginas);
        p.add(Box.createVerticalStrut(4));
        p.add(lblTotal);
        return p;
    }

    // ------------------------------------------------------------------
    //  Datos
    // ------------------------------------------------------------------

    /** Vuelve a armar la lista: del arbol AVL, con el buscador, el filtro y el orden escogidos. */
    void refrescar() {
        frecuencias = new HashMap<>();
        if (app.getTienda() != null) {
            try {
                frecuencias = app.getTienda().comprasPorCliente();
            } catch (SQLException e) {
                Ubicaciones.registrarError(e); // sin el conteo la lista igual sirve
            }
        }
        List<Cliente> todos = app.getClientes().enOrden(); // recorrido en orden del AVL: ya va de la A a la Z
        hayClientes = !todos.isEmpty();
        String texto = campoBuscar.getText().trim();
        Set<Cliente> porPrefijo = new HashSet<>();
        if (!texto.isEmpty() && !texto.replaceAll("[\\s.-]", "").matches("\\d+")) {
            porPrefijo.addAll(app.getClientes().buscarPorPrefijo(Geo.normalizar(texto))); // busqueda en el AVL
        }
        String mostrar = (String) comboMostrar.getSelectedItem();
        String clave = Geo.normalizar(texto);
        clientes = new ArrayList<>();
        for (Cliente c : todos) {
            if ("Activos".equals(mostrar) && !c.isActivo() || "Inactivos".equals(mostrar) && c.isActivo()) {
                continue;
            }
            if (!texto.isEmpty() && !porPrefijo.contains(c) && !FormularioCliente.coincide(c, texto)
                    && !Geo.normalizar(c.getCorreo()).startsWith(clave)) {
                continue;
            }
            clientes.add(c);
        }
        String orden = (String) comboOrden.getSelectedItem();
        if (ORDEN[1].equals(orden)) {
            clientes.sort(Comparator.comparingInt((Cliente c) -> -compras(c))
                    .thenComparingLong(c -> -total(c)));
        } else if (ORDEN[2].equals(orden)) {
            clientes.sort((a, b) -> {
                LocalDateTime x = ultima(a), y = ultima(b);
                if (x == null || y == null) {
                    return x == y ? 0 : x == null ? 1 : -1; // los que nunca han comprado, al final
                }
                return y.compareTo(x);
            });
        }
        mostrarPagina();
    }

    private BaseTienda.Frecuencia frecuencia(Cliente c) {
        return frecuencias.get(Geo.normalizar(c.getNombre()));
    }

    private int compras(Cliente c) {
        BaseTienda.Frecuencia f = frecuencia(c);
        return f == null ? 0 : f.compras;
    }

    private long total(Cliente c) {
        BaseTienda.Frecuencia f = frecuencia(c);
        return f == null ? 0 : f.total;
    }

    private LocalDateTime ultima(Cliente c) {
        BaseTienda.Frecuencia f = frecuencia(c);
        return f == null ? null : f.ultima;
    }

    private void mostrarPagina() {
        int porPagina = (Integer) comboPorPagina.getSelectedItem();
        int totalPaginas = Math.max(1, (clientes.size() + porPagina - 1) / porPagina);
        numeroPagina = Math.max(1, Math.min(numeroPagina, totalPaginas));
        int desde = (numeroPagina - 1) * porPagina;
        pagina = new ArrayList<>(clientes.subList(Math.min(desde, clientes.size()),
                Math.min(desde + porPagina, clientes.size())));
        modelo.fireTableDataChanged();
        tabla.repaint();
        int activos = 0;
        for (Cliente c : app.getClientes().enOrden()) {
            if (c.isActivo()) {
                activos++;
            }
        }
        lblPaginas.setText("Páginas: " + totalPaginas);
        lblTotal.setText("Total: " + clientes.size() + (hayClientes ? "  ·  " + activos
                + (activos == 1 ? " activo" : " activos") + " de " + app.getClientes().enOrden().size() : ""));
        botonesPagina.removeAll();
        if (totalPaginas > 1) {
            botonesPagina.add(new PanelProductos.BotonPagina("«", "Página anterior", numeroPagina > 1, false,
                    () -> irA(numeroPagina - 1)));
            for (int n : ListadoProductos.botones(numeroPagina, totalPaginas)) {
                if (n < 0) {
                    JLabel puntos = new JLabel("…");
                    puntos.setFont(Tema.texto(14));
                    puntos.setForeground(Tema.GRIS_TEXTO);
                    botonesPagina.add(puntos);
                } else {
                    final int destino = n;
                    botonesPagina.add(new PanelProductos.BotonPagina(String.valueOf(n), "Página " + n, true,
                            n == numeroPagina, () -> irA(destino)));
                }
            }
            botonesPagina.add(new PanelProductos.BotonPagina("»", "Página siguiente", numeroPagina < totalPaginas,
                    false, () -> irA(numeroPagina + 1)));
        }
        botonesPagina.revalidate();
        botonesPagina.repaint();
    }

    private void irA(int n) {
        numeroPagina = n;
        mostrarPagina();
    }

    // ------------------------------------------------------------------
    //  Acciones
    // ------------------------------------------------------------------

    private void nuevo() {
        FormularioCliente.abrir(this, app, null, "", c -> refrescar());
    }

    private void editar(Cliente c) {
        FormularioCliente.abrir(this, app, c, "", x -> refrescar());
    }

    private void menuAcciones(Cliente c, int x, int y) {
        JPopupMenu m = new JPopupMenu();
        agregar(m, "Editar", () -> editar(c));
        VentanaPrincipal v = app.getVentana();
        if (v != null && c.isActivo()) {
            agregar(m, "Agregar a la ruta", () -> v.agregarClienteARuta(c));
        }
        if (v != null && c.tieneUbicacion()) {
            agregar(m, "Ver en el mapa", () -> v.verClienteEnMapa(c));
        }
        if (app.getTienda() != null) {
            agregar(m, "Ver sus compras", () -> verCompras(c));
        }
        agregar(m, "Copiar datos", () -> copiar(c));
        agregar(m, c.isActivo() ? "Desactivar" : "Activar", () -> cambiarActivo(c));
        m.addSeparator();
        JMenuItem borrar = agregar(m, "Eliminar", () -> eliminar(c));
        borrar.setForeground(Tema.ERROR_TEXTO);
        m.show(tabla, x, y);
    }

    private static JMenuItem agregar(JPopupMenu m, String texto, Runnable r) {
        JMenuItem it = new JMenuItem(texto);
        it.setFont(Tema.texto(13.5f));
        it.addActionListener(e -> r.run());
        m.add(it);
        return it;
    }

    private void cambiarActivo(Cliente c) {
        Runnable hacer = () -> {
            try {
                Cliente actual = app.buscarCliente(c.getNombre()); // por si lo cambiaron mientras tanto
                if (actual == null) {
                    refrescar();
                    return;
                }
                app.guardarCliente(actual.conActivo(!c.isActivo()));
                Aviso.mostrar(this, Aviso.Tipo.BIEN, c.isActivo() ? "Cliente desactivado" : "Cliente activado",
                        c.getNombre());
            } catch (java.io.IOException e) {
                Alerta.mostrar(this, Alerta.Tipo.AVISO, "No se pudo cambiar", e.getMessage(), null);
            }
            refrescar();
        };
        if (c.isActivo()) {
            Alerta.confirmar(this, "¿Desactivar a " + c.getNombre() + "?",
                    "Ya no sale para escoger en la caja ni en Ruta. Sus datos y sus compras quedan guardados.",
                    "Sí, desactivar", "No", hacer);
        } else {
            hacer.run();
        }
    }

    private void eliminar(Cliente c) {
        int n = compras(c);
        Alerta.confirmar(this, "¿Eliminar a " + c.getNombre() + "?",
                n > 0 ? "Tiene " + n + (n == 1 ? " compra" : " compras") + ". Las ventas no se borran, pero el cliente"
                        + " sí. Si solo no quiere verlo, mejor desactívelo."
                        : "Se borran sus datos. Esto no se puede deshacer.",
                "Sí, eliminar", "No", () -> {
                    try {
                        app.eliminarCliente(c.getNombre());
                        refrescar();
                        Aviso.mostrar(this, Aviso.Tipo.BIEN, "Cliente eliminado", c.getNombre());
                    } catch (java.io.IOException e) {
                        Alerta.mostrar(this, Alerta.Tipo.AVISO, "No se pudo eliminar", e.getMessage(), null);
                    }
                });
    }

    private void copiar(Cliente c) {
        StringBuilder b = new StringBuilder(c.getNombre());
        for (String s : new String[]{c.documentoCorto(), c.getTelefono(), c.getCorreo(), c.getDireccion(),
            c.getMunicipio()}) {
            if (!s.isBlank()) {
                b.append('\n').append(s);
            }
        }
        try {
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(b.toString()), null);
            Aviso.mostrar(this, Aviso.Tipo.BIEN, "Datos copiados", "Péguelos donde los necesite (Ctrl+V)");
        } catch (IllegalStateException e) {
            Aviso.mostrar(this, Aviso.Tipo.ERROR, "No se pudo copiar", "Inténtelo otra vez");
        }
    }

    /** Las ultimas compras del cliente en la caja: numero, fecha, productos y total. */
    private void verCompras(Cliente c) {
        List<BaseTienda.VentaCliente> ventas;
        try {
            ventas = app.getTienda().ventasDeCliente(c.getNombre(), 50);
        } catch (SQLException e) {
            Ubicaciones.registrarError(e);
            Alerta.mostrar(this, Alerta.Tipo.AVISO, "No se pudieron leer las compras", e.getMessage(), null);
            return;
        }
        Capa[] capa = new Capa[1];
        JPanel modal = FormularioProveedor.modal("Compras de " + c.getNombre(), 660, () -> capa[0].cerrar());
        JPanel t = (JPanel) ((BorderLayout) modal.getLayout()).getLayoutComponent(BorderLayout.CENTER);
        BaseTienda.Frecuencia f = frecuencia(c);
        String resumen = f == null || f.compras == 0 ? "Todavía no tiene compras a su nombre en la caja."
                : f.compras + (f.compras == 1 ? " compra" : " compras") + " por " + Tarifa.pesos(f.total)
                + "  ·  la última el " + f.ultima.format(FECHA);
        JLabel r = new JLabel(resumen);
        r.setFont(Tema.textoFuerte(14));
        r.setForeground(Tema.GRIS_OSCURO);
        r.setAlignmentX(LEFT_ALIGNMENT);
        t.add(r);
        if (!ventas.isEmpty()) {
            t.add(Tema.espacio(10));
            String[] cols = {"Venta No.", "Fecha", "Productos", "Total"};
            Object[][] datos = new Object[ventas.size()][];
            for (int i = 0; i < ventas.size(); i++) {
                BaseTienda.VentaCliente v = ventas.get(i);
                datos[i] = new Object[]{Comprobante.numeroTexto(v.numero),
                    v.pagada.format(FECHA) + "  " + v.pagada.toLocalTime().format(rutas.modelo.Ruta.FORMATO_HORA), v.productos,
                    Tarifa.pesos(v.total)};
            }
            JTable tv = new JTable(datos, cols) {
                @Override
                public boolean isCellEditable(int row, int column) {
                    return false;
                }
            };
            tv.setRowHeight(36);
            tv.setFont(Tema.texto(13.5f));
            tv.setGridColor(Tema.BORDE);
            tv.getTableHeader().setDefaultRenderer(new PanelProductos.Encabezado());
            tv.getTableHeader().setPreferredSize(new Dimension(100, 40));
            tv.getTableHeader().setReorderingAllowed(false);
            DefaultTableCellRenderer centro = new DefaultTableCellRenderer();
            centro.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
            tv.setDefaultRenderer(Object.class, centro);
            tv.getAccessibleContext().setAccessibleName("Compras del cliente");
            int[] an = {90, 190, 90, 120};
            for (int i = 0; i < an.length; i++) {
                tv.getColumnModel().getColumn(i).setPreferredWidth(an[i]);
            }
            JScrollPane sc = new JScrollPane(tv);
            sc.setBorder(BorderFactory.createLineBorder(Tema.BORDE));
            sc.setAlignmentX(LEFT_ALIGNMENT);
            sc.setPreferredSize(new Dimension(600, Math.min(8, ventas.size()) * 36 + 44));
            t.add(sc);
            if (ventas.size() == 50) {
                JLabel mas = new JLabel("Se muestran las últimas 50.");
                mas.setFont(Tema.texto(12.5f));
                mas.setForeground(Tema.GRIS_TEXTO);
                mas.setAlignmentX(LEFT_ALIGNMENT);
                t.add(mas);
            }
        }
        t.add(Tema.espacio(12));
        Tema.Boton cerrar = Tema.botonSecundario("Cerrar");
        JPanel pie = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        pie.setOpaque(false);
        pie.setAlignmentX(LEFT_ALIGNMENT);
        pie.add(cerrar);
        t.add(pie);
        capa[0] = new Capa(this, modal, true);
        cerrar.addActionListener(e -> capa[0].cerrar());
        capa[0].mostrar(cerrar);
    }

    // ------------------------------------------------------------------
    //  Importar
    // ------------------------------------------------------------------

    private void abrirImportar() {
        if (Capa.hayAbierta(this)) {
            return;
        }
        Capa[] capa = new Capa[1];
        JPanel modal = FormularioProveedor.modal("Importar clientes", 840, () -> capa[0].cerrar());
        JPanel t = (JPanel) ((BorderLayout) modal.getLayout()).getLayoutComponent(BorderLayout.CENTER);
        JPanel cuerpo = new JPanel(new java.awt.GridLayout(1, 2, 24, 0));
        cuerpo.setOpaque(false);
        cuerpo.setAlignmentX(LEFT_ALIGNMENT);

        JPanel izq = new JPanel();
        izq.setOpaque(false);
        izq.setLayout(new BoxLayout(izq, BoxLayout.Y_AXIS));
        izq.add(parrafo("Descargue el formato, llénelo en Excel y súbalo. Estas son las columnas:", 13.5f,
                Tema.GRIS_OSCURO));
        izq.add(Tema.espacio(8));
        String[][] campos = {
            {"nombre", "obligatorio. Si ya existe, ese cliente se actualiza."},
            {"tipo_documento", "CC, NIT, CE, TI, PA o PPT."},
            {"documento", "si es NIT, el dígito de verificación se pone solo."},
            {"telefono, correo, direccion", "opcionales."},
            {"departamento, municipio", "opcionales."},
            {"maximo_credito", "hasta cuánto se le fía; vacío = sin tope."},
            {"estado", "ACTIVO o INACTIVO; vacío queda activo."},
            {"nota", "para el domiciliario (portón verde…)."}};
        for (String[] c : campos) {
            izq.add(parrafo("• " + c[0] + ": " + c[1], 13, Tema.GRIS_OSCURO));
            izq.add(Tema.espacio(3));
        }
        izq.add(Tema.espacio(4));
        izq.add(parrafo("Si alguna fila tiene un error no se guarda nada y se le dice cuál revisar. También sirve un"
                + " .csv o el Excel que sale de «Exportar».", 12.5f, Tema.GRIS_TEXTO));
        izq.add(Tema.espacio(10));
        Tema.Boton formato = Tema.botonSecundario("Descargar formato");
        formato.setIcon(new PanelCategoriasLista.ImagenIcono("excel", 22));
        formato.addActionListener(e -> descargarFormato());
        JPanel ff = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        ff.setOpaque(false);
        ff.setAlignmentX(LEFT_ALIGNMENT);
        ff.add(formato);
        izq.add(ff);
        cuerpo.add(izq);

        Tema.Tarjeta der = new Tema.Tarjeta();
        der.setLayout(new BoxLayout(der, BoxLayout.Y_AXIS));
        der.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        JLabel tit = new JLabel("Subir el archivo");
        tit.setFont(Tema.subtitulo(16));
        tit.setForeground(Tema.GRIS_OSCURO);
        tit.setAlignmentX(CENTER_ALIGNMENT);
        der.add(tit);
        der.add(Tema.espacio(10));
        Tema.Parrafo msg = Tema.parrafo(" ", 330);
        msg.setFont(Tema.texto(13));
        File[] elegido = {null};
        PanelCategoriasLista.ZonaArchivo zona = new PanelCategoriasLista.ZonaArchivo("Escoger el archivo de clientes",
                f -> {
                    elegido[0] = f;
                    msg.setForeground(Tema.GRIS_OSCURO);
                    msg.setText("Listo para cargar: " + f.getName());
                });
        zona.setAlignmentX(CENTER_ALIGNMENT);
        der.add(zona);
        der.add(Tema.espacio(10));
        Tema.Boton cargar = Tema.botonAccion("Cargar archivo");
        cargar.setAlignmentX(CENTER_ALIGNMENT);
        der.add(cargar);
        der.add(Tema.espacio(8));
        msg.setAlignmentX(CENTER_ALIGNMENT);
        der.add(msg);
        cuerpo.add(der);
        t.add(cuerpo);

        capa[0] = new Capa(this, modal, true);
        cargar.addActionListener(e -> {
            if (elegido[0] == null) {
                File f = Archivos.abrir(this, "Escoger el archivo de clientes", "xlsx", "csv");
                if (f == null) {
                    return;
                }
                elegido[0] = f;
            }
            importar(elegido[0], capa[0], msg);
        });
        capa[0].mostrar(cargar);
    }

    private void importar(File f, Capa capa, Tema.Parrafo msg) {
        ImportadorClientes.Resultado r;
        try {
            List<String[]> filas = f.getName().toLowerCase().endsWith(".csv") || f.getName().toLowerCase().endsWith(".txt")
                    ? rutas.logica.ImportadorProductos.leerCsv(f) : rutas.logica.ImportadorCategorias.leerArchivo(f);
            r = ImportadorClientes.revisar(filas, app.getClientes().enOrden());
        } catch (Exception e) {
            msg.setForeground(Tema.ERROR_TEXTO);
            msg.setText("No se pudo leer el archivo: " + e.getMessage());
            capa.reacomodar();
            return;
        }
        if (!r.errores.isEmpty()) {
            msg.setForeground(Tema.ERROR_TEXTO);
            StringBuilder sb = new StringBuilder("No se guardó nada. Revise:\n");
            for (int i = 0; i < Math.min(6, r.errores.size()); i++) {
                sb.append("• ").append(r.errores.get(i)).append('\n');
            }
            if (r.errores.size() > 6) {
                sb.append("y ").append(r.errores.size() - 6).append(" más.");
            }
            msg.setText(sb.toString().trim());
            capa.reacomodar();
            return;
        }
        try {
            app.guardarClientes(r.todos());
        } catch (java.io.IOException e) {
            msg.setForeground(Tema.ERROR_TEXTO);
            msg.setText("No se guardó nada: " + e.getMessage());
            capa.reacomodar();
            return;
        }
        capa.cerrar();
        refrescar();
        Aviso.mostrar(this, Aviso.Tipo.BIEN, "Clientes importados", r.nuevos.size()
                + (r.nuevos.size() == 1 ? " nuevo" : " nuevos") + " · " + r.actualizados.size()
                + (r.actualizados.size() == 1 ? " actualizado" : " actualizados"));
    }

    private void descargarFormato() {
        File f = Archivos.guardar(this, "Guardar el formato de clientes", "formato_clientes.xlsx", "xlsx");
        if (f == null) {
            return;
        }
        List<Object[]> ejemplo = new ArrayList<>();
        ejemplo.add(new Object[]{"CC", "1122334455", "Marcela Luna", "3001234567", "marcela@gmail.com",
            "Calle 15 # 7-20", "GUAJIRA", "RIOHACHA", 50000, "ACTIVO", "Portón verde"});
        ejemplo.add(new Object[]{"NIT", "900123456", "Restaurante El Faro", "3109876543", "", "Carrera 7 # 12-30",
            "", "", "", "", ""});
        try {
            Xlsx.escribir(f, "Clientes", ImportadorClientes.COLUMNAS, ejemplo);
            Aviso.mostrar(this, Aviso.Tipo.BIEN, "Formato guardado", f.getName() + ": borre las filas de ejemplo y llénelo");
        } catch (java.io.IOException e) {
            Alerta.mostrar(this, Alerta.Tipo.AVISO, "No se pudo guardar", e.getMessage(), null);
        }
    }

    private static Tema.Parrafo parrafo(String texto, float tam, Color color) {
        Tema.Parrafo p = Tema.parrafo(texto, 370);
        p.setFont(Tema.texto(tam));
        p.setForeground(color);
        p.setAlignmentX(LEFT_ALIGNMENT);
        return p;
    }

    // ------------------------------------------------------------------
    //  Exportar
    // ------------------------------------------------------------------

    private String sigla(Cliente c) {
        String d = c.documentoCorto();
        return d.isEmpty() ? "" : d.substring(0, d.indexOf(' '));
    }

    private void exportarExcel() {
        if (clientes.isEmpty()) {
            Aviso.mostrar(this, Aviso.Tipo.ERROR, "No hay clientes para exportar", "Cambie la búsqueda o el filtro");
            return;
        }
        File f = Archivos.guardar(this, "Exportar los clientes a Excel", "clientes.xlsx", "xlsx");
        if (f == null) {
            return;
        }
        List<Object[]> filas = new ArrayList<>();
        for (Cliente c : clientes) {
            LocalDateTime u = ultima(c);
            filas.add(new Object[]{sigla(c), c.getDocumento(), c.getNombre(), c.getTelefono(), c.getCorreo(),
                c.getDireccion(), c.getDepartamento(), c.getMunicipio(),
                c.getCreditoMaximo() > 0 ? (Object) c.getCreditoMaximo() : "", c.isActivo() ? "ACTIVO" : "INACTIVO",
                c.getNota(), compras(c), u == null ? "" : u.format(FECHA)});
        }
        String[] cols = new String[ImportadorClientes.COLUMNAS.length + 2];
        System.arraycopy(ImportadorClientes.COLUMNAS, 0, cols, 0, ImportadorClientes.COLUMNAS.length);
        cols[cols.length - 2] = "compras";
        cols[cols.length - 1] = "ultima_compra";
        try {
            // mismas columnas que el formato: el archivo se puede volver a importar
            Xlsx.escribir(f, "Clientes", cols, filas);
            Aviso.mostrar(this, Aviso.Tipo.BIEN, "Excel guardado", filas.size() + " clientes en " + f.getName());
        } catch (java.io.IOException e) {
            Alerta.mostrar(this, Alerta.Tipo.AVISO, "No se pudo guardar el Excel",
                    "Revise que el archivo no esté abierto en Excel. Detalle: " + e.getMessage(), null);
        }
    }

    private void exportarPdf() {
        if (clientes.isEmpty()) {
            Aviso.mostrar(this, Aviso.Tipo.ERROR, "No hay clientes para exportar", "Cambie la búsqueda o el filtro");
            return;
        }
        List<String[]> filas = new ArrayList<>();
        int i = 1;
        for (Cliente c : clientes) {
            filas.add(new String[]{String.valueOf(i++), c.getNombre(), c.documentoCorto().isEmpty() ? "—" : c.documentoCorto(),
                c.getTelefono().isEmpty() ? "—" : c.getTelefono(), c.getDireccion().isEmpty() ? "—" : c.getDireccion(),
                String.valueOf(compras(c)), c.isActivo() ? "ACTIVO" : "INACTIVO"});
        }
        DocumentoLista doc = new DocumentoLista("Lista de clientes",
                new String[]{"Item", "Nombre", "Documento", "Teléfono", "Dirección", "Compras", "Estado"},
                new float[]{0.6f, 2.2f, 1.6f, 1.2f, 2.2f, 0.9f, 1.1f},
                new boolean[]{false, false, false, false, false, true, false}, filas, app.getAjustesRecibo());
        VistaPrevia.mostrar(this, doc, "Lista de clientes");
    }

    // ------------------------------------------------------------------
    //  Tabla
    // ------------------------------------------------------------------

    private class Modelo extends AbstractTableModel {
        @Override
        public int getRowCount() {
            return pagina.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNAS.length;
        }

        @Override
        public String getColumnName(int c) {
            return COLUMNAS[c];
        }

        @Override
        public Object getValueAt(int r, int c) {
            Cliente k = pagina.get(r);
            int porPagina = (Integer) comboPorPagina.getSelectedItem();
            switch (c) {
                case 0:
                    return String.valueOf((numeroPagina - 1) * porPagina + r + 1);
                case COL_NOMBRE:
                    return k.getNombre().toUpperCase();
                case 2:
                    return guion(sigla(k));
                case 3:
                    return guion(k.getDocumento());
                case 4:
                    return guion(k.getTelefono());
                case 5:
                    return guion(k.getCorreo());
                case 6:
                    return guion(k.getDireccion());
                case 7:
                    return guion(k.getMunicipio());
                case COL_FIA:
                    return k.getCreditoMaximo() > 0 ? Tarifa.pesos(k.getCreditoMaximo()) : "—";
                case COL_COMPRAS:
                    return String.valueOf(compras(k));
                case 10:
                    LocalDateTime u = ultima(k);
                    return u == null ? "—" : u.format(FECHA);
                case COL_ESTADO:
                    return k.isActivo() ? "Activo" : "Inactivo";
                default:
                    return "";
            }
        }
    }

    private static String guion(String s) {
        return s == null || s.isBlank() ? "—" : s;
    }

    private class Celda extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foco, int r, int c) {
            super.getTableCellRendererComponent(t, v, sel, false, r, c);
            int vista = c;
            c = t.convertColumnIndexToModel(c); // con la ventana angosta se esconden columnas
            Cliente k = pagina.get(r);
            setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
            setFont(Tema.texto(13.5f));
            boolean izquierda = c == COL_NOMBRE || c == 5 || c == 6 || c == 7;
            setHorizontalAlignment(izquierda ? LEFT : c == COL_FIA ? RIGHT : CENTER);
            setBackground(sel ? Tema.AZUL_SUAVE : (r % 2 == 1 ? new Color(0xFAFBFC) : Color.WHITE));
            setForeground(k.isActivo() ? Tema.GRIS_OSCURO : Tema.GRIS_TEXTO);
            if ("—".equals(v)) {
                setForeground(Tema.GRIS_TEXTO);
            }
            String texto = String.valueOf(v);
            setToolTipText(!"—".equals(v) && getFontMetrics(getFont()).stringWidth(texto)
                    > t.getColumnModel().getColumn(vista).getWidth() - 16 ? texto : null);
            if (c == COL_NOMBRE && !k.getNota().isEmpty()) {
                setToolTipText("<html>" + texto + "<br>Nota: " + k.getNota().replace("<", "&lt;") + "</html>");
            }
            if (c == COL_COMPRAS && compras(k) > 0) {
                setFont(Tema.textoFuerte(13.5f));
                setToolTipText("Por " + Tarifa.pesos(total(k)) + " en total");
            } else if (c == COL_ESTADO) {
                // estado como texto de color, sin pildora (manual de marca)
                setFont(Tema.textoFuerte(13));
                setForeground(k.isActivo() ? Tema.EXITO_TEXTO : Tema.ERROR_TEXTO);
            }
            return this;
        }
    }

    /** «Acciones ▾»: abre el menu con lo que se puede hacer con el cliente. */
    private class Acciones extends DefaultTableCellRenderer {
        private boolean sel;
        private int fila;

        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foco, int r, int c) {
            this.sel = sel;
            this.fila = r;
            setText("");
            setToolTipText("Editar, agregar a la ruta, ver sus compras, desactivar o eliminar");
            return this;
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Tema.suavizar(g);
            g.setColor(sel ? Tema.AZUL_SUAVE : (fila % 2 == 1 ? new Color(0xFAFBFC) : Color.WHITE));
            g.fillRect(0, 0, getWidth(), getHeight());
            int w = Math.min(92, getWidth() - 12), h = 30, x = (getWidth() - w) / 2, y = (getHeight() - h) / 2;
            g.setColor(Tema.BLANCO);
            g.fillRoundRect(x, y, w, h, Tema.ARCO, Tema.ARCO);
            g.setColor(Tema.AZUL);
            g.drawRoundRect(x, y, w - 1, h - 1, Tema.ARCO, Tema.ARCO);
            g.setFont(Tema.textoFuerte(12.5f));
            String txt = "Acciones";
            java.awt.FontMetrics fm = g.getFontMetrics();
            int tx = x + (w - fm.stringWidth(txt) - 14) / 2;
            g.drawString(txt, tx, y + (h + fm.getAscent() - fm.getDescent()) / 2);
            int ax = tx + fm.stringWidth(txt) + 6, ay = y + h / 2 - 2;
            g.fillPolygon(new int[]{ax, ax + 8, ax + 4}, new int[]{ay, ay, ay + 5}, 3);
            g.dispose();
        }
    }
}
