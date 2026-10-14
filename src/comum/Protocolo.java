package comum;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Regras de transporte e formato do protocolo v2.4 (compartilhadas por cliente e servidor).
 *
 *  - TCP, UTF-8
 *  - 1 mensagem = 1 objeto JSON em linha unica, terminado por '\n'
 *  - no maximo 8192 bytes por mensagem, ja contando o '\n'
 */
public final class Protocolo {

    private Protocolo() { }

    public static final String VERSAO = "2.4";
    public static final int TAMANHO_MAXIMO = 8192;

    // Regex do Dicionario de Campos
    public static final Pattern USER       = Pattern.compile("^[a-z]{1,30}$");
    public static final Pattern PASSWORD   = Pattern.compile("^[A-Za-z0-9]{1,20}$");
    /** v2.1: aceita .com.br, .edu.br, alunos.utfpr.edu.br, hifen no dominio e "_" ou "-" antes do @. */
    public static final Pattern EMAIL      = Pattern.compile("^[a-z0-9._-]+@[a-z0-9-]+(\\.[a-z0-9-]+)*\\.[a-z]{2,}$");
    public static final Pattern TOKEN      = Pattern.compile("^[a-f0-9]{64}$");
    public static final Pattern ROLE       = Pattern.compile("^(user|admin)$");
    public static final Pattern STATUS     = Pattern.compile("^[0-9]{3}$");
    public static final Pattern CREATED_AT = Pattern.compile(
            "^[0-9]{4}-[0-9]{2}-[0-9]{2} ([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]$");

    /** true se o texto nao for nulo e casar inteiro com a regex. */
    public static boolean casa(Pattern regex, String texto) {
        return texto != null && regex.matcher(texto).matches();
    }

    /** Uma linha lida do socket. */
    public static final class Linha {
        public final String texto;
        public final boolean excedeuTamanho;
        public final int bytes; // tamanho total recebido, contando o '\n'

        Linha(String texto, boolean excedeuTamanho, int bytes) {
            this.texto = texto;
            this.excedeuTamanho = excedeuTamanho;
            this.bytes = bytes;
        }
    }

    /**
     * Le bytes ate encontrar '\n'. Retorna null se a conexao foi encerrada
     * (linha incompleta, sem '\n', e descartada).
     * Se a mensagem passar de 8192 bytes, o resto da linha e descartado e
     * a Linha volta marcada com excedeuTamanho = true.
     * Um '\r' no final (cliente Windows usando println) e removido.
     */
    public static Linha lerLinha(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int bytesConteudo = 0;
        boolean excedeu = false;
        while (true) {
            int b = in.read();
            if (b == -1) {
                return null;
            }
            if (b == '\n') {
                break;
            }
            bytesConteudo++;
            if (bytesConteudo > TAMANHO_MAXIMO - 1) { // -1 por causa do '\n'
                excedeu = true;
            }
            if (!excedeu) {
                buffer.write(b);
            }
        }
        byte[] dados = buffer.toByteArray();
        int tamanho = dados.length;
        if (tamanho > 0 && dados[tamanho - 1] == '\r') {
            tamanho--;
        }
        String texto = new String(dados, 0, tamanho, StandardCharsets.UTF_8);
        return new Linha(texto, excedeu, bytesConteudo + 1);
    }

    /** Envia a mensagem em UTF-8 terminada por '\n' (nunca "\r\n"). */
    public static void enviar(OutputStream out, String json) throws IOException {
        out.write((json + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }
}
