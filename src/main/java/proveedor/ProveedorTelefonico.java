package proveedor;

public class ProveedorTelefonico {
 
    public static void main (String[] args) {
        ProveedorServer servidor = new ProveedorServer(6000);
        servidor.iniciar(); // arranca el socket
    } 
}
