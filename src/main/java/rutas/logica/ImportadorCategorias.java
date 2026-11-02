package rutas.logica;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.SQLException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import rutas.modelo.Categoria;

/**
 * Importa categorias desde Excel (.xlsx) o CSV. Columnas (en cualquier orden):
 * codigo (opcional, numero que no se repite), nombre (obligatoria), url_imagen (opcional),
 * estado (opcional: ACTIVO o INACTIVO; vacio = activo) y orden (opcional).
 * Si el codigo ya existe se actualiza esa categoria (con codigo, dos categorias pueden llamarse
 * igual); si no hay codigo y el nombre ya existe, se actualiza la del mismo nombre. Todo o nada: si una fila esta mal no se guarda ninguna.
 */
public final class ImportadorCategorias {

    /** Lo que paso al importar. */
    public static class Resultado {
        public int creadas;
        public int actualizadas;
        public final List<String> errores = new ArrayList<>();
        /** Fotos para bajar despues: {id de la categoria, direccion}. */
        public final List<String[]> fotos = new ArrayList<>();
    }

    private ImportadorCategorias() {
    }

    public static List<String[]> leerArchivo(File f) throws IOException {
        String n = f.getName().toLowerCase();
        if (n.endsWith(".xlsx")) {
            return Xlsx.leer(f);
        }
        if (n.endsWith(".xls")) {
            throw new IOException("Ese es el formato viejo de Excel (.xls). Ábralo en Excel y guárdelo como .xlsx");
        }
        List<String[]> filas = new ArrayList<>();
        String texto = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        if (texto.startsWith("\uFEFF")) {
            texto = texto.substring(1);
        }
        for (String linea : texto.split("\\r?\\n")) {
            filas.add(linea.split(linea.contains(";") ? ";" : ",", -1));
        }
        return filas;
    }

