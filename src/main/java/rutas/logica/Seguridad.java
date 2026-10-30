package rutas.logica;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Cifrado de contraseñas con PBKDF2 (viene con Java, no hay que instalar nada).
 *
 * Nunca se guarda la contraseña: se guarda una "sal" al azar y el resultado de
 * mezclar la contraseña con esa sal muchas veces. Para revisar un ingreso se
 * repite la mezcla y se compara el resultado.
 */
public final class Seguridad {

    /** Vueltas de la mezcla: mas vueltas = mas lento adivinar contraseñas. */
    public static final int ITERACIONES = 210_000;

    private static final int BYTES_SAL = 16;
    private static final int BITS_HASH = 256;
    private static final SecureRandom AZAR = new SecureRandom();

    private Seguridad() {
    }

    public static byte[] crearSal() {
        byte[] sal = new byte[BYTES_SAL];
        AZAR.nextBytes(sal);
        return sal;
    }

    public static byte[] hash(char[] clave, byte[] sal, int iteraciones) {
        PBEKeySpec spec = new PBEKeySpec(clave, sal, iteraciones, BITS_HASH);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Este Java no tiene PBKDF2", e);
        } finally {
            spec.clearPassword();
        }
    }

    /** Compara en tiempo constante para no dar pistas por lo que se demora. */
    public static boolean coincide(char[] clave, byte[] sal, int iteraciones, byte[] esperado) {
        return MessageDigest.isEqual(hash(clave, sal, iteraciones), esperado);
    }

    public static String aTexto(byte[] datos) {
        return Base64.getEncoder().encodeToString(datos);
    }

    public static byte[] deTexto(String texto) {
        return Base64.getDecoder().decode(texto);
    }
}
