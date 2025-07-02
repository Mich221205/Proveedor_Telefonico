package proveedor;

import java.io.*;
import java.net.Socket;

public class NotificadorIdentificador {

    public static boolean notificarEstadoLinea(String telefono, String idTel, String idChip, String tipo, String cedula, String estado) {

        try (Socket socket = new Socket(Config.HOST_IDENTIFICADOR, Config.PUERTO_IDENTIFICADOR);
             PrintWriter salida = new PrintWriter(socket.getOutputStream(), true);
             BufferedReader entrada = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

            // Construir JSON de la trama a enviar, incluyendo tipo_transaccion = 6
            String trama = String.format(
                "{\"tipo_transaccion\":\"6\",\"telefono\":\"%s\",\"identificadorTel\":\"%s\",\"identificador_tarjeta\":\"%s\",\"tipo\":\"%s\",\"identificacion_cliente\":\"%s\",\"estado\":\"%s\"}",
                telefono, idTel, idChip, tipo, cedula, estado
            );

            salida.println(trama); // Enviar al Identificador

            String respuesta = entrada.readLine();
            System.out.println("Respuesta del Identificador: " + respuesta);

            return respuesta != null && respuesta.contains("OK");

        } catch (IOException e) {
            System.err.println("Error comunicándose con el Identificador: " + e.getMessage());
            return false;
        }
    }
}
