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
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import rutas.logica.BaseTienda;
import rutas.logica.Carrito;
import rutas.logica.ListadoProductos;
import rutas.logica.Tarifa;
import rutas.logica.Ubicaciones;
import rutas.logica.Xlsx;
import rutas.modelo.Bodega;
import rutas.modelo.Unidad;

/**
 * Compras › Historial de compras: las compras guardadas entre dos fechas, con filtro de estado y
 * bodega, buscador, paginas y exportar a Excel o PDF. Cada compra se puede ver completa,
 * imprimir y anular (anular devuelve el stock).
 */
public class PanelHistorialCompras extends JPanel {

    private static final Integer[] POR_PAGINA = {10, 25, 50, 100};
    private static final String[] ESTADOS = {"TODOS", "PAGADO", "ANULADO"};
    private static final String[] COLUMNAS = {"Item", "Compra", "Bodega", "Factura", "Fecha y hora",
        "Proveedor", "Atendió", "Valor", "Estado", "Acción"};
    private static final int COL_VALOR = 7, COL_ESTADO = 8, COL_ACCION = 9;
    static final DateTimeFormatter FECHA_HORA = DateTimeFormatter.ofPattern("dd/MM/yyyy h:mm a");
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("h:mm a");
    private static final Bodega TODAS = new Bodega(0, "TODAS");

