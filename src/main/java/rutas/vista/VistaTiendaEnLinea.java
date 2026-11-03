package rutas.vista;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.sql.SQLException;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.Timer;
import rutas.logica.ImportadorProductos;
import rutas.logica.TiendaEnLinea;

/**
 * Configuración › Tienda en línea: abrir o cerrar los pedidos, el domicilio y el mínimo, el
 * enlace para los clientes y cómo va la subida (se sube sola; «Actualizar ahora» por si acaso).
 */
class VistaTiendaEnLinea extends JPanel {

    private final Aplicacion app;
    private final Controles.Interruptor intAbierta = new Controles.Interruptor("Recibir pedidos ahora (tienda abierta)");
    private final JTextField campoHorario = Tema.campo("Ej. Lunes a sábado, 7:00 a. m. a 9:00 p. m.");
    private final JTextField campoDomicilio = Controles.campoNumero("0 = gratis");
    private final JTextField campoMinimo = Controles.campoNumero("0 = sin mínimo");
    private final Tema.Parrafo msg = PanelConfiguracion.mensaje();
    private final JLabel lblEstado = new JLabel(" ");
    private final JLabel lblProductos = new JLabel(" ");
    private final JTextField campoEnlace = Tema.campo("");
    private final Tema.Boton btnAhora = Tema.botonSecundario("Actualizar ahora");
    // los datos para pagar (Nequi, Bre-B...): se los manda WhatsApp a quien ya pidió; no salen en la página
    private final JPanel panelCuentas = PanelConfiguracion.columna();
    private final java.util.Map<rutas.modelo.MetodoPago, JTextField[]> camposCuentas =
            new java.util.EnumMap<>(rutas.modelo.MetodoPago.class);
    private final Tema.Parrafo msgCuentas = PanelConfiguracion.mensaje();
    // encargados de pedidos (entran a pedidos.html con su celular y una clave de 6 números)
    private final JPanel panelEncargados = PanelConfiguracion.columna();
    private final JTextField campoEncNombre = Tema.campo("Ej. Laura Gómez");
    private final JTextField campoEncCelular = Tema.campo("Ej. 300 123 4567");
    private final Tema.Parrafo msgEnc = PanelConfiguracion.mensaje();

