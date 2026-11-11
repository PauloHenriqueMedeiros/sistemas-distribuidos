package cliente;

import comum.Json;
import comum.Protocolo;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Comunicacao do cliente com o servidor, usada pela interface grafica.
 * Mesmas regras do cliente de terminal: JSON em linha unica + '\n', reconexao e reenvio (regra 1.11)
 * e conferencia de cada resposta contra o protocolo. Tudo o que acontece e avisado ao Ouvinte.
 */
public final class Rede {

    /** Tipos de evento: CONEXAO, ENVIADO, RECEBIDO, INFO, AVISO, ERRO. */
    public interface Ouvinte {
        void evento(String tipo, String texto);
    }

    private static final int TIMEOUT_CONEXAO_MS = 5_000;
    private static final int TIMEOUT_RESPOSTA_MS = 15_000;
    private static final long RECONECTAR_SE_PARADO_MS = 280_000; // o servidor fecha em 300 s

    private final Ouvinte ouvinte;
    private String ip;
    private int porta;
    private Socket socket;
    private InputStream entrada;
    private OutputStream saida;
    private long ultimaAtividade;

    public Rede(Ouvinte ouvinte) {
        this.ouvinte = ouvinte;
    }

    public synchronized String ip() { return ip; }
    public synchronized int porta() { return porta; }

    public synchronized boolean conectado() {
        return socket != null && !socket.isClosed();
    }

    /** Conecta; devolve null se deu certo, ou a mensagem de erro. */
    public synchronized String conectar(String novoIp, int novaPorta) {
        this.ip = novoIp;
        this.porta = novaPorta;
        return abrir();
    }

    private String abrir() {
        fechar();
        try {
            Socket s = new Socket();
            s.connect(new InetSocketAddress(ip, porta), TIMEOUT_CONEXAO_MS);
            s.setSoTimeout(TIMEOUT_RESPOSTA_MS);
            socket = s;
            entrada = new BufferedInputStream(s.getInputStream());
            saida = new BufferedOutputStream(s.getOutputStream());
            ultimaAtividade = System.currentTimeMillis();
            ouvinte.evento("CONEXAO", "Conectado ao servidor " + ip + ":" + porta);
            return null;
        } catch (UnknownHostException e) {
            return erro("Endereco desconhecido: " + ip);
        } catch (SocketTimeoutException e) {
            return erro("Tempo esgotado ao conectar em " + ip + ":" + porta + " (IP errado ou firewall bloqueando?)");
        } catch (ConnectException e) {
            return erro("Conexao recusada em " + ip + ":" + porta + " (o servidor esta rodando nessa porta?)");
        } catch (IOException | IllegalArgumentException e) {
            return erro("Nao foi possivel conectar: " + e.getMessage());
        }
    }

    private String erro(String texto) {
        ouvinte.evento("ERRO", texto);
        return texto;
    }

