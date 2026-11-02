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
import java.awt.Image;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import javax.swing.SwingWorker;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import rutas.logica.BaseTienda;
import rutas.logica.ImportadorCategorias;
import rutas.logica.ListadoProductos;
import rutas.logica.Ubicaciones;
import rutas.logica.Xlsx;
import rutas.modelo.Categoria;

/**
 * Inventario › Categorias: lista con buscador y paginas, nueva, editar, desactivar o borrar
 * (si no tiene productos), importar desde Excel y exportar a Excel o PDF.
 * Las categorias son los botones con foto de arriba en Vender.
 */
public class PanelCategoriasLista extends JPanel {

    private static final Integer[] POR_PAGINA = {10, 25, 50, 100};
    private static final String[] COLUMNAS = {"Item", "Código", "Imagen", "Nombre", "Productos", "Orden en Vender",
        "Estado", "Acciones"};
    private static final int COL_IMAGEN = 2;
    private static final int COL_ACCIONES = 7;

    private final Aplicacion app;
    private final BaseTienda bd;
    private final JComboBox<Integer> comboPorPagina = new JComboBox<>(POR_PAGINA);
    private final JTextField campoBuscar = Tema.campo("Buscar por nombre o código");
    private final Modelo modelo = new Modelo();
    private final JTable tabla = new JTable(modelo);
    private final CardLayout tarjetasTabla = new CardLayout();
    private final JPanel zonaTabla = new JPanel(tarjetasTabla);
    private final JLabel lblVacia = new JLabel();
    private final JLabel lblPaginas = new JLabel();
    private final JPanel botonesPagina = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 0));
    private List<Categoria> todas = new ArrayList<>();
    private List<Categoria> filtradas = new ArrayList<>();
    private List<Categoria> pagina = new ArrayList<>();
    private Map<Long, Integer> productos = new HashMap<>();
    private int numeroPagina = 1;
    private int totalPaginas = 1;

    public PanelCategoriasLista(Aplicacion app) {
        super(new BorderLayout());
        this.app = app;
        this.bd = app.getTienda();
        setBackground(Tema.GRIS_CLARO);
        setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));
        putClientProperty(Capa.FOCO_AL_CERRAR, campoBuscar);
        add(encabezado(), BorderLayout.NORTH);
        Tema.Tarjeta tarjeta = new Tema.Tarjeta();
        tarjeta.setLayout(new BorderLayout(0, 12));
        tarjeta.add(barra(), BorderLayout.NORTH);
        tarjeta.add(zonaTabla(), BorderLayout.CENTER);
        tarjeta.add(pie(), BorderLayout.SOUTH);
        add(tarjeta, BorderLayout.CENTER);
        addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentShown(java.awt.event.ComponentEvent e) {
                refrescar();
                javax.swing.SwingUtilities.invokeLater(campoBuscar::requestFocusInWindow);
            }
        });
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("control N"), "nueva");
        getActionMap().put("nueva", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (isShowing()) {
                    nueva();
                }
            }
        });
        refrescar();
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
        comboPorPagina.getAccessibleContext().setAccessibleName("Categorías por página");
        comboPorPagina.addActionListener(e -> {
            numeroPagina = 1;
            mostrarPagina();
        });
        JPanel izq = PanelProductos.columna("Por página", comboPorPagina);
        h.add(izq, BorderLayout.WEST);
        JLabel titulo = new JLabel("Lista de categorías de productos", SwingConstants.CENTER);
        titulo.setFont(Tema.titulo(24));
        titulo.setForeground(Tema.GRIS_OSCURO);
        h.add(titulo, BorderLayout.CENTER);
        JPanel botones = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        botones.setOpaque(false);
        botones.add(new PanelProductos.BotonIcono("Excel", PanelProductos.Icono.EXCEL, "Exportar las categorías a Excel",
                this::exportarExcel));
        botones.add(new PanelProductos.BotonIcono("PDF", PanelProductos.Icono.PDF,
                "Ver, imprimir o guardar la lista como PDF", this::exportarPdf));
        JPanel der = PanelProductos.columna("Exportar", botones);
        h.add(der, BorderLayout.EAST);
        int ancho = Math.max(izq.getPreferredSize().width, der.getPreferredSize().width);
        izq.setPreferredSize(new Dimension(ancho, izq.getPreferredSize().height));
        der.setPreferredSize(new Dimension(ancho, der.getPreferredSize().height));
        return h;
    }

    private JComponent barra() {
        JPanel b = new JPanel(new GridBagLayout());
        b.setOpaque(false);
        GridBagConstraints g = new GridBagConstraints();
        g.anchor = GridBagConstraints.CENTER;
        JLabel ayuda = new JLabel("Son los botones con foto de arriba en Vender.");
        ayuda.setFont(Tema.texto(13));
        ayuda.setForeground(Tema.GRIS_TEXTO);
        g.anchor = GridBagConstraints.WEST;
        b.add(ayuda, g);
        g.weightx = 1;
        b.add(Box.createHorizontalGlue(), g);
        g.weightx = 0;
        Tema.Boton importar = Tema.botonPlano("Importar categorías");
        importar.setIcon(Iconos.de("subir", 18, Tema.AZUL));
        importar.setToolTipText("Traer las categorías desde un archivo de Excel");
        importar.addActionListener(e -> abrirImportar());
        g.insets = new Insets(0, 0, 0, 12);
        b.add(importar, g);
        campoBuscar.setPreferredSize(new Dimension(280, 40));
        campoBuscar.setMinimumSize(new Dimension(200, 40));
        campoBuscar.getDocument().addDocumentListener(PanelProductos.alCambiar(() -> {
            numeroPagina = 1;
            filtrar();
        }));
        b.add(campoBuscar, g);
        Tema.Boton nueva = Tema.botonAccion("Nueva categoría");
        nueva.setToolTipText("Crear una categoría (Ctrl+N)");
        nueva.addActionListener(e -> nueva());
        g.insets = new Insets(0, 0, 0, 0);
        b.add(nueva, g);
        return b;
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
        tabla.getAccessibleContext().setAccessibleName("Tabla de categorías");
        tabla.setDefaultRenderer(Object.class, new Celda());
        tabla.getTableHeader().setReorderingAllowed(false);
        tabla.getTableHeader().setPreferredSize(new Dimension(100, 44));
        tabla.getTableHeader().setDefaultRenderer(new PanelProductos.Encabezado());
        int[] anchos = {60, 100, 80, 360, 110, 140, 120, 110};
        for (int i = 0; i < anchos.length; i++) {
            tabla.getColumnModel().getColumn(i).setPreferredWidth(anchos[i]);
        }
        tabla.getColumnModel().getColumn(0).setMaxWidth(70);
        tabla.getColumnModel().getColumn(COL_IMAGEN).setMaxWidth(90);
        tabla.getColumnModel().getColumn(COL_IMAGEN).setCellRenderer(new Miniatura());
        tabla.getColumnModel().getColumn(COL_ACCIONES).setCellRenderer(new Acciones());
        tabla.getColumnModel().getColumn(COL_ACCIONES).setMinWidth(96);
        tabla.getColumnModel().getColumn(COL_ACCIONES).setMaxWidth(110);
        tabla.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int f = tabla.rowAtPoint(e.getPoint());
                int c = tabla.columnAtPoint(e.getPoint());
                if (f < 0 || f >= pagina.size()) {
                    return;
                }
                Categoria cat = pagina.get(f);
                if (c == COL_ACCIONES) {
                    java.awt.Rectangle r = tabla.getCellRect(f, c, false);
                    if (e.getX() < r.x + r.width / 2) {
                        editar(cat);
                    } else {
                        quitar(cat);
                    }
                } else if (e.getClickCount() == 2) {
                    editar(cat);
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
        tabla.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ENTER"), "editar");
        tabla.getActionMap().put("editar", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                int f = tabla.getSelectedRow();
                if (f >= 0 && f < pagina.size()) {
                    editar(pagina.get(f));
                }
            }
        });
        tabla.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("DELETE"), "quitar");
        tabla.getActionMap().put("quitar", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                int f = tabla.getSelectedRow();
                if (f >= 0 && f < pagina.size()) {
                    quitar(pagina.get(f));
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
        JLabel ic = new JLabel(Iconos.de("categorias", 40, Tema.AZUL));
        ic.setAlignmentX(CENTER_ALIGNMENT);
        txt.add(ic);
        txt.add(Box.createVerticalStrut(12));
        JLabel t = new JLabel("No hay categorías para mostrar");
        t.setFont(Tema.subtitulo(18));
        t.setForeground(Tema.AZUL_PROFUNDO);
        t.setAlignmentX(CENTER_ALIGNMENT);
        txt.add(t);
        txt.add(Box.createVerticalStrut(6));
        lblVacia.setFont(Tema.texto(14));
        lblVacia.setForeground(Tema.GRIS_TEXTO);
        lblVacia.setAlignmentX(CENTER_ALIGNMENT);
        txt.add(lblVacia);
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

    void refrescar() {
        try {
            todas = bd.listarCategorias(true);
            productos = bd.productosPorCategoria();
        } catch (SQLException e) {
            Aviso.mostrar(this, Aviso.Tipo.ERROR, "No se pudieron leer las categorías", e.getMessage());
            return;
        }
        filtrar();
    }

    private void filtrar() {
        String t = rutas.modelo.Geo.normalizar(campoBuscar.getText().trim());
        filtradas = new ArrayList<>();
        for (Categoria c : todas) {
            if (t.isEmpty() || rutas.modelo.Geo.normalizar(c.getNombre()).contains(t)
                    || String.valueOf(c.getId()).equals(t)) {
                filtradas.add(c);
            }
        }
        lblVacia.setText(todas.isEmpty() ? "Cree la primera con «Nueva categoría» o impórtelas desde Excel."
                : "No hay categorías con «" + campoBuscar.getText().trim() + "».");
        mostrarPagina();
    }

    private void mostrarPagina() {
        int porPagina = (Integer) comboPorPagina.getSelectedItem();
        totalPaginas = Math.max(1, (filtradas.size() + porPagina - 1) / porPagina);
        numeroPagina = Math.max(1, Math.min(numeroPagina, totalPaginas));
        int desde = (numeroPagina - 1) * porPagina;
        pagina = new ArrayList<>(filtradas.subList(Math.min(desde, filtradas.size()),
                Math.min(desde + porPagina, filtradas.size())));
        modelo.fireTableDataChanged();
        tarjetasTabla.show(zonaTabla, filtradas.isEmpty() ? "vacia" : "tabla");
        lblPaginas.setText("Páginas: " + totalPaginas + "    ·    " + filtradas.size()
                + (filtradas.size() == 1 ? " categoría" : " categorías"));
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

    private int siguienteOrden() {
        int max = 0;
        for (Categoria c : todas) {
            max = Math.max(max, c.getOrden());
        }
        return max + 1;
    }

    // ------------------------------------------------------------------
    //  Acciones
    // ------------------------------------------------------------------

    private void nueva() {
        FormularioCategoria.abrir(this, bd, null, siguienteOrden(), this::refrescar);
    }

    private void editar(Categoria c) {
        FormularioCategoria.abrir(this, bd, c, siguienteOrden(), this::refrescar);
    }

    /** Papelera: sin productos se borra; con productos se desactiva (o se reactiva si ya estaba inactiva). */
    private void quitar(Categoria c) {
        int n = productos.getOrDefault(c.getId(), 0);
        if (!c.isActivo()) {
            cambiarActiva(c, true);
            return;
        }
        if (n == 0) {
            Alerta.confirmar(this, "¿Eliminar la categoría «" + c.getNombre() + "»?",
                    "No tiene productos. Se borra para siempre.", "Sí, eliminar", "Volver", () -> {
                        try {
                            bd.eliminarCategoria(c.getId());
                            refrescar();
                            Aviso.mostrar(this, Aviso.Tipo.BIEN, "Categoría eliminada", c.getNombre());
                        } catch (IllegalArgumentException | SQLException e) {
                            Alerta.mostrar(this, Alerta.Tipo.AVISO, "No se pudo eliminar", e.getMessage(), null);
                        }
                    });
        } else {
            Alerta.confirmar(this, "¿Desactivar «" + c.getNombre() + "»?",
                    "Tiene " + n + (n == 1 ? " producto" : " productos") + ", por eso no se borra: deja de salir en Vender"
                    + " y sus productos siguen en «Todos». Se puede reactivar cuando quiera.",
                    "Sí, desactivar", "Volver", () -> cambiarActiva(c, false));
        }
    }

    private void cambiarActiva(Categoria c, boolean activa) {
        try {
            bd.editarCategoria(new Categoria(c.getId(), c.getNombre(), c.getFoto(), c.getOrden(), activa));
            refrescar();
            Aviso.mostrar(this, Aviso.Tipo.BIEN, activa ? "Categoría reactivada" : "Categoría desactivada", c.getNombre());
        } catch (SQLException e) {
            Alerta.mostrar(this, Alerta.Tipo.AVISO, "No se pudo guardar", e.getMessage(), null);
        }
    }

    private void exportarExcel() {
        File f = Archivos.guardar(this, "Exportar las categorías a Excel", "categorias.xlsx", "xlsx");
        if (f == null) {
            return;
        }
        List<Object[]> filas = new ArrayList<>();
        for (Categoria c : filtradas) {
            filas.add(new Object[]{c.getId(), c.getNombre(), "", c.isActivo() ? "ACTIVO" : "INACTIVO", c.getOrden(),
                productos.getOrDefault(c.getId(), 0)});
        }
        try {
            Xlsx.escribir(f, "Categorías", new String[]{"codigo", "nombre", "url_imagen", "estado", "orden", "productos"},
                    filas);
            Aviso.mostrar(this, Aviso.Tipo.BIEN, "Excel guardado", filas.size() + " categorías en " + f.getName());
        } catch (java.io.IOException e) {
            Alerta.mostrar(this, Alerta.Tipo.AVISO, "No se pudo guardar el Excel",
                    "Revise que el archivo no esté abierto en Excel. Detalle: " + e.getMessage(), null);
        }
    }

    private void exportarPdf() {
        List<String[]> filas = new ArrayList<>();
        int i = 1;
        for (Categoria c : filtradas) {
            filas.add(new String[]{String.valueOf(i++), String.valueOf(c.getId()), c.getNombre(),
                String.valueOf(productos.getOrDefault(c.getId(), 0)), c.isActivo() ? "ACTIVO" : "INACTIVO"});
        }
        DocumentoLista doc = new DocumentoLista("Lista de categorías de productos",
                new String[]{"Item", "Código", "Nombre de la categoría", "Productos", "Estado"},
                new float[]{0.7f, 1f, 3.4f, 1.1f, 1.2f}, new boolean[]{false, false, false, true, false}, filas,
                app.getAjustesRecibo());
        VistaPrevia.mostrar(this, doc, "Lista de categorías");
    }

    // ------------------------------------------------------------------
    //  Importar
    // ------------------------------------------------------------------

    private void abrirImportar() {
        if (Capa.hayAbierta(this)) {
            return;
        }
        JPanel t = Capa.tarjeta(860, 20, 24);
        JPanel cab = new JPanel(new BorderLayout());
        cab.setOpaque(false);
        cab.setAlignmentX(LEFT_ALIGNMENT);
        cab.add(Tema.tituloPantalla("Importar categorías desde Excel"), BorderLayout.WEST);
        t.add(cab);
        t.add(Tema.espacio(6));
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
            {"codigo", "número que no se repite. Opcional: si lo deja vacío, el programa pone uno."},
            {"nombre", "el nombre de la categoría. Obligatorio."},
            {"url_imagen", "dirección de internet de una imagen (clic derecho en la imagen › copiar dirección)."
                + " Opcional."},
            {"estado", "ACTIVO o INACTIVO. Opcional: vacío queda activa."},
            {"orden", "en qué puesto sale en Vender (1, 2, 3…). Opcional."}};
        for (String[] c : campos) {
            izq.add(parrafo("• " + c[0] + ": " + c[1], 13, Tema.GRIS_OSCURO));
            izq.add(Tema.espacio(3));
        }
        izq.add(Tema.espacio(4));
        izq.add(parrafo("Si el código ya existe, esa categoría se actualiza. Si alguna fila tiene un error no se guarda"
                + " nada y se le dice cuál fila revisar. También sirve un archivo .csv.", 12.5f, Tema.GRIS_TEXTO));
        izq.add(Tema.espacio(10));
        Tema.Boton formato = Tema.botonSecundario("Descargar formato");
        formato.setIcon(new ImagenIcono("excel", 22));
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
        Tema.Parrafo msg = Tema.parrafo(" ", 340);
        msg.setFont(Tema.texto(13));
        File[] elegido = {null};
        ZonaArchivo zona = new ZonaArchivo(f -> {
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
        t.add(Tema.espacio(12));
        Tema.Boton volver = Tema.botonSecundario("Volver");
        JPanel fv = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        fv.setOpaque(false);
        fv.setAlignmentX(LEFT_ALIGNMENT);
        fv.add(volver);
        t.add(fv);
        Capa capa = new Capa(this, t, true);
        volver.addActionListener(e -> capa.cerrar());
        cargar.addActionListener(e -> {
            if (elegido[0] == null) {
                File f = Archivos.abrir(this, "Escoger el archivo de categorías", "xlsx", "csv");
                if (f == null) {
                    return;
                }
                elegido[0] = f;
            }
            importar(elegido[0], capa, msg);
        });
        capa.mostrar(cargar);
    }

    private void importar(File f, Capa capa, Tema.Parrafo msg) {
        ImportadorCategorias.Resultado r;
        try {
            r = ImportadorCategorias.importar(ImportadorCategorias.leerArchivo(f), bd);
        } catch (Exception e) {
            msg.setForeground(Tema.ERROR_TEXTO);
            msg.setText("No se pudo leer el archivo: " + e.getMessage());
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
        capa.cerrar();
        refrescar();
        Aviso.mostrar(this, Aviso.Tipo.BIEN, "Categorías importadas", r.creadas + " nuevas · " + r.actualizadas
                + " actualizadas" + (r.fotos.isEmpty() ? "" : " · bajando " + r.fotos.size() + " imágenes…"));
        if (!r.fotos.isEmpty()) {
            bajarFotos(r.fotos);
        }
    }

    /** Las imagenes de las direcciones se bajan aparte, sin trabar la pantalla. */
    private void bajarFotos(List<String[]> fotos) {
        new SwingWorker<int[], Void>() {
            @Override
            protected int[] doInBackground() {
                int bien = 0, mal = 0;
                for (String[] f : fotos) {
                    try {
                        long id = Long.parseLong(f[0]);
                        byte[] datos = FormularioProducto.bajarImagen(f[1]);
                        String ruta = FormularioCategoria.guardarBytes(datos, "categoria-" + id);
                        Fotos.olvidar(ruta);
                        synchronized (bd) {
                            Categoria c = bd.buscarCategoria(id);
                            if (c != null) {
                                bd.editarCategoria(new Categoria(id, c.getNombre(), ruta, c.getOrden(), c.isActivo()));
                            }
                        }
                        bien++;
                    } catch (Exception e) {
                        mal++;
                    }
                }
                return new int[]{bien, mal};
            }

            @Override
            protected void done() {
                try {
                    int[] r = get();
                    refrescar();
                    Aviso.mostrar(PanelCategoriasLista.this, r[1] == 0 ? Aviso.Tipo.BIEN : Aviso.Tipo.AVISO,
                            "Imágenes de las categorías", r[0] + " bajadas" + (r[1] == 0 ? "" : " · " + r[1]
                            + " no se pudieron bajar (revise la dirección)"));
                } catch (Exception e) {
                    Ubicaciones.registrarError(e);
                }
            }
        }.execute();
    }

    private void descargarFormato() {
        File f = Archivos.guardar(this, "Guardar el formato de categorías", "formato_categorias.xlsx", "xlsx");
        if (f == null) {
            return;
        }
        List<Object[]> ejemplo = new ArrayList<>();
        ejemplo.add(new Object[]{"", "Bebidas", "https://…/bebidas.png", "ACTIVO", 1});
        ejemplo.add(new Object[]{"", "Granos", "", "", 2});
        try {
            Xlsx.escribir(f, "Categorías", new String[]{"codigo", "nombre", "url_imagen", "estado", "orden"}, ejemplo);
            Aviso.mostrar(this, Aviso.Tipo.BIEN, "Formato guardado", f.getName() + ": borre las filas de ejemplo y llénelo");
        } catch (java.io.IOException e) {
            Alerta.mostrar(this, Alerta.Tipo.AVISO, "No se pudo guardar", e.getMessage(), null);
        }
    }

    private static Tema.Parrafo parrafo(String texto, float tam, Color color) {
        Tema.Parrafo p = Tema.parrafo(texto, 380);
        p.setFont(Tema.texto(tam));
        p.setForeground(color);
        p.setAlignmentX(LEFT_ALIGNMENT);
        return p;
    }

    /** Recuadro punteado para soltar o escoger el archivo. */
    static class ZonaArchivo extends JPanel {
        ZonaArchivo(java.util.function.Consumer<File> alElegir) {
            this("Escoger el archivo de categorías", alElegir);
        }

        ZonaArchivo(String tituloEscoger, java.util.function.Consumer<File> alElegir) {
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            getAccessibleContext().setAccessibleName("Escoger el archivo de Excel");
            setToolTipText("Suelte aquí el archivo o haga clic para escogerlo");
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    File f = Archivos.abrir(ZonaArchivo.this, tituloEscoger, "xlsx", "csv");
                    if (f != null) {
                        alElegir.accept(f);
                    }
                }
            });
            setTransferHandler(new javax.swing.TransferHandler() {
                @Override
                public boolean canImport(TransferSupport s) {
                    return s.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor);
                }

                @Override
                public boolean importData(TransferSupport s) {
                    try {
                        @SuppressWarnings("unchecked")
                        List<File> fs = (List<File>) s.getTransferable()
                                .getTransferData(java.awt.datatransfer.DataFlavor.javaFileListFlavor);
                        if (!fs.isEmpty()) {
                            alElegir.accept(fs.get(0));
                            return true;
                        }
                    } catch (Exception e) {
                        Ubicaciones.registrarError(e);
                    }
                    return false;
                }
            });
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(320, 150);
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, 150);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Tema.suavizar(g);
            int w = getWidth(), h = getHeight();
            g.setColor(Tema.BLANCO);
            g.fillRoundRect(0, 0, w - 1, h - 1, Tema.ARCO, Tema.ARCO);
            g.setColor(Tema.BORDE_CONTROL);
            g.setStroke(new java.awt.BasicStroke(1.5f, java.awt.BasicStroke.CAP_BUTT, java.awt.BasicStroke.JOIN_MITER, 10f,
                    new float[]{5f, 4f}, 0f));
            g.drawRoundRect(1, 1, w - 3, h - 3, Tema.ARCO, Tema.ARCO);
            new ImagenIcono("excel", 44).paintIcon(this, g, (w - 44) / 2, 24);
            g.setFont(Tema.texto(13));
            g.setColor(Tema.GRIS_TEXTO);
            java.awt.FontMetrics fm = g.getFontMetrics();
            String a = "Suelte aquí el archivo o haga clic";
            g.drawString(a, (w - fm.stringWidth(a)) / 2, 94);
            String b = "para escogerlo (.xlsx o .csv)";
            g.drawString(b, (w - fm.stringWidth(b)) / 2, 112);
            g.dispose();
        }
    }

    /** Icono a color de imagenes/archivos (excel, pdf). */
    static class ImagenIcono implements javax.swing.Icon {
        private final String nombre;
        private final int tam;

        ImagenIcono(String nombre, int tam) {
            this.nombre = nombre;
            this.tam = tam;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Iconos.dibujarColor((Graphics2D) g, "/imagenes/archivos/" + nombre + ".png", x, y, tam);
        }

        @Override
        public int getIconWidth() {
            return tam;
        }

        @Override
        public int getIconHeight() {
            return tam;
        }
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
            Categoria cat = pagina.get(r);
            int porPagina = (Integer) comboPorPagina.getSelectedItem();
            switch (c) {
                case 0:
                    return (numeroPagina - 1) * porPagina + r + 1;
                case 1:
                    return cat.getId();
                case 3:
                    return cat.getNombre();
                case 4:
                    return productos.getOrDefault(cat.getId(), 0);
                case 5:
                    return cat.getOrden();
                case 6:
                    return cat.isActivo() ? "Activa" : "Inactiva";
                default:
                    return "";
            }
        }
    }

    private class Celda extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foco, int r, int c) {
            super.getTableCellRendererComponent(t, v, sel, false, r, c);
            Categoria cat = pagina.get(r);
            setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10));
            setFont(Tema.texto(14));
            setHorizontalAlignment(c == 3 ? LEFT : CENTER);
            setBackground(sel ? Tema.AZUL_SUAVE : (r % 2 == 1 ? new Color(0xFAFBFC) : Color.WHITE));
            setForeground(cat.isActivo() ? Tema.GRIS_OSCURO : Tema.GRIS_TEXTO);
            setToolTipText(null);
            if (c == 6) {
                // estado como texto de color, sin pildora (manual de marca)
                setFont(Tema.textoFuerte(13.5f));
                setForeground(cat.isActivo() ? Tema.EXITO_TEXTO : Tema.GRIS_TEXTO);
                setToolTipText(cat.isActivo() ? "Sale en Vender" : "No sale en Vender");
            }
            return this;
        }
    }

    /** Foto pequeña de la categoria (o la caja si no tiene). */
    private class Miniatura extends DefaultTableCellRenderer {
        private Categoria cat;
        private boolean sel;
        private int fila;

        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foco, int r, int c) {
            this.cat = pagina.get(r);
            this.sel = sel;
            this.fila = r;
            setText("");
            return this;
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Tema.suavizar(g);
            g.setColor(sel ? Tema.AZUL_SUAVE : (fila % 2 == 1 ? new Color(0xFAFBFC) : Color.WHITE));
            g.fillRect(0, 0, getWidth(), getHeight());
            int d = 34, x = (getWidth() - d) / 2, y = (getHeight() - d) / 2;
            Image img = Fotos.cargar(cat.getFoto(), d, d);
            if (img != null) {
                Fotos.dibujar(g, img, x, y, d, d);
            } else {
                Fotos.paquete(g, x + d / 2, y + d / 2, 26);
            }
            g.dispose();
        }
    }

    private class Acciones extends DefaultTableCellRenderer {
        private Categoria cat;
        private boolean sel;
        private int fila;

        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foco, int r, int c) {
            this.cat = pagina.get(r);
            this.sel = sel;
            this.fila = r;
            setText("");
            int n = productos.getOrDefault(cat.getId(), 0);
            setToolTipText("Editar  ·  " + (!cat.isActivo() ? "Reactivar" : n == 0 ? "Eliminar" : "Desactivar"));
            return this;
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Tema.suavizar(g);
            g.setColor(sel ? Tema.AZUL_SUAVE : (fila % 2 == 1 ? new Color(0xFAFBFC) : Color.WHITE));
            g.fillRect(0, 0, getWidth(), getHeight());
            int cy = getHeight() / 2;
            int x1 = getWidth() / 2 - 24, x2 = getWidth() / 2 + 6;
            PanelProductos.Icono.LAPIZ.dibujar(g, x1, cy - 9, 18, Tema.GRIS_OSCURO);
            if (cat.isActivo()) {
                PanelProductos.Icono.PAPELERA.dibujar(g, x2, cy - 9, 18, Tema.ERROR);
            } else {
                PanelProductos.Icono.REACTIVAR.dibujar(g, x2, cy - 9, 18, Tema.AZUL);
            }
            g.dispose();
        }
    }
}
