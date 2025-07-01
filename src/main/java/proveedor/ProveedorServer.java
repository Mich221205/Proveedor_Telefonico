package proveedor;

import java.io.*;
import java.net.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class ProveedorServer {
    private final int puerto;
    private final Map<String, LocalDateTime> llamadasEnCurso = new HashMap<>();

    public ProveedorServer(int puerto) {
        this.puerto = puerto;
    }

    public void iniciar() {
        try (ServerSocket servidor = new ServerSocket(puerto)) {
            System.out.println("Servidor escuchando en puerto " + puerto + "...");

            while (true) {
                Socket cliente = servidor.accept();
                new Thread(() -> manejarCliente(cliente)).start();
            }
        } catch (Exception e) {
            System.err.println("Error general: " + e.getMessage());
        }
    }

    private void manejarCliente(Socket cliente) {
        try (
            BufferedReader entrada = new BufferedReader(new InputStreamReader(cliente.getInputStream()));
            PrintWriter salida = new PrintWriter(cliente.getOutputStream(), true)
        ) {
            String jsonStr = entrada.readLine();
            if (jsonStr == null || jsonStr.trim().isEmpty()) {
                salida.println("{\"status\":\"ERROR\",\"mensaje\":\"JSON vacio\"}");
                return;
            }

            Map<String, String> datos = parsearJsonSimple(jsonStr);
            if (datos == null) {
                salida.println("{\"status\":\"ERROR\",\"mensaje\":\"Formato JSON invalido\"}");
                return;
            }

            System.out.println("DEBUG JSON recibido: " + datos);
            String telefono = datos.get("telefono");
            String tipoStr = datos.get("tipo_transaccion");
            if (tipoStr == null || telefono == null) {
                salida.println("{\"status\":\"ERROR\",\"mensaje\":\"Datos incompletos\"}");
                return;
            }

            int tipoTransaccion = Integer.parseInt(tipoStr.trim());

            try (ConexionSQLServer db = new ConexionSQLServer()) {
                if (!db.conectar()) {
                    salida.println("{\"status\":\"ERROR\",\"mensaje\":\"Fallo de conexion a BD\"}");
                    return;
                }

                String respuesta;

                switch (tipoTransaccion) {
                    case 1: { // Llamada
                        String tipoServicio = db.obtenerTipoServicio(telefono);
                        String destino = datos.get("destino");
                        int tipoLlamada = Integer.parseInt(datos.get("tipo_llamada"));

                        respuesta = procesarLlamada(db, tipoServicio, telefono, destino, tipoLlamada);
                        llamadasEnCurso.put(telefono, LocalDateTime.now());
                        break;
                    }
               
                    case 2: {
                        String tipoServicio = db.obtenerTipoServicio(telefono);
                        respuesta = procesarConsulta(db, tipoServicio, telefono);
                        break;
                    }
                    
                    case 3: { // PROVEEDOR3 - Registrar nueva linea disponible
                        String telefonoo = datos.get("telefono"); 
                        String identificadorTel = datos.get("identificadorTel");
                        String identificadorTarjeta = datos.get("identificador_tarjeta");
                        String tipo = datos.get("tipo"); // prepago / postpago
                        String estado = datos.get("estado"); // disponible

                        if (telefonoo == null || identificadorTel == null || identificadorTarjeta == null || tipo == null || estado == null) {
                            respuesta = "{\"status\":\"ERROR\",\"mensaje\":\"Datos Incompletos\"}";
                            break;
                        }

                        if (db.telefonoExiste(telefonoo)) {
                            respuesta = "{\"status\":\"ERROR\",\"mensaje\":\"Telefono en uso\"}";
                            break;
                        }

                        boolean exito = db.insertarNuevaLineaProveedor(telefonoo, identificadorTel, identificadorTarjeta, tipo, estado);
                        respuesta = exito
                            ? "{\"status\":\"OK\"}"
                            : "{\"status\":\"ERROR\",\"mensaje\":\"No se pudo registrar la linea\"}";
                        break;
                    }

                    case 5: {
                        String destino = datos.get("destino");
                        String duracionStr = datos.get("duracion");
                        String fecha = datos.get("fecha");
                        String hora = datos.get("hora");

                        int h = Integer.parseInt(duracionStr.substring(0, 2));
                        int m = Integer.parseInt(duracionStr.substring(2, 4));
                        int s = Integer.parseInt(duracionStr.substring(4, 6));
                        long segundos = h * 3600 + m * 60 + s;

                        double costo = 0.0;
                        try {
                            costo = Double.parseDouble(datos.get("costo")); // ← ahora llega como numero decimal
                        } catch (Exception e) {
                            respuesta = "{\"status\":\"ERROR\",\"mensaje\":\"Costo invalido\"}";
                            break;
                        }

                        boolean exito = db.registrarLlamadaYTransaccion(
                            telefono, destino, fecha, hora, costo, duracionStr, 5
                        );
                        llamadasEnCurso.remove(telefono);

                        respuesta = exito
                            ? "{\"status\":\"OK\"}"
                            : "{\"status\":\"ERROR\",\"mensaje\":\"No se pudo guardar\"}";
                        break;
                    }

                    default:
                        respuesta = "{\"status\":\"ERROR\",\"mensaje\":\"Transaccion no soportada\"}";
                }

                salida.println(respuesta);
                System.out.println("Respuesta enviada al cliente: " + respuesta);
            }

        } catch (Exception e) {
            System.err.println("Error con cliente: " + e.getMessage());
        } finally {
            try {
                cliente.close();
            } catch (IOException ex) {
                System.err.println("Error al cerrar socket: " + ex.getMessage());
            }
        }
    }

    private Map<String, String> parsearJsonSimple(String jsonStr) {
        try {
            Map<String, String> datos = new HashMap<>();
            jsonStr = jsonStr.trim().replaceAll("[{}\"]", "");
            String[] partes = jsonStr.split(",");
            for (String parte : partes) {
                String[] keyValue = parte.split(":");
                if (keyValue.length == 2) {
                    datos.put(keyValue[0].trim(), keyValue[1].trim());
                }
            }
            return datos;
        } catch (Exception e) {
            return null;
        }
    }

    private String procesarLlamada(ConexionSQLServer db, String tipoServicio, String telefono, String destino, int tipoLlamada) {
        try {
            if ("postpago".equals(tipoServicio)) {
                return "{\"status\":\"OK\",\"costo\":\"0000000000\",\"tiempo\":\"245959\"}";
            } else if ("prepago".equals(tipoServicio)) {
                double saldo = db.obtenerSaldo(telefono);
                double tarifa = calcularTarifa(db, destino, tipoLlamada);

                if (saldo >= tarifa) {
                    int segundos = (int) ((saldo / tarifa) * 60);
                    String tiempo = String.format("%02d%02d%02d", segundos / 3600, (segundos % 3600) / 60, segundos % 60);
                    double costo = (tarifa / 60) * segundos;
                    String costoFormateado = String.format("%010d", (int) (costo * 100));

                    return "{\"status\":\"OK\",\"costo\":\"" + costoFormateado + "\",\"tiempo\":\"" + tiempo + "\"}";
                } else {
                    return "{\"status\":\"ERROR\",\"mensaje\":\"Saldo insuficiente\"}";
                }
            }
        } catch (Exception e) {
            return "{\"status\":\"ERROR\",\"mensaje\":\"Error procesando llamada\"}";
        }
        return "{\"status\":\"ERROR\",\"mensaje\":\"Tipo de servicio desconocido\"}";
    }

    private String procesarConsulta(ConexionSQLServer db, String tipoServicio, String telefono) {
        try {
            // Registrar la transaccion como lo hacias antes
            db.registrarLlamadaYTransaccion(telefono, telefono, "20250627", "000000", 0.0, "000000", 2);

            if ("postpago".equals(tipoServicio)) {
                return "{\"status\":\"OK\",\"saldo\":\"-1\"}";
            } else if ("prepago".equals(tipoServicio)) {
                double saldo = db.obtenerSaldo(telefono);
                return String.format("{\"status\":\"OK\",\"saldo\":\"%.2f\"}", saldo);
            }
        } catch (Exception e) {
            return "{\"status\":\"error\",\"message\":\"Error procesando consulta\"}";
        }

        return "{\"status\":\"error\",\"message\":\"Tipo de servicio desconocido\"}";
    }


    private double calcularTarifa(ConexionSQLServer db, String destino, int tipoLlamada) {
        if (tipoLlamada == 3) {
            String grupo = db.obtenerGrupoInternacional(destino);
            return db.obtenerTarifaInternacional(grupo);
        } else {
            return db.obtenerTarifaLocal(tipoLlamada);
        }
    }
}
