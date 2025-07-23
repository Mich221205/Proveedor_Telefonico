package proveedor;

public class ProveedorTelefonico {
 
    public static void main (String[] args) {
        ProveedorServer servidor = new ProveedorServer(Config.PUERTO_PROVEEDOR);
        servidor.iniciar(); // arranca el socket
    } 
}