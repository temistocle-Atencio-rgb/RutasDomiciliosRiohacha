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
        rutas.logica.NubeRepartidor.Ajustes aj = app.getAjustesNube();
        campoEnlace.setText(aj == null ? "" : TiendaEnLinea.enlace(aj.pagina));
        pintarEstado();
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
