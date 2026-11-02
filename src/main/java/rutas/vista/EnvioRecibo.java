package rutas.vista;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import rutas.logica.AjustesCorreo;
import rutas.logica.CorreoSmtp;
import rutas.logica.PdfImagen;
import rutas.logica.Tarifa;

/** El recibo como PDF (para guardarlo o mandarlo) y la ventanita para enviarlo por correo. */
final class EnvioRecibo {

    private static final int MARGEN = 10; // puntos alrededor del recibo en el PDF
    private static final double NITIDEZ = 4; // pixeles por punto: se ve bien al ampliar

    private EnvioRecibo() {
    }

    /** El recibo dibujado en un PDF de una pagina del ancho del rollo. */
    /** El PDF del correo y de "Guardar PDF": hoja carta ordenada como una factura. */
    static byte[] pdf(Comprobante comp) throws IOException {
        return ReciboCarta.pdf(comp);
    }

    /** El PDF del tamaño del ticket (como sale en la impresora termica). */
    static byte[] pdfTicket(Comprobante comp) throws IOException {
        int ancho = comp.anchoPuntos();
        int alto = comp.alto();
        int w = (int) Math.ceil((ancho + 2 * MARGEN) * NITIDEZ);
        int h = (int) Math.ceil((alto + 2 * MARGEN) * NITIDEZ);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, w, h);
        Tema.suavizar(g);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.scale(NITIDEZ, NITIDEZ);
        g.translate(MARGEN, MARGEN);
        g.setColor(java.awt.Color.BLACK);
        comp.dibujar(g, ancho);
        g.dispose();
        return PdfImagen.crear(img, ancho + 2 * MARGEN);
    }

    /** El detalle de la compra en texto, para el cuerpo del correo. */
    static String detalleTexto(Comprobante comp) {
        StringBuilder b = new StringBuilder();
        b.append("Detalle de su compra No. ").append(Comprobante.numeroTexto(comp.numero)).append(" · ")
                .append(comp.fechaTexto()).append('\n');
        for (String[] f : comp.filas()) {
            b.append("  ").append(f[0].trim()).append("  ").append(f[1]).append('\n');
        }
        b.append("Total: ").append(Tarifa.pesos(comp.total)).append('\n');
        for (rutas.modelo.Pago p : comp.pagos) {
            if (p.getValor() > 0) {
                b.append("Pagó con ").append(p.getMetodo().getTexto()).append(": ").append(Tarifa.pesos(p.getValor()))
                        .append('\n');
            }
        }
        b.append('\n').append(datosNegocio(comp, "\n"));
        b.append("\nEl recibo también va adjunto en PDF.");
        return b.toString();
    }

    /** Nombre, NIT, direccion y telefono del negocio (los de Configuración › Mi negocio). */
    private static String datosNegocio(Comprobante comp, String salto) {
        rutas.logica.AjustesRecibo a = comp.ajustes;
        StringBuilder b = new StringBuilder(a != null && !a.nombre.isBlank() ? a.nombre : comp.negocio);
        if (a != null) {
            if (!a.nit.isBlank()) {
                b.append(salto).append("NIT ").append(a.nit);
            }
            if (!a.direccion.isBlank()) {
                b.append(salto).append(a.direccion);
            }
            if (!a.telefono.isBlank()) {
                b.append(salto).append("Tel. ").append(a.telefono);
            }
        }
        return b.toString();
    }

    private static String esc(String t) {
        return t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    // ------------------------------------------------------------------
    //  Plantilla del correo (HTML): mismo estilo del recibo carta, en grises, con el logo adentro
    // ------------------------------------------------------------------

    private static final String TXT = "#1F2933";
    private static final String GRIS = "#6B7280";
    private static final String LINEA = "#D5D8DC";
    private static final String FUENTE = "'Inter','Segoe UI',Roboto,Helvetica,Arial,sans-serif";
    private static final String FUENTE_TITULO = "'Poppins','Segoe UI',Roboto,Helvetica,Arial,sans-serif";

    /**
     * Correo con diseño (plantilla HTML con tablas, como piden Gmail y Outlook): encabezado con el logo
     * (cid:logo@domiruta) y el numero, el mensaje escrito, el total destacado, los productos, el pago, los
     * datos del negocio y el aviso legal. Con logo=false no se pone la imagen (por ejemplo en las pruebas).
     */
    static String html(String mensaje, Comprobante comp) {
        return html(mensaje, comp, true);
    }

    static String html(String mensaje, Comprobante comp, boolean conLogo) {
        // ancho del logo en pixeles (Gmail no respeta width:auto: sin ancho fijo la imagen queda en 0)
        int anchoLogo = 31;
        byte[] png = conLogo ? ReciboCarta.logoPng(comp) : null;
        if (png != null) {
            try {
                java.awt.image.BufferedImage im = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
                anchoLogo = (int) Math.round(36.0 * im.getWidth() / im.getHeight());
            } catch (IOException | RuntimeException e) {
                anchoLogo = 31;
            }
        }
        rutas.logica.AjustesRecibo a = comp.ajustes != null ? comp.ajustes : new rutas.logica.AjustesRecibo();
        String negocio = !vacio(a.nombre) ? a.nombre : comp.negocio;
        String numero = Comprobante.numeroTexto(comp.numero);
        long aPagar = comp.total + comp.valorDomicilio;
        StringBuilder h = new StringBuilder();
        h.append("<!DOCTYPE html><html lang=\"es\"><head><meta charset=\"UTF-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<meta name=\"color-scheme\" content=\"light\"><title>Recibo de venta N.º ").append(numero)
                .append("</title></head>");
        h.append("<body style=\"margin:0;padding:0;background:#F2F4F7;\">");
        // lo que se ve en la bandeja junto al asunto
        h.append("<div style=\"display:none;max-height:0;overflow:hidden;opacity:0;\">Recibo de venta N.º ")
                .append(numero).append(" · Total ").append(esc(Tarifa.pesos(aPagar))).append(" · ").append(esc(negocio))
                .append("</div>");
        h.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" "
                + "style=\"background:#F2F4F7;\"><tr><td align=\"center\" style=\"padding:32px 12px;\">");
        h.append("<table role=\"presentation\" width=\"760\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" "
                + "style=\"width:100%;max-width:760px;background:#FFFFFF;border:1px solid #E3E7EC;border-radius:5px;"
                + "font-family:").append(FUENTE).append(";color:").append(TXT).append(";\">");

        // encabezado: logo + nombre | recibo de venta + numero
        h.append("<tr><td style=\"padding:32px 36px 22px;\"><table role=\"presentation\" width=\"100%\" "
                + "cellpadding=\"0\" cellspacing=\"0\" border=\"0\"><tr>");
        h.append("<td valign=\"middle\" style=\"font-family:").append(FUENTE_TITULO).append(";font-size:22px;"
                + "font-weight:700;color:").append(TXT).append(";\">");
        if (conLogo) {
            h.append("<img src=\"cid:").append(rutas.logica.CorreoSmtp.CID_LOGO).append("\" width=\"").append(anchoLogo)
                    .append("\" height=\"36\" alt=\"\" style=\"display:inline-block;width:").append(anchoLogo)
                    .append("px;height:36px;vertical-align:middle;border:0;outline:none;margin-right:10px;\">");
        }
        h.append("<span style=\"vertical-align:middle;\">").append(esc(negocio)).append("</span></td>");
        h.append("<td align=\"right\" valign=\"middle\" style=\"white-space:nowrap;\">"
                + "<div style=\"font-size:12px;font-weight:600;color:").append(GRIS).append(";\">Recibo de venta</div>"
                + "<div style=\"font-family:").append(FUENTE_TITULO).append(";font-size:22px;font-weight:700;"
                + "line-height:30px;\">N.º ").append(numero).append("</div></td>");
        h.append("</tr></table></td></tr>");
        h.append(raya(TXT, 1, "0 36px"));

        // mensaje escrito por el cajero
        h.append("<tr><td style=\"padding:24px 36px 4px;font-size:15px;line-height:24px;\">")
                .append(esc(mensaje.trim()).replace("\n", "<br>")).append("</td></tr>");

        // total destacado
        h.append("<tr><td style=\"padding:18px 36px 8px;\"><table role=\"presentation\" width=\"100%\" "
                + "cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"background:#F2F4F7;border-radius:5px;\">"
                + "<tr><td style=\"padding:18px 20px;\">");
        h.append("<div style=\"font-size:11px;font-weight:600;letter-spacing:1px;color:").append(GRIS).append(";\">")
                .append(comp.contraEntrega ? "TOTAL A PAGAR AL RECIBIR" : "TOTAL").append("</div>");
        h.append("<div style=\"font-size:30px;line-height:38px;font-weight:700;\">").append(esc(Tarifa.pesos(aPagar)))
                .append("</div></td>");
        h.append("<td align=\"right\" style=\"padding:18px 20px;font-size:13px;line-height:20px;color:").append(GRIS)
                .append(";\">").append(esc(comp.fechaTexto())).append("<br>")
                .append(esc(comp.contraEntrega ? "Contra entrega · efectivo" : mediosDePago(comp)))
                .append("</td></tr></table></td></tr>");

        // productos
        h.append("<tr><td style=\"padding:16px 36px 0;\"><table role=\"presentation\" width=\"100%\" cellpadding=\"0\" "
                + "cellspacing=\"0\" border=\"0\" style=\"font-size:14px;line-height:20px;\">");
        h.append("<tr><td style=\"padding:0 0 8px;font-size:12px;color:").append(GRIS).append(";border-bottom:1px solid ")
                .append(LINEA).append(";\">Producto</td><td align=\"right\" style=\"padding:0 0 8px;font-size:12px;color:")
                .append(GRIS).append(";border-bottom:1px solid ").append(LINEA).append(";\">Cant.</td>"
                + "<td align=\"right\" style=\"padding:0 0 8px;font-size:12px;color:").append(GRIS)
                .append(";border-bottom:1px solid ").append(LINEA).append(";\">Valor</td></tr>");
        for (rutas.modelo.LineaVenta l : comp.lineas) {
            String cant = l.getProducto().getUnidad() == rutas.modelo.Unidad.UNIDAD
                    ? PanelProductos.cantidad(l.getCantidad()) : CuadroGranel.peso(l.getCantidad(), l.getProducto());
            String celda = "padding:10px 0;border-bottom:1px solid " + LINEA + ";";
            h.append("<tr><td style=\"").append(celda).append("\">").append(esc(l.getProducto().getNombre()));
            h.append("<div style=\"font-size:12px;color:").append(GRIS).append(";\">")
                    .append(esc(Comprobante.precioUnitario(l)))
                    .append(l.getProducto().getUnidad() == rutas.modelo.Unidad.UNIDAD ? " c/u" : "");
            if (l.getDescuento() > 0) {
                h.append(" · descuento −").append(esc(Tarifa.pesos(l.getDescuento())));
            }
            h.append("</div></td>");
            h.append("<td align=\"right\" valign=\"top\" style=\"").append(celda)
                    .append("white-space:nowrap;padding-left:12px;\">").append(esc(cant)).append("</td>");
            h.append("<td align=\"right\" valign=\"top\" style=\"").append(celda)
                    .append("white-space:nowrap;padding-left:16px;\">").append(esc(Tarifa.pesos(l.getSubtotal()))).append("</td></tr>");
        }
        h.append("</table></td></tr>");

        // totales
        long bruto = 0;
        long descuentos = 0;
        for (rutas.modelo.LineaVenta l : comp.lineas) {
            bruto += l.getBruto();
            descuentos += l.getDescuento();
        }
        h.append("<tr><td style=\"padding:12px 36px 0;\"><table role=\"presentation\" width=\"100%\" cellpadding=\"0\" "
                + "cellspacing=\"0\" border=\"0\" style=\"font-size:14px;line-height:22px;\">");
        h.append(filaTotal("Subtotal", Tarifa.pesos(bruto), false));
        if (descuentos > 0) {
            h.append(filaTotal("Descuentos", "−" + Tarifa.pesos(descuentos), false));
        }
        if (comp.valorDomicilio > 0) {
            h.append(filaTotal("Domicilio", Tarifa.pesos(comp.valorDomicilio), false));
        }
        h.append(filaTotal("Total", Tarifa.pesos(aPagar), true));
        if (!comp.contraEntrega) {
            for (rutas.modelo.Pago p : comp.pagos) {
                if (p.getValor() > 0) {
                    h.append(filaTotal(p.getMetodo().getTexto(), Tarifa.pesos(p.getValor()), false));
                }
            }
            if (comp.recibido > 0) {
                h.append(filaTotal("Recibido", Tarifa.pesos(comp.recibido), false));
                h.append(filaTotal("Vueltas", Tarifa.pesos(comp.vueltas), false));
            }
        }
        h.append("</table></td></tr>");

        // domicilio
        if (comp.valorDomicilio > 0 && comp.datosCliente != null && !vacio(comp.datosCliente.getDireccion())) {
            h.append("<tr><td style=\"padding:20px 36px 0;font-size:14px;line-height:22px;\">"
                    + "<div style=\"font-weight:600;\">Entrega a domicilio</div>")
                    .append(esc(comp.datosCliente.getDireccion()));
            if (comp.contraEntrega) {
                h.append("<br>Paga al recibir: <b>").append(esc(Tarifa.pesos(aPagar))).append("</b>");
            } else {
                h.append("<br>El domicilio (").append(esc(Tarifa.pesos(comp.valorDomicilio)))
                        .append(") se paga al recibir.");
            }
            h.append("</td></tr>");
        }

        // adjunto
        h.append("<tr><td style=\"padding:22px 36px 0;font-size:13px;line-height:20px;color:").append(GRIS)
                .append(";\">El recibo completo va adjunto en PDF: <b style=\"color:").append(TXT).append(";\">")
                .append(esc(nombreArchivo(comp))).append("</b></td></tr>");

        // pie con los datos del negocio
        h.append("<tr><td style=\"padding:26px 36px 0;\"><div style=\"border-top:1px solid ").append(LINEA)
                .append(";font-size:0;line-height:0;\">&nbsp;</div></td></tr>");
        h.append("<tr><td style=\"padding:18px 36px 30px;font-size:13px;line-height:20px;color:").append(GRIS)
                .append(";\">");
        String gracias = vacio(a.gracias) || a.gracias.equalsIgnoreCase("GRACIAS POR SU COMPRA")
                ? "Gracias por su compra" : a.gracias;
        h.append("<div style=\"font-size:14px;font-weight:600;color:").append(TXT).append(";\">")
                .append(esc(gracias)).append("</div>");
        if (!vacio(a.telefono)) {
            h.append("Consultas sobre tu compra: ").append(esc(a.telefono)).append("<br>");
        }
        java.util.List<String> datos = new java.util.ArrayList<>();
        datos.add(negocio);
        if (!vacio(a.nit)) {
            datos.add("NIT " + a.nit);
        }
        if (!vacio(a.direccion)) {
            datos.add(a.direccion);
        }
        if (!vacio(a.ciudad)) {
            datos.add(a.ciudad);
        }
        h.append(esc(String.join(" · ", datos))).append("<br>");
        h.append("<span style=\"font-size:11px;\">Comprobante de venta. Este documento no es una factura electrónica"
                + " ni la reemplaza.</span>");
        h.append("</td></tr></table>");
        h.append("</td></tr></table></body></html>");
        return h.toString();
    }

    private static String filaTotal(String etiqueta, String valor, boolean fuerte) {
        if (fuerte) {
            return "<tr><td style=\"padding:8px 0 6px;border-top:1px solid " + TXT + ";font-size:15px;font-weight:600;\">"
                    + esc(etiqueta) + "</td><td align=\"right\" style=\"padding:8px 0 6px;border-top:1px solid " + TXT
                    + ";font-size:20px;font-weight:700;\">" + esc(valor) + "</td></tr>";
        }
        return "<tr><td style=\"padding:2px 0;color:" + GRIS + ";\">" + esc(etiqueta) + "</td><td align=\"right\" "
                + "style=\"padding:2px 0;\">" + esc(valor) + "</td></tr>";
    }

    private static String raya(String color, int grosor, String margen) {
        return "<tr><td style=\"padding:" + margen + ";\"><div style=\"border-top:" + grosor + "px solid " + color
                + ";font-size:0;line-height:0;\">&nbsp;</div></td></tr>";
    }

    private static String mediosDePago(Comprobante comp) {
        java.util.List<String> m = new java.util.ArrayList<>();
        for (rutas.modelo.Pago p : comp.pagos) {
            if (p.getValor() > 0) {
                m.add(p.getMetodo().getTexto());
            }
        }
        return m.isEmpty() ? "Efectivo" : String.join(" y ", m);
    }

    private static boolean vacio(String s) {
        return s == null || s.isBlank();
    }

    static String nombreArchivo(Comprobante comp) {
        return "Recibo-" + Comprobante.numeroTexto(comp.numero) + ".pdf";
    }

    /** Guarda el PDF donde diga la persona. */
    static void guardarPdf(JComponent dentroDe, Comprobante comp) {
        File f = Archivos.guardar(dentroDe, "Guardar el recibo como PDF", nombreArchivo(comp), "pdf");
        if (f == null) {
            return;
        }
        try {
            Files.write(f.toPath(), pdf(comp));
            Aviso.mostrar(dentroDe, Aviso.Tipo.BIEN, "PDF guardado", f.getName());
        } catch (IOException e) {
            Alerta.mostrar(dentroDe, Alerta.Tipo.AVISO, "No se pudo guardar el PDF",
                    "Revise que el archivo no esté abierto. Detalle: " + e.getMessage(), null);
        }
    }

    /** Ventanita "Enviar recibo por correo": ya llena con el correo del cliente, asunto, mensaje y el PDF. */
    static void abrir(JComponent dentroDe, Aplicacion app, Comprobante comp, rutas.modelo.Cliente cliente,
            java.util.function.Consumer<rutas.modelo.Cliente> alActualizarCliente) {
        String correoCliente = cliente == null ? "" : cliente.getCorreo();
        AjustesCorreo aj;
        try {
            aj = AjustesCorreo.leer(app.getTienda());
        } catch (java.sql.SQLException e) {
            aj = new AjustesCorreo();
        }
        final AjustesCorreo ajustes = aj;
        String negocio = comp.ajustes != null && !comp.ajustes.nombre.isBlank() ? comp.ajustes.nombre : comp.negocio;
        JPanel t = Capa.tarjeta(560, 24, 28);
        JLabel tit = Tema.tituloPantalla("Enviar recibo por correo");
        tit.setFont(Tema.subtitulo(18));
        tit.setIcon(new CapaCobro.LogoPago("/imagenes/archivos/gmail.png", 24));
        tit.setIconTextGap(10);
        t.add(tit);
        t.add(Tema.espacio(4));
        JLabel sub = new JLabel("Venta No. " + Comprobante.numeroTexto(comp.numero) + "  ·  " + Tarifa.pesos(comp.total));
        sub.setFont(Tema.texto(13.5f));
        sub.setForeground(Tema.GRIS_TEXTO);
        sub.setAlignmentX(Component.LEFT_ALIGNMENT);
        t.add(sub);
        t.add(Tema.espacio(12));
        if (!ajustes.listo()) {
            Tema.Parrafo falta = Tema.parrafo("Primero configure el Gmail de la tienda en Configuración › Correo"
                    + " (lo hace el administrador una sola vez).", 500);
            falta.setFont(Tema.textoFuerte(13.5f));
            falta.setForeground(Tema.AVISO_TEXTO);
            falta.setAlignmentX(Component.LEFT_ALIGNMENT);
            t.add(falta);
            t.add(Tema.espacio(10));
        }
        JTextField para = Tema.campo("correo@cliente.com");
        para.setText(correoCliente == null ? "" : correoCliente);
        JTextField asunto = Tema.campo("Asunto");
        asunto.setText("Recibo de compra No. " + Comprobante.numeroTexto(comp.numero) + " – " + negocio);
        Controles.AreaTexto mensaje = new Controles.AreaTexto("Mensaje", 5);
        String saludo = comp.cliente == null || comp.cliente.isBlank() ? "Hola," : "Hola " + comp.cliente.trim() + ",";
        mensaje.getArea().setText(saludo + "\n\nGracias por su compra en " + negocio + ". Le enviamos el recibo de su"
                + " compra No. " + Comprobante.numeroTexto(comp.numero) + " por " + Tarifa.pesos(comp.total)
                + ".\n\nCordialmente,\n" + negocio);
        mensaje.getArea().setCaretPosition(0); // que se vea desde el saludo
        t.add(campo("Para", para));
        // sugerencias: los correos de los clientes guardados (escribe "marce" y sale marcela@...)
        javax.swing.DefaultListModel<rutas.modelo.Cliente> sugeridos = new javax.swing.DefaultListModel<>();
        javax.swing.JList<rutas.modelo.Cliente> lista = new javax.swing.JList<>(sugeridos);
        lista.setFont(Tema.texto(13.5f));
        lista.setFixedCellHeight(30);
        lista.setVisibleRowCount(4);
        lista.setBorder(Tema.bordeLista());
        lista.setAlignmentX(Component.LEFT_ALIGNMENT);
        lista.setVisible(false);
        lista.setMaximumSize(new Dimension(Integer.MAX_VALUE, 4 * 30 + 4));
        lista.getAccessibleContext().setAccessibleName("Correos de clientes");
        lista.setCellRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(javax.swing.JList<?> l, Object v, int i, boolean sel, boolean f) {
                rutas.modelo.Cliente c = (rutas.modelo.Cliente) v;
                JLabel r = (JLabel) super.getListCellRendererComponent(l, c.getCorreo() + "   ·   " + c.getNombre(), i, sel, f);
                r.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10));
                r.setBackground(sel ? Tema.AZUL_SUAVE : Tema.BLANCO);
                r.setForeground(Tema.GRIS_OSCURO);
                return r;
            }
        });
        t.add(lista);
        javax.swing.JCheckBox guardar = Tema.casilla(cliente == null ? " " : "Guardar este correo en «" + cliente.getNombre() + "»");
        guardar.setAlignmentX(Component.LEFT_ALIGNMENT);
        guardar.setSelected(true);
        guardar.setVisible(false);
        t.add(guardar);
        t.add(Tema.espacio(8));
        t.add(campo("Asunto", asunto));
        t.add(Tema.espacio(8));
        mensaje.setPreferredSize(new Dimension(480, 150));
        mensaje.setMaximumSize(new Dimension(Integer.MAX_VALUE, 150));
        t.add(campo("Mensaje", mensaje));
        t.add(Tema.espacio(8));
        JLabel adj = new JLabel("Adjunto:  " + nombreArchivo(comp));
        adj.setIcon(new PanelProductos.BotonIcono("", PanelProductos.Icono.PDF, "", () -> { }).getIcon());
        adj.setFont(Tema.textoFuerte(13));
        adj.setForeground(Tema.GRIS_OSCURO);
        adj.setAlignmentX(Component.LEFT_ALIGNMENT);
        t.add(adj);
        JLabel error = new JLabel(" ");
        error.setFont(Tema.textoFuerte(13));
        error.setForeground(Tema.ERROR_TEXTO);
        error.setAlignmentX(Component.LEFT_ALIGNMENT);
        t.add(Tema.espacio(6));
        t.add(error);
        t.add(Tema.espacio(8));
        Tema.Boton enviar = Tema.botonAccion("Enviar");
        Tema.Boton cancelar = Tema.botonSecundario("Cancelar");
        JPanel pie = new JPanel(new BorderLayout());
        pie.setOpaque(false);
        pie.setAlignmentX(Component.LEFT_ALIGNMENT);
        pie.add(Tema.fila(8, cancelar, enviar), BorderLayout.EAST);
        t.add(pie);
        Capa capa = new Capa(dentroDe, t, true);
        cancelar.addActionListener(e -> capa.cerrar());
        boolean[] eligiendo = {false};
        Runnable revisar = () -> {
            String txt = para.getText().trim();
            // la casilla sale si hay cliente y el correo escrito es nuevo para el
            guardar.setVisible(cliente != null && CorreoSmtp.esCorreo(txt) && !txt.equalsIgnoreCase(cliente.getCorreo()));
            sugeridos.clear();
            if (!eligiendo[0] && txt.length() >= 2 && !CorreoSmtp.esCorreo(txt)) {
                String clave = rutas.modelo.Geo.normalizar(txt);
                for (rutas.modelo.Cliente c : app.getClientes().enOrden()) {
                    if (sugeridos.size() >= 4) {
                        break;
                    }
                    if (c.isActivo() && !c.getCorreo().isEmpty() && (c.getCorreo().toLowerCase().startsWith(txt.toLowerCase())
                            || FormularioCliente.coincide(c, txt) || rutas.modelo.Geo.normalizar(c.getCorreo()).contains(clave))) {
                        sugeridos.addElement(c);
                    }
                }
            }
            lista.setVisible(!sugeridos.isEmpty());
            capa.reacomodar();
        };
        para.getDocument().addDocumentListener(PanelProductos.alCambiar(revisar));
        Runnable elegir = () -> {
            rutas.modelo.Cliente c = lista.getSelectedValue();
            if (c != null) {
                eligiendo[0] = true;
                para.setText(c.getCorreo());
                eligiendo[0] = false;
                sugeridos.clear();
                lista.setVisible(false);
                capa.reacomodar();
                enviar.requestFocusInWindow();
            }
        };
        lista.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                elegir.run();
            }
        });
        lista.getInputMap().put(javax.swing.KeyStroke.getKeyStroke("ENTER"), "elegir");
        lista.getActionMap().put("elegir", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                elegir.run();
            }
        });
        para.getInputMap().put(javax.swing.KeyStroke.getKeyStroke("DOWN"), "bajar");
        para.getActionMap().put("bajar", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (lista.isVisible()) {
                    lista.setSelectedIndex(0);
                    lista.requestFocusInWindow();
                }
            }
        });
        enviar.setEnabled(ajustes.listo());
        enviar.addActionListener(e -> {
            String destino = para.getText().trim();
            if (!CorreoSmtp.esCorreo(destino)) {
                error.setText("Revise el correo del cliente (ej. nombre@gmail.com)");
                return;
            }
            enviar.setEnabled(false);
            cancelar.setEnabled(false);
            enviar.setText("Enviando…");
            error.setForeground(Tema.GRIS_TEXTO);
            error.setText("Enviando el correo, espere un momento…");
            String remitente = ajustes.remitente.isBlank() ? negocio : ajustes.remitente;
            String asuntoTxt = asunto.getText();
            String texto = mensaje.getArea().getText();
            new SwingWorker<String, Void>() {
                @Override
                protected String doInBackground() {
                    try {
                        // el detalle de la compra va tambien en el cuerpo (texto y HTML): un recibo con solo
                        // un PDF adjunto se parece a un engaño y Gmail lo manda a spam
                        CorreoSmtp.gmail(ajustes.cuenta, ajustes.clave).enviar(remitente, destino, asuntoTxt,
                                texto + "\n\n" + detalleTexto(comp), html(texto, comp), ReciboCarta.logoPng(comp),
                                nombreArchivo(comp), pdf(comp),
                                "application/pdf");
                        return null;
                    } catch (CorreoSmtp.ErrorCorreo ex) {
                        return ex.getMessage();
                    } catch (IOException | RuntimeException ex) {
                        return "No se pudo armar el PDF: " + ex.getMessage();
                    }
                }

                @Override
                protected void done() {
                    String r;
                    try {
                        r = get();
                    } catch (Exception ex) {
                        r = "No se pudo enviar: " + ex.getMessage();
                    }
                    if (r == null) {
                        capa.cerrar();
                        String extra = "";
                        if (guardar.isVisible() && guardar.isSelected() && cliente != null) {
                            try {
                                rutas.modelo.Cliente nuevo = cliente.conCorreo(destino);
                                app.guardarCliente(nuevo);
                                alActualizarCliente.accept(nuevo);
                                extra = "  ·  correo guardado en el cliente";
                            } catch (IOException ex) {
                                extra = "  ·  no se pudo guardar el correo en el cliente";
                            }
                        }
                        Aviso.mostrar(dentroDe, Aviso.Tipo.BIEN, "Recibo enviado", destino + extra
                                + "  ·  si le llega a spam, que marque «No es spam» una vez");
                    } else {
                        enviar.setText("Enviar");
                        enviar.setEnabled(true);
                        cancelar.setEnabled(true);
                        error.setForeground(Tema.ERROR_TEXTO);
                        error.setText("<html>" + r + "</html>");
                        capa.reacomodar();
                    }
                }
            }.execute();
        });
        capa.mostrar(para.getText().isEmpty() ? para : enviar);
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
        c.getAccessibleContext().setAccessibleName(etiqueta);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.getPreferredSize().height));
        return p;
    }
}
