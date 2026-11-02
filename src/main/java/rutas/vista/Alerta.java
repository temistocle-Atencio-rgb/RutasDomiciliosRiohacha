package rutas.vista;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

/**
 * Alerta que detiene, al estilo que le gusto al dueño: icono grande en circulo,
 * titulo corto y mensaje claro. Se dibuja encima de la ventana (Capa), nunca como
 * otra ventana. Enter acepta y Esc cierra.
 * Usarla solo para lo que de verdad detiene; lo demas va como Aviso.
 */
public final class Alerta {

    public enum Tipo { INFO, EXITO, AVISO, PREGUNTA }

    private Alerta() {
    }

    /** Alerta con un solo boton. */
    public static Capa mostrar(JComponent dentroDe, Tipo tipo, String titulo, String mensaje, Runnable alAceptar) {
        return crear(dentroDe, tipo, titulo, mensaje, "Entendido", null, alAceptar);
    }

    /** Pregunta con dos botones: el primero hace la accion, el segundo solo cierra. */
    public static Capa confirmar(JComponent dentroDe, String titulo, String mensaje, String textoSi, String textoNo,
            Runnable alAceptar) {
        return crear(dentroDe, Tipo.PREGUNTA, titulo, mensaje, textoSi, textoNo, alAceptar);
    }

    private static Capa crear(JComponent dentroDe, Tipo tipo, String titulo, String mensaje, String textoSi,
            String textoNo, Runnable alAceptar) {
        JPanel t = Capa.tarjeta(460, 32, 40);

        Icono icono = new Icono(tipo);
        icono.setAlignmentX(Component.CENTER_ALIGNMENT);
        t.add(icono);
        t.add(Tema.espacio(20));
        JLabel lt = new JLabel(titulo, SwingConstants.CENTER);
        lt.setFont(Tema.subtitulo(18));
        lt.setForeground(Tema.GRIS_OSCURO);
        lt.setAlignmentX(Component.CENTER_ALIGNMENT);
        t.add(lt);
        t.add(Tema.espacio(10));
        TextoCentrado lm = new TextoCentrado(mensaje, Tema.texto(15), Tema.GRIS_OSCURO, 380);
        lm.setAlignmentX(Component.CENTER_ALIGNMENT);
        t.add(lm);
        t.add(Tema.espacio(24));

        Tema.Boton si = Tema.botonPrimario(textoSi);
        si.setFont(Tema.textoFuerte(14.5f));
        JPanel botones = textoNo == null ? Tema.fila(10, si) : Tema.fila(10, si, Tema.botonSecundario(textoNo));
        botones.setAlignmentX(Component.CENTER_ALIGNMENT);
        ((java.awt.FlowLayout) botones.getLayout()).setAlignment(java.awt.FlowLayout.CENTER);
        t.add(botones);

        Capa capa = new Capa(dentroDe, t, true);
        si.addActionListener(e -> {
            capa.cerrar();
            if (alAceptar != null) {
                alAceptar.run();
            }
        });
        javax.swing.AbstractButton no = null;
        if (textoNo != null) {
            no = (javax.swing.AbstractButton) botones.getComponent(1);
            no.addActionListener(e -> capa.cerrar());
        }
        for (javax.swing.AbstractButton b : new javax.swing.AbstractButton[]{si, no}) {
            if (b != null) { // Enter oprime el boton que tenga el foco
                b.getInputMap(JComponent.WHEN_FOCUSED).put(javax.swing.KeyStroke.getKeyStroke("ENTER"), "pressed");
                b.getInputMap(JComponent.WHEN_FOCUSED).put(javax.swing.KeyStroke.getKeyStroke("released ENTER"),
                        "released");
            }
        }
        // en las preguntas el foco queda en la opcion que no borra nada: un Enter distraido no hace daño
        capa.mostrar(no != null ? no : si);
        return capa;
    }

    /** Texto de varias lineas centrado (parte las lineas segun el ancho). */
    static class TextoCentrado extends JPanel {
        private final String texto;
        private final int ancho;