    /** Revisa todas las filas y, si no hay errores, las guarda juntas. */
    public static Resultado importar(List<String[]> filas, BaseTienda bd) throws Exception {
        Resultado r = new Resultado();
        if (filas.isEmpty()) {
            r.errores.add("El archivo está vacío");
            return r;
        }
        String[] cab = filas.get(0);
        int cCodigo = -1, cNombre = -1, cUrl = -1, cEstado = -1, cOrden = -1;
        for (int i = 0; i < cab.length; i++) {
            String h = clave(cab[i]);
            if (h.equals("codigo") || h.equals("id") || h.equals("idcategoria")) {
                cCodigo = i;
            } else if (h.equals("nombre")) {
                cNombre = i;
            } else if (h.startsWith("url") || h.equals("imagen") || h.equals("foto")) {
                cUrl = i;
            } else if (h.startsWith("estado")) {
                cEstado = i;
            } else if (h.equals("orden")) {
                cOrden = i;
            }
        }
        if (cNombre < 0) {
            r.errores.add("Falta la columna «nombre» en la primera fila (encabezado)");
            return r;
        }
        List<Categoria> existentes = bd.listarCategorias(true);
        Map<Long, Categoria> porId = new HashMap<>();
        Map<String, Categoria> porNombre = new HashMap<>();
        for (Categoria c : existentes) {
            porId.put(c.getId(), c);
            porNombre.put(clave(c.getNombre()), c);
        }
        int siguienteOrden = existentes.size() + 1;
        Set<Long> codigosVistos = new HashSet<>();
        Set<String> nombresVistos = new HashSet<>();
        // primero se revisa todo; asi un error en la fila 40 no deja 39 guardadas a medias
        List<Object[]> plan = new ArrayList<>(); // {Long codigo o null, nombre, url, activo, orden, Categoria existente}
        for (int i = 1; i < filas.size(); i++) {
            String[] f = filas.get(i);
            String nombre = celda(f, cNombre);
            String codigoTxt = celda(f, cCodigo);
            if (nombre.isEmpty() && codigoTxt.isEmpty() && celda(f, cUrl).isEmpty()) {
                continue; // fila vacia
            }
            int filaExcel = i + 1;
            if (nombre.isEmpty()) {
                r.errores.add("Fila " + filaExcel + ": falta el nombre");
                continue;
            }
            Long codigo = null;
            if (!codigoTxt.isEmpty()) {
                try {
                    codigo = Long.parseLong(codigoTxt.replace(".", "").trim());
                    if (codigo <= 0) {
                        throw new NumberFormatException();
                    }
                } catch (NumberFormatException e) {
                    r.errores.add("Fila " + filaExcel + ": el código «" + codigoTxt + "» debe ser un número mayor que 0");
                    continue;
                }
                if (!codigosVistos.add(codigo)) {
                    r.errores.add("Fila " + filaExcel + ": el código " + codigo + " está repetido en el archivo");
                    continue;
                }
            }
            // sin codigo, el nombre es lo que identifica la categoria: no puede repetirse
            if (codigo == null && !nombresVistos.add(clave(nombre))) {
                r.errores.add("Fila " + filaExcel + ": «" + nombre + "» está repetida en el archivo");
                continue;
            }
            String estado = clave(celda(f, cEstado));
            boolean activo;
            if (estado.isEmpty() || estado.equals("activo") || estado.equals("1") || estado.equals("si")) {
                activo = true;
            } else if (estado.equals("inactivo") || estado.equals("desactivado") || estado.equals("0")
                    || estado.equals("no")) {
                activo = false;
            } else {
                r.errores.add("Fila " + filaExcel + ": el estado «" + celda(f, cEstado) + "» debe ser ACTIVO o INACTIVO");
                continue;
            }
            String url = celda(f, cUrl);
            if (!url.isEmpty() && !url.startsWith("http://") && !url.startsWith("https://")) {
                r.errores.add("Fila " + filaExcel + ": la dirección de imagen debe empezar por http:// o https://");
                continue;
            }
            Integer orden = null;
            if (!celda(f, cOrden).isEmpty()) {
                try {
                    orden = Integer.parseInt(celda(f, cOrden).trim());
                } catch (NumberFormatException e) {
                    r.errores.add("Fila " + filaExcel + ": el orden «" + celda(f, cOrden) + "» debe ser un número");
                    continue;
                }
            }
            Categoria existe = codigo != null ? porId.get(codigo) : porNombre.get(clave(nombre));
            plan.add(new Object[]{codigo, nombre.trim(), url, activo, orden, existe});
        }
        if (!r.errores.isEmpty()) {
            return r;
        }
        final int[] orden0 = {siguienteOrden};
        bd.enTransaccion(() -> {
            for (Object[] p : plan) {
                Long codigo = (Long) p[0];
                String nombre = (String) p[1];
                String url = (String) p[2];
                boolean activo = (Boolean) p[3];
                Integer orden = (Integer) p[4];
                Categoria existe = (Categoria) p[5];
                Categoria guardada;
                if (existe != null) {
                    guardada = new Categoria(existe.getId(), nombre, existe.getFoto(),
                            orden != null ? orden : existe.getOrden(), activo);
                    bd.editarCategoria(guardada);
                    r.actualizadas++;
                } else {
                    int o = orden != null ? orden : orden0[0]++;
                    guardada = codigo != null ? bd.crearCategoriaConCodigo(codigo, nombre, null, o, activo)
                            : crearSinCodigo(bd, nombre, o, activo);
                    r.creadas++;
                }
                if (!url.isEmpty()) {
                    r.fotos.add(new String[]{String.valueOf(guardada.getId()), url});
                }
            }
            return null;
        });
        return r;
    }

    private static Categoria crearSinCodigo(BaseTienda bd, String nombre, int orden, boolean activo) throws SQLException {
        Categoria c = bd.crearCategoria(nombre, null, orden);
        if (!activo) {
            c = new Categoria(c.getId(), c.getNombre(), null, orden, false);
            bd.editarCategoria(c);
        }
        return c;
    }

    private static String celda(String[] f, int i) {
        return i < 0 || i >= f.length || f[i] == null ? "" : f[i].trim();
    }

    /** Minusculas, sin tildes ni espacios ni signos: "Código " -> "codigo", "URL de imagen" -> "urldeimagen". */
    static String clave(String s) {
        String n = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase().replaceAll("[^a-z0-9]", "");
    }
}
