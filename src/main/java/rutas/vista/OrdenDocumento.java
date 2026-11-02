package rutas.vista;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import rutas.logica.AjustesCorreo;
import rutas.logica.Compra;
import rutas.logica.CorreoSmtp;
import rutas.logica.MensajesWhatsApp;
import rutas.logica.OrdenCompra;
import rutas.logica.PdfImagen;
import rutas.logica.Tarifa;
import rutas.logica.Ubicaciones;
import rutas.modelo.Proveedor;

/** La orden de compra en hoja carta (PDF) y como se le manda al proveedor: correo y WhatsApp. */
final class OrdenDocumento {

    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private OrdenDocumento() {
    }

    static String numero(OrdenCompra o, Aplicacion app) {
        long n = o.getNumero();
        if (n == 0) {
            try {
                n = app.getOrdenes().siguienteNumero();
            } catch (java.sql.SQLException e) {
                n = 0;
            }
        }
        return Comprobante.numeroTexto(n);
    }

    private static Proveedor proveedor(Aplicacion app, OrdenCompra o) {
        if (o.getProveedorId() == null) {
            return null;
        }
        try {
            return app.getOrdenes().buscarProveedor(o.getProveedorId());
        } catch (java.sql.SQLException e) {
            Ubicaciones.registrarError(e);
            return null;
        }
    }

    private static String bodega(Aplicacion app, long id) {
        try {
            for (rutas.modelo.Bodega b : app.getTienda().listarBodegas()) {
                if (b.getId() == id) {
                    return b.getNombre();
                }
            }
        } catch (java.sql.SQLException e) {
            Ubicaciones.registrarError(e);
        }
        return "";
    }

    /** La orden en hoja carta: datos arriba, los productos y al final los totales. */
    static DocumentoLista documento(Aplicacion app, OrdenCompra o) {
        List<String[]> filas = new ArrayList<>();
        for (Compra.Linea l : o.getLineas()) {
            filas.add(new String[]{l.getProducto().getNombre(),
                PanelHistorialCompras.cantidad(l.getCantidad(), l.getProducto().getUnidad()), Tarifa.pesos(l.getBase()),
                PanelHistorialCompras.porcentaje(l.getImpuesto()), Tarifa.pesos(l.getCosto()),
                PanelHistorialCompras.porcentaje(l.getDescuento()), Tarifa.pesos(l.getSubtotal())});
        }
        filas.add(new String[]{"", "", "", "", "Total bruto", "", Tarifa.pesos(o.getBruto())});
        filas.add(new String[]{"", "", "", "", "Descuento", "", o.getDescuentos() == 0 ? "$0" : "-" + Tarifa.pesos(o.getDescuentos())});
        filas.add(new String[]{"", "", "", "", "Impuestos", "", Tarifa.pesos(o.getImpuestos())});
        if (o.isConRetencion()) {
            filas.add(new String[]{"", "", "", "", "Retención " + PanelHistorialCompras.porcentaje(o.getRetencionPct()), "",
                "-" + Tarifa.pesos(o.getRetencion())});
        }
        filas.add(new String[]{"", "", "", "", "TOTAL", "", Tarifa.pesos(o.getTotalOrden())});
        if (!o.getObservaciones().isBlank()) {
            filas.add(new String[]{"Nota: " + o.getObservaciones().replaceAll("\\s+", " "), "", "", "", "", "", ""});
        }
        String estado = o.getId() == 0 ? "BORRADOR" : o.getEstado().toString().toUpperCase();
        DocumentoLista doc = new DocumentoLista("Orden de compra No. " + numero(o, app) + " · " + estado,
                new String[]{"Producto", "Cant", "Vr Base", "% Imp", "Costo unit.", "% Desc", "Subtotal"},
                new float[]{3.1f, 0.9f, 1.1f, 0.8f, 1.3f, 0.9f, 1.2f},
                new boolean[]{false, true, true, true, true, true, true}, filas, app.getAjustesRecibo());
        doc.setConteo(o.getLineas().size() + (o.getLineas().size() == 1 ? " producto" : " productos"));
        Proveedor pv = proveedor(app, o);
        StringBuilder sub = new StringBuilder("Proveedor: ").append(o.getProveedor().isBlank() ? "—" : o.getProveedor());
        if (pv != null && !pv.documentoTexto().isEmpty()) {
            sub.append(" (").append(pv.documentoTexto()).append(")");
        }
        sub.append("  ·  Fecha: ").append(o.getFecha() == null ? "—" : o.getFecha().format(FECHA));
        if (o.getFechaEstimada() != null) {
            sub.append("  ·  Entrega: ").append(o.getFechaEstimada().format(FECHA));
        }
        String b = bodega(app, o.getBodegaId());
        if (!b.isEmpty()) {
            sub.append("  ·  Bodega: ").append(b);
        }
        doc.setSubtitulo(sub.toString());
        return doc;
    }

