package rutas.osm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lector de JSON pequeno hecho a mano (para no depender de librerias externas).
 * Convierte el texto en Map (objetos), List (arreglos), String, Double, Boolean o null.
 * Es un analizador recursivo descendente: cada metodo lee un tipo de valor.
 */
public final class Json {

    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    public static Object leer(String texto) {
        Json j = new Json(texto);
        j.espacios();
        Object v = j.valor();
        j.espacios();
        if (j.i != j.s.length()) {
            throw j.error("Texto sobrante");
        }
        return v;
    }

    private Object valor() {
        espacios();
        if (i >= s.length()) {
            throw error("Fin inesperado");
        }
        char c = s.charAt(i);
        switch (c) {
            case '{': return objeto();
            case '[': return arreglo();
            case '"': return cadena();
            case 't': esperar("true"); return Boolean.TRUE;
            case 'f': esperar("false"); return Boolean.FALSE;
            case 'n': esperar("null"); return null;
            default: return numero();
        }
    }

    private Map<String, Object> objeto() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++; // {
        espacios();
        if (s.charAt(i) == '}') {
            i++;
            return m;
        }
        while (true) {
            espacios();
            String clave = cadena();
            espacios();
            if (s.charAt(i++) != ':') {
                throw error("Se esperaba ':'");
            }
            m.put(clave, valor());
            espacios();
            char c = s.charAt(i++);
            if (c == '}') {
                return m;
            }
            if (c != ',') {
                throw error("Se esperaba ',' o '}'");
            }
        }
    }

    private List<Object> arreglo() {
        List<Object> l = new ArrayList<>();
        i++; // [
        espacios();
        if (s.charAt(i) == ']') {
            i++;
            return l;
        }
        while (true) {
            l.add(valor());
            espacios();
            char c = s.charAt(i++);
            if (c == ']') {
                return l;
            }
            if (c != ',') {
                throw error("Se esperaba ',' o ']'");
            }
        }
    }

    private String cadena() {
        if (s.charAt(i) != '"') {
            throw error("Se esperaba texto");
        }
        i++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = s.charAt(i++);
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            char e = s.charAt(i++);
            switch (e) {
                case 'n': sb.append('\n'); break;
                case 't': sb.append('\t'); break;
                case 'r': sb.append('\r'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'u':
                    sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    i += 4;
                    break;
                default: sb.append(e);
            }
        }
    }

    private Double numero() {
        int ini = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
            i++;
        }
        if (ini == i) {
            throw error("Valor no valido");
        }
        return Double.parseDouble(s.substring(ini, i));
    }

    private void esperar(String palabra) {
        if (!s.startsWith(palabra, i)) {
            throw error("Se esperaba " + palabra);
        }
        i += palabra.length();
    }

    private void espacios() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
    }

    private IllegalArgumentException error(String msg) {
        return new IllegalArgumentException("JSON no valido en la posicion " + i + ": " + msg);
    }
}
