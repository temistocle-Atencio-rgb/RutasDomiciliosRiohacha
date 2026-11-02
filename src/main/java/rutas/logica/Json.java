package rutas.logica;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lector y escritor de JSON pequeño (sin librerías): lo justo para hablar con Supabase.
 * Un objeto se lee como Map, una lista como List, los números como Double y null como null.
 */
public final class Json {

    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    /** Lee un texto JSON. Lanza IllegalArgumentException si está mal escrito. */
    public static Object leer(String texto) {
        Json j = new Json(texto == null ? "" : texto);
        try {
            j.blancos();
            Object v = j.valor();
            j.blancos();
            if (j.i != j.s.length()) {
                throw new IllegalArgumentException("JSON con texto de más en la posición " + j.i);
            }
            return v;
        } catch (IndexOutOfBoundsException | NumberFormatException e) { // texto cortado o dañado
            throw new IllegalArgumentException("JSON incompleto o dañado");
        }
    }

    private Object valor() {
        if (i >= s.length()) {
            throw new IllegalArgumentException("JSON incompleto");
        }
        char c = s.charAt(i);
        switch (c) {
            case '{': return objeto();
            case '[': return lista();
            case '"': return texto();
            case 't': palabra("true"); return Boolean.TRUE;
            case 'f': palabra("false"); return Boolean.FALSE;
            case 'n': palabra("null"); return null;
            default: return numero();
        }
    }

    private Map<String, Object> objeto() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++; // {
        blancos();
        if (s.charAt(i) == '}') {
            i++;
            return m;
        }
        while (true) {
            blancos();
            String k = texto();
            blancos();
            esperar(':');
            blancos();
            m.put(k, valor());
            blancos();
            if (s.charAt(i) == ',') {
                i++;
                continue;
            }
            esperar('}');
            return m;
        }
    }

    private List<Object> lista() {
        List<Object> l = new ArrayList<>();
        i++; // [
        blancos();
        if (s.charAt(i) == ']') {
            i++;
            return l;
        }
        while (true) {
            blancos();
            l.add(valor());
            blancos();
            if (s.charAt(i) == ',') {
                i++;
                continue;
            }
            esperar(']');
            return l;
        }
    }

    private String texto() {
        esperar('"');
        StringBuilder b = new StringBuilder();
        while (true) {
            if (i >= s.length()) {
                throw new IllegalArgumentException("Texto sin cerrar en el JSON");
            }
            char c = s.charAt(i++);
            if (c == '"') {
                return b.toString();
            }
            if (c != '\\') {
                b.append(c);
                continue;
            }
            char e = s.charAt(i++);
            switch (e) {
                case 'n': b.append('\n'); break;
                case 't': b.append('\t'); break;
                case 'r': b.append('\r'); break;
                case 'b': b.append('\b'); break;
                case 'f': b.append('\f'); break;
                case 'u': b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                default: b.append(e); // " \ /
            }
        }
    }

    private Double numero() {
        int ini = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
            i++;
        }
        if (ini == i) {
            throw new IllegalArgumentException("Carácter inesperado en el JSON: " + s.charAt(i));
        }
        return Double.valueOf(s.substring(ini, i));
    }

    private void palabra(String p) {
        if (!s.startsWith(p, i)) {
            throw new IllegalArgumentException("Se esperaba " + p + " en el JSON");
        }
        i += p.length();
    }

    private void esperar(char c) {
        if (i >= s.length() || s.charAt(i) != c) {
            throw new IllegalArgumentException("Se esperaba '" + c + "' en la posición " + i + " del JSON");
        }
        i++;
    }

    private void blancos() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
    }

    // ------------------------------------------------------------------
    //  Escribir
    // ------------------------------------------------------------------

    /** Escribe un valor (Map, List, String, Number, Boolean o null) como texto JSON. */
    public static String escribir(Object v) {
        StringBuilder b = new StringBuilder();
        escribir(b, v);
        return b.toString();
    }

    private static void escribir(StringBuilder b, Object v) {
        if (v == null) {
            b.append("null");
        } else if (v instanceof String) {
            comillas(b, (String) v);
        } else if (v instanceof Boolean) {
            b.append(v);
        } else if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) { // JSON no tiene NaN
                b.append("null");
                return;
            }
            b.append(d == Math.rint(d) && Math.abs(d) < 1e15 ? String.valueOf((long) d) : String.valueOf(d));
        } else if (v instanceof Number) {
            b.append(((Number) v).longValue());
        } else if (v instanceof Map) {
            b.append('{');
            boolean primero = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (!primero) {
                    b.append(',');
                }
                primero = false;
                comillas(b, String.valueOf(e.getKey()));
                b.append(':');
                escribir(b, e.getValue());
            }
            b.append('}');
        } else if (v instanceof List) {
            b.append('[');
            boolean primero = true;
            for (Object o : (List<?>) v) {
                if (!primero) {
                    b.append(',');
                }
                primero = false;
                escribir(b, o);
            }
            b.append(']');
        } else {
            comillas(b, v.toString());
        }
    }

    private static void comillas(StringBuilder b, String t) {
        b.append('"');
        for (int k = 0; k < t.length(); k++) {
            char c = t.charAt(k);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
            }
        }
        b.append('"');
    }

    // ------------------------------------------------------------------
    //  Ayudas para leer lo que devuelve Supabase
    // ------------------------------------------------------------------

    public static String texto(Map<?, ?> m, String k) {
        Object v = m.get(k);
        return v == null ? "" : v.toString();
    }

    public static long entero(Map<?, ?> m, String k) {
        Object v = m.get(k);
        return v instanceof Number ? ((Number) v).longValue() : 0;
    }

    /** NaN si no hay número (por ejemplo la ubicación todavía vacía). */
    public static double decimal(Map<?, ?> m, String k) {
        Object v = m.get(k);
        return v instanceof Number ? ((Number) v).doubleValue() : Double.NaN;
    }
}
