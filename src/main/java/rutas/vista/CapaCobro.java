package rutas.vista;

import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.event.KeyEvent;
import java.awt.print.PrinterException;
import java.awt.print.PrinterJob;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongConsumer;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import rutas.logica.Carrito;
import rutas.logica.Cobro;
import rutas.logica.ImportadorProductos;
import rutas.logica.Tarifa;
import rutas.modelo.Cliente;
import rutas.modelo.Geo;
import rutas.modelo.LineaVenta;
import rutas.modelo.MetodoPago;
import rutas.modelo.Parada;
import rutas.modelo.Pago;

/**
 * Cobro de la venta, encima de la pantalla: total grande, medio de pago en botones,
 * billetes que suman, vueltas en grande y, al terminar, la pantalla de fin con el
 * numero de la venta, las vueltas y los botones para seguir.
 */
final class CapaCobro {

    private static final String COMBINADO = "Combinado";
    private static final long[] BILLETES = {500, 1000, 2000, 5000, 10_000, 20_000, 50_000, 100_000};

    private final JComponent dentroDe;
    private final Aplicacion app;
    private final Carrito carrito;
    private final Long idEspera;
    private final LongConsumer alCobrar;
    private final long total;

    private final JPanel modal = new JPanel(new java.awt.BorderLayout()) {
        @Override
        public Dimension getPreferredSize() {
            if (viendoDomicilio) { // la venta a domicilio ocupa casi toda la ventana
                javax.swing.JRootPane r = javax.swing.SwingUtilities.getRootPane(dentroDe);
                if (r != null) {
                    return new Dimension(r.getWidth() - 24, r.getHeight() - 24);
                }
            }
            return super.getPreferredSize();
        }
    };
    private final CardLayout cuerpos = new CardLayout();
    private final JPanel cuerpo = new JPanel(cuerpos);
    private final List<Tema.Boton> botonesMetodo = new ArrayList<>();
    private String modo = MetodoPago.EFECTIVO.name();
    private final List<MetodoPago> activos;
    private boolean compacto;   // los que acepta la tienda (Configuración › Medios de pago)

    private final JTextField recibido = campoPlata("Digite el valor pagado");
    private final JTextField valorOtro = campoPlata("Valor pagado");
    private final JComboBox<MetodoPago> metodo1 = new JComboBox<>(MetodoPago.values());
    private final JComboBox<MetodoPago> metodo2 = new JComboBox<>(MetodoPago.values());
    private final JTextField valor1 = campoPlata("Primer pago");
    private final JTextField valor2 = campoPlata("Segundo pago");
    private boolean valor2Escrito;
    private boolean llenando;

    private final JLabel resultado = new JLabel(" ");
    private final JTextField cliente = Tema.campo("Buscar por nombre, cédula o celular");
    private final JLabel lblCliente = new JLabel(" ");
    private Cliente elegido;      // cliente escogido de la lista (o recien creado)
    private boolean poniendo;     // se esta escribiendo el nombre del elegido: no se busca
    private final DefaultListModel<Cliente> sugerencias = new DefaultListModel<>();
    private final JList<Cliente> listaSugerencias = new JList<>(sugerencias);
    private final Tema.Boton cobrar;
    private final Tema.Boton volver = Tema.botonSecundario("Volver a la venta");
    private Capa capa;
    private Cobro.Resultado cuenta;

    // ---- venta a domicilio ----
    private boolean domicilio;        // esta venta va a domicilio
    private boolean viendoDomicilio;  // se ve la pantalla grande: mapa, Ruta/Buscar y el pago
    private boolean contraEntrega;    // paga al recibir (si no, paga ahora y el repartidor cobra solo el domicilio)
    private boolean vendida;
    private Parada entrega;           // la entrega de esta venta en Ruta (null si falta la direccion)
    private final Object marca = new Object(); // las entregas que se agregan durante esta venta llevan esta marca
    private boolean procesando;       // ya se dio Procesar: no se cobra dos veces
    private boolean cerrada;
    private javax.swing.Timer timerProcesar;
    private final Runnable oyenteRuta = this::revisarEntrega;
    private JComponent zona;          // mapa + columna prestados por la ventana principal
    private JComponent izqNormal;
    private JComponent derNormal;
    private JComponent pieNormal;
    private JPanel totPie;
    private JPanel botonesPie;
    private int anchoIzq;
    private int anchoDer;
    private final Tema.Boton botonDomicilio = Tema.botonSecundario("Domicilio  (F4)");
    private final Tema.Boton quitarDomicilio = Tema.botonPlano("Quitar domicilio");
    private final JLabel lblDomicilio = new JLabel();
    private final JLabel lblEntrega = new JLabel();
    private final JLabel lblTotalDom = new JLabel(" ");
    private final JLabel leRecibido = new JLabel("Efectivo recibido");
    private final Tema.Boton pagaAhora = Tema.botonSecundario("Paga ahora");
    private final Tema.Boton pagaContra = Tema.botonSecundario("Contra entrega");
    private JComponent tarjetaEntrega;
    private Tema.Parrafo notaCliente;
    private long domicilioVendido;
    // ---- columna apretada del domicilio (sin barra): referencias para cambiar tamaños ----
    private final List<JLabel> etiquetas = new ArrayList<>();
    private Tema.Boton crearGrande;
    private final Tema.Boton crearMini = Tema.botonSecundario("+ Nuevo");
    private Component huecoCrear;
    private JPanel metodosPanel;
    private int filasMetodos;
    private boolean compactoBase;
    private JPanel billetesPanel;
    private final List<Tema.Boton> botonesBillete = new ArrayList<>();
    private final Tema.Boton usarSeleccionada = Tema.botonPlano("Usar la entrega marcada en la lista de Ruta");

    private CapaCobro(JComponent dentroDe, Aplicacion app, Carrito carrito, Long idEspera, String clienteInicial,
            LongConsumer alCobrar) {
        this.dentroDe = dentroDe;
        this.app = app;
        this.carrito = carrito;
        this.idEspera = idEspera;
        this.alCobrar = alCobrar;
        this.total = carrito.getTotal();
        this.cobrar = Tema.botonAccion("Procesar  (Enter)");
        List<MetodoPago> leidos;
        try {
            leidos = rutas.logica.MediosPago.activos(app.getTienda());
        } catch (java.sql.SQLException e) {
            leidos = List.of(MetodoPago.EFECTIVO, MetodoPago.NEQUI, MetodoPago.DAVIPLATA, MetodoPago.TRANSFERENCIA);
        }
        this.activos = leidos;
        metodo1.removeAllItems();
        metodo2.removeAllItems();
        for (MetodoPago m : activos) {
            metodo1.addItem(m);
            metodo2.addItem(m);
        }
        if (clienteInicial != null) {
            poniendo = true;
            cliente.setText(clienteInicial);
            poniendo = false;
            elegido = app.buscarCliente(clienteInicial);
        }
    }

    static void mostrar(JComponent dentroDe, Aplicacion app, Carrito carrito, Long idEspera, String clienteInicial,
            LongConsumer alCobrar) {
        new CapaCobro(dentroDe, app, carrito, idEspera, clienteInicial, alCobrar).abrir();
    }

    private static JTextField campoPlata(String nombre) {
        JTextField f = Tema.campo(nombre);
        f.setFont(Tema.subtitulo(18));
        f.setHorizontalAlignment(JTextField.RIGHT);
        f.getAccessibleContext().setAccessibleName(nombre);
        f.setToolTipText(nombre);
        return f;
    }

