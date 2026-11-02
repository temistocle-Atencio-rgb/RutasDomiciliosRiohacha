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
import java.awt.Image;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.event.HierarchyEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultCellEditor;
import javax.swing.Icon;
import javax.swing.JComboBox;
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
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableColumnModel;
import rutas.logica.BaseTienda;
import rutas.logica.Carrito;
import rutas.logica.Catalogo;
import rutas.logica.Compra;
import rutas.logica.ImportadorProductos;
import rutas.logica.Tarifa;
import rutas.logica.Ubicaciones;
import rutas.modelo.Bodega;
import rutas.modelo.Producto;
import rutas.modelo.Unidad;

/**
 * Compras › Nueva compra. A la izquierda se buscan los productos y con «+» se agregan; a la derecha
 * va la compra: cantidad, costo sin y con impuesto, valor de venta y descuento de cada producto,
 * las observaciones y los totales. Al comprar sube el stock y queda el costo nuevo.
 *
 * La misma pantalla sirve para la orden de compra (modoOrden): arriba el proveedor, la bodega, la
 * fecha, la fecha estimada y la nota; la tabla con lo recibido; las retenciones; y los botones
 * Guardar borrador, Procesar orden, Ver PDF, Enviar correo, WhatsApp y Cancelar. La orden no
 * mueve el inventario: eso pasa al recibirla (se vuelve una compra).
 */
public class PanelCompras extends JPanel {

    private static final int MAX_FILAS = 120; // para que no se ponga lenta con miles de productos

    private final Aplicacion app;
    // crear productos y cambiar el precio de venta es de «Editar productos», no solo de «Registrar compras»
    private boolean editaProductos() {
        return app.getSesion() == null || app.getSesion().puede(rutas.logica.Permiso.EDITAR_PRODUCTOS);
    }
    private final BaseTienda bd;
    private final Catalogo cat;

    private final boolean modoOrden;                        // pantalla de orden de compra
    private Compra compra;
    private Long idEspera;                                  // la compra vino de "en espera"
    private rutas.logica.OrdenCompra recibiendo;            // la compra es la llegada de esta orden
    private boolean edicionMala;                            // lo ultimo escrito en la tabla no se pudo leer
    private final Map<Long, Long> impuestos = new HashMap<>(); // impuesto de la ficha de cada producto

    // ---- lado de productos ----
    private final JPanel ladoProductos = new JPanel(new BorderLayout());
    private final JTextField buscador = Tema.campo("Buscar por nombre o código");
    private final JPanel lista = new ListaFilas();
    private final JLabel lblResultados = new JLabel(" ");

