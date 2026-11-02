package rutas.vista;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.print.PageFormat;
import java.awt.print.Printable;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Codigo de barras Code 128 hecho a mano (sin librerias): lo leen todos los lectores
 * de la tienda. Si el codigo es solo de numeros usa el juego C (dos numeros por barra)
 * y queda mas corto; si tiene letras usa el juego B.
 */
public final class CodigoBarras {

    /** Anchos de barra y espacio de cada simbolo (0 a 105). El de parada va aparte. */
    private static final String[] PATRONES = {
        "212222", "222122", "222221", "121223", "121322", "131222", "122213", "122312", "132212", "221213",
        "221312", "231212", "112232", "122132", "122231", "113222", "123122", "123221", "223211", "221132",
        "221231", "213212", "223112", "312131", "311222", "321122", "321221", "312212", "322112", "322211",
        "212123", "212321", "232121", "111323", "131123", "131321", "112313", "132113", "132311", "211313",
        "231113", "231311", "112133", "112331", "132131", "113123", "113321", "133121", "313121", "211331",
        "231131", "213113", "213311", "213131", "311123", "311321", "331121", "312113", "312311", "332111",
        "314111", "221411", "431111", "111224", "111422", "121124", "121421", "141122", "141221", "112214",
        "112412", "122114", "122411", "142112", "142211", "241211", "221114", "413111", "241112", "134111",
        "111242", "121142", "121241", "114212", "124112", "124211", "411212", "421112", "421211", "212141",
        "214121", "412121", "111143", "111341", "131141", "114113", "114311", "411113", "411311", "113141",
        "114131", "311141", "411131", "211412", "211214", "211232"
    };
    private static final String PARADA = "2331112";
    private static final int INICIO_B = 104;
    private static final int INICIO_C = 105;
    private static final int CAMBIAR_A_B = 100;
    private static final int MARGEN = 10; // zona en blanco a cada lado (en modulos)

    private CodigoBarras() {
    }

    /** True si el texto se puede dibujar (numeros, letras sin tilde y signos comunes). */
    public static boolean sePuede(String codigo) {
        if (codigo == null || codigo.isBlank() || codigo.length() > 40) {
            return false;
        }
        for (char c : codigo.toCharArray()) {
            if (c < 32 || c > 126) {
                return false;
            }
        }
        return true;
    }

    /** Los simbolos del codigo, con el de inicio y el de control (sin la parada). */
    public static List<Integer> simbolos(String codigo) {
        List<Integer> s = new ArrayList<>();
        boolean numeros = codigo.chars().allMatch(Character::isDigit) && codigo.length() >= 2;
        if (numeros) {
            s.add(INICIO_C);
            int pares = codigo.length() / 2 * 2;
            for (int i = 0; i < pares; i += 2) {
                s.add(Integer.parseInt(codigo.substring(i, i + 2)));
            }
            if (pares < codigo.length()) { // sobra un numero: se pasa al juego B
                s.add(CAMBIAR_A_B);
                s.add(codigo.charAt(pares) - 32);
            }
        } else {
            s.add(INICIO_B);
            for (char c : codigo.toCharArray()) {
                s.add(c - 32);
            }
        }
        long suma = s.get(0);
        for (int i = 1; i < s.size(); i++) {
            suma += (long) i * s.get(i);
        }
        s.add((int) (suma % 103));
        return s;
    }

    /** Anchos alternados barra/espacio de todo el codigo, empezando por barra. */
    static int[] anchos(String codigo) {
        StringBuilder sb = new StringBuilder();
        for (int v : simbolos(codigo)) {
            sb.append(PATRONES[v]);
        }
        sb.append(PARADA);
        int[] a = new int[sb.length()];
        for (int i = 0; i < a.length; i++) {
            a[i] = sb.charAt(i) - '0';
        }
        return a;
    }

    static int modulos(int[] anchos) {
        int m = 2 * MARGEN;
        for (int a : anchos) {
            m += a;
        }
        return m;
    }

    /**
     * Dibuja las barras en el rectangulo dado (con su zona en blanco a los lados).
     * El ancho de cada modulo se redondea a pixeles enteros para que el lector no se confunda.
     */
    static void dibujarBarras(Graphics2D g, String codigo, int x, int y, int ancho, int alto) {
        int[] a = anchos(codigo);
        int total = modulos(a);
        double modulo = Math.max(1, Math.floor((double) ancho / total));
        if (ancho / (double) total < 1) {
            modulo = (double) ancho / total; // muy angosto: se dibuja igual, aunque quede fino
        }
        double xi = x + (ancho - modulo * total) / 2 + MARGEN * modulo;
        g.setColor(Color.BLACK);
        boolean barra = true;
        for (int w : a) {
            if (barra) {
                g.fill(new java.awt.geom.Rectangle2D.Double(xi, y, w * modulo, alto));
            }
            xi += w * modulo;
            barra = !barra;
        }
    }