    private void abrir() {
        javax.swing.JRootPane raiz = javax.swing.SwingUtilities.getRootPane(dentroDe);
        int ancho = raiz == null ? 900 : Math.max(820, Math.min(940, raiz.getWidth() - 60));
        modal.setOpaque(false);
        anchoIzq = (int) ((ancho - 72) * 0.42);
        anchoDer = (int) ((ancho - 72) * 0.58);
        izqNormal = anchoFijo(columnaCliente(), () -> anchoIzq);
        mostrarElegido(); // si la venta venia con un cliente
        derNormal = anchoFijo(columnaPago(ancho), () -> anchoDer);
        pieNormal = pie();
        tarjetaEntrega = tarjetaEntrega();
        armarNormal();

        capa = new Capa(dentroDe, modal, true);
        capa.setAlEsc(() -> {
            if (viendoDomicilio) { // Esc en la pantalla del domicilio vuelve al pago (no cierra)
                volverAlPago();
                return true;
            }
            return false;
        });
        capa.setAlCerrar(this::alCerrar);
        cobrar.addActionListener(e -> procesar());
        volver.addActionListener(e -> capa.cerrar());
        capa.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "cobrar");
        capa.getActionMap().put("cobrar", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                Component foco = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
                boolean enRuta = viendoDomicilio && foco != null && javax.swing.SwingUtilities.isDescendingFrom(foco, zona);
                if (cobrar.isShowing() && cobrar.isEnabled() && !listaSugerencias.isFocusOwner() && !enRuta) {
                    procesar();
                }
            }
        });
        capa.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_F4, 0), "domicilio");
        capa.getActionMap().put("domicilio", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (vendida || !botonDomicilio.isEnabled() || !capa.esLaDeArriba()) {
                    return;
                }
                if (viendoDomicilio) {
                    volverAlPago();
                } else {
                    verDomicilio();
                }
            }
        });
        for (JTextField f : new JTextField[]{recibido, valorOtro, valor1, valor2}) {
            f.getDocument().addDocumentListener(escucha(this::calcular));
            // en la columna del domicilio (con barra), el campo y el resultado quedan a la vista
            f.addFocusListener(new java.awt.event.FocusAdapter() {
                @Override
                public void focusGained(java.awt.event.FocusEvent e) {
                    javax.swing.SwingUtilities.invokeLater(() -> {
                        resultado.scrollRectToVisible(new java.awt.Rectangle(0, 0, resultado.getWidth(),
                                resultado.getHeight() + 8));
                        f.scrollRectToVisible(new java.awt.Rectangle(0, 0, f.getWidth(), f.getHeight()));
                    });
                }
            });
            f.addActionListener(e -> {
                if (cobrar.isEnabled()) {
                    procesar();
                }
            });
        }
        valor1.getDocument().addDocumentListener(escucha(this::llenarSegundo));
        valor2.getDocument().addDocumentListener(escucha(() -> {
            if (!llenando) {
                valor2Escrito = true;
            }
        }));
        metodo1.addActionListener(e -> calcular());
        metodo2.addActionListener(e -> calcular());
        elegirModo(MetodoPago.EFECTIVO.name());
        actualizarDomicilio();
        capa.mostrar(recibido);
    }

    /** La ventana normal de "Información de pago": cliente a la izquierda, pago a la derecha, total abajo. */
    private void armarNormal() {
        modal.removeAll();
        modal.add(cabeza(), java.awt.BorderLayout.NORTH);
        JPanel cuerpoTotal = new JPanel(new java.awt.GridBagLayout());
        cuerpoTotal.setOpaque(false);
        cuerpoTotal.setBorder(BorderFactory.createEmptyBorder(16, 24, 8, 24));
        java.awt.GridBagConstraints g = new java.awt.GridBagConstraints();
        g.gridy = 0;
        g.anchor = java.awt.GridBagConstraints.NORTHWEST;
        g.fill = java.awt.GridBagConstraints.HORIZONTAL;
        g.weightx = 0.42;
        g.weighty = 1;
        g.insets = new java.awt.Insets(0, 0, 0, 24);
        cuerpoTotal.add(izqNormal, g);
        g.gridx = 1;
        g.weightx = 0.58;
        g.insets = new java.awt.Insets(0, 0, 0, 0);
        cuerpoTotal.add(derNormal, g);
        modal.add(cuerpoTotal, java.awt.BorderLayout.CENTER);
        acomodarPie(false);
        modal.add(pieNormal, java.awt.BorderLayout.SOUTH);
    }

    /** Envuelve un panel con ancho fijo y el alto que vaya necesitando (la lista de clientes crece). */
    private static JComponent anchoFijo(JComponent c, java.util.function.IntSupplier ancho) {
        JPanel p = new JPanel(new java.awt.BorderLayout()) {
            @Override
            public Dimension getPreferredSize() {
                return new Dimension(ancho.getAsInt(), c.getPreferredSize().height);
            }
        };
        p.setOpaque(false);
        p.add(c, java.awt.BorderLayout.CENTER);
        return p;
    }

    /** Franja azul de arriba: titulo y la X para cerrar. */
    private JComponent cabeza() {
        return cabeza("Información de pago");
    }

    private JComponent cabeza(String titulo) {
        return cabeza(titulo, null);
    }

    /** extra: algo que va a la izquierda de la X (por ejemplo "Volver al pago"). */
    private JComponent cabeza(String titulo, JComponent extra) {
        JPanel c = new JPanel(new java.awt.BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create();
                Tema.suavizar(g);
                g.setColor(Tema.AZUL);
                g.fillRoundRect(0, 0, getWidth(), getHeight() + Tema.ARCO, Tema.ARCO, Tema.ARCO);
                g.dispose();
            }
        };
        c.setOpaque(false);
        c.setBorder(BorderFactory.createEmptyBorder(9, 24, 9, 12));
        JLabel t = new JLabel(titulo);
        t.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 6)); // la ultima letra no se corta
        t.setFont(Tema.subtitulo(Tema.LETRA_VENTANA));
        t.setForeground(Tema.BLANCO);
        c.add(t, java.awt.BorderLayout.WEST);
        javax.swing.JButton x = new javax.swing.JButton() {
            @Override
            protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create();
                Tema.suavizar(g);
                if (getModel().isRollover() || isFocusOwner()) {
                    g.setColor(Tema.AZUL_PROFUNDO);
                    g.fillRoundRect(0, 0, getWidth(), getHeight(), Tema.ARCO, Tema.ARCO);
                }
                g.setColor(Tema.BLANCO);
                g.setStroke(new java.awt.BasicStroke(2.2f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
                int m = getWidth() / 2, k = 6;
                g.drawLine(m - k, getHeight() / 2 - k, m + k, getHeight() / 2 + k);
                g.drawLine(m + k, getHeight() / 2 - k, m - k, getHeight() / 2 + k);
                g.dispose();
            }
        };
        x.setPreferredSize(new Dimension(32, 32));
        x.setContentAreaFilled(false);
        x.setBorderPainted(false);
        x.setFocusPainted(false);
        x.setOpaque(false);
        x.setRolloverEnabled(true);
        x.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        x.setToolTipText("Cerrar y volver a la venta (Esc)");
        x.getAccessibleContext().setAccessibleName("Cerrar");
        x.addActionListener(e -> capa.cerrar());
        if (extra == null) {
            c.add(x, java.awt.BorderLayout.EAST);
        } else {
            JPanel der = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 8, 0));
            der.setOpaque(false);
            der.add(extra);
            der.add(x);
            c.add(der, java.awt.BorderLayout.EAST);
        }
        return c;
    }

    /** Izquierda: buscar el cliente (nombre, cédula o celular) o crearlo. */
    private JComponent columnaCliente() {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.add(etiqueta("Cliente"));
        cliente.setFont(Tema.texto(14));
        cliente.setBorder(BorderFactory.createCompoundBorder(new Tema.BordeRedondo(Tema.BORDE_CONTROL, Tema.RADIO),
                BorderFactory.createCompoundBorder(new IconoIzquierda(Iconos.de("lupa", 18, Tema.GRIS_TEXTO)),
                        BorderFactory.createEmptyBorder(9, 0, 9, 10))));
        cliente.setToolTipText("Escriba el nombre, la cédula o el celular. Si no lo escoge, la venta queda a nombre de lo escrito.");
        // buscador y, en el domicilio, "+ Nuevo" al lado (en vez de la barra grande de "Crear cliente")
        JPanel filaBuscar = new JPanel(new java.awt.BorderLayout(6, 0));
        filaBuscar.setOpaque(false);
        filaBuscar.setAlignmentX(Component.LEFT_ALIGNMENT);
        filaBuscar.add(cliente, java.awt.BorderLayout.CENTER);
        crearMini.setFont(Tema.textoFuerte(13));
        crearMini.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));
        crearMini.setToolTipText("Crear cliente nuevo (nombre, cédula, celular, dirección y fiado)");
        crearMini.getAccessibleContext().setAccessibleName("Crear cliente");
        crearMini.setVisible(false);
        filaBuscar.add(crearMini, java.awt.BorderLayout.EAST);
        filaBuscar.setMaximumSize(new Dimension(Integer.MAX_VALUE, cliente.getPreferredSize().height));
        p.add(filaBuscar);
        listaSugerencias.setFont(Tema.texto(13.5f));
        listaSugerencias.setVisibleRowCount(6);
        listaSugerencias.setFixedCellHeight(34);
        listaSugerencias.setCellRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean f) {
                JLabel r = (JLabel) super.getListCellRendererComponent(l, FormularioCliente.linea((Cliente) v), i, s, f);
                r.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10));
                r.setBackground(s ? Tema.AZUL_SUAVE : Tema.BLANCO);
                r.setForeground(Tema.GRIS_OSCURO);
                return r;
            }
        });
        listaSugerencias.getAccessibleContext().setAccessibleName("Clientes encontrados");
        listaSugerencias.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                elegirCliente();
            }
        });
        listaSugerencias.setVisible(false);
        listaSugerencias.setAlignmentX(Component.LEFT_ALIGNMENT);
        listaSugerencias.setBorder(Tema.bordeLista());
        p.add(listaSugerencias);
        lblCliente.setFont(Tema.texto(13));
        lblCliente.setForeground(Tema.GRIS_TEXTO);
        lblCliente.setAlignmentX(Component.LEFT_ALIGNMENT);
        lblCliente.setBorder(BorderFactory.createEmptyBorder(6, 2, 0, 0));
        p.add(lblCliente);
        cliente.getDocument().addDocumentListener(escucha(this::buscarClientes));
        cliente.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "bajarLista");
        cliente.getActionMap().put("bajarLista", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (listaSugerencias.isVisible() && sugerencias.size() > 0) {
                    listaSugerencias.setSelectedIndex(0);
                    listaSugerencias.requestFocusInWindow();
                }
            }
        });
        listaSugerencias.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "elegir");
        listaSugerencias.getActionMap().put("elegir", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                elegirCliente();
            }
        });
        huecoCrear = Tema.espacio(12);
        p.add(huecoCrear);
        Tema.Boton crear = Tema.botonPrimario("+  Crear cliente");
        crearGrande = crear;
        crear.setToolTipText("Registrar un cliente nuevo (nombre, cédula, celular, dirección y fiado)");
        crear.setAlignmentX(Component.LEFT_ALIGNMENT);
        crear.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
        crearMini.addActionListener(e -> crear.doClick());
        crear.addActionListener(e -> FormularioCliente.abrir(dentroDe, app, elegido == null ? cliente.getText() : "",
                c -> {
                    elegido = c;
                    poniendo = true;
                    cliente.setText(c.getNombre());
                    poniendo = false;
                    mostrarElegido();
                    clienteCambio();
                }));
        p.add(crear);
        // venta a domicilio: abre el mapa y la ruta sin perder lo que se lleva del pago
        p.add(hueco(8));
        botonDomicilio.setToolTipText("Enviar esta venta a domicilio: dirección, mapa, ruta y valor del domicilio (F4)");
        botonDomicilio.setAlignmentX(Component.LEFT_ALIGNMENT);
        botonDomicilio.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
        botonDomicilio.addActionListener(e -> verDomicilio());
        botonDomicilio.setVisible(app.getVentana() != null); // sin la ventana principal no hay mapa que mostrar
        p.add(botonDomicilio);
        lblDomicilio.setFont(Tema.texto(13));
        lblDomicilio.setAlignmentX(Component.LEFT_ALIGNMENT);
        lblDomicilio.setBorder(BorderFactory.createEmptyBorder(6, 2, 0, 0));
        p.add(lblDomicilio);
        quitarDomicilio.setFont(Tema.textoFuerte(12.5f));
        quitarDomicilio.setBorder(BorderFactory.createEmptyBorder(4, 2, 4, 6));
        quitarDomicilio.setAlignmentX(Component.LEFT_ALIGNMENT);
        quitarDomicilio.setToolTipText("La venta ya no va a domicilio (la entrega se quita de Ruta)");
        quitarDomicilio.addActionListener(e -> quitarDomicilio());
        p.add(quitarDomicilio);
        p.add(hueco(10));
        Tema.Parrafo nota = Tema.parrafo("El cliente es opcional. Sirve para el recibo, el fiado y los domicilios.", 320);
        nota.setFont(Tema.texto(12.5f));
        nota.setForeground(Tema.GRIS_TEXTO);
        nota.setAlignmentX(Component.LEFT_ALIGNMENT);
        notaCliente = nota;
        p.add(nota);
        JPanel envoltura = new JPanel(new java.awt.BorderLayout());
        envoltura.setOpaque(false);
        envoltura.add(p, java.awt.BorderLayout.NORTH);
        return envoltura;
    }

    /** Derecha: medio de pago, billetes, valor y vueltas. */
    private JComponent columnaPago(int ancho) {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.add(etiqueta("Método de pago"));
        // solo los medios que acepta la tienda (Configuración › Medios de pago)
        int botones = activos.size() + (activos.size() > 1 ? 1 : 0);
        int columnas = Math.min(4, botones);
        int filas = (botones + columnas - 1) / columnas;
        JPanel metodos = new JPanel(new GridLayout(filas, columnas, 6, 6));
        metodos.setOpaque(false);
        metodos.setAlignmentX(Component.LEFT_ALIGNMENT);
        compacto = filas > 1; // con muchos medios los botones son mas bajitos para que quepa todo
        for (MetodoPago m : activos) {
            metodos.add(botonMetodo(m.getCorto(), m.name()));
        }
        if (activos.size() > 1) { // combinado necesita al menos dos medios
            metodos.add(botonMetodo(COMBINADO, COMBINADO));
        }
        metodosPanel = metodos;
        filasMetodos = filas;
        compactoBase = compacto;
        int alto = compacto ? 54 : 70;
        metodos.setMaximumSize(new Dimension(Integer.MAX_VALUE, filas * alto + (filas - 1) * 6));
        metodos.setPreferredSize(new Dimension(300, filas * alto + (filas - 1) * 6));
        p.add(metodos);
        p.add(hueco(12));
        cuerpo.setOpaque(false);
        cuerpo.setAlignmentX(Component.LEFT_ALIGNMENT);
        cuerpo.add(cuerpoEfectivo(), MetodoPago.EFECTIVO.name());
        cuerpo.add(cuerpoOtro(), "OTRO");
        cuerpo.add(cuerpoCombinado(), COMBINADO);
        p.add(cuerpo);
        p.add(hueco(8));
        resultado.setFont(Tema.titulo(Tema.LETRA_CIFRA_MEDIA));
        resultado.setAlignmentX(Component.LEFT_ALIGNMENT);
        resultado.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        p.add(resultado);
        return p;
    }

    /** Abajo: total a pagar en grande y los botones. */
    private JComponent pie() {
        JPanel p = new JPanel(new java.awt.BorderLayout(16, 8));
        p.setOpaque(false);
        totPie = new JPanel();
        totPie.setOpaque(false);
        totPie.setLayout(new BoxLayout(totPie, BoxLayout.Y_AXIS));
        JLabel lt = new JLabel("TOTAL A PAGAR");
        lt.setFont(Tema.textoFuerte(13));
        lt.setForeground(Tema.AZUL_PROFUNDO);
        totPie.add(lt);
        JLabel v = new JLabel(Tarifa.pesos(total));
        v.setFont(Tema.titulo(Tema.LETRA_CIFRA));
        v.setForeground(Tema.GRIS_OSCURO);
        totPie.add(v);
        lblTotalDom.setFont(Tema.texto(12.5f));
        lblTotalDom.setForeground(Tema.GRIS_TEXTO);
        lblTotalDom.setVisible(false);
        totPie.add(lblTotalDom);
        cobrar.setFont(Tema.subtitulo(14.5f));
        JPanel bots = Tema.fila(10, volver, cobrar);
        botonesPie = new JPanel(new java.awt.GridBagLayout());
        botonesPie.setOpaque(false);
        botonesPie.add(bots);
        return p;
    }

    /** Normal: total a la izquierda y botones a la derecha. En el domicilio (columna angosta): uno debajo del otro. */
    private void acomodarPie(boolean angosto) {
        JPanel p = (JPanel) pieNormal;
        p.removeAll();
        p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Tema.BORDE),
                angosto ? BorderFactory.createEmptyBorder(10, 16, 12, 16) : BorderFactory.createEmptyBorder(12, 24, 16, 24)));
        cobrar.setPreferredSize(new Dimension(angosto ? 180 : 230, angosto ? 44 : 46));
        volver.setVisible(!angosto); // en el domicilio la X de arriba hace lo mismo (cerrar y volver a la venta)
        botonesPie.setSize(0, 0); // la fila de botones se mide de nuevo (si no, se queda partida en dos)
        botonesPie.getComponent(0).setSize(0, 0);
        botonesPie.getComponent(0).invalidate();
        botonesPie.invalidate();
        p.add(totPie, java.awt.BorderLayout.WEST);
        p.add(botonesPie, java.awt.BorderLayout.EAST);
    }

    /** "Procesar": el boton muestra que esta trabajando y luego cobra. */
    private void procesar() {
        if (procesando || vendida || cerrada) {
            return;
        }
        if (domicilio && entrega == null) {
            VentanaPrincipal v = app.getVentana();
            if (v != null && viendoDomicilio) {
                v.getPedidos().agregarLoEscrito(); // la direccion escrita (a la vista) y sin agregar, se agrega
            }
            if (entrega == null) {
                resultado.setForeground(Tema.ERROR_TEXTO);
                resultado.setFont(Tema.textoFuerte(14));
                resultado.setText("Falta la dirección de la entrega");
                verDomicilio();
                return;
            }
        }
        calcular();
        if (!cobrar.isEnabled() || !cuenta.isAlcanza()) {
            return;
        }
        procesando = true;
        cobrar.setEnabled(false);
        cobrar.setText("Procesando…");
        timerProcesar = new javax.swing.Timer(350, e -> {
            timerProcesar = null;
            procesando = false;
            cobrar.setText("Procesar  (Enter)");
            if (!cerrada && !vendida) {
                cobrar();
            }
        });
        timerProcesar.setRepeats(false);
        timerProcesar.start();
    }

    /** Relleno izquierdo de un campo con un icono (la lupa del buscador). */
    private static class IconoIzquierda extends javax.swing.border.AbstractBorder {
        private final javax.swing.Icon icono;

        IconoIzquierda(javax.swing.Icon icono) {
            this.icono = icono;
        }

        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int w, int h) {
            icono.paintIcon(c, g, x + 12, y + (h - icono.getIconHeight()) / 2);
        }

        @Override
        public java.awt.Insets getBorderInsets(Component c) {
            return new java.awt.Insets(0, 12 + icono.getIconWidth() + 8, 0, 0);
        }
    }

    private Tema.Boton botonMetodo(String texto, String clave) {
        Tema.Boton b = Tema.botonSecundario(texto);
        b.setFont(Tema.textoFuerte(compacto ? 12.5f : 13.5f));
        b.setBorder(compacto ? BorderFactory.createEmptyBorder(5, 4, 4, 4)
                : BorderFactory.createEmptyBorder(8, 6, 7, 6)); // "Transferencia" cabe completo
        b.setToolTipText(COMBINADO.equals(clave) ? "Pagar con dos medios" : MetodoPago.valueOf(clave).getDetalle());
        // logo de la forma de pago encima del nombre
        b.setIcon(new LogoPago("/imagenes/pagos/" + clave.toLowerCase() + ".png", compacto ? 22 : 30));
        b.setVerticalTextPosition(javax.swing.SwingConstants.BOTTOM);
        b.setHorizontalTextPosition(javax.swing.SwingConstants.CENTER);
        b.setIconTextGap(compacto ? 3 : 5);
        b.setActionCommand(clave);
        b.addActionListener(e -> elegirModo(clave));
        botonesMetodo.add(b);
        return b;
    }

    private void elegirModo(String clave) {
        modo = clave;
        for (Tema.Boton b : botonesMetodo) {
            b.setSelected(b.getActionCommand().equals(clave));
            b.getAccessibleContext().setAccessibleDescription(b.isSelected() ? "elegido" : null);
        }
        if (clave.equals(MetodoPago.EFECTIVO.name())) {
            cuerpos.show(cuerpo, clave);
            recibido.requestFocusInWindow();
        } else if (clave.equals(COMBINADO)) {
            cuerpos.show(cuerpo, COMBINADO);
            valor1.requestFocusInWindow();
        } else {
            cuerpos.show(cuerpo, "OTRO");
            valorOtro.setText(Tarifa.pesos(total).replace("$", ""));
            valorOtro.requestFocusInWindow();
        }
        calcular();
    }

    private JComponent cuerpoEfectivo() {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        // billetes que suman, como en la caja de verdad (3 por fila)
        JPanel billetes = new JPanel(new GridLayout(3, 3, 6, 6));
        billetes.setOpaque(false);
        billetes.setAlignmentX(Component.LEFT_ALIGNMENT);
        for (long b : BILLETES) {
            Tema.Boton bt = Tema.botonPequeno(Tarifa.pesos(b));
            botonesBillete.add(bt);
            bt.setFont(Tema.textoFuerte(13.5f));
            bt.setToolTipText("Suma " + Tarifa.pesos(b));
            bt.addActionListener(e -> {
                recibido.setText(Tarifa.pesos(leer(recibido) + b).replace("$", ""));
                recibido.requestFocusInWindow();
            });
            billetes.add(bt);
        }
        Tema.Boton exacto = Tema.botonPequeno("V. Exacto");
        exacto.setFont(Tema.textoFuerte(13.5f));
        exacto.setToolTipText("Paga justo el total");
        exacto.addActionListener(e -> {
            recibido.setText(Tarifa.pesos(total).replace("$", ""));
            recibido.requestFocusInWindow();
        });
        billetes.add(exacto);
        botonesBillete.add(exacto);
        billetesPanel = billetes;
        billetes.setMaximumSize(new Dimension(Integer.MAX_VALUE, 3 * 34 + 12));
        p.add(billetes);
        p.add(hueco(10));
        JPanel fila = new JPanel(new java.awt.BorderLayout(8, 0));
        fila.setOpaque(false);
        fila.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel le = leRecibido;
        le.setFont(Tema.textoFuerte(13.5f));
        le.setForeground(Tema.GRIS_OSCURO);
        fila.add(le, java.awt.BorderLayout.WEST);
        Tema.Boton borrar = Tema.botonPlano("Borrar");
        borrar.setFont(Tema.textoFuerte(12.5f));
        borrar.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        borrar.addActionListener(e -> {
            recibido.setText("");
            recibido.requestFocusInWindow();
        });
        fila.add(borrar, java.awt.BorderLayout.EAST);
        fila.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        p.add(fila);
        p.add(Tema.espacio(4));
        Tema.anchoCompleto(recibido);
        p.add(recibido);
        return p;
    }

    private JComponent cuerpoOtro() {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.add(Tema.etiqueta("Valor pagado"));
        Tema.anchoCompleto(valorOtro);
        p.add(valorOtro);
        p.add(Tema.espacio(6));
        Tema.Parrafo nota = Tema.parrafo("Revise en el celular que el pago sí llegó antes de cobrar.", 560);
        p.add(nota);
        return p;
    }

    private JComponent cuerpoCombinado() {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.add(Tema.etiqueta("Primer pago"));
        p.add(filaCombinado(metodo1, valor1));
        p.add(Tema.espacio(8));
        p.add(Tema.etiqueta("Segundo pago (se llena con lo que falta)"));
        // primero el que no es efectivo, despues el efectivo
        metodo1.setSelectedItem(activos.size() > 1 ? activos.get(1) : MetodoPago.EFECTIVO);
        metodo2.setSelectedItem(MetodoPago.EFECTIVO);
        p.add(filaCombinado(metodo2, valor2));
        return p;
    }

    private JComponent filaCombinado(JComboBox<MetodoPago> m, JTextField v) {
        Tema.estiloCombo(m, MetodoPago::getTexto);
        m.getAccessibleContext().setAccessibleName("Medio de pago");
        m.setPreferredSize(new Dimension(190, 44));
        JPanel f = new JPanel(new java.awt.BorderLayout(8, 0));
        f.setOpaque(false);
        f.setAlignmentX(Component.LEFT_ALIGNMENT);
        f.add(m, java.awt.BorderLayout.WEST);
        f.add(v, java.awt.BorderLayout.CENTER);
        f.setMaximumSize(new Dimension(Integer.MAX_VALUE, 48));
        return f;
    }

    private void llenarSegundo() {
        if (valor2Escrito) {
            return;
        }
        llenando = true;
        long falta = Math.max(0, total - leer(valor1));
        javax.swing.SwingUtilities.invokeLater(() -> {
            llenando = true;
            valor2.setText(falta == 0 ? "" : Tarifa.pesos(falta).replace("$", ""));
            llenando = false;
        });
    }

    /** Lee pesos del campo; vacio o con error cuenta como 0 (el error se muestra aparte). */
    private static long leer(JTextField f) {
        try {
            String t = f.getText().trim();
            return t.isEmpty() ? 0 : ImportadorProductos.leerPesos(t);
        } catch (IllegalArgumentException e) {
            return 0;
        }
    }

    private static String errorDe(JTextField f) {
        try {
            String t = f.getText().trim();
            if (!t.isEmpty()) {
                ImportadorProductos.leerPesos(t);
            }
            return null;
        } catch (IllegalArgumentException e) {
            return "Escriba solo el valor en pesos (ej. 20.000)";
        }
    }

    private List<Pago> pagos() {
        List<Pago> l = new ArrayList<>();
        if (domicilio && contraEntrega) { // la plata llega con el repartidor: se registra el efectivo justo
            l.add(new Pago(MetodoPago.EFECTIVO, total));
            return l;
        }
        if (modo.equals(MetodoPago.EFECTIVO.name())) {
            l.add(new Pago(MetodoPago.EFECTIVO, leer(recibido)));
        } else if (modo.equals(COMBINADO)) {
            l.add(new Pago((MetodoPago) metodo1.getSelectedItem(), leer(valor1)));
            l.add(new Pago((MetodoPago) metodo2.getSelectedItem(), leer(valor2)));
        } else {
            l.add(new Pago(MetodoPago.valueOf(modo), leer(valorOtro)));
        }
        return l;
    }

    private void calcular() {
        if (domicilio && contraEntrega) {
            calcularContraEntrega();
        } else {
            calcularNormal();
        }
        if (procesando || vendida) {
            cobrar.setEnabled(false); // mientras dice "Procesando…" no se puede dar otra vez
        }
    }

    private void calcularNormal() {
        String error = null;
        for (JTextField f : campoActivos()) {
            if (error == null) {
                error = errorDe(f);
            }
        }
        cuenta = Cobro.calcular(total, pagos());
        if (error != null || cuenta.getError() != null) {
            resultado.setForeground(Tema.ERROR_TEXTO);
            resultado.setFont(Tema.textoFuerte(14));
            resultado.setText(error != null ? error : cuenta.getError());
        } else if (!cuenta.isAlcanza()) {
            boolean vacio = pagos().stream().allMatch(p -> p.getValor() == 0);
            resultado.setFont(Tema.titulo(Tema.LETRA_CIFRA_MEDIA));
            resultado.setForeground(vacio ? Tema.GRIS_TEXTO : Tema.ERROR_TEXTO);
            resultado.setText(vacio ? "Toque los billetes o escriba el valor" : "Faltan " + Tarifa.pesos(cuenta.getFaltan()));
            if (vacio) {
                resultado.setFont(Tema.texto(14));
            }
        } else {
            resultado.setFont(Tema.titulo(Tema.LETRA_CIFRA_MEDIA));
            resultado.setForeground(Tema.EXITO_TEXTO);
            resultado.setText(cuenta.getVueltas() > 0 ? "Vueltas: " + Tarifa.pesos(cuenta.getVueltas()) : "Pago exacto");
        }
        cobrar.setEnabled(error == null && cuenta.isAlcanza());
    }

    private JTextField[] campoActivos() {
        if (modo.equals(MetodoPago.EFECTIVO.name())) {
            return new JTextField[]{recibido};
        }
        if (modo.equals(COMBINADO)) {
            return new JTextField[]{valor1, valor2};
        }
        return new JTextField[]{valorOtro};
    }

    /** Busca mientras se escribe: por nombre (arbol AVL, por prefijo), apellido, cédula o celular. */
    private void buscarClientes() {
        if (poniendo) {
            return;
        }
        elegido = null;
        sugerencias.clear();
        String t = cliente.getText().trim();
        if (t.length() >= 2) {
            java.util.LinkedHashSet<Cliente> hallados = new java.util.LinkedHashSet<>();
            if (!t.replaceAll("[\\s.-]", "").matches("\\d+")) {
                hallados.addAll(app.getClientes().buscarPorPrefijo(Geo.normalizar(t), 6)); // rapido por el nombre
            }
            if (hallados.size() < 6) {
                for (Cliente c : app.getClientes().enOrden()) {
                    if (hallados.size() >= 6) {
                        break;
                    }
                    if (c.isActivo() && FormularioCliente.coincide(c, t)) {
                        hallados.add(c);
                    }
                }
            }
            for (Cliente c : hallados) {
                if (c.isActivo()) { // los inactivos no salen para escoger
                    sugerencias.addElement(c);
                }
            }
        }
        listaSugerencias.setVisible(!sugerencias.isEmpty());
        lblCliente.setForeground(Tema.GRIS_TEXTO);
        lblCliente.setVisible(!viendoDomicilio || t.length() >= 2 && sugerencias.isEmpty());
        lblCliente.setText(t.length() >= 2 && sugerencias.isEmpty()
                ? "No hay clientes con «" + t + "». Créelo o siga sin cliente." : " ");
        capa.reacomodar();
    }

    private void elegirCliente() {
        Cliente c = listaSugerencias.getSelectedValue();
        if (c != null) {
            elegido = c;
            poniendo = true;
            cliente.setText(c.getNombre());
            poniendo = false;
            sugerencias.clear();
            listaSugerencias.setVisible(false);
            mostrarElegido();
            capa.reacomodar();
            (modo.equals(MetodoPago.EFECTIVO.name()) ? recibido : cobrar).requestFocusInWindow();
            clienteCambio();
        }
    }

    /** Debajo del buscador: documento, celular y tope de fiado del cliente escogido. */
    private void mostrarElegido() {
        if (elegido == null) {
            lblCliente.setText(" ");
            return;
        }
        List<String> d = new ArrayList<>();
        if (!elegido.documentoCorto().isEmpty()) {
            d.add(elegido.documentoCorto());
        }
        if (!elegido.getTelefono().isEmpty()) {
            d.add("Cel. " + elegido.getTelefono());
        }
        if (elegido.getCreditoMaximo() > 0) {
            d.add("fía hasta " + Tarifa.pesos(elegido.getCreditoMaximo()));
        }
        lblCliente.setForeground(Tema.EXITO_TEXTO);
        lblCliente.setText("Cliente escogido" + (d.isEmpty() ? "" : "  ·  " + String.join("  ·  ", d)));
    }

    // ------------------------------------------------------------------
    //  Venta a domicilio: el mapa y la columna de Ruta/Buscar al lado del pago
    // ------------------------------------------------------------------

    /** En el domicilio, entre el cliente y el pago: a donde va, cuanto vale el domicilio y como paga. */
    private JComponent tarjetaEntrega() {
        JPanel t = new JPanel();
        t.setOpaque(false);
        t.setLayout(new BoxLayout(t, BoxLayout.Y_AXIS));
        t.setAlignmentX(Component.LEFT_ALIGNMENT);
        JPanel cab = new JPanel(new java.awt.BorderLayout());
        cab.setOpaque(false);
        cab.setAlignmentX(Component.LEFT_ALIGNMENT);
        cab.add(etiqueta("Entrega"), java.awt.BorderLayout.WEST);
        Tema.Boton quitar = Tema.botonPlano("Quitar domicilio");
        quitar.setFont(Tema.textoFuerte(12.5f));
        quitar.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 2));
        quitar.setToolTipText("La venta ya no va a domicilio (la entrega se quita de Ruta)");
        quitar.addActionListener(e -> quitarDomicilio());
        cab.add(quitar, java.awt.BorderLayout.EAST);
        cab.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        t.add(cab);
        lblEntrega.setFont(Tema.texto(13));
        lblEntrega.setForeground(Tema.GRIS_OSCURO);
        lblEntrega.setAlignmentX(Component.LEFT_ALIGNMENT);
        t.add(lblEntrega);
        // una entrega que ya estaba en Ruta (agregada antes de cobrar) se puede tomar para esta venta
        usarSeleccionada.setFont(Tema.textoFuerte(12.5f));
        usarSeleccionada.setBorder(BorderFactory.createEmptyBorder(3, 0, 3, 4));
        usarSeleccionada.setAlignmentX(Component.LEFT_ALIGNMENT);
        usarSeleccionada.setToolTipText("Haga clic en la entrega en la lista de Ruta y luego aquí");
        usarSeleccionada.addActionListener(e -> usarEntregaMarcada());
        t.add(usarSeleccionada);
        t.add(Tema.espacio(6));
        JPanel formas = new JPanel(new GridLayout(1, 2, 6, 0));
        formas.setOpaque(false);
        formas.setAlignmentX(Component.LEFT_ALIGNMENT);
        pagaAhora.setToolTipText("Paga los productos ahora; el repartidor cobra solo el domicilio");
        pagaContra.setToolTipText("Paga todo al recibir, en efectivo; el repartidor lleva el cambio");
        pagaAhora.addActionListener(e -> ponerContraEntrega(false));
        pagaContra.addActionListener(e -> ponerContraEntrega(true));
        formas.add(pagaAhora);
        formas.add(pagaContra);
        for (Tema.Boton b : new Tema.Boton[]{pagaAhora, pagaContra}) {
            b.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        }
        formas.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        formas.setPreferredSize(new Dimension(200, 34));
        t.add(formas);
        return t;
    }

    /** F4: la pantalla grande del domicilio (el pago sigue igual, a la derecha). */
    private void verDomicilio() {
        VentanaPrincipal v = app.getVentana();
        if (v == null || viendoDomicilio || vendida) {
            return;
        }
        if (!domicilio) {
            domicilio = true;
            entrega = null;
            app.setVentaEnCurso(marca); // lo que se agregue en Ruta desde ahora es de esta venta
            app.alCambiar(oyenteRuta);
            v.getPedidos().limpiarCampos(); // lo escrito antes en Ruta no es de esta venta
        }
        zona = v.prestarDomicilio();
        viendoDomicilio = true;
        javax.swing.JRootPane r = javax.swing.SwingUtilities.getRootPane(dentroDe);
        int anchoCol = r == null ? 440 : Math.max(400, Math.min(480, (int) (r.getWidth() * 0.3)));
        anchoIzq = anchoCol - 34;
        anchoDer = anchoCol - 34;
        modal.removeAll();
        javax.swing.JButton alPago = botonCabeza("Volver al pago  (Esc)");
        alPago.addActionListener(e -> volverAlPago());
        modal.add(cabeza("Venta a domicilio", alPago), java.awt.BorderLayout.NORTH);
        JPanel centro = new JPanel(new java.awt.BorderLayout());
        centro.setOpaque(false);
        centro.add(zona, java.awt.BorderLayout.CENTER);
        JPanel pila = new PilaAjustada();
        pila.setBorder(BorderFactory.createEmptyBorder(6, 16, 6, 16));
        izqNormal.setAlignmentX(Component.LEFT_ALIGNMENT); // todo pegado a la izquierda y del mismo ancho
        derNormal.setAlignmentX(Component.LEFT_ALIGNMENT);
        pila.add(izqNormal);
        pila.add(Tema.espacio(4));
        pila.add(tarjetaEntrega);
        pila.add(Tema.espacio(2));
        pila.add(derNormal);
        JScrollPane sc = Tema.scrollSinBorde(pila);
        sc.getViewport().setBackground(Tema.BLANCO);
        sc.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        JPanel col = new JPanel(new java.awt.BorderLayout());
        col.setOpaque(false);
        col.setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, Tema.BORDE));
        col.add(sc, java.awt.BorderLayout.CENTER);
        acomodarPie(true);
        col.add(pieNormal, java.awt.BorderLayout.SOUTH);
        col.setPreferredSize(new Dimension(anchoCol, 10));
        centro.add(col, java.awt.BorderLayout.EAST);
        modal.add(centro, java.awt.BorderLayout.CENTER);
        actualizarDomicilio();
        // tamaño normal si cabe sin barra (pantallas altas); si no, la version apretada
        int disponible = modal.getPreferredSize().height - modal.getComponent(0).getPreferredSize().height
                - pieNormal.getPreferredSize().height - 8;
        ajustarDensidad(false);
        actualizarDomicilio();
        pila.invalidate();
        if (pila.getPreferredSize().height > disponible) {
            ajustarDensidad(true);
            actualizarDomicilio();
        }
        capa.reacomodar();
        // la entrega: el cliente escogido con direccion se agrega de una; si no, su nombre queda escrito
        if (entrega == null) {
            if (elegido != null) {
                v.getPedidos().entregaDeCliente(elegido);
            } else if (!cliente.getText().isBlank()) {
                v.getPedidos().escribirCliente(cliente.getText().trim());
            }
        }
        javax.swing.SwingUtilities.invokeLater(() -> {
            if (entrega != null) {
                (modo.equals(MetodoPago.EFECTIVO.name()) ? recibido : cobrar).requestFocusInWindow();
            } else {
                v.getPedidos().enfocarDireccion();
                // la columna del pago empieza arriba (cliente y entrega a la vista)
                javax.swing.Timer arriba = new javax.swing.Timer(200, e -> sc.getVerticalScrollBar().setValue(0));
                arriba.setRepeats(false);
                arriba.start();
            }
        });
    }

    /** Esc o "Volver al pago": la ventana normal, con el domicilio todavia puesto. */
    private void volverAlPago() {
        if (!viendoDomicilio) {
            return;
        }
        viendoDomicilio = false;
        VentanaPrincipal v = app.getVentana();
        if (v != null) {
            v.devolverDomicilio();
        }
        javax.swing.JRootPane raiz = javax.swing.SwingUtilities.getRootPane(dentroDe);
        int ancho = raiz == null ? 900 : Math.max(820, Math.min(940, raiz.getWidth() - 60));
        anchoIzq = (int) ((ancho - 72) * 0.42);
        anchoDer = (int) ((ancho - 72) * 0.58);
        ajustarDensidad(false);
        armarNormal();
        actualizarDomicilio();
        capa.reacomodar();
        javax.swing.SwingUtilities.invokeLater(() ->
                (modo.equals(MetodoPago.EFECTIVO.name()) ? recibido : cobrar).requestFocusInWindow());
    }

    /** La venta ya no va a domicilio: su entrega sale de Ruta. */
    private void quitarDomicilio() {
        if (viendoDomicilio) {
            volverAlPago();
        }
        domicilio = false;
        app.quitarOyente(oyenteRuta);
        quitarEntregaPropia();
        ponerContraEntrega(false);
        actualizarDomicilio();
        calcular();
        capa.reacomodar();
    }

    /** Quita de Ruta solo las entregas de esta venta (las de otros pedidos siguen). */
    private void quitarEntregaPropia() {
        entrega = null;
        app.setVentaEnCurso(null);
        List<Parada> l = app.getEntregas();
        for (int i = l.size() - 1; i >= 0; i--) {
            if (l.get(i).getVenta() == marca) {
                app.quitarEntrega(i);
            }
        }
    }

    /** Al cerrar el cobro: el mapa vuelve a su sitio y, si no se vendio, la entrega sale de Ruta. */
    private void alCerrar() {
        cerrada = true;
        if (timerProcesar != null) {
            timerProcesar.stop(); // Esc o X justo despues de Procesar: no se cobra
            timerProcesar = null;
        }
        if (viendoDomicilio) {
            viendoDomicilio = false;
            VentanaPrincipal v = app.getVentana();
            if (v != null) {
                v.devolverDomicilio();
            }
        }
        app.quitarOyente(oyenteRuta);
        if (!(!vendida && domicilio)) {
            app.setVentaEnCurso(null);
        }
        if (!vendida && domicilio) {
            domicilio = false;
            boolean habia = false;
            for (Parada p : app.getEntregas()) {
                habia |= p.getVenta() == marca;
            }
            quitarEntregaPropia();
            if (habia) {
                Aviso.mostrar(dentroDe, Aviso.Tipo.AVISO, "Domicilio cancelado", "La entrega se quitó de Ruta");
            }
        }
    }

    /** Ruta avisa un cambio: la entrega de esta venta es la ultima que se agrego desde que se abrio el domicilio. */
    private void revisarEntrega() {
        if (!domicilio || vendida) {
            return;
        }
        Parada nueva = null;
        for (Parada p : app.getEntregas()) {
            if (p.getVenta() == marca) {
                nueva = p; // si se agregaron varias, la ultima
            }
        }
        entrega = nueva;
        // si se escribio el cliente en Ruta y no en el pago, el recibo tambien lo lleva
        if (entrega != null && cliente.getText().isBlank() && !entrega.getNombre().equals(entrega.getDireccion())) {
            poniendo = true;
            cliente.setText(entrega.getNombre());
            poniendo = false;
            elegido = app.buscarCliente(entrega.getNombre());
            mostrarElegido();
        }
        actualizarDomicilio();
        calcular();
    }

    /** Se escogio o creo un cliente: si la venta va a domicilio y falta la entrega, se agrega con su direccion. */
    private void clienteCambio() {
        VentanaPrincipal v = app.getVentana();
        if (domicilio && entrega == null && elegido != null && v != null) {
            if (viendoDomicilio) {
                v.getPedidos().entregaDeCliente(elegido);
            } else {
                verDomicilio(); // la entrega se agrega con el mapa a la vista (por si hay que marcarla)
            }
        }
    }

    private void ponerContraEntrega(boolean b) {
        contraEntrega = b;
        pagaAhora.setSelected(!b);
        pagaContra.setSelected(b);
        for (Tema.Boton m : botonesMetodo) { // contra entrega es en efectivo
            m.setEnabled(!b || m.getActionCommand().equals(MetodoPago.EFECTIVO.name()));
        }
        leRecibido.setText(b ? "Paga con (para llevar el cambio)" : "Efectivo recibido");
        if (b && !modo.equals(MetodoPago.EFECTIVO.name())) {
            elegirModo(MetodoPago.EFECTIVO.name());
        }
        actualizarDomicilio();
        calcular();
    }

    /** Textos y botones del domicilio segun lo que haya (ventana normal o pantalla grande). */
    private void actualizarDomicilio() {
        boolean hayVentana = app.getVentana() != null;
        botonDomicilio.setText(domicilio ? "Ver domicilio  (F4)" : "Domicilio  (F4)");
        botonDomicilio.setVisible(hayVentana && !viendoDomicilio);
        quitarDomicilio.setVisible(domicilio && !viendoDomicilio);
        lblDomicilio.setVisible(domicilio && !viendoDomicilio);
        if (notaCliente != null) {
            notaCliente.setVisible(!viendoDomicilio);
        }
        pagaAhora.setSelected(!contraEntrega);
        pagaContra.setSelected(contraEntrega);
        int dom = entrega == null ? 0 : app.domicilioDe(entrega);
        if (entrega == null) {
            lblDomicilio.setForeground(Tema.AVISO_TEXTO);
            lblDomicilio.setText("A domicilio: falta la dirección de la entrega");
            lblEntrega.setText(html("<span style='color:#5B6670'>Escriba la dirección en «Agregar entrega»<br>"
                    + "o escoja un cliente con la dirección guardada.</span>"));
        } else {
            lblDomicilio.setForeground(Tema.AZUL_PROFUNDO);
            lblDomicilio.setText(html("<b>A domicilio:</b> " + esc(entrega.getDireccion()) + "  ·  domicilio "
                    + Tarifa.pesos(dom)));
            String cel = entrega.getTelefono().isEmpty() ? "" : "  ·  Cel. " + esc(entrega.getTelefono());
            String quien = entrega.getNombre().equals(entrega.getDireccion()) ? "" : "<b>" + esc(entrega.getNombre())
                    + "</b>" + cel + "<br>";
            lblEntrega.setText(html(quien + esc(entrega.getDireccion())
                    + "  ·  <span style='color:#003A73'><b>Domicilio " + (dom > 0 ? Tarifa.pesos(dom) : "sin calcular")
                    + "</b></span>"));
        }
        usarSeleccionada.setVisible(domicilio && entrega == null && !app.getEntregas().isEmpty());
        // en el domicilio el renglon "Cliente escogido · CC · Cel." sobra si ya esta la entrega (alli va el celular)
        lblCliente.setVisible(!viendoDomicilio || !(entrega != null && elegido != null || lblCliente.getText().isBlank()));
        lblTotalDom.setVisible(domicilio && entrega != null);
        lblTotalDom.setText(dom == 0 ? "+ domicilio sin calcular (revise el punto en el mapa)" : contraEntrega
                ? "+ domicilio " + Tarifa.pesos(dom) + " = " + Tarifa.pesos(total + dom)
                : "+ domicilio " + Tarifa.pesos(dom) + " (repartidor)");
        lblTotalDom.setToolTipText(contraEntrega ? "El repartidor cobra todo al entregar"
                : "El domicilio lo cobra el repartidor al entregar");
        modal.revalidate();
        modal.repaint();
    }

    /** Texto en renglones (cortados a mano: el ancho en px del HTML de Swing cambia con la escala de Windows). */
    private static String html(String cuerpo) {
        return "<html>" + cuerpo + "</html>";
    }

    private static String esc(String t) {
        return t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Contra entrega: no se recibe plata ahora; se dice cuanto cobrar en la puerta y el cambio que se lleva. */
    private void calcularContraEntrega() {
        long dom = entrega == null ? 0 : app.domicilioDe(entrega);
        long puerta = total + dom;
        String error = errorDe(recibido);
        long con = leer(recibido);
        cuenta = Cobro.calcular(total, List.of(new Pago(MetodoPago.EFECTIVO, total)));
        resultado.setFont(Tema.textoFuerte(15));
        if (error != null) {
            resultado.setForeground(Tema.ERROR_TEXTO);
            resultado.setText(error);
            cobrar.setEnabled(false);
        } else if (con > 0 && con < puerta) {
            resultado.setForeground(Tema.ERROR_TEXTO);
            resultado.setText("Con " + Tarifa.pesos(con) + " no alcanza: son " + Tarifa.pesos(puerta));
            cobrar.setEnabled(false);
        } else {
            resultado.setForeground(Tema.AZUL_PROFUNDO);
            resultado.setText("Cobrar al entregar " + Tarifa.pesos(puerta)
                    + (con > puerta ? "  ·  cambio " + Tarifa.pesos(con - puerta) : ""));
            cobrar.setEnabled(true);
        }
    }

    /**
     * Despues de guardar la venta: la entrega en Ruta queda con lo que debe cobrar el repartidor,
     * y se arman los renglones del recibo para el.
     */
    private List<String> cerrarEntrega(long numero, List<LineaVenta> lineas) {
        Parada e = entrega;
        e.setVentaNumero(numero); // el domiciliario sabe qué bolsa es de quién
        e.setProductos((int) Comprobante.piezas(lineas));
        if (e.getTelefono().isEmpty() && elegido != null) {
            e.setTelefono(elegido.getTelefono());
        }
        int dom = app.domicilioDe(e);
        long con = contraEntrega ? leer(recibido) : 0;
        app.setVentaEnCurso(null);
        // si en Ruta marcaron "ya pagó por transferencia" se respeta (el repartidor no cobra nada)
        e.setValorPedido(contraEntrega ? (int) total : 0); // pago ahora: el repartidor cobra solo el domicilio
        e.setPagaCon((int) con);
        app.quitarOyente(oyenteRuta);
        app.avisar();
        List<String> l = new ArrayList<>();
        if (!e.getNombre().equals(e.getDireccion())) {
            l.add("*" + e.getNombre());
        }
        l.add(e.getDireccion());
        if (!e.getTelefono().isEmpty()) {
            l.add("Cel. " + e.getTelefono());
        }
        l.add("Domicilio: " + Tarifa.pesos(dom));
        if (contraEntrega) {
            l.add("*CONTRA ENTREGA: cobrar " + Tarifa.pesos(total + dom));
            if (con > total + dom) {
                l.add("Paga con " + Tarifa.pesos(con) + " · llevar cambio " + Tarifa.pesos(con - total - dom));
            }
        } else {
            l.add("*Productos pagados. Cobrar solo el domicilio: " + Tarifa.pesos(dom));
        }
        return l;
    }

    private final java.util.Map<Box.Filler, Integer> huecos = new java.util.LinkedHashMap<>();

    /** Espacio vertical que se achica en la columna apretada del domicilio. */
    private Component hueco(int alto) {
        Box.Filler f = (Box.Filler) Box.createVerticalStrut(alto);
        huecos.put(f, alto);
        return f;
    }

    private JLabel etiqueta(String texto) {
        JLabel l = Tema.etiqueta(texto);
        etiquetas.add(l);
        return l;
    }

    /**
     * En el domicilio la columna del pago es angosta y debe caber sin barra: lo mismo, mas apretado
     * (alto de filas, rellenos y espacios juntos; la letra no se achica). false = tamaños normales.
     */
    private void ajustarDensidad(boolean apretado) {
        for (JLabel l : etiquetas) {
            l.setBorder(apretado ? BorderFactory.createEmptyBorder(3, 0, 2, 0) : BorderFactory.createEmptyBorder(8, 0, 4, 0));
        }
        crearGrande.setVisible(!apretado);
        huecoCrear.setVisible(!apretado);
        crearMini.setVisible(apretado);
        boolean chico = apretado || compactoBase;
        // apretado: logo encima del nombre con su espacio (antes, a 42 px, el logo se cortaba arriba)
        int alto = apretado ? 52 : compactoBase ? 54 : 70;
        for (Tema.Boton b : botonesMetodo) {
            b.setFont(Tema.textoFuerte(chico ? 12.5f : 13.5f));
            b.setBorder(apretado ? BorderFactory.createEmptyBorder(5, 4, 5, 4) : compactoBase
                    ? BorderFactory.createEmptyBorder(5, 4, 4, 4) : BorderFactory.createEmptyBorder(8, 6, 7, 6));
            b.setIcon(new LogoPago("/imagenes/pagos/" + b.getActionCommand().toLowerCase() + ".png",
                    apretado ? 20 : compactoBase ? 22 : 30));
            b.setIconTextGap(chico ? 3 : 5);
        }
        int hMetodos = filasMetodos * alto + (filasMetodos - 1) * 6;
        metodosPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, hMetodos));
        metodosPanel.setPreferredSize(new Dimension(300, hMetodos));
        GridLayout gb = (GridLayout) billetesPanel.getLayout();
        gb.setHgap(apretado ? 4 : 6);
        gb.setVgap(apretado ? 4 : 6);
        for (Tema.Boton b : botonesBillete) {
            b.setBorder(apretado ? BorderFactory.createEmptyBorder(3, 8, 3, 8) : BorderFactory.createEmptyBorder(7, 12, 7, 12));
        }
        int hBilletes = apretado ? 3 * 26 + 8 : 3 * 34 + 12;
        billetesPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, hBilletes));
        billetesPanel.setPreferredSize(new Dimension(300, hBilletes));
        resultado.setMaximumSize(new Dimension(Integer.MAX_VALUE, apretado ? 28 : 36));
        for (java.util.Map.Entry<Box.Filler, Integer> h : huecos.entrySet()) {
            Dimension d = new Dimension(0, apretado ? 2 : h.getValue());
            h.getKey().changeShape(d, d, new Dimension(Short.MAX_VALUE, d.height));
        }
    }

    /** Toma para esta venta la entrega que se marco (clic) en la lista de Ruta, si no es de otra venta. */
    private void usarEntregaMarcada() {
        VentanaPrincipal v = app.getVentana();
        Parada p = v == null ? null : v.getPedidos().getSeleccionada();
        if (p == null) {
            lblEntrega.setText(html("<span style='color:#8A4B00'>Primero haga clic en la entrega en la lista<br>"
                    + "«Entregas» de Ruta y luego aquí.</span>"));
            return;
        }
        if (p.getVenta() != null && p.getVenta() != marca) {
            lblEntrega.setText(html("<span style='color:#8A4B00'>Esa entrega es de otra venta.</span>"));
            return;
        }
        p.setVenta(marca);
        revisarEntrega();
    }

    /** Boton blanco sobre la franja azul (sin fondo; al pasar el mouse, azul profundo). */
    private static javax.swing.JButton botonCabeza(String texto) {
        javax.swing.JButton b = new javax.swing.JButton(texto) {
            @Override
            protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create();
                Tema.suavizar(g);
                if (getModel().isRollover() || isFocusOwner()) {
                    g.setColor(Tema.AZUL_PROFUNDO);
                    g.fillRoundRect(0, 0, getWidth(), getHeight(), Tema.ARCO, Tema.ARCO);
                }
                g.dispose();
                super.paintComponent(g0);
            }
        };
        b.setFont(Tema.textoFuerte(13));
        b.setForeground(Tema.BLANCO);
        b.setContentAreaFilled(false);
        b.setBorderPainted(false);
        b.setFocusPainted(false);
        b.setOpaque(false);
        b.setRolloverEnabled(true);
        b.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        b.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        return b;
    }

    /** Panel que toma el ancho del scroll (sin barra horizontal). */
    private static class PilaAjustada extends JPanel implements javax.swing.Scrollable {
        PilaAjustada() {
            setOpaque(false);
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        }

        public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        public int getScrollableUnitIncrement(java.awt.Rectangle r, int o, int d) { return 16; }
        public int getScrollableBlockIncrement(java.awt.Rectangle r, int o, int d) { return r.height - 40; }
        public boolean getScrollableTracksViewportWidth() { return true; }
        public boolean getScrollableTracksViewportHeight() { return false; }
    }

    // ------------------------------------------------------------------
    //  Cobrar y pantalla de fin
    // ------------------------------------------------------------------

    private void cobrar() {
        if (vendida || cerrada) {
            return;
        }
        calcular();
        if (!cuenta.isAlcanza()) {
            return;
        }
        if (domicilio && entrega == null) { // la entrega se quito mientras procesaba
            resultado.setForeground(Tema.ERROR_TEXTO);
            resultado.setFont(Tema.textoFuerte(14));
            resultado.setText("Falta la dirección de la entrega");
            return;
        }
        cobrar.setEnabled(false);
        List<Pago> pagos = pagos();
        List<LineaVenta> lineas = new ArrayList<>(carrito.getLineas());
        String nombreCliente = cliente.getText().trim();
        long numero;
        try {
            numero = app.getTienda().cobrar(idEspera, app.getSesion().getUsuario().getId(), nombreCliente,
                    carrito, pagos, cuenta);
        } catch (Exception e) {
            rutas.logica.Ubicaciones.registrarError(e);
            calcular();
            Alerta.mostrar(dentroDe, Alerta.Tipo.AVISO, "No se pudo guardar la venta",
                    "No se cobró nada. Intente de nuevo. Detalle: " + e.getMessage(), null);
            return;
        }
        // de aqui en adelante la venta YA quedo guardada: un error no puede volver a habilitar Cobrar
        vendida = true;
        List<String> datosDomicilio = null;
        if (domicilio && entrega != null) {
            try {
                domicilioVendido = app.domicilioDe(entrega);
                datosDomicilio = cerrarEntrega(numero, lineas);
            } catch (RuntimeException e) {
                rutas.logica.Ubicaciones.registrarError(e);
            }
        }
        if (viendoDomicilio) { // el mapa vuelve a su sitio antes de mostrar el fin
            viendoDomicilio = false;
            VentanaPrincipal v = app.getVentana();
            if (v != null) {
                v.devolverDomicilio();
            }
        }
        boolean contra = domicilio && contraEntrega;
        Comprobante comp = null;
        try {
            comp = new Comprobante(app.getNegocio(), numero, LocalDateTime.now(),
                    app.getSesion().getUsuario().getNombre(), nombreCliente, lineas, total,
                    contra ? new ArrayList<>() : Comprobante.pagosNetos(pagos, cuenta.getVueltas()), contra ? 0 : cuenta.getRecibidoEfectivo(),
                    contra ? 0 : cuenta.getVueltas());
            comp.nota = carrito.getNota();
            comp.domicilio = datosDomicilio;
            comp.datosCliente = elegido;
            comp.valorDomicilio = datosDomicilio == null ? 0 : domicilioVendido;
            comp.contraEntrega = contra;
            comp.ajustes = app.getAjustesRecibo();
        } catch (RuntimeException e) {
            rutas.logica.Ubicaciones.registrarError(e);
        }
        try {
            alCobrar.accept(numero);
        } catch (RuntimeException e) {
            rutas.logica.Ubicaciones.registrarError(e);
        }
        if (comp != null) {
            mostrarFin(comp);
        } else {
            capa.cerrar();
            Alerta.mostrar(dentroDe, Alerta.Tipo.EXITO, "Venta No. " + Comprobante.numeroTexto(numero) + " cobrada",
                    "La venta quedó guardada. No se pudo armar el comprobante.", null);
        }
    }

    /**
     * "Venta realizada": a la izquierda las vueltas y lo que se puede hacer; a la derecha el recibo
     * completo (con su barra si es largo). La ventana es de alto fijo: nunca se sale de la pantalla.
     */
    private void mostrarFin(Comprobante comp) {
        javax.swing.JRootPane raiz = javax.swing.SwingUtilities.getRootPane(dentroDe);
        int ancho = raiz == null ? 940 : Math.max(760, Math.min(980, raiz.getWidth() - 60));
        int alto = raiz == null ? 600 : Math.max(420, Math.min(660, raiz.getHeight() - 60));
        modal.removeAll();
        modal.setPreferredSize(new Dimension(ancho, alto));
        modal.add(cabeza("Venta realizada"), java.awt.BorderLayout.NORTH);

        JPanel izq = new JPanel();
        izq.setOpaque(false);
        izq.setLayout(new BoxLayout(izq, BoxLayout.Y_AXIS));
        izq.setBorder(BorderFactory.createEmptyBorder(18, 28, 18, 24));
        Alerta.Icono ok = new Alerta.Icono(Alerta.Tipo.EXITO, 52);
        ok.setAlignmentX(Component.CENTER_ALIGNMENT);
        izq.add(ok);
        izq.add(Tema.espacio(8));
        izq.add(centrado("Venta No. " + Comprobante.numeroTexto(comp.numero), Tema.textoFuerte(15), Tema.GRIS_OSCURO));
        if (comp.cliente != null && !comp.cliente.isBlank()) {
            izq.add(centrado(comp.cliente.trim(), Tema.texto(13), Tema.GRIS_TEXTO));
        }
        izq.add(Tema.espacio(10));
        boolean aDomicilio = comp.domicilio != null;
        if (aDomicilio && contraEntrega) { // no se recibio plata: lo importante es lo que cobra el repartidor
            izq.add(centrado("Cobrar al entregar", Tema.texto(13), Tema.GRIS_TEXTO));
            izq.add(centrado(Tarifa.pesos(comp.total + domicilioVendido), Tema.titulo(Tema.LETRA_CIFRA),
                    Tema.AZUL_PROFUNDO));
            izq.add(centrado("Productos " + Tarifa.pesos(comp.total) + "  ·  domicilio " + Tarifa.pesos(domicilioVendido),
                    Tema.texto(13), Tema.GRIS_TEXTO));
        } else if (comp.recibido > 0) {
            izq.add(centrado("Vueltas", Tema.texto(13), Tema.GRIS_TEXTO));
            izq.add(centrado(Tarifa.pesos(comp.vueltas), Tema.titulo(Tema.LETRA_CIFRA), Tema.EXITO_TEXTO));
            izq.add(centrado("Total " + Tarifa.pesos(comp.total) + "  ·  recibió " + Tarifa.pesos(comp.recibido),
                    Tema.texto(13), Tema.GRIS_TEXTO));
        } else {
            izq.add(centrado("Total", Tema.texto(13), Tema.GRIS_TEXTO));
            izq.add(centrado(Tarifa.pesos(comp.total), Tema.titulo(Tema.LETRA_CIFRA), Tema.GRIS_OSCURO));
        }
        izq.add(Tema.espacio(18));
        JPanel opciones = new JPanel(new GridLayout(2, 2, 10, 10));
        opciones.setOpaque(false);
        opciones.setAlignmentX(Component.CENTER_ALIGNMENT);
        Tema.Boton ticket = opcionFin("Imprimir ticket", "notas", "Imprimir el recibo en la impresora de tickets");
        Tema.Boton carta = opcionFin("Guardar PDF", null, "Guardar el recibo como archivo PDF");
        carta.setIcon(new PanelProductos.BotonIcono("", PanelProductos.Icono.PDF, "", () -> { }).getIcon());
        Tema.Boton correo = opcionFin("Enviar por correo", null, "Mandar el recibo en PDF al correo del cliente");
        correo.setIcon(new LogoPago("/imagenes/archivos/gmail.png", 24));
        Tema.Boton nueva = opcionFin("Nueva venta", "carrito", "Cerrar y empezar otra venta (Enter)");
        opciones.add(ticket);
        opciones.add(carta);
        opciones.add(correo);
        opciones.add(nueva);
        opciones.setMaximumSize(new Dimension(400, 170));
        opciones.setPreferredSize(new Dimension(400, 170));
        izq.add(opciones);
        izq.add(Tema.espacio(10));
        JLabel estado = new JLabel(" ", JLabel.CENTER);
        estado.setFont(Tema.textoFuerte(13));
        estado.setAlignmentX(Component.CENTER_ALIGNMENT);
        estado.setMaximumSize(new Dimension(420, 60));
        izq.add(estado);
        if (aDomicilio) {
            izq.add(Tema.espacio(4));
            JLabel dl = centrado("A domicilio" + (contraEntrega ? " · contra entrega" : " · el repartidor cobra "
                    + Tarifa.pesos(domicilioVendido)), Tema.texto(13), Tema.GRIS_TEXTO);
            izq.add(dl);
            izq.add(Tema.espacio(8));
            // la venta ya tiene número: ahora sí se despacha (o espera para salir con otros pedidos)
            int otras = 0, mias = 0;
            for (Parada p : app.getEntregas()) {
                if (p.getVenta() == marca) {
                    mias++;
                } else {
                    otras++;
                }
            }
            Tema.Boton ahora = Tema.botonSecundario(otras > 0 ? "Despachar ahora (con " + otras
                    + (otras == 1 ? " más)" : " más)") : "Despachar ahora");
            ahora.setIcon(new LogoPago("/imagenes/archivos/whatsapp.png", 20));
            ahora.setIconTextGap(8);
            ahora.setToolTipText("Escoger quién la lleva y mandarle la ruta por WhatsApp"
                    + (otras > 0 ? " (sale con las otras entregas que esperan)" : ""));
            Tema.Boton despues = Tema.botonSecundario("Después");
            despues.setToolTipText("Queda esperando en Salidas, para juntarla con otros pedidos");
            JPanel desp = Tema.fila(8, ahora, despues);
            desp.setAlignmentX(Component.CENTER_ALIGNMENT);
            desp.setMaximumSize(new Dimension(460, 120));
            if (mias > 0) { // si ya salió (o se quitó), no hay nada que despachar aquí
                izq.add(desp);
            }
            ahora.addActionListener(e -> {
                capa.cerrar();
                VentanaPrincipal v = app.getVentana();
                if (v != null) {
                    // sigue en Vender: «¿Quién lleva esta salida?» sale encima y luego se sigue vendiendo
                    javax.swing.SwingUtilities.invokeLater(() -> v.getPedidos().despacharDesde(dentroDe));
                }
            });
            despues.addActionListener(e -> capa.cerrar());
        }

        // derecha: el recibo completo, con barra si es largo (100 productos = recibo largo)
        JPanel der = new JPanel(new java.awt.BorderLayout(0, 8));
        der.setBackground(new Color(0xE4E8ED));
        der.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
        int productos = carrito.cantidadProductos();
        JLabel tit = new JLabel("Así sale el recibo  ·  " + productos + (productos == 1 ? " producto" : " productos"));
        tit.setFont(Tema.texto(12.5f));
        tit.setForeground(Tema.GRIS_TEXTO);
        der.add(tit, java.awt.BorderLayout.NORTH);
        Vista vista = new Vista(comp);
        JPanel centro = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.CENTER, 0, 0));
        centro.setOpaque(false);
        centro.add(vista);
        JScrollPane sc = new JScrollPane(centro);
        sc.setBorder(BorderFactory.createEmptyBorder());
        sc.setOpaque(false);
        sc.getViewport().setOpaque(false);
        sc.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        sc.getVerticalScrollBar().setUnitIncrement(24);
        der.add(sc, java.awt.BorderLayout.CENTER);
        der.setPreferredSize(new Dimension(Math.min(400, ancho / 2 - 40), 10));

        JPanel cuerpoFin = new JPanel(new java.awt.BorderLayout());
        cuerpoFin.setOpaque(false);
        JPanel envIzq = new JPanel(new java.awt.GridBagLayout()); // centrado de arriba a abajo
        envIzq.setOpaque(false);
        envIzq.add(izq);
        cuerpoFin.add(envIzq, java.awt.BorderLayout.CENTER);
        cuerpoFin.add(der, java.awt.BorderLayout.EAST);
        modal.add(cuerpoFin, java.awt.BorderLayout.CENTER);

        nueva.addActionListener(e -> capa.cerrar());
        ticket.addActionListener(e -> {
            ticket.setEnabled(false);
            ticket.setText("Imprimiendo…");
            estado.setForeground(Tema.GRIS_TEXTO);
            estado.setText("Enviando a la impresora…");
            Impresion.imprimirAparte(comp, comp.alto(), comp.ajustes,
                    "Recibo " + Comprobante.numeroTexto(comp.numero), aviso -> {
                        ticket.setEnabled(true);
                        ticket.setText("Imprimir ticket");
                        if (aviso == null) {
                            estado.setForeground(Tema.EXITO_TEXTO);
                            estado.setText("Recibo enviado a la impresora");
                        } else if (!aviso.equals("No se imprimió")) {
                            estado.setForeground(Tema.ERROR_TEXTO);
                            estado.setText("<html><div style='text-align:center;width:300px'>" + aviso + "</div></html>");
                        } else {
                            estado.setText(" ");
                        }
                    });
        });
        carta.addActionListener(e -> EnvioRecibo.guardarPdf(dentroDe, comp));
        correo.addActionListener(e -> EnvioRecibo.abrir(dentroDe, app, comp, elegido, c -> elegido = c));
        capa.getActionMap().put("cobrar", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                // solo si el foco esta en esta pantalla (no en la ventanita del correo, que va encima)
                Component foco = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
                if (foco != null && javax.swing.SwingUtilities.isDescendingFrom(foco, modal)) {
                    capa.cerrar();
                }
            }
        });
        capa.reacomodar();
        javax.swing.SwingUtilities.invokeLater(() -> sc.getVerticalScrollBar().setValue(0));
        nueva.requestFocusInWindow();
    }

    /** Boton alto con icono arriba y texto abajo (pantalla de venta realizada). */
    private static Tema.Boton opcionFin(String texto, String icono, String ayuda) {
        boolean principal = texto.equals("Nueva venta");
        Tema.Boton b = principal ? Tema.botonAccion(texto) : Tema.botonSecundario(texto);
        b.setFont(Tema.textoFuerte(13.5f));
        b.setToolTipText(ayuda);
        if (icono != null) {
            b.setIcon(Iconos.de(icono, 26, principal ? Tema.GRIS_OSCURO : Tema.AZUL));
        }
        b.setVerticalTextPosition(javax.swing.SwingConstants.BOTTOM);
        b.setHorizontalTextPosition(javax.swing.SwingConstants.CENTER);
        b.setIconTextGap(6);
        b.setBorder(BorderFactory.createEmptyBorder(10, 6, 10, 6));
        return b;
    }

    private static JLabel centrado(String texto, java.awt.Font f, java.awt.Color c) {
        JLabel l = new JLabel(texto, JLabel.CENTER);
        l.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6)); // la ultima letra no se corta
        l.setFont(f);
        l.setForeground(c);
        l.setAlignmentX(Component.CENTER_ALIGNMENT);
        return l;
    }

    /** Vista previa del comprobante dentro de la pantalla de fin. */
    static class Vista extends JPanel {
        private final Comprobante comp;

        Vista(Comprobante comp) {
            this.comp = comp;
            setOpaque(false);
            getAccessibleContext().setAccessibleName("Comprobante de la venta");
        }

        @Override
        public Dimension getPreferredSize() {
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(1, 1,
                    java.awt.image.BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            int alto = comp.dibujar(g, comp.anchoPuntos());
            g.dispose();
            double k = escala();
            return new Dimension((int) Math.round(comp.anchoPuntos() * k) + 32, (int) Math.ceil(alto * k) + 24);
        }

        @Override
        public Dimension getMaximumSize() {
            return getPreferredSize();
        }

        /** El recibo se agranda para leerlo en pantalla, sin pasar de 300 px de ancho. */
        private double escala() {
            return Math.min(1.7, 300.0 / comp.anchoPuntos());
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Tema.suavizar(g);
            double k = escala();
            int w = (int) Math.round(comp.anchoPuntos() * k);
            g.setColor(Color.WHITE);
            g.fillRect((getWidth() - w) / 2 - 8, 6, w + 16, getHeight() - 12);
            g.translate((getWidth() - w) / 2, 4);
            g.scale(k, k);
            comp.dibujar(g, comp.anchoPuntos());
            g.dispose();
        }
    }

    private static DocumentListener escucha(Runnable r) {
        return new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { r.run(); }
            public void removeUpdate(DocumentEvent e) { r.run(); }
            public void changedUpdate(DocumentEvent e) { r.run(); }
        };
    }

    /**
     * Logo a color de una forma de pago, de alto fijo y ancho segun la imagen (a lo mas 80 px).
     * Se escala al tamaño real de pantalla (125 %, 150 %) para que no se vea borroso.
     */
    static class LogoPago implements javax.swing.Icon {
        private final java.awt.Image img;
        private final int alto;
        private final int ancho;
        private final int caja; // alto reservado (el de todos los logos)
        private final java.util.Map<Integer, java.awt.Image> escalas = new java.util.HashMap<>();

        LogoPago(String recurso, int alto) {
            java.net.URL url = CapaCobro.class.getResource(recurso);
            this.img = url == null ? null : new javax.swing.ImageIcon(url).getImage();
            int a = alto;
            int w = img == null ? 0 : Math.round(alto * (float) img.getWidth(null) / img.getHeight(null));
            if (w > 80) { // los logos anchos (Nequi, Bre-B) se achican para caber en el boton
                a = Math.round(alto * 80f / w);
                w = 80;
            }
            this.alto = img == null ? 0 : a;
            this.ancho = w;
            this.caja = alto;
        }

        @Override
        public void paintIcon(Component c, Graphics g0, int x, int y) {
            if (img == null) {
                return;
            }
            Graphics2D g = (Graphics2D) g0.create();
            double esc = g.getTransform().getScaleX();
            int pw = (int) Math.round(ancho * esc), ph = (int) Math.round(alto * esc);
            java.awt.Image im = escalas.computeIfAbsent(pw, k -> img.getScaledInstance(pw, ph, java.awt.Image.SCALE_SMOOTH));
            new javax.swing.ImageIcon(im); // espera a que termine de escalar
            g.translate(x, y + (caja - alto) / 2);
            g.scale(1 / esc, 1 / esc);
            g.drawImage(im, 0, 0, null);
            g.dispose();
        }

        @Override
        public int getIconWidth() {
            return ancho;
        }

        @Override
        public int getIconHeight() {
            return img == null ? 0 : caja;
        }
    }
}
