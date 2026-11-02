package rutas.logica;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Lee y escribe archivos de Excel (.xlsx) sin librerias: un .xlsx es un .zip con XML adentro.
 * Solo lo necesario para importar y exportar listas: la primera hoja, texto y numeros.
 */
public final class Xlsx {

    private Xlsx() {
    }

    // ------------------------------------------------------------------
    //  Leer
    // ------------------------------------------------------------------

    /** Filas de la primera hoja (cada fila con sus celdas como texto; las vacias quedan en ""). */
    public static List<String[]> leer(File archivo) throws IOException {
        try (ZipFile zip = new ZipFile(archivo)) {
            List<String> compartidos = new ArrayList<>();
            ZipEntry ss = zip.getEntry("xl/sharedStrings.xml");
            if (ss != null) {
                Document d = xml(zip, ss);
                NodeList si = d.getElementsByTagNameNS("*", "si");
                for (int i = 0; i < si.getLength(); i++) {
                    compartidos.add(textoDe((Element) si.item(i)));
                }
            }
            ZipEntry hoja = zip.getEntry(primeraHoja(zip));
            if (hoja == null) {
                throw new IOException("El archivo no tiene hojas");
            }
            Document d = xml(zip, hoja);
            List<String[]> filas = new ArrayList<>();
            NodeList rows = d.getElementsByTagNameNS("*", "row");
            for (int i = 0; i < rows.getLength(); i++) {
                Element row = (Element) rows.item(i);
                int numFila = row.hasAttribute("r") ? Integer.parseInt(row.getAttribute("r")) : filas.size() + 1;
                while (filas.size() < numFila - 1) {
                    filas.add(new String[0]); // filas vacias que Excel no escribe
                }
                List<String> celdas = new ArrayList<>();
                NodeList cs = row.getElementsByTagNameNS("*", "c");
                for (int j = 0; j < cs.getLength(); j++) {
                    Element c = (Element) cs.item(j);
                    int col = c.hasAttribute("r") ? columna(c.getAttribute("r")) : celdas.size();
                    while (celdas.size() < col) {
                        celdas.add("");
                    }
                    celdas.add(valor(c, compartidos));
                }
                filas.add(celdas.toArray(new String[0]));
            }
            return filas;
        } catch (java.util.zip.ZipException e) {
            throw new IOException("No es un archivo de Excel (.xlsx) válido");
        }
    }

    /** Ruta de la primera hoja segun el libro (normalmente xl/worksheets/sheet1.xml). */
    private static String primeraHoja(ZipFile zip) throws IOException {
        ZipEntry wb = zip.getEntry("xl/workbook.xml");
        ZipEntry rels = zip.getEntry("xl/_rels/workbook.xml.rels");
        if (wb != null && rels != null) {
            Element sheet = (Element) xml(zip, wb).getElementsByTagNameNS("*", "sheet").item(0);
            if (sheet != null) {
                String rid = sheet.getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id");
                NodeList rs = xml(zip, rels).getElementsByTagNameNS("*", "Relationship");
                for (int i = 0; i < rs.getLength(); i++) {
                    Element r = (Element) rs.item(i);
                    if (r.getAttribute("Id").equals(rid)) {
                        String t = r.getAttribute("Target");
                        return t.startsWith("/") ? t.substring(1) : "xl/" + t;
                    }
                }
            }
        }
        return "xl/worksheets/sheet1.xml";
    }

    private static String valor(Element c, List<String> compartidos) {
        String tipo = c.getAttribute("t");
        if ("inlineStr".equals(tipo)) {
            NodeList is = c.getElementsByTagNameNS("*", "is");
            return is.getLength() == 0 ? "" : textoDe((Element) is.item(0));
        }
        NodeList vs = c.getElementsByTagNameNS("*", "v");
        String v = vs.getLength() == 0 ? "" : vs.item(0).getTextContent();
        if ("s".equals(tipo)) {
            try {
                return compartidos.get(Integer.parseInt(v.trim()));
            } catch (RuntimeException e) {
                return "";
            }
        }
        if ("b".equals(tipo)) {
            return "1".equals(v) ? "VERDADERO" : "FALSO";
        }
        // numeros: 30.0 -> "30"
        if (v.matches("-?\\d+\\.0+")) {
            return v.substring(0, v.indexOf('.'));
        }
        return v;
    }

    /** Texto de un <si> o <is>: puede venir partido en varios <t> (letras con formato). */
    private static String textoDe(Element e) {
        StringBuilder sb = new StringBuilder();
        NodeList ts = e.getElementsByTagNameNS("*", "t");
        for (int i = 0; i < ts.getLength(); i++) {
            Node t = ts.item(i);
            // se ignora la guia fonetica (<rPh>)
            if (t.getParentNode() != null && "rPh".equals(t.getParentNode().getLocalName())) {
                continue;
            }
            sb.append(t.getTextContent());
        }
        return sb.toString();
    }

