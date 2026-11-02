package rutas.logica;

/**
 * Reconoce lo que escribe un lector de codigos USB de los que funcionan como teclado:
 * una rafaga de teclas muy seguidas (pocos milisegundos entre una y otra) que termina en Enter.
 * Una persona escribiendo rapido deja mas de 60 ms entre teclas; el lector, menos de 30.
 */
public final class Rafagas {

    /** Mas tiempo que esto entre dos teclas ya no es lector: es una persona. */
    public static final long MAXIMO_ENTRE_TECLAS = 60;
    /** Menos letras que esto no se toma como codigo (evita confundir un Enter suelto). */
    public static final int MINIMO_LETRAS = 4;

    private final StringBuilder letras = new StringBuilder();
    private long ultima = -1;

    /** Una tecla imprimible escrita en el instante {@code ms}. */
    public void tecla(char c, long ms) {
        if (ultima < 0 || ms - ultima > MAXIMO_ENTRE_TECLAS) {
            letras.setLength(0); // lo de antes fue escrito a mano: la rafaga empieza aqui
        }
        letras.append(c);
        ultima = ms;
    }

    /**
     * Enter en el instante {@code ms}. Devuelve el codigo si lo que vino antes fue una rafaga
     * de lector, o null si fue escrito a mano. Despues de un Enter se empieza de cero.
     */
    public String enter(long ms) {
        String codigo = ultima >= 0 && ms - ultima <= MAXIMO_ENTRE_TECLAS && letras.length() >= MINIMO_LETRAS
                ? letras.toString() : null;
        reiniciar();
        return codigo;
    }

    public void reiniciar() {
        letras.setLength(0);
        ultima = -1;
    }
}