    /**
     * Inventa un codigo de 13 numeros que empieza por 20 (los codigos 20 a 29 son de uso
     * interno de la tienda, no chocan con los de fabrica) y termina con el digito de control EAN.
     * Prueba hasta que encuentre uno que no este usado.
     */
    public static String generar(Predicate<String> usado) {
        SecureRandom r = new SecureRandom();
        for (int intento = 0; intento < 1000; intento++) {
            StringBuilder sb = new StringBuilder("20");
            for (int i = 0; i < 10; i++) {
                sb.append(r.nextInt(10));
            }
            sb.append(digitoEan(sb.toString()));
            String c = sb.toString();
            if (!usado.test(c)) {
                return c;
            }
        }
        throw new IllegalStateException("No se encontro un codigo libre");
    }

    /** Digito de control EAN-13 para los primeros 12 numeros. */
    public static int digitoEan(String doce) {
        int suma = 0;
        for (int i = 0; i < 12; i++) {
            int d = doce.charAt(i) - '0';
            suma += i % 2 == 0 ? d : d * 3;
        }
        return (10 - suma % 10) % 10;
    }

    // ------------------------------------------------------------------

    /** Vista del codigo en pantalla: barras negras sobre blanco y el numero debajo. */
    public static class Vista extends javax.swing.JPanel {
        private String codigo;

        public Vista() {
            setOpaque(false);
            getAccessibleContext().setAccessibleName("Código de barras");
        }

        public void setCodigo(String c) {
            codigo = c == null ? null : c.trim();
            getAccessibleContext().setAccessibleDescription(codigo);
            repaint();
        }

        public boolean tieneCodigo() {
            return sePuede(codigo);
        }

        @Override
        public Dimension getPreferredSize() {
            return isPreferredSizeSet() ? super.getPreferredSize() : new Dimension(260, 104);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Tema.suavizar(g);
            int w = getWidth(), h = getHeight();
            if (!sePuede(codigo)) {
                g.setColor(Tema.BORDE_CONTROL);
                float[] raya = {5f, 4f};
                g.setStroke(new java.awt.BasicStroke(1f, java.awt.BasicStroke.CAP_BUTT,
                        java.awt.BasicStroke.JOIN_MITER, 10f, raya, 0f));
                g.drawRoundRect(0, 0, w - 1, h - 1, Tema.ARCO, Tema.ARCO);
                g.setFont(Tema.texto(12.5f));
                g.setColor(Tema.GRIS_TEXTO);
                String t = codigo == null || codigo.isBlank() ? "Sin código: oprima «Generar»"
                        : "Ese código tiene letras con tilde o ñ";
                FontMetrics fm = g.getFontMetrics();
                g.drawString(t, (w - fm.stringWidth(t)) / 2, h / 2 + fm.getAscent() / 2 - 2);
                g.dispose();
                return;
            }
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, w, h);
            Font f = new Font(Font.MONOSPACED, Font.PLAIN, h < 95 ? 14 : 17);
            g.setFont(f);
            FontMetrics fm = g.getFontMetrics();
            int altoTexto = fm.getHeight();
            g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_OFF);
            dibujarBarras(g, codigo, 0, 4, w, h - altoTexto - 6);
            Tema.suavizar(g);
            g.setColor(Color.BLACK);
            g.drawString(codigo, (w - fm.stringWidth(codigo)) / 2, h - fm.getDescent() - 1);
            g.dispose();
        }
    }

    /** Etiqueta para imprimir: nombre, barras, numero y precio. */
    public static class Etiqueta implements Printable {
        private final String codigo;
        private final String nombre;
        private final String precio;

        public Etiqueta(String codigo, String nombre, String precio) {
            this.codigo = codigo;
            this.nombre = nombre;
            this.precio = precio;
        }

        @Override
        public int print(Graphics g0, PageFormat pf, int pagina) {
            if (pagina > 0) {
                return NO_SUCH_PAGE;
            }
            Graphics2D g = (Graphics2D) g0;
            g.translate(pf.getImageableX(), pf.getImageableY());
            int ancho = (int) Math.min(pf.getImageableWidth(), 220); // unos 7,7 cm
            Tema.suavizar(g);
            g.setColor(Color.BLACK);
            int y = 0;
            if (nombre != null && !nombre.isBlank()) {
                g.setFont(Tema.textoFuerte(10));
                y += 12;
                g.drawString(cortar(g.getFontMetrics(), nombre, ancho), 0, y);
            }
            y += 4;
            dibujarBarras(g, codigo, 0, y, ancho, 50);
            y += 50 + 12;
            g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 10));
            FontMetrics fm = g.getFontMetrics();
            g.drawString(codigo, (ancho - fm.stringWidth(codigo)) / 2, y);
            if (precio != null && !precio.isBlank()) {
                g.setFont(Tema.subtitulo(12));
                fm = g.getFontMetrics();
                y += 16;
                g.drawString(precio, (ancho - fm.stringWidth(precio)) / 2, y);
            }
            return PAGE_EXISTS;
        }

        private static String cortar(FontMetrics fm, String s, int ancho) {
            if (fm.stringWidth(s) <= ancho) {
                return s;
            }
            while (s.length() > 1 && fm.stringWidth(s + "…") > ancho) {
                s = s.substring(0, s.length() - 1);
            }
            return s + "…";
        }
    }
}
