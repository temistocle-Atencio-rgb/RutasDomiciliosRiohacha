package rutas.vista;

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
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import javax.swing.SwingConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import rutas.logica.BaseOrdenes;
import rutas.logica.ListadoProductos;
import rutas.logica.OrdenCompra;
import rutas.logica.Tarifa;
import rutas.logica.Ubicaciones;
import rutas.logica.Xlsx;

/**
 * Compras › Órdenes de compra, como la referencia: «Por página» y Exportar arriba; Buscar, Estado,
 * Filtrar por, Fecha inicial, Fecha final, «Consultar» y «Nueva orden»; la tabla (Item, Cod. orden,
 * Fecha creación, Fecha orden, Fecha estimada, Proveedor, Responsable, Total, Estado, Acciones) y
 * abajo Páginas y Total. «Nueva orden» y «Editar» abren la orden en esta misma pantalla, con
 * «Volver». Las pendientes atrasadas salen primero y en rojo.
 */
public class PanelOrdenesCompra extends JPanel {

    private static final Integer[] POR_PAGINA = {10, 25, 50, 100};
    private static final String TODOS = "TODOS";
    private static final String[] COLUMNAS = {"Item", "Cod. orden", "Fecha creación", "Fecha orden", "Fecha estimada",
        "Proveedor", "Responsable", "Total", "Estado", "Acciones"};
    private static final int COL_PROVEEDOR = 5, COL_TOTAL = 7, COL_ESTADO = 8, COL_ACCIONES = 9;
    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final Aplicacion app;
    private final CardLayout cartas = new CardLayout();
    private final JPanel lista = new JPanel(new BorderLayout());
    private final PanelCompras editor;
    private final JLabel tituloEditor = new JLabel("Nueva orden de compra");

