package servidor;

import comum.Json;
import comum.Log;
import comum.Protocolo;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;

/**
 * Uma thread por cliente conectado (regra 1.1).
 * Le uma linha, processa, responde exatamente uma vez (regra 1.7) e repete.
 */
public final class ConexaoCliente implements Runnable {

    /** Regra 1.9: 300 s sem mensagens -> fecha a conexao (o token NAO e invalidado). */
    private static final int TIMEOUT_INATIVIDADE_MS = 300_000;

    private final Socket socket;
    private final BancoDados banco;
    private final String id;

    public ConexaoCliente(Socket socket, BancoDados banco, int numero) {
        this.socket = socket;
        this.banco = banco;
        this.id = "[#" + numero + " " + socket.getInetAddress().getHostAddress() + ":" + socket.getPort() + "]";
    }

    @Override
    public void run() {
        Log.print(id, "CONECTADO");
        Operacoes operacoes = new Operacoes(banco, id);
        try (Socket s = socket) {
            s.setSoTimeout(TIMEOUT_INATIVIDADE_MS);
            InputStream entrada = new BufferedInputStream(s.getInputStream());
            OutputStream saida = new BufferedOutputStream(s.getOutputStream());

            while (true) {
                Protocolo.Linha linha = Protocolo.lerLinha(entrada);
                if (linha == null) {
                    Log.print(id, "DESCONECTADO (o cliente encerrou a conexao)");
                    return;
                }

                String resposta;
                if (linha.excedeuTamanho) {
                    Log.print(id, "RECEBIDO <- [mensagem de " + linha.bytes + " bytes, acima do limite de "
                            + Protocolo.TAMANHO_MAXIMO + "]");
                    resposta = Json.escrever(Operacoes.erroProtocolo("Mensagem excede o tamanho maximo"));
                } else if (linha.texto.trim().isEmpty()) {
                    Log.print(id, "linha vazia recebida e ignorada");
                    continue;
                } else {
                    Log.print(id, "RECEBIDO <- " + linha.texto);
                    resposta = Json.escrever(operacoes.processar(linha.texto));
                }

                Protocolo.enviar(saida, resposta);
                Log.print(id, "ENVIADO  -> " + resposta);
            }
        } catch (SocketTimeoutException e) {
            Log.print(id, "DESCONECTADO por inatividade (300 s). O token do usuario continua valido.");
        } catch (IOException e) {
            Log.print(id, "DESCONECTADO (erro de comunicacao: " + e.getMessage() + ")");
        }
    }
}
