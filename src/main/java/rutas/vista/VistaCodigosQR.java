package rutas.vista;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.datatransfer.StringSelection;
import java.awt.image.BufferedImage;
import java.awt.print.PageFormat;
import java.awt.print.PrinterJob;
import java.io.File;
import java.util.EnumMap;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import rutas.logica.AjustesRecibo;
import rutas.logica.BaseTienda;
import rutas.logica.EnlacesQR;
import rutas.logica.NubeRepartidor;

/**
 * Configuración › Códigos QR: la tienda en línea, el WhatsApp de la tienda, la página de los
 * repartidores y la del encargado. Se dibujan cada vez con la direccion actual (Conexión) y el
 * celular de «Mi negocio», con el logo en el centro. Si cambian, se avisa que los impresos
 * quedaron viejos.
 */
class VistaCodigosQR extends JPanel {

    private final Aplicacion app;
    private final Map<EnlacesQR.Tipo, Tarjeta> tarjetas = new EnumMap<>(EnlacesQR.Tipo.class);
    private final Tema.Parrafo msg = PanelConfiguracion.mensaje();
    private AjustesRecibo negocio = new AjustesRecibo();
    private String pagina = "";
    private BufferedImage logo;

    VistaCodigosQR(Aplicacion app) {
        super(new BorderLayout());
        this.app = app;
        setOpaque(false);
        Tema.Tarjeta t = PanelConfiguracion.tarjeta();
        t.add(Tema.seccion("Códigos QR"));
        t.add(PanelConfiguracion.nota("Cada quien escanea su código con la cámara del celular y le abre su página."
                + " Llevan el logo de «Mi negocio» en el centro. Salen de la dirección de la página (Conexión) y del"
                + " teléfono de «Mi negocio»: si los cambia, aquí se ven nuevos y le avisamos cuáles hay que volver a imprimir."
                + " Ninguno lleva claves."));
        t.add(Tema.espacio(12));
        JPanel grilla = new JPanel(new GridLayout(0, 2, 12, 12));
        grilla.setOpaque(false);
        grilla.setAlignmentX(LEFT_ALIGNMENT);
        for (EnlacesQR.Tipo tipo : EnlacesQR.Tipo.values()) {
            Tarjeta k = new Tarjeta(tipo);
            tarjetas.put(tipo, k);
            grilla.add(k);
        }
        t.add(grilla);
        t.add(Tema.espacio(10));
        msg.setAlignmentX(LEFT_ALIGNMENT);
        t.add(msg);
        add(t, BorderLayout.NORTH);
        // cada vez que se abre se lee de nuevo (pudieron cambiar la dirección, el teléfono o el logo)
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) {
                cargar();
            }
        });
        cargar();
    }

    private BaseTienda bd() {
        return app.getTienda();
    }

    /** Lee de nuevo la direccion, el telefono y el logo, y redibuja los cuatro. */
    void cargar() {
        try {
            negocio = AjustesRecibo.leer(bd());
            pagina = NubeRepartidor.Ajustes.leer(bd()).pagina;
        } catch (Exception e) {
            PanelConfiguracion.decir(msg, "No se pudieron leer los ajustes: " + e.getMessage(), false);
        }
        logo = ImagenQR.logo(negocio.logo);
        for (Tarjeta k : tarjetas.values()) {
            k.mostrar();
        }
        // los textos que bajan de renglon miden su alto con el ancho real: se acomoda otra vez ya visible
        javax.swing.SwingUtilities.invokeLater(() -> {
            revalidate();
            javax.swing.SwingUtilities.invokeLater(this::revalidate);
        });
    }

    private String enlace(EnlacesQR.Tipo t) {
        return EnlacesQR.enlace(t, pagina, negocio.telefono);
    }

    /** Lo que se imprimio o guardo la ultima vez de este QR ("" = nunca). */
    private String impreso(EnlacesQR.Tipo t) {
        try {
            return bd().leerConfig("qr.impreso." + t.name(), "");
        } catch (Exception e) {
            return "";
        }
    }

    private void anotarImpreso(EnlacesQR.Tipo t) {
        try {
            bd().guardarConfigs(new String[][]{{"qr.impreso." + t.name(), enlace(t)}});
        } catch (Exception e) {
            // si no se anota, solo se pierde el aviso
        }
        tarjetas.get(t).mostrar();
    }

    private ImagenQR.Datos datos(EnlacesQR.Tipo t) {
        ImagenQR.Datos d = new ImagenQR.Datos();
        d.tipo = t;
        d.enlace = enlace(t);
        d.tienda = negocio.nombre;
        d.logo = logo;
        StringBuilder pie = new StringBuilder();
        if (!negocio.telefono.isBlank()) {
            pie.append("Tel. ").append(negocio.telefono.trim());
        }
        if (!negocio.direccion.isBlank()) {
            pie.append(pie.length() > 0 ? " · " : "").append(negocio.direccion.trim());
        }
        if (!negocio.ciudad.isBlank()) {
            pie.append(pie.length() > 0 ? ", " : "").append(negocio.ciudad.trim());
        }
        d.pie = pie.toString();
        return d;
    }

    private void imprimir(EnlacesQR.Tipo t) {
        PrinterJob job = PrinterJob.getPrinterJob();
        job.setJobName("Código QR - " + t.nombre);
        PageFormat pf = job.defaultPage();
        java.awt.print.Paper papel = new java.awt.print.Paper();
        papel.setSize(612, 792); // carta
        papel.setImageableArea(36, 36, 612 - 72, 792 - 72);
        pf.setPaper(papel);
        pf.setOrientation(PageFormat.PORTRAIT);
        job.setPrintable(ImagenQR.imprimible(datos(t)), pf);
        if (!job.printDialog()) {
            return;
        }
        PanelConfiguracion.decir(msg, "Imprimiendo el cartel de «" + t.nombre + "»…", true);
        new Thread(() -> {
            String error = null;
            try {
                job.print();
            } catch (Exception e) {
                error = e.getMessage();
            }
            String err = error;
            javax.swing.SwingUtilities.invokeLater(() -> {
                if (err == null) {
                    anotarImpreso(t);
                    PanelConfiguracion.decir(msg, "Se mandó a imprimir el cartel de «" + t.nombre + "».", true);
                } else {
                    PanelConfiguracion.decir(msg, "No se pudo imprimir: " + err, false);
                }
            });
        }, "imprimir-qr").start();
    }

    private void guardarImagen(EnlacesQR.Tipo t) {
        String nombre = "QR " + t.nombre.replace("/", "-") + ".png";
        File f = Archivos.guardar(this, "Guardar imagen del código QR", nombre, "png");
        if (f == null) {
            return;
        }
        try {
            javax.imageio.ImageIO.write(ImagenQR.imagenCartel(datos(t)), "png", f);
            anotarImpreso(t);
            PanelConfiguracion.decir(msg, "Se guardó «" + f.getName() + "». Sirve para estados de WhatsApp o Instagram.", true);
        } catch (Exception e) {
            PanelConfiguracion.decir(msg, "No se pudo guardar la imagen: " + e.getMessage(), false);
        }
    }

    private void copiar(EnlacesQR.Tipo t) {
        java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(enlace(t)), null);
        PanelConfiguracion.decir(msg, "Se copió el enlace de «" + t.nombre + "».", true);
    }

    /** Lo que se escribe cuando no se puede armar el QR. */
    private String falta(EnlacesQR.Tipo t) {
        return t == EnlacesQR.Tipo.WHATSAPP
                ? "Escriba el celular de la tienda en «Mi negocio › Teléfono» (10 números, empieza por 3)."
                : "Falta la dirección de la página en «Conexión».";
    }

    /** Texto que baja de renglón en un ancho fijo (un JLabel mide bien su alto así). */
    private static String html(String t) {
        String limpio = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        return "<html><div style='width:165px'>" + limpio + "</div></html>";
    }

    /** Un QR con su nombre, para quién es, el enlace y sus botones. */
    private final class Tarjeta extends JPanel {

        private final EnlacesQR.Tipo tipo;
        private final JLabel imagen = new JLabel();
        private final JLabel lblEnlace = new JLabel(" ");
        private final JLabel aviso = new JLabel(" ");
        private final Tema.Boton bImprimir = Tema.botonSecundario("Imprimir cartel");
        private final Tema.Boton bGuardar = Tema.botonSecundario("Guardar imagen");
        private final Tema.Boton bCopiar = Tema.botonPlano("Copiar enlace");

        Tarjeta(EnlacesQR.Tipo tipo) {
            super(new BorderLayout(0, 0));
            this.tipo = tipo;
            setBackground(Tema.BLANCO);
            setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Tema.BORDE),
                    BorderFactory.createEmptyBorder(12, 12, 12, 12)));
            imagen.setPreferredSize(new Dimension(150, 150));
            imagen.setHorizontalAlignment(JLabel.CENTER);
            imagen.setVerticalAlignment(JLabel.TOP);

            JPanel der = PanelConfiguracion.columna();
            JLabel tit = new JLabel(tipo.nombre);
            tit.setFont(Tema.textoFuerte(14));
            tit.setForeground(Tema.AZUL_PROFUNDO);
            tit.setAlignmentX(LEFT_ALIGNMENT);
            der.add(tit);
            der.add(Tema.espacio(2));
            JLabel para = new JLabel(html(tipo.para));
            para.setFont(Tema.texto(12.5f));
            para.setForeground(Tema.GRIS_TEXTO);
            para.setAlignmentX(LEFT_ALIGNMENT);
            der.add(para);
            der.add(Tema.espacio(6));
            lblEnlace.setFont(Tema.texto(12));
            lblEnlace.setForeground(Tema.AZUL);
            lblEnlace.setAlignmentX(LEFT_ALIGNMENT);
            der.add(lblEnlace);
            aviso.setFont(Tema.textoFuerte(12));
            aviso.setForeground(Tema.AVISO_TEXTO);
            aviso.setAlignmentX(LEFT_ALIGNMENT);
            der.add(aviso);
            JPanel arriba = new JPanel(new BorderLayout(14, 0));
            arriba.setOpaque(false);
            arriba.add(imagen, BorderLayout.WEST);
            arriba.add(der, BorderLayout.CENTER);
            add(arriba, BorderLayout.CENTER);
            // botones abajo, a lo ancho de la tarjeta
            JPanel fila = new JPanel(new GridLayout(1, 2, 8, 0));
            fila.setOpaque(false);
            fila.add(bImprimir);
            fila.add(bGuardar);
            JPanel abajo = new JPanel(new BorderLayout(0, 4));
            abajo.setOpaque(false);
            abajo.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
            abajo.add(fila, BorderLayout.NORTH);
            JPanel copiar = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
            copiar.setOpaque(false);
            copiar.add(bCopiar);
            abajo.add(copiar, BorderLayout.SOUTH);
            add(abajo, BorderLayout.SOUTH);

            bImprimir.addActionListener(e -> imprimir(tipo));
            bGuardar.addActionListener(e -> guardarImagen(tipo));
            bCopiar.addActionListener(e -> copiar(tipo));
            bImprimir.getAccessibleContext().setAccessibleName("Imprimir cartel de " + tipo.nombre);
            bGuardar.getAccessibleContext().setAccessibleName("Guardar imagen de " + tipo.nombre);
            bCopiar.getAccessibleContext().setAccessibleName("Copiar enlace de " + tipo.nombre);
        }

        void mostrar() {
            String e = enlace(tipo);
            boolean hay = !e.isEmpty();
            bImprimir.setEnabled(hay);
            bGuardar.setEnabled(hay);
            bCopiar.setEnabled(hay);
            if (!hay) {
                imagen.setIcon(null);
                imagen.setText("Sin código");
                imagen.setForeground(Tema.GRIS_TEXTO);
                lblEnlace.setText(" ");
                aviso.setText(html(falta(tipo)));
                return;
            }
            imagen.setText(null);
            // se dibuja al doble y se reduce: queda nitido en pantallas con escala de Windows
            BufferedImage im = ImagenQR.imagen(e, logo, 300, Tema.AZUL_PROFUNDO);
            imagen.setIcon(new ImageIcon(im.getScaledInstance(150, 150, java.awt.Image.SCALE_SMOOTH)));
            imagen.getAccessibleContext().setAccessibleName("Código QR de " + tipo.nombre);
            String corto = e.replaceFirst("^https?://", "").replaceFirst("\\?.*$", "");
            lblEnlace.setText(corto);
            lblEnlace.setToolTipText(e);
            String antes = impreso(tipo);
            aviso.setText(!antes.isEmpty() && !antes.equals(e)
                    ? html("Cambió la dirección: el que imprimió antes ya no sirve. Imprímalo otra vez.") : " ");
        }
    }
}
