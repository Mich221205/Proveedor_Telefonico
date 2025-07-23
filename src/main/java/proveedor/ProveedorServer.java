package proveedor;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.*;
import java.lang.reflect.Type;
import java.net.*;
import java.time.LocalDateTime;
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

            Map<String, String> datos = parsearJsonConGson(jsonStr);
            if (datos == null) {
                salida.println("{\"status\":\"ERROR\",\"mensaje\":\"Formato JSON invalido\"}");
                return;
            }

            System.out.println("DEBUG JSON recibido: " + datos);

            String tipoStr = datos.get("tipo_transaccion");

            if (tipoStr == null) {
                salida.println("{\"status\":\"ERROR\",\"mensaje\":\"Tipo de transacción faltante\"}");
                return;
            }

            int tipoTransaccion = Integer.parseInt(tipoStr.trim());
            String respuesta = "";
            String telefono = datos.get("telefono"); // se usa solo si aplica

            try (ConexionSQLServer db = new ConexionSQLServer()) {
                if (!db.conectar()) {
                    salida.println("{\"status\":\"ERROR\",\"mensaje\":\"Fallo de conexion a BD\"}");
                    return;
                }

                switch (tipoTransaccion) {
                    case 1: {
                        if (telefono == null || datos.get("destino") == null || datos.get("tipo_llamada") == null) {
                            respuesta = "{\"status\":\"ERROR\",\"mensaje\":\"Datos incompletos\"}";
                            break;
                        }

                        String tipoServicio = db.obtenerTipoServicio(telefono);
                        String destino = datos.get("destino");
                        int tipoLlamada = Integer.parseInt(datos.get("tipo_llamada"));

                        respuesta = procesarLlamada(db, tipoServicio, telefono, destino, tipoLlamada);
                        llamadasEnCurso.put(telefono, LocalDateTime.now());
                        break;
                    }

                    case 2: {
                        if (telefono == null) {
                            respuesta = "{\"status\":\"ERROR\",\"mensaje\":\"Datos incompletos\"}";
                            break;
                        }

                        String tipoServicio = db.obtenerTipoServicio(telefono);
                        respuesta = procesarConsulta(db, tipoServicio, telefono);
                        break;
                    }

                    case 3: {
                        String telefonoo = datos.get("telefono");
                        String identificadorTel = datos.get("identificadorTel");
                        String identificadorTarjeta = datos.get("identificador_tarjeta");
                        String tipo = datos.get("tipo");
                        String estado = datos.get("estado");

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
                        if (telefono == null || datos.get("destino") == null || datos.get("fecha") == null ||
                            datos.get("hora") == null || datos.get("duracion") == null || datos.get("costo") == null) {
                            respuesta = "{\"status\":\"ERROR\",\"mensaje\":\"Datos incompletos\"}";
                            break;
                        }

                        String destino = datos.get("destino");
                        String duracionStr = datos.get("duracion");
                        String fecha = datos.get("fecha");
                        String hora = datos.get("hora");

                        int h = Integer.parseInt(duracionStr.substring(0, 2));
                        int m = Integer.parseInt(duracionStr.substring(2, 4));
                        int s = Integer.parseInt(duracionStr.substring(4, 6));
                        long segundos = h * 3600 + m * 60 + s;

                        double costo;
                        try {
                            costo = Double.parseDouble(datos.get("costo"));
                        } catch (Exception e) {
                            respuesta = "{\"status\":\"ERROR\",\"mensaje\":\"Costo invalido\"}";
                            break;
                        }

                        boolean exito = db.registrarLlamadaYTransaccion(telefono, destino, fecha, hora, costo, duracionStr, 5);
                        llamadasEnCurso.remove(telefono);

                        respuesta = exito
                            ? "{\"status\":\"OK\"}"
                            : "{\"status\":\"ERROR\",\"mensaje\":\"No se pudo guardar\"}";
                        break;
                    }

                    case 6: {
                        String tel = datos.get("telefono");
                        String idTel = datos.get("identificadorTel");
                        String idChip = datos.get("identificador_tarjeta");
                        String tipo = datos.get("tipo");
                        String cedula = datos.get("duenio");
                        String estadoOperacion = datos.get("estado");

                        if (tel == null || idTel == null || idChip == null || tipo == null || cedula == null || estadoOperacion == null) {
                            respuesta = "{\"status\":\"ERROR\",\"mensaje\":\"Datos Incompletos\"}";
                            break;
                        }

                        if (estadoOperacion.equalsIgnoreCase("activar")) {
                            int resultado = db.activarLinea(tel, idTel, idChip, tipo, cedula);
                            switch (resultado) {
                                case 1:
                                    boolean notificado = NotificadorIdentificador.notificarEstadoLinea(tel, idTel, idChip, tipo, cedula, "activo");
                                    respuesta = notificado
                                        ? "{\"status\":\"OK\"}"
                                        : "{\"status\":\"ERROR\",\"mensaje\":\"Activación fallida (no notificado)\"}";
                                    break;
                                case -2: respuesta = "{\"status\":\"ERROR\",\"mensaje\":\"Teléfono ya está en uso\"}"; break;
                                case -3: respuesta = "{\"status\":\"ERROR\",\"mensaje\":\"Tipo de teléfono inválido\"}"; break;
                                case -4: respuesta = "{\"status\":\"ERROR\",\"mensaje\":\"Cliente no encontrado por cédula\"}"; break;
                                case -5: respuesta = "{\"status\":\"ERROR\",\"mensaje\":\"Teléfono y tarjetas no encontrados\"}"; break;
                                case -6: respuesta = "{\"status\":\"ERROR\",\"mensaje\":\"No se pudo actualizar el estado\"}"; break;
                                case -99: respuesta = "{\"status\":\"ERROR\",\"mensaje\":\"Excepción SQL al activar línea\"}"; break;
                                default: respuesta = "{\"status\":\"ERROR\",\"mensaje\":\"Error desconocido al activar\"}";
                            }
                        } else if (estadoOperacion.equalsIgnoreCase("desactivar")) {
                            int resultado = db.desactivarLinea(tel, idTel, idChip, cedula);
                            switch (resultado) {
                                case 1:
                                    boolean notificado = NotificadorIdentificador.notificarEstadoLinea(tel, idTel, idChip, tipo, cedula, "inactivo");
                                    respuesta = notificado
                                        ? "{\"status\":\"OK\"}"
                                        : "{\"status\":\"ERROR\",\"mensaje\":\"Desactivación fallida (no notificado)\"}";
                                    break;
                                case -2: respuesta = "{\"status\":\"ERROR\",\"mensaje\":\"El cliente no coincide con el dueño actual del teléfono\"}"; break;
                                case -4: respuesta = "{\"status\":\"ERROR\",\"mensaje\":\"Cliente no encontrado por cédula\"}"; break;
                                case -5: respuesta = "{\"status\":\"ERROR\",\"mensaje\":\"Línea activa no encontrada\"}"; break;
                                case -6: respuesta = "{\"status\":\"ERROR\",\"mensaje\":\"No se pudo desactivar la línea\"}"; break;
                                case -99: respuesta = "{\"status\":\"ERROR\",\"mensaje\":\"Excepción SQL al desactivar línea\"}"; break;
                                default: respuesta = "{\"status\":\"ERROR\",\"mensaje\":\"Error desconocido al desactivar\"}";
                            }
                        } else {
                            respuesta = "{\"status\":\"ERROR\",\"mensaje\":\"Estado no válido (debe ser 'activar' o 'desactivar')\"}";
                        }
                        break;
                    }

                    case 7: {
                        String fechaCalculo = datos.get("fecha_calculo");
                        String fechaMaxPago = datos.get("fecha_max_pago");

                        if (fechaCalculo == null || fechaMaxPago == null) {
                            respuesta = "{\"status\":\"ERROR\",\"mensaje\":\"Datos incompletos\"}";
                            break;
                        }

                        boolean exito = db.ejecutarCalculoCobroPostpago(fechaCalculo, fechaMaxPago);
                        respuesta = exito
                            ? "{\"status\":\"OK\"}"
                            : "{\"status\":\"ERROR\",\"mensaje\":\"Error al ejecutar cálculo de cobros\"}";
                        break;
                    }

                    default:
                        respuesta = "{\"status\":\"ERROR\",\"mensaje\":\"Transaccion no soportada\"}";
                }
                salida.println(respuesta);
                salida.flush();

                try 
                {
                  Thread.sleep(100);
                }catch (InterruptedException ie)
                {
                  System.err.println("Error en espera antes de cerrar socket: " + ie.getMessage());
                }

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


    private Map<String, String> parsearJsonConGson(String jsonStr) {
        try {
            Type tipo = new TypeToken<Map<String, String>>() {}.getType();
            return new Gson().fromJson(jsonStr, tipo);
        } catch (Exception e) {
            System.err.println("Error parseando JSON con Gson: " + e.getMessage());
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