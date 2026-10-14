package servidor;

import comum.Log;
import comum.Protocolo;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;

/**
 * SERVIDOR - Sistema de Reserva de Salas (EP-1)
 *
 * Como rodar: java -jar Servidor.jar   (ou a classe servidor.Servidor)
 * Pede a porta, fica escutando e cria uma thread para cada cliente.
 * O servidor e so intermediario: o unico comando no terminal e "sair".
 * Consultas e administracao dos cadastros sao feitas pelo cliente, logado como admin.
 */
public final class Servidor {

    private static final String TAG = "[SERVIDOR]";

    public static void main(String[] args) {
        BufferedReader teclado = new BufferedReader(new InputStreamReader(System.in));

        System.out.println("==============================================================");
        System.out.println("   SERVIDOR - Sistema de Reserva de Salas (protocolo v" + Protocolo.VERSAO + ")");
        System.out.println("==============================================================");

        BancoDados banco = new BancoDados();
        banco.criarUsuario("admin@email.com", "admin", "admin123", "admin");

        ServerSocket servidor = null;
        int porta = 0;
        while (servidor == null) {
            porta = lerPorta(teclado);
            try {
                servidor = new ServerSocket(porta);
            } catch (IOException e) {
                Log.print(TAG, "ERRO: nao foi possivel abrir a porta " + porta + " (" + e.getMessage()
                        + "). Ela pode estar em uso. Escolha outra.");
            }
        }

        Log.print(TAG, "Servidor escutando na porta " + porta);
        mostrarIps(porta);
        Log.print(TAG, "Administrador padrao: email admin@email.com / senha admin123");
        mostrarAjuda();
        iniciarConsole(teclado);

        int contador = 0;
        while (true) {
            try {
                Socket cliente = servidor.accept();
                contador++;
                Thread t = new Thread(new ConexaoCliente(cliente, banco, contador), "cliente-" + contador);
                t.start();
            } catch (IOException e) {
                Log.print(TAG, "ERRO ao aceitar conexao: " + e.getMessage());
            }
        }
    }

    private static int lerPorta(BufferedReader teclado) {
        while (true) {
            System.out.print("Digite a porta do servidor (ex: 5000): ");
            System.out.flush();
            String linha;
            try {
                linha = teclado.readLine();
            } catch (IOException e) {
                linha = null;
            }
            if (linha == null) {
                System.out.println("\nEntrada encerrada. Saindo.");
                System.exit(0);
            }
            try {
                int porta = Integer.parseInt(linha.trim());
                if (porta >= 1 && porta <= 65535) return porta;
            } catch (NumberFormatException ignorada) {
                // cai na mensagem abaixo
            }
            System.out.println("Porta invalida. Digite um numero entre 1 e 65535.");
        }
    }

    /** Mostra os IPs desta maquina, para o colega digitar no cliente. */
    private static void mostrarIps(int porta) {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress end : Collections.list(ni.getInetAddresses())) {
                    if (end instanceof Inet4Address) {
                        Log.print(TAG, "IP para os clientes: " + end.getHostAddress() + "  porta " + porta
                                + "   (" + ni.getDisplayName() + ")");
                    }
                }
            }
        } catch (Exception e) {
            // so informativo
        }
        Log.print(TAG, "Na mesma maquina use 127.0.0.1  porta " + porta);
    }

    private static void mostrarAjuda() {
        System.out.println("--------------------------------------------------------------");
        System.out.println(" O servidor so atende os clientes; tudo e feito pelo cliente.");
        System.out.println(" Para consultar e gerenciar cadastros, entre no cliente como admin.");
        System.out.println(" Digite 'sair' e Enter para encerrar o servidor (os dados em memoria sao perdidos).");
        System.out.println("--------------------------------------------------------------");
    }

    /** Unico comando aceito no terminal do servidor: "sair". */
    private static void iniciarConsole(BufferedReader teclado) {
        Thread console = new Thread(() -> {
            try {
                String comando;
                while ((comando = teclado.readLine()) != null) {
                    comando = comando.trim().toLowerCase();
                    if (comando.isEmpty()) continue;
                    if (comando.equals("sair")) {
                        Log.print(TAG, "Servidor encerrado pelo operador.");
                        System.exit(0);
                    }
                    System.out.println("O unico comando do servidor e 'sair'. Consultas sao feitas pelo cliente (admin).");
                }
            } catch (IOException ignorada) {
                // terminal fechado
            }
        }, "console");
        console.setDaemon(true);
        console.start();
    }
}
