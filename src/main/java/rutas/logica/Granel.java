package rutas.logica;

/** Cuentas de los productos a granel (por libra o por kilo). */
public final class Granel {

    private Granel() {
    }

    /**
     * Cuanto producto dar por cierta plata: $2.000 de azucar a $4.400 el kilo son 455 g.
     * Devuelve milesimas de la unidad, redondeando al mas cercano.
     */
    public static long cantidadPorValor(long pesos, long precioPorUnidad) {
        if (precioPorUnidad <= 0) {
            throw new IllegalArgumentException("El producto no tiene precio");
        }
        if (pesos <= 0) {
            throw new IllegalArgumentException("Escriba cuánta plata");
        }
        return (pesos * 1000 + precioPorUnidad / 2) / precioPorUnidad;
    }
}
