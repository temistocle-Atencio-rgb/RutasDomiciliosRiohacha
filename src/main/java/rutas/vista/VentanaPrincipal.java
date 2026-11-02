package rutas.vista;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.Timer;
import rutas.modelo.Geo;
import rutas.modelo.Parada;
import rutas.modelo.Ruta;

/**
 * Ventana principal segun el manual para programas de escritorio: barra lateral azul con el cubo
 * y el menu, barra de titulo azul profundo, contenido sobre gris claro y el mapa a la derecha.
 */
public class VentanaPrincipal extends JFrame {

    private final Aplicacion app;
    private final PanelMapa panelMapa;
    private final CardLayout tarjetas = new CardLayout();
    private final JPanel contenido = new JPanel(tarjetas);
    private final List<Pestana> pestanas = new ArrayList<>();
    private final JLabel lblTrafico = new JLabel();
    private PanelNovedades panelNovedades;
    private int inicioDomicilios = -1; // indice de la primera pestaña del grupo Domicilios
    private int inicioAjustes = -1;    // indice de Configuracion (solo administrador)
    private final List<Grupo> grupos = new ArrayList<>();
    private final List<javax.swing.JButton> navegables = new ArrayList<>(); // orden del menu para las flechas
    // dos modos: pantalla completa (Vender) o columna + mapa (las de domicilios y la tienda)
    private final CardLayout modos = new CardLayout();
    private final JPanel centro = new JPanel(modos);
    private final CardLayout completas = new CardLayout();
    private final JPanel panelCompleto = new JPanel(completas);
    private PanelVender panelVender;
    private final JPanel conMapa = new JPanel(new BorderLayout());
    private PanelPedidos pedidos;
    private PanelSalidas panelSalidas;
    private PanelConfiguracion panelConfiguracion; // null si quien entró no es administrador
    private PanelCompras panelNuevaCompra;          // null si no puede registrar compras
    private final Notificaciones notificaciones = new Notificaciones(); // la campana de arriba
    private Seguimiento seguimiento;
    private JPanel zonaDomicilio;            // mapa + Ruta/Buscar prestados al cobro a domicilio
    private final List<javax.swing.JButton> pestanasDomicilio = new ArrayList<>();
    private java.awt.Component tarjetaAntes; // la columna que se veia antes de prestarla
    private final JLabel lblSeccion = new JLabel();
    private final Timer reloj = new Timer(30_000, e -> actualizarTrafico());

    private final List<rutas.modelo.Ciudad> ciudades;