    /** Las paginas del documento como PDF (para el correo). */
    static byte[] pdf(DocumentoLista doc) throws IOException {
        List<BufferedImage> paginas = new ArrayList<>();
        java.awt.print.PageFormat pf = doc.getPageFormat(0);
        double escala = 2.5; // buena nitidez sin que pese mucho
        for (int i = 0; i < doc.getNumberOfPages(); i++) {
            BufferedImage img = new BufferedImage((int) (pf.getWidth() * escala), (int) (pf.getHeight() * escala),
                    BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, img.getWidth(), img.getHeight());
            g.scale(escala, escala);
            doc.print(g, pf, i);
            g.dispose();
            paginas.add(img);
        }
        return PdfImagen.crearPaginas(paginas, pf.getWidth(), pf.getHeight());
    }

    // ------------------------------------------------------------------

    static void enviarWhatsapp(JComponent dentro, Aplicacion app, OrdenCompra o) {
        Proveedor pv = proveedor(app, o);
        String msj = MensajesWhatsApp.orden(app.getNegocio(), numero(o, app), o, pv);
        String cel = pv == null ? "" : pv.celularParaPedidos();
        try {
            AbrirWhatsApp.abrir(rutas.logica.Avisos.numeroWhatsApp(cel).isEmpty() ? "" : cel, msj);
            Aviso.mostrar(dentro, Aviso.Tipo.BIEN, "WhatsApp abierto", cel.isEmpty()
                    ? "Escoja el chat del proveedor y envíe el pedido" : "Revise el mensaje y envíelo");
        } catch (Exception ex) {
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new java.awt.datatransfer.StringSelection(msj), null);
            Aviso.mostrar(dentro, Aviso.Tipo.AVISO, "Mensaje copiado", "No se abrió WhatsApp: péguelo en el chat del proveedor");
        }
    }

    /** Para, asunto y mensaje; el PDF va adjunto y el pedido tambien en el cuerpo. */
    static void enviarCorreo(JComponent dentro, Aplicacion app, OrdenCompra o) {
        enviarCorreo(dentro, app, o, null);
    }

    /** borrador = {para, asunto, mensaje, motivo} de un envio que fallo (null la primera vez). */
    private static void enviarCorreo(JComponent dentro, Aplicacion app, OrdenCompra o, String[] borrador) {
        AjustesCorreo aj;
        try {
            aj = AjustesCorreo.leer(app.getTienda());
        } catch (java.sql.SQLException e) {
            aj = new AjustesCorreo();
        }
        final AjustesCorreo ajustes = aj;
        Proveedor pv = proveedor(app, o);
        String num = numero(o, app);
        String negocio = app.getNegocio() == null || app.getNegocio().isBlank() ? "La tienda" : app.getNegocio();
        Capa[] capa = new Capa[1];
        JPanel modal = FormularioProveedor.modal("Enviar la orden por correo", 600, () -> capa[0].cerrar());
        JPanel t = (JPanel) ((BorderLayout) modal.getLayout()).getLayoutComponent(BorderLayout.CENTER);
        JTextField para = Tema.campo("correo@proveedor.com");
        para.setText(pv == null ? "" : pv.correo);
        JTextField asunto = Tema.campo("Asunto");
        asunto.setText("Orden de compra No. " + num + " - " + negocio);
        Controles.AreaTexto mensaje = new Controles.AreaTexto("Mensaje", 5);
        String saludo = pv != null && !pv.vendedor.isBlank() ? "Buenas, " + pv.vendedor.trim() + "." : "Buenas.";
        mensaje.getArea().setText(saludo + "\n\nLe enviamos la orden de compra No. " + num + " (va en el PDF)."
                + (o.getFechaEstimada() == null ? "" : "\nLa necesitamos para el " + o.getFechaEstimada().format(FECHA) + ".")
                + "\n\nQuedamos atentos. Gracias.\n" + negocio);
        if (borrador != null) { // reintento: lo que se habia escrito
            para.setText(borrador[0]);
            asunto.setText(borrador[1]);
            mensaje.getArea().setText(borrador[2]);
            mensaje.getArea().setCaretPosition(0);
        }
        t.add(campo("Para", para));
        t.add(Tema.espacio(10));
        t.add(campo("Asunto", asunto));
        t.add(Tema.espacio(10));
        mensaje.setPreferredSize(new java.awt.Dimension(500, 120));
        t.add(campo("Mensaje", mensaje));
        JLabel estado = new JLabel(!ajustes.listo() ? "Primero configure el correo en Configuración › Correo"
                : borrador != null ? "<html><div style='width:520px'>" + borrador[3] + "</div></html>" : " ");
        estado.setFont(Tema.textoFuerte(13));
        estado.setForeground(ajustes.listo() && borrador == null ? Tema.GRIS_TEXTO : Tema.ERROR_TEXTO);
        estado.setAlignmentX(Component.LEFT_ALIGNMENT);
        t.add(Tema.espacio(8));
        t.add(estado);
        t.add(Tema.espacio(10));
        Tema.Boton enviar = Tema.botonAccion("Enviar");
        enviar.setIcon(new CapaCobro.LogoPago("/imagenes/archivos/gmail.png", 18));
        enviar.setEnabled(ajustes.listo());
        Tema.Boton cancelar = Tema.botonSecundario("Cancelar");
        JPanel pie = new JPanel(new BorderLayout());
        pie.setOpaque(false);
        pie.setAlignmentX(Component.LEFT_ALIGNMENT);
        pie.add(Tema.fila(8, cancelar, enviar), BorderLayout.EAST);
        t.add(pie);
        capa[0] = new Capa(dentro, modal, true);
        cancelar.addActionListener(e -> capa[0].cerrar());
        enviar.addActionListener(e -> {
            String destino = para.getText().trim();
            if (!CorreoSmtp.esCorreo(destino)) {
                estado.setForeground(Tema.ERROR_TEXTO);
                estado.setText("Revise el correo del proveedor (ej. ventas@proveedor.com)");
                return;
            }
            // la ventanita se cierra ya: el correo sale por detras y se puede seguir trabajando
            String remitente = ajustes.remitente.isBlank() ? negocio : ajustes.remitente;
            String asuntoTxt = asunto.getText();
            String escrito = mensaje.getArea().getText();
            String texto = escrito + "\n\n" + MensajesWhatsApp.orden(negocio, num, o, pv)
                    .replace("*", "").replace("_", "");
            DocumentoLista doc = documento(app, o);
            JComponent ventana = ColaCorreos.contenido(dentro);
            capa[0].cerrar();
            ColaCorreos.enviar(ventana, "la orden No. " + num, destino, () -> {
                try {
                    CorreoSmtp.gmail(ajustes.cuenta, ajustes.clave).enviar(remitente, destino, asuntoTxt, texto, null,
                            "Orden-de-compra-" + num + ".pdf", pdf(doc), "application/pdf");
                    return null;
                } catch (CorreoSmtp.ErrorCorreo ex) {
                    return ex.getMessage();
                } catch (IOException | RuntimeException ex) {
                    return "No se pudo armar el PDF: " + ex.getMessage();
                }
            }, () -> {
                // si el proveedor no tenia correo, queda guardado para la proxima
                if (pv != null && pv.correo.isBlank()) {
                    pv.correo = destino;
                    try {
                        app.getOrdenes().guardarProveedor(pv);
                    } catch (Exception ex) {
                        Ubicaciones.registrarError(ex);
                    }
                }
            }, motivo -> enviarCorreo(ventana, app, o, new String[]{destino, asuntoTxt, escrito, motivo}));
        });
        capa[0].mostrar(para.getText().isEmpty() ? para : enviar);
    }

    private static JComponent campo(String etiqueta, JComponent c) {
        JPanel p = new JPanel(new BorderLayout(0, 5));
        p.setOpaque(false);
        JLabel l = new JLabel(etiqueta);
        l.setFont(Tema.textoFuerte(13));
        l.setForeground(Tema.GRIS_OSCURO);
        l.setLabelFor(c);
        p.add(l, BorderLayout.NORTH);
        p.add(c, BorderLayout.CENTER);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, p.getPreferredSize().height));
        return p;
    }
}
