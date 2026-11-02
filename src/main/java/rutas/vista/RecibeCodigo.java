package rutas.vista;

import java.awt.Component;

/**
 * Pantalla donde cae lo que lee un lector de codigos (Vender, Compras y Productos).
 * El lector le entrega el codigo aunque el cursor no este en su buscador.
 */
public interface RecibeCodigo {

    /** Un codigo leido: se trata igual que si se hubiera escaneado en el buscador. */
    void codigoEscaneado(String codigo);

    /** True si {@code c} es el buscador donde normalmente escribe el escaner. */
    boolean esBuscador(Component c);
}