    VistaTiendaEnLinea(Aplicacion app) {
        super(new BorderLayout());
        this.app = app;
        setOpaque(false);
        Tema.Tarjeta t = PanelConfiguracion.tarjeta();
        t.add(Tema.seccion("Tienda en línea"));
        t.add(PanelConfiguracion.nota("Los clientes ven sus productos en una página, arman el pedido y se lo mandan."
                + " Salen solo los productos marcados «Vender en línea» (en Productos), con su foto, su precio y el"
                + " logo y los datos de «Mi negocio». No se ven costos ni cuántos quedan: solo «Agotado»."));
        t.add(Tema.espacio(10));

        intAbierta.setAlignmentX(LEFT_ALIGNMENT);
        t.add(intAbierta);
        t.add(Tema.espacio(8));
        JPanel g = PanelConfiguracion.grilla();
        PanelConfiguracion.poner(g, 0, 0, 2, "Horario (lo ve el cliente)", campoHorario);
        PanelConfiguracion.poner(g, 0, 1, 1, "Domicilio ($)", campoDomicilio);
        PanelConfiguracion.poner(g, 1, 1, 1, "Pedido mínimo ($)", campoMinimo);
        t.add(g);
        t.add(Tema.espacio(4));
        t.add(PanelConfiguracion.nota("Los medios de pago son los de «Medios de pago». Si el cliente recoge en la"
                + " tienda, no paga domicilio."));
        t.add(Tema.espacio(10));
        t.add(PanelConfiguracion.pie(msg, this::guardar));

        t.add(Tema.espacio(18));
        t.add(Tema.seccion("Datos para que le paguen"));
        t.add(PanelConfiguracion.nota("Cuando el cliente escoge Nequi, Bre-B o transferencia, le llega por WhatsApp a qué"
                + " número pagar y que mande la foto del comprobante. Estos datos no salen en la página."));
        t.add(Tema.espacio(6));
        panelCuentas.setAlignmentX(LEFT_ALIGNMENT);
        t.add(panelCuentas);
        t.add(Tema.espacio(6));
        // un solo botón naranja por pantalla (manual de marca): este va azul
        JPanel pieCuentas = new JPanel(new BorderLayout(12, 0));
        pieCuentas.setOpaque(false);
        pieCuentas.setAlignmentX(LEFT_ALIGNMENT);
        pieCuentas.add(msgCuentas, BorderLayout.CENTER);
        Tema.Boton bCuentas = Tema.botonSecundario("Guardar datos de pago");
        bCuentas.addActionListener(e -> guardarCuentas());
        JPanel der = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        der.setOpaque(false);
        der.add(bCuentas);
        pieCuentas.add(der, BorderLayout.EAST);
        pieCuentas.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, pieCuentas.getPreferredSize().height + 8));
        t.add(pieCuentas);

        t.add(Tema.espacio(18));
        t.add(Tema.seccion("Encargados de pedidos"));
        t.add(PanelConfiguracion.nota("Atienden los pedidos en línea desde el celular o el computador: aceptan, empacan,"
                + " revisan el comprobante, le escriben al cliente y escogen el domiciliario. No ven la caja ni los costos."
                + " Entran con su celular y una clave de 6 números."));
        t.add(Tema.espacio(6));
        panelEncargados.setAlignmentX(LEFT_ALIGNMENT);
        t.add(panelEncargados);
        t.add(Tema.espacio(6));
        JPanel gEnc = PanelConfiguracion.grilla();
        PanelConfiguracion.poner(gEnc, 0, 0, 1, "Nombre", campoEncNombre);
        PanelConfiguracion.poner(gEnc, 1, 0, 1, "Celular", campoEncCelular);
        t.add(gEnc);
        JPanel pieEnc = new JPanel(new BorderLayout(12, 0));
        pieEnc.setOpaque(false);
        pieEnc.setAlignmentX(LEFT_ALIGNMENT);
        pieEnc.add(msgEnc, BorderLayout.CENTER);
        Tema.Boton bEnc = Tema.botonSecundario("Agregar encargado");
        bEnc.addActionListener(e -> agregarEncargado());
        JPanel derEnc = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        derEnc.setOpaque(false);
        derEnc.add(bEnc);
        pieEnc.add(derEnc, BorderLayout.EAST);
        pieEnc.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, pieEnc.getPreferredSize().height + 8));
        t.add(pieEnc);

        t.add(Tema.espacio(18));
        t.add(Tema.seccion("El enlace de su tienda"));
        t.add(PanelConfiguracion.nota("Compártalo con sus clientes (estados de WhatsApp, grupos del barrio, Instagram)."));
        t.add(Tema.espacio(6));
        campoEnlace.setEditable(false);
        campoEnlace.getAccessibleContext().setAccessibleName("Enlace de la tienda en línea");
        campoEnlace.setAlignmentX(LEFT_ALIGNMENT);
        FormularioProducto.compactar(campoEnlace);
        campoEnlace.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, campoEnlace.getPreferredSize().height));
        t.add(campoEnlace);
        t.add(Tema.espacio(6));
        Tema.Boton copiar = Tema.botonPequeno("Copiar");
        copiar.addActionListener(e -> copiar());
        Tema.Boton wa = Tema.botonPequeno("Mandar por WhatsApp");
        wa.addActionListener(e -> mandar());
        JPanel f = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        f.setOpaque(false);
        f.setAlignmentX(LEFT_ALIGNMENT);
        f.add(copiar);
        f.add(wa);
        t.add(f);

        t.add(Tema.espacio(18));
        t.add(Tema.seccion("Cómo va la subida"));
        t.add(PanelConfiguracion.nota("DomiRuta sube solo lo que cambia: al guardar un producto, al vender lo último"
                + " (sale «Agotado»), al cambiar el logo o los medios de pago. Sin internet, lo sube cuando vuelva."));
        t.add(Tema.espacio(8));
        lblProductos.setFont(Tema.texto(13.5f));
        lblProductos.setForeground(Tema.GRIS_OSCURO);
        lblProductos.setAlignmentX(LEFT_ALIGNMENT);
        t.add(lblProductos);
        t.add(Tema.espacio(4));
        lblEstado.setFont(Tema.textoFuerte(13.5f));
        lblEstado.setAlignmentX(LEFT_ALIGNMENT);
        t.add(lblEstado);
        t.add(Tema.espacio(8));
        btnAhora.setAlignmentX(LEFT_ALIGNMENT);
        btnAhora.addActionListener(e -> {
            SubidaTienda s = app.getSubidaTienda();
            if (s != null) {
                s.ahora();
            }
            pintarEstado();
        });
        t.add(btnAhora);
        add(t, BorderLayout.NORTH);

        SubidaTienda s = app.getSubidaTienda();
        if (s != null) {
            s.alCambiar(this::pintarEstado);
        }
        // «hace 2 min» se mueve solo mientras se mira
        Timer reloj = new Timer(20_000, e -> pintarEstado());
        addHierarchyListener(e -> {
            if (isShowing()) {
                cargar();
                reloj.start();
            } else {
                reloj.stop();
            }
        });
        cargar();
    }

    private void cargar() {
        try {
            TiendaEnLinea.Ajustes a = TiendaEnLinea.Ajustes.leer(app.getTienda());
            intAbierta.setSelected(a.abierta);
            campoHorario.setText(a.horario);
            campoDomicilio.setText(a.domicilio == 0 ? "" : String.valueOf(a.domicilio));
            campoMinimo.setText(a.minimo == 0 ? "" : String.valueOf(a.minimo));
            int n = app.getTienda().productosEnLinea().size();
            lblProductos.setText(n == 0 ? "Todavía no hay productos marcados «Vender en línea»."
                    : n == 1 ? "1 producto se vende en línea." : n + " productos se venden en línea.");
        } catch (SQLException e) {
            PanelConfiguracion.decir(msg, "No se pudo leer: " + e.getMessage(), false);
        }
        cargarCuentas();
        cargarEncargados();
        rutas.logica.NubeRepartidor.Ajustes aj = app.getAjustesNube();
        campoEnlace.setText(aj == null ? "" : TiendaEnLinea.enlace(aj.pagina));
        pintarEstado();
    }

    private void cargarEncargados() {
        SubidaTienda s = app.getSubidaTienda();
        TiendaEnLinea t = s == null ? null : s.conexion();
        if (t == null) {
            panelEncargados.removeAll();
            panelEncargados.add(notaGris("Primero configure la conexión con Supabase (Configuración › Conexión)."));
            panelEncargados.revalidate();
            return;
        }
        s.accion(x -> {
            java.util.List<TiendaEnLinea.Encargado> l = x.encargados();
            javax.swing.SwingUtilities.invokeLater(() -> pintarEncargados(l));
        }, err -> {
            if (err != null) {
                panelEncargados.removeAll();
                panelEncargados.add(notaGris(err.startsWith("Falta la tienda") ? "Falta correr encargados.sql en Supabase." : err));
                panelEncargados.revalidate();
            }
        });
    }

    private static JLabel notaGris(String t) {
        JLabel l = new JLabel(t);
        l.setFont(Tema.texto(13));
        l.setForeground(Tema.GRIS_TEXTO);
        return l;
    }

    private void pintarEncargados(java.util.List<TiendaEnLinea.Encargado> l) {
        panelEncargados.removeAll();
        if (l.isEmpty()) {
            panelEncargados.add(notaGris("Todavía no hay encargados."));
        }
        for (TiendaEnLinea.Encargado e : l) {
            JPanel fila = new JPanel(new BorderLayout(10, 0));
            fila.setOpaque(false);
            fila.setAlignmentX(LEFT_ALIGNMENT);
            fila.setBorder(javax.swing.BorderFactory.createCompoundBorder(
                    javax.swing.BorderFactory.createMatteBorder(0, 0, 1, 0, Tema.BORDE),
                    javax.swing.BorderFactory.createEmptyBorder(8, 2, 8, 0)));
            JLabel n = new JLabel(e.nombre + " · " + e.celular);
            n.setFont(Tema.textoFuerte(13.5f));
            fila.add(n, BorderLayout.CENTER);
            JLabel est = new JLabel(e.conClave ? "Con clave" : "Sin clave");
            est.setFont(Tema.texto(12.5f));
            est.setForeground(e.conClave ? Tema.EXITO_TEXTO : Tema.AVISO_TEXTO);
            JPanel b = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
            b.setOpaque(false);
            b.add(est);
            Tema.Boton clave = Tema.botonPequeno(e.conClave ? "Nueva clave" : "Crear clave");
            clave.addActionListener(x -> crearClave(e));
            b.add(clave);
            Tema.Boton quitar = Tema.botonPequeno("Quitar");
            quitar.addActionListener(x -> quitarEncargado(e));
            b.add(quitar);
            fila.add(b, BorderLayout.EAST);
            fila.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, fila.getPreferredSize().height));
            panelEncargados.add(fila);
        }
        panelEncargados.revalidate();
        panelEncargados.repaint();
    }

    private void agregarEncargado() {
        SubidaTienda s = app.getSubidaTienda();
        String nombre = campoEncNombre.getText(), cel = campoEncCelular.getText();
        if (s == null) {
            return;
        }
        s.accion(x -> x.crearEncargado(nombre, cel), err -> {
            if (err != null) {
                PanelConfiguracion.decir(msgEnc, err, false);
                return;
            }
            campoEncNombre.setText("");
            campoEncCelular.setText("");
            PanelConfiguracion.decir(msgEnc, "Listo. Ahora créele la clave.", true);
            cargarEncargados();
        });
    }

    private void quitarEncargado(TiendaEnLinea.Encargado e) {
        int r = javax.swing.JOptionPane.showConfirmDialog(this, "¿Quitar a " + e.nombre + "? Ya no podrá entrar a los pedidos.",
                "Quitar encargado", javax.swing.JOptionPane.YES_NO_OPTION);
        if (r == javax.swing.JOptionPane.YES_OPTION) {
            app.getSubidaTienda().accion(x -> x.quitarEncargado(e.id), err -> {
                PanelConfiguracion.decir(msgEnc, err == null ? e.nombre + " ya no puede entrar." : err, err == null);
                cargarEncargados();
            });
        }
    }

    private void crearClave(TiendaEnLinea.Encargado e) {
        String clave = rutas.logica.NubeRepartidor.claveNueva();
        String[] res = {null};
        app.getSubidaTienda().accion(x -> res[0] = x.claveEncargado(e.id, clave), err -> {
            String r = err != null ? err : res[0];
            if (!"ok".equals(r)) {
                PanelConfiguracion.decir(msgEnc, "No se pudo crear la clave: " + r, false);
                return;
            }
            rutas.logica.NubeRepartidor.Ajustes aj = app.getAjustesNube();
            String enlace = aj == null ? "" : TiendaEnLinea.enlaceEncargado(aj.pagina);
            String msj = "Hola " + SubidaTienda.primerNombre(e.nombre) + ", ya puedes atender los pedidos en línea de "
                    + (app.getNegocio().isBlank() ? "la tienda" : app.getNegocio()) + ".\n\nEntra aquí: " + enlace
                    + "\nCelular: " + e.celular + "\nClave:\n" + clave + "\n\nNo le des la clave a nadie.";
            Object[] op = {"Mandar por WhatsApp", "Copiar", "Cerrar"};
            int k = javax.swing.JOptionPane.showOptionDialog(this, "La clave de " + e.nombre + " es:\n\n" + clave
                    + "\n\nSe la puede mandar por WhatsApp. La clave anterior ya no sirve.", "Clave del encargado",
                    javax.swing.JOptionPane.DEFAULT_OPTION, javax.swing.JOptionPane.INFORMATION_MESSAGE, null, op, op[0]);
            if (k == 0) {
                try {
                    AbrirWhatsApp.abrir(e.celular, msj);
                } catch (Exception ex) {
                    copiarTexto(msj);
                }
            } else if (k == 1) {
                copiarTexto(msj);
            }
            cargarEncargados();
        });
    }

    private void copiarTexto(String t) {
        java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(t), null);
        Aviso.mostrar(this, Aviso.Tipo.BIEN, "Copiado", "Péguelo en el chat del encargado");
    }

    private void cargarCuentas() {
        panelCuentas.removeAll();
        camposCuentas.clear();
        try {
            java.util.Map<rutas.modelo.MetodoPago, String[]> c = TiendaEnLinea.leerCuentas(app.getTienda());
            JPanel g = PanelConfiguracion.grilla();
            int fila = 0;
            for (rutas.modelo.MetodoPago m : rutas.logica.MediosPago.activos(app.getTienda())) {
                if (!TiendaEnLinea.pagaAntes(m)) {
                    continue;
                }
                JTextField cuenta = Tema.campo(m == rutas.modelo.MetodoPago.BREB ? "Llave (celular, cédula o correo)"
                        : m == rutas.modelo.MetodoPago.TRANSFERENCIA || m == rutas.modelo.MetodoPago.BANCOLOMBIA
                        ? "Banco, tipo y número de cuenta" : "Número");
                JTextField titular = Tema.campo("A nombre de");
                String[] v = c.getOrDefault(m, new String[]{"", ""});
                cuenta.setText(v[0]);
                titular.setText(v[1]);
                PanelConfiguracion.poner(g, 0, fila, 1, m.getTexto() + ": número o llave", cuenta);
                PanelConfiguracion.poner(g, 1, fila, 1, "A nombre de", titular);
                camposCuentas.put(m, new JTextField[]{cuenta, titular});
                fila++;
            }
            if (fila == 0) {
                JLabel l = new JLabel("Solo acepta efectivo o tarjeta. Active Nequi, Bre-B u otros en «Medios de pago».");
                l.setFont(Tema.texto(13));
                l.setForeground(Tema.GRIS_TEXTO);
                panelCuentas.add(l);
            } else {
                panelCuentas.add(g);
            }
        } catch (SQLException e) {
            PanelConfiguracion.decir(msgCuentas, "No se pudo leer: " + e.getMessage(), false);
        }
        panelCuentas.revalidate();
    }

    private void guardarCuentas() {
        try {
            java.util.Map<rutas.modelo.MetodoPago, String[]> c = new java.util.EnumMap<>(rutas.modelo.MetodoPago.class);
            camposCuentas.forEach((m, f) -> c.put(m, new String[]{f[0].getText(), f[1].getText()}));
            TiendaEnLinea.guardarCuentas(app.getTienda(), c);
            app.tiendaCambio();
            PanelConfiguracion.decir(msgCuentas, "Guardado. Se le mandan al cliente que pida con esos medios.", true);
            Aviso.mostrar(this, Aviso.Tipo.BIEN, "Datos para que le paguen", "Cambios guardados");
        } catch (IllegalArgumentException e) {
            PanelConfiguracion.decir(msgCuentas, e.getMessage(), false);
        } catch (SQLException e) {
            PanelConfiguracion.decir(msgCuentas, "No se pudo guardar: " + e.getMessage(), false);
        }
    }

    private void guardar() {
        try {
            TiendaEnLinea.Ajustes a = new TiendaEnLinea.Ajustes();
            a.abierta = intAbierta.isSelected();
            a.horario = campoHorario.getText().trim();
            a.domicilio = campoDomicilio.getText().isBlank() ? 0 : ImportadorProductos.leerPesos(campoDomicilio.getText());
            a.minimo = campoMinimo.getText().isBlank() ? 0 : ImportadorProductos.leerPesos(campoMinimo.getText());
            a.guardar(app.getTienda());
            app.tiendaCambio();
            PanelConfiguracion.decir(msg, a.abierta ? "Guardado. La tienda recibe pedidos." : "Guardado. La tienda no recibe"
                    + " pedidos hasta que la vuelva a abrir (los clientes pueden mirar).", true);
            Aviso.mostrar(this, Aviso.Tipo.BIEN, "Tienda en línea", "Cambios guardados");
        } catch (IllegalArgumentException e) {
            PanelConfiguracion.decir(msg, e.getMessage(), false);
        } catch (SQLException e) {
            PanelConfiguracion.decir(msg, "No se pudo guardar: " + e.getMessage(), false);
        }
    }

    private void pintarEstado() {
        SubidaTienda s = app.getSubidaTienda();
        if (s == null) {
            lblEstado.setText(" ");
            return;
        }
        lblEstado.setText(s.isOcupado() ? "Subiendo…" : s.getEstado());
        lblEstado.setForeground(s.hayError() && !s.isOcupado() ? Tema.ERROR_TEXTO : Tema.EXITO_TEXTO);
        btnAhora.setEnabled(!s.isOcupado());
    }

    private String mensajeEnlace() {
        String n = app.getNegocio();
        return (n.isBlank() ? "Haz tu pedido" : "Haz tu pedido en " + n) + " desde el celular y te lo llevamos a la casa:\n"
                + campoEnlace.getText();
    }

    private void copiar() {
        if (campoEnlace.getText().isBlank()) {
            PanelConfiguracion.decir(msg, "Falta la página en Configuración › Conexión.", false);
            return;
        }
        java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new java.awt.datatransfer.StringSelection(campoEnlace.getText()), null);
        Aviso.mostrar(this, Aviso.Tipo.BIEN, "Enlace copiado", "Péguelo donde lo quiera compartir");
    }

    private void mandar() {
        if (campoEnlace.getText().isBlank()) {
            PanelConfiguracion.decir(msg, "Falta la página en Configuración › Conexión.", false);
            return;
        }
        String m = mensajeEnlace();
        try {
            AbrirWhatsApp.abrir("", m);
        } catch (Exception ex) {
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new java.awt.datatransfer.StringSelection(m), null);
            Aviso.mostrar(this, Aviso.Tipo.AVISO, "Mensaje copiado", "No se abrió WhatsApp: péguelo en el chat");
        }
    }
}
