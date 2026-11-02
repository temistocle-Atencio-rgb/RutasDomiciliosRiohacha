package rutas.vista;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.font.FontRenderContext;
import java.awt.font.LineBreakMeasurer;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.text.AttributedCharacterIterator;
import java.text.AttributedString;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JComponent;

/**
 * Texto que puede traer emojis (por ejemplo, lo que escribe el domiciliario en el celular).
 * Las letras van en la fuente de DomiRuta (Inter) y lo que esa fuente no tiene, como 👍 o 🛵,
 * en la fuente de emojis del sistema (Segoe UI Emoji en Windows). Así no salen cuadritos.
 * Baja de línea según el ancho máximo y se encoge si el texto es corto.
 */
public class TextoEmoji extends javax.swing.JPanel { // JPanel: tiene nombre para el lector de pantalla

    private static final FontRenderContext FRC = new FontRenderContext(null, true, true);
    private static final String[] FAMILIAS = {"Segoe UI Emoji", "Segoe UI Symbol", "Noto Emoji", "Symbola",
        "Noto Color Emoji", "Apple Color Emoji"};
    private static String familiaEmoji; // null = todavía no se buscó; "" = no hay ninguna

    private String texto;
    private final int anchoMax;

    public TextoEmoji(String texto, Font fuente, Color color, int anchoMax) {
        this.texto = texto == null ? "" : texto;
        this.anchoMax = anchoMax;
        setFont(fuente);
        setForeground(color);
        setOpaque(false);
        setAlignmentX(LEFT_ALIGNMENT);
        getAccessibleContext().setAccessibleName(this.texto);
    }

    public String getTexto() {
        return texto;
    }

    public void setTexto(String t) {
        texto = t == null ? "" : t;
        getAccessibleContext().setAccessibleName(texto);
        revalidate();
        repaint();
    }

    /** La fuente de emojis del sistema, del tamaño pedido (la normal si no hay ninguna). */
    static Font fuenteEmoji(Font normal) {
        if (familiaEmoji == null) {
            familiaEmoji = "";
            try {
                java.util.Set<String> hay = new java.util.HashSet<>(java.util.Arrays.asList(
                        java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
                for (String f : FAMILIAS) {
                    if (hay.contains(f) && new Font(f, Font.PLAIN, 12).canDisplay(0x1F44D)) { // 👍
                        familiaEmoji = f;
                        break;
                    }
                }
            } catch (RuntimeException ex) {
                // sin entorno gráfico: se queda la normal
            }
        }
        if (familiaEmoji.isEmpty()) {
            return new Font(Font.DIALOG, Font.PLAIN, normal.getSize()).deriveFont(normal.getSize2D());
        }
        return new Font(familiaEmoji, Font.PLAIN, normal.getSize()).deriveFont(normal.getSize2D());
    }

    /** Partes que van con la fuente de emojis aunque la normal diga que las tiene (unen o cambian el emoji). */
    private static boolean pegado(int cp) {
        return cp == 0x200D || cp == 0xFE0F || cp == 0x20E3 || (cp >= 0x1F3FB && cp <= 0x1F3FF);
    }

    /** True si hay algo que la fuente normal no puede dibujar. */
    static boolean necesitaEmoji(String t, Font f) {
        for (int i = 0; i < t.length(); ) {
            int cp = t.codePointAt(i);
            if (!f.canDisplay(cp)) {
                return true;
            }
            i += Character.charCount(cp);
        }
        return false;
    }

    /** El texto con la fuente puesta por partes: normal para letras, de emojis para lo demás. */
    static AttributedString conFuentes(String t, Font f) {
        AttributedString a = new AttributedString(t);
        if (t.isEmpty()) {
            return a;
        }
        a.addAttribute(TextAttribute.FONT, f);
        Font emoji = null;
        boolean anterior = false;
        for (int i = 0; i < t.length(); ) {
            int cp = t.codePointAt(i);
            int n = Character.charCount(cp);
            boolean esEmoji = !f.canDisplay(cp) || (anterior && pegado(cp));
            if (esEmoji) {
                if (emoji == null) {
                    emoji = fuenteEmoji(f);
                }
                a.addAttribute(TextAttribute.FONT, emoji, i, i + n);
            }
            anterior = esEmoji;
            i += n;
        }
        return a;
    }

    /** Ancho de una línea (para recortar con «…»). */
    static float ancho(String t, Font f) {
        if (t == null || t.isEmpty()) {
            return 0;
        }
        return new TextLayout(conFuentes(t, f).getIterator(), FRC).getAdvance();
    }

    /** Dibuja una sola línea con emojis (y = línea base). */
    static void dibujar(Graphics2D g, String t, Font f, float x, float y) {
        if (t == null || t.isEmpty()) {
            return;
        }
        new TextLayout(conFuentes(t, f).getIterator(), g.getFontRenderContext()).draw(g, x, y);
    }

    /** Recorta con «…» para que quepa en el ancho. */
    static String recortar(String s, Font f, float anchoMax) {
        if (s == null) {
            return "";
        }
        if (ancho(s, f) <= anchoMax) {
            return s;
        }
        String r = s;
        while (r.length() > 1 && ancho(r + "…", f) > anchoMax) {
            int corte = r.length() - 1;
            if (corte > 0 && Character.isLowSurrogate(r.charAt(corte)) && Character.isHighSurrogate(r.charAt(corte - 1))) {
                corte--; // no partir un emoji por la mitad
            }
            r = r.substring(0, corte);
        }
        return r + "…";
    }

    // ------------------------------------------------------------------

    private List<TextLayout> lineas(float ancho) {
        List<TextLayout> l = new ArrayList<>();
        for (String parrafo : texto.split("\n", -1)) {
            if (parrafo.isEmpty()) {
                l.add(null); // renglón vacío
                continue;
            }
            AttributedCharacterIterator it = conFuentes(parrafo, getFont()).getIterator();
            LineBreakMeasurer m = new LineBreakMeasurer(it, FRC);
            while (m.getPosition() < it.getEndIndex()) {
                l.add(m.nextLayout(Math.max(20, ancho)));
            }
        }
        return l;
    }

    private float altoRenglon() {
        return getFontMetrics(getFont()).getHeight();
    }

    @Override
    public Dimension getPreferredSize() {
        if (isPreferredSizeSet()) {
            return super.getPreferredSize();
        }
        float w = 0, h = 0;
        for (TextLayout t : lineas(anchoMax)) {
            if (t == null) {
                h += altoRenglon();
            } else {
                w = Math.max(w, t.getAdvance());
                h += Math.max(altoRenglon(), t.getAscent() + t.getDescent() + t.getLeading());
            }
        }
        return new Dimension((int) Math.ceil(Math.min(anchoMax, w)) + 2, (int) Math.ceil(Math.max(h, altoRenglon())));
    }

    @Override
    public Dimension getMaximumSize() {
        return getPreferredSize();
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        Tema.suavizar(g);
        g.setColor(getForeground());
        float y = 0;
        for (TextLayout t : lineas(Math.min(anchoMax, Math.max(20, getWidth())))) {
            if (t == null) {
                y += altoRenglon();
                continue;
            }
            float alto = Math.max(altoRenglon(), t.getAscent() + t.getDescent() + t.getLeading());
            float base = y + (alto - t.getAscent() - t.getDescent()) / 2 + t.getAscent();
            t.draw(g, 0, base);
            y += alto;
        }
        g.dispose();
    }
}
