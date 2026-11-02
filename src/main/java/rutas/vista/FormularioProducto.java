package rutas.vista;

import java.awt.BasicStroke;
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
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import rutas.logica.BaseTienda;
import rutas.logica.Catalogo;
import rutas.logica.ImportadorProductos;
import rutas.logica.Tarifa;
import rutas.logica.Ubicaciones;
import rutas.modelo.Bodega;
import rutas.modelo.Categoria;
import rutas.modelo.FichaProducto;
import rutas.modelo.Lote;
import rutas.modelo.Producto;

/**
 * Formulario completo de un producto (nuevo o para actualizar), como capa encima de Productos:
 * imagen, tipo y codigo de barras a la izquierda; datos y precios en dos columnas; abajo el
 * stock por bodega y las opciones de caracteristicas, IMEI y lotes.
 */
final class FormularioProducto {

    private static final int ANCHO_IMAGEN = 250;
    private static final long MAXIMO_FOTO = 1024 * 1024; // 1 MB
    private static final String MANEJA = "MANEJA INVENTARIO";
    private static final String NO_MANEJA = "NO MANEJA INVENTARIO";
    private static final Categoria SIN_CATEGORIA = new Categoria(0, "Sin categoría", null, 0, true);

    private final JComponent panel;              // donde sale la capa
    private final PanelProductos productos;      // null si se abre desde Compras
    private final boolean desdeCompra;           // "Crear producto" de Compras: sin bodegas ni lotes
    private java.util.function.Consumer<Producto> alCrear;
    private boolean creadoAqui;                  // el producto nacio en este formulario (aunque se guarde dos veces)
    private final Aplicacion app;
    private final BaseTienda bd;
    private final Catalogo cat;
    private final boolean verCompra;
    private final Runnable alGuardar;
    private Producto editando;
    private FichaProducto ficha;

    // ---- campos ----
    private final JTextField campoNombre = Tema.campo("Ej. Aceite de oliva 1 L");
    private final Controles.AreaTexto areaDescripcion = new Controles.AreaTexto("Para qué sirve, presentación, sabor…", 4);
    private final Controles.AreaTexto areaGarantia = new Controles.AreaTexto("Condiciones o detalle de la garantía", 4);
    private final JTextField campoCodigo = Tema.campo("Escanee o genere el código");
    private final JComboBox<Categoria> comboCategoria = new JComboBox<>();
    private final JComboBox<String> comboInventario = new JComboBox<>(new String[]{MANEJA, NO_MANEJA});
    private final Controles.AreaTexto areaUrl = new Controles.AreaTexto("COPIAR DIRECCIÓN DE LA IMAGEN", 4);
    private final JComboBox<String> comboMedida = new JComboBox<>(FichaProducto.MEDIDAS);
    private final Controles.Etiquetas impuestos = new Controles.Etiquetas("Impuesto", "Sin impuesto", FichaProducto.IMPUESTOS);
    private final Controles.CampoFecha campoFecha = new Controles.CampoFecha("Fecha de vencimiento");
    private final JLabel lblVence = nota();
    private final JTextField campoCompra = Controles.campoNumero("0");
    private final Controles.Interruptor intMargen = new Controles.Interruptor("¿Precio por margen de utilidad?");
    private final JTextField campoMargen = Controles.campoNumero("Ej. 25");
    private final JPanel filaMargen = new JPanel();
    private final JTextField campoVenta = Controles.campoNumero("0");
    private final JLabel lblGanancia = nota();
    private final JTextField campoMayor = Controles.campoNumero("0");
    private final JComboBox<String> comboEstado = new JComboBox<>(new String[]{"ACTIVO", "INACTIVO"});
    private final JCheckBox chkProducto = Tema.casilla("Producto");
    private final JCheckBox chkInsumo = Tema.casilla("Insumo");
    private final JCheckBox chkReceta = Tema.casilla("Receta / Servicio / Combo");
    private final ZonaFoto zonaFoto = new ZonaFoto();
    private final CodigoBarras.Vista vistaCodigo = new CodigoBarras.Vista();
    private final JTextField campoStock = Controles.campoNumero("0");   // punto de venta
    private final JTextField campoMinimo = Controles.campoNumero("0");
    private final JPanel panelBodegas = columna();
    private final List<FilaBodega> filasBodega = new ArrayList<>();
    private final JPanel zonaInventario = columna();
    private final Controles.Interruptor intCaract = new Controles.Interruptor("¿Caracterizar producto?");
    private final JPanel panelCaract = columna();
    private final List<JTextField[]> filasCaract = new ArrayList<>();
    private final Controles.Interruptor intImei = new Controles.Interruptor("¿Agregar IMEI?");
    private final JPanel panelImei = columna();
    private final Controles.AreaTexto areaImei = new Controles.AreaTexto("Un IMEI por renglón (15 números)", 4);
    private final JLabel lblImei = nota();
    private final Controles.Interruptor intLotes = new Controles.Interruptor("¿Maneja lotes?");
    private final JPanel panelLotes = columna();
    private final List<FilaLote> filasLote = new ArrayList<>();
    private final JLabel lblLotes = nota();
    private final Tema.Parrafo lblForm = Tema.parrafo(" ", 640);

    private String stockAlAbrir;     // stock que se mostro al abrir: si no lo cambian, se respeta lo de la base
    private boolean cargando;         // mientras se llena el formulario no se recalcula el precio
    private boolean bajando;          // hay una imagen bajandose de internet
    private File fotoElegida;        // foto escogida que todavia no se ha copiado
    private byte[] fotoDeUrl;         // imagen bajada de la direccion, lista para guardar
    private String urlBajada;         // direccion de la que salio fotoDeUrl
    private final Timer esperaUrl = new Timer(700, e -> bajarVistaUrl());
    private Capa capa;
    private JPanel modal;
    private JScrollPane desliza;

    private FormularioProducto(JComponent panel, Aplicacion app, boolean verCompra, Runnable alGuardar) {
        this.panel = panel;
        this.productos = panel instanceof PanelProductos ? (PanelProductos) panel : null;
        this.desdeCompra = productos == null;
        this.app = app;
        this.bd = app.getTienda();
        this.cat = app.getCatalogo();
        this.verCompra = verCompra;
        this.alGuardar = alGuardar;
        lblForm.setFont(Tema.texto(13));
        Tema.estiloCombo(comboCategoria, Categoria::getNombre);
        Tema.estiloCombo(comboInventario, String::valueOf);
        Tema.estiloCombo(comboMedida, m -> m + (FichaProducto.unidadDe(m) == rutas.modelo.Unidad.UNIDAD ? "" : " (a granel)"));
        Tema.estiloCombo(comboEstado, String::valueOf);
        esperaUrl.setRepeats(false);
    }

    /** Abre el formulario. {@code p} null = producto nuevo (con {@code codigoNuevo} si vino del escaner). */
    static void abrir(PanelProductos panel, Aplicacion app, boolean verCompra, Producto p, String codigoNuevo,
            Runnable alGuardar) {
        if (Capa.hayAbierta(panel)) {
            return;
        }
        FormularioProducto f = new FormularioProducto(panel, app, verCompra, alGuardar);
        try {
            f.ficha = p == null ? new FichaProducto() : f.bd.leerFicha(p.getId());
        } catch (SQLException e) {
            Alerta.mostrar(panel, Alerta.Tipo.AVISO, "No se pudo abrir el producto", e.getMessage(), null);
            return;
        }
        f.editando = p;
        f.armar();
        f.cargar(p, codigoNuevo);
        f.capa.mostrar(f.campoNombre);
        SwingUtilities.invokeLater(() -> f.desliza.getVerticalScrollBar().setValue(0)); // abre arriba
    }

    /**
     * "Crear producto" desde una compra: el mismo formulario sin bodegas ni lotes (la cantidad
     * llega con la compra). Al guardar, {@code alCrear} recibe el producto nuevo.
     */
    static void abrirParaCompra(JComponent dentroDe, Aplicacion app, String codigoNuevo,
            java.util.function.Consumer<Producto> alCrear) {
        if (Capa.hayAbierta(dentroDe)) {
            return;
        }
        FormularioProducto f = new FormularioProducto(dentroDe, app, true, () -> { });
        f.alCrear = alCrear;
        f.ficha = new FichaProducto();
        f.armar();
        f.cargar(null, codigoNuevo);
        f.capa.mostrar(f.campoNombre);
        SwingUtilities.invokeLater(() -> f.desliza.getVerticalScrollBar().setValue(0));
    }

    // ------------------------------------------------------------------
    //  Armar la pantalla
    // ------------------------------------------------------------------

