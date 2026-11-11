package servidor;

import comum.Log;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Funcoes do servidor usadas pela janela (ServidorGUI): criar o banco com o admin inicial,
 * aceitar conexoes (uma thread por cliente) e listar os IPs da maquina.
 */
public final class Servidor {

    private Servidor() { }

    private static final String TAG = "[SERVIDOR]";

    /** Banco novo com o admin inicial (regra 3.15, v2.6): criado quando nao ha nenhum admin. */
    public static BancoDados criarBanco() {
        BancoDados banco = new BancoDados();
        if (banco.contarAdmins() == 0) {
            banco.criarUsuario("admin@admin.com", "admin", "admin", "admin");
        }
        return banco;
    }

    /** Aceita clientes para sempre, uma thread por conexao (regra 1.1). Termina quando o socket e fechado. */
    public static void atender(ServerSocket servidor, BancoDados banco) {
        int contador = 0;
        while (!servidor.isClosed()) {
            try {
                Socket cliente = servidor.accept();
                contador++;
                Thread t = new Thread(new ConexaoCliente(cliente, banco, contador), "cliente-" + contador);
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (servidor.isClosed()) return;
                Log.print(TAG, "ERRO ao aceitar conexao: " + e.getMessage());
            }
        }
    }

    /** IPv4 desta maquina (fora o 127.0.0.1), com o nome da placa de rede. */
    public static List<String> ipsDaMaquina() {
        List<String> ips = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress end : Collections.list(ni.getInetAddresses())) {
                    if (end instanceof Inet4Address) {
                        ips.add(end.getHostAddress() + "   (" + ni.getDisplayName() + ")");
                    }
                }
            }
        } catch (Exception e) {
            // so informativo
        }
        return ips;
    }
}
