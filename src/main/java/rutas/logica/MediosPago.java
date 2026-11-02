package rutas.logica;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import rutas.modelo.MetodoPago;

/** Los medios de pago que acepta la tienda (se guardan en la tabla config). Efectivo siempre va. */
public final class MediosPago {

    static final String CLAVE = "pagos.activos";
    /** Los de antes (asi una tienda que ya existia sigue igual). */
    static final String POR_DEFECTO = "EFECTIVO,NEQUI,DAVIPLATA,TRANSFERENCIA";

    private MediosPago() {
    }

    /** Los activos, en el orden de la lista (Efectivo primero). */
    public static List<MetodoPago> activos(BaseTienda bd) throws SQLException {
        return leer(bd.leerConfig(CLAVE, POR_DEFECTO));
    }

    static List<MetodoPago> leer(String texto) {
        Set<MetodoPago> s = EnumSet.of(MetodoPago.EFECTIVO);
        for (String p : texto.split(",")) {
            try {
                if (!p.isBlank()) {
                    s.add(MetodoPago.valueOf(p.trim()));
                }
            } catch (IllegalArgumentException e) {
                // un medio que ya no existe se ignora
            }
        }
        return new ArrayList<>(s);
    }

    public static void guardar(BaseTienda bd, Collection<MetodoPago> activos) throws SQLException {
        Set<MetodoPago> s = EnumSet.of(MetodoPago.EFECTIVO);
        s.addAll(activos);
        StringBuilder t = new StringBuilder();
        for (MetodoPago m : s) {
            if (t.length() > 0) {
                t.append(',');
            }
            t.append(m.name());
        }
        bd.guardarConfigs(new String[][]{{CLAVE, t.toString()}});
    }
}