    private void armar() {
        modal = new JPanel(new BorderLayout()) {
            @Override
            public Dimension getPreferredSize() {
                javax.swing.JRootPane r = SwingUtilities.getRootPane(panel);
                // compacto como la referencia: no llena la pantalla
                int ancho = r == null ? 1020 : Math.min(1020, r.getWidth() - 80);
                return new Dimension(Math.max(840, ancho), super.getPreferredSize().height);
            }
        };
        modal.setOpaque(false);
        modal.add(cabeza(), BorderLayout.NORTH);

        JPanel cuerpo = new Cuerpo();
        cuerpo.setOpaque(false);
        cuerpo.setBorder(BorderFactory.createEmptyBorder(14, 20, 12, 20));
        GridBagConstraints g = new GridBagConstraints();
        g.anchor = GridBagConstraints.NORTH;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.gridy = 0;
        g.gridx = 0;
        g.gridheight = 2;
        g.insets = new Insets(0, 0, 0, 22);
        cuerpo.add(columnaIzquierda(), g);
        g.gridheight = 1;
        g.weightx = 1;
        g.gridx = 1;
        g.insets = new Insets(0, 0, 0, 20);
        cuerpo.add(mismoAncho(columnaDatos()), g);
        g.gridx = 2;
        g.insets = new Insets(0, 0, 0, 0);
        cuerpo.add(mismoAncho(columnaPrecios()), g);
        g.gridx = 1;
        g.gridy = 1;
        g.gridwidth = 2;
        g.weighty = 1;
        g.insets = new Insets(16, 0, 0, 0);
        cuerpo.add(mismoAncho(parteDeAbajo()), g);

        desliza = new JScrollPane(cuerpo) {
            @Override
            public Dimension getPreferredSize() {
                Dimension d = cuerpo.getPreferredSize();
                javax.swing.JRootPane r = SwingUtilities.getRootPane(panel);
                int max = r == null ? d.height : Math.max(260, r.getHeight() - 170);
                return new Dimension(d.width, Math.min(d.height, max));
            }
        };
        desliza.setBorder(BorderFactory.createEmptyBorder());
        desliza.setOpaque(false);
        desliza.getViewport().setOpaque(false);
        desliza.getVerticalScrollBar().setUnitIncrement(18);
        desliza.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        modal.add(desliza, BorderLayout.CENTER);

        JPanel pie = new JPanel(new BorderLayout(16, 0));
        pie.setOpaque(false);
        pie.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Tema.BORDE),
                BorderFactory.createEmptyBorder(10, 20, 10, 20)));
        pie.add(lblForm, BorderLayout.CENTER);
        Tema.Boton guardar = Tema.botonAccion(editando == null ? "Guardar" : "Actualizar");
        Tema.Boton cancelar = Tema.botonSecundario("Cancelar");
        pie.add(Tema.fila(8, cancelar, guardar), BorderLayout.EAST);
        modal.add(pie, BorderLayout.SOUTH);

        capa = new Capa(panel, modal, true);
        capa.setAlCerrar(esperaUrl::stop);
        guardar.addActionListener(e -> guardar());
        cancelar.addActionListener(e -> capa.cerrar());
    }

    private JComponent cabeza() {
        JPanel cabeza = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create();
                Tema.suavizar(g);
                g.setColor(Tema.AZUL);
                g.fillRoundRect(0, 0, getWidth(), getHeight() + Tema.ARCO, Tema.ARCO, Tema.ARCO);
                g.dispose();
            }
        };
        cabeza.setOpaque(false);
        cabeza.setBorder(BorderFactory.createEmptyBorder(11, 20, 11, 12));
        JLabel titulo = new JLabel(desdeCompra ? "Crear producto" : editando == null ? "Nuevo producto" : "Actualizar producto");
        titulo.setFont(Tema.subtitulo(20));
        titulo.setForeground(Tema.BLANCO);
        cabeza.add(titulo, BorderLayout.WEST);
        JButton cerrar = new BotonCerrar();
        cerrar.addActionListener(e -> capa.cerrar());
        cabeza.add(cerrar, BorderLayout.EAST);
        return cabeza;
    }

    /** Izquierda: imagen de presentacion, tipo de producto y codigo de barras con Imprimir. */
    private JComponent columnaIzquierda() {
        JPanel col = columna();
        Tema.Tarjeta tarjeta = new Tema.Tarjeta();
        tarjeta.setLayout(new BoxLayout(tarjeta, BoxLayout.Y_AXIS));
        tarjeta.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
        JLabel t = new JLabel("IMAGEN DE PRESENTACIÓN", SwingConstants.CENTER);
        t.setFont(Tema.subtitulo(13));
        t.setForeground(Tema.GRIS_OSCURO);
        t.setAlignmentX(Component.CENTER_ALIGNMENT);
        tarjeta.add(t);
        tarjeta.add(Tema.espacio(10));
        zonaFoto.setAlignmentX(Component.CENTER_ALIGNMENT);
        tarjeta.add(zonaFoto);
        tarjeta.add(Tema.espacio(10));
        Tema.Boton subir = Tema.botonPrimario("Subir imagen");
        subir.setIcon(Iconos.de("foto", 20, Tema.BLANCO));
        subir.setIconTextGap(8);
        subir.setAlignmentX(Component.CENTER_ALIGNMENT);
        subir.setFont(Tema.textoFuerte(13));
        subir.setBorder(BorderFactory.createEmptyBorder(7, 14, 7, 14));
        subir.setMaximumSize(new Dimension(Integer.MAX_VALUE, subir.getPreferredSize().height));
        subir.addActionListener(e -> elegirFoto());
        tarjeta.add(subir);
        tarjeta.add(Tema.espacio(6));
        JLabel n = new JLabel("JPG o PNG. Máximo 1 MB.", SwingConstants.CENTER);
        n.setFont(Tema.texto(12));
        n.setForeground(Tema.GRIS_TEXTO);
        n.setAlignmentX(Component.CENTER_ALIGNMENT);
        tarjeta.add(n);
        tarjeta.setAlignmentX(Component.LEFT_ALIGNMENT);
        col.add(tarjeta);
        col.add(Tema.espacio(10));

        col.add(tipo(chkProducto));
        col.add(Tema.espacio(4));
        col.add(tipo(chkInsumo));
        col.add(Tema.espacio(4));
        col.add(tipo(chkReceta));
        // si solo queda marcada Receta / Servicio / Combo, se sugiere que no maneje inventario
        java.awt.event.ActionListener sugerir = e -> {
            if (chkReceta.isSelected() && !chkProducto.isSelected() && !chkInsumo.isSelected()) {
                comboInventario.setSelectedItem(NO_MANEJA);
            }
        };
        chkReceta.addActionListener(sugerir);
        chkProducto.addActionListener(sugerir);
        chkInsumo.addActionListener(sugerir);
        col.add(Tema.espacio(12));

        vistaCodigo.setPreferredSize(new Dimension(210, 78));
        JPanel filaCodigo = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        filaCodigo.setOpaque(false);
        filaCodigo.add(vistaCodigo);
        filaCodigo.setAlignmentX(Component.LEFT_ALIGNMENT);
        col.add(filaCodigo);
        col.add(Tema.espacio(6));
        Tema.Boton imprimir = Tema.botonPequeno("Imprimir");
        imprimir.setIcon(Iconos.de("notas", 18, Tema.AZUL));
        imprimir.setToolTipText("Imprimir la etiqueta con el código de barras");
        imprimir.addActionListener(e -> imprimirEtiqueta());
        JPanel f = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        f.setOpaque(false);
        f.add(imprimir);
        f.setAlignmentX(Component.LEFT_ALIGNMENT);
        col.add(f);
        col.setPreferredSize(new Dimension(ANCHO_IMAGEN, col.getPreferredSize().height));
        return col;
    }

    /** Casilla de tipo, sin fondo de color (el manual no usa franjas de color para esto). */
    private static JComponent tipo(JCheckBox c) {
        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        p.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        c.setFont(Tema.texto(13));
        p.add(c, BorderLayout.CENTER);
        return Controles.ancho(p);
    }

    /** Medio: nombre, descripcion, garantia, codigo, categoria, inventario y direccion de imagen. */
    private JComponent columnaDatos() {
        JPanel col = columna();
        col.add(etiquetado("Nombre", campoNombre));
        col.add(etiquetado("Descripción", areaDescripcion));
        col.add(etiquetado("Nota de garantía", areaGarantia));

        Tema.Boton generar = Tema.botonPrimario("Generar");
        generar.setIcon(Iconos.de("codigo", 18, Tema.BLANCO));
        generar.setToolTipText("Inventar un código nuevo que no tenga otro producto");
        generar.addActionListener(e -> generarCodigo());
        col.add(etiquetado("Código de barras", conBoton(campoCodigo, generar)));
        campoCodigo.getAccessibleContext().setAccessibleName("Código de barras");
        campoCodigo.getDocument().addDocumentListener(PanelProductos.alCambiar(
                () -> vistaCodigo.setCodigo(campoCodigo.getText())));

        Tema.Boton mas = Tema.botonPrimario("+");
        mas.setFont(Tema.subtitulo(17));
        mas.setBorder(BorderFactory.createEmptyBorder(6, 16, 6, 16));
        Tema.nombreAccesible(mas, "Crear una categoría nueva");
        mas.addActionListener(e -> nuevaCategoria());
        col.add(etiquetado("Categoría", conBoton(comboCategoria, mas)));
        comboCategoria.getAccessibleContext().setAccessibleName("Categoría");
        llenarCategorias(null);

        col.add(etiquetado("Inventario", comboInventario));
        comboInventario.addActionListener(e -> mostrarInventario());

        col.add(etiquetado("Dirección de imagen", areaUrl));
        Tema.Parrafo ayudaUrl = Tema.parrafo("También puede pegar una dirección de internet (https://…) si no desea"
                + " subir una imagen.", 300);
        ayudaUrl.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 6));
        col.add(ayudaUrl);
        areaUrl.getArea().getDocument().addDocumentListener(PanelProductos.alCambiar(esperaUrl::restart));
        return col;
    }

    /** Derecha: medida, impuesto, vencimiento, costo, margen, precios y estado. */
    private JComponent columnaPrecios() {
        JPanel col = columna();
        col.add(etiquetado("Unidad de medida", comboMedida));
        col.add(etiquetado("Impuesto", impuestos));
        col.add(etiquetado("Fecha de vencimiento", campoFecha));
        col.add(lblVence);
        campoFecha.setAlCambiar(this::revisarVencimiento);

        if (verCompra) {
            col.add(etiquetado("Costo por unidad de compra", campoCompra));
            col.add(Tema.espacio(10));
            col.add(intMargen);
            filaMargen.setLayout(new BoxLayout(filaMargen, BoxLayout.Y_AXIS));
            filaMargen.setOpaque(false);
            filaMargen.setAlignmentX(Component.LEFT_ALIGNMENT);
            filaMargen.add(etiquetado("Margen de utilidad (%)", campoMargen));
            col.add(filaMargen);
            intMargen.addItemListener(e -> {
                filaMargen.setVisible(intMargen.isSelected());
                campoVenta.setEditable(!intMargen.isSelected());
                campoVenta.setBackground(intMargen.isSelected() ? new Color(0xE9ECF0) : Tema.BLANCO);
                campoVenta.setToolTipText(intMargen.isSelected() ? "Se calcula con el costo y el margen" : null);
                calcularVenta();
                reacomodar();
            });
            campoCompra.getDocument().addDocumentListener(PanelProductos.alCambiar(this::calcularVenta));
            campoMargen.getDocument().addDocumentListener(PanelProductos.alCambiar(this::calcularVenta));
        }
        col.add(etiquetado("Valor por unidad de venta", campoVenta));
        if (verCompra) {
            col.add(lblGanancia);
            campoVenta.getDocument().addDocumentListener(PanelProductos.alCambiar(this::calcularGanancia));
            campoCompra.getDocument().addDocumentListener(PanelProductos.alCambiar(this::calcularGanancia));
        }
        col.add(etiquetado("Valor por unidad de venta al por mayor", campoMayor));
        col.add(etiquetado("Estado", comboEstado));
        return col;
    }

    /** Abajo (ancho de las dos columnas): stock por bodega y las tres opciones con interruptor. */
    private JComponent parteDeAbajo() {
        JPanel p = columna();
        zonaInventario.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(zonaInventario);
        p.add(separador());

        p.add(intCaract);
        panelCaract.setBorder(BorderFactory.createEmptyBorder(4, 0, 6, 0));
        p.add(panelCaract);
        p.add(separador());
        p.add(intImei);
        panelImei.setBorder(BorderFactory.createEmptyBorder(4, 0, 6, 0));
        panelImei.add(Controles.ancho(areaImei));
        panelImei.add(lblImei);
        areaImei.getArea().getDocument().addDocumentListener(PanelProductos.alCambiar(this::contarImei));
        p.add(panelImei);
        p.add(separador());
        p.add(intLotes);
        panelLotes.setBorder(BorderFactory.createEmptyBorder(4, 0, 6, 0));
        p.add(panelLotes);

        intCaract.addItemListener(e -> {
            if (intCaract.isSelected() && filasCaract.isEmpty()) {
                agregarCaracteristica("", "");
            }
            panelCaract.setVisible(intCaract.isSelected());
            reacomodar();
        });
        intImei.addItemListener(e -> {
            panelImei.setVisible(intImei.isSelected());
            reacomodar();
        });
        intLotes.addItemListener(e -> {
            if (intLotes.isSelected() && filasLote.isEmpty()) {
                agregarLote(new Lote("", null, 0));
            }
            panelLotes.setVisible(intLotes.isSelected());
            revisarVencimiento();
            reacomodar();
        });
        if (desdeCompra) {
            // la cantidad entra con la compra: aqui no van bodegas ni lotes
            p.remove(zonaInventario);
            p.remove(0); // el separador de debajo
            for (int i = p.getComponentCount() - 1; i >= 0; i--) {
                java.awt.Component c = p.getComponent(i);
                p.remove(i);
                if (c == intLotes) {
                    break;
                }
            }
            p.remove(p.getComponentCount() - 1); // el separador que quedo de ultimo
        }
        return p;
    }

    /** Columnas que se reparten el ancho por igual (sin importar lo largo de su contenido). */
    private static JComponent mismoAncho(JComponent c) {
        c.setPreferredSize(null);
        Dimension d = c.getPreferredSize();
        c.setPreferredSize(new Dimension(260, d.height));
        c.setMinimumSize(new Dimension(200, 10));
        return new Elastico(c);
    }

    /** Envuelve un panel para que su alto se calcule cada vez (las secciones se abren y cierran). */
    private static class Elastico extends JPanel {
        private final JComponent hijo;

        Elastico(JComponent hijo) {
            super(new BorderLayout());
            this.hijo = hijo;
            setOpaque(false);
            hijo.setPreferredSize(null);
            add(hijo);
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(260, hijo.getPreferredSize().height);
        }

        @Override
        public Dimension getMinimumSize() {
            return new Dimension(200, hijo.getPreferredSize().height);
        }
    }

    /** El cuerpo toma el ancho de la ventana (solo se desliza hacia abajo). */
    private static class Cuerpo extends JPanel implements javax.swing.Scrollable {
        Cuerpo() {
            super(new GridBagLayout());
        }

        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        public int getScrollableUnitIncrement(java.awt.Rectangle r, int o, int d) {
            return 18;
        }

        public int getScrollableBlockIncrement(java.awt.Rectangle r, int o, int d) {
            return Math.max(18, r.height - 40);
        }

        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    private static JComponent separador() {
        JPanel s = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                g.setColor(Tema.BORDE);
                g.fillRect(0, getHeight() / 2, getWidth(), 1);
            }
        };
        s.setOpaque(false);
        s.setPreferredSize(new Dimension(10, 22));
        return Controles.ancho(s);
    }

    // ------------------------------------------------------------------
    //  Bodegas
    // ------------------------------------------------------------------

    /** Una fila de la tabla de bodegas: nombre (fijo), stock y stock minimo. */
    private static class FilaBodega {
        final Bodega bodega;
        final JTextField stock;
        final JTextField minimo;

        FilaBodega(Bodega b, JTextField stock, JTextField minimo) {
            this.bodega = b;
            this.stock = stock;
            this.minimo = minimo;
        }
    }

    /** Arma las filas de bodegas conservando lo que ya estaba escrito. */
    private void armarBodegas() {
        java.util.Map<Long, String[]> escrito = new java.util.HashMap<>();
        for (FilaBodega f : filasBodega) {
            escrito.put(f.bodega.getId(), new String[]{f.stock.getText(), f.minimo.getText()});
        }
        filasBodega.clear();
        panelBodegas.removeAll();
        List<Bodega> lista;
        try {
            lista = bd.listarBodegas();
        } catch (SQLException e) {
            Ubicaciones.registrarError(e);
            lista = List.of(new Bodega(Bodega.PUNTO_DE_VENTA, "PUNTO DE VENTA"));
        }
        JPanel grilla = new JPanel(new GridBagLayout());
        grilla.setOpaque(false);
        GridBagConstraints g = new GridBagConstraints();
        g.fill = GridBagConstraints.HORIZONTAL;
        g.anchor = GridBagConstraints.WEST;
        int y = 0;
        for (Bodega b : lista) {
            JTextField st = b.esPuntoDeVenta() ? campoStock : Controles.campoNumero("0");
            JTextField mi = b.esPuntoDeVenta() ? campoMinimo : Controles.campoNumero("0");
            for (JTextField t : new JTextField[]{st, mi}) {
                t.setBackground(Tema.AVISO_FONDO);
            }
            st.getAccessibleContext().setAccessibleName("Stock en " + b.getNombre());
            mi.getAccessibleContext().setAccessibleName("Stock mínimo en " + b.getNombre());
            String[] antes = escrito.get(b.getId());
            if (antes != null) {
                st.setText(antes[0]);
                mi.setText(antes[1]);
            } else if (!b.esPuntoDeVenta()) {
                long[] sm = ficha.bodegas.get(b.getId());
                st.setText(sm == null ? "" : PanelProductos.cantidad(sm[0]));
                mi.setText(sm == null ? "" : PanelProductos.cantidad(sm[1]));
            }
            filasBodega.add(new FilaBodega(b, st, mi));
            g.gridy = y++;
            g.gridx = 0;
            g.weightx = 1.4;
            g.insets = new Insets(0, 0, 8, 24);
            grilla.add(etiquetado("Bodega", Controles.campoFijo(b.getNombre())), g);
            g.gridx = 1;
            g.weightx = 0.6;
            grilla.add(etiquetado("Stock", st), g);
            g.gridx = 2;
            g.insets = new Insets(0, 0, 8, 0);
            grilla.add(etiquetado("Stock mínimo", mi), g);
        }
        grilla.setAlignmentX(Component.LEFT_ALIGNMENT);
        panelBodegas.add(grilla);
        Tema.Boton nueva = Tema.botonPequeno("Nueva bodega");
        nueva.setIcon(Iconos.de("bodega", 18, Tema.AZUL));
        nueva.addActionListener(e -> nuevaBodega());
        JPanel f = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        f.setOpaque(false);
        f.add(nueva);
        f.setAlignmentX(Component.LEFT_ALIGNMENT);
        panelBodegas.add(f);
        reacomodar();
    }

    /** Si el producto maneja inventario se ven las bodegas; si no, una nota. */
    private void mostrarInventario() {
        zonaInventario.removeAll();
        if (NO_MANEJA.equals(comboInventario.getSelectedItem())) {
            Tema.Parrafo l = Tema.parrafo("Este producto no maneja inventario: se vende sin contar existencias "
                    + "(sirve para servicios, recetas y combos).", 600);
            l.setFont(Tema.texto(13.5f));
            l.setForeground(Tema.GRIS_OSCURO);
            zonaInventario.add(l);
        } else {
            Tema.Parrafo l = Tema.parrafo("Gestione la cantidad del producto que tiene en cada bodega y en el stock"
                    + " mínimo especifique cuál es la cantidad mínima que debe haber en bodega para que el sistema marque"
                    + " la alerta. Lo que se vende sale del punto de venta.", 600);
            l.setFont(Tema.texto(13.5f));
            l.setForeground(Tema.GRIS_OSCURO);
            zonaInventario.add(l);
            zonaInventario.add(Tema.espacio(6));
            panelBodegas.setAlignmentX(Component.LEFT_ALIGNMENT);
            zonaInventario.add(panelBodegas);
        }
        reacomodar();
    }

    private void nuevaBodega() {
        pedirNombre("Nueva bodega", "Ej. Bodega 2", nombre -> {
            Bodega b = bd.crearBodega(nombre);
            armarBodegas();
            decir("Bodega «" + b.getNombre() + "» creada", true);
        });
    }

    // ------------------------------------------------------------------
    //  Caracteristicas, IMEI y lotes
    // ------------------------------------------------------------------

    private void agregarCaracteristica(String nombre, String valor) {
        JTextField n = Tema.campo("Ej. Color");
        JTextField v = Tema.campo("Ej. Rojo");
        n.setText(nombre);
        v.setText(valor);
        JTextField[] fila = {n, v};
        filasCaract.add(fila);
        pintarCaracteristicas();
    }

    private void pintarCaracteristicas() {
        panelCaract.removeAll();
        JPanel grilla = new JPanel(new GridBagLayout());
        grilla.setOpaque(false);
        GridBagConstraints g = new GridBagConstraints();
        g.fill = GridBagConstraints.HORIZONTAL;
        g.anchor = GridBagConstraints.SOUTHWEST;
        int y = 0;
        for (JTextField[] fila : filasCaract) {
            g.gridy = y++;
            g.gridx = 0;
            g.weightx = 1;
            g.insets = new Insets(0, 0, 8, 16);
            grilla.add(etiquetado("Característica", fila[0]), g);
            g.gridx = 1;
            grilla.add(etiquetado("Valor", fila[1]), g);
            g.gridx = 2;
            g.weightx = 0;
            g.insets = new Insets(0, 0, 10, 0);
            JButton quitar = Controles.botonQuitar("Quitar esta característica");
            quitar.addActionListener(e -> {
                filasCaract.remove(fila);
                pintarCaracteristicas();
            });
            grilla.add(quitar, g);
        }
        grilla.setAlignmentX(Component.LEFT_ALIGNMENT);
        panelCaract.add(grilla);
        Tema.Boton mas = Tema.botonPequeno("+ Agregar característica");
        mas.addActionListener(e -> agregarCaracteristica("", ""));
        panelCaract.add(enFila(mas));
        reacomodar();
    }

    private void contarImei() {
        List<String> l = leerImeis();
        int malos = 0;
        for (String i : l) {
            if (!FichaProducto.imeiValido(i)) {
                malos++;
            }
        }
        if (l.isEmpty()) {
            lblImei.setText("Un IMEI tiene 15 números (marque *#06# en el celular para verlo).");
            lblImei.setForeground(Tema.GRIS_TEXTO);
        } else if (malos > 0) {
            lblImei.setText(l.size() + " escritos · " + malos + (malos == 1 ? " no es válido" : " no son válidos"));
            lblImei.setForeground(Tema.ERROR_TEXTO);
        } else {
            lblImei.setText(l.size() + (l.size() == 1 ? " IMEI válido" : " IMEI válidos"));
            lblImei.setForeground(Tema.EXITO_TEXTO);
        }
    }

    private List<String> leerImeis() {
        List<String> l = new ArrayList<>();
        for (String r : areaImei.getText().split("[\\r\\n,;]+")) {
            String t = r.replaceAll("[\\s-]", "");
            if (!t.isEmpty()) {
                l.add(t);
            }
        }
        return l;
    }

    /** Fila de lote: numero, fecha de vencimiento y cantidad. */
    private class FilaLote {
        final JTextField numero = Tema.campo("Ej. L-2026-01");
        final Controles.CampoFecha vence = new Controles.CampoFecha("Vencimiento del lote");
        final JTextField cantidad = Controles.campoNumero("0");

        FilaLote(Lote l) {
            numero.setText(l.getNumero());
            vence.setFecha(l.getVence());
            cantidad.setText(l.getCantidad() == 0 && l.getNumero().isEmpty() ? "" : PanelProductos.cantidad(l.getCantidad()));
            vence.setAlCambiar(FormularioProducto.this::revisarVencimiento);
            cantidad.getDocument().addDocumentListener(PanelProductos.alCambiar(FormularioProducto.this::sumarLotes));
        }
    }

    private void agregarLote(Lote l) {
        filasLote.add(new FilaLote(l));
        pintarLotes();
    }

    private void pintarLotes() {
        panelLotes.removeAll();
        JPanel grilla = new JPanel(new GridBagLayout());
        grilla.setOpaque(false);
        GridBagConstraints g = new GridBagConstraints();
        g.fill = GridBagConstraints.HORIZONTAL;
        g.anchor = GridBagConstraints.SOUTHWEST;
        int y = 0;
        for (FilaLote f : filasLote) {
            g.gridy = y++;
            g.gridx = 0;
            g.weightx = 1;
            g.insets = new Insets(0, 0, 8, 16);
            grilla.add(etiquetado("Número de lote", f.numero), g);
            g.gridx = 1;
            grilla.add(etiquetado("Vence", f.vence), g);
            g.gridx = 2;
            g.weightx = 0.7;
            grilla.add(etiquetado("Cantidad", f.cantidad), g);
            g.gridx = 3;
            g.weightx = 0;
            g.insets = new Insets(0, 0, 10, 0);
            JButton quitar = Controles.botonQuitar("Quitar este lote");
            quitar.addActionListener(e -> {
                filasLote.remove(f);
                pintarLotes();
                revisarVencimiento();
            });
            grilla.add(quitar, g);
        }
        grilla.setAlignmentX(Component.LEFT_ALIGNMENT);
        panelLotes.add(grilla);
        Tema.Boton mas = Tema.botonPequeno("Agregar lote");
        mas.setIcon(Iconos.de("lote", 18, Tema.AZUL));
        mas.addActionListener(e -> agregarLote(new Lote("", null, 0)));
        panelLotes.add(enFila(mas));
        panelLotes.add(lblLotes);
        sumarLotes();
        reacomodar();
    }

    private void sumarLotes() {
        long suma = 0;
        try {
            for (FilaLote f : filasLote) {
                if (!f.cantidad.getText().isBlank()) {
                    suma += ImportadorProductos.leerCantidad(f.cantidad.getText());
                }
            }
        } catch (IllegalArgumentException e) {
            lblLotes.setText("Revise las cantidades de los lotes");
            lblLotes.setForeground(Tema.ERROR_TEXTO);
            return;
        }
        String enPunto = campoStock.getText().isBlank() ? "0" : campoStock.getText().trim();
        lblLotes.setText("Los lotes suman " + PanelProductos.cantidad(suma) + " · en el punto de venta hay " + enPunto);
        lblLotes.setForeground(Tema.GRIS_TEXTO);
    }

    // ------------------------------------------------------------------
    //  Cargar
    // ------------------------------------------------------------------

    private void cargar(Producto p, String codigoNuevo) {
        cargando = true;
        try {
            llenar(p, codigoNuevo);
        } finally {
            cargando = false;
        }
        stockAlAbrir = campoStock.getText().trim();
    }

    private void llenar(Producto p, String codigoNuevo) {
        FichaProducto f = ficha;
        if (p != null && FichaProducto.unidadDe(f.medida) != p.getUnidad()) {
            f.medida = FichaProducto.medidaDe(p.getUnidad()); // la importacion cambio la unidad: manda la del producto
        }
        if (p == null) {
            campoCodigo.setText(codigoNuevo == null ? "" : codigoNuevo);
            comboEstado.setSelectedIndex(0);
            if (codigoNuevo != null) {
                decir("Ese código no está registrado. Escriba los datos y guarde.", true);
                lblForm.setForeground(Tema.AVISO_TEXTO);
            }
        } else {
            campoNombre.setText(p.getNombre());
            campoCodigo.setText(p.getCodigo() == null ? "" : p.getCodigo());
            llenarCategorias(p.getCategoriaId());
            campoVenta.setText(numero(p.getPrecioVenta()));
            campoCompra.setText(numero(p.getPrecioCompra()));
            campoStock.setText(PanelProductos.cantidad(p.getStock()));
            campoMinimo.setText(PanelProductos.cantidad(p.getMinimo()));
            comboEstado.setSelectedIndex(p.isActivo() ? 0 : 1);
            comboInventario.setSelectedItem(p.isManejaInventario() ? MANEJA : NO_MANEJA);
            if (p.getFoto() != null) {
                File foto = new File(p.getFoto());
                zonaFoto.setImagen(foto.isFile() ? new ImageIcon(foto.getAbsolutePath()).getImage() : null);
            }
            if (!p.isActivo()) {
                decir("Este producto está inactivo: no sale en Vender.", true);
                lblForm.setForeground(Tema.AVISO_TEXTO);
            }
        }
        areaDescripcion.setText(f.descripcion);
        areaGarantia.setText(f.garantia);
        comboMedida.setSelectedItem(f.medida);
        impuestos.setElegidas(f.impuestos);
        campoFecha.setFecha(f.vencimiento);
        campoMayor.setText(f.precioMayor == 0 ? "" : numero(f.precioMayor));
        chkProducto.setSelected(f.esProducto);
        chkInsumo.setSelected(f.esInsumo);
        chkReceta.setSelected(f.esReceta);
        areaUrl.setText(f.urlImagen);
        urlBajada = f.urlImagen; // la que ya esta guardada no se vuelve a bajar
        esperaUrl.stop();
        if (verCompra) {
            campoMargen.setText(f.margenCentesimas == 0 ? "" : porcentaje(f.margenCentesimas));
            filaMargen.setVisible(false);
            intMargen.setSelected(f.porMargen);
        }
        for (String[] c : f.caracteristicas) {
            filasCaract.add(new JTextField[]{campoCon(c[0], "Ej. Color"), campoCon(c[1], "Ej. Rojo")});
        }
        pintarCaracteristicas();
        panelCaract.setVisible(false);
        intCaract.setSelected(f.caracterizar);
        areaImei.setText(String.join("\n", f.imeis));
        contarImei();
        panelImei.setVisible(false);
        intImei.setSelected(f.usaImei);
        for (Lote l : f.lotes) {
            filasLote.add(new FilaLote(l));
        }
        pintarLotes();
        panelLotes.setVisible(false);
        intLotes.setSelected(f.usaLotes);
        armarBodegas();
        mostrarInventario();
        revisarVencimiento();
        calcularGanancia();
    }

    private static JTextField campoCon(String texto, String ayuda) {
        JTextField t = Tema.campo(ayuda);
        t.setText(texto);
        return t;
    }

    private void llenarCategorias(Long elegir) {
        comboCategoria.removeAllItems();
        comboCategoria.addItem(SIN_CATEGORIA);
        try {
            for (Categoria c : bd.listarCategorias(false)) {
                comboCategoria.addItem(c);
            }
        } catch (SQLException e) {
            Ubicaciones.registrarError(e);
        }
        comboCategoria.setSelectedItem(SIN_CATEGORIA);
        for (int i = 0; i < comboCategoria.getItemCount(); i++) {
            if (elegir != null && comboCategoria.getItemAt(i).getId() == elegir) {
                comboCategoria.setSelectedIndex(i);
            }
        }
    }

    // ------------------------------------------------------------------
    //  Avisos mientras se escribe
    // ------------------------------------------------------------------

    private void revisarVencimiento() {
        revisarVencimiento0();
        boolean ver = !lblVence.getText().isBlank();
        if (lblVence.isVisible() != ver) {
            lblVence.setVisible(ver);
            reacomodar();
        }
    }

    private void revisarVencimiento0() {
        LocalDate propia = campoFecha.fechaSiEsValida();
        if (!campoFecha.getCampo().getText().isBlank() && propia == null) {
            lblVence.setText("Escriba la fecha así: 31/12/2026");
            lblVence.setForeground(Tema.ERROR_TEXTO);
            return;
        }
        LocalDate v = propia;
        String origen = "";
        if (intLotes.isSelected()) {
            for (FilaLote f : filasLote) {
                LocalDate lv = f.vence.fechaSiEsValida();
                if (lv != null && (v == null || lv.isBefore(v))) {
                    v = lv;
                    origen = " (lote " + (f.numero.getText().isBlank() ? "sin número" : f.numero.getText().trim()) + ")";
                }
            }
        }
        if (v == null) {
            lblVence.setText(" ");
            return;
        }
        long dias = ChronoUnit.DAYS.between(LocalDate.now(), v);
        if (dias < 0) {
            lblVence.setText("Ya venció" + origen + " hace " + (-dias) + (dias == -1 ? " día" : " días"));
            lblVence.setForeground(Tema.ERROR_TEXTO);
        } else if (dias <= 30) {
            lblVence.setText(dias == 0 ? "Vence hoy" + origen : "Vence en " + dias + (dias == 1 ? " día" : " días") + origen);
            lblVence.setForeground(Tema.AVISO_TEXTO);
        } else {
            lblVence.setText("Vence en " + dias + " días" + origen);
            lblVence.setForeground(Tema.GRIS_TEXTO);
        }
    }

    /** Con el margen encendido, el precio de venta sale del costo mas el margen. */
    private void calcularVenta() {
        if (cargando || !intMargen.isSelected()) {
            return;
        }
        try {
            long costo = campoCompra.getText().isBlank() ? 0 : ImportadorProductos.leerPesos(campoCompra.getText());
            long margen = leerPorcentaje(campoMargen.getText());
            String nuevo = numero(FichaProducto.precioConMargen(costo, margen));
            if (!nuevo.equals(campoVenta.getText())) {
                SwingUtilities.invokeLater(() -> campoVenta.setText(nuevo)); // no se cambia un documento mientras avisa
            }
        } catch (IllegalArgumentException e) {
            // mientras escribe puede quedar a medias; el error se dice al guardar
        }
    }

    private void calcularGanancia() {
        try {
            if (campoVenta.getText().isBlank() || campoCompra.getText().isBlank()) {
                lblGanancia.setText(" ");
                return;
            }
            long venta = ImportadorProductos.leerPesos(campoVenta.getText());
            long compra = ImportadorProductos.leerPesos(campoCompra.getText());
            long gana = venta - compra;
            if (compra <= 0) {
                lblGanancia.setText(" ");
            } else if (gana < 0) {
                lblGanancia.setText("Se vende " + Tarifa.pesos(-gana) + " por debajo del costo");
                lblGanancia.setForeground(Tema.ERROR_TEXTO);
            } else {
                lblGanancia.setText("Ganancia: " + Tarifa.pesos(gana) + " por unidad ("
                        + porcentaje(Math.round(gana * 10000.0 / compra)) + " % sobre el costo)");
                lblGanancia.setForeground(Tema.EXITO_TEXTO);
            }
        } catch (IllegalArgumentException e) {
            lblGanancia.setText(" ");
        }
    }

    // ------------------------------------------------------------------
    //  Guardar
    // ------------------------------------------------------------------

    private void guardar() {
        try {
            // 1. leer y revisar todo antes de tocar la base
            String medida = (String) comboMedida.getSelectedItem();
            boolean maneja = MANEJA.equals(comboInventario.getSelectedItem());
            long venta = ImportadorProductos.leerPesos(campoVenta.getText());
            long compra = !verCompra ? (editando == null ? 0 : editando.getPrecioCompra())
                    : campoCompra.getText().isBlank() ? 0 : ImportadorProductos.leerPesos(campoCompra.getText());
            rutas.modelo.Unidad unidad = FichaProducto.unidadDe(medida);
            long stock;
            if (editando != null && campoStock.getText().trim().equals(stockAlAbrir)) {
                // no tocaron la cantidad: se guarda la de la base (pudo bajar por ventas mientras tanto,
                // y puede estar negativa por ventas: eso no se revisa aqui)
                Producto actual = bd.buscarProductoPorId(editando.getId());
                stock = actual != null ? actual.getStock() : editando.getStock();
            } else {
                stock = leerCantidadOCero(campoStock, "el stock del punto de venta");
                ImportadorProductos.revisarCantidadEscrita(stock, unidad, "El stock del punto de venta");
            }
            long minimo;
            if (editando != null && campoMinimo.getText().trim().equals(PanelProductos.cantidad(editando.getMinimo()))) {
                minimo = editando.getMinimo(); // sin cambios: se respeta lo que ya estaba
            } else {
                minimo = leerCantidadOCero(campoMinimo, "el stock mínimo del punto de venta");
                ImportadorProductos.revisarCantidadEscrita(minimo, unidad, "El stock mínimo");
            }
            FichaProducto f = leerFicha(venta);
            f.validar(venta);
            byte[] desdeUrl = null;
            boolean hayQueBajar = fotoElegida == null && !f.urlImagen.isEmpty()
                    && (!f.urlImagen.equals(ficha.urlImagen) || editando == null || editando.getFoto() == null);
            if (hayQueBajar) {
                if (f.urlImagen.equals(urlBajada) && fotoDeUrl != null) {
                    desdeUrl = fotoDeUrl;
                } else {
                    // se baja aparte (sin trabar la pantalla) y se pide oprimir otra vez
                    if (!bajando) {
                        bajarVistaUrl(true);
                    }
                    decir("Espere a que cargue la imagen de la dirección y oprima «"
                            + (editando == null ? "Guardar" : "Actualizar") + "» otra vez.", false);
                    lblForm.setForeground(Tema.AVISO_TEXTO);
                    return;
                }
            }
            // un IMEI que ya tiene otro producto se avisa ANTES de guardar nada
            if (f.usaImei) {
                for (String imei : f.imeis) {
                    String otro = bd.productoConImei(imei, editando == null ? 0 : editando.getId());
                    if (otro != null) {
                        throw new IllegalArgumentException("El IMEI " + imei + " ya está en «" + otro + "»");
                    }
                }
            }
            if (fotoElegida != null && fotoElegida.length() > MAXIMO_FOTO) {
                throw new IllegalArgumentException("La foto pesa más de 1 MB. Escoja una más liviana.");
            }
            Categoria c = (Categoria) comboCategoria.getSelectedItem();
            Long categoriaId = c == null || c.getId() == 0 ? null : c.getId();
            long id = editando == null ? 0 : editando.getId();
            String foto = editando == null ? null : editando.getFoto();
            Producto p = new Producto(id, campoCodigo.getText(), campoNombre.getText(), FichaProducto.unidadDe(medida),
                    venta, compra, stock, minimo, foto, true, categoriaId, maneja);

            // 2. guardar
            boolean eraNuevo = editando == null;
            p = eraNuevo ? cat.crear(p) : cat.editar(p);
            editando = p; // si lo que sigue falla, guardar otra vez edita este producto en vez de duplicarlo
            bd.guardarFicha(p.getId(), f);
            ficha = f;
            String ruta = null;
            if (fotoElegida != null) {
                ruta = PanelProductos.copiarFoto(fotoElegida, "producto-" + p.getId());
            } else if (desdeUrl != null) {
                ruta = guardarBytes(desdeUrl, "producto-" + p.getId());
            }
            if (ruta != null) {
                Fotos.olvidar(ruta); // la foto nueva queda con el mismo nombre de archivo
                p = cat.editar(new Producto(p.getId(), p.getCodigo(), p.getNombre(), p.getUnidad(), p.getPrecioVenta(),
                        p.getPrecioCompra(), p.getStock(), p.getMinimo(), ruta, true, p.getCategoriaId(),
                        p.isManejaInventario()));
                fotoElegida = null;
                fotoDeUrl = null;
                urlBajada = f.urlImagen;
            }
            boolean quiereActivo = desdeCompra || comboEstado.getSelectedIndex() == 0; // lo que se compra queda activo
            boolean estaActivo = bd.buscarProductoPorId(p.getId()).isActivo();
            if (quiereActivo && !estaActivo) {
                cat.reactivar(p.getId());
            } else if (!quiereActivo && estaActivo) {
                cat.desactivar(p.getId());
            }
            capa.cerrar();
            alGuardar.run();
            if (eraNuevo) {
                creadoAqui = true;
            }
            if (alCrear != null && creadoAqui) {
                alCrear.accept(cat.buscarPorId(p.getId()) != null ? cat.buscarPorId(p.getId()) : p);
            }
            Aviso.mostrar(panel, Aviso.Tipo.BIEN, eraNuevo ? "Producto creado" : "Cambios guardados", p.getNombre());
        } catch (IllegalArgumentException e) {
            decir(e.getMessage(), false);
        } catch (SQLException | IOException e) {
            decir("No se pudo guardar: " + e.getMessage(), false);
        }
    }

    /** Arma la ficha con lo que hay en pantalla (puede lanzar IllegalArgumentException si algo esta mal escrito). */
    private FichaProducto leerFicha(long venta) {
        FichaProducto f = new FichaProducto();
        f.descripcion = areaDescripcion.getText().trim();
        f.garantia = areaGarantia.getText().trim();
        f.medida = (String) comboMedida.getSelectedItem();
        f.impuestos = impuestos.getElegidas();
        f.vencimiento = campoFecha.getFecha();
        f.porMargen = verCompra ? intMargen.isSelected() : ficha.porMargen;
        if (!verCompra && f.porMargen && editando != null
                && venta != FichaProducto.precioConMargen(editando.getPrecioCompra(), ficha.margenCentesimas)) {
            f.porMargen = false; // cambiaron el precio a mano: ya no sale del margen
        }
        f.margenCentesimas = verCompra ? (campoMargen.getText().isBlank() ? 0 : leerPorcentaje(campoMargen.getText()))
                : ficha.margenCentesimas;
        if (f.porMargen && campoMargen.getText().isBlank() && verCompra) {
            throw new IllegalArgumentException("Escriba el margen de utilidad (%) o apague «¿Precio por margen de utilidad?»");
        }
        f.precioMayor = campoMayor.getText().isBlank() ? 0 : ImportadorProductos.leerPesos(campoMayor.getText());
        f.esProducto = chkProducto.isSelected();
        f.esInsumo = chkInsumo.isSelected();
        f.esReceta = chkReceta.isSelected();
        f.urlImagen = areaUrl.getText().trim();
        f.caracterizar = intCaract.isSelected();
        if (f.caracterizar) {
            for (JTextField[] c : filasCaract) {
                if (!c[0].getText().isBlank() || !c[1].getText().isBlank()) {
                    f.caracteristicas.add(new String[]{c[0].getText().trim(), c[1].getText().trim()});
                }
            }
        }
        f.usaImei = intImei.isSelected();
        if (f.usaImei) {
            f.imeis = leerImeis();
        }
        f.usaLotes = intLotes.isSelected();
        if (f.usaLotes) {
            for (FilaLote l : filasLote) {
                if (l.numero.getText().isBlank() && l.cantidad.getText().isBlank() && l.vence.getCampo().getText().isBlank()) {
                    continue; // fila vacia
                }
                LocalDate v;
                try {
                    v = l.vence.getFecha();
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("Lote " + l.numero.getText().trim() + ": " + e.getMessage());
                }
                long cantLote = leerCantidadOCero(l.cantidad, "la cantidad del lote");
                ImportadorProductos.revisarCantidadEscrita(cantLote, FichaProducto.unidadDe(f.medida),
                        "La cantidad del lote " + l.numero.getText().trim());
                f.lotes.add(new Lote(l.numero.getText(), v, cantLote));
            }
        }
        java.util.Map<Long, long[]> enBase = null; // lo que hay hoy en la base (pudo entrar una compra)
        for (FilaBodega b : filasBodega) {
            if (!b.bodega.esPuntoDeVenta()) {
                long[] antes = ficha.bodegas.get(b.bodega.getId());
                String alAbrir = antes == null ? "" : PanelProductos.cantidad(antes[0]);
                boolean igual = editando != null && b.stock.getText().trim().equals(alAbrir);
                long st = igual && antes != null ? antes[0] : leerCantidadOCero(b.stock, "el stock de " + b.bodega.getNombre());
                if (!igual) {
                    ImportadorProductos.revisarCantidadEscrita(st, FichaProducto.unidadDe(f.medida),
                            "El stock de " + b.bodega.getNombre());
                }
                if (editando != null && b.stock.getText().trim().equals(alAbrir)) {
                    // no tocaron esa cantidad: se guarda la de la base
                    try {
                        if (enBase == null) {
                            enBase = bd.leerFicha(editando.getId()).bodegas;
                        }
                        long[] hoy = enBase.get(b.bodega.getId());
                        st = hoy == null ? st : hoy[0];
                    } catch (SQLException e) {
                        Ubicaciones.registrarError(e);
                    }
                }
                f.bodegas.put(b.bodega.getId(), new long[]{st,
                    leerCantidadOCero(b.minimo, "el stock mínimo de " + b.bodega.getNombre())});
            }
        }
        return f;
    }

    private static long leerCantidadOCero(JTextField t, String que) {
        if (t.getText().isBlank()) {
            return 0;
        }
        try {
            return ImportadorProductos.leerCantidad(t.getText());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Revise " + que + ": " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    //  Foto: archivo, arrastrar o direccion de internet
    // ------------------------------------------------------------------

    private void elegirFoto() {
        File f = Archivos.abrir(panel, "Elegir la imagen del producto", "jpg", "jpeg", "png");
        if (f != null) {
            usarFoto(f);
        }
    }

    private void usarFoto(File f) {
        if (f.length() > MAXIMO_FOTO) {
            decir("Esa imagen pesa " + (f.length() / 1024) + " KB. El máximo es 1 MB.", false);
            return;
        }
        Image img = new ImageIcon(f.getAbsolutePath()).getImage();
        if (img.getWidth(null) <= 0) {
            decir("No se pudo abrir esa imagen. Use JPG o PNG.", false);
            return;
        }
        fotoElegida = f;
        zonaFoto.setImagen(img);
        decir("La imagen se guarda al oprimir «" + (editando == null ? "Guardar" : "Actualizar") + "»", true);
    }

    /** Al dejar de escribir la direccion, se baja la imagen para verla (sin trabar la pantalla). */
    private void bajarVistaUrl() {
        bajarVistaUrl(false);
    }

    private void bajarVistaUrl(boolean aunqueSeaLaGuardada) {
        String url = areaUrl.getText().trim();
        if (fotoElegida != null || url.isEmpty() || (url.equals(urlBajada) && (!aunqueSeaLaGuardada || fotoDeUrl != null))) {
            return;
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            decir("La dirección de imagen debe empezar por http:// o https://", false);
            return;
        }
        decir("Abriendo la imagen de la dirección…", true);
        lblForm.setForeground(Tema.GRIS_TEXTO);
        bajando = true;
        new SwingWorker<byte[], Void>() {
            @Override
            protected byte[] doInBackground() throws Exception {
                return bajarImagen(url);
            }

            @Override
            protected void done() {
                bajando = false;
                if (!url.equals(areaUrl.getText().trim()) || fotoElegida != null) {
                    return; // ya escribieron otra direccion o escogieron un archivo
                }
                try {
                    byte[] b = get();
                    fotoDeUrl = b;
                    urlBajada = url;
                    zonaFoto.setImagen(javax.imageio.ImageIO.read(new ByteArrayInputStream(b)));
                    decir("Imagen lista. Se guarda al oprimir «" + (editando == null ? "Guardar" : "Actualizar") + "»", true);
                } catch (Exception e) {
                    Throwable c = e.getCause() == null ? e : e.getCause();
                    decir(c.getMessage() == null ? "No se pudo abrir la imagen de esa dirección" : c.getMessage(), false);
                }
            }
        }.execute();
    }

    /** Baja una imagen JPG o PNG de hasta 1 MB. */
    static byte[] bajarImagen(String direccion) {
        java.net.URL url;
        try {
            url = java.net.URI.create(direccion.trim()).toURL();
        } catch (IllegalArgumentException | java.net.MalformedURLException e) {
            throw new IllegalArgumentException("Esa dirección de imagen no está bien escrita");
        }
        byte[] datos;
        try {
            java.net.URLConnection con = url.openConnection();
            con.setConnectTimeout(6000);
            con.setReadTimeout(8000);
            con.setRequestProperty("User-Agent", "DomiRuta");
            try (InputStream in = con.getInputStream()) {
                datos = in.readNBytes((int) MAXIMO_FOTO + 1);
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("No se pudo bajar la imagen (revise internet y la dirección)");
        }
        if (datos.length > MAXIMO_FOTO) {
            throw new IllegalArgumentException("La imagen de esa dirección pesa más de 1 MB");
        }
        if (!esPng(datos) && !esJpg(datos)) {
            throw new IllegalArgumentException("La dirección no es de una imagen JPG o PNG");
        }
        try {
            if (javax.imageio.ImageIO.read(new ByteArrayInputStream(datos)) == null) {
                throw new IllegalArgumentException("La imagen de esa dirección está dañada");
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("La imagen de esa dirección está dañada");
        }
        return datos;
    }

    private static boolean esPng(byte[] b) {
        return b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
    }

    private static boolean esJpg(byte[] b) {
        return b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8;
    }

    private static String guardarBytes(byte[] datos, String nombreBase) throws IOException {
        File carpeta = new File(Ubicaciones.carpetaUsuario(), "fotos");
        carpeta.mkdirs();
        File destino = new File(carpeta, nombreBase + (esPng(datos) ? ".png" : ".jpg"));
        Files.write(destino.toPath(), datos);
        return destino.getAbsolutePath();
    }

    // ------------------------------------------------------------------
    //  Codigo de barras y categoria
    // ------------------------------------------------------------------

    private void generarCodigo() {
        String antes = campoCodigo.getText().trim();
        String nuevo;
        try {
            nuevo = CodigoBarras.generar(c -> {
                try {
                    return cat.buscarCodigo(c) != null || bd.buscarPorCodigoEnBase(c) != null;
                } catch (SQLException e) {
                    return true;
                }
            });
        } catch (IllegalStateException e) {
            decir("No se pudo generar un código. Intente otra vez.", false);
            return;
        }
        campoCodigo.setText(nuevo);
        String boton = editando == null ? "Guardar" : "Actualizar";
        decir(antes.isEmpty() ? "Código generado. Se guarda al oprimir «" + boton + "»."
                : "Código cambiado (antes era " + antes + "). Si no oprime «" + boton + "», queda el anterior.", true);
    }

    private void imprimirEtiqueta() {
        String codigo = campoCodigo.getText().trim();
        if (!CodigoBarras.sePuede(codigo)) {
            decir(codigo.isEmpty() ? "Primero escriba o genere el código." : "Ese código no se puede imprimir"
                    + " (tiene tildes o ñ).", false);
            return;
        }
        String precio = "";
        try {
            precio = campoVenta.getText().isBlank() ? "" : Tarifa.pesos(ImportadorProductos.leerPesos(campoVenta.getText()));
        } catch (IllegalArgumentException e) {
            // sin precio valido la etiqueta sale sin precio
        }
        java.awt.print.PrinterJob job = java.awt.print.PrinterJob.getPrinterJob();
        job.setJobName("Etiqueta " + codigo);
        job.setPrintable(new CodigoBarras.Etiqueta(codigo, campoNombre.getText().trim(), precio));
        try {
            if (job.printDialog()) {
                job.print();
            }
        } catch (java.awt.print.PrinterException | RuntimeException e) {
            decir("No se pudo imprimir. En Windows puede escoger «Microsoft Print to PDF».", false);
        }
    }

    private void nuevaCategoria() {
        pedirNombre("Nueva categoría", "Ej. Aceites", nombre -> {
            int orden = bd.listarCategorias(true).size() + 1;
            Categoria nueva = bd.crearCategoria(nombre, null, orden);
            llenarCategorias(nueva.getId());
            if (productos != null) {
                productos.categoriasCambiaron();
            }
            decir("Categoría «" + nueva.getNombre() + "» creada", true);
        });
    }

    interface ConNombre {
        void usar(String nombre) throws SQLException;
    }

    /** Capa pequeña encima del formulario para escribir un nombre (categoria o bodega). */
    private void pedirNombre(String titulo, String ayuda, ConNombre accion) {
        JPanel t = Capa.tarjeta(380, 22, 24);
        t.add(Tema.tituloPantalla(titulo));
        JTextField nombre = Tema.campo(ayuda);
        nombre.setMaximumSize(new Dimension(Integer.MAX_VALUE, nombre.getPreferredSize().height));
        t.add(etiquetado("Nombre", nombre));
        Tema.Parrafo msg = Tema.parrafo(" ", 320);
        msg.setFont(Tema.texto(13));
        t.add(msg);
        t.add(Tema.espacio(8));
        Tema.Boton crear = Tema.botonPrimario("Crear");
        Tema.Boton volver = Tema.botonSecundario("Volver");
        t.add(Tema.fila(8, crear, volver));
        Capa c = new Capa(panel, t, true);
        Runnable hacer = () -> {
            try {
                accion.usar(nombre.getText());
                c.cerrar();
            } catch (IllegalArgumentException e) {
                msg.setForeground(Tema.ERROR_TEXTO);
                msg.setText(e.getMessage());
            } catch (SQLException e) {
                msg.setForeground(Tema.ERROR_TEXTO);
                msg.setText("No se pudo crear: " + e.getMessage());
            }
        };
        crear.addActionListener(e -> hacer.run());
        nombre.addActionListener(e -> hacer.run());
        volver.addActionListener(e -> c.cerrar());
        c.mostrar(nombre);
    }

    // ------------------------------------------------------------------
    //  Ayudas
    // ------------------------------------------------------------------

    private void decir(String texto, boolean bien) {
        lblForm.setForeground(bien ? Tema.EXITO_TEXTO : Tema.ERROR_TEXTO);
        lblForm.setText(texto);
    }

    private void reacomodar() {
        if (capa != null) {
            modal.revalidate();
            capa.reacomodar();
        }
    }

    private static JPanel columna() {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        return p;
    }

    private static JLabel nota() {
        JLabel l = new JLabel(" ");
        l.setFont(Tema.texto(12.5f));
        l.setForeground(Tema.GRIS_TEXTO);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        l.setBorder(BorderFactory.createEmptyBorder(3, 0, 0, 0));
        return l;
    }

    /** Etiqueta encima del control. El panel nunca queda mas bajo que lo que necesita (si no, el borde de abajo se corta). */
    private static JPanel etiquetado(String etiqueta, JComponent c) {
        JPanel p = new JPanel() {
            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        p.setOpaque(false);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        JLabel l = new JLabel(etiqueta);
        l.setFont(Tema.textoFuerte(12.5f));
        l.setForeground(Tema.GRIS_OSCURO);
        l.setBorder(BorderFactory.createEmptyBorder(8, 0, 4, 0));
        l.setMaximumSize(new Dimension(Integer.MAX_VALUE, l.getPreferredSize().height));
        l.setLabelFor(c instanceof JScrollPane ? ((JScrollPane) c).getViewport().getView() : c);
        compactar(c);
        if (c instanceof JTextField || c instanceof JComboBox) {
            String antes = c.getAccessibleContext().getAccessibleName();
            if (antes == null || antes.matches("[0-9.,\\s]*") || antes.startsWith("Ej.") || antes.startsWith("Escanee")) {
                c.getAccessibleContext().setAccessibleName(etiqueta); // el lector de pantalla dice la etiqueta
            }
        }
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        if (!(c instanceof JScrollPane)) {
            c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height));
        }
        p.add(l);
        p.add(c);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        return p;
    }

    /** Campos y listas mas bajitos y con letra de 13 px, como en la referencia. */
    static void compactar(JComponent c) {
        if (c instanceof JTextField) {
            JTextField t = (JTextField) c;
            t.setFont(Tema.texto(13));
            t.setBorder(BorderFactory.createCompoundBorder(new Tema.BordeRedondo(Tema.BORDE_CONTROL, Tema.RADIO),
                    BorderFactory.createEmptyBorder(6, 9, 6, 9)));
        } else if (c instanceof JComboBox) {
            c.setFont(Tema.texto(13));
        } else if (c instanceof Controles.CampoFecha) {
            compactar(((Controles.CampoFecha) c).getCampo());
        } else if (c instanceof JPanel && ((JPanel) c).getLayout() instanceof BorderLayout) {
            for (Component k : c.getComponents()) { // campo con boton al lado
                if (k instanceof JTextField || k instanceof JComboBox) {
                    compactar((JComponent) k);
                }
            }
        }
    }

    private static JPanel conBoton(JComponent campo, JComponent boton) {
        compactar(campo);
        int alto = campo.getPreferredSize().height;
        boton.setFont(boton.getFont().deriveFont(13f));
        boton.setPreferredSize(new Dimension(boton.getPreferredSize().width, alto)); // mismo alto que el campo
        JPanel p = new JPanel(new BorderLayout(6, 0));
        p.setOpaque(false);
        p.add(campo, BorderLayout.CENTER);
        p.add(boton, BorderLayout.EAST);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, Math.max(campo.getPreferredSize().height,
                boton.getPreferredSize().height)));
        return p;
    }

    private static JPanel enFila(JComponent c) {
        JPanel f = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 4));
        f.setOpaque(false);
        f.add(c);
        f.setAlignmentX(Component.LEFT_ALIGNMENT);
        return f;
    }

    private static String numero(long pesos) {
        return Tarifa.pesos(pesos).replace("$", "");
    }

    /** 2550 -> "25,5"; 2500 -> "25". */
    static String porcentaje(long centesimas) {
        BigDecimal b = BigDecimal.valueOf(centesimas, 2).stripTrailingZeros();
        return b.scale() < 0 ? b.setScale(0).toPlainString() : b.toPlainString().replace('.', ',');
    }

    /** "25,5" o "25.5" o "25 %" -> 2550. */
    static long leerPorcentaje(String t) {
        String s = t.replace("%", "").trim().replace(',', '.');
        if (s.isEmpty()) {
            return 0;
        }
        try {
            BigDecimal b = new BigDecimal(s).movePointRight(2);
            if (b.scale() > 0 && b.stripTrailingZeros().scale() > 0) {
                throw new IllegalArgumentException("El margen va con máximo dos decimales");
            }
            return b.longValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            throw new IllegalArgumentException("El margen «" + t.trim() + "» no es un número");
        }
    }

    // ------------------------------------------------------------------
    //  Piezas dibujadas
    // ------------------------------------------------------------------

    /** X blanca de la barra azul para cerrar. */
    private static class BotonCerrar extends JButton {
        BotonCerrar() {
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setPreferredSize(new Dimension(38, 38));
            setToolTipText("Cerrar sin guardar (Esc)");
            getAccessibleContext().setAccessibleName("Cerrar");
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

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Tema.suavizar(g);
            int w = getWidth(), h = getHeight();
            if (getModel().isRollover() || isFocusOwner()) {
                g.setColor(Tema.AZUL_PROFUNDO);
                g.fillRoundRect(0, 0, w, h, Tema.ARCO, Tema.ARCO);
            }
            if (isFocusOwner()) {
                g.setColor(Tema.BLANCO);
                g.setStroke(new BasicStroke(1.5f));
                g.drawRoundRect(1, 1, w - 3, h - 3, Tema.ARCO, Tema.ARCO);
            }
            g.setColor(Tema.BLANCO);
            g.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            int m = 12;
            g.drawLine(m, m, w - m, h - m);
            g.drawLine(w - m, m, m, h - m);
            g.dispose();
        }
    }

    /**
     * Recuadro punteado de la imagen con la caja de "Arrastre aqui o haga clic" debajo.
     * Se puede soltar un archivo encima o hacer clic para elegirlo.
     */
    private static final Image CAJA = cargarCaja();

    private static Image cargarCaja() {
        java.net.URL u = FormularioProducto.class.getResource("/imagenes/caja.png");
        if (u == null) {
            return new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        }
        Image grande = new ImageIcon(u).getImage();
        return new ImageIcon(grande.getScaledInstance(110, 110, Image.SCALE_AREA_AVERAGING)).getImage();
    }

    private class ZonaFoto extends JPanel {
        private Image imagen;
        private boolean encima;

        ZonaFoto() {
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText("Arrastre aquí la imagen o haga clic para elegirla");
            getAccessibleContext().setAccessibleName("Imagen del producto");
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    elegirFoto();
                }
            });
            setTransferHandler(new javax.swing.TransferHandler() {
                @Override
                public boolean canImport(TransferSupport s) {
                    boolean si = s.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor);
                    if (encima != si) {
                        encima = si;
                        repaint();
                    }
                    return si;
                }

                @Override
                public boolean importData(TransferSupport s) {
                    encima = false;
                    repaint();
                    try {
                        @SuppressWarnings("unchecked")
                        List<File> archivos = (List<File>) s.getTransferable()
                                .getTransferData(java.awt.datatransfer.DataFlavor.javaFileListFlavor);
                        for (File f : archivos) {
                            String n = f.getName().toLowerCase();
                            if (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png")) {
                                usarFoto(f);
                                return true;
                            }
                        }
                        decir("Esa no es una imagen JPG o PNG", false);
                    } catch (java.awt.datatransfer.UnsupportedFlavorException | IOException e) {
                        decir("No se pudo leer el archivo", false);
                    }
                    return false;
                }
            });
            try {
                getDropTarget().addDropTargetListener(new java.awt.dnd.DropTargetAdapter() {
                    @Override
                    public void dragExit(java.awt.dnd.DropTargetEvent e) {
                        encima = false;
                        repaint();
                    }

                    @Override
                    public void drop(java.awt.dnd.DropTargetDropEvent e) {
                        // lo maneja el TransferHandler
                    }
                });
            } catch (java.util.TooManyListenersException | RuntimeException e) {
                // sin esto solo se pierde el color al salir
            }
        }

        void setImagen(Image img) {
            imagen = img;
            repaint();
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(210, 196);
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, 196);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Tema.suavizar(g);
            int w = getWidth(), h = getHeight();
            g.setColor(encima ? Tema.AZUL_SUAVE : Tema.BLANCO);
            g.fillRoundRect(0, 0, w - 1, h - 1, Tema.ARCO, Tema.ARCO);
            g.setColor(encima ? Tema.AZUL : Tema.BORDE_CONTROL);
            g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{4f, 4f}, 0f));
            g.drawRoundRect(0, 0, w - 1, h - 1, Tema.ARCO, Tema.ARCO);
            // caja de abajo: "Arrastre aqui o haga clic para seleccionar"
            int cajaAlto = 46, cx = 12, cy = h - cajaAlto - 12, cw = w - 24;
            int arriba = 12, altoImagen = cy - arriba - 10;
            if (imagen != null && imagen.getWidth(null) > 0) {
                int iw = imagen.getWidth(null), ih = imagen.getHeight(null);
                double e = Math.min((w - 40.0) / iw, (double) altoImagen / ih);
                int dw = (int) (iw * e), dh = (int) (ih * e);
                g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                        java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.drawImage(imagen, (w - dw) / 2, arriba + (altoImagen - dh) / 2, dw, dh, null);
            } else {
                // caja del diseño (carpeta ICONO) cuando no hay foto
                int t = Math.min(96, altoImagen - 8);
                g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                        java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.drawImage(CAJA, (w - t) / 2, arriba + (altoImagen - t) / 2, t, t, null);
            }
            g.setStroke(new BasicStroke(1f));
            g.setColor(new Color(0xF5F8FC));
            g.fillRoundRect(cx, cy, cw, cajaAlto, Tema.ARCO, Tema.ARCO);
            g.setColor(Tema.BORDE);
            g.drawRoundRect(cx, cy, cw, cajaAlto, Tema.ARCO, Tema.ARCO);
            int r = 22, ox = cx + 14, oy = cy + (cajaAlto - r) / 2;
            g.setColor(Tema.AZUL);
            g.fillOval(ox, oy, r, r);
            g.setColor(Tema.BLANCO);
            g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine(ox + r / 2, oy + 6, ox + r / 2, oy + r - 6);
            g.drawLine(ox + 6, oy + r / 2, ox + r - 6, oy + r / 2);
            g.setFont(Tema.textoFuerte(11.5f));
            g.setColor(Tema.AZUL_PROFUNDO);
            java.awt.FontMetrics fm = g.getFontMetrics();
            String a = "Arrastre aquí o haga clic", b = "para seleccionar";
            int tx = ox + r + 8, ancho = cx + cw - tx - 8;
            g.drawString(a, tx + (ancho - fm.stringWidth(a)) / 2, cy + cajaAlto / 2 - 3);
            g.drawString(b, tx + (ancho - fm.stringWidth(b)) / 2, cy + cajaAlto / 2 + fm.getAscent() - 1);
            g.dispose();
        }
    }
}
