package rutas.logica;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.function.LongSupplier;
import rutas.modelo.Usuario;

/**
 * Revisa usuario y contraseña. Tras 5 intentos fallidos seguidos hay que
 * esperar 30 segundos (asi nadie prueba contraseñas a la loca).
 */
public class Ingreso {

    public static final String MENSAJE_MALO = "Usuario o contraseña incorrectos";
    private static final int MAX_FALLOS = 5;
    private static final long ESPERA_MS = 30_000;

    private final BaseTienda bd;
    private final LongSupplier reloj;
    private int fallos;
    private long bloqueadoHasta;

    public Ingreso(BaseTienda bd, LongSupplier reloj) {
        this.bd = bd;
        this.reloj = reloj;
    }

    public Ingreso(BaseTienda bd) {
        this(bd, System::currentTimeMillis);
    }

    public Sesion entrar(String usuario, char[] clave) throws IngresoException {
        try {
            long ahora = reloj.getAsLong();
            if (ahora < bloqueadoHasta) {
                long seg = (bloqueadoHasta - ahora + 999) / 1000;
                throw new IngresoException("Demasiados intentos. Espere " + seg + " segundos");
            }
            Usuario u = bd.validar(usuario, clave);
            if (u == null) {
                fallos++;
                if (fallos >= MAX_FALLOS) {
                    fallos = 0;
                    bloqueadoHasta = ahora + ESPERA_MS;
                }
                throw new IngresoException(MENSAJE_MALO);
            }
            fallos = 0;
            return new Sesion(u);
        } catch (SQLException e) {
            throw new IngresoException("No se pudo leer la base de la tienda: " + e.getMessage());
        } finally {
            Arrays.fill(clave, '\0');
        }
    }

    /** Error de ingreso con un mensaje listo para mostrar. */
    public static class IngresoException extends Exception {

        public IngresoException(String mensaje) {
            super(mensaje);
        }
    }
}