    public synchronized void fechar() {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignorada) {
                // nada a fazer
            }
            ouvinte.evento("CONEXAO", "Conexao com " + ip + ":" + porta + " encerrada.");
        }
        socket = null;
        entrada = null;
        saida = null;
    }

    /**
     * Envia a requisicao e devolve a resposta (ou null se nao houve resposta valida).
     * Regra 1.11: se a conexao caiu ou foi fechada pelo servidor, reconecta e reenvia uma vez.
     */
    @SuppressWarnings("unchecked")
    public synchronized Map<String, Object> enviar(Map<String, Object> req) {
        String opEnviada = (String) req.get("op");
        String linha = Json.escrever(req);

        if (conectado() && System.currentTimeMillis() - ultimaAtividade > RECONECTAR_SE_PARADO_MS) {
            ouvinte.evento("INFO", "Conexao parada ha mais de 280 s (o servidor fecha em 300 s). Reconectando...");
            fechar();
        }
        String textoResposta = null;
        for (int tentativa = 1; tentativa <= 2 && textoResposta == null; tentativa++) {
            if (!conectado()) {
                ouvinte.evento("INFO", "Sem conexao. Tentando conectar em " + ip + ":" + porta + "...");
                if (abrir() != null) {
                    ouvinte.evento("ERRO", "Nao foi possivel enviar: sem conexao com o servidor.");
                    return null;
                }
            }
            try {
                Protocolo.enviar(saida, linha);
                ouvinte.evento("ENVIADO", linha + (tentativa > 1 ? "   (reenvio)" : ""));
                ultimaAtividade = System.currentTimeMillis();
                Protocolo.Linha resp = Protocolo.lerResposta(entrada); // resposta sem limite (regra 1.6)
                ultimaAtividade = System.currentTimeMillis();
                if (resp == null) {
                    fechar();
                    if (tentativa == 1) {
                        ouvinte.evento("INFO", "O servidor fechou a conexao sem responder (talvez por inatividade). "
                                + "Reconectando e reenviando a mesma requisicao (regra 1.11)...");
                        continue;
                    }
                    ouvinte.evento("ERRO", "O servidor encerrou a conexao sem responder, de novo.");
                    return null;
                }
                textoResposta = resp.texto;
            } catch (SocketTimeoutException e) {
                ouvinte.evento("ERRO", "O servidor nao respondeu em " + (TIMEOUT_RESPOSTA_MS / 1000) + " s.");
                fechar();
                return null;
            } catch (IOException e) {
                fechar();
                if (tentativa == 1) {
                    ouvinte.evento("INFO", "A conexao caiu (" + e.getMessage() + "). Reconectando e reenviando (regra 1.11)...");
                    continue;
                }
                ouvinte.evento("ERRO", "Falha de comunicacao com o servidor: " + e.getMessage());
                return null;
            }
        }

        ouvinte.evento("RECEBIDO", textoResposta);
        Object lido;
        try {
            lido = Json.ler(textoResposta);
        } catch (Json.ErroJson e) {
            ouvinte.evento("ERRO", "A resposta do servidor NAO e um JSON valido (" + e.getMessage() + ").");
            return null;
        }
        if (!(lido instanceof Map)) {
            ouvinte.evento("ERRO", "A resposta do servidor nao e um objeto JSON { ... }.");
            return null;
        }
        Map<String, Object> resposta = (Map<String, Object>) lido;
        for (String aviso : validarResposta(resposta, opEnviada)) {
            ouvinte.evento("AVISO", "Resposta fora do protocolo: " + aviso);
        }
        return resposta;
    }

    // ------------------------------------------------------------------ conferencia da resposta

    static String texto(Map<?, ?> m, String chave) {
        Object v = m.get(chave);
        return v instanceof String ? (String) v : null;
    }

    private static List<String> validarResposta(Map<String, Object> r, String opEnviada) {
        List<String> avisos = new ArrayList<>();
        String esperado = opEnviada == null ? "error" : opEnviada + "_response";
        String op = texto(r, "op");
        if (op == null) {
            avisos.add("falta a chave 'op' (string)");
        } else if (!op.equals(esperado) && !op.equals("error")) {
            avisos.add("'op' deveria ser '" + esperado + "', mas veio '" + op + "'");
        }
        String status = texto(r, "status");
        if (!Protocolo.casa(Protocolo.STATUS, status)) {
            avisos.add("'status' ausente ou fora do formato (string com 3 digitos, ex: \"200\")");
        }
        if (texto(r, "message") == null) avisos.add("'message' ausente ou nao e string");
        for (Map.Entry<String, Object> e : r.entrySet()) {
            Object v = e.getValue();
            if (v == null) {
                avisos.add("campo '" + e.getKey() + "' veio null");
            } else if (!(v instanceof String) && !(v instanceof List)) {
                avisos.add("campo '" + e.getKey() + "' deveria ser string (regra 2.3)");
            }
        }
        if (r.containsKey("password")) avisos.add("a resposta contem 'password' (a senha nunca deve ser devolvida)");

        boolean cria = "register".equals(opEnviada) || "create_room".equals(opEnviada)
                || "create_reservation".equals(opEnviada);
        if (cria && "200".equals(status)) avisos.add("'" + opEnviada + "' com sucesso deveria responder status 201");
        if (opEnviada != null && !cria && "201".equals(status)) {
            avisos.add("'" + opEnviada + "' com sucesso deveria responder status 200");
        }
        if ("201".equals(status) && "create_room".equals(opEnviada) && !Protocolo.casa(Protocolo.ID, texto(r, "room_id"))) {
            avisos.add("falta 'room_id' (string numerica)");
        }
        if ("201".equals(status) && "create_reservation".equals(opEnviada)
                && !Protocolo.casa(Protocolo.ID, texto(r, "reservation_id"))) {
            avisos.add("falta 'reservation_id' (string numerica)");
        }
        if (!"200".equals(status)) return avisos;

        switch (opEnviada == null ? "" : opEnviada) {
            case "login":
                if (!Protocolo.casa(Protocolo.TOKEN, texto(r, "token"))) avisos.add("'token' ausente ou fora do formato ^[a-f0-9]{64}$");
                if (!Protocolo.casa(Protocolo.ROLE, texto(r, "role"))) avisos.add("'role' ausente ou diferente de \"user\"/\"admin\"");
                break;
            case "read_user":
            case "admin_read_user":
                if (texto(r, "user") == null) avisos.add("falta 'user'");
                if (texto(r, "email") == null) avisos.add("falta 'email'");
                if (!Protocolo.casa(Protocolo.ROLE, texto(r, "role"))) avisos.add("'role' ausente ou invalido");
                if (!Protocolo.casa(Protocolo.CREATED_AT, texto(r, "created_at"))) {
                    avisos.add("'created_at' ausente ou fora do formato AAAA-MM-DD HH:MM:SS");
                }
                break;
            case "admin_list_users":
                conferirLista(r, "users", new String[] {"user", "email", "role"}, avisos);
                break;
            case "list_rooms":
                conferirLista(r, "rooms", new String[] {"room_id", "name", "capacity", "location", "room_status"}, avisos);
                if (r.get("rooms") instanceof List) {
                    for (Object item : (List<?>) r.get("rooms")) {
                        if (item instanceof Map && !(((Map<?, ?>) item).get("slots") instanceof List)) {
                            avisos.add("item de 'rooms' sem o array 'slots' (v2.8)");
                            break;
                        }
                    }
                }
                break;
            case "list_reservations":
                conferirLista(r, "reservations", new String[] {"reservation_id", "room_id", "name", "user", "date",
                    "start_time", "end_time", "topic", "participants"}, avisos);
                break;
            case "read_room":
                for (String campo : new String[] {"room_id", "name", "capacity", "location", "room_status"}) {
                    if (texto(r, campo) == null) avisos.add("falta '" + campo + "'");
                }
                if (!(r.get("resources") instanceof List)) avisos.add("falta o array 'resources'");
                if (!(r.get("slots") instanceof List)) avisos.add("falta o array 'slots' (v2.8)");
                if (r.containsKey("agenda") && !(r.get("agenda") instanceof List)) avisos.add("'agenda' deveria ser array");
                break;
            default:
                break;
        }
        return avisos;
    }

    private static void conferirLista(Map<String, Object> r, String chave, String[] campos, List<String> avisos) {
        Object lista = r.get(chave);
        if (!(lista instanceof List)) {
            avisos.add("falta o array '" + chave + "'");
            return;
        }
        List<?> itens = (List<?>) lista;
        if (!String.valueOf(itens.size()).equals(texto(r, "count"))) {
            avisos.add("'count' (" + texto(r, "count") + ") diferente da quantidade de itens em '" + chave + "' (" + itens.size() + ")");
        }
        for (Object item : itens) {
            if (!(item instanceof Map)) {
                avisos.add("item de '" + chave + "' nao e objeto");
                return;
            }
            Map<?, ?> m = (Map<?, ?>) item;
            if (m.containsKey("password")) avisos.add("um item de '" + chave + "' contem 'password'");
            for (String campo : campos) {
                if (!(m.get(campo) instanceof String)) {
                    avisos.add("item de '" + chave + "' sem '" + campo + "' (string)");
                    return;
                }
            }
        }
    }
}
