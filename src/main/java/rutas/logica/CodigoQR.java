package rutas.logica;

import java.nio.charset.StandardCharsets;

/**
 * Genera codigos QR (norma ISO/IEC 18004) sin librerias ni internet.
 *
 * Siempre usa el nivel de correccion H: se puede tapar hasta cerca del 30 % del codigo y
 * se sigue leyendo, por eso el logo de la tienda puede ir en el centro.
 *
 * Pasos: texto en bytes -> bits (modo byte) -> bloques con correccion Reed-Solomon
 * (aritmetica en el campo GF(256)) -> se acomodan en zigzag en la matriz -> se prueba cada
 * una de las 8 mascaras y se deja la que menos penaliza (la que mejor lee la camara).
 */
public final class CodigoQR {

    // nivel H, versiones 1..40: bytes de correccion por bloque y cantidad de bloques
    private static final int[] ECC_POR_BLOQUE = {0, 17, 28, 22, 16, 22, 28, 26, 26, 24, 28, 24, 28, 22, 24, 24, 30,
        28, 28, 26, 28, 30, 24, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30};
    private static final int[] BLOQUES = {0, 1, 1, 2, 4, 4, 4, 5, 6, 8, 8, 11, 11, 16, 16, 18, 16, 19, 21, 25, 25, 25,
        34, 30, 32, 35, 37, 40, 42, 45, 48, 51, 54, 57, 60, 63, 66, 70, 74, 77, 81};
    private static final int NIVEL_H = 2; // bits del nivel H en el formato

    /** Version (1..40): el codigo mide 17 + 4 x version cuadritos por lado. */
    public final int version;
    public final int lado;
    public final int mascara;
    private final boolean[][] negro;   // [fila][columna]
    private final boolean[][] fijo;    // patrones que no son datos

    private CodigoQR(int version) {
        this.version = version;
        this.lado = 17 + 4 * version;
        this.negro = new boolean[lado][lado];
        this.fijo = new boolean[lado][lado];
        this.mascara = -1;
    }

    private CodigoQR(CodigoQR base, int mascara) {
        this.version = base.version;
        this.lado = base.lado;
        this.mascara = mascara;
        this.negro = new boolean[lado][lado];
        this.fijo = base.fijo;
        for (int y = 0; y < lado; y++) {
            negro[y] = base.negro[y].clone();
        }
    }

    /** true si el cuadrito (columna x, fila y) es negro. Fuera del codigo es blanco. */
    public boolean negro(int x, int y) {
        return x >= 0 && y >= 0 && x < lado && y < lado && negro[y][x];
    }

    /** Arma el QR mas pequeño donde cabe el texto. */
    public static CodigoQR de(String texto) {
        byte[] datos = (texto == null ? "" : texto).getBytes(StandardCharsets.UTF_8);
        int v = 1;
        while (v <= 40 && capacidad(v) < bitsNecesarios(datos.length, v)) {
            v++;
        }
        if (v > 40) {
            throw new IllegalArgumentException("El texto es muy largo para un código QR");
        }
        CodigoQR q = new CodigoQR(v);
        q.patrones();
        q.ponerDatos(q.bloques(q.bitsDatos(datos)));
        // la mascara que menos penaliza
        CodigoQR mejor = null;
        int menor = Integer.MAX_VALUE;
        for (int m = 0; m < 8; m++) {
            CodigoQR c = new CodigoQR(q, m);
            c.enmascarar(m);
            c.formato(m);
            int p = c.penalidad();
            if (p < menor) {
                menor = p;
                mejor = c;
            }
        }
        return mejor;
    }

    // ------------------------------------------------------------------
    //  Capacidad
    // ------------------------------------------------------------------

    /** Cuadritos para datos y correccion (sin patrones), en bytes. */
    static int bytesTotales(int v) {
        int r = (16 * v + 128) * v + 64;
        if (v >= 2) {
            int n = v / 7 + 2;
            r -= (25 * n - 10) * n - 55;
            if (v >= 7) {
                r -= 36;
            }
        }
        return r / 8;
    }

    /** Bits de datos que caben (sin la correccion). */
    static int capacidad(int v) {
        return (bytesTotales(v) - ECC_POR_BLOQUE[v] * BLOQUES[v]) * 8;
    }

    private static int bitsNecesarios(int n, int v) {
        return 4 + (v < 10 ? 8 : 16) + 8 * n;
    }

    // ------------------------------------------------------------------
    //  Patrones fijos
    // ------------------------------------------------------------------

    private void poner(int x, int y, boolean n) {
        negro[y][x] = n;
        fijo[y][x] = true;
    }

    private void patrones() {
        for (int i = 0; i < lado; i++) {          // lineas de tiempo
            poner(6, i, i % 2 == 0);
            poner(i, 6, i % 2 == 0);
        }
        buscador(3, 3);                            // los 3 cuadros grandes de las esquinas
        buscador(lado - 4, 3);
        buscador(3, lado - 4);
        int[] al = alineacion();
        for (int i = 0; i < al.length; i++) {
            for (int j = 0; j < al.length; j++) {
                boolean esquina = (i == 0 && j == 0) || (i == 0 && j == al.length - 1) || (i == al.length - 1 && j == 0);
                if (!esquina) {
                    cuadroAlineacion(al[i], al[j]);
                }
            }
        }
        formato(0);                                // reserva el lugar (se escribe bien despues)
        versionBits();
    }