    private final Aplicacion app;
    private final BaseTienda bd;
    private final JComboBox<Integer> comboPorPagina = new JComboBox<>(POR_PAGINA);
    private final Controles.CampoFecha fechaInicial = new Controles.CampoFecha("Fecha inicial");
    private final Controles.CampoFecha fechaFinal = new Controles.CampoFecha("Fecha final");
    private final JComboBox<LocalTime> horaInicial = comboHoras();
    private final JComboBox<LocalTime> horaFinal = comboHoras();
    private final JComboBox<String> comboEstado = new JComboBox<>(ESTADOS);
    private final JComboBox<Bodega> comboBodega = new JComboBox<>();
    private final JTextField campoBuscar = Tema.campo("Buscar por número, factura o proveedor");
    private final JLabel lblError = new JLabel(" ");
    private final Modelo modelo = new Modelo();
    private final JTable tabla = new JTable(modelo);
    private final CardLayout tarjetasTabla = new CardLayout();
    private final JPanel zonaTabla = new JPanel(tarjetasTabla);
    private final JLabel lblPaginas = new JLabel();
    private final JPanel botonesPagina = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 0));
    private List<BaseTienda.CompraGuardada> compras = new ArrayList<>();
    private List<BaseTienda.CompraGuardada> pagina = new ArrayList<>();
    private int numeroPagina = 1;
    private int totalPaginas = 1;

    public PanelHistorialCompras(Aplicacion app) {
        super(new BorderLayout());
        this.app = app;
        this.bd = app.getTienda();
        setBackground(Tema.GRIS_CLARO);
        setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));
        putClientProperty(Capa.FOCO_AL_CERRAR, campoBuscar);
        fechaInicial.setFecha(LocalDate.now());
        fechaFinal.setFecha(LocalDate.now());
        horaInicial.setSelectedIndex(0);
        horaFinal.setSelectedIndex(horaFinal.getItemCount() - 1);
        add(encabezado(), BorderLayout.NORTH);
        Tema.Tarjeta tarjeta = new Tema.Tarjeta();
        tarjeta.setLayout(new BorderLayout(0, 12));
        tarjeta.add(filtros(), BorderLayout.NORTH);
        tarjeta.add(zonaTabla(), BorderLayout.CENTER);
        tarjeta.add(pie(), BorderLayout.SOUTH);
        add(tarjeta, BorderLayout.CENTER);
        addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentShown(java.awt.event.ComponentEvent e) {
                llenarBodegas();
                buscar(); // puede haber compras nuevas
            }
        });
        llenarBodegas();
        buscar();
    }

    // ------------------------------------------------------------------
    //  Partes de la pantalla
    // ------------------------------------------------------------------

    private JComponent encabezado() {
        JPanel h = new JPanel(new BorderLayout());
        h.setOpaque(false);
        h.setBorder(BorderFactory.createEmptyBorder(0, 0, 14, 0));
        Tema.estiloCombo(comboPorPagina, String::valueOf);
        comboPorPagina.setPreferredSize(new Dimension(96, 34));
        comboPorPagina.getAccessibleContext().setAccessibleName("Compras por página");
        comboPorPagina.addActionListener(e -> {
            numeroPagina = 1;
            mostrarPagina();
        });
        JPanel izq = PanelProductos.columna("Por página", comboPorPagina);
        h.add(izq, BorderLayout.WEST);
        JLabel titulo = new JLabel("Historial de compras", SwingConstants.CENTER);
        titulo.setFont(Tema.titulo(24));
        titulo.setForeground(Tema.GRIS_OSCURO);
        h.add(titulo, BorderLayout.CENTER);
        JPanel botones = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        botones.setOpaque(false);
        botones.add(new PanelProductos.BotonIcono("Excel", PanelProductos.Icono.EXCEL, "Exportar estas compras a Excel",
                this::exportarExcel));
        botones.add(new PanelProductos.BotonIcono("PDF", PanelProductos.Icono.PDF,
                "Ver, imprimir o guardar estas compras como PDF", this::exportarPdf));
        JPanel der = PanelProductos.columna("Exportar", botones);
        h.add(der, BorderLayout.EAST);
        int ancho = Math.max(izq.getPreferredSize().width, der.getPreferredSize().width);
        izq.setPreferredSize(new Dimension(ancho, izq.getPreferredSize().height));
        der.setPreferredSize(new Dimension(ancho, der.getPreferredSize().height));
        return h;
    }

    private JComponent filtros() {
        JPanel f = new JPanel(new GridBagLayout());
        f.setOpaque(false);
        GridBagConstraints g = new GridBagConstraints();
        g.anchor = GridBagConstraints.NORTHWEST;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.insets = new Insets(0, 0, 0, 16);
        g.gridy = 0;

        Tema.estiloCombo(horaInicial, PanelHistorialCompras::textoHora);
        Tema.estiloCombo(horaFinal, PanelHistorialCompras::textoHora);
        horaInicial.getAccessibleContext().setAccessibleName("Hora inicial");
        horaFinal.getAccessibleContext().setAccessibleName("Hora final");
        g.gridx = 0;
        g.weightx = 0.3;
        f.add(fechaConHora("Fecha inicial", fechaInicial, horaInicial), g);
        g.gridx = 1;
        f.add(fechaConHora("Fecha final", fechaFinal, horaFinal), g);

        Tema.estiloCombo(comboEstado, String::valueOf);
        comboEstado.getAccessibleContext().setAccessibleName("Estado");
        Tema.estiloCombo(comboBodega, Bodega::getNombre);
        comboBodega.getAccessibleContext().setAccessibleName("Bodega");
        JPanel est = new JPanel();
        est.setOpaque(false);
        est.setLayout(new BoxLayout(est, BoxLayout.Y_AXIS));
        est.add(etiqueta("Estado"));
        est.add(Box.createVerticalStrut(5));
        est.add(Controles.ancho(comboEstado));
        est.add(Box.createVerticalStrut(8));
        est.add(etiqueta("Bodega"));
        est.add(Box.createVerticalStrut(5));
        est.add(Controles.ancho(comboBodega));
        g.gridx = 2;
        g.weightx = 0.25;
        f.add(est, g);

        Tema.Boton buscar = Tema.botonPrimario("Buscar");
        buscar.setIcon(Iconos.de("lupa", 16, Tema.BLANCO));
        buscar.setToolTipText("Mostrar las compras con estos filtros (Enter)");
        buscar.addActionListener(e -> {
            numeroPagina = 1;
            buscar();
        });
        JPanel colBuscar = new JPanel();
        colBuscar.setOpaque(false);
        colBuscar.setLayout(new BoxLayout(colBuscar, BoxLayout.Y_AXIS));
        colBuscar.add(Box.createVerticalStrut(etiqueta("X").getPreferredSize().height + 5));
        buscar.setAlignmentX(LEFT_ALIGNMENT);
        colBuscar.add(buscar);
        g.gridx = 3;
        g.weightx = 0;
        g.fill = GridBagConstraints.NONE;
        g.insets = new Insets(0, 0, 0, 0);
        f.add(colBuscar, g);
        g.gridx = 4;
        g.weightx = 0.2;
        f.add(Box.createHorizontalGlue(), g);

        // segunda fila: el error de fechas a la izquierda y el buscador a la derecha
        JPanel fila = new JPanel(new BorderLayout(12, 0));
        fila.setOpaque(false);
        lblError.setFont(Tema.texto(13));
        lblError.setForeground(Tema.ERROR_TEXTO);
        fila.add(lblError, BorderLayout.CENTER);
        campoBuscar.setPreferredSize(new Dimension(320, 38));
        campoBuscar.setBorder(BorderFactory.createCompoundBorder(new Tema.BordeRedondo(Tema.BORDE_CONTROL, Tema.RADIO),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));
        campoBuscar.getDocument().addDocumentListener(PanelProductos.alCambiar(() -> {
            numeroPagina = 1;
            buscar();
        }));
        fila.add(campoBuscar, BorderLayout.EAST);
        g.gridx = 0;
        g.gridy = 1;
        g.gridwidth = 5;
        g.weightx = 1;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.insets = new Insets(12, 0, 0, 0);
        f.add(fila, g);

        // Enter en cualquier filtro busca
        f.registerKeyboardAction(e -> {
            numeroPagina = 1;
            buscar();
        }, KeyStroke.getKeyStroke("ENTER"), JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        return f;
    }

    private static JLabel etiqueta(String t) {
        JLabel l = new JLabel(t);
        l.setFont(Tema.textoFuerte(13.5f));
        l.setForeground(Tema.GRIS_OSCURO);
        l.setAlignmentX(LEFT_ALIGNMENT);
        return l;
    }

    private static JComponent fechaConHora(String titulo, Controles.CampoFecha fecha, JComboBox<LocalTime> hora) {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.add(etiqueta(titulo));
        p.add(Box.createVerticalStrut(5));
        fecha.setAlignmentX(LEFT_ALIGNMENT);
        p.add(fecha);
        p.add(Box.createVerticalStrut(8));
        p.add(Controles.ancho(hora));
        return p;
    }

    /** Horas cada media hora, de 12:00 a. m. a 11:30 p. m., y al final 11:59 p. m. (fin del dia). */
    private static JComboBox<LocalTime> comboHoras() {
        JComboBox<LocalTime> c = new JComboBox<>();
        for (int m = 0; m < 24 * 60; m += 30) {
            c.addItem(LocalTime.of(m / 60, m % 60));
        }
        c.addItem(LocalTime.of(23, 59));
        c.setMaximumRowCount(12);
        return c;
    }

    static String textoHora(LocalTime t) {
        return t.format(HORA).replace("AM", "a. m.").replace("PM", "p. m.");
    }

    static String textoFecha(LocalDateTime t) {
        return t.format(FECHA_HORA).replace("AM", "a. m.").replace("PM", "p. m.");
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
        tabla.getAccessibleContext().setAccessibleName("Tabla de compras");
        tabla.setDefaultRenderer(Object.class, new Celda());
        tabla.getTableHeader().setReorderingAllowed(false);
        tabla.getTableHeader().setPreferredSize(new Dimension(100, 44));
        tabla.getTableHeader().setDefaultRenderer(new PanelProductos.Encabezado());
        int[] anchos = {50, 70, 130, 110, 176, 190, 110, 110, 90, 90};
        for (int i = 0; i < anchos.length; i++) {
            tabla.getColumnModel().getColumn(i).setPreferredWidth(anchos[i]);
        }
        tabla.getColumnModel().getColumn(4).setMinWidth(170); // la fecha y hora completa
        tabla.getColumnModel().getColumn(COL_VALOR).setMinWidth(100);
        tabla.getColumnModel().getColumn(COL_ESTADO).setMinWidth(92);
        tabla.getColumnModel().getColumn(3).setMinWidth(90);
        tabla.getColumnModel().getColumn(1).setMinWidth(74);
        tabla.getColumnModel().getColumn(0).setMaxWidth(64);
        tabla.getColumnModel().getColumn(COL_ACCION).setCellRenderer(new Acciones());
        tabla.getColumnModel().getColumn(COL_ACCION).setMinWidth(90);
        tabla.getColumnModel().getColumn(COL_ACCION).setMaxWidth(104);
        tabla.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int f = tabla.rowAtPoint(e.getPoint());
                int c = tabla.columnAtPoint(e.getPoint());
                if (f < 0 || f >= pagina.size()) {
                    return;
                }
                BaseTienda.CompraGuardada cg = pagina.get(f);
                if (c == COL_ACCION) {
                    java.awt.Rectangle r = tabla.getCellRect(f, c, false);
                    if (cg.isAnulada() || e.getX() < r.x + r.width / 2) {
                        verDetalle(cg);
                    } else {
                        anular(cg, null);
                    }
                } else if (e.getClickCount() == 2) {
                    verDetalle(cg);
                }
            }
        });
        tabla.addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                tabla.setCursor(tabla.columnAtPoint(e.getPoint()) == COL_ACCION
                        ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
            }
        });
        tabla.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ENTER"), "ver");
        tabla.getActionMap().put("ver", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                int f = tabla.getSelectedRow();
                if (f >= 0 && f < pagina.size()) {
                    verDetalle(pagina.get(f));
                }
            }
        });
        JScrollPane sc = new JScrollPane(tabla);
        sc.setBorder(BorderFactory.createLineBorder(Tema.BORDE));
        sc.getViewport().setBackground(Color.WHITE);

        JPanel vacia = new JPanel(new GridBagLayout());
        vacia.setBackground(Tema.GRIS_CLARO);
        vacia.setBorder(BorderFactory.createLineBorder(Tema.BORDE));
        JPanel txt = new JPanel();
        txt.setOpaque(false);
        txt.setLayout(new BoxLayout(txt, BoxLayout.Y_AXIS));
        JLabel ic = new JLabel(Iconos.de("carrito", 40, Tema.AZUL));
        ic.setAlignmentX(CENTER_ALIGNMENT);
        txt.add(ic);
        txt.add(Box.createVerticalStrut(12));
        JLabel t = new JLabel("Sin registros de compras para el filtro aplicado");
        t.setFont(Tema.subtitulo(17));
        t.setForeground(Tema.AZUL_PROFUNDO);
        t.setAlignmentX(CENTER_ALIGNMENT);
        txt.add(t);
        txt.add(Box.createVerticalStrut(6));
        JLabel t2 = new JLabel("Cambie las fechas o el estado y oprima «Buscar».");
        t2.setFont(Tema.texto(14));
        t2.setForeground(Tema.GRIS_TEXTO);
        t2.setAlignmentX(CENTER_ALIGNMENT);
        txt.add(t2);
        vacia.add(txt);
        zonaTabla.setOpaque(false);
        zonaTabla.add(sc, "tabla");
        zonaTabla.add(vacia, "vacia");
        return zonaTabla;
    }

    private JComponent pie() {
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

    private void llenarBodegas() {
        Object antes = comboBodega.getSelectedItem();
        comboBodega.removeAllItems();
        comboBodega.addItem(TODAS);
        try {
            for (Bodega b : bd.listarBodegas()) {
                comboBodega.addItem(b);
                if (antes instanceof Bodega && ((Bodega) antes).getId() == b.getId()) {
                    comboBodega.setSelectedItem(b);
                }
            }
        } catch (SQLException e) {
            Ubicaciones.registrarError(e);
        }
    }

    /** Vuelve a leer las compras con los filtros de arriba. */
    void buscar() {
        try {
            LocalDate d1 = fechaInicial.getFecha();
            LocalDate d2 = fechaFinal.getFecha();
            if (d1 == null || d2 == null) {
                throw new IllegalArgumentException("Escriba la fecha inicial y la fecha final");
            }
            LocalDateTime desde = d1.atTime((LocalTime) horaInicial.getSelectedItem());
            LocalDateTime hasta = d2.atTime((LocalTime) horaFinal.getSelectedItem());
            String est = (String) comboEstado.getSelectedItem();
            Boolean anuladas = "PAGADO".equals(est) ? Boolean.FALSE : "ANULADO".equals(est) ? Boolean.TRUE : null;
            Bodega b = (Bodega) comboBodega.getSelectedItem();
            Long bodega = b == null || b.getId() == 0 ? null : b.getId();
            compras = bd.listarCompras(desde, hasta, anuladas, bodega, campoBuscar.getText());
            lblError.setText(" ");
        } catch (IllegalArgumentException e) {
            lblError.setText(e.getMessage());
            compras = new ArrayList<>();
        } catch (SQLException e) {
            Ubicaciones.registrarError(e);
            lblError.setText("No se pudieron leer las compras: " + e.getMessage());
            compras = new ArrayList<>();
        }
        mostrarPagina();
    }

    private void mostrarPagina() {
        int porPagina = (Integer) comboPorPagina.getSelectedItem();
        totalPaginas = Math.max(1, (compras.size() + porPagina - 1) / porPagina);
        numeroPagina = Math.max(1, Math.min(numeroPagina, totalPaginas));
        int desde = (numeroPagina - 1) * porPagina;
        pagina = new ArrayList<>(compras.subList(Math.min(desde, compras.size()),
                Math.min(desde + porPagina, compras.size())));
        modelo.fireTableDataChanged();
        tarjetasTabla.show(zonaTabla, compras.isEmpty() ? "vacia" : "tabla");
        long pagado = 0;
        int anuladas = 0;
        for (BaseTienda.CompraGuardada c : compras) {
            if (c.isAnulada()) {
                anuladas++;
            } else {
                pagado += c.getTotal();
            }
        }
        lblPaginas.setText("Páginas: " + totalPaginas + "    ·    " + compras.size()
                + (compras.size() == 1 ? " compra" : " compras")
                + (anuladas > 0 ? " (" + anuladas + (anuladas == 1 ? " anulada)" : " anuladas)") : "")
                + "    ·    Total comprado: " + Tarifa.pesos(pagado));
        botonesPagina.removeAll();
        if (totalPaginas > 1) {
            botonesPagina.add(new PanelProductos.BotonPagina("«", "Página anterior", numeroPagina > 1, false,
                    () -> irA(numeroPagina - 1)));
            for (int n : ListadoProductos.botones(numeroPagina, totalPaginas)) {
                if (n < 0) {
                    JLabel puntos = new JLabel("…");
                    puntos.setFont(Tema.texto(14));
                    puntos.setForeground(Tema.GRIS_TEXTO);
                    puntos.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
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

    private String rango() {
        LocalDate d1 = fechaInicial.fechaSiEsValida();
        LocalDate d2 = fechaFinal.fechaSiEsValida();
        DateTimeFormatter f = DateTimeFormatter.ofPattern("dd/MM/yyyy");
        if (d1 == null || d2 == null) {
            return "";
        }
        return d1.equals(d2) ? d1.format(f) : d1.format(f) + " al " + d2.format(f);
    }

    // ------------------------------------------------------------------
    //  Exportar
    // ------------------------------------------------------------------

    private void exportarExcel() {
        if (compras.isEmpty()) {
            Aviso.mostrar(this, Aviso.Tipo.ERROR, "No hay compras para exportar", "Cambie los filtros y oprima «Buscar»");
            return;
        }
        File f = Archivos.guardar(this, "Exportar las compras a Excel", "compras.xlsx", "xlsx");
        if (f == null) {
            return;
        }
        List<Object[]> filas = new ArrayList<>();
        int i = 1;
        for (BaseTienda.CompraGuardada c : compras) {
            filas.add(new Object[]{i++, c.getNumero(), c.getBodega(), c.getFactura(), textoFecha(c.getFecha()),
                c.getProveedor(), c.getAtendio(), c.getTotal(), c.isAnulada() ? "ANULADO" : "PAGADO"});
        }
        try {
            Xlsx.escribir(f, "Compras", new String[]{"item", "compra", "bodega", "factura_proveedor", "fecha",
                "proveedor", "atendio", "valor_compra", "estado"}, filas);
            Aviso.mostrar(this, Aviso.Tipo.BIEN, "Excel guardado", filas.size() + " compras en " + f.getName());
        } catch (java.io.IOException e) {
            Alerta.mostrar(this, Alerta.Tipo.AVISO, "No se pudo guardar el Excel",
                    "Revise que el archivo no esté abierto en Excel. Detalle: " + e.getMessage(), null);
        }
    }

    private void exportarPdf() {
        if (compras.isEmpty()) {
            Aviso.mostrar(this, Aviso.Tipo.ERROR, "No hay compras para exportar", "Cambie los filtros y oprima «Buscar»");
            return;
        }
        List<String[]> filas = new ArrayList<>();
        int i = 1;
        for (BaseTienda.CompraGuardada c : compras) {
            filas.add(new String[]{String.valueOf(i++), Comprobante.numeroTexto(c.getNumero()), textoFecha(c.getFecha()),
                c.getProveedor().isEmpty() ? "—" : c.getProveedor(), c.getFactura(), c.getBodega(),
                c.isAnulada() ? "ANULADO" : "PAGADO", Tarifa.pesos(c.getTotal())});
        }
        String r = rango();
        DocumentoLista doc = new DocumentoLista("Historial de compras" + (r.isEmpty() ? "" : " · " + r),
                new String[]{"Item", "Compra", "Fecha y hora", "Proveedor", "Factura", "Bodega", "Estado", "Valor"},
                new float[]{0.6f, 1f, 1.9f, 2f, 1.1f, 1.4f, 1.1f, 1.2f},
                new boolean[]{false, false, false, false, false, false, false, true}, filas, app.getAjustesRecibo());
        VistaPrevia.mostrar(this, doc, "Historial de compras");
    }

    // ------------------------------------------------------------------
    //  Detalle y anular
    // ------------------------------------------------------------------

    private void verDetalle(BaseTienda.CompraGuardada cg) {
        if (Capa.hayAbierta(this)) {
            return;
        }
        BaseTienda.DetalleCompra d;
        try {
            d = bd.detalleCompra(cg.getId());
        } catch (IllegalArgumentException | SQLException e) {
            Alerta.mostrar(this, Alerta.Tipo.AVISO, "No se pudo abrir la compra", e.getMessage(), null);
            return;
        }
        DetalleCompraVista.mostrar(this, d, app.getAjustesRecibo(), () -> anular(cg, d));
    }

    /** Anular: muestra lo que pasa con el stock, pide el motivo y devuelve la mercancia. */
    private void anular(BaseTienda.CompraGuardada cg, BaseTienda.DetalleCompra d) {
        if (cg.isAnulada() || Capa.hayAbierta(this)) {
            return;
        }
        List<String> avisos;
        try {
            avisos = bd.revisarAnulacion(cg.getId());
        } catch (SQLException e) {
            Alerta.mostrar(this, Alerta.Tipo.AVISO, "No se pudo revisar la compra", e.getMessage(), null);
            return;
        }
        JPanel t = Capa.tarjeta(480, 26, 30);
        t.add(Tema.tituloPantalla("¿Anular la compra No. " + Comprobante.numeroTexto(cg.getNumero()) + "?"));
        t.add(Tema.espacio(8));
        Tema.Parrafo p = Tema.parrafo("Se resta del stock" + (cg.getBodega().isEmpty() ? "" : " de " + cg.getBodega())
                + " todo lo que entró con esta compra y queda marcada como ANULADO. No se borra: sigue saliendo en el"
                + " historial. El costo y el precio de venta de los productos no cambian.", 420);
        p.setFont(Tema.texto(13.5f));
        p.setForeground(Tema.GRIS_OSCURO);
        p.setAlignmentX(LEFT_ALIGNMENT);
        t.add(p);
        if (!avisos.isEmpty()) {
            t.add(Tema.espacio(10));
            Tema.Parrafo a = Tema.parrafo("Ya se vendió parte de esta mercancía. Quedarían en negativo: "
                    + String.join(", ", avisos) + ".", 420);
            a.setFont(Tema.textoFuerte(13));
            a.setForeground(Tema.AVISO_TEXTO);
            a.setAlignmentX(LEFT_ALIGNMENT);
            t.add(a);
        }
        t.add(Tema.espacio(14));
        JLabel lm = etiqueta("¿Por qué se anula?");
        t.add(lm);
        t.add(Tema.espacio(5));
        JTextField motivo = Tema.campo("Ej. La factura se registró dos veces");
        motivo.getAccessibleContext().setAccessibleName("Motivo de la anulación");
        motivo.setAlignmentX(LEFT_ALIGNMENT);
        Tema.anchoCompleto(motivo);
        t.add(motivo);
        JLabel error = new JLabel(" ");
        error.setFont(Tema.texto(12.5f));
        error.setForeground(Tema.ERROR_TEXTO);
        error.setAlignmentX(LEFT_ALIGNMENT);
        t.add(Tema.espacio(4));
        t.add(error);
        t.add(Tema.espacio(10));
        Tema.Boton si = new Tema.Boton("Sí, anular compra", Tema.BLANCO, Tema.ERROR_TEXTO, Tema.ERROR);
        Tema.Boton no = Tema.botonSecundario("Volver");
        JPanel f = Tema.fila(8, no, si);
        f.setAlignmentX(LEFT_ALIGNMENT);
        t.add(f);
        Capa capa = new Capa(this, t, true);
        Runnable hacer = () -> {
            try {
                List<Long> ids = bd.productosDeCompra(cg.getId());
                bd.anularCompra(cg.getId(), app.getSesion().getUsuario().getId(), motivo.getText());
                capa.cerrar();
                for (long id : ids) {
                    try {
                        app.getCatalogo().recargarProducto(id);
                    } catch (SQLException ex) {
                        Ubicaciones.registrarError(ex);
                    }
                }
                buscar();
                Aviso.mostrar(this, Aviso.Tipo.BIEN, "Compra No. " + Comprobante.numeroTexto(cg.getNumero()) + " anulada",
                        "El stock ya se devolvió");
            } catch (IllegalArgumentException | IllegalStateException ex) {
                error.setText(ex.getMessage());
            } catch (SQLException ex) {
                Ubicaciones.registrarError(ex);
                error.setText("No se pudo anular: " + ex.getMessage());
            }
        };
        si.addActionListener(e -> hacer.run());
        motivo.addActionListener(e -> hacer.run());
        no.addActionListener(e -> capa.cerrar());
        capa.mostrar(motivo);
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
            BaseTienda.CompraGuardada cg = pagina.get(r);
            int porPagina = (Integer) comboPorPagina.getSelectedItem();
            switch (c) {
                case 0:
                    return (numeroPagina - 1) * porPagina + r + 1;
                case 1:
                    return Comprobante.numeroTexto(cg.getNumero());
                case 2:
                    return cg.getBodega();
                case 3:
                    return cg.getFactura().isEmpty() ? "—" : cg.getFactura();
                case 4:
                    return textoFecha(cg.getFecha());
                case 5:
                    return cg.getProveedor().isEmpty() ? "Sin proveedor" : cg.getProveedor();
                case 6:
                    return cg.getAtendio();
                case COL_VALOR:
                    return Tarifa.pesos(cg.getTotal());
                case COL_ESTADO:
                    return cg.isAnulada() ? "ANULADO" : "PAGADO";
                default:
                    return "";
            }
        }
    }

    private class Celda extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foco, int r, int c) {
            super.getTableCellRendererComponent(t, v, sel, false, r, c);
            BaseTienda.CompraGuardada cg = pagina.get(r);
            setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10));
            setFont(Tema.texto(13.5f));
            if (c == 2) {
                setFont(Tema.texto(12.5f));
                setToolTipText(String.valueOf(v));
            }
            setHorizontalAlignment(c == 5 ? LEFT : c == COL_VALOR ? RIGHT : CENTER);
            setBackground(sel ? Tema.AZUL_SUAVE : (r % 2 == 1 ? new Color(0xFAFBFC) : Color.WHITE));
            setForeground(cg.isAnulada() ? Tema.GRIS_TEXTO : Tema.GRIS_OSCURO);
            if (c != 2) {
                setToolTipText(null);
            }
            if (c == 5 && cg.getProveedor().isEmpty()) {
                setForeground(Tema.GRIS_TEXTO);
            } else if (c == COL_VALOR) {
                setFont(Tema.textoFuerte(13.5f));
            } else if (c == COL_ESTADO) {
                // estado como texto de color, sin pildora (manual de marca)
                setFont(Tema.textoFuerte(13));
                setForeground(cg.isAnulada() ? Tema.ERROR_TEXTO : Tema.EXITO_TEXTO);
            }
            return this;
        }
    }

    /** Ver (lupa) y anular (papelera). Una compra anulada solo se puede ver. */
    private class Acciones extends DefaultTableCellRenderer {
        private BaseTienda.CompraGuardada cg;
        private boolean sel;
        private int fila;

        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foco, int r, int c) {
            this.cg = pagina.get(r);
            this.sel = sel;
            this.fila = r;
            setText("");
            setToolTipText(cg.isAnulada() ? "Ver la compra" : "Ver la compra  ·  Anular");
            return this;
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Tema.suavizar(g);
            g.setColor(sel ? Tema.AZUL_SUAVE : (fila % 2 == 1 ? new Color(0xFAFBFC) : Color.WHITE));
            g.fillRect(0, 0, getWidth(), getHeight());
            int cy = getHeight() / 2;
            if (cg.isAnulada()) {
                PanelProductos.Icono.LUPA.dibujar(g, getWidth() / 2 - 9, cy - 9, 18, Tema.AZUL);
            } else {
                PanelProductos.Icono.LUPA.dibujar(g, getWidth() / 2 - 24, cy - 9, 18, Tema.AZUL);
                PanelProductos.Icono.PAPELERA.dibujar(g, getWidth() / 2 + 6, cy - 9, 18, Tema.ERROR);
            }
            g.dispose();
        }
    }

    /** Texto de una cantidad con su unidad ("3", "2,5 kg"). */
    static String cantidad(long milesimas, Unidad u) {
        String n = PanelProductos.cantidad(milesimas);
        return u == Unidad.UNIDAD ? n : n + (u == Unidad.KILO ? " kg" : " lb");
    }

    static String porcentaje(long centesimas) {
        return Carrito.textoPorcentaje(centesimas) + " %";
    }
}
