package rutas.logica;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/**
 * Hace un PDF de una pagina con una imagen (el recibo dibujado), sin librerias.
 * La pagina mide lo mismo que la imagen a la escala dada (puntos de 1/72 de pulgada).
 */
public final class PdfImagen {

    private PdfImagen() {
    }

    /** Varias paginas del mismo tamaño (por ejemplo carta: 612 x 792 puntos), una imagen por pagina. */
    public static byte[] crearPaginas(List<BufferedImage> paginas, double anchoPuntos, double altoPuntos)
            throws IOException {
        int n = paginas.size();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<Integer> posiciones = new ArrayList<>();
        escribir(out, "%PDF-1.4\n%âãÏÓ\n");
        posiciones.add(out.size());
        escribir(out, "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");
        StringBuilder kids = new StringBuilder();
        for (int i = 0; i < n; i++) {
            kids.append(3 + 3 * i).append(" 0 R ");
        }
        posiciones.add(out.size());
        escribir(out, "2 0 obj\n<< /Type /Pages /Kids [" + kids + "] /Count " + n + " >>\nendobj\n");
        String contenido = String.format(java.util.Locale.US, "q %.2f 0 0 %.2f 0 0 cm /Im1 Do Q", anchoPuntos, altoPuntos);
        for (int i = 0; i < n; i++) {
            int pag = 3 + 3 * i;
            BufferedImage img = paginas.get(i);
            byte[] jpg = jpeg(img);
            posiciones.add(out.size());
            escribir(out, String.format(java.util.Locale.US, "%d 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 %.2f %.2f]"
                    + " /Resources << /XObject << /Im1 %d 0 R >> >> /Contents %d 0 R >>\nendobj\n", pag, anchoPuntos,
                    altoPuntos, pag + 1, pag + 2));
            posiciones.add(out.size());
            escribir(out, (pag + 1) + " 0 obj\n<< /Type /XObject /Subtype /Image /Width " + img.getWidth() + " /Height "
                    + img.getHeight() + " /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length "
                    + jpg.length + " >>\nstream\n");
            out.write(jpg);
            escribir(out, "\nendstream\nendobj\n");
            posiciones.add(out.size());
            escribir(out, (pag + 2) + " 0 obj\n<< /Length " + contenido.length() + " >>\nstream\n" + contenido
                    + "\nendstream\nendobj\n");
        }
        int xref = out.size();
        int total = posiciones.size() + 1;
        StringBuilder x = new StringBuilder("xref\n0 " + total + "\n0000000000 65535 f \n");
        for (int p : posiciones) {
            x.append(String.format("%010d 00000 n \n", p));
        }
        x.append("trailer\n<< /Size ").append(total).append(" /Root 1 0 R >>\nstartxref\n").append(xref).append("\n%%EOF\n");
        escribir(out, x.toString());
        return out.toByteArray();
    }

    /** imagen: el recibo; anchoPuntos: ancho de la pagina en puntos (ej. 58 mm = 164 pt). */
    public static byte[] crear(BufferedImage imagen, double anchoPuntos) throws IOException {
        byte[] jpg = jpeg(imagen);
        double w = anchoPuntos;
        double h = anchoPuntos * imagen.getHeight() / imagen.getWidth();
        String contenido = String.format(java.util.Locale.US, "q %.2f 0 0 %.2f 0 0 cm /Im1 Do Q", w, h);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<Integer> posiciones = new ArrayList<>();
        escribir(out, "%PDF-1.4\n%âãÏÓ\n");
        posiciones.add(out.size());
        escribir(out, "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");
        posiciones.add(out.size());
        escribir(out, "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n");
        posiciones.add(out.size());
        escribir(out, String.format(java.util.Locale.US, "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 %.2f %.2f]"
                + " /Resources << /XObject << /Im1 4 0 R >> >> /Contents 5 0 R >>\nendobj\n", w, h));
        posiciones.add(out.size());
        escribir(out, "4 0 obj\n<< /Type /XObject /Subtype /Image /Width " + imagen.getWidth() + " /Height "
                + imagen.getHeight() + " /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length "
                + jpg.length + " >>\nstream\n");
        out.write(jpg);
        escribir(out, "\nendstream\nendobj\n");
        posiciones.add(out.size());
        escribir(out, "5 0 obj\n<< /Length " + contenido.length() + " >>\nstream\n" + contenido + "\nendstream\nendobj\n");
        int xref = out.size();
        StringBuilder x = new StringBuilder("xref\n0 6\n0000000000 65535 f \n");
        for (int p : posiciones) {
            x.append(String.format("%010d 00000 n \n", p));
        }
        x.append("trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n").append(xref).append("\n%%EOF\n");
        escribir(out, x.toString());
        return out.toByteArray();
    }

    private static byte[] jpeg(BufferedImage img) throws IOException {
        BufferedImage rgb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = rgb.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
        g.drawImage(img, 0, 0, null);
        g.dispose();
        ImageWriter w = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam p = w.getDefaultWriteParam();
        p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        p.setCompressionQuality(0.92f);
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream os = new MemoryCacheImageOutputStream(b)) {
            w.setOutput(os);
            w.write(null, new IIOImage(rgb, null, null), p);
        } finally {
            w.dispose();
        }
        return b.toByteArray();
    }

    private static void escribir(ByteArrayOutputStream out, String s) {
        byte[] b = s.getBytes(StandardCharsets.ISO_8859_1);
        out.write(b, 0, b.length);
    }
}