    public VentanaPrincipal(Aplicacion app, List<rutas.modelo.Ciudad> ciudades) {
        super("DomiRuta · Rutas de domicilios en " + app.getCiudad().getNombre());
        this.app = app;
        this.ciudades = ciudades;
        setIconImages(Logo.iconos());
        panelMapa = new PanelMapa(app.getMapa());
        app.setPanelMapa(panelMapa);
        panelMapa.setOyente(this::clicEnMapa);

        app.setVentana(this);
        JPanel derecha = new JPanel(new BorderLayout());
        derecha.add(barraTitulo(), BorderLayout.NORTH);
        conMapa.add(panelContenido(), BorderLayout.WEST);
        conMapa.add(panelMapa, BorderLayout.CENTER);
        centro.add(conMapa, "mapa");
        centro.add(panelCompleto, "completo");
        derecha.add(centro, BorderLayout.CENTER);
        seleccionar(0);
        JPanel raiz = new JPanel(new BorderLayout());
        raiz.add(barraLateral(), BorderLayout.WEST);
        raiz.add(derecha, BorderLayout.CENTER);
        setContentPane(raiz);

        app.avisar();
        // la ventana se ajusta a la pantalla (con la escala de Windows al 125 % la pantalla "mide" menos)
        java.awt.Rectangle util = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        setMinimumSize(new Dimension(Math.min(1100, util.width), Math.min(640, util.height)));
        setSize(Math.min(1440, util.width), Math.min(860, util.height));
        setLocationRelativeTo(null);
        if (util.width < 1500) {
            setExtendedState(MAXIMIZED_BOTH);
        }
        // al abrir, cada seccion empieza desde arriba
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowOpened(java.awt.event.WindowEvent e) {
                javax.swing.SwingUtilities.invokeLater(() -> {
                    for (java.awt.Component c : contenido.getComponents()) {
                        if (c instanceof JScrollPane) {
                            ((JScrollPane) c).getViewport().setViewPosition(new java.awt.Point(0, 0));
                        }
                    }
                    if (panelVender != null && pestanas.get(0).completa) {
                        panelVender.enfocar(); // la caja abre lista para escanear
                    } else {
                        pestanas.get(0).requestFocusInWindow();
                    }
                });
            }
        });
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                app.cerrar(); // la base de datos se cierra bien antes de salir
            }
        });

        actualizarTrafico();
        app.alCambiar(this::actualizarTrafico);
        reloj.start();

        // lo que marca el repartidor en el celular llega solo (cada 15 s)
        seguimiento = new Seguimiento(app);
        panelSalidas.setSeguimiento(seguimiento);
        seguimiento.alCambiar(new Seguimiento.Oyente() {
            @Override
            public void mensajeNuevo(rutas.modelo.Salida s, rutas.modelo.Salida.Mensaje m) {
                java.awt.Toolkit.getDefaultToolkit().beep();
                avisar(s, Aviso.Tipo.MENSAJE, "Mensaje de " + (s.repartidor.isEmpty() ? "el repartidor"
                        : s.repartidor), m.texto, true);
            }

            @Override
            public void respondio(rutas.modelo.Salida s, boolean acepto) {
                String quien = s.repartidor.isEmpty() ? "El domiciliario" : s.repartidor;
                if (acepto) {
                    avisar(s, Aviso.Tipo.BIEN, quien + " aceptó", "Ya va con las entregas", false);
                } else {
                    java.awt.Toolkit.getDefaultToolkit().beep();
                    avisar(s, Aviso.Tipo.ERROR, quien + " no puede llevarla",
                            (s.motivoRechazo.isEmpty() ? "" : s.motivoRechazo + ". ") + "Escoja a otro en «Salidas»", true);
                }
            }

            @Override
            public void terminoRuta(rutas.modelo.Salida s) {
                String quien = s.repartidor.isEmpty() ? "El repartidor" : s.repartidor;
                if (!s.abierta()) {
                    avisar(s, Aviso.Tipo.BIEN, quien + " entregó todo", "La salida se cerró sola", false);
                } else {
                    avisar(s, Aviso.Tipo.AVISO, quien + " terminó las entregas", PanelSalidas.loQueDebe(s), true);
                }
            }
        });
        notificaciones.alTocar(this::irASalida);
        app.alCambiar(this::marcarMensajes);
        marcarMensajes();
        seguimiento.empezar();
    }

    /** El aviso de abajo (se va solo) y además queda en la campana de arriba. */
    private void avisar(rutas.modelo.Salida s, Aviso.Tipo tipo, String titulo, String texto, boolean importante) {
        notificaciones.agregar(tipo, titulo, texto, s.id);
        // si está vendiendo, el aviso sale igual (sobre la ventana, no solo sobre el mapa)
        JComponent donde = conMapa.isShowing() ? conMapa : (JComponent) getContentPane();
        Aviso.mostrar(donde, tipo, titulo, texto, importante);
    }

    Notificaciones getNotificaciones() {
        return notificaciones;
    }

    /** Desde la campana: abre Salidas y muestra esa salida (si ya se cerró, el Historial). */
    void irASalida(long id) {
        for (rutas.modelo.Salida s : app.getSalidas()) {
            if (s.id == id && !s.abierta()) {
                irAHistorial();
                return;
            }
        }
        int i = indicePestana("Salidas");
        if (i >= 0) {
            seleccionar(i);
            javax.swing.SwingUtilities.invokeLater(() -> panelSalidas.mostrarSalida(id));
        }
    }

    /** El número de mensajes sin leer va al lado de «Salidas» en el menú. */
    private void marcarMensajes() {
        int n = 0;
        for (rutas.modelo.Salida s : app.salidasAbiertas()) {
            n += s.mensajesSinLeer();
        }
        for (Pestana p : pestanas) {
            if (p.nombre.equals("Salidas") && p.aviso != n) {
                p.aviso = n;
                p.repaint();
            }
        }
    }

    /** Al cerrar la ventana se detiene el reloj (si no, la ventana vieja nunca se libera). */
    @Override
    public void dispose() {
        reloj.stop();
        if (seguimiento != null) {
            seguimiento.parar();
        }
        super.dispose();
    }

    // ------------------------------------------------------------------

    /** Barra de titulo azul profundo: municipio a la izquierda y estado del trafico a la derecha. */
    private JComponent barraTitulo() {
        JPanel h = new JPanel(new BorderLayout());
        h.setBackground(Tema.AZUL_PROFUNDO);
        h.setBorder(BorderFactory.createEmptyBorder(12, 20, 12, 20));

        JPanel izq = new JPanel();
        izq.setOpaque(false);
        izq.setLayout(new BoxLayout(izq, BoxLayout.X_AXIS));
        lblSeccion.setFont(Tema.texto(14));
        lblSeccion.setForeground(Color.WHITE);
        izq.add(lblSeccion);
        izq.add(Box.createHorizontalStrut(6));
        izq.add(selectorCiudad());
        h.add(izq, BorderLayout.WEST);

        // el estado va como texto, sin cuadro de color detras (regla del manual)
        lblTrafico.setFont(Tema.textoFuerte(13.5f));
        lblTrafico.setForeground(Color.WHITE);
        JPanel der = new JPanel();
        der.setOpaque(false);
        der.setLayout(new BoxLayout(der, BoxLayout.X_AXIS));
        der.add(lblTrafico);
        der.add(Box.createHorizontalStrut(10));
        der.add(notificaciones.getCampana());
        rutas.logica.Sesion sesion = app.getSesion();
        if (sesion != null) {
            der.add(Box.createHorizontalStrut(18));
            JLabel quien = new JLabel(sesion.getUsuario().getNombre() + " · " + sesion.getUsuario().getRol().getTexto());
            quien.setFont(Tema.texto(13.5f));
            quien.setForeground(Color.WHITE);
            der.add(quien);
            der.add(Box.createHorizontalStrut(10));
            Tema.Boton salir = Tema.botonPequeno("Salir");
            salir.setToolTipText("Cerrar la sesión y volver al ingreso");
            // la aplicacion (mapa y pedidos en cola) sigue igual: solo cambia quien la usa
            salir.addActionListener(e -> rutas.Main.cerrarSesion(this, app, ciudades));
            der.add(salir);
        }
        h.add(der, BorderLayout.EAST);
        return h;
    }

    /** Nombre de la ciudad con menu para cambiar de municipio o actualizar el mapa. */
    private JComponent selectorCiudad() {
        JLabel l = new JLabel(app.getCiudad().getNombre() + ", La Guajira");
        l.setHorizontalTextPosition(JLabel.LEFT);
        l.setIconTextGap(6);
        l.setIcon(new javax.swing.Icon() { // flechita hacia abajo
            public void paintIcon(java.awt.Component c, Graphics g0, int x, int y) {
                Graphics2D g = (Graphics2D) g0.create();
                Tema.suavizar(g);
                g.setColor(Color.WHITE);
                g.fillPolygon(new int[]{x, x + 8, x + 4}, new int[]{y + 2, y + 2, y + 7}, 3);
                g.dispose();
            }
            public int getIconWidth() { return 9; }
            public int getIconHeight() { return 9; }
        });
        l.setFont(Tema.textoFuerte(13));
        l.setForeground(Color.WHITE);
        l.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        l.setToolTipText("Cambiar de municipio o actualizar el mapa");
        l.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
                java.io.File datos = app.getCarpetaDatos();
                java.io.File usuario = app.getCarpetaUsuario().getParentFile();
                for (rutas.modelo.Ciudad c : ciudades) {
                    boolean lista = c.estaDisponible(datos, usuario);
                    javax.swing.JMenuItem it = new javax.swing.JMenuItem(c.getNombre()
                            + (lista ? "" : "   (descargar mapa)"));
                    it.setFont(c == app.getCiudad() || c.getClave().equals(app.getCiudad().getClave())
                            ? Tema.textoFuerte(14) : Tema.texto(14));
                    it.addActionListener(ev -> cambiarCiudad(c, false));
                    menu.add(it);
                }
                menu.addSeparator();
                javax.swing.JMenuItem act = new javax.swing.JMenuItem("Actualizar el mapa de "
                        + app.getCiudad().getNombre() + " desde OpenStreetMap");
                act.setFont(Tema.texto(14));
                act.addActionListener(ev -> cambiarCiudad(app.getCiudad(), true));
                menu.add(act);
                menu.show(l, 0, l.getHeight() + 4);
            }
        });
        return l;
    }

    private void cambiarCiudad(rutas.modelo.Ciudad c, boolean actualizar) {
        java.io.File usuario = app.getCarpetaUsuario().getParentFile();
        if (c.getClave().equals(app.getCiudad().getClave()) && !actualizar) {
            return;
        }
        boolean disponible = c.estaDisponible(app.getCarpetaDatos(), usuario);
        if (actualizar || !disponible) {
            int r = JOptionPane.showConfirmDialog(this, "Se va a descargar el mapa de " + c.getNombre()
                    + " desde OpenStreetMap.\nNecesita internet y puede tardar de 1 a 3 minutos.\n¿Continuar?",
                    "Descargar mapa", JOptionPane.OK_CANCEL_OPTION);
            if (r != JOptionPane.OK_OPTION || !DialogoDescarga.descargar(this, c, usuario)) {
                return;
            }
        }
        app.cerrar();
        dispose();
        rutas.Main.abrir(c.getClave());
    }

    private void actualizarTrafico() {
        LocalTime ahora = LocalTime.now();
        boolean pico = app.getTrafico().esHoraPico(ahora);
        lblTrafico.setText((pico ? "Hora pico: más trancones" : "Tráfico normal")
                + (app.getTrafico().isLluvia() ? "  ·  Lloviendo" : "") + "  ·  "
                + ahora.format(Ruta.FORMATO_HORA) + "  ·  " + app.getMapa().getIncidentes().size() + " novedades");
    }

    /** Barra lateral azul del manual: el cubo arriba, el menu en blanco y los atajos visibles abajo. */
    private JComponent barraLateral() {
        JPanel lat = new JPanel(new BorderLayout());
        lat.setBackground(Tema.AZUL);
        lat.setPreferredSize(new Dimension(184, 100));

        JPanel arriba = new JPanel();
        arriba.setOpaque(false);
        arriba.setLayout(new BoxLayout(arriba, BoxLayout.Y_AXIS));
        arriba.setBorder(BorderFactory.createEmptyBorder(20, 16, 12, 12));
        Logo logo = new Logo(Logo.Version.BLANCO, 30);
        logo.setAlignmentX(LEFT_ALIGNMENT);
        arriba.add(logo);
        arriba.add(Box.createVerticalStrut(24)); // espacio libre de una cara del cubo
        for (Pestana p : pestanas) {
            if (p.indice == 0 || p.indice == inicioDomicilios || p.indice == inicioAjustes) {
                arriba.add(tituloGrupo(p.indice == 0 ? "Tienda" : p.indice == inicioAjustes ? "Ajustes" : "Domicilios"));
            }
            p.setAlignmentX(LEFT_ALIGNMENT);
            if (p.grupo != null) {
                // submenu: en la barra solo va el boton del grupo; sus opciones salen en un panel al lado
                Grupo g = grupo(p.grupo);
                if (g.getParent() == null) {
                    g.setAlignmentX(LEFT_ALIGNMENT);
                    arriba.add(g);
                    arriba.add(Box.createVerticalStrut(4));
                    navegables.add(g);
                }
                p.setFont(Tema.texto(14));
                p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
                p.setPreferredSize(new Dimension(200, 38));
                g.hijos.add(p);
                g.menu.add(p);
                g.menu.add(Box.createVerticalStrut(2));
                g.barra = lat;
            } else {
                arriba.add(p);
                arriba.add(Box.createVerticalStrut(4));
            }
            navegables.add(p);
        }
        lat.add(arriba, BorderLayout.NORTH);

        JPanel atajos = new JPanel();
        atajos.setOpaque(false);
        atajos.setLayout(new BoxLayout(atajos, BoxLayout.Y_AXIS));
        atajos.setBorder(BorderFactory.createEmptyBorder(12, 20, 20, 12));
        JLabel tit = new JLabel("Atajos");
        tit.setFont(Tema.textoFuerte(12.5f));
        tit.setForeground(Color.WHITE);
        atajos.add(tit);
        atajos.add(Box.createVerticalStrut(4));
        for (String[] a : new String[][]{{"F12", "cobrar"}, {"F8", "venta en espera"}, {"Ctrl+Enter", "calcular ruta"},
            {"Ctrl+1 a " + Math.min(9, pestanas.size()), "secciones"}, {"Esc", "cancelar"}}) {
            JLabel l = new JLabel(a[0] + "  " + a[1]);
            l.setFont(Tema.texto(12.5f));
            l.setForeground(TEXTO_LATERAL);
            atajos.add(l);
            atajos.add(Box.createVerticalStrut(2));
        }
        lat.add(atajos, BorderLayout.SOUTH);
        // en pantallas bajitas (o con el submenu abierto) la ayuda de atajos se esconde para no tapar el menu
        Runnable acomodar = () -> {
            boolean cabe = lat.getHeight() >= arriba.getPreferredSize().height + atajos.getPreferredSize().height;
            if (atajos.isVisible() != cabe) {
                atajos.setVisible(cabe);
                lat.revalidate();
            }
        };
        lat.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                acomodar.run();
            }
        });
        return lat;
    }

    /** Titulo pequeño de un grupo del menu lateral. */
    private static JComponent tituloGrupo(String texto) {
        JLabel l = new JLabel(texto.toUpperCase());
        l.setFont(Tema.textoFuerte(11.5f));
        l.setForeground(TEXTO_LATERAL);
        l.setBorder(BorderFactory.createEmptyBorder(10, 12, 6, 0));
        l.setAlignmentX(LEFT_ALIGNMENT);
        return l;
    }

    /** Texto secundario sobre el azul: 6.3:1. */
    private static final Color TEXTO_LATERAL = new Color(0xDCE6F2);

    /** Panel de contenido sobre gris claro, con las tarjetas blancas. */
    private JComponent panelContenido() {
        // Tienda: Vender, Inventario (todos lo consultan) y Compras (con permiso). Usuarios va en Configuración.
        rutas.logica.Sesion sesion = app.getSesion();
        if (app.getTienda() != null && sesion != null) {
            panelVender = new PanelVender(app);
            agregarCompleta("Vender", panelVender);
        }
        if (sesion != null && app.getTienda() != null && sesion.puede(rutas.logica.Permiso.VER_INVENTARIO)) {
            agregarCompleta("Productos", new PanelProductos(app)); // sin permiso de editar: solo consulta
            pestanas.get(pestanas.size() - 1).grupo = "Inventario"; // primera opcion del submenu Inventario
            if (sesion.puede(rutas.logica.Permiso.EDITAR_PRODUCTOS)) {
                agregarCompleta("Categorías", new PanelCategoriasLista(app));
                pestanas.get(pestanas.size() - 1).grupo = "Inventario";
            }
        }
        if (sesion != null && app.getTienda() != null && sesion.puede(rutas.logica.Permiso.REGISTRAR_COMPRAS)) {
            panelNuevaCompra = new PanelCompras(app);
            agregarCompleta("Nueva compra", panelNuevaCompra);
            pestanas.get(pestanas.size() - 1).grupo = "Compras"; // primera opcion del submenu Compras
            agregarCompleta("Órdenes de compra", new PanelOrdenesCompra(app));
            pestanas.get(pestanas.size() - 1).grupo = "Compras";
            agregarCompleta("Historial de compras", new PanelHistorialCompras(app));
            pestanas.get(pestanas.size() - 1).grupo = "Compras";
            agregarCompleta("Proveedores", new PanelProveedores(app));
            pestanas.get(pestanas.size() - 1).grupo = "Compras";
        }
        // Domicilios
        pedidos = new PanelPedidos(app);
        panelNovedades = new PanelNovedades(app);
        inicioDomicilios = pestanas.size();
        agregarPestana("Ruta", pedidos);
        panelSalidas = new PanelSalidas(app);
        agregarPestana("Salidas", panelSalidas);
        agregarPestana("Buscar", new PanelExplorar(app));
        agregarCompleta("Clientes", new PanelClientes(app)); // lista a todo el ancho, como Proveedores
        agregarPestana("Novedades", panelNovedades);
        agregarPestana("Historial", new PanelHistorial(app));
        // Ajustes: logo y datos del negocio, impresora, usuarios, domiciliarios, conexión
        if (sesion != null && app.getTienda() != null && sesion.puede(rutas.logica.Permiso.ADMIN_USUARIOS)) {
            inicioAjustes = pestanas.size();
            panelConfiguracion = new PanelConfiguracion(app);
            agregarCompleta("Configuración", panelConfiguracion);
        }
        contenido.setPreferredSize(new Dimension(412, 100));
        contenido.setBackground(Tema.GRIS_CLARO);
        contenido.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Tema.BORDE));
        // Ctrl+1 a Ctrl+5 cambian de seccion
        for (int i = 0; i < pestanas.size(); i++) {
            final int indice = i;
            if (i >= 9) {
                break; // solo hay teclas del 1 al 9
            }
            getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(javax.swing.KeyStroke.getKeyStroke(
                    java.awt.event.KeyEvent.VK_1 + i, java.awt.event.InputEvent.CTRL_DOWN_MASK), "seccion" + i);
            getRootPane().getActionMap().put("seccion" + i, new javax.swing.AbstractAction() {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent e) {
                    if (zonaDomicilio != null && zonaDomicilio.isShowing()) {
                        if (Capa.abiertas(contenido) > 1) {
                            return; // hay un cuadro encima del cobro
                        }
                        String n = pestanas.get(indice).nombre; // en el cobro a domicilio solo Ruta y Buscar
                        if (n.equals("Ruta") || n.equals("Buscar")) {
                            mostrarEnDomicilio(n);
                        }
                    } else if (!Capa.hayAbierta(contenido)) { // con un cuadro abierto no se cambia lo de atras
                        seleccionar(indice);
                    }
                }
            });
        }
        return contenido;
    }

    // ------------------------------------------------------------------
    //  Venta a domicilio: el cobro toma prestados el mapa y la columna de Ruta/Buscar
    // ------------------------------------------------------------------

    /**
     * Arriba las pestañas Ruta y Buscar; debajo el mapa y, a su derecha, la columna (la misma de
     * siempre, no una copia: lo que se agregue aqui queda en Ruta). Se devuelve con devolverDomicilio.
     */
    JComponent prestarDomicilio() {
        if (zonaDomicilio == null) {
            zonaDomicilio = new JPanel(new BorderLayout());
            zonaDomicilio.setBackground(Tema.GRIS_CLARO);
            JPanel tabs = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 0));
            tabs.setOpaque(false);
            tabs.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
            for (String n : new String[]{"Ruta", "Buscar"}) {
                int i = indicePestana(n);
                javax.swing.JButton b = new PestanaDomicilio(n, i < 0 || i >= 9 ? null : "Ctrl+" + (i + 1));
                b.addActionListener(e -> mostrarEnDomicilio(n));
                pestanasDomicilio.add(b);
                tabs.add(b);
            }
            zonaDomicilio.add(tabs, BorderLayout.NORTH);
        }
        tarjetaAntes = null;
        for (java.awt.Component c : contenido.getComponents()) {
            if (c.isVisible()) {
                tarjetaAntes = c;
            }
        }
        conMapa.remove(contenido);
        conMapa.remove(panelMapa);
        JPanel abajo = new JPanel(new BorderLayout());
        abajo.add(panelMapa, BorderLayout.CENTER);
        JPanel columna = new JPanel(new BorderLayout());
        columna.add(contenido, BorderLayout.CENTER);
        columna.add(barraCalcular(), BorderLayout.SOUTH); // fijo: con 10 entregas no hay que bajar a buscarlo
        abajo.add(columna, BorderLayout.EAST);
        zonaDomicilio.add(abajo, BorderLayout.CENTER);
        pedidos.modoCaja(true);
        mostrarEnDomicilio("Ruta");
        return zonaDomicilio;
    }

    /** El mapa y la columna vuelven a su sitio, como estaban. */
    void devolverDomicilio() {
        if (zonaDomicilio == null || panelMapa.getParent() == conMapa) {
            return;
        }
        java.awt.Container abajo = panelMapa.getParent();
        abajo.remove(panelMapa);
        contenido.getParent().remove(contenido);
        zonaDomicilio.remove(abajo);
        conMapa.add(contenido, BorderLayout.WEST);
        conMapa.add(panelMapa, BorderLayout.CENTER);
        panelMapa.setModo(PanelMapa.Modo.NORMAL); // un "marcar en el mapa" a medias no se queda pendiente
        pedidos.modoCaja(false);
        if (tarjetaAntes != null) { // la columna queda en la seccion que estaba
            tarjetas.first(contenido);
            for (int k = 0; k < contenido.getComponentCount() && !tarjetaAntes.isVisible(); k++) {
                tarjetas.next(contenido);
            }
        }
        conMapa.revalidate();
        conMapa.repaint();
    }

    private JPanel barraCalcularRuta;

    /** Abajo de la columna Ruta, siempre a la vista: calcular la ruta (Ctrl+Enter). */
    private JPanel barraCalcular() {
        if (barraCalcularRuta == null) {
            barraCalcularRuta = new JPanel(new BorderLayout());
            barraCalcularRuta.setBackground(Tema.GRIS_CLARO);
            barraCalcularRuta.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(1, 0, 0, 1, Tema.BORDE), BorderFactory.createEmptyBorder(8, 16, 10, 16)));
            Tema.Boton calc = Tema.botonPrimario("Calcular ruta  (Ctrl+Enter)");
            calc.setToolTipText("Calcula el orden de las entregas, la hora y el camino en el mapa");
            calc.addActionListener(e -> pedidos.calcularDesdeCaja());
            barraCalcularRuta.add(calc, BorderLayout.CENTER);
        }
        return barraCalcularRuta;
    }

    void mostrarEnDomicilio(String nombre) {
        tarjetas.show(contenido, nombre);
        if (barraCalcularRuta != null) {
            barraCalcularRuta.setVisible(nombre.equals("Ruta"));
        }
        for (javax.swing.JButton b : pestanasDomicilio) {
            b.setSelected(b.getActionCommand().equals(nombre));
            b.repaint();
        }
        if (nombre.equals("Ruta")) {
            pedidos.enfocarDireccion();
        }
    }

    PanelPedidos getPedidos() {
        return pedidos;
    }

    /** Despues de una venta a domicilio: ir a la pantalla Ruta. */
    /** Solo el administrador ve Configuración, donde está la lista de domiciliarios. */
    boolean puedeEditarDomiciliarios() {
        return panelConfiguracion != null && indicePestana("Configuración") >= 0;
    }

    /** Configuración › Domiciliarios: cambiar nombre, celular, tipo, desactivar o quitar. */
    void irADomiciliarios() {
        int i = indicePestana("Configuración");
        if (i >= 0 && panelConfiguracion != null) {
            seleccionar(i);
            panelConfiguracion.mostrarDomiciliarios();
        }
    }

    /** Llegó la mercancía de una orden: se abre Nueva compra ya llena con lo que faltaba. */
    void recibirOrden(rutas.logica.OrdenCompra o) {
        int i = indicePestana("Nueva compra");
        if (i < 0 || panelNuevaCompra == null) {
            return;
        }
        seleccionar(i);
        javax.swing.SwingUtilities.invokeLater(() -> panelNuevaCompra.recibirOrden(o));
    }

    void irAHistorial() {
        int i = indicePestana("Historial");
        if (i >= 0) {
            seleccionar(i);
        }
    }

    /** Desde Clientes: abre Ruta con el cliente escrito (Enter agrega la entrega). */
    void agregarClienteARuta(rutas.modelo.Cliente c) {
        irARuta();
        if (pedidos != null) {
            pedidos.limpiarCampos();
            pedidos.entregaDeCliente(c);
        }
    }

    /** Desde Clientes: muestra el punto del cliente en el mapa. */
    void verClienteEnMapa(rutas.modelo.Cliente c) {
        int i = indicePestana("Buscar");
        if (i >= 0) {
            seleccionar(i);
        }
        app.getPanelMapa().resaltar(c.getLat(), c.getLon(), c.getNombre());
    }

    void irARuta() {
        int i = indicePestana("Ruta");
        if (i >= 0) {
            seleccionar(i);
        }
    }

    private int indicePestana(String nombre) {
        for (Pestana p : pestanas) {
            if (p.nombre.equals(nombre)) {
                return p.indice;
            }
        }
        return -1;
    }

    /** Pestaña de arriba del mapa en el cobro: la elegida en blanco con borde azul, la otra azul. */
    private static class PestanaDomicilio extends javax.swing.JButton {
        private final String atajo;

        PestanaDomicilio(String nombre, String atajo) {
            super(nombre);
            this.atajo = atajo;
            setActionCommand(nombre);
            setFont(Tema.textoFuerte(13.5f));
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
            setPreferredSize(new Dimension(atajo == null ? 110 : 150, 36));
            if (atajo != null) {
                setToolTipText(nombre + " (" + atajo + ")");
            }
        }

        @Override
        protected void paintComponent(java.awt.Graphics g0) {
            java.awt.Graphics2D g = (java.awt.Graphics2D) g0.create();
            Tema.suavizar(g);
            boolean sel = isSelected();
            g.setColor(sel ? Tema.BLANCO : getModel().isRollover() ? Tema.AZUL_PROFUNDO : Tema.AZUL);
            g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, Tema.ARCO, Tema.ARCO);
            if (sel) {
                g.setColor(Tema.AZUL);
                g.setStroke(new java.awt.BasicStroke(2f));
                g.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, Tema.ARCO, Tema.ARCO);
            }
            if (isFocusOwner()) {
                g.setColor(sel ? Tema.AZUL_PROFUNDO : Tema.BLANCO);
                g.setStroke(new java.awt.BasicStroke(1f));
                g.drawRoundRect(4, 4, getWidth() - 9, getHeight() - 9, Tema.ARCO, Tema.ARCO);
            }
            java.awt.Color texto = sel ? Tema.AZUL : Tema.BLANCO;
            g.setFont(getFont());
            java.awt.FontMetrics fm = g.getFontMetrics();
            int y = (getHeight() + fm.getAscent() - fm.getDescent()) / 2;
            g.setColor(texto);
            g.drawString(getText(), 14, y);
            if (atajo != null) {
                g.setFont(Tema.texto(12));
                java.awt.FontMetrics f2 = g.getFontMetrics();
                g.setColor(sel ? Tema.GRIS_TEXTO : new java.awt.Color(255, 255, 255, 200));
                g.drawString(atajo, getWidth() - 12 - f2.stringWidth(atajo), y);
            }
            g.dispose();
        }
    }

    /** Pestaña que ocupa todo el ancho, sin el mapa (Vender). */
    private void agregarCompleta(String nombre, JPanel panel) {
        panelCompleto.add(panel, nombre);
        pestanas.add(new Pestana(nombre, pestanas.size(), true));
    }

    private void agregarPestana(String nombre, JPanel panel) {
        JPanel envoltura = new PanelAjustado();
        envoltura.setBackground(Tema.GRIS_CLARO);
        envoltura.add(panel, BorderLayout.NORTH);
        JScrollPane sc = Tema.scrollSinBorde(envoltura);
        sc.getViewport().setBackground(Tema.GRIS_CLARO);
        contenido.add(sc, nombre);
        pestanas.add(new Pestana(nombre, pestanas.size(), false));
    }

    private Grupo grupo(String nombre) {
        for (Grupo g : grupos) {
            if (g.nombre.equals(nombre)) {
                return g;
            }
        }
        Grupo g = new Grupo(nombre);
        grupos.add(g);
        return g;
    }

    /** Flechas arriba y abajo por el menu, saltando las opciones de un submenu cerrado. */
    private void mover(javax.swing.JButton desde, int paso) {
        List<javax.swing.JButton> visibles = new ArrayList<>();
        for (javax.swing.JButton b : navegables) {
            if (b.isShowing()) {
                visibles.add(b);
            }
        }
        int i = visibles.indexOf(desde);
        if (i >= 0 && !visibles.isEmpty()) {
            visibles.get((i + paso + visibles.size()) % visibles.size()).requestFocusInWindow();
        }
    }

    private void seleccionar(int i) {
        for (Grupo g : grupos) {
            g.abrir(false); // al escoger una seccion el panel del submenu se cierra
            g.repaint();
        }
        for (Pestana p : pestanas) {
            p.activa = p.indice == i;
            p.getAccessibleContext().setAccessibleDescription(p.activa ? "sección actual" : null);
            p.repaint();
        }
        Pestana p = pestanas.get(i);
        if (p.completa) {
            completas.show(panelCompleto, p.nombre);
            modos.show(centro, "completo");
        } else {
            tarjetas.show(contenido, p.nombre);
            modos.show(centro, "mapa");
        }
        boolean tienda = inicioDomicilios < 0 || i < inicioDomicilios;
        lblSeccion.setText((i == inicioAjustes ? "Configuración" : "Clientes".equals(p.nombre) ? "Clientes"
                : tienda ? "Caja" : "Rutas de domicilios") + "  ·");
    }

    /** Panel que toma el ancho del scroll (asi no aparece barra horizontal). */
    private static class PanelAjustado extends JPanel implements javax.swing.Scrollable {
        PanelAjustado() {
            super(new BorderLayout());
        }

        public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        public int getScrollableUnitIncrement(java.awt.Rectangle r, int o, int d) { return 16; }
        public int getScrollableBlockIncrement(java.awt.Rectangle r, int o, int d) { return r.height - 40; }
        public boolean getScrollableTracksViewportWidth() { return true; }
        public boolean getScrollableTracksViewportHeight() { return false; }
    }

    /**
     * Opcion del menu lateral. Activa: fondo blanco con texto azul profundo. Al pasar el raton: azul profundo.
     * Con el teclado (Tab, flechas, Enter) se marca con un aro de 2 px.
     */
    private class Pestana extends javax.swing.JButton {
        final int indice;
        final String nombre;
        final boolean completa;
        boolean activa;
        int aviso; // mensajes sin leer (se pinta un circulo naranja con el numero)
        String grupo; // nombre del submenu al que pertenece (null = va suelta)

        Pestana(String nombre, int indice, boolean completa) {
            super(nombre);
            this.nombre = nombre;
            this.indice = indice;
            this.completa = completa;
            setFont(Tema.textoFuerte(14));
            setHorizontalAlignment(LEFT);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setRolloverEnabled(true);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 12));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
            setPreferredSize(new Dimension(150, 40));
            setToolTipText(nombre + " (Ctrl+" + (indice + 1) + ")");
            addActionListener(e -> seleccionar(indice));
            addFocusListener(new java.awt.event.FocusAdapter() {
                @Override
                public void focusGained(java.awt.event.FocusEvent e) { repaint(); }

                @Override
                public void focusLost(java.awt.event.FocusEvent e) { repaint(); }
            });
            // flechas arriba y abajo para moverse por el menu
            getInputMap().put(javax.swing.KeyStroke.getKeyStroke("DOWN"), "bajar");
            getInputMap().put(javax.swing.KeyStroke.getKeyStroke("UP"), "subir");
            getActionMap().put("bajar", new javax.swing.AbstractAction() {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent e) {
                    mover(Pestana.this, 1);
                }
            });
            getActionMap().put("subir", new javax.swing.AbstractAction() {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent e) {
                    mover(Pestana.this, -1);
                }
            });
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Tema.suavizar(g);
            int w = getWidth() - 1, h = getHeight() - 1;
            if (activa) {
                g.setColor(Color.WHITE);
                g.fillRoundRect(0, 0, w, h, Tema.ARCO, Tema.ARCO);
            } else if (getModel().isRollover()) {
                g.setColor(Tema.AZUL_PROFUNDO);
                g.fillRoundRect(0, 0, w, h, Tema.ARCO, Tema.ARCO);
            }
            if (isFocusOwner()) {
                // sobre el azul ni el azul ni el gris oscuro se ven: el aro va en blanco, o en azul si el fondo es blanco
                g.setColor(activa ? Tema.AZUL : Color.WHITE);
                g.setStroke(new BasicStroke(2f));
                g.drawRoundRect(1, 1, w - 2, h - 2, Tema.ARCO, Tema.ARCO);
            }
            if (aviso > 0) { // en vez del atajo: circulo naranja con el numero en gris oscuro (contraste del manual)
                String n = aviso > 9 ? "9+" : String.valueOf(aviso);
                g.setFont(Tema.titulo(12));
                java.awt.FontMetrics fa = g.getFontMetrics();
                int d = Math.max(20, fa.stringWidth(n) + 10);
                int cx = w - 10 - d, cy = (h - 20) / 2;
                g.setColor(Tema.NARANJA);
                g.fillRoundRect(cx, cy, d, 20, 20, 20);
                g.setColor(Tema.GRIS_OSCURO);
                g.drawString(n, cx + (d - fa.stringWidth(n)) / 2, cy + 10 + fa.getAscent() / 2 - 2);
                g.dispose();
                setForeground(activa ? Tema.AZUL_PROFUNDO : Color.WHITE);
                super.paintComponent(g0);
                return;
            }
            // atajo a la derecha
            g.setFont(Tema.texto(12.5f));
            String atajo = "Ctrl+" + (indice + 1);
            g.setColor(activa ? Tema.GRIS_TEXTO : TEXTO_LATERAL);
            java.awt.FontMetrics fm = g.getFontMetrics();
            int finTexto = getInsets().left + getFontMetrics(getFont()).stringWidth(getText());
            // Ctrl+1 a Ctrl+9 (no hay tecla para Ctrl+10); si no cabe, el atajo queda en la ayuda
            if (indice < 9 && finTexto + 8 < w - 10 - fm.stringWidth(atajo)) {
                g.drawString(atajo, w - 10 - fm.stringWidth(atajo), (getHeight() + fm.getAscent() - fm.getDescent()) / 2);
            }
            g.dispose();
            setForeground(activa ? Tema.AZUL_PROFUNDO : Color.WHITE);
            super.paintComponent(g0);
        }
    }

    /**
     * Boton de un submenu (Inventario). Al hacer clic sus opciones salen en un panel al lado de la
     * barra (de arriba a abajo), asi el menu no crece aunque el submenu tenga muchas opciones.
     * Se cierra al escoger una opcion, con Esc o al hacer clic en otra parte.
     */
    private class Grupo extends javax.swing.JButton {
        final String nombre;
        final List<Pestana> hijos = new ArrayList<>();
        final JPanel menu = new JPanel();
        JComponent barra;
        boolean abierto;
        private final JPanel panel = new JPanel(new BorderLayout());
        private java.awt.event.AWTEventListener clicAfuera;
        private final java.awt.event.ComponentAdapter alCambiarTamano = new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                abrir(false);
            }
        };

        Grupo(String nombre) {
            super(nombre);
            this.nombre = nombre;
            menu.setOpaque(false);
            menu.setLayout(new BoxLayout(menu, BoxLayout.Y_AXIS));
            panel.setBackground(Tema.AZUL_PROFUNDO);
            panel.setBorder(BorderFactory.createEmptyBorder(20, 10, 12, 10));
            JLabel tit = new JLabel(nombre.toUpperCase());
            tit.setFont(Tema.textoFuerte(11.5f));
            tit.setForeground(TEXTO_LATERAL);
            tit.setBorder(BorderFactory.createEmptyBorder(4, 12, 10, 0));
            panel.add(tit, BorderLayout.NORTH);
            panel.add(menu, BorderLayout.CENTER);
            // Esc en el panel lo cierra y vuelve al boton del grupo
            panel.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                    .put(javax.swing.KeyStroke.getKeyStroke("ESCAPE"), "cerrar");
            panel.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                    .put(javax.swing.KeyStroke.getKeyStroke("LEFT"), "cerrar");
            panel.getActionMap().put("cerrar", accion(() -> {
                abrir(false);
                requestFocusInWindow();
            }));
            setFont(Tema.textoFuerte(14));
            setForeground(Color.WHITE);
            setHorizontalAlignment(LEFT);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setRolloverEnabled(true);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 12));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
            setPreferredSize(new Dimension(150, 40));
            actualizarNombre();
            addActionListener(e -> {
                abrir(!abierto);
                if (abierto && !hijos.isEmpty()) {
                    hijos.get(0).requestFocusInWindow();
                }
            });
            addFocusListener(new java.awt.event.FocusAdapter() {
                @Override
                public void focusGained(java.awt.event.FocusEvent e) { repaint(); }

                @Override
                public void focusLost(java.awt.event.FocusEvent e) { repaint(); }
            });
            String[][] teclas = {{"DOWN", "bajar"}, {"UP", "subir"}, {"RIGHT", "abrir"}, {"LEFT", "cerrar"}};
            for (String[] t : teclas) {
                getInputMap().put(javax.swing.KeyStroke.getKeyStroke(t[0]), t[1]);
            }
            getActionMap().put("bajar", accion(() -> mover(this, 1)));
            getActionMap().put("subir", accion(() -> mover(this, -1)));
            getActionMap().put("abrir", accion(() -> {
                abrir(true);
                if (!hijos.isEmpty()) {
                    hijos.get(0).requestFocusInWindow();
                }
            }));
            getActionMap().put("cerrar", accion(() -> abrir(false)));
        }

        private javax.swing.AbstractAction accion(Runnable r) {
            return new javax.swing.AbstractAction() {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent e) {
                    r.run();
                }
            };
        }

        void abrir(boolean si) {
            if (abierto == si || barra == null || getRootPane() == null) {
                return;
            }
            abierto = si;
            javax.swing.JLayeredPane lp = getRootPane().getLayeredPane();
            if (si) {
                java.awt.Rectangle b = javax.swing.SwingUtilities.convertRectangle(barra.getParent(), barra.getBounds(), lp);
                panel.setBounds(b.x + b.width, b.y, 240, b.height);
                lp.add(panel, javax.swing.JLayeredPane.POPUP_LAYER);
                // clic fuera del panel (y fuera de este boton): se cierra
                clicAfuera = ev -> {
                    if (ev.getID() == java.awt.event.MouseEvent.MOUSE_PRESSED && ev.getSource() instanceof java.awt.Component) {
                        java.awt.Component c = (java.awt.Component) ev.getSource();
                        if (!javax.swing.SwingUtilities.isDescendingFrom(c, panel) && c != this) {
                            javax.swing.SwingUtilities.invokeLater(() -> abrir(false));
                        }
                    }
                };
                java.awt.Toolkit.getDefaultToolkit().addAWTEventListener(clicAfuera, java.awt.AWTEvent.MOUSE_EVENT_MASK);
                lp.addComponentListener(alCambiarTamano); // si cambia el tamaño de la ventana, se cierra
            } else {
                lp.remove(panel);
                lp.removeComponentListener(alCambiarTamano);
                if (clicAfuera != null) {
                    java.awt.Toolkit.getDefaultToolkit().removeAWTEventListener(clicAfuera);
                    clicAfuera = null;
                }
            }
            lp.revalidate();
            lp.repaint();
            actualizarNombre();
            repaint();
        }

        private void actualizarNombre() {
            getAccessibleContext().setAccessibleName(nombre + (abierto ? ", submenú abierto" : ", submenú"));
            setToolTipText(abierto ? "Cerrar " + nombre + " (Esc)" : "Abrir " + nombre);
        }

        private boolean tieneActiva() {
            for (Pestana p : hijos) {
                if (p.activa) {
                    return true;
                }
            }
            return false;
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Tema.suavizar(g);
            int w = getWidth() - 1, h = getHeight() - 1;
            boolean marcado = tieneActiva(); // estamos en una de sus opciones: se marca como seccion actual
            if (marcado) {
                g.setColor(Color.WHITE);
                g.fillRoundRect(0, 0, w, h, Tema.ARCO, Tema.ARCO);
            } else if (abierto || getModel().isRollover()) {
                g.setColor(Tema.AZUL_PROFUNDO);
                g.fillRoundRect(0, 0, w, h, Tema.ARCO, Tema.ARCO);
            }
            if (isFocusOwner()) {
                g.setColor(marcado ? Tema.AZUL : Color.WHITE);
                g.setStroke(new BasicStroke(2f));
                g.drawRoundRect(1, 1, w - 2, h - 2, Tema.ARCO, Tema.ARCO);
            }
            // flecha hacia la derecha: el submenu sale al lado
            int cx = w - 14, cy = getHeight() / 2;
            g.setColor(marcado ? Tema.GRIS_TEXTO : TEXTO_LATERAL);
            g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine(cx - 2, cy - 4, cx + 2, cy);
            g.drawLine(cx + 2, cy, cx - 2, cy + 4);
            g.dispose();
            setForeground(marcado ? Tema.AZUL_PROFUNDO : Color.WHITE);
            super.paintComponent(g0);
        }
    }

    // ------------------------------------------------------------------
    //  Clics en el mapa
    // ------------------------------------------------------------------

    private void clicEnMapa(PanelMapa.Modo modo, double x, double y, int nodo, int via) {
        switch (modo) {
            case AGREGAR_ENTREGA: {
                Parada p = paradaEn(x, y, "Entrega marcada en el mapa");
                String nombre = JOptionPane.showInputDialog(this, "Cliente o referencia para\n" + p.getDireccion()
                        + "\n(opcional):", "Nueva entrega", JOptionPane.QUESTION_MESSAGE);
                if (nombre == null) {
                    return; // cancelo
                }
                Parada nueva = new Parada(nombre.trim().isEmpty() ? p.getDireccion() : nombre.trim(),
                        p.getDireccion(), p.getLat(), p.getLon());
                nueva.setNodo(p.getNodo());
                nueva.setPrecision(p.getPrecision());
                app.agregarEntrega(nueva);
                break; // sigue en modo agregar para marcar varias seguidas
            }
            case FIJAR_BASE: {
                Parada p = paradaEn(x, y, "Punto de salida marcado en el mapa");
                Parada base = new Parada("Base", p.getDireccion(), p.getLat(), p.getLon());
                base.setNodo(p.getNodo());
                base.setPrecision(p.getPrecision());
                panelMapa.setModo(PanelMapa.Modo.NORMAL);
                app.setBase(base);
                break;
            }
            case CORREGIR_UBICACION: {
                int n = app.getMapa().getGrafo().nodoMasCercano(x, y, app.getAlcanzable());
                if (n < 0) {
                    return;
                }
                panelMapa.setModo(PanelMapa.Modo.NORMAL);
                try {
                    app.terminarCorreccion(Geo.lat(y), Geo.lon(x));
                    panelMapa.resaltar(Geo.lat(y), Geo.lon(x), null);
                } catch (java.io.IOException ex) {
                    JOptionPane.showMessageDialog(this, "No se pudo guardar la corrección: " + ex.getMessage());
                }
                break;
            }
            case REPORTAR_NOVEDAD:
                if (via < 0) {
                    JOptionPane.showMessageDialog(this, "Haga clic justo encima de una calle.");
                    return;
                }
                panelMapa.setViasSeleccionadas(List.of(via));
                panelMapa.setModo(PanelMapa.Modo.NORMAL);
                panelNovedades.reportar(via);
                seleccionar(3);
                actualizarTrafico();
                break;
            default:
                panelMapa.setViasSeleccionadas(via < 0 ? null : List.of(via));
                break;
        }
    }

    /** Parada en el punto del clic, pegada al nodo alcanzable mas cercano. */
    private Parada paradaEn(double x, double y, String precision) {
        int nodo = app.getMapa().getGrafo().nodoMasCercano(x, y, app.getAlcanzable());
        String dir = app.getGeo().describir(nodo);
        Parada p = new Parada(dir, dir, Geo.lat(y), Geo.lon(x));
        p.setNodo(nodo);
        p.setPrecision(precision);
        return p;
    }
}