    private void buscador(int cx, int cy) {
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int d = Math.max(Math.abs(dx), Math.abs(dy));
                int x = cx + dx, y = cy + dy;
                if (x >= 0 && y >= 0 && x < lado && y < lado) {
                    poner(x, y, d != 2 && d != 4);
                }
            }
        }
    }

    private void cuadroAlineacion(int cx, int cy) {
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                poner(cx + dx, cy + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
            }
        }
    }

    /** Centros de los cuadritos de alineacion (filas y columnas). */
    public int[] alineacion() {
        if (version == 1) {
            return new int[0];
        }
        int n = version / 7 + 2;
        int paso = version == 32 ? 26 : (version * 4 + n * 2 + 1) / (n * 2 - 2) * 2;
        int[] r = new int[n];
        r[0] = 6;
        for (int i = n - 1, pos = lado - 7; i >= 1; i--, pos -= paso) {
            r[i] = pos;
        }
        return r;
    }

    /** Nivel y mascara, con su correccion BCH; va en dos copias alrededor de los buscadores. */
    private void formato(int m) {
        int dato = NIVEL_H << 3 | m;
        int resto = dato;
        for (int i = 0; i < 10; i++) {
            resto = (resto << 1) ^ ((resto >>> 9) * 0x537);
        }
        int b = (dato << 10 | resto) ^ 0x5412;
        for (int i = 0; i <= 5; i++) {
            poner(8, i, bit(b, i));
        }
        poner(8, 7, bit(b, 6));
        poner(8, 8, bit(b, 7));
        poner(7, 8, bit(b, 8));
        for (int i = 9; i < 15; i++) {
            poner(14 - i, 8, bit(b, i));
        }
        for (int i = 0; i < 8; i++) {
            poner(lado - 1 - i, 8, bit(b, i));
        }
        for (int i = 8; i < 15; i++) {
            poner(8, lado - 15 + i, bit(b, i));
        }
        poner(8, lado - 8, true); // el cuadrito negro que siempre va
    }

    private void versionBits() {
        if (version < 7) {
            return;
        }
        int resto = version;
        for (int i = 0; i < 12; i++) {
            resto = (resto << 1) ^ ((resto >>> 11) * 0x1F25);
        }
        int b = version << 12 | resto;
        for (int i = 0; i < 18; i++) {
            boolean n = bit(b, i);
            int a = lado - 11 + i % 3, c = i / 3;
            poner(a, c, n);
            poner(c, a, n);
        }
    }

    private static boolean bit(int x, int i) {
        return ((x >>> i) & 1) != 0;
    }

    // ------------------------------------------------------------------
    //  Datos y correccion de errores
    // ------------------------------------------------------------------

    /** Modo byte + largo + bytes + terminador + relleno, en bytes. */
    private byte[] bitsDatos(byte[] datos) {
        int cap = capacidad(version);
        java.util.BitSet bits = new java.util.BitSet();
        int[] n = {0};
        java.util.function.BiConsumer<Integer, Integer> escribir = (valor, largo) -> {
            for (int i = largo - 1; i >= 0; i--) {
                bits.set(n[0]++, ((valor >>> i) & 1) != 0);
            }
        };
        escribir.accept(0b0100, 4);
        escribir.accept(datos.length, version < 10 ? 8 : 16);
        for (byte b : datos) {
            escribir.accept(b & 0xFF, 8);
        }
        escribir.accept(0, Math.min(4, cap - n[0]));
        escribir.accept(0, (8 - n[0] % 8) % 8);
        byte[] r = new byte[cap / 8];
        for (int i = 0; i < n[0]; i++) {
            if (bits.get(i)) {
                r[i / 8] |= (byte) (0x80 >>> (i % 8));
            }
        }
        for (int i = n[0] / 8, k = 0; i < r.length; i++, k++) {
            r[i] = (byte) (k % 2 == 0 ? 0xEC : 0x11);
        }
        return r;
    }

    /** Parte en bloques, calcula la correccion de cada uno y los intercala. */
    private byte[] bloques(byte[] datos) {
        int nb = BLOQUES[version], ecc = ECC_POR_BLOQUE[version], total = bytesTotales(version);
        int cortos = nb - total % nb, largoCorto = total / nb;
        byte[] gen = ReedSolomon.generador(ecc);
        byte[][] bl = new byte[nb][];
        for (int i = 0, k = 0; i < nb; i++) {
            int largo = largoCorto - ecc + (i < cortos ? 0 : 1);
            byte[] d = java.util.Arrays.copyOfRange(datos, k, k + largo);
            k += largo;
            byte[] c = ReedSolomon.resto(d, gen);
            byte[] b = new byte[largoCorto + 1];
            System.arraycopy(d, 0, b, 0, d.length);
            // los bloques cortos dejan un hueco antes de la correccion, para intercalar parejo
            System.arraycopy(c, 0, b, b.length - ecc, ecc);
            bl[i] = b;
        }
        byte[] r = new byte[total];
        int k = 0;
        for (int i = 0; i < largoCorto + 1; i++) {
            for (int j = 0; j < nb; j++) {
                if (i != largoCorto - ecc || j >= cortos) {
                    r[k++] = bl[j][i];
                }
            }
        }
        return r;
    }

    /** Recorre la matriz en zigzag (de a dos columnas, de derecha a izquierda) llenando los datos. */
    private void ponerDatos(byte[] datos) {
        int i = 0, totalBits = datos.length * 8;
        for (int der = lado - 1; der >= 1; der -= 2) {
            if (der == 6) {
                der = 5; // se salta la linea de tiempo vertical
            }
            for (int v = 0; v < lado; v++) {
                for (int j = 0; j < 2; j++) {
                    int x = der - j;
                    boolean sube = ((der + 1) & 2) == 0;
                    int y = sube ? lado - 1 - v : v;
                    if (!fijo[y][x] && i < totalBits) {
                        negro[y][x] = ((datos[i >>> 3] >>> (7 - (i & 7))) & 1) != 0;
                        i++;
                    }
                }
            }
        }
    }

    private void enmascarar(int m) {
        for (int y = 0; y < lado; y++) {
            for (int x = 0; x < lado; x++) {
                if (fijo[y][x]) {
                    continue;
                }
                boolean inv;
                switch (m) {
                    case 0: inv = (x + y) % 2 == 0; break;
                    case 1: inv = y % 2 == 0; break;
                    case 2: inv = x % 3 == 0; break;
                    case 3: inv = (x + y) % 3 == 0; break;
                    case 4: inv = (x / 3 + y / 2) % 2 == 0; break;
                    case 5: inv = x * y % 2 + x * y % 3 == 0; break;
                    case 6: inv = (x * y % 2 + x * y % 3) % 2 == 0; break;
                    default: inv = ((x + y) % 2 + x * y % 3) % 2 == 0; break;
                }
                negro[y][x] ^= inv;
            }
        }
    }

    /** Puntos en contra segun la norma (rachas, cuadros 2x2, figuras parecidas al buscador, balance). */
    int penalidad() {
        int p = 0, negros = 0;
        for (int a = 0; a < 2; a++) {             // a=0 filas, a=1 columnas
            for (int i = 0; i < lado; i++) {
                int racha = 0;
                boolean color = false;
                int patron = 0;
                for (int j = 0; j < lado; j++) {
                    boolean n = a == 0 ? negro[i][j] : negro[j][i];
                    if (j > 0 && n == color) {
                        racha++;
                        if (racha == 5) {
                            p += 3;
                        } else if (racha > 5) {
                            p++;
                        }
                    } else {
                        color = n;
                        racha = 1;
                    }
                    patron = ((patron << 1) | (n ? 1 : 0)) & 0x7FF;
                    if (j >= 10 && (patron == 0b10111010000 || patron == 0b00001011101)) {
                        p += 40;
                    }
                }
            }
        }
        for (int y = 0; y < lado; y++) {
            for (int x = 0; x < lado; x++) {
                boolean n = negro[y][x];
                if (n) {
                    negros++;
                }
                if (x < lado - 1 && y < lado - 1 && n == negro[y][x + 1] && n == negro[y + 1][x] && n == negro[y + 1][x + 1]) {
                    p += 3;
                }
            }
        }
        int total = lado * lado;
        int k = (Math.abs(negros * 20 - total * 10) + total - 1) / total - 1;
        return p + Math.max(0, k) * 10;
    }

    /** Reed-Solomon en GF(256) con el polinomio 0x11D (el de los QR). */
    static final class ReedSolomon {

        private ReedSolomon() {
        }

        static int multiplicar(int a, int b) {
            int r = 0;
            for (int i = 7; i >= 0; i--) {
                r = (r << 1) ^ ((r >>> 7) * 0x11D);
                r ^= ((b >>> i) & 1) * a;
            }
            return r & 0xFF;
        }

        /** Coeficientes del polinomio (x - a^0)(x - a^1)...(x - a^(n-1)), sin el 1 del grado mayor. */
        static byte[] generador(int n) {
            byte[] r = new byte[n];
            r[n - 1] = 1;
            int raiz = 1;
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    r[j] = (byte) multiplicar(r[j] & 0xFF, raiz);
                    if (j + 1 < n) {
                        r[j] ^= r[j + 1];
                    }
                }
                raiz = multiplicar(raiz, 0x02);
            }
            return r;
        }

        /** Los bytes de correccion: el resto de dividir los datos entre el generador. */
        static byte[] resto(byte[] datos, byte[] gen) {
            byte[] r = new byte[gen.length];
            for (byte b : datos) {
                int f = (b ^ r[0]) & 0xFF;
                System.arraycopy(r, 1, r, 0, r.length - 1);
                r[r.length - 1] = 0;
                for (int i = 0; i < r.length; i++) {
                    r[i] ^= (byte) multiplicar(gen[i] & 0xFF, f);
                }
            }
            return r;
        }
    }
}