        TextoCentrado(String texto, java.awt.Font fuente, Color color, int ancho) {
            this.texto = texto == null ? "" : texto;
            this.ancho = ancho;
            setOpaque(false);
            setFont(fuente);
            setForeground(color);
            getAccessibleContext().setAccessibleName(this.texto);
        }

        private java.util.List<String> lineas(java.awt.FontMetrics fm) {
            java.util.List<String> l = new java.util.ArrayList<>();
            for (String parrafo : texto.split("\n")) {
                StringBuilder linea = new StringBuilder();
                for (String palabra : parrafo.split(" ")) {
                    String prueba = linea.length() == 0 ? palabra : linea + " " + palabra;
                    if (fm.stringWidth(prueba) > ancho && linea.length() > 0) {
                        l.add(linea.toString());
                        linea = new StringBuilder(palabra);
                    } else {
                        linea = new StringBuilder(prueba);
                    }
                }
                l.add(linea.toString());
            }
            return l;
        }

        @Override
        public Dimension getPreferredSize() {
            java.awt.FontMetrics fm = getFontMetrics(getFont());
            return new Dimension(ancho, lineas(fm).size() * fm.getHeight() + 2);
        }

        @Override
        public Dimension getMaximumSize() {
            return getPreferredSize();
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Tema.suavizar(g);
            g.setFont(getFont());
            g.setColor(getForeground());
            java.awt.FontMetrics fm = g.getFontMetrics();
            int y = fm.getAscent();
            for (String l : lineas(fm)) {
                g.drawString(l, (getWidth() - fm.stringWidth(l)) / 2, y);
                y += fm.getHeight();
            }
            g.dispose();
        }
    }

    /** Circulo de 72 px con el simbolo del tipo, en linea (sin relleno de color). */
    static class Icono extends JPanel {
        private final Tipo tipo;

        Icono(Tipo tipo) {
            this(tipo, 64);
        }

        Icono(Tipo tipo, int lado) {
            this.tipo = tipo;
            setOpaque(false);
            setPreferredSize(new Dimension(lado, lado));
            setMaximumSize(new Dimension(lado, lado));
        }

        static Color color(Tipo t) {
            switch (t) {
                case EXITO:
                    return Tema.EXITO;
                case AVISO:
                    return Tema.NARANJA;
                default:
                    return Tema.AZUL;
            }
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Tema.suavizar(g);
            Color c = color(tipo);
            int lado = Math.min(getWidth(), getHeight());
            double k = (lado - 4) / 72.0; // se dibuja a 72 y se escala al tamano pedido
            g.translate((getWidth() - 72 * k) / 2, (getHeight() - 72 * k) / 2);
            g.scale(k, k);
            int d = 72, x = 0, y = 0;
            g.setColor(Tema.mezclar(c, Color.WHITE, 0.88));
            g.fillOval(x, y, d, d);
            g.setColor(c);
            g.setStroke(new BasicStroke(3.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawOval(x + 2, y + 2, d - 4, d - 4);
            int cx = x + d / 2, cy = y + d / 2;
            switch (tipo) {
                case EXITO:
                    g.drawPolyline(new int[]{cx - 15, cx - 4, cx + 16}, new int[]{cy + 1, cy + 12, cy - 11}, 3);
                    break;
                case AVISO:
                    g.drawLine(cx, cy - 16, cx, cy + 5);
                    g.fillOval(cx - 3, cy + 11, 6, 6);
                    break;
                case PREGUNTA:
                    g.drawArc(cx - 10, cy - 19, 20, 18, 160, -250);
                    g.drawLine(cx, cy - 1, cx, cy + 5);
                    g.fillOval(cx - 3, cy + 11, 6, 6);
                    break;
                default:
                    g.fillOval(cx - 3, cy - 18, 6, 6);
                    g.drawLine(cx, cy - 5, cx, cy + 16);
            }
            g.dispose();
        }
    }
}