    private final JComboBox<Integer> comboPorPagina = new JComboBox<>(POR_PAGINA);
    private final JTextField campoBuscar = Tema.campo("Número o proveedor");
    private final JComboBox<Object> comboEstado = new JComboBox<>();
    private final JComboBox<BaseOrdenes.FiltroFecha> comboFiltro = new JComboBox<>(BaseOrdenes.FiltroFecha.values());
    private final Controles.CampoFecha fechaInicial = new Controles.CampoFecha("Fecha inicial");
    private final Controles.CampoFecha fechaFinal = new Controles.CampoFecha("Fecha final");
    private final JLabel lblError = new JLabel(" ");
    private final Modelo modelo = new Modelo();
    // sin ordenes, debajo de los titulos sale la franja gris «No hay órdenes registradas» (como la referencia)
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
            String t = "No hay órdenes registradas";
            java.awt.FontMetrics fm = g.getFontMetrics();
            g.drawString(t, (getWidth() - fm.stringWidth(t)) / 2, (alto + fm.getAscent() - fm.getDescent()) / 2);
            g.dispose();
        }
    };
    private final CardLayout tarjetasTabla = new CardLayout();
    private final JPanel zonaTabla = new JPanel(tarjetasTabla);
    private final JLabel lblPaginas = new JLabel();
    private final JLabel lblTotal = new JLabel();
    private final JPanel botonesPagina = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 0));
    private List<BaseOrdenes.OrdenResumen> ordenes = new ArrayList<>();
    private List<BaseOrdenes.OrdenResumen> pagina = new ArrayList<>();
    private int numeroPagina = 1;

    public PanelOrdenesCompra(Aplicacion app) {
        super(new BorderLayout());
        this.app = app;
        setBackground(Tema.GRIS_CLARO);
        setLayout(cartas);
        editor = new PanelCompras(app, true);
        editor.setAlTerminarOrden(this::volverALista);
        armarLista();
        add(lista, "lista");
        add(armarEditor(), "editor");
        cartas.show(this, "lista");
        addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentShown(java.awt.event.ComponentEvent e) {
                if (lista.isVisible()) {
                    buscar(); // pudo cambiar algo (una orden recibida desde Nueva compra)
                }
            }
        });
        buscar();
    }

    // ------------------------------------------------------------------
    //  Lista
    // ------------------------------------------------------------------

    private void armarLista() {
        lista.setBackground(Tema.GRIS_CLARO);
        lista.setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));
        Tema.Tarjeta tarjeta = new Tema.Tarjeta();
        tarjeta.setLayout(new BorderLayout(0, 12));
        JPanel arriba = new JPanel();
        arriba.setOpaque(false);
        arriba.setLayout(new BoxLayout(arriba, BoxLayout.Y_AXIS));
        JLabel titulo = new JLabel("Órdenes de compra");
        titulo.setFont(Tema.titulo(24));
        titulo.setForeground(Tema.GRIS_OSCURO);
        titulo.setAlignmentX(LEFT_ALIGNMENT);
        arriba.add(titulo);
        arriba.add(Box.createVerticalStrut(10));
        arriba.add(encabezado());
        arriba.add(Box.createVerticalStrut(12));
        arriba.add(filtros());
        tarjeta.add(arriba, BorderLayout.NORTH);
        tarjeta.add(zonaTabla(), BorderLayout.CENTER);
        tarjeta.add(pie(), BorderLayout.SOUTH);
        lista.add(tarjeta, BorderLayout.CENTER);
    }

    /** «Por página» a la izquierda y Exportar (Excel, PDF) a la derecha. */
    private JComponent encabezado() {
        JPanel h = new JPanel(new BorderLayout());
        h.setOpaque(false);
        h.setAlignmentX(LEFT_ALIGNMENT);
        Tema.estiloCombo(comboPorPagina, String::valueOf);
        comboPorPagina.setPreferredSize(new Dimension(110, 36));
        comboPorPagina.getAccessibleContext().setAccessibleName("Órdenes por página");
        comboPorPagina.addActionListener(e -> {
            numeroPagina = 1;
            mostrarPagina();
        });
        h.add(PanelProductos.columna("Por página", comboPorPagina), BorderLayout.WEST);
        JPanel botones = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        botones.setOpaque(false);
        botones.add(new PanelProductos.BotonIcono("Excel", PanelProductos.Icono.EXCEL, "Exportar estas órdenes a Excel",
                this::exportarExcel));
        botones.add(new PanelProductos.BotonIcono("PDF", PanelProductos.Icono.PDF,
                "Ver, imprimir o guardar estas órdenes como PDF", this::exportarPdf));
        h.add(PanelProductos.columna("Exportar", botones), BorderLayout.EAST);
        h.setMaximumSize(new Dimension(Integer.MAX_VALUE, h.getPreferredSize().height));
        return h;
    }

    private JComponent filtros() {
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
            buscar();
        }));
        comboEstado.addItem(TODOS);
        for (OrdenCompra.Estado e : OrdenCompra.Estado.values()) {
            comboEstado.addItem(e);
        }
        Tema.estiloCombo(comboEstado, o -> o instanceof OrdenCompra.Estado ? o.toString().toUpperCase() : String.valueOf(o));
        Tema.estiloCombo(comboFiltro, String::valueOf);
        comboEstado.addActionListener(e -> {
            numeroPagina = 1;
            buscar();
        });
        comboFiltro.addActionListener(e -> {
            numeroPagina = 1;
            buscar();
        });
        // como la referencia: de hoy a hoy al abrir; las pendientes salen siempre (no se pierden)
        fechaInicial.setFecha(LocalDate.now());
        fechaFinal.setFecha(LocalDate.now());
        fechaInicial.setAlCambiar(this::buscarSiEsValida);
        fechaFinal.setAlCambiar(this::buscarSiEsValida);

        g.gridx = 0;
        g.weightx = 0.24;
        f.add(etiquetado("Buscar", campoBuscar), g);
        g.gridx = 1;
        g.weightx = 0.17;
        f.add(etiquetado("Estado", comboEstado), g);
        g.gridx = 2;
        g.weightx = 0.17;
        f.add(etiquetado("Filtrar por", comboFiltro), g);
        g.gridx = 3;
        g.weightx = 0.16;
        f.add(etiquetado("Fecha inicial", fechaInicial), g);
        g.gridx = 4;
        g.weightx = 0.16;
        f.add(etiquetado("Fecha final", fechaFinal), g);

        Tema.Boton consultar = Tema.botonPrimario("Consultar");
        consultar.setToolTipText("Volver a leer las órdenes con estos filtros (Enter)");
        consultar.addActionListener(e -> {
            numeroPagina = 1;
            buscar();
        });
        Tema.Boton nueva = Tema.botonAccion("Nueva orden");
        nueva.setToolTipText("Crear una orden de compra para un proveedor");
        nueva.addActionListener(e -> abrirEditor(null));
        g.gridx = 5;
        g.weightx = 0;
        g.fill = GridBagConstraints.NONE;
        g.insets = new Insets(0, 0, 0, 8);
        f.add(consultar, g);
        g.gridx = 6;
        g.insets = new Insets(0, 0, 0, 0);
        f.add(nueva, g);

        lblError.setFont(Tema.texto(13));
        lblError.setForeground(Tema.ERROR_TEXTO);
        g.gridx = 0;
        g.gridy = 1;
        g.gridwidth = 7;
        g.anchor = GridBagConstraints.WEST;
        g.insets = new Insets(4, 0, 0, 0);
        f.add(lblError, g);
        f.registerKeyboardAction(e -> {
            numeroPagina = 1;
            buscar();
        }, KeyStroke.getKeyStroke("ENTER"), JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        f.setMaximumSize(new Dimension(Integer.MAX_VALUE, f.getPreferredSize().height));
        return f;
    }

    private static JComponent etiquetado(String texto, JComponent c) {
        JPanel p = new JPanel(new BorderLayout(0, 5));
        p.setOpaque(false);
        JLabel l = new JLabel(texto);
        l.setFont(Tema.textoFuerte(13.5f));
        l.setForeground(Tema.GRIS_OSCURO);
        l.setLabelFor(c);
        p.add(l, BorderLayout.NORTH);
        c.setPreferredSize(new Dimension(120, 38));
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
        tabla.getAccessibleContext().setAccessibleName("Tabla de órdenes de compra");
        tabla.setDefaultRenderer(Object.class, new Celda());
        tabla.getTableHeader().setReorderingAllowed(false);
        // alto segun la letra real: dos renglones + margen (asi no se corta con la escala de Windows)
        int renglon = tabla.getFontMetrics(Tema.textoFuerte(13.5f)).getHeight();
        tabla.getTableHeader().setPreferredSize(new Dimension(100, Math.max(60, renglon * 2 + 24)));
        tabla.getTableHeader().setDefaultRenderer(new PanelProductos.Encabezado());
        int[] anchos = {50, 90, 118, 108, 118, 200, 140, 110, 130, 104};
        for (int i = 0; i < anchos.length; i++) {
            tabla.getColumnModel().getColumn(i).setPreferredWidth(anchos[i]);
        }
        tabla.getColumnModel().getColumn(0).setMaxWidth(64);
        tabla.getColumnModel().getColumn(COL_ACCIONES).setCellRenderer(new Acciones());
        tabla.getColumnModel().getColumn(COL_ACCIONES).setMinWidth(100);
        tabla.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int f = tabla.rowAtPoint(e.getPoint());
                int c = tabla.columnAtPoint(e.getPoint());
                if (f < 0 || f >= pagina.size()) {
                    return;
                }
                BaseOrdenes.OrdenResumen o = pagina.get(f);
                if (c == COL_ACCIONES) {
                    java.awt.Rectangle r = tabla.getCellRect(f, c, false);
                    menuAcciones(o, r.x, r.y + r.height);
                } else if (e.getClickCount() == 2) {
                    if (o.estado.editable()) {
                        abrirEditor(o);
                    } else {
                        verPdf(o);
                    }
                }
            }
        });
        tabla.addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                tabla.setCursor(tabla.columnAtPoint(e.getPoint()) == COL_ACCIONES
                        ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
            }
        });
        tabla.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ENTER"), "acciones");
        tabla.getActionMap().put("acciones", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                int f = tabla.getSelectedRow();
                if (f >= 0 && f < pagina.size()) {
                    java.awt.Rectangle r = tabla.getCellRect(f, COL_ACCIONES, false);
                    menuAcciones(pagina.get(f), r.x, r.y + r.height);
                }
            }
        });
        JScrollPane sc = new JScrollPane(tabla);
        sc.setBorder(BorderFactory.createLineBorder(Tema.BORDE));
        sc.getViewport().setBackground(Color.WHITE);

        zonaTabla.setOpaque(false);
        zonaTabla.add(sc, "tabla");
        return zonaTabla;
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

    private void buscarSiEsValida() {
        if (fechaInicial.fechaSiEsValida() != null && fechaFinal.fechaSiEsValida() != null) {
            numeroPagina = 1;
            buscar();
        }
    }

    /** Vuelve a leer las ordenes con los filtros. Con «TODOS», las pendientes salen aunque sean de antes. */
    void buscar() {
        if (app.getOrdenes() == null) {
            return;
        }
        try {
            LocalDate d1 = fechaInicial.getFecha();
            LocalDate d2 = fechaFinal.getFecha();
            Object est = comboEstado.getSelectedItem();
            OrdenCompra.Estado estado = est instanceof OrdenCompra.Estado ? (OrdenCompra.Estado) est : null;
            BaseOrdenes.FiltroFecha porFecha = (BaseOrdenes.FiltroFecha) comboFiltro.getSelectedItem();
            String b = campoBuscar.getText();
            BaseOrdenes bo = app.getOrdenes();
            Map<Long, BaseOrdenes.OrdenResumen> juntas = new LinkedHashMap<>();
            if (estado == null && b.isBlank()) {
                for (OrdenCompra.Estado abierta : new OrdenCompra.Estado[]{OrdenCompra.Estado.PENDIENTE,
                    OrdenCompra.Estado.PARCIAL, OrdenCompra.Estado.BORRADOR}) {
                    for (BaseOrdenes.OrdenResumen r : bo.listarOrdenes(abierta, porFecha, null, null, "")) {
                        juntas.put(r.id, r);
                    }
                }
            }
            for (BaseOrdenes.OrdenResumen r : bo.listarOrdenes(estado, porFecha, d1, d2, b)) {
                juntas.put(r.id, r);
            }
            ordenes = new ArrayList<>(juntas.values());
            LocalDate hoy = LocalDate.now();
            ordenes.sort((x, y) -> x.atrasada(hoy) != y.atrasada(hoy) ? (x.atrasada(hoy) ? -1 : 1)
                    : Long.compare(y.numero, x.numero));
            lblError.setText(" ");
        } catch (IllegalArgumentException e) {
            lblError.setText(e.getMessage());
            ordenes = new ArrayList<>();
        } catch (SQLException e) {
            Ubicaciones.registrarError(e);
            lblError.setText("No se pudieron leer las órdenes: " + e.getMessage());
            ordenes = new ArrayList<>();
        }
        mostrarPagina();
    }

    private void mostrarPagina() {
        int porPagina = (Integer) comboPorPagina.getSelectedItem();
        int totalPaginas = Math.max(1, (ordenes.size() + porPagina - 1) / porPagina);
        numeroPagina = Math.max(1, Math.min(numeroPagina, totalPaginas));
        int desde = (numeroPagina - 1) * porPagina;
        pagina = new ArrayList<>(ordenes.subList(Math.min(desde, ordenes.size()), Math.min(desde + porPagina, ordenes.size())));
        modelo.fireTableDataChanged();
        tarjetasTabla.show(zonaTabla, "tabla"); // vacia: la tabla misma dice «No hay órdenes registradas»
        tabla.repaint();
        long total = 0;
        int atrasadas = 0;
        LocalDate hoy = LocalDate.now();
        for (BaseOrdenes.OrdenResumen o : ordenes) {
            if (o.estado != OrdenCompra.Estado.CANCELADA) {
                total += o.total;
            }
            if (o.atrasada(hoy)) {
                atrasadas++;
            }
        }
        lblPaginas.setText("Páginas: " + totalPaginas);
        lblTotal.setText("Total: " + ordenes.size() + (ordenes.isEmpty() ? "" : "  ·  " + Tarifa.pesos(total)
                + (atrasadas > 0 ? "  ·  " + atrasadas + (atrasadas == 1 ? " atrasada" : " atrasadas") : "")));
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

    private void menuAcciones(BaseOrdenes.OrdenResumen o, int x, int y) {
        JPopupMenu m = new JPopupMenu();
        if (o.estado.editable()) {
            agregar(m, "Editar", () -> abrirEditor(o));
        }
        if (o.estado.porRecibir()) {
            agregar(m, "Recibir mercancía", () -> recibir(o));
        }
        agregar(m, "Ver PDF", () -> verPdf(o));
        if (o.estado != OrdenCompra.Estado.CANCELADA && o.estado != OrdenCompra.Estado.BORRADOR) {
            agregar(m, "Enviar por WhatsApp", () -> conOrden(o, ord -> OrdenDocumento.enviarWhatsapp(this, app, ord)));
            agregar(m, "Enviar por correo", () -> conOrden(o, ord -> OrdenDocumento.enviarCorreo(this, app, ord)));
        }
        if (o.estado != OrdenCompra.Estado.CANCELADA && o.estado != OrdenCompra.Estado.RECIBIDA) {
            m.addSeparator();
            JMenuItem c = agregar(m, "Cancelar orden", () -> cancelar(o));
            c.setForeground(Tema.ERROR_TEXTO);
        }
        m.show(tabla, x, y);
    }

    private static JMenuItem agregar(JPopupMenu m, String texto, Runnable r) {
        JMenuItem it = new JMenuItem(texto);
        it.setFont(Tema.texto(13.5f));
        it.addActionListener(e -> r.run());
        m.add(it);
        return it;
    }

    private void conOrden(BaseOrdenes.OrdenResumen r, java.util.function.Consumer<OrdenCompra> hacer) {
        try {
            hacer.accept(app.getOrdenes().cargarOrden(r.id, app.getCatalogo(), null));
        } catch (IllegalArgumentException | SQLException e) {
            Alerta.mostrar(this, Alerta.Tipo.AVISO, "No se pudo abrir la orden", e.getMessage(), null);
        }
    }

    private void verPdf(BaseOrdenes.OrdenResumen r) {
        conOrden(r, o -> VistaPrevia.mostrar(this, OrdenDocumento.documento(app, o), "Orden de compra"));
    }

    private void recibir(BaseOrdenes.OrdenResumen r) {
        conOrden(r, o -> {
            VentanaPrincipal v = app.getVentana();
            if (v != null) {
                v.recibirOrden(o);
            }
        });
    }

    private void cancelar(BaseOrdenes.OrdenResumen r) {
        Alerta.confirmar(this, "¿Cancelar la orden No. " + Comprobante.numeroTexto(r.numero) + "?",
                r.estado == OrdenCompra.Estado.PARCIAL ? "Ya llegó una parte; lo que falta no se espera más."
                        : "Queda como Cancelada. Si ya se la mandó al proveedor, avísele.",
                "Sí, cancelar orden", "No", () -> {
                    try {
                        app.getOrdenes().cancelarOrden(r.id);
                        buscar();
                        Aviso.mostrar(this, Aviso.Tipo.BIEN, "Orden cancelada", "No. " + Comprobante.numeroTexto(r.numero));
                    } catch (IllegalStateException | SQLException e) {
                        Alerta.mostrar(this, Alerta.Tipo.AVISO, "No se pudo cancelar", e.getMessage(), null);
                    }
                });
    }

    // ------------------------------------------------------------------
    //  Editor (Nueva orden / Editar), con «Volver»
    // ------------------------------------------------------------------

    private JComponent armarEditor() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(Tema.GRIS_CLARO);
        JPanel cab = new JPanel(new BorderLayout());
        cab.setBackground(Tema.GRIS_CLARO);
        cab.setBorder(BorderFactory.createEmptyBorder(12, 20, 12, 20));
        tituloEditor.setFont(Tema.titulo(22));
        tituloEditor.setForeground(Tema.GRIS_OSCURO);
        cab.add(tituloEditor, BorderLayout.WEST);
        Tema.Boton volver = Tema.botonSecundario("Volver");
        volver.setToolTipText("Volver a la lista de órdenes (lo que no guardó se pierde)");
        volver.addActionListener(e -> volverALista());
        cab.add(volver, BorderLayout.EAST);
        p.add(cab, BorderLayout.NORTH);
        p.add(editor, BorderLayout.CENTER);
        return p;
    }

    /** r null = nueva. */
    void abrirEditor(BaseOrdenes.OrdenResumen r) {
        if (Capa.hayAbierta(this)) {
            return;
        }
        OrdenCompra o = null;
        List<String> avisos = new ArrayList<>();
        if (r != null) {
            try {
                o = app.getOrdenes().cargarOrden(r.id, app.getCatalogo(), avisos);
            } catch (IllegalArgumentException | SQLException e) {
                Alerta.mostrar(this, Alerta.Tipo.AVISO, "No se pudo abrir la orden", e.getMessage(), null);
                return;
            }
        }
        tituloEditor.setText(r == null ? "Nueva orden de compra" : "Orden de compra No. " + Comprobante.numeroTexto(r.numero));
        cartas.show(this, "editor");
        editor.abrirOrden(o, avisos);
    }

    private void volverALista() {
        cartas.show(this, "lista");
        buscar();
    }

    // ------------------------------------------------------------------
    //  Exportar
    // ------------------------------------------------------------------

    private static String fecha(LocalDate d) {
        return d == null ? "—" : d.format(FECHA);
    }

    private void exportarExcel() {
        if (ordenes.isEmpty()) {
            Aviso.mostrar(this, Aviso.Tipo.ERROR, "No hay órdenes para exportar", "Cambie los filtros y oprima «Consultar»");
            return;
        }
        File f = Archivos.guardar(this, "Exportar las órdenes a Excel", "ordenes_de_compra.xlsx", "xlsx");
        if (f == null) {
            return;
        }
        List<Object[]> filas = new ArrayList<>();
        int i = 1;
        for (BaseOrdenes.OrdenResumen o : ordenes) {
            filas.add(new Object[]{i++, o.numero, PanelHistorialCompras.textoFecha(o.creada), fecha(o.fecha),
                fecha(o.fechaEstimada), o.proveedor, o.responsable, o.total, o.estado.toString().toUpperCase()});
        }
        try {
            Xlsx.escribir(f, "Ordenes", new String[]{"item", "orden", "fecha_creacion", "fecha_orden", "fecha_estimada",
                "proveedor", "responsable", "total", "estado"}, filas);
            Aviso.mostrar(this, Aviso.Tipo.BIEN, "Excel guardado", filas.size() + " órdenes en " + f.getName());
        } catch (java.io.IOException e) {
            Alerta.mostrar(this, Alerta.Tipo.AVISO, "No se pudo guardar el Excel",
                    "Revise que el archivo no esté abierto en Excel. Detalle: " + e.getMessage(), null);
        }
    }

    private void exportarPdf() {
        if (ordenes.isEmpty()) {
            Aviso.mostrar(this, Aviso.Tipo.ERROR, "No hay órdenes para exportar", "Cambie los filtros y oprima «Consultar»");
            return;
        }
        List<String[]> filas = new ArrayList<>();
        int i = 1;
        for (BaseOrdenes.OrdenResumen o : ordenes) {
            filas.add(new String[]{String.valueOf(i++), Comprobante.numeroTexto(o.numero), fecha(o.fecha),
                fecha(o.fechaEstimada), o.proveedor.isEmpty() ? "—" : o.proveedor, o.responsable,
                o.estado.toString().toUpperCase(), Tarifa.pesos(o.total)});
        }
        DocumentoLista doc = new DocumentoLista("Órdenes de compra",
                new String[]{"Item", "Orden", "Fecha", "Estimada", "Proveedor", "Responsable", "Estado", "Total"},
                new float[]{0.6f, 0.9f, 1.1f, 1.1f, 2.2f, 1.4f, 1.4f, 1.2f},
                new boolean[]{false, false, false, false, false, false, false, true}, filas, app.getAjustesRecibo());
        VistaPrevia.mostrar(this, doc, "Órdenes de compra");
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
            BaseOrdenes.OrdenResumen o = pagina.get(r);
            int porPagina = (Integer) comboPorPagina.getSelectedItem();
            switch (c) {
                case 0:
                    return String.valueOf((numeroPagina - 1) * porPagina + r + 1);
                case 1:
                    return Comprobante.numeroTexto(o.numero);
                case 2:
                    return o.creada.format(FECHA);
                case 3:
                    return fecha(o.fecha);
                case 4:
                    return fecha(o.fechaEstimada);
                case COL_PROVEEDOR:
                    return o.proveedor.isEmpty() ? "Sin proveedor" : o.proveedor;
                case 6:
                    return o.responsable;
                case COL_TOTAL:
                    return Tarifa.pesos(o.total);
                case COL_ESTADO:
                    return o.atrasada(LocalDate.now()) ? "Atrasada" : o.estado.toString();
                default:
                    return "";
            }
        }
    }

    private class Celda extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foco, int r, int c) {
            super.getTableCellRendererComponent(t, v, sel, false, r, c);
            BaseOrdenes.OrdenResumen o = pagina.get(r);
            setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10));
            setFont(Tema.texto(13.5f));
            setHorizontalAlignment(c == COL_PROVEEDOR ? LEFT : c == COL_TOTAL ? RIGHT : CENTER);
            setBackground(sel ? Tema.AZUL_SUAVE : (r % 2 == 1 ? new Color(0xFAFBFC) : Color.WHITE));
            setForeground(o.estado == OrdenCompra.Estado.CANCELADA ? Tema.GRIS_TEXTO : Tema.GRIS_OSCURO);
            setToolTipText(null);
            boolean atrasada = o.atrasada(LocalDate.now());
            if (c == 4 && atrasada) {
                setForeground(Tema.ERROR_TEXTO);
                setFont(Tema.textoFuerte(13.5f));
                setToolTipText("Ya pasó la fecha y no ha llegado");
            } else if (c == COL_TOTAL) {
                setFont(Tema.textoFuerte(13.5f));
            } else if (c == COL_ESTADO) {
                // estado como texto de color, sin pildora (manual de marca)
                setFont(Tema.textoFuerte(13));
                setForeground(atrasada ? Tema.ERROR_TEXTO : colorEstado(o.estado));
            }
            return this;
        }
    }

    static Color colorEstado(OrdenCompra.Estado e) {
        switch (e) {
            case RECIBIDA:
                return Tema.EXITO_TEXTO;
            case CANCELADA:
                return Tema.ERROR_TEXTO;
            case BORRADOR:
                return Tema.GRIS_TEXTO;
            case PARCIAL:
                return Tema.AZUL;
            default:
                return Tema.AVISO_TEXTO;
        }
    }

    /** «Acciones ▾»: abre el menu con lo que se puede hacer con la orden. */
    private class Acciones extends DefaultTableCellRenderer {
        private boolean sel;
        private int fila;

        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foco, int r, int c) {
            this.sel = sel;
            this.fila = r;
            setText("");
            setToolTipText("Editar, recibir, ver PDF, enviar o cancelar");
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
