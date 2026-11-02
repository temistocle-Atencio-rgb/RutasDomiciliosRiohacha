package rutas.vista;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.print.PrinterException;
import java.awt.print.PrinterJob;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;

/** Vista previa de un documento (todas sus paginas) con el boton para imprimirlo o guardarlo en PDF. */
final class VistaPrevia {

    private VistaPrevia() {
    }

    static void mostrar(JComponent dentroDe, DocumentoLista doc, String nombreTrabajo) {
        if (Capa.hayAbierta(dentroDe)) {
            return;
        }
        javax.swing.JRootPane raiz = javax.swing.SwingUtilities.getRootPane(dentroDe);
        int ancho = raiz == null ? 900 : Math.min(980, raiz.getWidth() - 80);
        int alto = raiz == null ? 600 : raiz.getHeight() - 140;
        JPanel modal = new JPanel(new BorderLayout());
        modal.setOpaque(false);
        modal.setPreferredSize(new Dimension(ancho, alto));

        JPanel cabeza = new JPanel(new BorderLayout(12, 0));
        cabeza.setOpaque(false);
        cabeza.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Tema.BORDE),
                BorderFactory.createEmptyBorder(12, 20, 12, 14)));
        JLabel t = new JLabel("Vista previa del documento");
        t.setFont(Tema.subtitulo(17));
        t.setForeground(Tema.GRIS_OSCURO);
        cabeza.add(t, BorderLayout.WEST);
        Tema.Boton imprimir = Tema.botonAccion("Imprimir o guardar PDF");
        imprimir.setIcon(Iconos.de("notas", 18, Tema.GRIS_OSCURO));
        Tema.Boton cerrar = Tema.botonSecundario("Cerrar");
        cabeza.add(Tema.fila(8, cerrar, imprimir), BorderLayout.EAST);
        modal.add(cabeza, BorderLayout.NORTH);

        JPanel hojas = new JPanel(new java.awt.GridBagLayout());
        hojas.setBackground(new Color(0xE4E8ED));
        hojas.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        double escala = Math.min(1.3, (ancho - 70) / 612.0);
        java.awt.GridBagConstraints gc = new java.awt.GridBagConstraints();
        gc.gridx = 0;
        gc.insets = new java.awt.Insets(0, 0, 14, 0);
        for (int i = 0; i < doc.getNumberOfPages(); i++) {
            gc.gridy = i;
            hojas.add(new Hoja(doc, i, escala), gc); // una hoja debajo de otra, centradas
        }
        JScrollPane sc = Tema.scrollSinBorde(hojas);
        sc.getVerticalScrollBar().setUnitIncrement(24);
        modal.add(sc, BorderLayout.CENTER);

        Capa capa = new Capa(dentroDe, modal, true);
        cerrar.addActionListener(e -> capa.cerrar());
        imprimir.addActionListener(e -> {
            PrinterJob job = PrinterJob.getPrinterJob();
            job.setJobName(nombreTrabajo);
            job.setPageable(doc);
            try {
                if (job.printDialog()) {
                    job.print();
                    capa.cerrar();
                    Aviso.mostrar(dentroDe, Aviso.Tipo.BIEN, "Documento enviado",
                            "Para PDF escoja «Microsoft Print to PDF» en la lista de impresoras");
                }
            } catch (PrinterException | RuntimeException ex) {
                Alerta.mostrar(dentroDe, Alerta.Tipo.AVISO, "No se pudo imprimir",
                        "Revise que haya una impresora instalada. Para PDF escoja «Microsoft Print to PDF».", null);
            }
        });
        capa.mostrar(imprimir);
    }

    /** Una hoja carta dibujada a escala, con sombra. */
    private static class Hoja extends JComponent {
        private final DocumentoLista doc;
        private final int pagina;
        private final double escala;

        Hoja(DocumentoLista doc, int pagina, double escala) {
            this.doc = doc;
            this.pagina = pagina;
            this.escala = escala;
            Dimension d = new Dimension((int) (612 * escala), (int) (792 * escala));
            setPreferredSize(d);
            setMinimumSize(d);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Tema.suavizar(g);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, getWidth(), getHeight());
            g.scale(escala, escala);
            g.translate(36, 36);
            doc.dibujar(g, 540, 720, pagina);
            g.dispose();
        }
    }
}