    /** "C7" -> 2 (columna empezando en 0). */
    static int columna(String ref) {
        int n = 0;
        for (char ch : ref.toCharArray()) {
            if (Character.isLetter(ch)) {
                n = n * 26 + (Character.toUpperCase(ch) - 'A' + 1);
            } else {
                break;
            }
        }
        return n - 1;
    }

    private static Document xml(ZipFile zip, ZipEntry e) throws IOException {
        try (InputStream in = zip.getInputStream(e)) {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            // un archivo de afuera no puede pedir otros archivos ni entidades
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            DocumentBuilder b = f.newDocumentBuilder();
            return b.parse(in);
        } catch (IOException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IOException("El archivo de Excel está dañado: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------------
    //  Escribir
    // ------------------------------------------------------------------

    /**
     * Escribe una hoja con encabezado en negrita. Los numeros (Long/Integer) van como numero;
     * lo demas como texto.
     */
    public static void escribir(File archivo, String nombreHoja, String[] encabezado, List<Object[]> filas)
            throws IOException {
        try (OutputStream fo = new FileOutputStream(archivo); ZipOutputStream zip = new ZipOutputStream(fo)) {
            poner(zip, "[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                    + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                    + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                    + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                    + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                    + "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>"
                    + "</Types>");
            poner(zip, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                    + "</Relationships>");
            poner(zip, "xl/workbook.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
                    + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                    + "<sheets><sheet name=\"" + esc(nombreHoja.length() > 31 ? nombreHoja.substring(0, 31) : nombreHoja)
                    + "\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
            poner(zip, "xl/_rels/workbook.xml.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
                    + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>"
                    + "</Relationships>");
            poner(zip, "xl/styles.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                    + "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font>"
                    + "<font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>"
                    + "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills>"
                    + "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>"
                    + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
                    + "<cellXfs count=\"2\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"
                    + "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/></cellXfs>"
                    + "</styleSheet>");
            StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">");
            sb.append("<cols>");
            for (int i = 0; i < encabezado.length; i++) {
                int ancho = encabezado[i].length() + 4;
                for (Object[] f : filas) {
                    if (i < f.length && f[i] != null) {
                        ancho = Math.max(ancho, Math.min(60, String.valueOf(f[i]).length() + 2));
                    }
                }
                sb.append("<col min=\"").append(i + 1).append("\" max=\"").append(i + 1).append("\" width=\"")
                        .append(ancho).append("\" customWidth=\"1\"/>");
            }
            sb.append("</cols><sheetData>");
            fila(sb, 1, encabezado, 1);
            int n = 2;
            for (Object[] f : filas) {
                fila(sb, n++, f, 0);
            }
            sb.append("</sheetData></worksheet>");
            poner(zip, "xl/worksheets/sheet1.xml", sb.toString());
        }
    }

    private static void fila(StringBuilder sb, int n, Object[] celdas, int estilo) {
        sb.append("<row r=\"").append(n).append("\">");
        for (int i = 0; i < celdas.length; i++) {
            Object v = celdas[i];
            if (v == null) {
                continue;
            }
            String ref = letra(i) + n;
            String s = estilo > 0 ? " s=\"" + estilo + "\"" : "";
            if (v instanceof Long || v instanceof Integer) {
                sb.append("<c r=\"").append(ref).append("\"").append(s).append("><v>").append(v).append("</v></c>");
            } else {
                sb.append("<c r=\"").append(ref).append("\" t=\"inlineStr\"").append(s).append("><is><t xml:space=\"preserve\">")
                        .append(esc(String.valueOf(v))).append("</t></is></c>");
            }
        }
        sb.append("</row>");
    }

    /** 0 -> "A", 26 -> "AA". */
    static String letra(int col) {
        StringBuilder sb = new StringBuilder();
        int n = col + 1;
        while (n > 0) {
            int r = (n - 1) % 26;
            sb.insert(0, (char) ('A' + r));
            n = (n - 1) / 26;
        }
        return sb.toString();
    }

    private static String esc(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                default:
                    if (c >= 0x20 || c == '\t' || c == '\n' || c == '\r') {
                        sb.append(c); // los caracteres de control no van en XML
                    }
            }
        }
        return sb.toString();
    }

    private static void poner(ZipOutputStream zip, String nombre, String contenido) throws IOException {
        zip.putNextEntry(new ZipEntry(nombre));
        zip.write(contenido.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