    // ---- lado de la compra ----
    private final JLabel lblNumero = new JLabel();
    private final Tema.Boton btnEspera = Tema.botonSecundario("Espera");
    private final Tema.Boton btnComprar = Tema.botonAccion("Comprar");
    private final Tema.Boton btnCancelar = Tema.botonSecundario("Cancelar");
    private final JPanel botones = new JPanel(new java.awt.GridLayout(1, 3, 8, 0));
    private final BotonBarra btnListaEspera = new BotonBarra("En espera");
    private final BotonBarra btnPlegar = new BotonBarra("‹");
    private final Controles.AreaTexto areaObs = new Controles.AreaTexto("Escriba aquí las observaciones de la compra", 4);
    private final JLabel lblBruto = valor(false);
    private final JLabel lblDescuento = valor(false);
    private final JLabel lblImpuestos = valor(false);
    private final JLabel lblTotal = valor(true);
    private final JLabel lblRetencion = valor(false);
    private final javax.swing.JCheckBox interruptorRetencion = new Interruptor();
    private final JLabel lblEstadoOrden = new JLabel(" ");
    // ---- cabecera de la orden ----
    private final JComboBox<rutas.modelo.Proveedor> comboProveedor = new JComboBox<>();
    private final JComboBox<Bodega> comboBodegaOrden = new JComboBox<>();
    private final Controles.CampoFecha fechaOrden = new Controles.CampoFecha("Fecha");
    private final Controles.CampoFecha fechaEstimada = new Controles.CampoFecha("Fecha estimada");
    private final Controles.AreaTexto areaNota = new Controles.AreaTexto("Escribe una nota general de la orden", 2);
    private boolean llenandoCabecera;
    private Runnable alTerminarOrden = () -> { };
    private final ModeloCompra modelo = new ModeloCompra();
    private final JTable tabla = new JTable(modelo) {
        private final Icon vacio = Iconos.de("carrito", 56, new Color(0xC3CBD5));

        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            if (getRowCount() > 0) {
                return;
            }
            Graphics2D g = (Graphics2D) g0.create();
            Tema.suavizar(g);
            int y = Math.max(20, getParent().getHeight() / 2 - 60);
            vacio.paintIcon(this, g, (getWidth() - 56) / 2, y);
            g.setFont(Tema.subtitulo(14));
            g.setColor(Tema.GRIS_OSCURO);
            String t1 = "Sin registros de productos agregados";
            g.drawString(t1, (getWidth() - g.getFontMetrics().stringWidth(t1)) / 2, y + 82);
            g.setFont(Tema.texto(12.5f));
            g.setColor(Tema.GRIS_TEXTO);
            String t2 = "Busque un producto a la izquierda y toque «+» para agregarlo";
            g.drawString(t2, (getWidth() - g.getFontMetrics().stringWidth(t2)) / 2, y + 102);
            g.dispose();
        }
    };

    public PanelCompras(Aplicacion app) {
        this(app, false);
    }

    /** orden = true: la pantalla de «Nueva orden de compra». */
    PanelCompras(Aplicacion app, boolean orden) {
        super(new BorderLayout());
        this.app = app;
        this.modoOrden = orden;
        this.compra = orden ? new rutas.logica.OrdenCompra() : new Compra();
        this.bd = app.getTienda();
        this.cat = app.getCatalogo();
        setBackground(Tema.GRIS_CLARO);
        putClientProperty(Capa.FOCO_AL_CERRAR, buscador);
        add(armarProductos(), BorderLayout.WEST);
        add(armarCompra(), BorderLayout.CENTER);
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) {
                impuestos.clear(); // el impuesto de la ficha pudo cambiar en Productos
                refrescarProductos(); // por si cambiaron productos en otra pantalla
                refrescarCompra();
                SwingUtilities.invokeLater(() -> {
                    if (!Capa.hayAbierta(this)) {
                        buscador.requestFocusInWindow();
                    }
                });
            }
        });
        refrescarProductos();
        refrescarCompra();
    }

    // ------------------------------------------------------------------
    //  Izquierda: productos
    // ------------------------------------------------------------------

    private JComponent armarProductos() {
        ladoProductos.setBackground(Tema.BLANCO);
        ladoProductos.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Tema.BORDE));
        ladoProductos.setPreferredSize(new Dimension(320, 100));

        JPanel arriba = new JPanel();
        arriba.setOpaque(false);
        arriba.setLayout(new BoxLayout(arriba, BoxLayout.Y_AXIS));
        arriba.setBorder(BorderFactory.createEmptyBorder(18, 18, 8, 18));

        JPanel cab = new JPanel(new BorderLayout(10, 0));
        cab.setOpaque(false);
        cab.setAlignmentX(LEFT_ALIGNMENT);
        JLabel ico = new JLabel(Iconos.de("carrito", 26, Tema.AZUL));
        cab.add(ico, BorderLayout.WEST);
        JPanel textos = new JPanel();
        textos.setOpaque(false);
        textos.setLayout(new BoxLayout(textos, BoxLayout.Y_AXIS));
        JLabel t = new JLabel("Productos");
        t.setFont(Tema.subtitulo(18));
        t.setForeground(Tema.GRIS_OSCURO);
        textos.add(t);
        JLabel st = new JLabel(modoOrden ? "Busca y agrega productos a tu orden" : "Busca y agrega productos a tu compra");
        st.setFont(Tema.texto(12.5f));
        st.setForeground(Tema.GRIS_TEXTO);
        textos.add(st);
        cab.add(textos, BorderLayout.CENTER);
        cab.setMaximumSize(new Dimension(Integer.MAX_VALUE, cab.getPreferredSize().height));
        arriba.add(cab);
        arriba.add(Tema.espacio(12));

        Tema.Boton nuevo = Tema.botonPrimario("+  Nuevo producto");
        nuevo.setFont(Tema.textoFuerte(13));
        nuevo.setAlignmentX(LEFT_ALIGNMENT);
        nuevo.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        nuevo.setToolTipText("Crear un producto que todavía no existe y agregarlo a la compra");
        nuevo.addActionListener(e -> nuevoProducto(null));
        nuevo.setVisible(editaProductos());
        arriba.add(nuevo);
        arriba.add(Tema.espacio(10));
        if (modoOrden) { // lo que esta en su minimo o por debajo, con la cantidad que falta
            Tema.Boton acabando = Tema.botonSecundario("Agregar lo que se está acabando");
            acabando.setFont(Tema.textoFuerte(13));
            acabando.setAlignmentX(LEFT_ALIGNMENT);
            acabando.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
            acabando.setToolTipText("Agrega los productos que están en su stock mínimo o por debajo, con lo que falta");
            acabando.addActionListener(e -> agregarLoQueSeAcaba());
            arriba.add(acabando);
            arriba.add(Tema.espacio(10));
        }

        buscador.setFont(Tema.texto(14));
        buscador.setBorder(BorderFactory.createCompoundBorder(new Tema.BordeRedondo(Tema.BORDE_CONTROL, Tema.RADIO),
                new PanelVender.BordeIconos(Iconos.de("lupa", 18, Tema.GRIS_TEXTO), Iconos.de("codigo", 20, Tema.GRIS_TEXTO))));
        buscador.getAccessibleContext().setAccessibleName("Buscar producto por nombre o código");
        Tema.anchoCompleto(buscador);
        buscador.getDocument().addDocumentListener(PanelProductos.alCambiar(this::refrescarProductos));
        buscador.addActionListener(e -> enterEnBuscador());
        buscador.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusGained(java.awt.event.FocusEvent e) {
                SwingUtilities.invokeLater(buscador::selectAll);
            }
        });
        arriba.add(buscador);
        arriba.add(Tema.espacio(8));
        lblResultados.setFont(Tema.texto(12));
        lblResultados.setForeground(Tema.GRIS_TEXTO);
        lblResultados.setAlignmentX(LEFT_ALIGNMENT);
        arriba.add(lblResultados);
        ladoProductos.add(arriba, BorderLayout.NORTH);

        lista.setBackground(Tema.BLANCO);
        lista.setLayout(new BoxLayout(lista, BoxLayout.Y_AXIS));
        JScrollPane sc = Tema.scrollSinBorde(lista);
        sc.getViewport().setBackground(Tema.BLANCO);
        sc.getVerticalScrollBar().setUnitIncrement(20);
        ladoProductos.add(sc, BorderLayout.CENTER);
        return ladoProductos;
    }

    private void refrescarProductos() {
        String t = buscador.getText().trim();
        List<Producto> encontrados = t.isEmpty() ? cat.porCategoria(null) : cat.buscar(t);
        lista.removeAll();
        int n = 0;
        for (Producto p : encontrados) {
            if (n++ >= MAX_FILAS) {
                break;
            }
            lista.add(new FilaProducto(p));
        }
        if (encontrados.isEmpty()) {
            lblResultados.setText(t.isEmpty() ? "Aún no hay productos. Créelos con «Nuevo producto»."
                    : "No hay productos con «" + t + "»");
        } else if (encontrados.size() > MAX_FILAS) {
            lblResultados.setText("Se muestran " + MAX_FILAS + " de " + encontrados.size() + ". Escriba para encontrar el suyo.");
        } else {
            lblResultados.setText(encontrados.size() + (encontrados.size() == 1 ? " producto" : " productos"));
        }
        lista.revalidate();
        lista.repaint();
    }

    /** Enter: el escaner (codigo exacto) o un solo resultado. Un codigo que no existe ofrece crearlo. */
    private void enterEnBuscador() {
        String t = buscador.getText().trim();
        if (t.isEmpty()) {
            return;
        }
        Producto p = cat.buscarCodigo(t);
        if (p == null) {
            List<Producto> l = cat.buscar(t);
            if (l.size() == 1) {
                p = l.get(0);
            }
        }
        if (p != null) {
            agregar(p);
            buscador.setText("");
            return;
        }
        if (t.replaceAll("\\s", "").matches("\\d{6,}")) {
            Toolkit.getDefaultToolkit().beep();
            String codigo = t.replaceAll("\\s", "");
            if (editaProductos()) {
                Alerta.confirmar(this, "Código desconocido", "No hay ningún producto con el código " + codigo
                        + ". ¿Quiere crearlo y agregarlo a la compra?", "Crear producto", "No", () -> nuevoProducto(codigo));
            } else {
                Alerta.mostrar(this, Alerta.Tipo.AVISO, "Código desconocido", "No hay ningún producto con el código "
                        + codigo + ". Pídale al administrador que lo cree.", null);
            }
        }
    }

    private void nuevoProducto(String codigo) {
        FormularioProducto.abrirParaCompra(this, app, codigo, p -> {
            refrescarProductos();
            agregar(p);
        });
    }

    /** Agrega el producto (o le suma una unidad si ya estaba). */
    private void agregar(Producto p) {
        if (Capa.hayAbierta(this)) {
            return;
        }
        detenerEdicion();
        Producto actual = cat.buscarPorId(p.getId());
        Producto pr = actual == null ? p : actual;
        Compra.Linea l = compra.agregar(pr, impuestoDe(pr));
        refrescarCompra();
        int fila = compra.getLineas().indexOf(l);
        if (fila >= 0) {
            tabla.setRowSelectionInterval(fila, fila);
            tabla.scrollRectToVisible(tabla.getCellRect(fila, 0, true));
        }
        Aviso.mostrar(this, Aviso.Tipo.BIEN, "Producto agregado", pr.getNombre());
    }

    /** El impuesto que dice la ficha del producto (IVA 19 % = 1900). */
    private long impuestoDe(Producto p) {
        return impuestos.computeIfAbsent(p.getId(), id -> {
            try {
                return Compra.impuestoDe(bd.leerFicha(id).impuestos);
            } catch (SQLException e) {
                return 0L;
            }
        });
    }

    /** Lista de filas que toma el ancho del scroll. */
    private static class ListaFilas extends JPanel implements Scrollable {
        public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        public int getScrollableUnitIncrement(java.awt.Rectangle r, int o, int d) { return 20; }
        public int getScrollableBlockIncrement(java.awt.Rectangle r, int o, int d) { return r.height - 40; }
        public boolean getScrollableTracksViewportWidth() { return true; }
        public boolean getScrollableTracksViewportHeight() { return false; }
    }

    /** Un producto de la lista: foto, nombre, codigo, stock, costo y el boton «+». */
    private class FilaProducto extends JComponent {
        private static final int ALTO = 66;
        private final Producto p;
        private boolean encima;

        FilaProducto(Producto p) {
            this.p = p;
            setAlignmentX(LEFT_ALIGNMENT);
            setFocusable(true);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText("Agregar «" + p.getNombre() + "» a la compra");
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    agregar(p);
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
            getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "agregar");
            getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "agregar");
            getActionMap().put("agregar", new AbstractAction() {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent e) {
                    agregar(p);
                }
            });
            addFocusListener(new java.awt.event.FocusAdapter() {
                @Override
                public void focusGained(java.awt.event.FocusEvent e) {
                    scrollRectToVisible(new java.awt.Rectangle(0, 0, getWidth(), getHeight()));
                    repaint();
                }

                @Override
                public void focusLost(java.awt.event.FocusEvent e) {
                    repaint();
                }
            });
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(300, ALTO);
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, ALTO);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Tema.suavizar(g);
            int w = getWidth();
            if (encima || isFocusOwner()) {
                g.setColor(Tema.AZUL_SUAVE);
                g.fillRect(0, 0, w, ALTO);
            }
            g.setColor(Tema.BORDE);
            g.drawLine(18, ALTO - 1, w - 18, ALTO - 1);
            // foto
            int f = 44, fx = 18, fy = (ALTO - f) / 2;
            g.setColor(Tema.BLANCO);
            g.fillRoundRect(fx, fy, f, f, Tema.ARCO, Tema.ARCO);
            g.setColor(Tema.BORDE);
            g.drawRoundRect(fx, fy, f, f, Tema.ARCO, Tema.ARCO);
            Image img = Fotos.cargar(p.getFoto(), f - 4, f - 4);
            if (img != null) {
                Fotos.dibujar(g, img, fx + 2, fy + 2, f - 4, f - 4);
            } else {
                Fotos.paquete(g, fx + f / 2, fy + f / 2, 28);
            }
            // boton +
            int b = 30, bx = w - 18 - b, by = (ALTO - b) / 2;
            g.setColor(encima || isFocusOwner() ? Tema.AZUL : Tema.BLANCO);
            g.fillRoundRect(bx, by, b, b, Tema.ARCO, Tema.ARCO);
            g.setColor(Tema.AZUL);
            g.drawRoundRect(bx, by, b, b, Tema.ARCO, Tema.ARCO);
            g.setColor(encima || isFocusOwner() ? Tema.BLANCO : Tema.AZUL);
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine(bx + b / 2, by + 9, bx + b / 2, by + b - 9);
            g.drawLine(bx + 9, by + b / 2, bx + b - 9, by + b / 2);
            // costo a la derecha
            g.setFont(Tema.textoFuerte(13));
            String costo = Tarifa.pesos(p.getPrecioCompra());
            int cw = g.getFontMetrics().stringWidth(costo);
            int cx = bx - 12 - cw;
            g.setColor(Tema.GRIS_OSCURO);
            g.drawString(costo, cx, ALTO / 2 + 5);
            // textos
            int tx = fx + f + 12, tw = cx - 10 - tx;
            g.setFont(Tema.textoFuerte(13));
            g.setColor(Tema.GRIS_OSCURO);
            g.drawString(recortar(g, p.getNombre(), tw), tx, 22);
            g.setFont(Tema.texto(12));
            g.setColor(Tema.GRIS_TEXTO);
            String cod = p.getCodigo() == null ? "Sin código" : "Código: " + p.getCodigo();
            g.drawString(recortar(g, cod, tw), tx, 39);
            String sTxt = "Stock: " + textoStock(p);
            g.setColor(!p.isManejaInventario() ? Tema.GRIS_TEXTO
                    : p.getStock() <= 0 ? Tema.ERROR_TEXTO : Tema.EXITO_TEXTO);
            g.drawString(recortar(g, sTxt, tw), tx, 55);
            g.dispose();
        }
    }

    private static String recortar(Graphics2D g, String s, int ancho) {
        java.awt.FontMetrics fm = g.getFontMetrics();
        if (fm.stringWidth(s) <= ancho) {
            return s;
        }
        String t = s;
        while (t.length() > 1 && fm.stringWidth(t + "…") > ancho) {
            t = t.substring(0, t.length() - 1);
        }
        return t + "…";
    }

    /** "12", "-3" o "4,5 kg" (lo que hay en la tienda); "—" si no maneja inventario. */
    private static String textoStock(Producto p) {
        if (!p.isManejaInventario()) {
            return "—";
        }
        return p.getUnidad() == Unidad.UNIDAD ? PanelProductos.cantidad(p.getStock()) : CuadroGranel.peso(p.getStock(), p);
    }

    // ------------------------------------------------------------------
    //  Derecha: la compra
    // ------------------------------------------------------------------

    private JComponent armarCompra() {
        JPanel der = new JPanel(new BorderLayout());
        der.setBackground(Tema.BLANCO);

        // franja azul: plegar productos, titulo, numero y compras en espera
        JPanel barra = new JPanel(new BorderLayout(10, 0));
        barra.setBackground(Tema.AZUL);
        barra.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 16));
        btnPlegar.setToolTipText("Ocultar los productos para ver más grande la compra");
        btnPlegar.setFont(Tema.titulo(20));
        btnPlegar.addActionListener(e -> plegar());
        JLabel tit = new JLabel(modoOrden ? "INFORMACIÓN DE LA ORDEN DE COMPRA" : "INFORMACIÓN DE LA COMPRA");
        tit.setFont(Tema.subtitulo(14.5f));
        tit.setForeground(Tema.BLANCO);
        JPanel izq = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        izq.setOpaque(false);
        izq.add(btnPlegar);
        izq.add(tit);
        barra.add(izq, BorderLayout.WEST);
        lblNumero.setFont(Tema.texto(13));
        lblNumero.setVerticalAlignment(SwingConstants.CENTER);
        lblNumero.setForeground(new Color(255, 255, 255, 225));
        btnListaEspera.setFont(Tema.textoFuerte(13));
        btnListaEspera.setToolTipText("Ver las compras que dejó en espera");
        btnListaEspera.addActionListener(e -> verEspera());
        JPanel derBarra = new JPanel(new GridBagLayout()); // centrado de arriba a abajo
        derBarra.setOpaque(false);
        GridBagConstraints gb = new GridBagConstraints();
        gb.insets = new Insets(0, 0, 0, 12);
        derBarra.add(lblNumero, gb);
        gb.insets = new Insets(0, 0, 0, 0);
        derBarra.add(btnListaEspera, gb);
        barra.add(derBarra, BorderLayout.EAST);
        configurarTabla();
        JScrollPane sc = new JScrollPane(tabla);
        sc.setBorder(BorderFactory.createEmptyBorder(10, 12, 0, 12));
        sc.getViewport().setBackground(Tema.BLANCO);
        sc.setBackground(Tema.BLANCO);
        if (modoOrden) { // arriba de la tabla: proveedor, bodega, fechas y nota (como la referencia)
            btnListaEspera.setVisible(false);
            JPanel arriba = new JPanel(new BorderLayout());
            arriba.setOpaque(false);
            arriba.add(barra, BorderLayout.NORTH);
            arriba.add(cabeceraOrden(), BorderLayout.CENTER);
            der.add(arriba, BorderLayout.NORTH);
        } else {
            der.add(barra, BorderLayout.NORTH);
        }
        der.add(sc, BorderLayout.CENTER);
        der.add(armarPie(), BorderLayout.SOUTH);
        return der;
    }

    private JComponent armarPie() {
        JPanel pie = new JPanel(new GridBagLayout());
        pie.setOpaque(false);
        pie.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Tema.BORDE),
                BorderFactory.createEmptyBorder(10, 24, 12, 24)));
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0;
        g.gridy = 0;
        g.weightx = 1;
        g.fill = GridBagConstraints.BOTH;
        g.anchor = GridBagConstraints.NORTHWEST;
        g.insets = new Insets(0, 0, 0, 28);

        JPanel obs = new JPanel(new BorderLayout(0, 6));
        obs.setOpaque(false);
        JLabel lo = new JLabel("Observaciones");
        lo.setFont(Tema.textoFuerte(13));
        lo.setForeground(Tema.GRIS_OSCURO);
        obs.add(lo, BorderLayout.NORTH);
        areaObs.setPreferredSize(new Dimension(260, 74));
        areaObs.getArea().getDocument().addDocumentListener(PanelProductos.alCambiar(
                () -> compra.setObservaciones(areaObs.getArea().getText())));
        obs.add(areaObs, BorderLayout.CENTER);
        if (modoOrden) { // en la orden la nota va arriba; aqui queda lo recibido y el estado
            obs.removeAll();
            lblEstadoOrden.setFont(Tema.textoFuerte(13.5f));
            lblEstadoOrden.setVerticalAlignment(SwingConstants.TOP);
            obs.add(lblEstadoOrden, BorderLayout.NORTH);
            Tema.Parrafo ayudaOrden = Tema.parrafo("La orden no mueve el inventario. Cuando llegue la mercancía, "
                    + "use «Recibir» en Órdenes de compra: se vuelve una compra y ahí sí sube el stock.", 300);
            ayudaOrden.setFont(Tema.texto(12.5f));
            ayudaOrden.setForeground(Tema.GRIS_TEXTO);
            obs.add(ayudaOrden, BorderLayout.CENTER);
        }
        pie.add(obs, g);

        JPanel tot = new JPanel(new GridBagLayout());
        tot.setOpaque(false);
        GridBagConstraints t = new GridBagConstraints();
        t.gridy = 0;
        t.anchor = GridBagConstraints.EAST;
        t.insets = new Insets(2, 0, 2, 0);
        filaTotal(tot, t, "Total Bruto", lblBruto, null);
        Tema.Boton lapiz = Tema.botonPlano("");
        lapiz.setIcon(Iconos.de("editar", 16, Tema.AZUL));
        lapiz.setBorder(BorderFactory.createEmptyBorder(3, 5, 3, 5));
        lapiz.setToolTipText("Escribir un descuento para toda la compra");
        lapiz.getAccessibleContext().setAccessibleName("Cambiar el descuento de la compra");
        lapiz.addActionListener(e -> pedirDescuento());
        filaTotal(tot, t, "Descuento", lblDescuento, lapiz);
        filaTotal(tot, t, "Impuestos", lblImpuestos, null);
        if (modoOrden) { // retencion en la fuente: interruptor y el % (se cambia con el lapiz)
            interruptorRetencion.setOpaque(false);
            Tema.estiloToggle(interruptorRetencion);
            interruptorRetencion.setToolTipText("Descontar la retención en la fuente del total");
            interruptorRetencion.getAccessibleContext().setAccessibleName("Retenciones");
            interruptorRetencion.addActionListener(e -> {
                if (compra instanceof rutas.logica.OrdenCompra) {
                    ((rutas.logica.OrdenCompra) compra).setConRetencion(interruptorRetencion.isSelected());
                    refrescarCompra();
                }
            });
            Tema.Boton lapizRet = Tema.botonPlano("");
            lapizRet.setIcon(Iconos.de("editar", 16, Tema.AZUL));
            lapizRet.setBorder(BorderFactory.createEmptyBorder(3, 5, 3, 5));
            lapizRet.setToolTipText("Cambiar el porcentaje de retención");
            lapizRet.getAccessibleContext().setAccessibleName("Cambiar el porcentaje de retención");
            lapizRet.addActionListener(e -> pedirRetencion());
            JPanel ret = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
            ret.setOpaque(false);
            ret.add(interruptorRetencion);
            ret.add(lapizRet);
            filaTotal(tot, t, "Retenciones", lblRetencion, ret);
        }
        t.gridx = 0;
        t.gridwidth = 3;
        t.fill = GridBagConstraints.HORIZONTAL;
        t.insets = new Insets(6, 0, 6, 0);
        JComponent linea = new JPanel();
        linea.setBackground(Tema.BORDE);
        linea.setPreferredSize(new Dimension(10, 1));
        tot.add(linea, t);
        t.gridy++;
        t.gridwidth = 1;
        t.fill = GridBagConstraints.NONE;
        t.insets = new Insets(0, 0, 0, 0);
        filaTotal(tot, t, "Total Compra", lblTotal, null);

        JPanel derecha = new JPanel(new BorderLayout(0, 10));
        derecha.setOpaque(false);
        derecha.add(tot, BorderLayout.NORTH);
        if (!modoOrden) {
            derecha.add(botones, BorderLayout.SOUTH);
        }
        g.gridx = 1;
        g.weightx = 0;
        g.fill = GridBagConstraints.VERTICAL;
        g.insets = new Insets(0, 0, 0, 0);
        pie.add(derecha, g);
        botones.setOpaque(false);
        if (modoOrden) { // seis botones a todo el ancho, como en la referencia
            g.gridx = 0;
            g.gridy = 1;
            g.gridwidth = 2;
            g.weightx = 1;
            g.fill = GridBagConstraints.HORIZONTAL;
            g.insets = new Insets(12, 0, 0, 0);
            // el ancho lo da la pantalla (no los botones): asi el pie nunca se aplasta
            JPanel envBotones = new JPanel(new BorderLayout()) {
                @Override
                public Dimension getPreferredSize() {
                    return new Dimension(10, botones.getPreferredSize().height);
                }

                @Override
                public Dimension getMinimumSize() {
                    return getPreferredSize();
                }
            };
            envBotones.setOpaque(false);
            envBotones.add(botones, BorderLayout.CENTER);
            // en pantallas angostas los seis botones van en dos filas de tres
            envBotones.addComponentListener(new java.awt.event.ComponentAdapter() {
                @Override
                public void componentResized(java.awt.event.ComponentEvent e) {
                    int filas = envBotones.getWidth() < 940 ? 2 : 1;
                    if (((java.awt.GridLayout) botones.getLayout()).getRows() != filas) {
                        botones.setLayout(new java.awt.GridLayout(filas, 6 / filas, 8, 8));
                        pie.revalidate();
                    }
                }
            });
            pie.add(envBotones, g);
            armarBotonesOrden();
            return pie;
        }
        btnComprar.setIcon(Iconos.de("carrito", 18, Tema.GRIS_OSCURO));
        btnComprar.setToolTipText("Guardar la compra: sube el stock y queda el costo nuevo (F12)");
        btnEspera.setToolTipText("Dejar la compra guardada para terminarla después");
        btnCancelar.setToolTipText("Quitar todos los productos de la compra");
        botones.add(btnCancelar);
        botones.add(btnEspera);
        botones.add(btnComprar);

        btnComprar.addActionListener(e -> comprar());
        btnEspera.addActionListener(e -> ponerEnEspera());
        btnCancelar.addActionListener(e -> cancelar());
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_F12, 0), "comprar");
        getActionMap().put("comprar", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (isShowing()) {
                    comprar();
                }
            }
        });
        return pie;
    }

    private static void filaTotal(JPanel p, GridBagConstraints t, String nombre, JLabel valor, JComponent extra) {
        JLabel l = new JLabel(nombre);
        boolean total = valor.getFont().getSize2D() > 16;
        l.setFont(total ? Tema.subtitulo(15) : Tema.texto(13.5f));
        l.setForeground(total ? Tema.GRIS_OSCURO : Tema.GRIS_TEXTO);
        t.gridx = 0;
        t.anchor = GridBagConstraints.WEST;
        p.add(l, t);
        t.gridx = 1;
        t.anchor = GridBagConstraints.CENTER;
        p.add(extra == null ? Box.createHorizontalStrut(26) : extra, t);
        t.gridx = 2;
        t.anchor = GridBagConstraints.EAST;
        p.add(valor, t);
        t.gridy++;
    }

    private static JLabel valor(boolean total) {
        JLabel l = new JLabel("$0", SwingConstants.RIGHT);
        l.setFont(total ? Tema.titulo(24) : Tema.textoFuerte(14));
        l.setForeground(Tema.GRIS_OSCURO);
        l.setPreferredSize(new Dimension(total ? 170 : 130, total ? 34 : 22));
        return l;
    }

    private void plegar() {
        boolean ver = !ladoProductos.isVisible();
        ladoProductos.setVisible(ver);
        btnPlegar.setText(ver ? "‹" : "›");
        btnPlegar.setToolTipText(ver ? "Ocultar los productos para ver más grande la compra" : "Mostrar los productos");
        revalidate();
        repaint();
        if (ver) {
            buscador.requestFocusInWindow();
        }
    }

    // ---- tabla ----

    private static final int COL_STOCK = 0, COL_PRODUCTO = 1, COL_CANT = 2, COL_BASE = 3, COL_IMP = 4,
            COL_COMPRA = 5, COL_VENTA = 6, COL_DESC = 7, COL_SUBTOTAL = 8, COL_QUITAR = 9, COL_RECIBIDA = 10;
    // que columna (de las de arriba) va en cada posicion: la compra y la orden (como la referencia)
    private static final int[] VISTA_COMPRA = {COL_STOCK, COL_PRODUCTO, COL_CANT, COL_BASE, COL_IMP, COL_COMPRA,
        COL_VENTA, COL_DESC, COL_SUBTOTAL, COL_QUITAR};
    private static final int[] VISTA_ORDEN = {COL_PRODUCTO, COL_CANT, COL_BASE, COL_IMP, COL_RECIBIDA, COL_COMPRA,
        COL_DESC, COL_SUBTOTAL, COL_QUITAR};

    /** La columna de datos que va en la posicion v de la tabla. */
    private int col(int v) {
        int[] vista = modoOrden ? VISTA_ORDEN : VISTA_COMPRA;
        return v >= 0 && v < vista.length ? vista[v] : -1;
    }

    /** Lo que se escribe en la tabla (cajitas blancas). En la orden el impuesto y lo recibido van en gris. */
    private boolean esCajita(int c) {
        if (modoOrden) {
            return c == COL_CANT || c == COL_BASE || c == COL_COMPRA || c == COL_DESC;
        }
        return c >= COL_CANT && c <= COL_DESC;
    }

    /** Cajitas grises (se ven pero no se cambian): en la orden, el impuesto y lo recibido. */
    private boolean esGris(int c) {
        return modoOrden && (c == COL_IMP || c == COL_RECIBIDA);
    }

    private void configurarTabla() {
        modelo.fireTableStructureChanged(); // la tabla se creo antes de saber si es orden o compra
        tabla.setRowHeight(52);
        tabla.setFont(Tema.texto(13));
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
        tabla.getAccessibleContext().setAccessibleName("Productos de la compra");
        JTableHeader h = tabla.getTableHeader();
        if (modoOrden) {
            h.setPreferredSize(new Dimension(100, 50)); // titulos de dos renglones («Cant. recibida»)
        }
        h.setReorderingAllowed(false);
        h.setResizingAllowed(false);
        h.setDefaultRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object v, boolean s, boolean f, int r, int c) {
                JLabel l = (JLabel) super.getTableCellRendererComponent(t, v, false, false, r, c);
                l.setFont(Tema.textoFuerte(12));
                l.setForeground(Tema.GRIS_OSCURO);
                l.setBackground(Tema.GRIS_CLARO);
                int k = col(c);
                l.setHorizontalAlignment(k == COL_PRODUCTO ? LEFT : k == COL_SUBTOTAL ? RIGHT : CENTER);
                if (modoOrden && v != null && k != COL_PRODUCTO) { // en la orden: dos renglones si no cabe
                    l.setText("<html><div style='text-align:center'>" + v + "</div></html>");
                }
                l.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Tema.BORDE),
                        BorderFactory.createEmptyBorder(9, 6, 9, 6)));
                return l;
            }
        });
        tabla.setDefaultRenderer(Object.class, new Celda());
        TableColumnModel cm = tabla.getColumnModel();
        // los numeros no se encogen: si falta espacio el nombre baja a dos renglones
        // ancho de cada columna de datos (por su numero COL_...)
        int[] anchos = {48, 200, 60, 78, 54, 84, 82, 60, 86, 32, 78};
        if (modoOrden) {
            anchos[COL_PRODUCTO] = 220;
            anchos[COL_IMP] = 86;
            anchos[COL_COMPRA] = 96;
        }
        DefaultCellEditor editor = new DefaultCellEditor(new CampoCelda());
        editor.setClickCountToStart(1);
        PanelVender.Quitar quitar = new PanelVender.Quitar();
        quitar.setToolTipText(modoOrden ? "Quitar de la orden (Supr)" : "Quitar de la compra (Supr)");
        for (int v = 0; v < cm.getColumnCount(); v++) {
            int k = col(v);
            cm.getColumn(v).setPreferredWidth(anchos[k]);
            if (k != COL_PRODUCTO) {
                cm.getColumn(v).setMinWidth(anchos[k]);
            } else {
                cm.getColumn(v).setMinWidth(110);
            }
            if (k == COL_QUITAR) {
                cm.getColumn(v).setMaxWidth(32);
                cm.getColumn(v).setCellRenderer(quitar);
            }
            if (esCajita(k) || (!modoOrden && k == COL_VENTA)) {
                cm.getColumn(v).setCellEditor(editor);
            }
        }
        tabla.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int fila = tabla.rowAtPoint(e.getPoint());
                if (fila >= 0 && col(tabla.columnAtPoint(e.getPoint())) == COL_QUITAR && sePuedeCambiar()) {
                    quitarFila(fila);
                }
            }
        });
        tabla.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0),
                "quitar");
        tabla.getActionMap().put("quitar", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (tabla.getSelectedRow() >= 0 && !tabla.isEditing() && sePuedeCambiar()) {
                    quitarFila(tabla.getSelectedRow());
                }
            }
        });
    }

    /** Termina lo que se este escribiendo en la tabla. False si no se pudo leer (ya salio el aviso). */
    private boolean detenerEdicion() {
        edicionMala = false;
        if (tabla.isEditing()) {
            tabla.getCellEditor().stopCellEditing();
        }
        boolean bien = !edicionMala;
        edicionMala = false;
        return bien;
    }

    private void quitarFila(int fila) {
        if (tabla.isEditing()) {
            tabla.getCellEditor().cancelCellEditing();
        }
        if (fila < compra.getLineas().size()) {
            compra.quitar(compra.getLineas().get(fila));
        }
        refrescarCompra();
    }

    private void refrescarCompra() {
        modelo.fireTableDataChanged();
        long numero;
        try {
            numero = bd.siguienteNumeroCompra();
        } catch (SQLException e) {
            numero = 0;
        }
        lblNumero.setText(idEspera != null ? "Compra en espera" : "Compra No. " + Comprobante.numeroTexto(numero));
        if (recibiendo != null) {
            lblNumero.setText("Recibiendo la orden No. " + Comprobante.numeroTexto(recibiendo.getNumero()));
        }
        if (modoOrden) {
            refrescarOrden();
            return;
        }
        lblBruto.setText(Tarifa.pesos(compra.getBruto()));
        lblDescuento.setText(compra.getDescuentos() == 0 ? "$0" : "-" + Tarifa.pesos(compra.getDescuentos()));
        lblImpuestos.setText(Tarifa.pesos(compra.getImpuestos()));
        lblTotal.setText(Tarifa.pesos(compra.getTotal()));
        boolean hay = !compra.getLineas().isEmpty();
        botones.setVisible(hay || idEspera != null); // como la referencia: los botones salen con productos
        btnComprar.setEnabled(hay);
        btnEspera.setEnabled(hay);
        int enEspera;
        try {
            enEspera = bd.listarComprasEspera().size();
        } catch (SQLException e) {
            enEspera = 0;
        }
        btnListaEspera.setText("En espera (" + enEspera + ")");
        btnListaEspera.setVisible(enEspera > 0);
        tabla.repaint();
        revalidate();
    }

    private Producto actual(Compra.Linea l) {
        Producto p = cat.buscarPorId(l.getProducto().getId());
        return p == null ? l.getProducto() : p;
    }

    private static String textoCantidad(Compra.Linea l) {
        Producto p = l.getProducto();
        return p.getUnidad() == Unidad.UNIDAD ? PanelProductos.cantidad(l.getCantidad()) : CuadroGranel.peso(l.getCantidad(), p);
    }

    /** Las filas salen de la compra. */
    private class ModeloCompra extends AbstractTableModel {
        private String[] columnas() {
            return modoOrden
                ? new String[]{"Producto", "Cant.", "Vr Base", "% Impuestos", "Cant. recibida", "Costo unitario",
                    "% Desc", "Subtotal", ""}
                : new String[]{"Stock", "Producto", "Cant", "Vr Base", "% Imp", "Vr Compra", "Vr Venta", "% Desc",
                    "SubTotal", ""};
        }

        @Override
        public int getRowCount() {
            return compra.getLineas().size();
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
        public boolean isCellEditable(int r, int v) {
            int c = col(v);
            if (!sePuedeCambiar()) {
                return false;
            }
            return esCajita(c) && (c != COL_VENTA || editaProductos()); // el precio de venta: solo con permiso
        }

        @Override
        public Object getValueAt(int r, int v) {
            Compra.Linea l = compra.getLineas().get(r);
            int c = col(v);
            switch (c) {
                case COL_RECIBIDA:
                    return l.getProducto().getUnidad() == Unidad.UNIDAD ? PanelProductos.cantidad(l.getRecibida())
                            : CuadroGranel.peso(l.getRecibida(), l.getProducto());
                case COL_STOCK:
                    return textoStock(actual(l));
                case COL_PRODUCTO:
                    return l.getProducto().getNombre();
                case COL_CANT:
                    return textoCantidad(l);
                case COL_BASE:
                    return Tarifa.pesos(l.getBase());
                case COL_IMP:
                    return Carrito.textoPorcentaje(l.getImpuesto());
                case COL_COMPRA:
                    return Tarifa.pesos(l.getCosto());
                case COL_VENTA:
                    return Tarifa.pesos(l.getPrecioVentaVisto(actual(l)));
                case COL_DESC:
                    return Carrito.textoPorcentaje(l.getDescuento());
                case COL_SUBTOTAL:
                    return Tarifa.pesos(l.getSubtotal());
                default:
                    return "";
            }
        }

        @Override
        public void setValueAt(Object v, int r, int vc) {
            String t = v == null ? "" : v.toString().trim();
            if (r >= compra.getLineas().size() || t.equals(String.valueOf(getValueAt(r, vc)).trim())) {
                return;
            }
            int c = col(vc);
            Compra.Linea l = compra.getLineas().get(r);
            edicionMala = false;
            try {
                switch (c) {
                    case COL_CANT: {
                        String bajo = t.toLowerCase();
                        Unidad u = l.getProducto().getUnidad();
                        String limpio = t.replaceAll("(?i)\\s*(kg|lb|g|und)$", "");
                        long cant = ImportadorProductos.leerCantidad(limpio);
                        boolean gramos = bajo.endsWith("g") && !bajo.endsWith("kg");
                        if (u == Unidad.LIBRA && (gramos || bajo.endsWith("kg"))) {
                            throw new IllegalArgumentException("Este producto va por libras: escriba la cantidad en lb");
                        }
                        if (u == Unidad.KILO && bajo.endsWith("lb")) {
                            throw new IllegalArgumentException("Este producto va por kilos: escriba la cantidad en kg o g");
                        }
                        if (gramos) {
                            cant = cant / 1000; // "455 g" -> 455 milesimas de kilo
                        }
                        if (cant <= 0) {
                            throw new IllegalArgumentException("La cantidad debe ser mayor que 0");
                        }
                        if (l.getProducto().getUnidad() == Unidad.UNIDAD && cant % 1000 != 0) {
                            throw new IllegalArgumentException("Este producto va por unidades enteras");
                        }
                        l.setCantidad(cant);
                        break;
                    }
                    case COL_BASE:
                        l.setBase(ImportadorProductos.leerPesos(t));
                        break;
                    case COL_IMP: // cambiar el impuesto deja la base y recalcula el valor compra
                        try {
                            l.setImpuesto(Carrito.leerPorcentaje(t));
                        } catch (IllegalArgumentException e) {
                            throw new IllegalArgumentException("El impuesto va de 0 % a 100 %");
                        }
                        break;
                    case COL_COMPRA:
                        l.setCosto(ImportadorProductos.leerPesos(t));
                        break;
                    case COL_VENTA: {
                        if (t.isEmpty()) {
                            l.setPrecioVenta(null); // vacio = se deja el precio que tiene
                            break;
                        }
                        long venta = ImportadorProductos.leerPesos(t);
                        if (venta <= 0) {
                            throw new IllegalArgumentException("El valor de venta debe ser mayor que 0");
                        }
                        // igual al actual = no cambia (asi un precio cambiado en Productos no se pisa)
                        l.setPrecioVenta(venta == actual(l).getPrecioVenta() ? null : venta);
                        break;
                    }
                    case COL_DESC:
                        l.setDescuento(Carrito.leerPorcentaje(t));
                        break;
                    default:
                        break;
                }
            } catch (IllegalArgumentException e) {
                edicionMala = true; // Comprar y Espera no siguen hasta que se corrija
                Aviso.mostrar(PanelCompras.this, Aviso.Tipo.ERROR, "No se cambió", e.getMessage());
            }
            refrescarCompra();
        }
    }

    /**
     * Celdas de la compra. Lo que se puede cambiar se ve como cajita. El producto lleva su foto y su codigo.
     * El stock sale en verde si hay y en rojo si esta en cero o negativo.
     */
    private class Celda extends DefaultTableCellRenderer {
        private boolean cajita;
        private boolean gris; // cajita que no se cambia (fondo gris, como en la referencia)
        private Image foto;
        private boolean conFoto;
        private String nombre = "";
        private String codigo = "";

        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean s, boolean f, int r, int vc) {
            super.getTableCellRendererComponent(t, v, s, false, r, vc);
            int c = col(vc);
            cajita = (esCajita(c) && sePuedeCambiar()) || esGris(c);
            gris = esGris(c) || (esCajita(c) && !sePuedeCambiar());
            conFoto = false;
            setOpaque(true);
            setIcon(null);
            setToolTipText(cajita ? "Clic para cambiar" : null);
            setForeground(Tema.GRIS_OSCURO);
            setFont(Tema.texto(13));
            setBorder(BorderFactory.createEmptyBorder(0, cajita ? 9 : 6, 0, cajita ? 9 : 6));
            Compra.Linea l = r < compra.getLineas().size() ? compra.getLineas().get(r) : null;
            if (c == COL_STOCK) {
                setHorizontalAlignment(CENTER);
                Producto p = l == null ? null : actual(l);
                setForeground(p == null || !p.isManejaInventario() ? Tema.GRIS_TEXTO
                        : p.getStock() <= 0 ? Tema.ERROR_TEXTO : Tema.EXITO_TEXTO);
                setFont(Tema.textoFuerte(13));
            } else if (c == COL_PRODUCTO) {
                setHorizontalAlignment(LEFT);
                conFoto = true;
                foto = l == null ? null : Fotos.cargar(l.getProducto().getFoto(), 30, 30);
                setBorder(BorderFactory.createEmptyBorder(0, 44, 0, 6));
                codigo = l == null || l.getProducto().getCodigo() == null ? "Sin código"
                        : "Cod: " + l.getProducto().getCodigo();
                if (modoOrden && l != null && actual(l).isManejaInventario()) { // para decidir cuanto pedir
                    Producto pa = actual(l);
                    codigo = "Quedan " + textoStock(pa) + (pa.getMinimo() > 0 ? " · mínimo "
                            + (pa.getUnidad() == Unidad.UNIDAD ? PanelProductos.cantidad(pa.getMinimo())
                            : CuadroGranel.peso(pa.getMinimo(), pa)) : "");
                }
                nombre = String.valueOf(v);
                setText(""); // el nombre y el codigo se dibujan abajo, recortados a un renglon
                setToolTipText(nombre);
            } else if (c == COL_SUBTOTAL) {
                setHorizontalAlignment(RIGHT);
                setFont(Tema.textoFuerte(13.5f));
            } else if (c == COL_VENTA && l != null && l.getPrecioVenta() != null
                    && !l.getPrecioVenta().equals(actual(l).getPrecioVenta())) {
                setHorizontalAlignment(RIGHT);
                setForeground(Tema.AZUL); // precio de venta nuevo: se nota que va a cambiar
                setFont(Tema.textoFuerte(13));
                setToolTipText("Antes " + Tarifa.pesos(actual(l).getPrecioVenta()) + ". Clic para cambiar");
            } else {
                setHorizontalAlignment(c == COL_DESC && !modoOrden || c == COL_IMP && !modoOrden ? CENTER : RIGHT);
                if (gris) {
                    setToolTipText(c == COL_RECIBIDA ? "Se llena al recibir la mercancía" : "Viene de la ficha del producto");
                }
            }
            return this;
        }

        @Override
        protected void paintComponent(Graphics g0) {
            if (conFoto) {
                super.paintComponent(g0);
                Graphics2D g = (Graphics2D) g0.create();
                Tema.suavizar(g);
                int t = 32, x = 4, y = (getHeight() - t) / 2;
                g.setColor(Tema.BLANCO);
                g.fillRoundRect(x, y, t, t, Tema.ARCO, Tema.ARCO);
                g.setColor(Tema.BORDE);
                g.drawRoundRect(x, y, t, t, Tema.ARCO, Tema.ARCO);
                if (foto != null) {
                    Fotos.dibujar(g, foto, x + 1, y + 1, t - 2, t - 2);
                } else {
                    Fotos.paquete(g, x + t / 2, y + t / 2, 24);
                }
                int tx = x + t + 8, tw = getWidth() - tx - 6;
                g.setFont(Tema.textoFuerte(13));
                g.setColor(Tema.GRIS_OSCURO);
                g.drawString(recortar(g, nombre, tw), tx, getHeight() / 2 - 3);
                g.setFont(Tema.texto(12));
                g.setColor(Tema.GRIS_TEXTO);
                g.drawString(recortar(g, codigo, tw), tx, getHeight() / 2 + 13);
                g.dispose();
                return;
            }
            if (!cajita) {
                super.paintComponent(g0);
                return;
            }
            Graphics2D g = (Graphics2D) g0.create();
            Tema.suavizar(g);
            g.setColor(getBackground());
            g.fillRect(0, 0, getWidth(), getHeight());
            int alto = Math.min(32, getHeight() - 14);
            int y = (getHeight() - alto) / 2;
            g.setColor(gris ? new Color(0xE9ECF0) : Tema.BLANCO);
            g.fillRoundRect(3, y, getWidth() - 7, alto, Tema.ARCO, Tema.ARCO);
            g.setColor(gris ? Tema.BORDE : Tema.BORDE_CONTROL);
            g.drawRoundRect(3, y, getWidth() - 7, alto, Tema.ARCO, Tema.ARCO);
            g.dispose();
            setOpaque(false);
            super.paintComponent(g0);
            setOpaque(true);
        }
    }

    /** Campo para cambiar una cajita dentro de la tabla. */
    private class CampoCelda extends JTextField {
        CampoCelda() {
            setFont(Tema.texto(13));
            setHorizontalAlignment(JTextField.RIGHT);
            setOpaque(false);
            setBorder(BorderFactory.createCompoundBorder(BorderFactory.createEmptyBorder(7, 3, 7, 4),
                    BorderFactory.createCompoundBorder(new Tema.BordeRedondo(Tema.AZUL, Tema.RADIO),
                            BorderFactory.createEmptyBorder(0, 5, 0, 5))));
            addFocusListener(new java.awt.event.FocusAdapter() {
                @Override
                public void focusGained(java.awt.event.FocusEvent e) {
                    SwingUtilities.invokeLater(CampoCelda.this::selectAll);
                }
            });
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Tema.suavizar(g);
            int fila = tabla.getEditingRow();
            g.setColor(fila >= 0 && tabla.isRowSelected(fila) ? tabla.getSelectionBackground() : Tema.BLANCO);
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setColor(Tema.BLANCO);
            g.fillRoundRect(3, 7, getWidth() - 7, getHeight() - 14, Tema.ARCO, Tema.ARCO);
            g.dispose();
            super.paintComponent(g0);
        }
    }

    /** Boton de texto blanco para la franja azul («‹» y «En espera»). */
    private static class BotonBarra extends javax.swing.JButton {
        BotonBarra(String texto) {
            super(texto);
            setForeground(Tema.BLANCO);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setRolloverEnabled(true);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setBorder(BorderFactory.createEmptyBorder(3, 9, 3, 9));
            addFocusListener(new java.awt.event.FocusAdapter() {
                @Override
                public void focusGained(java.awt.event.FocusEvent e) { repaint(); }

                @Override
                public void focusLost(java.awt.event.FocusEvent e) { repaint(); }
            });
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Tema.suavizar(g);
            if (getModel().isRollover() || isFocusOwner()) {
                g.setColor(Tema.AZUL_PROFUNDO);
                g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, Tema.ARCO, Tema.ARCO);
            }
            if (isFocusOwner()) {
                g.setColor(Tema.BLANCO);
                g.setStroke(new BasicStroke(2f));
                g.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, Tema.ARCO, Tema.ARCO);
            }
            String txt = getText();
            if ("‹".equals(txt) || "›".equals(txt)) {
                // flecha dibujada: el caracter sale muy pequeño en la fuente
                g.setColor(Tema.BLANCO);
                g.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                int cx = getWidth() / 2, cy = getHeight() / 2, d = "‹".equals(txt) ? 1 : -1;
                g.drawLine(cx + 3 * d, cy - 7, cx - 4 * d, cy);
                g.drawLine(cx - 4 * d, cy, cx + 3 * d, cy + 7);
                g.dispose();
                return;
            }
            g.dispose();
            super.paintComponent(g0);
        }

        @Override
        public Dimension getPreferredSize() {
            String txt = getText();
            return "‹".equals(txt) || "›".equals(txt) ? new Dimension(32, 32) : super.getPreferredSize();
        }
    }

    // ------------------------------------------------------------------
    //  Descuento, comprar, espera y cancelar
    // ------------------------------------------------------------------

    private void pedirDescuento() {
        detenerEdicion();
        if (Capa.hayAbierta(this)) {
            return;
        }
        JPanel t = Capa.tarjeta(400, 26, 28);
        t.add(Tema.tituloPantalla("Descuento de la compra"));
        t.add(Tema.espacio(6));
        Tema.Parrafo ayuda = Tema.parrafo("Valor en pesos que el proveedor le rebajó a toda la factura. Escriba 0 para quitarlo.", 340);
        ayuda.setFont(Tema.texto(13));
        ayuda.setForeground(Tema.GRIS_TEXTO);
        ayuda.setAlignmentX(LEFT_ALIGNMENT);
        t.add(ayuda);
        t.add(Tema.espacio(12));
        JTextField campo = Controles.campoNumero("Ej. 5.000");
        if (compra.getDescuentoGeneral() > 0) {
            campo.setText(Tarifa.pesos(compra.getDescuentoGeneral()).replace("$", ""));
        }
        campo.setAlignmentX(LEFT_ALIGNMENT);
        Tema.anchoCompleto(campo);
        t.add(campo);
        JLabel error = new JLabel(" ");
        error.setFont(Tema.texto(12.5f));
        error.setForeground(Tema.ERROR_TEXTO);
        error.setAlignmentX(LEFT_ALIGNMENT);
        t.add(Tema.espacio(4));
        t.add(error);
        t.add(Tema.espacio(10));
        Tema.Boton ok = Tema.botonPrimario("Aplicar");
        Tema.Boton no = Tema.botonSecundario("Cancelar");
        JPanel f = Tema.fila(8, no, ok);
        f.setAlignmentX(LEFT_ALIGNMENT);
        t.add(f);
        Capa capa = new Capa(this, t, true);
        Runnable aplicar = () -> {
            try {
                long d = campo.getText().isBlank() ? 0 : ImportadorProductos.leerPesos(campo.getText());
                // lo maximo es lo que vale la compra sin este descuento
                long maximo = compra.getBruto() - (compra.getDescuentos() - compra.getDescuentoGeneral())
                        + compra.getImpuestos();
                if (d > maximo) {
                    throw new IllegalArgumentException("El descuento es más grande que la compra ("
                            + Tarifa.pesos(maximo) + ")");
                }
                compra.setDescuentoGeneral(d);
                capa.cerrar();
                refrescarCompra();
            } catch (IllegalArgumentException e) {
                error.setText(e.getMessage());
            }
        };
        ok.addActionListener(e -> aplicar.run());
        no.addActionListener(e -> capa.cerrar());
        campo.addActionListener(e -> aplicar.run());
        capa.mostrar(campo);
    }

    /** Comprar: se confirma proveedor, factura y bodega, y se guarda todo junto. */
    private void comprar() {
        if (!detenerEdicion()) {
            return;
        }
        if (compra.getLineas().isEmpty() || Capa.hayAbierta(this)) {
            return;
        }
        try {
            compra.validar();
        } catch (IllegalArgumentException e) {
            Alerta.mostrar(this, Alerta.Tipo.AVISO, "Revise la compra", e.getMessage(), null);
            return;
        }
        List<Bodega> bodegas;
        try {
            bodegas = bd.listarBodegas();
        } catch (SQLException e) {
            bodegas = new ArrayList<>();
            bodegas.add(new Bodega(Bodega.PUNTO_DE_VENTA, "PUNTO DE VENTA"));
        }
        JPanel t = Capa.tarjeta(460, 26, 30);
        t.add(Tema.tituloPantalla("Guardar la compra"));
        t.add(Tema.espacio(4));
        int n = compra.getLineas().size();
        JLabel res = new JLabel(n + (n == 1 ? " producto" : " productos") + "  ·  Total " + Tarifa.pesos(compra.getTotal()));
        res.setFont(Tema.texto(13.5f));
        res.setForeground(Tema.GRIS_TEXTO);
        res.setAlignmentX(LEFT_ALIGNMENT);
        t.add(res);
        t.add(Tema.espacio(14));
        // el proveedor se escoge de la lista (o se escribe uno que no esté); «Crear» lo agrega a la lista
        JComboBox<String> comboProv = new JComboBox<>();
        comboProv.setEditable(true);
        Tema.estiloCombo(comboProv, String::valueOf);
        comboProv.addItem("");
        try {
            for (rutas.modelo.Proveedor pv : app.getOrdenes().listarProveedores(true)) {
                comboProv.addItem(pv.nombre);
            }
        } catch (SQLException ex) {
            Ubicaciones.registrarError(ex);
        }
        comboProv.setSelectedItem(compra.getProveedor());
        JTextField proveedor = (JTextField) comboProv.getEditor().getEditorComponent();
        Tema.Boton crearProv = Tema.botonPequeno("Crear");
        crearProv.setToolTipText("Agregar un proveedor nuevo a la lista");
        JTextField factura = Tema.campo("Ej. FE-1234 (opcional)");
        factura.setText(compra.getFactura());
        JComboBox<Bodega> combo = new JComboBox<>(bodegas.toArray(new Bodega[0]));
        Tema.estiloCombo(combo, Bodega::getNombre);
        for (Bodega b : bodegas) {
            if (b.getId() == compra.getBodegaId()) {
                combo.setSelectedItem(b);
            }
        }
        JPanel filaProv = new JPanel(new BorderLayout(8, 0));
        filaProv.setOpaque(false);
        filaProv.add(comboProv, BorderLayout.CENTER);
        filaProv.add(crearProv, BorderLayout.EAST);
        t.add(campoConEtiqueta("Proveedor", filaProv));
        t.add(Tema.espacio(10));
        t.add(campoConEtiqueta("N.º de factura", factura));
        t.add(Tema.espacio(10));
        t.add(campoConEtiqueta("Bodega donde entra la mercancía", combo));
        t.add(Tema.espacio(10));
        Tema.Parrafo nota = Tema.parrafo("Al guardar, la cantidad se suma al stock de esa bodega y el valor compra"
                + " queda como costo de cada producto. Si cambió el valor venta, también se actualiza.", 400);
        nota.setFont(Tema.texto(12.5f));
        nota.setForeground(Tema.GRIS_TEXTO);
        nota.setAlignmentX(LEFT_ALIGNMENT);
        t.add(nota);
        JLabel error = new JLabel(" ");
        error.setFont(Tema.texto(12.5f));
        error.setForeground(Tema.ERROR_TEXTO);
        error.setAlignmentX(LEFT_ALIGNMENT);
        t.add(Tema.espacio(4));
        t.add(error);
        t.add(Tema.espacio(10));
        Tema.Boton ok = Tema.botonAccion("Guardar compra");
        Tema.Boton no = Tema.botonSecundario("Volver");
        JPanel f = Tema.fila(8, no, ok);
        f.setAlignmentX(LEFT_ALIGNMENT);
        t.add(f);
        Capa capa = new Capa(this, t, true);
        crearProv.addActionListener(e -> FormularioProveedor.abrir(this, app, null, pv -> {
            comboProv.addItem(pv.nombre);
            comboProv.setSelectedItem(pv.nombre);
        }));
        ok.addActionListener(e -> {
            Object elegido = comboProv.getEditor().getItem();
            compra.setProveedor(elegido == null ? "" : String.valueOf(elegido));
            compra.setFactura(factura.getText());
            Bodega b = (Bodega) combo.getSelectedItem();
            compra.setBodegaId(b == null ? Bodega.PUNTO_DE_VENTA : b.getId());
            long numero;
            try {
                numero = bd.registrarCompra(idEspera, app.getSesion().getUsuario().getId(), compra);
            } catch (IllegalArgumentException | IllegalStateException ex) {
                error.setText(ex.getMessage());
                return;
            } catch (SQLException ex) {
                Ubicaciones.registrarError(ex);
                error.setText("No se pudo guardar: " + ex.getMessage());
                return;
            }
            // ya quedo guardada: la pantalla se limpia de una vez para que no se pueda guardar dos veces
            List<Compra.Linea> guardadas = new ArrayList<>(compra.getLineas());
            rutas.logica.OrdenCompra deOrden = recibiendo;
            capa.cerrar();
            nuevaCompra();
            String avisoOrden = deOrden == null ? null : registrarLlegada(deOrden, guardadas, numero);
            for (Compra.Linea l : guardadas) {
                try {
                    cat.recargarProducto(l.getProducto().getId());
                } catch (SQLException ex) {
                    Ubicaciones.registrarError(ex);
                }
            }
            refrescarProductos();
            String donde = b == null || b.esPuntoDeVenta() ? "" : " en " + b.getNombre();
            Aviso.mostrar(this, Aviso.Tipo.BIEN, "Compra No. " + Comprobante.numeroTexto(numero) + " guardada",
                    avisoOrden != null ? avisoOrden : "El stock ya subió" + donde);
        });
        no.addActionListener(e -> capa.cerrar());
        capa.mostrar(proveedor);
    }

    private static JComponent campoConEtiqueta(String texto, JComponent campo) {
        JPanel p = new JPanel(new BorderLayout(0, 5));
        p.setOpaque(false);
        JLabel l = new JLabel(texto);
        l.setFont(Tema.textoFuerte(13));
        l.setForeground(Tema.GRIS_OSCURO);
        p.add(l, BorderLayout.NORTH);
        p.add(campo, BorderLayout.CENTER);
        p.setAlignmentX(LEFT_ALIGNMENT);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.getPreferredSize().height));
        return p;
    }

    private void nuevaCompra() {
        compra = modoOrden ? new rutas.logica.OrdenCompra() : new Compra();
        idEspera = null;
        recibiendo = null;
        areaObs.getArea().setText("");
        refrescarCompra();
        buscador.requestFocusInWindow();
    }

    private void ponerEnEspera() {
        if (!detenerEdicion()) {
            return;
        }
        if (compra.getLineas().isEmpty() || Capa.hayAbierta(this)) {
            return;
        }
        if (guardarEspera()) {
            nuevaCompra();
            Aviso.mostrar(this, Aviso.Tipo.BIEN, "Compra en espera", "La encuentra arriba en «En espera»");
        }
    }

    private boolean guardarEspera() {
        try {
            idEspera = bd.guardarCompraEspera(idEspera, app.getSesion().getUsuario().getId(), compra);
            return true;
        } catch (IllegalArgumentException | IllegalStateException e) {
            Alerta.mostrar(this, Alerta.Tipo.AVISO, "No se pudo poner en espera", e.getMessage(), null);
        } catch (SQLException e) {
            Ubicaciones.registrarError(e);
            Alerta.mostrar(this, Alerta.Tipo.AVISO, "No se pudo poner en espera", e.getMessage(), null);
        }
        return false;
    }

    private void cancelar() {
        detenerEdicion();
        if ((compra.getLineas().isEmpty() && idEspera == null) || Capa.hayAbierta(this)) {
            return;
        }
        int n = compra.getLineas().size();
        String msg = n == 0 ? "La compra deja de estar en espera."
                : "Se quita" + (n == 1 ? " el producto" : "n los " + n + " productos") + " de la compra"
                + (idEspera != null ? " y deja de estar en espera." : ". No se guarda nada.");
        Alerta.confirmar(this, "¿Cancelar esta compra?", msg, "Sí, cancelar", "No, seguir", () -> {
            if (idEspera != null) {
                try {
                    bd.borrarCompraEspera(idEspera);
                } catch (SQLException e) {
                    Ubicaciones.registrarError(e);
                }
            }
            nuevaCompra();
        });
    }

    /** Lista de compras en espera: retomar o borrar. */
    private void verEspera() {
        detenerEdicion();
        if (Capa.hayAbierta(this)) {
            return;
        }
        List<BaseTienda.CompraEspera> esperas;
        try {
            esperas = bd.listarComprasEspera();
        } catch (SQLException e) {
            Alerta.mostrar(this, Alerta.Tipo.AVISO, "No se pudo abrir", e.getMessage(), null);
            return;
        }
        JPanel t = Capa.tarjeta(520, 24, 26);
        t.add(Tema.tituloPantalla("Compras en espera"));
        t.add(Tema.espacio(12));
        Capa[] capa = new Capa[1];
        Component primero = null;
        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy h:mm a");
        for (BaseTienda.CompraEspera ce : esperas) {
            JPanel fila = new JPanel(new BorderLayout(12, 0));
            fila.setOpaque(false);
            fila.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Tema.BORDE),
                    BorderFactory.createEmptyBorder(8, 0, 8, 0)));
            JPanel txt = new JPanel();
            txt.setOpaque(false);
            txt.setLayout(new BoxLayout(txt, BoxLayout.Y_AXIS));
            JLabel a = new JLabel((ce.getProveedor() == null ? "Sin proveedor" : ce.getProveedor())
                    + "  ·  " + Tarifa.pesos(ce.getTotal()));
            a.setFont(Tema.textoFuerte(13.5f));
            a.setForeground(Tema.GRIS_OSCURO);
            txt.add(a);
            JLabel b = new JLabel(ce.getProductos() + (ce.getProductos() == 1 ? " producto" : " productos") + "  ·  "
                    + ce.getCreada().format(fmt).replace("AM", "a. m.").replace("PM", "p. m."));
            b.setFont(Tema.texto(12.5f));
            b.setForeground(Tema.GRIS_TEXTO);
            txt.add(b);
            fila.add(txt, BorderLayout.CENTER);
            Tema.Boton retomar = Tema.botonPequeno("Retomar");
            Tema.Boton borrar = Tema.botonPequeno("");
            borrar.setIcon(Iconos.de("basura", 16, Tema.ERROR));
            borrar.setToolTipText("Borrar esta compra en espera");
            borrar.getAccessibleContext().setAccessibleName("Borrar compra en espera");
            fila.add(Tema.fila(6, borrar, retomar), BorderLayout.EAST);
            fila.setAlignmentX(LEFT_ALIGNMENT);
            fila.setMaximumSize(new Dimension(Integer.MAX_VALUE, fila.getPreferredSize().height));
            t.add(fila);
            if (primero == null) {
                primero = retomar;
            }
            retomar.addActionListener(e -> {
                capa[0].cerrar();
                retomar(ce.getId());
            });
            borrar.addActionListener(e -> {
                try {
                    bd.borrarCompraEspera(ce.getId());
                    if (idEspera != null && idEspera == ce.getId()) {
                        idEspera = null;
                    }
                } catch (SQLException ex) {
                    Ubicaciones.registrarError(ex);
                }
                capa[0].cerrar();
                refrescarCompra();
                Aviso.mostrar(this, Aviso.Tipo.BIEN, "Compra en espera borrada", "");
            });
        }
        t.add(Tema.espacio(14));
        Tema.Boton cerrar = Tema.botonSecundario("Cerrar");
        JPanel f = Tema.fila(8, cerrar);
        f.setAlignmentX(LEFT_ALIGNMENT);
        t.add(f);
        capa[0] = new Capa(this, t, true);
        cerrar.addActionListener(e -> capa[0].cerrar());
        capa[0].mostrar(primero == null ? cerrar : primero);
    }

    /** Abre una compra en espera. Si hay otra a medias, primero queda en espera. */
    private void retomar(long id) {
        if (idEspera != null && idEspera == id) {
            return; // ya es la que esta abierta
        }
        if (!compra.getLineas().isEmpty() && !guardarEspera()) {
            return;
        }
        try {
            BaseTienda.CompraRetomada r = bd.cargarCompraEspera(id, cat);
            compra = r.getCompra();
            idEspera = id;
            recibiendo = null; // la compra retomada no es la llegada de una orden
            areaObs.getArea().setText(compra.getObservaciones());
            refrescarCompra();
            if (!r.getAvisos().isEmpty()) {
                Alerta.mostrar(this, Alerta.Tipo.AVISO, "Revise la compra", String.join(". ", r.getAvisos()), null);
            }
        } catch (IllegalArgumentException | SQLException e) {
            Alerta.mostrar(this, Alerta.Tipo.AVISO, "No se pudo retomar", e.getMessage(), null);
            refrescarCompra();
        }
    }

    // ------------------------------------------------------------------
    //  Recibir una orden de compra (en la pantalla de Nueva compra)
    // ------------------------------------------------------------------

    /**
     * Llego la mercancia de la orden: la compra se llena con lo que falta por llegar, el proveedor y
     * la bodega. Se corrige lo que vino distinto y se guarda como siempre; ahi la orden queda
     * Recibida (o Recibida en parte). Si habia otra compra a medias, queda en espera.
     */
    void recibirOrden(rutas.logica.OrdenCompra o) {
        detenerEdicion();
        if (!compra.getLineas().isEmpty() && recibiendo == null && !guardarEspera()) {
            return;
        }
        Compra c = new Compra();
        for (Compra.Linea l : o.getLineas()) {
            if (l.getPendiente() <= 0) {
                continue;
            }
            Producto actual = cat.buscarPorId(l.getProducto().getId());
            Compra.Linea n = new Compra.Linea(actual == null ? l.getProducto() : actual, l.getPendiente(), l.getBase(),
                    l.getImpuesto());
            if (l.getCostoEscrito() != null) {
                n.setCosto(l.getCostoEscrito());
            }
            n.setDescuento(l.getDescuento());
            c.getLineas().add(n);
        }
        c.setProveedor(o.getProveedor());
        c.setBodegaId(o.getBodegaId());
        c.setObservaciones("Orden de compra No. " + Comprobante.numeroTexto(o.getNumero())
                + (o.getObservaciones().isBlank() ? "" : ". " + o.getObservaciones()));
        compra = c;
        idEspera = null;
        recibiendo = o;
        areaObs.getArea().setText(c.getObservaciones());
        refrescarCompra();
        Aviso.mostrar(this, Aviso.Tipo.MENSAJE, "Recibir la orden No. " + Comprobante.numeroTexto(o.getNumero()),
                "Corrija lo que llegó distinto y oprima «Comprar»");
    }

    /** Despues de guardar la compra de una orden: se anota lo recibido. Devuelve el texto del aviso. */
    private String registrarLlegada(rutas.logica.OrdenCompra o, List<Compra.Linea> guardadas, long numeroCompra) {
        Map<Long, Long> recibido = new java.util.LinkedHashMap<>();
        for (Compra.Linea l : guardadas) {
            recibido.merge(l.getProducto().getId(), l.getCantidad(), Long::sum);
        }
        try {
            rutas.logica.OrdenCompra.Estado e = app.getOrdenes().registrarRecepcion(o.getId(), recibido, numeroCompra);
            return "El stock subió y la orden No. " + Comprobante.numeroTexto(o.getNumero()) + " quedó "
                    + e.toString().toLowerCase();
        } catch (IllegalArgumentException | IllegalStateException | SQLException ex) {
            Ubicaciones.registrarError(ex);
            return "El stock subió, pero no se pudo marcar la orden: " + ex.getMessage();
        }
    }

    // ------------------------------------------------------------------
    //  Orden de compra (modoOrden)
    // ------------------------------------------------------------------

    private rutas.logica.OrdenCompra orden() {
        return (rutas.logica.OrdenCompra) compra;
    }

    /** En la orden solo se cambia mientras este en borrador o pendiente. */
    private boolean sePuedeCambiar() {
        return !modoOrden || orden().getEstado().editable();
    }

    /** Agrega a la orden lo que esta en su minimo o por debajo, con lo que falta para llegar al minimo. */
    private void agregarLoQueSeAcaba() {
        if (!sePuedeCambiar() || Capa.hayAbierta(this)) {
            return;
        }
        detenerEdicion();
        int n = 0;
        for (Producto p : rutas.logica.BaseOrdenes.porAcabarse(cat)) {
            boolean ya = false;
            for (Compra.Linea l : compra.getLineas()) {
                ya |= l.getProducto().getId() == p.getId();
            }
            if (ya) {
                continue;
            }
            long falta = Math.max(p.getMinimo() - p.getStock(), 0);
            if (p.getUnidad() == Unidad.UNIDAD) {
                falta = Math.max(1000, (falta + 999) / 1000 * 1000); // unidades enteras, minimo 1
            } else if (falta == 0) {
                falta = 1000;
            }
            Compra.Linea l = compra.agregar(p, impuestoDe(p));
            l.setCantidad(falta);
            n++;
        }
        refrescarCompra();
        Aviso.mostrar(this, n == 0 ? Aviso.Tipo.AVISO : Aviso.Tipo.BIEN, n == 0 ? "No hay productos por acabarse"
                : n + (n == 1 ? " producto agregado" : " productos agregados"),
                n == 0 ? "Todos están por encima de su stock mínimo" : "Con lo que falta para llegar al mínimo");
    }

    /** Que hacer al terminar (volver a la lista de ordenes). */
    void setAlTerminarOrden(Runnable r) {
        alTerminarOrden = r == null ? () -> { } : r;
    }

    /** Proveedor, bodega, fecha y fecha estimada en una fila, y la nota general debajo (como la referencia). */
    private JComponent cabeceraOrden() {
        JPanel c = new JPanel(new GridBagLayout());
        c.setOpaque(false);
        c.setBorder(BorderFactory.createEmptyBorder(12, 14, 0, 14));
        GridBagConstraints g = new GridBagConstraints();
        g.gridy = 0;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.anchor = GridBagConstraints.NORTHWEST;

        Tema.estiloCombo(comboProveedor, pv -> pv == null ? "SELECCIONAR PROVEEDOR" : pv.nombre);
        comboProveedor.setMaximumRowCount(12);
        comboProveedor.getAccessibleContext().setAccessibleName("Proveedor");
        comboProveedor.addActionListener(e -> {
            if (!llenandoCabecera) {
                leerCabecera(false);
                refrescarOrden();
            }
        });
        Tema.Boton crear = Tema.botonSecundario("Crear");
        crear.setToolTipText("Agregar un proveedor nuevo");
        crear.addActionListener(e -> FormularioProveedor.abrir(this, app, null, pv -> {
            llenarProveedores();
            seleccionarProveedor(pv.id);
            leerCabecera(false);
            refrescarOrden();
        }));
        JPanel prov = new JPanel(new BorderLayout(8, 0));
        prov.setOpaque(false);
        prov.add(comboProveedor, BorderLayout.CENTER);
        prov.add(crear, BorderLayout.EAST);
        g.gridx = 0;
        g.weightx = 0.36;
        g.insets = new Insets(0, 0, 0, 12);
        c.add(conEtiquetaCentro("Proveedor", prov), g);

        Tema.estiloCombo(comboBodegaOrden, Bodega::getNombre);
        comboBodegaOrden.getAccessibleContext().setAccessibleName("Bodega");
        g.gridx = 1;
        g.weightx = 0.22;
        c.add(conEtiquetaCentro("Bodega", comboBodegaOrden), g);
        g.gridx = 2;
        g.weightx = 0.21;
        c.add(conEtiquetaCentro("Fecha", fechaOrden), g);
        g.gridx = 3;
        g.weightx = 0.21;
        g.insets = new Insets(0, 0, 0, 0);
        c.add(conEtiquetaCentro("Fecha estimada", fechaEstimada), g);

        areaNota.setPreferredSize(new Dimension(300, 52));
        g.gridx = 0;
        g.gridy = 1;
        g.gridwidth = 4;
        g.weightx = 1;
        g.insets = new Insets(10, 0, 0, 0);
        c.add(conEtiquetaCentro("Nota general", areaNota), g);
        return c;
    }

    /** Etiqueta en negrita centrada encima del control, como en la referencia. */
    private static JComponent conEtiquetaCentro(String texto, JComponent campo) {
        JPanel p = new JPanel(new BorderLayout(0, 5));
        p.setOpaque(false);
        JLabel l = new JLabel(texto, SwingConstants.CENTER);
        l.setFont(Tema.textoFuerte(13));
        l.setForeground(Tema.GRIS_OSCURO);
        l.setLabelFor(campo);
        p.add(l, BorderLayout.NORTH);
        p.add(campo, BorderLayout.CENTER);
        if (!(campo instanceof Controles.AreaTexto)) {
            campo.setPreferredSize(new Dimension(80, 40));
        }
        return p;
    }

    private void llenarProveedores() {
        llenandoCabecera = true;
        Object antes = comboProveedor.getSelectedItem();
        comboProveedor.removeAllItems();
        comboProveedor.addItem(null);
        try {
            for (rutas.modelo.Proveedor pv : app.getOrdenes().listarProveedores(true)) {
                comboProveedor.addItem(pv);
            }
        } catch (SQLException e) {
            Ubicaciones.registrarError(e);
        }
        if (antes instanceof rutas.modelo.Proveedor) {
            seleccionarProveedor(((rutas.modelo.Proveedor) antes).id);
        }
        llenandoCabecera = false;
    }

    private void seleccionarProveedor(Long id) {
        boolean antes = llenandoCabecera;
        llenandoCabecera = true;
        comboProveedor.setSelectedIndex(0);
        for (int i = 1; i < comboProveedor.getItemCount(); i++) {
            if (id != null && comboProveedor.getItemAt(i).id == id) {
                comboProveedor.setSelectedIndex(i);
            }
        }
        llenandoCabecera = antes;
    }

    private void llenarBodegasOrden() {
        comboBodegaOrden.removeAllItems();
        try {
            for (Bodega b : bd.listarBodegas()) {
                comboBodegaOrden.addItem(b);
            }
        } catch (SQLException e) {
            comboBodegaOrden.addItem(new Bodega(Bodega.PUNTO_DE_VENTA, "PUNTO DE VENTA"));
        }
    }

    /** Pone en la pantalla la orden (nueva o para editar). */
    void abrirOrden(rutas.logica.OrdenCompra o, List<String> avisos) {
        detenerEdicion();
        compra = o == null ? new rutas.logica.OrdenCompra() : o;
        idEspera = null;
        llenandoCabecera = true;
        llenarProveedores();
        llenarBodegasOrden();
        rutas.logica.OrdenCompra ord = orden();
        if (ord.getProveedorId() == null && !ord.getProveedor().isBlank()) {
            // un proveedor que ya no esta en la lista (desactivado): se muestra igual
            rutas.modelo.Proveedor viejo = new rutas.modelo.Proveedor();
            viejo.nombre = ord.getProveedor();
            comboProveedor.addItem(viejo);
            comboProveedor.setSelectedItem(viejo);
        } else {
            seleccionarProveedor(ord.getProveedorId());
            if (ord.getProveedorId() != null && comboProveedor.getSelectedIndex() <= 0) {
                try { // desactivado: se agrega para no perderlo
                    rutas.modelo.Proveedor pv = app.getOrdenes().buscarProveedor(ord.getProveedorId());
                    if (pv != null) {
                        comboProveedor.addItem(pv);
                        comboProveedor.setSelectedItem(pv);
                    }
                } catch (SQLException e) {
                    Ubicaciones.registrarError(e);
                }
            }
        }
        for (int i = 0; i < comboBodegaOrden.getItemCount(); i++) {
            if (comboBodegaOrden.getItemAt(i).getId() == ord.getBodegaId()) {
                comboBodegaOrden.setSelectedIndex(i);
            }
        }
        fechaOrden.setFecha(ord.getFecha());
        fechaEstimada.setFecha(ord.getFechaEstimada());
        areaNota.getArea().setText(ord.getObservaciones());
        interruptorRetencion.setSelected(ord.isConRetencion());
        boolean editable = ord.getEstado().editable();
        for (JComponent c : new JComponent[]{comboProveedor, comboBodegaOrden, fechaOrden.getCampo(), fechaEstimada.getCampo(),
            interruptorRetencion}) {
            c.setEnabled(editable);
        }
        areaNota.getArea().setEditable(editable);
        llenandoCabecera = false;
        refrescarCompra();
        if (avisos != null && !avisos.isEmpty()) {
            Alerta.mostrar(this, Alerta.Tipo.AVISO, "Revise la orden", String.join(". ", avisos), null);
        }
    }

    /**
     * Pasa lo de arriba (proveedor, bodega, fechas y nota) a la orden. estricto: si una fecha esta
     * mal escrita lanza el aviso y devuelve false.
     */
    private boolean leerCabecera(boolean estricto) {
        rutas.logica.OrdenCompra o = orden();
        Object pv = comboProveedor.getSelectedItem();
        if (pv instanceof rutas.modelo.Proveedor) {
            rutas.modelo.Proveedor p = (rutas.modelo.Proveedor) pv;
            o.setProveedor(p.nombre);
            o.setProveedorId(p.id == 0 ? null : p.id);
        } else {
            o.setProveedor("");
            o.setProveedorId(null);
        }
        Bodega b = (Bodega) comboBodegaOrden.getSelectedItem();
        o.setBodegaId(b == null ? Bodega.PUNTO_DE_VENTA : b.getId());
        o.setObservaciones(areaNota.getArea().getText());
        try {
            LocalDateFecha f = new LocalDateFecha(fechaOrden.getFecha(), fechaEstimada.getFecha());
            o.setFecha(f.fecha == null ? java.time.LocalDate.now() : f.fecha);
            o.setFechaEstimada(f.estimada);
            return true;
        } catch (IllegalArgumentException e) {
            if (estricto) {
                Alerta.mostrar(this, Alerta.Tipo.AVISO, "Revise la fecha", e.getMessage(), null);
            }
            return false;
        }
    }

    /** Las dos fechas leidas juntas (asi un error en una no deja la otra a medias). */
    private static final class LocalDateFecha {
        final java.time.LocalDate fecha;
        final java.time.LocalDate estimada;

        LocalDateFecha(java.time.LocalDate fecha, java.time.LocalDate estimada) {
            this.fecha = fecha;
            this.estimada = estimada;
        }
    }

    private final Tema.Boton btnBorrador = Tema.botonSecundario("Guardar borrador");
    private final Tema.Boton btnProcesar = Tema.botonAccion("Procesar orden");
    private final Tema.Boton btnPdf = Tema.botonSecundario("Ver PDF");
    private final Tema.Boton btnCorreo = Tema.botonSecundario("Enviar correo");
    private final Tema.Boton btnWhatsapp = Tema.botonSecundario("WhatsApp");
    private final Tema.Boton btnCancelarOrden = Tema.botonSecundario("Cancelar");

    private void armarBotonesOrden() {
        botones.removeAll();
        botones.setLayout(new java.awt.GridLayout(1, 6, 8, 0));
        btnPdf.setIcon(new PanelProductos.BotonIcono("", PanelProductos.Icono.PDF, "", () -> { }).getIcon());
        btnCorreo.setIcon(new CapaCobro.LogoPago("/imagenes/archivos/gmail.png", 18));
        btnWhatsapp.setIcon(new CapaCobro.LogoPago("/imagenes/archivos/whatsapp.png", 18));
        btnCancelarOrden.setForeground(Tema.ERROR_TEXTO);
        btnBorrador.setToolTipText("Guardar la orden para seguirla después (no se le manda al proveedor)");
        btnProcesar.setToolTipText("Guardar la orden como pendiente: ya se le pidió al proveedor (F12)");
        btnPdf.setToolTipText("Ver, imprimir o guardar la orden en PDF");
        btnCorreo.setToolTipText("Mandar la orden en PDF al correo del proveedor");
        btnWhatsapp.setToolTipText("Mandar el pedido por WhatsApp al vendedor del proveedor");
        btnCancelarOrden.setToolTipText("Cancelar la orden (si ya se guardó, queda Cancelada)");
        for (Tema.Boton b : new Tema.Boton[]{btnBorrador, btnProcesar, btnPdf, btnCorreo, btnWhatsapp, btnCancelarOrden}) {
            b.setIconTextGap(6);
            botones.add(b);
        }
        btnBorrador.addActionListener(e -> guardarBorrador());
        btnProcesar.addActionListener(e -> procesarOrden(null));
        btnPdf.addActionListener(e -> verPdfOrden());
        btnCorreo.addActionListener(e -> procesarOrden(this::enviarCorreoOrden));
        btnWhatsapp.addActionListener(e -> procesarOrden(this::enviarWhatsappOrden));
        btnCancelarOrden.addActionListener(e -> cancelarOrden());
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_F12, 0), "comprar");
        getActionMap().put("comprar", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (isShowing()) {
                    procesarOrden(null);
                }
            }
        });
    }

    /** Numero, totales, retencion, estado y botones de la orden. */
    private void refrescarOrden() {
        rutas.logica.OrdenCompra o = orden();
        long numero = o.getNumero();
        if (numero == 0) {
            try {
                numero = app.getOrdenes().siguienteNumero();
            } catch (SQLException e) {
                numero = 0;
            }
        }
        lblNumero.setText("Orden No. " + Comprobante.numeroTexto(numero) + (o.getId() == 0 ? " (nueva)" : ""));
        lblBruto.setText(Tarifa.pesos(o.getBruto()));
        lblDescuento.setText(o.getDescuentos() == 0 ? "$0" : "-" + Tarifa.pesos(o.getDescuentos()));
        lblImpuestos.setText(Tarifa.pesos(o.getImpuestos()));
        lblRetencion.setText((o.isConRetencion() ? "(" + Carrito.textoPorcentaje(o.getRetencionPct()) + " %)  -" : "-")
                + Tarifa.pesos(o.getRetencion()));
        lblTotal.setText(Tarifa.pesos(o.getTotalOrden()));
        lblTotal.setForeground(Tema.EXITO_TEXTO);
        interruptorRetencion.setSelected(o.isConRetencion());
        String est = o.getEstado().toString();
        lblEstadoOrden.setText("Estado: " + est + (o.getCompras().isEmpty() ? "" : "  ·  compras " + o.getCompras()));
        lblEstadoOrden.setForeground(o.getEstado() == rutas.logica.OrdenCompra.Estado.CANCELADA ? Tema.ERROR_TEXTO
                : o.getEstado() == rutas.logica.OrdenCompra.Estado.RECIBIDA ? Tema.EXITO_TEXTO
                : o.getEstado() == rutas.logica.OrdenCompra.Estado.BORRADOR ? Tema.GRIS_TEXTO : Tema.AVISO_TEXTO);
        boolean editable = o.getEstado().editable();
        boolean hay = !o.getLineas().isEmpty();
        btnBorrador.setEnabled(editable && o.getEstado() == rutas.logica.OrdenCompra.Estado.BORRADOR);
        btnBorrador.setText(o.getEstado() == rutas.logica.OrdenCompra.Estado.PENDIENTE ? "Guardado" : "Guardar borrador");
        btnProcesar.setEnabled(editable && hay && !o.getProveedor().isBlank());
        btnProcesar.setText(o.getEstado() == rutas.logica.OrdenCompra.Estado.PENDIENTE ? "Guardar cambios" : "Procesar orden");
        btnPdf.setEnabled(hay);
        btnCorreo.setEnabled(hay && !o.getProveedor().isBlank());
        btnWhatsapp.setEnabled(hay && !o.getProveedor().isBlank());
        btnCancelarOrden.setEnabled(o.getEstado() != rutas.logica.OrdenCompra.Estado.CANCELADA
                && o.getEstado() != rutas.logica.OrdenCompra.Estado.RECIBIDA);
        botones.setVisible(true);
        btnListaEspera.setVisible(false);
        tabla.repaint();
        revalidate();
    }

    private void pedirRetencion() {
        detenerEdicion();
        if (Capa.hayAbierta(this) || !sePuedeCambiar()) {
            return;
        }
        JPanel t = Capa.tarjeta(400, 26, 28);
        t.add(Tema.tituloPantalla("Retención en la fuente"));
        t.add(Tema.espacio(6));
        Tema.Parrafo ayuda = Tema.parrafo("Porcentaje que se le descuenta al proveedor sobre la base (sin IVA). "
                + "Por compras suele ser 2,5 %.", 340);
        ayuda.setFont(Tema.texto(13));
        ayuda.setForeground(Tema.GRIS_TEXTO);
        ayuda.setAlignmentX(LEFT_ALIGNMENT);
        t.add(ayuda);
        t.add(Tema.espacio(12));
        JTextField campo = Controles.campoNumero("Ej. 2,5");
        campo.setText(Carrito.textoPorcentaje(orden().getRetencionPct()));
        campo.setAlignmentX(LEFT_ALIGNMENT);
        Tema.anchoCompleto(campo);
        t.add(campo);
        JLabel error = new JLabel(" ");
        error.setFont(Tema.texto(12.5f));
        error.setForeground(Tema.ERROR_TEXTO);
        error.setAlignmentX(LEFT_ALIGNMENT);
        t.add(Tema.espacio(4));
        t.add(error);
        t.add(Tema.espacio(10));
        Tema.Boton ok = Tema.botonPrimario("Aplicar");
        Tema.Boton no = Tema.botonSecundario("Cancelar");
        JPanel f = Tema.fila(8, no, ok);
        f.setAlignmentX(LEFT_ALIGNMENT);
        t.add(f);
        Capa capa = new Capa(this, t, true);
        Runnable aplicar = () -> {
            try {
                long pct = Carrito.leerPorcentaje(campo.getText());
                orden().setRetencionPct(pct);
                orden().setConRetencion(pct > 0);
                capa.cerrar();
                refrescarCompra();
            } catch (IllegalArgumentException e) {
                error.setText("Escriba un porcentaje de 0 a 100");
            }
        };
        ok.addActionListener(e -> aplicar.run());
        no.addActionListener(e -> capa.cerrar());
        campo.addActionListener(e -> aplicar.run());
        capa.mostrar(campo);
    }

    private void guardarBorrador() {
        if (!detenerEdicion() || !leerCabecera(true) || Capa.hayAbierta(this)) {
            return;
        }
        rutas.logica.OrdenCompra o = orden();
        if (o.getLineas().isEmpty() && o.getProveedor().isBlank()) {
            Aviso.mostrar(this, Aviso.Tipo.AVISO, "No hay nada que guardar", "Escoja el proveedor o agregue productos");
            return;
        }
        try {
            long n = app.getOrdenes().guardarOrden(o, app.getSesion().getUsuario().getId());
            refrescarCompra();
            Aviso.mostrar(this, Aviso.Tipo.BIEN, "Orden No. " + Comprobante.numeroTexto(n) + " guardada",
                    "Quedó como borrador en Órdenes de compra");
        } catch (IllegalArgumentException | IllegalStateException e) {
            Alerta.mostrar(this, Alerta.Tipo.AVISO, "No se pudo guardar", e.getMessage(), null);
        } catch (SQLException e) {
            Ubicaciones.registrarError(e);
            Alerta.mostrar(this, Alerta.Tipo.AVISO, "No se pudo guardar", e.getMessage(), null);
        }
    }

    /**
     * Procesar: la orden queda Pendiente (ya se le pidio al proveedor). luego: que hacer despues
     * (mandarla por correo o WhatsApp); si es null, se pregunta como mandarla.
     */
    private void procesarOrden(Runnable luego) {
        if (!detenerEdicion() || !leerCabecera(true) || Capa.hayAbierta(this)) {
            return;
        }
        rutas.logica.OrdenCompra o = orden();
        if (!o.getEstado().editable()) {
            if (luego != null) {
                luego.run(); // ya estaba procesada: solo se manda
            }
            return;
        }
        try {
            o.validarOrden();
        } catch (IllegalArgumentException e) {
            Alerta.mostrar(this, Alerta.Tipo.AVISO, "Revise la orden", e.getMessage(), null);
            return;
        }
        rutas.logica.OrdenCompra.Estado antes = o.getEstado();
        o.setEstado(rutas.logica.OrdenCompra.Estado.PENDIENTE);
        long n;
        try {
            n = app.getOrdenes().guardarOrden(o, app.getSesion().getUsuario().getId());
        } catch (IllegalArgumentException | IllegalStateException e) {
            o.setEstado(antes);
            Alerta.mostrar(this, Alerta.Tipo.AVISO, "No se pudo guardar", e.getMessage(), null);
            return;
        } catch (SQLException e) {
            o.setEstado(antes);
            Ubicaciones.registrarError(e);
            Alerta.mostrar(this, Alerta.Tipo.AVISO, "No se pudo guardar", e.getMessage(), null);
            return;
        }
        refrescarCompra();
        if (luego != null) {
            luego.run();
            return;
        }
        // lista: ¿como se la mandamos al proveedor?
        Capa[] capa = new Capa[1];
        JPanel modal = FormularioProveedor.modal("Orden No. " + Comprobante.numeroTexto(n) + " procesada", 520,
                () -> capa[0].cerrar());
        JPanel t = (JPanel) ((BorderLayout) modal.getLayout()).getLayoutComponent(BorderLayout.CENTER);
        Tema.Parrafo txt = Tema.parrafo("Quedó pendiente en Órdenes de compra. Mándesela a " + o.getProveedor()
                + " para que le despache el pedido.", 460);
        txt.setFont(Tema.texto(14));
        t.add(txt);
        t.add(Tema.espacio(16));
        Tema.Boton wa = Tema.botonSecundario("Enviar por WhatsApp");
        wa.setIcon(new CapaCobro.LogoPago("/imagenes/archivos/whatsapp.png", 18));
        Tema.Boton mail = Tema.botonSecundario("Enviar correo");
        mail.setIcon(new CapaCobro.LogoPago("/imagenes/archivos/gmail.png", 18));
        Tema.Boton pdf = Tema.botonSecundario("Ver PDF");
        pdf.setIcon(btnPdf.getIcon());
        Tema.Boton listo = Tema.botonAccion("Listo");
        JPanel f = new JPanel(new java.awt.GridLayout(2, 2, 8, 8));
        f.setOpaque(false);
        f.setAlignmentX(LEFT_ALIGNMENT);
        f.add(wa);
        f.add(mail);
        f.add(pdf);
        f.add(listo);
        t.add(f);
        capa[0] = new Capa(this, modal, true);
        capa[0].setAlCerrar(this::terminarOrden);
        wa.addActionListener(e -> {
            enviarWhatsappOrden();
        });
        mail.addActionListener(e -> enviarCorreoOrden());
        pdf.addActionListener(e -> verPdfOrden());
        listo.addActionListener(e -> capa[0].cerrar());
        capa[0].mostrar(wa);
    }

    /** Vuelve a la lista de ordenes con la pantalla limpia. */
    private void terminarOrden() {
        nuevaCompra();
        alTerminarOrden.run();
    }

    private void cancelarOrden() {
        detenerEdicion();
        if (Capa.hayAbierta(this)) {
            return;
        }
        rutas.logica.OrdenCompra o = orden();
        if (o.getId() == 0) {
            if (o.getLineas().isEmpty()) {
                terminarOrden();
                return;
            }
            Alerta.confirmar(this, "¿Descartar esta orden?", "No se ha guardado. Se pierden los productos que agregó.",
                    "Sí, descartar", "No, seguir", this::terminarOrden);
            return;
        }
        Alerta.confirmar(this, "¿Cancelar la orden No. " + Comprobante.numeroTexto(o.getNumero()) + "?",
                "Queda como Cancelada en Órdenes de compra. Si ya se la mandó al proveedor, avísele.",
                "Sí, cancelar orden", "No, seguir", () -> {
                    try {
                        app.getOrdenes().cancelarOrden(o.getId());
                        Aviso.mostrar(this, Aviso.Tipo.BIEN, "Orden cancelada", "No. " + Comprobante.numeroTexto(o.getNumero()));
                        terminarOrden();
                    } catch (IllegalStateException | SQLException e) {
                        Alerta.mostrar(this, Alerta.Tipo.AVISO, "No se pudo cancelar", e.getMessage(), null);
                    }
                });
    }

    private void verPdfOrden() {
        if (!detenerEdicion()) {
            return;
        }
        leerCabecera(false);
        VistaPrevia.mostrar(this, OrdenDocumento.documento(app, orden()), "Orden de compra");
    }

    private void enviarWhatsappOrden() {
        OrdenDocumento.enviarWhatsapp(this, app, orden());
    }

    private void enviarCorreoOrden() {
        OrdenDocumento.enviarCorreo(this, app, orden());
    }

    /** Interruptor (switch) azul para las retenciones, como el de la referencia. */
    private static class Interruptor extends javax.swing.JCheckBox {
        Interruptor() {
            setOpaque(false);
            setFocusPainted(false);
            setBorderPainted(false);
            setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(40, 24);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Tema.suavizar(g);
            int w = 36, h = 20, x = 2, y = (getHeight() - h) / 2;
            g.setColor(!isEnabled() ? Tema.BORDE : isSelected() ? Tema.AZUL : new Color(0xC9CFD6));
            g.fillRoundRect(x, y, w, h, h, h);
            g.setColor(Tema.BLANCO);
            int d = h - 6;
            g.fillOval(isSelected() ? x + w - d - 3 : x + 3, y + 3, d, d);
            if (isFocusOwner()) {
                g.setColor(Tema.AZUL_PROFUNDO);
                g.setStroke(new BasicStroke(2f));
                g.drawRoundRect(x - 1, y - 1, w + 2, h + 2, h + 2, h + 2);
            }
            g.dispose();
        }
    }
}
