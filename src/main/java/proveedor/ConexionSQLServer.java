// ConexionSQLServer.java
package proveedor;

import java.sql.*;
import java.util.Objects;

public class ConexionSQLServer implements AutoCloseable {
    private Connection conexion;

    public boolean conectar() {
        try {
            String url = "jdbc:sqlserver://localhost:1433;"
                       + "databaseName=COMPANIA_TELEFONICA;"
                       + "user=sa;"
                       + "password=mich22;"
                       + "encrypt=false;"
                       + "trustServerCertificate=true;";

            Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
            conexion = DriverManager.getConnection(url);
            System.out.println("Conexión exitosa a SQL Server.");
            return true;
        } catch (Exception e) {
            System.err.println("Error al conectar con SQL Server: " + e.getMessage());
            return false;
        }
    }

    public double obtenerSaldo(String numero) {
        try (PreparedStatement stmt = conexion.prepareStatement(
                "SELECT SALDO FROM dbo.TELEFONOS WHERE NUM_TELEFONO = ?")) {
            stmt.setString(1, numero);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getDouble("SALDO") : 0.0;
            }
        } catch (SQLException e) {
            System.err.println("Error al obtener saldo: " + e.getMessage());
            return 0.0;
        }
    }
    
    public boolean telefonoExiste(String telefonoo) {
    try (PreparedStatement stmt = conexion.prepareStatement(
            "SELECT 1 FROM TELEFONOS WHERE NUM_TELEFONO = ?")) {
        stmt.setString(1, telefonoo);
        try (ResultSet rs = stmt.executeQuery()) {
            return rs.next(); // true si existe, false si no
        }
    } catch (SQLException e) {
        System.err.println("Error al verificar existencia del teléfono: " + e.getMessage());
        return true; // Por seguridad, asumimos que existe si hay error
    }
    }

    
    public int obtenerIdTipoTelefono(String tipo) {
        try (PreparedStatement stmt = conexion.prepareStatement(
                "SELECT ID_T_TELEFONO FROM TIPO_TELEFONO WHERE LOWER(DESCRIPCION) = ?")) {
            stmt.setString(1, tipo.toLowerCase());
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getInt("ID_T_TELEFONO") : -1;
            }
        } catch (SQLException e) {
            System.err.println("Error al obtener ID_T_TELEFONO: " + e.getMessage());
            return -1;
        }
    }
    
    public boolean insertarNuevaLineaProveedor(String numero, String identificadorTel, String identificadorTarjeta, String tipo, String estado) {
        try {
            int idTipoTelefono = obtenerIdTipoTelefono(tipo);
            if (idTipoTelefono == -1) return false;

            int idCodigo = 1; // Código de país por defecto (ajustar si es dinámico)
            double saldoInicial = tipo.equalsIgnoreCase("prepago") ? 1000.0 : 0.0;

            // Convertir estado a booleano (1 = disponible, 0 = no disponible)
            boolean estadoDisponible = estado.equalsIgnoreCase("disponible") || estado.equals("1");

            PreparedStatement stmt = conexion.prepareStatement(
                "INSERT INTO TELEFONOS (NUM_TELEFONO, IDENTIFICADOR_TELEFONO, IDENTIFICADOR_TARJETA, ID_CODIGO, ID_CLIENTE, TIPO_TELEFONO, SALDO, ESTADO) " +
                "VALUES (?, ?, ?, ?, NULL, ?, ?, ?)"
            );
            stmt.setString(1, numero);
            stmt.setString(2, identificadorTel);
            stmt.setString(3, identificadorTarjeta);
            stmt.setInt(4, idCodigo);
            stmt.setInt(5, idTipoTelefono);
            stmt.setDouble(6, saldoInicial);
            stmt.setBoolean(7, estadoDisponible);

            return stmt.executeUpdate() > 0;

        } catch (SQLException e) {
            System.err.println("Error al insertar línea proveedor: " + e.getMessage());
            return false;
        }
    }

    public String obtenerTipoServicio(String numero) {
        Objects.requireNonNull(numero, "El número de teléfono no puede ser nulo");

        try (PreparedStatement stmt = conexion.prepareStatement(
                "SELECT TT.DESCRIPCION " +
                "FROM dbo.TELEFONOS T " +
                "JOIN dbo.TIPO_TELEFONO TT ON T.TIPO_TELEFONO = TT.ID_T_TELEFONO " +
                "WHERE T.NUM_TELEFONO = ?")) {

            stmt.setString(1, numero);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getString("DESCRIPCION").toLowerCase() : "no_encontrado";
            }
        } catch (SQLException e) {
            System.err.println("Error al obtener tipo de servicio: " + e.getMessage());
            return "error";
        }
    }

    public boolean registrarLlamadaYTransaccion(String numeroOrigen, String numeroDestino,
                                                String fecha, String hora, double costo,
                                                String duracion, int tipoTransaccion) {
        try {
            PreparedStatement psLlamada = conexion.prepareStatement(
                "INSERT INTO LLAMADAS (NUMTELEFONO_ORIGEN, NUMTELEFONO_DESTINO, COSTO, DURACION) " +
                "VALUES (?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            psLlamada.setString(1, numeroOrigen);
            psLlamada.setString(2, numeroDestino);
            psLlamada.setDouble(3, costo);
            psLlamada.setString(4, duracion);
            psLlamada.executeUpdate();

            int idLlamada = -1;
            try (ResultSet rs = psLlamada.getGeneratedKeys()) {
                if (rs.next()) {
                    idLlamada = rs.getInt(1);
                } else {
                    System.err.println("No se pudo obtener el ID de la llamada.");
                    return false;
                }
            }

            if (!registrarTransaccion(tipoTransaccion, idLlamada)) return false;

            if (tipoTransaccion == 5 && "prepago".equals(obtenerTipoServicio(numeroOrigen))) {
                PreparedStatement psRebajo = conexion.prepareStatement(
                    "UPDATE TELEFONOS SET SALDO = SALDO - ? WHERE NUM_TELEFONO = ?");
                psRebajo.setDouble(1, costo);
                psRebajo.setString(2, numeroOrigen);
                return psRebajo.executeUpdate() > 0;
            }

            return true;
        } catch (SQLException e) {
            System.err.println("Error al registrar llamada y transacción: " + e.getMessage());
            return false;
        }
    }

    public boolean registrarTransaccion(int tipoTransaccion, int idLlamada) {
        try (PreparedStatement ps = conexion.prepareStatement(
                "INSERT INTO TRANSACCIONES (ID_T_TRANS, ID_LLAMADA) VALUES (?, ?)")) {
            ps.setInt(1, tipoTransaccion);
            ps.setInt(2, idLlamada);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("Error al registrar transacción: " + e.getMessage());
            return false;
        }
    }

    public double obtenerTarifaLocal(int tipoLlamada) {
        try (PreparedStatement stmt = conexion.prepareStatement(
                "SELECT COSTOS FROM TIPO_LLAMADA WHERE ID_T_LLAMADA = ?")) {
            stmt.setInt(1, tipoLlamada);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getDouble("COSTOS") : 9999;
            }
        } catch (SQLException e) {
            System.err.println("Error al obtener tarifa local: " + e.getMessage());
            return 9999;
        }
    }

    public String obtenerGrupoInternacional(String numeroDestino) {
        try {
            for (int i = 4; i >= 1; i--) {
                if (numeroDestino.length() >= i) {
                    String prefijo = numeroDestino.substring(0, i);
                    try (PreparedStatement stmt = conexion.prepareStatement(
                            "SELECT GRUPO FROM PREFIJOS_INTERNACIONALES WHERE PREFIJO = ?")) {
                        stmt.setString(1, prefijo);
                        try (ResultSet rs = stmt.executeQuery()) {
                            if (rs.next()) return rs.getString("GRUPO");
                        }
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("Error al obtener grupo internacional: " + e.getMessage());
        }
        return null;
    }

    public double obtenerTarifaInternacional(String grupo) {
        if (grupo == null) return 9999;
        int tipoLlamada;
        switch (grupo) {
            case "C1": tipoLlamada = 4; break;
            case "B":  tipoLlamada = 5; break;
            case "D":  tipoLlamada = 6; break;
            case "E":  tipoLlamada = 7; break;
            default:   return 9999;
        }
        return obtenerTarifaLocal(tipoLlamada);
    }

    @Override
    public void close() {
        try {
            if (conexion != null && !conexion.isClosed()) {
                conexion.close();
                System.out.println("Conexión cerrada correctamente.");
            }
        } catch (SQLException e) {
            System.err.println("Error al cerrar conexión: " + e.getMessage());
        }
    }
}
