package rutas.vista;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
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
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import rutas.logica.ListadoProductos;
import rutas.logica.Ubicaciones;
import rutas.logica.Xlsx;

/**
 * Lo que tienen en comun las pantallas de Gastos (igual que el Historial de compras): arriba
 * «Por página», el titulo y Exportar; una tarjeta con los filtros, la tabla con sus acciones
 * (iconos o botoncitos dibujados en la celda), el aviso de «no hay registros» y las paginas.
 */
abstract class ListaGastos<T> extends JPanel {

    private static final Integer[] POR_PAGINA = {10, 25, 50, 100};

    /** Un botoncito dentro de una celda: icono, o texto con borde si no hay icono. */
    static final class Accion {
        final PanelProductos.Icono icono;
        final String texto;
        final Color color;
        final String ayuda;
        final Runnable hacer;

        Accion(PanelProductos.Icono icono, String texto, Color color, String ayuda, Runnable hacer) {
            this.icono = icono;
            this.texto = texto;
            this.color = color;
            this.ayuda = ayuda;
            this.hacer = hacer;
        }

        int ancho(FontMetrics fm) {
            return icono != null ? 18 : fm.stringWidth(texto) + 20;
        }
    }

    protected final Aplicacion app;
    private final String titulo;
    private final String[] columnas;
    private final int[] anchos;
    protected final JComboBox<Integer> comboPorPagina = new JComboBox<>(POR_PAGINA);
    protected final JLabel lblError = new JLabel(" ");
    private final Modelo modelo = new Modelo();
    protected final JTable tabla;
    private final CardLayout tarjetas = new CardLayout();
    private final JPanel zona = new JPanel(tarjetas);
    private final JLabel lblPaginas = new JLabel();
    private final JPanel botonesPagina = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 0));
    protected List<T> filas = new ArrayList<>();
    private List<T> pagina = new ArrayList<>();
    private int numeroPagina = 1;
    private int totalPaginas = 1;
    private final JLabel lblVacio = new JLabel();
    private final JLabel lblVacio2 = new JLabel();

    ListaGastos(Aplicacion app, String titulo, String[] columnas, int[] anchos, String vacio, String vacio2,
                String iconoVacio) {
        super(new BorderLayout());
        this.app = app;
        this.titulo = titulo;
        this.columnas = columnas;
        this.anchos = anchos;
        this.tabla = new JTable(modelo) {
            // si la ventana es angosta aparece la barra de abajo en vez de apretar las columnas de mas
            @Override
            public boolean getScrollableTracksViewportWidth() {
                return getParent() == null || getMinimumSize().width <= getParent().getWidth();
            }

            @Override
            public Dimension getMinimumSize() {
                int w = 0;
                for (int i = 0; i < getColumnModel().getColumnCount(); i++) {
                    w += getColumnModel().getColumn(i).getMinWidth();
                }
                return new Dimension(w, super.getMinimumSize().height);
            }
        };
        lblVacio.setText(vacio);
        lblVacio2.setText(vacio2);
        setBackground(Tema.GRIS_CLARO);
        setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));
        this.iconoVacio = iconoVacio;
    }

    private final String iconoVacio;

    /** Arma la pantalla: cada subclase lo llama al final de su constructor (ya con sus campos listos). */
    protected final void armar() {
        add(encabezado(), BorderLayout.NORTH);
        Tema.Tarjeta tarjeta = new Tema.Tarjeta();
        tarjeta.setLayout(new BorderLayout(0, 12));
        JPanel arriba = new JPanel(new BorderLayout(0, 8));
        arriba.setOpaque(false);
        JComponent f = filtros();
        if (f != null) {
            arriba.add(f, BorderLayout.CENTER);
        }
        lblError.setFont(Tema.texto(13));
        lblError.setForeground(Tema.ERROR_TEXTO);
        arriba.add(lblError, BorderLayout.SOUTH);
        arriba.registerKeyboardAction(e -> consultar(), KeyStroke.getKeyStroke("ENTER"),
                JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        tarjeta.add(arriba, BorderLayout.NORTH);
        tarjeta.add(zonaTabla(iconoVacio), BorderLayout.CENTER);
        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        lblPaginas.setFont(Tema.texto(14));
        lblPaginas.setForeground(Tema.GRIS_OSCURO);
        p.add(lblPaginas, BorderLayout.NORTH);
        botonesPagina.setOpaque(false);
        p.add(botonesPagina, BorderLayout.CENTER);
        tarjeta.add(p, BorderLayout.SOUTH);
        add(tarjeta, BorderLayout.CENTER);
        addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentShown(java.awt.event.ComponentEvent e) {
                alMostrar();
                consultar();
            }
        });
    }

    // ------------------------------------------------------------------
    //  Lo que pone cada pantalla
    // ------------------------------------------------------------------

    /** Los filtros (null si no tiene). */
    protected abstract JComponent filtros();

    /** Lee las filas con los filtros (IllegalArgumentException = error del usuario, se muestra en rojo). */
    protected abstract List<T> leer() throws Exception;

    /** El texto de la celda (las columnas de acciones devuelven ""). */
    protected abstract Object valor(T t, int columna);

    /** Botoncitos de esa celda (vacio si no es columna de acciones). */
    protected List<Accion> acciones(T t, int columna) {
        return List.of();
    }

    /** Color del texto (null = el normal). */
    protected Color color(T t, int columna) {
        return null;
    }

    /**
     * Lo menos que se aprieta una columna: los pesos, el estado y las acciones nunca se cortan;
     * los textos largos si (bajan a otra linea).
     */
    protected int minimo(int columna, int ancho) {
        return alineacion(columna) == SwingConstants.RIGHT || "Estado".equals(columnas[columna])
                || "Acción".equals(columnas[columna]) || "Pagar".equals(columnas[columna]) ? ancho : Math.min(ancho, 64);
    }

    /** Parte de 0 a 1 para pintar una barra en la celda (menos de 0 = sin barra). */
    protected double barra(T t, int columna) {
        return -1;
    }

    protected boolean negrita(T t, int columna) {
        return false;
    }

    protected int alineacion(int columna) {
        return SwingConstants.CENTER;
    }

    /** Lo que va al lado de «Páginas: N». */
    protected String resumen(List<T> filas) {
        return "";
    }

    /** Doble clic o Enter en una fila. */
    protected void abrir(T t) {
    }

    /** Antes de consultar cada vez que la pantalla se muestra (recargar combos). */
    protected void alMostrar() {
    }

    /** Lo que va en el PDF a la derecha (por defecto, las columnas de valores). */
    protected boolean derechaPdf(int columna) {
        return alineacion(columna) == SwingConstants.RIGHT;
    }

    /** Columnas que no salen al exportar (las de acciones). */
    protected boolean exporta(int columna) {
        return !"Acción".equals(columnas[columna]) && !"Pagar".equals(columnas[columna]);
    }

    protected String subtituloPdf() {
        return "";
    }

    // ------------------------------------------------------------------
    //  Partes
    // ------------------------------------------------------------------

    private JComponent encabezado() {
        JPanel h = new JPanel(new BorderLayout());
        h.setOpaque(false);
        h.setBorder(BorderFactory.createEmptyBorder(0, 0, 14, 0));
        Tema.estiloCombo(comboPorPagina, String::valueOf);
        comboPorPagina.setPreferredSize(new Dimension(96, 34));
        comboPorPagina.getAccessibleContext().setAccessibleName("Filas por página");
        comboPorPagina.addActionListener(e -> {
            numeroPagina = 1;
            mostrarPagina();
        });
        JPanel izq = PanelProductos.columna("Por página", comboPorPagina);
        h.add(izq, BorderLayout.WEST);
        JLabel t = new JLabel(titulo, SwingConstants.CENTER);
        t.setFont(Tema.titulo(24));
        t.setForeground(Tema.GRIS_OSCURO);
        h.add(t, BorderLayout.CENTER);
        JPanel botones = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        botones.setOpaque(false);
        botones.add(new PanelProductos.BotonIcono("Excel", PanelProductos.Icono.EXCEL, "Exportar a Excel", this::excel));
        botones.add(new PanelProductos.BotonIcono("PDF", PanelProductos.Icono.PDF, "Ver, imprimir o guardar como PDF",
                this::pdf));
        JPanel der = PanelProductos.columna("Exportar", botones);
        h.add(der, BorderLayout.EAST);
        int ancho = Math.max(izq.getPreferredSize().width, der.getPreferredSize().width);
        izq.setPreferredSize(new Dimension(ancho, izq.getPreferredSize().height));
        der.setPreferredSize(new Dimension(ancho, der.getPreferredSize().height));
        return h;
    }

    private JComponent zonaTabla(String iconoVacio) {
        tabla.setRowHeight(52);
        tabla.setShowVerticalLines(true);
        tabla.setShowHorizontalLines(true);
        tabla.setGridColor(Tema.BORDE);
        tabla.setIntercellSpacing(new Dimension(1, 1));
        tabla.setFillsViewportHeight(true);
        tabla.setBackground(Color.WHITE);
        tabla.setSelectionBackground(Tema.AZUL_SUAVE);
        tabla.setSelectionForeground(Tema.GRIS_OSCURO);
        tabla.setFont(Tema.texto(14));
        tabla.getAccessibleContext().setAccessibleName(titulo);
        tabla.setDefaultRenderer(Object.class, new Celda());
        tabla.getTableHeader().setReorderingAllowed(false);
        tabla.getTableHeader().setPreferredSize(new Dimension(100, 60));
        tabla.getTableHeader().setDefaultRenderer(new PanelProductos.Encabezado());
        for (int i = 0; i < anchos.length; i++) {
            tabla.getColumnModel().getColumn(i).setPreferredWidth(anchos[i]);
            tabla.getColumnModel().getColumn(i).setMinWidth(minimo(i, anchos[i]));
        }
        tabla.getColumnModel().getColumn(0).setMaxWidth(64);
        tabla.getColumnModel().getColumn(0).setMinWidth(44);
        tabla.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int f = tabla.rowAtPoint(e.getPoint());
                int c = tabla.columnAtPoint(e.getPoint());
                if (f < 0 || f >= pagina.size()) {
                    return;
                }
                Accion a = accionEn(f, c, e.getX());
                if (a != null) {
                    a.hacer.run();
                } else if (e.getClickCount() == 2) {
                    abrir(pagina.get(f));
                }
            }
        });
        tabla.addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                int f = tabla.rowAtPoint(e.getPoint());
                int c = tabla.columnAtPoint(e.getPoint());
                Accion a = f >= 0 && f < pagina.size() ? accionEn(f, c, e.getX()) : null;
                tabla.setCursor(a != null ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
                tabla.setToolTipText(a != null ? a.ayuda : null);
            }
        });
        tabla.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ENTER"), "abrir");
        tabla.getActionMap().put("abrir", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                int f = tabla.getSelectedRow();
                if (f >= 0 && f < pagina.size()) {
                    abrir(pagina.get(f));
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
        JLabel ic = new JLabel(Iconos.de(iconoVacio, 40, Tema.AZUL));
        ic.setAlignmentX(CENTER_ALIGNMENT);
        txt.add(ic);
        txt.add(Box.createVerticalStrut(12));
        lblVacio.setFont(Tema.subtitulo(17));
        lblVacio.setForeground(Tema.AZUL_PROFUNDO);
        lblVacio.setAlignmentX(CENTER_ALIGNMENT);
        txt.add(lblVacio);
        txt.add(Box.createVerticalStrut(6));
        lblVacio2.setFont(Tema.texto(14));
        lblVacio2.setForeground(Tema.GRIS_TEXTO);
        lblVacio2.setAlignmentX(CENTER_ALIGNMENT);
        txt.add(lblVacio2);
        vacia.add(txt);
        zona.setOpaque(false);
        zona.add(sc, "tabla");
        zona.add(vacia, "vacia");
        return zona;
    }

    /** Que botoncito queda bajo el raton (o null). */
    private Accion accionEn(int f, int c, int x) {
        List<Accion> l = acciones(pagina.get(f), c);
        if (l.isEmpty()) {
            return null;
        }
        java.awt.Rectangle r = tabla.getCellRect(f, c, false);
        FontMetrics fm = tabla.getFontMetrics(Tema.textoFuerte(12.5f));
        int[] xs = posiciones(l, fm, r.width);
        for (int i = 0; i < l.size(); i++) {
            int a = r.x + xs[i];
            if (x >= a - 4 && x <= a + l.get(i).ancho(fm) + 4) {
                return l.get(i);
            }
        }
        return null;
    }

    private static int[] posiciones(List<Accion> l, FontMetrics fm, int ancho) {
        int sep = 12, total = -sep;
        for (Accion a : l) {
            total += a.ancho(fm) + sep;
        }
        int[] xs = new int[l.size()];
        int x = (ancho - total) / 2;
        for (int i = 0; i < l.size(); i++) {
            xs[i] = x;
            x += l.get(i).ancho(fm) + sep;
        }
        return xs;
    }

    /** Fecha con su hora debajo (como en PuveSoft). */
    static JComponent fechaConHora(String titulo, Controles.CampoFecha fecha, JComboBox<LocalTime> hora) {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.add(etiqueta(titulo));
        p.add(Box.createVerticalStrut(5));
        fecha.setAlignmentX(LEFT_ALIGNMENT);
        p.add(fecha);
        p.add(Box.createVerticalStrut(8));
        Tema.estiloCombo(hora, PanelHistorialCompras::textoHora);
        hora.getAccessibleContext().setAccessibleName("Hora de " + titulo.toLowerCase());
        p.add(Controles.ancho(hora));
        return p;
    }

    /** Dos filtros uno encima del otro. */
    static JComponent apilados(String t1, JComponent c1, String t2, JComponent c2) {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.add(etiqueta(t1));
        p.add(Box.createVerticalStrut(5));
        p.add(Controles.ancho(c1));
        if (c2 != null) {
            p.add(Box.createVerticalStrut(8));
            p.add(etiqueta(t2));
            p.add(Box.createVerticalStrut(5));
            p.add(Controles.ancho(c2));
        }
        return p;
    }

    /** Un boton con su texto pequeño debajo (Fijos / Gastos fijos). */
    static JComponent conLeyenda(JComponent boton, String leyenda) {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.add(Box.createVerticalStrut(etiqueta("X").getPreferredSize().height + 5));
        boton.setAlignmentX(CENTER_ALIGNMENT);
        p.add(boton);
        if (leyenda != null) {
            p.add(Box.createVerticalStrut(4));
            JLabel l = new JLabel(leyenda);
            l.setFont(Tema.texto(12));
            l.setForeground(Tema.GRIS_TEXTO);
            l.setAlignmentX(CENTER_ALIGNMENT);
            p.add(l);
        }
        return p;
    }

    static JLabel etiqueta(String t) {
        JLabel l = new JLabel(t);
        l.setFont(Tema.textoFuerte(13.5f));
        l.setForeground(Tema.GRIS_OSCURO);
        l.setAlignmentX(LEFT_ALIGNMENT);
        return l;
    }

    static JComboBox<LocalTime> comboHoras(boolean fin) {
        JComboBox<LocalTime> c = new JComboBox<>();
        for (int m = 0; m < 24 * 60; m += 30) {
            c.addItem(LocalTime.of(m / 60, m % 60));
        }
        c.addItem(LocalTime.of(23, 59));
        c.setMaximumRowCount(12);
        c.setSelectedIndex(fin ? c.getItemCount() - 1 : 0);
        return c;
    }

    /** Fecha y hora juntas; error claro si la fecha esta mal escrita. */
    static LocalDateTime momento(Controles.CampoFecha f, JComboBox<LocalTime> h) {
        LocalDate d = f.getFecha();
        if (d == null) {
            throw new IllegalArgumentException("Escriba la fecha inicial y la fecha final (dd/mm/aaaa)");
        }
        return d.atTime((LocalTime) h.getSelectedItem());
    }

    // ------------------------------------------------------------------
    //  Datos y paginas
    // ------------------------------------------------------------------

    /** Lee otra vez con los filtros y vuelve a la pagina 1. */
    void consultar() {
        numeroPagina = 1;
        recargar();
    }

    /** Lee otra vez quedandose en la misma pagina (despues de guardar o anular). */
    void recargar() {
        try {
            filas = leer();
            lblError.setText(" ");
        } catch (IllegalArgumentException e) {
            lblError.setText(e.getMessage());
            filas = new ArrayList<>();
        } catch (Exception e) {
            Ubicaciones.registrarError(e);
            lblError.setText("No se pudo leer: " + e.getMessage());
            filas = new ArrayList<>();
        }
        mostrarPagina();
    }

    private void mostrarPagina() {
        int porPagina = (Integer) comboPorPagina.getSelectedItem();
        totalPaginas = Math.max(1, (filas.size() + porPagina - 1) / porPagina);
        numeroPagina = Math.max(1, Math.min(numeroPagina, totalPaginas));
        int desde = (numeroPagina - 1) * porPagina;
        pagina = new ArrayList<>(filas.subList(Math.min(desde, filas.size()), Math.min(desde + porPagina, filas.size())));
        modelo.fireTableDataChanged();
        tarjetas.show(zona, filas.isEmpty() ? "vacia" : "tabla");
        String r = resumen(filas);
        lblPaginas.setText("Páginas: " + totalPaginas + (r.isEmpty() ? "" : "    ·    " + r));
        botonesPagina.removeAll();
        if (totalPaginas > 1) {
            botonesPagina.add(new PanelProductos.BotonPagina("«", "Página anterior", numeroPagina > 1, false,
                    () -> irAPagina(numeroPagina - 1)));
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
                            n == numeroPagina, () -> irAPagina(destino)));
                }
            }
            botonesPagina.add(new PanelProductos.BotonPagina("»", "Página siguiente", numeroPagina < totalPaginas,
                    false, () -> irAPagina(numeroPagina + 1)));
        }
        botonesPagina.revalidate();
        botonesPagina.repaint();
    }

    private void irAPagina(int n) {
        numeroPagina = n;
        mostrarPagina();
    }

    /** Fila de la pagina visible (para las pruebas). */
    T filaVisible(int i) {
        return pagina.get(i);
    }

    int filasVisibles() {
        return pagina.size();
    }

    // ------------------------------------------------------------------
    //  Exportar (todas las filas del filtro, no solo la pagina)
    // ------------------------------------------------------------------

    private List<Integer> columnasExporta() {
        List<Integer> l = new ArrayList<>();
        for (int c = 0; c < columnas.length; c++) {
            if (exporta(c)) {
                l.add(c);
            }
        }
        return l;
    }

    private String texto(T t, int fila, int c) {
        return c == 0 ? String.valueOf(fila + 1) : String.valueOf(valor(t, c));
    }

    private void excel() {
        if (filas.isEmpty()) {
            Aviso.mostrar(this, Aviso.Tipo.ERROR, "No hay registros para exportar", "Cambie los filtros y oprima «Consultar»");
            return;
        }
        File f = Archivos.guardar(this, "Exportar a Excel", titulo.toLowerCase().replace(' ', '_') + ".xlsx", "xlsx");
        if (f == null) {
            return;
        }
        List<Integer> cs = columnasExporta();
        String[] enc = new String[cs.size()];
        for (int i = 0; i < cs.size(); i++) {
            enc[i] = columnas[cs.get(i)];
        }
        List<Object[]> l = new ArrayList<>();
        for (int i = 0; i < filas.size(); i++) {
            Object[] o = new Object[cs.size()];
            for (int k = 0; k < cs.size(); k++) {
                String s = texto(filas.get(i), i, cs.get(k));
                // los pesos van como numero para poder sumarlos en Excel
                o[k] = s.matches("-?\\$ ?[\\d.]+") ? Long.parseLong(s.replaceAll("[^\\d-]", "")) : s;
            }
            l.add(o);
        }
        try {
            Xlsx.escribir(f, titulo.length() > 30 ? titulo.substring(0, 30) : titulo, enc, l);
            Aviso.mostrar(this, Aviso.Tipo.BIEN, "Excel guardado", l.size() + " filas en " + f.getName());
        } catch (java.io.IOException e) {
            Alerta.mostrar(this, Alerta.Tipo.AVISO, "No se pudo guardar el Excel",
                    "Revise que el archivo no esté abierto en Excel. Detalle: " + e.getMessage(), null);
        }
    }

    private void pdf() {
        if (filas.isEmpty()) {
            Aviso.mostrar(this, Aviso.Tipo.ERROR, "No hay registros para exportar", "Cambie los filtros y oprima «Consultar»");
            return;
        }
        List<Integer> cs = columnasExporta();
        String[] enc = new String[cs.size()];
        float[] pesos = new float[cs.size()];
        boolean[] der = new boolean[cs.size()];
        for (int i = 0; i < cs.size(); i++) {
            enc[i] = columnas[cs.get(i)];
            pesos[i] = Math.max(0.6f, anchos[cs.get(i)] / 100f);
            der[i] = derechaPdf(cs.get(i));
        }
        List<String[]> l = new ArrayList<>();
        for (int i = 0; i < filas.size(); i++) {
            String[] o = new String[cs.size()];
            for (int k = 0; k < cs.size(); k++) {
                o[k] = texto(filas.get(i), i, cs.get(k));
            }
            l.add(o);
        }
        String s = subtituloPdf();
        DocumentoLista doc = new DocumentoLista(titulo + (s.isEmpty() ? "" : " · " + s), enc, pesos, der, l,
                app.getAjustesRecibo());
        VistaPrevia.mostrar(this, doc, titulo);
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
            return columnas.length;
        }

        @Override
        public String getColumnName(int c) {
            return columnas[c];
        }

        @Override
        public Object getValueAt(int r, int c) {
            if (c == 0) {
                return (numeroPagina - 1) * (Integer) comboPorPagina.getSelectedItem() + r + 1;
            }
            return valor(pagina.get(r), c);
        }
    }

    private class Celda extends DefaultTableCellRenderer {
        private List<Accion> botones = List.of();
        private boolean sel;
        private int fila;
        private double parte = -1;

        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foco, int r, int c) {
            T x = pagina.get(r);
            botones = acciones(x, c);
            parte = barra(x, c);
            this.sel = sel;
            this.fila = r;
            super.getTableCellRendererComponent(t, botones.isEmpty() ? v : "", sel, false, r, c);
            setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
            setFont(negrita(x, c) ? Tema.textoFuerte(13f) : Tema.texto(13f));
            setHorizontalAlignment(alineacion(c));
            setBackground(sel ? Tema.AZUL_SUAVE : (r % 2 == 1 ? new Color(0xFAFBFC) : Color.WHITE));
            Color k = color(x, c);
            setForeground(k != null ? k : Tema.GRIS_OSCURO);
            String s = String.valueOf(v);
            setToolTipText(botones.isEmpty() && s.length() > 22 ? s : null);
            if (botones.isEmpty() && s.length() > 9 && alineacion(c) != SwingConstants.RIGHT) {
                // el texto largo baja a una segunda linea (como en la referencia) en vez de cortarse
                String al = alineacion(c) == SwingConstants.LEFT ? "left" : "center";
                setText("<html><div style='text-align:" + al + "'>" + PanelGastos.escapar(s) + "</div></html>");
            }
            return this;
        }

        @Override
        protected void paintComponent(Graphics g0) {
            if (botones.isEmpty() && parte >= 0) {
                // barra suave detras del porcentaje (en azul del manual)
                Graphics2D g = (Graphics2D) g0.create();
                g.setColor(getBackground());
                g.fillRect(0, 0, getWidth(), getHeight());
                int w = (int) Math.round((getWidth() - 20) * Math.min(1, parte));
                g.setColor(new Color(0x005198 & 0xFFFFFF | 0x33000000, true));
                g.fillRect(10, getHeight() / 2 - 9, Math.max(w, parte > 0 ? 2 : 0), 18);
                g.dispose();
                setOpaque(false);
                super.paintComponent(g0);
                setOpaque(true);
                return;
            }
            if (botones.isEmpty()) {
                super.paintComponent(g0);
                return;
            }
            Graphics2D g = (Graphics2D) g0.create();
            Tema.suavizar(g);
            g.setColor(sel ? Tema.AZUL_SUAVE : (fila % 2 == 1 ? new Color(0xFAFBFC) : Color.WHITE));
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setFont(Tema.textoFuerte(12.5f));
            FontMetrics fm = g.getFontMetrics();
            int[] xs = posiciones(botones, fm, getWidth());
            int cy = getHeight() / 2;
            for (int i = 0; i < botones.size(); i++) {
                Accion a = botones.get(i);
                if (a.icono != null) {
                    a.icono.dibujar(g, xs[i], cy - 9, 18, a.color);
                } else {
                    int w = a.ancho(fm), h = 26;
                    g.setColor(Color.WHITE);
                    g.fillRoundRect(xs[i], cy - h / 2, w, h, Tema.RADIO * 2, Tema.RADIO * 2);
                    g.setColor(a.color);
                    g.drawRoundRect(xs[i], cy - h / 2, w - 1, h - 1, Tema.RADIO * 2, Tema.RADIO * 2);
                    g.drawString(a.texto, xs[i] + 10, cy + (fm.getAscent() - fm.getDescent()) / 2);
                }
            }
            g.dispose();
        }
    }
}
