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
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import rutas.logica.AjustesRecibo;
import rutas.logica.Ubicaciones;

/**
 * Configuracion (solo el administrador). A la izquierda las opciones; cada una abre su propia
 * vista con su boton Guardar. A la derecha se ve como sale el recibo con lo que se va escribiendo.
 * Por ahora: Mi negocio (logo y datos del recibo) e Impresora (ancho del papel y demas).
 */
public class PanelConfiguracion extends JPanel {

    private static final String NEGOCIO = "Mi negocio";
    private static final String IMPRESORA = "Impresora";
    private static final String MEDIOS = "Medios de pago";
    private static final String CORREO = "Correo";
    private static final String REPARTIDOR = "Conexión";
    private static final String REPARTIDORES = "Domiciliarios";
    private static final String USUARIOS = "Usuarios";
    private static final String TIENDA = "Tienda en línea";
    /** Las vistas que tienen que ver con el recibo (a la derecha se ve cómo sale). */
    private static final List<String> CON_RECIBO = List.of("Mi negocio", "Impresora", "Medios de pago", "Correo");
    private static final String PREGUNTAR = "Preguntar cada vez";
    private static final String[] ANCHOS = {"58 mm", "80 mm", "Personalizado"};
    private static final String[] PPP = {"203 ppp (la más común)", "180 ppp", "300 ppp"};

    private final Aplicacion app;
    private AjustesRecibo ajustes;
    private final CardLayout vistas = new CardLayout();
    private final JPanel zonaVistas = new JPanel(vistas);
    private final List<Opcion> opciones = new ArrayList<>();
    private final VistaRecibo vista = new VistaRecibo();
    private JComponent columnaRecibo; // se esconde en lo que no tiene que ver con el recibo
    private final JLabel lblVista = new JLabel();

    // ---- Mi negocio ----
    private final JTextField campoNombre = Tema.campo("Ej. Tienda La Esquina");
    private final JTextField campoRazon = Tema.campo("Nombre del dueño o de la empresa");
    private final JTextField campoNit = Tema.campo("Ej. 901543586-2");
    private final JTextField campoTelefono = Tema.campo("Ej. 301 560 6295");
    private final JTextField campoDireccion = Tema.campo("Ej. Calle 15 # 7-20");
    private final JTextField campoCiudad = Tema.campo("Ej. Riohacha, La Guajira");
    private final JComboBox<String> comboRegimen = new JComboBox<>(AjustesRecibo.REGIMENES);
    private final JTextField campoGracias = Tema.campo("Ej. GRACIAS POR SU COMPRA");
    private final JTextField campoPie = Tema.campo("Ej. Cambios dentro de los 8 días con este recibo");
    private final JCheckBox chkSistema = Tema.casilla("Mostrar «Sistema DomiRuta» al final del recibo");
    private final VistaLogo vistaLogo = new VistaLogo();
    private final JSlider tonoLogo = new JSlider(30, 230, 160);
    private final JCheckBox chkTramado = Tema.casilla("Tramado de puntos (para fotos o degradados)");
    private final Tema.Parrafo msgNegocio = mensaje();
    private File logoElegido;      // logo nuevo que todavia no se ha guardado
    private boolean quitarLogo;

    // ---- Impresora ----
    private final JComboBox<String> comboImpresora = new JComboBox<>();
    private final JComboBox<String> comboAncho = new JComboBox<>(ANCHOS);
    private final JTextField campoAncho = Controles.campoNumero("mm");
    private final JTextField campoMargenIzq = Controles.campoNumero("mm");
    private final JTextField campoMargenDer = Controles.campoNumero("mm");
    private final JComboBox<AjustesRecibo.Letra> comboLetra = new JComboBox<>(AjustesRecibo.Letra.values());
    private final JComboBox<String> comboPpp = new JComboBox<>(PPP);
    private final JComboBox<Integer> comboCopias = new JComboBox<>(new Integer[]{1, 2, 3});
    private final JCheckBox chkCortar = Tema.casilla("Cortar el papel al terminar");
    private final JCheckBox chkCajon = Tema.casilla("Abrir el cajón del dinero al cobrar");
    private final JLabel lblUtil = new JLabel(" ");
    private final Tema.Parrafo msgImpresora = mensaje();
    private boolean llenando;

    // ---- Medios de pago ----
    private final java.util.Map<rutas.modelo.MetodoPago, Controles.Interruptor> interruptores =
            new java.util.EnumMap<>(rutas.modelo.MetodoPago.class);
    private final Tema.Parrafo msgMedios = mensaje();

    // ---- Correo ----
    private final JTextField campoCuenta = Tema.campo("Ej. mitienda@gmail.com");
    private final javax.swing.JPasswordField campoClave = Tema.campoClave("16 letras (ej. abcd efgh ijkl mnop)");
    private final JTextField campoRemitente = Tema.campo("Ej. Tienda La Esquina");
    private final JLabel lblClave = new JLabel(" ");
    private final Tema.Parrafo msgCorreo = mensaje();
    private final Tema.Boton btnPrueba = Tema.botonSecundario("Enviar correo de prueba");
    private String claveGuardada = "";
    // Repartidor (Supabase): la clave secreta queda solo en este computador
    private final JTextField campoNubeUrl = Tema.campo("https://xxxx.supabase.co");
    private final javax.swing.JPasswordField campoNubeClave = Tema.campoClave("Empieza por sb_secret_");
    private final JTextField campoNubePagina = Tema.campo("https://usuario.github.io/domiruta-repartidor/");
    private final JLabel lblNubeClave = new JLabel(" ");
    private final Tema.Parrafo msgNube = mensaje();
    private final Tema.Boton btnProbarNube = Tema.botonSecundario("Probar conexión");
    private String nubeClaveGuardada = "";

