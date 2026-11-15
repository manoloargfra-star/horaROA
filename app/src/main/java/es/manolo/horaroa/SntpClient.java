package es.manolo.horaroa;

import android.os.SystemClock;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

/**
 * Cliente SNTP (RFC 4330) mínimo.
 * Consulta el servidor varias veces y se queda con la muestra de menor retardo.
 */
public class SntpClient {

    public static final String SERVIDOR = "hora.roa.es";
    private static final int PUERTO = 123;
    private static final int TIMEOUT_MS = 3000;
    private static final long SEGUNDOS_1900_1970 = 2208988800L;

    /** Resultado de una sincronización. */
    public static class Resultado {
        /** Hora ROA (ms Unix) en el instante de recepción de la respuesta. */
        public long horaRoaMs;
        /** SystemClock.elapsedRealtime() en ese mismo instante. */
        public long elapsedMs;
        /** Diferencia reloj del móvil - hora ROA (ms). Positivo = el móvil adelanta. */
        public long desfaseMovilMs;
        /** Retardo de ida y vuelta (ms). */
        public long retardoMs;
        /** Estrato del servidor. */
        public int estrato;
    }

    public Resultado sincronizar(int intentos) throws Exception {
        InetAddress direccion = InetAddress.getByName(SERVIDOR);
        Resultado mejor = null;
        Exception ultimoError = null;

        for (int i = 0; i < intentos; i++) {
            try {
                Resultado r = consulta(direccion);
                if (mejor == null || r.retardoMs < mejor.retardoMs) mejor = r;
            } catch (Exception e) {
                ultimoError = e;
            }
        }
        if (mejor == null) throw ultimoError != null ? ultimoError : new Exception("Sin respuesta");
        return mejor;
    }

    private Resultado consulta(InetAddress direccion) throws Exception {
        byte[] buffer = new byte[48];
        buffer[0] = 0x23; // LI = 0, versión = 4, modo = 3 (cliente)

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(TIMEOUT_MS);
            DatagramPacket peticion = new DatagramPacket(buffer, buffer.length, direccion, PUERTO);

            long t1 = System.currentTimeMillis();
            long e1 = SystemClock.elapsedRealtime();
            escribirTimestamp(buffer, 40, t1); // transmit timestamp
            socket.send(peticion);

            DatagramPacket respuesta = new DatagramPacket(buffer, buffer.length);
            socket.receive(respuesta);
            long e4 = SystemClock.elapsedRealtime();
            long t4 = t1 + (e4 - e1); // inmune a saltos del reloj del móvil

            int modo = buffer[0] & 0x07;
            int estrato = buffer[1] & 0xff;
            if (modo != 4 && modo != 5) throw new Exception("Modo NTP no válido");
            if (estrato == 0 || estrato > 15) throw new Exception("Servidor no sincronizado");

            long t2 = leerTimestamp(buffer, 32); // receive timestamp del servidor
            long t3 = leerTimestamp(buffer, 40); // transmit timestamp del servidor

            long desfase = ((t2 - t1) + (t3 - t4)) / 2; // ROA - móvil
            long retardo = (t4 - t1) - (t3 - t2);

            Resultado r = new Resultado();
            r.horaRoaMs = t4 + desfase;
            r.elapsedMs = e4;
            r.desfaseMovilMs = -desfase;
            r.retardoMs = retardo;
            r.estrato = estrato;
            return r;
        }
    }

    private static long leer32(byte[] b, int off) {
        return ((long) (b[off] & 0xff) << 24) | ((b[off + 1] & 0xff) << 16)
                | ((b[off + 2] & 0xff) << 8) | (b[off + 3] & 0xff);
    }

    private static long leerTimestamp(byte[] b, int off) {
        long segundos = leer32(b, off);
        long fraccion = leer32(b, off + 4);
        return (segundos - SEGUNDOS_1900_1970) * 1000L + (fraccion * 1000L) / 0x100000000L;
    }

    private static void escribirTimestamp(byte[] b, int off, long ms) {
        long segundos = ms / 1000L + SEGUNDOS_1900_1970;
        long fraccion = (ms % 1000L) * 0x100000000L / 1000L;
        for (int i = 3; i >= 0; i--) { b[off + i] = (byte) segundos; segundos >>= 8; }
        for (int i = 7; i >= 4; i--) { b[off + i] = (byte) fraccion; fraccion >>= 8; }
    }
}