    public PanelConfiguracion(Aplicacion app) {
        super(new BorderLayout(20, 0));
        this.app = app;
        setBackground(Tema.GRIS_CLARO);
        setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));

        JPanel arriba = new JPanel(new BorderLayout());
        arriba.setOpaque(false);
        arriba.add(Tema.tituloPantalla("Configuración"), BorderLayout.WEST);
        arriba.setBorder(BorderFactory.createEmptyBorder(0, 0, 12, 0));
        add(arriba, BorderLayout.NORTH);

        add(menu(), BorderLayout.WEST);
        zonaVistas.setOpaque(false);
        zonaVistas.add(desliza(vistaNegocio()), NEGOCIO);
        zonaVistas.add(desliza(vistaImpresora()), IMPRESORA);
        zonaVistas.add(desliza(vistaMedios()), MEDIOS);
        zonaVistas.add(desliza(vistaCorreo()), CORREO);
        zonaVistas.add(desliza(vistaRepartidor()), REPARTIDOR);
        zonaVistas.add(desliza(vistaCuentas()), REPARTIDORES);
        if (app.getTienda() != null) {
            zonaVistas.add(desliza(new VistaTiendaEnLinea(app)), TIENDA);
            PanelUsuarios usuarios = new PanelUsuarios(app);
            JPanel envUsuarios = new JPanel(new BorderLayout());
            envUsuarios.setOpaque(false);
            envUsuarios.add(usuarios, BorderLayout.NORTH);
            zonaVistas.add(desliza(envUsuarios), USUARIOS);
        }
        add(zonaVistas, BorderLayout.CENTER);
        columnaRecibo = columnaRecibo();
        add(columnaRecibo, BorderLayout.EAST);

        for (JComboBox<?> c : List.of(comboRegimen, comboImpresora, comboAncho, comboPpp)) {
            FormularioProducto.compactar(c);
        }
        Tema.estiloCombo(comboRegimen, String::valueOf);
        Tema.estiloCombo(comboImpresora, String::valueOf);
        Tema.estiloCombo(comboAncho, String::valueOf);
        Tema.estiloCombo(comboLetra, AjustesRecibo.Letra::toString);
        Tema.estiloCombo(comboPpp, String::valueOf);
        Tema.estiloCombo(comboCopias, String::valueOf);

        // la vista previa cambia con cada tecla
        Runnable cambio = this::actualizarVista;
        for (JTextField t : List.of(campoNombre, campoRazon, campoNit, campoTelefono, campoDireccion, campoCiudad,
                campoGracias, campoPie, campoAncho, campoMargenIzq, campoMargenDer)) {
            t.getDocument().addDocumentListener(PanelProductos.alCambiar(cambio));
        }
        for (JComboBox<?> c : List.of(comboRegimen, comboAncho, comboLetra, comboPpp)) {
            c.addActionListener(e -> cambio.run());
        }
        chkSistema.addActionListener(e -> cambio.run());
        tonoLogo.addChangeListener(e -> cambio.run());
        chkTramado.addActionListener(e -> cambio.run());
        comboAncho.addActionListener(e -> {
            boolean personal = comboAncho.getSelectedIndex() == 2;
            campoAncho.setEditable(personal);
            campoAncho.setFocusable(personal);
            if (!personal && !llenando) {
                double ancho = comboAncho.getSelectedIndex() == 0 ? 58 : 80;
                campoAncho.setText(numero(ancho));
                String m = numero(AjustesRecibo.margenComun(ancho)); // margen de fabrica para ese rollo
                campoMargenIzq.setText(m);
                campoMargenDer.setText(m);
            }
        });

        // se lee de nuevo cada vez que se abre (otro administrador pudo cambiarlo)
        addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentShown(java.awt.event.ComponentEvent e) {
                cargar();
                cargarCuentas(); // un despacho pudo crear el enlace personal de alguien
            }
        });
        cargar();
        elegir(NEGOCIO);
    }

    // ------------------------------------------------------------------
    //  Menu de la izquierda
    // ------------------------------------------------------------------

    private JComponent menu() {
        Tema.Tarjeta t = new Tema.Tarjeta();
        t.setLayout(new BoxLayout(t, BoxLayout.Y_AXIS));
        t.setBorder(BorderFactory.createEmptyBorder(12, 10, 12, 10));
        JLabel tit = new JLabel("AJUSTES");
        tit.setFont(Tema.textoFuerte(11.5f));
        tit.setForeground(Tema.GRIS_TEXTO);
        tit.setBorder(BorderFactory.createEmptyBorder(2, 8, 8, 0));
        tit.setAlignmentX(LEFT_ALIGNMENT);
        t.add(tit);
        for (String[] o : new String[][]{{NEGOCIO, "Logo y datos del recibo"}, {IMPRESORA, "Papel, márgenes y corte"},
            {MEDIOS, "Cómo le pueden pagar"}, {CORREO, "Enviar recibos por Gmail"},
            {USUARIOS, "Cuentas de la caja y permisos"},
            {REPARTIDORES, "Quién lleva los domicilios"}, {REPARTIDOR, "Enlace para marcar entregas"},
            {TIENDA, "Pedidos desde la página"}}) {
            if ((USUARIOS.equals(o[0]) || TIENDA.equals(o[0])) && app.getTienda() == null) {
                continue;
            }
            Opcion op = new Opcion(o[0], o[1]);
            opciones.add(op);
            t.add(op);
            t.add(javax.swing.Box.createVerticalStrut(4));
        }
        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        p.add(t, BorderLayout.NORTH);
        p.setPreferredSize(new Dimension(210, 10));
        return p;
    }

    /** Abre la lista de domiciliarios (desde «¿Quién lleva esta salida?» o desde Salidas). */
    void mostrarDomiciliarios() {
        elegir(REPARTIDORES);
    }

    /** Abre Usuarios (cuentas de la caja y permisos). */
    void mostrarUsuarios() {
        elegir(USUARIOS);
    }

    private void elegir(String nombre) {
        vistas.show(zonaVistas, nombre);
        if (columnaRecibo != null) {
            columnaRecibo.setVisible(CON_RECIBO.contains(nombre));
        }
        if (REPARTIDORES.equals(nombre)) {
            cargarCuentas();
        }
        javax.swing.SwingUtilities.invokeLater(() -> deslizables.forEach(sc -> sc.getVerticalScrollBar().setValue(0)));
        for (Opcion o : opciones) {
            o.activa = o.nombre.equals(nombre);
            o.getAccessibleContext().setAccessibleDescription(o.activa ? "opción actual" : null);
            o.repaint();
        }
        lblVista.setText(IMPRESORA.equals(nombre) ? "Así sale el recibo (prueba)" : "Así sale el recibo");
        vista.prueba = IMPRESORA.equals(nombre);
        actualizarVista();
    }

    /** Opcion del menu: titulo y una linea gris de lo que tiene. */
    private class Opcion extends JButton {
        final String nombre;
        boolean activa;

        Opcion(String nombre, String detalle) {
            this.nombre = nombre;
            setLayout(new BorderLayout());
            JLabel t = new JLabel(nombre);
            t.setFont(Tema.textoFuerte(13.5f));
            JLabel d = new JLabel(detalle);
            d.setFont(Tema.texto(12));
            d.setForeground(Tema.GRIS_TEXTO);
            JPanel c = new JPanel();
            c.setOpaque(false);
            c.setLayout(new BoxLayout(c, BoxLayout.Y_AXIS));
            c.add(t);
            c.add(d);
            add(c);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setAlignmentX(LEFT_ALIGNMENT);
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 54));
            setPreferredSize(new Dimension(190, 54));
            getAccessibleContext().setAccessibleName(nombre + ": " + detalle);
            addActionListener(e -> elegir(nombre));
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
            if (activa) {
                g.setColor(Tema.AZUL_SUAVE);
                g.fillRoundRect(0, 0, getWidth(), getHeight(), Tema.ARCO, Tema.ARCO);
                g.setColor(Tema.AZUL);
                g.fillRect(0, 8, 3, getHeight() - 16); // marca de la opcion actual
            } else if (getModel().isRollover()) {
                g.setColor(Tema.GRIS_CLARO);
                g.fillRoundRect(0, 0, getWidth(), getHeight(), Tema.ARCO, Tema.ARCO);
            }
            if (isFocusOwner()) {
                g.setColor(Tema.AZUL);
                g.setStroke(new java.awt.BasicStroke(2f));
                g.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, Tema.ARCO, Tema.ARCO);
            }
            g.dispose();
            for (Component k : ((JPanel) getComponent(0)).getComponents()) {
                if (k instanceof JLabel && ((JLabel) k).getFont().isBold()) {
                    k.setForeground(activa ? Tema.AZUL_PROFUNDO : Tema.GRIS_OSCURO);
                }
            }
            super.paintComponent(g0);
        }
    }

    // ------------------------------------------------------------------
    //  Mi negocio
    // ------------------------------------------------------------------

    private JComponent vistaNegocio() {
        Tema.Tarjeta t = tarjeta();
        t.add(Tema.seccion("Mi negocio"));
        t.add(nota("Estos datos y el logo salen en el recibo de cada venta. Cada tienda pone los suyos."));
        t.add(Tema.espacio(8));

        // logo
        JPanel filaLogo = new JPanel(new BorderLayout(16, 0));
        filaLogo.setOpaque(false);
        filaLogo.add(vistaLogo, BorderLayout.WEST);
        JPanel botones = columna();
        JLabel tl = etiqueta("Logo");
        botones.add(tl);
        Tema.Parrafo ayuda = nota("La impresora térmica solo imprime negro: así se verá. Use un logo sencillo y con buen"
                + " contraste. El mismo logo sale en el recibo carta (PDF y correo) y como su marca de agua.");
        botones.add(ayuda);
        botones.add(Tema.espacio(6));
        Tema.Boton subir = Tema.botonSecundario("Subir logo");
        subir.setIcon(Iconos.de("foto", 18, Tema.AZUL));
        subir.addActionListener(e -> elegirLogo());
        Tema.Boton quitar = Tema.botonPlano("Quitar");
        quitar.addActionListener(e -> {
            logoElegido = null;
            quitarLogo = true;
            actualizarVista();
            decir(msgNegocio, "El logo se quita al oprimir «Guardar cambios»", true);
        });
        botones.add(Tema.fila(8, subir, quitar));
        botones.add(Tema.espacio(8));
        JLabel tt = etiqueta("Tono del logo (más a la derecha, más negro)");
        tt.setLabelFor(tonoLogo);
        botones.add(tt);
        tonoLogo.setOpaque(false);
        tonoLogo.setAlignmentX(LEFT_ALIGNMENT);
        tonoLogo.getAccessibleContext().setAccessibleName("Tono del logo");
        tonoLogo.setMaximumSize(new Dimension(320, tonoLogo.getPreferredSize().height));
        botones.add(tonoLogo);
        botones.add(chkTramado);
        filaLogo.add(botones, BorderLayout.CENTER);
        filaLogo.setAlignmentX(LEFT_ALIGNMENT);
        t.add(filaLogo);
        t.add(Tema.espacio(6));

        JPanel g = grilla();
        poner(g, 0, 0, 2, "Nombre del negocio", campoNombre);
        poner(g, 0, 1, 1, "Razón social", campoRazon);
        poner(g, 1, 1, 1, "NIT o cédula", campoNit);
        poner(g, 0, 2, 1, "Dirección", campoDireccion);
        poner(g, 1, 2, 1, "Teléfono", campoTelefono);
        poner(g, 0, 3, 1, "Ciudad", campoCiudad);
        poner(g, 1, 3, 1, "Régimen", comboRegimen);
        poner(g, 0, 4, 2, "Mensaje de agradecimiento", campoGracias);
        poner(g, 0, 5, 2, "Pie del recibo (opcional)", campoPie);
        t.add(g);
        t.add(Tema.espacio(8));
        t.add(chkSistema);
        t.add(Tema.espacio(12));
        t.add(pie(msgNegocio, this::guardarNegocio));
        return t;
    }

    private void elegirLogo() {
        File f = Archivos.abrir(this, "Escoger el logo del negocio", "png", "jpg", "jpeg");
        if (f == null) {
            return;
        }
        if (f.length() > 2 * 1024 * 1024) {
            decir(msgNegocio, "Ese logo pesa más de 2 MB. Escoja uno más liviano.", false);
            return;
        }
        try {
            if (javax.imageio.ImageIO.read(f) == null) {
                decir(msgNegocio, "No se pudo abrir esa imagen. Use PNG o JPG.", false);
                return;
            }
        } catch (IOException e) {
            decir(msgNegocio, "No se pudo abrir esa imagen. Use PNG o JPG.", false);
            return;
        }
        logoElegido = f;
        quitarLogo = false;
        actualizarVista();
        decir(msgNegocio, "El logo se guarda al oprimir «Guardar cambios»", true);
    }

    private void guardarNegocio() {
        try {
            AjustesRecibo a = leerPantalla(true);
            if (logoElegido != null) {
                String n = logoElegido.getName().toLowerCase();
                File destino = new File(Ubicaciones.carpetaUsuario(), "logo-negocio" + (n.endsWith(".png") ? ".png" : ".jpg"));
                Files.copy(logoElegido.toPath(), destino.toPath(), StandardCopyOption.REPLACE_EXISTING);
                a.logo = destino.getAbsolutePath();
            }
            a.guardar(app.getTienda());
            ajustes = a;
            logoElegido = null;
            quitarLogo = false;
            app.tiendaCambio(); // el logo y los datos también van a la tienda en línea
            decir(msgNegocio, "Cambios guardados. Los próximos recibos salen así.", true);
            Aviso.mostrar(this, Aviso.Tipo.BIEN, "Mi negocio", "Cambios guardados");
        } catch (IllegalArgumentException e) {
            decir(msgNegocio, e.getMessage(), false);
        } catch (SQLException | IOException e) {
            decir(msgNegocio, "No se pudo guardar: " + e.getMessage(), false);
        }
    }

    // ------------------------------------------------------------------
    //  Medios de pago
    // ------------------------------------------------------------------

    private JComponent vistaMedios() {
        Tema.Tarjeta t = tarjeta();
        t.add(Tema.seccion("Medios de pago"));
        t.add(nota("Active los que acepta su tienda. En el cobro solo salen esos. Efectivo siempre está."));
        t.add(Tema.espacio(10));
        JPanel lista = new JPanel(new java.awt.GridLayout(0, 1, 0, 0));
        lista.setOpaque(false);
        lista.setAlignmentX(LEFT_ALIGNMENT);
        for (rutas.modelo.MetodoPago m : rutas.modelo.MetodoPago.values()) {
            JPanel fila = new JPanel(new BorderLayout(14, 0));
            fila.setOpaque(false);
            fila.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Tema.BORDE),
                    BorderFactory.createEmptyBorder(9, 4, 9, 0)));
            JLabel logo = new JLabel(new CapaCobro.LogoPago(m.getLogo(), 30));
            logo.setHorizontalAlignment(SwingConstants.CENTER);
            logo.setPreferredSize(new Dimension(84, 34));
            fila.add(logo, BorderLayout.WEST);
            JPanel txt = columna();
            JLabel n = new JLabel(m.getTexto());
            n.setFont(Tema.textoFuerte(14));
            n.setForeground(Tema.GRIS_OSCURO);
            txt.add(n);
            JLabel d = new JLabel(m.getDetalle());
            d.setFont(Tema.texto(12.5f));
            d.setForeground(Tema.GRIS_TEXTO);
            d.setToolTipText(m.getDetalle());
            txt.add(d);
            fila.add(txt, BorderLayout.CENTER);
            Controles.Interruptor sw = new Controles.Interruptor("");
            sw.getAccessibleContext().setAccessibleName("Aceptar " + m.getTexto());
            sw.setToolTipText(m == rutas.modelo.MetodoPago.EFECTIVO ? "Efectivo siempre está activo"
                    : "Aceptar " + m.getTexto());
            if (m == rutas.modelo.MetodoPago.EFECTIVO) {
                sw.setSelected(true);
                sw.setEnabled(false);
            }
            interruptores.put(m, sw);
            fila.add(sw, BorderLayout.EAST);
            lista.add(fila);
        }
        t.add(lista);
        t.add(Tema.espacio(12));
        t.add(pie(msgMedios, this::guardarMedios));
        return t;
    }

    private void cargarMedios() {
        try {
            java.util.List<rutas.modelo.MetodoPago> activos = rutas.logica.MediosPago.activos(app.getTienda());
            interruptores.forEach((m, sw) -> sw.setSelected(activos.contains(m)));
            msgMedios.setText(" ");
        } catch (SQLException e) {
            decir(msgMedios, "No se pudieron leer los medios de pago: " + e.getMessage(), false);
        }
    }

    private void guardarMedios() {
        java.util.List<rutas.modelo.MetodoPago> elegidos = new ArrayList<>();
        interruptores.forEach((m, sw) -> {
            if (sw.isSelected()) {
                elegidos.add(m);
            }
        });
        try {
            rutas.logica.MediosPago.guardar(app.getTienda(), elegidos);
            app.tiendaCambio();
            int n = Math.max(1, elegidos.size());
            decir(msgMedios, "Guardado. En el cobro salen " + n + (n == 1 ? " medio de pago." : " medios de pago."), true);
            Aviso.mostrar(this, Aviso.Tipo.BIEN, "Medios de pago", "Cambios guardados");
        } catch (SQLException e) {
            decir(msgMedios, "No se pudo guardar: " + e.getMessage(), false);
        }
    }

    // ------------------------------------------------------------------
    //  Correo (Gmail con contraseña de aplicación)
    // ------------------------------------------------------------------

    private JComponent vistaCorreo() {
        Tema.Tarjeta t = tarjeta();
        t.add(Tema.seccion("Correo"));
        t.add(nota("Con un Gmail de la tienda, el recibo de cada venta se manda en PDF al correo del cliente con un"
                + " clic. Se configura una sola vez."));
        t.add(Tema.espacio(6));
        JPanel g = grilla();
        poner(g, 0, 0, 2, "Gmail de la tienda", campoCuenta);
        poner(g, 0, 1, 2, "Contraseña de aplicación", campoClave);
        poner(g, 0, 2, 2, "Nombre que ve el cliente", campoRemitente);
        t.add(g);
        lblClave.setFont(Tema.texto(12.5f));
        lblClave.setForeground(Tema.GRIS_TEXTO);
        lblClave.setAlignmentX(LEFT_ALIGNMENT);
        t.add(lblClave);
        t.add(Tema.espacio(12));
        JLabel tp = new JLabel("Cómo sacar la contraseña de aplicación (3 minutos)");
        tp.setFont(Tema.textoFuerte(13.5f));
        tp.setForeground(Tema.AZUL_PROFUNDO);
        tp.setAlignmentX(LEFT_ALIGNMENT);
        t.add(tp);
        t.add(Tema.espacio(4));
        String[] pasos = {
            "1. Entre a myaccount.google.com con el Gmail de la tienda y abra «Seguridad».",
            "2. Active la «Verificación en dos pasos» (Google le manda un código al celular).",
            "3. Vaya a myaccount.google.com/apppasswords, escriba DomiRuta y oprima «Crear».",
            "4. Copie las 16 letras y péguelas arriba. No es su contraseña normal: no la comparta con nadie.",
            "5. Oprima «Guardar cambios» y luego «Enviar correo de prueba»."};
        for (String p : pasos) {
            Tema.Parrafo l = Tema.parrafo(p, 560);
            l.setFont(Tema.texto(13));
            l.setForeground(Tema.GRIS_OSCURO);
            l.setAlignmentX(LEFT_ALIGNMENT);
            t.add(l);
        }
        t.add(Tema.espacio(4));
        t.add(nota("Si Google dice que la opción no está disponible, es porque falta la verificación en dos pasos."
                + " La clave queda guardada solo en este computador."));
        t.add(Tema.espacio(12));
        btnPrueba.setIcon(new CapaCobro.LogoPago("/imagenes/archivos/gmail.png", 18));
        btnPrueba.setIconTextGap(8);
        btnPrueba.setAlignmentX(LEFT_ALIGNMENT);
        btnPrueba.setToolTipText("Manda un correo al mismo Gmail de la tienda para ver que todo quedó bien");
        btnPrueba.addActionListener(e -> probarCorreo());
        t.add(btnPrueba);
        t.add(Tema.espacio(12));
        t.add(pie(msgCorreo, this::guardarCorreo));
        return t;
    }

    private void cargarCorreo() {
        try {
            rutas.logica.AjustesCorreo a = rutas.logica.AjustesCorreo.leer(app.getTienda());
            campoCuenta.setText(a.cuenta);
            campoRemitente.setText(a.remitente);
            claveGuardada = a.clave;
            campoClave.setText("");
            lblClave.setText(a.clave.isEmpty() ? "Todavía no hay contraseña guardada."
                    : "Hay una contraseña guardada. Escriba otra solo si la cambió en Google.");
            msgCorreo.setText(" ");
        } catch (SQLException e) {
            decir(msgCorreo, "No se pudo leer el correo: " + e.getMessage(), false);
        }
    }

    /** Lo escrito en pantalla (si la clave esta vacia, se deja la guardada). */
    private rutas.logica.AjustesCorreo correoEnPantalla() {
        rutas.logica.AjustesCorreo a = new rutas.logica.AjustesCorreo();
        a.cuenta = campoCuenta.getText().trim();
        String nueva = new String(campoClave.getPassword()).trim();
        a.clave = nueva.isEmpty() ? claveGuardada : nueva;
        a.remitente = campoRemitente.getText().trim();
        return a;
    }

    private void guardarCorreo() {
        try {
            rutas.logica.AjustesCorreo a = correoEnPantalla();
            a.guardar(app.getTienda());
            cargarCorreo();
            decir(msgCorreo, a.listo() ? "Guardado. Pruebe con «Enviar correo de prueba»."
                    : "Guardado. Falta el Gmail o la contraseña para poder enviar.", true);
            Aviso.mostrar(this, Aviso.Tipo.BIEN, "Correo", "Cambios guardados");
        } catch (IllegalArgumentException e) {
            decir(msgCorreo, e.getMessage(), false);
        } catch (SQLException e) {
            decir(msgCorreo, "No se pudo guardar: " + e.getMessage(), false);
        }
    }

    /** Manda un correo al mismo Gmail de la tienda (en segundo plano, sin trabar la pantalla). */
    private void probarCorreo() {
        rutas.logica.AjustesCorreo a = correoEnPantalla();
        if (!a.listo()) {
            decir(msgCorreo, "Escriba el Gmail y la contraseña de aplicación de 16 letras.", false);
            return;
        }
        btnPrueba.setEnabled(false);
        btnPrueba.setText("Enviando…");
        msgCorreo.setForeground(Tema.GRIS_TEXTO);
        msgCorreo.setText("Enviando el correo de prueba…");
        String negocio = campoNombre.getText().isBlank() ? "DomiRuta" : campoNombre.getText().trim();
        String remitente = a.remitente.isBlank() ? negocio : a.remitente;
        new javax.swing.SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() {
                try {
                    rutas.logica.CorreoSmtp.gmail(a.cuenta, a.clave).enviar(remitente, a.cuenta,
                            "Prueba de DomiRuta", "¡Listo! Si le llegó este correo, " + negocio
                            + " ya puede mandar los recibos por correo.", null, null, null);
                    return null;
                } catch (rutas.logica.CorreoSmtp.ErrorCorreo e) {
                    return e.getMessage();
                }
            }

            @Override
            protected void done() {
                btnPrueba.setEnabled(true);
                btnPrueba.setText("Enviar correo de prueba");
                String r;
                try {
                    r = get();
                } catch (Exception e) {
                    r = e.getMessage();
                }
                if (r == null) {
                    decir(msgCorreo, "Correo de prueba enviado a " + a.cuenta + ". Revise la bandeja de entrada.", true);
                } else {
                    decir(msgCorreo, r, false);
                }
            }
        }.execute();
    }

    // ------------------------------------------------------------------
    //  Repartidor (Supabase): el enlace donde marca las entregas
    // ------------------------------------------------------------------

    private JComponent vistaRepartidor() {
        Tema.Tarjeta t = tarjeta();
        t.add(Tema.seccion("Conexión"));
        t.add(nota("Con esto, al despachar le llega al domiciliario un enlace solo para esa salida: marca cada entrega"
                + " desde el celular, escribe a la tienda y se ve en el mapa. Sin esto todo funciona igual, pero las"
                + " salidas se cierran a mano con «Volvió». Se configura una sola vez."));
        t.add(Tema.espacio(6));
        JPanel g = grilla();
        poner(g, 0, 0, 2, "Dirección del proyecto de Supabase", campoNubeUrl);
        poner(g, 0, 1, 2, "Clave secreta (sb_secret_…)", campoNubeClave);
        poner(g, 0, 2, 2, "Página de los domiciliarios", campoNubePagina);
        t.add(g);
        lblNubeClave.setFont(Tema.texto(12.5f));
        lblNubeClave.setForeground(Tema.GRIS_TEXTO);
        lblNubeClave.setAlignmentX(LEFT_ALIGNMENT);
        t.add(lblNubeClave);
        t.add(Tema.espacio(12));
        JLabel tp = new JLabel("Dónde está la clave secreta");
        tp.setFont(Tema.textoFuerte(13.5f));
        tp.setForeground(Tema.AZUL_PROFUNDO);
        tp.setAlignmentX(LEFT_ALIGNMENT);
        t.add(tp);
        t.add(Tema.espacio(4));
        String[] pasos = {
            "1. En supabase.com abra su proyecto y vaya a Project Settings › API Keys.",
            "2. En «Secret keys» copie la clave (empieza por sb_secret_) y péguela arriba.",
            "3. Oprima «Guardar cambios» y luego «Probar conexión».",
            "No la comparta con nadie ni la ponga en la página del repartidor: esa usa la clave pública."};
        for (String p : pasos) {
            Tema.Parrafo l = Tema.parrafo(p, 560);
            l.setFont(Tema.texto(13));
            l.setForeground(Tema.GRIS_OSCURO);
            l.setAlignmentX(LEFT_ALIGNMENT);
            t.add(l);
        }
        t.add(Tema.espacio(12));
        btnProbarNube.setAlignmentX(LEFT_ALIGNMENT);
        btnProbarNube.addActionListener(e -> probarNube());
        t.add(btnProbarNube);
        t.add(Tema.espacio(12));
        t.add(pie(msgNube, this::guardarNube));
        return t;
    }

    private void cargarNube() {
        if (app.getTienda() == null) {
            return;
        }
        try {
            rutas.logica.NubeRepartidor.Ajustes a = rutas.logica.NubeRepartidor.Ajustes.leer(app.getTienda());
            campoNubeUrl.setText(a.url);
            campoNubePagina.setText(a.pagina);
            nubeClaveGuardada = a.clave;
            campoNubeClave.setText("");
            lblNubeClave.setText(a.clave.isEmpty() ? "Todavía no hay clave guardada."
                    : "Hay una clave guardada. Escriba otra solo si la cambió en Supabase.");
            msgNube.setText(" ");
        } catch (SQLException e) {
            decir(msgNube, "No se pudo leer: " + e.getMessage(), false);
        }
    }

    private rutas.logica.NubeRepartidor.Ajustes nubeEnPantalla() {
        rutas.logica.NubeRepartidor.Ajustes a = new rutas.logica.NubeRepartidor.Ajustes();
        a.url = campoNubeUrl.getText().trim();
        String nueva = new String(campoNubeClave.getPassword()).trim();
        a.clave = nueva.isEmpty() ? nubeClaveGuardada : nueva;
        a.pagina = campoNubePagina.getText().trim();
        return a;
    }

    private void guardarNube() {
        try {
            rutas.logica.NubeRepartidor.Ajustes a = nubeEnPantalla();
            a.guardar(app.getTienda());
            app.nubeCambio();
            cargarNube();
            decir(msgNube, a.listo() ? "Guardado. Pruebe con «Probar conexión»."
                    : "Guardado. Falta la dirección, la clave o el enlace.", true);
            Aviso.mostrar(this, Aviso.Tipo.BIEN, "Repartidor", "Cambios guardados");
            app.avisar();
        } catch (IllegalArgumentException e) {
            decir(msgNube, e.getMessage(), false);
        } catch (SQLException e) {
            decir(msgNube, "No se pudo guardar: " + e.getMessage(), false);
        }
    }

    private void probarNube() {
        rutas.logica.NubeRepartidor.Ajustes a = nubeEnPantalla();
        if (!a.listo()) {
            decir(msgNube, "Escriba la dirección del proyecto, la clave secreta y el enlace de la página.", false);
            return;
        }
        btnProbarNube.setEnabled(false);
        btnProbarNube.setText("Probando…");
        new javax.swing.SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() {
                try {
                    new rutas.logica.NubeRepartidor(a).probar();
                    return null;
                } catch (java.io.IOException e) {
                    return e.getMessage();
                }
            }

            @Override
            protected void done() {
                btnProbarNube.setEnabled(true);
                btnProbarNube.setText("Probar conexión");
                String r;
                try {
                    r = get();
                } catch (Exception e) {
                    r = e.getMessage();
                }
                if (r == null) {
                    decir(msgNube, "Conexión correcta. Las próximas salidas llevan el enlace para el domiciliario.", true);
                } else {
                    decir(msgNube, r, false);
                }
            }
        }.execute();
    }

    // ------------------------------------------------------------------
    //  Domiciliarios: quién lleva los domicilios (sin contraseñas)
    // ------------------------------------------------------------------

    private final JPanel listaCuentas = new JPanel();
    private final Tema.Parrafo msgCuentas = mensaje();

    private JComponent vistaCuentas() {
        Tema.Tarjeta t = tarjeta();
        t.add(Tema.seccion("Domiciliarios"));
        t.add(nota("Quienes llevan los domicilios: los fijos de la tienda, los independientes que prestan el servicio"
                + " cuando pueden y los grupos de domicilios. Cada uno entra a la app con su celular y una clave de 6"
                + " números: con «Crear clave» se la manda por WhatsApp. Una sola sesión a la vez: si entra en otro"
                + " celular, el anterior se cierra y aquí sale el aviso."));
        t.add(Tema.espacio(10));
        Tema.Boton nuevo = Tema.botonPrimario("Nuevo domiciliario");
        nuevo.setAlignmentX(LEFT_ALIGNMENT);
        nuevo.addActionListener(e -> formularioDomiciliario(null));
        t.add(nuevo);
        t.add(Tema.espacio(10));
        listaCuentas.setOpaque(false);
        listaCuentas.setLayout(new BoxLayout(listaCuentas, BoxLayout.Y_AXIS));
        listaCuentas.setAlignmentX(LEFT_ALIGNMENT);
        t.add(listaCuentas);
        t.add(Tema.espacio(8));
        t.add(msgCuentas);
        return t;
    }

    private void cargarCuentas() {
        listaCuentas.removeAll();
        List<rutas.modelo.Domiciliario> l = app.getDomiciliarios();
        for (rutas.modelo.Domiciliario d : l) {
            listaCuentas.add(filaDomiciliario(d));
        }
        msgCuentas.setForeground(Tema.GRIS_TEXTO);
        msgCuentas.setText(l.isEmpty() ? "Todavía no hay domiciliarios. También los puede agregar al despachar (\u00abOtro\u00bb)." : " ");
        listaCuentas.revalidate();
        listaCuentas.repaint();
    }

    private JComponent filaDomiciliario(rutas.modelo.Domiciliario d) {
        JPanel f = new JPanel(new BorderLayout(10, 0));
        f.setOpaque(false);
        f.setAlignmentX(LEFT_ALIGNMENT);
        f.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Tema.BORDE),
                BorderFactory.createEmptyBorder(8, 0, 8, 0)));
        JPanel izq = new JPanel();
        izq.setOpaque(false);
        izq.setLayout(new BoxLayout(izq, BoxLayout.Y_AXIS));
        JLabel n = new JLabel(d.nombre);
        n.setFont(Tema.textoFuerte(14));
        n.setForeground(d.activo ? Tema.GRIS_OSCURO : Tema.GRIS_TEXTO);
        JLabel det = new JLabel(d.tipo + (d.celular.isEmpty() ? "" : " · Cel. " + d.celular));
        det.setFont(Tema.texto(13));
        det.setForeground(Tema.GRIS_TEXTO);
        izq.add(n);
        izq.add(det);
        if (!d.activo) {
            JLabel e = new JLabel("No aparece al despachar");
            e.setFont(Tema.textoFuerte(12.5f));
            e.setForeground(Tema.ERROR_TEXTO);
            izq.add(e);
        } else {
            JLabel e = new JLabel(textoApp(d));
            Aplicacion.Disponibilidad disp = app.disponibilidad(d);
            e.setFont(disp == Aplicacion.Disponibilidad.DISPONIBLE ? Tema.textoFuerte(12.5f) : Tema.texto(12.5f));
            e.setForeground(disp == Aplicacion.Disponibilidad.DISPONIBLE ? Tema.EXITO_TEXTO : Tema.GRIS_TEXTO);
            izq.add(e);
        }
        Tema.Boton editar = Tema.botonPequeno("Editar");
        editar.addActionListener(a -> formularioDomiciliario(d));
        Tema.Boton quitar = Tema.botonPequeno("Quitar");
        long abiertas = app.salidasAbiertas().stream().filter(x -> x.repartidor.equals(d.nombre)).count();
        quitar.addActionListener(a -> Alerta.confirmar(this, "¿Quitar a " + d.nombre + " de la lista?",
                (abiertas > 0 ? PanelPedidos.primerNombre(d.nombre) + " tiene " + abiertas + (abiertas == 1
                        ? " salida abierta: sigue en «Salidas» y en el mapa hasta que la cierre o la cancele. "
                        : " salidas abiertas: siguen en «Salidas» y en el mapa hasta que las cierre o las cancele. ") : "")
                + "Las salidas que ya hizo no se borran." + (d.enNube() ? " Ya no podrá entrar a la app." : ""),
                "Sí, quitar", "Volver", () -> quitarDomiciliario(d.id)));
        rutas.logica.NubeRepartidor.EstadoRepartidor est = app.estadoNube(d);
        boolean conClave = est != null && est.conClave;
        Tema.Boton clave = Tema.botonPequeno(conClave ? "Nueva clave" : "Crear clave");
        clave.setToolTipText("Entra a la app con su celular y una clave de 6 números (se la manda por WhatsApp)");
        clave.addActionListener(a -> {
            if (conClave) {
                Alerta.confirmar(this, "¿Nueva clave para " + d.nombre + "?",
                        "La clave que tiene deja de servir y su sesión se cierra. Después se la manda por WhatsApp.",
                        "Sí, nueva clave", "Volver", () -> crearClave(d, clave));
            } else {
                crearClave(d, clave);
            }
        });
        clave.setEnabled(d.activo);
        JComponent botones = Tema.fila(6, clave, editar, quitar);
        f.add(izq, BorderLayout.CENTER);
        f.add(botones, BorderLayout.EAST);
        f.setMaximumSize(new Dimension(Integer.MAX_VALUE, f.getPreferredSize().height));
        return f;
    }

    /** «Disponible», «En ruta · le faltan 2», «No disponible · abrió la app a las 9:40» o que todavía no tiene su enlace. */
    private String textoApp(rutas.modelo.Domiciliario d) {
        if (!d.enNube()) {
            return "Sin clave: todavía no puede entrar a la app";
        }
        rutas.logica.NubeRepartidor.EstadoRepartidor ec = app.estadoNube(d);
        if (ec != null && !ec.conClave) {
            return "Sin clave: todavía no puede entrar a la app";
        }
        if (ec != null && ec.sesionEn == null) {
            return "Tiene clave · no ha entrado (o cerró sesión)";
        }
        Aplicacion.Disponibilidad disp = app.disponibilidad(d);
        if (disp == Aplicacion.Disponibilidad.SIN_APP) {
            return "Tiene su enlace personal";
        }
        String t = ElegirDomiciliario.textoEstado(app, d, disp);
        rutas.logica.NubeRepartidor.EstadoRepartidor e = app.estadoNube(d);
        if (e != null && e.vistoEn != null) {
            java.time.LocalDateTime v = e.vistoEn;
            String cuando = v.toLocalDate().equals(java.time.LocalDate.now())
                    ? "a las " + rutas.modelo.Ruta.FORMATO_HORA.format(v)
                    : "el " + v.format(java.time.format.DateTimeFormatter.ofPattern("d/MM"));
            t += " · abrió la app " + cuando;
        } else if (e != null) {
            t += " · todavía no abre la app";
        }
        return t;
    }

    /** Una llamada a Supabase en otro hilo; alTerminar (puede ser null) corre en la pantalla con el error o null. */
    private interface LlamadaNube {
        void hacer(rutas.logica.NubeRepartidor nube) throws java.io.IOException;
    }

    private void enNube(LlamadaNube llamada, java.util.function.Consumer<String> alTerminar) {
        rutas.logica.NubeRepartidor nube = app.getNube();
        if (nube == null) {
            if (alTerminar != null) {
                alTerminar.accept("Falta configurar la conexión con Supabase (arriba, en «Conexión»).");
            }
            return;
        }
        new javax.swing.SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() {
                try {
                    llamada.hacer(nube);
                    return null;
                } catch (java.io.IOException ex) {
                    return ex.getMessage();
                }
            }

            @Override
            protected void done() {
                String err;
                try {
                    err = get();
                } catch (Exception ex) {
                    err = ex.getMessage();
                }
                if (alTerminar != null) {
                    alTerminar.accept(err);
                } else if (err != null) {
                    rutas.logica.Ubicaciones.registrarError(new java.io.IOException(err));
                }
            }
        }.execute();
    }

    /** Primero se quita en Supabase (que el enlace deje de servir) y después de la lista. */
    private void quitarDomiciliario(long id) {
        rutas.modelo.Domiciliario d = app.domiciliarioActual(id);
        if (d == null) {
            cargarCuentas();
            return;
        }
        Runnable local = () -> {
            try {
                app.eliminarDomiciliario(d);
            } catch (java.io.IOException ex) {
                decir(msgCuentas, ex.getMessage(), false);
            }
            cargarCuentas();
        };
        if (!d.enNube() || app.getNube() == null) {
            local.run();
            return;
        }
        enNube(n2 -> n2.borrarRepartidor(d.idNube), err -> {
            if (err != null) {
                decir(msgCuentas, "No se quitó: su enlace personal sigue sirviendo hasta que haya internet ("
                        + err + "). Intente otra vez.", false);
                return;
            }
            local.run();
        });
    }

    /**
     * Le crea una clave de 6 números (lo crea en Supabase si hace falta), la muestra una sola vez y la manda por
     * WhatsApp con el enlace de la app. Aquí no se guarda: en Supabase queda cifrada.
     */
    private void crearClave(rutas.modelo.Domiciliario vista, Tema.Boton boton) {
        rutas.modelo.Domiciliario d = app.domiciliarioActual(vista.id);
        if (d == null) {
            cargarCuentas();
            return;
        }
        if (rutas.logica.Avisos.numeroWhatsApp(d.celular).isEmpty()) {
            decir(msgCuentas, "Ponga el celular de " + d.nombre + " (con «Editar»): es su usuario para entrar.", false);
            return;
        }
        String idAntes = d.idNube;
        String nueva = rutas.logica.NubeRepartidor.claveNueva();
        String[] resp = {""};
        boton.setEnabled(false);
        enNube(n2 -> {
            if (d.enNube() && !n2.existeRepartidor(d.idNube)) {
                d.idNube = "";
                d.enlace = "";
            }
            if (!d.enNube()) {
                String[] r = n2.crearRepartidor(d.nombre, d.celular);
                d.idNube = r[0];
                d.enlace = r[1];
            }
            resp[0] = n2.ponerClave(d.idNube, nueva);
        }, err -> {
            boton.setEnabled(true);
            if (!d.idNube.equals(idAntes)) {
                guardarEnlace(d);
            }
            if (err != null || !"ok".equals(resp[0])) {
                decir(msgCuentas, "No se pudo poner la clave: " + (err != null ? err : resp[0]), false);
                return;
            }
            cargarCuentas();
            rutas.logica.NubeRepartidor.Ajustes aj = app.getAjustesNube();
            String pagina = aj == null ? "" : aj.pagina;
            mostrarClave(d, nueva, pagina);
        });
    }

    /** El mensaje con la clave: la clave va sola en su línea (en WhatsApp se toca y se copia fácil). */
    public static String mensajeClave(rutas.modelo.Domiciliario d, String clave, String pagina) {
        return "Hola " + PanelPedidos.primerNombre(d.nombre) + ", esta es tu clave de DomiRuta:\n\n"
                + clave + "\n\n"
                + "Para entrar usa tu celular " + d.celular.replaceAll("\\D", "") + " y esa clave aquí:\n"
                + pagina + "\n\n"
                + "Puedes cambiarla después en la app (Mis salidas › Cambiar mi clave). No se la des a nadie.";
    }

    /** La clave se ve una sola vez: mandarla por WhatsApp, por correo o copiarla. */
    private void mostrarClave(rutas.modelo.Domiciliario d, String clave, String pagina) {
        String msj = mensajeClave(d, clave, pagina);
        JPanel t = Capa.tarjeta(480, 28, 32);
        JLabel ti = new JLabel("Clave de " + PanelPedidos.primerNombre(d.nombre));
        ti.setFont(Tema.subtitulo(17));
        ti.setForeground(Tema.GRIS_OSCURO);
        ti.setAlignmentX(LEFT_ALIGNMENT);
        t.add(ti);
        t.add(Tema.espacio(8));
        JLabel cl = new JLabel(clave.substring(0, 3) + " " + clave.substring(3)); // 939 113: se lee fácil en voz alta
        cl.setFont(Tema.titulo(34));
        cl.setForeground(Tema.AZUL_PROFUNDO);
        cl.setAlignmentX(LEFT_ALIGNMENT);
        t.add(cl);
        t.add(Tema.espacio(6));
        t.add(nota("Se ve solo esta vez: no queda guardada en ningún lado. Mándesela ahora."));
        Tema.Parrafo estado = mensaje();
        t.add(estado);
        t.add(Tema.espacio(12));
        rutas.logica.AjustesCorreo ac = null;
        try {
            ac = app.getTienda() == null ? null : rutas.logica.AjustesCorreo.leer(app.getTienda());
        } catch (java.sql.SQLException ex) {
            // sin correo configurado: no sale el botón
        }
        boolean conCorreo = ac != null && ac.listo() && rutas.logica.CorreoSmtp.esCorreo(d.correo);
        Tema.Boton wa = Tema.botonPrimario("Mandar por WhatsApp");
        Tema.Boton mail = Tema.botonSecundario("Mandar por correo");
        Tema.Boton copiar = Tema.botonSecundario("Copiar");
        Tema.Boton cerrar = Tema.botonPequeno("Cerrar");
        mail.setVisible(conCorreo);
        mail.setToolTipText(conCorreo ? "A " + d.correo : null);
        t.add(Tema.fila(8, wa, mail, copiar));
        t.add(Tema.espacio(8));
        t.add(cerrar);
        Capa capa = new Capa(this, t, true);
        cerrar.addActionListener(a -> capa.cerrar());
        wa.addActionListener(a -> {
            try {
                AbrirWhatsApp.abrir(d.celular, msj);
                decir(estado, "Se abrió WhatsApp con el mensaje.", true);
            } catch (Exception ex) {
                copiarTexto(msj);
                decir(estado, "No se abrió WhatsApp: el mensaje quedó copiado.", false);
            }
        });
        copiar.addActionListener(a -> {
            copiarTexto(msj);
            decir(estado, "Copiado: péguelo donde quiera (mensaje de texto, Telegram…).", true);
        });
        rutas.logica.AjustesCorreo acf = ac;
        mail.addActionListener(a -> {
            mail.setEnabled(false);
            decir(estado, "Mandando el correo…", true);
            String remitente = acf.remitente.isBlank() ? app.getNegocio() : acf.remitente;
            new javax.swing.SwingWorker<Void, Void>() {
                @Override
                protected Void doInBackground() throws Exception {
                    rutas.logica.CorreoSmtp.gmail(acf.cuenta, acf.clave).enviar(remitente, d.correo,
                            "Tu clave de DomiRuta", msj, null, null, null);
                    return null;
                }

                @Override
                protected void done() {
                    mail.setEnabled(true);
                    try {
                        get();
                        decir(estado, "Correo enviado a " + d.correo + ".", true);
                    } catch (Exception ex) {
                        Throwable c = ex.getCause() == null ? ex : ex.getCause();
                        decir(estado, "No se pudo mandar el correo: " + c.getMessage(), false);
                    }
                }
            }.execute();
        });
        capa.mostrar(wa);
    }

    private static void copiarTexto(String s) {
        java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(s), null);
    }

    /** Lo crea en Supabase si hace falta (o si allá ya no estaba) y abre WhatsApp con su enlace personal. */
    private void enviarEnlacePersonal(rutas.modelo.Domiciliario vista, Tema.Boton boton) {
        rutas.modelo.Domiciliario d = app.domiciliarioActual(vista.id); // la lista en pantalla puede ser vieja
        if (d == null) {
            cargarCuentas();
            return;
        }
        String idAntes = d.idNube;
        boton.setEnabled(false);
        enNube(n2 -> {
            if (d.enNube() && !n2.existeRepartidor(d.idNube)) {
                d.idNube = "";
                d.enlace = "";
            }
            if (!d.enNube()) {
                String[] r = n2.crearRepartidor(d.nombre, d.celular);
                d.idNube = r[0];
                d.enlace = r[1];
            }
        }, err -> {
            boton.setEnabled(true);
            if (!d.idNube.equals(idAntes)) {
                guardarEnlace(d);
            }
            if (err != null) {
                decir(msgCuentas, "No se pudo crear el enlace: " + err, false);
                return;
            }
            mandarPorWhatsApp(d);
            cargarCuentas();
        });
    }

    private void cambiarEnlacePersonal(rutas.modelo.Domiciliario vista, Tema.Boton boton) {
        rutas.modelo.Domiciliario d = app.domiciliarioActual(vista.id);
        if (d == null || !d.enNube()) {
            cargarCuentas();
            return;
        }
        boton.setEnabled(false);
        enNube(n2 -> {
            String nuevo = n2.revocarEnlace(d.idNube);
            if (nuevo == null) { // ya no estaba en Supabase: se crea de nuevo
                String[] r = n2.crearRepartidor(d.nombre, d.celular);
                d.idNube = r[0];
                d.enlace = r[1];
            } else {
                d.enlace = nuevo;
            }
        }, err -> {
            boton.setEnabled(true);
            guardarEnlace(d);
            if (err != null) {
                decir(msgCuentas, "No se pudo cambiar el enlace: " + err, false);
                return;
            }
            Aviso.mostrar(this, Aviso.Tipo.BIEN, "Enlace cambiado", "El anterior ya no sirve");
            mandarPorWhatsApp(d);
            cargarCuentas();
        });
    }

    private void guardarEnlace(rutas.modelo.Domiciliario d) {
        try {
            app.guardarEnlaceNube(d);
        } catch (java.io.IOException ex) {
            decir(msgCuentas, ex.getMessage(), false);
        }
    }

    private void mandarPorWhatsApp(rutas.modelo.Domiciliario d) {
        rutas.logica.NubeRepartidor.Ajustes aj = app.getAjustesNube();
        if (aj == null || d.enlace.isEmpty()) {
            return;
        }
        String msj = rutas.logica.MensajesWhatsApp.enlacePersonal(app.getNegocio(), d.nombre,
                rutas.logica.NubeRepartidor.enlacePersonal(aj.pagina, d.enlace));
        String cel = rutas.logica.Avisos.numeroWhatsApp(d.celular).isEmpty() ? "" : d.celular;
        try {
            AbrirWhatsApp.abrir(cel, msj);
        } catch (Exception ex) {
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new java.awt.datatransfer.StringSelection(msj), null);
            Aviso.mostrar(this, Aviso.Tipo.AVISO, "Mensaje copiado", "No se abrió WhatsApp: péguelo en el chat de " + d.nombre);
        }
    }

    private void formularioDomiciliario(rutas.modelo.Domiciliario d) {
        JPanel t = Capa.tarjeta(520, 24, 28);
        JLabel titulo = new JLabel(d == null ? "Nuevo domiciliario" : "Editar domiciliario");
        titulo.setFont(Tema.subtitulo(18));
        titulo.setForeground(Tema.AZUL_PROFUNDO);
        titulo.setAlignmentX(LEFT_ALIGNMENT);
        t.add(titulo);
        t.add(Tema.espacio(10));
        JTextField nombre = Tema.campo("Ej. Carlos Pérez o Domicilios Express");
        JTextField celular = Tema.campo("Ej. 300 111 2233");
        JTextField correo = Tema.campo("Ej. carlos@gmail.com (opcional)");
        JComboBox<rutas.modelo.Domiciliario.Tipo> tipo = new JComboBox<>(rutas.modelo.Domiciliario.Tipo.values());
        Tema.estiloCombo(tipo, String::valueOf);
        javax.swing.JCheckBox activo = Tema.casilla("Aparece al despachar");
        activo.setSelected(true);
        if (d != null) {
            nombre.setText(d.nombre);
            celular.setText(d.celular);
            correo.setText(d.correo);
            tipo.setSelectedItem(d.tipo);
            activo.setSelected(d.activo);
        }
        JPanel g = grilla();
        poner(g, 0, 0, 2, "Nombre", nombre);
        poner(g, 0, 1, 1, "Celular (WhatsApp)", celular);
        poner(g, 1, 1, 1, "Tipo", tipo);
        poner(g, 0, 2, 2, "Correo (para mandarle la clave)", correo);
        t.add(g);
        t.add(activo);
        t.add(nota("Fijo: le entrega toda la plata a la tienda. Independiente o grupo: se queda con los domicilios"
                + " (al volver, DomiRuta dice cuánto le entrega). Para un grupo, ponga el celular de quien reparte."));
        Tema.Parrafo error = mensaje();
        t.add(Tema.espacio(6));
        t.add(error);
        t.add(Tema.espacio(10));
        Tema.Boton guardar = Tema.botonPrimario("Guardar");
        Tema.Boton cancelar = Tema.botonSecundario("Cancelar");
        JPanel pie = new JPanel(new BorderLayout());
        pie.setOpaque(false);
        pie.setAlignmentX(LEFT_ALIGNMENT);
        pie.add(Tema.fila(8, cancelar, guardar), BorderLayout.EAST);
        t.add(pie);
        Capa capa = new Capa(this, t, true);
        cancelar.addActionListener(a -> capa.cerrar());
        Runnable hacer = () -> {
            rutas.modelo.Domiciliario x = new rutas.modelo.Domiciliario(d == null ? 0 : d.id, nombre.getText(),
                    celular.getText(), (rutas.modelo.Domiciliario.Tipo) tipo.getSelectedItem());
            x.activo = activo.isSelected();
            x.correo = correo.getText().trim();
            if (!x.correo.isEmpty() && !rutas.logica.CorreoSmtp.esCorreo(x.correo)) {
                decir(error, "Revise el correo (ej. carlos@gmail.com) o déjelo vacío", false);
                return;
            }
            if (d != null) { // su enlace personal sigue siendo el mismo
                x.idNube = d.idNube;
                x.enlace = d.enlace;
            }
            String cel = x.celular.replaceAll("[\\s-]", "");
            if (!cel.isEmpty() && rutas.logica.Avisos.numeroWhatsApp(cel).isEmpty()) {
                decir(error, "El celular lleva 10 números y empieza por 3 (ej. 300 111 2233)", false);
                return;
            }
            try {
                app.guardarDomiciliario(x);
            } catch (java.io.IOException ex) {
                decir(error, ex.getMessage(), false);
                return;
            }
            capa.cerrar();
            rutas.modelo.Domiciliario guardado = app.domiciliarioActual(x.id);
            if (guardado != null && guardado.enNube()) { // que su app muestre el nombre nuevo y, si lo desactivaron, no salga disponible
                enNube(n2 -> n2.actualizarRepartidor(guardado.idNube, x.nombre, x.celular, x.activo), null);
            }
            cargarCuentas();
            Aviso.mostrar(this, Aviso.Tipo.BIEN, "Domiciliarios", "Guardado");
        };
        guardar.addActionListener(a -> hacer.run());
        nombre.addActionListener(a -> hacer.run());
        celular.addActionListener(a -> hacer.run());
        capa.mostrar(nombre);
    }

    // ------------------------------------------------------------------
    //  Impresora
    // ------------------------------------------------------------------

    private JComponent vistaImpresora() {
        Tema.Tarjeta t = tarjeta();
        t.add(Tema.seccion("Impresora"));
        t.add(nota("Sirve con cualquier impresora térmica que tenga su controlador en Windows (Epson, Xprinter,"
                + " 3nStar, Digital POS…). El recibo se acomoda al ancho del papel; el largo es el que necesite."));
        t.add(Tema.espacio(8));
        JPanel g = grilla();
        poner(g, 0, 0, 2, "Impresora de recibos", comboImpresora);
        poner(g, 0, 1, 1, "Ancho del papel", comboAncho);
        poner(g, 1, 1, 1, "Ancho en mm (personalizado)", campoAncho);
        poner(g, 0, 2, 1, "Margen izquierdo (mm)", campoMargenIzq);
        poner(g, 1, 2, 1, "Margen derecho (mm)", campoMargenDer);
        poner(g, 0, 3, 1, "Tamaño de letra", comboLetra);
        poner(g, 1, 3, 1, "Resolución", comboPpp);
        poner(g, 0, 4, 1, "Copias por venta", comboCopias);
        t.add(g);
        lblUtil.setFont(Tema.texto(12.5f));
        lblUtil.setForeground(Tema.GRIS_TEXTO);
        lblUtil.setAlignmentX(LEFT_ALIGNMENT);
        lblUtil.setBorder(BorderFactory.createEmptyBorder(6, 0, 4, 0));
        t.add(lblUtil);
        t.add(Tema.espacio(6));
        t.add(chkCortar);
        t.add(Tema.espacio(4));
        t.add(chkCajon);
        t.add(nota("Cortar y abrir el cajón usan órdenes ESC/POS. Si su impresora no las acepta, el recibo igual"
                + " sale; apague estas opciones."));
        t.add(Tema.espacio(12));
        Tema.Boton prueba = Tema.botonSecundario("Imprimir prueba");
        prueba.setIcon(Iconos.de("notas", 18, Tema.AZUL));
        prueba.addActionListener(e -> imprimirPrueba());
        JPanel p = pie(msgImpresora, this::guardarImpresora);
        ((JPanel) p.getComponent(1)).add(prueba, 0);
        t.add(p);
        return t;
    }

    private void guardarImpresora() {
        try {
            AjustesRecibo a = leerPantalla(true);
            a.guardar(app.getTienda());
            ajustes = a;
            decir(msgImpresora, "Cambios guardados. Haga una prueba para revisar el ancho.", true);
            Aviso.mostrar(this, Aviso.Tipo.BIEN, "Impresora", "Cambios guardados");
        } catch (IllegalArgumentException e) {
            decir(msgImpresora, e.getMessage(), false);
        } catch (SQLException e) {
            decir(msgImpresora, "No se pudo guardar: " + e.getMessage(), false);
        }
    }

    /** Imprime un recibo de ejemplo con una regla en mm, con lo que esta en pantalla (aunque no se haya guardado). */
    private void imprimirPrueba() {
        try {
            AjustesRecibo a = leerPantalla(true);
            Comprobante c = Comprobante.ejemplo(a);
            c.prueba = true;
            msgImpresora.setForeground(Tema.GRIS_TEXTO);
            msgImpresora.setText("Imprimiendo la prueba…");
            // aparte: si no hay impresora o no responde, el programa no se queda pegado
            Impresion.imprimirAparte(c, c.alto(), a, "Prueba de impresión DomiRuta", aviso -> {
                if (aviso == null) {
                    decir(msgImpresora, "Prueba enviada. Revise que la regla se vea completa de borde a borde.", true);
                } else {
                    decir(msgImpresora, aviso, false);
                }
            });
        } catch (IllegalArgumentException e) {
            decir(msgImpresora, e.getMessage(), false);
        } catch (RuntimeException e) {
            decir(msgImpresora, "No se pudo imprimir: revise que la impresora esté encendida y con papel.", false);
        }
    }

    // ------------------------------------------------------------------
    //  Cargar y leer
    // ------------------------------------------------------------------

    private void cargar() {
        cargarMedios();
        cargarCorreo();
        cargarNube();
        ajustes = app.getAjustesRecibo();
        llenando = true;
        try {
            AjustesRecibo a = ajustes;
            campoNombre.setText(a.nombre.isBlank() ? app.getNegocio() : a.nombre);
            campoRazon.setText(a.razonSocial);
            campoNit.setText(a.nit);
            campoTelefono.setText(a.telefono);
            campoDireccion.setText(a.direccion);
            campoCiudad.setText(a.ciudad);
            comboRegimen.setSelectedItem(a.regimen);
            campoGracias.setText(a.gracias);
            campoPie.setText(a.pie);
            chkSistema.setSelected(a.mostrarSistema);
            tonoLogo.setValue(a.umbralLogo);
            chkTramado.setSelected(a.tramado);
            logoElegido = null;
            quitarLogo = false;

            comboImpresora.removeAllItems();
            comboImpresora.addItem(PREGUNTAR);
            for (String n : Impresion.impresoras()) {
                comboImpresora.addItem(n);
            }
            if (!a.impresora.isBlank()) {
                boolean esta = false;
                for (int i = 0; i < comboImpresora.getItemCount(); i++) {
                    esta |= comboImpresora.getItemAt(i).equals(a.impresora);
                }
                if (!esta) {
                    comboImpresora.addItem(a.impresora); // guardada pero no conectada ahora
                }
                comboImpresora.setSelectedItem(a.impresora);
            }
            comboAncho.setSelectedIndex(a.anchoMm == 58 ? 0 : a.anchoMm == 80 ? 1 : 2);
            campoAncho.setText(numero(a.anchoMm));
            boolean personal = comboAncho.getSelectedIndex() == 2;
            campoAncho.setEditable(personal);
            campoAncho.setFocusable(personal);
            campoMargenIzq.setText(numero(a.margenIzqMm));
            campoMargenDer.setText(numero(a.margenDerMm));
            comboLetra.setSelectedItem(a.letra);
            comboPpp.setSelectedIndex(a.ppp == 180 ? 1 : a.ppp == 300 ? 2 : 0);
            comboCopias.setSelectedItem(a.copias);
            chkCortar.setSelected(a.cortar);
            chkCajon.setSelected(a.abrirCajon);
        } finally {
            llenando = false;
        }
        actualizarVista();
    }

    /** Arma los ajustes con lo que hay en pantalla. Con {@code estricto} lanza error si un numero esta mal. */
    private AjustesRecibo leerPantalla(boolean estricto) {
        AjustesRecibo a = new AjustesRecibo();
        a.nombre = campoNombre.getText();
        a.razonSocial = campoRazon.getText();
        a.nit = campoNit.getText();
        a.telefono = campoTelefono.getText();
        a.direccion = campoDireccion.getText();
        a.ciudad = campoCiudad.getText();
        a.regimen = (String) comboRegimen.getSelectedItem();
        a.gracias = campoGracias.getText();
        a.pie = campoPie.getText();
        a.mostrarSistema = chkSistema.isSelected();
        a.umbralLogo = tonoLogo.getValue();
        a.tramado = chkTramado.isSelected();
        a.logo = quitarLogo ? null : logoElegido != null ? logoElegido.getAbsolutePath() : ajustes.logo;
        String imp = (String) comboImpresora.getSelectedItem();
        a.impresora = imp == null || PREGUNTAR.equals(imp) ? "" : imp;
        a.anchoMm = mm(comboAncho.getSelectedIndex() == 0 ? "58" : comboAncho.getSelectedIndex() == 1 ? "80"
                : campoAncho.getText(), "el ancho del papel", estricto, ajustes.anchoMm);
        a.margenIzqMm = mm(campoMargenIzq.getText(), "el margen izquierdo", estricto, ajustes.margenIzqMm);
        a.margenDerMm = mm(campoMargenDer.getText(), "el margen derecho", estricto, ajustes.margenDerMm);
        a.letra = (AjustesRecibo.Letra) comboLetra.getSelectedItem();
        a.ppp = comboPpp.getSelectedIndex() == 1 ? 180 : comboPpp.getSelectedIndex() == 2 ? 300 : 203;
        a.copias = (Integer) comboCopias.getSelectedItem();
        a.cortar = chkCortar.isSelected();
        a.abrirCajon = chkCajon.isSelected();
        if (estricto) {
            a.validar();
        }
        return a;
    }

    private static double mm(String t, String que, boolean estricto, double siFalla) {
        try {
            return Double.parseDouble(t.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            if (estricto) {
                throw new IllegalArgumentException("Revise " + que + ": escriba un número en mm (ej. 2 o 2,5)");
            }
            return siFalla;
        }
    }

    private static String numero(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v).replace('.', ',');
    }

    private void actualizarVista() {
        if (llenando || ajustes == null) {
            return;
        }
        AjustesRecibo a = leerPantalla(false);
        try {
            a.validar();
            lblUtil.setText("Ancho útil para escribir: " + numero(Math.round(a.anchoUtilMm() * 10) / 10.0) + " mm · "
                    + a.puntosUtiles() + " puntos de la impresora");
            lblUtil.setForeground(Tema.GRIS_TEXTO);
        } catch (IllegalArgumentException e) {
            lblUtil.setText(e.getMessage());
            lblUtil.setForeground(Tema.ERROR_TEXTO);
            if (a.anchoUtilMm() < 30 || a.anchoMm < 40) {
                return; // no se puede dibujar un recibo tan angosto
            }
        }
        vistaLogo.setAjustes(a);
        vista.setAjustes(a);
    }

    // ------------------------------------------------------------------
    //  Columna del recibo
    // ------------------------------------------------------------------

    private JComponent columnaRecibo() {
        JPanel col = new JPanel(new BorderLayout(0, 8));
        col.setOpaque(false);
        lblVista.setFont(Tema.textoFuerte(13));
        lblVista.setForeground(Tema.GRIS_OSCURO);
        col.add(lblVista, BorderLayout.NORTH);
        JScrollPane sc = Tema.scrollSinBorde(vista);
        sc.getViewport().setBackground(new Color(0xE4E8ED));
        sc.setBorder(BorderFactory.createEmptyBorder());
        col.add(sc, BorderLayout.CENTER);
        col.setPreferredSize(new Dimension(340, 10));
        return col;
    }

    /** El recibo de ejemplo dibujado a buen tamaño, en blanco sobre gris, como el papel. */
    private static class VistaRecibo extends JPanel {
        private Comprobante comp;
        boolean prueba;

        VistaRecibo() {
            setOpaque(false);
            getAccessibleContext().setAccessibleName("Vista previa del recibo");
        }

        void setAjustes(AjustesRecibo a) {
            comp = Comprobante.ejemplo(a);
            comp.prueba = prueba;
            revalidate();
            repaint();
        }

        /** El papel se ve al ancho real relativo: 58 mm sale mas angosto que 80 mm. */
        private double escala() {
            return comp == null ? 1 : Math.min(1.6, 300.0 / Impresion.mmAPuntos(comp.ajustes.anchoMm));
        }

        @Override
        public Dimension getPreferredSize() {
            if (comp == null) {
                return new Dimension(320, 200);
            }
            double k = escala();
            return new Dimension(320, (int) Math.ceil((comp.alto() + 20) * k) + 24);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            if (comp == null) {
                return;
            }
            Graphics2D g = (Graphics2D) g0.create();
            Tema.suavizar(g);
            double k = escala();
            int papel = (int) Math.round(Impresion.mmAPuntos(comp.ajustes.anchoMm) * k);
            int x = (getWidth() - papel) / 2;
            Tema.sombra(g, x, 12, papel, getHeight() - 24);
            g.setColor(Color.WHITE);
            g.fillRect(x, 12, papel, getHeight() - 24);
            g.translate(x + Impresion.mmAPuntos(comp.ajustes.margenIzqMm) * k, 22);
            g.scale(k, k);
            comp.dibujar(g, comp.anchoPuntos());
            g.dispose();
        }
    }

    /** El logo como saldra impreso (blanco y negro), en su recuadro. */
    private static class VistaLogo extends JPanel {
        private BufferedImage img;
        private int ppp = 203;

        VistaLogo() {
            setOpaque(false);
            setPreferredSize(new Dimension(150, 110));
            getAccessibleContext().setAccessibleName("Logo como saldrá impreso");
        }

        void setAjustes(AjustesRecibo a) {
            ppp = a.ppp;
            img = LogoTermico.preparar(a.logo, (int) Math.round(a.puntosUtiles() * 0.8), a.umbralLogo, a.tramado);
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Tema.suavizar(g);
            int w = getWidth(), h = getHeight();
            g.setColor(Color.WHITE);
            g.fillRoundRect(0, 0, w - 1, h - 1, Tema.ARCO, Tema.ARCO);
            g.setColor(Tema.BORDE_CONTROL);
            g.setStroke(new java.awt.BasicStroke(1f, java.awt.BasicStroke.CAP_BUTT, java.awt.BasicStroke.JOIN_MITER, 10f,
                    new float[]{4f, 4f}, 0f));
            g.drawRoundRect(0, 0, w - 1, h - 1, Tema.ARCO, Tema.ARCO);
            if (img == null) {
                g.setFont(Tema.texto(12.5f));
                g.setColor(Tema.GRIS_TEXTO);
                String t = "Sin logo";
                g.drawString(t, (w - g.getFontMetrics().stringWidth(t)) / 2, h / 2 + 4);
            } else {
                double e = Math.min((w - 16.0) / img.getWidth(), (h - 16.0) / img.getHeight());
                int dw = (int) (img.getWidth() * e), dh = (int) (img.getHeight() * e);
                g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                        java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                g.drawImage(img, (w - dw) / 2, (h - dh) / 2, dw, dh, null);
            }
            g.dispose();
        }
    }

    // ------------------------------------------------------------------
    //  Ayudas
    // ------------------------------------------------------------------

    static Tema.Tarjeta tarjeta() {
        Tema.Tarjeta t = new Tema.Tarjeta();
        t.setLayout(new BoxLayout(t, BoxLayout.Y_AXIS));
        t.setBorder(BorderFactory.createEmptyBorder(16, 20, 18, 20));
        return t;
    }

    /** Panel que toma el ancho visible (los textos largos bajan de renglon en vez de salirse). */
    private static class Ajustado extends JPanel implements javax.swing.Scrollable {
        Ajustado() {
            super(new BorderLayout());
        }

        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        public int getScrollableUnitIncrement(java.awt.Rectangle r, int o, int d) {
            return 16;
        }

        public int getScrollableBlockIncrement(java.awt.Rectangle r, int o, int d) {
            return Math.max(16, r.height - 40);
        }

        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    private final List<JScrollPane> deslizables = new ArrayList<>();

    private JScrollPane desliza(JComponent c) {
        JPanel envoltura = new Ajustado();
        envoltura.setOpaque(false);
        envoltura.add(c, BorderLayout.NORTH);
        JScrollPane sc = Tema.scrollSinBorde(envoltura);
        sc.setOpaque(false);
        sc.getViewport().setOpaque(false);
        sc.getVerticalScrollBar().setUnitIncrement(16);
        sc.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        deslizables.add(sc);
        return sc;
    }

    private static JPanel columna() {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        return p;
    }

    static JPanel grilla() {
        JPanel g = new JPanel(new GridBagLayout());
        g.setOpaque(false);
        g.setAlignmentX(LEFT_ALIGNMENT);
        return g;
    }

    /** Pone una etiqueta con su campo en la grilla de dos columnas. */
    static void poner(JPanel grilla, int x, int y, int ancho, String etiqueta, JComponent c) {
        FormularioProducto.compactar(c);
        JPanel p = columna();
        JLabel l = etiqueta(etiqueta);
        l.setLabelFor(c);
        p.add(l);
        c.setAlignmentX(LEFT_ALIGNMENT);
        c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height));
        p.add(c);
        if (c instanceof JTextField || c instanceof JComboBox) {
            c.getAccessibleContext().setAccessibleName(etiqueta);
        }
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = x;
        g.gridy = y;
        g.gridwidth = ancho;
        g.weightx = 1;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.anchor = GridBagConstraints.NORTHWEST;
        g.insets = new Insets(0, 0, 4, x == 0 && ancho == 1 ? 16 : 0);
        grilla.add(p, g);
    }

    private static JLabel etiqueta(String texto) {
        JLabel l = new JLabel(texto);
        l.setFont(Tema.textoFuerte(12.5f));
        l.setForeground(Tema.GRIS_OSCURO);
        l.setBorder(BorderFactory.createEmptyBorder(8, 0, 4, 0));
        l.setAlignmentX(LEFT_ALIGNMENT);
        return l;
    }

    static Tema.Parrafo nota(String texto) {
        Tema.Parrafo l = Tema.parrafo(texto, 400);
        l.setFont(Tema.texto(12.5f));
        l.setForeground(Tema.GRIS_TEXTO);
        l.setAlignmentX(LEFT_ALIGNMENT);
        return l;
    }

    static Tema.Parrafo mensaje() {
        Tema.Parrafo p = Tema.parrafo(" ", 360);
        p.setFont(Tema.texto(13));
        return p;
    }

    static void decir(Tema.Parrafo l, String texto, boolean bien) {
        l.setForeground(bien ? Tema.EXITO_TEXTO : Tema.ERROR_TEXTO);
        l.setText(texto);
    }

    /** Pie de la vista: mensaje a la izquierda y el boton Guardar cambios (naranja) a la derecha. */
    static JPanel pie(Tema.Parrafo msg, Runnable guardar) {
        JPanel p = new JPanel(new BorderLayout(12, 0));
        p.setOpaque(false);
        p.setAlignmentX(LEFT_ALIGNMENT);
        p.add(msg, BorderLayout.CENTER);
        Tema.Boton b = Tema.botonAccion("Guardar cambios");
        b.addActionListener(e -> guardar.run());
        JPanel derecha = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        derecha.setOpaque(false);
        derecha.add(b);
        p.add(derecha, BorderLayout.EAST);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.getPreferredSize().height + 8));
        return p;
    }
}
